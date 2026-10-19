# Compares the per-chunk block hashes of two benchmark runs (mod/run/nirncraft-perf/bench-*.hashes).
#
#   tools/bake/compare_hashes.ps1 <a.hashes> <b.hashes>
#
# Chunks on the rim of each benchmark square can legitimately differ between runs: features of chunks outside the
# square (generated in whatever order the workers reach them) may spill into them. The interior must match exactly
# when both runs generate the same world; a mismatch there means the change altered the output.
param([Parameter(Mandatory = $true)][string]$A, [Parameter(Mandatory = $true)][string]$B)
$ma = @{}; $mb = @{}
foreach ($l in Get-Content $A) { $p = $l -split ' '; $ma["$($p[0]) $($p[1]) $($p[2])"] = $p[3] }
foreach ($l in Get-Content $B) { $p = $l -split ' '; $mb["$($p[0]) $($p[1]) $($p[2])"] = $p[3] }
# rim = the outermost ring of each area's square
$bounds = @{}
foreach ($k in $ma.Keys) {
    $p = $k -split ' '; $n = $p[0]; $x = [int]$p[1]; $z = [int]$p[2]
    if (-not $bounds.ContainsKey($n)) { $bounds[$n] = @($x, $x, $z, $z) }
    $bb = $bounds[$n]
    $bounds[$n] = @([Math]::Min($bb[0], $x), [Math]::Max($bb[1], $x), [Math]::Min($bb[2], $z), [Math]::Max($bb[3], $z))
}
$same = 0; $rimDiff = 0; $innerDiff = 0; $missing = 0
foreach ($k in $ma.Keys) {
    if (-not $mb.ContainsKey($k)) { $missing++; continue }
    if ($ma[$k] -eq $mb[$k]) { $same++; continue }
    $p = $k -split ' '; $bb = $bounds[$p[0]]; $x = [int]$p[1]; $z = [int]$p[2]
    if ($x -eq $bb[0] -or $x -eq $bb[1] -or $z -eq $bb[2] -or $z -eq $bb[3]) { $rimDiff++ } else { $innerDiff++; Write-Host "differs: $k" }
}
Write-Host "chunks: $($ma.Count); identical $same; differ on the rim $rimDiff; differ inside $innerDiff; missing $missing"
if ($innerDiff -gt 0) { exit 1 }
