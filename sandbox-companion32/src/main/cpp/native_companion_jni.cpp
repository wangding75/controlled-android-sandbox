#include <jni.h>
#include <string>

namespace {
std::string abi_name() {
#if defined(__arm__)
    return "armeabi-v7a";
#elif defined(__i386__)
    return "x86";
#else
    return "unsupported";
#endif
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_warden_controlledsandbox_companion32_NativeCompanionBridge_nativeProcessBitness(JNIEnv*, jclass) {
    return static_cast<jint>(sizeof(void*) * 8);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_companion32_NativeCompanionBridge_nativeStatus(JNIEnv* env, jclass) {
    const std::string value = "bitness=" + std::to_string(sizeof(void*) * 8) + ";abi=" + abi_name()
            + ";hookLibrary=controlled_sandbox_native32";
    return env->NewStringUTF(value.c_str());
}
