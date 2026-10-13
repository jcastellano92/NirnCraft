# Skycraft bake: pre-computes everything deterministic about the fixed Nirn world and writes it into the mod's
# resources (mod/src/main/resources/skycraft/baked/). Re-run it whenever worldgen data or code changes (terrain,
# structures, places, roads, settlement plans) and commit the result.
#
#   tools/bake/bake.ps1 [-Port 25748] [-Threads 0]
#
# What it does: boots the dev server (./gradlew runServer) on a fresh world with the Nirn seed and runs the autorun
# tasks "bake,verify" (com.skycraft.bake.Bake): every registered Bakeable (landmass, roadplan = the plan-first road
# plan, see docs/WORLDGEN-PIPELINE.md section 6) writes skycraft-bake/<name>.bin plus manifest.json; then
# a verify pass re-checks a sample against live generation. The files are copied into the resources.
# Runtime: the loaders use the baked data only when the world seed and the worldgen data hash match the manifest;
# otherwise they compute live (log lines "[bake] ...").
param(
    [int]$Port = 25748,
    [int]$Threads = 0,
    [string]$Xmx = ""   # server heap (default 4G or $env:SKYCRAFT_SERVER_XMX)
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$mod = Join-Path $root "mod"
$run = Join-Path $mod "run"
$out = Join-Path $PSScriptRoot "out"
$staging = Join-Path $run "skycraft-bake"
$target = Join-Path $mod "src\main\resources\skycraft\baked"
New-Item -ItemType Directory -Force $run, $out | Out-Null

if (Test-Path (Join-Path $run "world")) { Remove-Item -Recurse -Force (Join-Path $run "world") }
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
Set-Content -Path (Join-Path $run "eula.txt") -Value "eula=true" -Encoding ascii
$props = Join-Path $run "server.properties"
$lines = @()
if (Test-Path $props) { $lines = Get-Content $props | Where-Object { $_ -notmatch '^(level-seed|server-port|online-mode|level-type|max-tick-time)=' } }
$lines += "level-seed=2428247"   # Nirn, see com.skycraft.bake.WorldSeed
$lines += "server-port=$Port"
$lines += "online-mode=false"
$lines += "level-type=minecraft\:normal"
$lines += "max-tick-time=-1"
Set-Content -Path $props -Value $lines -Encoding ascii

$env:SKYCRAFT_AUTORUN = "bake:skycraft-bake,verify"
$env:JAVA_TOOL_OPTIONS = "-Xmx" + $(if ($Xmx) { $Xmx } elseif ($env:SKYCRAFT_SERVER_XMX) { $env:SKYCRAFT_SERVER_XMX } else { "4G" })
if ($Threads -gt 0) { $env:SKYCRAFT_BAKE_THREADS = "$Threads" }
$log = Join-Path $out "bake.full.log"
Write-Host "Baking (log: $log) ..."
$ErrorActionPreference = "Continue"
try {
    $p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c .\gradlew.bat runServer --console=plain -Dorg.gradle.jvmargs=-Xmx2G" `
        -WorkingDirectory $mod -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -Wait -PassThru
    Get-Content "$log.err" -ErrorAction SilentlyContinue | Add-Content $log
} finally {
    Remove-Item Env:SKYCRAFT_AUTORUN -ErrorAction SilentlyContinue
    Remove-Item Env:SKYCRAFT_BAKE_THREADS -ErrorAction SilentlyContinue
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}
Select-String -Path $log -Pattern '\[bake\]|\[autorun\]|Exception|FATAL' | ForEach-Object { $_.Line } | Set-Content (Join-Path $out "bake.log")
Get-Content (Join-Path $out "bake.log")
if (-not (Test-Path (Join-Path $staging "manifest.json"))) { Write-Error "The bake produced no manifest; see $log"; exit 1 }
New-Item -ItemType Directory -Force $target | Out-Null
Get-ChildItem $target -File | Remove-Item -Force
Copy-Item (Join-Path $staging "*") $target
Write-Host "Baked data copied to $target :"
Get-ChildItem $target | Format-Table Name, Length
