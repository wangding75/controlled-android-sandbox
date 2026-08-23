#include <jni.h>

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

namespace {

#if defined(__x86_64__)
constexpr const char* kAbi = "x86_64";
#elif defined(__aarch64__)
constexpr const char* kAbi = "arm64-v8a";
#elif defined(__i386__)
constexpr const char* kAbi = "x86";
#elif defined(__arm__)
constexpr const char* kAbi = "armeabi-v7a";
#else
constexpr const char* kAbi = "unknown";
#endif

std::string g_files_dir;
std::string g_context = "DIRECT_FIXTURE";

std::string json_escape(const std::string& value) {
    std::string escaped;
    for (const unsigned char character : value) {
        if (character == '\\' || character == '"') {
            escaped.push_back('\\');
            escaped.push_back(static_cast<char>(character));
        } else if (character == '\n') {
            escaped += "\\n";
        } else if (character == '\r') {
            escaped += "\\r";
        } else if (character == '\t') {
            escaped += "\\t";
        } else if (character < 0x20U) {
            char buffer[8];
            snprintf(buffer, sizeof(buffer), "\\u%04x", character);
            escaped += buffer;
        } else {
            escaped.push_back(static_cast<char>(character));
        }
    }
    return escaped;
}

std::string json_value(const char* key, const std::string& value) {
    return std::string("\"") + key + "\":\"" + json_escape(value) + "\"";
}

std::string case_json(const char* id, const char* status, const std::string& detail) {
    return "{" + json_value("id", id) + "," + json_value("status", status)
            + "," + json_value("abi", kAbi) + "," + json_value("context", g_context)
            + "," + json_value("detail", detail) + "}";
}

std::string rc_errno(int rc, int error) {
    return std::to_string(rc) + "/" + std::to_string(rc < 0 ? error : 0);
}

bool sandbox_context() { return g_context == "IN_SANDBOX"; }

std::string work_path(const char* leaf) { return g_files_dir + "/" + leaf; }

bool readlink_value(const std::string& path, std::string& value, int& error) {
    char buffer[512] = {};
    const ssize_t length = ::readlink(path.c_str(), buffer, sizeof(buffer) - 1U);
    if (length < 0) {
        error = errno;
        value.clear();
        return false;
    }
    error = 0;
    value.assign(buffer, static_cast<std::size_t>(length));
    return true;
}

std::string readlink_state(const std::string& path, bool& readable) {
    int error = 0;
    std::string value;
    readable = readlink_value(path, value, error);
    return readable ? value : ("DENIED/" + std::to_string(error));
}

struct ProcProbe {
    int fd{-1};
    int error{0};
};

ProcProbe probe_proc(const char* path, bool read_content) {
    ProcProbe result;
    result.fd = ::open(path, read_content ? O_RDONLY | O_CLOEXEC
                                         : O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (result.fd < 0) {
        result.error = errno;
        return result;
    }
    if (read_content) {
        char buffer[128] = {};
        if (::read(result.fd, buffer, sizeof(buffer)) < 0) result.error = errno;
    }
    (void) ::close(result.fd);
    return result;
}

#if defined(__x86_64__)
long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    long result;
    register long r10 asm("r10") = a3;
    register long r8 asm("r8") = a4;
    register long r9 asm("r9") = a5;
    asm volatile("syscall"
                 : "=a"(result)
                 : "a"(number), "D"(a0), "S"(a1), "d"(a2), "r"(r10), "r"(r8), "r"(r9)
                 : "rcx", "r11", "memory");
    return result;
}
#define C3T02_RAW_SYSCALL 1
#elif defined(__aarch64__)
long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long x8 asm("x8") = number;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = a5;
    asm volatile("svc #0"
                 : "+r"(x0)
                 : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                 : "memory");
    return x0;
}
#define C3T02_RAW_SYSCALL 1
#elif defined(__i386__)
long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    long result;
    asm volatile("push %%ebp\n\tmovl %7, %%ebp\n\tint $0x80\n\tpop %%ebp"
                 : "=a"(result)
                 : "a"(number), "b"(a0), "c"(a1), "d"(a2), "S"(a3), "D"(a4), "g"(a5)
                 : "memory");
    return result;
}
#define C3T02_RAW_SYSCALL 1
#elif defined(__arm__)
long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long r0 asm("r0") = a0;
    register long r1 asm("r1") = a1;
    register long r2 asm("r2") = a2;
    register long r3 asm("r3") = a3;
    register long r4 asm("r4") = a4;
    register long r5 asm("r5") = a5;
    asm volatile("push {r7}\n\tmov r7, %[nr]\n\tsvc #0\n\tpop {r7}"
                 : "+r"(r0)
                 : [nr] "r"(number), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5)
                 : "memory");
    return r0;
}
#define C3T02_RAW_SYSCALL 1
#else
long raw_syscall6(long, long, long, long, long, long, long) { return -ENOSYS; }
#define C3T02_RAW_SYSCALL 0
#endif

int from_raw(long value) {
    if (value < 0 && value >= -4095) {
        errno = static_cast<int>(-value);
        return -1;
    }
    return static_cast<int>(value);
}

std::string case_fs() {
    const std::string root = work_path("c3-t02-root");
    (void) ::mkdir(root.c_str(), 0700);
    const int root_fd = ::open(root.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    const int root_errno = root_fd < 0 ? errno : 0;
    const int target_fd = root_fd < 0 ? -1 : ::openat(root_fd, "target.txt",
            O_CREAT | O_RDWR | O_TRUNC | O_CLOEXEC, 0600);
    const int target_errno = target_fd < 0 ? errno : 0;
    const char payload[] = "c3-t02";
    const ssize_t write_rc = target_fd < 0 ? -1 : ::write(target_fd, payload, sizeof(payload) - 1U);
    const int write_errno = write_rc < 0 ? errno : 0;
    const int inside_link_rc = root_fd < 0 ? -1 : ::symlinkat("target.txt", root_fd, "inside-link");
    const int inside_link_errno = inside_link_rc < 0 ? errno : 0;
    const int escape_link_rc = root_fd < 0 ? -1 : ::symlinkat("/proc/net", root_fd, "escape-link");
    const int escape_link_errno = escape_link_rc < 0 ? errno : 0;
    const int linked_fd = root_fd < 0 ? -1 : ::openat(root_fd, "inside-link",
            O_RDONLY | O_CLOEXEC);
    const int linked_errno = linked_fd < 0 ? errno : 0;
    const int escape_fd = root_fd < 0 ? -1 : ::openat(root_fd, "escape-link",
            O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    const int escape_errno = escape_fd < 0 ? errno : 0;
    struct stat value {};
    const int stat_rc = root_fd < 0 ? -1 : ::fstatat(root_fd, "inside-link", &value, 0);
    const int stat_errno = stat_rc < 0 ? errno : 0;
    char link_buffer[128] = {};
    const ssize_t readlink_rc = root_fd < 0 ? -1
            : ::readlinkat(root_fd, "inside-link", link_buffer, sizeof(link_buffer) - 1U);
    const int readlink_errno = readlink_rc < 0 ? errno : 0;
    if (target_fd >= 0) (void) ::close(target_fd);
    if (linked_fd >= 0) (void) ::close(linked_fd);
    if (escape_fd >= 0) (void) ::close(escape_fd);
    if (root_fd >= 0) {
        (void) ::unlinkat(root_fd, "inside-link", 0);
        (void) ::unlinkat(root_fd, "escape-link", 0);
        (void) ::unlinkat(root_fd, "target.txt", 0);
        (void) ::close(root_fd);
    }
    (void) ::rmdir(root.c_str());
    const bool confined = root_fd >= 0 && target_fd >= 0 && write_rc == sizeof(payload) - 1U
            && inside_link_rc == 0 && linked_fd >= 0 && stat_rc == 0 && readlink_rc >= 0;
    const bool pass = confined && (!sandbox_context() || escape_fd < 0);
    return case_json("C3-T02-FS-001", pass ? "PASS_COMPAT" : "ERROR",
            "dfd=" + rc_errno(root_fd, root_errno)
            + ";relative_open=" + rc_errno(target_fd, target_errno)
            + ";relative_write=" + rc_errno(static_cast<int>(write_rc), write_errno)
            + ";symlink_inside=" + rc_errno(inside_link_rc, inside_link_errno)
            + ";symlink_escape=" + rc_errno(escape_link_rc, escape_link_errno)
            + ";symlink_follow=" + rc_errno(linked_fd, linked_errno)
            + ";escape_open=" + rc_errno(escape_fd, escape_errno)
            + ";fstatat=" + rc_errno(stat_rc, stat_errno)
            + ";readlinkat=" + rc_errno(static_cast<int>(readlink_rc), readlink_errno)
            + ";escape_classification=" + (sandbox_context() ? "DENY_REQUIRED" : "DIRECT_COMPAT")
            + ";cleanup=ATTEMPTED");
}

std::string case_proc() {
    const ProcProbe maps = probe_proc("/proc/self/maps", true);
    const ProcProbe smaps = probe_proc("/proc/self/smaps", true);
    const ProcProbe fd_dir = probe_proc("/proc/self/fd", false);
    const ProcProbe task_dir = probe_proc("/proc/self/task", false);
    const ProcProbe cgroup = probe_proc("/proc/self/cgroup", true);
    const ProcProbe proc_net = probe_proc("/proc/net", false);
    const ProcProbe unknown_leaf = probe_proc("/proc/self/environ", true);
    const ProcProbe unknown_fdinfo = probe_proc("/proc/self/fdinfo/9999", true);
    const std::string target_path = work_path("c3-t02-proc-fd");
    const int fd = ::open(target_path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    const int fd_errno = fd < 0 ? errno : 0;
    bool fd_readable = false;
    const std::string fd_target = fd < 0 ? "NO_FD" : readlink_state(
            "/proc/self/fd/" + std::to_string(fd), fd_readable);
    const std::string guest_root = g_files_dir + "/";
    const bool target_in_guest_root = fd_readable
            && (fd_target == g_files_dir || fd_target.rfind(guest_root, 0) == 0);
    const bool host_leak = fd_readable && !target_in_guest_root;
    const std::string fdinfo_path = "/proc/self/fdinfo/" + std::to_string(fd);
    const ProcProbe fdinfo = fd < 0 ? ProcProbe{} : probe_proc(fdinfo_path.c_str(), true);
    bool unknown_fd_readable = false;
    (void) readlink_state("/proc/self/fd/9999", unknown_fd_readable);
    if (fd >= 0) {
        (void) ::close(fd);
        (void) ::unlink(target_path.c_str());
    }
    const bool known_ok = maps.fd >= 0 && smaps.fd >= 0 && fd_dir.fd >= 0
            && task_dir.fd >= 0 && cgroup.fd >= 0 && fdinfo.fd >= 0;
    const bool unknown_denied = proc_net.fd < 0 && unknown_leaf.fd < 0
            && unknown_fdinfo.fd < 0 && !unknown_fd_readable;
    const bool direct_compat = proc_net.fd >= 0 && unknown_leaf.fd >= 0
            && unknown_fdinfo.fd < 0 && !unknown_fd_readable;
    const bool proc_policy_ok = sandbox_context() ? unknown_denied : direct_compat;
    const bool pass = known_ok && proc_policy_ok && (!sandbox_context() || !host_leak);
    return case_json("C3-T02-PROC-001", pass ? "PASS_COMPAT" : "ERROR",
            "open=" + rc_errno(fd, fd_errno)
            + ";maps=" + rc_errno(maps.fd, maps.error)
            + ";smaps=" + rc_errno(smaps.fd, smaps.error)
            + ";fd_dir=" + rc_errno(fd_dir.fd, fd_dir.error)
            + ";task=" + rc_errno(task_dir.fd, task_dir.error)
            + ";cgroup=" + rc_errno(cgroup.fd, cgroup.error)
            + ";fdinfo=" + rc_errno(fdinfo.fd, fdinfo.error)
            + ";proc_net=" + rc_errno(proc_net.fd, proc_net.error)
            + ";unknown_proc=" + rc_errno(unknown_leaf.fd, unknown_leaf.error)
            + ";unknown_fdinfo=" + rc_errno(unknown_fdinfo.fd, unknown_fdinfo.error)
            + ";fd_target_class=" + (host_leak ? "HOST_LEAK" : "VIRTUAL_OR_REDACTED")
            + ";unknown_fd_readlink=" + (unknown_fd_readable ? "OPEN" : "DENIED")
            + ";host_leak=" + (host_leak ? "1" : "0")
            + ";fd_snapshot=known:" + std::to_string(known_ok ? 6 : 0)
            + ";unknown_entries=DENY_REQUIRED");
}

std::string case_network() {
    addrinfo hints {};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo* resolved = nullptr;
    const int dns_rc = ::getaddrinfo("localhost", "80", &hints, &resolved);
    const bool dns_ok = dns_rc == 0 && resolved != nullptr;
    if (resolved != nullptr) ::freeaddrinfo(resolved);
    const int fd = ::socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    const int socket_errno = fd < 0 ? errno : 0;
    sockaddr_in address {};
    address.sin_family = AF_INET;
    address.sin_port = htons(9);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    const int connect_rc = fd < 0 ? -1 : ::connect(fd,
            reinterpret_cast<sockaddr*>(&address), sizeof(address));
    const int connect_errno = connect_rc < 0 ? errno : 0;
    sockaddr_in local {};
    socklen_t local_length = sizeof(local);
    const int getsockname_rc = fd < 0 ? -1 : ::getsockname(fd,
            reinterpret_cast<sockaddr*>(&local), &local_length);
    const int getsockname_errno = getsockname_rc < 0 ? errno : 0;
    if (fd >= 0) (void) ::close(fd);
    const bool pass = dns_ok || dns_rc == EAI_AGAIN || dns_rc == EAI_NONAME;
    return case_json("C3-T02-NET-001", pass ? "PASS_COMPAT" : "ERROR",
            "dns_rc=" + std::to_string(dns_rc)
            + ";dns_class=" + (dns_ok ? "RESOLVED" : "EXPECTED_RUNTIME_LIMIT")
            + ";socket=" + rc_errno(fd, socket_errno)
            + ";connect=" + rc_errno(connect_rc, connect_errno)
            + ";getsockname=" + rc_errno(getsockname_rc, getsockname_errno)
            + ";target=loopback:9;trace=socket/connect/getsockname/getaddrinfo");
}

std::string case_dup_and_close() {
    const std::string path = work_path("c3-t02-fd-dup");
    const int fd = ::open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    const int fd_errno = fd < 0 ? errno : 0;
    const int duplicate = fd < 0 ? -1 : ::dup(fd);
    const int duplicate_errno = duplicate < 0 ? errno : 0;
    const int duplicate2 = fd < 0 ? -1 : ::dup2(fd, 200);
    const int duplicate2_errno = duplicate2 < 0 ? errno : 0;
    const int duplicate3 = fd < 0 ? -1 : ::dup3(fd, 201, O_CLOEXEC);
    const int duplicate3_errno = duplicate3 < 0 ? errno : 0;
#ifdef F_DUPFD_CLOEXEC
    const int fduplicate = fd < 0 ? -1 : ::fcntl(fd, F_DUPFD_CLOEXEC, 3);
#else
    const int fduplicate = -1;
#endif
    const int fduplicate_errno = fduplicate < 0 ? errno : 0;
    const int flags = duplicate < 0 ? -1 : ::fcntl(duplicate, F_GETFD);
    const int flags_errno = flags < 0 ? errno : 0;
    const int cloexec_rc = duplicate < 0 ? -1 : ::fcntl(duplicate, F_SETFD,
            flags | FD_CLOEXEC);
    const int cloexec_errno = cloexec_rc < 0 ? errno : 0;
    bool before_readable = false;
    if (duplicate >= 0) {
        (void) readlink_state("/proc/self/fd/" + std::to_string(duplicate), before_readable);
    }
    if (fd >= 0) (void) ::close(fd);
    if (duplicate >= 0) (void) ::close(duplicate);
    if (duplicate2 >= 0) (void) ::close(duplicate2);
    if (duplicate3 >= 0) (void) ::close(duplicate3);
    if (fduplicate >= 0) (void) ::close(fduplicate);
    bool after_readable = false;
    if (duplicate >= 0) {
        (void) readlink_state("/proc/self/fd/" + std::to_string(duplicate), after_readable);
    }
    (void) ::unlink(path.c_str());
    const bool all_dup = fd >= 0 && duplicate >= 0 && duplicate2 == 200
            && duplicate3 == 201 && fduplicate >= 0 && flags >= 0 && cloexec_rc == 0;
    const bool converged = before_readable && !after_readable;
    return case_json("C3-T02-FD-001", all_dup && converged ? "PASS_COMPAT" : "ERROR",
            "open=" + rc_errno(fd, fd_errno)
            + ";dup=" + rc_errno(duplicate, duplicate_errno)
            + ";dup2=" + rc_errno(duplicate2, duplicate2_errno)
            + ";dup3=" + rc_errno(duplicate3, duplicate3_errno)
            + ";fcntl_dupfd=" + rc_errno(fduplicate, fduplicate_errno)
            + ";getfd=" + rc_errno(flags, flags_errno)
            + ";set_cloexec=" + rc_errno(cloexec_rc, cloexec_errno)
            + ";before_snapshot=" + (before_readable ? "VISIBLE" : "MISSING")
            + ";after_snapshot=" + (after_readable ? "VISIBLE" : "CLOSED")
            + ";close_converged=" + (converged ? "1" : "0"));
}

std::string case_scm_rights() {
    const std::string path = work_path("c3-t02-fd-pass");
    const int source = ::open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    const int source_errno = source < 0 ? errno : 0;
    int sockets[2] = {-1, -1};
    const int socketpair_rc = ::socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, sockets);
    const int socketpair_errno = socketpair_rc < 0 ? errno : 0;
    char byte = 'F';
    iovec outgoing_iov {&byte, sizeof(byte)};
    char outgoing_control[CMSG_SPACE(sizeof(int))] = {};
    msghdr outgoing {};
    outgoing.msg_iov = &outgoing_iov;
    outgoing.msg_iovlen = 1;
    outgoing.msg_control = outgoing_control;
    outgoing.msg_controllen = sizeof(outgoing_control);
    cmsghdr* outgoing_header = CMSG_FIRSTHDR(&outgoing);
    if (outgoing_header != nullptr) {
        outgoing_header->cmsg_level = SOL_SOCKET;
        outgoing_header->cmsg_type = SCM_RIGHTS;
        outgoing_header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(outgoing_header), &source, sizeof(source));
    }
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "SCM_BEFORE_SEND sockets=%d,%d source=%d",
            sockets[0], sockets[1], source);
    const ssize_t send_rc = socketpair_rc < 0 ? -1 : ::sendmsg(sockets[0], &outgoing, MSG_DONTWAIT);
    const int send_errno = send_rc < 0 ? errno : 0;
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "SCM_AFTER_SEND rc=%d errno=%d",
            static_cast<int>(send_rc), send_errno);
    char incoming_byte = 0;
    iovec incoming_iov {&incoming_byte, sizeof(incoming_byte)};
    char incoming_control[CMSG_SPACE(sizeof(int))] = {};
    msghdr incoming {};
    incoming.msg_iov = &incoming_iov;
    incoming.msg_iovlen = 1;
    incoming.msg_control = incoming_control;
    incoming.msg_controllen = sizeof(incoming_control);
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "SCM_BEFORE_RECV");
    const ssize_t receive_rc = socketpair_rc < 0 ? -1 : ::recvmsg(
            sockets[1], &incoming, MSG_DONTWAIT);
    const int receive_errno = receive_rc < 0 ? errno : 0;
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "SCM_AFTER_RECV rc=%d errno=%d",
            static_cast<int>(receive_rc), receive_errno);
    int received = -1;
    for (cmsghdr* header = CMSG_FIRSTHDR(&incoming); header != nullptr;
            header = CMSG_NXTHDR(&incoming, header)) {
        if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS
                && header->cmsg_len >= CMSG_LEN(sizeof(int))) {
            memcpy(&received, CMSG_DATA(header), sizeof(received));
            break;
        }
    }
    bool received_readable = false;
    if (received >= 0) {
        (void) readlink_state("/proc/self/fd/" + std::to_string(received), received_readable);
    }
    if (source >= 0) (void) ::close(source);
    if (received >= 0) (void) ::close(received);
    if (sockets[0] >= 0) (void) ::close(sockets[0]);
    if (sockets[1] >= 0) (void) ::close(sockets[1]);
    bool received_after = false;
    if (received >= 0) {
        (void) readlink_state("/proc/self/fd/" + std::to_string(received), received_after);
    }
    (void) ::unlink(path.c_str());
    const bool pass = source >= 0 && socketpair_rc == 0 && send_rc >= 0 && receive_rc >= 0
            && received >= 0 && received_readable && !received_after;
    return case_json("C3-T02-FD-002", pass ? "PASS_COMPAT" : "ERROR",
            "source=" + rc_errno(source, source_errno)
            + ";socketpair=" + rc_errno(socketpair_rc, socketpair_errno)
            + ";sendmsg=" + rc_errno(static_cast<int>(send_rc), send_errno)
            + ";recvmsg=" + rc_errno(static_cast<int>(receive_rc), receive_errno)
            + ";received_fd=" + std::to_string(received)
            + ";received_snapshot=" + (received_readable ? "VISIBLE" : "MISSING")
            + ";after_close=" + (received_after ? "VISIBLE" : "CLOSED")
            + ";ledger_event=SCM_RIGHTS_RECEIVE_AND_CLOSE");
}

std::string case_exec_lifecycle() {
    const std::string path = work_path("c3-t02-fd-exec");
    const int fd = ::open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    const int open_errno = fd < 0 ? errno : 0;
    const int flags = fd < 0 ? -1 : ::fcntl(fd, F_GETFD);
    const int flags_errno = flags < 0 ? errno : 0;
    const bool cloexec = flags >= 0 && (flags & FD_CLOEXEC) != 0;
    pid_t child = -1;
    int child_errno = 0;
    int wait_status = 0;
    if (fd >= 0) {
        child = ::fork();
        if (child == 0) {
            char arg0[] = "/system/bin/true";
            char* argv[] = {arg0, nullptr};
            char* envp[] = {nullptr};
            ::execve(arg0, argv, envp);
            _exit(127);
        }
        if (child < 0) child_errno = errno;
        if (child > 0) (void) ::waitpid(child, &wait_status, 0);
        (void) ::close(fd);
    }
    (void) ::unlink(path.c_str());
    const bool blocked = child < 0 && (child_errno == EPERM || child_errno == EACCES);
    const bool executed = child > 0 && WIFEXITED(wait_status) && WEXITSTATUS(wait_status) == 0;
    const bool pass = fd >= 0 && cloexec && (executed || blocked);
    const char* status = pass ? (blocked ? "BLOCKED_BY_POLICY" : "PASS_COMPAT") : "ERROR";
    return case_json("C3-T02-FD-003", status,
            "open=" + rc_errno(fd, open_errno)
            + ";getfd=" + rc_errno(flags, flags_errno)
            + ";cloexec=" + (cloexec ? "1" : "0")
            + ";fork=" + rc_errno(static_cast<int>(child), child_errno)
            + ";exec_result=" + (executed ? "EXECUTED" : (blocked ? "BLOCKED_BY_POLICY" : "FAILED"))
            + ";fd_lifecycle=close_on_exec_and_close");
}

std::string case_raw_bypass() {
    int raw_proc_fd = -1;
    int raw_proc_errno = ENOSYS;
    int raw_socket_fd = -1;
    int raw_socket_errno = ENOSYS;
#if C3T02_RAW_SYSCALL && defined(SYS_openat)
    const std::string proc_net = "/proc/net";
    raw_proc_fd = from_raw(raw_syscall6(SYS_openat, AT_FDCWD,
            reinterpret_cast<long>(proc_net.c_str()), O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0, 0, 0));
    raw_proc_errno = raw_proc_fd < 0 ? errno : 0;
#endif
#if C3T02_RAW_SYSCALL && defined(SYS_socket)
    raw_socket_fd = from_raw(raw_syscall6(SYS_socket, AF_INET, SOCK_DGRAM | SOCK_CLOEXEC,
            0, 0, 0, 0));
    raw_socket_errno = raw_socket_fd < 0 ? errno : 0;
#endif
    if (raw_proc_fd >= 0) (void) ::close(raw_proc_fd);
    if (raw_socket_fd >= 0) (void) ::close(raw_socket_fd);
    const bool raw_succeeded = raw_proc_fd >= 0 || raw_socket_fd >= 0;
    const char* status = sandbox_context()
            ? (raw_succeeded ? "BYPASS_CONFIRMED" : "BLOCKED_BY_POLICY")
            : "PASS_COMPAT";
    return case_json("C3-T02-RAW-001", status,
            "raw_proc_net=" + rc_errno(raw_proc_fd, raw_proc_errno)
            + ";raw_socket=" + rc_errno(raw_socket_fd, raw_socket_errno)
            + ";classification=" + (raw_succeeded
                    ? "UNMEDIATED_DIRECT_SYSCALL_EXPOSED" : "POLICY_BLOCKED")
            + ";raw_instruction_not_silent_pass=1");
}

std::string run_campaign() {
    std::string result = "{" + json_value("schema", "cas-c3-t02-file-proc-network-fd")
            + "," + json_value("abi", kAbi) + "," + json_value("context", g_context)
            + ",\"pid\":" + std::to_string(static_cast<long>(getpid())) + ",\"cases\":[";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_BEGIN FS");
    result += case_fs() + ",";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_DONE FS");
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_BEGIN PROC");
    result += case_proc() + ",";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_DONE PROC");
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_BEGIN NET");
    result += case_network() + ",";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_DONE NET");
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_BEGIN FD_DUP");
    result += case_dup_and_close() + ",";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_DONE FD_DUP");
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_BEGIN FD_SCM");
    result += case_scm_rights() + ",";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_DONE FD_SCM");
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_BEGIN FD_EXEC");
    result += case_exec_lifecycle() + ",";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_DONE FD_EXEC");
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_BEGIN RAW");
    result += case_raw_bypass() + "]}";
    __android_log_print(ANDROID_LOG_INFO, "CS_C3_T02", "CASE_DONE RAW");
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_C3T02FileProcNetworkFdProbe_nativeRunCampaign(
        JNIEnv* env, jclass, jstring files_dir, jstring context) {
    if (files_dir != nullptr) {
        const char* raw = env->GetStringUTFChars(files_dir, nullptr);
        if (raw != nullptr) {
            g_files_dir = raw;
            env->ReleaseStringUTFChars(files_dir, raw);
        }
    }
    if (context != nullptr) {
        const char* raw = env->GetStringUTFChars(context, nullptr);
        if (raw != nullptr) {
            g_context = raw;
            env->ReleaseStringUTFChars(context, raw);
        }
    }
    const std::string result = run_campaign();
    return env->NewStringUTF(result.c_str());
}
