#include "controlled_sandbox/native_policy.h"
#include "controlled_sandbox/native_audio.h"
#include "controlled_sandbox/native_interceptors.h"
#include "controlled_sandbox/native_loader.h"
#include "controlled_sandbox/native_network.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <link.h>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

std::string string_value(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("GetStringUTFChars failed");
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::vector<std::string> string_array(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> out;
    if (values == nullptr) return out;
    const jsize count = env->GetArrayLength(values);
    out.reserve(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; i++) {
        auto* value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        out.push_back(string_value(env, value));
        env->DeleteLocalRef(value);
    }
    return out;
}

std::vector<controlled_sandbox::CidrV4> cidr_array(JNIEnv* env, jobjectArray values) {
    std::vector<controlled_sandbox::CidrV4> out;
    for (const auto& value : string_array(env, values)) {
        auto parsed = controlled_sandbox::CidrV4::parse(value);
        if (!parsed) throw std::invalid_argument("Invalid IPv4 CIDR: " + value);
        out.push_back(*parsed);
    }
    return out;
}

std::vector<controlled_sandbox::CidrV6> cidr6_array(JNIEnv* env, jobjectArray values) {
    std::vector<controlled_sandbox::CidrV6> out;
    for (const auto& value : string_array(env, values)) {
        auto parsed = controlled_sandbox::CidrV6::parse(value);
        if (!parsed) throw std::invalid_argument("Invalid IPv6 CIDR: " + value);
        out.push_back(*parsed);
    }
    return out;
}

void throw_java(JNIEnv* env, const char* type, const std::string& message) {
    jclass klass = env->FindClass(type);
    if (klass != nullptr) env->ThrowNew(klass, message.c_str());
}

using HiddenApiNative = void (*)(JNIEnv*, jclass, jobjectArray);

struct LoadedSegment {
    uintptr_t begin;
    uintptr_t end;
    int flags;
};

struct HiddenApiNativeSearch {
    const char* method_name;
    const char* signature;
    HiddenApiNative method;
};

bool readable(const LoadedSegment& segment, uintptr_t address, std::size_t size) {
    return (segment.flags & PF_R) != 0
            && address >= segment.begin
            && address <= segment.end
            && size <= segment.end - address;
}

bool executable(const LoadedSegment& segment, uintptr_t address) {
    return (segment.flags & PF_X) != 0
            && address >= segment.begin
            && address < segment.end;
}

uintptr_t read_pointer(uintptr_t address) {
    uintptr_t value = 0;
    std::memcpy(&value, reinterpret_cast<const void*>(address), sizeof(value));
    return value;
}

uintptr_t find_string(const LoadedSegment& segment, const char* value) {
    if ((segment.flags & PF_R) == 0 || segment.end <= segment.begin) return 0;
    const std::size_t length = std::strlen(value) + 1;
    if (length > segment.end - segment.begin) return 0;
    for (uintptr_t address = segment.begin;
            address <= segment.end - length; address++) {
        if (std::memcmp(reinterpret_cast<const void*>(address), value, length) == 0) {
            return address;
        }
    }
    return 0;
}

int find_hidden_api_native(dl_phdr_info* info, std::size_t, void* opaque) {
    auto* search = static_cast<HiddenApiNativeSearch*>(opaque);
    const char* module = info->dlpi_name == nullptr ? "" : info->dlpi_name;
    if (std::strstr(module, "libart.so") == nullptr) return 0;

    LoadedSegment segments[32]{};
    std::size_t segment_count = 0;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum && segment_count < 32; index++) {
        const ElfW(Phdr)& header = info->dlpi_phdr[index];
        if (header.p_type != PT_LOAD || header.p_memsz == 0) continue;
        segments[segment_count++] = LoadedSegment{
                static_cast<uintptr_t>(info->dlpi_addr + header.p_vaddr),
                static_cast<uintptr_t>(info->dlpi_addr + header.p_vaddr + header.p_memsz),
                static_cast<int>(header.p_flags)};
    }

    uintptr_t name_address = 0;
    for (std::size_t index = 0; index < segment_count && name_address == 0; index++) {
        name_address = find_string(segments[index], search->method_name);
    }
    if (name_address == 0) return 0;

    const std::size_t pointer_bytes = sizeof(uintptr_t);
    for (std::size_t segment_index = 0; segment_index < segment_count; segment_index++) {
        const LoadedSegment& segment = segments[segment_index];
        if ((segment.flags & PF_R) == 0 || segment.end - segment.begin < pointer_bytes * 3) {
            continue;
        }
        const uintptr_t last = segment.end - pointer_bytes * 3;
        for (uintptr_t address = segment.begin; address <= last; address += pointer_bytes) {
            const uintptr_t name = read_pointer(address);
            if (name != name_address) continue;
            const uintptr_t signature = read_pointer(address + pointer_bytes);
            bool signature_matches = false;
            for (std::size_t candidate = 0; candidate < segment_count; candidate++) {
                if (!readable(segments[candidate], signature,
                        std::strlen(search->signature) + 1)) continue;
                signature_matches = std::strcmp(
                        reinterpret_cast<const char*>(signature), search->signature) == 0;
                if (signature_matches) break;
            }
            if (!signature_matches) continue;
            const uintptr_t method = read_pointer(address + pointer_bytes * 2);
            for (std::size_t candidate = 0; candidate < segment_count; candidate++) {
                if (!executable(segments[candidate], method)) continue;
                search->method = reinterpret_cast<HiddenApiNative>(method);
                return 1;
            }
        }
    }
    return 0;
}

HiddenApiNative find_hidden_api_native() {
    HiddenApiNativeSearch search{
            "setHiddenApiExemptions", "([Ljava/lang/String;)V", nullptr};
    dl_iterate_phdr(find_hidden_api_native, &search);
    return search.method;
}

bool install_hidden_api_bridge(JNIEnv* env) {
    constexpr const char* tag = "CS_HIDDEN_API_NATIVE";
    jclass runtime = env->FindClass("dalvik/system/VMRuntime");
    if (runtime == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, tag, "VMRuntime class unavailable");
        return false;
    }

    // Java lookup intentionally remains unused for the hidden method: API35 filters it from
    // ordinary application reflection. Scan the already loaded ART native-method table and call
    // only the exact AOSP VMRuntime implementation; never re-register the VMRuntime class after
    // ART startup, which is a process-aborting operation on API35.
    HiddenApiNative native_method = find_hidden_api_native();
    if (native_method == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, tag, "VMRuntime native method unavailable");
        env->DeleteLocalRef(runtime);
        return false;
    }
    jmethodID get_runtime = env->GetStaticMethodID(
            runtime, "getRuntime", "()Ldalvik/system/VMRuntime;");
    if (get_runtime == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, tag, "VMRuntime.getRuntime unavailable");
        env->DeleteLocalRef(runtime);
        return false;
    }

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(runtime);
        return false;
    }
    // Keep this list tied to framework classes used by the audited compatibility hooks. A
    // package wildcard or process-wide global policy is intentionally not used.
    constexpr const char* prefixes[] = {
            "Landroid/app/",
            "Landroid/content/",
            "Landroid/hardware/",
            "Landroid/media/",
            "Landroid/net/",
            "Landroid/os/",
            "Landroid/provider/",
            "Landroid/service/",
            "Landroid/telephony/",
            "Landroid/view/",
            "Landroid/webkit/"
    };
    constexpr jsize prefix_count = static_cast<jsize>(sizeof(prefixes) / sizeof(prefixes[0]));
    jobjectArray values = env->NewObjectArray(prefix_count, string_class, nullptr);
    if (values == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(string_class);
        env->DeleteLocalRef(runtime);
        return false;
    }
    for (jsize index = 0; index < prefix_count; index++) {
        jstring value = env->NewStringUTF(prefixes[index]);
        if (value == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(values);
            env->DeleteLocalRef(string_class);
            env->DeleteLocalRef(runtime);
            return false;
        }
        env->SetObjectArrayElement(values, index, value);
        env->DeleteLocalRef(value);
    }
    jobject instance = env->CallStaticObjectMethod(runtime, get_runtime);
    if (env->ExceptionCheck() || instance == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, tag, "VMRuntime.getRuntime invocation failed");
        env->DeleteLocalRef(values);
        env->DeleteLocalRef(string_class);
        env->DeleteLocalRef(runtime);
        return false;
    }
    native_method(env, runtime, values);
    const bool success = !env->ExceptionCheck();
    if (!success) env->ExceptionClear();
    __android_log_print(success ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, tag,
            "VMRuntime.setHiddenApiExemptions success=%s", success ? "true" : "false");
    env->DeleteLocalRef(instance);
    env->DeleteLocalRef(values);
    env->DeleteLocalRef(string_class);
    env->DeleteLocalRef(runtime);
    return success;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallHiddenApiBridge(
        JNIEnv* env, jclass) {
    return install_hidden_api_bridge(env) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeConfigure(
        JNIEnv* env, jclass, jstring session_id, jlong generation,
        jstring package_name, jstring process_name, jint user_id, jint virtual_uid,
        jint virtual_pid, jstring abi_name, jstring instance_root,
        jstring apk_path, jstring native_library_root, jboolean default_allow,
        jobjectArray allow_hosts, jobjectArray deny_hosts,
        jobjectArray allow_cidrs, jobjectArray deny_cidrs,
        jobjectArray allow_cidrs_v6, jobjectArray deny_cidrs_v6,
        jstring virtual_hostname, jstring virtual_interface_name,
        jstring virtual_ipv4, jstring virtual_ipv6,
        jstring proxy_host, jint proxy_port, jboolean cleartext_permitted,
        jint network_id, jstring transport, jboolean vpn_active,
        jboolean metered, jboolean validated, jint mtu,
        jstring private_dns_server_name, jobjectArray dns_servers) {
    try {
        if (generation <= 0) throw std::invalid_argument("generation must be positive");
        controlled_sandbox::global_policy().configure(
                string_value(env, session_id), static_cast<std::uint64_t>(generation),
                string_value(env, package_name), string_value(env, process_name), user_id,
                virtual_uid, virtual_pid, string_value(env, abi_name), string_value(env, instance_root),
                string_value(env, apk_path), string_value(env, native_library_root), default_allow,
                string_array(env, allow_hosts), string_array(env, deny_hosts),
                cidr_array(env, allow_cidrs), cidr_array(env, deny_cidrs),
                cidr6_array(env, allow_cidrs_v6), cidr6_array(env, deny_cidrs_v6),
                controlled_sandbox::NativeNetworkIdentity{string_value(env, virtual_hostname),
                        string_value(env, virtual_interface_name), string_value(env, virtual_ipv4),
                        string_value(env, virtual_ipv6), string_value(env, proxy_host), proxy_port,
                        cleartext_permitted == JNI_TRUE, network_id, string_value(env, transport),
                        vpn_active == JNI_TRUE, metered == JNI_TRUE, validated == JNI_TRUE, mtu,
                        string_value(env, private_dns_server_name), string_array(env, dns_servers)});
        return JNI_TRUE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeMapPath(
        JNIEnv* env, jclass, jstring path) {
    try {
        const std::string mapped = controlled_sandbox::global_policy().map_path(string_value(env, path));
        return env->NewStringUTF(mapped.c_str());
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/SecurityException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeAllowHost(
        JNIEnv* env, jclass, jstring host) {
    return controlled_sandbox::global_policy().allow_host(string_value(env, host)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeAllowIpv4(
        JNIEnv* env, jclass, jstring address) {
    return controlled_sandbox::global_policy().allow_ipv4(string_value(env, address)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeAllowIpv6(
        JNIEnv* env, jclass, jstring address) {
    return controlled_sandbox::global_policy().allow_ipv6(string_value(env, address)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeConfigureAudioCapture(
        JNIEnv* env, jclass, jstring session_id, jlong generation, jboolean allowed) {
    try {
        if (allowed != JNI_TRUE) controlled_sandbox::revoke_native_audio_captures();
        controlled_sandbox::global_audio_capture_policy().configure(
                string_value(env, session_id), static_cast<std::uint64_t>(generation), allowed == JNI_TRUE);
        return JNI_TRUE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeSetAudioCaptureAllowed(
        JNIEnv* env, jclass, jlong generation, jboolean allowed) {
    try {
        if (allowed != JNI_TRUE) controlled_sandbox::revoke_native_audio_captures();
        controlled_sandbox::global_audio_capture_policy().set_allowed(
                static_cast<std::uint64_t>(generation), allowed == JNI_TRUE);
        return JNI_TRUE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeBeginAudioCapture(
        JNIEnv* env, jclass, jstring api) {
    try { return static_cast<jlong>(controlled_sandbox::global_audio_capture_policy().begin(string_value(env, api))); }
    catch (const std::exception& error) { throw_java(env, "java/lang/IllegalArgumentException", error.what()); return 0; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeEndAudioCapture(
        JNIEnv*, jclass, jlong token) {
    return controlled_sandbox::global_audio_capture_policy().end(static_cast<std::uint64_t>(token))
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeAudioCaptureStatus(JNIEnv* env, jclass) {
    const auto snapshot = controlled_sandbox::global_audio_capture_policy().snapshot();
    const std::string value = "configured=" + std::string(snapshot.configured ? "true" : "false")
            + ";generation=" + std::to_string(snapshot.generation)
            + ";allowed=" + std::string(snapshot.allowed ? "true" : "false")
            + ";active=" + std::to_string(snapshot.active_count)
            + ";revision=" + std::to_string(snapshot.revision);
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeResetAudioCapture(JNIEnv*, jclass) {
    controlled_sandbox::revoke_native_audio_captures();
    controlled_sandbox::global_audio_capture_policy().reset();
}

#include "controlled_sandbox/native_hook.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallHooks(
        JNIEnv* env, jclass, jstring guest_library_root) {
    try {
        return controlled_sandbox::global_hooks().install(string_value(env, guest_library_root))
                ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeRefreshHooks(JNIEnv*, jclass) {
    return controlled_sandbox::global_hooks().refresh() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeHookStatus(JNIEnv* env, jclass) {
    const auto status = controlled_sandbox::global_hooks().status();
    std::string value = "installed=" + std::string(status.installed ? "true" : "false")
            + ";scanned=" + std::to_string(status.modules_scanned)
            + ";matched=" + std::to_string(status.modules_matched)
            + ";patched=" + std::to_string(status.relocations_patched)
            + ";refresh=" + std::to_string(status.refresh_count)
            + ";targets=" + std::to_string(status.target_relocations)
            + ";patchFailures=" + std::to_string(status.patch_failures)
            + ";policyRevision=" + std::to_string(status.policy_revision)
            + ";root=" + status.guest_library_root
            + ";error=" + status.last_error;
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeResetHooks(JNIEnv*, jclass) {
    controlled_sandbox::global_hooks().reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeResetPolicy(JNIEnv*, jclass) {
    controlled_sandbox::global_policy().reset();
}

#include "controlled_sandbox/native_crash.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallCrashRecorder(
        JNIEnv* env, jclass, jstring output_path) {
    const auto policy = controlled_sandbox::global_policy().snapshot();
    return controlled_sandbox::global_crash_recorder().install(
            string_value(env, output_path), controlled_sandbox::NativeCrashContext{
                    policy.session_id, policy.generation, policy.process_name, policy.abi_name})
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeCrashStatus(JNIEnv* env, jclass) {
    const auto status = controlled_sandbox::global_crash_recorder().status();
    std::string value = "installed=" + std::string(status.installed ? "true" : "false")
            + ";altStack=" + std::string(status.alternate_stack_installed ? "true" : "false")
            + ";generation=" + std::to_string(status.generation)
            + ";records=" + std::to_string(status.records_written)
            + ";path=" + status.output_path + ";error=" + status.last_error;
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeNetworkStatus(JNIEnv* env, jclass) {
    try {
        const std::string value = controlled_sandbox::native_network_status_string();
        return env->NewStringUTF(value.c_str());
    } catch (const std::exception& error) {
        const std::string value = std::string("error=") + error.what();
        return env->NewStringUTF(value.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeLoaderStatus(JNIEnv* env, jclass) {
    const auto status = controlled_sandbox::NativeLibraryLoaderPolicy::status();
    const std::string value = "pathValidations=" + std::to_string(status.path_validations)
            + ";fdValidations=" + std::to_string(status.fd_validations)
            + ";relroValidations=" + std::to_string(status.relro_validations)
            + ";denied=" + std::to_string(status.denied_requests)
            + ";error=" + status.last_error;
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeResetCrashRecorder(JNIEnv*, jclass) {
    controlled_sandbox::global_crash_recorder().reset();
}
