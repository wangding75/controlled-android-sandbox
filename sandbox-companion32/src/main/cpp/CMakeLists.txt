cmake_minimum_required(VERSION 3.22.1)
project(controlled_sandbox_native32 LANGUAGES CXX)

set(SHARED_NATIVE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../sandbox-native/src/main/cpp")
add_library(controlled_sandbox_native32 SHARED
    native_companion_jni.cpp
    ${SHARED_NATIVE_ROOT}/native_policy.cpp
    ${SHARED_NATIVE_ROOT}/native_file_system.cpp
    ${SHARED_NATIVE_ROOT}/native_procfs.cpp
    ${SHARED_NATIVE_ROOT}/native_loader.cpp
    ${SHARED_NATIVE_ROOT}/native_network.cpp
    ${SHARED_NATIVE_ROOT}/native_network_interceptors.cpp
    ${SHARED_NATIVE_ROOT}/native_audio.cpp
    ${SHARED_NATIVE_ROOT}/native_interceptors.cpp
    ${SHARED_NATIVE_ROOT}/native_camera1.cpp
    ${SHARED_NATIVE_ROOT}/native_hook.cpp
    ${SHARED_NATIVE_ROOT}/native_crash.cpp
    ${SHARED_NATIVE_ROOT}/native_policy_jni.cpp
    ${SHARED_NATIVE_ROOT}/native_jni_exception_probe.cpp)
target_include_directories(controlled_sandbox_native32 PRIVATE ${SHARED_NATIVE_ROOT}/include)
target_compile_features(controlled_sandbox_native32 PRIVATE cxx_std_20)
target_compile_options(controlled_sandbox_native32 PRIVATE -Wall -Wextra -Werror)
target_link_libraries(controlled_sandbox_native32 PRIVATE android log dl)
