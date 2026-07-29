#pragma once

#include <string_view>

namespace controlled_sandbox {

/** Returns the Guest-only PLT/GOT interceptor for a supported imported symbol. */
[[nodiscard]] void* replacement_for_symbol(std::string_view name) noexcept;

/** Best-effort stop for active native audio capture handles before permission revocation. */
void revoke_native_audio_captures() noexcept;

}  // namespace controlled_sandbox
