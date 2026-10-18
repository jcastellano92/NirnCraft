# The move-in, rehearsed without touching the main tree: snapshot the main checkout's mod/src/main/java into a
# scratch dir, run move_in.ps1 -Apply on the copy, and javac the whole mod against the conversion's patched 26.3 jar
# (NeoForge patches and access transformers applied) and its Gradle dependency cache. Fails when anything aether adds
# or patches does not compile; errors elsewhere are the conversion's and are only counted.
#   .\native\java\mod-integration\sandbox_check.ps1 [-Out <dir>] [-Mod <main tree>\mod]
param([string]$Out = (Join-Path $env:TEMP 'skycraft-aether-sandbox'), [string]$Mod = '',
      [string]$Patched = 'E:\Skycraft-scratch\migration-26.3\build\moddev\artifacts\minecraft-patched-26.3.0.90-merged.jar',
      [string]$Deps = 'E:\Skycraft-scratch\migration-26.3\gradle-home\caches\modules-2\files-2.1')
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $Mod) {
    Push-Location $here
    try { $common = (Resolve-Path (& git rev-parse --git-common-dir).Trim()).Path } finally { Pop-Location }
    $Mod = Join-Path (Split-Path $common -Parent) 'mod'
}
if (-not (Test-Path $Patched)) { throw "no patched 26.3 jar at $Patched (the conversion's moddev build)" }
$ref = 'F:\Projects\Personal\Skycraft\toolchains\ref\mc26.3'
$jdk = 'F:\Projects\Personal\Skycraft\toolchains\jdk-25.0.4.1+1\bin'

Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
$box = Join-Path $Out 'mod'
New-Item -ItemType Directory -Force "$box\src\main\resources" | Out-Null
Copy-Item -Recurse "$Mod\src\main\java" "$box\src\main\java"
& "$here\move_in.ps1" -Apply -Mod $box 6>$null | Out-Null

# the build's own dependencies (not its Gradle tooling), then Minecraft's libraries
$skip = 'gradle\.plugin|net\.neoforged\.moddev|installertools|org\.gradle|groovy|javadoctor|net\.neoforged\.jst'
$cp = @($Patched) +
      (Get-ChildItem $Deps -Recurse -Filter *.jar | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' -and $_.FullName -notmatch $skip } |
       ForEach-Object FullName) +
      (Get-ChildItem "$ref\libs", "$ref\runtime" -Filter *.jar | ForEach-Object FullName)
$files = Join-Path $Out 'files.txt'
Get-ChildItem "$box\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName } | Set-Content -Encoding ascii $files
$cpFile = Join-Path $Out 'cp.txt'
[IO.File]::WriteAllText($cpFile, '-cp "' + ($cp -join ';').Replace('\', '/') + '"')
$log = Join-Path $Out 'javac.log'
# (javac's diagnostics arrive on stderr, which Windows PowerShell turns into errors under 'Stop')
$ErrorActionPreference = 'Continue'
& "$jdk\javac.exe" --release 25 -proc:none -encoding UTF-8 -nowarn -Xmaxerrs 100000 -J-Xmx6g "@$cpFile" -d "$Out\classes" "@$files" 2>&1 |
    ForEach-Object { "$_" } | Out-File -Encoding utf8 $log
$ErrorActionPreference = 'Stop'
$errors = @(Select-String -Path $log -Pattern ': error:')
$ours = @($errors | Where-Object { $_.Line -match '\\aether\\|\\Skycraft\.java:|\\network\\SkyNetwork\.java:|\\WeatherFieldTexture\.java:' })
Write-Host "sandbox: $($errors.Count) javac errors in the converted mod with aether moved in; $($ours.Count) in aether's files or patches ($log)"
$ours | Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.Line)" }
if ($ours.Count) { throw 'aether does not compile inside the converted mod' }
