#include "controlled_sandbox/native_network.h"
#include "controlled_sandbox/native_policy.h"

#include <arpa/inet.h>
#include <array>
#include <barrier>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
#include <vector>

using namespace controlled_sandbox;

extern "C" int controlled_socket(int domain, int type, int protocol);
extern "C" int controlled_close(int descriptor);
extern "C" int controlled_dup(int descriptor);
extern "C" int controlled_dup2(int descriptor, int target);
extern "C" int controlled_dup3(int descriptor, int target, int flags);
extern "C" int controlled_fcntl(int descriptor, int command, ...);
extern "C" int controlled_bind(int socket_fd, const sockaddr* address, socklen_t length);
extern "C" int controlled_connect(int socket_fd, const sockaddr* address, socklen_t length);
extern "C" ssize_t controlled_send(int socket_fd, const void* buffer, size_t length, int flags);
extern "C" ssize_t controlled_sendto(int socket_fd, const void* buffer, size_t length, int flags,
                                      const sockaddr* destination, socklen_t destination_length);
extern "C" ssize_t controlled_sendmsg(int socket_fd, const msghdr* message, int flags);
extern "C" ssize_t controlled_recv(int socket_fd, void* buffer, size_t length, int flags);
extern "C" ssize_t controlled_recvfrom(int socket_fd, void* buffer, size_t length, int flags,
                                        sockaddr* source, socklen_t* source_length);
extern "C" ssize_t controlled_recvmsg(int socket_fd, msghdr* message, int flags);
extern "C" ssize_t controlled_read(int descriptor, void* buffer, size_t length);
extern "C" ssize_t controlled_write(int descriptor, const void* buffer, size_t length);
extern "C" int controlled_accept(int socket_fd, sockaddr* address, socklen_t* length);
extern "C" int controlled_accept4(int socket_fd, sockaddr* address, socklen_t* length, int flags);
extern "C" int controlled_getsockname(int socket_fd, sockaddr* address, socklen_t* length);
extern "C" int controlled_getpeername(int socket_fd, sockaddr* address, socklen_t* length);

namespace {

void require(bool value, const std::string& message) {
    if (!value) throw std::runtime_error(message + ": errno=" + std::to_string(errno));
}

void configure_policy(bool allow_loopback) {
    static std::uint64_t generation = 1;
    NativeNetworkIdentity identity{"guest.sandbox", "vnet7", "10.64.7.2", "fd00::7",
            "proxy.sandbox", 8080, true, 107, "VPN", true, true, true, 1400,
            "dns.sandbox", {"10.8.0.53"}};
    std::vector<CidrV4> allow4;
    std::vector<CidrV4> deny4;
    if (allow_loopback) allow4.push_back(*CidrV4::parse("127.0.0.0/8"));
    else deny4.push_back(*CidrV4::parse("127.0.0.0/8"));
    global_policy().configure("network-interceptors", generation++,
            "com.example.guest", "com.example.guest", 7, 190007, 20701, "x86_64",
            "/tmp/guest", "/tmp/base.apk", "/tmp/lib", false,
            {}, {}, std::move(allow4), std::move(deny4), {}, {}, identity);
}

sockaddr_in loopback_address(std::uint16_t port = 0) {
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(port);
    require(inet_pton(AF_INET, "127.0.0.1", &address.sin_addr) == 1, "parse loopback");
    return address;
}

sockaddr_in bound_address(int socket_fd) {
    sockaddr_in address{};
    socklen_t length = sizeof(address);
    require(::getsockname(socket_fd, reinterpret_cast<sockaddr*>(&address), &length) == 0,
            "read bound address");
    return address;
}

int create_controlled_udp_receiver() {
    const int receiver = controlled_socket(AF_INET, SOCK_DGRAM, 0);
    require(receiver >= 0, "create controlled UDP receiver");
    const sockaddr_in address = loopback_address();
    require(controlled_bind(receiver, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == 0,
            "bind controlled UDP receiver");
    return receiver;
}

void raw_send_udp(const sockaddr_in& destination, const std::string& payload) {
    const int sender = ::socket(AF_INET, SOCK_DGRAM, 0);
    require(sender >= 0, "create raw UDP sender");
    require(::sendto(sender, payload.data(), payload.size(), 0,
            reinterpret_cast<const sockaddr*>(&destination), sizeof(destination))
            == static_cast<ssize_t>(payload.size()), "send raw UDP payload");
    require(::close(sender) == 0, "close raw UDP sender");
}

struct TcpPair {
    int listener{-1};
    int client{-1};
    int server{-1};
};

TcpPair create_tcp_pair() {
    configure_policy(true);
    TcpPair pair;
    pair.listener = ::socket(AF_INET, SOCK_STREAM, 0);
    require(pair.listener >= 0, "create TCP listener");
    int reuse = 1;
    require(::setsockopt(pair.listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) == 0,
            "set listener reuse");
    sockaddr_in address = loopback_address();
    require(::bind(pair.listener, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == 0,
            "bind TCP listener");
    require(::listen(pair.listener, 8) == 0, "listen TCP");
    address = bound_address(pair.listener);
    pair.client = controlled_socket(AF_INET, SOCK_STREAM, 0);
    require(pair.client >= 0, "create controlled TCP client");
    require(controlled_connect(pair.client, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == 0,
            "connect controlled TCP client");
    pair.server = ::accept(pair.listener, nullptr, nullptr);
    require(pair.server >= 0, "accept raw TCP peer");
    return pair;
}

void close_tcp_pair(TcpPair& pair) {
    if (pair.server >= 0) require(::close(pair.server) == 0, "close TCP server");
    if (pair.client >= 0) require(controlled_close(pair.client) == 0, "close controlled TCP client");
    if (pair.listener >= 0) require(::close(pair.listener) == 0, "close TCP listener");
    pair = {};
}

void require_no_server_payload(int server) {
    const int current = ::fcntl(server, F_GETFL);
    require(current >= 0, "read server flags");
    require(::fcntl(server, F_SETFL, current | O_NONBLOCK) == 0, "set server nonblocking");
    char byte{};
    errno = 0;
    require(::recv(server, &byte, 1, 0) == -1 && (errno == EAGAIN || errno == EWOULDBLOCK),
            "denied send emitted no payload");
    require(::fcntl(server, F_SETFL, current) == 0, "restore server flags");
}

void test_denied_recvfrom_preserves_buffers_and_queue() {
    configure_policy(false);
    const int receiver = create_controlled_udp_receiver();
    const sockaddr_in destination = bound_address(receiver);
    raw_send_udp(destination, "secret-payload");

    std::array<char, 32> payload{};
    payload.fill('#');
    sockaddr_storage source{};
    std::memset(&source, 0x5a, sizeof(source));
    const sockaddr_storage original_source = source;
    socklen_t source_length = 7;
    errno = 0;
    require(controlled_recvfrom(receiver, payload.data(), payload.size(), MSG_DONTWAIT,
            reinterpret_cast<sockaddr*>(&source), &source_length) == -1 && errno == EACCES,
            "denied recvfrom");
    require(payload == std::array<char, 32>{'#', '#', '#', '#', '#', '#', '#', '#',
            '#', '#', '#', '#', '#', '#', '#', '#', '#', '#', '#', '#', '#', '#', '#', '#',
            '#', '#', '#', '#', '#', '#', '#', '#'}, "recvfrom payload unchanged");
    require(std::memcmp(&source, &original_source, sizeof(source)) == 0 && source_length == 7,
            "recvfrom source unchanged");

    std::array<char, 32> raw{};
    require(::recvfrom(receiver, raw.data(), raw.size(), MSG_DONTWAIT, nullptr, nullptr) == 14,
            "denied datagram remains queued");
    require(std::string(raw.data(), 14) == "secret-payload", "queued payload intact");
    require(controlled_close(receiver) == 0, "close UDP receiver");
}

void test_allowed_recvfrom_short_address_and_concurrency() {
    configure_policy(true);
    const int receiver = create_controlled_udp_receiver();
    const sockaddr_in destination = bound_address(receiver);
    raw_send_udp(destination, "abcdef");

    std::array<char, 3> payload{};
    std::array<unsigned char, 2> tiny_address{};
    socklen_t tiny_length = tiny_address.size();
    require(controlled_recvfrom(receiver, payload.data(), payload.size(), 0,
            reinterpret_cast<sockaddr*>(tiny_address.data()), &tiny_length) == 3,
            "allowed short recvfrom");
    require(std::string(payload.data(), payload.size()) == "abc", "short payload copied");
    require(tiny_length == sizeof(sockaddr_in), "short source reports full length");

    raw_send_udp(destination, "uvwxyz");
    std::array<char, 3> message_payload{};
    iovec receive_vector{message_payload.data(), message_payload.size()};
    std::array<unsigned char, 2> message_address{};
    msghdr receive_message{};
    receive_message.msg_name = message_address.data();
    receive_message.msg_namelen = message_address.size();
    receive_message.msg_iov = &receive_vector;
    receive_message.msg_iovlen = 1;
    require(controlled_recvmsg(receiver, &receive_message, 0) == 3,
            "allowed short recvmsg");
    require(std::string(message_payload.data(), message_payload.size()) == "uvw",
            "short recvmsg payload copied");
    require(receive_message.msg_namelen == sizeof(sockaddr_in)
            && (receive_message.msg_flags & MSG_TRUNC) != 0,
            "short recvmsg reports source and truncation");

    raw_send_udp(destination, "A");
    raw_send_udp(destination, "B");
    const int receive_alias = controlled_dup(receiver);
    require(receive_alias >= 0
            && native_socket_io_mutex(receiver) == native_socket_io_mutex(receive_alias),
            "dup aliases share receive mutex");
    std::barrier start(3);
    std::array<char, 2> values{};
    std::array<int, 2> statuses{};
    std::thread first([&] {
        start.arrive_and_wait();
        statuses[0] = static_cast<int>(controlled_recvfrom(receiver, &values[0], 1, 0, nullptr, nullptr));
    });
    std::thread second([&] {
        start.arrive_and_wait();
        statuses[1] = static_cast<int>(controlled_recvfrom(receive_alias, &values[1], 1, 0, nullptr, nullptr));
    });
    start.arrive_and_wait();
    first.join();
    second.join();
    require(statuses[0] == 1 && statuses[1] == 1, "concurrent recvfrom statuses");
    require((values[0] == 'A' && values[1] == 'B') || (values[0] == 'B' && values[1] == 'A'),
            "concurrent recvfrom payloads");
    require(controlled_close(receive_alias) == 0, "close concurrent receive alias");
    require(controlled_close(receiver) == 0, "close concurrent UDP receiver");
}

void test_sendto_and_named_sendmsg_policy() {
    configure_policy(false);
    const int receiver = ::socket(AF_INET, SOCK_DGRAM, 0);
    require(receiver >= 0, "create raw sendto receiver");
    sockaddr_in receiver_address = loopback_address();
    require(::bind(receiver, reinterpret_cast<const sockaddr*>(&receiver_address),
            sizeof(receiver_address)) == 0, "bind raw sendto receiver");
    receiver_address = bound_address(receiver);
    const int sender = controlled_socket(AF_INET, SOCK_DGRAM, 0);
    require(sender >= 0, "create controlled sendto sender");

    errno = 0;
    require(controlled_sendto(sender, "T", 1, 0,
            reinterpret_cast<const sockaddr*>(&receiver_address), sizeof(receiver_address)) == -1
            && errno == EACCES, "denied sendto");
    iovec vector{const_cast<char*>("M"), 1};
    msghdr message{};
    message.msg_name = &receiver_address;
    message.msg_namelen = sizeof(receiver_address);
    message.msg_iov = &vector;
    message.msg_iovlen = 1;
    errno = 0;
    require(controlled_sendmsg(sender, &message, 0) == -1 && errno == EACCES,
            "denied named sendmsg");
    const int original_flags = ::fcntl(receiver, F_GETFL);
    require(original_flags >= 0
            && ::fcntl(receiver, F_SETFL, original_flags | O_NONBLOCK) == 0,
            "set sendto receiver nonblocking");
    char received{};
    errno = 0;
    require(::recv(receiver, &received, 1, 0) == -1
            && (errno == EAGAIN || errno == EWOULDBLOCK),
            "denied sendto APIs emit no payload");
    require(::fcntl(receiver, F_SETFL, original_flags) == 0,
            "restore sendto receiver flags");

    configure_policy(true);
    require(controlled_sendto(sender, "T", 1, 0,
            reinterpret_cast<const sockaddr*>(&receiver_address), sizeof(receiver_address)) == 1,
            "allowed sendto");
    require(::recv(receiver, &received, 1, 0) == 1 && received == 'T',
            "receive allowed sendto");
    require(controlled_sendmsg(sender, &message, 0) == 1, "allowed named sendmsg");
    require(::recv(receiver, &received, 1, 0) == 1 && received == 'M',
            "receive allowed named sendmsg");
    require(controlled_close(sender) == 0, "close controlled sendto sender");
    require(::close(receiver) == 0, "close raw sendto receiver");
}

void test_denied_recvmsg_preserves_message_and_queue() {
    configure_policy(false);
    const int receiver = create_controlled_udp_receiver();
    raw_send_udp(bound_address(receiver), "hidden");

    std::array<char, 8> payload{};
    payload.fill('!');
    iovec vector{payload.data(), payload.size()};
    sockaddr_storage source{};
    std::memset(&source, 0x33, sizeof(source));
    msghdr message{};
    message.msg_name = &source;
    message.msg_namelen = 5;
    message.msg_iov = &vector;
    message.msg_iovlen = 1;
    message.msg_flags = 0x1234;
    errno = 0;
    require(controlled_recvmsg(receiver, &message, MSG_DONTWAIT) == -1 && errno == EACCES,
            "denied recvmsg");
    require(payload == std::array<char, 8>{'!', '!', '!', '!', '!', '!', '!', '!'},
            "recvmsg payload unchanged");
    require(message.msg_namelen == 5 && message.msg_flags == 0x1234,
            "recvmsg metadata unchanged");
    std::array<char, 8> raw{};
    require(::recv(receiver, raw.data(), raw.size(), MSG_DONTWAIT) == 6,
            "denied recvmsg datagram remains queued");
    require(std::string(raw.data(), 6) == "hidden", "recvmsg queued payload intact");
    require(controlled_close(receiver) == 0, "close recvmsg UDP receiver");
}

void test_connected_send_receive_and_peer_buffers() {
    TcpPair pair = create_tcp_pair();
    configure_policy(false);

    sockaddr_storage peer{};
    std::memset(&peer, 0x44, sizeof(peer));
    const sockaddr_storage original_peer = peer;
    socklen_t peer_length = 9;
    errno = 0;
    require(controlled_getpeername(pair.client, reinterpret_cast<sockaddr*>(&peer), &peer_length) == -1
            && errno == EACCES, "denied getpeername");
    require(std::memcmp(&peer, &original_peer, sizeof(peer)) == 0 && peer_length == 9,
            "getpeername output unchanged");

    const char payload[] = "blocked";
    errno = 0;
    require(controlled_send(pair.client, payload, sizeof(payload) - 1, 0) == -1 && errno == EACCES,
            "denied send");
    iovec send_vector{const_cast<char*>(payload), sizeof(payload) - 1};
    msghdr send_message{};
    send_message.msg_iov = &send_vector;
    send_message.msg_iovlen = 1;
    errno = 0;
    require(controlled_sendmsg(pair.client, &send_message, 0) == -1 && errno == EACCES,
            "denied sendmsg");
    errno = 0;
    require(controlled_write(pair.client, payload, sizeof(payload) - 1) == -1 && errno == EACCES,
            "denied socket write");
    require_no_server_payload(pair.server);

    auto denied_receive = [&](const std::string& value, auto call, const std::string& label) {
        require(::send(pair.server, value.data(), value.size(), 0) == static_cast<ssize_t>(value.size()),
                "send server payload for " + label);
        std::array<char, 32> target{};
        target.fill('?');
        errno = 0;
        require(call(target) == -1 && errno == EACCES, "denied " + label);
        require(target.front() == '?' && target.back() == '?', label + " buffer unchanged");
        std::array<char, 32> raw{};
        require(::recv(pair.client, raw.data(), raw.size(), 0) == static_cast<ssize_t>(value.size()),
                label + " left stream payload queued");
        require(std::string(raw.data(), value.size()) == value, label + " queued payload intact");
    };
    denied_receive("recv-data", [&](auto& target) {
        return controlled_recv(pair.client, target.data(), target.size(), 0);
    }, "recv");
    denied_receive("read-data", [&](auto& target) {
        return controlled_read(pair.client, target.data(), target.size());
    }, "read");
    denied_receive("msg-data", [&](auto& target) {
        iovec receive_vector{target.data(), target.size()};
        msghdr receive_message{};
        receive_message.msg_iov = &receive_vector;
        receive_message.msg_iovlen = 1;
        return controlled_recvmsg(pair.client, &receive_message, 0);
    }, "recvmsg");

    configure_policy(true);
    require(controlled_send(pair.client, "S", 1, 0) == 1, "allowed send");
    char received{};
    require(::recv(pair.server, &received, 1, 0) == 1 && received == 'S', "server receives send");
    require(controlled_write(pair.client, "W", 1) == 1, "allowed write");
    require(::recv(pair.server, &received, 1, 0) == 1 && received == 'W', "server receives write");
    iovec send_vector_allowed{const_cast<char*>("M"), 1};
    msghdr send_message_allowed{};
    send_message_allowed.msg_iov = &send_vector_allowed;
    send_message_allowed.msg_iovlen = 1;
    require(controlled_sendmsg(pair.client, &send_message_allowed, 0) == 1,
            "allowed connected sendmsg");
    require(::recv(pair.server, &received, 1, 0) == 1 && received == 'M',
            "server receives connected sendmsg");
    require(::send(pair.server, "R", 1, 0) == 1, "server sends recv payload");
    require(controlled_recv(pair.client, &received, 1, 0) == 1 && received == 'R', "allowed recv");
    require(::send(pair.server, "G", 1, 0) == 1, "server sends recvmsg payload");
    iovec receive_vector_allowed{&received, 1};
    msghdr receive_message_allowed{};
    receive_message_allowed.msg_iov = &receive_vector_allowed;
    receive_message_allowed.msg_iovlen = 1;
    require(controlled_recvmsg(pair.client, &receive_message_allowed, 0) == 1 && received == 'G',
            "allowed connected recvmsg");
    require(::send(pair.server, "D", 1, 0) == 1, "server sends read payload");
    require(controlled_read(pair.client, &received, 1) == 1 && received == 'D',
            "allowed socket read");

    std::array<unsigned char, 2> tiny_peer{};
    socklen_t tiny_peer_length = tiny_peer.size();
    require(controlled_getpeername(pair.client, reinterpret_cast<sockaddr*>(tiny_peer.data()),
            &tiny_peer_length) == 0 && tiny_peer_length == sizeof(sockaddr_in),
            "allowed short getpeername");
    std::array<unsigned char, 2> tiny_local{};
    socklen_t tiny_local_length = tiny_local.size();
    require(controlled_getsockname(pair.client, reinterpret_cast<sockaddr*>(tiny_local.data()),
            &tiny_local_length) == 0 && tiny_local_length == sizeof(sockaddr_in),
            "allowed short getsockname");
    close_tcp_pair(pair);
}

int create_controlled_listener() {
    const int listener = controlled_socket(AF_INET, SOCK_STREAM, 0);
    require(listener >= 0, "create controlled listener");
    const sockaddr_in address = loopback_address();
    require(controlled_bind(listener, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == 0,
            "bind controlled listener");
    require(::listen(listener, 8) == 0, "listen controlled listener");
    return listener;
}

int connect_raw_client(const sockaddr_in& address) {
    const int client = ::socket(AF_INET, SOCK_STREAM, 0);
    require(client >= 0, "create raw accept client");
    require(::connect(client, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == 0,
            "connect raw accept client");
    return client;
}

void test_accept_policy_and_descriptor_tracking() {
    configure_policy(false);
    const int listener = create_controlled_listener();
    const sockaddr_in address = bound_address(listener);
    int client = connect_raw_client(address);
    sockaddr_storage source{};
    std::memset(&source, 0x6b, sizeof(source));
    const sockaddr_storage original = source;
    socklen_t source_length = 4;
    errno = 0;
    require(controlled_accept(listener, reinterpret_cast<sockaddr*>(&source), &source_length) == -1
            && errno == EACCES, "denied accept");
    require(std::memcmp(&source, &original, sizeof(source)) == 0 && source_length == 4,
            "accept output unchanged");
    require(::close(client) == 0, "close denied accept client");

    client = connect_raw_client(address);
    errno = 0;
    require(controlled_accept4(listener, nullptr, nullptr, SOCK_CLOEXEC) == -1
            && errno == EACCES, "denied accept4");
    require(::close(client) == 0, "close denied accept4 client");

    configure_policy(true);
    client = connect_raw_client(address);
    int accepted = controlled_accept(listener, nullptr, nullptr);
    require(accepted >= 0 && native_is_tracked_socket(accepted), "allowed accept tracked");
    require(native_socket_io_mutex(accepted) != native_socket_io_mutex(listener),
            "accepted socket receives independent mutex");
    require(controlled_close(accepted) == 0, "close accepted socket");
    require(::close(client) == 0, "close allowed accept client");

    client = connect_raw_client(address);
    accepted = controlled_accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
    require(accepted >= 0 && native_is_tracked_socket(accepted), "allowed accept4 tracked");
    require((::fcntl(accepted, F_GETFD) & FD_CLOEXEC) != 0, "accept4 preserves flags");
    require(controlled_close(accepted) == 0, "close accept4 socket");
    require(::close(client) == 0, "close accept4 client");
    require(controlled_close(listener) == 0, "close controlled listener");
}

void test_duplication_tracking() {
    configure_policy(true);
    const int original = controlled_socket(AF_INET, SOCK_DGRAM, 0);
    require(original >= 0 && native_is_tracked_socket(original), "original socket tracked");
    const int first = controlled_dup(original);
    require(first >= 0 && native_is_tracked_socket(first)
            && native_socket_io_mutex(first) == native_socket_io_mutex(original),
            "dup socket tracked with shared mutex");

    int second = ::open("/dev/null", O_RDONLY);
    require(second >= 0, "open dup2 target");
    require(controlled_dup2(original, second) == second && native_is_tracked_socket(second),
            "dup2 socket tracked");

    int third = ::open("/dev/null", O_RDONLY);
    require(third >= 0, "open dup3 target");
    require(controlled_dup3(original, third, O_CLOEXEC) == third && native_is_tracked_socket(third),
            "dup3 socket tracked");

    const int fourth = controlled_fcntl(original, F_DUPFD, 0);
    require(fourth >= 0 && native_is_tracked_socket(fourth), "fcntl duplicate socket tracked");
#ifdef F_DUPFD_CLOEXEC
    const int fifth = controlled_fcntl(original, F_DUPFD_CLOEXEC, 0);
    require(fifth >= 0 && native_is_tracked_socket(fifth)
            && (::fcntl(fifth, F_GETFD) & FD_CLOEXEC) != 0,
            "fcntl cloexec duplicate socket tracked");
#endif
    require(controlled_fcntl(original, F_GETFD) >= 0, "fcntl no-argument forwarding");

    char lock_path[] = "/tmp/controlled-sandbox-lock-XXXXXX";
    const int lock_file = ::mkstemp(lock_path);
    require(lock_file >= 0, "create fcntl pointer fixture");
    require(::unlink(lock_path) == 0, "unlink fcntl pointer fixture");
    flock lock{};
    lock.l_type = F_WRLCK;
    lock.l_whence = SEEK_SET;
    errno = 0;
    require(controlled_fcntl(lock_file, F_GETLK, &lock) == 0,
            "fcntl pointer-argument forwarding");
    require(::close(lock_file) == 0, "close fcntl pointer fixture");

    const int file_source = ::open("/dev/null", O_RDONLY);
    require(file_source >= 0, "open non-socket source");
    require(controlled_dup2(file_source, first) == first && !native_is_tracked_socket(first),
            "dup2 non-socket clears stale socket state");

    require(::close(file_source) == 0, "close non-socket source");
    require(::close(first) == 0, "close replaced descriptor");
    require(controlled_close(second) == 0, "close dup2 socket");
    require(controlled_close(third) == 0, "close dup3 socket");
    require(controlled_close(fourth) == 0, "close fcntl socket");
#ifdef F_DUPFD_CLOEXEC
    require(controlled_close(fifth) == 0, "close fcntl cloexec socket");
#endif
    require(controlled_close(original) == 0, "close original socket");
    require(!native_is_tracked_socket(original), "controlled close clears socket tracking");
}

}  // namespace

int main() {
    test_denied_recvfrom_preserves_buffers_and_queue();
    test_allowed_recvfrom_short_address_and_concurrency();
    test_sendto_and_named_sendmsg_policy();
    test_denied_recvmsg_preserves_message_and_queue();
    test_connected_send_receive_and_peer_buffers();
    test_accept_policy_and_descriptor_tracking();
    test_duplication_tracking();
    global_policy().reset();
    std::cout << "PASS native network interceptor denial/copy/FD lifecycle self-test\n";
    return 0;
}
