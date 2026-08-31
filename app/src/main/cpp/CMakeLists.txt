cmake_minimum_required(VERSION 3.22.1)

project(viewer_native LANGUAGES CXX)

add_library(viewer_native SHARED
        viewer_pixels.cpp)

target_compile_features(viewer_native PRIVATE cxx_std_20)
target_compile_options(viewer_native PRIVATE
        -O3
        -Wall
        -Wextra
        -Werror
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden)
target_link_libraries(viewer_native PRIVATE
        -Wl,--gc-sections
        android
        atomic
        dl
        jnigraphics
        log
        )
