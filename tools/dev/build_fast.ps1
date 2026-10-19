<# Opt-in NVMe outputs/cache. Does not move the repository, saves, or change global Gradle settings.
   First seed/measure with benchmark_storage.py. Other tools retain mod/build unless given this jar explicitly. #>
param(
    [string]$ScratchRoot = 'E:\NirnCraft-scratch\storage-benchmark',
    [string[]]$Tasks = @('build'),
    [string]$InitScript = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cachePath = Join-Path $ScratchRoot 'gradle-home'
if (-not (Test-Path -LiteralPath (Join-Path $cachePath 'caches\forge_gradle'))) {
    throw 'Seed and benchmark this scratch location with tools/dev/benchmark_storage.py first.'
}
$resolvedInitScript = if ($InitScript) { (Resolve-Path -LiteralPath $InitScript).Path } else { "" }
$oldGradleHome = $env:GRADLE_USER_HOME
try {
    $env:GRADLE_USER_HOME = $cachePath
    Push-Location (Join-Path $repoRoot 'mod')
    # Windows PowerShell wraps javac notes on stderr as nonterminating errors.
    # The process exit code, not a deprecation note, determines build success.
    $ErrorActionPreference = 'Continue'
    $auditArgs = @()
    if ($resolvedInitScript) { $auditArgs = @('-I', $resolvedInitScript) }
    & .\gradlew.bat @Tasks --offline --console=plain '-Dorg.gradle.jvmargs=-Xmx2G' `
        @auditArgs `
        "-PnirncraftBuildDir=$(Join-Path $ScratchRoot 'build-main')" `
        --project-cache-dir (Join-Path $ScratchRoot 'project-cache-main')
    $buildCode = $LASTEXITCODE
} finally {
    Pop-Location
    $env:GRADLE_USER_HOME = $oldGradleHome
}
Write-Host "Build outputs: $(Join-Path $ScratchRoot 'build-main')"
exit $buildCode
