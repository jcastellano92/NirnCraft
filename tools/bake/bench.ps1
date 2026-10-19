# NirnCraft worldgen benchmark (headless).
#
#   tools/bake/bench.ps1 [-Radius 5] [-Tasks "columns,bench:5"] [-Port 25747] [-Keep] [-Label before]
#
# Boots the dev server (./gradlew runServer, only NirnCraft loaded) on a FRESH world with the fixed Nirn seed, runs the
# autorun tasks (see com.nirncraft.perf.PerfAutorun), then stops. The chunk benchmark generates five fixed areas
# (Whiterun, Throat of the World, Skingrad, Balmora, a far realm) and prints chunks/s, chunks/s per worker, the
# per-stage CPU split and the NirnCraft hot spots (Java Flight Recorder, in process). Results:
#   mod/run/nirncraft-perf/bench-<time>.txt  (+ .jfr: open it in JDK Mission Control or `jfr print`)
#   tools/bake/out/bench-<label>.log        (the [perf] lines)
# Only one Gradle run per worktree at a time. Use -Keep to reuse the existing world (generation is then mostly loading).
param(
    [int]$Radius = 5,
    [string]$Tasks = "",
    [int]$Port = 25747,
    [switch]$Keep,
    [string]$Label = "run",
    [string]$JvmOpts = ""
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$mod = Join-Path $root "mod"
$run = Join-Path $mod "run"
$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Force $run, $out | Out-Null
if ($Tasks -eq "") { $Tasks = "columns,bench:$Radius" }

if (-not $Keep -and (Test-Path (Join-Path $run "world"))) { Remove-Item -Recurse -Force (Join-Path $run "world") }
Set-Content -Path (Join-Path $run "eula.txt") -Value "eula=true" -Encoding ascii
$props = Join-Path $run "server.properties"
$lines = @()
if (Test-Path $props) { $lines = Get-Content $props | Where-Object { $_ -notmatch '^(level-seed|server-port|online-mode|level-type|max-tick-time)=' } }
$lines += "level-seed=2428247"   # the fixed Nirn seed ("Nirn".hashCode()), see com.nirncraft.bake.WorldSeed
$lines += "server-port=$Port"
$lines += "online-mode=false"
$lines += "level-type=minecraft\:normal"
$lines += "max-tick-time=-1"
Set-Content -Path $props -Value $lines -Encoding ascii

$env:NIRNCRAFT_AUTORUN = $Tasks
# deep stacks so JFR samples keep their root frames (stage attribution)
$env:JAVA_TOOL_OPTIONS = "-XX:FlightRecorderOptions:stackdepth=1024 $JvmOpts"
$log = Join-Path $out "bench-$Label.full.log"
Write-Host "Running '$Tasks' on a $(if ($Keep) {'kept'} else {'fresh'}) world; log: $log"
Push-Location $mod
$ErrorActionPreference = "Continue"
try {
    # a plain process with redirected output, so the JVM's stderr isn't turned into PowerShell errors
    $p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c .\gradlew.bat runServer --console=plain -Dorg.gradle.jvmargs=-Xmx2G" `
        -WorkingDirectory $mod -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -Wait -PassThru
    Get-Content "$log.err" -ErrorAction SilentlyContinue | Add-Content $log
} finally {
    Pop-Location
    Remove-Item Env:NIRNCRAFT_AUTORUN -ErrorAction SilentlyContinue
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}
$summary = Join-Path $out "bench-$Label.log"
Select-String -Path $log -Pattern '\[perf\]|\[autorun\]|Preparing spawn area|Done \(|Exception|ERROR' | ForEach-Object { $_.Line } | Set-Content $summary
Get-Content $summary
