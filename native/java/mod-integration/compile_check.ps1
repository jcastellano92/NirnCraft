# Compile the staged mod glue against the real Minecraft 26.3 client jar and NeoForge 26.3.0.90 (reference jars in
# toolchains\ref\mc26.3, downloaded for javap/javac only, never shipped), and against
# the mod's own terrain height path (height-subset, compiled from the main tree's mod/ as it stands).
# Catches API drift before the glue moves into mod/. Run from the repo root.
$ErrorActionPreference = 'Stop'
$ref = (Join-Path (& "$PSScriptRoot\..\..\toolchains.ps1") 'ref\mc26.3')
$jdk = (Join-Path (& "$PSScriptRoot\..\..\toolchains.ps1") 'jdk-25.0.4.1+1\bin')
# per worktree (sibling worktrees run this concurrently)
$tag = Split-Path (Resolve-Path "$PSScriptRoot\..\..\..").Path -Leaf
$height = Join-Path $env:TEMP "nirncraft-atlas-height-$tag"
& "$PSScriptRoot\height-subset\build_subset.ps1" -Out $height
$cp = ((@("$ref\client.jar", "$ref\neoforge-universal.jar", "$height\classes") +
        (Get-ChildItem "$ref\libs", "$ref\runtime" -Filter *.jar | ForEach-Object FullName)) -join ';')
$out = Join-Path $env:TEMP "nirncraft-atlas-gluecheck-$tag"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
# compile-stubs: signatures of mod classes still being converted (SkyNetwork ...), never moved into the mod; glue
# marked atlas:sandbox-only needs more of the real mod and is compiled by sandbox_check.ps1 instead (its stub here)
$src = @('native\java\src\com\nirncraft\atlas\AtlasNative.java') +
       (Get-ChildItem native\java\mod-integration\src -Recurse -Filter *.java |
        Where-Object { -not (Select-String -Path $_.FullName -SimpleMatch 'atlas:sandbox-only' -Quiet) } | ForEach-Object FullName) +
       (Get-ChildItem native\java\mod-integration\compile-stubs -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk\javac.exe" --release 25 -proc:none -encoding UTF-8 -cp $cp -d "$out\classes" @src
if ($LASTEXITCODE) { throw 'glue does not compile against 26.3' }
Write-Host 'mod glue compiles against Minecraft 26.3 + NeoForge 26.3.0.90 + the mod''s height path'
