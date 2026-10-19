<#
Native release loop: full Java25 build/tests, independently verified bake in a
fresh E: QA world, repack, full client/server candidates, actual server smoke gate.
Existing worlds and archives are preserved. Run only with exclusive runtime access.
#>
param(
    [switch]$SkipQa,
    [string]$OutputDir = '',
    [string]$Java = '',
    [string]$Xmx = '4G'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
$scratch = if ($env:NIRNCRAFT_263_SCRATCH) { [IO.Path]::GetFullPath($env:NIRNCRAFT_263_SCRATCH) } else { 'E:\NirnCraft-scratch\migration-26.3' }
if (-not $Java) {
    $javaRoot = if ($env:NIRNCRAFT_JAVA25_HOME) { $env:NIRNCRAFT_JAVA25_HOME } else { (Get-Content -LiteralPath 'E:\NirnCraft-scratch\toolchains\java25-location.txt' -Raw).Trim() }
    $Java = Join-Path $javaRoot 'bin/java.exe'
}
$Java = (Resolve-Path -LiteralPath $Java).Path
if (-not $OutputDir) { $OutputDir = Join-Path $root ('dist/release-26.3-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$clientZip = Join-Path $OutputDir 'NirnCraft-26.3-client-instance.zip'
$serverZip = Join-Path $OutputDir 'NirnCraft-26.3-server.zip'
if ((Test-Path -LiteralPath $clientZip) -or (Test-Path -LiteralPath $serverZip)) { throw 'Preserve previous packages; choose a fresh OutputDir.' }
$oldJava = $env:NIRNCRAFT_JAVA25_HOME
$env:NIRNCRAFT_JAVA25_HOME = Split-Path (Split-Path $Java -Parent) -Parent
try {
    & py -3 -I tools/build_server_instance.py --check-java --java $Java
    if ($LASTEXITCODE -ne 0) { throw 'Select Java 25 before starting the release build.' }
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    Write-Host '1/5 Full exact-platform build and native tests'
    & py -3 -I tools/dev/build_263.py --log (Join-Path $OutputDir 'build.log') build
    if ($LASTEXITCODE -ne 0) { throw 'Native build/tests failed.' }
    Write-Host '2/5 Verify/rebake in a new native world; preserve old worlds'
    & (Join-Path $root 'tools/bake/bake.ps1') -Xmx $Xmx
    if ($LASTEXITCODE -ne 0) { throw 'Native independent bake verification failed.' }
    Write-Host '3/5 Package the verified native bakes'
    & py -3 -I tools/dev/build_263.py --log (Join-Path $OutputDir 'jar.log') jar
    if ($LASTEXITCODE -ne 0) { throw 'Native jar build failed.' }
    $core = Get-ChildItem -LiteralPath (Join-Path $scratch 'build/libs') -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc|slim)\.jar$' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $core) { throw 'Native build did not produce a core jar.' }
    & py -3 -I tools/refresh_packwiz.py
    if ($LASTEXITCODE -ne 0) { throw 'Pack index refresh failed.' }
    & py -3 -I tools/validate_pack.py --core-jar $core.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Exact pack/core validation failed.' }
    Write-Host '4/5 Full server installation and client package'
    & py -3 -I tools/build_server_instance.py --core-jar $core.FullName --java $Java --output-zip $serverZip --work-root (Join-Path $scratch 'server-build') --cache (Join-Path $scratch 'server-cache')
    if ($LASTEXITCODE -ne 0) { throw 'Native full server packaging failed.' }
    & py -3 -I tools/build_prism_instance.py --core-jar $core.FullName --output-zip $clientZip
    if ($LASTEXITCODE -ne 0) { throw 'Native client packaging failed.' }
    if (-not $SkipQa) {
        Write-Host '5/5 Actual full-mod installed server boot, live bake check, save and clean stop'
        & py -3 -I tools/qa/test_server_package_263.py --archive $serverZip --java $Java --work-root (Join-Path $scratch 'server-qa') --heap $Xmx
        if ($LASTEXITCODE -ne 0) { throw 'Native installed server acceptance failed; candidates/evidence retained.' }
        Write-Host "Server gate passed. Client visual and multiplayer connection acceptance remain separate: $OutputDir"
    } else {
        Write-Host "Candidate packages created; dedicated server QA was not run (-SkipQa): $OutputDir"
    }
} finally { $env:NIRNCRAFT_JAVA25_HOME = $oldJava }
exit 0
