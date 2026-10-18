# Run the exact column source offline: the mod's terrain height path (height-subset, from the main tree's mod/) with a
# world seed, through AetherExactSource into aether_core.dll, outside Minecraft. Needs native\build.ps1 first.
# Run from the repo root (any worktree).
#   .\native\java\mod-integration\harness\run_exact_harness.ps1 [-Seed 12345] [-X 0] [-Z 0] [-Yaw 100] [-Threads 8]
param([long]$Seed = 12345, [double]$X = 0, [double]$Z = 0, [double]$Yaw = 100, [int]$Threads = 8,
      [string]$Out = 'native/out/exact_source.png')
$ErrorActionPreference = 'Stop'
$ref = 'F:\Projects\Personal\Skycraft\toolchains\ref\mc26.3'
$jdk = 'F:\Projects\Personal\Skycraft\toolchains\jdk-25.0.4.1+1\bin'
$height = Join-Path $env:TEMP 'skycraft-aether-height'
& "$PSScriptRoot\..\height-subset\build_subset.ps1" -Out $height
# the Minecraft jars plus what vanilla's bootstrap loads (netty, authlib, commons: toolchains\ref\mc26.3\runtime)
$mc = @("$ref\client.jar", "$ref\neoforge-universal.jar") + (Get-ChildItem "$ref\libs", "$ref\runtime" -Filter *.jar | ForEach-Object FullName)
$classes = Join-Path $env:TEMP 'skycraft-aether-harness'
Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
# all the glue, with the harness's SkyNetwork (it plays the server back) instead of compile-stubs
$src = @('native\java\src\com\skycraft\aether\AetherNative.java', 'native\java\src\com\skycraft\aether\AetherProbe.java') +
       (Get-ChildItem native\java\mod-integration\src, "$PSScriptRoot" -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk\javac.exe" --release 25 -proc:none -encoding UTF-8 -nowarn -cp ((@("$height\classes") + $mc) -join ';') -d $classes @src
if ($LASTEXITCODE) { throw 'harness does not compile' }
$cp = (@($classes, "$height\classes", "$height\res") + $mc) -join ';'
& "$jdk\java.exe" --enable-native-access=ALL-UNNAMED -Xmx6g $env:HARNESS_JAVA_OPTS -cp $cp com.skycraft.aether.ExactSourceHarness `
    native/build/Release/aether_core.dll native/data $Out $Seed $X $Z $Yaw $Threads
if ($LASTEXITCODE) { throw 'exact source harness failed' }
