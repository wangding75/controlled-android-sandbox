#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"

#include <cerrno>
#include <cstdlib>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unistd.h>

using controlled_sandbox::NativeFileSystemResolver;
using controlled_sandbox::PathPolicyError;

void require_fs(bool condition, const std::string& name) {
    if (!condition) throw std::runtime_error("Failed: " + name);
}

int main() {
    char pattern[] = "/tmp/controlled-sandbox-fs-XXXXXX";
    char* created = mkdtemp(pattern);
    require_fs(created != nullptr, "mkdtemp");
    const std::filesystem::path root(created);
    const auto instance = root / "instance";
    const auto data = instance / "data";
    const auto lib = root / "lib";
    const auto apk = root / "base.apk";
    const auto outside = root / "outside";
    std::filesystem::create_directories(data / "files");
    std::filesystem::create_directories(lib);
    std::filesystem::create_directories(outside);
    std::ofstream(apk) << "apk";
    std::ofstream(data / "files" / "a.txt") << "a";
    std::ofstream(outside / "secret.txt") << "secret";

    auto& policy = controlled_sandbox::global_policy();
    policy.reset();
    policy.configure("session-fs", 1, "com.example.guest", "com.example.guest:main", 3, 103000, 20300, "x86_64",
            instance.string(), apk.string(), lib.string(), true, {}, {}, {}, {});

    auto absolute = NativeFileSystemResolver::resolve("/data/data/com.example.guest/files/a.txt");
    require_fs(absolute.path == (data / "files" / "a.txt").string(), "absolute resolve");
    NativeFileSystemResolver::validate_confinement(absolute, true);

    int directory = open((data / "files").c_str(), O_RDONLY | O_DIRECTORY);
    require_fs(directory >= 0, "open dirfd");
    auto relative = NativeFileSystemResolver::resolve_at(directory, "a.txt");
    require_fs(relative.path == (data / "files" / "a.txt").string(), "dirfd relative resolve");
    close(directory);

    bool bad_fd = false;
    try { (void) NativeFileSystemResolver::resolve_at(-99, "a.txt"); }
    catch (const PathPolicyError& error) { bad_fd = error.error_number() == EBADF; }
    require_fs(bad_fd, "bad dirfd errno");

    const auto escape_link = data / "escape";
    std::filesystem::create_directory_symlink(outside, escape_link);
    auto escaped = NativeFileSystemResolver::resolve("/data/data/com.example.guest/escape/secret.txt");
    bool symlink_escape = false;
    try { NativeFileSystemResolver::validate_confinement(escaped, true); }
    catch (const PathPolicyError& error) { symlink_escape = error.error_number() == EACCES; }
    require_fs(symlink_escape, "intermediate symlink escape rejected");

    const auto final_link = data / "files" / "link";
    std::filesystem::create_symlink(outside / "secret.txt", final_link);
    auto final_resolved = NativeFileSystemResolver::resolve("/data/data/com.example.guest/files/link");
    NativeFileSystemResolver::validate_confinement(final_resolved, false);
    bool followed_escape = false;
    try { NativeFileSystemResolver::validate_confinement(final_resolved, true); }
    catch (const PathPolicyError& error) { followed_escape = error.error_number() == EACCES; }
    require_fs(followed_escape, "final symlink follow rejected");

    require_fs(NativeFileSystemResolver::rewrite_readlink_result(
            (data / "files" / "a.txt").string()) ==
            "/data/user/3/com.example.guest/files/a.txt", "readlink redaction");
    require_fs(NativeFileSystemResolver::rewrite_readlink_result("socket:[123]") ==
            "socket:[123]", "non-path readlink preserved");

    bool empty = false;
    try { (void) NativeFileSystemResolver::resolve(""); }
    catch (const PathPolicyError& error) { empty = error.error_number() == ENOENT; }
    require_fs(empty, "empty path errno");
    bool null_path = false;
    try { (void) NativeFileSystemResolver::resolve(nullptr); }
    catch (const PathPolicyError& error) { null_path = error.error_number() == EFAULT; }
    require_fs(null_path, "null path errno");

    policy.reset();
    std::filesystem::remove_all(root);
    std::cout << "PASS sandbox-native file-system resolver self-test\n";
    return EXIT_SUCCESS;
}
