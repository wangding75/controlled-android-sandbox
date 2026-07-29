#pragma once

#include <cstdint>
#include <string>

namespace controlled_sandbox {

struct NativeAudioCaptureSnapshot {
    bool configured{false};
    std::string session_id;
    std::uint64_t generation{};
    std::uint64_t revision{};
    bool allowed{false};
    std::size_t active_count{};
};

class NativeAudioCapturePolicy final {
public:
    void configure(std::string session_id, std::uint64_t generation, bool allowed);
    void set_allowed(std::uint64_t generation, bool allowed);
    [[nodiscard]] std::uint64_t begin(std::string api);
    bool end(std::uint64_t token) noexcept;
    void reset() noexcept;
    [[nodiscard]] NativeAudioCaptureSnapshot snapshot() const;
private:
    struct State;
    State* state_;
public:
    NativeAudioCapturePolicy();
    ~NativeAudioCapturePolicy();
    NativeAudioCapturePolicy(const NativeAudioCapturePolicy&) = delete;
    NativeAudioCapturePolicy& operator=(const NativeAudioCapturePolicy&) = delete;
};

NativeAudioCapturePolicy& global_audio_capture_policy();

}  // namespace controlled_sandbox
