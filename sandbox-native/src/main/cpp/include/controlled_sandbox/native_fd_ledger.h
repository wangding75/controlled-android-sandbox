#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace controlled_sandbox {

enum class NativeFdOwnership {
    GuestOwned,
    HostInternal,
    BrokerTransport,
    Inherited,
    VirtualizedPath,
};

struct NativeFdRecord {
    NativeFdOwnership ownership{NativeFdOwnership::Inherited};
    std::uint64_t policy_revision{};
    std::string virtual_path;
};

/**
 * Process-local FD ownership ledger.  It intentionally stores metadata only;
 * the kernel remains the owner of descriptor lifetime.  The ledger is used
 * to carry virtual ownership across dup/dup2/dup3/F_DUPFD and to redact
 * unknown inherited descriptors from /proc/self/fd readlink results.
 */
class NativeFdLedger final {
public:
    static void register_fd(int descriptor, NativeFdOwnership ownership,
                            std::uint64_t policy_revision,
                            std::string virtual_path = {});
    static void observe_inherited(int descriptor, std::uint64_t policy_revision,
                                  std::string virtual_path = {});
    static void duplicate(int source, int target);
    static void close(int descriptor) noexcept;
    static void reset() noexcept;

    [[nodiscard]] static std::optional<NativeFdRecord> lookup(int descriptor);
    [[nodiscard]] static bool guest_visible(int descriptor);
    [[nodiscard]] static std::vector<int> visible_descriptors();

    /** Project a raw /proc/self/fd target without exposing a host-private path. */
    [[nodiscard]] static std::string project_readlink(int descriptor,
                                                       std::string_view raw_target);
    [[nodiscard]] static std::string project_path(std::string_view raw_target);
};

}  // namespace controlled_sandbox
