#include "controlled_sandbox/native_policy.h"

#include <algorithm>
#include <array>
#include <arpa/inet.h>
#include <cerrno>
#include <cctype>
#include <limits.h>
#include <mutex>
#include <sstream>
#include <utility>

namespace controlled_sandbox {
namespace {

std::string lower_ascii(std::string_view value) {
    std::string out(value);
    std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    while (!out.empty() && out.back() == '.') out.pop_back();
    return out;
}

std::string upper_ascii(std::string_view value) {
    std::string out(value);
    std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) {
        return static_cast<char>(std::toupper(c));
    });
    return out;
}

bool host_matches(const std::string& host, const std::string& rule) {
    if (rule.empty()) return false;
    if (rule.front() == '.') {
        return host.size() > rule.size()
                && host.compare(host.size() - rule.size(), rule.size(), rule) == 0;
    }
    return host == rule;
}

std::optional<std::uint32_t> parse_ipv4(std::string_view value) noexcept {
    in_addr address{};
    std::string copy(value);
    if (inet_pton(AF_INET, copy.c_str(), &address) != 1) return std::nullopt;
    return ntohl(address.s_addr);
}

std::optional<std::array<std::uint8_t, 16>> parse_ipv6(std::string_view value) noexcept {
    in6_addr address{};
    std::string copy(value);
    if (inet_pton(AF_INET6, copy.c_str(), &address) != 1) return std::nullopt;
    std::array<std::uint8_t, 16> out{};
    std::copy(std::begin(address.s6_addr), std::end(address.s6_addr), out.begin());
    return out;
}

NativeNetworkIdentity normalize_network_identity(NativeNetworkIdentity value,
                                                  const std::string& package_name,
                                                  int virtual_user_id) {
    if (value.hostname.empty()) value.hostname = package_name + ".sandbox";
    if (value.interface_name.empty()) value.interface_name = "vnet0";
    if (value.ipv4_address.empty()) value.ipv4_address = "10.64." + std::to_string(virtual_user_id % 250) + ".2";
    if (value.ipv6_address.empty()) value.ipv6_address = "fd00::" + std::to_string(virtual_user_id + 2);
    if (value.hostname.size() > 253 || value.interface_name.size() > 15) {
        throw std::invalid_argument("virtual network identity is too long");
    }
    if (!parse_ipv4(value.ipv4_address) || !parse_ipv6(value.ipv6_address)) {
        throw std::invalid_argument("virtual network address is invalid");
    }
    if (value.proxy_port < 0 || value.proxy_port > 65535) {
        throw std::invalid_argument("proxy port is invalid");
    }
    if (value.network_id < 1) throw std::invalid_argument("network id is invalid");
    value.transport = upper_ascii(value.transport);
    if (value.transport != "WIFI" && value.transport != "CELLULAR"
            && value.transport != "ETHERNET" && value.transport != "VPN") {
        throw std::invalid_argument("network transport is invalid");
    }
    if (value.mtu < 576 || value.mtu > 65535) throw std::invalid_argument("network MTU is invalid");
    if (value.private_dns_server_name.size() > 253) {
        throw std::invalid_argument("private DNS server name is too long");
    }
    if (value.dns_servers.size() > 8) throw std::invalid_argument("too many DNS servers");
    for (auto& server : value.dns_servers) {
        if (server.size() > 64 || (!parse_ipv4(server) && !parse_ipv6(server))) {
            throw std::invalid_argument("DNS server address is invalid");
        }
    }
    value.proxy_host = lower_ascii(value.proxy_host);
    value.private_dns_server_name = lower_ascii(value.private_dns_server_name);
    return value;
}

bool path_has_prefix(std::string_view path, std::string_view prefix) {
    return path == prefix || (path.size() > prefix.size()
            && path.compare(0, prefix.size(), prefix) == 0
            && path[prefix.size()] == '/');
}

std::string trim_root(std::string value, const char* label) {
    if (value.empty() || value.front() != '/') {
        throw std::invalid_argument(std::string(label) + " must be absolute");
    }
    while (value.size() > 1 && value.back() == '/') value.pop_back();
    if (value.size() > PATH_MAX) throw std::invalid_argument(std::string(label) + " is too long");
    return value;
}

std::string normalize_absolute(std::string_view path) {
    if (path.empty()) throw PathPolicyError(ENOENT, "PATH_EMPTY");
    if (path.size() > PATH_MAX) throw PathPolicyError(ENAMETOOLONG, "PATH_TOO_LONG");
    if (path.find('\0') != std::string_view::npos) throw PathPolicyError(EINVAL, "PATH_NUL");
    if (path.front() != '/') throw PathPolicyError(EINVAL, "PATH_NOT_ABSOLUTE");

    std::vector<std::string> segments;
    std::stringstream input{std::string(path)};
    std::string segment;
    while (std::getline(input, segment, '/')) {
        if (segment.empty() || segment == ".") continue;
        if (segment == "..") {
            if (!segments.empty()) segments.pop_back();
            continue;
        }
        segments.push_back(segment);
    }
    std::string out = "/";
    for (std::size_t index = 0; index < segments.size(); index++) {
        if (index > 0) out.push_back('/');
        out.append(segments[index]);
    }
    if (out.size() > PATH_MAX) throw PathPolicyError(ENAMETOOLONG, "PATH_TOO_LONG");
    return out;
}

std::string append_relative(std::string_view root, std::string_view relative) {
    if (relative.empty()) return std::string(root);
    std::string combined(root);
    if (combined.back() != '/') combined.push_back('/');
    combined.append(relative);
    return normalize_absolute(combined);
}

std::string suffix_after(std::string_view path, std::string_view prefix) {
    if (path == prefix) return {};
    if (!path_has_prefix(path, prefix)) return {};
    return std::string(path.substr(prefix.size() + 1));
}

bool package_segment_matches(std::string_view segment, std::string_view package_name) {
    return segment == package_name || (segment.size() > package_name.size()
            && segment.compare(0, package_name.size(), package_name) == 0
            && segment[package_name.size()] == '-');
}

bool data_app_path_belongs_to_package(std::string_view path, std::string_view package_name,
                                      std::size_t stop_before) {
    if (!path_has_prefix(path, "/data/app")) return false;
    std::size_t cursor = std::string_view("/data/app/").size();
    while (cursor < stop_before) {
        const std::size_t slash = path.find('/', cursor);
        const std::size_t end = slash == std::string_view::npos ? path.size() : slash;
        if (end > cursor && package_segment_matches(path.substr(cursor, end - cursor), package_name)) {
            return true;
        }
        if (slash == std::string_view::npos || slash >= stop_before) break;
        cursor = slash + 1;
    }
    return false;
}

bool is_data_app_apk_alias(std::string_view path, std::string_view package_name) {
    if (!path_has_prefix(path, "/data/app")) return false;
    const std::size_t slash = path.rfind('/');
    if (slash == std::string_view::npos || path.substr(slash + 1) != "base.apk") return false;
    return data_app_path_belongs_to_package(path, package_name, slash);
}

std::string data_app_library_suffix(std::string_view path, std::string_view package_name) {
    if (!path_has_prefix(path, "/data/app")) return {};
    const std::size_t marker = path.find("/lib/");
    if (marker == std::string_view::npos
            || !data_app_path_belongs_to_package(path, package_name, marker)) return {};
    std::string_view rest = path.substr(marker + 5);
    const std::size_t abi_end = rest.find('/');
    if (abi_end == std::string_view::npos || abi_end + 1 >= rest.size()) return {};
    return std::string(rest.substr(abi_end + 1));
}

bool is_sensitive_proc_path(std::string_view path) {
    return path == "/proc/self/mem" || path == "/proc/self/pagemap"
            || path == "/proc/self/clear_refs" || path == "/proc/self/syscall";
}

bool is_private_android_root(std::string_view path) {
    if (path_has_prefix(path, "/data/data") || path_has_prefix(path, "/data/user")
            || path_has_prefix(path, "/data/user_de")) return true;
    if (!path_has_prefix(path, "/storage/emulated")) return false;
    return path.find("/Android/data") != std::string_view::npos
            || path.find("/Android/obb") != std::string_view::npos;
}

}  // namespace

PathPolicyError::PathPolicyError(int error_number, std::string message)
        : std::runtime_error(std::move(message)), error_number_(error_number) {
    if (error_number_ <= 0) error_number_ = EACCES;
}

int PathPolicyError::error_number() const noexcept { return error_number_; }

bool CidrV4::contains(std::uint32_t address) const noexcept {
    return (address & mask) == network;
}

std::optional<CidrV4> CidrV4::parse(std::string_view value) noexcept {
    const auto slash = value.find('/');
    if (slash == std::string_view::npos) return std::nullopt;
    auto address = parse_ipv4(value.substr(0, slash));
    if (!address) return std::nullopt;
    int bits = -1;
    try {
        bits = std::stoi(std::string(value.substr(slash + 1)));
    } catch (...) {
        return std::nullopt;
    }
    if (bits < 0 || bits > 32) return std::nullopt;
    const std::uint32_t mask = bits == 0 ? 0u : (0xFFFFFFFFu << (32 - bits));
    return CidrV4{*address & mask, mask};
}

bool CidrV6::contains(const std::array<std::uint8_t, 16>& address) const noexcept {
    unsigned bits = prefix_length;
    for (std::size_t index = 0; index < network.size(); index++) {
        if (bits == 0) return true;
        const unsigned compare = std::min(bits, 8U);
        const std::uint8_t mask = static_cast<std::uint8_t>(0xFFU << (8U - compare));
        if ((address[index] & mask) != (network[index] & mask)) return false;
        bits -= compare;
    }
    return true;
}

std::optional<CidrV6> CidrV6::parse(std::string_view value) noexcept {
    const auto slash = value.find('/');
    if (slash == std::string_view::npos) return std::nullopt;
    auto address = parse_ipv6(value.substr(0, slash));
    if (!address) return std::nullopt;
    int bits = -1;
    try { bits = std::stoi(std::string(value.substr(slash + 1))); } catch (...) { return std::nullopt; }
    if (bits < 0 || bits > 128) return std::nullopt;
    CidrV6 out{*address, static_cast<std::uint8_t>(bits)};
    unsigned remaining = static_cast<unsigned>(bits);
    for (std::size_t index = 0; index < out.network.size(); index++) {
        if (remaining >= 8) { remaining -= 8; continue; }
        if (remaining == 0) out.network[index] = 0;
        else { out.network[index] &= static_cast<std::uint8_t>(0xFFU << (8U - remaining)); remaining = 0; }
    }
    return out;
}

void NativePolicyEngine::configure(std::string session_id, std::uint64_t generation,
                                   std::string package_name, std::string process_name,
                                   int virtual_user_id, int virtual_uid, int virtual_pid,
                                   std::string abi_name, std::string instance_root, std::string apk_path,
                                   std::string native_library_root, bool default_network_allow,
                                   std::vector<std::string> allow_hosts,
                                   std::vector<std::string> deny_hosts,
                                   std::vector<CidrV4> allow_cidrs,
                                   std::vector<CidrV4> deny_cidrs,
                                   std::vector<CidrV6> allow_cidrs_v6,
                                   std::vector<CidrV6> deny_cidrs_v6,
                                   NativeNetworkIdentity network_identity) {
    if (session_id.empty() || session_id.size() > 128) throw std::invalid_argument("session id is invalid");
    if (generation < 1) throw std::invalid_argument("generation must be positive");
    if (package_name.empty()) throw std::invalid_argument("package name is required");
    if (process_name.empty() || process_name.size() > 255) throw std::invalid_argument("process name is invalid");
    if (virtual_user_id < 0) throw std::invalid_argument("virtual user id must be non-negative");
    if (virtual_uid < 0) throw std::invalid_argument("virtual uid must be non-negative");
    if (virtual_pid < 1) throw std::invalid_argument("virtual pid must be positive");
    if (abi_name.empty() || abi_name.size() > 32) throw std::invalid_argument("ABI name is invalid");
    instance_root = trim_root(std::move(instance_root), "instance root");
    apk_path = trim_root(std::move(apk_path), "apk path");
    if (!native_library_root.empty()) {
        native_library_root = trim_root(std::move(native_library_root), "native library root");
    }
    auto normalize_rules = [](std::vector<std::string>& values) {
        for (auto& value : values) value = lower_ascii(value);
        values.erase(std::remove_if(values.begin(), values.end(),
                [](const std::string& value) { return value.empty(); }), values.end());
        std::sort(values.begin(), values.end());
        values.erase(std::unique(values.begin(), values.end()), values.end());
    };
    normalize_rules(allow_hosts);
    normalize_rules(deny_hosts);
    network_identity = normalize_network_identity(std::move(network_identity), package_name, virtual_user_id);

    std::unique_lock lock(mutex_);
    if (configured_) {
        if (session_id != session_id_) throw std::logic_error("NATIVE_POLICY_SESSION_ACTIVE");
        if (generation < generation_) throw std::logic_error("STALE_NATIVE_POLICY_GENERATION");
        if (package_name != package_name_ || process_name != process_name_
                || virtual_user_id != virtual_user_id_ || virtual_uid != virtual_uid_
                || virtual_pid != virtual_pid_ || abi_name != abi_name_
                || instance_root != instance_root_ || apk_path != apk_path_
                || native_library_root != native_library_root_
                || network_identity.hostname != network_identity_.hostname
                || network_identity.interface_name != network_identity_.interface_name
                || network_identity.ipv4_address != network_identity_.ipv4_address
                || network_identity.ipv6_address != network_identity_.ipv6_address
                || network_identity.proxy_host != network_identity_.proxy_host
                || network_identity.proxy_port != network_identity_.proxy_port
                || network_identity.cleartext_permitted != network_identity_.cleartext_permitted
                || network_identity.network_id != network_identity_.network_id
                || network_identity.transport != network_identity_.transport
                || network_identity.vpn_active != network_identity_.vpn_active
                || network_identity.metered != network_identity_.metered
                || network_identity.validated != network_identity_.validated
                || network_identity.mtu != network_identity_.mtu
                || network_identity.private_dns_server_name != network_identity_.private_dns_server_name
                || network_identity.dns_servers != network_identity_.dns_servers) {
            throw std::logic_error("NATIVE_POLICY_IDENTITY_CHANGED_WITHIN_SESSION");
        }
    }
    session_id_ = std::move(session_id);
    generation_ = generation;
    revision_++;
    package_name_ = std::move(package_name);
    process_name_ = std::move(process_name);
    virtual_user_id_ = virtual_user_id;
    virtual_uid_ = virtual_uid;
    virtual_pid_ = virtual_pid;
    abi_name_ = std::move(abi_name);
    instance_root_ = std::move(instance_root);
    apk_path_ = std::move(apk_path);
    native_library_root_ = std::move(native_library_root);
    default_network_allow_ = default_network_allow;
    allow_hosts_ = std::move(allow_hosts);
    deny_hosts_ = std::move(deny_hosts);
    allow_cidrs_ = std::move(allow_cidrs);
    deny_cidrs_ = std::move(deny_cidrs);
    allow_cidrs_v6_ = std::move(allow_cidrs_v6);
    deny_cidrs_v6_ = std::move(deny_cidrs_v6);
    network_identity_ = std::move(network_identity);
    configured_ = true;
}

void NativePolicyEngine::reset() noexcept {
    std::unique_lock lock(mutex_);
    session_id_.clear();
    generation_ = 0;
    package_name_.clear();
    process_name_.clear();
    virtual_user_id_ = 0;
    virtual_uid_ = 0;
    virtual_pid_ = 0;
    abi_name_.clear();
    instance_root_.clear();
    apk_path_.clear();
    native_library_root_.clear();
    default_network_allow_ = true;
    allow_hosts_.clear();
    deny_hosts_.clear();
    allow_cidrs_.clear();
    deny_cidrs_.clear();
    allow_cidrs_v6_.clear();
    deny_cidrs_v6_.clear();
    network_identity_ = {};
    configured_ = false;
    revision_++;
}

NativePathDecision NativePolicyEngine::resolve_path(std::string_view guest_path) const {
    const std::string normalized = normalize_absolute(guest_path);
    std::shared_lock lock(mutex_);
    if (!configured_) throw PathPolicyError(EACCES, "NATIVE_POLICY_NOT_CONFIGURED");
    if (is_sensitive_proc_path(normalized)) throw PathPolicyError(EACCES, "PROC_SELF_PATH_DENIED");

    const std::string data_data = "/data/data/" + package_name_;
    const std::string data_user = "/data/user/" + std::to_string(virtual_user_id_) + "/" + package_name_;
    const std::string data_user_zero = "/data/user/0/" + package_name_;
    const std::string data_user_de = "/data/user_de/" + std::to_string(virtual_user_id_) + "/" + package_name_;
    const std::string external = "/storage/emulated/" + std::to_string(virtual_user_id_)
            + "/Android/data/" + package_name_;
    const std::string data_target = instance_root_ + "/data";
    const std::string external_target = instance_root_ + "/external";

    const std::array<std::string, 4> private_prefixes{data_data, data_user, data_user_zero, data_user_de};
    for (const auto& prefix : private_prefixes) {
        if (!path_has_prefix(guest_path, prefix)) continue;
        if (!path_has_prefix(normalized, prefix)) throw PathPolicyError(EACCES, "PATH_TRAVERSAL");
        const std::string relative = suffix_after(normalized, prefix);
        if (relative == "lib" || path_has_prefix(relative, "lib")) {
            if (native_library_root_.empty()) throw PathPolicyError(ENOENT, "NATIVE_LIBRARY_ROOT_MISSING");
            const std::string library_relative = relative == "lib" ? std::string{} : relative.substr(4);
            return NativePathDecision{append_relative(native_library_root_, library_relative),
                    native_library_root_, revision_, true};
        }
        return NativePathDecision{append_relative(data_target, relative), instance_root_, revision_, true};
    }

    if (path_has_prefix(guest_path, external)) {
        if (!path_has_prefix(normalized, external)) throw PathPolicyError(EACCES, "PATH_TRAVERSAL");
        return NativePathDecision{append_relative(external_target, suffix_after(normalized, external)),
                instance_root_, revision_, true};
    }

    if (is_data_app_apk_alias(normalized, package_name_)) {
        return NativePathDecision{apk_path_, {}, revision_, true};
    }
    const std::string library_suffix = data_app_library_suffix(normalized, package_name_);
    if (!library_suffix.empty()) {
        if (native_library_root_.empty()) throw PathPolicyError(ENOENT, "NATIVE_LIBRARY_ROOT_MISSING");
        return NativePathDecision{append_relative(native_library_root_, library_suffix),
                native_library_root_, revision_, true};
    }

    if (path_has_prefix(normalized, instance_root_)) {
        return NativePathDecision{normalized, instance_root_, revision_, false};
    }
    if (normalized == apk_path_) return NativePathDecision{normalized, {}, revision_, false};
    if (!native_library_root_.empty() && path_has_prefix(normalized, native_library_root_)) {
        return NativePathDecision{normalized, native_library_root_, revision_, false};
    }

    if (path_has_prefix(normalized, "/data/app")) {
        throw PathPolicyError(EACCES, "CROSS_PACKAGE_APK_PATH_DENIED");
    }
    if (is_private_android_root(normalized)) {
        throw PathPolicyError(EACCES, "CROSS_PACKAGE_PRIVATE_PATH_DENIED");
    }
    return NativePathDecision{normalized, {}, revision_, false};
}

std::string NativePolicyEngine::map_path(std::string_view guest_path) const {
    return resolve_path(guest_path).path;
}

std::string NativePolicyEngine::reverse_map_path(std::string_view host_path) const {
    if (host_path.empty() || host_path.front() != '/') return std::string(host_path);
    const std::string normalized = normalize_absolute(host_path);
    std::shared_lock lock(mutex_);
    if (!configured_) return normalized;
    if (normalized == apk_path_) return "/data/app/" + package_name_ + "/base.apk";
    if (!native_library_root_.empty() && path_has_prefix(normalized, native_library_root_)) {
        const std::string suffix = suffix_after(normalized, native_library_root_);
        return append_relative("/data/app/" + package_name_ + "/lib/" + abi_name_, suffix);
    }
    const std::string data_target = instance_root_ + "/data";
    if (path_has_prefix(normalized, data_target)) {
        const std::string virtual_root = "/data/user/" + std::to_string(virtual_user_id_) + "/" + package_name_;
        return append_relative(virtual_root, suffix_after(normalized, data_target));
    }
    const std::string external_target = instance_root_ + "/external";
    if (path_has_prefix(normalized, external_target)) {
        const std::string virtual_root = "/storage/emulated/" + std::to_string(virtual_user_id_)
                + "/Android/data/" + package_name_;
        return append_relative(virtual_root, suffix_after(normalized, external_target));
    }
    return normalized;
}

bool NativePolicyEngine::allow_host(std::string_view host_value) const {
    const std::string host = lower_ascii(host_value);
    if (host.empty()) return false;
    std::shared_lock lock(mutex_);
    if (!configured_) return false;
    for (const auto& rule : deny_hosts_) if (host_matches(host, rule)) return false;
    for (const auto& rule : allow_hosts_) if (host_matches(host, rule)) return true;
    return default_network_allow_;
}

bool NativePolicyEngine::allow_ipv4(std::string_view address_value) const {
    auto address = parse_ipv4(address_value);
    if (!address) return false;
    std::shared_lock lock(mutex_);
    if (!configured_) return false;
    for (const auto& range : deny_cidrs_) if (range.contains(*address)) return false;
    for (const auto& range : allow_cidrs_) if (range.contains(*address)) return true;
    return default_network_allow_;
}

bool NativePolicyEngine::allow_ipv6(std::string_view address_value) const {
    auto address = parse_ipv6(address_value);
    if (!address) return false;
    std::shared_lock lock(mutex_);
    if (!configured_) return false;
    for (const auto& range : deny_cidrs_v6_) if (range.contains(*address)) return false;
    for (const auto& range : allow_cidrs_v6_) if (range.contains(*address)) return true;
    return default_network_allow_;
}

NativeNetworkIdentity NativePolicyEngine::network_identity() const {
    std::shared_lock lock(mutex_);
    if (!configured_) throw std::logic_error("NATIVE_POLICY_NOT_CONFIGURED");
    return network_identity_;
}

bool NativePolicyEngine::configured() const noexcept {
    std::shared_lock lock(mutex_);
    return configured_;
}

NativePolicySnapshot NativePolicyEngine::snapshot() const {
    std::shared_lock lock(mutex_);
    return NativePolicySnapshot{configured_, session_id_, generation_, revision_, package_name_,
            process_name_, virtual_user_id_, virtual_uid_, virtual_pid_, abi_name_,
            instance_root_, apk_path_, native_library_root_, network_identity_};
}

NativePolicyEngine& global_policy() {
    static NativePolicyEngine policy;
    return policy;
}

}  // namespace controlled_sandbox
