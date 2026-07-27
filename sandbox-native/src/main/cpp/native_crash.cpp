#include "controlled_sandbox/native_crash.h"

#include <csignal>
#include <cstdint>
#include <fcntl.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>

namespace controlled_sandbox {
namespace {

constexpr std::array<int, 5> signals{SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV};

struct CrashState {
    mutable std::mutex mutex;
    NativeCrashStatus status;
    int descriptor{-1};
    std::array<struct sigaction, signals.size()> previous{};
};

CrashState& crash_state() {
    static CrashState state;
    return state;
}

void append_unsigned(char* output, std::size_t capacity, std::size_t& length, unsigned value) {
    char reverse[16]{};
    std::size_t digits = 0;
    do {
        reverse[digits++] = static_cast<char>('0' + value % 10U);
        value /= 10U;
    } while (value > 0U && digits < sizeof(reverse));
    while (digits > 0 && length < capacity) output[length++] = reverse[--digits];
}

void crash_handler(int signal_number, siginfo_t* info, void*) {
    auto& state = crash_state();
    char line[192]{};
    std::size_t length = 0;
    constexpr char prefix[] = "{\"event\":\"NATIVE_SIGNAL\",\"signal\":";
    for (char c : prefix) {
        if (c == '\0') break;
        line[length++] = c;
    }
    append_unsigned(line, sizeof(line), length, static_cast<unsigned>(signal_number));
    constexpr char address[] = ",\"address\":";
    for (char c : address) {
        if (c == '\0' || length >= sizeof(line)) break;
        line[length++] = c;
    }
    const auto raw_address = reinterpret_cast<std::uintptr_t>(info == nullptr ? nullptr : info->si_addr);
    append_unsigned(line, sizeof(line), length, static_cast<unsigned>(raw_address & 0xffffffffU));
    constexpr char suffix[] = "}\n";
    for (char c : suffix) {
        if (c == '\0' || length >= sizeof(line)) break;
        line[length++] = c;
    }
    if (state.descriptor >= 0) (void) write(state.descriptor, line, length);
    signal(signal_number, SIG_DFL);
    (void) kill(getpid(), signal_number);
}

}  // namespace

bool NativeCrashRecorder::install(std::string output_path) {
    if (output_path.empty() || output_path.front() != '/') return false;
    auto& state = crash_state();
    std::lock_guard lock(state.mutex);
    if (state.status.installed && state.status.output_path == output_path) return true;
    if (state.descriptor >= 0) close(state.descriptor);
    state.descriptor = open(output_path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (state.descriptor < 0) {
        state.status = {};
        state.status.last_error = std::string("OPEN_FAILED:") + std::strerror(errno);
        return false;
    }
    struct sigaction action{};
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_RESETHAND;
    for (std::size_t index = 0; index < signals.size(); index++) {
        if (sigaction(signals[index], &action, &state.previous[index]) != 0) {
            state.status.last_error = std::string("SIGACTION_FAILED:") + std::strerror(errno);
            close(state.descriptor);
            state.descriptor = -1;
            return false;
        }
    }
    state.status.installed = true;
    state.status.output_path = std::move(output_path);
    state.status.last_error.clear();
    return true;
}

void NativeCrashRecorder::reset() {
    auto& state = crash_state();
    std::lock_guard lock(state.mutex);
    if (state.status.installed) {
        for (std::size_t index = 0; index < signals.size(); index++) {
            (void) sigaction(signals[index], &state.previous[index], nullptr);
        }
    }
    if (state.descriptor >= 0) close(state.descriptor);
    state.descriptor = -1;
    state.status = {};
}

NativeCrashStatus NativeCrashRecorder::status() const {
    auto& state = crash_state();
    std::lock_guard lock(state.mutex);
    return state.status;
}

NativeCrashRecorder& global_crash_recorder() {
    static NativeCrashRecorder recorder;
    return recorder;
}

}  // namespace controlled_sandbox
