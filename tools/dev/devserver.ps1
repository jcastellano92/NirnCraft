<#
Persistent headless dev server with a command inbox: boot once, then run commands in seconds instead of booting a
fresh server per check.

  tools\dev\devserver.ps1 start                 # prepare exact26.3 MDG launch, then Java25 in mod\run\devworld-26.3
  tools\dev\devserver.ps1 hotswap               # compileJava + JDWP RedefineClasses (<15s; falls back to restart on schema change)
  tools\dev\devserver.ps1 run "cmd a|cmd b" -Wait   # run lines one after another, print their output, exit 0/1
  tools\dev\devserver.ps1 run "..."             # queue only; prints the job id (then: wait <id>)
  tools\dev\devserver.ps1 wait <id>             # wait for a queued job and print it
  tools\dev\devserver.ps1 status | stop | restart | reset   # reset = delete the dev world, boot a fresh one

Line forms (com.nirncraft.dev.DevServer): a server command ('/' optional; feedback captured), task:<autorun task>[:arg]
(roadplan, roadterrain[:n], bake[:dir], verify, columns, bench[:r], stages[:n]), @wait <ticks>, @until <regex>,
@idle (no chunk work pending), @timeout <s>. Dev-only commands: nirncraft-dev regen <x1> <z1> <x2> <z2> [dimension]
(regenerates that area in place with the current code, overworld = paved too; nirncraft:scratch = same generator,
no roads), nirncraft-dev release, nirncraft-dev status, nirncraft-dev reload <scenes|places|cityplans|quests|palettes|all>.
Other useful lines: skypop <place>, nirncraft-roads export, nirncraft-dungeon-qa export ..., nirncraft-roadplan verify + @until verify:.

Robustness: one job at a time (the server takes the oldest inbox file); per-job timeout (server side @timeout, client
side -Timeout); 'run' auto-starts the server when it is down and prefers JDWP hotswap over restart when Java files under
mod\src\main\java are newer than the last sync (-NoRestart to skip); a dead server is reported with its crash report and log tail.
World: mod\run\devworld-26.3\world (seed 2428247, Nirn), port: -Port, else $env:NIRNCRAFT_DEV_PORT, else derived from the
worktree path (25700-25989); JDWP port: -JdwpPort, else $env:NIRNCRAFT_JDWP_PORT, else Port + 1000 (127.0.0.1:<jdwpPort>).
Full job output: mod\run\devworld-26.3\outbox\<id>.out (the console shows command feedback,
tagged log lines like [bake]/[dev]/[roadplan] and errors; -All shows every captured log line).
Memory: the server JVM gets -Xmx (default 4G; -Xmx 6G or $env:NIRNCRAFT_SERVER_XMX). ONE dev server per machine: start
refuses while another worktree's dev server runs or boots (registry in %LOCALAPPDATA%\nirncraft-dev; stop that one, or
-Force when you know there is RAM for two).
#>
param(
    [Parameter(Position = 0)][ValidateSet("start", "stop", "status", "run", "wait", "restart", "reset", "hotswap")][string]$Action = "status",
    [Parameter(Position = 1)][string]$Cmd = "",
    [switch]$Wait,
    [switch]$All,
    [switch]$NoRestart,
    [int]$Port = 0,
    [int]$JdwpPort = 0,
    [int]$Timeout = 1800,
    [int]$BootTimeout = 1200,
    [string]$Xmx = "",
    [switch]$Force,
    [string]$RuntimeRoot = ""
)
$ErrorActionPreference = "Stop"
# The canonical form of a path: junctions / links followed (the scratch folder may be a junction to an older-named
# one), as Python's Path.resolve() writes them into the launch files; identity checks compare these.
function Real([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $parts = $full.TrimEnd('\').Split('\')
    $acc = $parts[0] + '\'
    for ($i = 1; $i -lt $parts.Count; $i++) {
        $acc = Join-Path $acc $parts[$i]
        $item = Get-Item -LiteralPath $acc -Force -ErrorAction SilentlyContinue
        if ($item -and $item.LinkType -in @('Junction', 'SymbolicLink') -and $item.Target) {
            $acc = [IO.Path]::GetFullPath([string]@($item.Target)[0])
        }
    }
    return $acc
}
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Mod = Join-Path $Root "mod"
$Dev = Join-Path $Mod "run\devworld-26.3"
$Scratch = Real $(if ($env:NIRNCRAFT_263_SCRATCH) { $env:NIRNCRAFT_263_SCRATCH } else { 'E:\NirnCraft-scratch\migration-26.3' })
$Compiled = Join-Path $Scratch 'build\classes\java\main'
$JavaRoot = if ($env:NIRNCRAFT_JAVA25_HOME) { $env:NIRNCRAFT_JAVA25_HOME } else { (Get-Content -LiteralPath 'E:\NirnCraft-scratch\toolchains\java25-location.txt' -Raw).Trim() }
$JavaExe = Real (Join-Path $JavaRoot 'bin\java.exe')
if ($RuntimeRoot) {
    $candidate = (Real $RuntimeRoot).TrimEnd('\')
    $workspacePrefix = ([IO.Path]::GetFullPath((Join-Path $Root 'dist\world-tests\runtimes')).TrimEnd('\') + '\')
    $nvmePrefix = (Real 'E:\NirnCraft-scratch\world-benchmark').TrimEnd('\') + '\'
    $nativePrefix = [IO.Path]::GetFullPath((Join-Path $Scratch 'runtime')).TrimEnd('\') + '\'
    if (-not ($candidate.StartsWith($workspacePrefix,[StringComparison]::OrdinalIgnoreCase) -or
              $candidate.StartsWith($nvmePrefix,[StringComparison]::OrdinalIgnoreCase) -or
              $candidate.StartsWith($nativePrefix,[StringComparison]::OrdinalIgnoreCase))) {
        throw 'RuntimeRoot must be inside dist\world-tests\runtimes\, E:\NirnCraft-scratch\world-benchmark\, or the native scratch runtime directory.'
    }
    $Dev = $candidate
}
function Remove-DevChild([string]$Target) {
    $absolute = [IO.Path]::GetFullPath($Target)
    $prefix = [IO.Path]::GetFullPath($Dev).TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw "Refused deletion outside dev runtime: $absolute" }
    if (Test-Path -LiteralPath $absolute) {
        if ((Get-Item -LiteralPath $absolute).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refused recursive deletion of reparse point: $absolute" }
        Remove-Item -LiteralPath $absolute -Recurse -Force
    }
}
$Inbox = Join-Path $Dev "inbox"
$Outbox = Join-Path $Dev "outbox"
$Log = Join-Path $Dev "server.log"
$BootClasses = Join-Path $Dev "classes-boot"
$LastClasses = Join-Path $Dev "classes-last"
$LaunchFile = Join-Path $Dev 'native-launch.json'
New-Item -ItemType Directory -Force $Dev, $Inbox, $Outbox | Out-Null

if (-not $Xmx) { $Xmx = $(if ($env:NIRNCRAFT_SERVER_XMX) { $env:NIRNCRAFT_SERVER_XMX } else { "4G" }) }
$Registry = Join-Path $env:LOCALAPPDATA "nirncraft-dev"
New-Item -ItemType Directory -Force $Registry | Out-Null
$Key = Join-Path $Registry ((("$Root|$Dev").ToLower() -replace '[^a-z0-9]+', '_').Trim('_') + ".txt")

# other dev servers of this machine (any worktree) that are alive: "<pid> <root>" per registry file
function OtherServers {
    foreach ($f in Get-ChildItem $Registry -Filter *.txt -ErrorAction SilentlyContinue) {
        if ($f.FullName -eq $Key) { continue }
        $l = (Get-Content $f.FullName -TotalCount 1) -split ' ', 2
        if ($l.Count -eq 2 -and (Get-Process -Id ([int]$l[0]) -ErrorAction SilentlyContinue)) { $l[1] }
        else { Remove-Item $f.FullName -ErrorAction SilentlyContinue }
    }
}

if ($Port -le 0) {
    if ($env:NIRNCRAFT_DEV_PORT) { $Port = [int]$env:NIRNCRAFT_DEV_PORT }
    else {
        $h = 0; foreach ($c in "$Root".ToLower().ToCharArray()) { $h = ($h * 31 + [int]$c) % 100003 }
        $Port = 25700 + ($h % 290)
    }
}
if ($JdwpPort -le 0) {
    if ($env:NIRNCRAFT_JDWP_PORT) { $JdwpPort = [int]$env:NIRNCRAFT_JDWP_PORT }
    else { $JdwpPort = $Port + 1000 }
}

function ActiveJdwpPort {
    $jf = Join-Path $Outbox "jdwp.port"
    if (Test-Path $jf) {
        try { return [int](Get-Content $jf -TotalCount 1).Trim() } catch {}
    }
    return $JdwpPort
}

# Inspect only the selected process. Never print full launcher/authentication command lines.
function NativeProcess([int]$ServerPid) {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$ServerPid" -ErrorAction SilentlyContinue
    if (-not $p -or -not $p.ExecutablePath -or -not $p.CommandLine) { return $null }
    if (-not $p.ExecutablePath.Equals($JavaExe,[StringComparison]::OrdinalIgnoreCase)) { return $null }
    $line = $p.CommandLine.Replace('\\','\').Replace('/','\').ToLowerInvariant()
    $control = ('-Dnirncraft.dev=' + $Dev).Replace('/','\').ToLowerInvariant()
    $program = (Join-Path $Dev 'native-launch\serverRunProgramArgs.txt').Replace('/','\').ToLowerInvariant()
    if (-not $line.Contains($control) -or -not $line.Contains($program) -or
        -not $line.Contains('net.neoforged.devlaunch.main')) { return $null }
    return $p
}

function NativeDevelopmentServers {
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'net\.neoforged\.devlaunch\.Main' -and $_.CommandLine -match 'serverRunProgramArgs\.txt' } |
        ForEach-Object { [pscustomobject]@{ Pid=$_.ProcessId; Executable=$_.ExecutablePath } }
}

function RunningInfo {
    foreach ($name in @('ready','launch.pid')) {
        $path = Join-Path $Outbox $name
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $lines = @(Get-Content -LiteralPath $path)
        if ($lines.Count -lt 2) { continue }
        $serverId = 0
        if (-not [int]::TryParse($lines[0], [ref]$serverId)) { continue }
        $p = NativeProcess $serverId
        if ($p) { return [pscustomobject]@{ Pid=$serverId; Since=[DateTimeOffset]::FromUnixTimeMilliseconds([long]$lines[1]).LocalDateTime; Ready=($name -eq 'ready') } }
        if (Get-Process -Id $serverId -ErrorAction SilentlyContinue) { throw "Runtime receipt refers to live unverified PID $serverId. Refusing native control or world replacement." }
    }
    return $null
}

function ReadyInfo {
    $f = Join-Path $Outbox "ready"
    if (-not (Test-Path $f)) { return $null }
    $l = @(Get-Content $f)
    if ($l.Count -lt 2) { return $null }
    $p = NativeProcess ([int]$l[0])
    if (-not $p) {
        if (Get-Process -Id ([int]$l[0]) -ErrorAction SilentlyContinue) { throw 'Ready receipt points to a live process with a different native runtime identity.' }
        return $null
    }
    return [pscustomobject]@{ Pid = [int]$l[0]; Since = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$l[1]).LocalDateTime }
}

function LastSyncTime($r) {
    if (-not $r) { return [DateTime]::MinValue }
    $sf = Join-Path $Outbox "code-sync"
    if (Test-Path $sf) {
        try {
            $dt = [DateTimeOffset]::FromUnixTimeMilliseconds([long](Get-Content $sf -TotalCount 1).Trim()).LocalDateTime
            return $dt
        } catch {}
    }
    return $r.Since
}

function HeartbeatAge {
    $f = Join-Path $Outbox "heartbeat"
    if (-not (Test-Path $f)) { return 0 }
    try { $ms = [long]((Get-Content $f -TotalCount 1).Split(' ')[0]) } catch { return 1e9 }
    return ([DateTimeOffset]::Now.ToUnixTimeMilliseconds() - $ms) / 1000.0
}

function SourceInputs {
    foreach ($relative in @('src\main','src\generated\resources')) {
        $path = Join-Path $Mod $relative
        if (Test-Path -LiteralPath $path) { Get-ChildItem -LiteralPath $path -Recurse -File }
    }
    Get-Item (Join-Path $Mod 'build.gradle'),(Join-Path $Mod 'settings.gradle'),(Join-Path $Mod 'gradle.properties') -ErrorAction SilentlyContinue
}

function NewestSource {
    $n = SourceInputs |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($n) { return $n } else { return $null }
}

function Snapshot-BootClasses([long]$SyncMilliseconds) {
    Remove-DevChild $BootClasses
    Remove-DevChild $LastClasses
    if (Test-Path $Compiled) {
        Copy-Item -Recurse -Force $Compiled $BootClasses
        Copy-Item -Recurse -Force $Compiled $LastClasses
    }
    Set-Content (Join-Path $Outbox "code-sync") "$SyncMilliseconds" -Encoding ascii
}

function DeathReport {
    Write-Host "SERVER DIED. Log: $Log"
    $cr = Get-ChildItem (Join-Path $Dev "crash-reports") -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($cr) {
        Write-Host "crash report: $($cr.FullName)"
        Get-Content $cr.FullName -TotalCount 30 | Where-Object { $_ -match "Description|Exception|Error|at com\.nirncraft" } | Select-Object -First 12 | ForEach-Object { Write-Host "  $_" }
    }
    if (Test-Path $Log) {
        Get-Content $Log -Tail 400 | Where-Object { $_ -match "ERROR|FATAL|Exception|BUILD FAILED|error:" } | Select-Object -Last 15 | ForEach-Object { Write-Host "  $_" }
    }
}

function Start-Dev {
    $preview = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*-Dnirncraft.autotest=true*' -and $_.CommandLine -like '*-Dnirncraft.dev=world-preview*' })
    if ($preview.Count -gt 0) { Write-Host 'REFUSED: close the disposable Minecraft preview before starting a headless server.'; return $false }
    $r = ReadyInfo
    if ($r) { Write-Host "dev server already running (pid $($r.Pid), port $Port, jdwp 127.0.0.1:$(ActiveJdwpPort), since $($r.Since))"; return $true }
    $booting = RunningInfo
    if ($booting) { Write-Host "native dev server is still booting (pid $($booting.Pid)); wait for readiness"; return $false }
    if (Test-Path $Key) {
        $l = (Get-Content $Key -TotalCount 1) -split ' ', 2
        if (Get-Process -Id ([int]$l[0]) -ErrorAction SilentlyContinue) { Write-Host "this worktree's dev server is still booting (pid $($l[0])); wait for it"; return $false }
    }
    $others = @(OtherServers)
    $unregistered = @(NativeDevelopmentServers)
    if ($unregistered.Count -gt 0 -and -not $Force) {
        Write-Host "REFUSED: an existing native development server is running (PID $($unregistered.Pid -join ', ')). Stop it through its owning controller first."
        return $false
    }
    if ($others.Count -gt 0 -and -not $Force) {
        Write-Host "REFUSED: another dev server runs on this machine (out-of-memory risk): $($others -join '; ')"
        Write-Host "  stop it (tools\dev\devserver.ps1 stop in that worktree) or pass -Force"
        return $false
    }
    Set-Content (Join-Path $Dev "eula.txt") "eula=true" -Encoding ascii
    $props = Join-Path $Dev "server.properties"
    $lines = @()
    if (Test-Path $props) { $lines = @(Get-Content $props | Where-Object { $_ -notmatch '^(server-port|online-mode|max-tick-time|level-name|pause-when-empty-seconds)=' }) }
    else { $lines = @("level-seed=2428247", "view-distance=4", "simulation-distance=4", "spawn-protection=0", "motd=NirnCraft dev server") }
    $lines += "server-port=$Port", "online-mode=false", "max-tick-time=-1", "level-name=world", 'pause-when-empty-seconds=-1'
    Set-Content $props $lines -Encoding ascii
    # dev-only world datapack: nirncraft:scratch = the overworld's generator in a second dimension (never shipped)
    $dp = Join-Path $Dev "world\datapacks\nirncraft-dev"
    New-Item -ItemType Directory -Force (Join-Path $dp "data\nirncraft\dimension") | Out-Null
    Set-Content (Join-Path $dp "pack.mcmeta") '{"pack":{"min_format":[121,0],"max_format":[121,0],"description":"NirnCraft dev server: scratch dimension"}}' -Encoding ascii
    Set-Content (Join-Path $dp "data\nirncraft\dimension\scratch.json") '{"type":"minecraft:overworld","generator":{"type":"minecraft:noise","settings":"nirncraft:tamriel","biome_source":{"type":"nirncraft:tamriel"}}}' -Encoding ascii
    # MDG's public standalone-launch tasks compile resources/classes and supply the exact native classpath.
    $prepareLog = Join-Path $Dev 'native-prepare.log'
    $prepareStarted = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    # PowerShell 5.1 turns a native program's stderr (javac's warnings) into a terminating error under the callers'
    # 'Stop'; the exit code below is the real verdict
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & py -3 -I (Join-Path $PSScriptRoot 'native_server_263.py') --runtime $Dev --control $Dev --jdwp $JdwpPort --xmx $Xmx --output $LaunchFile *> $prepareLog
    $ErrorActionPreference = $eap
    if ($LASTEXITCODE -ne 0) { Write-Host "Native server preparation failed; see $prepareLog"; Get-Content -LiteralPath $prepareLog -Tail 20 | ForEach-Object { Write-Host $_ }; return $false }
    $launch = Get-Content -LiteralPath $LaunchFile -Raw | ConvertFrom-Json
    if ($launch.minecraft -ne '26.3' -or $launch.neoforge -ne '26.3.0.90' -or $launch.javaVersion -ne 25 -or
        -not $launch.java.Equals($JavaExe,[StringComparison]::OrdinalIgnoreCase)) { throw 'Native launch manifest does not match the selected exact platform.' }
    Remove-Item (Join-Path $Outbox "ready"), (Join-Path $Outbox "heartbeat"), (Join-Path $Outbox "jdwp.port"), (Join-Path $Outbox "code-sync") -ErrorAction SilentlyContinue
    Get-ChildItem $Inbox -File -ErrorAction SilentlyContinue | Remove-Item -Force
    # Snapshot exactly the bytecode prepared for this boot. Edits during generation remain stale until a later sync.
    Snapshot-BootClasses $prepareStarted
    $t0 = Get-Date
    Write-Host "starting dev server (port $Port, jdwp 127.0.0.1:$JdwpPort, log $Log) ..."
    # Every generated argument uses forward-slash paths, so Windows quoting needs no trailing-backslash rewrite.
    $nativeArgs = @($launch.args | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }) -join ' '
    $oldOptions = $env:JAVA_TOOL_OPTIONS; $oldJdkOptions = $env:JDK_JAVA_OPTIONS; $oldLegacyOptions = $env:_JAVA_OPTIONS
    try {
        Remove-Item Env:JAVA_TOOL_OPTIONS, Env:JDK_JAVA_OPTIONS, Env:_JAVA_OPTIONS -ErrorAction SilentlyContinue
        $p = Start-Process -FilePath $JavaExe -ArgumentList $nativeArgs -WorkingDirectory $Dev `
            -RedirectStandardOutput $Log -RedirectStandardError "$Log.err" -PassThru -WindowStyle Hidden
    } finally { $env:JAVA_TOOL_OPTIONS=$oldOptions; $env:JDK_JAVA_OPTIONS=$oldJdkOptions; $env:_JAVA_OPTIONS=$oldLegacyOptions }
    Set-Content (Join-Path $Outbox 'launch.pid') @("$($p.Id)", "$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())") -Encoding ascii
    Set-Content $Key "$($p.Id) $Root (booting)" -Encoding ascii
    while ($true) {
        Start-Sleep -Milliseconds 500
        $r = ReadyInfo
        if ($r) {
            Set-Content $Key "$($r.Pid) $Root" -Encoding ascii
            Set-Content (Join-Path $Outbox "jdwp.port") "$JdwpPort" -Encoding ascii
            Write-Host ("dev server ready in {0:N0} s (pid {1}, jdwp 127.0.0.1:{2}, -Xmx{3})" -f ((Get-Date) - $t0).TotalSeconds, $r.Pid, $JdwpPort, $Xmx); return $true
        }
        if ($p.HasExited) {
            Remove-Item $Key -ErrorAction SilentlyContinue
            Get-Content "$Log.err" -ErrorAction SilentlyContinue | Add-Content $Log
            DeathReport; return $false
        }
        if (((Get-Date) - $t0).TotalSeconds -gt $BootTimeout) { Write-Host "boot timed out after $BootTimeout s"; Stop-Dev; return $false }
    }
}

function Stop-Dev {
    $r = RunningInfo
    if (-not $r) { Write-Host "native dev server not running"; return }
    if ($r.Ready -and (HeartbeatAge) -lt 5) {
        $id = Submit "stop"
        $until = (Get-Date).AddSeconds(5)
        while ((NativeProcess $r.Pid) -and (Get-Date) -lt $until) { Start-Sleep -Milliseconds 200 }
    }
    if (NativeProcess $r.Pid) {
        $stopScratch = Join-Path $Dev ('orderly-stop-' + $r.Pid + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        & py -3 -I (Join-Path $PSScriptRoot 'stop_native_server.py') --pid $r.Pid --java $JavaExe --runtime $Dev --control $Dev --scratch $stopScratch | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { throw 'Public native stop request failed; server and world were left intact.' }
    }
    $until = (Get-Date).AddSeconds(90)
    while (NativeProcess $r.Pid) {
        if ((Get-Date) -gt $until) { throw 'Orderly stop exceeded its deadline; server remains running. Inspect save logs before taking another action.' }
        Start-Sleep -Milliseconds 300
    }
    Remove-Item (Join-Path $Outbox "ready"), (Join-Path $Outbox "jdwp.port"), (Join-Path $Outbox "code-sync"), (Join-Path $Outbox 'launch.pid'), $Key -ErrorAction SilentlyContinue
    Write-Host "dev server stopped"
}

function Invoke-Hotswap {
    $r = ReadyInfo
    if (-not $r) {
        Write-Host "dev server not running: starting it first ..."
        return (Start-Dev)
    }
    $ht0 = Get-Date
    $syncMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $clog = Join-Path $Dev "hotswap-compile.log"
    $sync = LastSyncTime $r
    $changedResources = SourceInputs |
        Where-Object { $_.Extension -ne '.java' -and $_.LastWriteTime -gt $sync } | Select-Object -First 1
    if ($changedResources) {
        Write-Host 'Resources changed: restarting with freshly prepared native resources.'
        Stop-Dev
        return (Start-Dev)
    }
    & py -3 -I (Join-Path $PSScriptRoot 'build_263.py') compileJava *> $clog
    $crc = $LASTEXITCODE
    if ($crc -ne 0) {
        Write-Host "HOTSWAP FAILED: compileJava failed (server left running; see $clog):"
        Select-String -Path $clog -Pattern "\.java:\d+: error:" | Select-Object -First 15 | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
        return $false
    }
    $activeJdwp = ActiveJdwpPort
    $hsOut = @(& py -3 -I (Join-Path $PSScriptRoot "hotswap.py") --host 127.0.0.1 --port $activeJdwp `
        --classes $Compiled --boot $BootClasses --last $LastClasses 2>&1)
    $hsrc = $LASTEXITCODE
    $elapsed = ((Get-Date) - $ht0).TotalSeconds
    if ($hsrc -eq 0) {
        Set-Content (Join-Path $Outbox "code-sync") "$syncMs" -Encoding ascii
        $hsOut | ForEach-Object { Write-Host $_ }
        Write-Host ("hotswap applied in {0:N1} s (pid {1}, jdwp 127.0.0.1:{2})" -f $elapsed, $r.Pid, $activeJdwp)
        return $true
    }
    $why = ($hsOut -join "; ")
    Write-Host "hotswap cannot apply in-place ($why) -> falling back to server restart ..."
    Stop-Dev
    return (Start-Dev)
}

function Submit([string]$text) {
    $id = (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + (Get-Random -Maximum 10000)
    $lines = $text -split "\|" | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $tmp = Join-Path $Inbox "$id.tmp"
    [IO.File]::WriteAllLines($tmp, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
    Move-Item $tmp (Join-Path $Inbox "$id.cmd")
    return $id
}

function Show([string]$line) {
    if ($All -or -not $line.StartsWith("[log] ")) { Write-Host $line; return }
    if ($line -match "^\[log\] (ERROR|FATAL|WARN .*NirnCraft)|\[(dev|bake|autorun|roadplan|roads|qa|census|dungeon-qa|pop)\]|Exception") { Write-Host $line }
}

function Wait-DevJob([string]$id) {
    $out = Join-Path $Outbox "$id.out"
    $done = Join-Path $Outbox "$id.done"
    $t0 = Get-Date
    $seen = 0
    $warned = $false
    while ($true) {
        $fin = Test-Path $done
        if (Test-Path $out) {
            $l = @(Get-Content $out -Encoding utf8)
            for ($i = $seen; $i -lt $l.Count; $i++) { Show $l[$i] }
            $seen = $l.Count
        }
        if ($fin) {
            $st = (Get-Content $done | Where-Object { $_ -match "^status=" }) -replace "status=", ""
            $secs = (Get-Content $done | Where-Object { $_ -match "^seconds=" }) -replace "seconds=", ""
            Write-Host "== job $id $st in $secs s (full output: $out)"
            if ($st -eq "OK") { return 0 } elseif ($st -eq "TIMEOUT") { return 3 } else { return 1 }
        }
        if (-not (ReadyInfo)) {
            Start-Sleep -Seconds 1
            if (-not (ReadyInfo) -and -not (Test-Path $done)) { DeathReport; return 2 }
        }
        $age = HeartbeatAge
        if ($age -gt 120 -and -not $warned) { Write-Host "(server thread busy or hung: no heartbeat for $([int]$age) s)"; $warned = $true }
        if ($age -lt 5) { $warned = $false }
        if (((Get-Date) - $t0).TotalSeconds -gt $Timeout) { Write-Host "client timeout after $Timeout s (job $id still queued/running)"; return 3 }
        Start-Sleep -Milliseconds 300
    }
}

switch ($Action) {
    "start" { if (Start-Dev) { exit 0 } else { exit 2 } }
    "stop" { Stop-Dev; exit 0 }
    "restart" { Stop-Dev; if (Start-Dev) { exit 0 } else { exit 2 } }
    "hotswap" { if (Invoke-Hotswap) { exit 0 } else { exit 1 } }
    "reset" {
        # fresh dev world (new road network, new chunks); keeps server.properties
        Stop-Dev
        if (@(NativeDevelopmentServers).Count -gt 0) { throw 'Refused world reset while another native development server is running.' }
        Remove-DevChild (Join-Path $Dev "world")
        if (Start-Dev) { exit 0 } else { exit 2 }
    }
    "status" {
        $r = ReadyInfo
        if (-not $r) {
            $booting = RunningInfo
            if ($booting) { Write-Host "native dev server: booting (pid $($booting.Pid), port $Port, runtime $Dev)"; exit 2 }
            Write-Host "native dev server: not running (port $Port, runtime $Dev)"; exit 1
        }
        $hb = Get-Content (Join-Path $Outbox "heartbeat") -ErrorAction SilentlyContinue
        $n = NewestSource
        $sync = LastSyncTime $r
        $stale = $n -and $n.LastWriteTime -gt $sync
        Write-Host ("dev server: pid {0}, port {1}, jdwp 127.0.0.1:{2}, up since {3}, heartbeat {4:N0} s ago ({5}){6}" -f $r.Pid, $Port, (ActiveJdwpPort), $r.Since, (HeartbeatAge), $hb,
                $(if ($stale) { "; STALE: $($n.Name) changed since last sync (run will hotswap or restart)" } else { "" }))
        exit 0
    }
    "wait" { exit (Wait-DevJob $Cmd) }
    "run" {
        if (-not $Cmd) { throw "run needs commands: run `"cmd a|cmd b`"" }
        $r = ReadyInfo
        if ($r -and -not $NoRestart) {
            $n = NewestSource
            if ($n -and $n.LastWriteTime -gt (LastSyncTime $r)) {
                Write-Host "sources changed ($($n.Name)): attempting JDWP hotswap ..."
                if (-not (Invoke-Hotswap)) { exit 2 }
                $r = ReadyInfo
            }
        }
        if (-not $r) { if (-not (Start-Dev)) { exit 2 } }
        $id = Submit $Cmd
        if (-not $Wait) { Write-Host "queued job $id (tools\dev\devserver.ps1 wait $id)"; exit 0 }
        exit (Wait-DevJob $id)
    }
}
