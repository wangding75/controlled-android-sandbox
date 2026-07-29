#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/stat.h>
#if __has_include(<linux/openat2.h>)
#include <linux/openat2.h>
#else
struct open_how { std::uint64_t flags; std::uint64_t mode; std::uint64_t resolve; };
#endif
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" int openat2(int, const char*, const struct open_how*, std::size_t);
extern "C" int faccessat2(int, const char*, int, int);
extern "C" int renameat2(int, const char*, int, const char*, unsigned int);
extern "C" ssize_t getdents64(int, void*, std::size_t);

extern "C" int fixture_open_read(const char* path, char* output, int capacity) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -errno;
    const ssize_t count = read(fd, output, static_cast<size_t>(capacity));
    const int saved = errno;
    close(fd);
    if (count < 0) return -saved;
    return static_cast<int>(count);
}

extern "C" int fixture_openat_read(int directory, const char* path, char* output, int capacity) {
    int fd = openat(directory, path, O_RDONLY);
    if (fd < 0) return -errno;
    const ssize_t count = read(fd, output, static_cast<size_t>(capacity));
    const int saved = errno;
    close(fd);
    if (count < 0) return -saved;
    return static_cast<int>(count);
}

extern "C" int fixture_openat2_read(int directory, const char* path, char* output, int capacity) {
    struct open_how how{};
    how.flags = O_RDONLY | O_CLOEXEC;
    int fd = openat2(directory, path, &how, sizeof(how));
    if (fd < 0) return -errno;
    const ssize_t count = read(fd, output, static_cast<size_t>(capacity));
    const int saved = errno;
    close(fd);
    if (count < 0) return -saved;
    return static_cast<int>(count);
}

extern "C" int fixture_access(const char* path, int mode) {
    if (access(path, mode) != 0) return -errno;
    return 0;
}

extern "C" int fixture_stat(const char* path) {
    struct stat value{};
    if (stat(path, &value) != 0) return -errno;
    return S_ISREG(value.st_mode) ? 1 : 0;
}

extern "C" int fixture_statx(const char* path) {
    struct statx value{};
    if (statx(AT_FDCWD, path, 0, STATX_TYPE | STATX_SIZE, &value) != 0) return -errno;
    return (value.stx_mode & S_IFMT) == S_IFREG ? static_cast<int>(value.stx_size) : 0;
}

extern "C" int fixture_lstat(const char* path) {
    struct stat value{};
    if (lstat(path, &value) != 0) return -errno;
    return S_ISLNK(value.st_mode) ? 2 : (S_ISREG(value.st_mode) ? 1 : 0);
}

extern "C" int fixture_readlink(const char* path, char* output, int capacity) {
    const ssize_t count = readlink(path, output, static_cast<size_t>(capacity));
    if (count < 0) return -errno;
    return static_cast<int>(count);
}

extern "C" int fixture_create(const char* path) {
    int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC, 0640);
    if (fd < 0) return -errno;
    const char value[] = "created";
    const ssize_t count = write(fd, value, sizeof(value) - 1U);
    const int saved = errno;
    close(fd);
    if (count != static_cast<ssize_t>(sizeof(value) - 1U)) return -(saved == 0 ? EIO : saved);
    return 0;
}

extern "C" int fixture_faccessat(int directory, const char* path, int mode) {
    if (faccessat(directory, path, mode, 0) != 0) return -errno;
    return 0;
}

extern "C" int fixture_faccessat2(int directory, const char* path, int mode) {
    if (faccessat2(directory, path, mode, 0) != 0) return -errno;
    return 0;
}

extern "C" int fixture_fstatat(int directory, const char* path) {
    struct stat value{};
    if (fstatat(directory, path, &value, 0) != 0) return -errno;
    return S_ISREG(value.st_mode) ? 1 : 0;
}

extern "C" int fixture_readlinkat(int directory, const char* path, char* output, int capacity) {
    const ssize_t count = readlinkat(directory, path, output, static_cast<size_t>(capacity));
    if (count < 0) return -errno;
    return static_cast<int>(count);
}

extern "C" int fixture_renameat2(const char* old_path, const char* new_path) {
    if (renameat2(AT_FDCWD, old_path, AT_FDCWD, new_path, 0) != 0) return -errno;
    return 0;
}

extern "C" int fixture_getdents64(const char* path) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -errno;
    char buffer[2048]{};
    const ssize_t count = getdents64(fd, buffer, sizeof(buffer));
    const int saved = errno;
    close(fd);
    if (count < 0) return -saved;
    return static_cast<int>(count);
}

extern "C" int fixture_mmap_first_byte(const char* path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -errno;
    void* mapped = mmap(nullptr, 1, PROT_READ, MAP_PRIVATE, fd, 0);
    const int saved = errno;
    close(fd);
    if (mapped == MAP_FAILED) return -saved;
    const int value = *static_cast<const unsigned char*>(mapped);
    munmap(mapped, 1);
    return value;
}

extern "C" int fixture_dlopen_value(const char* name) {
    void* handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) return -errno;
    void* raw = dlsym(handle, "controlled_loader_child_value");
    if (raw == nullptr) { dlclose(handle); return -ENOENT; }
    auto function = reinterpret_cast<int (*)()>(raw);
    const int result = function();
    dlclose(handle);
    return result;
}
