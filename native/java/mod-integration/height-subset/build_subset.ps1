# Compile the mod's terrain height path (files.txt: TamrielHeight, TamrielMap, MapRaster, hydrology, peaks, road and
# pad shaping ...) on its own, against the real Minecraft 26.3 jars, so the far-field glue and the offline harness run
# the game's exact generator without the rest of the mod. The sources are snapshotted read-only from the main tree's
# mod/ (whatever state its 26.3 conversion is in); stubs/ stands in for the few edges that lead out of the height
# path (see each stub's comment). Run from the repo root (any worktree).
#   .\native\java\mod-integration\height-subset\build_subset.ps1 [-Out <dir>] [-Mod <main tree>\mod]
# Writes <Out>\classes (compiled subset + stubs) and <Out>\res (the resources the height path reads).
param([string]$Out = (Join-Path $env:TEMP 'skycraft-aether-height'), [string]$Mod = '')
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $Mod) {
    # the main checkout: the parent of the shared .git directory
    Push-Location $here
    try { $common = (Resolve-Path (& git rev-parse --git-common-dir).Trim()).Path } finally { Pop-Location }
    $Mod = Join-Path (Split-Path $common -Parent) 'mod'
}
$ref = 'F:\Projects\Personal\Skycraft\toolchains\ref\mc26.3'
$jdk = 'F:\Projects\Personal\Skycraft\toolchains\jdk-25.0.4.1+1\bin'
$cp = ((@("$ref\client.jar", "$ref\neoforge-universal.jar") + (Get-ChildItem "$ref\libs" -Filter *.jar | ForEach-Object FullName)) -join ';')

Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
$src = Join-Path $Out 'src'
$files = Get-Content (Join-Path $here 'files.txt') | Where-Object { $_.Trim() }
foreach ($f in $files) {
    $from = Join-Path "$Mod\src\main\java" $f
    if (-not (Test-Path $from)) { throw "height subset: $f is missing from $Mod" }
    $to = Join-Path $src $f
    New-Item -ItemType Directory -Force (Split-Path $to -Parent) | Out-Null
    Copy-Item $from $to
}
$all = @(Get-ChildItem "$here\stubs", $src -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk\javac.exe" --release 25 -proc:none -implicit:none -encoding UTF-8 -nowarn -cp $cp `
    -sourcepath "$here\stubs;$src" -d "$Out\classes" @all
if ($LASTEXITCODE) { throw 'height subset does not compile against 26.3 (see the errors above: the mod changed?)' }

$res = Join-Path $Out 'res'
foreach ($r in 'skycraft\tamriel', 'skycraft\baked', 'data\skycraft\worldgen\noise') {
    New-Item -ItemType Directory -Force (Split-Path "$res\$r" -Parent) | Out-Null
    Copy-Item -Recurse "$Mod\src\main\resources\$r" "$res\$r"
}
Write-Host "height subset: $($files.Count) mod files + stubs compiled against Minecraft 26.3 -> $Out"
