<#
Persistent headless dev server with a command inbox: boot once, then run commands in seconds instead of booting a
fresh server per check.

  tools\dev\devserver.ps1 start                 # boot (gradle runServer in mod\run\devworld; returns when ready)
  tools\dev\devserver.ps1 run "cmd a|cmd b" -Wait   # run lines one after another, print their output, exit 0/1
  tools\dev\devserver.ps1 run "..."             # queue only; prints the job id (then: wait <id>)
  tools\dev\devserver.ps1 wait <id>             # wait for a queued job and print it
  tools\dev\devserver.ps1 status | stop | restart | reset   # reset = delete the dev world, boot a fresh one

Line forms (com.skycraft.dev.DevServer): a server command ('/' optional; feedback captured), task:<autorun task>[:arg]
(roadplan, roadterrain[:n], bake[:dir], verify, columns, bench[:r], stages[:n]), @wait <ticks>, @until <regex>,
@idle (no chunk work pending), @timeout <s>. Dev-only commands: skycraft-dev regen <x1> <z1> <x2> <z2> [dimension]
(regenerates that area in place with the current code, overworld = paved too; skycraft:scratch = same generator,
no roads), skycraft-dev release, skycraft-dev status. Other useful lines: skypop <place>, skycraft-roads export,
skycraft-dungeon-qa export ..., skycraft-roadplan verify + @until verify:.

Robustness: one job at a time (the server takes the oldest inbox file); per-job timeout (server side @timeout, client
side -Timeout); 'run' auto-starts the server when it is down and RESTARTS it when any file under mod\src\main is newer
than the running server (new code; -NoRestart to skip); a dead server is reported with its crash report and log tail.
World: mod\run\devworld\world (seed 2428247, Nirn), port: -Port, else $env:SKYCRAFT_DEV_PORT, else derived from the
worktree path (25700-25989). Full job output: mod\run\devworld\outbox\<id>.out (the console shows command feedback,
tagged log lines like [bake]/[dev]/[roadplan] and errors; -All shows every captured log line).
#>
param(
    [Parameter(Position = 0)][ValidateSet("start", "stop", "status", "run", "wait", "restart", "reset")][string]$Action = "status",
    [Parameter(Position = 1)][string]$Cmd = "",
    [switch]$Wait,
    [switch]$All,
    [switch]$NoRestart,
    [int]$Port = 0,
    [int]$Timeout = 1800,
    [int]$BootTimeout = 1200
)
$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Mod = Join-Path $Root "mod"
$Dev = Join-Path $Mod "run\devworld"
$Inbox = Join-Path $Dev "inbox"
$Outbox = Join-Path $Dev "outbox"
$Log = Join-Path $Dev "server.log"
New-Item -ItemType Directory -Force $Dev, $Inbox, $Outbox | Out-Null

if ($Port -le 0) {
    if ($env:SKYCRAFT_DEV_PORT) { $Port = [int]$env:SKYCRAFT_DEV_PORT }
    else {
        $h = 0; foreach ($c in "$Root".ToLower().ToCharArray()) { $h = ($h * 31 + [int]$c) % 100003 }
        $Port = 25700 + ($h % 290)
    }
}

function ReadyInfo {
    $f = Join-Path $Outbox "ready"
    if (-not (Test-Path $f)) { return $null }
    $l = @(Get-Content $f)
    if ($l.Count -lt 2) { return $null }
    $p = Get-Process -Id ([int]$l[0]) -ErrorAction SilentlyContinue
    if (-not $p) { return $null }
    return [pscustomobject]@{ Pid = [int]$l[0]; Since = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$l[1]).LocalDateTime }
}

function HeartbeatAge {
    $f = Join-Path $Outbox "heartbeat"
    if (-not (Test-Path $f)) { return 0 }
    try { $ms = [long]((Get-Content $f -TotalCount 1).Split(' ')[0]) } catch { return 1e9 }
    return ([DateTimeOffset]::Now.ToUnixTimeMilliseconds() - $ms) / 1000.0
}

function NewestSource {
    $src = Join-Path $Mod "src\main"
    $n = Get-ChildItem $src -Recurse -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($n) { return $n } else { return $null }
}

function DeathReport {
    Write-Host "SERVER DIED. Log: $Log"
    $cr = Get-ChildItem (Join-Path $Dev "crash-reports") -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($cr) {
        Write-Host "crash report: $($cr.FullName)"
        Get-Content $cr.FullName -TotalCount 30 | Where-Object { $_ -match "Description|Exception|Error|at com\.skycraft" } | Select-Object -First 12 | ForEach-Object { Write-Host "  $_" }
    }
    if (Test-Path $Log) {
        Get-Content $Log -Tail 400 | Where-Object { $_ -match "ERROR|FATAL|Exception|BUILD FAILED|error:" } | Select-Object -Last 15 | ForEach-Object { Write-Host "  $_" }
    }
}

function Start-Dev {
    $r = ReadyInfo
    if ($r) { Write-Host "dev server already running (pid $($r.Pid), port $Port, since $($r.Since))"; return $true }
    Set-Content (Join-Path $Dev "eula.txt") "eula=true" -Encoding ascii
    $props = Join-Path $Dev "server.properties"
    $lines = @()
    if (Test-Path $props) { $lines = @(Get-Content $props | Where-Object { $_ -notmatch '^(server-port|online-mode|max-tick-time|level-name)=' }) }
    else { $lines = @("level-seed=2428247", "view-distance=4", "simulation-distance=4", "spawn-protection=0", "motd=Skycraft dev server") }
    $lines += "server-port=$Port", "online-mode=false", "max-tick-time=-1", "level-name=world"
    Set-Content $props $lines -Encoding ascii
    # dev-only world datapack: skycraft:scratch = the overworld's generator in a second dimension (never shipped)
    $dp = Join-Path $Dev "world\datapacks\skycraft-dev"
    New-Item -ItemType Directory -Force (Join-Path $dp "data\skycraft\dimension") | Out-Null
    Set-Content (Join-Path $dp "pack.mcmeta") '{"pack":{"pack_format":15,"description":"Skycraft dev server: scratch dimension"}}' -Encoding ascii
    Set-Content (Join-Path $dp "data\skycraft\dimension\scratch.json") '{"type":"minecraft:overworld","generator":{"type":"minecraft:noise","settings":"skycraft:tamriel","biome_source":{"type":"skycraft:tamriel"}}}' -Encoding ascii
    Remove-Item (Join-Path $Outbox "ready"), (Join-Path $Outbox "heartbeat") -ErrorAction SilentlyContinue
    Get-ChildItem $Inbox -File -ErrorAction SilentlyContinue | Remove-Item -Force
    $t0 = Get-Date
    Write-Host "starting dev server (port $Port, log $Log) ..."
    $env:JAVA_TOOL_OPTIONS = "-Dskycraft.dev=."
    try {
        $p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c .\gradlew.bat runServer --console=plain -PskycraftRunDir=run/devworld `"-Dorg.gradle.jvmargs=-Xmx2G`"" `
            -WorkingDirectory $Mod -RedirectStandardOutput $Log -RedirectStandardError "$Log.err" -PassThru -WindowStyle Hidden
    } finally { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
    while ($true) {
        Start-Sleep -Milliseconds 500
        $r = ReadyInfo
        if ($r) { Write-Host ("dev server ready in {0:N0} s (pid {1})" -f ((Get-Date) - $t0).TotalSeconds, $r.Pid); return $true }
        if ($p.HasExited) {
            Get-Content "$Log.err" -ErrorAction SilentlyContinue | Add-Content $Log
            DeathReport; return $false
        }
        if (((Get-Date) - $t0).TotalSeconds -gt $BootTimeout) { Write-Host "boot timed out after $BootTimeout s"; Stop-Dev; return $false }
    }
}

function Stop-Dev {
    $r = ReadyInfo
    if (-not $r) { Write-Host "dev server not running"; return }
    $id = Submit "stop"
    $proc = Get-Process -Id $r.Pid -ErrorAction SilentlyContinue
    if ($proc -and -not $proc.WaitForExit(90000)) { Stop-Process -Id $r.Pid -Force -ErrorAction SilentlyContinue; Write-Host "killed pid $($r.Pid)" }
    Remove-Item (Join-Path $Outbox "ready") -ErrorAction SilentlyContinue
    Write-Host "dev server stopped"
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
    if ($line -match "^\[log\] (ERROR|FATAL|WARN .*Skycraft)|\[(dev|bake|autorun|roadplan|roads|qa|census|dungeon-qa|pop)\]|Exception") { Write-Host $line }
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
    "reset" {
        # fresh dev world (new road network, new chunks); keeps server.properties
        Stop-Dev
        Remove-Item -Recurse -Force (Join-Path $Dev "world") -ErrorAction SilentlyContinue
        if (Start-Dev) { exit 0 } else { exit 2 }
    }
    "status" {
        $r = ReadyInfo
        if (-not $r) { Write-Host "dev server: not running (port $Port)"; exit 1 }
        $hb = Get-Content (Join-Path $Outbox "heartbeat") -ErrorAction SilentlyContinue
        $n = NewestSource
        $stale = $n -and $n.LastWriteTime -gt $r.Since
        Write-Host ("dev server: pid {0}, port {1}, up since {2}, heartbeat {3:N0} s ago ({4}){5}" -f $r.Pid, $Port, $r.Since, (HeartbeatAge), $hb,
                $(if ($stale) { "; STALE: $($n.Name) changed after boot (run restarts it)" } else { "" }))
        exit 0
    }
    "wait" { exit (Wait-DevJob $Cmd) }
    "run" {
        if (-not $Cmd) { throw "run needs commands: run `"cmd a|cmd b`"" }
        $r = ReadyInfo
        if ($r -and -not $NoRestart) {
            $n = NewestSource
            if ($n -and $n.LastWriteTime -gt $r.Since) { Write-Host "sources changed after boot ($($n.Name)): restarting"; Stop-Dev; $r = $null }
        }
        if (-not $r) { if (-not (Start-Dev)) { exit 2 } }
        $id = Submit $Cmd
        if (-not $Wait) { Write-Host "queued job $id (tools\dev\devserver.ps1 wait $id)"; exit 0 }
        exit (Wait-DevJob $id)
    }
}
