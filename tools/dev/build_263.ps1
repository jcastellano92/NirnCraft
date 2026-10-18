<# Minecraft26.3 migration build. Keeps the old Forge cache, build and runtime separate. #>
param([string[]]$Tasks=@('build'),[switch]$Offline)
$ErrorActionPreference='Stop'
$launcher=Get-Command py -ErrorAction Stop
$forward=@('-3','-I',(Join-Path $PSScriptRoot 'build_263.py'))
if ($Offline) { $forward += '--offline' }
$forward += '--'
$forward += $Tasks
# The canonical helper owns the OS guard, child environment and working directory.
$ErrorActionPreference='Continue'
& $launcher @forward
exit $LASTEXITCODE
