#include <jni.h>

#include <android/log.h>

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#if __has_include(<sys/xattr.h>)
#include <sys/xattr.h>
#define FIXTURE_HAS_XATTR 1
#else
#define FIXTURE_HAS_XATTR 0
#endif

#include <string>

#if defined(__ANDROID__) && __has_include(<android/dlext.h>)
#include <android/dlext.h>
#define FIXTURE_HAS_DLOPEN_EXT 1
#else
#define FIXTURE_HAS_DLOPEN_EXT 0
#endif

namespace {

constexpr const char* kTag = "CS_NATIVE_ADV";

#if defined(__x86_64__)
constexpr const char* kCompiledAbi = "x86_64";
#elif defined(__aarch64__)
constexpr const char* kCompiledAbi = "arm64-v8a";
#elif defined(__i386__)
constexpr const char* kCompiledAbi = "x86";
#elif defined(__arm__)
constexpr const char* kCompiledAbi = "armeabi-v7a";
#else
constexpr const char* kCompiledAbi = "unknown";
#endif

std::string g_files_dir;
std::string g_context = "DIRECT_FIXTURE";

std::string json_escape(const std::string& input) {
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
        } else if (ch == '\t') {
            out += "\\t";
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

std::string json_kv(const char* key, const std::string& value) {
    return std::string("\"") + key + "\":\"" + json_escape(value) + "\"";
}

std::string json_kv_raw(const char* key, const std::string& value) {
    return std::string("\"") + key + "\":" + value;
}

void write_all(int fd, const std::string& text) {
    const char* cursor = text.data();
    size_t remaining = text.size();
    while (remaining > 0) {
        ssize_t wrote = write(fd, cursor, remaining);
        if (wrote <= 0) break;
        cursor += static_cast<size_t>(wrote);
        remaining -= static_cast<size_t>(wrote);
    }
}

std::string case_json(const char* id, const char* status, const std::string& detail) {
    std::string out = "{";
    out += json_kv("id", id);
    out += ",";
    out += json_kv("status", status);
    out += ",";
    out += json_kv("abi", kCompiledAbi);
    out += ",";
    out += json_kv("context", g_context);
    out += ",";
    out += json_kv("detail", detail);
    out += "}";
    return out;
}

#if defined(__x86_64__)
long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
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
#define RAW_SYSCALL_AVAILABLE 1
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
#define RAW_SYSCALL_AVAILABLE 1
#elif defined(__i386__)
long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
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
#define RAW_SYSCALL_AVAILABLE 1
#elif defined(__arm__)
long raw_syscall6(long number, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long r0 asm("r0") = a0;
    register long r1 asm("r1") = a1;
    register long r2 asm("r2") = a2;
    register long r3 asm("r3") = a3;
    register long r4 asm("r4") = a4;
    register long r5 asm("r5") = a5;
    // Thumb reserves r7 as the frame pointer; move the syscall number through r12.
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
#define RAW_SYSCALL_AVAILABLE 1
#else
long raw_syscall6(long, long, long, long, long, long, long) { return -ENOSYS; }
#define RAW_SYSCALL_AVAILABLE 0
#endif

bool syscall_failed(long value) {
    return value < 0 && value >= -4095;
}

int from_syscall(long value) {
    if (syscall_failed(value)) {
        errno = static_cast<int>(-value);
        return -1;
    }
    return static_cast<int>(value);
}

std::string probe_path() {
    return g_files_dir.empty() ? "/data/local/tmp/cas-native-adv.txt"
                               : (g_files_dir + "/native-adv-probe.txt");
}

std::string read_fd_path(int fd) {
    char link[256];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    char target[512];
    ssize_t n = readlink(link, target, sizeof(target) - 1);
    if (n < 0) return std::string("readlink_errno=") + std::to_string(errno);
    target[n] = 0;
    return std::string(target);
}

void case_001(int out) {
    const std::string path = probe_path();
    int fd = open(path.c_str(), O_CREAT | O_RDWR | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        write_all(out, case_json("NATIVE-ADV-001", "ERROR",
                std::string("libc open failed errno=") + std::to_string(errno)));
        return;
    }
    const char payload[] = "NATIVE_ADV_001";
    (void) write(fd, payload, sizeof(payload) - 1);
    struct stat st {};
    int st_rc = fstat(fd, &st);
    close(fd);
    char linkbuf[512];
    ssize_t link_n = readlink(path.c_str(), linkbuf, sizeof(linkbuf) - 1);
    std::string detail = "open_path=" + path
            + ";fstat_rc=" + std::to_string(st_rc)
            + ";size=" + std::to_string(static_cast<long>(st.st_size))
            + ";readlink_rc=" + std::to_string(static_cast<long>(link_n))
            + ";readlink_errno=" + std::to_string(errno);
    write_all(out, case_json("NATIVE-ADV-001", "PASS_COMPAT", detail));
}

void case_002(int out) {
#ifndef SYS_openat
    write_all(out, case_json("NATIVE-ADV-002", "UNVERIFIED_RUNTIME", "SYS_openat unavailable"));
    return;
#else
    const std::string path = probe_path();
    int libc_fd = openat(AT_FDCWD, path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    int sys_fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path.c_str(),
            O_RDONLY | O_CLOEXEC, 0));
    std::string libc_target = libc_fd >= 0 ? read_fd_path(libc_fd) : "libc_fail";
    std::string sys_target = sys_fd >= 0 ? read_fd_path(sys_fd) : "syscall_fail";
    if (libc_fd >= 0) close(libc_fd);
    if (sys_fd >= 0) close(sys_fd);
    std::string detail = "libc_fd=" + std::to_string(libc_fd)
            + ";syscall_fd=" + std::to_string(sys_fd)
            + ";libc_target=" + libc_target
            + ";syscall_target=" + sys_target
            + ";context=" + g_context;
    const char* status = "PASS_COMPAT";
    if (g_context == "IN_SANDBOX" && sys_fd >= 0 && libc_fd >= 0
            && libc_target != sys_target) {
        status = "BYPASS_CONFIRMED";
    } else if (sys_fd < 0 && libc_fd < 0) {
        status = "BLOCKED_BY_POLICY";
    }
    write_all(out, case_json("NATIVE-ADV-002", status, detail));
#endif
}

void case_003(int out) {
#if !RAW_SYSCALL_AVAILABLE
    write_all(out, case_json("NATIVE-ADV-003", "UNVERIFIED_RUNTIME",
            "raw instruction not compiled for this ABI"));
    return;
#elif !defined(SYS_openat)
    write_all(out, case_json("NATIVE-ADV-003", "UNVERIFIED_RUNTIME", "SYS_openat unavailable"));
    return;
#else
    const std::string path = probe_path();
    int libc_fd = openat(AT_FDCWD, path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    long raw = raw_syscall6(SYS_openat, AT_FDCWD,
            reinterpret_cast<long>(path.c_str()), O_RDONLY | O_CLOEXEC, 0, 0, 0);
    int raw_fd = from_syscall(raw);
    std::string libc_target = libc_fd >= 0 ? read_fd_path(libc_fd) : "libc_fail";
    std::string raw_target = raw_fd >= 0 ? read_fd_path(raw_fd) : "raw_fail";
    if (libc_fd >= 0) close(libc_fd);
    if (raw_fd >= 0) close(raw_fd);
    std::string detail = "raw_available=1;raw_rc=" + std::to_string(raw)
            + ";raw_fd=" + std::to_string(raw_fd)
            + ";libc_target=" + libc_target
            + ";raw_target=" + raw_target;
    const char* status = "UNVERIFIED_RUNTIME";
    if (raw_fd >= 0) {
        if (g_context == "IN_SANDBOX" && libc_fd >= 0 && libc_target != raw_target) {
            status = "BYPASS_CONFIRMED";
        } else if (g_context == "IN_SANDBOX") {
            // Raw instruction executed. Same path as libc does not prove mediation;
            // it only proves the instruction ran. Treat as architecture gap.
            status = "BYPASS_CONFIRMED";
            detail += ";note=raw_instruction_executed_outside_plt";
        } else {
            status = "PASS_COMPAT";
            detail += ";note=direct_fixture_raw_instruction_works";
        }
    }
    write_all(out, case_json("NATIVE-ADV-003", status, detail));
#endif
}

void case_004(int out) {
    int libc_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
#ifdef SYS_socket
    int sys_fd = static_cast<int>(syscall(SYS_socket, AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0));
#else
    int sys_fd = -1;
#endif
#if RAW_SYSCALL_AVAILABLE && defined(SYS_socket)
    long raw = raw_syscall6(SYS_socket, AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0, 0, 0, 0);
    int raw_fd = from_syscall(raw);
#else
    int raw_fd = -1;
#endif
    sockaddr_in address {};
    address.sin_family = AF_INET;
    address.sin_port = htons(9);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int libc_conn = -1;
    int sys_conn = -1;
    int raw_conn = -1;
    if (libc_fd >= 0) {
        libc_conn = connect(libc_fd, reinterpret_cast<sockaddr*>(&address), sizeof(address));
    }
#ifdef SYS_connect
    if (sys_fd >= 0) {
        sys_conn = static_cast<int>(syscall(SYS_connect, sys_fd,
                reinterpret_cast<sockaddr*>(&address), sizeof(address)));
    }
#endif
#if RAW_SYSCALL_AVAILABLE && defined(SYS_connect)
    if (raw_fd >= 0) {
        raw_conn = from_syscall(raw_syscall6(SYS_connect, raw_fd,
                reinterpret_cast<long>(&address), sizeof(address), 0, 0, 0));
    }
#endif
    if (libc_fd >= 0) close(libc_fd);
    if (sys_fd >= 0) close(sys_fd);
    if (raw_fd >= 0) close(raw_fd);
    std::string detail = "target=127.0.0.1:9;libc_socket=" + std::to_string(libc_fd)
            + ";syscall_socket=" + std::to_string(sys_fd)
            + ";raw_socket=" + std::to_string(raw_fd)
            + ";libc_connect=" + std::to_string(libc_conn)
            + ";syscall_connect=" + std::to_string(sys_conn)
            + ";raw_connect=" + std::to_string(raw_conn);
    const char* status = "PASS_COMPAT";
    if (g_context == "IN_SANDBOX" && libc_conn != 0 && (sys_conn == 0 || raw_conn == 0)) {
        status = "BYPASS_CONFIRMED";
        detail += ";note=syscall_or_raw_connect_succeeded_after_libc_denied";
    }
    write_all(out, case_json("NATIVE-ADV-004", status, detail));
}

std::string slurp_path(const char* path, bool use_syscall) {
    int fd = -1;
    if (use_syscall) {
#ifdef SYS_openat
        fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0));
#endif
    } else {
        fd = open(path, O_RDONLY | O_CLOEXEC);
    }
    if (fd < 0) return std::string("OPEN_FAIL:") + std::to_string(errno);
    char buffer[512];
    ssize_t n = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (n < 0) return std::string("READ_FAIL:") + std::to_string(errno);
    buffer[n] = 0;
    std::string text(buffer, static_cast<size_t>(n));
    if (text.size() > 160) text.resize(160);
    return text;
}

void case_005(int out) {
    const char* paths[] = {
            "/proc/self/status",
            "/proc/self/maps",
            "/proc/self/fd",
            "/proc/self/task",
            "/proc/net",
    };
    std::string detail;
    for (const char* path : paths) {
        if (!detail.empty()) detail += "|";
        std::string libc = slurp_path(path, false);
        std::string sys = slurp_path(path, true);
        detail += std::string(path) + "{libc=" + libc.substr(0, 40)
                + ";sys=" + sys.substr(0, 40) + "}";
    }
    detail += ";getpid=" + std::to_string(static_cast<long>(getpid()));
    detail += ";getuid=" + std::to_string(static_cast<long>(getuid()));
    char process_name[16] = {};
    const int process_name_rc = prctl(PR_GET_NAME, process_name);
    detail += ";prctl_name_rc=" + std::to_string(process_name_rc)
            + ";prctl_name=" + std::string(process_name);
#ifdef PR_GET_DUMPABLE
    detail += ";prctl_dumpable=" + std::to_string(prctl(PR_GET_DUMPABLE));
#endif
#ifdef PR_GET_SECCOMP
    detail += ";prctl_seccomp=" + std::to_string(prctl(PR_GET_SECCOMP));
#endif
#ifdef PR_GET_NO_NEW_PRIVS
    detail += ";prctl_no_new_privs=" + std::to_string(prctl(PR_GET_NO_NEW_PRIVS));
#endif
    std::string libc_maps = slurp_path("/proc/self/maps", false);
    std::string sys_maps = slurp_path("/proc/self/maps", true);
    const char* status = "PASS_COMPAT";
    if (g_context == "IN_SANDBOX"
            && sys_maps.find("OPEN_FAIL") == std::string::npos
            && (libc_maps.find("OPEN_FAIL") != std::string::npos
                    || libc_maps != sys_maps)) {
        status = "BYPASS_CONFIRMED";
    }
    write_all(out, case_json("NATIVE-ADV-005", status, detail));
}

void case_006(int out) {
    pid_t child = fork();
    if (child == 0) {
        raise(SIGSTOP);
        _exit(0);
    }
    if (child < 0) {
        write_all(out, case_json("NATIVE-ADV-006", "BLOCKED_BY_POLICY",
                std::string("fork denied errno=") + std::to_string(errno)));
        return;
    }
    int status = 0;
    (void) waitpid(child, &status, WUNTRACED);
    long attach = ptrace(PTRACE_ATTACH, child, nullptr, nullptr);
    int attach_errno = errno;
    if (attach == 0) {
        int wait_status = 0;
        (void) waitpid(child, &wait_status, 0);
        (void) ptrace(PTRACE_DETACH, child, nullptr, nullptr);
    }
    kill(child, SIGKILL);
    (void) waitpid(child, nullptr, 0);
    long self = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
    std::string detail = "child=" + std::to_string(static_cast<long>(child))
            + ";attach=" + std::to_string(attach)
            + ";attach_errno=" + std::to_string(attach_errno)
            + ";traceme=" + std::to_string(self)
            + ";traceme_errno=" + std::to_string(errno);
    write_all(out, case_json("NATIVE-ADV-006", "PASS_COMPAT", detail));
}

void case_007(int out) {
    const char* candidates[] = {
            "/system/bin/true",
            "/system/bin/toybox",
            "/system/bin/id",
    };
    const char* chosen = nullptr;
    for (const char* candidate : candidates) {
        if (access(candidate, X_OK) == 0) {
            chosen = candidate;
            break;
        }
    }
    if (chosen == nullptr) {
        write_all(out, case_json("NATIVE-ADV-007", "BLOCKED_CASE_SAFETY",
                "no safe executable found"));
        return;
    }
    int fds[2];
    if (pipe(fds) != 0) {
        write_all(out, case_json("NATIVE-ADV-007", "ERROR", "pipe failed"));
        return;
    }
    pid_t child = fork();
    if (child < 0) {
        close(fds[0]);
        close(fds[1]);
        write_all(out, case_json("NATIVE-ADV-007", "BLOCKED_BY_POLICY",
                std::string("fork denied before execve errno=") + std::to_string(errno)));
        return;
    }
    if (child == 0) {
        close(fds[0]);
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        close(fds[1]);
        const char* argv0 = chosen;
        const char* argv1 = nullptr;
        if (strstr(chosen, "toybox") != nullptr) argv1 = "true";
        char* args[3];
        args[0] = const_cast<char*>(argv0);
        args[1] = const_cast<char*>(argv1);
        args[2] = nullptr;
        execve(chosen, args, environ);
        int err = errno;
        char msg[64];
        snprintf(msg, sizeof(msg), "execve_errno=%d\n", err);
        (void) write(STDOUT_FILENO, msg, strlen(msg));
        _exit(127);
    }
    close(fds[1]);
    char buffer[256];
    ssize_t n = read(fds[0], buffer, sizeof(buffer) - 1);
    close(fds[0]);
    if (n < 0) n = 0;
    buffer[n] = 0;
    int wait_status = 0;
    (void) waitpid(child, &wait_status, 0);
    std::string detail = std::string("target=") + chosen
            + ";exit=" + std::to_string(WIFEXITED(wait_status) ? WEXITSTATUS(wait_status) : -1)
            + ";signaled=" + std::to_string(WIFSIGNALED(wait_status) ? WTERMSIG(wait_status) : 0)
            + ";output=" + std::string(buffer, static_cast<size_t>(n));
    const char* status = "PASS_COMPAT";
    if (g_context == "IN_SANDBOX" && WIFEXITED(wait_status) && WEXITSTATUS(wait_status) != 127) {
        status = "BYPASS_CONFIRMED";
        detail += ";note=execve_replaced_fixture_child";
    }
    write_all(out, case_json("NATIVE-ADV-007", status, detail));
}

void case_008(int out) {
    void* self = dlopen("libcontrolled_sandbox_fixture.so", RTLD_NOW | RTLD_LOCAL);
    void* payload = dlopen("libfixture_adv_payload.so", RTLD_NOW | RTLD_LOCAL);
    const char* marker = nullptr;
    if (payload != nullptr) {
        using MarkerFn = const char* (*)();
        auto fn = reinterpret_cast<MarkerFn>(dlsym(payload, "fixture_adv_payload_marker"));
        if (fn != nullptr) marker = fn();
    }
    std::string custom_path = g_files_dir + "/libfixture_adv_payload.so";
    void* custom = dlopen(custom_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    int custom_errno = errno;
#if FIXTURE_HAS_DLOPEN_EXT
    android_dlextinfo info {};
    void* ext = android_dlopen_ext("libfixture_adv_payload.so", RTLD_NOW | RTLD_LOCAL, &info);
    int ext_errno = errno;
#else
    void* ext = nullptr;
    int ext_errno = ENOSYS;
#endif
    std::string detail = std::string("self=") + (self ? "ok" : "fail")
            + ";payload=" + (payload ? "ok" : "fail")
            + ";marker=" + (marker ? marker : "none")
            + ";custom_path=" + custom_path
            + ";custom=" + (custom ? "ok" : "fail")
            + ";custom_errno=" + std::to_string(custom_errno)
            + ";dlopen_ext=" + (ext ? "ok" : "fail")
            + ";dlopen_ext_errno=" + std::to_string(ext_errno);
    if (self != nullptr) dlclose(self);
    if (payload != nullptr) dlclose(payload);
    if (custom != nullptr) dlclose(custom);
    if (ext != nullptr && ext != payload) dlclose(ext);
    write_all(out, case_json("NATIVE-ADV-008", "PASS_COMPAT", detail));
}

void case_009(int out) {
    const std::string path = probe_path();
    int fd = open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    if (fd < 0) {
        write_all(out, case_json("NATIVE-ADV-009", "ERROR",
                std::string("open failed errno=") + std::to_string(errno)));
        return;
    }
    std::string via_proc = read_fd_path(fd);
    int duped = dup(fd);
    std::string via_dup = duped >= 0 ? read_fd_path(duped) : "dup_fail";
    int fds[2] = {-1, -1};
    std::string inherited = "pipe_fail";
    if (pipe(fds) == 0) {
        pid_t child = fork();
        if (child == 0) {
            close(fds[0]);
            std::string child_view = read_fd_path(fd);
            write_all(fds[1], child_view);
            _exit(0);
        }
        if (child < 0) {
            close(fds[0]);
            close(fds[1]);
            inherited = "fork_denied_by_policy";
        } else {
            close(fds[1]);
            char buffer[512];
            ssize_t n = read(fds[0], buffer, sizeof(buffer) - 1);
            close(fds[0]);
            if (n < 0) n = 0;
            buffer[n] = 0;
            inherited = std::string(buffer, static_cast<size_t>(n));
            (void) waitpid(child, nullptr, 0);
        }
    }
    if (duped >= 0) close(duped);
    close(fd);
    std::string detail = "opened=" + path
            + ";proc_fd=" + via_proc
            + ";dup=" + via_dup
            + ";inherited=" + inherited;
    const char* status = "PASS_COMPAT";
    if (g_context == "IN_SANDBOX" && via_proc.find(path) == std::string::npos
            && via_proc.find("readlink_errno") == std::string::npos) {
        status = "BYPASS_CONFIRMED";
        detail += ";note=proc_fd_revealed_non_guest_path";
    }
    write_all(out, case_json("NATIVE-ADV-009", status, detail));
}

void case_010(int out) {
    const char* nodes[] = {"/dev/binder", "/dev/vndbinder", "/dev/hwbinder"};
    std::string detail;
    bool any_open = false;
    for (const char* node : nodes) {
        int fd = open(node, O_RDONLY | O_CLOEXEC);
        int err = errno;
        if (!detail.empty()) detail += ";";
        detail += std::string(node) + "=" + (fd >= 0 ? "open" : "fail")
                + ",errno=" + std::to_string(fd >= 0 ? 0 : err);
        if (fd >= 0) {
            any_open = true;
            close(fd);
        }
    }
    detail += ";note=observation_only_no_transaction";
    write_all(out, case_json("NATIVE-ADV-010", any_open ? "PASS_COMPAT" : "BLOCKED_BY_POLICY",
            detail));
}

struct OpenHowCompat {
    uint64_t flags;
    uint64_t mode;
    uint64_t resolve;
};

bool is_expected_xattr_errno(int value) {
    if (value == ENOSYS || value == ENOTSUP || value == EPERM || value == EACCES
            || value == ENOTDIR || value == ENOENT) {
        return true;
    }
#ifdef ENODATA
    if (value == ENODATA) return true;
#endif
#ifdef ENOATTR
    if (value == ENOATTR) return true;
#endif
    return false;
}

bool xattr_call_expected(int rc, int error) {
    return rc == 0 || is_expected_xattr_errno(error);
}

std::string rc_with_errno(int rc, int value) {
    return std::to_string(rc) + ",errno=" + std::to_string(value);
}

void case_011(int out) {
    // This case is deliberately test-only. It records each native entry point
    // separately; it must not turn an unhooked entry point into a compatibility
    // or hostile-isolation claim.
    const std::string path = probe_path();
    const char* xattr_name = "user.cas.c3t01";
    const char xattr_value[] = "c3-t01";

    int open_fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    int open_errno = errno;
    int openat_fd = openat(AT_FDCWD, path.c_str(), O_RDONLY | O_CLOEXEC, 0);
    int openat_errno = errno;

    int openat2_fd = -1;
    int openat2_errno = ENOSYS;
#if defined(SYS_openat2)
    OpenHowCompat how {O_RDONLY | O_CLOEXEC, 0, 0};
    long openat2_result = syscall(SYS_openat2, AT_FDCWD, path.c_str(), &how, sizeof(how));
    openat2_fd = from_syscall(openat2_result);
    openat2_errno = errno;
#endif

    int access_rc = faccessat(AT_FDCWD, path.c_str(), R_OK, 0);
    int access_errno = errno;
    int faccessat2_rc = -1;
    int faccessat2_errno = ENOSYS;
#if defined(SYS_faccessat2)
    long faccessat2_result = syscall(SYS_faccessat2, AT_FDCWD, path.c_str(), R_OK, 0);
    faccessat2_rc = from_syscall(faccessat2_result);
    faccessat2_errno = errno;
#endif

    struct stat stat_info {};
    int stat_rc = stat(path.c_str(), &stat_info);
    int stat_errno = errno;
    int fstatat_rc = fstatat(AT_FDCWD, path.c_str(), &stat_info, 0);
    int fstatat_errno = errno;

    int xattr_set_rc = -1;
    int xattr_set_errno = ENOSYS;
    int xattr_get_rc = -1;
    int xattr_get_errno = ENOSYS;
    int xattr_list_rc = -1;
    int xattr_list_errno = ENOSYS;
    int xattr_remove_rc = -1;
    int xattr_remove_errno = ENOSYS;
#if FIXTURE_HAS_XATTR
    xattr_set_rc = setxattr(path.c_str(), xattr_name, xattr_value, sizeof(xattr_value) - 1, 0);
    xattr_set_errno = errno;
    char xattr_buffer[64] = {};
    xattr_get_rc = static_cast<int>(getxattr(path.c_str(), xattr_name,
            xattr_buffer, sizeof(xattr_buffer)));
    xattr_get_errno = errno;
    char xattr_list[128] = {};
    xattr_list_rc = static_cast<int>(listxattr(path.c_str(), xattr_list, sizeof(xattr_list)));
    xattr_list_errno = errno;
    xattr_remove_rc = removexattr(path.c_str(), xattr_name);
    xattr_remove_errno = errno;
#endif

    char saved_cwd[PATH_MAX] = {};
    char cwd_after[PATH_MAX] = {};
    char real_path[PATH_MAX] = {};
    const bool saved_cwd_ok = getcwd(saved_cwd, sizeof(saved_cwd)) != nullptr;
    const int chdir_rc = chdir(g_files_dir.c_str());
    const int chdir_errno = errno;
    const bool cwd_after_ok = getcwd(cwd_after, sizeof(cwd_after)) != nullptr;
    const int realpath_ok = realpath(path.c_str(), real_path) != nullptr ? 1 : 0;
    const int realpath_errno = errno;
    if (saved_cwd_ok) {
        (void) chdir(saved_cwd);
    }

    int pipe_fds[2] = {-1, -1};
    int ioctl_rc = -1;
    int ioctl_errno = ENOSYS;
    int syscall_ioctl_rc = -1;
    int syscall_ioctl_errno = ENOSYS;
    int raw_ioctl_rc = -1;
    int raw_ioctl_errno = ENOSYS;
    int available = 0;
    if (pipe(pipe_fds) == 0) {
        ioctl_rc = ioctl(pipe_fds[0], FIONREAD, &available);
        ioctl_errno = errno;
#if defined(SYS_ioctl)
        syscall_ioctl_rc = static_cast<int>(syscall(SYS_ioctl, pipe_fds[0], FIONREAD, &available));
        syscall_ioctl_errno = errno;
#if RAW_SYSCALL_AVAILABLE
        raw_ioctl_rc = from_syscall(raw_syscall6(SYS_ioctl, pipe_fds[0], FIONREAD,
                reinterpret_cast<long>(&available), 0, 0, 0));
        raw_ioctl_errno = errno;
#endif
#endif
        close(pipe_fds[0]);
        close(pipe_fds[1]);
    }

    const char* foreign_path =
            "/data/data/com.warden.controlledsandbox.debug/files/instances/u0";
    int foreign_fd = open(foreign_path, O_RDONLY | O_CLOEXEC);
    int foreign_errno = errno;
    if (foreign_fd >= 0) {
        close(foreign_fd);
    }
    if (open_fd >= 0) close(open_fd);
    if (openat_fd >= 0) close(openat_fd);
    if (openat2_fd >= 0) close(openat2_fd);

    const bool xattr_expected = xattr_call_expected(xattr_set_rc, xattr_set_errno)
            && xattr_call_expected(xattr_get_rc, xattr_get_errno)
            && xattr_call_expected(xattr_list_rc, xattr_list_errno)
            && xattr_call_expected(xattr_remove_rc, xattr_remove_errno);
    std::string detail = "path=" + path
            + ";open=" + rc_with_errno(open_fd, open_errno)
            + ";openat=" + rc_with_errno(openat_fd, openat_errno)
            + ";openat2=" + rc_with_errno(openat2_fd, openat2_errno)
            + ";faccessat=" + rc_with_errno(access_rc, access_errno)
            + ";faccessat2=" + rc_with_errno(faccessat2_rc, faccessat2_errno)
            + ";stat=" + rc_with_errno(stat_rc, stat_errno)
            + ";fstatat=" + rc_with_errno(fstatat_rc, fstatat_errno)
            + ";xattr_set=" + rc_with_errno(xattr_set_rc, xattr_set_errno)
            + ";xattr_get=" + rc_with_errno(xattr_get_rc, xattr_get_errno)
            + ";xattr_list=" + rc_with_errno(xattr_list_rc, xattr_list_errno)
            + ";xattr_remove=" + rc_with_errno(xattr_remove_rc, xattr_remove_errno)
            + ";xattr_class=" + (xattr_expected ? "EXPECTED_LIMITATION_OR_SUPPORTED" : "UNCLASSIFIED_ERROR")
            + ";cwd_saved=" + (saved_cwd_ok ? saved_cwd : "getcwd_fail")
            + ";chdir=" + rc_with_errno(chdir_rc, chdir_errno)
            + ";cwd_after=" + (cwd_after_ok ? cwd_after : "getcwd_fail")
            + ";realpath=" + std::to_string(realpath_ok)
            + ";realpath_value=" + (realpath_ok ? real_path : "")
            + ";realpath_errno=" + std::to_string(realpath_errno)
            + ";ioctl=" + rc_with_errno(ioctl_rc, ioctl_errno)
            + ";syscall_ioctl=" + rc_with_errno(syscall_ioctl_rc, syscall_ioctl_errno)
            + ";raw_ioctl=" + rc_with_errno(raw_ioctl_rc, raw_ioctl_errno)
            + ";foreign_open=" + rc_with_errno(foreign_fd, foreign_errno)
            + ";negative_host_path=" + (foreign_fd < 0 ? "DENIED" : "OPENED")
            + ";classification="
            + (g_context == "IN_SANDBOX" ? "UNCONTROLLED_ENTRYPOINTS_RECORDED" : "DIRECT_COMPAT_BASELINE")
            + ";raw_instruction_case=NATIVE-ADV-003";
    const char* status = g_context == "IN_SANDBOX" ? "BYPASS_CONFIRMED" : "PASS_COMPAT";
    if (foreign_fd >= 0 || !xattr_expected) {
        detail += ";negative_or_xattr_check=REVIEW_REQUIRED";
    } else {
        detail += ";negative_or_xattr_check=CLASSIFIED";
    }
    write_all(out, case_json("NATIVE-ADV-011", status, detail));
}

struct CaseSpec {
    const char* id;
    void (*fn)(int);
};

const CaseSpec kCases[] = {
        {"NATIVE-ADV-001", case_001},
        {"NATIVE-ADV-002", case_002},
        {"NATIVE-ADV-003", case_003},
        {"NATIVE-ADV-004", case_004},
        {"NATIVE-ADV-005", case_005},
        {"NATIVE-ADV-006", case_006},
        {"NATIVE-ADV-007", case_007},
        {"NATIVE-ADV-008", case_008},
        {"NATIVE-ADV-009", case_009},
        {"NATIVE-ADV-010", case_010},
        {"NATIVE-ADV-011", case_011},
};

std::string run_one(const CaseSpec& spec) {
    int fds[2];
    if (pipe(fds) != 0) {
        return case_json(spec.id, "ERROR", "pipe failed");
    }
    pid_t pid = fork();
    if (pid == 0) {
        close(fds[0]);
        spec.fn(fds[1]);
        close(fds[1]);
        _exit(0);
    }
    if (pid < 0) {
        // CAS deliberately denies unbrokered process creation.  Run the case in the
        // current Guest thread so filesystem/proc/FD assertions still execute, while
        // cases that require a child report BLOCKED_BY_POLICY themselves.
        spec.fn(fds[1]);
        close(fds[1]);
        std::string collected;
        char buffer[1024];
        ssize_t n;
        while ((n = read(fds[0], buffer, sizeof(buffer))) > 0) {
            collected.append(buffer, static_cast<size_t>(n));
        }
        close(fds[0]);
        return collected.empty()
                ? case_json(spec.id, "ERROR", "inline policy fallback produced no result")
                : collected;
    }
    close(fds[1]);
    std::string collected;
    char buffer[1024];
    for (int attempt = 0; attempt < 80; ++attempt) {
        pollfd pfd {};
        pfd.fd = fds[0];
        pfd.events = POLLIN;
        int ready = poll(&pfd, 1, 250);
        if (ready > 0) {
            ssize_t n = read(fds[0], buffer, sizeof(buffer));
            if (n > 0) collected.append(buffer, static_cast<size_t>(n));
            if (n == 0) break;
        }
        int status = 0;
        pid_t waited = waitpid(pid, &status, WNOHANG);
        if (waited == pid) {
            ssize_t n;
            while ((n = read(fds[0], buffer, sizeof(buffer))) > 0) {
                collected.append(buffer, static_cast<size_t>(n));
            }
            if (collected.empty()) {
                collected = case_json(spec.id, "ERROR",
                        std::string("child_exit=")
                                + std::to_string(WIFEXITED(status) ? WEXITSTATUS(status) : -1)
                                + ";signal="
                                + std::to_string(WIFSIGNALED(status) ? WTERMSIG(status) : 0));
            }
            close(fds[0]);
            return collected;
        }
    }
    close(fds[0]);
    kill(pid, SIGKILL);
    (void) waitpid(pid, nullptr, 0);
    if (collected.empty()) return case_json(spec.id, "ERROR", "timeout");
    return collected;
}

std::string run_campaign() {
    std::string out = "{";
    out += json_kv("schema", "t57-r03-p0a-01-native-adv-c3-t01");
    out += ",";
    out += json_kv("abi", kCompiledAbi);
    out += ",";
    out += json_kv("context", g_context);
    out += ",";
    out += json_kv_raw("pid", std::to_string(static_cast<long>(getpid())));
    out += ",";
    out += json_kv_raw("uid", std::to_string(static_cast<long>(getuid())));
    out += ",\"cases\":[";
    bool first = true;
    for (const CaseSpec& spec : kCases) {
        if (!first) out += ",";
        first = false;
        out += run_one(spec);
        __android_log_print(ANDROID_LOG_INFO, kTag, "CASE %s done", spec.id);
    }
    out += "]}";
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_NativeAdversarialProbe_nativeRunCampaign(
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
    std::string result = run_campaign();
    __android_log_print(ANDROID_LOG_INFO, kTag, "RESULT_BEGIN");
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", result.c_str());
    __android_log_print(ANDROID_LOG_INFO, kTag, "RESULT_END");
    return env->NewStringUTF(result.c_str());
}
