#include "controlled_sandbox/native_boundary.h"
#include "controlled_sandbox/native_fd_ledger.h"
#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"
#include "controlled_sandbox/native_process_interceptors.h"
#include "controlled_sandbox/native_procfs.h"

#include <cassert>
#include <fcntl.h>
#include <iostream>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

using namespace controlled_sandbox;

namespace {

void configure_policy() {
    global_policy().configure(
            "native-convergence-session", 7, "com.example.native", "guest.worker",
            0, 10123, 23123, "x86_64", "/tmp/cas-native-convergence",
            "/tmp/cas-native-convergence/base.apk", "/tmp/cas-native-convergence/lib/x86_64",
            true, {}, {}, {}, {});
}

}  // namespace

int main() {
    global_policy().reset();
    configure_policy();
    const NativePolicySnapshot first = global_policy().snapshot();
    assert(first.configured);
    assert(first.revision != 0);

#ifdef SYS_openat
    assert(NativeSyscallPolicy::lookup(SYS_openat).action == NativeSyscallAction::Project);
#endif
#ifdef SYS_getpid
    assert(NativeSyscallPolicy::lookup(SYS_getpid).action == NativeSyscallAction::Project);
#endif
#ifdef SYS_execve
    assert(NativeSyscallPolicy::lookup(SYS_execve).action == NativeSyscallAction::Deny);
#endif
#ifdef SYS_ptrace
    assert(NativeSyscallPolicy::lookup(SYS_ptrace).action == NativeSyscallAction::Deny);
#endif
#ifdef SYS_clone
    assert(NativeSyscallPolicy::lookup(SYS_clone).action == NativeSyscallAction::Project);
#endif

    const NativePathDecision normalized = global_policy().resolve_path(
            "/data/user/0/com.example.native/files/../cache/./state");
    assert(normalized.path.find("..") == std::string::npos);
    assert(normalized.path.find("/tmp/cas-native-convergence/data/cache/state") == 0);

    global_policy().set_guest_cwd("/data/user/0/com.example.native/files");
    const NativeResolvedPath relative = NativeFileSystemResolver::resolve_at(
            AT_FDCWD, "../cache");
    assert(relative.virtual_path == "/data/user/0/com.example.native/cache");
    assert(relative.path.find("/tmp/cas-native-convergence/data/cache") == 0);

    assert(NativeProcFileSystem::is_virtual_path("/proc/self/status"));
    assert(NativeProcFileSystem::is_virtual_path("/proc/thread-self/task/" +
            std::to_string(first.virtual_pid) + "/status"));
    assert(NativeProcFileSystem::is_proc_fd_path(
            "/proc/" + std::to_string(first.virtual_pid) + "/fd/7"));
    assert(NativeProcFileSystem::is_proc_fdinfo_path("/proc/self/fdinfo/7"));
    assert(NativeProcFileSystem::is_proc_map_file_path("/proc/self/map_files/1-2"));
    assert(!NativeProcFileSystem::is_virtual_path("/proc/1/status"));

    NativeFdLedger::reset();
    NativeFdLedger::register_fd(5, NativeFdOwnership::VirtualizedPath, first.revision,
            "/data/user/0/com.example.native/files/state");
    NativeFdLedger::duplicate(5, 7);
    const auto duplicate = NativeFdLedger::lookup(7);
    assert(duplicate.has_value());
    assert(duplicate->ownership == NativeFdOwnership::VirtualizedPath);
    assert(duplicate->virtual_path == "/data/user/0/com.example.native/files/state");
    assert(NativeFdLedger::guest_visible(7));
    NativeFdLedger::register_fd(9, NativeFdOwnership::HostInternal, first.revision);
    assert(!NativeFdLedger::guest_visible(9));
    assert(NativeFdLedger::project_readlink(9, "/tmp/cas-native-convergence/private")
            == "[host-internal-fd]");
    NativeFdLedger::close(5);
    NativeFdLedger::close(7);
    NativeFdLedger::close(9);

    assert(NativeProcessIdentity::guest_pid() == first.virtual_pid);
    assert(NativeProcessIdentity::guest_ppid() == 1);
    assert(NativeProcessIdentity::guest_uid() == static_cast<uid_t>(first.virtual_uid));
    assert(NativeProcessIdentity::guest_gid() == static_cast<gid_t>(first.virtual_uid));
    assert(NativeProcessIdentity::sanitize_process_name("a\n(b)") == "a__b_");
    pid_t translated = 0;
    assert(NativeProcessIdentity::translate_target(first.virtual_pid, translated));
    assert(translated == NativeProcessIdentity::host_pid());
    assert(!NativeProcessIdentity::translate_target(first.virtual_pid + 1, translated));
    if (first.virtual_pid != NativeProcessIdentity::host_pid()) {
        assert(!NativeProcessIdentity::translate_target(NativeProcessIdentity::host_pid(),
                translated));
    }

#ifdef PR_SET_SECCOMP
    assert(NativeSeccompPolicy::guest_prctl_action(PR_SET_SECCOMP)
            == NativeGuestSeccompAction::Denied);
#endif
#ifdef PR_GET_SECCOMP
    assert(NativeSeccompPolicy::guest_prctl_action(PR_GET_SECCOMP)
            == NativeGuestSeccompAction::Mediated);
#endif
#ifdef PR_GET_NAME
    assert(native_prctl_argument_count(PR_GET_NAME) == 1);
#endif
#ifdef PR_GET_NO_NEW_PRIVS
    assert(native_prctl_argument_count(PR_GET_NO_NEW_PRIVS) == 0);
#endif
#ifdef PR_SET_SECCOMP
    assert(native_prctl_argument_count(PR_SET_SECCOMP) == 2);
#endif
    assert(NativeSeccompPolicy::is_install_operation(SECCOMP_SET_MODE_FILTER));
    assert(NativeSeccompPolicy::guest_seccomp_action(SECCOMP_SET_MODE_FILTER)
            == NativeGuestSeccompAction::Denied);

    configure_policy();
    assert(!global_policy().revision_current(first.revision));
    assert(global_policy().revision_current(global_policy().snapshot().revision));

    global_policy().reset();
    std::cout << "PASS native convergence policy substrate\n";
    return 0;
}
