# Run the exact column source offline: the mod's terrain height path with a world seed, through AtlasExactSource into
# atlas_core.dll, outside Minecraft. Needs native\build.ps1 first. Run from the repo root (any worktree).
#   .\native\java\mod-integration\harness\run_exact_harness.ps1 [-Seed 12345] [-X 0] [-Z 0] [-Yaw 100] [-Threads 8]
#   -Main HeightProfileHarness: CPU profile of the exact columns (sample / noise / plan / isles / rest), no native library
#   -Gpu: GPU exact-height path vs the CPU path: every column of the window for 3 seeds x 3 cameras, raw bits, timings
#   ($env:HARNESS_JAVA_OPTS='-Dharness.gpu=true' with the default main: the render check with GPU-made single-player tiles;
#   -Dharness.above=60 -Dharness.pitch=-30: the camera's height over the ground and its pitch)
# Since the glue moved into mod/ (2026-10-18) it needs the real mod classes: the harness compiles against
# sandbox_check.ps1's build of this worktree's mod (-Sandbox; -Rebuild remakes it), not the old height subset.
param([long]$Seed = 12345, [double]$X = 0, [double]$Z = 0, [double]$Yaw = 100, [int]$Threads = 8,
      [string]$Out = 'native/out/exact_source.png', [string]$Main = 'ExactSourceHarness', [switch]$Gpu,
      [string]$Sandbox = '', [switch]$Rebuild)
if ($Gpu) { $Main = 'GpuHeightHarness' }
$ErrorActionPreference = 'Stop'
$ref = (Join-Path (& "$PSScriptRoot\..\..\..\toolchains.ps1") 'ref\mc26.3')
$jdk = (Join-Path (& "$PSScriptRoot\..\..\..\toolchains.ps1") 'jdk-25.0.4.1+1\bin')
# per worktree, so harness runs in sibling worktrees never overwrite each other's classes
$root = (Resolve-Path "$PSScriptRoot\..\..\..\..").Path
$tag = Split-Path $root -Leaf
if (-not $Sandbox) { $Sandbox = Join-Path $env:TEMP "nirncraft-atlas-sandbox-$tag" }
if ($Rebuild -or -not (Test-Path "$Sandbox\classes\com\nirncraft\atlas\AtlasExactSource.class")) {
    & "$PSScriptRoot\..\sandbox_check.ps1" -Out $Sandbox -Mod (Join-Path $root 'mod')
}
$resources = Join-Path $root 'mod\src\main\resources'
# the Minecraft jars plus what vanilla's bootstrap loads (netty, authlib, commons: toolchains\ref\mc26.3\runtime)
$mc = @("$ref\client.jar", "$ref\neoforge-universal.jar") + (Get-ChildItem "$ref\libs", "$ref\runtime", "$ref\runtime-fml" -Filter *.jar | ForEach-Object FullName)
$classes = Join-Path $env:TEMP "nirncraft-atlas-harness-$tag"
Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
# the harness (with its SkyNetwork, which plays the server back, ahead of the mod's on the class path) and the probe
$src = @('native\java\src\com\nirncraft\atlas\AtlasProbe.java') +
       (Get-ChildItem "$PSScriptRoot" -Recurse -Filter *.java | Where-Object { $_.FullName -notmatch '\\settlements\\' } |
        ForEach-Object FullName) + @("$PSScriptRoot\..\height-subset\stubs\com\nirncraft\bake\Bake.java")
& "$jdk\javac.exe" --release 25 -proc:none -encoding UTF-8 -nowarn -cp ((@("$Sandbox\classes") + $mc) -join ';') -d $classes @src
if ($LASTEXITCODE) { throw 'harness does not compile' }
$cp = (@($classes, "$Sandbox\classes", $resources) + $mc) -join ';'
# $env:HARNESS_JAVA_OPTS: extra JVM options, space separated (-Dharness.debugLod=true, -Dharness.noShadows=true,
# -Dharness.probe=x,y0,y1 to print depth/normal down one pixel column of the exact render)
$opts = @(if ($env:HARNESS_JAVA_OPTS) { $env:HARNESS_JAVA_OPTS -split ' ' | Where-Object { $_ } })
& "$jdk\java.exe" --enable-native-access=ALL-UNNAMED -Xmx6g @opts -cp $cp "com.nirncraft.atlas.$Main" `
    native/build/Release/atlas_core.dll native/data $Out $Seed $X $Z $Yaw $Threads
if ($LASTEXITCODE) { throw 'exact source harness failed' }
