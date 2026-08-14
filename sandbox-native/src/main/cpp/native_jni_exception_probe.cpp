#include "controlled_sandbox/native_jni_exception_probe.h"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>

namespace controlled_sandbox {
namespace {

constexpr const char* kTag = "CS_JNI_EX";

using ExceptionCheckFn = jboolean (*)(JNIEnv*);
using ExceptionOccurredFn = jthrowable (*)(JNIEnv*);
using ExceptionClearFn = void (*)(JNIEnv*);
using ThrowFn = jint (*)(JNIEnv*, jthrowable);
using GetEnvFn = jint (*)(JavaVM*, void**, jint);
using AttachFn = jint (*)(JavaVM*, JNIEnv**, void*);

std::atomic<bool> installed{false};
ExceptionCheckFn orig_check = nullptr;
ExceptionOccurredFn orig_occurred = nullptr;
ExceptionClearFn orig_clear = nullptr;
ThrowFn orig_throw = nullptr;
thread_local bool in_probe = false;
std::atomic<uintptr_t> last_throwable{0};

pid_t current_tid() {
    return static_cast<pid_t>(syscall(SYS_gettid));
}

std::string jstring_to_utf(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

void append_native_callers(std::string& out) {
    void* pcs[] = {
            __builtin_extract_return_addr(__builtin_return_address(0)),
    };
    out += " nativeCallers=";
    for (void* pc : pcs) {
        if (pc == nullptr) continue;
        Dl_info info{};
        if (dladdr(pc, &info) != 0 && info.dli_fname != nullptr) {
            out += info.dli_fname;
            if (info.dli_sname != nullptr) {
                out += "(";
                out += info.dli_sname;
                out += ")";
            }
            out += ";";
        }
    }
}

void report_pending(JNIEnv* env, const char* via) {
    if (env == nullptr || orig_occurred == nullptr || orig_clear == nullptr || orig_throw == nullptr) {
        return;
    }
    jthrowable pending = orig_occurred(env);
    if (pending == nullptr) return;
    const auto key = reinterpret_cast<uintptr_t>(pending);
    if (last_throwable.exchange(key) == key) return;

    orig_clear(env);

    char thread_name[32]{};
    pthread_getname_np(pthread_self(), thread_name, sizeof(thread_name));
    const pid_t tid = current_tid();

    jclass throwable_type = env->GetObjectClass(pending);
    jclass class_type = env->FindClass("java/lang/Class");
    jclass ste_type = env->FindClass("java/lang/StackTraceElement");
    std::string exception_class = "unknown";
    std::string message;
    std::string stack;
    std::string top_method;
    std::string declaring_class;
    if (throwable_type != nullptr && class_type != nullptr) {
        jmethodID get_name = env->GetMethodID(class_type, "getName", "()Ljava/lang/String;");
        jmethodID get_message = env->GetMethodID(throwable_type, "getMessage", "()Ljava/lang/String;");
        jmethodID get_stack = env->GetMethodID(throwable_type, "getStackTrace",
                "()[Ljava/lang/StackTraceElement;");
        if (get_name != nullptr) {
            auto* name = static_cast<jstring>(env->CallObjectMethod(throwable_type, get_name));
            exception_class = jstring_to_utf(env, name);
            if (name != nullptr) env->DeleteLocalRef(name);
        }
        if (get_message != nullptr) {
            auto* text = static_cast<jstring>(env->CallObjectMethod(pending, get_message));
            message = jstring_to_utf(env, text);
            if (text != nullptr) env->DeleteLocalRef(text);
        }
        if (get_stack != nullptr && ste_type != nullptr) {
            jmethodID ste_class = env->GetMethodID(ste_type, "getClassName", "()Ljava/lang/String;");
            jmethodID ste_method = env->GetMethodID(ste_type, "getMethodName", "()Ljava/lang/String;");
            jmethodID ste_file = env->GetMethodID(ste_type, "getFileName", "()Ljava/lang/String;");
            jmethodID ste_line = env->GetMethodID(ste_type, "getLineNumber", "()I");
            jmethodID ste_native = env->GetMethodID(ste_type, "isNativeMethod", "()Z");
            auto* frames = static_cast<jobjectArray>(env->CallObjectMethod(pending, get_stack));
            if (frames != nullptr && ste_class != nullptr && ste_method != nullptr) {
                const jsize count = env->GetArrayLength(frames);
                for (jsize index = 0; index < count && index < 24; index++) {
                    jobject frame = env->GetObjectArrayElement(frames, index);
                    if (frame == nullptr) continue;
                    auto* cls = static_cast<jstring>(env->CallObjectMethod(frame, ste_class));
                    auto* method = static_cast<jstring>(env->CallObjectMethod(frame, ste_method));
                    auto* file = ste_file == nullptr ? nullptr
                            : static_cast<jstring>(env->CallObjectMethod(frame, ste_file));
                    const int line = ste_line == nullptr ? -1 : env->CallIntMethod(frame, ste_line);
                    const bool native = ste_native != nullptr
                            && env->CallBooleanMethod(frame, ste_native) == JNI_TRUE;
                    std::string class_name = jstring_to_utf(env, cls);
                    std::string method_name = jstring_to_utf(env, method);
                    if (index == 0) {
                        declaring_class = class_name;
                        top_method = class_name + "." + method_name + (native ? "(native)" : "()");
                    }
                    stack += class_name;
                    stack += ".";
                    stack += method_name;
                    stack += native ? "(native)" : "";
                    if (file != nullptr) {
                        stack += "(";
                        stack += jstring_to_utf(env, file);
                        stack += ":";
                        stack += std::to_string(line);
                        stack += ")";
                    }
                    stack += " <- ";
                    if (cls != nullptr) env->DeleteLocalRef(cls);
                    if (method != nullptr) env->DeleteLocalRef(method);
                    if (file != nullptr) env->DeleteLocalRef(file);
                    env->DeleteLocalRef(frame);
                }
                env->DeleteLocalRef(frames);
            }
        }
    }
    if (env->ExceptionCheck()) orig_clear(env);

    std::string defining;
    if (!declaring_class.empty()) {
        jclass diagnostic = env->FindClass(
                "com/warden/controlledsandbox/runtime/guest/GuestNativeBindingDiagnostic");
        if (diagnostic != nullptr) {
            jmethodID describe = env->GetStaticMethodID(diagnostic, "describeNamedClassLoader",
                    "(Ljava/lang/String;)Ljava/lang/String;");
            if (describe != nullptr) {
                jstring name = env->NewStringUTF(declaring_class.c_str());
                auto* described = static_cast<jstring>(
                        env->CallStaticObjectMethod(diagnostic, describe, name));
                defining = jstring_to_utf(env, described);
                if (name != nullptr) env->DeleteLocalRef(name);
                if (described != nullptr) env->DeleteLocalRef(described);
            } else {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(diagnostic);
        } else {
            env->ExceptionClear();
        }
    }
    if (env->ExceptionCheck()) orig_clear(env);

    std::string native_so;
    append_native_callers(native_so);

    __android_log_print(ANDROID_LOG_ERROR, kTag,
            "PENDING via=%s tid=%d thread=%s class=%s message=%s top=%s declaring=%s defining=%s%s stack=%s",
            via == nullptr ? "" : via,
            static_cast<int>(tid),
            thread_name,
            exception_class.c_str(),
            message.c_str(),
            top_method.c_str(),
            declaring_class.c_str(),
            defining.c_str(),
            native_so.c_str(),
            stack.c_str());

    jclass diagnostic = env->FindClass(
            "com/warden/controlledsandbox/runtime/guest/GuestNativeBindingDiagnostic");
    if (diagnostic != nullptr) {
        jmethodID record = env->GetStaticMethodID(diagnostic, "recordPendingJniException",
                "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (record != nullptr) {
            jstring j_class = env->NewStringUTF(exception_class.c_str());
            jstring j_message = env->NewStringUTF(message.c_str());
            jstring j_stack = env->NewStringUTF(stack.c_str());
            jstring j_top = env->NewStringUTF(top_method.c_str());
            jstring j_decl = env->NewStringUTF(declaring_class.c_str());
            jstring j_def = env->NewStringUTF(defining.c_str());
            jstring j_so = env->NewStringUTF(native_so.c_str());
            env->CallStaticVoidMethod(diagnostic, record, static_cast<jint>(tid),
                    j_class, j_message, j_stack, j_top, j_decl, j_def, j_so);
            if (j_class != nullptr) env->DeleteLocalRef(j_class);
            if (j_message != nullptr) env->DeleteLocalRef(j_message);
            if (j_stack != nullptr) env->DeleteLocalRef(j_stack);
            if (j_top != nullptr) env->DeleteLocalRef(j_top);
            if (j_decl != nullptr) env->DeleteLocalRef(j_decl);
            if (j_def != nullptr) env->DeleteLocalRef(j_def);
            if (j_so != nullptr) env->DeleteLocalRef(j_so);
        } else {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(diagnostic);
    } else {
        env->ExceptionClear();
    }
    if (env->ExceptionCheck()) orig_clear(env);

    orig_throw(env, pending);
    if (throwable_type != nullptr) env->DeleteLocalRef(throwable_type);
    if (class_type != nullptr) env->DeleteLocalRef(class_type);
    if (ste_type != nullptr) env->DeleteLocalRef(ste_type);
}

jboolean hooked_exception_check(JNIEnv* env) {
    if (!in_probe) {
        in_probe = true;
        report_pending(env, "ExceptionCheck");
        in_probe = false;
    }
    return orig_check != nullptr ? orig_check(env) : JNI_FALSE;
}

jthrowable hooked_exception_occurred(JNIEnv* env) {
    if (!in_probe) {
        in_probe = true;
        report_pending(env, "ExceptionOccurred");
        in_probe = false;
    }
    return orig_occurred != nullptr ? orig_occurred(env) : nullptr;
}

JNINativeInterface hooked_jni{};
JNIInvokeInterface hooked_vm{};
GetEnvFn orig_get_env = nullptr;
AttachFn orig_attach = nullptr;
AttachFn orig_attach_daemon = nullptr;

void bind_env(JNIEnv* env) {
    if (env == nullptr) return;
    auto** slot = reinterpret_cast<const JNINativeInterface**>(env);
    if (*slot == &hooked_jni) return;
    *slot = &hooked_jni;
}

jint hooked_get_env(JavaVM* vm, void** env, jint version) {
    const jint status = orig_get_env != nullptr ? orig_get_env(vm, env, version) : JNI_ERR;
    if (status == JNI_OK && env != nullptr) bind_env(static_cast<JNIEnv*>(*env));
    return status;
}

jint hooked_attach(JavaVM* vm, JNIEnv** env, void* args) {
    const jint status = orig_attach != nullptr ? orig_attach(vm, env, args) : JNI_ERR;
    if (status == JNI_OK && env != nullptr) bind_env(*env);
    return status;
}

jint hooked_attach_daemon(JavaVM* vm, JNIEnv** env, void* args) {
    const jint status = orig_attach_daemon != nullptr
            ? orig_attach_daemon(vm, env, args) : JNI_ERR;
    if (status == JNI_OK && env != nullptr) bind_env(*env);
    return status;
}

}  // namespace

bool install_jni_pending_exception_probe(JNIEnv* env) {
    if (env == nullptr || env->functions == nullptr) return false;
    if (installed.exchange(true)) {
        bind_env(env);
        return true;
    }
    std::memcpy(&hooked_jni, env->functions, sizeof(hooked_jni));
    orig_check = hooked_jni.ExceptionCheck;
    orig_occurred = hooked_jni.ExceptionOccurred;
    orig_clear = hooked_jni.ExceptionClear;
    orig_throw = hooked_jni.Throw;
    hooked_jni.ExceptionCheck = hooked_exception_check;
    hooked_jni.ExceptionOccurred = hooked_exception_occurred;
    bind_env(env);

    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr && vm->functions != nullptr) {
        std::memcpy(&hooked_vm, vm->functions, sizeof(hooked_vm));
        orig_get_env = hooked_vm.GetEnv;
        orig_attach = hooked_vm.AttachCurrentThread;
        orig_attach_daemon = hooked_vm.AttachCurrentThreadAsDaemon;
        hooked_vm.GetEnv = hooked_get_env;
        hooked_vm.AttachCurrentThread = hooked_attach;
        hooked_vm.AttachCurrentThreadAsDaemon = hooked_attach_daemon;
        auto** vm_slot = reinterpret_cast<const JNIInvokeInterface**>(vm);
        *vm_slot = &hooked_vm;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "PROBE ExceptionCheck/Occurred installed via JNIEnv copy");
    return true;
}

}  // namespace controlled_sandbox
