#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include <jni.h>

namespace controlled_sandbox {

/** The ABI-sized return storage used by android::sp<T> on the audited Camera1 ABI. */
struct NativeCamera1SpStorage {
    void* pointer{nullptr};
};

struct NativeCamera1Status {
    bool symbols_ready{false};
    bool hooks_ready{false};
    bool identity_configured{false};
    bool virtual_camera{false};
    bool allow_open{true};
    bool replace_preview{false};
    bool replace_capture{false};
    std::size_t preview_frames{0};
    std::size_t capture_frames{0};
    std::size_t preview_deliveries{0};
    std::size_t capture_deliveries{0};
    std::size_t context_bindings{0};
    std::size_t hook_targets{0};
    std::size_t hook_patched{0};
    std::size_t hook_failures{0};
    std::string guest_package;
    std::string host_package;
    int virtual_uid{-1};
    int host_uid{-1};
    std::string last_error;
};

class NativeCamera1Adapter final {
public:
    bool prepare_symbols();
    bool configure_identity(std::string guest_package, int virtual_uid,
                            std::string host_package, int host_uid,
                            bool virtual_camera, bool allow_open,
                            bool replace_preview, bool replace_capture);
    bool configure_frames(std::vector<std::vector<std::uint8_t>> preview_frames,
                          std::vector<std::vector<std::uint8_t>> capture_frames,
                          std::string source_kind, std::string source_sha256,
                          int width, int height);

    void remember_context(JNIEnv* env, void* context, jobject camera);
    void forget_context(JNIEnv* env, void* context);
    /** Returns true when the event was handled by the virtual data plane. */
    bool replace_data(JNIEnv* env, void* context, int message_type);
    bool deny_open() const;
    bool should_project_identity() const;
    std::string host_package() const;
    void record_hook_result(std::size_t patched, std::size_t targets, std::size_t failures);
    void reset(JNIEnv* env);
    [[nodiscard]] NativeCamera1Status status() const;

private:
    NativeCamera1Adapter() = default;
    friend NativeCamera1Adapter& global_camera1_adapter();
};

NativeCamera1Adapter& global_camera1_adapter();

bool is_camera1_system_module(std::string_view module_path) noexcept;
bool is_camera1_system_symbol(std::string_view symbol) noexcept;
void* replacement_for_camera1_symbol(std::string_view symbol) noexcept;

}  // namespace controlled_sandbox
