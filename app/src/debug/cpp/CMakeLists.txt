cmake_minimum_required(VERSION 3.22.1)
project(cas_native_enf LANGUAGES CXX)

add_library(cas_native_enf SHARED
    ../../../../verification/native-enforcement/enforcement_native.cpp
)
target_compile_features(cas_native_enf PRIVATE cxx_std_20)
target_compile_options(cas_native_enf PRIVATE -Wall -Wextra -Werror)
target_link_options(cas_native_enf PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(cas_native_enf PRIVATE log)
