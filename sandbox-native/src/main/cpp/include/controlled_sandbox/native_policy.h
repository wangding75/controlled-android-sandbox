#pragma once

#include <cstdint>
#include <optional>
#include <shared_mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace controlled_sandbox {

struct CidrV4 {
    std::uint32_t network{};
    std::uint32_t mask{};
    bool contains(std::uint32_t address) const noexcept;
    static std::optional<CidrV4> parse(std::string_view value) noexcept;
};

class PathPolicyError final : public std::runtime_error {
public:
    PathPolicyError(int error_number, std::string message);
    [[nodiscard]] int error_number() const noexcept;

private:
    int error_number_;
};

struct NativePathDecision {
    std::string path;
    std::string confinement_root;
    std::uint64_t policy_revision{};
    bool rewritten{false};
};

struct NativePolicySnapshot {
    bool configured{false};
    std::string session_id;
    std::uint64_t generation{};
    std::uint64_t revision{};
    std::string package_name;
    std::string process_name;
    int virtual_user_id{};
    int virtual_uid{};
    int virtual_pid{};
    std::string abi_name;
    std::string instance_root;
    std::string apk_path;
    std::string native_library_root;
};

class NativePolicyEngine final {
public:
    void configure(std::string session_id, std::uint64_t generation,
                   std::string package_name, std::string process_name,
                   int virtual_user_id, int virtual_uid, int virtual_pid,
                   std::string abi_name, std::string instance_root, std::string apk_path,
                   std::string native_library_root, bool default_network_allow,
                   std::vector<std::string> allow_hosts,
                   std::vector<std::string> deny_hosts,
                   std::vector<CidrV4> allow_cidrs,
                   std::vector<CidrV4> deny_cidrs);

    void reset() noexcept;

    [[nodiscard]] NativePathDecision resolve_path(std::string_view guest_path) const;
    [[nodiscard]] std::string map_path(std::string_view guest_path) const;
    [[nodiscard]] std::string reverse_map_path(std::string_view host_path) const;
    [[nodiscard]] bool allow_host(std::string_view host) const;
    [[nodiscard]] bool allow_ipv4(std::string_view address) const;
    [[nodiscard]] bool configured() const noexcept;
    [[nodiscard]] NativePolicySnapshot snapshot() const;

private:
    mutable std::shared_mutex mutex_;
    std::string session_id_;
    std::uint64_t generation_{};
    std::uint64_t revision_{};
    std::string package_name_;
    std::string process_name_;
    int virtual_user_id_{};
    int virtual_uid_{};
    int virtual_pid_{};
    std::string abi_name_;
    std::string instance_root_;
    std::string apk_path_;
    std::string native_library_root_;
    bool default_network_allow_{true};
    bool configured_{false};
    std::vector<std::string> allow_hosts_;
    std::vector<std::string> deny_hosts_;
    std::vector<CidrV4> allow_cidrs_;
    std::vector<CidrV4> deny_cidrs_;
};

NativePolicyEngine& global_policy();

}  // namespace controlled_sandbox
