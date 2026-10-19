# Runs the real dev client through a tour (teleport + screenshots) and waits for it to finish.
#   tools\qa\vis_tour.ps1 -Tour "Name:x:z:yaw+Name:x:z:yaw" [-Up 60] [-Pitch 40] [-Wait 500] [-Timeout 900]
# Screenshots land in mod\run\build\autotest-screenshots; the client log in mod\run\vis-client.log.
param([string]$Tour, [int]$Up = 60, [int]$Pitch = 40, [int]$Wait = 500, [int]$Timeout = 900)
$Mod = (Resolve-Path "$PSScriptRoot\..\..\mod").Path
$Run = "$Mod\run"
$opt = "$Run\options.txt"
if (Test-Path $opt) {
    $t = Get-Content $opt
    $t = $t -replace 'onboardAccessibility:true', 'onboardAccessibility:false' -replace 'pauseOnLostFocus:true', 'pauseOnLostFocus:false' -replace 'skipMultiplayerWarning:false', 'skipMultiplayerWarning:true'
    Set-Content $opt $t -Encoding ascii
}
Remove-Item -Recurse -Force "$Run\build\autotest-screenshots" -ErrorAction SilentlyContinue
$env:JAVA_TOOL_OPTIONS = "-Xmx4G -Dnirncraft.autotest=true -Dnirncraft.autotest.world=qa_world -Dnirncraft.autotest.tour=$($Tour.Replace(' ', '+')) -Dnirncraft.autotest.tourUp=$Up -Dnirncraft.autotest.tourPitch=$Pitch -Dnirncraft.autotest.tourWait=$Wait"
$out = "$Run\vis-client.log"
$p = Start-Process -FilePath "$Mod\gradlew.bat" -ArgumentList "runClient", "--console=plain" -WorkingDirectory $Mod `
    -RedirectStandardOutput $out -RedirectStandardError "$out.err" -PassThru -WindowStyle Normal
Remove-Item Env:JAVA_TOOL_OPTIONS
if (-not $p.WaitForExit($Timeout * 1000)) {
    Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'java' -and $_.CommandLine -match 'NirnCraft' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    "TIMEOUT"
}
Select-String -Path $out -Pattern 'Autotest' | Select-Object -Last 12 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(200, $_.Line.Length)) }
Get-ChildItem "$Run\build\autotest-screenshots" -ErrorAction SilentlyContinue | Select-Object Name, Length

