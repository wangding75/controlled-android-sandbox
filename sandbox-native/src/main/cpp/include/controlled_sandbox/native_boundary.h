#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <sys/types.h>

namespace controlled_sandbox {

/**
 * The action taken by the libc/syscall compatibility boundary.  This is a
 * policy classification, not a claim that a raw instruction is intercepted.
 */
enum class NativeSyscallAction {
    PassThrough,
    Project,
    Deny,
};

struct NativeSyscallRule {
    NativeSyscallAction action{NativeSyscallAction::PassThrough};
    std::string_view name{};
};

class NativeSyscallPolicy final {
public:
    [[nodiscard]] static NativeSyscallRule lookup(long number) noexcept;
    [[nodiscard]] static bool is_projected(long number) noexcept;
    [[nodiscard]] static bool is_denied(long number) noexcept;
};

/**
 * Process-local identity projection shared by native wrappers and procfs
 * rendering.  The host_* methods are deliberately kept separate so native
 * implementation code never accidentally consumes a virtual PID as a real
 * kernel target.
 */
class NativeProcessIdentity final {
public:
    [[nodiscard]] static pid_t host_pid() noexcept;
    [[nodiscard]] static pid_t host_ppid() noexcept;
    [[nodiscard]] static pid_t host_tid() noexcept;
    [[nodiscard]] static uid_t host_uid() noexcept;
    [[nodiscard]] static uid_t host_euid() noexcept;
    [[nodiscard]] static gid_t host_gid() noexcept;
    [[nodiscard]] static gid_t host_egid() noexcept;

    [[nodiscard]] static pid_t guest_pid() noexcept;
    [[nodiscard]] static pid_t guest_ppid() noexcept;
    [[nodiscard]] static pid_t guest_tid() noexcept;
    [[nodiscard]] static uid_t guest_uid() noexcept;
    [[nodiscard]] static uid_t guest_euid() noexcept;
    [[nodiscard]] static gid_t guest_gid() noexcept;
    [[nodiscard]] static gid_t guest_egid() noexcept;

    /** Translate a Guest-visible process target to a kernel process target. */
    [[nodiscard]] static bool translate_target(pid_t guest_target,
                                               pid_t& host_target) noexcept;
    [[nodiscard]] static bool is_current_target(pid_t target) noexcept;
    [[nodiscard]] static std::string guest_process_name();
    [[nodiscard]] static std::string sanitize_process_name(std::string_view value);
};

enum class NativeGuestSeccompAction {
    Allowed,
    Mediated,
    Denied,
};

struct NativeSeccompSnapshot {
    int mode{0};
    int no_new_privs{0};
};

class NativeSeccompPolicy final {
public:
    [[nodiscard]] static NativeSeccompSnapshot snapshot() noexcept;
    [[nodiscard]] static NativeGuestSeccompAction guest_prctl_action(
            int option) noexcept;
    [[nodiscard]] static NativeGuestSeccompAction guest_seccomp_action(
            unsigned int operation) noexcept;
    [[nodiscard]] static bool is_install_operation(unsigned int operation) noexcept;
};

/** Internal syscall path used by native policy code and procfs snapshots. */
[[nodiscard]] long trusted_syscall6(long number, long a0 = 0, long a1 = 0,
                                    long a2 = 0, long a3 = 0, long a4 = 0,
                                    long a5 = 0) noexcept;

}  // namespace controlled_sandbox
