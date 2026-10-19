# PowerShell script to run the local NirnCraft Client smoke test / autotest
# Usage: .\tools\autotest.ps1 [-World <name>]
#   -World: create a fresh creative world of that name and walk through the Skyrim screens without a person
#           (screenshots land in mod/run/build/autotest-screenshots). Without it, the autotest waits for you to
#           open a world yourself.
param([string]$World = "")

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
$ModDir = Join-Path $RootDir "mod"

Write-Host "=== NirnCraft Local Client Autotest ===" -ForegroundColor Cyan
Write-Host "Target Mod Dir: $ModDir"

Set-Location $ModDir

# Gradle's own -D flags never reach the forked game JVM: pass the autotest switches through JAVA_TOOL_OPTIONS.
$opts = "-Dnirncraft.autotest=true"
if ($World -ne "") { $opts = "$opts -Dnirncraft.autotest.world=$World" }
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS $opts".Trim()

# Launch Minecraft Forge client with autotest flag enabled
& .\gradlew.bat runClient --console=plain

Write-Host "Autotest run finished." -ForegroundColor Green
$Screenshots = Join-Path $ModDir "run\build\autotest-screenshots"
if (Test-Path $Screenshots) {
    Write-Host "Screenshots captured at: $Screenshots" -ForegroundColor Cyan
    Get-ChildItem $Screenshots
}
