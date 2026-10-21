# One code base, every machine

Owner's requirement (2026-10-19): NirnCraft runs well with Minecraft on Windows, Bazzite and other Linux
distributions, the Steam Deck, integrated GPUs, a GTX 1080, an RTX 3090, with no GPU at all, and later macOS and ARM,
from one code base whose builds just work; every system scales up and down at run time, the most performance for the
least visual impact, informed by cheap probes at load and while playing (as resolution scalers do, or GTA's mix of
screen-space and real-time effects in the places they pay). Not only the height kernel: the renderer (atlas, and the
shader pack while it ships) and the game systems behind them. Where the renderer stands: [ATLAS-STATUS.md](ATLAS-STATUS.md).

## What already exists

| piece | where | state |
|---|---|---|
| kernels written once in Slang, compiled to SPIR-V (Vulkan) and to C++ (CPU) | native/shaders, native/CMakeLists.txt | the height kernel, the world generator (atlas_gen), the trace and its light, clouds, sun map; bit-exact CPU/Vulkan checks for the height kernel and the generator |
| CPU backend of atlas_core (no GPU, or a Vulkan failure) | native/src (AtlasBackend CPU) | works, in game too (the GL path on a worker, ATLAS-RENDERER-PLAN.md "R6 CPU path in game"); not a tested deployment path |
| natives packed per platform in the jar | mod/src/main/resources/natives/{windows-x86_64, linux-x86_64}, natives/manifest.json | Windows ABI 0.17 (5ffc5489); the Linux .so is an older build (3440faae, before the air, the GPU generator and the sky glow entry points: rebuild it in WSL, glibc 2.28 floor, Next steps 1); tools/dev/check_natives.py checks them against AtlasNative (ABI 0.11 to 0.17) |
| one adaptive controller for the pack and atlas | docs/ADAPTIVE-QUALITY.md, render/quality/, atlas/AtlasQuality, shaders/lib/quality.glsl | continuous knobs with measured value per ms, GPU class first guess (HardwareTier, incl. the Deck and Mesa names), calibration behind the loading cover, live probes, tiers 1 / 2 down to atlas off, host load (LAN guests), saved profiles |
| offline measurement of every knob's visual cost | native/tools/pack_lab_quality.py | the pack's knobs; atlas_lab measures the new representations |

## The matrix

| target | GPU path | CPU path | build |
|---|---|---|---|
| Windows x64 (NVIDIA, AMD, Intel) | Vulkan | yes | MSVC (native/build.ps1) |
| Linux x64 (Bazzite, SteamOS, Ubuntu, Fedora, Arch): NVIDIA, Mesa RADV / ANV | Vulkan | yes | clang or gcc against an old glibc baseline, one .so for every distribution (native/build_linux.sh) |
| Steam Deck (Van Gogh APU, 1280 x 800) | Vulkan (RADV) | yes | the Linux x64 build; its own HardwareTier class and budget |
| no GPU / software rasteriser (llvmpipe) | - | yes | any of the above; atlas at its CPU tier |
| Linux arm64 (ARM boards, ARM laptops) | Vulkan where present | yes | cross-compiled .so |
| macOS arm64 / x64 | Metal (Slang's Metal target) or Vulkan on MoltenVK | yes | clang; signed dylib |

Loading order (AtlasNative): the platform's library, then the GPU backend, falling back to the CPU backend, falling
back to atlas off; each step logged once, and the harness records which one ran.

## One rule for scaling everything

Every system that costs time exposes **knobs**: continuous 0..1, a floor that still works, changes that fade in
(dithered, never stepping the whole screen), and a cost and a visual value measured offline (the pack_lab / atlas_lab
method: render with the knob lowered, compare with the reference, perceptual dE). One controller per resource spends
the budget where it buys the most:

* **GPU:** the pack's and atlas's knobs (existing), and the new representation's: group threshold (tol), rays per
  pixel (the light by need's rate and footprint already adapt), surface cache refresh per frame, face texture density,
  resolution scale.
* **CPU threads:** the CPU paths already run on every hardware thread (atlas::parallelRun: the CPU backend's frame,
  the voxeliser; the height kernel up to 8; the preview source half the cores); the thread count becomes a knob
  too, so they take all cores when the machine has them free (no GPU, a loading cover, a many-core CPU) and give
  them back when Minecraft's chunk builder or the integrated server needs them.
* **CPU (client):** chunk meshing share, far-field worker passes, change-feed apply budget, entity stand-in distance,
  particle and weather simulation density.
* **CPU (server / host):** generation and pregeneration share, the change feed's push rate, section recording,
  guests' tile serving (HostLoad already does part of this).
* **Memory:** far tile cache, surface cache size, section mips, texture atlas resolution.

Probes stay cheap: classification at load (GPU name, VRAM, driver, cores, panel), a calibration while a loading
cover is up, then one knob nudged at a time while playing (the existing scheme), never more than a frame's 2 % on
average. The profile is saved per machine and per pack version.

## Testing it

* Offline (every change): CPU and Vulkan backends of the same kernels agree (the height kernel bit for bit, renders
  within a dE bound); atlas_lab and pack_lab measure each knob's visual cost; the harness can force a hardware class
  and a knob vector on any machine (the lab tools' q= and the live run's quality options).
* Builds: every platform in the matrix compiles and passes the native tests in CI (Windows and Linux x64 first, then
  arm64, then macOS).
* Real machines (before releases): the owner's Windows PC (3090), a Bazzite install, the Steam Deck; a short scripted
  run per machine (the harness's smoke suite) that records the class, the backend that loaded, the controller's
  settled knobs and frame times.

## Distribution and updates (owner, 2026-10-19: needed once real players test)

* **The modpack as a Modrinth pack** (`.mrpack`): Prism, the Modrinth app and other launchers install it, update
  it, and drop or add mods between versions (Lootr leaving when NirnCraft's containers replace it is one such pack
  version). NirnCraft itself as a Modrinth project the pack depends on; third-party mods by their Modrinth versions.
* **One jar for every platform:** the natives for each OS / architecture inside it (natives/<os>-<arch>/), the right
  one loaded at start, the CPU backend when none fits; the shader pack shipped in the pack (shaderpacks/) at the
  version the mod expects (the mod warns when they differ).
* **Native libraries and hosting rules:** check the hosting platform's rules on bundled native code before the first
  upload; if they object, the jar ships without natives and fetches the matching one from the project's GitHub
  releases on first run (checksum pinned in the jar), or the whole pack is distributed from GitHub releases.
* **Updates in steady state:** semantic versions, a changelog per release, save-format migrations run on world load
  (the far tile cache already keys itself by the far identity hash and rebuilds when generation changes), and a
  rollback path (the previous pack version stays installable).
* **Release checks:** the harness smoke suite on the release build, the natives' ABI check for every platform, and
  the real-machine runs above.

## Next steps

1. **Done 2026-10-19.** Linux x64 toolchain (WSL for builds, owner OK 2026-10-19; end users run the native library)
   and an ABI 0.13 .so in the jar; the harness checks the packed natives ABI against the Java side's minimum
   (tools/dev/check_natives.py; hooking it into the harness is the owner's session's step).
   * Toolchain, no sudo: WSL Ubuntu 24.04; micromamba with conda-forge `gxx_linux-64` 13.4 + `sysroot_linux-64` 2.28
     (glibc 2.28 headers and libs), `cmake` 4.4, `ninja` 1.13, `spirv-tools` (spirv-val), `python` 3.12 + `numpy` +
     `pillow` (the test rasters), in `~/.atlas-tc/env`; Slang 2026.18.3 and Vulkan-Headers 1.3.296 fetched by the
     script. The distro's Mesa (lavapipe + llvmpipe, EGL) was already installed, so `--software` runs every suite.
   * Commands (from WSL, the worktree under /mnt/<drive>/...; the full setup is in native/build_linux.sh's header):

         PATH=~/.atlas-tc/env/bin:$PATH CXX=x86_64-conda-linux-gnu-g++ BUILD_DIR=~/atlas-build \
           taskset -c 0-7 nice -n 15 native/build_linux.sh --clean --software
         cp ~/atlas-build/libatlas_core.so mod/src/main/resources/natives/linux-x86_64/
         py -3 -I tools/dev/check_natives.py      # on Windows: loads the DLL, and the .so through WSL

     (taskset / nice: half the cores at low priority, when the machine is shared.)
   * Results on lavapipe / llvmpipe: atlas_tests, atlas_height_test (Vulkan vs C++ bit for bit), atlas_gl_test (both
     contexts, host-copy: lavapipe has no external semaphore fd) and atlas_pack_test all pass (two full runs, the
     shipped .so from the `--clean` one). In an earlier run under heavy host load, three Vulkan frames on lavapipe
     exceeded the backend's 5 s fence timeout (FENCE_TIMEOUT_NS: "the GPU hung"), failing the region-seam and sun-map
     checks; both later runs passed them. A slow software device, not a defect; a real GPU is far inside the limit.
   * The .so: 47 atlas_* exports = atlas.h's declarations; newest glibc symbol GLIBC_2.27 (expf, powf, log2f), NEEDED
     only libc / libm / libdl / libpthread / ld-linux; libstdc++ and libgcc static (no GLIBCXX / CXXABI / GCC_
     references); no RPATH. Fixes the build needed: atlas_lab links Threads (glibc before 2.34 keeps pthread_create in
     libpthread), and conda's gcc specs add `-rpath <env>/lib` to every link, so the library links with a copy of the
     specs without it (build_linux.sh).
2. The new representation's knobs (with the Slang port), measured by atlas_lab like the pack's.
3. A CPU-backend tier test: atlas on the CPU backend at its lowest tier inside a budget on an 8-core CPU.
4. arm64 Linux, then macOS (Slang's Metal target, or MoltenVK first as the quicker path).
5. The client and server CPU knobs into the controller (generation, change feed, entities, weather).
