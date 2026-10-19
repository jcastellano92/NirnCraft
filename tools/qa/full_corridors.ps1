<#
The full corridor gate (the road acceptance numbers) through the dev server, once at the end of a phase:
fresh dev world -> wait for the four corridors' roads to be planned -> regenerate every chunk within 2 chunks of their
road points in ONE regen (fresh generation, roads paved) -> road_measure.py over all four corridors.

  tools\qa\full_corridors.ps1                       # fresh world (devserver reset), the 4 standard corridors
  tools\qa\full_corridors.ps1 -Reuse                # keep the dev world: replan + regenerate (faster, no 87 s boot)
  tools\qa\full_corridors.ps1 -Corridors whiterun:riverwood,riverwood:helgen -Json out.json

The standard corridors (docs/FINISH-PLAN.md section 2): whiterun:riverwood riverwood:helgen whiterun:rorikstead
dragon_bridge:karthwasten. Output = road_measure.py's report. Iterate with quick_corridor.ps1 (one tile, ~15 s) and run
this once when the road work of a phase is done. Exit code = 0 surface pass / 1 findings / 2 setup failure.
#>
param(
    [string[]]$Corridors = @("whiterun:riverwood", "riverwood:helgen", "whiterun:rorikstead", "dragon_bridge:karthwasten"),
    [switch]$Reuse,
    [int]$Region = 2,
    [int]$PlanWait = 1200,
    [string]$Json = "",
    [string]$RuntimeRoot = ""
)
$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Dev = Join-Path $Root "tools\dev\devserver.ps1"
$World = Join-Path $Root "mod\run\devworld-26.3\world"
$DevSettings = @{}
if ($RuntimeRoot) { $DevSettings.RuntimeRoot = $RuntimeRoot; $World = Join-Path ([IO.Path]::GetFullPath($RuntimeRoot)) 'world' }
$Tile = Join-Path $PSScriptRoot "corridor_tile.py"
$t0 = Get-Date
function Lap([string]$what) { Write-Host ("[{0,6:N1} s] {1}" -f ((Get-Date) - $t0).TotalSeconds, $what) }

if ($Reuse) {
    & $Dev @DevSettings run "nirncraft-roads replan|save-all flush" -Wait | Out-Null
} else {
    & $Dev @DevSettings reset | Out-Null
}
if ($LASTEXITCODE -ne 0) { Write-Host "dev server not usable (exit $LASTEXITCODE)"; exit 2 }
Lap $(if ($Reuse) { "dev world replanned" } else { "fresh dev world booted" })

$runs = $null
$lastPark = ""
while ($true) {
    & $Dev @DevSettings run "save-all flush" -Wait -NoRestart | Out-Null
    $runs = & py -3 -I $Tile $World @Corridors --region $Region 2>&1
    $code = $LASTEXITCODE
    if ($code -eq 0) { break }
    if ($code -ne 3 -or ((Get-Date) - $t0).TotalSeconds -gt $PlanWait) { Write-Host "cannot select the corridors: $runs"; exit 2 }
    Write-Host "  waiting for the road planner: $($runs | Select-Object -Last 1)"
    # the planner works on the links nearest to the world spawn first: move it to the missing corridor
    $at = ($runs | Select-Object -Last 1) -replace '.* at (-?\d+) (-?\d+)$', 'setworldspawn $1 100 $2'
    # Poll actual saved-road readiness; a fixed twenty-second wait kept delaying
    # corridors that completed just after the previous check.
    if ($at -match "^setworldspawn" -and $at -ne $lastPark) {
        & $Dev @DevSettings run "$at|@wait 20" -Wait -NoRestart | Out-Null
        $lastPark = $at
    } else { & $Dev @DevSettings run "@wait 20" -Wait -NoRestart | Out-Null }
}
$spec = ($runs | Where-Object { $_ -match "^-?\d+:" } | Select-Object -First 1)
Lap ("regenerating: " + ($runs | Where-Object { $_ -match "chunks" } | Select-Object -First 1))
& $Dev @DevSettings run "@timeout 1500|nirncraft-dev regenchunks $spec|save-all flush" -Wait -NoRestart
if ($LASTEXITCODE -ne 0) { Write-Host "regen failed (exit $LASTEXITCODE)"; exit 1 }
Lap "regenerated and paved"

$a = @($World) + $Corridors + @("--gate")
if ($Json) { $a += @("--json", $Json) }
& py -3 -I (Join-Path $PSScriptRoot "road_measure.py") @a
$rc = $LASTEXITCODE
Lap "measured"
exit $rc
