#pragma once

#include <ifaddrs.h>
#include <sys/socket.h>
#include <string>

namespace controlled_sandbox {

/** Fail-closed network address check for IPv4/IPv6 socket endpoints. */
[[nodiscard]] bool native_socket_address_allowed(const sockaddr* address, socklen_t length) noexcept;

/** Guest-visible host identity; throws when the native policy is not configured. */
[[nodiscard]] std::string native_virtual_hostname();

/** Builds a bounded synthetic interface list containing loopback and one virtual Guest interface. */
int native_project_ifaddrs(ifaddrs** result) noexcept;

/** Frees a list produced by native_project_ifaddrs. Returns false for foreign lists. */
bool native_free_projected_ifaddrs(ifaddrs* value) noexcept;

}  // namespace controlled_sandbox
