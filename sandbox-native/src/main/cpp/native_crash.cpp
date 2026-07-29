#include "controlled_sandbox/native_crash.h"

#include <csignal>
#include <cstdint>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <array>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>

namespace controlled_sandbox {
namespace {

constexpr std::array<int, 6> signals{SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV, SIGTRAP};
constexpr off_t MAX_CRASH_FILE_BYTES = 2 * 1024 * 1024;
constexpr std::size_t ALT_STACK_BYTES = 128 * 1024;

struct CrashState {
    mutable std::mutex mutex;
    NativeCrashStatus status;
    std::atomic<int> descriptor{-1};
    std::atomic<std::uint64_t> records_written{0};
    std::array<struct sigaction, signals.size()> previous{};
    stack_t previous_stack{};
    void* alternate_stack{MAP_FAILED};
    std::array<char, 129> session{};
    std::array<char, 257> process{};
    std::array<char, 33> abi{};
    std::uint64_t generation{};
};

CrashState& crash_state() {
    static CrashState state;
    return state;
}

void copy_bounded(std::string_view value, char* target, std::size_t capacity) {
    if (target == nullptr || capacity == 0) return;
    const std::size_t count = std::min(value.size(), capacity - 1);
    std::memcpy(target, value.data(), count);
    target[count] = '\0';
}

void append_literal(char* output, std::size_t capacity, std::size_t& length, const char* value) {
    if (value == nullptr) return;
    for (std::size_t index = 0; value[index] != '\0' && length < capacity; index++) {
        output[length++] = value[index];
    }
}

void append_unsigned(char* output, std::size_t capacity, std::size_t& length, std::uint64_t value) {
    char reverse[32]{};
    std::size_t digits = 0;
    do {
        reverse[digits++] = static_cast<char>('0' + value % 10U);
        value /= 10U;
    } while (value > 0U && digits < sizeof(reverse));
    while (digits > 0 && length < capacity) output[length++] = reverse[--digits];
}

void append_signed(char* output, std::size_t capacity, std::size_t& length, std::int64_t value) {
    if (value < 0) {
        if (length < capacity) output[length++] = '-';
        const std::uint64_t magnitude = static_cast<std::uint64_t>(-(value + 1)) + 1U;
        append_unsigned(output, capacity, length, magnitude);
    } else {
        append_unsigned(output, capacity, length, static_cast<std::uint64_t>(value));
    }
}

void append_hex(char* output, std::size_t capacity, std::size_t& length, std::uintptr_t value) {
    constexpr char digits[] = "0123456789abcdef";
    append_literal(output, capacity, length, "\"0x");
    bool started = false;
    for (int shift = static_cast<int>(sizeof(value) * 8U) - 4; shift >= 0; shift -= 4) {
        const unsigned nibble = static_cast<unsigned>((value >> shift) & 0xFU);
        if (nibble != 0 || started || shift == 0) {
            started = true;
            if (length < capacity) output[length++] = digits[nibble];
        }
    }
    if (length < capacity) output[length++] = '"';
}

void append_json_string(char* output, std::size_t capacity, std::size_t& length, const char* value) {
    if (length < capacity) output[length++] = '"';
    if (value != nullptr) {
        for (std::size_t index = 0; value[index] != '\0' && length < capacity; index++) {
            const char current = value[index];
            if (current == '"' || current == '\\') {
                if (length < capacity) output[length++] = '\\';
                if (length < capacity) output[length++] = current;
            } else if (current >= 0x20 && current <= 0x7e) {
                output[length++] = current;
            }
        }
    }
    if (length < capacity) output[length++] = '"';
}

void crash_handler(int signal_number, siginfo_t* info, void*) {
    auto& state = crash_state();
    char line[1024]{};
    std::size_t length = 0;
    timespec now{};
    (void) clock_gettime(CLOCK_REALTIME, &now);
    const std::uint64_t timestamp_ms = static_cast<std::uint64_t>(now.tv_sec) * 1000U
            + static_cast<std::uint64_t>(now.tv_nsec / 1000000L);
    const pid_t pid = getpid();
    const pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));

    append_literal(line, sizeof(line), length, "{\"event\":\"NATIVE_FATAL_SIGNAL\",\"timestampMs\":");
    append_unsigned(line, sizeof(line), length, timestamp_ms);
    append_literal(line, sizeof(line), length, ",\"pid\":");
    append_signed(line, sizeof(line), length, pid);
    append_literal(line, sizeof(line), length, ",\"tid\":");
    append_signed(line, sizeof(line), length, tid);
    append_literal(line, sizeof(line), length, ",\"signal\":");
    append_signed(line, sizeof(line), length, signal_number);
    append_literal(line, sizeof(line), length, ",\"code\":");
    append_signed(line, sizeof(line), length, info == nullptr ? 0 : info->si_code);
    append_literal(line, sizeof(line), length, ",\"address\":");
    append_hex(line, sizeof(line), length,
            reinterpret_cast<std::uintptr_t>(info == nullptr ? nullptr : info->si_addr));
    append_literal(line, sizeof(line), length, ",\"generation\":");
    append_unsigned(line, sizeof(line), length, state.generation);
    append_literal(line, sizeof(line), length, ",\"session\":");
    append_json_string(line, sizeof(line), length, state.session.data());
    append_literal(line, sizeof(line), length, ",\"process\":");
    append_json_string(line, sizeof(line), length, state.process.data());
    append_literal(line, sizeof(line), length, ",\"abi\":");
    append_json_string(line, sizeof(line), length, state.abi.data());
    append_literal(line, sizeof(line), length, "}\n");

    const int descriptor = state.descriptor.load(std::memory_order_relaxed);
    if (descriptor >= 0) {
        (void) write(descriptor, line, length);
        (void) fsync(descriptor);
        state.records_written.fetch_add(1, std::memory_order_relaxed);
    }
    struct sigaction action{};
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    (void) sigaction(signal_number, &action, nullptr);
    if (syscall(SYS_tgkill, pid, tid, signal_number) == 0) return;
    _exit(128 + signal_number);
}

void rotate_if_needed(const std::string& output_path) {
    struct stat metadata{};
    if (lstat(output_path.c_str(), &metadata) != 0) return;
    if (!S_ISREG(metadata.st_mode)) throw std::runtime_error("CRASH_OUTPUT_NOT_REGULAR");
    if (metadata.st_size <= MAX_CRASH_FILE_BYTES) return;
    const std::string previous = output_path + ".1";
    (void) unlink(previous.c_str());
    if (rename(output_path.c_str(), previous.c_str()) != 0) {
        throw std::runtime_error(std::string("CRASH_ROTATE_FAILED:") + std::strerror(errno));
    }
}

void reset_locked(CrashState& state) {
    if (state.status.installed) {
        for (std::size_t index = 0; index < signals.size(); index++) {
            (void) sigaction(signals[index], &state.previous[index], nullptr);
        }
    }
    const int descriptor = state.descriptor.exchange(-1, std::memory_order_acq_rel);
    if (descriptor >= 0) close(descriptor);
    if (state.alternate_stack != MAP_FAILED) {
        (void) sigaltstack(&state.previous_stack, nullptr);
        munmap(state.alternate_stack, ALT_STACK_BYTES);
        state.alternate_stack = MAP_FAILED;
    }
    state.status = {};
    state.records_written.store(0, std::memory_order_relaxed);
    state.session = {};
    state.process = {};
    state.abi = {};
    state.generation = 0;
}

}  // namespace

bool NativeCrashRecorder::install(std::string output_path, NativeCrashContext context) {
    if (output_path.empty() || output_path.front() != '/' || output_path.size() > 4096) return false;
    auto& state = crash_state();
    std::lock_guard lock(state.mutex);
    if (state.status.installed && state.status.output_path == output_path
            && state.generation == context.generation) return true;
    reset_locked(state);
    try {
        rotate_if_needed(output_path);
    } catch (const std::exception& error) {
        state.status.last_error = error.what();
        return false;
    }
    const int descriptor = open(output_path.c_str(),
            O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (descriptor < 0) {
        state.status = {};
        state.status.last_error = std::string("OPEN_FAILED:") + std::strerror(errno);
        return false;
    }
    struct stat metadata{};
    if (fstat(descriptor, &metadata) != 0 || !S_ISREG(metadata.st_mode)) {
        state.status.last_error = "CRASH_OUTPUT_NOT_REGULAR";
        close(descriptor);
        return false;
    }

    state.alternate_stack = mmap(nullptr, ALT_STACK_BYTES, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (state.alternate_stack == MAP_FAILED) {
        state.status.last_error = std::string("SIGALTSTACK_ALLOC_FAILED:") + std::strerror(errno);
        close(descriptor);
        return false;
    }
    stack_t stack{};
    stack.ss_sp = state.alternate_stack;
    stack.ss_size = ALT_STACK_BYTES;
    stack.ss_flags = 0;
    if (sigaltstack(&stack, &state.previous_stack) != 0) {
        state.status.last_error = std::string("SIGALTSTACK_FAILED:") + std::strerror(errno);
        munmap(state.alternate_stack, ALT_STACK_BYTES);
        state.alternate_stack = MAP_FAILED;
        close(descriptor);
        return false;
    }

    copy_bounded(context.session_id, state.session.data(), state.session.size());
    copy_bounded(context.process_name, state.process.data(), state.process.size());
    copy_bounded(context.abi_name, state.abi.data(), state.abi.size());
    state.generation = context.generation;
    state.records_written.store(0, std::memory_order_relaxed);
    state.descriptor.store(descriptor, std::memory_order_release);

    struct sigaction action{};
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_ONSTACK;
    for (std::size_t index = 0; index < signals.size(); index++) {
        if (sigaction(signals[index], &action, &state.previous[index]) != 0) {
            state.status.last_error = std::string("SIGACTION_FAILED:") + std::strerror(errno);
            for (std::size_t rollback = 0; rollback < index; rollback++) {
                (void) sigaction(signals[rollback], &state.previous[rollback], nullptr);
            }
            state.descriptor.store(-1, std::memory_order_release);
            close(descriptor);
            (void) sigaltstack(&state.previous_stack, nullptr);
            munmap(state.alternate_stack, ALT_STACK_BYTES);
            state.alternate_stack = MAP_FAILED;
            return false;
        }
    }
    state.status.installed = true;
    state.status.alternate_stack_installed = true;
    state.status.output_path = std::move(output_path);
    state.status.last_error.clear();
    state.status.generation = context.generation;
    state.status.records_written = 0;
    return true;
}

void NativeCrashRecorder::reset() {
    auto& state = crash_state();
    std::lock_guard lock(state.mutex);
    reset_locked(state);
}

NativeCrashStatus NativeCrashRecorder::status() const {
    auto& state = crash_state();
    std::lock_guard lock(state.mutex);
    NativeCrashStatus result = state.status;
    result.records_written = state.records_written.load(std::memory_order_relaxed);
    return result;
}

NativeCrashRecorder& global_crash_recorder() {
    static NativeCrashRecorder recorder;
    return recorder;
}

}  // namespace controlled_sandbox
