#include "controlled_sandbox/hostile_seccomp.h"

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <vector>

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
#define HOSTILE_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define HOSTILE_AUDIT_ARCH AUDIT_ARCH_AARCH64
#elif defined(__i386__)
#define HOSTILE_AUDIT_ARCH AUDIT_ARCH_I386
#elif defined(__arm__)
#define HOSTILE_AUDIT_ARCH AUDIT_ARCH_ARM
#else
#define HOSTILE_AUDIT_ARCH 0
#endif

namespace controlled_sandbox {
namespace {

constexpr uint32_t kErrnoEperm = SECCOMP_RET_ERRNO | (EPERM & 0xffff);

#if defined(SYS_socket)
static_assert(SYS_socket > 0, "SYS_socket");
#endif
#if defined(SYS_connect)
static_assert(SYS_connect > 0, "SYS_connect");
#endif
#if defined(SYS_bind)
static_assert(SYS_bind > 0, "SYS_bind");
#endif
#if defined(SYS_sendto)
static_assert(SYS_sendto > 0, "SYS_sendto");
#endif
#if defined(SYS_ptrace)
static_assert(SYS_ptrace > 0, "SYS_ptrace");
#endif
#if defined(SYS_execve)
static_assert(SYS_execve > 0, "SYS_execve");
#endif

// Classic BPF cannot call a C helper; emit an explicit compare chain.
void append_deny(std::vector<sock_filter>* filter, int number) {
    // if (nr == number) return ERRNO|EPERM
    filter->push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<uint32_t>(number), 0, 1));
    filter->push_back(BPF_STMT(BPF_RET | BPF_K, kErrnoEperm));
}

}  // namespace

std::vector<std::string> hostile_seccomp_deny_names() {
    std::vector<std::string> names;
#if defined(SYS_socket)
    names.emplace_back("socket");
#endif
#if defined(SYS_connect)
    names.emplace_back("connect");
#endif
#if defined(SYS_bind)
    names.emplace_back("bind");
#endif
#if defined(SYS_sendto)
    names.emplace_back("sendto");
#endif
#if defined(SYS_sendmsg)
    names.emplace_back("sendmsg");
#endif
#if defined(SYS_socketcall)
    names.emplace_back("socketcall");
#endif
    names.emplace_back("ptrace");
    names.emplace_back("execve");
#if defined(SYS_execveat)
    names.emplace_back("execveat");
#endif
    return names;
}

int install_hostile_seccomp(std::string* status) {
    if (HOSTILE_AUDIT_ARCH == 0) {
        if (status) *status = "UNSUPPORTED_ARCH";
        errno = ENOSYS;
        return -1;
    }
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        if (status) *status = std::string("NO_NEW_PRIVS_FAILED errno=") + std::to_string(errno);
        return -1;
    }
    std::vector<sock_filter> filter;
    filter.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)));
    filter.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, HOSTILE_AUDIT_ARCH, 1, 0));
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    filter.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)));
#if defined(SYS_socket)
    append_deny(&filter, SYS_socket);
#endif
#if defined(SYS_connect)
    append_deny(&filter, SYS_connect);
#endif
#if defined(SYS_bind)
    append_deny(&filter, SYS_bind);
#endif
#if defined(SYS_sendto)
    append_deny(&filter, SYS_sendto);
#endif
#if defined(SYS_sendmsg)
    append_deny(&filter, SYS_sendmsg);
#endif
#if defined(SYS_socketcall)
    append_deny(&filter, SYS_socketcall);
#endif
#if defined(SYS_ptrace)
    append_deny(&filter, SYS_ptrace);
#endif
#if defined(SYS_execve)
    append_deny(&filter, SYS_execve);
#endif
#if defined(SYS_execveat)
    append_deny(&filter, SYS_execveat);
#endif
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));

    sock_fprog prog{};
    prog.len = static_cast<unsigned short>(filter.size());
    prog.filter = filter.data();
#ifdef SYS_seccomp
    long rc = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog);
    if (rc == 0) {
        if (status) *status = "SECCOMP_FILTER_INSTALLED";
        return 0;
    }
#endif
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) == 0) {
        if (status) *status = "SECCOMP_FILTER_INSTALLED_PRCTL";
        return 0;
    }
    if (status) *status = std::string("SECCOMP_INSTALL_FAILED errno=") + std::to_string(errno);
    return -1;
}

}  // namespace controlled_sandbox
