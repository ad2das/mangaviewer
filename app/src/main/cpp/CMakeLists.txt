cmake_minimum_required(VERSION 3.22.1)

project(viewer_native LANGUAGES CXX)

add_library(viewer_native SHARED
        gl_presentation_callback.cpp
        gl_strip_readback.cpp
        gl_viewer_readback.cpp
        gl_readback_jni.cpp
        gl_viewer_verification.cpp
        gl_renderer_jni.cpp
        gl_resources_jni.cpp
        gl_viewer_resources.cpp
        gl_texture_upload.cpp
        gl_viewer_renderer.cpp
        viewer_cpu_decode.cpp
        )

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_sources(viewer_native PRIVATE ../../debug/cpp/surface_transaction_probe.cpp)
endif()

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
        EGL
        GLESv3
        jnigraphics
        log
        z
        )
