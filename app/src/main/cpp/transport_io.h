#pragma once

#include "ring_buffer.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <thread>

namespace goatsdrive {

class TransportIO {
public:
    static TransportIO& getInstance();
    void setBuffers(VideoRingBuffer* vBuffer, AudioRingBuffer* aBuffer);
    bool startSocket(int socketFd);
    bool startUsb(int usbFd);
    void stop();
    bool sendInputPacket(const uint8_t* data, size_t length);
    [[nodiscard]] bool isRunning() const;

private:
    TransportIO();
    ~TransportIO();

    void configureSocket(int fd);
    bool startThreads();
    void readerLoop();
    void writerLoop();

    int m_activeFd;
    bool m_isUsb;
    std::atomic<bool> m_running;

    VideoRingBuffer* m_videoBuffer;
    AudioRingBuffer* m_audioBuffer;
    SpscRingBuffer<64 * 1024> m_inputQueue;

    std::thread m_readerThread;
    std::thread m_writerThread;
};

} // namespace goatsdrive
