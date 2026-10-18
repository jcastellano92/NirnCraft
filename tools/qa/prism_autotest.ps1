# Builds/refreshes a throw-away Prism instance "Skycraft-autotest" from dist\Skycraft-client-instance.zip (real jar, real mods,
# Oculus + the patched Complementary pack) and runs the client autotest tour in it, so sky/shader/weather screenshots come from
# the same setup a player has. Your normal instance is never touched.
#   tools\qa\prism_autotest.ps1 -Tour "Name:x:z:yaw+Name:x:z:yaw" [-Up 60] [-Pitch 40] [-Wait 500] [-Timeout 1500] [-Shaders on|off]
# Weather tour entries: Name:x:z:yaw:weather:groundPitch:dayTime, separated by commas.
# Example: -Tour "Storm:-400:200:180:storm:-18:6000,Night:-400:200:180:cloudy:-18:18000" -Pitch -12 -CloudQuality 1
# Captures default to 1280x800; -CloudQuality 1 uses the reduced ray budget for lower-end hardware.
# -Live leaves the client running. skycraft-live.txt accepts reload, command <server command>,
# visit <x> <z> <heightAboveSurface> <yaw> <pitch> <weather>, front <x> <z> <radius> <weather> [vx vz],
# wait <ticks>, capture <name>, and stop. File changes replace the pending action queue.
# Add -Extra "-Dskycraft.autotest.tourFront=true" for controlled weather captures; moving natural fronts can
# otherwise override the regional weather command. Each capture logs its actual local weather and precipitation.
param([string]$Tour = "", [switch]$Live, [string]$SaveSource = "", [int]$Up = 60, [int]$Pitch = 40, [int]$Wait = 500, [int]$Timeout = 1500, [string]$Shaders = "on", [string]$Extra = "", [ValidateRange(1,3)][int]$CloudQuality = 2, [ValidateRange(320,7680)][int]$Width = 1280, [ValidateRange(240,4320)][int]$Height = 800)
$ErrorActionPreference = "Stop"
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$pl = "$env:APPDATA\PrismLauncher"
$inst = "$pl\instances\Skycraft-autotest"
if (Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'javaw?\.exe' -and ($_.CommandLine -match '[/\\]instances[/\\]Skycraft-autotest[/\\]' -or $_.CommandLine -match '-Dskycraft\.autotest=true(?:\s|$)') }) {
    throw "The disposable Skycraft-autotest client is still running; close it before refreshing the instance."
}
$zip = "$root\dist\Skycraft-client-instance.zip"
$tmp = "$pl\skycraft-autotest-staging"
# Verify the two disposable targets before recursive deletion/moving.
if ([IO.Path]::GetFullPath($inst) -ne [IO.Path]::Combine([IO.Path]::GetFullPath($pl), 'instances', 'Skycraft-autotest')) { throw "Invalid test instance path" }
if ([IO.Path]::GetFullPath($tmp) -ne [IO.Path]::Combine([IO.Path]::GetFullPath($pl), 'skycraft-autotest-staging')) { throw "Invalid staging path" }
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive $zip $tmp -Force
$src = Get-ChildItem $tmp -Directory | Select-Object -First 1
if (-not $src -or -not $src.FullName.StartsWith(([IO.Path]::GetFullPath($tmp) + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) { throw "No valid instance directory in archive" }
# keep the saves/screenshots of the previous autotest run out of the way: always a fresh instance
Remove-Item $inst -Recurse -Force -ErrorAction SilentlyContinue
$prepared = $src.FullName
$cfg = Get-Content "$prepared\instance.cfg"
$flags = "-Dskycraft.autotest=true -Dskycraft.autotest.world=qa_world -Dskycraft.autotest.tour=$($Tour.Replace(' ', '+')) -Dskycraft.autotest.tourUp=$Up -Dskycraft.autotest.tourPitch=$Pitch -Dskycraft.autotest.tourWait=$Wait $Extra"
if ($SaveSource) {
    $savePath = (Resolve-Path -LiteralPath $SaveSource).Path
    $saveName = Split-Path -Leaf $savePath
    New-Item -ItemType Directory -Force "$prepared\.minecraft\saves" | Out-Null
    Copy-Item -LiteralPath $savePath -Destination "$prepared\.minecraft\saves\$saveName" -Recurse
    $flags += " -Dskycraft.autotest.createflow=true -Dskycraft.autotest.load=$saveName"
}
if ($Live) { $flags += " -Dskycraft.autotest.live=true" }
$cfg = $cfg -replace '^name=.*', 'name=Skycraft-autotest'
$cfg = $cfg | ForEach-Object {
    if ($_ -match '^JvmArgs=(.*)$') { 'JvmArgs="' + $Matches[1].Trim('"') + ' ' + $flags + '"' } else { $_ }
}
$cfg = @($cfg | Where-Object { $_ -notmatch '^(OverrideWindow|MinecraftWinWidth|MinecraftWinHeight|LaunchMaximized)=' }) + @("OverrideWindow=true", "MinecraftWinWidth=$Width", "MinecraftWinHeight=$Height", "LaunchMaximized=false")
Set-Content "$prepared\instance.cfg" $cfg -Encoding ascii
$mc = "$prepared\.minecraft"
New-Item -ItemType Directory -Force "$mc\config" | Out-Null
$opt = "$mc\options.txt"
$o = @("onboardAccessibility:false", "pauseOnLostFocus:false", "skipMultiplayerWarning:true", "tutorialStep:none", "renderDistance:12", "maxFps:60", "fullscreen:false", "lastServer:")
Set-Content $opt $o -Encoding ascii
$op = "$mc\config\oculus.properties"
$enable = if ($Shaders -eq "on") { "true" } else { "false" }
Set-Content $op @("shaderPack=ComplementaryReimagined_r5.9.3.zip", "enableShaders=$enable") -Encoding ascii
$shaderOptions = "$mc\shaderpacks\ComplementaryReimagined_r5.9.3.zip.txt"
$qualityOptions = @(Get-Content $shaderOptions -ErrorAction SilentlyContinue | Where-Object { $_ -notmatch '^CLOUD_QUALITY=' })
Set-Content $shaderOptions ($qualityOptions + "CLOUD_QUALITY=$CloudQuality") -Encoding ascii
Remove-Item "$mc\build\autotest-screenshots" -Recurse -Force -ErrorAction SilentlyContinue
# Rename on the same volume after configuration: Prism watches the instances directory and caches settings.
Move-Item -LiteralPath $prepared -Destination $inst
Remove-Item -LiteralPath $tmp -Recurse -Force
$mc = "$inst\.minecraft"
$prism = "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe"
# Flags live in this instance's configuration, including when an already running Prism handles the launch.
$p = Start-Process $prism -ArgumentList "--launch", "Skycraft-autotest" -PassThru -WindowStyle Hidden
$deadline = (Get-Date).AddSeconds($Timeout)
$log = "$mc\logs\latest.log"
$done = $false
$crashed = $false
$crashPattern = 'Encountered an unexpected exception|Exception ticking world|Game crashed!|LinkageError|OutOfMemoryError|Failed to create shader rendering pipeline|ShaderCompileException|Invalid program'
while ((Get-Date) -lt $deadline -and -not $done) {
    if (Test-Path $log) {
        $completion = if ($Live) { 'Autotest live: ready' } else { 'tour finished' }
        $done = [bool](Select-String -Path $log -Pattern $completion -Quiet)
        if (Select-String -Path $log -Pattern $crashPattern -Quiet) { $crashed = $true; break }
    }
    if (-not $done) { Start-Sleep -Milliseconds 500 }
}
if ($Live -and $done -and -not $crashed) {
    "Live Prism preview ready. Write actions to $mc\skycraft-live.txt; captures land in $mc\build\autotest-screenshots."
    return
}
if ($crashed) { "FAILED (client/server crashed; screenshots so far are kept)" }
elseif (-not $done) { "TIMEOUT (screenshots so far are kept)" }
if ($done -and -not $crashed) {
    # Let the integrated server save and exit after its completion marker.
    $closing = (Get-Date).AddSeconds(30)
    do {
        $clients = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'javaw?\.exe' -and ($_.CommandLine -match '[/\\]instances[/\\]Skycraft-autotest[/\\]' -or $_.CommandLine -match '-Dskycraft\.autotest=true(?:\s|$)') })
        if ($clients.Count -eq 0) { break }
        Start-Sleep 1
    } while ((Get-Date) -lt $closing)
}
Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'javaw?\.exe' -and $_.CommandLine -match 'Skycraft-autotest' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Select-String -Path $log -Pattern 'Autotest' -ErrorAction SilentlyContinue | Select-Object -Last 8 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(200, $_.Line.Length)) }
Get-ChildItem "$mc\build\autotest-screenshots" -ErrorAction SilentlyContinue | Select-Object FullName, Length
if (-not $done -or $crashed) { exit 1 }
if (Select-String -Path $log -Pattern 'Invalid program|Shader compilation failed|Failed to create shader' -Quiet) { exit 1 }
