<#
Native bake/verification through the checked persistent controller, in a NEW QA world.
Never deletes an existing world. Fresh staging is published only after the native
refresh command's independent computation verifies it and the server saves/stops.
#>
param(
    [int]$Port = 25748,
    [int]$Threads = 0,
    [string]$Xmx = '4G',
    [int]$Timeout = 1200,
    [string]$RuntimeRoot = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$scratch = if ($env:SKYCRAFT_263_SCRATCH) { [IO.Path]::GetFullPath($env:SKYCRAFT_263_SCRATCH) } else { 'E:\Skycraft-scratch\migration-26.3' }
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $scratch ('runtime/release-bake-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
if (Test-Path -LiteralPath $RuntimeRoot) { throw 'Preserve existing bake worlds and evidence; choose a fresh RuntimeRoot.' }
$controller = Join-Path $root 'tools/dev/devserver.ps1'
$oldThreads = $env:SKYCRAFT_BAKE_THREADS
$started = $false
try {
    if ($Threads -gt 0) { $env:SKYCRAFT_BAKE_THREADS = "$Threads" }
    & $controller start -RuntimeRoot $RuntimeRoot -Port $Port -Xmx $Xmx -BootTimeout $Timeout
    if ($LASTEXITCODE -ne 0) { throw 'Native bake server failed to start; no source bakes were changed.' }
    $started = $true
    & $controller run 'skycraft-bake refresh|skycraft-dev bake-status' -Wait -All -NoRestart -RuntimeRoot $RuntimeRoot -Timeout $Timeout
    if ($LASTEXITCODE -ne 0) { throw 'Native refresh/verification failed; staging and old bakes were preserved.' }
    $job = Get-ChildItem -LiteralPath (Join-Path $RuntimeRoot 'outbox') -Filter '*.done' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $job) { throw 'Native bake completed without a job receipt.' }
    $evidence = [IO.Path]::ChangeExtension($job.FullName, '.out')
    $expected = (Get-Content -LiteralPath (Join-Path $RuntimeRoot 'outbox/bake-inputs') -Raw).Trim()
    if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Native runtime input hash is missing.' }
} finally {
    try {
        if ($started) {
            & $controller stop -RuntimeRoot $RuntimeRoot
            if ($LASTEXITCODE -ne 0) { throw 'Native bake server did not stop cleanly; source bakes remain unchanged.' }
        }
    } finally { $env:SKYCRAFT_BAKE_THREADS = $oldThreads }
}
$staging = Join-Path $RuntimeRoot 'skycraft-bake'
if (-not (Test-Path -LiteralPath (Join-Path $staging 'manifest.json'))) {
    # Up-to-date refresh compares the existing embedded bundle against a real
    # computation; there is intentionally no replacement in this case.
    $staging = Join-Path $root 'mod/src/main/resources/skycraft/baked'
}
& py -3 -I (Join-Path $PSScriptRoot 'publish_263.py') --staging $staging --expected $expected --backup (Join-Path $RuntimeRoot 'publication') --evidence $evidence --done $job.FullName --shutdown-log (Join-Path $RuntimeRoot 'server.log')
if ($LASTEXITCODE -ne 0) { throw 'Native staged bundle validation/publication failed; inspect retained QA evidence.' }
Write-Host "Native bakes verified; preserved world and evidence: $RuntimeRoot"
exit 0
