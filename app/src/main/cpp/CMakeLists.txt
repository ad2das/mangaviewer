cmake_minimum_required(VERSION 3.22.1)

project(ntk_strip_renderer LANGUAGES CXX)

if(NOT DEFINED NTK_QUALIFICATION_PROFILE_ID OR
        NTK_QUALIFICATION_PROFILE_ID STREQUAL "")
    message(FATAL_ERROR "NTK_QUALIFICATION_PROFILE_ID must come from the sealed manifest")
endif()

find_package(games-frame-pacing REQUIRED CONFIG)

set(SWAPPY_LOCAL_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/swappy")
set(SWAPPY_DEX_DIR "${CMAKE_CURRENT_BINARY_DIR}/swappy-dex")
set(SWAPPY_DEX_OBJECT "${SWAPPY_DEX_DIR}/classes_dex.o")
file(MAKE_DIRECTORY "${SWAPPY_DEX_DIR}")
add_custom_command(
        OUTPUT "${SWAPPY_DEX_OBJECT}"
        COMMAND "${CMAKE_AR}" x
                "$<TARGET_FILE:games-frame-pacing::swappy_static>" classes_dex.o
        WORKING_DIRECTORY "${SWAPPY_DEX_DIR}"
        DEPENDS games-frame-pacing::swappy_static
        VERBATIM)
set_source_files_properties("${SWAPPY_DEX_OBJECT}" PROPERTIES
        EXTERNAL_OBJECT TRUE
        GENERATED TRUE)

add_library(swappy_local STATIC
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/ChoreographerFilter.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/ChoreographerThread.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/CpuInfo.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/Settings.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/Thread.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/SwappyCommon.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/swappy_c.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/SwappyDisplayManager.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/CPUTracer.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common/FrameStatistics.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/opengl/EGL.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/opengl/swappyGL_c.cpp
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/opengl/SwappyGL.cpp
        ${SWAPPY_LOCAL_ROOT}/src/common/system_utils.cpp
        ${SWAPPY_DEX_OBJECT})
target_include_directories(swappy_local PUBLIC
        ${SWAPPY_LOCAL_ROOT}/include
        ${SWAPPY_LOCAL_ROOT}/src/common
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common
        ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/opengl)
target_compile_features(swappy_local PRIVATE cxx_std_17)
target_compile_options(swappy_local PRIVATE
        -O3 -fno-exceptions -fno-rtti -ffunction-sections -fdata-sections)

add_library(ntk_strip_renderer SHARED
        ntk_strip_renderer.cpp
        ntk_rolling_surface_renderer.cpp
        ntk_fixed_depth_one_scheduler.cpp
        present/HardwareBufferRenderTargetPool.cpp
        present/SurfaceControlPresentBackend.cpp)

target_compile_features(ntk_strip_renderer PRIVATE cxx_std_20)
target_compile_options(ntk_strip_renderer PRIVATE
        -Wall
        -Wextra
        -Werror
        -fvisibility=hidden)
target_compile_definitions(ntk_strip_renderer PRIVATE
        NTK_QUALIFICATION_PROFILE_ID=\"${NTK_QUALIFICATION_PROFILE_ID}\")

target_link_libraries(ntk_strip_renderer PRIVATE
        -Wl,--whole-archive
        swappy_local
        -Wl,--no-whole-archive
        android
        atomic
        dl
        EGL
        GLESv2
        GLESv3
        jnigraphics
        log)

# The phase tests are Android executables, not host binaries: they link the
# exact production SwappyCommon object code and are executed on the target ABI
# by the runNtkNativeOracleTests Gradle verification task.  Keeping them Debug
# only avoids adding test build cost or payload to benchmark/release APKs.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_executable(FixedNonPipelinePhaseOracleParityTest
            ${SWAPPY_LOCAL_ROOT}/tests/FixedNonPipelinePhaseOracleParityTest.cpp)
    target_compile_features(FixedNonPipelinePhaseOracleParityTest PRIVATE
            cxx_std_17)
    target_compile_options(FixedNonPipelinePhaseOracleParityTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(FixedExternalTransportAdmissionTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/FixedExternalTransportAdmissionTest.cpp)
    target_compile_features(FixedExternalTransportAdmissionTest PRIVATE
            cxx_std_20)
    target_compile_options(FixedExternalTransportAdmissionTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(GpuSceneAdmissionLedgerTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/GpuSceneAdmissionLedgerTest.cpp)
    target_compile_features(GpuSceneAdmissionLedgerTest PRIVATE cxx_std_20)
    target_compile_options(GpuSceneAdmissionLedgerTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(PreparedSceneBankLedgerTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/PreparedSceneBankLedgerTest.cpp)
    target_compile_features(PreparedSceneBankLedgerTest PRIVATE cxx_std_20)
    target_compile_options(PreparedSceneBankLedgerTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(FixedPresentJoinStateMachineTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/FixedPresentJoinStateMachineTest.cpp)
    target_compile_features(FixedPresentJoinStateMachineTest PRIVATE cxx_std_20)
    target_compile_options(FixedPresentJoinStateMachineTest PRIVATE
            -Wall -Wextra -Werror)
    target_include_directories(FixedPresentJoinStateMachineTest PRIVATE
            ${SWAPPY_LOCAL_ROOT}/include)

    add_executable(HardwareBufferRenderTargetPoolTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/HardwareBufferRenderTargetPoolTest.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/present/HardwareBufferRenderTargetPool.cpp)
    target_compile_features(HardwareBufferRenderTargetPoolTest PRIVATE cxx_std_20)
    target_compile_options(HardwareBufferRenderTargetPoolTest PRIVATE
            -Wall -Wextra -Werror)
    target_link_libraries(HardwareBufferRenderTargetPoolTest PRIVATE
            android dl EGL GLESv2 GLESv3)

    add_executable(SurfaceControlPresentBackendTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/SurfaceControlPresentBackendTest.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/present/SurfaceControlPresentBackend.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/present/HardwareBufferRenderTargetPool.cpp)
    target_compile_features(SurfaceControlPresentBackendTest PRIVATE cxx_std_20)
    target_compile_options(SurfaceControlPresentBackendTest PRIVATE
            -Wall -Wextra -Werror)
    target_include_directories(SurfaceControlPresentBackendTest PRIVATE
            ${SWAPPY_LOCAL_ROOT}/include
            ${SWAPPY_LOCAL_ROOT}/src)
    target_link_libraries(SurfaceControlPresentBackendTest PRIVATE
            android dl EGL GLESv2 GLESv3)

    add_executable(SwappyExternalSubmissionContractTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/SwappyExternalSubmissionContractTest.cpp)
    target_compile_features(SwappyExternalSubmissionContractTest PRIVATE cxx_std_20)
    target_compile_options(SwappyExternalSubmissionContractTest PRIVATE
            -Wall -Wextra -Werror)
    target_include_directories(SwappyExternalSubmissionContractTest PRIVATE
            ${SWAPPY_LOCAL_ROOT}/include
            ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common)

    add_executable(SwappyExternalTransportStateTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/SwappyExternalTransportStateTest.cpp)
    target_compile_features(SwappyExternalTransportStateTest PRIVATE cxx_std_20)
    target_compile_options(SwappyExternalTransportStateTest PRIVATE
            -Wall -Wextra -Werror)
    target_include_directories(SwappyExternalTransportStateTest PRIVATE
            ${SWAPPY_LOCAL_ROOT}/include
            ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common)

    add_executable(RendererPostSubmitFatalContractTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/RendererPostSubmitFatalContractTest.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/present/HardwareBufferRenderTargetPool.cpp)
    target_compile_features(RendererPostSubmitFatalContractTest PRIVATE cxx_std_20)
    target_compile_options(RendererPostSubmitFatalContractTest PRIVATE
            -Wall -Wextra -Werror)
    target_link_libraries(RendererPostSubmitFatalContractTest PRIVATE
            android dl EGL GLESv2 GLESv3)

    add_executable(FixedDepthOneSchedulerTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/FixedDepthOneSchedulerTest.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/ntk_fixed_depth_one_scheduler.cpp)
    target_compile_features(FixedDepthOneSchedulerTest PRIVATE cxx_std_20)
    target_compile_options(FixedDepthOneSchedulerTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(SwappyCommonFixedAdmissionTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/SwappyCommonFixedAdmissionTest.cpp)
    target_compile_features(SwappyCommonFixedAdmissionTest PRIVATE cxx_std_20)
    target_compile_options(SwappyCommonFixedAdmissionTest PRIVATE
            -Wall -Wextra -Werror)
    target_include_directories(SwappyCommonFixedAdmissionTest PRIVATE
            ${SWAPPY_LOCAL_ROOT}/include
            ${SWAPPY_LOCAL_ROOT}/games-frame-pacing/common)
    target_link_libraries(SwappyCommonFixedAdmissionTest PRIVATE
            swappy_local android atomic dl log)

    add_executable(UnownedSurfaceBootstrapAuthorityTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/UnownedSurfaceBootstrapAuthorityTest.cpp)
    target_compile_features(UnownedSurfaceBootstrapAuthorityTest PRIVATE cxx_std_20)
    target_compile_options(UnownedSurfaceBootstrapAuthorityTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(ExactManifestSurfaceEpochPromotionTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/ExactManifestSurfaceEpochPromotionTest.cpp)
    target_compile_features(ExactManifestSurfaceEpochPromotionTest PRIVATE cxx_std_20)
    target_compile_options(ExactManifestSurfaceEpochPromotionTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(AttachGenerationContractTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/AttachGenerationContractTest.cpp)
    target_compile_features(AttachGenerationContractTest PRIVATE cxx_std_20)
    target_compile_options(AttachGenerationContractTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(NativeSurfaceLeaseRegistryTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/NativeSurfaceLeaseRegistryTest.cpp)
    target_compile_features(NativeSurfaceLeaseRegistryTest PRIVATE cxx_std_20)
    target_compile_options(NativeSurfaceLeaseRegistryTest PRIVATE
            -Wall -Wextra -Werror)

    add_executable(DetachedWarmLifecycleContractTest
            ${CMAKE_CURRENT_SOURCE_DIR}/tests/DetachedWarmLifecycleContractTest.cpp)
    target_compile_features(DetachedWarmLifecycleContractTest PRIVATE cxx_std_20)
    target_compile_options(DetachedWarmLifecycleContractTest PRIVATE
            -Wall -Wextra -Werror)

    # AGP supplies an ABI-specific library output directory.  Put executables
    # beside libc++_shared.so so the device runner has one unambiguous source.
    if(CMAKE_LIBRARY_OUTPUT_DIRECTORY)
        set_target_properties(
                FixedNonPipelinePhaseOracleParityTest
                FixedExternalTransportAdmissionTest
                GpuSceneAdmissionLedgerTest
                PreparedSceneBankLedgerTest
                FixedPresentJoinStateMachineTest
                HardwareBufferRenderTargetPoolTest
                SurfaceControlPresentBackendTest
                SwappyExternalSubmissionContractTest
                SwappyExternalTransportStateTest
                RendererPostSubmitFatalContractTest
                FixedDepthOneSchedulerTest
                SwappyCommonFixedAdmissionTest
                UnownedSurfaceBootstrapAuthorityTest
                ExactManifestSurfaceEpochPromotionTest
                AttachGenerationContractTest
                NativeSurfaceLeaseRegistryTest
                DetachedWarmLifecycleContractTest
                PROPERTIES
                RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
    endif()

    # externalNativeBuild normally requests the packaged shared library target.
    # These dependencies make both executable tests part of every Debug native
    # build, so the Gradle runner can never execute a stale or missing binary.
    add_dependencies(ntk_strip_renderer
            FixedNonPipelinePhaseOracleParityTest
            FixedExternalTransportAdmissionTest
            GpuSceneAdmissionLedgerTest
            PreparedSceneBankLedgerTest
            FixedPresentJoinStateMachineTest)
    add_dependencies(ntk_strip_renderer HardwareBufferRenderTargetPoolTest)
    add_dependencies(ntk_strip_renderer SurfaceControlPresentBackendTest)
    add_dependencies(ntk_strip_renderer SwappyExternalSubmissionContractTest)
    add_dependencies(ntk_strip_renderer SwappyExternalTransportStateTest)
    add_dependencies(ntk_strip_renderer RendererPostSubmitFatalContractTest)
    add_dependencies(ntk_strip_renderer FixedDepthOneSchedulerTest)
    add_dependencies(ntk_strip_renderer SwappyCommonFixedAdmissionTest)
    add_dependencies(ntk_strip_renderer UnownedSurfaceBootstrapAuthorityTest)
    add_dependencies(ntk_strip_renderer ExactManifestSurfaceEpochPromotionTest)
    add_dependencies(ntk_strip_renderer AttachGenerationContractTest)
    add_dependencies(ntk_strip_renderer NativeSurfaceLeaseRegistryTest)
    add_dependencies(ntk_strip_renderer DetachedWarmLifecycleContractTest)
endif()
