#include "ring_buffer.h"

#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <thread>
#include <unistd.h>

#define TAG "GoatsDrive_IO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace goatsdrive {

// Fixed 8-byte framing header for multiplexed automotive stream
#pragma pack(push, 1)
struct ProjectionHeader {
    uint8_t magic[2];      // 0x47, 0x44 ('G', 'D')
    uint8_t channel;       // 0x01: Video, 0x02: Audio, 0x03: Control, 0x04: Input
    uint8_t flags;         // 0x01: Keyframe, 0x02: Sync, 0x00: Standard
    uint32_t payloadLength;// Big-endian payload size
};
#pragma pack(pop)

static constexpr uint8_t CHANNEL_VIDEO   = 0x01;
static constexpr uint8_t CHANNEL_AUDIO   = 0x02;
static constexpr uint8_t CHANNEL_CONTROL = 0x03;
static constexpr uint8_t CHANNEL_INPUT   = 0x04;

class TransportIO {
public:
    static TransportIO& getInstance() {
        static TransportIO instance;
        return instance;
    }

    void setBuffers(VideoRingBuffer* vBuffer, AudioRingBuffer* aBuffer) {
        m_videoBuffer = vBuffer;
        m_audioBuffer = aBuffer;
    }

    bool startSocket(int socketFd) {
        stop();
        m_activeFd = socketFd;
        m_isUsb = false;
        configureSocket(socketFd);
        return startThreads();
    }

    bool startUsb(int usbFd) {
        stop();
        m_activeFd = usbFd;
        m_isUsb = true;
        // Non-blocking mode with direct IO
        int flags = fcntl(usbFd, F_GETFL, 0);
        fcntl(usbFd, F_SETFL, flags | O_NONBLOCK);
        return startThreads();
    }

    void stop() {
        m_running.store(false, std::memory_order_release);
        if (m_activeFd >= 0) {
            shutdown(m_activeFd, SHUT_RDWR);
            close(m_activeFd);
            m_activeFd = -1;
        }

        if (m_readerThread.joinable()) {
            m_readerThread.join();
        }
        if (m_writerThread.joinable()) {
            m_writerThread.join();
        }
    }

    bool sendInputPacket(const uint8_t* data, size_t length) {
        if (!m_running.load(std::memory_order_acquire) || m_activeFd < 0 || length == 0) {
            return false;
        }
        return m_inputQueue.write(data, length) == length;
    }

    [[nodiscard]] bool isRunning() const {
        return m_running.load(std::memory_order_relaxed);
    }

private:
    TransportIO()
        : m_activeFd(-1),
          m_isUsb(false),
          m_running(false),
          m_videoBuffer(nullptr),
          m_audioBuffer(nullptr) {}

    ~TransportIO() {
        stop();
    }

    void configureSocket(int fd) {
        int flag = 1;
        // Disable Nagle's algorithm for sub-1ms control/touch response
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

        // Buffer optimization for 1080p60 H.264 stream
        int rcvBufSize = 2 * 1024 * 1024;
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvBufSize, sizeof(rcvBufSize));

        int sndBufSize = 128 * 1024;
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndBufSize, sizeof(sndBufSize));

        // Expedited Forwarding / Low Latency DSCP
        int tos = 0xB8; // DSCP EF
        setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));
    }

    bool startThreads() {
        m_running.store(true, std::memory_order_release);
        m_readerThread = std::thread(&TransportIO::readerLoop, this);
        m_writerThread = std::thread(&TransportIO::writerLoop, this);
        return true;
    }

    void readerLoop() {
        pthread_setname_np(pthread_self(), "GD_TransportRx");
        setpriority(PRIO_PROCESS, 0, -16); // High priority near realtime

        LOGI("Transport reader thread started (FD: %d, isUsb: %d)", m_activeFd, m_isUsb);

        // Pre-allocated stack buffer: ZERO HEAP ALLOCATIONS
        alignas(16) uint8_t rxBuffer[65536];
        ProjectionHeader header;
        uint8_t* headerPtr = reinterpret_cast<uint8_t*>(&header);
        size_t headerBytesRead = 0;

        uint32_t currentPayloadRemaining = 0;
        uint8_t currentChannel = 0;

        while (m_running.load(std::memory_order_relaxed)) {
            // Step 1: Read exactly the 8-byte framing header
            while (headerBytesRead < sizeof(ProjectionHeader)) {
                ssize_t bytes = read(m_activeFd, headerPtr + headerBytesRead, sizeof(ProjectionHeader) - headerBytesRead);
                if (bytes <= 0) {
                    if (bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                        usleep(500); // 0.5ms yield
                        if (!m_running.load(std::memory_order_relaxed)) return;
                        continue;
                    }
                    LOGE("Reader socket/USB connection closed or error: %d", errno);
                    m_running.store(false, std::memory_order_release);
                    return;
                }
                headerBytesRead += static_cast<size_t>(bytes);
            }

            // Verify Magic bytes 'G', 'D'
            if (header.magic[0] != 0x47 || header.magic[1] != 0x44) {
                LOGE("Transport framing out of sync: [0x%02X, 0x%02X]. Resyncing...",
                     header.magic[0], header.magic[1]);
                // Shift 1 byte and scan for next sync word
                std::memmove(headerPtr, headerPtr + 1, sizeof(ProjectionHeader) - 1);
                headerBytesRead = sizeof(ProjectionHeader) - 1;
                continue;
            }

            // Convert Big-Endian payload length to host
            currentPayloadRemaining = ntohl(header.payloadLength);
            currentChannel = header.channel;
            headerBytesRead = 0; // Reset for next frame

            // Step 2: Stream payload directly into designated SPSC ring buffer
            while (currentPayloadRemaining > 0 && m_running.load(std::memory_order_relaxed)) {
                size_t chunkToRead = std::min(static_cast<size_t>(currentPayloadRemaining), sizeof(rxBuffer));
                ssize_t bytes = read(m_activeFd, rxBuffer, chunkToRead);

                if (bytes <= 0) {
                    if (bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                        usleep(200);
                        continue;
                    }
                    LOGE("Reader payload read error: %d", errno);
                    m_running.store(false, std::memory_order_release);
                    return;
                }

                const size_t bytesRead = static_cast<size_t>(bytes);
                currentPayloadRemaining -= bytesRead;

                // Zero-copy dispatch into respective ring buffer
                if (currentChannel == CHANNEL_VIDEO && m_videoBuffer) {
                    size_t written = 0;
                    while (written < bytesRead && m_running.load(std::memory_order_relaxed)) {
                        size_t w = m_videoBuffer->write(rxBuffer + written, bytesRead - written);
                        written += w;
                        if (w == 0) {
                            // Decoder is momentarily busy; yield 250us without heap alloc
                            usleep(250);
                        }
                    }
                } else if (currentChannel == CHANNEL_AUDIO && m_audioBuffer) {
                    size_t written = 0;
                    while (written < bytesRead && m_running.load(std::memory_order_relaxed)) {
                        size_t w = m_audioBuffer->write(rxBuffer + written, bytesRead - written);
                        written += w;
                        if (w == 0) {
                            usleep(250);
                        }
                    }
                } else if (currentChannel == CHANNEL_CONTROL) {
                    // Control heartbeats / KeepAlive
                }
            }
        }
        LOGI("Transport reader thread exiting");
    }

    void writerLoop() {
        pthread_setname_np(pthread_self(), "GD_TransportTx");
        setpriority(PRIO_PROCESS, 0, -14);

        alignas(16) uint8_t txBuffer[1024];

        while (m_running.load(std::memory_order_relaxed)) {
            size_t available = m_inputQueue.availableRead();
            if (available == 0) {
                usleep(1000); // 1ms sleep for touch thread idle
                continue;
            }

            size_t toRead = std::min(available, sizeof(txBuffer));
            size_t bytes = m_inputQueue.read(txBuffer, toRead);
            if (bytes > 0 && m_activeFd >= 0) {
                size_t sent = 0;
                while (sent < bytes) {
                    ssize_t s = write(m_activeFd, txBuffer + sent, bytes - sent);
                    if (s <= 0) {
                        if (s < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                            usleep(100);
                            continue;
                        }
                        break;
                    }
                    sent += static_cast<size_t>(s);
                }
            }
        }
        LOGI("Transport writer thread exiting");
    }

    int m_activeFd;
    bool m_isUsb;
    std::atomic<bool> m_running;

    VideoRingBuffer* m_videoBuffer;
    AudioRingBuffer* m_audioBuffer;
    SpscRingBuffer<64 * 1024> m_inputQueue; // Outbound touch & steering wheel events

    std::thread m_readerThread;
    std::thread m_writerThread;
};

} // namespace goatsdrive
