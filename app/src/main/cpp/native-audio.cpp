#include "native-audio.h"

#include <android/api-level.h>
#include <android/log.h>

#include <cstring>

#define TAG "GoatsDrive_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace goatsdrive {

NativeAudioSink& NativeAudioSink::getInstance() {
    static NativeAudioSink instance;
    return instance;
}

NativeAudioSink::NativeAudioSink()
    : m_sampleRate(48000),
      m_channels(2),
      m_running(false),
      m_ducked(false),
#if HAVE_AAUDIO
      m_aaudioStream(nullptr),
#endif
      m_slEngineObject(nullptr),
      m_slEngine(nullptr),
      m_slOutputMixObject(nullptr),
      m_slPlayerObject(nullptr),
      m_slPlayerPlay(nullptr),
      m_slBufferQueue(nullptr),
      m_currentPcmIndex(0) {}

NativeAudioSink::~NativeAudioSink() {
    stop();
}

bool NativeAudioSink::init(int sampleRate, int channelCount) {
    stop();

    m_sampleRate = sampleRate;
    m_channels = channelCount;
    m_ducked.store(false, std::memory_order_relaxed);

    int apiLevel = android_get_device_api_level();
    LOGI("Initializing Audio Sink (API Level: %d, Rate: %d Hz, Channels: %d)",
         apiLevel, sampleRate, channelCount);

#if HAVE_AAUDIO
    if (apiLevel >= 26) {
        if (initAAudio()) {
            LOGI("AAudio low-latency sink active");
            return true;
        }
        LOGW("AAudio init failed. Falling back to OpenSL ES...");
    }
#endif

    if (initOpenSLES()) {
        LOGI("OpenSL ES low-latency sink active");
        return true;
    }

    LOGE("Failed to initialize any native audio engine");
    return false;
}

void NativeAudioSink::stop() {
    m_running.store(false, std::memory_order_release);

#if HAVE_AAUDIO
    stopAAudio();
#endif
    stopOpenSLES();

    m_audioBuffer.reset();
    LOGI("Native Audio Sink stopped");
}

void NativeAudioSink::setDucked(bool ducked) {
    m_ducked.store(ducked, std::memory_order_release);
    LOGI("Audio ducking state changed: %s", ducked ? "DUCKED (20% vol)" : "FULL_VOLUME");
}

AudioRingBuffer& NativeAudioSink::getRingBuffer() {
    return m_audioBuffer;
}

#if HAVE_AAUDIO
bool NativeAudioSink::initAAudio() {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("AAudio_createStreamBuilder failed: %s", AAudio_convertResultToText(result));
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, m_sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, m_channels);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(builder, aaudioDataCallbackStatic, this);

    result = AAudioStreamBuilder_openStream(builder, &m_aaudioStream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGW("AAudioStreamBuilder_openStream failed: %s", AAudio_convertResultToText(result));
        m_aaudioStream = nullptr;
        return false;
    }

    result = AAudioStream_requestStart(m_aaudioStream);
    if (result != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed: %s", AAudio_convertResultToText(result));
        AAudioStream_close(m_aaudioStream);
        m_aaudioStream = nullptr;
        return false;
    }

    m_running.store(true, std::memory_order_release);
    return true;
}

void NativeAudioSink::stopAAudio() {
    if (m_aaudioStream) {
        AAudioStream_requestStop(m_aaudioStream);
        AAudioStream_close(m_aaudioStream);
        m_aaudioStream = nullptr;
    }
}

aaudio_data_callback_result_t NativeAudioSink::aaudioDataCallbackStatic(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames
) {
    auto* self = static_cast<NativeAudioSink*>(userData);
    return self->onAAudioData(static_cast<int16_t*>(audioData), numFrames);
}

aaudio_data_callback_result_t NativeAudioSink::onAAudioData(int16_t* output, int32_t numFrames) {
    const size_t bytesNeeded = static_cast<size_t>(numFrames * m_channels * sizeof(int16_t));
    uint8_t* rawOut = reinterpret_cast<uint8_t*>(output);

    size_t bytesRead = m_audioBuffer.read(rawOut, bytesNeeded);

    if (bytesRead < bytesNeeded) {
        std::memset(rawOut + bytesRead, 0, bytesNeeded - bytesRead);
    }

    if (m_ducked.load(std::memory_order_relaxed)) {
        const int totalSamples = numFrames * m_channels;
        for (int i = 0; i < totalSamples; ++i) {
            output[i] = static_cast<int16_t>(output[i] >> 2);
        }
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
#endif

bool NativeAudioSink::initOpenSLES() {
    SLresult result = slCreateEngine(&m_slEngineObject, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slEngineObject)->Realize(m_slEngineObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slEngineObject)->GetInterface(m_slEngineObject, SL_IID_ENGINE, &m_slEngine);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slEngine)->CreateOutputMix(m_slEngine, &m_slOutputMixObject, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slOutputMixObject)->Realize(m_slOutputMixObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;

    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2
    };

    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        static_cast<SLuint32>(m_channels),
        static_cast<SLuint32>(m_sampleRate * 1000),
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        (m_channels == 2) ? (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT) : SL_SPEAKER_FRONT_CENTER,
        SL_BYTEORDER_LITTLEENDIAN
    };

    SLDataSource audioSrc = {&loc_bufq, &format_pcm};
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, m_slOutputMixObject};
    SLDataSink audioSnk = {&loc_outmix, nullptr};

    const SLInterfaceID ids[1] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean req[1] = {SL_BOOLEAN_TRUE};

    result = (*m_slEngine)->CreateAudioPlayer(m_slEngine, &m_slPlayerObject, &audioSrc, &audioSnk, 1, ids, req);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slPlayerObject)->Realize(m_slPlayerObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slPlayerObject)->GetInterface(m_slPlayerObject, SL_IID_PLAY, &m_slPlayerPlay);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slPlayerObject)->GetInterface(m_slPlayerObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &m_slBufferQueue);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slBufferQueue)->RegisterCallback(m_slBufferQueue, openSlCallbackStatic, this);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*m_slPlayerPlay)->SetPlayState(m_slPlayerPlay, SL_PLAYSTATE_PLAYING);
    if (result != SL_RESULT_SUCCESS) return false;

    m_running.store(true, std::memory_order_release);

    std::memset(m_openSlBuffers[0], 0, OPENSL_BUF_SIZE);
    std::memset(m_openSlBuffers[1], 0, OPENSL_BUF_SIZE);
    (*m_slBufferQueue)->Enqueue(m_slBufferQueue, m_openSlBuffers[0], OPENSL_BUF_SIZE);
    (*m_slBufferQueue)->Enqueue(m_slBufferQueue, m_openSlBuffers[1], OPENSL_BUF_SIZE);

    return true;
}

void NativeAudioSink::stopOpenSLES() {
    if (m_slPlayerPlay) {
        (*m_slPlayerPlay)->SetPlayState(m_slPlayerPlay, SL_PLAYSTATE_STOPPED);
    }
    if (m_slPlayerObject) {
        (*m_slPlayerObject)->Destroy(m_slPlayerObject);
        m_slPlayerObject = nullptr;
        m_slPlayerPlay = nullptr;
        m_slBufferQueue = nullptr;
    }
    if (m_slOutputMixObject) {
        (*m_slOutputMixObject)->Destroy(m_slOutputMixObject);
        m_slOutputMixObject = nullptr;
    }
    if (m_slEngineObject) {
        (*m_slEngineObject)->Destroy(m_slEngineObject);
        m_slEngineObject = nullptr;
        m_slEngine = nullptr;
    }
}

void NativeAudioSink::openSlCallbackStatic(SLAndroidSimpleBufferQueueItf bq, void* context) {
    auto* self = static_cast<NativeAudioSink*>(context);
    self->onOpenSlBuffer(bq);
}

void NativeAudioSink::onOpenSlBuffer(SLAndroidSimpleBufferQueueItf bq) {
    if (!m_running.load(std::memory_order_relaxed)) return;

    uint8_t* targetBuf = m_openSlBuffers[m_currentPcmIndex];
    m_currentPcmIndex = (m_currentPcmIndex + 1) % 2;

    size_t bytesRead = m_audioBuffer.read(targetBuf, OPENSL_BUF_SIZE);
    if (bytesRead < OPENSL_BUF_SIZE) {
        std::memset(targetBuf + bytesRead, 0, OPENSL_BUF_SIZE - bytesRead);
    }

    if (m_ducked.load(std::memory_order_relaxed)) {
        auto* samples = reinterpret_cast<int16_t*>(targetBuf);
        const size_t totalSamples = OPENSL_BUF_SIZE / sizeof(int16_t);
        for (size_t i = 0; i < totalSamples; ++i) {
            samples[i] = static_cast<int16_t>(samples[i] >> 2);
        }
    }

    (*bq)->Enqueue(bq, targetBuf, OPENSL_BUF_SIZE);
}

} // namespace goatsdrive
