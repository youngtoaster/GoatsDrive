#include "transport_io.h"

#include <android/log.h>
#include <cstring>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#define TAG "GoatsDrive_IO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace goatsdrive {

#pragma pack(push, 1)
struct ProjectionHeader {
    uint8_t magic[2];
    uint8_t channel;
    uint8_t flags;
    uint32_t payloadLength;
};
#pragma pack(pop)

static constexpr uint8_t CHANNEL_VIDEO   = 0x01;
static constexpr uint8_t CHANNEL_AUDIO   = 0x02;
static constexpr uint8_t CHANNEL_CONTROL = 0x03;

TransportIO& TransportIO::getInstance() {
    static TransportIO instance;
    return instance;
}

TransportIO::TransportIO()
    : m_activeFd(-1),
      m_isUsb(false),
      m_running(false),
      m_videoBuffer(nullptr),
      m_audioBuffer(nullptr) {}

TransportIO::~TransportIO() {
    stop();
}

void TransportIO::setBuffers(VideoRingBuffer* vBuffer, AudioRingBuffer* aBuffer) {
    m_videoBuffer = vBuffer;
    m_audioBuffer = aBuffer;
}

bool TransportIO::startSocket(int socketFd) {
    stop();
    m_activeFd = socketFd;
    m_isUsb = false;
    configureSocket(socketFd);
    return startThreads();
}

bool TransportIO::startUsb(int usbFd) {
    stop();
    m_activeFd = usbFd;
    m_isUsb = true;
    int flags = fcntl(usbFd, F_GETFL, 0);
    fcntl(usbFd, F_SETFL, flags | O_NONBLOCK);
    return startThreads();
}

void TransportIO::stop() {
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

bool TransportIO::sendInputPacket(const uint8_t* data, size_t length) {
    if (!m_running.load(std::memory_order_acquire) || m_activeFd < 0 || length == 0) {
        return false;
    }
    return m_inputQueue.write(data, length) == length;
}

bool TransportIO::isRunning() const {
    return m_running.load(std::memory_order_relaxed);
}

void TransportIO::configureSocket(int fd) {
    int flag = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

    int rcvBufSize = 2 * 1024 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvBufSize, sizeof(rcvBufSize));

    int sndBufSize = 128 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndBufSize, sizeof(sndBufSize));

    int tos = 0xB8;
    setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));
}

bool TransportIO::startThreads() {
    m_running.store(true, std::memory_order_release);
    m_readerThread = std::thread(&TransportIO::readerLoop, this);
    m_writerThread = std::thread(&TransportIO::writerLoop, this);
    return true;
}

void TransportIO::readerLoop() {
    pthread_setname_np(pthread_self(), "GD_TransportRx");
    setpriority(PRIO_PROCESS, 0, -16);

    LOGI("Transport reader thread started (FD: %d, isUsb: %d)", m_activeFd, m_isUsb);

    alignas(16) uint8_t rxBuffer[65536];
    ProjectionHeader header;
    uint8_t* headerPtr = reinterpret_cast<uint8_t*>(&header);
    size_t headerBytesRead = 0;

    uint32_t currentPayloadRemaining = 0;
    uint8_t currentChannel = 0;

    while (m_running.load(std::memory_order_relaxed)) {
        while (headerBytesRead < sizeof(ProjectionHeader)) {
            ssize_t bytes = read(m_activeFd, headerPtr + headerBytesRead, sizeof(ProjectionHeader) - headerBytesRead);
            if (bytes <= 0) {
                if (bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                    usleep(500);
                    if (!m_running.load(std::memory_order_relaxed)) return;
                    continue;
                }
                LOGE("Reader socket/USB connection closed or error: %d", errno);
                m_running.store(false, std::memory_order_release);
                return;
            }
            headerBytesRead += static_cast<size_t>(bytes);
        }

        if (header.magic[0] != 0x47 || header.magic[1] != 0x44) {
            LOGE("Transport framing out of sync: [0x%02X, 0x%02X]. Resyncing...",
                 header.magic[0], header.magic[1]);
            std::memmove(headerPtr, headerPtr + 1, sizeof(ProjectionHeader) - 1);
            headerBytesRead = sizeof(ProjectionHeader) - 1;
            continue;
        }

        currentPayloadRemaining = ntohl(header.payloadLength);
        currentChannel = header.channel;
        headerBytesRead = 0;

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

            if (currentChannel == CHANNEL_VIDEO && m_videoBuffer) {
                size_t written = 0;
                while (written < bytesRead && m_running.load(std::memory_order_relaxed)) {
                    size_t w = m_videoBuffer->write(rxBuffer + written, bytesRead - written);
                    written += w;
                    if (w == 0) {
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
                // Keepalive heartbeat
            }
        }
    }
    LOGI("Transport reader thread exiting");
}

void TransportIO::writerLoop() {
    pthread_setname_np(pthread_self(), "GD_TransportTx");
    setpriority(PRIO_PROCESS, 0, -14);

    alignas(16) uint8_t txBuffer[1024];

    while (m_running.load(std::memory_order_relaxed)) {
        size_t available = m_inputQueue.availableRead();
        if (available == 0) {
            usleep(1000);
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

} // namespace goatsdrive
