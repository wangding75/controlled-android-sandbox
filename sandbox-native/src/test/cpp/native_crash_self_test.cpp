#include "controlled_sandbox/native_crash.h"

#include <csignal>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

int main() {
    struct rlimit core_limit{};
    core_limit.rlim_cur = 0;
    core_limit.rlim_max = 0;
    (void) setrlimit(RLIMIT_CORE, &core_limit);

    auto& recorder = controlled_sandbox::global_crash_recorder();
    const std::string path = "/tmp/controlled-sandbox-native-crash-" + std::to_string(getpid()) + ".jsonl";
    unlink(path.c_str());
    unlink((path + ".1").c_str());
    controlled_sandbox::NativeCrashContext context{
            "crash-session", 42, "com.example.guest:remote", "x86_64"};
    if (!recorder.install(path, context)) throw std::runtime_error("install native crash recorder");
    auto status = recorder.status();
    if (!status.installed || !status.alternate_stack_installed || status.output_path != path
            || status.generation != 42) {
        throw std::runtime_error("native crash status");
    }

    const pid_t child = fork();
    if (child < 0) throw std::runtime_error("fork crash fixture");
    if (child == 0) {
        raise(SIGSEGV);
        _exit(99);
    }
    int wait_status = 0;
    if (waitpid(child, &wait_status, 0) != child) throw std::runtime_error("wait crash fixture");
    if (!WIFSIGNALED(wait_status) || WTERMSIG(wait_status) != SIGSEGV) {
        throw std::runtime_error("crash fixture did not terminate with SIGSEGV");
    }

    std::ifstream input(path);
    const std::string body((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    if (body.find("\"event\":\"NATIVE_FATAL_SIGNAL\"") == std::string::npos
            || body.find("\"signal\":11") == std::string::npos
            || body.find("\"generation\":42") == std::string::npos
            || body.find("\"session\":\"crash-session\"") == std::string::npos
            || body.find("\"abi\":\"x86_64\"") == std::string::npos
            || body.find("\"tid\":") == std::string::npos) {
        throw std::runtime_error("native crash evidence incomplete: " + body);
    }

    recorder.reset();
    if (recorder.status().installed) throw std::runtime_error("native crash reset");
    unlink(path.c_str());
    unlink((path + ".1").c_str());
    std::cout << "PASS sandbox-native fatal signal evidence self-test\n";
    return EXIT_SUCCESS;
}
