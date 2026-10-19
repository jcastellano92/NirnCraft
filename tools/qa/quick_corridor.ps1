<#
Quick road check (the fast loop): regenerate ONE tile of a corridor in the running dev server with the current code,
then measure its roads with road_measure.py. Target: well under a minute with the dev server up.

  tools\qa\quick_corridor.ps1 whiterun:riverwood            # best tile (most judged road points)
  tools\qa\quick_corridor.ps1 riverwood:helgen -Index 2     # third-best tile
  tools\qa\quick_corridor.ps1 whiterun:rorikstead -Json out.json

Steps: devserver.ps1 run (auto-starts the dev server, restarts it when mod\src\main changed) -> save-all ->
corridor_tile.py picks a 160-block tile from the roads the dev world has planned (waits while the planner is still
working on a fresh world) -> /nirncraft-dev regen of the tile plus -Margin blocks (fresh chunks, repaved) ->
road_measure.py --box <tile>. The full gate is tools\qa\full_corridors.ps1 (all four corridors, fresh world).
Note: the dev world keeps its planned road NETWORK across restarts; after changing road planning code, add -Replan
(runs /nirncraft-roads replan first).
#>
param(
    [Parameter(Position = 0, Mandatory = $true)][string]$Corridor,
    [int]$Index = 0,
    [int]$Margin = 16,
    [int]$PlanWait = 900,
    [string]$Json = "",
    [switch]$Replan
)
$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Dev = Join-Path $Root "tools\dev\devserver.ps1"
$World = Join-Path $Root "mod\run\devworld-26.3\world"
$t0 = Get-Date
function Lap([string]$what) { Write-Host ("[{0,5:N1} s] {1}" -f ((Get-Date) - $t0).TotalSeconds, $what) }

$first = "save-all flush"
if ($Replan) { $first = "nirncraft-roads replan|@wait 200|save-all flush" }
& $Dev run $first -Wait | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "dev server not usable (exit $LASTEXITCODE)"; exit 2 }
Lap "dev server up"

$tile = $null
while ($true) {
    $tile = & py -3 -I (Join-Path $PSScriptRoot "corridor_tile.py") $World $Corridor --index $Index 2>&1
    if ($LASTEXITCODE -eq 0) { break }
    if ($LASTEXITCODE -ne 3 -or ((Get-Date) - $t0).TotalSeconds -gt $PlanWait) { Write-Host "no tile: $tile"; exit 2 }
    Write-Host "  waiting for the road planner: $tile"
    & $Dev run "@wait 300|save-all flush" -Wait -NoRestart | Out-Null
}
$v = "$tile".Trim().Split(' ')
$x1, $z1, $x2, $z2 = [int]$v[0], [int]$v[1], [int]$v[2], [int]$v[3]
Lap "tile $x1 $z1 $x2 $z2 ($($v[4]) road points)"

& $Dev run ("nirncraft-dev regen {0} {1} {2} {3}" -f ($x1 - $Margin), ($z1 - $Margin), ($x2 + $Margin), ($z2 + $Margin)) -Wait -NoRestart
if ($LASTEXITCODE -ne 0) { Write-Host "regen failed (exit $LASTEXITCODE)"; exit 1 }
Lap "regenerated"

$args2 = @($World, $Corridor, "--box", $x1, $z1, $x2, $z2)
if ($Json) { $args2 += @("--json", $Json) }
& py -3 -I (Join-Path $PSScriptRoot "road_measure.py") @args2
Lap "measured"
