# Exact-platform launcher; run from any directory. Edit user_jvm_args.txt for RAM.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$runtimeJava = if ($env:NIRNCRAFT_JAVA) { $env:NIRNCRAFT_JAVA } elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
try { $runtimeJava = (Get-Command -Name $runtimeJava -CommandType Application -ErrorAction Stop).Source }
catch { throw 'Java 25 is required. Set NIRNCRAFT_JAVA to java.exe or JAVA_HOME to its installation.' }
$probe = New-Object System.Diagnostics.Process
$probe.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
$probe.StartInfo.FileName = $runtimeJava
$probe.StartInfo.Arguments = '-XshowSettings:properties -version'
$probe.StartInfo.UseShellExecute = $false
$probe.StartInfo.CreateNoWindow = $true
$probe.StartInfo.RedirectStandardError = $true
$probe.StartInfo.RedirectStandardOutput = $true
try {
    [void]$probe.Start()
    $errorOutput = $probe.StandardError.ReadToEndAsync()
    $standardOutput = $probe.StandardOutput.ReadToEndAsync()
    if (-not $probe.WaitForExit(20000)) { $probe.Kill(); throw 'Selected Java did not answer its version check within 20 seconds.' }
    $versionText = $errorOutput.Result + $standardOutput.Result
    if ($probe.ExitCode -ne 0 -or $versionText -notmatch '(?m)^\s*java\.specification\.version\s*=\s*25\s*$') {
        throw "NirnCraft requires Java 25. The selected executable is $runtimeJava"
    }
} finally { $probe.Dispose() }
$nativeArgs = 'libraries/net/neoforged/neoforge/@NEOFORGE@/win_args.txt'
if (-not (Test-Path -LiteralPath $nativeArgs) -or -not (Test-Path -LiteralPath 'mods/nirncraft-core.jar')) {
    throw 'The native server installation is incomplete. Extract the full NirnCraft server ZIP.'
}
& $runtimeJava '@user_jvm_args.txt' ('@' + $nativeArgs) @args
exit $LASTEXITCODE
