#include <jni.h>

#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

#ifndef PR_SET_NO_NEW_PRIVS
#define PR_SET_NO_NEW_PRIVS 38
#endif
#ifndef SECCOMP_MODE_FILTER
#define SECCOMP_MODE_FILTER 2
#endif
#ifndef SECCOMP_SET_MODE_FILTER
#define SECCOMP_SET_MODE_FILTER 1
#endif
#ifndef SECCOMP_RET_ERRNO
#define SECCOMP_RET_ERRNO 0x00050000U
#endif
#ifndef SECCOMP_RET_ALLOW
#define SECCOMP_RET_ALLOW 0x7fff0000U
#endif

#if defined(__x86_64__)
constexpr const char* kCompiledAbi = "x86_64";
#define ENF_AUDIT_ARCH AUDIT_ARCH_X86_64
#define RAW_SYSCALL_AVAILABLE 1
#elif defined(__aarch64__)
constexpr const char* kCompiledAbi = "arm64-v8a";
#define ENF_AUDIT_ARCH AUDIT_ARCH_AARCH64
#define RAW_SYSCALL_AVAILABLE 1
#elif defined(__i386__)
constexpr const char* kCompiledAbi = "x86";
#define ENF_AUDIT_ARCH AUDIT_ARCH_I386
#define RAW_SYSCALL_AVAILABLE 1
#elif defined(__arm__)
constexpr const char* kCompiledAbi = "armeabi-v7a";
#define ENF_AUDIT_ARCH AUDIT_ARCH_ARM
#define RAW_SYSCALL_AVAILABLE 1
#else
constexpr const char* kCompiledAbi = "unknown";
#define ENF_AUDIT_ARCH 0
#define RAW_SYSCALL_AVAILABLE 0
#endif

#if defined(__x86_64__)
static long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    long ret;
    register long r10 asm("r10") = a3;
    register long r8 asm("r8") = a4;
    register long r9 asm("r9") = a5;
    asm volatile("syscall"
                 : "=a"(ret)
                 : "a"(number), "D"(a0), "S"(a1), "d"(a2), "r"(r10), "r"(r8), "r"(r9)
                 : "rcx", "r11", "memory");
    return ret;
}
#elif defined(__aarch64__)
static long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
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
#elif defined(__i386__)
static long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    long ret;
    asm volatile(
            "push %%ebp\n\t"
            "movl %7, %%ebp\n\t"
            "int $0x80\n\t"
            "pop %%ebp"
            : "=a"(ret)
            : "a"(number), "b"(a0), "c"(a1), "d"(a2), "S"(a3), "D"(a4), "g"(a5)
            : "memory");
    return ret;
}
#elif defined(__arm__)
static long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long r0 asm("r0") = a0;
    register long r1 asm("r1") = a1;
    register long r2 asm("r2") = a2;
    register long r3 asm("r3") = a3;
    register long r4 asm("r4") = a4;
    register long r5 asm("r5") = a5;
    asm volatile(
            "push {r7}\n\t"
            "mov r7, %[nr]\n\t"
            "svc #0\n\t"
            "pop {r7}"
            : "+r"(r0)
            : [nr] "r"(number), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5)
            : "memory");
    return r0;
}
#else
static long raw_syscall6(long, long, long, long, long, long, long) { return -ENOSYS; }
#endif

static bool syscall_failed(long value) {
    return value < 0 && value >= -4095;
}

static int from_syscall(long value) {
    if (syscall_failed(value)) {
        errno = static_cast<int>(-value);
        return -1;
    }
    return static_cast<int>(value);
}

static std::string json_escape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (unsigned char ch : input) {
        if (ch == '\\' || ch == '"') {
            out.push_back('\\');
            out.push_back(static_cast<char>(ch));
        } else if (ch == '\n') {
            out += "\\n";
        } else if (ch == '\r') {
            out += "\\r";
        } else if (ch < 0x20) {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", ch);
            out += buf;
        } else {
            out.push_back(static_cast<char>(ch));
        }
    }
    return out;
}

static std::string attempt_json(const char* path_name, int rc, int err, const char* note) {
    std::string out = "{";
    out += "\"path\":\"";
    out += json_escape(path_name);
    out += "\",\"rc\":";
    out += std::to_string(rc);
    out += ",\"errno\":";
    out += std::to_string(err);
    out += ",\"errname\":\"";
    out += json_escape(err == 0 ? "ok" : strerror(err));
    out += "\",\"note\":\"";
    out += json_escape(note);
    out += "\"}";
    return out;
}

static int close_if(int fd) {
    if (fd >= 0) close(fd);
    return fd;
}

static int libc_open_path(const char* path) {
    return open(path, O_RDONLY | O_CLOEXEC);
}

static int syscall_open_path(const char* path) {
#ifdef SYS_openat
    return from_syscall(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0));
#else
    errno = ENOSYS;
    return -1;
#endif
}

static int raw_open_path(const char* path) {
#if RAW_SYSCALL_AVAILABLE && defined(SYS_openat)
    return from_syscall(raw_syscall6(SYS_openat, AT_FDCWD, reinterpret_cast<long>(path),
            O_RDONLY | O_CLOEXEC, 0, 0, 0));
#else
    errno = ENOSYS;
    return -1;
#endif
}

#if defined(__i386__)
#ifndef SYS_socketcall
#define SYS_socketcall 102
#endif
#ifndef SYS_SOCKET
#define SYS_SOCKET 1
#endif
#ifndef SYS_CONNECT
#define SYS_CONNECT 3
#endif

static int syscall_socket_i386() {
    unsigned long args[3] = {AF_INET, SOCK_STREAM, 0};
    return from_syscall(syscall(SYS_socketcall, SYS_SOCKET, args));
}

static int raw_socket_i386() {
    unsigned long args[3] = {AF_INET, SOCK_STREAM, 0};
    return from_syscall(raw_syscall6(SYS_socketcall, SYS_SOCKET, reinterpret_cast<long>(args),
            0, 0, 0, 0));
}

static int syscall_connect_i386(int fd, const sockaddr* addr, socklen_t len) {
    unsigned long args[3] = {static_cast<unsigned long>(fd),
            reinterpret_cast<unsigned long>(addr), static_cast<unsigned long>(len)};
    return from_syscall(syscall(SYS_socketcall, SYS_CONNECT, args));
}

static int raw_connect_i386(int fd, const sockaddr* addr, socklen_t len) {
    unsigned long args[3] = {static_cast<unsigned long>(fd),
            reinterpret_cast<unsigned long>(addr), static_cast<unsigned long>(len)};
    return from_syscall(raw_syscall6(SYS_socketcall, SYS_CONNECT, reinterpret_cast<long>(args),
            0, 0, 0, 0));
}
#endif

static int syscall_socket() {
#if defined(__i386__)
    return syscall_socket_i386();
#elif defined(SYS_socket)
    return from_syscall(syscall(SYS_socket, AF_INET, SOCK_STREAM, 0));
#else
    errno = ENOSYS;
    return -1;
#endif
}

static int raw_socket() {
#if defined(__i386__)
    return raw_socket_i386();
#elif RAW_SYSCALL_AVAILABLE && defined(SYS_socket)
    return from_syscall(raw_syscall6(SYS_socket, AF_INET, SOCK_STREAM, 0, 0, 0, 0));
#else
    errno = ENOSYS;
    return -1;
#endif
}

static int syscall_connect(int fd, const sockaddr* addr, socklen_t len) {
#if defined(__i386__)
    return syscall_connect_i386(fd, addr, len);
#elif defined(SYS_connect)
    return from_syscall(syscall(SYS_connect, fd, addr, len));
#else
    errno = ENOSYS;
    return -1;
#endif
}

static int raw_connect(int fd, const sockaddr* addr, socklen_t len) {
#if defined(__i386__)
    return raw_connect_i386(fd, addr, len);
#elif RAW_SYSCALL_AVAILABLE && defined(SYS_connect)
    return from_syscall(raw_syscall6(SYS_connect, fd, reinterpret_cast<long>(addr), len, 0, 0, 0));
#else
    errno = ENOSYS;
    return -1;
#endif
}

static void apply_connect_timeout(int fd) {
    struct timeval tv;
    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

static std::string connect_attempt(const char* kind,
        int (*make_socket)(),
        int (*do_connect)(int, const sockaddr*, socklen_t),
        const sockaddr_in* addr) {
    errno = 0;
    int fd = make_socket();
    int sock_err = fd < 0 ? errno : 0;
    int rc = -1;
    int err = sock_err;
    if (fd >= 0) {
        apply_connect_timeout(fd);
        errno = 0;
        rc = do_connect(fd, reinterpret_cast<const sockaddr*>(addr), sizeof(*addr));
        err = rc == 0 ? 0 : errno;
        close(fd);
    }
    std::string out = "{";
    out += "\"kind\":\"";
    out += json_escape(kind);
    out += "\",\"socket_fd\":";
    out += std::to_string(fd);
    out += ",\"socket_errno\":";
    out += std::to_string(sock_err);
    out += ",\"rc\":";
    out += std::to_string(rc);
    out += ",\"errno\":";
    out += std::to_string(err);
    out += ",\"errname\":\"";
    out += json_escape(err == 0 ? "ok" : strerror(err));
    out += "\",\"outcome\":\"";
    out += (rc == 0 ? "DIRECT_ALLOWED" : "DIRECT_DENIED");
    out += "\"}";
    return out;
}

static int libc_socket() {
    return socket(AF_INET, SOCK_STREAM, 0);
}

static int libc_connect_wrap(int fd, const sockaddr* addr, socklen_t len) {
    return connect(fd, addr, len);
}

static jstring to_jstring(JNIEnv* env, const std::string& text) {
    return env->NewStringUTF(text.c_str());
}

static std::string probe_open(const char* path) {
    int libc_fd = libc_open_path(path);
    int libc_err = libc_fd < 0 ? errno : 0;
    close_if(libc_fd);
    int sys_fd = syscall_open_path(path);
    int sys_err = sys_fd < 0 ? errno : 0;
    close_if(sys_fd);
    int raw_fd = raw_open_path(path);
    int raw_err = raw_fd < 0 ? errno : 0;
    close_if(raw_fd);

    std::string out = "{";
    out += "\"abi\":\"";
    out += kCompiledAbi;
    out += "\",\"path\":\"";
    out += json_escape(path ? path : "");
    out += "\",\"raw_available\":";
    out += RAW_SYSCALL_AVAILABLE ? "true" : "false";
    out += ",\"libc\":";
    out += attempt_json("libc_open", libc_fd, libc_err, "open");
    out += ",\"syscall\":";
    out += attempt_json("syscall_openat", sys_fd, sys_err, "SYS_openat");
    out += ",\"raw\":";
    out += attempt_json("raw_openat", raw_fd, raw_err,
            RAW_SYSCALL_AVAILABLE ? "raw_syscall" : "UNVERIFIED_RUNTIME");
    out += "}";
    return out;
}

static std::string probe_connect(const char* host, int port) {
    sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host ? host : "127.0.0.1", &addr.sin_addr) != 1) {
        return "{\"error\":\"bad_host\",\"abi\":\"" + std::string(kCompiledAbi) + "\"}";
    }
    std::string out = "{";
    out += "\"abi\":\"";
    out += kCompiledAbi;
    out += "\",\"host\":\"";
    out += json_escape(host ? host : "");
    out += "\",\"port\":";
    out += std::to_string(port);
    out += ",\"raw_available\":";
    out += RAW_SYSCALL_AVAILABLE ? "true" : "false";
    out += ",\"libc\":";
    out += connect_attempt("libc", libc_socket, libc_connect_wrap, &addr);
    out += ",\"syscall\":";
    out += connect_attempt("syscall", syscall_socket, syscall_connect, &addr);
    out += ",\"raw\":";
    out += connect_attempt("raw", raw_socket, raw_connect, &addr);
    out += "}";
    return out;
}

static std::string classify_seccomp(int prctl_nnp, int nnp_err, int filter_rc, int filter_err,
        int baseline_ppid, int filtered_ppid, int filtered_ppid_err, int live_pid,
        int child_status, int child_signal) {
    if (child_signal != 0) return "SECCOMP_FILTER_CRASHED";
    if (prctl_nnp != 0 && (nnp_err == EPERM || nnp_err == EACCES || nnp_err == EINVAL)) {
        return "SECCOMP_FILTER_BLOCKED_BY_ANDROID";
    }
    if (filter_rc != 0) {
        if (filter_err == EPERM || filter_err == EACCES || filter_err == EINVAL
                || filter_err == ENOSYS) {
            return "SECCOMP_FILTER_BLOCKED_BY_ANDROID";
        }
        return "SECCOMP_FILTER_REJECTED";
    }
    if (filtered_ppid < 0 && filtered_ppid_err == EPERM && live_pid > 0
            && baseline_ppid > 0) {
        return "SECCOMP_FILTER_FEASIBLE";
    }
    if (filter_rc == 0 && filtered_ppid == baseline_ppid) {
        return "SECCOMP_FILTER_REJECTED";
    }
    (void)child_status;
    return "SECCOMP_FILTER_REJECTED";
}

static int install_getppid_errno_filter() {
    struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ENF_AUDIT_ARCH, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getppid, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EPERM & 0xffff)),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog;
    prog.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    prog.filter = filter;
#ifdef SYS_seccomp
    long via_syscall = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog);
    if (!syscall_failed(via_syscall) && via_syscall == 0) return 0;
    int first_err = syscall_failed(via_syscall) ? static_cast<int>(-via_syscall) : errno;
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) == 0) return 0;
    if (errno == 0) errno = first_err;
    return -1;
#else
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog);
#endif
}

static std::string probe_seccomp_in_child() {
    int baseline_ppid = static_cast<int>(getppid());
    int nnp = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    int nnp_err = nnp == 0 ? 0 : errno;
    errno = 0;
    int filter_rc = install_getppid_errno_filter();
    int filter_err = filter_rc == 0 ? 0 : errno;
    errno = 0;
    int filtered_ppid = static_cast<int>(getppid());
    int filtered_err = filtered_ppid < 0 ? errno : 0;
    int live_pid = static_cast<int>(getpid());
    int live_tid_err = 0;
    (void)live_tid_err;
    std::string classification = classify_seccomp(nnp, nnp_err, filter_rc, filter_err,
            baseline_ppid, filtered_ppid, filtered_err, live_pid, 0, 0);
    char buf[1024];
    snprintf(buf, sizeof(buf),
            "{\"abi\":\"%s\",\"prctl_no_new_privs\":%d,\"prctl_errno\":%d,"
            "\"filter_rc\":%d,\"filter_errno\":%d,\"baseline_getppid\":%d,"
            "\"filtered_getppid\":%d,\"filtered_getppid_errno\":%d,"
            "\"live_getpid\":%d,\"signal\":0,\"classification\":\"%s\","
            "\"raw_available\":%s}",
            kCompiledAbi, nnp, nnp_err, filter_rc, filter_err, baseline_ppid,
            filtered_ppid, filtered_err, live_pid, classification.c_str(),
            RAW_SYSCALL_AVAILABLE ? "true" : "false");
    return std::string(buf);
}

static std::string probe_seccomp() {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        return "{\"classification\":\"SECCOMP_FILTER_REJECTED\",\"error\":\"pipe\",\"errno\":"
                + std::to_string(errno) + ",\"abi\":\"" + kCompiledAbi + "\"}";
    }
    pid_t child = fork();
    if (child < 0) {
        int err = errno;
        close(pipefd[0]);
        close(pipefd[1]);
        std::string fallback = probe_seccomp_in_child();
        return "{\"fork_errno\":" + std::to_string(err)
                + ",\"fork_used\":false,\"in_process\":true,\"result\":" + fallback + "}";
    }
    if (child == 0) {
        close(pipefd[0]);
        std::string body = probe_seccomp_in_child();
        const char* data = body.c_str();
        size_t remaining = body.size();
        while (remaining > 0) {
            ssize_t wrote = write(pipefd[1], data, remaining);
            if (wrote <= 0) break;
            data += static_cast<size_t>(wrote);
            remaining -= static_cast<size_t>(wrote);
        }
        close(pipefd[1]);
        _exit(0);
    }
    close(pipefd[1]);
    std::string body;
    char chunk[256];
    while (true) {
        ssize_t n = read(pipefd[0], chunk, sizeof(chunk));
        if (n <= 0) break;
        body.append(chunk, static_cast<size_t>(n));
    }
    close(pipefd[0]);
    int status = 0;
    waitpid(child, &status, 0);
    int signal = WIFSIGNALED(status) ? WTERMSIG(status) : 0;
    int exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    if (body.empty()) {
        const char* classification = signal != 0
                ? "SECCOMP_FILTER_CRASHED"
                : "SECCOMP_FILTER_REJECTED";
        char buf[512];
        snprintf(buf, sizeof(buf),
                "{\"abi\":\"%s\",\"fork_used\":true,\"child_exit\":%d,\"signal\":%d,"
                "\"classification\":\"%s\",\"error\":\"empty_child_output\"}",
                kCompiledAbi, exit_code, signal, classification);
        return std::string(buf);
    }
    std::string out = "{\"fork_used\":true,\"child_exit\":";
    out += std::to_string(exit_code);
    out += ",\"child_signal\":";
    out += std::to_string(signal);
    out += ",\"result\":";
    out += body;
    out += "}";
    return out;
}

static std::string syscall_attempt(const char* kind, long rc) {
    int err = 0;
    int normalized = static_cast<int>(rc);
    if (syscall_failed(rc)) {
        err = static_cast<int>(-rc);
        normalized = -1;
    } else if (rc < 0) {
        err = errno;
        normalized = -1;
    }
    std::string out = "{";
    out += "\"kind\":\"";
    out += json_escape(kind);
    out += "\",\"rc\":";
    out += std::to_string(normalized);
    out += ",\"errno\":";
    out += std::to_string(err);
    out += ",\"errname\":\"";
    out += json_escape(err == 0 ? "ok" : strerror(err));
    out += "\",\"denied\":";
    out += (normalized < 0 ? "true" : "false");
    out += "}";
    return out;
}

static bool path_is_host_private(const char* target, const char* host_package) {
    if (target == nullptr || host_package == nullptr || host_package[0] == 0) return false;
    std::string path(target);
    std::string pkg(host_package);
    return path.find("/data/data/" + pkg) != std::string::npos
            || (path.find("/data/user/") != std::string::npos && path.find(pkg) != std::string::npos);
}

static std::string probe_inherited_fds(const char* host_package) {
    DIR* dir = opendir("/proc/self/fd");
    int leaks = 0;
    int count = 0;
    std::string items = "[";
    if (dir == nullptr) {
        return "{\"error\":\"opendir\",\"errno\":" + std::to_string(errno) + ",\"count\":0,\"host_private_leaks\":0}";
    }
    int dir_fd = dirfd(dir);
    while (dirent* entry = readdir(dir)) {
        if (entry->d_name[0] == '.') continue;
        int fd = atoi(entry->d_name);
        if (fd == dir_fd) continue;
        char linkpath[64];
        snprintf(linkpath, sizeof(linkpath), "/proc/self/fd/%d", fd);
        char target[512];
        ssize_t n = readlink(linkpath, target, sizeof(target) - 1);
        if (n < 0) continue;
        target[n] = 0;
        count++;
        bool leak = path_is_host_private(target, host_package);
        if (leak) leaks++;
        if (items.size() > 1) items += ",";
        items += "{\"fd\":";
        items += std::to_string(fd);
        items += ",\"target\":\"";
        items += json_escape(target);
        items += "\",\"host_private\":";
        items += leak ? "true" : "false";
        items += "}";
        if (items.size() > 3500) break;
    }
    closedir(dir);
    items += "]";
    std::string out = "{";
    out += "\"count\":";
    out += std::to_string(count);
    out += ",\"host_private_leaks\":";
    out += std::to_string(leaks);
    out += ",\"fds\":";
    out += items;
    out += "}";
    return out;
}

static std::string probe_attack(const char* core_path, const char* other_guest,
        const char* host_package, int host_pid) {
    long ptrace_rc = -ENOSYS;
#ifdef SYS_ptrace
    errno = 0;
    if (host_pid > 0) {
        long attach = syscall(SYS_ptrace, PTRACE_ATTACH, host_pid, 0, 0);
        ptrace_rc = attach < 0 ? -errno : attach;
    } else {
        long trace = syscall(SYS_ptrace, PTRACE_TRACEME, 0, 0, 0);
        ptrace_rc = trace < 0 ? -errno : trace;
    }
#endif
    long exec_rc = -ENOSYS;
#ifdef SYS_execve
    const char* path = "/system/bin/true";
    char* argv[] = {const_cast<char*>(path), nullptr};
    errno = 0;
    exec_rc = syscall(SYS_execve, path, argv, environ);
    if (exec_rc < 0) exec_rc = -errno;
#endif
    errno = 0;
    pid_t child = fork();
    int fork_err = child < 0 ? errno : 0;
    int fork_rc = child < 0 ? -1 : static_cast<int>(child);
    if (child == 0) {
        _exit(0);
    }
    if (child > 0) {
        int status = 0;
        waitpid(child, &status, 0);
    }
    int binder_fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
    int binder_err = binder_fd < 0 ? errno : 0;
    close_if(binder_fd);
    int binderfs_fd = open("/dev/binderfs/binder", O_RDWR | O_CLOEXEC);
    int binderfs_err = binderfs_fd < 0 ? errno : 0;
    close_if(binderfs_fd);

    std::string out = "{";
    out += "\"abi\":\"";
    out += kCompiledAbi;
    out += "\",\"raw_available\":";
    out += RAW_SYSCALL_AVAILABLE ? "true" : "false";
    out += ",\"ptrace\":";
    out += syscall_attempt("ptrace", ptrace_rc);
    out += ",\"execve\":";
    out += syscall_attempt("execve", exec_rc);
    out += ",\"clone\":";
    out += attempt_json("fork", fork_rc, fork_err, fork_rc >= 0
            ? "KERNEL_LIMIT_EXPOSED_SAME_UID" : "DENIED");
    out += ",\"binder\":";
    out += attempt_json("/dev/binder", binder_fd, binder_err, "open");
    out += ",\"binderfs\":";
    out += attempt_json("/dev/binderfs/binder", binderfs_fd, binderfs_err, "open");
    out += ",\"inherited_fd\":";
    out += probe_inherited_fds(host_package);
    out += ",\"core_storage\":";
    out += probe_open(core_path ? core_path : "");
    out += ",\"other_guest\":";
    out += probe_open(other_guest ? other_guest : "");
    out += "}";
    return out;
}

static jstring jni_probe_open(JNIEnv* env, jclass, jstring path) {
    const char* utf = path == nullptr ? "" : env->GetStringUTFChars(path, nullptr);
    std::string result = probe_open(utf == nullptr ? "" : utf);
    if (path != nullptr && utf != nullptr) env->ReleaseStringUTFChars(path, utf);
    return to_jstring(env, result);
}

static jstring jni_probe_connect(JNIEnv* env, jclass, jstring host, jint port) {
    const char* utf = host == nullptr ? "127.0.0.1" : env->GetStringUTFChars(host, nullptr);
    std::string result = probe_connect(utf == nullptr ? "127.0.0.1" : utf, static_cast<int>(port));
    if (host != nullptr && utf != nullptr) env->ReleaseStringUTFChars(host, utf);
    return to_jstring(env, result);
}

static jstring jni_probe_seccomp(JNIEnv* env, jclass) {
    return to_jstring(env, probe_seccomp());
}

static jstring jni_probe_attack(JNIEnv* env, jclass, jstring core_path, jstring other_guest,
        jstring host_package, jint host_pid) {
    const char* core = core_path == nullptr ? "" : env->GetStringUTFChars(core_path, nullptr);
    const char* other = other_guest == nullptr ? "" : env->GetStringUTFChars(other_guest, nullptr);
    const char* pkg = host_package == nullptr ? "" : env->GetStringUTFChars(host_package, nullptr);
    std::string result = probe_attack(core == nullptr ? "" : core, other == nullptr ? "" : other,
            pkg == nullptr ? "" : pkg, static_cast<int>(host_pid));
    if (core_path != nullptr && core != nullptr) env->ReleaseStringUTFChars(core_path, core);
    if (other_guest != nullptr && other != nullptr) env->ReleaseStringUTFChars(other_guest, other);
    if (host_package != nullptr && pkg != nullptr) env->ReleaseStringUTFChars(host_package, pkg);
    return to_jstring(env, result);
}

static jstring jni_abi(JNIEnv* env, jclass) {
    return to_jstring(env, std::string(kCompiledAbi));
}

static const JNINativeMethod kMethods[] = {
        {"nativeProbeOpen", "(Ljava/lang/String;)Ljava/lang/String;",
                reinterpret_cast<void*>(jni_probe_open)},
        {"nativeProbeConnect", "(Ljava/lang/String;I)Ljava/lang/String;",
                reinterpret_cast<void*>(jni_probe_connect)},
        {"nativeProbeSeccomp", "()Ljava/lang/String;",
                reinterpret_cast<void*>(jni_probe_seccomp)},
        {"nativeCompiledAbi", "()Ljava/lang/String;",
                reinterpret_cast<void*>(jni_abi)},
};

static const JNINativeMethod kHostMethods[] = {
        {"nativeProbeAttack",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
                reinterpret_cast<void*>(jni_probe_attack)},
};

static void register_if_present(JNIEnv* env, const char* class_name, const JNINativeMethod* methods,
        jint count) {
    jclass cls = env->FindClass(class_name);
    if (cls == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->RegisterNatives(cls, methods, count);
    env->DeleteLocalRef(cls);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    register_if_present(env, "com/warden/controlledsandbox/NativeEnforcementNative", kMethods,
            static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    register_if_present(env, "com/warden/controlledsandbox/NativeEnforcementNative", kHostMethods,
            static_cast<jint>(sizeof(kHostMethods) / sizeof(kHostMethods[0])));
    register_if_present(env, "com/warden/controlledsandbox/fixture/NativeEnforcementNative",
            kMethods, static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    return JNI_VERSION_1_6;
}
