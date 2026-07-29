#pragma once

#include <string_view>

namespace controlled_sandbox {

/** Returns the Guest-only PLT/GOT interceptor for a supported imported symbol. */
[[nodiscard]] void* replacement_for_symbol(std::string_view name) noexcept;

}  // namespace controlled_sandbox
