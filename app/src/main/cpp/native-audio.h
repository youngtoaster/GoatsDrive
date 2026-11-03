#pragma once

#include "ring_buffer.h"

#if HAVE_AAUDIO
#include <aaudio/AAudio.h>
#endif
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <atomic>
#include <cstdint>

namespace goatsdrive {

class NativeAudioSink {
public:
    static NativeAudioSink& getInstance();

    bool init(int sampleRate = 48000, int channelCount = 2);
    void stop();
    void setDucked(bool ducked);
    AudioRingBuffer& getRingBuffer();

private:
    NativeAudioSink();
    ~NativeAudioSink();

#if HAVE_AAUDIO
    bool initAAudio();
    void stopAAudio();
    static aaudio_data_callback_result_t aaudioDataCallbackStatic(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames
    );
    aaudio_data_callback_result_t onAAudioData(int16_t* output, int32_t numFrames);
#endif

    bool initOpenSLES();
    void stopOpenSLES();
    static void openSlCallbackStatic(SLAndroidSimpleBufferQueueItf bq, void* context);
    void onOpenSlBuffer(SLAndroidSimpleBufferQueueItf bq);

    int m_sampleRate;
    int m_channels;
    std::atomic<bool> m_running;
    std::atomic<bool> m_ducked;

    alignas(64) AudioRingBuffer m_audioBuffer;

#if HAVE_AAUDIO
    AAudioStream* m_aaudioStream;
#endif

    SLObjectItf m_slEngineObject;
    SLEngineItf m_slEngine;
    SLObjectItf m_slOutputMixObject;
    SLObjectItf m_slPlayerObject;
    SLPlayItf m_slPlayerPlay;
    SLAndroidSimpleBufferQueueItf m_slBufferQueue;
    size_t m_currentPcmIndex;
};

} // namespace goatsdrive
