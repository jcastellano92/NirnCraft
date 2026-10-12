<#
Skycraft QA harness: one command, a short report. Built so automated sessions read ~40 lines instead of raw logs.

  tools\qa\qa.ps1                 # build + static checks (fast, ~3-5 min)
  tools\qa\qa.ps1 -Server         # + headless dedicated server: worldgen, all selftests, clean stop
  tools\qa\qa.ps1 -Server -Client # + client autotest tour and a single contact-sheet image of all screenshots
  tools\qa\qa.ps1 -NoBuild ...    # skip the Gradle build (reuse the last one)
  tools\qa\qa.ps1 -Layout        # no build: static checks only, incl. the layout lint (places vs map rasters, ~2 s;
                                  # the lint also runs in every default run)
  tools\qa\qa.ps1 -NoBuild -Parity # + print the 10 lowest cells of the Skyrim-parity scorecard (tools/qa/parity.py)

Writes the report to mod\run\qa-report.txt and prints it. Exit code 0 = all PASS.
Extra server commands: -ServerCmds "cmd one|cmd two" ('+' or spaces both fine). Client tour spots: -Tour "Name:x:z;Name:x:z".
#>
param(
    [switch]$Server,
    [switch]$Client,
    [switch]$NoBuild,
    [switch]$Layout,
    [switch]$Parity,
    [switch]$Novelty,
    [string]$ServerCmds = "",
    [string]$Tour = "",
    [int]$ServerTimeout = 900,
    [int]$ClientTimeout = 1500
)
$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Mod = Join-Path $Root "mod"
$Run = Join-Path $Mod "run"
New-Item -ItemType Directory -Force $Run | Out-Null
$Report = New-Object System.Collections.Generic.List[string]
$Fail = $false
function Add([string]$line) { $Report.Add($line) }
function Result([string]$name, [bool]$ok, [string]$detail) {
    if (-not $ok) { $script:Fail = $true }
    Add(("{0,-6} {1,-18} {2}" -f ($(if ($ok) { "PASS" } else { "FAIL" })), $name, $detail))
}
$py = "py"

# ------------------------------------------------------------------ build
if (-not $NoBuild) {
    $log = Join-Path $Run "qa-build.log"
    Push-Location $Mod
    & .\gradlew.bat build --console=plain "-Dorg.gradle.jvmargs=-Xmx2G" *> $log
    Pop-Location
    $txt = Get-Content $log -Raw
    $ok = $txt -match "BUILD SUCCESSFUL"
    $errs = @(Select-String -Path $log -Pattern "\.java:\d+: error:" | ForEach-Object { $_.Line.Trim() })
    $dups = @(Select-String -Path $log -Pattern "Duplicate lang key" | ForEach-Object { $_.Line.Trim() })
    Result "build" $ok ("{0} compile errors, {1} duplicate lang keys" -f $errs.Count, $dups.Count)
    $errs | Select-Object -First 15 | ForEach-Object { Add("       $_") }
}

# ------------------------------------------------------------------ static checks
Push-Location $Root
$a = & $py -3 -I tools/check_assets.py 2>&1 | Select-Object -Last 1
Result "assets" ($a -match " 0 problems") "$a"
$audit = & $py -3 -I tools/audit_items.py 2>&1
# lang-only display keys and the optional runtime dump are known non-gaps
$gaps = @($audit | Where-Object { $_ -match "^GAP " -and $_ -notmatch "^GAP \(all\)" -and $_ -notmatch "^GAP (potion_of|poison_of|legendary_fish):" })
Result "items" ($gaps.Count -eq 0) ("{0} gaps; {1}" -f $gaps.Count, ($audit | Select-Object -Last 1))
$gaps | Select-Object -First 10 | ForEach-Object { Add("       $_") }
$q = & node tools/quest_lang.js 2>&1
$qerr = @($q | Where-Object { $_ -match "(?i)error|problem|missing|unknown" })
Result "quests(static)" ($qerr.Count -eq 0) ("{0}" -f ($q | Select-Object -Last 1))
$qerr | Select-Object -First 10 | ForEach-Object { Add("       $_") }
$s = & $py -3 -I tools/music/sound_catalog.py --check 2>&1
$serr = @($s | Where-Object { $_ -match "(?i)missing|unregistered|error" -and $_ -notmatch "0 missing" })
Result "sounds" ($serr.Count -eq 0) ("{0}" -f ($s | Select-Object -Last 1))
$serr | Select-Object -First 8 | ForEach-Object { Add("       $_") }
$d = & $py -3 -I tools/qa/data_checks.py 2>&1
$derr = @($d | Where-Object { $_ -match "^ERR" })
Result "data" ($derr.Count -eq 0) ("{0}" -f ($d | Select-Object -Last 1))
$derr | Select-Object -First 15 | ForEach-Object { Add("       $_") }
$ll = & $py -3 -I tools/qa/layout_lint.py 2>&1
$llerr = @($ll | Where-Object { $_ -match "^ERR" })
Result "layout" ($LASTEXITCODE -eq 0) ("{0}" -f ($ll | Select-Object -Last 1))
$llerr | Select-Object -First 10 | ForEach-Object { Add("       $_") }
if ($Parity) {
    # informational only (never fails the run): the 10 lowest region x category cells vs Skyrim = 100. docs/PARITY.md.
    $pr = @(& $py -3 -I tools/qa/parity.py --lowest 10 2>&1)
    Add("INFO   parity            lowest cells vs Skyrim (full table: py -3 -I tools/qa/parity.py; plan: docs/PARITY.md)")
    $pr | ForEach-Object { Add("       $_") }
}
if ($Novelty) {
    # informational: near-duplicate text clusters in the lang files (tools/qa/novelty_lint.py, docs/PARITY.md)
    $nv = @(& $py -3 -I tools/qa/novelty_lint.py --top 10 2>&1)
    Add("INFO   novelty           10 worst near-duplicate clusters (py -3 -I tools/qa/novelty_lint.py --strict gates)")
    $nv | ForEach-Object { Add("       $_") }
}
Pop-Location

# ------------------------------------------------------------------ headless server
if ($Server) {
    Set-Content (Join-Path $Run "eula.txt") "eula=true" -Encoding ascii
    $props = Join-Path $Run "server.properties"
    if (-not (Test-Path $props)) { Set-Content $props "online-mode=false`nserver-port=25601`n" -Encoding ascii }
    Remove-Item -Recurse -Force (Join-Path $Run "world") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $Run "crash-reports") -ErrorAction SilentlyContinue
    $cmds = @("forceload add -96 -96 96 96", "skycraft-quest selftest flow", "skycraft magic selftest")
    if ($ServerCmds) { $cmds += ($ServerCmds -split "\|") }
    $cmds += "stop"
    $auto = (($cmds | ForEach-Object { $_.Trim().Replace(' ', '+') }) -join "|")
    $env:JAVA_TOOL_OPTIONS = "-Dskycraft.autorun=$auto -Dskycraft.autorun.wait=300"
    $out = Join-Path $Run "qa-server.log"
    $start = Get-Date
    $p = Start-Process -FilePath (Join-Path $Mod "gradlew.bat") -ArgumentList "runServer", "--console=plain" -WorkingDirectory $Mod `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err" -PassThru -WindowStyle Hidden
    if (-not $p.WaitForExit($ServerTimeout * 1000)) {
        Get-CimInstance Win32_Process | Where-Object { $_.Name -match "java" -and $_.CommandLine -match [regex]::Escape($Mod) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        Result "server" $false "timed out after ${ServerTimeout}s"
    }
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    $secs = [int]((Get-Date) - $start).TotalSeconds
    $summary = & $py -3 -I (Join-Path $PSScriptRoot "log_summary.py") $out (Join-Path $Run "crash-reports") 2>&1
    $sfail = @($summary | Where-Object { $_ -match "^FAIL" })
    Result "server" ($sfail.Count -eq 0) "ran ${secs}s"
    $summary | ForEach-Object { Add("       $_") }
}

# ------------------------------------------------------------------ client autotest + contact sheet
if ($Client) {
    $shots = Join-Path $Run "build\autotest-screenshots"
    Remove-Item -Recurse -Force $shots -ErrorAction SilentlyContinue
    $flags = "-Dskycraft.autotest=true -Dskycraft.autotest.world=qa_world"
    if ($Tour) { $flags += " -Dskycraft.autotest.tour=" + $Tour.Replace(' ', '+') }
    $env:JAVA_TOOL_OPTIONS = $flags
    $out = Join-Path $Run "qa-client.log"
    $p = Start-Process -FilePath (Join-Path $Mod "gradlew.bat") -ArgumentList "runClient", "--console=plain" -WorkingDirectory $Mod `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err" -PassThru -WindowStyle Minimized
    if (-not $p.WaitForExit($ClientTimeout * 1000)) {
        Get-CimInstance Win32_Process | Where-Object { $_.Name -match "java" -and $_.CommandLine -match [regex]::Escape($Mod) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        Add("       client timed out after ${ClientTimeout}s (screenshots so far are still summarised)")
    }
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    $summary = & $py -3 -I (Join-Path $PSScriptRoot "log_summary.py") $out (Join-Path $Run "crash-reports") 2>&1
    $cfail = @($summary | Where-Object { $_ -match "^FAIL" })
    Result "client" ($cfail.Count -eq 0) "log summary below"
    $summary | ForEach-Object { Add("       $_") }
    $sheet = & $py -3 -I (Join-Path $PSScriptRoot "contact_sheet.py") $shots (Join-Path $Run "qa-contact-sheet.jpg") 2>&1
    Add("       contact sheet: $sheet")
}

Add(("OVERALL {0}" -f ($(if ($Fail) { "FAIL" } else { "PASS" }))))
$Report | Set-Content (Join-Path $Run "qa-report.txt") -Encoding utf8
$Report | ForEach-Object { $_ }
if ($Fail) { exit 1 } else { exit 0 }
