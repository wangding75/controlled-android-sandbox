#pragma once

#include <cstddef>
#include <sys/socket.h>
#include <sys/types.h>

namespace controlled_sandbox {

extern "C" int controlled_socket(int domain, int type, int protocol);
extern "C" int controlled_close(int descriptor);
extern "C" int controlled_dup(int descriptor);
extern "C" int controlled_dup2(int descriptor, int target);
extern "C" int controlled_dup3(int descriptor, int target, int flags);
extern "C" int controlled_fcntl(int descriptor, int command, ...);
extern "C" int controlled_bind(int socket_fd, const sockaddr* address, socklen_t length);
extern "C" int controlled_connect(int socket_fd, const sockaddr* address, socklen_t length);
extern "C" ssize_t controlled_send(int socket_fd, const void* buffer, std::size_t length, int flags);
extern "C" ssize_t controlled_sendto(int socket_fd, const void* buffer, std::size_t length, int flags,
                                      const sockaddr* destination, socklen_t destination_length);
extern "C" ssize_t controlled_sendmsg(int socket_fd, const msghdr* message, int flags);
extern "C" ssize_t controlled_recv(int socket_fd, void* buffer, std::size_t length, int flags);
extern "C" ssize_t controlled_recvfrom(int socket_fd, void* buffer, std::size_t length, int flags,
                                        sockaddr* source, socklen_t* source_length);
extern "C" ssize_t controlled_recvmsg(int socket_fd, msghdr* message, int flags);
extern "C" ssize_t controlled_read(int descriptor, void* buffer, std::size_t length);
extern "C" ssize_t controlled_write(int descriptor, const void* buffer, std::size_t length);
extern "C" int controlled_accept(int socket_fd, sockaddr* address, socklen_t* length);
extern "C" int controlled_accept4(int socket_fd, sockaddr* address, socklen_t* length, int flags);
extern "C" int controlled_getsockname(int socket_fd, sockaddr* address, socklen_t* length);
extern "C" int controlled_getpeername(int socket_fd, sockaddr* address, socklen_t* length);

}  // namespace controlled_sandbox
