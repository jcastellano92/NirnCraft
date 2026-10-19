# One code base, every machine

Owner's requirement (2026-10-19): NirnCraft runs well with Minecraft on Windows, Bazzite and other Linux
distributions, the Steam Deck, integrated GPUs, a GTX 1080, an RTX 3090, with no GPU at all, and later macOS and ARM,
from one code base whose builds just work; every system scales up and down at run time, the most performance for the
least visual impact, informed by cheap probes at load and while playing (as resolution scalers do, or GTA's mix of
screen-space and real-time effects in the places they pay). Not only the height kernel: the far field, the shader
pack, the near field once it is ours, and the game systems behind them.

## What already exists

| piece | where | state |
|---|---|---|
| kernels written once in Slang, compiled to SPIR-V (Vulkan) and to C++ (CPU) | native/shaders, native/CMakeLists.txt | the height kernel, the far field, clouds, sun map; bit-exact CPU/Vulkan checks for the height kernel |
| CPU backend of atlas_core (no GPU, or a Vulkan failure) | native/src (AtlasBackend CPU) | works; not a tested deployment path |
| natives packed per platform in the jar | mod/src/main/resources/natives/{windows-x86_64, linux-x86_64} | Linux is stale (ABI before 0.13: no Linux toolchain set up yet) |
| one adaptive controller for the pack and the far field | docs/ADAPTIVE-QUALITY.md, render/quality/, atlas/AtlasQuality, shaders/lib/quality.glsl | continuous knobs with measured value per ms, GPU class first guess (HardwareTier, incl. the Deck and Mesa names), calibration behind the loading cover, live probes, tiers 1 / 2 down to the far field off, host load (LAN guests), saved profiles |
| offline measurement of every knob's visual cost | native/tools/pack_lab_quality.py | the pack's knobs; atlas_lab measures the new representations |

## The matrix

| target | GPU path | CPU path | build |
|---|---|---|---|
| Windows x64 (NVIDIA, AMD, Intel) | Vulkan | yes | MSVC (native/build.ps1) |
| Linux x64 (Bazzite, SteamOS, Ubuntu, Fedora, Arch): NVIDIA, Mesa RADV / ANV | Vulkan | yes | clang or gcc against an old glibc baseline, one .so for every distribution (native/build_linux.sh) |
| Steam Deck (Van Gogh APU, 1280 x 800) | Vulkan (RADV) | yes | the Linux x64 build; its own HardwareTier class and budget |
| no GPU / software rasteriser (llvmpipe) | - | yes | any of the above; the far field at its CPU tier |
| Linux arm64 (ARM boards, ARM laptops) | Vulkan where present | yes | cross-compiled .so |
| macOS arm64 / x64 | Metal (Slang's Metal target) or Vulkan on MoltenVK | yes | clang; signed dylib |

Loading order (AtlasNative): the platform's library, then the GPU backend, falling back to the CPU backend, falling
back to the far field off; each step logged once, and the harness records which one ran.

## One rule for scaling everything

Every system that costs time exposes **knobs**: continuous 0..1, a floor that still works, changes that fade in
(dithered, never stepping the whole screen), and a cost and a visual value measured offline (the pack_lab / atlas_lab
method: render with the knob lowered, compare with the reference, perceptual dE). One controller per resource spends
the budget where it buys the most:

* **GPU:** the pack's and the far field's knobs (existing), and the new representation's: group threshold (tol),
  rays per pixel, surface cache refresh per frame, face texture density, far resolution scale.
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

## Next steps

1. Linux x64 toolchain (WSL or a container) and an ABI 0.13 .so in the jar; the harness checks the packed natives'
   ABI against the Java side's minimum.
2. The new representation's knobs (with the Slang port), measured by atlas_lab like the pack's.
3. A CPU-backend tier test: the far field on the CPU backend at its lowest tier inside a budget on an 8-core CPU.
4. arm64 Linux, then macOS (Slang's Metal target, or MoltenVK first as the quicker path).
5. The client and server CPU knobs into the controller (generation, change feed, entities, weather).
