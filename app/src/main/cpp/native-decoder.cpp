#include "native-decoder.h"

#include <android/log.h>
#include <android/native_window_jni.h>

#include <chrono>
#include <cstring>
#include <pthread.h>
#include <sys/resource.h>
#include <unistd.h>

#define TAG "GoatsDrive_Decoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace goatsdrive {

NativeVideoDecoder& NativeVideoDecoder::getInstance() {
    static NativeVideoDecoder instance;
    return instance;
}

NativeVideoDecoder::NativeVideoDecoder()
    : m_window(nullptr),
      m_codec(nullptr),
      m_width(1280),
      m_height(720),
      m_running(false),
      m_decodedFrames(0),
      m_droppedFrames(0) {}

NativeVideoDecoder::~NativeVideoDecoder() {
    stop();
}

bool NativeVideoDecoder::init(ANativeWindow* window, int width, int height) {
    stop();

    if (!window) {
        LOGE("Cannot initialize decoder with null ANativeWindow");
        return false;
    }

    m_window = window;
    ANativeWindow_acquire(m_window);
    m_width = width;
    m_height = height;

    ANativeWindow_setBuffersGeometry(m_window, width, height, WINDOW_FORMAT_RGBA_8888);

    m_codec = AMediaCodec_createDecoderByType("video/avc");
    if (!m_codec) {
        LOGE("Failed to create AMediaCodec for video/avc");
        return false;
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);

    AMediaFormat_setInt32(format, "low-latency", 1);
    AMediaFormat_setInt32(format, "vendor.low-latency.enable", 1);
    AMediaFormat_setInt32(format, "priority", 0);
    AMediaFormat_setInt32(format, "operating-rate", 60);

    media_status_t status = AMediaCodec_configure(m_codec, format, m_window, nullptr, 0);
    AMediaFormat_delete(format);

    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        AMediaCodec_delete(m_codec);
        m_codec = nullptr;
        return false;
    }

    status = AMediaCodec_start(m_codec);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        AMediaCodec_delete(m_codec);
        m_codec = nullptr;
        return false;
    }

    LOGI("Zero-copy AMediaCodec initialized successfully [%dx%d]", width, height);

    m_running.store(true, std::memory_order_release);
    m_feedThread = std::thread(&NativeVideoDecoder::feedLoop, this);
    m_drainThread = std::thread(&NativeVideoDecoder::drainLoop, this);

    return true;
}

void NativeVideoDecoder::stop() {
    m_running.store(false, std::memory_order_release);

    if (m_feedThread.joinable()) {
        m_feedThread.join();
    }
    if (m_drainThread.joinable()) {
        m_drainThread.join();
    }

    if (m_codec) {
        AMediaCodec_stop(m_codec);
        AMediaCodec_delete(m_codec);
        m_codec = nullptr;
    }

    if (m_window) {
        ANativeWindow_release(m_window);
        m_window = nullptr;
    }

    m_videoBuffer.reset();
    LOGI("NativeVideoDecoder stopped and cleaned up");
}

VideoRingBuffer& NativeVideoDecoder::getRingBuffer() {
    return m_videoBuffer;
}

uint64_t NativeVideoDecoder::getDecodedFrames() const {
    return m_decodedFrames.load(std::memory_order_relaxed);
}

uint64_t NativeVideoDecoder::getDroppedFrames() const {
    return m_droppedFrames.load(std::memory_order_relaxed);
}

int NativeVideoDecoder::findStartCode(const uint8_t* p, size_t len, size_t& scLen) {
    if (len < 3) return -1;
    for (size_t i = 0; i <= len - 3; ++i) {
        if (p[i] == 0 && p[i + 1] == 0) {
            if (p[i + 2] == 1) {
                scLen = 3;
                return static_cast<int>(i);
            } else if (i <= len - 4 && p[i + 2] == 0 && p[i + 3] == 1) {
                scLen = 4;
                return static_cast<int>(i);
            }
        }
    }
    return -1;
}

void NativeVideoDecoder::feedLoop() {
    pthread_setname_np(pthread_self(), "GD_DecFeed");
    setpriority(PRIO_PROCESS, 0, -18);

    alignas(16) static uint8_t s_nalBuf[1024 * 1024];
    size_t nalBytesInBuf = 0;

    while (m_running.load(std::memory_order_relaxed)) {
        size_t available = m_videoBuffer.availableRead();
        if (available > 0) {
            size_t space = sizeof(s_nalBuf) - nalBytesInBuf;
            size_t toRead = std::min(available, space);
            size_t readCount = m_videoBuffer.read(s_nalBuf + nalBytesInBuf, toRead);
            nalBytesInBuf += readCount;
        }

        if (nalBytesInBuf < 4) {
            usleep(500);
            continue;
        }

        size_t firstScLen = 0;
        int firstScPos = findStartCode(s_nalBuf, nalBytesInBuf, firstScLen);
        if (firstScPos < 0) {
            if (nalBytesInBuf > 3) {
                std::memmove(s_nalBuf, s_nalBuf + nalBytesInBuf - 3, 3);
                nalBytesInBuf = 3;
            }
            continue;
        }

        size_t secondScLen = 0;
        size_t offset = firstScPos + firstScLen;
        int secondScPos = -1;
        if (offset < nalBytesInBuf) {
            secondScPos = findStartCode(s_nalBuf + offset, nalBytesInBuf - offset, secondScLen);
        }

        if (secondScPos < 0) {
            if (nalBytesInBuf >= sizeof(s_nalBuf) - 4096) {
                secondScPos = static_cast<int>(nalBytesInBuf - offset);
                secondScLen = 0;
            } else {
                usleep(250);
                continue;
            }
        }

        const size_t nalLength = static_cast<size_t>(secondScPos) + (firstScPos == 0 ? 0 : firstScPos);
        const uint8_t* nalData = s_nalBuf + firstScPos;

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(m_codec, 2000);
        if (inIdx >= 0) {
            size_t inBufSize = 0;
            uint8_t* codecInBuf = AMediaCodec_getInputBuffer(m_codec, inIdx, &inBufSize);
            if (codecInBuf && inBufSize >= nalLength) {
                std::memcpy(codecInBuf, nalData, nalLength);

                int64_t ptsUs = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();

                AMediaCodec_queueInputBuffer(m_codec, inIdx, 0, nalLength, ptsUs, 0);
            } else {
                AMediaCodec_queueInputBuffer(m_codec, inIdx, 0, 0, 0, 0);
            }

            size_t totalConsumed = offset + secondScPos;
            if (totalConsumed < nalBytesInBuf) {
                std::memmove(s_nalBuf, s_nalBuf + totalConsumed, nalBytesInBuf - totalConsumed);
                nalBytesInBuf -= totalConsumed;
            } else {
                nalBytesInBuf = 0;
            }
        } else {
            usleep(500);
        }
    }
}

void NativeVideoDecoder::drainLoop() {
    pthread_setname_np(pthread_self(), "GD_DecDrain");
    setpriority(PRIO_PROCESS, 0, -19);

    AMediaCodecBufferInfo info;

    while (m_running.load(std::memory_order_relaxed)) {
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(m_codec, &info, 1000);

        if (outIdx >= 0) {
            media_status_t status = AMediaCodec_releaseOutputBuffer(m_codec, outIdx, true);
            if (status == AMEDIA_OK) {
                m_decodedFrames.fetch_add(1, std::memory_order_relaxed);
            } else {
                m_droppedFrames.fetch_add(1, std::memory_order_relaxed);
            }
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* newFormat = AMediaCodec_getOutputFormat(m_codec);
            int32_t w = 0, h = 0;
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_WIDTH, &w);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_HEIGHT, &h);
            LOGI("Codec format updated to [%dx%d]", w, h);
            AMediaFormat_delete(newFormat);
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            // Buffer changed
        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            // Wait for next frame
        }
    }
}

} // namespace goatsdrive
