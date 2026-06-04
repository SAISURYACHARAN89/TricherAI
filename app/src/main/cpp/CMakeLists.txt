cmake_minimum_required(VERSION 3.22.1)

project(whisperfft)

add_library(whisperfft SHARED
        whisperfft_jni.cpp
)

target_compile_features(whisperfft PRIVATE cxx_std_11)

target_compile_options(whisperfft PRIVATE -O3 -ffast-math)

target_link_libraries(whisperfft
        log
)

