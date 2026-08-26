#include "controlled_sandbox/native_hook.h"
#include "controlled_sandbox/native_policy.h"

#include <cerrno>
#include <cstdlib>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unistd.h>

using controlled_sandbox::NativeHookRuntime;

void require_hook(bool condition, const std::string& label) {
    if (!condition) throw std::runtime_error("Failed: " + label);
}

template <typename Function>
Function load_symbol(void* handle, const char* name) {
    void* value = dlsym(handle, name);
    if (value == nullptr) throw std::runtime_error(std::string("Missing fixture symbol: ") + name);
    return reinterpret_cast<Function>(value);
}

int main(int argc, char** argv) {
    require_hook(argc == 2, "fixture path argument");
    const std::filesystem::path fixture_path = std::filesystem::absolute(argv[1]);
    const std::filesystem::path fixture_root = fixture_path.parent_path();

    require_hook(NativeHookRuntime::is_target_symbol("open"), "open target");
    require_hook(NativeHookRuntime::is_target_symbol("access"), "access target");
    require_hook(NativeHookRuntime::is_target_symbol("stat"), "stat target");
    require_hook(NativeHookRuntime::is_target_symbol("lstat"), "lstat target");
    require_hook(NativeHookRuntime::is_target_symbol("readlink"), "readlink target");
    require_hook(NativeHookRuntime::is_target_symbol("fstatat"), "fstatat target");
    require_hook(NativeHookRuntime::is_target_symbol("openat2"), "openat2 target");
    require_hook(NativeHookRuntime::is_target_symbol("statx"), "statx target");
    require_hook(NativeHookRuntime::is_target_symbol("renameat2"), "renameat2 target");
    require_hook(NativeHookRuntime::is_target_symbol("faccessat2"), "faccessat2 target");
    require_hook(NativeHookRuntime::is_target_symbol("getdents64"), "getdents64 target");
    require_hook(NativeHookRuntime::is_target_symbol("mmap"), "mmap target");
    require_hook(NativeHookRuntime::is_target_symbol("socket"), "socket target");
    require_hook(NativeHookRuntime::is_target_symbol("close"), "socket close target");
    require_hook(NativeHookRuntime::is_target_symbol("bind"), "bind target");
    require_hook(NativeHookRuntime::is_target_symbol("sendto"), "sendto target");
    require_hook(NativeHookRuntime::is_target_symbol("recvfrom"), "recvfrom target");
    require_hook(NativeHookRuntime::is_target_symbol("send"), "send target");
    require_hook(NativeHookRuntime::is_target_symbol("sendmsg"), "sendmsg target");
    require_hook(NativeHookRuntime::is_target_symbol("recv"), "recv target");
    require_hook(NativeHookRuntime::is_target_symbol("recvmsg"), "recvmsg target");
    require_hook(NativeHookRuntime::is_target_symbol("read"), "read target");
    require_hook(NativeHookRuntime::is_target_symbol("write"), "write target");
    require_hook(NativeHookRuntime::is_target_symbol("accept"), "accept target");
    require_hook(NativeHookRuntime::is_target_symbol("accept4"), "accept4 target");
    require_hook(NativeHookRuntime::is_target_symbol("dup"), "dup target");
    require_hook(NativeHookRuntime::is_target_symbol("dup2"), "dup2 target");
    require_hook(NativeHookRuntime::is_target_symbol("dup3"), "dup3 target");
    require_hook(NativeHookRuntime::is_target_symbol("fcntl"), "fcntl target");
    require_hook(NativeHookRuntime::is_target_symbol("fcntl64"), "fcntl64 target");
    require_hook(NativeHookRuntime::is_target_symbol("setsockopt"), "socket option target");
    require_hook(NativeHookRuntime::is_target_symbol("if_nametoindex"), "interface index target");
    require_hook(NativeHookRuntime::is_target_symbol("getaddrinfo"), "DNS target");
    require_hook(NativeHookRuntime::is_target_symbol("getnameinfo"), "reverse DNS target");
    require_hook(NativeHookRuntime::is_target_symbol("getifaddrs"), "interface projection target");
    require_hook(NativeHookRuntime::is_target_symbol("gethostname"), "hostname target");
    require_hook(NativeHookRuntime::is_target_symbol("AAudioStream_requestStart"), "AAudio capture target");
    require_hook(NativeHookRuntime::is_target_symbol("AMediaRecorder_start"), "MediaRecorder capture target");
    require_hook(NativeHookRuntime::is_target_symbol("dlopen"), "dynamic loader target");
    require_hook(NativeHookRuntime::is_target_symbol("android_dlopen_ext"), "Android loader target");
    require_hook(NativeHookRuntime::is_target_symbol("kill"), "process lifetime kill target");
    require_hook(NativeHookRuntime::is_target_symbol("_exit"), "process lifetime exit target");
    require_hook(NativeHookRuntime::is_target_symbol("abort"), "process lifetime abort target");
    require_hook(NativeHookRuntime::is_target_symbol("getpid"), "process identity getpid target");
    require_hook(NativeHookRuntime::is_target_symbol("getuid"), "process identity getuid target");
    require_hook(NativeHookRuntime::is_target_symbol("fork"), "process creation fork target");
    require_hook(NativeHookRuntime::is_process_lifetime_symbol("tgkill"), "lifetime tgkill");
    require_hook(NativeHookRuntime::is_process_lifetime_system_module(
            "/apex/com.android.runtime/lib64/libandroid_runtime.so"),
            "android_runtime lifetime module");
    require_hook(!NativeHookRuntime::is_process_lifetime_system_module("/system/lib64/libc.so"),
            "libc is not a lifetime system module");
    require_hook(NativeHookRuntime::is_guest_module(fixture_path.string(), fixture_root.string()),
            "guest module");
    const std::string legacy_data_root = "/data/user/0" + fixture_root.string();
    const std::string legacy_data_module = legacy_data_root + "/libfixture.so";
    require_hook(NativeHookRuntime::is_guest_module(legacy_data_module, "/data/data" + fixture_root.string()),
            "Android data directory alias module");
    require_hook(!NativeHookRuntime::is_guest_module("/system/lib64/libc.so", fixture_root.string()),
            "system module excluded");

    void* fixture = dlopen(fixture_path.c_str(), RTLD_LAZY | RTLD_LOCAL);
    require_hook(fixture != nullptr, std::string("load fixture: ") + (dlerror() == nullptr ? "" : dlerror()));

    char pattern[] = "/tmp/controlled-sandbox-hook-XXXXXX";
    char* created = mkdtemp(pattern);
    require_hook(created != nullptr, "mkdtemp");
    const std::filesystem::path root(created);
    const auto instance = root / "instance";
    const auto data = instance / "data";
    const auto outside = root / "outside";
    const auto apk = root / "base.apk";
    std::filesystem::create_directories(data / "files");
    std::filesystem::create_directories(outside);
    std::ofstream(data / "files" / "hello.txt") << "hello";
    std::ofstream(outside / "secret.txt") << "secret";
    std::ofstream(apk) << "apk";
    std::filesystem::create_symlink(data / "files" / "hello.txt", data / "files" / "link");
    std::filesystem::create_directory_symlink(outside, data / "escape");

    NativeHookRuntime runtime;
    require_hook(!runtime.install(fixture_root.string()), "install rejected without policy");
    controlled_sandbox::global_policy().configure("hook-session", 1, "com.example.guest", "com.example.guest:main", 0, 10000, 20000, "x86_64",
            instance.string(), apk.string(), fixture_root.string(), true, {}, {}, {}, {});
    NativeHookRuntime lifetime_runtime;
    require_hook(lifetime_runtime.install_process_lifetime(), "host lifetime install");
    require_hook(lifetime_runtime.status().process_lifetime_installed,
            "host lifetime installed state");
    require_hook(lifetime_runtime.status().process_lifetime_refresh_count >= 1,
            "host lifetime initial refresh");
    lifetime_runtime.reset();
    require_hook(runtime.install(fixture_root.string()), "install");
    auto status = runtime.status();
    require_hook(status.installed, "installed state");
    require_hook(status.refresh_count >= 1, "initial refresh");
    require_hook(status.modules_scanned >= 1, "module scan");
    require_hook(status.modules_matched >= 1, "fixture matched");
    require_hook(status.target_relocations >= 12, "extended targets discovered");
    require_hook(status.relocations_patched >= 12, "extended targets patched");
    require_hook(status.policy_revision > 0, "policy revision captured");
    require_hook(status.patch_failures == 0, "no patch failures");

    using ReadFn = int (*)(const char*, char*, int);
    using ReadAtFn = int (*)(int, const char*, char*, int);
    using UnaryFn = int (*)(const char*, int);
    using PathFn = int (*)(const char*);
    auto fixture_open_read = load_symbol<ReadFn>(fixture, "fixture_open_read");
    auto fixture_openat_read = load_symbol<ReadAtFn>(fixture, "fixture_openat_read");
    auto fixture_openat2_read = load_symbol<ReadAtFn>(fixture, "fixture_openat2_read");
    auto fixture_access = load_symbol<UnaryFn>(fixture, "fixture_access");
    auto fixture_stat = load_symbol<PathFn>(fixture, "fixture_stat");
    auto fixture_statx = load_symbol<PathFn>(fixture, "fixture_statx");
    auto fixture_lstat = load_symbol<PathFn>(fixture, "fixture_lstat");
    auto fixture_readlink = load_symbol<ReadFn>(fixture, "fixture_readlink");
    auto fixture_create = load_symbol<PathFn>(fixture, "fixture_create");
    auto fixture_faccessat = load_symbol<int (*)(int, const char*, int)>(fixture, "fixture_faccessat");
    auto fixture_faccessat2 = load_symbol<int (*)(int, const char*, int)>(fixture, "fixture_faccessat2");
    auto fixture_fstatat = load_symbol<int (*)(int, const char*)>(fixture, "fixture_fstatat");
    auto fixture_readlinkat = load_symbol<ReadAtFn>(fixture, "fixture_readlinkat");
    auto fixture_renameat2 = load_symbol<int (*)(const char*, const char*)>(fixture, "fixture_renameat2");
    auto fixture_getdents64 = load_symbol<PathFn>(fixture, "fixture_getdents64");
    auto fixture_mmap_first_byte = load_symbol<PathFn>(fixture, "fixture_mmap_first_byte");
    auto fixture_dlopen_value = load_symbol<PathFn>(fixture, "fixture_dlopen_value");

    char buffer[256]{};
    int count = fixture_open_read("/data/data/com.example.guest/files/hello.txt", buffer, sizeof(buffer));
    require_hook(count == 5 && std::string(buffer, 5) == "hello", "hooked open rewrite");
    require_hook(fixture_access("/data/user/0/com.example.guest/files/hello.txt", R_OK) == 0,
            "hooked access rewrite");
    require_hook(fixture_stat("/data/data/com.example.guest/files/hello.txt") == 1,
            "hooked stat rewrite");
    require_hook(fixture_statx("/data/data/com.example.guest/files/hello.txt") == 5,
            "hooked statx rewrite");
    require_hook(fixture_mmap_first_byte("/data/data/com.example.guest/files/hello.txt") == 'h',
            "hooked mmap descriptor confinement");
    require_hook(fixture_access("/data/data/com.example.guest/files/missing.txt", F_OK) == -ENOENT,
            "underlying access errno preserved");
    require_hook(fixture_create("/data/data/com.example.guest/files/created.txt") == 0,
            "hooked open create mode");
    require_hook(std::filesystem::is_regular_file(data / "files" / "created.txt"),
            "created file mapped into instance");
    require_hook(fixture_lstat("/data/data/com.example.guest/files/link") == 2,
            "hooked lstat no-follow");

    std::fill(std::begin(buffer), std::end(buffer), '\0');
    count = fixture_readlink("/data/data/com.example.guest/files/link", buffer, sizeof(buffer));
    require_hook(count > 0 && std::string(buffer, static_cast<std::size_t>(count)) ==
            "/data/user/0/com.example.guest/files/hello.txt", "hooked readlink redaction");

    int directory = open((data / "files").c_str(), O_RDONLY | O_DIRECTORY);
    require_hook(directory >= 0, "open fixture dirfd");
    std::fill(std::begin(buffer), std::end(buffer), '\0');
    count = fixture_openat_read(directory, "hello.txt", buffer, sizeof(buffer));
    require_hook(count == 5 && std::string(buffer, 5) == "hello", "hooked openat relative rewrite");
    std::fill(std::begin(buffer), std::end(buffer), '\0');
    count = fixture_openat2_read(directory, "hello.txt", buffer, sizeof(buffer));
    require_hook(count == 5 && std::string(buffer, 5) == "hello", "hooked openat2 relative rewrite");
    require_hook(fixture_faccessat(directory, "hello.txt", R_OK) == 0,
            "hooked faccessat relative rewrite");
    require_hook(fixture_faccessat2(directory, "hello.txt", R_OK) == 0,
            "hooked faccessat2 relative rewrite");
    require_hook(fixture_fstatat(directory, "hello.txt") == 1,
            "hooked fstatat relative rewrite");
    std::fill(std::begin(buffer), std::end(buffer), '\0');
    count = fixture_readlinkat(directory, "link", buffer, sizeof(buffer));
    require_hook(count > 0 && std::string(buffer, static_cast<std::size_t>(count)) ==
            "/data/user/0/com.example.guest/files/hello.txt", "hooked readlinkat redaction");
    close(directory);

    require_hook(fixture_getdents64("/data/data/com.example.guest/files") > 0,
            "hooked getdents64 directory confinement");
    require_hook(fixture_renameat2("/data/data/com.example.guest/files/created.txt",
            "/data/data/com.example.guest/files/renamed.txt") == 0, "hooked renameat2 rewrite");
    require_hook(std::filesystem::is_regular_file(data / "files" / "renamed.txt"),
            "renameat2 stays inside instance");
    require_hook(fixture_renameat2("/data/data/com.example.guest/files/renamed.txt",
            (outside / "renamed.txt").c_str()) == -EXDEV, "cross confinement rename denied");

    std::vector<char> proc_buffer(65536);
    count = fixture_open_read("/proc/self/cmdline", proc_buffer.data(), static_cast<int>(proc_buffer.size()));
    require_hook(count > 0 && std::string(proc_buffer.data(), static_cast<std::size_t>(count)).find(
            "com.example.guest:main") != std::string::npos, "virtual proc cmdline");
    count = fixture_open_read("/proc/self/status", proc_buffer.data(), static_cast<int>(proc_buffer.size()));
    std::string proc_status(proc_buffer.data(), static_cast<std::size_t>(count > 0 ? count : 0));
    require_hook(count > 0 && proc_status.find("Pid:\t20000") != std::string::npos,
            "virtual proc status pid");
    require_hook(proc_status.find("Uid:\t10000") != std::string::npos, "virtual proc status uid");
    count = fixture_open_read("/proc/self/maps", proc_buffer.data(), static_cast<int>(proc_buffer.size()));
    std::string proc_maps(proc_buffer.data(), static_cast<std::size_t>(count > 0 ? count : 0));
    require_hook(count > 0 && proc_maps.find(root.string()) == std::string::npos,
            "virtual proc maps hides host paths");
    require_hook(fixture_dlopen_value("libnative_loader_child.so") == 17,
            "controlled guest soname load");
    require_hook(fixture_dlopen_value("libunknown-controlled.so") == -ENOENT,
            "unknown loader soname denied");

    require_hook(fixture_open_read("/data/user/0/com.other.app/files/secret", buffer, sizeof(buffer)) == -EACCES,
            "cross package denied with errno");
    require_hook(fixture_open_read("/data/data/com.example.guest/escape/secret.txt", buffer, sizeof(buffer)) == -EACCES,
            "symlink escape denied");
    require_hook(fixture_open_read("/proc/self/mem", buffer, sizeof(buffer)) == -EACCES,
            "proc mem denied");

    controlled_sandbox::global_policy().configure("hook-session", 2, "com.example.guest", "com.example.guest:main", 0, 10000, 20000, "x86_64",
            instance.string(), apk.string(), fixture_root.string(), true, {}, {}, {}, {});
    require_hook(!runtime.refresh(), "policy generation change requires reinstall");
    require_hook(runtime.status().last_error == "POLICY_REVISION_CHANGED_REINSTALL_REQUIRED",
            "revision mismatch diagnostic");
    require_hook(runtime.install(fixture_root.string()), "reinstall after generation change");
    require_hook(runtime.status().policy_revision == controlled_sandbox::global_policy().snapshot().revision,
            "reinstall captures current revision");

    runtime.reset();
    controlled_sandbox::global_policy().reset();
    require_hook(!runtime.status().installed, "reset");
    dlclose(fixture);
    std::filesystem::remove_all(root);
    std::cout << "PASS sandbox-native PLT file hook self-test\n";
    return EXIT_SUCCESS;
}
