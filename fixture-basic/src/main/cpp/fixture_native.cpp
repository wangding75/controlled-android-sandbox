#include <jni.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <netdb.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_FixtureNative_nativeProbe(JNIEnv* env, jclass) {
    std::string status;
    const char* path = "/data/data/com.warden.controlledsandbox.fixture/files/native-probe.txt";
    const int descriptor = open(path, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    if (descriptor >= 0) {
        constexpr char payload[] = "NATIVE_FILE_OK";
        (void) write(descriptor, payload, sizeof(payload) - 1);
        close(descriptor);
        status += "FILE_OK";
    } else {
        status += "FILE_FAIL:" + std::to_string(errno);
    }

    addrinfo* addresses = nullptr;
    const int dns = getaddrinfo("localhost", nullptr, nullptr, &addresses);
    if (addresses != nullptr) freeaddrinfo(addresses);
    status += dns == 0 ? ";DNS_OK" : ";DNS_FAIL:" + std::to_string(dns);

    void* self = dlopen(nullptr, RTLD_NOW);
    status += self != nullptr ? ";DLOPEN_OK" : ";DLOPEN_FAIL";
    if (self != nullptr) dlclose(self);
    return env->NewStringUTF(status.c_str());
}
