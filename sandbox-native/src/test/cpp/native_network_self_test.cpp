#include "controlled_sandbox/native_network.h"
#include "controlled_sandbox/native_policy.h"

#include <arpa/inet.h>
#include <cstring>
#include <ifaddrs.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace controlled_sandbox;

namespace {
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
}

int main() {
    NativeNetworkIdentity identity{"guest.sandbox", "vnet7", "10.64.7.2", "fd00::7", "proxy.sandbox", 8080, false};
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

    sockaddr_in allowed4{}; allowed4.sin_family = AF_INET;
    inet_pton(AF_INET, "10.8.1.1", &allowed4.sin_addr);
    require(native_socket_address_allowed(reinterpret_cast<sockaddr*>(&allowed4), sizeof(allowed4)),
            "socket IPv4 policy");
    sockaddr_in6 denied6{}; denied6.sin6_family = AF_INET6;
    inet_pton(AF_INET6, "fd09::1", &denied6.sin6_addr);
    require(!native_socket_address_allowed(reinterpret_cast<sockaddr*>(&denied6), sizeof(denied6)),
            "socket IPv6 policy");
    require(native_virtual_hostname() == "guest.sandbox", "virtual hostname");

    ifaddrs* interfaces = nullptr;
    require(native_project_ifaddrs(&interfaces) == 0 && interfaces != nullptr, "project interfaces");
    int count = 0; bool virtual_found = false;
    for (ifaddrs* item = interfaces; item != nullptr; item = item->ifa_next) {
        count++;
        if (item->ifa_name != nullptr && std::strcmp(item->ifa_name, "vnet7") == 0) virtual_found = true;
    }
    require(count == 3 && virtual_found, "bounded virtual interface list");
    require(native_free_projected_ifaddrs(interfaces), "free projected list");
    global_policy().reset();
    std::cout << "PASS sandbox-native network identity self-test\n";
}
