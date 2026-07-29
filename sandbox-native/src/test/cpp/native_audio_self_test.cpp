#include "controlled_sandbox/native_audio.h"

#include <iostream>
#include <stdexcept>

using namespace controlled_sandbox;
namespace { void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); } }

int main() {
    auto& policy = global_audio_capture_policy();
    policy.configure("audio-session", 3, false);
    require(policy.begin("AAudio") == 0, "denied capture must not start");
    policy.set_allowed(3, true);
    auto first = policy.begin("AAudio");
    auto second = policy.begin("MediaRecorder");
    require(first != 0 && second != 0 && policy.snapshot().active_count == 2, "granted capture leases");
    policy.set_allowed(3, false);
    require(policy.snapshot().active_count == 0 && !policy.snapshot().allowed, "revocation clears active capture");
    policy.configure("audio-session", 4, true);
    require(policy.snapshot().generation == 4 && policy.begin("OpenSL") != 0, "generation rebind");
    bool stale = false;
    try { policy.set_allowed(3, true); } catch (const std::logic_error&) { stale = true; }
    require(stale, "stale generation rejected");
    policy.reset();
    require(!policy.snapshot().configured, "reset capture policy");
    std::cout << "PASS sandbox-native audio capture policy self-test\n";
}
