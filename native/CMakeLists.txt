cmake_minimum_required(VERSION 3.20)
project(atlas_core VERSION 0.1.0 LANGUAGES CXX)

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
  # GCC / Clang (Linux): export only ATLAS_API symbols, like the DLL
  add_compile_options(-Wall -Wno-unused-function -Wno-unused-variable -Wno-missing-braces)
  set(CMAKE_CXX_VISIBILITY_PRESET hidden)
  set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
  set(CMAKE_POSITION_INDEPENDENT_CODE ON)
endif()
find_package(Threads REQUIRED)

# Vulkan: headers only. The loader (vulkan-1.dll / libvulkan.so.1) is opened at run time (src/vk_loader.cpp), so the
# library has no link-time Vulkan dependency. Linux headers: the system's, a Vulkan-Headers checkout, or an SDK.
if(WIN32)
  set(ATLAS_VK_INCLUDE "${VULKAN_SDK}/Include")
  set(ATLAS_GL_LIBS opengl32)
  set(ATLAS_TEST_GL_LIBS opengl32 gdi32 user32)
else()
  find_path(ATLAS_VK_INCLUDE vulkan/vulkan.h HINTS "${VULKAN_HEADERS}/include" "${VULKAN_SDK}/include" "${VULKAN_SDK}/Include")
  if(NOT ATLAS_VK_INCLUDE)
    message(FATAL_ERROR "Vulkan headers not found (set VULKAN_HEADERS to a Vulkan-Headers checkout)")
  endif()
  set(ATLAS_GL_LIBS ${CMAKE_DL_LIBS})  # GL is resolved at run time from the caller's EGL / GLX context
  set(ATLAS_TEST_GL_LIBS ${CMAKE_DL_LIBS})
endif()

set(GEN "${CMAKE_BINARY_DIR}/gen")
file(MAKE_DIRECTORY "${GEN}")
find_program(SLANGC slangc HINTS "${SLANG_ROOT}/bin" REQUIRED NO_DEFAULT_PATH)
set(KERNEL_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_far.slang")
set(KERNEL_DEPS "${KERNEL_SRC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_shared.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_clouds.slang"
    "${CMAKE_SOURCE_DIR}/shaders/atlas_actors.slang")

# One kernel source, two targets.
add_custom_command(
  OUTPUT "${GEN}/atlas_far.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry farMain -stage compute -O3 -o "${GEN}/atlas_far.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_far_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_far.spv -DOUT=${GEN}/atlas_far_spv.h -DNAME=atlas_far_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_far.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_cloud.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry cloudMain -stage compute -O3 -o "${GEN}/atlas_cloud.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far:cloudMain -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_cloud_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_cloud.spv -DOUT=${GEN}/atlas_cloud_spv.h -DNAME=atlas_cloud_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_cloud.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_sunmap.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry sunMapMain -stage compute -O3 -o "${GEN}/atlas_sunmap.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far:sunMapMain -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_sunmap_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_sunmap.spv -DOUT=${GEN}/atlas_sunmap_spv.h -DNAME=atlas_sunmap_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_sunmap.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_publish.spv"
  COMMAND "${SLANGC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_publish.slang" -target spirv -entry publishMain -stage compute -O3
          -o "${GEN}/atlas_publish.spv"
  DEPENDS "${CMAKE_SOURCE_DIR}/shaders/atlas_publish.slang" COMMENT "slangc atlas_publish -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_publish_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_publish.spv -DOUT=${GEN}/atlas_publish_spv.h -DNAME=atlas_publish_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_publish.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_ingest.spv"
  COMMAND "${SLANGC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_ingest.slang" -target spirv -entry ingestMain -stage compute -O3
          -o "${GEN}/atlas_ingest.spv"
  DEPENDS "${CMAKE_SOURCE_DIR}/shaders/atlas_ingest.slang" COMMENT "slangc atlas_ingest -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_ingest_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_ingest.spv -DOUT=${GEN}/atlas_ingest_spv.h -DNAME=atlas_ingest_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_ingest.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_far_cpu.cpp"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target cpp -entry farMain -stage compute -entry cloudMain -stage compute -entry sunMapMain -stage compute -O3
          -o "${GEN}/atlas_far_cpu.cpp"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far -> C++ (CPU reference)")

add_library(atlas_core SHARED
  src/api.cpp src/svdag.cpp src/world.cpp src/preview_source.cpp src/cpu_backend.cpp src/vk_backend.cpp src/vk_loader.cpp
  src/gl_api.cpp src/lights.cpp
  "${GEN}/atlas_far_cpu.cpp" "${GEN}/atlas_far_spv.h" "${GEN}/atlas_publish_spv.h" "${GEN}/atlas_ingest_spv.h"
  "${GEN}/atlas_cloud_spv.h" "${GEN}/atlas_sunmap_spv.h")
target_include_directories(atlas_core PUBLIC include PRIVATE src "${GEN}" "${SLANG_ROOT}/include" "${ATLAS_VK_INCLUDE}")
target_compile_definitions(atlas_core PRIVATE ATLAS_BUILD ATLAS_VERSION_STRING="${PROJECT_VERSION}" VK_NO_PROTOTYPES)
target_link_libraries(atlas_core PRIVATE ${ATLAS_GL_LIBS} Threads::Threads)
if(NOT MSVC)
  # one .so for every distro: the C++ runtime inside (a container-built library loads in the game's JVM on the host,
  # whatever libstdc++ that has), none of its symbols exported, and no undefined symbol left for load time
  option(ATLAS_STATIC_RUNTIME "link libstdc++ / libgcc into libatlas_core.so" ON)
  if(ATLAS_STATIC_RUNTIME)
    target_link_options(atlas_core PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL)
  endif()
  target_link_options(atlas_core PRIVATE -Wl,--no-undefined)
  # the shipped Release library: no static symbol table or DWARF (the linked-in C++ runtime brings megabytes of it);
  # the exported ATLAS_API symbols are dynamic and stay
  target_link_options(atlas_core PRIVATE $<$<CONFIG:Release>:-s>)
  # and nothing but the C API in the dynamic symbol table (hidden visibility misses the generated kernels' exports)
  target_link_options(atlas_core PRIVATE "-Wl,--version-script=${CMAKE_SOURCE_DIR}/cmake/atlas_core.map")
  set_property(TARGET atlas_core APPEND PROPERTY LINK_DEPENDS "${CMAKE_SOURCE_DIR}/cmake/atlas_core.map")
endif()
# The generated kernel is plain arithmetic; keep MSVC from contracting differently than the GPU where it can.
if(MSVC)
  set_source_files_properties("${GEN}/atlas_far_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:precise;/W0")
else()
  # no FMA contraction (the GPU and MSVC builds do not contract either), no warnings from generated code
  set_source_files_properties("${GEN}/atlas_far_cpu.cpp" PROPERTIES COMPILE_OPTIONS "-ffp-contract=off;-fno-fast-math;-w")
endif()

add_executable(atlas_render tools/atlas_render.cpp)
target_link_libraries(atlas_render PRIVATE atlas_core)
target_include_directories(atlas_render PRIVATE src)

# the far-field experiment bed (docs/ATLAS-LOD.md): CPU only, inputs exported in game with /nirncraft-atlaslab
add_executable(atlas_lab tools/atlas_lab.cpp)
target_include_directories(atlas_lab PRIVATE src)
target_link_libraries(atlas_lab PRIVATE Threads::Threads)  # std::thread: glibc < 2.34 keeps pthread in libpthread

# atlas_lab's two finalists in Slang (docs/ATLAS-LOD.md, "Slang port"): one kernel source for the CPU backend (Slang ->
# C++) and Vulkan (Slang -> SPIR-V, the loader opened at run time); parity against atlas_lab's tracers, timings, memory.
# 41035: slangc cannot see that the traversal stacks are written before they are read.
set(LOD_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_lod.slang")
foreach(rep boxes groups)
  add_custom_command(
    OUTPUT "${GEN}/atlas_lod_${rep}.spv"
    COMMAND "${SLANGC}" "${LOD_SRC}" -target spirv -entry ${rep}Main -stage compute -O3 -warnings-disable 41035
            -o "${GEN}/atlas_lod_${rep}.spv"
    DEPENDS "${LOD_SRC}" COMMENT "slangc atlas_lod:${rep}Main -> SPIR-V")
  add_custom_command(
    OUTPUT "${GEN}/atlas_lod_${rep}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_lod_${rep}.spv -DOUT=${GEN}/atlas_lod_${rep}_spv.h -DNAME=atlas_lod_${rep}_spv
            -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_lod_${rep}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
endforeach()
# precise floats on the CPU: the kernel's arithmetic as literal as atlas_lab's, so the two images agree exactly
add_custom_command(
  OUTPUT "${GEN}/atlas_lod_cpu.cpp"
  COMMAND "${SLANGC}" "${LOD_SRC}" -target cpp -entry boxesMain -stage compute -entry groupsMain -stage compute -O3 -fp-mode precise
          -warnings-disable 41035 -o "${GEN}/atlas_lod_cpu.cpp"
  DEPENDS "${LOD_SRC}" COMMENT "slangc atlas_lod -> C++ (CPU backend)")
add_executable(atlas_lab_gpu tools/atlas_lab_gpu.cpp src/vk_loader.cpp "${GEN}/atlas_lod_cpu.cpp" "${GEN}/atlas_lod_boxes_spv.h"
               "${GEN}/atlas_lod_groups_spv.h")
target_include_directories(atlas_lab_gpu PRIVATE src "${GEN}" "${SLANG_ROOT}/include" "${ATLAS_VK_INCLUDE}")
target_compile_definitions(atlas_lab_gpu PRIVATE VK_NO_PROTOTYPES)
target_link_libraries(atlas_lab_gpu PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
if(MSVC)
  set_source_files_properties("${GEN}/atlas_lod_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:precise;/W0")
else()
  set_source_files_properties("${GEN}/atlas_lod_cpu.cpp" PROPERTIES COMPILE_OPTIONS "-ffp-contract=off;-fno-fast-math;-w")
endif()

# the groups hierarchy built on the GPU (docs/ATLAS-LOD.md, "GPU group build"; atlas_lab_gpu build=gpu): one source, the
# CPU backend's kernels (no atomics or group memory: a serial scan) and Vulkan's (a work-group scan), precise floats on
# both so the SAH picks atlas_lab's planes; host driver tools/atlas_lod_gpubuild.hpp
set(LODB_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_lod_build.slang")
set(LODB_COMMON lbRuns lbErrCount lbErrTask lbErrReduce lbSliceTask lbSliceReduce lbSweep lbRegionInit lbRegionCount
    lbRegionTight lbAdvance lbDescUp lbRootBases lbPosDown lbPack lbRefitBox lbRefitPack)
set(LODB_HEADERS)
foreach(e ${LODB_COMMON} lbScanBlocks lbScanTop lbScanAdd lbErrMax lbSweepWide)
  add_custom_command(
    OUTPUT "${GEN}/atlas_lod_build_${e}.spv"
    COMMAND "${SLANGC}" "${LODB_SRC}" -target spirv -entry ${e} -stage compute -O3 -fp-mode precise -o "${GEN}/atlas_lod_build_${e}.spv"
    DEPENDS "${LODB_SRC}" COMMENT "slangc atlas_lod_build:${e} -> SPIR-V")
  add_custom_command(
    OUTPUT "${GEN}/atlas_lod_build_${e}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_lod_build_${e}.spv -DOUT=${GEN}/atlas_lod_build_${e}_spv.h -DNAME=lodb_${e}_spv
            -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_lod_build_${e}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND LODB_HEADERS "${GEN}/atlas_lod_build_${e}_spv.h")
endforeach()
set(LODB_CPU_ENTRIES)
foreach(e ${LODB_COMMON} lbScanSerial)
  list(APPEND LODB_CPU_ENTRIES -entry ${e} -stage compute)
endforeach()
add_custom_command(
  OUTPUT "${GEN}/atlas_lod_build_cpu.cpp"
  COMMAND "${SLANGC}" "${LODB_SRC}" -target cpp ${LODB_CPU_ENTRIES} -O3 -fp-mode precise -o "${GEN}/atlas_lod_build_cpu.cpp"
  DEPENDS "${LODB_SRC}" COMMENT "slangc atlas_lod_build -> C++ (CPU backend)")
target_sources(atlas_lab_gpu PRIVATE "${GEN}/atlas_lod_build_cpu.cpp" ${LODB_HEADERS})
# two generated kernels in one program: their file-local structs keep their own names
set(LODB_RENAME "GlobalParams_0=LodBuildGlobals_0;KernelContext_0=LodBuildContext_0")
if(MSVC)
  set_source_files_properties("${GEN}/atlas_lod_build_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:precise;/W0"
                              COMPILE_DEFINITIONS "${LODB_RENAME}")
else()
  set_source_files_properties("${GEN}/atlas_lod_build_cpu.cpp" PROPERTIES COMPILE_OPTIONS "-ffp-contract=off;-fno-fast-math;-w"
                              COMPILE_DEFINITIONS "${LODB_RENAME}")
endif()

# the exact reference (docs/ATLAS-LOD.md, ground truth): Minecraft's own chunk mesh traced offline (/nirncraft-atlaslab quads)
add_executable(atlas_truth tools/atlas_truth.cpp)
target_include_directories(atlas_truth PRIVATE src)
target_link_libraries(atlas_truth PRIVATE Threads::Threads)

add_executable(atlas_tests tests/test_main.cpp src/svdag.cpp)
target_include_directories(atlas_tests PRIVATE src include)
target_link_libraries(atlas_tests PRIVATE atlas_core Threads::Threads)

add_executable(atlas_gl_test tests/gl_interop_test.cpp src/gl_api.cpp)
target_include_directories(atlas_gl_test PRIVATE src include)
target_include_directories(atlas_gl_test PRIVATE tests)
target_link_libraries(atlas_gl_test PRIVATE atlas_core ${ATLAS_TEST_GL_LIBS} Threads::Threads)

add_executable(atlas_pack_test tests/pack_parity_test.cpp src/gl_api.cpp)
target_include_directories(atlas_pack_test PRIVATE src include)
target_include_directories(atlas_pack_test PRIVATE tests)
target_link_libraries(atlas_pack_test PRIVATE atlas_core ${ATLAS_TEST_GL_LIBS})

add_executable(atlas_bench tools/atlas_bench.cpp)
target_include_directories(atlas_bench PRIVATE src include)
target_include_directories(atlas_bench PRIVATE tests)
target_link_libraries(atlas_bench PRIVATE atlas_core ${ATLAS_TEST_GL_LIBS})

# Exact terrain height (atlas_height.slang): precise floating point, then spv_exact adds the float controls and
# refuses the build unless every arithmetic instruction is NoContraction and no inexact extended instruction is used.
add_executable(spv_exact tools/spv_exact.cpp)
set(HEIGHT_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_height.slang")
# spirv-val: the SDK's on Windows; on Linux the SDK's, else PATH (spirv-tools / SPIRV-Tools package). spv_exact checks
# the float contract itself; the validator is an extra structural check, skipped with a warning when absent on Linux.
if(WIN32)
  find_program(SPIRV_VAL spirv-val HINTS "${VULKAN_SDK}/Bin" NO_DEFAULT_PATH)
else()
  find_program(SPIRV_VAL spirv-val HINTS "${VULKAN_SDK}/bin" "${VULKAN_SDK}/Bin" "${VULKAN_HEADERS}/bin")
endif()
set(HEIGHT_VALIDATE)
if(SPIRV_VAL)
  set(HEIGHT_VALIDATE COMMAND "${SPIRV_VAL}" --target-env vulkan1.2 "${GEN}/atlas_height.spv")
elseif(WIN32)
  message(FATAL_ERROR "spirv-val not found in ${VULKAN_SDK}/Bin")
else()
  message(WARNING "spirv-val not found (install spirv-tools): atlas_height.spv is checked by spv_exact only")
endif()
add_custom_command(
  OUTPUT "${GEN}/atlas_height.spv"
  COMMAND "${SLANGC}" "${HEIGHT_SRC}" -target spirv -entry heightMain -stage compute -O3 -fp-mode precise
          -o "${GEN}/atlas_height_raw.spv"
  COMMAND spv_exact "${GEN}/atlas_height_raw.spv" "${GEN}/atlas_height.spv"
  ${HEIGHT_VALIDATE}
  DEPENDS "${HEIGHT_SRC}" spv_exact COMMENT "slangc atlas_height -> SPIR-V (exact float contract)")
add_custom_command(
  OUTPUT "${GEN}/atlas_height_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_height.spv -DOUT=${GEN}/atlas_height_spv.h -DNAME=atlas_height_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_height.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_height_cpu.cpp"
  COMMAND "${SLANGC}" "${HEIGHT_SRC}" -target cpp -entry heightMain -stage compute -O3 -fp-mode precise
          -o "${GEN}/atlas_height_cpu.cpp"
  DEPENDS "${HEIGHT_SRC}" COMMENT "slangc atlas_height -> C++ (CPU reference)")
target_sources(atlas_core PRIVATE src/height_engine.cpp "${GEN}/atlas_height_cpu.cpp" "${GEN}/atlas_height_spv.h")
# x64 SSE2 scalar arithmetic is IEEE binary32/64; /fp:strict (MSVC) and -ffp-contract=off -fno-fast-math (GCC/Clang)
# forbid contraction and keep the C++ reference's float operations literal
if(MSVC)
  set_source_files_properties("${GEN}/atlas_height_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:strict;/W0")
else()
  set_source_files_properties("${GEN}/atlas_height_cpu.cpp" PROPERTIES COMPILE_OPTIONS
                              "-ffp-contract=off;-fno-fast-math;-w")
endif()

add_executable(atlas_height_test tests/height_test.cpp)
target_include_directories(atlas_height_test PRIVATE include)
target_link_libraries(atlas_height_test PRIVATE atlas_core)

enable_testing()
add_test(NAME atlas_tests COMMAND atlas_tests WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}")

# offline lab for the shader pack's screen passes over an atlas-rendered scene (Windows, GL compatibility context)
if(WIN32)
  add_executable(pack_lab tools/pack_lab.cpp)
  target_link_libraries(pack_lab PRIVATE atlas_core opengl32)
  target_include_directories(pack_lab PRIVATE src)
endif()
