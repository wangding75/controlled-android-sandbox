#include "controlled_sandbox/native_camera1.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <limits>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <unordered_map>
#include <utility>

namespace controlled_sandbox {
namespace {

constexpr char kTag[] = "CS_CAMERA1_NATIVE";
constexpr int kCameraMsgPreviewFrame = 0x10;
constexpr int kCameraMsgCompressedImage = 0x100;
constexpr std::size_t kMaxFrames = 8;
constexpr std::size_t kMaxFrameBytes = 16U * 1024U * 1024U;
constexpr std::size_t kMaxFrameMemory = 64U * 1024U * 1024U;

using CameraConnectFn = void (*)(NativeCamera1SpStorage*, int, const void*, int, int, int);
using GetNativeCameraFn = void (*)(NativeCamera1SpStorage*, JNIEnv*, jobject, void**);
using CopyAndPostFn = void (*)(void*, JNIEnv*, const NativeCamera1SpStorage*, int);
using ReleaseContextFn = void (*)(void*);
using String16ConstructorFn = void (*)(void*, const char*);
using String16DestructorFn = void (*)(void*);

std::atomic<CameraConnectFn> real_camera_connect{nullptr};
std::atomic<GetNativeCameraFn> real_get_native_camera{nullptr};
std::atomic<CopyAndPostFn> real_copy_and_post{nullptr};
std::atomic<ReleaseContextFn> real_release_context{nullptr};
std::atomic<JavaVM*> java_vm{nullptr};
String16ConstructorFn string16_constructor = nullptr;
String16DestructorFn string16_destructor = nullptr;

struct ContextBinding {
    jobject event_reference{nullptr};
};

struct AdapterState {
    mutable std::shared_mutex mutex;
    bool symbols_ready{false};
    bool hooks_ready{false};
    bool identity_configured{false};
    bool virtual_camera{false};
    bool allow_open{true};
    bool replace_preview{false};
    bool replace_capture{false};
    std::string guest_package;
    std::string host_package;
    int virtual_uid{-1};
    int host_uid{-1};
    std::string source_kind;
    std::string source_sha256;
    int width{0};
    int height{0};
    std::vector<std::vector<std::uint8_t>> preview_frames;
    std::vector<std::vector<std::uint8_t>> capture_frames;
    std::atomic<std::size_t> preview_index{0};
    std::atomic<std::size_t> capture_index{0};
    std::atomic<std::size_t> preview_deliveries{0};
    std::atomic<std::size_t> capture_deliveries{0};
    std::unordered_map<void*, ContextBinding> contexts;
    std::string last_error;
    std::size_t hook_targets{0};
    std::size_t hook_patched{0};
    std::size_t hook_failures{0};
};

AdapterState& state() {
    static AdapterState value;
    return value;
}

std::uintptr_t runtime_pointer(std::uintptr_t base, std::uintptr_t value) {
    return value < base ? base + value : value;
}

struct LoadedSymbolQuery {
    const char* module;
    const char* symbol;
    void* address{nullptr};
};

bool module_name_matches(const char* path, const char* module) {
    if (path == nullptr || module == nullptr) return false;
    const std::string_view value(path);
    const std::string_view expected(module);
    return value == expected || (value.size() > expected.size()
            && value.compare(value.size() - expected.size(), expected.size(), expected) == 0
            && value[value.size() - expected.size() - 1] == '/');
}

std::size_t dynamic_symbol_count(const ElfW(Dyn)* dynamic, std::uintptr_t base) {
    const ElfW(Word)* classic_hash = nullptr;
    const ElfW(Word)* gnu_hash = nullptr;
    for (const ElfW(Dyn)* item = dynamic; item->d_tag != DT_NULL; item++) {
        if (item->d_tag == DT_HASH) {
            classic_hash = reinterpret_cast<const ElfW(Word)*>(
                    runtime_pointer(base, item->d_un.d_ptr));
        } else if (item->d_tag == DT_GNU_HASH) {
            gnu_hash = reinterpret_cast<const ElfW(Word)*>(
                    runtime_pointer(base, item->d_un.d_ptr));
        }
    }
    if (classic_hash != nullptr) return static_cast<std::size_t>(classic_hash[1]);
    if (gnu_hash == nullptr) return 0;
    const std::uint32_t bucket_count = gnu_hash[0];
    const std::uint32_t symbol_offset = gnu_hash[1];
    const std::uint32_t bloom_size = gnu_hash[2];
    if (bucket_count == 0) return 0;
    const std::size_t bloom_words = sizeof(ElfW(Addr)) / sizeof(std::uint32_t);
    const std::uint32_t* buckets = gnu_hash + 4 + bloom_size * bloom_words;
    const std::uint32_t* chains = buckets + bucket_count;
    std::uint32_t largest = symbol_offset;
    for (std::uint32_t bucket = 0; bucket < bucket_count; bucket++) {
        std::uint32_t index = buckets[bucket];
        if (index < symbol_offset) continue;
        while ((chains[index - symbol_offset] & 1U) == 0U) index++;
        if (index > largest) largest = index;
    }
    return static_cast<std::size_t>(largest) + 1U;
}

int find_loaded_symbol(dl_phdr_info* info, std::size_t, void* opaque) {
    auto* query = static_cast<LoadedSymbolQuery*>(opaque);
    if (query == nullptr || query->address != nullptr
            || !module_name_matches(info->dlpi_name, query->module)) return 0;
    const ElfW(Dyn)* dynamic = nullptr;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; index++) {
        if (info->dlpi_phdr[index].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(
                    info->dlpi_addr + info->dlpi_phdr[index].p_vaddr);
            break;
        }
    }
    if (dynamic == nullptr) return 0;
    const ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    for (const ElfW(Dyn)* item = dynamic; item->d_tag != DT_NULL; item++) {
        if (item->d_tag == DT_SYMTAB) {
            symbols = reinterpret_cast<const ElfW(Sym)*>(
                    runtime_pointer(info->dlpi_addr, item->d_un.d_ptr));
        } else if (item->d_tag == DT_STRTAB) {
            strings = reinterpret_cast<const char*>(
                    runtime_pointer(info->dlpi_addr, item->d_un.d_ptr));
        }
    }
    if (symbols == nullptr || strings == nullptr) return 0;
    const std::size_t count = dynamic_symbol_count(dynamic, info->dlpi_addr);
    for (std::size_t index = 0; index < count; index++) {
        const ElfW(Sym)& candidate = symbols[index];
        if (candidate.st_name == 0 || std::strcmp(strings + candidate.st_name, query->symbol) != 0) {
            continue;
        }
        if (candidate.st_shndx == SHN_UNDEF || candidate.st_value == 0) continue;
        const std::uintptr_t address = candidate.st_shndx == SHN_ABS
                ? static_cast<std::uintptr_t>(candidate.st_value)
                : info->dlpi_addr + static_cast<std::uintptr_t>(candidate.st_value);
        query->address = reinterpret_cast<void*>(address);
        return 1;
    }
    return 0;
}

void* loaded_module_symbol(const char* module, const char* name) {
    LoadedSymbolQuery query{module, name};
    (void) dl_iterate_phdr(find_loaded_symbol, &query);
    return query.address;
}

std::array<std::string, 2> system_module_candidates(const char* module) {
#if defined(__LP64__)
    constexpr const char* kRuntimeLib = "/apex/com.android.runtime/lib64/";
    constexpr const char* kSystemLib = "/system/lib64/";
#else
    constexpr const char* kRuntimeLib = "/apex/com.android.runtime/lib/";
    constexpr const char* kSystemLib = "/system/lib/";
#endif
    return {std::string(kRuntimeLib) + module, std::string(kSystemLib) + module};
}

void* open_module(const char* module, int flags, std::string& opened_path) {
    void* handle = dlopen(module, flags | RTLD_NOLOAD);
    if (handle != nullptr) {
        opened_path = module;
        return handle;
    }
    handle = dlopen(module, flags);
    if (handle != nullptr) {
        opened_path = module;
        return handle;
    }
    if (module == nullptr || module[0] == '/') return nullptr;
    for (const std::string& candidate : system_module_candidates(module)) {
        handle = dlopen(candidate.c_str(), flags | RTLD_NOLOAD);
        if (handle == nullptr) handle = dlopen(candidate.c_str(), flags);
        if (handle != nullptr) {
            opened_path = candidate;
            return handle;
        }
    }
    return nullptr;
}

void* module_symbol(const char* module, const char* name, bool log_failure) {
    void* address = loaded_module_symbol(module, name);
    if (module != nullptr) {
        std::string opened_path;
        void* handle = open_module(module, RTLD_NOW | RTLD_GLOBAL, opened_path);
        if (handle != nullptr && opened_path != module) {
            __android_log_print(ANDROID_LOG_INFO, kTag,
                    "SYMBOL_MODULE_LOADED module=%s path=%s", module, opened_path.c_str());
        }
        if (address == nullptr && handle != nullptr) address = dlsym(handle, name);
        if (address == nullptr) {
            void* default_address = dlsym(RTLD_DEFAULT, name);
            if (default_address != nullptr) address = default_address;
        }
        if (address == nullptr && log_failure) {
            const char* error = dlerror();
            if (handle == nullptr) {
                __android_log_print(ANDROID_LOG_WARN, kTag,
                        "SYMBOL_MODULE_UNAVAILABLE module=%s error=%s", module,
                        error == nullptr ? "unknown" : error);
            } else {
                __android_log_print(ANDROID_LOG_WARN, kTag,
                        "SYMBOL_MODULE_LOOKUP_FAILED module=%s symbol=%s error=%s", module, name,
                        error == nullptr ? "unknown" : error);
            }
        }
    }
    if (address == nullptr) address = dlsym(RTLD_DEFAULT, name);
    return address;
}

template <typename Function>
Function symbol(const char* name, const char* module, const char* dependency = nullptr) {
    // A symbol may legitimately live in a linked dependency on different Android
    // releases.  Do not report the primary module as broken until the dependency
    // lookup has also failed; otherwise a successful cross-module fallback looks
    // like an ABI failure in production logs.
    void* address = module_symbol(module, name, dependency == nullptr);
    if (address == nullptr && dependency != nullptr) {
        address = module_symbol(dependency, name, true);
    }
    return reinterpret_cast<Function>(address);
}

bool valid_frame_set(const std::vector<std::vector<std::uint8_t>>& frames,
                     std::size_t& total) {
    if (frames.empty() || frames.size() > kMaxFrames) return false;
    for (const auto& frame : frames) {
        if (frame.empty() || frame.size() > kMaxFrameBytes) return false;
        if (total > kMaxFrameMemory - frame.size()) return false;
        total += frame.size();
    }
    return true;
}

bool make_local_event_reference(JNIEnv* env, jobject global_reference, jobject& local) {
    local = nullptr;
    if (env == nullptr || global_reference == nullptr) return false;
    local = env->NewLocalRef(global_reference);
    if (local == nullptr && env->ExceptionCheck()) env->ExceptionClear();
    return local != nullptr;
}

bool post_java_event(JNIEnv* env, jobject event_reference, int message_type,
                     const std::vector<std::uint8_t>& bytes) {
    if (env == nullptr || event_reference == nullptr || bytes.empty()
            || bytes.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return false;
    }
    jclass camera_class = env->FindClass("android/hardware/Camera");
    if (camera_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID post_event = env->GetStaticMethodID(camera_class, "postEventFromNative",
            "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (post_event == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(camera_class);
        return false;
    }
    jbyteArray payload = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (payload == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(camera_class);
        return false;
    }
    env->SetByteArrayRegion(payload, 0, static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(payload);
        env->DeleteLocalRef(camera_class);
        return false;
    }
    env->CallStaticVoidMethod(camera_class, post_event, event_reference, message_type, 0, 0, payload);
    const bool success = !env->ExceptionCheck();
    if (!success) env->ExceptionClear();
    env->DeleteLocalRef(payload);
    env->DeleteLocalRef(camera_class);
    return success;
}

void camera_connect_replacement(NativeCamera1SpStorage* out, int camera_id,
                                const void* guest_package, int arg3, int arg4, int arg5) {
    auto real = real_camera_connect.load(std::memory_order_acquire);
    if (real == nullptr) return;
    auto& adapter = global_camera1_adapter();
    if (out == nullptr) return;
    if (adapter.deny_open()) {
        out->pointer = nullptr;
        __android_log_print(ANDROID_LOG_INFO, kTag,
                "CONNECT_DENIED cameraId=%d guest=%s reason=profile_allowOpen_false",
                camera_id, adapter.status().guest_package.c_str());
        return;
    }
    if (!adapter.should_project_identity() || string16_constructor == nullptr
            || string16_destructor == nullptr) {
        real(out, camera_id, guest_package, arg3, arg4, arg5);
        return;
    }
    alignas(std::max_align_t) std::array<std::uint64_t, 4> host_string{};
    const auto configured = adapter.status();
    const int projected_arg3 = arg3 == configured.virtual_uid ? configured.host_uid : arg3;
    const int projected_arg4 = arg4 == configured.virtual_uid ? configured.host_uid : arg4;
    const int projected_arg5 = arg5 == configured.virtual_uid ? configured.host_uid : arg5;
    string16_constructor(host_string.data(), configured.host_package.c_str());
    real(out, camera_id, host_string.data(), projected_arg3, projected_arg4, projected_arg5);
    string16_destructor(host_string.data());
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "CONNECT_PROJECTED cameraId=%d guestPackage=%s hostPackage=%s virtualUid=%d "
            "hostUid=%d argsBefore=%d,%d,%d argsAfter=%d,%d,%d actualUid=%d actualPid=%d result=%p",
            camera_id, configured.guest_package.c_str(), configured.host_package.c_str(),
            configured.virtual_uid, configured.host_uid, arg3, arg4, arg5, projected_arg3,
            projected_arg4, projected_arg5, static_cast<int>(getuid()), static_cast<int>(getpid()),
            out->pointer);
}

void get_native_camera_replacement(NativeCamera1SpStorage* out, JNIEnv* env,
                                   jobject camera, void** context) {
    auto real = real_get_native_camera.load(std::memory_order_acquire);
    if (real == nullptr) return;
    real(out, env, camera, context);
    if (context != nullptr && *context != nullptr) {
        global_camera1_adapter().remember_context(env, *context, camera);
    }
}

void copy_and_post_replacement(void* context, JNIEnv* env,
                               const NativeCamera1SpStorage* memory, int message_type) {
    auto& adapter = global_camera1_adapter();
    if (adapter.replace_data(env, context, message_type)) return;
    auto real = real_copy_and_post.load(std::memory_order_acquire);
    if (real != nullptr) real(context, env, memory, message_type);
}

void release_context_replacement(void* context) {
    auto real = real_release_context.load(std::memory_order_acquire);
    if (real != nullptr) real(context);

    JavaVM* vm = java_vm.load(std::memory_order_acquire);
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm != nullptr) {
        const jint result = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            JavaVMAttachArgs args{JNI_VERSION_1_6, "cs-camera1-release", nullptr};
            if (vm->AttachCurrentThread(&env, &args) == JNI_OK) attached = true;
        }
    }
    global_camera1_adapter().forget_context(env, context);
    if (attached && vm != nullptr) vm->DetachCurrentThread();
}

}  // namespace

bool NativeCamera1Adapter::prepare_symbols() {
    auto& current = state();
    std::unique_lock lock(current.mutex);
    if (current.symbols_ready) return true;
    real_camera_connect.store(symbol<CameraConnectFn>(
            "_ZN7android6Camera7connectEiRKNS_8String16Eiii",
            "libandroid_runtime.so", "libcamera_client.so"), std::memory_order_release);
    real_get_native_camera.store(symbol<GetNativeCameraFn>(
            "_Z17get_native_cameraP7_JNIEnvP8_jobjectPP16JNICameraContext",
            "libandroid_runtime.so"),
            std::memory_order_release);
    real_copy_and_post.store(symbol<CopyAndPostFn>(
            "_ZN16JNICameraContext11copyAndPostEP7_JNIEnvRKN7android2spINS2_7IMemoryEEEi",
            "libandroid_runtime.so"),
            std::memory_order_release);
    real_release_context.store(symbol<ReleaseContextFn>(
            "_ZN16JNICameraContext7releaseEv", "libandroid_runtime.so"),
            std::memory_order_release);
    string16_constructor = symbol<String16ConstructorFn>(
            "_ZN7android8String16C1EPKc", "libutils.so");
    string16_destructor = symbol<String16DestructorFn>(
            "_ZN7android8String16D1Ev", "libutils.so");
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "SYMBOLS_LOOKUP connect=%p getNative=%p copyAndPost=%p release=%p "
            "string16Ctor=%p string16Dtor=%p",
            reinterpret_cast<void*>(real_camera_connect.load(std::memory_order_acquire)),
            reinterpret_cast<void*>(real_get_native_camera.load(std::memory_order_acquire)),
            reinterpret_cast<void*>(real_copy_and_post.load(std::memory_order_acquire)),
            reinterpret_cast<void*>(real_release_context.load(std::memory_order_acquire)),
            reinterpret_cast<void*>(string16_constructor),
            reinterpret_cast<void*>(string16_destructor));
    if (real_camera_connect.load(std::memory_order_acquire) == nullptr
            || real_get_native_camera.load(std::memory_order_acquire) == nullptr
            || real_copy_and_post.load(std::memory_order_acquire) == nullptr
            || real_release_context.load(std::memory_order_acquire) == nullptr
            || string16_constructor == nullptr || string16_destructor == nullptr) {
        current.last_error = "CAMERA1_NATIVE_SYMBOL_RESOLUTION_FAILED";
        current.symbols_ready = false;
        return false;
    }
    current.symbols_ready = true;
    current.last_error.clear();
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "SYMBOLS_READY connect=%p getNative=%p copyAndPost=%p release=%p string16=%p",
            reinterpret_cast<void*>(real_camera_connect.load()),
            reinterpret_cast<void*>(real_get_native_camera.load()),
            reinterpret_cast<void*>(real_copy_and_post.load()),
            reinterpret_cast<void*>(real_release_context.load()),
            reinterpret_cast<void*>(string16_constructor));
    return true;
}

bool NativeCamera1Adapter::configure_identity(std::string guest_package, int virtual_uid,
                                              std::string host_package, int host_uid,
                                              bool virtual_camera, bool allow_open,
                                              bool replace_preview, bool replace_capture) {
    auto& current = state();
    std::unique_lock lock(current.mutex);
    if (guest_package.empty() || host_package.empty()) {
        current.last_error = "CAMERA1_NATIVE_IDENTITY_INVALID";
        return false;
    }
    current.guest_package = std::move(guest_package);
    current.virtual_uid = virtual_uid;
    current.host_package = std::move(host_package);
    current.host_uid = host_uid;
    current.virtual_camera = virtual_camera;
    current.allow_open = allow_open;
    current.replace_preview = replace_preview;
    current.replace_capture = replace_capture;
    // A profile reconfiguration starts a new source epoch.  Clear the old
    // frames before the Java layer reads the new source so a failed/corrupt
    // source can never fall back to a previous instance or generation.
    current.source_kind.clear();
    current.source_sha256.clear();
    current.width = 0;
    current.height = 0;
    current.preview_frames.clear();
    current.capture_frames.clear();
    current.preview_index.store(0, std::memory_order_release);
    current.capture_index.store(0, std::memory_order_release);
    current.identity_configured = true;
    current.last_error.clear();
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "IDENTITY_READY guestPackage=%s virtualUid=%d hostPackage=%s hostUid=%d "
            "virtualCamera=%s allowOpen=%s replacePreview=%s replaceCapture=%s",
            current.guest_package.c_str(), current.virtual_uid, current.host_package.c_str(),
            current.host_uid, virtual_camera ? "true" : "false", allow_open ? "true" : "false",
            replace_preview ? "true" : "false", replace_capture ? "true" : "false");
    return true;
}

bool NativeCamera1Adapter::configure_frames(
        std::vector<std::vector<std::uint8_t>> preview_frames,
        std::vector<std::vector<std::uint8_t>> capture_frames,
        std::string source_kind, std::string source_sha256, int width, int height) {
    std::size_t total = 0;
    if (source_kind.empty() || source_sha256.empty() || width <= 0 || height <= 0
            || !valid_frame_set(preview_frames, total) || !valid_frame_set(capture_frames, total)) {
        auto& current = state();
        std::unique_lock lock(current.mutex);
        current.last_error = "CAMERA1_NATIVE_FRAME_BOUNDS_INVALID";
        return false;
    }
    auto& current = state();
    std::unique_lock lock(current.mutex);
    if (!current.identity_configured || !current.virtual_camera) {
        current.last_error = "CAMERA1_NATIVE_FRAME_PROFILE_INVALID";
        return false;
    }
    current.preview_frames = std::move(preview_frames);
    current.capture_frames = std::move(capture_frames);
    current.source_kind = std::move(source_kind);
    current.source_sha256 = std::move(source_sha256);
    current.width = width;
    current.height = height;
    current.preview_index.store(0, std::memory_order_release);
    current.capture_index.store(0, std::memory_order_release);
    current.last_error.clear();
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "SOURCE_READY kind=%s sha256=%s width=%d height=%d previewFrames=%zu captureFrames=%zu",
            current.source_kind.c_str(), current.source_sha256.c_str(), current.width,
            current.height, current.preview_frames.size(), current.capture_frames.size());
    return true;
}

void NativeCamera1Adapter::remember_context(JNIEnv* env, void* context, jobject camera) {
    if (env == nullptr || context == nullptr || camera == nullptr) return;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
        java_vm.store(vm, std::memory_order_release);
    }
    auto& current = state();
    std::unique_lock lock(current.mutex);
    auto found = current.contexts.find(context);
    if (found != current.contexts.end()) return;
    jclass weak_reference_class = env->FindClass("java/lang/ref/WeakReference");
    if (weak_reference_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        current.last_error = "CAMERA1_NATIVE_CONTEXT_WEAK_REFERENCE_CLASS_FAILED";
        return;
    }
    jmethodID constructor = env->GetMethodID(weak_reference_class, "<init>",
            "(Ljava/lang/Object;)V");
    if (constructor == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(weak_reference_class);
        current.last_error = "CAMERA1_NATIVE_CONTEXT_WEAK_REFERENCE_CONSTRUCTOR_FAILED";
        return;
    }
    jobject weak_reference = env->NewObject(weak_reference_class, constructor, camera);
    if (weak_reference == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        current.last_error = "CAMERA1_NATIVE_CONTEXT_BIND_FAILED";
        env->DeleteLocalRef(weak_reference_class);
        return;
    }
    jobject global_reference = env->NewGlobalRef(weak_reference);
    env->DeleteLocalRef(weak_reference);
    env->DeleteLocalRef(weak_reference_class);
    if (global_reference == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        current.last_error = "CAMERA1_NATIVE_CONTEXT_GLOBAL_REFERENCE_FAILED";
        return;
    }
    current.contexts.emplace(context, ContextBinding{global_reference});
    __android_log_print(ANDROID_LOG_INFO, kTag, "CONTEXT_BOUND context=%p", context);
}

void NativeCamera1Adapter::forget_context(JNIEnv* env, void* context) {
    if (context == nullptr) return;
    ContextBinding binding;
    std::size_t remaining = 0;
    {
        auto& current = state();
        std::unique_lock lock(current.mutex);
        const auto found = current.contexts.find(context);
        if (found == current.contexts.end()) return;
        binding = found->second;
        current.contexts.erase(found);
        remaining = current.contexts.size();
    }
    if (env != nullptr && binding.event_reference != nullptr) {
        env->DeleteGlobalRef(binding.event_reference);
    }
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "CONTEXT_RELEASED context=%p remaining=%zu", context, remaining);
}

bool NativeCamera1Adapter::replace_data(JNIEnv* env, void* context, int message_type) {
    auto& current = state();
    std::vector<std::uint8_t> bytes;
    jobject local_event_reference = nullptr;
    std::string source_kind;
    bool capture = false;
    {
        std::shared_lock lock(current.mutex);
        if (!current.identity_configured || !current.virtual_camera) return false;
        capture = message_type == kCameraMsgCompressedImage;
        const bool preview = message_type == kCameraMsgPreviewFrame;
        if ((!capture && !preview) || (capture ? !current.replace_capture : !current.replace_preview)) {
            return false;
        }
        const auto& frames = capture ? current.capture_frames : current.preview_frames;
        if (frames.empty()) {
            __android_log_print(ANDROID_LOG_ERROR, kTag,
                    "DATA_REJECTED context=%p messageType=0x%x reason=source_not_configured",
                    context, message_type);
            return true;
        }
        auto found = current.contexts.find(context);
        if (found == current.contexts.end()) {
            __android_log_print(ANDROID_LOG_ERROR, kTag,
                    "DATA_REJECTED context=%p messageType=0x%x reason=context_not_bound",
                    context, message_type);
            return true;
        }
        if (!make_local_event_reference(env, found->second.event_reference,
                local_event_reference)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag,
                    "DATA_REJECTED context=%p messageType=0x%x reason=dead_java_camera",
                    context, message_type);
            return true;
        }
        auto& cursor = capture ? current.capture_index : current.preview_index;
        const std::size_t index = cursor.fetch_add(1, std::memory_order_relaxed) % frames.size();
        bytes = frames[index];
        source_kind = current.source_kind;
    }
    const bool delivered = post_java_event(env, local_event_reference, message_type, bytes);
    env->DeleteLocalRef(local_event_reference);
    if (!delivered) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                "DATA_REJECTED context=%p messageType=0x%x reason=java_delivery_failed",
                context, message_type);
        return true;
    }
    if (capture) current.capture_deliveries.fetch_add(1, std::memory_order_relaxed);
    else current.preview_deliveries.fetch_add(1, std::memory_order_relaxed);
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "DATA_REPLACED context=%p messageType=0x%x bytes=%zu sourceKind=%s",
            context, message_type, bytes.size(), source_kind.c_str());
    return true;
}

bool NativeCamera1Adapter::deny_open() const {
    const auto& current = state();
    std::shared_lock lock(current.mutex);
    return current.identity_configured && !current.allow_open;
}

bool NativeCamera1Adapter::should_project_identity() const {
    const auto& current = state();
    std::shared_lock lock(current.mutex);
    return current.identity_configured && !current.host_package.empty();
}

std::string NativeCamera1Adapter::host_package() const {
    const auto& current = state();
    std::shared_lock lock(current.mutex);
    return current.host_package;
}

void NativeCamera1Adapter::record_hook_result(std::size_t patched, std::size_t targets,
                                              std::size_t failures) {
    auto& current = state();
    std::unique_lock lock(current.mutex);
    current.hooks_ready = failures == 0 && targets > 0;
    // A later refresh sees already-replaced PLT slots and therefore reports patched=0.
    // Preserve the successful installation count while still recording new targets/failures.
    current.hook_patched = std::max(current.hook_patched, patched);
    current.hook_targets = std::max(current.hook_targets, targets);
    current.hook_failures = failures;
    if (!current.hooks_ready) current.last_error = "CAMERA1_NATIVE_PLT_PATCH_FAILED";
}

void NativeCamera1Adapter::reset(JNIEnv* env) {
    auto& current = state();
    std::unordered_map<void*, ContextBinding> contexts;
    {
        std::unique_lock lock(current.mutex);
        contexts.swap(current.contexts);
        current.identity_configured = false;
        current.virtual_camera = false;
        current.allow_open = true;
        current.replace_preview = false;
        current.replace_capture = false;
        current.guest_package.clear();
        current.host_package.clear();
        current.virtual_uid = -1;
        current.host_uid = -1;
        current.source_kind.clear();
        current.source_sha256.clear();
        current.preview_frames.clear();
        current.capture_frames.clear();
        current.preview_index.store(0, std::memory_order_release);
        current.capture_index.store(0, std::memory_order_release);
        current.preview_deliveries.store(0, std::memory_order_release);
        current.capture_deliveries.store(0, std::memory_order_release);
        current.last_error.clear();
    }
    if (env != nullptr) {
        for (const auto& item : contexts) {
            if (item.second.event_reference != nullptr) env->DeleteGlobalRef(item.second.event_reference);
        }
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "RESET contexts=%zu", contexts.size());
}

NativeCamera1Status NativeCamera1Adapter::status() const {
    const auto& current = state();
    std::shared_lock lock(current.mutex);
    NativeCamera1Status out;
    out.symbols_ready = current.symbols_ready;
    out.hooks_ready = current.hooks_ready;
    out.identity_configured = current.identity_configured;
    out.virtual_camera = current.virtual_camera;
    out.allow_open = current.allow_open;
    out.replace_preview = current.replace_preview;
    out.replace_capture = current.replace_capture;
    out.preview_frames = current.preview_frames.size();
    out.capture_frames = current.capture_frames.size();
    out.preview_deliveries = current.preview_deliveries.load(std::memory_order_relaxed);
    out.capture_deliveries = current.capture_deliveries.load(std::memory_order_relaxed);
    out.context_bindings = current.contexts.size();
    out.hook_targets = current.hook_targets;
    out.hook_patched = current.hook_patched;
    out.hook_failures = current.hook_failures;
    out.guest_package = current.guest_package;
    out.host_package = current.host_package;
    out.virtual_uid = current.virtual_uid;
    out.host_uid = current.host_uid;
    out.last_error = current.last_error;
    return out;
}

NativeCamera1Adapter& global_camera1_adapter() {
    static NativeCamera1Adapter adapter;
    return adapter;
}

bool is_camera1_system_module(std::string_view module_path) noexcept {
    constexpr std::string_view name = "libandroid_runtime.so";
    return module_path == name || (module_path.size() > name.size()
            && module_path.compare(module_path.size() - name.size(), name.size(), name) == 0);
}

bool is_camera1_system_symbol(std::string_view symbol_name) noexcept {
    static constexpr std::array<std::string_view, 4> names{
            "_ZN7android6Camera7connectEiRKNS_8String16Eiii",
            "_Z17get_native_cameraP7_JNIEnvP8_jobjectPP16JNICameraContext",
            "_ZN16JNICameraContext11copyAndPostEP7_JNIEnvRKN7android2spINS2_7IMemoryEEEi",
            "_ZN16JNICameraContext7releaseEv"};
    return std::find(names.begin(), names.end(), symbol_name) != names.end();
}

void* replacement_for_camera1_symbol(std::string_view symbol_name) noexcept {
    if (symbol_name == "_ZN7android6Camera7connectEiRKNS_8String16Eiii") {
        return reinterpret_cast<void*>(&camera_connect_replacement);
    }
    if (symbol_name == "_Z17get_native_cameraP7_JNIEnvP8_jobjectPP16JNICameraContext") {
        return reinterpret_cast<void*>(&get_native_camera_replacement);
    }
    if (symbol_name == "_ZN16JNICameraContext11copyAndPostEP7_JNIEnvRKN7android2spINS2_7IMemoryEEEi") {
        return reinterpret_cast<void*>(&copy_and_post_replacement);
    }
    if (symbol_name == "_ZN16JNICameraContext7releaseEv") {
        return reinterpret_cast<void*>(&release_context_replacement);
    }
    return nullptr;
}

}  // namespace controlled_sandbox
