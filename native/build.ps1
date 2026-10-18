# Build aether_core (Release) and run its tests. Toolchains: F:\Projects\Personal\Skycraft\toolchains (see README).
#   .\native\build.ps1 [-Config Release|Debug] [-NoTest]
param([string]$Config = 'Release', [switch]$NoTest)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$tc = if ($env:SKYCRAFT_TOOLCHAINS) { $env:SKYCRAFT_TOOLCHAINS } else { Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $here))))) 'toolchains' }
if (-not (Test-Path "$tc\env.ps1")) { $tc = 'F:\Projects\Personal\Skycraft\toolchains' }
. "$tc\env.ps1"
$build = Join-Path $here 'build'
if (-not (Test-Path "$build\CMakeCache.txt")) {
    cmake -S $here -B $build -G 'Visual Studio 16 2019' -A x64 | Out-Host
    if ($LASTEXITCODE) { throw 'cmake configure failed' }
}
cmake --build $build --config $Config --parallel | Out-Host
if ($LASTEXITCODE) { throw 'build failed' }
$raster = Join-Path $here 'data\tamriel_raster.bin'
if (-not (Test-Path $raster)) { py -3 -I (Join-Path $here 'tools\export_raster.py') (Split-Path -Parent $here) | Out-Host }
if (-not $NoTest) {
    & "$build$Configether_gl_test.exe" (Join-Path $here "data")
    if ($LASTEXITCODE) { throw "gl interop test failed" }
    & "$build\$Config\aether_tests.exe" $raster
    if ($LASTEXITCODE) { throw 'tests failed' }
}
