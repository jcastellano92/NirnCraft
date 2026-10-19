# Far-field settlements offline: the real mod classes (sandbox_check.ps1's build of the converted mod with atlas
# moved in) on vanilla 26.3 + NeoForge + FML jars, the main tree's resources read in place, exact terrain and the
# StreetPlan's buildings rendered by atlas_core.dll. Needs native\build.ps1 first. Run from the repo root.
#   .\native\java\mod-integration\harness\run_settlement_harness.ps1 [-Place whiterun] [-Seed 12345] [-Rebuild]
param([string]$Place = 'whiterun', [long]$Seed = 12345, [switch]$Rebuild, [string]$Out = 'native/out/settlements.png', [double]$MidHeight = 45,
      [string]$Sandbox = (Join-Path $env:TEMP 'nirncraft-atlas-sandbox'), [string]$Mod = '', [string]$JavaOpts = '',
      [string]$Main = 'SettlementHarness', [string]$X = '0', [string]$Z = '0')
# -Main SurfaceHarness: the far surface painter (AtlasSurface) on vanilla's overworld, offline: -Seed -X -Z
$ErrorActionPreference = 'Stop'
$ref = (Join-Path (& "$PSScriptRoot\..\..\..\toolchains.ps1") 'ref\mc26.3')
$jdk = (Join-Path (& "$PSScriptRoot\..\..\..\toolchains.ps1") 'jdk-25.0.4.1+1\bin')
# -Sandbox / -Mod: build (and run) from another worktree's mod/ (sandbox_check -Mod), e.g. this worktree's own
$sandbox = $Sandbox
if ($Rebuild -or -not (Test-Path "$sandbox\classes\com\nirncraft\atlas\AtlasSettlements.class")) {
    if ($Mod) { & "$PSScriptRoot\..\sandbox_check.ps1" -Out $sandbox -Mod $Mod } else { & "$PSScriptRoot\..\sandbox_check.ps1" -Out $sandbox }
}
Push-Location $PSScriptRoot
try { $common = (Resolve-Path (& git rev-parse --git-common-dir).Trim()).Path } finally { Pop-Location }
$resources = Join-Path (Split-Path $common -Parent) 'mod\src\main\resources'
# vanilla Minecraft (the patched jar needs a running FML loader) + NeoForge + FML's loader + log4j for the mod's logs
$mc = @("$ref\client.jar", "$ref\neoforge-universal.jar") +
      (Get-ChildItem "$ref\libs", "$ref\runtime", "$ref\runtime-fml" -Filter *.jar | ForEach-Object FullName)
$classes = Join-Path $env:TEMP 'nirncraft-atlas-settlement-harness'
Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
# the harness, the shared seed setup, and Bake reading the shipped bakes without their inputs check (the converted
# code invalidates them; planning live needs registered structure pieces, which only a running game has)
$src = @(Get-ChildItem "$PSScriptRoot\settlements" -Recurse -Filter *.java | ForEach-Object FullName) +
       @("$PSScriptRoot\com\nirncraft\atlas\HarnessSeed.java",
         "$PSScriptRoot\..\..\src\com\nirncraft\atlas\AtlasProbe.java",
         "$PSScriptRoot\..\height-subset\stubs\com\nirncraft\bake\Bake.java")
& "$jdk\javac.exe" --release 25 -proc:none -encoding UTF-8 -nowarn -cp ((@("$sandbox\classes") + $mc) -join ';') -d $classes @src
if ($LASTEXITCODE) { throw 'settlement harness does not compile' }
$cp = (@($classes, "$sandbox\classes", $resources) + $mc) -join ';'
$opts = @(if ($JavaOpts) { $JavaOpts -split ' ' | Where-Object { $_ } }) + @(if ($env:HARNESS_JAVA_OPTS) { $env:HARNESS_JAVA_OPTS -split ' ' | Where-Object { $_ } })
if ($Main -eq 'SurfaceHarness') {
    & "$jdk\java.exe" -Xmx6g @opts -cp $cp com.nirncraft.atlas.SurfaceHarness $Seed $X $Z
} else {
    & "$jdk\java.exe" --enable-native-access=ALL-UNNAMED -Xmx6g @opts -cp $cp com.nirncraft.atlas.SettlementHarness `
        native/build/Release/atlas_core.dll native/data $Out $Place $Seed $MidHeight
}
if ($LASTEXITCODE) { throw 'settlement harness failed' }
