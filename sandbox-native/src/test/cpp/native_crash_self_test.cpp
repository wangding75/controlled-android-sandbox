#include "controlled_sandbox/native_crash.h"

#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unistd.h>

int main() {
    auto& recorder = controlled_sandbox::global_crash_recorder();
    const std::string path = "/tmp/controlled-sandbox-native-crash-" + std::to_string(getpid()) + ".jsonl";
    if (!recorder.install(path)) throw std::runtime_error("install native crash recorder");
    auto status = recorder.status();
    if (!status.installed || status.output_path != path) throw std::runtime_error("native crash status");
    recorder.reset();
    if (recorder.status().installed) throw std::runtime_error("native crash reset");
    unlink(path.c_str());
    std::cout << "PASS sandbox-native crash recorder self-test\n";
    return EXIT_SUCCESS;
}
