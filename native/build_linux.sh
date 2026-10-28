#!/usr/bin/env bash
# Build atlas_core for Linux (libatlas_core.so) and run its suites.
#
#   native/build_linux.sh [--debug] [--no-test] [--software] [--glx] [--hostcopy] [--validate] [--clean] [--allow-skip]
#
#   --software   run on Mesa's lavapipe (Vulkan) + llvmpipe (GL) instead of the GPU: a strict, GPU-free conformance
#                pass (needs mesa-vulkan-drivers' lvp_icd json; slow: minutes)
#   --glx        make the GL test context with GLX (X11 / XWayland, what GLFW uses on X) instead of EGL surfaceless
#   --hostcopy   force the host-copy fallback (ATLAS_INTEROP=hostcopy) to test it on a machine that has interop
#   --validate   Khronos validation + synchronization validation (needs VK_LAYER_KHRONOS_validation installed:
#                vulkan-validation-layers on Fedora/Bazzite, vulkan-validationlayers on Debian/Ubuntu)
#
# Needs: a C++20 compiler (g++ >= 11 or clang++ >= 14), cmake >= 3.20, the Vulkan loader (libvulkan.so.1: every
# Steam / Mesa / NVIDIA install has it), python3 (to export the test rasters once), curl + tar (fetches Slang and,
# when /usr/include/vulkan is missing, Vulkan-Headers into native/build-linux/deps; nothing is installed system-wide).
# Optional: spirv-val (spirv-tools / SPIRV-Tools) validates the exact-height kernel's SPIR-V at build time.
# Shipping build (loads on glibc 2.28+: SteamOS, Bazzite, Fedora, Debian 10+): build against a glibc 2.28 sysroot, e.g.
#   conda-forge's gcc_linux-64 (sysroot_linux-64 2.28): CXX=x86_64-conda-linux-gnu-g++ native/build_linux.sh
#   No sudo needed (this is how the packed .so is built, also from Windows in WSL, Ubuntu 24.04; docs/PLATFORMS.md):
#     curl -fsSL -o mm.tar.bz2 https://micro.mamba.pm/api/micromamba/linux-64/latest   # a stock WSL has no bzip2:
#     python3 -c 'import tarfile; tarfile.open("mm.tar.bz2").extract("bin/micromamba", ".")'  # python unpacks it
#     bin/micromamba create -y -r ~/.atlas-tc/root -p ~/.atlas-tc/env -c conda-forge --override-channels \
#       gxx_linux-64=13 sysroot_linux-64=2.28 cmake ninja spirv-tools python=3.12 numpy pillow
#     PATH=~/.atlas-tc/env/bin:$PATH CXX=x86_64-conda-linux-gnu-g++ BUILD_DIR=~/atlas-build native/build_linux.sh
#   Put the env's bin on PATH rather than activating it: activation adds CXXFLAGS / LDFLAGS (-march, -isystem and
#   -rpath into the env) that do not belong in the shipped library. The compiler's own specs add an rpath into the
#   env too; the script links the library with a copy of them without it. python3 + numpy + pillow export the test
#   rasters. BUILD_DIR in the Linux home builds several times faster than on a /mnt/<drive> path.
#
# Steam Deck (SteamOS is read-only, no compiler): build in a distrobox / toolbox container (Bazzite ships both;
#   `distrobox create -i fedora:41 atlas && distrobox enter atlas`, then `sudo dnf install gcc-c++ cmake ninja-build
#   python3 vulkan-loader mesa-vulkan-drivers vulkan-validation-layers`), and run the tests from Desktop Mode (the
#   container shares the GPU and the session). The .so links libstdc++/libgcc statically, so the one built in the
#   container loads in the game's JVM on the host.
# Bazzite / Fedora: the same container, or toolbox. NVIDIA proprietary: install the driver's 32/64-bit Vulkan ICD
#   (nvidia_icd.json) and run the tests in a desktop session (GL needs the NVIDIA EGL/GLX vendor library).
#
# Environment overrides: SLANG_ROOT (a Slang release), VULKAN_HEADERS (a Vulkan-Headers checkout), CXX, BUILD_DIR,
# ATLAS_VK_DEVICE (index or name substring), ATLAS_INTEROP=hostcopy|zerocopy.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$HERE")"
CONFIG=Release
TEST=1
SOFTWARE=0
CLEAN=0
VALIDATE=0
ALLOW_SKIP=0
for a in "$@"; do
  case "$a" in
    --debug) CONFIG=Debug ;;
    --no-test) TEST=0 ;;
    --software) SOFTWARE=1 ;;
    --glx) export ATLAS_TEST_GL=glx ;;
    --hostcopy) export ATLAS_INTEROP=hostcopy ;;
    --validate) VALIDATE=1 ;;
    --clean) CLEAN=1 ;;
    --allow-skip) ALLOW_SKIP=1 ;;
    -h|--help) sed -n '2,39p' "$0"; exit 0 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done

BUILD="${BUILD_DIR:-$HERE/build-linux}"
DEPS="$BUILD/deps"
[ "$CLEAN" = 1 ] && rm -rf "$BUILD"
mkdir -p "$DEPS"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1 ($2)" >&2; exit 1; }; }
need cmake "cmake >= 3.20"
need curl "to fetch Slang"
need tar "to unpack Slang"

# ---- Slang (the kernel compiler): the same release as the Windows toolchain, checksum-pinned -------------------
SLANG_VERSION=2026.18.3
SLANG_SHA256=4d664ca905124ae68204ee05170f5acd8dc53956baf850b2e7b23215cfb78f7b
if [ -z "${SLANG_ROOT:-}" ]; then
  SLANG_ROOT="$DEPS/slang-$SLANG_VERSION"
  if [ ! -x "$SLANG_ROOT/bin/slangc" ]; then
    echo "fetching Slang $SLANG_VERSION"
    curl -fsSL -o "$DEPS/slang.tar.gz" \
      "https://github.com/shader-slang/slang/releases/download/v$SLANG_VERSION/slang-$SLANG_VERSION-linux-x86_64.tar.gz"
    echo "$SLANG_SHA256  $DEPS/slang.tar.gz" | sha256sum -c - >/dev/null || { echo "Slang checksum mismatch" >&2; exit 1; }
    mkdir -p "$SLANG_ROOT" && tar -xzf "$DEPS/slang.tar.gz" -C "$SLANG_ROOT"
  fi
fi
export SLANG_ROOT

# ---- Vulkan headers: the system's, else a pinned Vulkan-Headers release ----------------------------------------
VK_HEADERS_TAG=v1.3.296
if [ -z "${VULKAN_HEADERS:-}" ] && [ ! -f /usr/include/vulkan/vulkan.h ]; then
  VULKAN_HEADERS="$DEPS/Vulkan-Headers-${VK_HEADERS_TAG#v}"
  if [ ! -f "$VULKAN_HEADERS/include/vulkan/vulkan.h" ]; then
    echo "fetching Vulkan-Headers $VK_HEADERS_TAG"
    curl -fsSL -o "$DEPS/vkh.tar.gz" "https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/$VK_HEADERS_TAG.tar.gz"
    tar -xzf "$DEPS/vkh.tar.gz" -C "$DEPS"
  fi
fi
export VULKAN_HEADERS="${VULKAN_HEADERS:-}"

# ---- configure + build ----------------------------------------------------------------------------------------
GEN=()
command -v ninja >/dev/null 2>&1 && GEN=(-G Ninja)
# conda-forge's gcc specs add -rpath <env>/lib to every link (%{!static:-rpath ...}): the shipped library needs no
# runpath (it only needs glibc) and must not carry the builder's paths, so it links with a copy of the specs without
# that clause (the test programs keep it: they find the env's libstdc++ through it)
LINK=()
SPECS="$("${CXX:-c++}" -print-file-name=specs 2>/dev/null || true)"
if [ -f "$SPECS" ] && grep -q -- '-rpath' "$SPECS"; then
  sed 's/ *%{!static:-rpath [^}]*}//' "$SPECS" > "$DEPS/gcc-norpath.specs"
  LINK=(-DCMAKE_SHARED_LINKER_FLAGS="-specs=$DEPS/gcc-norpath.specs")
fi
# a build directory configured from another checkout (a worktree) builds that checkout's sources: start it again
if [ -f "$BUILD/CMakeCache.txt" ] && ! grep -qxF "CMAKE_HOME_DIRECTORY:INTERNAL=$HERE" "$BUILD/CMakeCache.txt"; then
  echo "build_linux: $BUILD was configured for another source tree; reconfiguring for $HERE"
  rm -rf "$BUILD/CMakeCache.txt" "$BUILD/CMakeFiles"
fi
if [ ! -f "$BUILD/CMakeCache.txt" ]; then
  cmake -S "$HERE" -B "$BUILD" "${GEN[@]}" -DCMAKE_BUILD_TYPE="$CONFIG" "${LINK[@]}"
fi
cmake --build "$BUILD" --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
echo "built: $BUILD/libatlas_core.so"
if command -v objdump >/dev/null 2>&1 && objdump -p "$BUILD/libatlas_core.so" | grep -qE '^ *(RPATH|RUNPATH) '; then
  echo "WARNING: libatlas_core.so has an RPATH/RUNPATH (the toolchain's): do not ship it" >&2
fi
# what the library needs from the system (the mod ships it to every distro: keep this list short)
if command -v objdump >/dev/null 2>&1; then
  echo "NEEDED: $(objdump -p "$BUILD/libatlas_core.so" | awk '/NEEDED/ {printf "%s ", $2}')"
  echo "newest glibc symbol: $(objdump -T "$BUILD/libatlas_core.so" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1)"
fi

[ "$TEST" = 1 ] || exit 0

# ---- data the tests read (gitignored caches of the bundled rasters) --------------------------------------------
RASTER="$HERE/data/tamriel_raster.bin"
if [ ! -f "$RASTER" ]; then need python3 "exports the raster cache"; python3 -I "$HERE/tools/export_raster.py" "$REPO"; fi
if [ ! -f "$HERE/data/cloud_noise.bin" ]; then python3 -I "$HERE/tools/export_cloud_noise.py" "$REPO"; fi

if [ "$SOFTWARE" = 1 ]; then
  LVP=""
  for f in /usr/share/vulkan/icd.d/lvp_icd.x86_64.json /usr/share/vulkan/icd.d/lvp_icd.json; do [ -f "$f" ] && LVP="$f"; done
  [ -n "$LVP" ] || { echo "lavapipe not installed (mesa-vulkan-drivers)" >&2; exit 1; }
  export VK_DRIVER_FILES="$LVP" VK_ICD_FILENAMES="$LVP"
  export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ATLAS_TEST_SLOW=1
  echo "software drivers: lavapipe ($LVP) + llvmpipe"
fi
if [ "$VALIDATE" = 1 ]; then
  export ATLAS_VK_VALIDATION=1 VK_KHRONOS_VALIDATION_VALIDATE_SYNC=true
fi

cd "$REPO"
status=0
LOG="$BUILD/last_test.log"
run() {
  echo "== $*"
  local rc
  set +e
  nice -n 10 "$@" 2>&1 | tee "$LOG"
  rc=${PIPESTATUS[0]}
  set -e
  if [ "$rc" != 0 ]; then echo "-- FAILED ($rc)"; status=1
  # a suite that cannot make its GL context or Vulkan device says SKIP and exits 0: on a target machine that is
  # the failure worth seeing (headless build boxes: --allow-skip)
  elif grep -q '^SKIP' "$LOG" && [ "$ALLOW_SKIP" != 1 ]; then echo "-- SKIPPED (counts as a failure; --allow-skip)"; status=1
  else echo "-- ok"; fi
}
run "$BUILD/atlas_pipeline_cache_io_test"
run bash "$HERE/tests/run_cache_lifetime.sh" "$(realpath "$BUILD/atlas_cache_lifetime_test")"
run "$BUILD/atlas_tests" "$RASTER"
run "$BUILD/atlas_close_admission_test"
# the exact-height kernel: correctly rounded division, known inputs, Vulkan vs its C++ reference bit for bit (a device
# without shaderFloat64 + RTE / SignedZeroInfNanPreserve float controls reports the CPU fallback and passes)
run "$BUILD/atlas_height_test"
run "$BUILD/atlas_vk_test" "$HERE/data"
run "$BUILD/atlas_pack_test" "$REPO/mod/src/main/resources/assets/nirncraft/shaders/include/atlas_air.glsl"
[ "$status" = 0 ] && echo "ALL PASSED" || echo "SOME SUITES FAILED"
exit "$status"
