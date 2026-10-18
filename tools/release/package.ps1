param([string]$Version = "r1", [string]$Notes = "")
# Packages the freshly built jar (plus the patched shader pack, if present) and the changelog into
# releases\skycraft-<version>.zip. Never touches the game's mods or shaderpacks folders.
$root = "f:\Projects\Personal\Skycraft\Skycraft"
$out = Join-Path $root "releases"
New-Item -ItemType Directory -Force $out | Out-Null
$stage = Join-Path $out "stage-$Version"
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item "$root\mod\build\libs\skycraft-0.1.0.jar" "$stage\skycraft-core.jar"
if ($Notes -ne "" -and (Test-Path $Notes)) { Copy-Item $Notes "$stage\CHANGELOG.md" }
$pack = "$root\releases\shaderpack\ComplementaryReimagined_r5.9.3.zip"
$hasPack = Test-Path $pack
if ($hasPack) {
    New-Item -ItemType Directory -Force "$stage\shaderpacks" | Out-Null
    Copy-Item $pack "$stage\shaderpacks\"
}
$install = @(
    "INSTALL",
    "1. Close the game completely.",
    "2. Copy skycraft-core.jar into <instance>\.minecraft\mods\ (replace the old skycraft-core.jar).",
    "   Never replace the jar while the game is running: it crashes on world exit."
)
if ($hasPack) {
    $install += @(
        "3. OPTIONAL shader pack (mountain-shadowed sunrise/sunset). Use it only together with this jar.",
        "   Back up <instance>\.minecraft\shaderpacks\ComplementaryReimagined_r5.9.3.zip, then replace it with",
        "   shaderpacks\ComplementaryReimagined_r5.9.3.zip from this zip.",
        "   If Iris reports a shader compile error, put your backup back and send me the message."
    )
}
$install | Set-Content "$stage\INSTALL.txt"
$zip = Join-Path $out "skycraft-$Version.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Compress-Archive -Path "$stage\*" -DestinationPath $zip
Remove-Item $stage -Recurse -Force
Get-Item $zip | Select-Object FullName, Length
