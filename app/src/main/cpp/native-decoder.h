#pragma once

#include "ring_buffer.h"

#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <atomic>
#include <cstdint>
#include <thread>

namespace goatsdrive {

class NativeVideoDecoder {
public:
    static NativeVideoDecoder& getInstance();

    bool init(ANativeWindow* window, int width, int height);
    void stop();

    VideoRingBuffer& getRingBuffer();
    [[nodiscard]] uint64_t getDecodedFrames() const;
    [[nodiscard]] uint64_t getDroppedFrames() const;

private:
    NativeVideoDecoder();
    ~NativeVideoDecoder();

    static int findStartCode(const uint8_t* p, size_t len, size_t& scLen);
    void feedLoop();
    void drainLoop();

    ANativeWindow* m_window;
    AMediaCodec* m_codec;
    int m_width;
    int m_height;
    std::atomic<bool> m_running;

    alignas(64) VideoRingBuffer m_videoBuffer;

    std::thread m_feedThread;
    std::thread m_drainThread;

    std::atomic<uint64_t> m_decodedFrames;
    std::atomic<uint64_t> m_droppedFrames;
};

} // namespace goatsdrive
