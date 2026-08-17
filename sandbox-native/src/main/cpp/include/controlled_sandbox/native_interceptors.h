#pragma once

#include <string_view>

namespace controlled_sandbox {

/**
 * Returns the Guest-only PLT/GOT interceptor for a supported imported symbol.  The syscall symbol
 * is included to mediate libc syscall(SYS_*) calls; inline assembly/raw SVC remains outside a
 * userspace PLT boundary and must not be treated as a hostile-code security boundary.
 */
[[nodiscard]] void* replacement_for_symbol(std::string_view name) noexcept;

/** Best-effort stop for active native audio capture handles before permission revocation. */
void revoke_native_audio_captures() noexcept;

/**
 * Guest code (CrashSDK, splash recycle) must not SIGKILL the sandbox-owned slot.
 * The Host GuestProcessService sets this true only during its own teardown.
 */
void set_guest_process_exit_allowed(bool allowed) noexcept;
[[nodiscard]] bool guest_process_exit_allowed() noexcept;

}  // namespace controlled_sandbox
