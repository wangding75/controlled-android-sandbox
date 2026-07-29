#pragma once

#include <ifaddrs.h>
#include <net/if.h>
#include <sys/socket.h>
#include <cstdint>
#include <string>

namespace controlled_sandbox {

struct NativeNetworkStatus {
    std::size_t tracked_sockets{};
    std::uint64_t allowed_endpoints{};
    std::uint64_t denied_endpoints{};
    std::uint64_t projected_local_addresses{};
    std::uint64_t denied_socket_options{};
};

/** Fail-closed network address check for IPv4/IPv6 socket endpoints. */
[[nodiscard]] bool native_socket_address_allowed(const sockaddr* address, socklen_t length) noexcept;

/** Applies address policy plus the bounded cleartext policy for a socket destination. */
[[nodiscard]] bool native_socket_destination_allowed(
        int socket_fd, const sockaddr* address, socklen_t length) noexcept;

/** Records a socket created by a hooked Guest module. Returns false when the bounded registry is full. */
[[nodiscard]] bool native_register_socket(int socket_fd, int domain, int type, int protocol) noexcept;

/** Removes process-local virtual socket state before the descriptor is closed. */
void native_unregister_socket(int socket_fd) noexcept;

/** Projects a Guest bind address to a Host-safe address. */
int native_project_bind_address(const sockaddr* requested, socklen_t requested_length,
                                sockaddr_storage* projected, socklen_t* projected_length) noexcept;

/** Rewrites a local Host socket address to the configured Guest address. */
void native_project_local_address(sockaddr* address, socklen_t length) noexcept;

/** Handles security-sensitive and identity-bearing socket options. */
int native_set_virtual_socket_option(int socket_fd, int level, int option_name,
                                     const void* option_value, socklen_t option_length,
                                     bool* handled) noexcept;
int native_get_virtual_socket_option(int socket_fd, int level, int option_name,
                                     void* option_value, socklen_t* option_length,
                                     bool* handled) noexcept;

/** Guest-visible host identity; throws when the native policy is not configured. */
[[nodiscard]] std::string native_virtual_hostname();

/** Builds a bounded synthetic interface list containing loopback and one virtual Guest interface. */
int native_project_ifaddrs(ifaddrs** result) noexcept;

/** Frees a list produced by native_project_ifaddrs. Returns false for foreign lists. */
bool native_free_projected_ifaddrs(ifaddrs* value) noexcept;

/** Virtual interface index/name projection that never exposes Host interfaces. */
[[nodiscard]] unsigned int native_virtual_if_nametoindex(const char* name) noexcept;
char* native_virtual_if_indextoname(unsigned int index, char* name) noexcept;

[[nodiscard]] NativeNetworkStatus native_network_status() noexcept;
[[nodiscard]] std::string native_network_status_string();

}  // namespace controlled_sandbox
