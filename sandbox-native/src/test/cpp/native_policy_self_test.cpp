#include "controlled_sandbox/native_policy.h"

#include <cerrno>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

using controlled_sandbox::CidrV4;
using controlled_sandbox::NativePolicyEngine;
using controlled_sandbox::PathPolicyError;

void require(bool condition, const std::string& name) {
    if (!condition) throw std::runtime_error("Failed: " + name);
}

void configure(NativePolicyEngine& policy, std::uint64_t generation = 7) {
    auto allow = CidrV4::parse("10.0.0.0/8");
    auto deny = CidrV4::parse("10.8.0.0/16");
    require(allow.has_value() && deny.has_value(), "CIDR parse");
    policy.configure("session-a", generation, "com.example.guest", 3,
                     "/sandbox/users/3/apps/com.example.guest",
                     "/sandbox/packages/com.example.guest/base.apk",
                     "/sandbox/packages/com.example.guest/lib/arm64",
                     false, {"api.example.com", ".trusted.example"}, {"blocked.example"},
                     {*allow}, {*deny});
}

int main() {
    require(!CidrV4::parse("10.0.0.0/44").has_value(), "CIDR reject prefix");
    NativePolicyEngine policy;
    bool unconfigured = false;
    try { (void) policy.map_path("/data/data/com.example.guest/files/a.txt"); }
    catch (const PathPolicyError& error) { unconfigured = error.error_number() == EACCES; }
    require(unconfigured, "unconfigured fail closed");

    configure(policy);
    require(policy.configured(), "configured");
    auto snapshot = policy.snapshot();
    require(snapshot.session_id == "session-a" && snapshot.generation == 7, "identity snapshot");
    require(policy.map_path("/data/data/com.example.guest/files/a.txt") ==
            "/sandbox/users/3/apps/com.example.guest/data/files/a.txt", "data path");
    require(policy.map_path("/data/user/3/com.example.guest/databases/a.db") ==
            "/sandbox/users/3/apps/com.example.guest/data/databases/a.db", "user path");
    require(policy.map_path("/storage/emulated/3/Android/data/com.example.guest/cache/x") ==
            "/sandbox/users/3/apps/com.example.guest/external/cache/x", "external path");
    require(policy.map_path("/data/app/com.example.guest/base.apk") ==
            "/sandbox/packages/com.example.guest/base.apk", "apk alias");
    require(policy.map_path("/data/app/~~hash/com.example.guest-random/base.apk") ==
            "/sandbox/packages/com.example.guest/base.apk", "modern apk alias");
    require(policy.map_path("/data/app/com.example.guest/lib/arm64/libguest.so") ==
            "/sandbox/packages/com.example.guest/lib/arm64/libguest.so", "native library alias");
    require(policy.map_path("/data/data/com.example.guest/lib/libguest.so") ==
            "/sandbox/packages/com.example.guest/lib/arm64/libguest.so", "legacy native library alias");
    require(policy.map_path("/storage/emulated/3/DCIM/photo.jpg") ==
            "/storage/emulated/3/DCIM/photo.jpg", "shared external storage pass through");
    require(policy.map_path("/system/lib64/libc.so") == "/system/lib64/libc.so", "system pass through");
    bool other_apk = false;
    try { (void) policy.map_path("/data/app/~~hash/com.other-com.example.guest/base.apk"); }
    catch (const PathPolicyError& error) { other_apk = error.error_number() == EACCES; }
    require(other_apk, "cross package apk alias rejected");

    bool traversal = false;
    try { (void) policy.map_path("/data/data/com.example.guest/../../escape"); }
    catch (const PathPolicyError& error) { traversal = error.error_number() == EACCES; }
    require(traversal, "traversal rejected");
    bool cross_package = false;
    try { (void) policy.map_path("/data/user/3/com.other.app/files/secret"); }
    catch (const PathPolicyError& error) { cross_package = error.error_number() == EACCES; }
    require(cross_package, "cross package private path rejected");
    bool proc_mem = false;
    try { (void) policy.map_path("/proc/self/mem"); }
    catch (const PathPolicyError& error) { proc_mem = error.error_number() == EACCES; }
    require(proc_mem, "proc self mem rejected");

    require(policy.reverse_map_path("/sandbox/users/3/apps/com.example.guest/data/files/a.txt") ==
            "/data/user/3/com.example.guest/files/a.txt", "reverse data mapping");
    require(policy.reverse_map_path("/sandbox/packages/com.example.guest/base.apk") ==
            "/data/app/com.example.guest/base.apk", "reverse apk mapping");

    require(policy.allow_host("api.example.com"), "exact host allow");
    require(policy.allow_host("cdn.trusted.example"), "suffix host allow");
    require(!policy.allow_host("blocked.example"), "host deny");
    require(!policy.allow_host("unknown.example"), "default host deny");
    require(policy.allow_ipv4("10.1.2.3"), "CIDR allow");
    require(!policy.allow_ipv4("10.8.1.2"), "CIDR deny precedence");

    configure(policy, 8);
    require(policy.snapshot().generation == 8, "generation advanced");
    bool stale = false;
    try { configure(policy, 7); }
    catch (const std::logic_error&) { stale = true; }
    require(stale, "stale generation rejected");
    bool identity_change = false;
    try {
        policy.configure("session-a", 9, "com.changed.guest", 3,
                "/sandbox/users/3/apps/com.example.guest",
                "/sandbox/packages/com.example.guest/base.apk",
                "/sandbox/packages/com.example.guest/lib/arm64", true, {}, {}, {}, {});
    } catch (const std::logic_error&) { identity_change = true; }
    require(identity_change, "identity change within session rejected");
    bool session_collision = false;
    try {
        policy.configure("session-b", 9, "com.example.guest", 3,
                "/sandbox/users/3/apps/com.example.guest",
                "/sandbox/packages/com.example.guest/base.apk",
                "/sandbox/packages/com.example.guest/lib/arm64", true, {}, {}, {}, {});
    } catch (const std::logic_error&) { session_collision = true; }
    require(session_collision, "active session switch rejected");

    std::vector<std::thread> readers;
    for (int i = 0; i < 8; i++) {
        readers.emplace_back([&policy] {
            for (int round = 0; round < 1000; round++) {
                if (policy.map_path("/data/data/com.example.guest/cache/a") !=
                        "/sandbox/users/3/apps/com.example.guest/data/cache/a") {
                    throw std::runtime_error("concurrent mapping mismatch");
                }
            }
        });
    }
    for (auto& reader : readers) reader.join();

    policy.reset();
    require(!policy.configured(), "reset");
    require(!policy.allow_host("api.example.com"), "network fail closed after reset");

    std::cout << "PASS sandbox-native policy self-test\n";
    return EXIT_SUCCESS;
}
