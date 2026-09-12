#include "controlled_sandbox/native_procfs.h"
#include "controlled_sandbox/native_policy.h"

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

using controlled_sandbox::NativeProcFileSystem;
using controlled_sandbox::NativePolicySnapshot;

namespace {

void require(bool condition, const std::string& label) {
    if (!condition) throw std::runtime_error("Failed: " + label);
}

std::string read_file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

}  // namespace

int main() {
    char pattern[] = "/tmp/controlled-sandbox-proc-XXXXXX";
    char* created = mkdtemp(pattern);
    require(created != nullptr, "mkdtemp");
    const std::filesystem::path root(created);
    const auto instance = root / "instance";
    const auto library = root / "lib" / "x86_64";
    const auto apk = root / "base.apk";
    std::filesystem::create_directories(instance / "data");
    std::filesystem::create_directories(library);
    std::ofstream(apk) << "apk";
    std::ofstream(library / "libguest.so") << "guest";

    auto& policy = controlled_sandbox::global_policy();
    policy.configure("proc-session", 4, "com.example.guest", "com.example.guest:worker",
            3, 103000, 20304, "x86_64", instance.string(), apk.string(), library.string(),
            true, {}, {}, {}, {});
    const NativePolicySnapshot snapshot = policy.snapshot();
    const std::string raw =
            "1000-2000 r-xp 00000000 00:00 0 " + (library / "libguest.so").string() + "\n"
            "2000-3000 r--p 00000000 00:00 0 " + apk.string() + "\n"
            "3000-4000 rw-p 00000000 00:00 0 /data/user/0/com.host.app/files/secret\n"
            "4000-5000 r-xp 00000000 00:00 0 /system/lib64/libc.so\n"
            "5000-6000 rw-p 00000000 00:00 0 [heap]\n";
    const std::string sanitized = NativeProcFileSystem::sanitize_maps(raw, snapshot);
    require(sanitized.find("/data/app/com.example.guest/lib/x86_64/libguest.so") != std::string::npos,
            "guest library reverse mapping");
    require(sanitized.find("/data/app/com.example.guest/base.apk") != std::string::npos,
            "guest apk reverse mapping");
    require(sanitized.find("com.host.app") == std::string::npos, "host private path removed");
    require(sanitized.find("[anon:sandbox-runtime]") != std::string::npos, "host mapping placeholder");
    require(sanitized.find("/system/lib64/libc.so") != std::string::npos, "system mapping preserved");
    require(sanitized.find("[heap]") != std::string::npos, "anonymous mapping preserved");

    const std::string cmdline = NativeProcFileSystem::render_cmdline(snapshot);
    require(cmdline == std::string("com.example.guest:worker\0", 25), "virtual cmdline");
    const std::string status = NativeProcFileSystem::render_status(snapshot);
    require(status.find("Name:\tcom.example.guest:worker") != std::string::npos, "virtual status name");
    require(status.find("Pid:\t" + std::to_string(getpid())) != std::string::npos,
            "status pid matches kernel getpid");
    require(status.find("Uid:\t103000\t103000\t103000\t103000") != std::string::npos,
            "virtual status uid");

    for (const std::string path : {"/proc/self/maps", "/proc/self/cmdline", "/proc/self/status",
                                   "/proc/self/mountinfo", "/proc/self/stat", "/proc/self/statm",
                                   "/proc/self/io"}) {
        auto decision = NativeProcFileSystem::materialize(path);
        require(decision.rewritten, "proc path rewritten");
        require(decision.confinement_root == instance.string(), "proc snapshot confinement");
        require(std::filesystem::is_regular_file(decision.path), "proc snapshot exists");
        struct stat value{};
        require(stat(decision.path.c_str(), &value) == 0, "proc snapshot stat");
        require((value.st_mode & 0777) == 0400, "proc snapshot permissions");
        require(!read_file(decision.path).empty(), "proc snapshot content");
    }
    require(NativeProcFileSystem::is_virtual_path(
                    "/proc/" + std::to_string(getpid()) + "/maps"),
            "host pid maps alias");
    require(NativeProcFileSystem::is_virtual_path(
                    "/proc/" + std::to_string(snapshot.virtual_pid) + "/status"),
            "virtual pid status alias");
    require(NativeProcFileSystem::is_virtual_path(
                    "/proc/self/task/" + std::to_string(getpid()) + "/status"),
            "task status alias");
    require(!NativeProcFileSystem::is_virtual_path("/proc/999999/maps"),
            "foreign proc pid denied");
    // NBB IOCore.proc() only remaps cmdline. Public kernel proc files stay on the real
    // node; CAS must not fail-close the files U4/Chromium opens at engine init.
    for (const char* public_proc : {"/proc/cpuinfo", "/proc/meminfo", "/proc/stat",
                                    "/proc/version", "/proc/uptime", "/proc/loadavg"}) {
        const auto decision = policy.resolve_path(public_proc);
        require(decision.path == public_proc, std::string(public_proc) + " stays the kernel path");
        require(!decision.rewritten, std::string(public_proc) + " is not a virtual snapshot");
    }
    bool foreign_proc_denied = false;
    try {
        policy.resolve_path("/proc/1/maps");
    } catch (const controlled_sandbox::PathPolicyError& error) {
        foreign_proc_denied = std::string(error.what()).find("PROC_PATH_DENIED") != std::string::npos;
    }
    require(foreign_proc_denied, "other-pid proc paths stay denied");

    policy.reset();
    const std::string user0_instance =
            "/data/user/0/com.host.app/files/instances/u0/com.example.guest";
    const std::string user0_apk =
            "/data/user/0/com.host.app/files/packages/com.example.guest/base.apk";
    const std::string user0_lib =
            "/data/user/0/com.host.app/files/packages/com.example.guest/lib/x86_64";
    policy.configure("proc-session", 5, "com.example.guest", "com.example.guest:worker",
            3, 103000, 20304, "x86_64", user0_instance, user0_apk, user0_lib,
            true, {}, {}, {}, {});
    const std::string alias_maps =
            "1000-2000 r-xp 00000000 00:00 0 /data/data/com.host.app/files/packages/"
            "com.example.guest/lib/x86_64/libwebviewuc.so\n"
            "2000-3000 r--p 00000000 00:00 0 /data/data/com.host.app/files/packages/"
            "com.example.guest/base.apk\n";
    const std::string alias_sanitized =
            NativeProcFileSystem::sanitize_maps(alias_maps, policy.snapshot());
    require(alias_sanitized.find("/data/app/com.example.guest/lib/x86_64/libwebviewuc.so")
                    != std::string::npos,
            "data/data alias of native library is reverse-mapped");
    require(alias_sanitized.find("/data/app/com.example.guest/base.apk") != std::string::npos,
            "data/data alias of apk is reverse-mapped");
    require(alias_sanitized.find("[anon:sandbox-runtime]") == std::string::npos,
            "guest payload must not be anonymized via data/data alias");
    require(alias_sanitized.find("com.host.app") == std::string::npos,
            "host path stripped after data/data alias reverse-map");

    policy.reset();
    std::filesystem::remove_all(root);
    std::cout << "PASS sandbox-native procfs virtualization self-test\n";
    return EXIT_SUCCESS;
}
