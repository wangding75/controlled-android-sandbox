#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaError.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

namespace {

#if defined(__x86_64__)
constexpr const char* kCompiledAbi = "x86_64";
#elif defined(__aarch64__)
constexpr const char* kCompiledAbi = "arm64-v8a";
#elif defined(__i386__)
constexpr const char* kCompiledAbi = "x86";
#elif defined(__arm__)
constexpr const char* kCompiledAbi = "armeabi-v7a";
#else
constexpr const char* kCompiledAbi = "unknown";
#endif

std::string json_escape(const std::string& input) {
    std::string output;
    output.reserve(input.size() + 8U);
    for (const unsigned char character : input) {
        if (character == '\\' || character == '"') {
            output.push_back('\\');
            output.push_back(static_cast<char>(character));
        } else if (character == '\n') {
            output += "\\n";
        } else if (character == '\r') {
            output += "\\r";
        } else if (character == '\t') {
            output += "\\t";
        } else if (character < 0x20U) {
            char buffer[8] = {};
            snprintf(buffer, sizeof(buffer), "\\u%04x", character);
            output += buffer;
        } else {
            output.push_back(static_cast<char>(character));
        }
    }
    return output;
}

std::string json_status(const char* status, const std::string& detail) {
    return std::string("{\"status\":\"") + status + "\",\"abi\":\""
            + kCompiledAbi + "\",\"detail\":\"" + json_escape(detail) + "\"}";
}

std::string error_code(const char* operation, int code) {
    return std::string(operation) + "=" + std::to_string(code);
}

std::string late_dlopen() {
    void* handle = ::dlopen("libfixture_adv_payload.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* error = ::dlerror();
        return json_status("FAIL", std::string("dlopen=") + (error == nullptr ? "unknown" : error));
    }
    using Marker = const char* (*)();
    auto marker = reinterpret_cast<Marker>(::dlsym(handle, "fixture_adv_payload_marker"));
    const char* marker_value = marker == nullptr ? nullptr : marker();
    const std::string marker_text = marker_value == nullptr ? "missing" : marker_value;
    const int close_rc = ::dlclose(handle);
    const bool passed = marker_text == "FIXTURE_ADV_PAYLOAD_V1" && close_rc == 0;
    return json_status(
            passed ? "PASS" : "FAIL",
            std::string("symbol=") + marker_text
                    + ";dlclose=" + std::to_string(close_rc));
}

std::string surface_buffer_round_trip(JNIEnv* env, jobject surface) {
    if (surface == nullptr) return json_status("FAIL", "surface=null");
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return json_status("FAIL", "ANativeWindow_fromSurface=null");

    const int geometry_rc = ANativeWindow_setBuffersGeometry(
            window, 64, 48, WINDOW_FORMAT_RGBA_8888);
    if (geometry_rc != 0) {
        ANativeWindow_release(window);
        return json_status("FAIL", error_code("setBuffersGeometry", geometry_rc));
    }

    ANativeWindow_Buffer buffer{};
    ARect dirty{0, 0, 64, 48};
    const int lock_rc = ANativeWindow_lock(window, &buffer, &dirty);
    if (lock_rc != 0 || buffer.bits == nullptr || buffer.stride < buffer.width) {
        ANativeWindow_release(window);
        return json_status("FAIL", error_code("lock", lock_rc)
                + ";bits=" + (buffer.bits == nullptr ? "null" : "present")
                + ";stride=" + std::to_string(buffer.stride));
    }

    auto* pixels = static_cast<std::uint32_t*>(buffer.bits);
    for (int row = 0; row < buffer.height; ++row) {
        for (int column = 0; column < buffer.width; ++column) {
            pixels[row * buffer.stride + column] = 0xff000000U
                    | (static_cast<std::uint32_t>(row) << 8U)
                    | static_cast<std::uint32_t>(column);
        }
    }
    const int post_rc = ANativeWindow_unlockAndPost(window);
    const int width = buffer.width;
    const int height = buffer.height;
    const int stride = buffer.stride;
    ANativeWindow_release(window);
    return json_status(post_rc == 0 ? "PASS" : "FAIL",
            error_code("unlockAndPost", post_rc) + ";width=" + std::to_string(width)
                    + ";height=" + std::to_string(height)
                    + ";stride=" + std::to_string(stride));
}

std::string codec_probe() {
    AMediaCodec* codec = AMediaCodec_createEncoderByType("video/avc");
    if (codec == nullptr) return json_status("ENVIRONMENT_NOT_AVAILABLE", "createEncoderByType=null");
    AMediaFormat* format = AMediaFormat_new();
    bool started = false;
    auto cleanup = [&]() {
        if (started) (void) AMediaCodec_stop(codec);
        if (format != nullptr) (void) AMediaFormat_delete(format);
        (void) AMediaCodec_delete(codec);
    };
    if (format == nullptr) {
        (void) AMediaCodec_delete(codec);
        return json_status("FAIL", "AMediaFormat_new=null");
    }

    AMediaFormat_setString(format, "mime", "video/avc");
    AMediaFormat_setInt32(format, "width", 16);
    AMediaFormat_setInt32(format, "height", 16);
    AMediaFormat_setInt32(format, "color-format", 21);
    AMediaFormat_setInt32(format, "bitrate", 32 * 1024);
    AMediaFormat_setInt32(format, "frame-rate", 1);
    AMediaFormat_setInt32(format, "i-frame-interval", 1);

    const media_status_t configure_rc = AMediaCodec_configure(
            codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    if (configure_rc != AMEDIA_OK) {
        cleanup();
        return json_status("ENVIRONMENT_NOT_AVAILABLE", error_code("configure", configure_rc));
    }
    const media_status_t start_rc = AMediaCodec_start(codec);
    if (start_rc != AMEDIA_OK) {
        cleanup();
        return json_status("ENVIRONMENT_NOT_AVAILABLE", error_code("start", start_rc));
    }
    started = true;

    const ssize_t input_index = AMediaCodec_dequeueInputBuffer(codec, 2'000'000);
    if (input_index < 0) {
        cleanup();
        return json_status("FAIL", error_code("dequeueInput", static_cast<int>(input_index)));
    }
    size_t input_capacity = 0;
    std::uint8_t* input = AMediaCodec_getInputBuffer(
            codec, static_cast<size_t>(input_index), &input_capacity);
    if (input == nullptr || input_capacity == 0U) {
        cleanup();
        return json_status("FAIL", "inputBuffer=empty");
    }
    const size_t payload_size = std::min<size_t>(input_capacity, 16U * 16U * 3U / 2U);
    std::memset(input, 0x80, payload_size);
    const media_status_t queue_rc = AMediaCodec_queueInputBuffer(
            codec, static_cast<size_t>(input_index), 0, payload_size, 0, 0);
    if (queue_rc != AMEDIA_OK) {
        cleanup();
        return json_status("FAIL", error_code("queueInput", queue_rc));
    }

    ssize_t output_index = AMEDIACODEC_INFO_TRY_AGAIN_LATER;
    AMediaCodecBufferInfo output_info{};
    bool output_format_changed = false;
    for (int attempt = 0; attempt < 30; ++attempt) {
        output_index = AMediaCodec_dequeueOutputBuffer(codec, &output_info, 200'000);
        if (output_index >= 0) break;
        if (output_index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            output_format_changed = true;
        } else if (output_index != AMEDIACODEC_INFO_TRY_AGAIN_LATER
                && output_index != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            break;
        }
    }
    if (output_index < 0) {
        cleanup();
        return json_status("FAIL", error_code("dequeueOutput", static_cast<int>(output_index))
                + ";formatChanged=" + (output_format_changed ? "1" : "0"));
    }
    size_t output_capacity = 0;
    std::uint8_t* output = AMediaCodec_getOutputBuffer(
            codec, static_cast<size_t>(output_index), &output_capacity);
    const bool output_valid = output_info.size == 0 || output != nullptr;
    const media_status_t release_rc = AMediaCodec_releaseOutputBuffer(
            codec, static_cast<size_t>(output_index), false);
    cleanup();
    if (!output_valid || release_rc != AMEDIA_OK) {
        return json_status("FAIL", std::string("outputBuffer=")
                + (output_valid ? "valid" : "null")
                + ";release=" + std::to_string(release_rc));
    }
    return json_status("PASS", "inputBytes=" + std::to_string(payload_size)
            + ";outputBytes=" + std::to_string(output_info.size)
            + ";outputCapacity=" + std::to_string(output_capacity)
            + ";formatChanged=" + (output_format_changed ? "1" : "0"));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_C3T03NativeMediaActivity_nativeCompiledAbi(
        JNIEnv* env, jclass) {
    return env->NewStringUTF(kCompiledAbi);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_warden_controlledsandbox_fixture_C3T03NativeMediaActivity_nativePageSize(
        JNIEnv*, jclass) {
    const long page_size = ::sysconf(_SC_PAGESIZE);
    return page_size > 0 ? static_cast<jint>(page_size) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_C3T03NativeMediaActivity_nativeLateDlopen(
        JNIEnv* env, jclass) {
    const std::string result = late_dlopen();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_C3T03NativeMediaActivity_nativeSurfaceBufferRoundTrip(
        JNIEnv* env, jclass, jobject surface) {
    const std::string result = surface_buffer_round_trip(env, surface);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_warden_controlledsandbox_fixture_C3T03NativeMediaActivity_nativeCodecProbe(
        JNIEnv* env, jclass) {
    const std::string result = codec_probe();
    return env->NewStringUTF(result.c_str());
}
