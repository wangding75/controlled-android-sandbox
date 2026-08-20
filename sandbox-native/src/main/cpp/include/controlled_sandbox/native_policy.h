#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <shared_mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>
#include <utility>
#include <vector>

namespace controlled_sandbox {

struct CidrV4 {
    std::uint32_t network{};
    std::uint32_t mask{};
    bool contains(std::uint32_t address) const noexcept;
    static std::optional<CidrV4> parse(std::string_view value) noexcept;
};

struct CidrV6 {
    std::array<std::uint8_t, 16> network{};
    std::uint8_t prefix_length{};
    bool contains(const std::array<std::uint8_t, 16>& address) const noexcept;
    static std::optional<CidrV6> parse(std::string_view value) noexcept;
};

struct NativeNetworkIdentity {
    std::string hostname;
    std::string interface_name;
    std::string ipv4_address;
    std::string ipv6_address;
    std::string proxy_host;
    int proxy_port{};
    bool cleartext_permitted{true};
    int network_id{100};
    std::string transport{"WIFI"};
    bool vpn_active{false};
    bool metered{false};
    bool validated{true};
    int mtu{1500};
    std::string private_dns_server_name;
    std::vector<std::string> dns_servers;
};

class PathPolicyError final : public std::runtime_error {
public:
    PathPolicyError(int error_number, std::string message);
    [[nodiscard]] int error_number() const noexcept;

private:
    int error_number_;
};

struct NativePathDecision {
    NativePathDecision() = default;
    NativePathDecision(std::string path_value, std::string confinement_root_value,
                       std::uint64_t policy_revision_value,
                       bool rewritten_value = false, int directory_fd_value = -100,
                       bool capability_value = false, std::string virtual_path_value = {})
        : path(std::move(path_value)),
          confinement_root(std::move(confinement_root_value)),
          policy_revision(policy_revision_value),
          rewritten(rewritten_value),
          directory_fd(directory_fd_value),
          capability(capability_value),
          virtual_path(std::move(virtual_path_value)) {}

    std::string path;
    std::string confinement_root;
    std::uint64_t policy_revision{};
    bool rewritten{false};
    int directory_fd{-100};
    bool capability{false};
    std::string virtual_path;
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
    std::string guest_cwd;
    NativeNetworkIdentity network_identity;
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
                   std::vector<CidrV4> deny_cidrs,
                   std::vector<CidrV6> allow_cidrs_v6 = {},
                   std::vector<CidrV6> deny_cidrs_v6 = {},
                   NativeNetworkIdentity network_identity = {});

    /** Installs duplicated Binder directory capabilities for an isolated Guest process. */
    void configure_file_capabilities(int data_root_fd, int apk_file_fd,
                                     std::string apk_entry_name, int native_library_fd);
    void clear_file_capabilities() noexcept;
    [[nodiscard]] bool file_capabilities_configured() const noexcept;
    [[nodiscard]] bool is_capability_fd(int descriptor) const noexcept;
    [[nodiscard]] bool is_capability_file_fd(int descriptor) const noexcept;
    void register_capability_fd(int descriptor) noexcept;
    void unregister_capability_fd(int descriptor) noexcept;
    [[nodiscard]] NativePathDecision resolve_capability_relative(
            int directory_fd, std::string_view relative_path) const;
    [[nodiscard]] int capability_data_root_fd() const noexcept;
    [[nodiscard]] bool revision_current(std::uint64_t revision) const noexcept;
    [[nodiscard]] std::string guest_cwd() const;
    void set_guest_cwd(std::string guest_path);

    void reset() noexcept;

    [[nodiscard]] NativePathDecision resolve_path(std::string_view guest_path) const;
    [[nodiscard]] std::string map_path(std::string_view guest_path) const;
    [[nodiscard]] std::string reverse_map_path(std::string_view host_path) const;
    [[nodiscard]] bool allow_host(std::string_view host) const;
    [[nodiscard]] bool allow_ipv4(std::string_view address) const;
    [[nodiscard]] bool allow_ipv6(std::string_view address) const;
    [[nodiscard]] NativeNetworkIdentity network_identity() const;
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
    std::string guest_cwd_{"/"};
    bool default_network_allow_{true};
    bool configured_{false};
    std::vector<std::string> allow_hosts_;
    std::vector<std::string> deny_hosts_;
    std::vector<CidrV4> allow_cidrs_;
    std::vector<CidrV4> deny_cidrs_;
    std::vector<CidrV6> allow_cidrs_v6_;
    std::vector<CidrV6> deny_cidrs_v6_;
    NativeNetworkIdentity network_identity_;
    int data_root_fd_{-1};
    int apk_file_fd_{-1};
    std::string apk_entry_name_;
    int native_library_fd_{-1};
    std::unordered_set<int> capability_fds_;
};

NativePolicyEngine& global_policy();

}  // namespace controlled_sandbox
