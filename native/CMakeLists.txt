cmake_minimum_required(VERSION 3.20)
project(aether_core VERSION 0.1.0 LANGUAGES CXX)

# Toolchains live outside the repo (see native/README.md); env.ps1 sets these on Windows, build_linux.sh on Linux.
set(SLANG_ROOT "$ENV{SLANG_ROOT}" CACHE PATH "Slang release root")
set(VULKAN_SDK "$ENV{VULKAN_SDK}" CACHE PATH "Vulkan SDK root (Windows; optional on Linux)")
set(VULKAN_HEADERS "$ENV{VULKAN_HEADERS}" CACHE PATH "Vulkan-Headers checkout (Linux, when no system vulkan/vulkan.h)")
if(NOT EXISTS "${SLANG_ROOT}/bin/slangc.exe" AND NOT EXISTS "${SLANG_ROOT}/bin/slangc")
  message(FATAL_ERROR "SLANG_ROOT does not point at a Slang release (run toolchains/env.ps1 or build_linux.sh)")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(MSVC)
  add_compile_options(/W3 /permissive- /bigobj /utf-8 /Zc:__cplusplus)
  add_compile_definitions(_CRT_SECURE_NO_WARNINGS)  # getenv / fopen are fine here (and portable)
else()
  # GCC / Clang (Linux): export only AETHER_API symbols, like the DLL
  add_compile_options(-Wall -Wno-unused-function -Wno-unused-variable -Wno-missing-braces)
  set(CMAKE_CXX_VISIBILITY_PRESET hidden)
  set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
  set(CMAKE_POSITION_INDEPENDENT_CODE ON)
endif()
find_package(Threads REQUIRED)

# Vulkan: headers only. The loader (vulkan-1.dll / libvulkan.so.1) is opened at run time (src/vk_loader.cpp), so the
# library has no link-time Vulkan dependency. Linux headers: the system's, a Vulkan-Headers checkout, or an SDK.
if(WIN32)
  set(AETHER_VK_INCLUDE "${VULKAN_SDK}/Include")
  set(AETHER_GL_LIBS opengl32)
  set(AETHER_TEST_GL_LIBS opengl32 gdi32 user32)
else()
  find_path(AETHER_VK_INCLUDE vulkan/vulkan.h HINTS "${VULKAN_HEADERS}/include" "${VULKAN_SDK}/include" "${VULKAN_SDK}/Include")
  if(NOT AETHER_VK_INCLUDE)
    message(FATAL_ERROR "Vulkan headers not found (set VULKAN_HEADERS to a Vulkan-Headers checkout)")
  endif()
  set(AETHER_GL_LIBS ${CMAKE_DL_LIBS})  # GL is resolved at run time from the caller's EGL / GLX context
  set(AETHER_TEST_GL_LIBS ${CMAKE_DL_LIBS})
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
  src/api.cpp src/svdag.cpp src/world.cpp src/preview_source.cpp src/cpu_backend.cpp src/vk_backend.cpp src/vk_loader.cpp
  src/gl_api.cpp
  "${GEN}/aether_far_cpu.cpp" "${GEN}/aether_far_spv.h" "${GEN}/aether_publish_spv.h" "${GEN}/aether_ingest_spv.h"
  "${GEN}/aether_cloud_spv.h" "${GEN}/aether_sunmap_spv.h")
target_include_directories(aether_core PUBLIC include PRIVATE src "${GEN}" "${SLANG_ROOT}/include" "${AETHER_VK_INCLUDE}")
target_compile_definitions(aether_core PRIVATE AETHER_BUILD AETHER_VERSION_STRING="${PROJECT_VERSION}" VK_NO_PROTOTYPES)
target_link_libraries(aether_core PRIVATE ${AETHER_GL_LIBS} Threads::Threads)
if(NOT MSVC)
  # one .so for every distro: the C++ runtime inside (a container-built library loads in the game's JVM on the host,
  # whatever libstdc++ that has), none of its symbols exported, and no undefined symbol left for load time
  option(AETHER_STATIC_RUNTIME "link libstdc++ / libgcc into libaether_core.so" ON)
  if(AETHER_STATIC_RUNTIME)
    target_link_options(aether_core PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL)
  endif()
  target_link_options(aether_core PRIVATE -Wl,--no-undefined)
  # the shipped Release library: no static symbol table or DWARF (the linked-in C++ runtime brings megabytes of it);
  # the exported AETHER_API symbols are dynamic and stay
  target_link_options(aether_core PRIVATE $<$<CONFIG:Release>:-s>)
  # and nothing but the C API in the dynamic symbol table (hidden visibility misses the generated kernels' exports)
  target_link_options(aether_core PRIVATE "-Wl,--version-script=${CMAKE_SOURCE_DIR}/cmake/aether_core.map")
  set_property(TARGET aether_core APPEND PROPERTY LINK_DEPENDS "${CMAKE_SOURCE_DIR}/cmake/aether_core.map")
endif()
# The generated kernel is plain arithmetic; keep MSVC from contracting differently than the GPU where it can.
if(MSVC)
  set_source_files_properties("${GEN}/aether_far_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:precise;/W0")
else()
  # no FMA contraction (the GPU and MSVC builds do not contract either), no warnings from generated code
  set_source_files_properties("${GEN}/aether_far_cpu.cpp" PROPERTIES COMPILE_OPTIONS "-ffp-contract=off;-fno-fast-math;-w")
endif()

add_executable(aether_render tools/aether_render.cpp)
target_link_libraries(aether_render PRIVATE aether_core)
target_include_directories(aether_render PRIVATE src)

add_executable(aether_tests tests/test_main.cpp src/svdag.cpp)
target_include_directories(aether_tests PRIVATE src include)
target_link_libraries(aether_tests PRIVATE aether_core Threads::Threads)

add_executable(aether_gl_test tests/gl_interop_test.cpp src/gl_api.cpp)
target_include_directories(aether_gl_test PRIVATE src include)
target_include_directories(aether_gl_test PRIVATE tests)
target_link_libraries(aether_gl_test PRIVATE aether_core ${AETHER_TEST_GL_LIBS} Threads::Threads)

add_executable(aether_pack_test tests/pack_parity_test.cpp src/gl_api.cpp)
target_include_directories(aether_pack_test PRIVATE src include)
target_include_directories(aether_pack_test PRIVATE tests)
target_link_libraries(aether_pack_test PRIVATE aether_core ${AETHER_TEST_GL_LIBS})

add_executable(aether_bench tools/aether_bench.cpp)
target_include_directories(aether_bench PRIVATE src include)
target_include_directories(aether_bench PRIVATE tests)
target_link_libraries(aether_bench PRIVATE aether_core ${AETHER_TEST_GL_LIBS})

# Exact terrain height (aether_height.slang): precise floating point, then spv_exact adds the float controls and
# refuses the build unless every arithmetic instruction is NoContraction and no inexact extended instruction is used.
add_executable(spv_exact tools/spv_exact.cpp)
set(HEIGHT_SRC "${CMAKE_SOURCE_DIR}/shaders/aether_height.slang")
# spirv-val: the SDK's on Windows; on Linux the SDK's, else PATH (spirv-tools / SPIRV-Tools package). spv_exact checks
# the float contract itself; the validator is an extra structural check, skipped with a warning when absent on Linux.
if(WIN32)
  find_program(SPIRV_VAL spirv-val HINTS "${VULKAN_SDK}/Bin" NO_DEFAULT_PATH)
else()
  find_program(SPIRV_VAL spirv-val HINTS "${VULKAN_SDK}/bin" "${VULKAN_SDK}/Bin" "${VULKAN_HEADERS}/bin")
endif()
set(HEIGHT_VALIDATE)
if(SPIRV_VAL)
  set(HEIGHT_VALIDATE COMMAND "${SPIRV_VAL}" --target-env vulkan1.2 "${GEN}/aether_height.spv")
elseif(WIN32)
  message(FATAL_ERROR "spirv-val not found in ${VULKAN_SDK}/Bin")
else()
  message(WARNING "spirv-val not found (install spirv-tools): aether_height.spv is checked by spv_exact only")
endif()
add_custom_command(
  OUTPUT "${GEN}/aether_height.spv"
  COMMAND "${SLANGC}" "${HEIGHT_SRC}" -target spirv -entry heightMain -stage compute -O3 -fp-mode precise
          -o "${GEN}/aether_height_raw.spv"
  COMMAND spv_exact "${GEN}/aether_height_raw.spv" "${GEN}/aether_height.spv"
  ${HEIGHT_VALIDATE}
  DEPENDS "${HEIGHT_SRC}" spv_exact COMMENT "slangc aether_height -> SPIR-V (exact float contract)")
add_custom_command(
  OUTPUT "${GEN}/aether_height_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/aether_height.spv -DOUT=${GEN}/aether_height_spv.h -DNAME=aether_height_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/aether_height.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/aether_height_cpu.cpp"
  COMMAND "${SLANGC}" "${HEIGHT_SRC}" -target cpp -entry heightMain -stage compute -O3 -fp-mode precise
          -o "${GEN}/aether_height_cpu.cpp"
  DEPENDS "${HEIGHT_SRC}" COMMENT "slangc aether_height -> C++ (CPU reference)")
target_sources(aether_core PRIVATE src/height_engine.cpp "${GEN}/aether_height_cpu.cpp" "${GEN}/aether_height_spv.h")
# x64 SSE2 scalar arithmetic is IEEE binary32/64; /fp:strict (MSVC) and -ffp-contract=off -fno-fast-math (GCC/Clang)
# forbid contraction and keep the C++ reference's float operations literal
if(MSVC)
  set_source_files_properties("${GEN}/aether_height_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:strict;/W0")
else()
  set_source_files_properties("${GEN}/aether_height_cpu.cpp" PROPERTIES COMPILE_OPTIONS
                              "-ffp-contract=off;-fno-fast-math;-w")
endif()

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
