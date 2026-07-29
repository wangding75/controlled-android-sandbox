#include "controlled_sandbox/native_network.h"
#include "controlled_sandbox/native_policy.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <ifaddrs.h>
#include <iostream>
#include <net/if.h>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

using namespace controlled_sandbox;

namespace {
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
}

int main() {
    NativeNetworkIdentity identity{"guest.sandbox", "vnet7", "10.64.7.2", "fd00::7",
            "proxy.sandbox", 8080, false, 107, "VPN", true, true, true, 1400,
            "dns.sandbox", {"10.8.0.53", "fd08::53"}};
    global_policy().configure("network-session", 7, "com.example.guest", "com.example.guest", 7,
            190007, 20701, "x86_64", "/tmp/guest", "/tmp/base.apk", "/tmp/lib", false,
            {"allowed.example"}, {"blocked.example"},
            {*CidrV4::parse("10.0.0.0/8")}, {*CidrV4::parse("10.9.0.0/16")},
            {*CidrV6::parse("fd00::/8")}, {*CidrV6::parse("fd09::/16")}, identity);
    require(global_policy().allow_host("allowed.example"), "allowed DNS host");
    require(!global_policy().allow_host("blocked.example"), "denied DNS host");
    require(global_policy().allow_ipv4("10.8.1.1"), "allowed IPv4 CIDR");
    require(!global_policy().allow_ipv4("10.9.1.1"), "denied IPv4 CIDR");
    require(global_policy().allow_ipv6("fd08::1"), "allowed IPv6 CIDR");
    require(!global_policy().allow_ipv6("fd09::1"), "denied IPv6 CIDR");

    const int socket_fd = ::socket(AF_INET, SOCK_STREAM, 0);
    require(socket_fd >= 0, "create socket");
    require(native_register_socket(socket_fd, AF_INET, SOCK_STREAM, 0), "register socket");

    sockaddr_in allowed4{}; allowed4.sin_family = AF_INET;
    allowed4.sin_port = htons(443);
    inet_pton(AF_INET, "10.8.1.1", &allowed4.sin_addr);
    require(native_socket_destination_allowed(socket_fd,
            reinterpret_cast<sockaddr*>(&allowed4), sizeof(allowed4)), "TLS destination policy");
    allowed4.sin_port = htons(80);
    require(!native_socket_destination_allowed(socket_fd,
            reinterpret_cast<sockaddr*>(&allowed4), sizeof(allowed4)), "cleartext destination denied");

    sockaddr_in6 denied6{}; denied6.sin6_family = AF_INET6;
    inet_pton(AF_INET6, "fd09::1", &denied6.sin6_addr);
    require(!native_socket_address_allowed(reinterpret_cast<sockaddr*>(&denied6), sizeof(denied6)),
            "socket IPv6 policy");
    require(native_virtual_hostname() == "guest.sandbox", "virtual hostname");

    sockaddr_in virtual_bind{};
    virtual_bind.sin_family = AF_INET;
    virtual_bind.sin_port = htons(12345);
    inet_pton(AF_INET, "10.64.7.2", &virtual_bind.sin_addr);
    sockaddr_storage projected{};
    socklen_t projected_length = 0;
    require(native_project_bind_address(reinterpret_cast<sockaddr*>(&virtual_bind), sizeof(virtual_bind),
            &projected, &projected_length) == 0, "project virtual bind");
    require(reinterpret_cast<sockaddr_in*>(&projected)->sin_addr.s_addr == htonl(INADDR_ANY),
            "virtual bind rewrites to wildcard");

    sockaddr_in foreign_bind{};
    foreign_bind.sin_family = AF_INET;
    inet_pton(AF_INET, "192.168.1.9", &foreign_bind.sin_addr);
    require(native_project_bind_address(reinterpret_cast<sockaddr*>(&foreign_bind), sizeof(foreign_bind),
            &projected, &projected_length) == -1 && errno == EADDRNOTAVAIL,
            "foreign local address denied");

#ifdef SO_BINDTODEVICE
    bool handled = false;
    const char virtual_interface[] = "vnet7";
    require(native_set_virtual_socket_option(socket_fd, SOL_SOCKET, SO_BINDTODEVICE,
            virtual_interface, sizeof(virtual_interface), &handled) == 0 && handled,
            "virtual SO_BINDTODEVICE");
    char interface_buffer[IFNAMSIZ]{};
    socklen_t interface_length = sizeof(interface_buffer);
    handled = false;
    require(native_get_virtual_socket_option(socket_fd, SOL_SOCKET, SO_BINDTODEVICE,
            interface_buffer, &interface_length, &handled) == 0 && handled
            && std::strcmp(interface_buffer, "vnet7") == 0, "read virtual SO_BINDTODEVICE");
    const char host_interface[] = "eth0";
    handled = false;
    require(native_set_virtual_socket_option(socket_fd, SOL_SOCKET, SO_BINDTODEVICE,
            host_interface, sizeof(host_interface), &handled) == -1 && handled,
            "host SO_BINDTODEVICE denied");
#endif

    require(native_virtual_if_nametoindex("lo") == 1, "loopback index");
    require(native_virtual_if_nametoindex("vnet7") == 100, "virtual interface index");
    char interface_name[IFNAMSIZ]{};
    require(native_virtual_if_indextoname(100, interface_name) != nullptr
            && std::strcmp(interface_name, "vnet7") == 0, "virtual interface name");
    require(native_virtual_if_nametoindex("eth0") == 0, "host interface hidden");

    ifaddrs* interfaces = nullptr;
    require(native_project_ifaddrs(&interfaces) == 0 && interfaces != nullptr, "project interfaces");
    int count = 0; bool virtual_found = false;
    for (ifaddrs* item = interfaces; item != nullptr; item = item->ifa_next) {
        count++;
        if (item->ifa_name != nullptr && std::strcmp(item->ifa_name, "vnet7") == 0) virtual_found = true;
    }
    require(count == 3 && virtual_found, "bounded virtual interface list");
    require(native_free_projected_ifaddrs(interfaces), "free projected list");

    const NativeNetworkStatus status = native_network_status();
    require(status.tracked_sockets == 1, "tracked socket count");
    require(status.allowed_endpoints >= 1 && status.denied_endpoints >= 1, "endpoint audit counts");
    require(native_network_status_string().find("transport=VPN") != std::string::npos,
            "Connectivity status projection");
    native_unregister_socket(socket_fd);
    close(socket_fd);
    global_policy().reset();
    std::cout << "PASS sandbox-native network and Connectivity isolation self-test\n";
}
