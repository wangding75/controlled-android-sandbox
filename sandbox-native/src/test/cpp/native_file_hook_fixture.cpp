#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

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

extern "C" int fixture_access(const char* path, int mode) {
    if (access(path, mode) != 0) return -errno;
    return 0;
}

extern "C" int fixture_stat(const char* path) {
    struct stat value{};
    if (stat(path, &value) != 0) return -errno;
    return S_ISREG(value.st_mode) ? 1 : 0;
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
