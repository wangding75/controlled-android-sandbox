#pragma once

#include <cstdint>
#include <string>

namespace controlled_sandbox {

struct NativeCrashContext {
    std::string session_id;
    std::uint64_t generation{};
    std::string process_name;
    std::string abi_name;
};

struct NativeCrashStatus {
    bool installed{false};
    bool alternate_stack_installed{false};
    std::string output_path;
    std::string last_error;
    std::uint64_t generation{};
    std::uint64_t records_written{};
};

class NativeCrashRecorder final {
public:
    bool install(std::string output_path, NativeCrashContext context = {});
    void reset();
    [[nodiscard]] NativeCrashStatus status() const;
};

NativeCrashRecorder& global_crash_recorder();

}  // namespace controlled_sandbox
