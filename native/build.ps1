# Build atlas_core (Release) and run its tests. Toolchains: the toolchains folder beside the checkout (see README).
#   .\native\build.ps1 [-Config Release|Debug] [-NoTest] [-Validate]
# -Validate runs the GPU suites under the SDK's Khronos validation + synchronization validation. Linux: build_linux.sh.
param([string]$Config = 'Release', [switch]$NoTest, [switch]$Validate)
# not 'Stop': Windows PowerShell 5.1 turns a native program's stderr into an error record when output is redirected,
# and the suites print deliberate fault-injection lines there; every step checks $LASTEXITCODE and throws instead
$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$tc = if ($env:NIRNCRAFT_TOOLCHAINS) { $env:NIRNCRAFT_TOOLCHAINS } else { (& "$PSScriptRoot\toolchains.ps1") }
. "$tc\env.ps1"
$build = Join-Path $here 'build'
# build (and any re-configure it triggers) with the CMake that made the cache: another version on PATH (the
# toolchain's CMake 4.x vs Visual Studio's 3.x) refuses the existing cache
$cmake = 'cmake'
if (Test-Path "$build\CMakeCache.txt") {
    $line = Select-String -Path "$build\CMakeCache.txt" -Pattern '^CMAKE_COMMAND:INTERNAL=(.+)$' | Select-Object -First 1
    if ($line -and (Test-Path $line.Matches[0].Groups[1].Value)) { $cmake = $line.Matches[0].Groups[1].Value }
} else {
    & $cmake -S $here -B $build -G 'Visual Studio 16 2019' -A x64 | Out-Host
    if ($LASTEXITCODE) { throw 'cmake configure failed' }
}
& $cmake --build $build --config $Config --parallel | Out-Host
if ($LASTEXITCODE) { throw 'build failed' }
# the sources this core was built from (the harness rebuilds only when they change, not when a file's time does)
py -3 -I (Join-Path (Split-Path -Parent $here) 'tools\dev\native_sources.py') --stamp (Join-Path $build "$Config\atlas_core.sources")
$repo = Split-Path -Parent $here
$raster = Join-Path $here 'data\tamriel_raster.bin'
if (-not (Test-Path $raster)) { py -3 -I (Join-Path $here 'tools\export_raster.py') $repo | Out-Host }
if (-not (Test-Path (Join-Path $here 'data\cloud_noise.bin'))) {
    py -3 -I (Join-Path $here 'tools\export_cloud_noise.py') $repo | Out-Host
}
if (-not $NoTest) {
    & (Join-Path $build "$Config\atlas_pipeline_cache_io_test.exe")
    if ($LASTEXITCODE) { throw 'pipeline cache IO tests failed' }
    & (Join-Path $build "$Config\atlas_cache_test.exe")
    if ($LASTEXITCODE) { throw 'cache recovery tests failed' }
    & (Join-Path $build "$Config\atlas_frame_lock_test.exe")
    if ($LASTEXITCODE) { throw 'frame lock/snapshot tests failed' }
    if ($Validate) {
        $env:VK_ADD_LAYER_PATH = Join-Path $env:VULKAN_SDK 'Bin'
        $env:ATLAS_VK_VALIDATION = '1'
        $env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC = 'true'
    }
    & (Join-Path $build "$Config\atlas_tests.exe") $raster
    if ($LASTEXITCODE) { throw 'tests failed' }
    # the exact-height kernel: correctly rounded division, known inputs, Vulkan vs its C++ reference bit for bit
    & (Join-Path $build "$Config\atlas_height_test.exe")
    if ($LASTEXITCODE) { throw 'height tests failed' }
    # the GL bridge, zero-copy where the driver pair allows it, on two contexts: the default (compatibility) one and
    # an OpenGL 3.3 core forward-compatible profile like Minecraft's, which must land on the same interop path ...
    & (Join-Path $build "$Config\atlas_gl_test.exe") (Join-Path $here 'data') --context=both
    if ($LASTEXITCODE) { throw 'gl interop test failed' }
    # ... and the host-copy fallback AMD / Mesa / cross-GPU machines may land on, forced here
    $env:ATLAS_INTEROP = 'hostcopy'
    try {
        & (Join-Path $build "$Config\atlas_gl_test.exe") (Join-Path $here 'data') --context=both
        if ($LASTEXITCODE) { throw 'gl host-copy test failed' }
    } finally { Remove-Item Env:ATLAS_INTEROP -ErrorAction SilentlyContinue }
    # the game's GLSL sky (the air, AtlasSky) must match atlas's, or the horizon shows a seam
    $atmo = Join-Path $repo 'mod/src/main/resources/assets/nirncraft/shaders/include/atlas_air.glsl'
    & (Join-Path $build "$Config/atlas_pack_test.exe") $atmo
    if ($LASTEXITCODE) { throw 'pack parity test failed' }
}
