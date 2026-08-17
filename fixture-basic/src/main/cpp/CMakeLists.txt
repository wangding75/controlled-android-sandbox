cmake_minimum_required(VERSION 3.22.1)
project(controlled_sandbox_fixture LANGUAGES CXX)

add_library(controlled_sandbox_fixture SHARED
    fixture_native.cpp
    adversarial_native.cpp
)
target_compile_features(controlled_sandbox_fixture PRIVATE cxx_std_20)
target_compile_options(controlled_sandbox_fixture PRIVATE -Wall -Wextra -Werror)
target_link_libraries(controlled_sandbox_fixture PRIVATE log dl)

add_library(fixture_adv_payload SHARED adversarial_payload.cpp)
target_compile_features(fixture_adv_payload PRIVATE cxx_std_20)
target_compile_options(fixture_adv_payload PRIVATE -Wall -Wextra -Werror)
