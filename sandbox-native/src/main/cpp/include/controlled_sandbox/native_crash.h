#pragma once

#include <string>

namespace controlled_sandbox {

struct NativeCrashStatus {
    bool installed{false};
    std::string output_path;
    std::string last_error;
};

class NativeCrashRecorder final {
public:
    bool install(std::string output_path);
    void reset();
    [[nodiscard]] NativeCrashStatus status() const;
};

NativeCrashRecorder& global_crash_recorder();

}  // namespace controlled_sandbox
