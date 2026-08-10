#include <jni.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <netdb.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_FixtureNative_nativeProbe(
        JNIEnv* env, jclass, jstring requested_path) {
    std::string status;
    if (requested_path == nullptr) return env->NewStringUTF("FILE_FAIL:EINVAL");
    const char* raw_path = env->GetStringUTFChars(requested_path, nullptr);
    if (raw_path == nullptr) return env->NewStringUTF("FILE_FAIL:ENOMEM");
    const std::string path(raw_path);
    env->ReleaseStringUTFChars(requested_path, raw_path);
    if (path.empty() || path.find("..") != std::string::npos || path.front() != '/') {
        return env->NewStringUTF("FILE_FAIL:EINVAL");
    }
    const int descriptor = open(path.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0600);
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

    // The Sandbox loader deliberately rejects dlopen(nullptr): exposing the
    // main-program handle would bypass the guest library allowlist.  Probe the
    // current, explicitly allowed Guest soname instead.
    void* self = dlopen("libcontrolled_sandbox_fixture.so", RTLD_NOW | RTLD_LOCAL);
    status += self != nullptr ? ";DLOPEN_OK" : ";DLOPEN_FAIL";
    if (self != nullptr) dlclose(self);
    return env->NewStringUTF(status.c_str());
}
