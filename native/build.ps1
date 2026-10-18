# Build aether_core (Release) and run its tests. Toolchains: F:\Projects\Personal\Skycraft\toolchains (see README).
#   .\native\build.ps1 [-Config Release|Debug] [-NoTest] [-Validate]
# -Validate runs the GPU suites under the SDK's Khronos validation + synchronization validation. Linux: build_linux.sh.
param([string]$Config = 'Release', [switch]$NoTest, [switch]$Validate)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$tc = if ($env:SKYCRAFT_TOOLCHAINS) { $env:SKYCRAFT_TOOLCHAINS } else { 'F:\Projects\Personal\Skycraft\toolchains' }
. "$tc\env.ps1"
$build = Join-Path $here 'build'
if (-not (Test-Path "$build\CMakeCache.txt")) {
    cmake -S $here -B $build -G 'Visual Studio 16 2019' -A x64 | Out-Host
    if ($LASTEXITCODE) { throw 'cmake configure failed' }
}
cmake --build $build --config $Config --parallel | Out-Host
if ($LASTEXITCODE) { throw 'build failed' }
$repo = Split-Path -Parent $here
$raster = Join-Path $here 'data\tamriel_raster.bin'
if (-not (Test-Path $raster)) { py -3 -I (Join-Path $here 'tools\export_raster.py') $repo | Out-Host }
if (-not (Test-Path (Join-Path $here 'data\cloud_noise.bin'))) {
    py -3 -I (Join-Path $here 'tools\export_cloud_noise.py') $repo | Out-Host
}
if (-not $NoTest) {
    if ($Validate) {
        $env:VK_ADD_LAYER_PATH = Join-Path $env:VULKAN_SDK 'Bin'
        $env:AETHER_VK_VALIDATION = '1'
        $env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC = 'true'
    }
    & (Join-Path $build "$Config\aether_tests.exe") $raster
    if ($LASTEXITCODE) { throw 'tests failed' }
    # the exact-height kernel: correctly rounded division, known inputs, Vulkan vs its C++ reference bit for bit
    & (Join-Path $build "$Config\aether_height_test.exe")
    if ($LASTEXITCODE) { throw 'height tests failed' }
    # the GL bridge, zero-copy (skips cleanly on machines without a GL 4.5 context) ...
    & (Join-Path $build "$Config\aether_gl_test.exe") (Join-Path $here 'data')
    if ($LASTEXITCODE) { throw 'gl interop test failed' }
    # ... and the host-copy fallback AMD / Mesa / cross-GPU machines may land on, forced here
    $env:AETHER_INTEROP = 'hostcopy'
    try {
        & (Join-Path $build "$Config\aether_gl_test.exe") (Join-Path $here 'data')
        if ($LASTEXITCODE) { throw 'gl host-copy test failed' }
    } finally { Remove-Item Env:AETHER_INTEROP -ErrorAction SilentlyContinue }
    # the shader pack's atmosphere must match aether's, or the near/far hand-off shows a seam
    $atmo = Join-Path $repo 'shaderpack/SkycraftAether/shaders/lib/atmosphere.glsl'
    & (Join-Path $build "$Config/aether_pack_test.exe") $atmo
    if ($LASTEXITCODE) { throw 'pack parity test failed' }
}
