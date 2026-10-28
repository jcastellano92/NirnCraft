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
    "${CMAKE_SOURCE_DIR}/shaders/atlas_actors.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_groups.slang"
    "${CMAKE_SOURCE_DIR}/shaders/atlas_air.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_bricks.slang")

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
# farMain again for the unified field's split frames alone (the game's): every trace inlined into one function
# (ATLAS_FAR_SPLIT, atlas_far.slang; ~50 s, ~11 GB to compile)
add_custom_command(
  OUTPUT "${GEN}/atlas_farsplit.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry farMain -stage compute -O3 -DATLAS_FAR_SPLIT=1 -o "${GEN}/atlas_farsplit.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far (split frames) -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_farsplit_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_farsplit.spv -DOUT=${GEN}/atlas_farsplit_spv.h -DNAME=atlas_farsplit_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_farsplit.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
# the shade side of the split frames' far pass: farMain drawing from the hit buffer (ATLAS_FAR_PASS 2, atlas_far.slang)
add_custom_command(
  OUTPUT "${GEN}/atlas_farshade.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry farMain -stage compute -O3 -DATLAS_FAR_SPLIT=1 -DATLAS_FAR_PASS=2
          -o "${GEN}/atlas_farshade.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far (split frames, the shade) -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_farshade_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_farshade.spv -DOUT=${GEN}/atlas_farshade_spv.h -DNAME=atlas_farshade_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_farshade.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
# the lean trace stage (shaders/stage_trace.slang, docs/ATLAS-RENDER-BLUEPRINT.md step 6), its clock build and its water
# pass (ATLAS_LEAN_PASS 1: the trace behind each water surface) and refine pass (2), on the primary walk's work lists
set(STAGE_TRACE_SRC "${CMAKE_SOURCE_DIR}/shaders/stage_trace.slang")
foreach(lp "stage_trace:0:0" "stage_trace_clock:1:0" "stage_trace_water:0:1" "stage_trace_refine:0:2")
  string(REPLACE ":" ";" lpl "${lp}")
  list(GET lpl 0 lname)
  list(GET lpl 1 lclock)
  list(GET lpl 2 lpass)
  add_custom_command(
    OUTPUT "${GEN}/${lname}.spv"
    COMMAND "${SLANGC}" "${STAGE_TRACE_SRC}" -target spirv -entry traceMain -stage compute -O3 -DATLAS_LEAN_CLOCK=${lclock}
            -DATLAS_LEAN_PASS=${lpass} -o "${GEN}/${lname}.spv"
    DEPENDS "${STAGE_TRACE_SRC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_shared.slang" COMMENT "slangc ${lname} -> SPIR-V")
  add_custom_command(
    OUTPUT "${GEN}/${lname}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/${lname}.spv -DOUT=${GEN}/${lname}_spv.h -DNAME=${lname}_spv
            -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/${lname}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
endforeach()
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
  OUTPUT "${GEN}/atlas_nearlight.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry nearLightMain -stage compute -O3 -o "${GEN}/atlas_nearlight.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far:nearLightMain -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_nearlight_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_nearlight.spv -DOUT=${GEN}/atlas_nearlight_spv.h -DNAME=atlas_nearlight_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_nearlight.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_nearup.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry nearUpsampleMain -stage compute -O3 -o "${GEN}/atlas_nearup.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far:nearUpsampleMain -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_nearup_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_nearup.spv -DOUT=${GEN}/atlas_nearup_spv.h -DNAME=atlas_nearup_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_nearup.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
add_custom_command(
  OUTPUT "${GEN}/atlas_neartemporal.spv"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry nearTemporalMain -stage compute -O3 -o "${GEN}/atlas_neartemporal.spv"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far:nearTemporalMain -> SPIR-V")
add_custom_command(
  OUTPUT "${GEN}/atlas_neartemporal_spv.h"
  COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_neartemporal.spv -DOUT=${GEN}/atlas_neartemporal_spv.h -DNAME=atlas_neartemporal_spv
          -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
  DEPENDS "${GEN}/atlas_neartemporal.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
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
# the level-0 grid's build kernels (atlas_voxgrid.slang): one SPIR-V module per entry point
set(VG_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_voxgrid.slang")
set(VG_HEADERS "")
foreach(pair "section:vgSectionMain" "satx:vgSatX" "satz:vgSatZ" "saty:vgSatY" "grow:vgGrowMain" "fill:vgFillMain" "ingest:vgIngestMain" "down:vgDownMain"
             "pack:vgPackMain" "unpack:vgUnpackMain" "cand:vgCandMain" "rank:vgRankMain" "rankcut:vgRankCutMain"
             "ranklist:vgRankListMain")
  string(REPLACE ":" ";" kv "${pair}")
  list(GET kv 0 short)
  list(GET kv 1 entry)
  add_custom_command(
    OUTPUT "${GEN}/atlas_vg_${short}.spv"
    COMMAND "${SLANGC}" "${VG_SRC}" -target spirv -entry ${entry} -stage compute -O3 -o "${GEN}/atlas_vg_${short}.spv"
    DEPENDS "${VG_SRC}" COMMENT "slangc atlas_voxgrid:${entry} -> SPIR-V")
  add_custom_command(
    OUTPUT "${GEN}/atlas_vg_${short}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_vg_${short}.spv -DOUT=${GEN}/atlas_vg_${short}_spv.h -DNAME=atlas_vg_${short}_spv
            -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_vg_${short}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND VG_HEADERS "${GEN}/atlas_vg_${short}_spv.h")
endforeach()
add_custom_command(
  OUTPUT "${GEN}/atlas_far_cpu.cpp"
  COMMAND "${SLANGC}" "${KERNEL_SRC}" -target cpp -entry farMain -stage compute -entry cloudMain -stage compute -entry sunMapMain -stage compute -O3
          -o "${GEN}/atlas_far_cpu.cpp"
  DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far -> C++ (CPU reference)")

# more entry points of the far kernel's module, with its bindings (the far lights on the GPU)
set(FAR_EXTRA_HEADERS "")
foreach(pair "lightCullMain;lightcull" "lightAdmitMain;lightadmit" "lightBinMain;lightbin" "gridLightMain;gridlight")
  list(GET pair 0 entry)
  list(GET pair 1 short)
  add_custom_command(
    OUTPUT "${GEN}/atlas_${short}.spv"
    COMMAND "${SLANGC}" "${KERNEL_SRC}" -target spirv -entry ${entry} -stage compute -O3 -o "${GEN}/atlas_${short}.spv"
    DEPENDS ${KERNEL_DEPS} COMMENT "slangc atlas_far:${entry} -> SPIR-V")
  add_custom_command(
    OUTPUT "${GEN}/atlas_${short}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_${short}.spv -DOUT=${GEN}/atlas_${short}_spv.h -DNAME=atlas_${short}_spv
            -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_${short}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND FAR_EXTRA_HEADERS "${GEN}/atlas_${short}_spv.h")
endforeach()
# the build fingerprint of the caches (cmake/buildhash.cmake): every build, rewritten only when the sources changed
add_custom_target(atlas_build_hash ALL
  COMMAND "${CMAKE_COMMAND}" -DROOT=${CMAKE_SOURCE_DIR} -DOUT=${GEN}/atlas_build_hash.h -P "${CMAKE_SOURCE_DIR}/cmake/buildhash.cmake"
  BYPRODUCTS "${GEN}/atlas_build_hash.h" COMMENT "the native build hash")
add_library(atlas_core SHARED
  src/api.cpp src/svdag.cpp src/world.cpp src/groups.cpp src/preview_source.cpp src/cpu_backend.cpp src/vk_backend.cpp src/vk_loader.cpp
  src/lights.cpp src/voxgrid.cpp src/sectionstore.cpp src/gencache.cpp src/bricks.cpp
  "${GEN}/atlas_far_cpu.cpp" "${GEN}/atlas_far_spv.h" "${GEN}/atlas_farsplit_spv.h" "${GEN}/stage_trace_spv.h" "${GEN}/stage_trace_clock_spv.h" "${GEN}/stage_trace_water_spv.h" "${GEN}/stage_trace_refine_spv.h" "${GEN}/atlas_farshade_spv.h" "${GEN}/atlas_publish_spv.h" "${GEN}/atlas_ingest_spv.h"
  "${GEN}/atlas_cloud_spv.h" "${GEN}/atlas_sunmap_spv.h" "${GEN}/atlas_nearlight_spv.h" "${GEN}/atlas_nearup_spv.h"
  "${GEN}/atlas_neartemporal_spv.h" ${VG_HEADERS} ${FAR_EXTRA_HEADERS})
target_include_directories(atlas_core PUBLIC include PRIVATE src "${GEN}" "${SLANG_ROOT}/include" "${ATLAS_VK_INCLUDE}")
add_dependencies(atlas_core atlas_build_hash)
target_compile_definitions(atlas_core PRIVATE ATLAS_BUILD ATLAS_VERSION_STRING="${PROJECT_VERSION}" VK_NO_PROTOTYPES)
target_link_libraries(atlas_core PRIVATE ${CMAKE_DL_LIBS} Threads::Threads)
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
    lbRegionTight lbAdvance lbDescUp lbRootBases lbPosDown lbPack lbRefitBox lbRefitPack lbOctLeaf lbOctUp lbOctList
    lbOctErr lbOctRoot lbOctPos lbOctPack)
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

# Disk-cache recovery needs no renderer, shader compile, or GPU context.
add_executable(atlas_cache_test tests/cache_test.cpp src/gencache.cpp)
target_include_directories(atlas_cache_test PRIVATE src include)
target_link_libraries(atlas_cache_test PRIVATE Threads::Threads)
add_executable(atlas_cache_lifetime_test tests/cache_lifetime_test.cpp src/gencache.cpp)
target_include_directories(atlas_cache_lifetime_test PRIVATE src include)
target_link_libraries(atlas_cache_lifetime_test PRIVATE Threads::Threads)
if(NOT MSVC)
  # The standard root->unprivileged runner cannot rely on a compiler owner's private runtime directory.
  target_link_options(atlas_cache_lifetime_test PRIVATE -static-libstdc++ -static-libgcc)
endif()

# Host lock/snapshot regression: real terrain bookkeeping, no renderer, generated shaders or GPU context.
add_executable(atlas_frame_lock_test tests/frame_lock_test.cpp src/world.cpp src/voxgrid.cpp src/sectionstore.cpp
  src/gencache.cpp src/groups.cpp src/svdag.cpp)
target_include_directories(atlas_frame_lock_test PRIVATE src include "${ATLAS_VK_INCLUDE}")
target_link_libraries(atlas_frame_lock_test PRIVATE Threads::Threads)

add_executable(atlas_pack_test tests/pack_parity_test.cpp)
add_executable(atlas_vk_test tests/vk_backend_test.cpp)
target_include_directories(atlas_vk_test PRIVATE src include "${ATLAS_VK_INCLUDE}")
target_link_libraries(atlas_vk_test PRIVATE atlas_core Threads::Threads ${CMAKE_DL_LIBS})
add_executable(atlas_close_admission_test tests/close_admission_test.cpp)
target_include_directories(atlas_close_admission_test PRIVATE src include "${ATLAS_VK_INCLUDE}")
target_link_libraries(atlas_close_admission_test PRIVATE Threads::Threads)
target_include_directories(atlas_pack_test PRIVATE src include)
target_include_directories(atlas_pack_test PRIVATE tests)
target_link_libraries(atlas_pack_test PRIVATE atlas_core ${ATLAS_TEST_GL_LIBS})

add_executable(atlas_bench tools/atlas_bench.cpp)
target_include_directories(atlas_bench PRIVATE src include)
target_include_directories(atlas_bench PRIVATE tests)
target_link_libraries(atlas_bench PRIVATE atlas_core)

# Exact terrain height (atlas_height.slang): precise floating point, then spv_exact adds the float controls and
# refuses the build unless every arithmetic instruction is NoContraction and no inexact extended instruction is used.
add_executable(spv_exact tools/spv_exact.cpp)
# The kernels' budget (shaders/kernel_budget.json), a ratchet: spv_budget checks every built module's SPIR-V after
# atlas_core links (no GPU: instructions, thread-private statics, groupshared, struct-passing calls, function-scope
# arrays), and the Vulkan backend checks the driver's pipeline statistics against its "pipe" part (ATLAS_PIPE_BUDGET)
add_executable(spv_budget tools/spv_budget.cpp)
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

# The client's world generator (atlas_gen.slang, docs/ATLAS-CLIENT-GENERATION.md): the same exact float contract,
# one SPIR-V module per pass, and the C++ reference (its generated structs renamed: one library, several kernels)
set(GEN_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_gen.slang")
set(GEN_HEADERS)
set(GEN_CPU_ENTRIES)
foreach(pass corner aquifer fill top surface pack cols secs mats math probe pageCols pageBiomes tile tileSurf tileBiome farChunk)
  set(entry "${pass}Main")
  set(GEN_VALIDATE)
  if(SPIRV_VAL)
    set(GEN_VALIDATE COMMAND "${SPIRV_VAL}" --target-env vulkan1.2 "${GEN}/atlas_gen_${pass}.spv")
  endif()
  add_custom_command(
    OUTPUT "${GEN}/atlas_gen_${pass}.spv"
    COMMAND "${SLANGC}" "${GEN_SRC}" -target spirv -entry ${entry} -stage compute -O3 -fp-mode precise
            -warnings-disable 41035 -o "${GEN}/atlas_gen_${pass}_raw.spv"
    COMMAND spv_exact "${GEN}/atlas_gen_${pass}_raw.spv" "${GEN}/atlas_gen_${pass}.spv"
    ${GEN_VALIDATE}
    DEPENDS "${GEN_SRC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_exact.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_map.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_page.slang" spv_exact COMMENT "slangc atlas_gen:${entry} -> SPIR-V (exact float contract)")
  add_custom_command(
    OUTPUT "${GEN}/atlas_gen_${pass}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_gen_${pass}.spv -DOUT=${GEN}/atlas_gen_${pass}_spv.h
            -DNAME=atlas_gen_${pass}_spv -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_gen_${pass}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND GEN_HEADERS "${GEN}/atlas_gen_${pass}_spv.h")
  # (GPU-only passes stay out of the C++ reference: its target has no atomics)
  if(NOT pass STREQUAL "farChunk")
    list(APPEND GEN_CPU_ENTRIES -entry ${entry} -stage compute)
  endif()
endforeach()
add_custom_command(
  OUTPUT "${GEN}/atlas_gen_cpu.cpp"
  COMMAND "${SLANGC}" "${GEN_SRC}" -target cpp ${GEN_CPU_ENTRIES} -O3 -fp-mode precise -warnings-disable 41035
          -o "${GEN}/atlas_gen_cpu.cpp"
  DEPENDS "${GEN_SRC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_exact.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_map.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_page.slang" COMMENT "slangc atlas_gen -> C++ (CPU reference)")
# the generator's chunk store (atlas_store.slang: a chunk into its slot and back, the map's region and elevation per
# column; -O0 like the features)
foreach(pass storeIn storeOut)
  add_custom_command(
    OUTPUT "${GEN}/atlas_store_${pass}.spv"
    COMMAND "${SLANGC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_store.slang" -target spirv -entry ${pass}Main -stage compute -O0
            -fp-mode precise -warnings-disable 41035 -o "${GEN}/atlas_store_${pass}_raw.spv"
    COMMAND spv_exact "${GEN}/atlas_store_${pass}_raw.spv" "${GEN}/atlas_store_${pass}.spv"
    DEPENDS "${CMAKE_SOURCE_DIR}/shaders/atlas_store.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_exact.slang"
            "${CMAKE_SOURCE_DIR}/shaders/atlas_map.slang" spv_exact COMMENT "slangc atlas_store:${pass}Main -> SPIR-V (exact float contract)")
  add_custom_command(
    OUTPUT "${GEN}/atlas_store_${pass}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_store_${pass}.spv -DOUT=${GEN}/atlas_store_${pass}_spv.h
            -DNAME=atlas_store_${pass}_spv -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_store_${pass}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND GEN_HEADERS "${GEN}/atlas_store_${pass}_spv.h")
endforeach()
target_sources(atlas_core PRIVATE src/gen_engine.cpp src/gen_bake.cpp "${GEN}/atlas_gen_cpu.cpp" ${GEN_HEADERS})
set(GEN_RENAME "GlobalParams_0=GenGlobals_0;KernelContext_0=GenContext_0")
if(MSVC)
  set_source_files_properties("${GEN}/atlas_gen_cpu.cpp" PROPERTIES COMPILE_OPTIONS "/fp:strict;/W0"
                              COMPILE_DEFINITIONS "${GEN_RENAME}")
else()
  set_source_files_properties("${GEN}/atlas_gen_cpu.cpp" PROPERTIES COMPILE_OPTIONS "-ffp-contract=off;-fno-fast-math;-w"
                              COMPILE_DEFINITIONS "${GEN_RENAME}")
endif()
# Structures from the plans (atlas_overlays.slang, overlays_engine.cpp): the pieces' op tapes and the street painter
# stamped on chunk columns; the same exact float contract as the generator (the painter's float expressions)
set(OVL_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_overlays.slang")
set(OVL_HEADERS)
foreach(pair "pieces:piecesMain" "streets:streetsMain" "sanity:sanityMain" "connect:connectMain" "access:accessMain" "mark:markMain"
             "done:doneMain")
  string(REPLACE ":" ";" pe "${pair}")
  list(GET pe 0 pass)
  list(GET pe 1 entry)
  set(OVL_VALIDATE)
  if(SPIRV_VAL)
    set(OVL_VALIDATE COMMAND "${SPIRV_VAL}" --target-env vulkan1.2 "${GEN}/atlas_ovl_${pass}.spv")
  endif()
  add_custom_command(
    OUTPUT "${GEN}/atlas_ovl_${pass}.spv"
    COMMAND "${SLANGC}" "${OVL_SRC}" -target spirv -entry ${entry} -stage compute -O3 -fp-mode precise
            -warnings-disable 41035 -o "${GEN}/atlas_ovl_${pass}_raw.spv"
    COMMAND spv_exact "${GEN}/atlas_ovl_${pass}_raw.spv" "${GEN}/atlas_ovl_${pass}.spv"
    ${OVL_VALIDATE}
    DEPENDS "${OVL_SRC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_exact.slang" spv_exact COMMENT "slangc atlas_overlays:${entry} -> SPIR-V (exact float contract)")
  add_custom_command(
    OUTPUT "${GEN}/atlas_ovl_${pass}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_ovl_${pass}.spv -DOUT=${GEN}/atlas_ovl_${pass}_spv.h
            -DNAME=atlas_ovl_${pass}_spv -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_ovl_${pass}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND OVL_HEADERS "${GEN}/atlas_ovl_${pass}_spv.h")
endforeach()
# the road painter (atlas_overlays_roads.slang: RoadPaint over the generator's map) and the heightmap it reads
set(OVLR_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_overlays_roads.slang")
foreach(pair "roads:roadsMain" "mbnl:mbnlMain")
  string(REPLACE ":" ";" pe "${pair}")
  list(GET pe 0 pass)
  list(GET pe 1 entry)
  set(OVL_VALIDATE)
  if(SPIRV_VAL)
    set(OVL_VALIDATE COMMAND "${SPIRV_VAL}" --target-env vulkan1.2 "${GEN}/atlas_ovl_${pass}.spv")
  endif()
  add_custom_command(
    OUTPUT "${GEN}/atlas_ovl_${pass}.spv"
    COMMAND "${SLANGC}" "${OVLR_SRC}" -target spirv -entry ${entry} -stage compute -O3 -fp-mode precise
            -warnings-disable 41035 -o "${GEN}/atlas_ovl_${pass}_raw.spv"
    COMMAND spv_exact "${GEN}/atlas_ovl_${pass}_raw.spv" "${GEN}/atlas_ovl_${pass}.spv"
    ${OVL_VALIDATE}
    DEPENDS "${OVLR_SRC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_exact.slang" "${CMAKE_SOURCE_DIR}/shaders/atlas_map.slang"
            "${CMAKE_SOURCE_DIR}/shaders/atlas_ovl_javamath.slang" spv_exact COMMENT "slangc atlas_overlays_roads:${entry} -> SPIR-V (exact float contract)")
  add_custom_command(
    OUTPUT "${GEN}/atlas_ovl_${pass}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_ovl_${pass}.spv -DOUT=${GEN}/atlas_ovl_${pass}_spv.h
            -DNAME=atlas_ovl_${pass}_spv -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_ovl_${pass}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND OVL_HEADERS "${GEN}/atlas_ovl_${pass}_spv.h")
endforeach()
target_sources(atlas_core PRIVATE src/overlays_engine.cpp ${OVL_HEADERS})

# The client's decoration (atlas_features.slang, docs/ATLAS-CLIENT-GENERATION.md stage 4): the same exact float
# contract, one SPIR-V module per kernel (one a feature type); the big helpers are [noinline] (else the driver inlines
# them at every call site). Its exact math is a copy of its own (atlas_feat_math.slang: the flag through an argument,
# see there). -O0, and it ships so: from slangc's -O3 SPIR-V (checked valid and
# right op for op) NVIDIA's driver 616.56 compiles wrong code (spring: `holes == 1` false with holes 1; its SSA form,
# not the source, triggers it), while the -O0 SPIR-V matches Java exactly (E:/NirnCraft-scratch/features/o3).
# GPU only (the CPU fallback is a later job of its own).
set(FEAT_SRC "${CMAKE_SOURCE_DIR}/shaders/atlas_features.slang")
set(FEAT_HEADERS)
# (dev) the kernels' clock probes (atlas_features.slang FEAT_CLOCK; run with ATLAS_FEAT_CLOCK=1 ATLAS_FEAT_PART_TRACE=1)
option(ATLAS_FEAT_CLOCK "decoration kernels with clock probes (dev)" OFF)
set(FEAT_DEFS)
if(ATLAS_FEAT_CLOCK)
  set(FEAT_DEFS -DFEAT_CLOCK=1)
endif()
foreach(pair "hm:featHmMain" "init:featInitMain" "copy:featCopyMain" "apply:featApplyMain" "pack:featPackMain"
             "ore:featOreMain" "cover:featCoverMain" "rock:featRockMain" "tree:featTreeMain" "freeze:featFreezeMain"
             "disk:featDiskMain" "spring:featSpringMain" "lake:featLakeMain" "aux:featAuxMain"
             "sapply:featStoreApplyMain" "mask:featMaskMain" "farplaces:featFarPlacesMain" "fartrees:featFarTreesMain"
             "farcanopy:featFarCanopyMain" "vein:featVeinMain" "lakew:featLakeWideMain"
             "rockw:featRockWideMain")
  string(REPLACE ":" ";" pe "${pair}")
  list(GET pe 0 pass)
  list(GET pe 1 entry)
  set(FEAT_VALIDATE)
  if(SPIRV_VAL)
    set(FEAT_VALIDATE COMMAND "${SPIRV_VAL}" --target-env vulkan1.2 "${GEN}/atlas_feat_${pass}.spv")
  endif()
  add_custom_command(
    OUTPUT "${GEN}/atlas_feat_${pass}.spv"
    COMMAND "${SLANGC}" "${FEAT_SRC}" -target spirv -entry ${entry} -stage compute -O0 -fp-mode precise ${FEAT_DEFS}
            -warnings-disable 41035 -o "${GEN}/atlas_feat_${pass}_raw.spv"
    COMMAND spv_exact "${GEN}/atlas_feat_${pass}_raw.spv" "${GEN}/atlas_feat_${pass}.spv"
    ${FEAT_VALIDATE}
    DEPENDS "${FEAT_SRC}" "${CMAKE_SOURCE_DIR}/shaders/atlas_feat_math.slang" spv_exact COMMENT "slangc atlas_features:${entry} -> SPIR-V (exact float contract)")
  add_custom_command(
    OUTPUT "${GEN}/atlas_feat_${pass}_spv.h"
    COMMAND "${CMAKE_COMMAND}" -DIN=${GEN}/atlas_feat_${pass}.spv -DOUT=${GEN}/atlas_feat_${pass}_spv.h
            -DNAME=atlas_feat_${pass}_spv -P "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake"
    DEPENDS "${GEN}/atlas_feat_${pass}.spv" "${CMAKE_SOURCE_DIR}/cmake/embed_spv.cmake")
  list(APPEND FEAT_HEADERS "${GEN}/atlas_feat_${pass}_spv.h")
endforeach()
# one producer of the decoration's kernels (two targets naming the same outputs ran their rules side by side and
# raced on the same header): atlas_core and atlas_feat depend on it
add_custom_target(atlas_feat_kernels DEPENDS ${FEAT_HEADERS})
target_sources(atlas_core PRIVATE src/features_engine.cpp)
add_dependencies(atlas_core atlas_feat_kernels)
# the decoration alone (its offline check needs no renderer kernels: a small library of its own)
add_library(atlas_feat SHARED src/features_engine.cpp src/vk_loader.cpp)
add_dependencies(atlas_feat atlas_feat_kernels)
target_include_directories(atlas_feat PUBLIC include PRIVATE src "${GEN}" "${ATLAS_VK_INCLUDE}")
target_compile_definitions(atlas_feat PRIVATE ATLAS_BUILD VK_NO_PROTOTYPES ATLAS_FEAT_ALONE)  # (no generator: no atlas_feat_attach)
target_link_libraries(atlas_feat PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
# the decoration's offline check: a dump from AtlasGenFeaturesGpuTest (Java's decoration) against the kernels
# the driver's JIT time of a compute module (no pipeline cache): spv_jit_time <a.spv> ...
add_executable(spv_jit_time tools/spv_jit_time.cpp src/vk_loader.cpp)
target_include_directories(spv_jit_time PRIVATE src "${ATLAS_VK_INCLUDE}")
target_compile_definitions(spv_jit_time PRIVATE VK_NO_PROTOTYPES)
target_link_libraries(spv_jit_time PRIVATE ${CMAKE_DL_LIBS})
add_executable(atlas_feat_test tests/features_test.cpp)
target_include_directories(atlas_feat_test PRIVATE include)
target_link_libraries(atlas_feat_test PRIVATE atlas_feat)
# the far passes without Java (dev tool: gen_probe <dir> <lod> <tx> <tz> [far], the parity test's dump)
add_executable(gen_probe tools/gen_probe.cpp)
target_include_directories(gen_probe PRIVATE include)
target_link_libraries(gen_probe PRIVATE atlas_core)
# the self-baking cache without Java (dev tool: gen_cache_bench <dump dir> <cache dir> [x= z= radius= seed=])
add_executable(gen_cache_bench tools/gen_cache_bench.cpp)
target_include_directories(gen_cache_bench PRIVATE include)
target_link_libraries(gen_cache_bench PRIVATE atlas_core Threads::Threads)
# the whole-map coarse bake without Java (dev tool: gen_bake_tool <dump dir> <cache dir> [mode=bake|load ...])
add_executable(gen_bake_tool tools/gen_bake_tool.cpp)
target_include_directories(gen_bake_tool PRIVATE include)
target_link_libraries(gen_bake_tool PRIVATE atlas_core Threads::Threads)

add_executable(atlas_height_test tests/height_test.cpp)
target_include_directories(atlas_height_test PRIVATE include)
target_link_libraries(atlas_height_test PRIVATE atlas_core)

enable_testing()
add_executable(atlas_pipeline_cache_io_test tests/pipeline_cache_io_test.cpp)
target_include_directories(atlas_pipeline_cache_io_test PRIVATE src)
target_link_libraries(atlas_pipeline_cache_io_test PRIVATE Threads::Threads)
add_test(NAME atlas_pipeline_cache_io_test COMMAND atlas_pipeline_cache_io_test)
add_test(NAME atlas_tests COMMAND atlas_tests WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}")
add_test(NAME atlas_cache_test COMMAND atlas_cache_test)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  add_test(NAME atlas_cache_lifetime_test COMMAND bash "${CMAKE_SOURCE_DIR}/tests/run_cache_lifetime.sh"
           "$<TARGET_FILE:atlas_cache_lifetime_test>")
else()
  add_test(NAME atlas_cache_lifetime_test COMMAND atlas_cache_lifetime_test)
endif()
add_test(NAME atlas_frame_lock_test COMMAND atlas_frame_lock_test)
add_test(NAME atlas_close_admission_test COMMAND atlas_close_admission_test)

# offline lab for the shader pack's screen passes over an atlas-rendered scene (Windows, GL compatibility context)
if(WIN32)
  add_executable(pack_lab tools/pack_lab.cpp)
  target_link_libraries(pack_lab PRIVATE atlas_core opengl32)
  target_include_directories(pack_lab PRIVATE src)
endif()

# the flight recorder (docs/ATLAS-PLAN.md stage 0.2): always-on CPU zones and GPU timestamps, spike dumps
target_sources(atlas_core PRIVATE src/flight_recorder.cpp src/texturepack.cpp)

# the kernels' budget after each target links (spv_budget above); the backend's pipeline statistics against its "pipe" part.
# Each target checks only the modules it embeds (its generated *_spv.h, listed at configure time in
# spv_modules_<target>.txt), so a target never waits on another target's SPIR-V (2026-10-26: atlas_core's check failed in a
# fresh build folder on atlas_lab_gpu's atlas_lod_build_*.spv) and a stale .spv left by an older build is not checked.
# spv_modules.txt keeps the union for tools.
set(SPV_MODULES "")
get_property(SPV_TARGETS DIRECTORY PROPERTY BUILDSYSTEM_TARGETS)
foreach(t ${SPV_TARGETS})
  set(_mods "")
  get_target_property(srcs ${t} SOURCES)
  if(srcs)
    foreach(src ${srcs})
      if(src MATCHES "([A-Za-z0-9_]+)_spv[.]h$")
        list(APPEND _mods "${CMAKE_MATCH_1}")
      endif()
    endforeach()
  endif()
  if(t STREQUAL "atlas_core")
    foreach(src ${FEAT_HEADERS})  # (the decoration's kernels: a custom target's, not a library's sources)
      if(src MATCHES "([A-Za-z0-9_]+)_spv[.]h$")
        list(APPEND _mods "${CMAKE_MATCH_1}")
      endif()
    endforeach()
  endif()
  if(_mods)
    list(REMOVE_DUPLICATES _mods)
    list(SORT _mods)
    list(APPEND SPV_MODULES ${_mods})
    string(REPLACE ";" "\n" _text "${_mods}")
    file(WRITE "${GEN}/spv_modules_${t}.txt" "${_text}\n")
    if(NOT t STREQUAL "spv_budget")
      add_dependencies(${t} spv_budget)
      add_custom_command(TARGET ${t} POST_BUILD
        COMMAND spv_budget "${CMAKE_SOURCE_DIR}/shaders/kernel_budget.json" "${GEN}" "${GEN}/spv_modules_${t}.txt"
        COMMENT "spv_budget (${t}): its kernels' SPIR-V against shaders/kernel_budget.json")
    endif()
  endif()
endforeach()
list(REMOVE_DUPLICATES SPV_MODULES)
list(SORT SPV_MODULES)
string(REPLACE ";" "\n" SPV_MODULES_TEXT "${SPV_MODULES}")
file(WRITE "${GEN}/spv_modules.txt" "${SPV_MODULES_TEXT}\n")
target_compile_definitions(atlas_core PRIVATE ATLAS_KERNEL_BUDGET="${CMAKE_SOURCE_DIR}/shaders/kernel_budget.json")
