# Compile and run the Java 25 FFM probe against the built aether_core.dll. Run from the repo root.
#   .\native\java\run_probe.ps1 [-Backend vulkan|cpu]
param([string]$Backend = 'vulkan')
$ErrorActionPreference = 'Stop'
. 'F:\Projects\Personal\Skycraft\toolchains\env.ps1'
$src = Get-ChildItem native/java/src -Recurse -Filter *.java | ForEach-Object FullName
& "$env:JAVA25_HOME\bin\javac.exe" --release 25 -d native/build/java @src
if ($LASTEXITCODE) { throw 'javac failed' }
& "$env:JAVA25_HOME\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp native/build/java com.skycraft.aether.AetherProbe `
    native/build/Release/aether_core.dll native/data native/out/java_probe.png $Backend
if ($LASTEXITCODE) { throw 'probe failed' }
