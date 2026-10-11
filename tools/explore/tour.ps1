# Client screenshot tour of minor sites (seed 1234, the autotest world): fresh creative world, then each stop is
# shot from the ground and from the air by ClientAutotest. Screenshots land in mod/run/build/autotest-screenshots.
# Usage: .\tools\explore\tour.ps1 [-Stops "Name:x:z:yaw,..."] [-Wait 500]
param(
    [string]$Stops = "HuntersCamp:-53:747:-90,NordicCairn:107:1352:-90,AbandonedCamp:232:681:-90,HerbGarden:426:1220:-90,MineralVein:-263:1528:-90,BanditLookout:-438:1542:-90,Overlook:-213:1668:-90,DwemerVent:665:1226:-90,Wayshrine:-598:1545:-90,DeadAdventurer:367:584:-90,TreasureCache:-563:548:-90,AyleidWell:2532:6704:-90,VelothiShrine:6466:-1840:-90,YokudanObelisk:-8286:7120:-90,HistSapling:8864:5200:-90,Shipwreck:495:-1979:-90,FishingDock:934:4074:-90",
    [int]$Wait = 500
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$ModDir = Join-Path $Root "mod"
Set-Location $ModDir
Remove-Item -Recurse -Force (Join-Path $ModDir "run\saves\exploretour") -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $ModDir "run\build\autotest-screenshots") -ErrorAction SilentlyContinue
# a first launch shows the accessibility onboarding screen instead of the title screen, which the autotest waits for
$Options = Join-Path $ModDir "run\options.txt"
if (Test-Path $Options) {
    # and an unfocused window would pause the game behind the menu in every screenshot
    (Get-Content $Options) -replace 'onboardAccessibility:true', 'onboardAccessibility:false' -replace 'pauseOnLostFocus:true', 'pauseOnLostFocus:false' |
        Set-Content -Encoding ascii $Options
} else {
    New-Item -ItemType Directory -Force (Join-Path $ModDir "run") | Out-Null
    Set-Content -Encoding ascii $Options "onboardAccessibility:false`npauseOnLostFocus:false"
}
$env:JAVA_TOOL_OPTIONS = "-Dskycraft.autotest=true -Dskycraft.autotest.world=exploretour -Dskycraft.autotest.tour=$Stops -Dskycraft.autotest.tourWait=$Wait"
& .\gradlew.bat runClient --console=plain "-Dorg.gradle.jvmargs=-Xmx2G"
Get-ChildItem (Join-Path $ModDir "run\build\autotest-screenshots") -ErrorAction SilentlyContinue | Select-Object Name
