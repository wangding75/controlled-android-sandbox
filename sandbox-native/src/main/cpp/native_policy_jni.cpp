#include "controlled_sandbox/native_policy.h"

#include <jni.h>
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

void throw_java(JNIEnv* env, const char* type, const std::string& message) {
    jclass klass = env->FindClass(type);
    if (klass != nullptr) env->ThrowNew(klass, message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeConfigure(
        JNIEnv* env, jclass, jstring session_id, jlong generation,
        jstring package_name, jstring process_name, jint user_id, jint virtual_uid,
        jint virtual_pid, jstring abi_name, jstring instance_root,
        jstring apk_path, jstring native_library_root, jboolean default_allow,
        jobjectArray allow_hosts, jobjectArray deny_hosts,
        jobjectArray allow_cidrs, jobjectArray deny_cidrs) {
    try {
        if (generation <= 0) throw std::invalid_argument("generation must be positive");
        controlled_sandbox::global_policy().configure(
                string_value(env, session_id), static_cast<std::uint64_t>(generation),
                string_value(env, package_name), string_value(env, process_name), user_id,
                virtual_uid, virtual_pid, string_value(env, abi_name), string_value(env, instance_root),
                string_value(env, apk_path), string_value(env, native_library_root), default_allow,
                string_array(env, allow_hosts), string_array(env, deny_hosts),
                cidr_array(env, allow_cidrs), cidr_array(env, deny_cidrs));
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
    return controlled_sandbox::global_crash_recorder().install(string_value(env, output_path))
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeCrashStatus(JNIEnv* env, jclass) {
    const auto status = controlled_sandbox::global_crash_recorder().status();
    std::string value = "installed=" + std::string(status.installed ? "true" : "false")
            + ";path=" + status.output_path + ";error=" + status.last_error;
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_warden_controlledsandbox_nativebridge_NativePolicy_nativeResetCrashRecorder(JNIEnv*, jclass) {
    controlled_sandbox::global_crash_recorder().reset();
}
