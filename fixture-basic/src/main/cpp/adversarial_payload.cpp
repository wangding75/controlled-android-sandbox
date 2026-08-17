#include <jni.h>

#include <atomic>
#include <cstring>

namespace {

std::atomic<int> g_onload_count{0};

}  // namespace

extern "C" const char* fixture_adv_payload_marker(void) {
    return "FIXTURE_ADV_PAYLOAD_V1";
}

extern "C" int fixture_adv_payload_onload_count(void) {
    return g_onload_count.load();
}

extern "C" jint JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    g_onload_count.fetch_add(1);
    return JNI_VERSION_1_6;
}
