#pragma once

#include <cstdint>
#include <cstddef>
#include <atomic>
#include <cstring>
#include <algorithm>

namespace goatsdrive {

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) Ring Buffer.
 * Guaranteed zero dynamic allocations during runtime.
 * Cache-line aligned (64-byte) head/tail pointers eliminate false sharing
 * between the network/USB IO thread (producer) and decoder thread (consumer).
 */
template <size_t Capacity>
class SpscRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    SpscRingBuffer() : m_head(0), m_tail(0) {
        std::memset(m_buffer, 0, sizeof(m_buffer));
    }

    ~SpscRingBuffer() = default;

    // Non-copyable, non-movable for cache stability
    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    /**
     * Producer: Write bytes to the buffer.
     * @return Number of bytes actually written.
     */
    size_t write(const uint8_t* src, size_t count) {
        if (!src || count == 0) return 0;

        const size_t head = m_head.load(std::memory_order_relaxed);
        const size_t tail = m_tail.load(std::memory_order_acquire);
        const size_t available = Capacity - (head - tail);

        const size_t toWrite = std::min(count, available);
        if (toWrite == 0) return 0;

        const size_t index = head & MASK;
        const size_t chunk = std::min(toWrite, Capacity - index);

        std::memcpy(&m_buffer[index], src, chunk);
        if (toWrite > chunk) {
            std::memcpy(&m_buffer[0], src + chunk, toWrite - chunk);
        }

        m_head.store(head + toWrite, std::memory_order_release);
        return toWrite;
    }

    /**
     * Consumer: Read bytes from the buffer.
     * @return Number of bytes actually read.
     */
    size_t read(uint8_t* dst, size_t count) {
        if (!dst || count == 0) return 0;

        const size_t tail = m_tail.load(std::memory_order_relaxed);
        const size_t head = m_head.load(std::memory_order_acquire);
        const size_t available = head - tail;

        const size_t toRead = std::min(count, available);
        if (toRead == 0) return 0;

        const size_t index = tail & MASK;
        const size_t chunk = std::min(toRead, Capacity - index);

        std::memcpy(dst, &m_buffer[index], chunk);
        if (toRead > chunk) {
            std::memcpy(dst + chunk, &m_buffer[0], toRead - chunk);
        }

        m_tail.store(tail + toRead, std::memory_order_release);
        return toRead;
    }

    /**
     * Consumer: Peek bytes without advancing tail pointer.
     */
    size_t peek(uint8_t* dst, size_t count) const {
        if (!dst || count == 0) return 0;

        const size_t tail = m_tail.load(std::memory_order_relaxed);
        const size_t head = m_head.load(std::memory_order_acquire);
        const size_t available = head - tail;

        const size_t toPeek = std::min(count, available);
        if (toPeek == 0) return 0;

        const size_t index = tail & MASK;
        const size_t chunk = std::min(toPeek, Capacity - index);

        std::memcpy(dst, &m_buffer[index], chunk);
        if (toPeek > chunk) {
            std::memcpy(dst + chunk, &m_buffer[0], toPeek - chunk);
        }

        return toPeek;
    }

    /**
     * Consumer: Skip bytes in the ring buffer.
     */
    size_t skip(size_t count) {
        const size_t tail = m_tail.load(std::memory_order_relaxed);
        const size_t head = m_head.load(std::memory_order_acquire);
        const size_t available = head - tail;

        const size_t toSkip = std::min(count, available);
        m_tail.store(tail + toSkip, std::memory_order_release);
        return toSkip;
    }

    [[nodiscard]] size_t availableRead() const {
        return m_head.load(std::memory_order_acquire) - m_tail.load(std::memory_order_relaxed);
    }

    [[nodiscard]] size_t availableWrite() const {
        return Capacity - (m_head.load(std::memory_order_relaxed) - m_tail.load(std::memory_order_acquire));
    }

    void reset() {
        m_tail.store(0, std::memory_order_relaxed);
        m_head.store(0, std::memory_order_release);
    }

private:
    static constexpr size_t MASK = Capacity - 1;

    // Cache line isolation (64 bytes)
    alignas(64) std::atomic<size_t> m_head;
    alignas(64) std::atomic<size_t> m_tail;
    alignas(64) uint8_t m_buffer[Capacity];
};

// 4MB Ring Buffer for Video NAL Units (Smooth 60FPS 1080p stream buffering)
using VideoRingBuffer = SpscRingBuffer<4 * 1024 * 1024>;

// 256KB Ring Buffer for PCM Audio (Low-latency 48kHz Stereo)
using AudioRingBuffer = SpscRingBuffer<256 * 1024>;

} // namespace goatsdrive
