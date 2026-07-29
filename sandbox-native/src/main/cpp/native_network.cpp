#include "controlled_sandbox/native_network.h"
#include "controlled_sandbox/native_policy.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sstream>
#include <unordered_set>

#ifndef SOCK_TYPE_MASK
#define SOCK_TYPE_MASK 0x0f
#endif

namespace controlled_sandbox {
namespace {

constexpr std::size_t MAX_TRACKED_SOCKETS = 2048;
constexpr unsigned int LOOPBACK_INDEX = 1;
constexpr unsigned int VIRTUAL_INDEX = 100;

struct VirtualSocketState {
    int domain{};
    int type{};
    int protocol{};
    std::uint64_t policy_revision{};
    std::string bound_interface;
};

std::mutex projected_mutex;
std::unordered_set<ifaddrs*> projected_heads;
std::mutex sockets_mutex;
std::map<int, VirtualSocketState> sockets;
std::uint64_t allowed_endpoints{};
std::uint64_t denied_endpoints{};
std::uint64_t projected_local_addresses{};
std::uint64_t denied_socket_options{};

void count_endpoint(bool allowed) {
    std::lock_guard lock(sockets_mutex);
    if (allowed) allowed_endpoints++;
    else denied_endpoints++;
}

void count_projection() {
    std::lock_guard lock(sockets_mutex);
    projected_local_addresses++;
}

void count_option_denial() {
    std::lock_guard lock(sockets_mutex);
    denied_socket_options++;
}

void free_node(ifaddrs* node) noexcept {
    if (node == nullptr) return;
    std::free(node->ifa_name);
    std::free(node->ifa_addr);
    std::free(node->ifa_netmask);
    std::free(node->ifa_broadaddr);
    std::free(node);
}

void free_list(ifaddrs* head) noexcept {
    while (head != nullptr) {
        ifaddrs* next = head->ifa_next;
        free_node(head);
        head = next;
    }
}

ifaddrs* ipv4_node(const char* name, const char* address, const char* mask, unsigned flags) {
    auto* node = static_cast<ifaddrs*>(std::calloc(1, sizeof(ifaddrs)));
    auto* addr = static_cast<sockaddr_in*>(std::calloc(1, sizeof(sockaddr_in)));
    auto* netmask = static_cast<sockaddr_in*>(std::calloc(1, sizeof(sockaddr_in)));
    if (node == nullptr || addr == nullptr || netmask == nullptr) {
        std::free(node); std::free(addr); std::free(netmask); return nullptr;
    }
    node->ifa_name = ::strdup(name);
    if (node->ifa_name == nullptr) { free_node(node); return nullptr; }
    addr->sin_family = AF_INET;
    netmask->sin_family = AF_INET;
    if (inet_pton(AF_INET, address, &addr->sin_addr) != 1
            || inet_pton(AF_INET, mask, &netmask->sin_addr) != 1) {
        free_node(node); return nullptr;
    }
    node->ifa_flags = flags;
    node->ifa_addr = reinterpret_cast<sockaddr*>(addr);
    node->ifa_netmask = reinterpret_cast<sockaddr*>(netmask);
    return node;
}

ifaddrs* ipv6_node(const char* name, const char* address, unsigned flags) {
    auto* node = static_cast<ifaddrs*>(std::calloc(1, sizeof(ifaddrs)));
    auto* addr = static_cast<sockaddr_in6*>(std::calloc(1, sizeof(sockaddr_in6)));
    auto* netmask = static_cast<sockaddr_in6*>(std::calloc(1, sizeof(sockaddr_in6)));
    if (node == nullptr || addr == nullptr || netmask == nullptr) {
        std::free(node); std::free(addr); std::free(netmask); return nullptr;
    }
    node->ifa_name = ::strdup(name);
    if (node->ifa_name == nullptr) { free_node(node); return nullptr; }
    addr->sin6_family = AF_INET6;
    netmask->sin6_family = AF_INET6;
    std::memset(&netmask->sin6_addr, 0xFF, sizeof(netmask->sin6_addr));
    if (inet_pton(AF_INET6, address, &addr->sin6_addr) != 1) {
        free_node(node); return nullptr;
    }
    node->ifa_flags = flags;
    node->ifa_addr = reinterpret_cast<sockaddr*>(addr);
    node->ifa_netmask = reinterpret_cast<sockaddr*>(netmask);
    return node;
}

bool address_equals(const sockaddr* address, const NativeNetworkIdentity& identity) {
    if (address == nullptr) return false;
    if (address->sa_family == AF_INET) {
        in_addr configured{};
        if (inet_pton(AF_INET, identity.ipv4_address.c_str(), &configured) != 1) return false;
        return reinterpret_cast<const sockaddr_in*>(address)->sin_addr.s_addr == configured.s_addr;
    }
    if (address->sa_family == AF_INET6) {
        in6_addr configured{};
        if (inet_pton(AF_INET6, identity.ipv6_address.c_str(), &configured) != 1) return false;
        return std::memcmp(&reinterpret_cast<const sockaddr_in6*>(address)->sin6_addr,
                           &configured, sizeof(configured)) == 0;
    }
    return false;
}

bool unspecified_or_loopback(const sockaddr* address) {
    if (address == nullptr) return false;
    if (address->sa_family == AF_INET) {
        const std::uint32_t host = ntohl(reinterpret_cast<const sockaddr_in*>(address)->sin_addr.s_addr);
        return host == INADDR_ANY || (host >> 24U) == 127U;
    }
    if (address->sa_family == AF_INET6) {
        const auto& value = reinterpret_cast<const sockaddr_in6*>(address)->sin6_addr;
        return IN6_IS_ADDR_UNSPECIFIED(&value) || IN6_IS_ADDR_LOOPBACK(&value);
    }
    return address->sa_family == AF_UNIX;
}

unsigned int destination_port(const sockaddr* address) {
    if (address == nullptr) return 0;
    if (address->sa_family == AF_INET) return ntohs(reinterpret_cast<const sockaddr_in*>(address)->sin_port);
    if (address->sa_family == AF_INET6) return ntohs(reinterpret_cast<const sockaddr_in6*>(address)->sin6_port);
    return 0;
}

bool known_cleartext_port(unsigned int port) {
    return port == 80 || port == 81 || port == 8000 || port == 8080 || port == 8888;
}

int socket_type_for(int socket_fd) {
    {
        std::lock_guard lock(sockets_mutex);
        auto found = sockets.find(socket_fd);
        if (found != sockets.end()) return found->second.type & SOCK_TYPE_MASK;
    }
    int type = 0;
    socklen_t length = sizeof(type);
    if (::getsockopt(socket_fd, SOL_SOCKET, SO_TYPE, &type, &length) == 0) return type;
    return 0;
}

bool is_virtual_interface(const char* name, const NativeNetworkIdentity& identity) {
    return name != nullptr && (identity.interface_name == name || std::strcmp(name, "lo") == 0);
}

}  // namespace

bool native_socket_address_allowed(const sockaddr* address, socklen_t length) noexcept {
    if (address == nullptr) return false;
    try {
        if (address->sa_family == AF_INET && length >= sizeof(sockaddr_in)) {
            const auto* value = reinterpret_cast<const sockaddr_in*>(address);
            char text[INET_ADDRSTRLEN]{};
            return inet_ntop(AF_INET, &value->sin_addr, text, sizeof(text)) != nullptr
                    && global_policy().allow_ipv4(text);
        }
        if (address->sa_family == AF_INET6 && length >= sizeof(sockaddr_in6)) {
            const auto* value = reinterpret_cast<const sockaddr_in6*>(address);
            char text[INET6_ADDRSTRLEN]{};
            return inet_ntop(AF_INET6, &value->sin6_addr, text, sizeof(text)) != nullptr
                    && global_policy().allow_ipv6(text);
        }
        return address->sa_family == AF_UNIX;
    } catch (...) {
        return false;
    }
}

bool native_socket_destination_allowed(int socket_fd, const sockaddr* address, socklen_t length) noexcept {
    const bool address_allowed = native_socket_address_allowed(address, length);
    if (!address_allowed) { count_endpoint(false); return false; }
    try {
        const NativeNetworkIdentity identity = global_policy().network_identity();
        if (!identity.cleartext_permitted && socket_type_for(socket_fd) == SOCK_STREAM
                && known_cleartext_port(destination_port(address))) {
            count_endpoint(false);
            return false;
        }
        count_endpoint(true);
        return true;
    } catch (...) {
        count_endpoint(false);
        return false;
    }
}

bool native_register_socket(int socket_fd, int domain, int type, int protocol) noexcept {
    if (socket_fd < 0) return false;
    try {
        const NativePolicySnapshot policy = global_policy().snapshot();
        if (!policy.configured) return false;
        std::lock_guard lock(sockets_mutex);
        if (sockets.size() >= MAX_TRACKED_SOCKETS && sockets.find(socket_fd) == sockets.end()) return false;
        sockets[socket_fd] = VirtualSocketState{domain, type, protocol, policy.revision, {}};
        return true;
    } catch (...) {
        return false;
    }
}

void native_unregister_socket(int socket_fd) noexcept {
    std::lock_guard lock(sockets_mutex);
    sockets.erase(socket_fd);
}

int native_project_bind_address(const sockaddr* requested, socklen_t requested_length,
                                sockaddr_storage* projected, socklen_t* projected_length) noexcept {
    if (requested == nullptr || projected == nullptr || projected_length == nullptr) {
        errno = EFAULT;
        return -1;
    }
    try {
        const NativeNetworkIdentity identity = global_policy().network_identity();
        if (requested->sa_family == AF_INET && requested_length >= sizeof(sockaddr_in)) {
            const auto* source = reinterpret_cast<const sockaddr_in*>(requested);
            if (!unspecified_or_loopback(requested) && !address_equals(requested, identity)) {
                errno = EADDRNOTAVAIL;
                return -1;
            }
            auto* target = reinterpret_cast<sockaddr_in*>(projected);
            std::memset(target, 0, sizeof(*target));
            *target = *source;
            if (address_equals(requested, identity)) target->sin_addr.s_addr = htonl(INADDR_ANY);
            *projected_length = sizeof(*target);
            return 0;
        }
        if (requested->sa_family == AF_INET6 && requested_length >= sizeof(sockaddr_in6)) {
            const auto* source = reinterpret_cast<const sockaddr_in6*>(requested);
            if (!unspecified_or_loopback(requested) && !address_equals(requested, identity)) {
                errno = EADDRNOTAVAIL;
                return -1;
            }
            auto* target = reinterpret_cast<sockaddr_in6*>(projected);
            std::memset(target, 0, sizeof(*target));
            *target = *source;
            if (address_equals(requested, identity)) target->sin6_addr = in6addr_any;
            *projected_length = sizeof(*target);
            return 0;
        }
        if (requested->sa_family == AF_UNIX) {
            if (requested_length > sizeof(*projected)) { errno = EINVAL; return -1; }
            std::memcpy(projected, requested, requested_length);
            *projected_length = requested_length;
            return 0;
        }
        errno = EAFNOSUPPORT;
        return -1;
    } catch (...) {
        errno = EACCES;
        return -1;
    }
}

void native_project_local_address(sockaddr* address, socklen_t length) noexcept {
    if (address == nullptr) return;
    try {
        const NativeNetworkIdentity identity = global_policy().network_identity();
        if (address->sa_family == AF_INET && length >= sizeof(sockaddr_in)) {
            auto* value = reinterpret_cast<sockaddr_in*>(address);
            if ((ntohl(value->sin_addr.s_addr) >> 24U) != 127U
                    && inet_pton(AF_INET, identity.ipv4_address.c_str(), &value->sin_addr) == 1) {
                count_projection();
            }
        } else if (address->sa_family == AF_INET6 && length >= sizeof(sockaddr_in6)) {
            auto* value = reinterpret_cast<sockaddr_in6*>(address);
            if (!IN6_IS_ADDR_LOOPBACK(&value->sin6_addr)
                    && inet_pton(AF_INET6, identity.ipv6_address.c_str(), &value->sin6_addr) == 1) {
                count_projection();
            }
        }
    } catch (...) { }
}

int native_set_virtual_socket_option(int socket_fd, int level, int option_name,
                                     const void* option_value, socklen_t option_length,
                                     bool* handled) noexcept {
    if (handled == nullptr) { errno = EFAULT; return -1; }
    *handled = false;
    try {
        const NativeNetworkIdentity identity = global_policy().network_identity();
#ifdef SO_BINDTODEVICE
        if (level == SOL_SOCKET && option_name == SO_BINDTODEVICE) {
            *handled = true;
            if (option_value == nullptr || option_length == 0 || option_length > IFNAMSIZ) {
                errno = EINVAL; return -1;
            }
            const auto* bytes = static_cast<const char*>(option_value);
            const std::size_t length = strnlen(bytes, option_length);
            const std::string requested(bytes, length);
            if (!is_virtual_interface(requested.c_str(), identity)) {
                count_option_denial(); errno = ENODEV; return -1;
            }
            std::lock_guard lock(sockets_mutex);
            auto found = sockets.find(socket_fd);
            if (found == sockets.end()) { errno = EBADF; return -1; }
            found->second.bound_interface = requested;
            return 0;
        }
#endif
#ifdef SO_MARK
        if (level == SOL_SOCKET && option_name == SO_MARK) {
            *handled = true; count_option_denial(); errno = EPERM; return -1;
        }
#endif
#ifdef IP_TRANSPARENT
        if (level == SOL_IP && option_name == IP_TRANSPARENT) {
            *handled = true; count_option_denial(); errno = EPERM; return -1;
        }
#endif
#ifdef IPV6_TRANSPARENT
        if (level == SOL_IPV6 && option_name == IPV6_TRANSPARENT) {
            *handled = true; count_option_denial(); errno = EPERM; return -1;
        }
#endif
        return 0;
    } catch (...) {
        *handled = true;
        errno = EACCES;
        return -1;
    }
}

int native_get_virtual_socket_option(int socket_fd, int level, int option_name,
                                     void* option_value, socklen_t* option_length,
                                     bool* handled) noexcept {
    if (handled == nullptr) { errno = EFAULT; return -1; }
    *handled = false;
    try {
#ifdef SO_BINDTODEVICE
        if (level == SOL_SOCKET && option_name == SO_BINDTODEVICE) {
            *handled = true;
            if (option_value == nullptr || option_length == nullptr) { errno = EFAULT; return -1; }
            const NativeNetworkIdentity identity = global_policy().network_identity();
            std::string name = identity.interface_name;
            {
                std::lock_guard lock(sockets_mutex);
                auto found = sockets.find(socket_fd);
                if (found == sockets.end()) { errno = EBADF; return -1; }
                if (!found->second.bound_interface.empty()) name = found->second.bound_interface;
            }
            if (*option_length < name.size() + 1) { errno = EINVAL; return -1; }
            std::memcpy(option_value, name.c_str(), name.size() + 1);
            *option_length = static_cast<socklen_t>(name.size() + 1);
            return 0;
        }
#endif
#ifdef SO_MARK
        if (level == SOL_SOCKET && option_name == SO_MARK) {
            *handled = true; count_option_denial(); errno = EPERM; return -1;
        }
#endif
        return 0;
    } catch (...) {
        *handled = true;
        errno = EACCES;
        return -1;
    }
}

std::string native_virtual_hostname() {
    return global_policy().network_identity().hostname;
}

int native_project_ifaddrs(ifaddrs** result) noexcept {
    if (result == nullptr) { errno = EFAULT; return -1; }
    *result = nullptr;
    try {
        NativeNetworkIdentity identity = global_policy().network_identity();
        const unsigned virtual_flags = IFF_UP | IFF_RUNNING
                | (identity.vpn_active ? IFF_POINTOPOINT : IFF_BROADCAST);
        ifaddrs* loopback = ipv4_node("lo", "127.0.0.1", "255.0.0.0", IFF_UP | IFF_LOOPBACK | IFF_RUNNING);
        ifaddrs* ipv4 = ipv4_node(identity.interface_name.c_str(), identity.ipv4_address.c_str(),
                                 "255.255.255.0", virtual_flags);
        ifaddrs* ipv6 = ipv6_node(identity.interface_name.c_str(), identity.ipv6_address.c_str(), virtual_flags);
        if (loopback == nullptr || ipv4 == nullptr || ipv6 == nullptr) {
            free_list(loopback); free_list(ipv4); free_list(ipv6); errno = ENOMEM; return -1;
        }
        loopback->ifa_next = ipv4;
        ipv4->ifa_next = ipv6;
        {
            std::lock_guard lock(projected_mutex);
            if (projected_heads.size() >= 64) {
                free_list(loopback); errno = EMFILE; return -1;
            }
            projected_heads.insert(loopback);
        }
        *result = loopback;
        return 0;
    } catch (...) {
        errno = EACCES;
        return -1;
    }
}

bool native_free_projected_ifaddrs(ifaddrs* value) noexcept {
    if (value == nullptr) return true;
    {
        std::lock_guard lock(projected_mutex);
        auto found = projected_heads.find(value);
        if (found == projected_heads.end()) return false;
        projected_heads.erase(found);
    }
    free_list(value);
    return true;
}

unsigned int native_virtual_if_nametoindex(const char* name) noexcept {
    try {
        const NativeNetworkIdentity identity = global_policy().network_identity();
        if (name == nullptr) return 0;
        if (std::strcmp(name, "lo") == 0) return LOOPBACK_INDEX;
        if (identity.interface_name == name) return VIRTUAL_INDEX;
        errno = ENODEV;
        return 0;
    } catch (...) {
        errno = EACCES;
        return 0;
    }
}

char* native_virtual_if_indextoname(unsigned int index, char* name) noexcept {
    if (name == nullptr) { errno = EFAULT; return nullptr; }
    try {
        const NativeNetworkIdentity identity = global_policy().network_identity();
        const char* source = nullptr;
        if (index == LOOPBACK_INDEX) source = "lo";
        else if (index == VIRTUAL_INDEX) source = identity.interface_name.c_str();
        else { errno = ENXIO; return nullptr; }
        std::strncpy(name, source, IFNAMSIZ - 1);
        name[IFNAMSIZ - 1] = '\0';
        return name;
    } catch (...) {
        errno = EACCES;
        return nullptr;
    }
}

NativeNetworkStatus native_network_status() noexcept {
    std::lock_guard lock(sockets_mutex);
    return NativeNetworkStatus{sockets.size(), allowed_endpoints, denied_endpoints,
            projected_local_addresses, denied_socket_options};
}

std::string native_network_status_string() {
    const NativeNetworkStatus status = native_network_status();
    const NativeNetworkIdentity identity = global_policy().network_identity();
    std::ostringstream out;
    out << "networkId=" << identity.network_id
        << ";transport=" << identity.transport
        << ";vpn=" << (identity.vpn_active ? "true" : "false")
        << ";metered=" << (identity.metered ? "true" : "false")
        << ";validated=" << (identity.validated ? "true" : "false")
        << ";mtu=" << identity.mtu
        << ";interface=" << identity.interface_name
        << ";proxy=" << identity.proxy_host << ':' << identity.proxy_port
        << ";privateDns=" << identity.private_dns_server_name
        << ";dnsCount=" << identity.dns_servers.size()
        << ";trackedSockets=" << status.tracked_sockets
        << ";allowedEndpoints=" << status.allowed_endpoints
        << ";deniedEndpoints=" << status.denied_endpoints
        << ";projectedLocal=" << status.projected_local_addresses
        << ";deniedOptions=" << status.denied_socket_options;
    return out.str();
}

}  // namespace controlled_sandbox
