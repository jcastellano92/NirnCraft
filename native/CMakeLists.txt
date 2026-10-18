cmake_minimum_required(VERSION 3.28)
project(aether_core VERSION 0.1.0 LANGUAGES CXX)

# Toolchains live outside the repo (see native/README.md); env.ps1 sets these.
set(SLANG_ROOT "$ENV{SLANG_ROOT}" CACHE PATH "Slang release root")
set(VULKAN_SDK "$ENV{VULKAN_SDK}" CACHE PATH "Vulkan SDK root")
if(NOT EXISTS "${SLANG_ROOT}/bin/slangc.exe" AND NOT EXISTS "${SLANG_ROOT}/bin/slangc")
  message(FATAL_ERROR "SLANG_ROOT does not point at a Slang release (run toolchains/env.ps1)")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(MSVC)
  add_compile_options(/W3 /permissive- /bigobj /utf-8 /Zc:__cplusplus)
endif()

set(GEN "${CMAKE_BINARY_DIR}/gen")
file(MAKE_DIRECTORY "${GEN}")
find_program(SLANGC slangc HINTS "${SLANG_ROOT}/bin" REQUIRED NO_DEFAULT_PATH)
set(KERNEL_SRC "${CMAKE_SOURCE_DIR}/shaders/aether_far.slang")
set(KERNEL_DEPS "${KERNEL_SRC}" "${CMAKE_SOURCE_DIR}/shaders/aether_shared.slang" "${CMAKE_SOURCE_DIR}/shaders/aether_clouds.slang"
    "${CMAKE_SOURCE_DIR}/shaders/aether_actors.slang")

# One kernel source, two targets.
add_custom_command(
  OUTPUT "${GEN}/aether_far.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry farMain -stage compute -O3 -o "${GEN}/aether_far.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc aether_far -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/aether_far_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/aether_far.spv -DOUT=${GEN}/aether_far_spv.h -DNAME=aether_far_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/aether_far.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/aether_cloud.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry cloudMain -stage compute -O3 -o "${GEN}/aether_cloud.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc aether_far:cloudMain -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/aether_cloud_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/aether_cloud.spv -DOUT=${GEN}/aether_cloud_spv.h -DNAME=aether_cloud_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/aether_cloud.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/aether_sunmap.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry sunMapMain -stage compute -O3 -o "${GEN}/aether_sunmap.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc aether_far:sunMapMain -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/aether_sunmap_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/aether_sunmap.spv -DOUT=${GEN}/aether_sunmap_spv.h -DNAME=aether_sunmap_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/aether_sunmap.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/aether_publish.spv"
  COMMAND "${SLANGC}" "${CMAKE_SOURCE_DIR}/shaders/aether_publish.slang" -target spirv -entry publishMain -stage compute -O3
          -o "${GEN}/aether_publish.spv"
  DEPENDS "${CMAKE_SOURCE_DIR}/shaders/aether_publish.slang" COMMENT "slangc aether_publish -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/aether_publish_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/aether_publish.spv -DOUT=${GEN}/aether_publish_spv.h -DNAME=aether_publish_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/aether_publish.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/aether_ingest.spv"
  COMMAND "${SLANGC}" "${CMAKE_SOURCE_DIR}/shaders/aether_ingest.slang" -target spirv -entry ingestMain -stage compute -O3
          -o "${GEN}/aether_ingest.spv"
  DEPENDS "${CMAKE_SOURCE_DIR}/shaders/aether_ingest.slang" COMMENT "slangc aether_ingest -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/aether_ingest_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/aether_ingest.spv -DOUT=${GEN}/aether_ingest_spv.h -DNAME=aether_ingest_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/aether_ingest.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/aether_far_cpu.cpp"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target cpp -entry farMain -stage compute -entry cloudMain -stage compute -entry sunMapMain -stage compute -O3
          -o "${GEN}/aether_far_cpu.cpp"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc aether_far -> C++ (CPU reference)")

add_library(aether_core SHARED
  src/api.cpp src/svdag.cpp src/world.cpp src/preview_source.cpp src/cpu_backend.cpp src/vk_backend.cpp src/gl_api.cpp
  "${GEN}/aether_far_cpu.cpp" "${GEN}/aether_far_spv.h" "${GEN}/aether_publish_spv.h" "${GEN}/aether_ingest_spv.h"
  "${GEN}/aether_cloud_spv.h" "${GEN}/aether_sunmap_spv.h")
target_include_directories(aether_core PUBLIC include PRIVATE src "${GEN}" "${SLANG_ROOT}/include" "${VULKAN_SDK}/Include")
target_compile_definitions(aether_core PRIVATE AETHER_BUILD AETHER_VERSION_STRING="${PROJECT_VERSION}")
target_link_libraries(aether_core PRIVATE "${VULKAN_SDK}/Lib/vulkan-1.lib" opengl32)
# The generated kernel is plain arithmetic; keep MSVC from contracting differently than the GPU where it can.
set_source_files_properties("${GEN}/aether_far_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:precise;/W0")

add_executable(aether_render tools/aether_render.cpp)
target_link_libraries(aether_render PRIVATE aether_core)
target_include_directories(aether_render PRIVATE src)

add_executable(aether_tests tests/test_main.cpp src/svdag.cpp)
target_include_directories(aether_tests PRIVATE src include)
target_link_libraries(aether_tests PRIVATE aether_core)

add_executable(aether_gl_test tests/gl_interop_test.cpp src/gl_api.cpp)
target_include_directories(aether_gl_test PRIVATE src include)
target_link_libraries(aether_gl_test PRIVATE aether_core opengl32 gdi32 user32)

add_executable(aether_pack_test tests/pack_parity_test.cpp src/gl_api.cpp)
target_include_directories(aether_pack_test PRIVATE src include)
target_link_libraries(aether_pack_test PRIVATE aether_core opengl32 gdi32 user32)

add_executable(aether_bench tools/aether_bench.cpp)
target_include_directories(aether_bench PRIVATE src include)
target_link_libraries(aether_bench PRIVATE aether_core opengl32 gdi32 user32)

# Exact terrain height (aether_height.slang): precise floating point, then spv_exact adds the float controls and
# refuses the build unless every arithmetic instruction is NoContraction and no inexact extended instruction is used.
add_executable(spv_exact tools/spv_exact.cpp)
set(HEIGHT_SRC "${CMAKE_SOURCE_DIR}/shaders/aether_height.slang")
find_program(SPIRV_VAL spirv-val HINTS "${VULKAN_SDK}/Bin" NO_DEFAULT_PATH)
add_custom_command(
  OUTPUT "${GEN}/aether_height.spv"
  COMMAND "${SLANGC}" "${HEIGHT_SRC}" -target spirv -entry heightMain -stage compute -O3 -fp-mode precise
          -o "${GEN}/aether_height_raw.spv"
  COMMAND spv_exact "${GEN}/aether_height_raw.spv" "${GEN}/aether_height.spv"
  COMMAND "${SPIRV_VAL}" --target-env vulkan1.2 "${GEN}/aether_height.spv"
  DEPENDS "${HEIGHT_SRC}" spv_exact COMMENT "slangc aether_height -> SPIR-V (exact float contract)")
add_custom_command(
  OUTPUT "${GEN}/aether_height_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/aether_height.spv -DOUT=${GEN}/aether_height_spv.h -DNAME=aether_height_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/aether_height.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/aether_height_cpu.cpp"
  COMMAND "${SLANGC}" "${HEIGHT_SRC}" -target cpp -entry heightMain -stage compute -entry divTestMain -stage compute -O3 -fp-mode precise
          -o "${GEN}/aether_height_cpu.cpp"
  DEPENDS "${HEIGHT_SRC}" COMMENT "slangc aether_height -> C++ (CPU reference)")
target_sources(aether_core PRIVATE src/height_engine.cpp "${GEN}/aether_height_cpu.cpp" "${GEN}/aether_height_spv.h")
# x64 SSE2 scalar arithmetic is IEEE binary32/64; /fp:strict also forbids contraction and keeps the C++ reference's
# float operations literal
set_source_files_properties("${GEN}/aether_height_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:strict;/W0")

add_executable(aether_height_test tests/height_test.cpp)
target_include_directories(aether_height_test PRIVATE include)
target_link_libraries(aether_height_test PRIVATE aether_core)

enable_testing()
add_test(NAME aether_tests COMMAND aether_tests WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}")

# offline lab for the shader pack's screen passes over an aether-rendered scene (Windows, GL compatibility context)
if(WIN32)
  add_executable(pack_lab tools/pack_lab.cpp)
  target_link_libraries(pack_lab PRIVATE aether_core opengl32)
  target_include_directories(pack_lab PRIVATE src)
endif()
