# One command that always produces a Prism instance zip WITH an up-to-date bake, whatever changed in the code.
#
#   tools\build_release.ps1 [-SkipQa]
#
# 1. gradle build (compiles; the bake's hash covers the computing classes' bytecode, so it must be baked after this)
# 2. tools\bake\bake.ps1 (boots a dev server on the Nirn seed, writes mod\src\main\resources\skycraft\baked\*)
# 3. gradle build again (packs the fresh bake; class bytes are unchanged so the hash stays valid)
# 4. packwiz refresh, Prism instance zip (dist\Skycraft-client-instance.zip)
# 5. qa.ps1 -Server -Bake -NoBuild (world boots, quests complete, "Bake is up to date")
param([switch]$SkipQa)
$ErrorActionPreference = "Continue"  # native tools write notices to stderr; failures are caught via $LASTEXITCODE
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root
function Step($n) { Write-Host "`n=== $n ===" -ForegroundColor Cyan }

Step "1/5 build"
Push-Location mod; & .\gradlew.bat build -x test -q; if ($LASTEXITCODE) { throw "gradle build failed" }; Pop-Location

Step "2/5 bake"
& (Join-Path $root "tools\bake\bake.ps1")
if ($LASTEXITCODE) { throw "bake failed" }

Step "3/5 build with bake"
Push-Location mod; & .\gradlew.bat build -x test -q; if ($LASTEXITCODE) { throw "gradle build failed" }; Pop-Location

Step "4/5 pack + Prism instance"
py -3 -I tools/refresh_packwiz.py; if ($LASTEXITCODE) { throw "packwiz refresh failed" }
py -3 -I tools/build_prism_instance.py
if ($LASTEXITCODE) { throw "prism build failed" }

if (-not $SkipQa) {
    Step "5/5 qa (server + bake check)"
    & (Join-Path $root "tools\qa\qa.ps1") -Server -Bake -NoBuild
    if ($LASTEXITCODE) { throw "qa failed" }
}
Write-Host "`nRelease build OK: dist\Skycraft-client-instance.zip" -ForegroundColor Green
