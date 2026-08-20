#include "controlled_sandbox/native_network_interceptors.h"
#include "controlled_sandbox/native_network.h"
#include "controlled_sandbox/native_policy.h"
#include "controlled_sandbox/native_fd_ledger.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits>
#include <mutex>
#include <new>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>
#include <vector>
#include <utility>

namespace controlled_sandbox {
namespace {

using SocketFn = int (*)(int, int, int);
using BindFn = int (*)(int, const sockaddr*, socklen_t);
using ConnectFn = int (*)(int, const sockaddr*, socklen_t);
using SendFn = ssize_t (*)(int, const void*, size_t, int);
using SendToFn = ssize_t (*)(int, const void*, size_t, int, const sockaddr*, socklen_t);
using SendMsgFn = ssize_t (*)(int, const msghdr*, int);
using RecvFromFn = ssize_t (*)(int, void*, size_t, int, sockaddr*, socklen_t*);
using RecvMsgFn = ssize_t (*)(int, msghdr*, int);
using AcceptFn = int (*)(int, sockaddr*, socklen_t*);
using Accept4Fn = int (*)(int, sockaddr*, socklen_t*, int);
using GetSockNameFn = int (*)(int, sockaddr*, socklen_t*);
using GetPeerNameFn = int (*)(int, sockaddr*, socklen_t*);
using GetSockOptFn = int (*)(int, int, int, void*, socklen_t*);
using CloseFn = int (*)(int);
using DupFn = int (*)(int);
using Dup2Fn = int (*)(int, int);
using Dup3Fn = int (*)(int, int, int);
using FcntlFn = int (*)(int, int, ...);
using ReadFn = ssize_t (*)(int, void*, size_t);
using WriteFn = ssize_t (*)(int, const void*, size_t);

std::atomic<SocketFn> real_socket{nullptr};
std::atomic<BindFn> real_bind{nullptr};
std::atomic<ConnectFn> real_connect{nullptr};
std::atomic<SendFn> real_send{nullptr};
std::atomic<SendToFn> real_sendto{nullptr};
std::atomic<SendMsgFn> real_sendmsg{nullptr};
std::atomic<RecvFromFn> real_recvfrom{nullptr};
std::atomic<RecvMsgFn> real_recvmsg{nullptr};
std::atomic<AcceptFn> real_accept{nullptr};
std::atomic<Accept4Fn> real_accept4{nullptr};
std::atomic<GetSockNameFn> real_getsockname{nullptr};
std::atomic<GetPeerNameFn> real_getpeername{nullptr};
std::atomic<GetSockOptFn> real_getsockopt{nullptr};
std::atomic<CloseFn> real_close{nullptr};
std::atomic<DupFn> real_dup{nullptr};
std::atomic<Dup2Fn> real_dup2{nullptr};
std::atomic<Dup3Fn> real_dup3{nullptr};
std::atomic<FcntlFn> real_fcntl{nullptr};
std::atomic<ReadFn> real_read{nullptr};
std::atomic<WriteFn> real_write{nullptr};

void* resolve_next(const char* name) {
    dlerror();
    void* value = dlsym(RTLD_NEXT, name);
    (void) dlerror();
    return value;
}

template <typename Function>
Function require_real(std::atomic<Function>& storage, const char* name) {
    Function current = storage.load(std::memory_order_acquire);
    if (current != nullptr) return current;
    current = reinterpret_cast<Function>(resolve_next(name));
    storage.store(current, std::memory_order_release);
    return current;
}

constexpr std::size_t NETWORK_RECEIVE_LOCK_COUNT = 64;
constexpr std::size_t MAX_TEMP_NETWORK_PAYLOAD = 8U * 1024U * 1024U;
constexpr std::size_t MAX_TEMP_NETWORK_CONTROL = 1024U * 1024U;
constexpr std::size_t MAX_TEMP_NETWORK_IOVECS = 1024U;
std::array<std::mutex, NETWORK_RECEIVE_LOCK_COUNT> network_receive_locks;

std::mutex& network_receive_lock(int socket_fd) noexcept {
    const auto index = static_cast<std::size_t>(static_cast<unsigned int>(socket_fd))
            % NETWORK_RECEIVE_LOCK_COUNT;
    return network_receive_locks[index];
}

bool socket_address_arguments_valid(sockaddr* address, socklen_t* length) noexcept {
    return (address == nullptr) == (length == nullptr);
}

void copy_socket_address(sockaddr* destination, socklen_t* destination_length,
                         socklen_t destination_capacity, const sockaddr_storage& source,
                         socklen_t source_length) noexcept {
    if (destination == nullptr || destination_length == nullptr) return;
    const std::size_t copy_length = std::min<std::size_t>(destination_capacity, source_length);
    if (copy_length > 0) std::memcpy(destination, &source, copy_length);
    *destination_length = source_length;
}

int socket_type_for_interceptor(int socket_fd) noexcept {
    GetSockOptFn function = require_real(real_getsockopt, "getsockopt");
    if (function == nullptr) return 0;
    int type = 0;
    socklen_t length = sizeof(type);
    if (function(socket_fd, SOL_SOCKET, SO_TYPE, &type, &length) != 0) return 0;
#ifdef SOCK_TYPE_MASK
    return type & SOCK_TYPE_MASK;
#else
    return type & 0x0f;
#endif
}

bool message_oriented_socket(int socket_fd) noexcept {
    const int type = socket_type_for_interceptor(socket_fd);
    return type == SOCK_DGRAM || type == SOCK_SEQPACKET || type == SOCK_RAW;
}

bool socket_address_present(const sockaddr_storage& address, socklen_t length) noexcept {
    if (std::cmp_less(length, sizeof(sa_family_t))) return false;
    return address.ss_family == AF_INET || address.ss_family == AF_INET6
            || address.ss_family == AF_UNIX;
}

bool socket_addresses_equal(const sockaddr_storage& first, socklen_t first_length,
                            const sockaddr_storage& second, socklen_t second_length) noexcept {
    if (!socket_address_present(first, first_length)
            || !socket_address_present(second, second_length)
            || first.ss_family != second.ss_family) return false;
    if (first.ss_family == AF_INET) {
        if (std::cmp_less(first_length, sizeof(sockaddr_in)) || std::cmp_less(second_length, sizeof(sockaddr_in))) return false;
        const auto* left = reinterpret_cast<const sockaddr_in*>(&first);
        const auto* right = reinterpret_cast<const sockaddr_in*>(&second);
        return left->sin_port == right->sin_port
                && left->sin_addr.s_addr == right->sin_addr.s_addr;
    }
    if (first.ss_family == AF_INET6) {
        if (std::cmp_less(first_length, sizeof(sockaddr_in6)) || std::cmp_less(second_length, sizeof(sockaddr_in6))) return false;
        const auto* left = reinterpret_cast<const sockaddr_in6*>(&first);
        const auto* right = reinterpret_cast<const sockaddr_in6*>(&second);
        return left->sin6_port == right->sin6_port
                && left->sin6_scope_id == right->sin6_scope_id
                && std::memcmp(&left->sin6_addr, &right->sin6_addr, sizeof(in6_addr)) == 0;
    }
    const std::size_t compared = std::min<std::size_t>(first_length, second_length);
    return first_length == second_length && std::memcmp(&first, &second, compared) == 0;
}

int check_connected_peer(int socket_fd, bool destination_policy) noexcept {
    GetPeerNameFn function = require_real(real_getpeername, "getpeername");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    sockaddr_storage peer{};
    socklen_t peer_length = sizeof(peer);
    if (function(socket_fd, reinterpret_cast<sockaddr*>(&peer), &peer_length) != 0) {
        if (errno == ENOTCONN || errno == EINVAL) return 0;
        return -1;
    }
    const bool allowed = destination_policy
            ? native_socket_destination_allowed(socket_fd,
                    reinterpret_cast<const sockaddr*>(&peer), peer_length)
            : native_socket_address_allowed(reinterpret_cast<const sockaddr*>(&peer), peer_length);
    if (!allowed) { errno = EACCES; return -1; }
    return 1;
}

ssize_t checked_recvfrom(int socket_fd, void* buffer, size_t length, int flags,
                         sockaddr* source, socklen_t* source_length) {
    RecvFromFn function = require_real(real_recvfrom, "recvfrom");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if ((buffer == nullptr && length != 0) || !socket_address_arguments_valid(source, source_length)) {
        errno = EFAULT;
        return -1;
    }
    if (length > MAX_TEMP_NETWORK_PAYLOAD) { errno = EMSGSIZE; return -1; }

    const socklen_t source_capacity = source_length == nullptr ? 0 : *source_length;
    const std::shared_ptr<std::mutex> socket_mutex = native_socket_io_mutex(socket_fd);
    std::mutex& selected_mutex = socket_mutex == nullptr
            ? network_receive_lock(socket_fd) : *socket_mutex;
    std::lock_guard receive_lock(selected_mutex);

    const bool message_oriented = message_oriented_socket(socket_fd);
    sockaddr_storage probed_source{};
    socklen_t probed_source_length = 0;
    if (message_oriented) {
        unsigned char probe_byte{};
        probed_source_length = sizeof(probed_source);
        const ssize_t probe_status = function(socket_fd, &probe_byte, sizeof(probe_byte),
                flags | MSG_PEEK, reinterpret_cast<sockaddr*>(&probed_source), &probed_source_length);
        if (probe_status < 0) return probe_status;
        if (!native_socket_address_allowed(reinterpret_cast<const sockaddr*>(&probed_source),
                                            probed_source_length)) {
            errno = EACCES;
            return -1;
        }
    } else {
        const int peer_status = check_connected_peer(socket_fd, false);
        if (peer_status < 0) return -1;
    }

    try {
        std::vector<unsigned char> temporary_payload(length);
        sockaddr_storage temporary_source{};
        socklen_t temporary_source_length = sizeof(temporary_source);
        void* temporary_buffer = length == 0 ? nullptr : temporary_payload.data();
        const ssize_t received = function(socket_fd, temporary_buffer, length, flags,
                reinterpret_cast<sockaddr*>(&temporary_source), &temporary_source_length);
        if (received < 0) return received;
        if (message_oriented && !socket_addresses_equal(
                probed_source, probed_source_length, temporary_source, temporary_source_length)) {
            errno = EAGAIN;
            return -1;
        }
        const std::size_t copied = std::min<std::size_t>(
                static_cast<std::size_t>(received), temporary_payload.size());
        if (copied > 0) std::memcpy(buffer, temporary_payload.data(), copied);
        copy_socket_address(source, source_length, source_capacity,
                            temporary_source, temporary_source_length);
        return received;
    } catch (const std::bad_alloc&) {
        errno = ENOMEM;
        return -1;
    } catch (...) {
        errno = EACCES;
        return -1;
    }
}

bool prepare_temporary_iovecs(const msghdr& message, std::vector<unsigned char>& payload,
                              std::vector<iovec>& vectors) {
    if (message.msg_iovlen > 0 && message.msg_iov == nullptr) { errno = EFAULT; return false; }
    if (message.msg_iovlen > MAX_TEMP_NETWORK_IOVECS) { errno = EMSGSIZE; return false; }
    std::size_t total = 0;
    for (std::size_t index = 0; index < message.msg_iovlen; index++) {
        const iovec& item = message.msg_iov[index];
        if (item.iov_len > 0 && item.iov_base == nullptr) { errno = EFAULT; return false; }
        if (item.iov_len > std::numeric_limits<std::size_t>::max() - total) {
            errno = EMSGSIZE;
            return false;
        }
        total += item.iov_len;
        if (total > MAX_TEMP_NETWORK_PAYLOAD
                || total > static_cast<std::size_t>(std::numeric_limits<ssize_t>::max())) {
            errno = EMSGSIZE;
            return false;
        }
    }
    payload.resize(total);
    vectors.resize(message.msg_iovlen);
    std::size_t offset = 0;
    for (std::size_t index = 0; index < message.msg_iovlen; index++) {
        vectors[index].iov_base = message.msg_iov[index].iov_len == 0
                ? nullptr : payload.data() + offset;
        vectors[index].iov_len = message.msg_iov[index].iov_len;
        offset += message.msg_iov[index].iov_len;
    }
    return true;
}

void copy_temporary_iovecs(const std::vector<unsigned char>& payload,
                           const msghdr& destination, ssize_t received) noexcept {
    std::size_t remaining = std::min<std::size_t>(
            received > 0 ? static_cast<std::size_t>(received) : 0, payload.size());
    std::size_t offset = 0;
    for (std::size_t index = 0; index < destination.msg_iovlen && remaining > 0; index++) {
        const std::size_t copied = std::min<std::size_t>(destination.msg_iov[index].iov_len, remaining);
        if (copied > 0) std::memcpy(destination.msg_iov[index].iov_base, payload.data() + offset, copied);
        offset += destination.msg_iov[index].iov_len;
        remaining -= copied;
    }
}

void close_received_file_descriptors(msghdr& message) noexcept {
    for (cmsghdr* header = CMSG_FIRSTHDR(&message); header != nullptr;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS
                || header->cmsg_len < CMSG_LEN(0)) continue;
        const std::size_t descriptor_count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        const int* descriptors = reinterpret_cast<const int*>(CMSG_DATA(header));
        CloseFn close_function = require_real(real_close, "close");
        if (close_function == nullptr) return;
        for (std::size_t index = 0; index < descriptor_count; index++) {
            if (descriptors[index] >= 0) {
                NativeFdLedger::close(descriptors[index]);
                (void) close_function(descriptors[index]);
            }
        }
    }
}

void observe_received_file_descriptors(const msghdr& message) noexcept {
    for (cmsghdr* header = CMSG_FIRSTHDR(const_cast<msghdr*>(&message)); header != nullptr;
         header = CMSG_NXTHDR(const_cast<msghdr*>(&message), header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS
                || header->cmsg_len < CMSG_LEN(0)) continue;
        const std::size_t descriptor_count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        const int* descriptors = reinterpret_cast<const int*>(CMSG_DATA(header));
        const std::uint64_t revision = global_policy().snapshot().revision;
        for (std::size_t index = 0; index < descriptor_count; index++) {
            if (descriptors[index] >= 0) {
                NativeFdLedger::observe_inherited(descriptors[index], revision);
            }
        }
    }
}

bool fcntl_has_no_argument(int command) noexcept {
    switch (command) {
        case F_GETFD:
        case F_GETFL:
        case F_GETOWN:
#ifdef F_GETSIG
        case F_GETSIG:
#endif
#ifdef F_GETLEASE
        case F_GETLEASE:
#endif
#ifdef F_GETPIPE_SZ
        case F_GETPIPE_SZ:
#endif
#ifdef F_GET_SEALS
        case F_GET_SEALS:
#endif
            return true;
        default:
            return false;
    }
}

bool fcntl_has_pointer_argument(int command) noexcept {
    switch (command) {
        case F_GETLK:
        case F_SETLK:
        case F_SETLKW:
#if defined(F_GETLK64) && F_GETLK64 != F_GETLK
        case F_GETLK64:
#endif
#if defined(F_SETLK64) && F_SETLK64 != F_SETLK
        case F_SETLK64:
#endif
#if defined(F_SETLKW64) && F_SETLKW64 != F_SETLKW
        case F_SETLKW64:
#endif
#ifdef F_CANCELLK
        case F_CANCELLK:
#endif
#ifdef F_GETOWNER_UIDS
        case F_GETOWNER_UIDS:
#endif
#ifdef F_OFD_GETLK
        case F_OFD_GETLK:
        case F_OFD_SETLK:
        case F_OFD_SETLKW:
#endif
#ifdef F_GETOWN_EX
        case F_GETOWN_EX:
        case F_SETOWN_EX:
#endif
#ifdef F_GET_RW_HINT
        case F_GET_RW_HINT:
#endif
#ifdef F_SET_RW_HINT
        case F_SET_RW_HINT:
#endif
#ifdef F_GET_FILE_RW_HINT
        case F_GET_FILE_RW_HINT:
#endif
#ifdef F_SET_FILE_RW_HINT
        case F_SET_FILE_RW_HINT:
#endif
            return true;
        default:
            return false;
    }
}

extern "C" int controlled_socket(int domain, int type, int protocol) {
    SocketFn function = require_real(real_socket, "socket");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (domain != AF_INET && domain != AF_INET6 && domain != AF_UNIX) { errno = EAFNOSUPPORT; return -1; }
    const int socket_fd = function(domain, type, protocol);
    if (socket_fd < 0) return socket_fd;
    if (!native_register_socket(socket_fd, domain, type, protocol)) {
        CloseFn close_function = require_real(real_close, "close");
        if (close_function != nullptr) (void) close_function(socket_fd);
        errno = EMFILE;
        return -1;
    }
    NativeFdLedger::register_fd(socket_fd, NativeFdOwnership::GuestOwned,
            global_policy().snapshot().revision);
    return socket_fd;
}

extern "C" int controlled_close(int descriptor) {
    CloseFn function = require_real(real_close, "close");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    // Linux releases the descriptor before reporting late close errors. Remove policy state first so
    // another thread cannot reuse the numeric descriptor and then have its new state erased here.
    native_unregister_socket(descriptor);
    global_policy().unregister_capability_fd(descriptor);
    NativeFdLedger::close(descriptor);
    return function(descriptor);
}

bool bind_duplicate_or_close(int source, int duplicated) noexcept {
    if (duplicated < 0) return false;
    const bool capability = global_policy().is_capability_fd(source);
    const bool tracked_socket = native_is_tracked_socket(source);
    if (native_rebind_duplicated_descriptor(source, duplicated)
            || capability || !tracked_socket) {
        if (capability) global_policy().register_capability_fd(duplicated);
        NativeFdLedger::duplicate(source, duplicated);
        return true;
    }
    CloseFn close_function = require_real(real_close, "close");
    if (close_function != nullptr) {
        NativeFdLedger::close(duplicated);
        (void) close_function(duplicated);
    }
    errno = EMFILE;
    return false;
}

extern "C" int controlled_dup(int descriptor) {
    DupFn function = require_real(real_dup, "dup");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    const int duplicated = function(descriptor);
    return duplicated < 0 || bind_duplicate_or_close(descriptor, duplicated) ? duplicated : -1;
}

extern "C" int controlled_dup2(int descriptor, int target) {
    Dup2Fn function = require_real(real_dup2, "dup2");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    const int duplicated = function(descriptor, target);
    if (duplicated >= 0 && duplicated != descriptor) {
        global_policy().unregister_capability_fd(target);
    }
    return duplicated < 0 || bind_duplicate_or_close(descriptor, duplicated) ? duplicated : -1;
}

extern "C" int controlled_dup3(int descriptor, int target, int flags) {
    Dup3Fn function = require_real(real_dup3, "dup3");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    const int duplicated = function(descriptor, target, flags);
    if (duplicated >= 0 && duplicated != descriptor) {
        global_policy().unregister_capability_fd(target);
    }
    return duplicated < 0 || bind_duplicate_or_close(descriptor, duplicated) ? duplicated : -1;
}

extern "C" int controlled_fcntl(int descriptor, int command, ...) {
    FcntlFn function = require_real(real_fcntl, "fcntl");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    int status = -1;
    if (fcntl_has_no_argument(command)) {
        status = function(descriptor, command);
    } else {
        va_list values;
        va_start(values, command);
        if (fcntl_has_pointer_argument(command)) {
            void* argument = va_arg(values, void*);
            status = function(descriptor, command, argument);
        } else {
            const int argument = va_arg(values, int);
            status = function(descriptor, command, argument);
        }
        va_end(values);
    }
    if (status >= 0 && (command == F_DUPFD
#ifdef F_DUPFD_CLOEXEC
            || command == F_DUPFD_CLOEXEC
#endif
            )) {
        if (!bind_duplicate_or_close(descriptor, status)) return -1;
    }
    return status;
}

extern "C" int controlled_bind(int socket_fd, const sockaddr* address, socklen_t length) {
    BindFn function = require_real(real_bind, "bind");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    sockaddr_storage projected{};
    socklen_t projected_length = 0;
    if (native_project_bind_address(address, length, &projected, &projected_length) != 0) return -1;
    return function(socket_fd, reinterpret_cast<const sockaddr*>(&projected), projected_length);
}

extern "C" int controlled_connect(int socket_fd, const sockaddr* address, socklen_t length) {
    ConnectFn function = require_real(real_connect, "connect");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!native_socket_destination_allowed(socket_fd, address, length)) { errno = EACCES; return -1; }
    return function(socket_fd, address, length);
}

extern "C" ssize_t controlled_send(int socket_fd, const void* buffer, size_t length, int flags) {
    SendFn function = require_real(real_send, "send");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (buffer == nullptr && length != 0) { errno = EFAULT; return -1; }
    if (native_is_tracked_socket(socket_fd) && check_connected_peer(socket_fd, true) < 0) return -1;
    return function(socket_fd, buffer, length, flags);
}

extern "C" ssize_t controlled_sendto(int socket_fd, const void* buffer, size_t length, int flags,
                                      const sockaddr* destination, socklen_t destination_length) {
    SendToFn function = require_real(real_sendto, "sendto");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (buffer == nullptr && length != 0) { errno = EFAULT; return -1; }
    if (destination != nullptr) {
        if (!native_socket_destination_allowed(socket_fd, destination, destination_length)) {
            errno = EACCES;
            return -1;
        }
    } else if (native_is_tracked_socket(socket_fd) && check_connected_peer(socket_fd, true) < 0) {
        return -1;
    }
    return function(socket_fd, buffer, length, flags, destination, destination_length);
}

extern "C" ssize_t controlled_sendmsg(int socket_fd, const msghdr* message, int flags) {
    SendMsgFn function = require_real(real_sendmsg, "sendmsg");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (message == nullptr) { errno = EFAULT; return -1; }
    if (message->msg_name != nullptr) {
        if (!native_socket_destination_allowed(socket_fd,
                static_cast<const sockaddr*>(message->msg_name), message->msg_namelen)) {
            errno = EACCES;
            return -1;
        }
    } else if (native_is_tracked_socket(socket_fd) && check_connected_peer(socket_fd, true) < 0) {
        return -1;
    }
    return function(socket_fd, message, flags);
}

extern "C" ssize_t controlled_recv(int socket_fd, void* buffer, size_t length, int flags) {
    return checked_recvfrom(socket_fd, buffer, length, flags, nullptr, nullptr);
}

extern "C" ssize_t controlled_recvfrom(int socket_fd, void* buffer, size_t length, int flags,
                                        sockaddr* source, socklen_t* source_length) {
    return checked_recvfrom(socket_fd, buffer, length, flags, source, source_length);
}

extern "C" ssize_t controlled_recvmsg(int socket_fd, msghdr* message, int flags) {
    RecvMsgFn function = require_real(real_recvmsg, "recvmsg");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (message == nullptr
            || (message->msg_name == nullptr && message->msg_namelen != 0)
            || (message->msg_control == nullptr && message->msg_controllen != 0)) {
        errno = EFAULT;
        return -1;
    }
    if (message->msg_controllen > MAX_TEMP_NETWORK_CONTROL) { errno = EMSGSIZE; return -1; }

    const std::shared_ptr<std::mutex> socket_mutex = native_socket_io_mutex(socket_fd);
    std::mutex& selected_mutex = socket_mutex == nullptr
            ? network_receive_lock(socket_fd) : *socket_mutex;
    std::lock_guard receive_lock(selected_mutex);
    const bool message_oriented = message_oriented_socket(socket_fd);
    sockaddr_storage probed_source{};
    socklen_t probed_source_length = 0;
    if (message_oriented) {
        unsigned char probe_byte{};
        iovec probe_vector{&probe_byte, sizeof(probe_byte)};
        msghdr probe{};
        probe.msg_name = &probed_source;
        probe.msg_namelen = sizeof(probed_source);
        probe.msg_iov = &probe_vector;
        probe.msg_iovlen = 1;
        const ssize_t probe_status = function(socket_fd, &probe, flags | MSG_PEEK);
        if (probe_status < 0) return probe_status;
        probed_source_length = probe.msg_namelen;
        if (!native_socket_address_allowed(reinterpret_cast<const sockaddr*>(&probed_source),
                                            probe.msg_namelen)) {
            errno = EACCES;
            return -1;
        }
    } else if (check_connected_peer(socket_fd, false) < 0) {
        return -1;
    }

    try {
        std::vector<unsigned char> temporary_payload;
        std::vector<iovec> temporary_vectors;
        if (!prepare_temporary_iovecs(*message, temporary_payload, temporary_vectors)) return -1;
        std::vector<unsigned char> temporary_control(message->msg_controllen);
        sockaddr_storage temporary_source{};
        msghdr temporary{};
        temporary.msg_name = &temporary_source;
        temporary.msg_namelen = sizeof(temporary_source);
        temporary.msg_iov = temporary_vectors.empty() ? nullptr : temporary_vectors.data();
        temporary.msg_iovlen = temporary_vectors.size();
        temporary.msg_control = temporary_control.empty() ? nullptr : temporary_control.data();
        temporary.msg_controllen = temporary_control.size();

        const ssize_t received = function(socket_fd, &temporary, flags);
        if (received < 0) return received;
        if (message_oriented && !socket_addresses_equal(
                probed_source, probed_source_length, temporary_source, temporary.msg_namelen)) {
            close_received_file_descriptors(temporary);
            errno = EAGAIN;
            return -1;
        }

        copy_temporary_iovecs(temporary_payload, *message, received);
        if (message->msg_name != nullptr) {
            const socklen_t capacity = message->msg_namelen;
            const std::size_t copied = std::min<std::size_t>(capacity, temporary.msg_namelen);
            if (copied > 0) std::memcpy(message->msg_name, &temporary_source, copied);
            message->msg_namelen = temporary.msg_namelen;
        }
        if (message->msg_control != nullptr && message->msg_controllen > 0) {
            const std::size_t copied = std::min<std::size_t>(
                    message->msg_controllen, temporary.msg_controllen);
            if (copied > 0) std::memcpy(message->msg_control, temporary_control.data(), copied);
        }
        message->msg_controllen = temporary.msg_controllen;
        message->msg_flags = temporary.msg_flags;
        observe_received_file_descriptors(*message);
        return received;
    } catch (const std::bad_alloc&) {
        errno = ENOMEM;
        return -1;
    } catch (...) {
        errno = EACCES;
        return -1;
    }
}

extern "C" ssize_t controlled_read(int descriptor, void* buffer, size_t length) {
    ReadFn function = require_real(real_read, "read");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!native_is_tracked_socket(descriptor)) return function(descriptor, buffer, length);
    return checked_recvfrom(descriptor, buffer, length, 0, nullptr, nullptr);
}

extern "C" ssize_t controlled_write(int descriptor, const void* buffer, size_t length) {
    WriteFn function = require_real(real_write, "write");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!native_is_tracked_socket(descriptor)) return function(descriptor, buffer, length);
    if (buffer == nullptr && length != 0) { errno = EFAULT; return -1; }
    if (check_connected_peer(descriptor, true) < 0) return -1;
    return function(descriptor, buffer, length);
}

template <typename Call>
int controlled_accept_common(int socket_fd, sockaddr* address, socklen_t* length, Call&& call) {
    if (!socket_address_arguments_valid(address, length)) { errno = EFAULT; return -1; }
    const socklen_t address_capacity = length == nullptr ? 0 : *length;
    sockaddr_storage temporary_address{};
    socklen_t temporary_length = sizeof(temporary_address);
    const int accepted = call(reinterpret_cast<sockaddr*>(&temporary_address), &temporary_length);
    if (accepted < 0) return accepted;
    if (!native_socket_address_allowed(reinterpret_cast<const sockaddr*>(&temporary_address),
                                       temporary_length)) {
        CloseFn close_function = require_real(real_close, "close");
        if (close_function != nullptr) (void) close_function(accepted);
        errno = EACCES;
        return -1;
    }
    bool registered = native_adopt_accepted_socket(socket_fd, accepted);
    if (!registered) {
        const int type = socket_type_for_interceptor(accepted);
        registered = native_register_socket(accepted, temporary_address.ss_family, type, 0);
    }
    if (!registered) {
        CloseFn close_function = require_real(real_close, "close");
        if (close_function != nullptr) (void) close_function(accepted);
        errno = EMFILE;
        return -1;
    }
    NativeFdLedger::register_fd(accepted, NativeFdOwnership::GuestOwned,
            global_policy().snapshot().revision);
    copy_socket_address(address, length, address_capacity, temporary_address, temporary_length);
    return accepted;
}

extern "C" int controlled_accept(int socket_fd, sockaddr* address, socklen_t* length) {
    AcceptFn function = require_real(real_accept, "accept");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return controlled_accept_common(socket_fd, address, length,
            [&](sockaddr* temporary, socklen_t* temporary_length) {
                return function(socket_fd, temporary, temporary_length);
            });
}

extern "C" int controlled_accept4(int socket_fd, sockaddr* address, socklen_t* length, int flags) {
    Accept4Fn function = require_real(real_accept4, "accept4");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return controlled_accept_common(socket_fd, address, length,
            [&](sockaddr* temporary, socklen_t* temporary_length) {
                return function(socket_fd, temporary, temporary_length, flags);
            });
}

extern "C" int controlled_getsockname(int socket_fd, sockaddr* address, socklen_t* length) {
    GetSockNameFn function = require_real(real_getsockname, "getsockname");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!socket_address_arguments_valid(address, length) || address == nullptr) {
        errno = EFAULT;
        return -1;
    }
    const socklen_t capacity = *length;
    sockaddr_storage temporary{};
    socklen_t temporary_length = sizeof(temporary);
    const int status = function(socket_fd, reinterpret_cast<sockaddr*>(&temporary), &temporary_length);
    if (status != 0) return status;
    native_project_local_address(reinterpret_cast<sockaddr*>(&temporary), temporary_length);
    copy_socket_address(address, length, capacity, temporary, temporary_length);
    return 0;
}

extern "C" int controlled_getpeername(int socket_fd, sockaddr* address, socklen_t* length) {
    GetPeerNameFn function = require_real(real_getpeername, "getpeername");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!socket_address_arguments_valid(address, length) || address == nullptr) {
        errno = EFAULT;
        return -1;
    }
    const socklen_t capacity = *length;
    sockaddr_storage temporary{};
    socklen_t temporary_length = sizeof(temporary);
    const int status = function(socket_fd, reinterpret_cast<sockaddr*>(&temporary), &temporary_length);
    if (status != 0) return status;
    if (!native_socket_address_allowed(reinterpret_cast<const sockaddr*>(&temporary), temporary_length)) {
        errno = EACCES;
        return -1;
    }
    copy_socket_address(address, length, capacity, temporary, temporary_length);
    return 0;
}


}  // namespace
}  // namespace controlled_sandbox
