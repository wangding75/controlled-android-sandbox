#pragma once

#include <cstddef>
#include <cstdarg>
#include <dirent.h>
#include <string_view>
#include <sys/stat.h>
#include <sys/types.h>

namespace controlled_sandbox {

extern "C" pid_t controlled_getpid();
extern "C" pid_t controlled_getppid();
extern "C" pid_t controlled_gettid();
extern "C" uid_t controlled_getuid();
extern "C" uid_t controlled_geteuid();
extern "C" gid_t controlled_getgid();
extern "C" gid_t controlled_getegid();
extern "C" int controlled_prctl(int option, ...);
extern "C" long controlled_ptrace(long request, ...);
extern "C" pid_t controlled_fork();
extern "C" pid_t controlled_vfork();
extern "C" int controlled_clone(int (*function)(void*), void* stack, int flags, void* argument, ...);
extern "C" long controlled_clone3(void* arguments, std::size_t size);
extern "C" int controlled_execve(const char* filename, char* const argv[], char* const envp[]);
extern "C" int controlled_execveat(int directory, const char* filename,
                                   char* const argv[], char* const envp[], int flags);
extern "C" int controlled_seccomp(unsigned int operation, unsigned int flags, void* arguments);

/** Number of variadic arguments required by the supported prctl options. */
[[nodiscard]] int native_prctl_argument_count(int option) noexcept;

extern "C" char* controlled_getcwd(char* buffer, std::size_t size);
extern "C" int controlled_chdir(const char* path);
extern "C" int controlled_fchdir(int descriptor);
extern "C" char* controlled_realpath(const char* path, char* resolved);
extern "C" int controlled_chmod(const char* path, mode_t mode);
extern "C" int controlled_fchmod(int descriptor, mode_t mode);
extern "C" int controlled_fchmodat(int directory, const char* path, mode_t mode, int flags);
extern "C" int controlled_chown(const char* path, uid_t owner, gid_t group);
extern "C" int controlled_fchown(int descriptor, uid_t owner, gid_t group);
extern "C" int controlled_fchownat(int directory, const char* path, uid_t owner,
                                   gid_t group, int flags);
extern "C" int controlled_truncate(const char* path, off_t length);
extern "C" int controlled_ftruncate(int descriptor, off_t length);
extern "C" int controlled_fstat(int descriptor, struct stat* value);
extern "C" ssize_t controlled_getdents(int descriptor, void* buffer, std::size_t size);
extern "C" int controlled_closedir(DIR* directory);
extern "C" void* controlled_dlsym(void* handle, const char* name);

[[nodiscard]] void* native_process_replacement_for_symbol(std::string_view name) noexcept;

}  // namespace controlled_sandbox
