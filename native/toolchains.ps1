# Prints the toolchains folder: $env:NIRNCRAFT_TOOLCHAINS, else the nearest `toolchains` folder above this checkout
# (it lives beside the repository, outside git; versions and SHA-256 in native/README.md).
if ($env:NIRNCRAFT_TOOLCHAINS) { return $env:NIRNCRAFT_TOOLCHAINS }
$d = $PSScriptRoot
while ($d -and -not (Test-Path -LiteralPath (Join-Path $d 'toolchains'))) { $d = Split-Path $d }
if (-not $d) { throw 'toolchains folder not found: set NIRNCRAFT_TOOLCHAINS' }
Join-Path $d 'toolchains'
