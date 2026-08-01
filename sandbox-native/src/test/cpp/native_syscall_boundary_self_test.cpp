#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>

namespace {

void require_boundary(bool condition, const std::string& label) {
    if (!condition) throw std::runtime_error("Failed: " + label);
}

std::uint16_t bound_port(int socket_fd) {
    sockaddr_in address{};
    socklen_t length = sizeof(address);
    require_boundary(getsockname(socket_fd, reinterpret_cast<sockaddr*>(&address), &length) == 0,
            "getsockname");
    return ntohs(address.sin_port);
}

sockaddr_in loopback(std::uint16_t port) {
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(port);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    return address;
}

}  // namespace

int main() {
    char pattern[] = "/tmp/controlled-sandbox-syscall-XXXXXX";
    char* created = mkdtemp(pattern);
    require_boundary(created != nullptr, "mkdtemp");
    const std::filesystem::path root(created);
    const auto instance = root / "instance";
    const auto data = instance / "data";
    const auto outside = root / "host-private";
    const auto apk = root / "base.apk";
    const auto native_root = root / "lib";
    std::filesystem::create_directories(data);
    std::filesystem::create_directories(outside);
    std::filesystem::create_directories(native_root);
    std::ofstream(outside / "secret.txt") << "host-private-secret";
    std::ofstream(apk) << "apk";
    std::filesystem::create_directory_symlink(outside, data / "escape");

    controlled_sandbox::global_policy().configure(
            "syscall-boundary", 1, "com.example.guest", "com.example.guest:main",
            0, 10000, 20000, "x86_64", instance.string(), apk.string(), native_root.string(),
            false, {}, {}, {}, {});

    const auto resolved = controlled_sandbox::NativeFileSystemResolver::resolve(
            "/data/data/com.example.guest/escape/secret.txt");
    bool wrapper_rejected = false;
    try {
        controlled_sandbox::NativeFileSystemResolver::validate_confinement(resolved, true);
    } catch (const controlled_sandbox::PathPolicyError& expected) {
        wrapper_rejected = expected.error_number() == EACCES;
    }
    require_boundary(wrapper_rejected, "filesystem wrapper rejects symlink escape");

    const int direct_fd = static_cast<int>(syscall(
            SYS_openat, AT_FDCWD, resolved.path.c_str(), O_RDONLY | O_CLOEXEC, 0));
    require_boundary(direct_fd >= 0, "direct SYS_openat bypass reaches host path");
    char file_buffer[64]{};
    const ssize_t file_count = read(direct_fd, file_buffer, sizeof(file_buffer));
    close(direct_fd);
    require_boundary(file_count == 19
                    && std::string(file_buffer, static_cast<std::size_t>(file_count))
                            == "host-private-secret",
            "direct SYS_openat reads host-private payload");

    require_boundary(!controlled_sandbox::global_policy().allow_ipv4("127.0.0.1"),
            "native network policy denies loopback");

    const int listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    require_boundary(listener >= 0, "tcp listener socket");
    int reuse = 1;
    require_boundary(setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) == 0,
            "tcp reuseaddr");
    sockaddr_in tcp_address = loopback(0);
    require_boundary(bind(listener, reinterpret_cast<sockaddr*>(&tcp_address), sizeof(tcp_address)) == 0,
            "tcp bind");
    require_boundary(listen(listener, 1) == 0, "tcp listen");
    tcp_address = loopback(bound_port(listener));
    std::thread accept_thread([listener] {
        int accepted = accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        if (accepted >= 0) close(accepted);
    });
    const int direct_tcp = static_cast<int>(syscall(SYS_socket, AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0));
    require_boundary(direct_tcp >= 0, "direct tcp SYS_socket");
    const long connect_status = syscall(SYS_connect, direct_tcp,
            reinterpret_cast<sockaddr*>(&tcp_address), sizeof(tcp_address));
    require_boundary(connect_status == 0, "direct SYS_connect bypasses network policy");
    close(direct_tcp);
    accept_thread.join();
    close(listener);

    const int udp_receiver = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    require_boundary(udp_receiver >= 0, "udp receiver socket");
    sockaddr_in udp_address = loopback(0);
    require_boundary(bind(udp_receiver, reinterpret_cast<sockaddr*>(&udp_address), sizeof(udp_address)) == 0,
            "udp bind");
    udp_address = loopback(bound_port(udp_receiver));
    const int direct_udp = static_cast<int>(syscall(SYS_socket, AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
    require_boundary(direct_udp >= 0, "direct udp SYS_socket");
    const char payload[] = "syscall-payload";
    const long sent = syscall(SYS_sendto, direct_udp, payload, sizeof(payload) - 1U, 0,
            reinterpret_cast<sockaddr*>(&udp_address), sizeof(udp_address));
    require_boundary(sent == static_cast<long>(sizeof(payload) - 1U),
            "direct SYS_sendto bypasses network policy");
    char udp_buffer[32]{};
    const ssize_t received = recv(udp_receiver, udp_buffer, sizeof(udp_buffer), 0);
    require_boundary(received == static_cast<ssize_t>(sizeof(payload) - 1U)
                    && std::memcmp(udp_buffer, payload, sizeof(payload) - 1U) == 0,
            "direct SYS_sendto payload received");
    close(direct_udp);
    close(udp_receiver);

    controlled_sandbox::global_policy().reset();
    std::filesystem::remove_all(root);
    std::cout << "PASS direct SYS_openat/SYS_connect/SYS_sendto bypass characterization" << std::endl;
    return 0;
}
