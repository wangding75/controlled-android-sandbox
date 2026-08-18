#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_warden_controlledsandbox_fixture_scale_ScaleNative_version(JNIEnv*, jclass) {
    return 1;
}
