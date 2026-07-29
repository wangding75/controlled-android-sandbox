#include "controlled_sandbox/native_audio.h"

#include <map>
#include <mutex>
#include <stdexcept>
#include <utility>

namespace controlled_sandbox {

struct NativeAudioCapturePolicy::State {
    mutable std::mutex mutex;
    bool configured{false};
    std::string session_id;
    std::uint64_t generation{};
    std::uint64_t revision{};
    std::uint64_t sequence{};
    bool allowed{false};
    std::map<std::uint64_t, std::string> active;
};

NativeAudioCapturePolicy::NativeAudioCapturePolicy() : state_(new State()) {}
NativeAudioCapturePolicy::~NativeAudioCapturePolicy() { delete state_; }

void NativeAudioCapturePolicy::configure(std::string session_id, std::uint64_t generation, bool allowed) {
    if (session_id.empty() || session_id.size() > 128 || generation < 1) {
        throw std::invalid_argument("native audio identity is invalid");
    }
    std::lock_guard lock(state_->mutex);
    if (state_->configured) {
        if (state_->session_id != session_id) throw std::logic_error("NATIVE_AUDIO_SESSION_ACTIVE");
        if (generation < state_->generation) throw std::logic_error("STALE_NATIVE_AUDIO_GENERATION");
        if (generation != state_->generation) state_->active.clear();
    }
    state_->configured = true;
    state_->session_id = std::move(session_id);
    state_->generation = generation;
    state_->allowed = allowed;
    if (!allowed) state_->active.clear();
    state_->revision++;
}

void NativeAudioCapturePolicy::set_allowed(std::uint64_t generation, bool allowed) {
    std::lock_guard lock(state_->mutex);
    if (!state_->configured || generation != state_->generation) {
        throw std::logic_error("NATIVE_AUDIO_GENERATION_MISMATCH");
    }
    state_->allowed = allowed;
    if (!allowed) state_->active.clear();
    state_->revision++;
}

std::uint64_t NativeAudioCapturePolicy::begin(std::string api) {
    if (api.empty() || api.size() > 96) throw std::invalid_argument("audio API is invalid");
    std::lock_guard lock(state_->mutex);
    if (!state_->configured || !state_->allowed) return 0;
    const std::uint64_t token = ++state_->sequence;
    state_->active.emplace(token, std::move(api));
    return token;
}

bool NativeAudioCapturePolicy::end(std::uint64_t token) noexcept {
    std::lock_guard lock(state_->mutex);
    return token != 0 && state_->active.erase(token) == 1;
}

void NativeAudioCapturePolicy::reset() noexcept {
    std::lock_guard lock(state_->mutex);
    state_->configured = false;
    state_->session_id.clear();
    state_->generation = 0;
    state_->allowed = false;
    state_->active.clear();
    state_->revision++;
}

NativeAudioCaptureSnapshot NativeAudioCapturePolicy::snapshot() const {
    std::lock_guard lock(state_->mutex);
    return {state_->configured, state_->session_id, state_->generation, state_->revision,
            state_->allowed, state_->active.size()};
}

NativeAudioCapturePolicy& global_audio_capture_policy() {
    static NativeAudioCapturePolicy policy;
    return policy;
}

}  // namespace controlled_sandbox
