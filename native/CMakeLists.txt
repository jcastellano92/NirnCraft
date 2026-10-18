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
set(KERNEL_DEPS "${KERNEL_SRC}" "${CMAKE_SOURCE_DIR}/shaders/aether_shared.slang" "${CMAKE_SOURCE_DIR}/shaders/aether_clouds.slang")

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
  OUTPUT "${GEN}/aether_far_cpu.cpp"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target cpp -entry farMain -stage compute -O3 -o "${GEN}/aether_far_cpu.cpp"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc aether_far -> C++ (CPU reference)")

add_library(aether_core SHARED
  src/api.cpp src/svdag.cpp src/world.cpp src/preview_source.cpp src/cpu_backend.cpp src/vk_backend.cpp src/gl_api.cpp
  "${GEN}/aether_far_cpu.cpp" "${GEN}/aether_far_spv.h" "${GEN}/aether_publish_spv.h")
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

enable_testing()
add_test(NAME aether_tests COMMAND aether_tests WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}")
