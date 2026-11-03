#include "ring_buffer.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#define TAG "GoatsDrive_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Forward declarations of engine singletons
namespace goatsdrive {
    class TransportIO {
    public:
        static TransportIO& getInstance();
        void setBuffers(VideoRingBuffer* vBuffer, AudioRingBuffer* aBuffer);
        bool startSocket(int socketFd);
        bool startUsb(int usbFd);
        void stop();
        bool sendInputPacket(const uint8_t* data, size_t length);
    };

    class NativeVideoDecoder {
    public:
        static NativeVideoDecoder& getInstance();
        bool init(ANativeWindow* window, int width, int height);
        void stop();
        VideoRingBuffer& getRingBuffer();
        uint64_t getDecodedFrames() const;
        uint64_t getDroppedFrames() const;
    };

    class NativeAudioSink {
    public:
        static NativeAudioSink& getInstance();
        bool init(int sampleRate, int channelCount);
        void stop();
        void setDucked(bool ducked);
        AudioRingBuffer& getRingBuffer();
    };
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_apexlink_projection_NativeBridge_nativeInit(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject surface,
    jint width,
    jint height
) {
    if (!surface) {
        LOGE("nativeInit: null Surface provided");
        return JNI_FALSE;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("nativeInit: Failed to obtain ANativeWindow from Surface");
        return JNI_FALSE;
    }

    auto& decoder = goatsdrive::NativeVideoDecoder::getInstance();
    if (!decoder.init(window, width, height)) {
        LOGE("nativeInit: Video decoder failed to initialize");
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    auto& audio = goatsdrive::NativeAudioSink::getInstance();
    if (!audio.init(48000, 2)) {
        LOGE("nativeInit: Audio sink failed to initialize");
        // Proceed even if audio fails to allow video-only operation
    }

    // Connect Transport ring buffers directly
    auto& transport = goatsdrive::TransportIO::getInstance();
    transport.setBuffers(&decoder.getRingBuffer(), &audio.getRingBuffer());

    LOGI("GoatsDrive native engine initialized successfully (%dx%d)", width, height);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_apexlink_projection_NativeBridge_nativeStartSocketTransport(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint socketFd
) {
    if (socketFd < 0) return JNI_FALSE;
    bool ok = goatsdrive::TransportIO::getInstance().startSocket(socketFd);
    LOGI("Socket transport started: %s (FD: %d)", ok ? "SUCCESS" : "FAILED", socketFd);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_apexlink_projection_NativeBridge_nativeStartUsbTransport(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint usbFd
) {
    if (usbFd < 0) return JNI_FALSE;
    bool ok = goatsdrive::TransportIO::getInstance().startUsb(usbFd);
    LOGI("USB transport started: %s (FD: %d)", ok ? "SUCCESS" : "FAILED", usbFd);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_apexlink_projection_NativeBridge_nativeStop(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    goatsdrive::TransportIO::getInstance().stop();
    goatsdrive::NativeVideoDecoder::getInstance().stop();
    goatsdrive::NativeAudioSink::getInstance().stop();
    LOGI("GoatsDrive native engine stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_apexlink_projection_NativeBridge_nativeSendTouch(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray packet,
    jint length
) {
    if (!packet || length <= 0) return JNI_FALSE;

    // Use GetPrimitiveArrayCritical for ZERO heap copy and zero GC pause
    jbyte* bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(packet, nullptr));
    if (!bytes) return JNI_FALSE;

    bool result = goatsdrive::TransportIO::getInstance().sendInputPacket(
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<size_t>(length)
    );

    env->ReleasePrimitiveArrayCritical(packet, bytes, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_apexlink_projection_NativeBridge_nativeSendKey(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint keyCode,
    jint action
) {
    // Pack key packet into stack memory: [Magic(2), Channel(1), Flags(1), Length(4), KeyCode(4), Action(4)]
    #pragma pack(push, 1)
    struct KeyPacket {
        uint8_t magic[2] = {0x47, 0x44};
        uint8_t channel = 0x04; // Channel Input
        uint8_t flags = 0x00;
        uint32_t payloadLength = 0x08000000; // 8 bytes in big-endian
        int32_t code;
        int32_t act;
    } pkt;
    #pragma pack(pop)

    pkt.code = keyCode;
    pkt.act = action;

    return goatsdrive::TransportIO::getInstance().sendInputPacket(
        reinterpret_cast<const uint8_t*>(&pkt),
        sizeof(pkt)
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_apexlink_projection_NativeBridge_nativeSetAudioDuck(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean ducked
) {
    goatsdrive::NativeAudioSink::getInstance().setDucked(ducked == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_apexlink_projection_NativeBridge_nativeGetMetrics(
    JNIEnv* env,
    jobject /*thiz*/,
    jlongArray outMetrics
) {
    if (!outMetrics) return;

    jsize len = env->GetArrayLength(outMetrics);
    if (len < 2) return;

    jlong* ptr = static_cast<jlong*>(env->GetPrimitiveArrayCritical(outMetrics, nullptr));
    if (ptr) {
        ptr[0] = static_cast<jlong>(goatsdrive::NativeVideoDecoder::getInstance().getDecodedFrames());
        ptr[1] = static_cast<jlong>(goatsdrive::NativeVideoDecoder::getInstance().getDroppedFrames());
        env->ReleasePrimitiveArrayCritical(outMetrics, ptr, 0);
    }
}

} // extern "C"
