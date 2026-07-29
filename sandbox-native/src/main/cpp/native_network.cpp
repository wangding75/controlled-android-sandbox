#include "controlled_sandbox/native_network.h"
#include "controlled_sandbox/native_policy.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <net/if.h>
#include <unordered_set>

namespace controlled_sandbox {
namespace {

std::mutex projected_mutex;
std::unordered_set<ifaddrs*> projected_heads;

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

std::string native_virtual_hostname() {
    return global_policy().network_identity().hostname;
}

int native_project_ifaddrs(ifaddrs** result) noexcept {
    if (result == nullptr) { errno = EFAULT; return -1; }
    *result = nullptr;
    try {
        NativeNetworkIdentity identity = global_policy().network_identity();
        ifaddrs* loopback = ipv4_node("lo", "127.0.0.1", "255.0.0.0", IFF_UP | IFF_LOOPBACK | IFF_RUNNING);
        ifaddrs* ipv4 = ipv4_node(identity.interface_name.c_str(), identity.ipv4_address.c_str(),
                                 "255.255.255.0", IFF_UP | IFF_RUNNING | IFF_BROADCAST);
        ifaddrs* ipv6 = ipv6_node(identity.interface_name.c_str(), identity.ipv6_address.c_str(),
                                 IFF_UP | IFF_RUNNING);
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

}  // namespace controlled_sandbox
