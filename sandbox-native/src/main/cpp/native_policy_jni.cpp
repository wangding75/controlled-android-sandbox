#include "controlled_sandbox/native_policy.h"
#include "controlled_sandbox/native_audio.h"
#include "controlled_sandbox/native_camera1.h"
#include "controlled_sandbox/native_interceptors.h"
#include "controlled_sandbox/native_hook.h"
#include "controlled_sandbox/native_loader.h"
#include "controlled_sandbox/native_network.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <cstdint>
#include <cerrno>
#include <cstring>
#include <cmath>
#include <stdexcept>
#include <string>
#include <vector>
#include <sys/syscall.h>
#include <unistd.h>

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

std::vector<std::vector<std::uint8_t>> byte_array_2d(JNIEnv* env, jobjectArray values) {
    std::vector<std::vector<std::uint8_t>> out;
    if (values == nullptr) return out;
    const jsize count = env->GetArrayLength(values);
    if (count < 1 || count > 8) throw std::invalid_argument("camera frame count is invalid");
    out.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; index++) {
        auto* value = static_cast<jbyteArray>(env->GetObjectArrayElement(values, index));
        if (value == nullptr) throw std::invalid_argument("camera frame is null");
        const jsize length = env->GetArrayLength(value);
        if (length <= 0 || length > 16 * 1024 * 1024) {
            env->DeleteLocalRef(value);
            throw std::invalid_argument("camera frame size is invalid");
        }
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) throw std::runtime_error("GetByteArrayRegion failed");
        out.push_back(std::move(bytes));
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

// VMRuntime.setHiddenApiExemptions is an instance native method.  Its second argument is
// the VMRuntime object returned by getRuntime(), not the VMRuntime Class object used for
// lookup.  Keeping the receiver type as jobject makes that contract explicit and prevents
// a translated/native bridge from dereferencing a class object as a VMRuntime instance.
using HiddenApiNative = void (*)(JNIEnv*, jobject, jobjectArray);

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
            "Landroid/companion/",
            "Landroid/content/",
            "Landroid/hardware/",
            "Landroid/media/",
            "Landroid/net/",
            "Landroid/os/",
            "Landroid/print/",
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
    native_method(env, instance, values);
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

bool clear_detached_activity_record(JNIEnv* env, jobject activity) {
    if (activity == nullptr) throw std::invalid_argument("activity is required");

    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (activity_thread == nullptr) throw std::runtime_error("ActivityThread class unavailable");
    const jfieldID current_id = env->GetStaticFieldID(
            activity_thread, "sCurrentActivityThread", "Landroid/app/ActivityThread;");
    if (current_id == nullptr) throw std::runtime_error("ActivityThread current instance unavailable");
    jobject current = env->GetStaticObjectField(activity_thread, current_id);
    if (current == nullptr) throw std::runtime_error("ActivityThread current instance is null");

    const jfieldID activities_id = env->GetFieldID(
            activity_thread, "mActivities", "Landroid/util/ArrayMap;");
    if (activities_id == nullptr) throw std::runtime_error("ActivityThread activity map unavailable");
    jobject activities = env->GetObjectField(current, activities_id);
    if (activities == nullptr) throw std::runtime_error("ActivityThread activity map is null");

    jclass array_map = env->FindClass("android/util/ArrayMap");
    if (array_map == nullptr) throw std::runtime_error("ArrayMap class unavailable");
    const jmethodID map_get = env->GetMethodID(
            array_map, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (map_get == nullptr) {
        env->ExceptionClear();
        throw std::runtime_error("ActivityThread activity lookup unavailable");
    }
    jclass activity_class = env->GetObjectClass(activity);
    const jfieldID activity_token = env->GetFieldID(
            activity_class, "mToken", "Landroid/os/IBinder;");
    if (activity_token == nullptr) throw std::runtime_error("Activity framework token unavailable");
    jobject token = env->GetObjectField(activity, activity_token);
    if (token == nullptr) throw std::runtime_error("Activity framework token is null");
    jobject record = env->CallObjectMethod(activities, map_get, token);
    if (env->ExceptionCheck()) throw std::runtime_error("ActivityThread activity lookup failed");
    if (record == nullptr) throw std::runtime_error("ActivityClientRecord for framework token not found");
    jclass record_class = env->GetObjectClass(record);
    const jfieldID record_activity = env->GetFieldID(
            record_class, "activity", "Landroid/app/Activity;");
    if (record_activity == nullptr) throw std::runtime_error("ActivityClientRecord activity unavailable");
    jobject record_activity_value = env->GetObjectField(record, record_activity);
    if (record_activity_value == nullptr || !env->IsSameObject(record_activity_value, activity)) {
        throw std::runtime_error("ActivityClientRecord framework token points to another Activity");
    }
    const jfieldID record_window = env->GetFieldID(
            record_class, "window", "Landroid/view/Window;");
    if (record_window == nullptr) throw std::runtime_error("ActivityClientRecord window unavailable");
    jobject previous_window = env->GetObjectField(record, record_window);
    if (previous_window == nullptr) throw std::runtime_error("ActivityClientRecord window is already null");
    env->SetObjectField(record, record_window, nullptr);
    if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord window clear failed");
    if (env->GetObjectField(record, record_window) != nullptr) {
        throw std::runtime_error("ActivityClientRecord window clear was not observed");
    }
    const jfieldID preserve = env->GetFieldID(record_class, "mPreserveWindow", "Z");
    jboolean preserve_value = JNI_FALSE;
    if (preserve != nullptr) {
        preserve_value = env->GetBooleanField(record, preserve);
        env->SetBooleanField(record, preserve, JNI_FALSE);
        if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord preserve clear failed");
        if (env->GetBooleanField(record, preserve) != JNI_FALSE) {
            throw std::runtime_error("ActivityClientRecord preserve clear was not observed");
        }
    } else {
        env->ExceptionClear();
    }
    const jfieldID pending_window = env->GetFieldID(
            record_class, "mPendingRemoveWindow", "Landroid/view/Window;");
    if (pending_window == nullptr) {
        throw std::runtime_error("ActivityClientRecord pending window unavailable");
    }
    jobject pending_window_value = env->GetObjectField(record, pending_window);
    env->SetObjectField(record, pending_window, nullptr);
    if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord pending window clear failed");
    const jfieldID pending_manager = env->GetFieldID(
            record_class, "mPendingRemoveWindowManager", "Landroid/view/WindowManager;");
    if (pending_manager == nullptr) {
        throw std::runtime_error("ActivityClientRecord pending manager unavailable");
    }
    env->SetObjectField(record, pending_manager, nullptr);
    if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord pending manager clear failed");
    const jmethodID map_size = env->GetMethodID(array_map, "size", "()I");
    const jmethodID map_value_at = env->GetMethodID(
            array_map, "valueAt", "(I)Ljava/lang/Object;");
    if (map_size == nullptr || map_value_at == nullptr) {
        throw std::runtime_error("ActivityThread activity map scan unavailable");
    }
    jint records_cleared = 1;
    const jint activity_count = env->CallIntMethod(activities, map_size);
    if (env->ExceptionCheck()) throw std::runtime_error("ActivityThread activity map size failed");
    for (jint index = 0; index < activity_count; index++) {
        jobject candidate = env->CallObjectMethod(activities, map_value_at, index);
        if (env->ExceptionCheck()) throw std::runtime_error("ActivityThread activity map scan failed");
        if (candidate == nullptr || env->IsSameObject(candidate, record)) continue;
        jobject candidate_activity = env->GetObjectField(candidate, record_activity);
        if (candidate_activity == nullptr || !env->IsSameObject(candidate_activity, activity)) continue;
        jobject candidate_window = env->GetObjectField(candidate, record_window);
        if (candidate_window != nullptr) {
            env->SetObjectField(candidate, record_window, nullptr);
            if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord replacement window clear failed");
        }
        if (preserve != nullptr) {
            env->SetBooleanField(candidate, preserve, JNI_FALSE);
            if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord replacement preserve clear failed");
        }
        env->SetObjectField(candidate, pending_window, nullptr);
        if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord replacement pending window clear failed");
        env->SetObjectField(candidate, pending_manager, nullptr);
        if (env->ExceptionCheck()) throw std::runtime_error("ActivityClientRecord replacement pending manager clear failed");
        records_cleared++;
    }
    const jfieldID window_added = env->GetFieldID(
            activity_class, "mWindowAdded", "Z");
    if (window_added == nullptr) throw std::runtime_error("Activity window marker unavailable");
    env->SetBooleanField(activity, window_added, JNI_FALSE);
    if (env->ExceptionCheck()) throw std::runtime_error("Activity window marker clear failed");
    if (env->GetBooleanField(activity, window_added) != JNI_FALSE) {
        throw std::runtime_error("Activity window marker clear was not observed");
    }
    const jfieldID started_activity = env->GetFieldID(activity_class, "mStartedActivity", "Z");
    const jfieldID visible_from_client = env->GetFieldID(activity_class, "mVisibleFromClient", "Z");
    const jfieldID visible_from_server = env->GetFieldID(activity_class, "mVisibleFromServer", "Z");
    const jfieldID finished = env->GetFieldID(activity_class, "mFinished", "Z");
    if (started_activity == nullptr || visible_from_client == nullptr
            || visible_from_server == nullptr || finished == nullptr) {
        throw std::runtime_error("Activity visibility state unavailable");
    }
    const jfieldID hide_for_now = env->GetFieldID(record_class, "hideForNow", "Z");
    if (hide_for_now == nullptr) throw std::runtime_error("ActivityClientRecord visibility state unavailable");
    __android_log_print(ANDROID_LOG_INFO, "CS_WINDOW_RECORD",
            "cleared=1 records=%d preserveWindow=%s pendingRemoveWindow=%s started=%d visibleClient=%d visibleServer=%d finished=%d hideForNow=%d token=%p",
            records_cleared,
            preserve_value == JNI_FALSE ? "0" : "1",
            pending_window_value == nullptr ? "0" : "1",
            env->GetBooleanField(activity, started_activity),
            env->GetBooleanField(activity, visible_from_client),
            env->GetBooleanField(activity, visible_from_server),
            env->GetBooleanField(activity, finished),
            env->GetBooleanField(record, hide_for_now), token);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallHiddenApiBridge(
        JNIEnv* env, jclass) {
    return install_hidden_api_bridge(env) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeClearDetachedActivityRecord(
        JNIEnv* env, jclass, jobject activity) {
    try {
        return clear_detached_activity_record(env, activity) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return JNI_FALSE;
    }
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeConfigureFileCapabilities(
        JNIEnv* env, jclass, jint data_root_fd, jint apk_parent_fd, jstring apk_entry_name,
        jint native_library_fd) {
    try {
        controlled_sandbox::global_policy().configure_file_capabilities(
                data_root_fd, apk_parent_fd, string_value(env, apk_entry_name), native_library_fd);
        return JNI_TRUE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeOpenCapability(
        JNIEnv* env, jclass, jint directory_fd, jstring entry_name, jboolean write) {
    try {
        const std::string entry = string_value(env, entry_name);
        if (directory_fd < 0 || entry.empty() || entry == "." || entry == ".."
                || entry.find('/') != std::string::npos || entry.find('\\') != std::string::npos
                || entry.find('\0') != std::string::npos) {
            throw std::invalid_argument("capability entry is invalid");
        }
        const int flags = (write == JNI_TRUE ? O_RDWR : O_RDONLY) | O_CLOEXEC;
        const long descriptor = syscall(SYS_openat, directory_fd, entry.c_str(), flags, 0);
        if (descriptor < 0) {
            throw std::runtime_error("capability openat failed errno=" + std::to_string(errno));
        }
        return static_cast<jint>(descriptor);
    } catch (const std::exception& error) {
        throw_java(env, "java/io/IOException", error.what());
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeMaterializeCapabilityFile(
        JNIEnv* env, jclass, jint source_fd) {
    try {
        if (source_fd < 0) throw std::invalid_argument("source capability fd is invalid");
#ifndef SYS_memfd_create
        throw std::runtime_error("memfd_create is unavailable");
#else
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
        const long target = syscall(SYS_memfd_create, "cas-isolated-apk", MFD_CLOEXEC);
        if (target < 0) {
            throw std::runtime_error("memfd_create failed errno=" + std::to_string(errno));
        }
        std::vector<std::uint8_t> buffer(64U * 1024U);
        std::int64_t offset = 0;
        for (;;) {
            const long read = syscall(SYS_pread64, source_fd, buffer.data(), buffer.size(), offset);
            if (read < 0) {
                const int saved = errno;
                syscall(SYS_close, target);
                throw std::runtime_error("capability pread failed errno=" + std::to_string(saved));
            }
            if (read == 0) break;
            std::size_t written = 0;
            while (written < static_cast<std::size_t>(read)) {
                const long count = syscall(SYS_write, target, buffer.data() + written,
                        static_cast<std::size_t>(read) - written);
                if (count <= 0) {
                    const int saved = errno;
                    syscall(SYS_close, target);
                    throw std::runtime_error("capability memfd write failed errno="
                            + std::to_string(saved));
                }
                written += static_cast<std::size_t>(count);
            }
            offset += read;
        }
        syscall(SYS_lseek, target, 0, SEEK_SET);
        return static_cast<jint>(target);
#endif
    } catch (const std::exception& error) {
        throw_java(env, "java/io/IOException", error.what());
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeCreateProcessLocalFile(
        JNIEnv* env, jclass, jstring name) {
    try {
#ifndef SYS_memfd_create
        throw std::runtime_error("memfd_create is unavailable");
#else
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
        const std::string requested = string_value(env, name);
        if (requested.empty() || requested.size() > 64 || requested.find('/') != std::string::npos
                || requested.find('\\') != std::string::npos) {
            throw std::invalid_argument("process-local file name is invalid");
        }
        const long descriptor = syscall(SYS_memfd_create, requested.c_str(), MFD_CLOEXEC);
        if (descriptor < 0) {
            throw std::runtime_error("memfd_create failed errno=" + std::to_string(errno));
        }
        return static_cast<jint>(descriptor);
#endif
    } catch (const std::exception& error) {
        throw_java(env, "java/io/IOException", error.what());
        return -1;
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
#include "controlled_sandbox/native_interceptors.h"
#include "controlled_sandbox/native_jni_exception_probe.h"

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
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallSystemIoHooks(
        JNIEnv* env, jclass) {
    try {
        return controlled_sandbox::global_hooks().install_system_io() ? JNI_TRUE : JNI_FALSE;
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
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeSetGuestProcessExitAllowed(
        JNIEnv*, jclass, jboolean allowed) {
    controlled_sandbox::set_guest_process_exit_allowed(allowed == JNI_TRUE);
}

namespace {

using NativeLoadStatic3 = jstring (*)(JNIEnv*, jclass, jstring, jobject, jobject);
using NativeLoadStatic2 = jstring (*)(JNIEnv*, jclass, jstring, jobject);
using NativeLoadInstance3 = jstring (*)(JNIEnv*, jobject, jstring, jobject, jobject);
using NativeLoadInstance2 = jstring (*)(JNIEnv*, jobject, jstring, jobject);

NativeLoadStatic3 orig_native_load_static3 = nullptr;
NativeLoadStatic2 orig_native_load_static2 = nullptr;
NativeLoadInstance3 orig_native_load_instance3 = nullptr;
NativeLoadInstance2 orig_native_load_instance2 = nullptr;
bool native_load_diag_installed = false;
bool native_load_redirect_installed = false;

struct LoadedSymbolLookup {
    const char* symbol = nullptr;
    void* address = nullptr;
};

std::uint32_t gnu_hash(const char* name) {
    std::uint32_t hash = 5381;
    for (const unsigned char* cursor = reinterpret_cast<const unsigned char*>(name);
         *cursor != '\0'; cursor++) {
        hash = (hash * 33) + *cursor;
    }
    return hash;
}

bool find_gnu_hash_symbol(const link_map* map, const ElfW(Dyn)* dynamic,
                          const char* symbol, void** result) {
    const ElfW(Addr) load_bias = static_cast<ElfW(Addr)>(map->l_addr);
    const std::uint32_t* hash_table = nullptr;
    const ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    for (const ElfW(Dyn)* entry = dynamic; entry->d_tag != DT_NULL; entry++) {
        if (entry->d_tag == DT_SYMTAB) symbols = reinterpret_cast<const ElfW(Sym)*>(load_bias + entry->d_un.d_ptr);
        if (entry->d_tag == DT_STRTAB) strings = reinterpret_cast<const char*>(load_bias + entry->d_un.d_ptr);
        if (entry->d_tag == DT_GNU_HASH) hash_table = reinterpret_cast<const std::uint32_t*>(load_bias + entry->d_un.d_ptr);
    }
    if (hash_table == nullptr || symbols == nullptr || strings == nullptr) return false;
    const std::uint32_t bucket_count = hash_table[0];
    const std::uint32_t symbol_offset = hash_table[1];
    const std::uint32_t bloom_count = hash_table[2];
    const std::uint32_t bloom_shift = hash_table[3];
    if (bucket_count == 0 || bloom_count == 0) return false;
    const auto* bloom = reinterpret_cast<const ElfW(Addr)*>(hash_table + 4);
    const auto* buckets = reinterpret_cast<const std::uint32_t*>(bloom + bloom_count);
    const auto* chains = buckets + bucket_count;
    const std::uint32_t hash = gnu_hash(symbol);
    const ElfW(Addr) word = bloom[(hash / (sizeof(ElfW(Addr)) * 8)) % bloom_count];
    const ElfW(Addr) mask = (static_cast<ElfW(Addr)>(1) << (hash % (sizeof(ElfW(Addr)) * 8)))
            | (static_cast<ElfW(Addr)>(1) << ((hash >> bloom_shift) % (sizeof(ElfW(Addr)) * 8)));
    if ((word & mask) != mask) return false;
    std::uint32_t index = buckets[hash % bucket_count];
    if (index < symbol_offset) return false;
    for (;;) {
        const std::uint32_t chain_hash = chains[index - symbol_offset];
        if ((chain_hash | 1U) == (hash | 1U)
                && std::strcmp(strings + symbols[index].st_name, symbol) == 0
                && symbols[index].st_shndx != SHN_UNDEF) {
            *result = reinterpret_cast<void*>(load_bias + symbols[index].st_value);
            return *result != nullptr;
        }
        if ((chain_hash & 1U) != 0) break;
        index++;
    }
    return false;
}

int find_loaded_openjdk_symbol(struct dl_phdr_info* info, size_t, void* opaque) {
    auto* lookup = static_cast<LoadedSymbolLookup*>(opaque);
    if (info == nullptr || info->dlpi_name == nullptr || lookup == nullptr
            || std::strstr(info->dlpi_name, "libopenjdk.so") == nullptr) return 0;
    for (int index = 0; index < info->dlpi_phnum; index++) {
        const ElfW(Phdr)& header = info->dlpi_phdr[index];
        if (header.p_type != PT_DYNAMIC) continue;
        const auto* dynamic = reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr + header.p_vaddr);
        link_map map{};
        map.l_addr = info->dlpi_addr;
        if (find_gnu_hash_symbol(&map, dynamic, lookup->symbol, &lookup->address)) return 1;
    }
    return 0;
}

void* lookup_native_load(const char* symbol) {
    if (symbol == nullptr) return nullptr;
    dlerror();
    void* value = dlsym(RTLD_DEFAULT, symbol);
    if (value != nullptr) return value;
    (void) dlerror();

    LoadedSymbolLookup loaded{symbol, nullptr};
    dl_iterate_phdr(find_loaded_openjdk_symbol, &loaded);
    if (loaded.address != nullptr) return loaded.address;

    // Runtime.nativeLoad is registered by libopenjdk through JNI_OnLoad.  On API 32
    // the exported symbol is Runtime_nativeLoad, but the APEX linker namespace does
    // not always expose it through RTLD_DEFAULT to an app-owned JNI library.  Probe
    // the loaded/openjdk handles explicitly before giving up.  This is only a symbol
    // lookup; no ArtMethod entry point or access flags are modified.
    constexpr const char* kOpenJdkLibraries[] = {
            "libopenjdk.so",
            "/apex/com.android.art/lib64/libopenjdk.so",
            "/system/lib64/libopenjdk.so",
    };
    for (const char* library : kOpenJdkLibraries) {
        void* handle = dlopen(library, RTLD_NOW | RTLD_NOLOAD);
        if (handle == nullptr) handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) continue;
        value = dlsym(handle, symbol);
        if (value != nullptr) return value;
    }
    __android_log_print(ANDROID_LOG_WARN, "CS_NATIVE_BIND",
            "nativeLoad symbol unavailable symbol=%s", symbol);
    return nullptr;
}

void report_native_load(JNIEnv* env, jstring filename, jobject loader, jobject caller) {
    if (env == nullptr) return;
    jclass diagnostic = env->FindClass(
            "com/warden/controlledsandbox/runtime/guest/GuestNativeBindingDiagnostic");
    if (diagnostic == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID record = env->GetStaticMethodID(diagnostic, "recordNativeLoad",
            "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/ClassLoader;Ljava/lang/String;)V");
    if (record == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(diagnostic);
        return;
    }
    env->CallStaticVoidMethod(diagnostic, record, filename, caller, loader, filename);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(diagnostic);
}

jstring diag_native_load_static3(JNIEnv* env, jclass clazz, jstring filename, jobject loader,
                                 jobject caller) {
    report_native_load(env, filename, loader, caller);
    if (orig_native_load_static3 == nullptr) return nullptr;
    return orig_native_load_static3(env, clazz, filename, loader, caller);
}

jstring diag_native_load_static2(JNIEnv* env, jclass clazz, jstring filename, jobject loader) {
    report_native_load(env, filename, loader, nullptr);
    if (orig_native_load_static2 == nullptr) return nullptr;
    return orig_native_load_static2(env, clazz, filename, loader);
}

jstring diag_native_load_instance3(JNIEnv* env, jobject runtime, jstring filename, jobject loader,
                                   jobject caller) {
    report_native_load(env, filename, loader, caller);
    if (orig_native_load_instance3 == nullptr) return nullptr;
    return orig_native_load_instance3(env, runtime, filename, loader, caller);
}

jstring diag_native_load_instance2(JNIEnv* env, jobject runtime, jstring filename, jobject loader) {
    report_native_load(env, filename, loader, nullptr);
    if (orig_native_load_instance2 == nullptr) return nullptr;
    return orig_native_load_instance2(env, runtime, filename, loader);
}

jstring redirect_native_load_argument(JNIEnv* env, jstring filename) {
    if (env == nullptr || filename == nullptr
            || !controlled_sandbox::global_policy().configured()) {
        return filename;
    }
    const char* chars = env->GetStringUTFChars(filename, nullptr);
    if (chars == nullptr) return filename;
    std::string original(chars);
    env->ReleaseStringUTFChars(filename, chars);
    if (original.empty() || original.front() != '/') return filename;
    try {
        const std::string mapped = controlled_sandbox::global_policy().map_path(original);
        if (mapped == original) return filename;
        jstring replacement = env->NewStringUTF(mapped.c_str());
        if (replacement != nullptr) {
            __android_log_print(ANDROID_LOG_DEBUG, "CS_NATIVE_BIND",
                    "translated nativeLoad path=%s -> %s", original.c_str(), mapped.c_str());
            return replacement;
        }
    } catch (const controlled_sandbox::PathPolicyError& error) {
        // Runtime.nativeLoad returns a non-null error string to its Java caller. Returning the
        // policy error preserves that contract and fails closed without invoking the linker.
        return env->NewStringUTF(error.what());
    } catch (const std::exception& error) {
        return env->NewStringUTF(error.what());
    }
    return filename;
}

jstring redirect_native_load_static3(JNIEnv* env, jclass clazz, jstring filename, jobject loader,
                                     jobject caller) {
    jstring mapped = redirect_native_load_argument(env, filename);
    if (orig_native_load_static3 == nullptr) return nullptr;
    jstring result = orig_native_load_static3(env, clazz, mapped, loader, caller);
    if (mapped != filename && mapped != nullptr) env->DeleteLocalRef(mapped);
    return result;
}

jstring redirect_native_load_static2(JNIEnv* env, jclass clazz, jstring filename, jobject loader) {
    jstring mapped = redirect_native_load_argument(env, filename);
    if (orig_native_load_static2 == nullptr) return nullptr;
    jstring result = orig_native_load_static2(env, clazz, mapped, loader);
    if (mapped != filename && mapped != nullptr) env->DeleteLocalRef(mapped);
    return result;
}

jstring redirect_native_load_instance3(JNIEnv* env, jobject runtime, jstring filename,
                                       jobject loader, jobject caller) {
    jstring mapped = redirect_native_load_argument(env, filename);
    if (orig_native_load_instance3 == nullptr) return nullptr;
    jstring result = orig_native_load_instance3(env, runtime, mapped, loader, caller);
    if (mapped != filename && mapped != nullptr) env->DeleteLocalRef(mapped);
    return result;
}

jstring redirect_native_load_instance2(JNIEnv* env, jobject runtime, jstring filename,
                                       jobject loader) {
    jstring mapped = redirect_native_load_argument(env, filename);
    if (orig_native_load_instance2 == nullptr) return nullptr;
    jstring result = orig_native_load_instance2(env, runtime, mapped, loader);
    if (mapped != filename && mapped != nullptr) env->DeleteLocalRef(mapped);
    return result;
}

bool install_one_native_load(JNIEnv* env, jclass runtime, const char* signature, void* replacement,
                             void** original_slot, const char* symbol_a, const char* symbol_b) {
    if (env->GetStaticMethodID(runtime, "nativeLoad", signature) != nullptr) {
        *original_slot = lookup_native_load(symbol_a);
        if (*original_slot == nullptr) *original_slot = lookup_native_load(symbol_b);
        if (*original_slot == nullptr) {
            env->ExceptionClear();
            return false;
        }
        JNINativeMethod method{"nativeLoad", signature, replacement};
        const bool ok = env->RegisterNatives(runtime, &method, 1) == 0;
        if (!ok) env->ExceptionClear();
        return ok;
    }
    env->ExceptionClear();
    if (env->GetMethodID(runtime, "nativeLoad", signature) != nullptr) {
        *original_slot = lookup_native_load(symbol_a);
        if (*original_slot == nullptr) *original_slot = lookup_native_load(symbol_b);
        if (*original_slot == nullptr) return false;
        JNINativeMethod method{"nativeLoad", signature, replacement};
        const bool ok = env->RegisterNatives(runtime, &method, 1) == 0;
        if (!ok) env->ExceptionClear();
        return ok;
    }
    env->ExceptionClear();
    return false;
}

// Hidden-api policy may deny GetMethodID even after the process-local exemption bridge has
// succeeded. RegisterNatives is the supported JNI registration boundary and only needs the
// class/name/signature tuple; it does not require reflective lookup of the hidden method.
bool register_native_load_unchecked(JNIEnv* env, jclass runtime, const char* signature,
                                    void* replacement, void** original_slot,
                                    const char* symbol_a, const char* symbol_b) {
    *original_slot = lookup_native_load(symbol_a);
    if (*original_slot == nullptr) *original_slot = lookup_native_load(symbol_b);
    if (*original_slot == nullptr) return false;
    JNINativeMethod method{"nativeLoad", signature, replacement};
    const bool ok = env->RegisterNatives(runtime, &method, 1) == 0;
    if (!ok) env->ExceptionClear();
    return ok;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallJniPendingExceptionProbe(
        JNIEnv* env, jclass) {
    return controlled_sandbox::install_jni_pending_exception_probe(env) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallNativeLoadDiagnostic(
        JNIEnv* env, jclass) {
    if (native_load_diag_installed) return JNI_TRUE;
    jclass runtime = env->FindClass("java/lang/Runtime");
    if (runtime == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    bool installed = install_one_native_load(env, runtime,
            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",
            reinterpret_cast<void*>(&diag_native_load_static3),
            reinterpret_cast<void**>(&orig_native_load_static3),
            "Runtime_nativeLoad",
            "Java_java_lang_Runtime_nativeLoad");
    if (!installed) {
        installed = install_one_native_load(env, runtime,
                "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",
                reinterpret_cast<void*>(&diag_native_load_instance3),
                reinterpret_cast<void**>(&orig_native_load_instance3),
                "Runtime_nativeLoad",
                "Java_java_lang_Runtime_nativeLoad");
    }
    if (!installed) {
        installed = install_one_native_load(env, runtime,
                "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",
                reinterpret_cast<void*>(&diag_native_load_static2),
                reinterpret_cast<void**>(&orig_native_load_static2),
                "Runtime_nativeLoad",
                "Java_java_lang_Runtime_nativeLoad");
    }
    if (!installed) {
        installed = install_one_native_load(env, runtime,
                "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",
                reinterpret_cast<void*>(&diag_native_load_instance2),
                reinterpret_cast<void**>(&orig_native_load_instance2),
                "Runtime_nativeLoad",
                "Java_java_lang_Runtime_nativeLoad");
    }
    env->DeleteLocalRef(runtime);
    native_load_diag_installed = installed;
    __android_log_print(ANDROID_LOG_INFO, "CS_NATIVE_BIND",
            "PROBE nativeLoadWrap installed=%d", installed ? 1 : 0);
    return installed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallNativeLoadRedirect(
        JNIEnv* env, jclass) {
    if (native_load_redirect_installed) return JNI_TRUE;
    jclass runtime = env->FindClass("java/lang/Runtime");
    if (runtime == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    bool installed = register_native_load_unchecked(env, runtime,
            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",
            reinterpret_cast<void*>(&redirect_native_load_static3),
            reinterpret_cast<void**>(&orig_native_load_static3),
            "Runtime_nativeLoad",
            "Java_java_lang_Runtime_nativeLoad");
    if (!installed) {
        installed = register_native_load_unchecked(env, runtime,
                "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",
                reinterpret_cast<void*>(&redirect_native_load_instance3),
                reinterpret_cast<void**>(&orig_native_load_instance3),
                "Runtime_nativeLoad",
                "Java_java_lang_Runtime_nativeLoad");
    }
    if (!installed) {
        installed = register_native_load_unchecked(env, runtime,
                "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",
                reinterpret_cast<void*>(&redirect_native_load_static2),
                reinterpret_cast<void**>(&orig_native_load_static2),
                "Runtime_nativeLoad",
                "Java_java_lang_Runtime_nativeLoad");
    }
    if (!installed) {
        installed = register_native_load_unchecked(env, runtime,
                "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",
                reinterpret_cast<void*>(&redirect_native_load_instance2),
                reinterpret_cast<void**>(&orig_native_load_instance2),
                "Runtime_nativeLoad",
                "Java_java_lang_Runtime_nativeLoad");
    }
    env->DeleteLocalRef(runtime);
    native_load_redirect_installed = installed;
    __android_log_print(ANDROID_LOG_INFO, "CS_NATIVE_BIND",
            "translated nativeLoadRedirect installed=%d", installed ? 1 : 0);
    return installed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeInstallCamera1Adapter(
        JNIEnv*, jclass) {
    return controlled_sandbox::global_hooks().installCamera1() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeConfigureCamera1Identity(
        JNIEnv* env, jclass, jstring guest_package, jint virtual_uid,
        jstring host_package, jint host_uid, jboolean virtual_camera,
        jboolean allow_open, jboolean replace_preview, jboolean replace_capture) {
    try {
        return controlled_sandbox::global_camera1_adapter().configure_identity(
                string_value(env, guest_package), virtual_uid, string_value(env, host_package),
                host_uid, virtual_camera == JNI_TRUE, allow_open == JNI_TRUE,
                replace_preview == JNI_TRUE, replace_capture == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeConfigureCamera1Frames(
        JNIEnv* env, jclass, jstring source_kind, jstring source_sha256,
        jint width, jint height, jobjectArray preview_frames, jobjectArray capture_frames) {
    try {
        return controlled_sandbox::global_camera1_adapter().configure_frames(
                byte_array_2d(env, preview_frames), byte_array_2d(env, capture_frames),
                string_value(env, source_kind), string_value(env, source_sha256), width, height)
                ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeCamera1Status(
        JNIEnv* env, jclass) {
    const auto status = controlled_sandbox::global_camera1_adapter().status();
    std::string value = "symbolsReady=" + std::string(status.symbols_ready ? "true" : "false")
            + ";hooksReady=" + std::string(status.hooks_ready ? "true" : "false")
            + ";identityConfigured=" + std::string(status.identity_configured ? "true" : "false")
            + ";virtualCamera=" + std::string(status.virtual_camera ? "true" : "false")
            + ";allowOpen=" + std::string(status.allow_open ? "true" : "false")
            + ";replacePreview=" + std::string(status.replace_preview ? "true" : "false")
            + ";replaceCapture=" + std::string(status.replace_capture ? "true" : "false")
            + ";previewFrames=" + std::to_string(status.preview_frames)
            + ";captureFrames=" + std::to_string(status.capture_frames)
            + ";previewDeliveries=" + std::to_string(status.preview_deliveries)
            + ";captureDeliveries=" + std::to_string(status.capture_deliveries)
            + ";contexts=" + std::to_string(status.context_bindings)
            + ";hookTargets=" + std::to_string(status.hook_targets)
            + ";hookPatched=" + std::to_string(status.hook_patched)
            + ";hookFailures=" + std::to_string(status.hook_failures)
            + ";guest=" + status.guest_package + ";host=" + status.host_package
            + ";virtualUid=" + std::to_string(status.virtual_uid)
            + ";hostUid=" + std::to_string(status.host_uid)
            + ";error=" + status.last_error;
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeResetCamera1(
        JNIEnv* env, jclass) {
    controlled_sandbox::global_camera1_adapter().reset(env);
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

extern "C" JNIEXPORT jint JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeQueueJpeg(
        JNIEnv* env, jclass, jobject surface, jbyteArray jpeg) {
    // ImageReader's JPEG consumer accepts the platform's RGBA-to-BLOB compatibility path on
    // API 29+.  The buffer must be one row and carry the camera3 JPEG transport footer; writing
    // a normal Canvas frame here produces an RGBA buffer with a JPEG reader contract and causes
    // ImageReader#getPlanes() to abort in native code.
    if (surface == nullptr || jpeg == nullptr) return -1;
    const jsize length = env->GetArrayLength(jpeg);
    if (length <= 0) return -2;
    constexpr std::size_t kJpegBlobBytes = 8;
    const std::size_t required = static_cast<std::size_t>(length) + kJpegBlobBytes;
    const std::size_t pixels = (required + 3U) / 4U;
    const std::size_t side = static_cast<std::size_t>(std::ceil(std::sqrt(
            static_cast<double>(pixels))));
    if (side == 0 || side > static_cast<std::size_t>(INT32_MAX)) return -3;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return -4;
    int result = ANativeWindow_setBuffersGeometry(window, static_cast<int32_t>(side),
            static_cast<int32_t>(side),
            AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    if (result != 0) {
        ANativeWindow_release(window);
        return result;
    }

    jbyte* source = env->GetByteArrayElements(jpeg, nullptr);
    if (source == nullptr) {
        ANativeWindow_release(window);
        return -5;
    }
    ANativeWindow_Buffer buffer{};
    ARect dirty{0, 0, static_cast<int32_t>(side), static_cast<int32_t>(side)};
    result = ANativeWindow_lock(window, &buffer, &dirty);
    if (result == 0) {
        constexpr int32_t kRgbaBytes = 4;
        // Android's RGBA-to-BLOB reader path measures the payload as the first row plus
        // subsequent stride rows, not simply width*height.  Keep the footer at that exact
        // transport boundary so ImageReader can find the JPEG size.
        const std::size_t rowBytes = (static_cast<std::size_t>(buffer.width)
                + static_cast<std::size_t>(buffer.stride)
                * static_cast<std::size_t>(buffer.height - 1))
                * static_cast<std::size_t>(kRgbaBytes);
        const std::size_t capacity = static_cast<std::size_t>(buffer.stride)
                * static_cast<std::size_t>(buffer.height)
                * static_cast<std::size_t>(kRgbaBytes);
        if (buffer.bits == nullptr || buffer.height != buffer.width || rowBytes < required
                || capacity < required) {
            result = -6;
        } else {
            std::memset(buffer.bits, 0, capacity);
            std::memcpy(buffer.bits, source, static_cast<std::size_t>(length));
            // camera3_jpeg_blob_t is { uint16_t id; uint16_t padding; uint32_t size }.
            auto* footer = static_cast<std::uint8_t*>(buffer.bits) + rowBytes - kJpegBlobBytes;
            footer[0] = 0xFF;
            footer[1] = 0x00;
            footer[2] = 0x00;
            footer[3] = 0x00;
            const std::uint32_t jpegSize = static_cast<std::uint32_t>(length);
            std::memcpy(footer + 4, &jpegSize, sizeof(jpegSize));
        }
        const int unlockResult = ANativeWindow_unlockAndPost(window);
        if (result == 0) result = unlockResult;
    }
    env->ReleaseByteArrayElements(jpeg, source, JNI_ABORT);
    ANativeWindow_release(window);
    return result;
}
