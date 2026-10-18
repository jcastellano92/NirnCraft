# Compile the staged mod glue against the real Minecraft 26.3 client jar and NeoForge 26.3.0.90 (reference jars in
# F:\Projects\Personal\Skycraft\toolchains\ref\mc26.3, downloaded for javap/javac only, never shipped).
# Catches API drift before the glue moves into mod/. Run from the repo root.
$ErrorActionPreference = 'Stop'
$ref = 'F:\Projects\Personal\Skycraft\toolchains\ref\mc26.3'
$jdk = 'F:\Projects\Personal\Skycraft\toolchains\jdk-25.0.4.1+1\bin'
$libs = 'joml-1.10.9', 'fastutil-8.5.18', 'guava-33.6.0-jre', 'datafixerupper-10.0.21', 'brigadier-1.3.11', 'lwjgl-3.4.3', 'bus-8.0.5', 'slf4j'
$cp = (@("$ref\client.jar", "$ref\neoforge-universal.jar") + ($libs | ForEach-Object { "$ref\libs\$_.jar" })) -join ';'
$out = Join-Path $env:TEMP 'skycraft-aether-gluecheck'
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\stub\com\skycraft" | Out-Null
$stub = @'
package com.skycraft;
/** compile stub: the real class lives in the mod */
public final class Skycraft { public static final org.slf4j.Logger LOGGER = null; }
'@
# no BOM: javac rejects one (Set-Content -Encoding utf8 writes it on Windows PowerShell 5.1)
[IO.File]::WriteAllText("$out\stub\com\skycraft\Skycraft.java", $stub, (New-Object Text.UTF8Encoding $false))
$src = @("$out\stub\com\skycraft\Skycraft.java", 'native\java\src\com\skycraft\aether\AetherNative.java') +
       (Get-ChildItem native\java\mod-integration\src -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk\javac.exe" --release 25 -proc:none -cp $cp -d "$out\classes" @src
if ($LASTEXITCODE) { throw 'glue does not compile against 26.3' }
Write-Host 'mod glue compiles against Minecraft 26.3 + NeoForge 26.3.0.90'
