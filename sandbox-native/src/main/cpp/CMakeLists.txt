cmake_minimum_required(VERSION 3.22.1)
project(controlled_sandbox_native LANGUAGES CXX)

add_library(controlled_sandbox_native SHARED
    native_policy.cpp
    native_file_system.cpp
    native_procfs.cpp
    native_network.cpp
    native_audio.cpp
    native_loader.cpp
    native_interceptors.cpp
    native_hook.cpp
    native_crash.cpp
    native_policy_jni.cpp
)
target_include_directories(controlled_sandbox_native PRIVATE include)
target_compile_features(controlled_sandbox_native PRIVATE cxx_std_20)
target_compile_options(controlled_sandbox_native PRIVATE -Wall -Wextra -Werror)

target_link_libraries(controlled_sandbox_native PRIVATE dl)
