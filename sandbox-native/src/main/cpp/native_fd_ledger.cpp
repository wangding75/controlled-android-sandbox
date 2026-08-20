#include "controlled_sandbox/native_fd_ledger.h"

#include "controlled_sandbox/native_policy.h"

#include <algorithm>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>

namespace controlled_sandbox {
namespace {

struct LedgerState {
    std::mutex mutex;
    std::unordered_map<int, NativeFdRecord> records;
};

LedgerState& state() {
    static LedgerState value;
    return value;
}

bool has_prefix(std::string_view value, std::string_view prefix) {
    return value == prefix || (value.size() > prefix.size()
            && value.compare(0, prefix.size(), prefix) == 0
            && value[prefix.size()] == '/');
}

bool public_system_path(std::string_view value) {
    for (const std::string_view root : {"/system", "/apex", "/vendor", "/product", "/odm"}) {
        if (has_prefix(value, root)) return true;
    }
    return false;
}

bool kernel_pseudo_target(std::string_view value) {
    return value.rfind("socket:[", 0) == 0 || value.rfind("pipe:[", 0) == 0
            || value.rfind("anon_inode:", 0) == 0 || value.rfind("memfd:", 0) == 0
            || value.rfind("/memfd:", 0) == 0;
}

}  // namespace

void NativeFdLedger::register_fd(int descriptor, NativeFdOwnership ownership,
                                 std::uint64_t policy_revision,
                                 std::string virtual_path) {
    if (descriptor < 0) return;
    try {
        std::lock_guard lock(state().mutex);
        state().records[descriptor] = NativeFdRecord{ownership, policy_revision,
                                                       std::move(virtual_path)};
    } catch (...) {
        // FD tracking is a compatibility ledger. A bookkeeping allocation
        // failure must not turn a successful kernel open into a process crash.
    }
}

void NativeFdLedger::observe_inherited(int descriptor, std::uint64_t policy_revision,
                                       std::string virtual_path) {
    if (descriptor < 0) return;
    try {
        std::lock_guard lock(state().mutex);
        if (state().records.find(descriptor) == state().records.end()) {
            state().records.emplace(descriptor,
                    NativeFdRecord{NativeFdOwnership::Inherited, policy_revision,
                                   std::move(virtual_path)});
        }
    } catch (...) {
    }
}

void NativeFdLedger::duplicate(int source, int target) {
    if (target < 0) return;
    try {
        std::lock_guard lock(state().mutex);
        const auto found = state().records.find(source);
        if (found == state().records.end()) {
            state().records.erase(target);
            return;
        }
        state().records[target] = found->second;
    } catch (...) {
    }
}

void NativeFdLedger::close(int descriptor) noexcept {
    if (descriptor < 0) return;
    std::lock_guard lock(state().mutex);
    state().records.erase(descriptor);
}

void NativeFdLedger::reset() noexcept {
    std::lock_guard lock(state().mutex);
    state().records.clear();
}

std::optional<NativeFdRecord> NativeFdLedger::lookup(int descriptor) {
    if (descriptor < 0) return std::nullopt;
    try {
        std::lock_guard lock(state().mutex);
        const auto found = state().records.find(descriptor);
        if (found == state().records.end()) return std::nullopt;
        return found->second;
    } catch (...) {
        return std::nullopt;
    }
}

bool NativeFdLedger::guest_visible(int descriptor) {
    const auto record = lookup(descriptor);
    if (!record) return false;
    return record->ownership != NativeFdOwnership::HostInternal;
}

std::vector<int> NativeFdLedger::visible_descriptors() {
    std::vector<int> result;
    std::lock_guard lock(state().mutex);
    result.reserve(state().records.size());
    for (const auto& [descriptor, record] : state().records) {
        if (record.ownership != NativeFdOwnership::HostInternal) result.push_back(descriptor);
    }
    std::sort(result.begin(), result.end());
    return result;
}

std::string NativeFdLedger::project_path(std::string_view raw_target) {
    if (raw_target.empty()) return "[virtual-fd]";
    if (kernel_pseudo_target(raw_target) || public_system_path(raw_target)) {
        return std::string(raw_target);
    }
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (policy.configured && raw_target.front() == '/') {
        const std::string virtual_path = global_policy().reverse_map_path(raw_target);
        if (virtual_path != raw_target || has_prefix(raw_target, policy.instance_root)
                || raw_target == policy.apk_path
                || (!policy.native_library_root.empty()
                    && has_prefix(raw_target, policy.native_library_root))) {
            return virtual_path;
        }
    }
    // Unknown absolute paths are inherited/host-internal observations.  Keep
    // readlink's shape but do not make the host path recoverable.
    return "[virtual-fd]";
}

std::string NativeFdLedger::project_readlink(int descriptor,
                                              std::string_view raw_target) {
    const auto record = lookup(descriptor);
    if (record && !record->virtual_path.empty()
            && record->ownership != NativeFdOwnership::HostInternal) {
        return record->virtual_path;
    }
    if (record && record->ownership == NativeFdOwnership::HostInternal) {
        return "[host-internal-fd]";
    }
    return project_path(raw_target);
}

}  // namespace controlled_sandbox
