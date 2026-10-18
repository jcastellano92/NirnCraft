# Prepare and optionally launch the isolated Minecraft 26.3 / NeoForge / Java 25 full-pack preview.
# The legacy Skycraft-autotest and normal Skycraft instances are preserved.
# Use -PrepareOnly before the owner's runtime launch gate; this never starts Minecraft.
param(
    [string]$Tour = "", [switch]$Live, [switch]$PrepareOnly, [string]$SaveSource = "", [string]$Server = "",
    [int]$Up = 60, [int]$Pitch = 40, [int]$Wait = 500, [int]$Timeout = 1500,
    [ValidateSet("off", "on")][string]$Shaders = "off", [string]$Extra = "",
    [ValidateRange(1,3)][int]$CloudQuality = 2,
    [ValidateRange(320,7680)][int]$Width = 1280, [ValidateRange(240,4320)][int]$Height = 800,
    [string]$Java = "", [string]$SourceZip = "", [string]$PrismRoot = "$env:APPDATA\PrismLauncher",
    [string]$PrismExe = "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe"
)
$ErrorActionPreference = "Stop"
if ($Shaders -ne "off") {
    throw "The customized shader pack is preserved but disabled until its native 26.3 integration is accepted. Start with -Shaders off."
}
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$name = "Skycraft-26.3-autotest"
$inst = Join-Path $PrismRoot "instances\$name"
$script = Join-Path $PSScriptRoot "prepare_prism_263.py"
$prepareArgs = @("-3", $script, "--prism-root", $PrismRoot, "--width", "$Width", "--height", "$Height",
    "--up", "$Up", "--pitch", "$Pitch", "--tour-wait", "$Wait")
if ($Java) { $prepareArgs += @("--java", $Java) }
if ($SourceZip) { $prepareArgs += @("--archive", $SourceZip) }
if ($Tour) { $prepareArgs += @("--tour", $Tour) }
if ($Extra) { $prepareArgs += @("--extra=$Extra") }
if ($SaveSource) { $prepareArgs += @("--save-source", $SaveSource) }
if ($Server) { $prepareArgs += @("--server", $Server) }
if ($Live) { $prepareArgs += "--live" }
& py @prepareArgs
if ($LASTEXITCODE -ne 0) { throw "Native Prism preparation failed; no launch requested." }
if ($PrepareOnly) {
    "Native full-pack preview prepared. Minecraft was not launched. Prior preview saves/screenshots remain in the preparation backup."
    return
}
if (-not (Test-Path -LiteralPath $PrismExe -PathType Leaf)) { throw "Prism Launcher executable not found: $PrismExe" }
$mc = Join-Path $inst ".minecraft"
# Prism scopes its running-launcher IPC to this data directory too.
$launchArgs = @("--dir", ('"' + [IO.Path]::GetFullPath($PrismRoot) + '"'), "--launch", $name)
Start-Process -FilePath $PrismExe -ArgumentList $launchArgs -WindowStyle Hidden | Out-Null
$deadline = (Get-Date).AddSeconds($Timeout)
$log = Join-Path $mc "logs\latest.log"
$completion = if ($Live) { 'Autotest live: ready' } else { 'tour finished|Skycraft Autotest: Completed successfully' }
$crashPattern = 'Encountered an unexpected exception|Exception ticking world|Game crashed!|LinkageError|OutOfMemoryError|Failed to create shader rendering pipeline|ShaderCompileException|Invalid program|Failed to load registries|Failed to parse data|Caught exception during event|Loading errors encountered'
$done = $false
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $log) {
        if (Select-String -LiteralPath $log -Pattern $crashPattern -Quiet) {
            throw "Native preview failed. Inspect $log; this script does not kill Minecraft or remove its evidence."
        }
        if (Select-String -LiteralPath $log -Pattern $completion -Quiet) { $done = $true; break }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $done) { throw "Native preview readiness timed out; inspect $log and tools/dev/live_world.py status. No process was killed." }
if ($Live) {
    "Native live preview ready: $mc\skycraft-live.txt; captures: $mc\build\autotest-screenshots."
    return
}
Select-String -LiteralPath $log -Pattern 'Autotest' | Select-Object -Last 8 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(200, $_.Line.Length)) }
Get-ChildItem (Join-Path $mc "build\autotest-screenshots") -ErrorAction SilentlyContinue | Select-Object FullName, Length
"Native tour completion was observed; review capture receipts and images before accepting visual quality."
