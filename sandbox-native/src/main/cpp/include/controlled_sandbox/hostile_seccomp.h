#pragma once

#include <string>
#include <vector>

namespace controlled_sandbox {

// Installs a more-restrictive classic BPF deny filter in the calling process.
// Intended only for ISOLATED_HOSTILE workers. It does not provide Guest
// syscall projection, user-notify, or raw-SVC coverage. Returns 0 on success
// and sets errno-style detail in the status string.
int install_hostile_seccomp(std::string* status);

// Names of denied syscalls. Numbers come from <sys/syscall.h> at compile time.
std::vector<std::string> hostile_seccomp_deny_names();

}  // namespace controlled_sandbox
