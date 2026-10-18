# Build aether_core (Release) and run its tests. Toolchains: F:\Projects\Personal\Skycraft\toolchains (see README).
#   .\native\build.ps1 [-Config Release|Debug] [-NoTest]
param([string]$Config = 'Release', [switch]$NoTest)
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
    & (Join-Path $build "$Config\aether_tests.exe") $raster
    if ($LASTEXITCODE) { throw 'tests failed' }
    # the zero-copy GL bridge (skips cleanly on machines without a GL 4.5 + EXT_memory_object_win32 context)
    & (Join-Path $build "$Config\aether_gl_test.exe") (Join-Path $here 'data')
    if ($LASTEXITCODE) { throw 'gl interop test failed' }
}
