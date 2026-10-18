# Move the staged aether glue into the mod once the NeoForge 26.3 conversion compiles. DRY RUN by default: prints
# every copy and patch it would make; -Apply makes them. Run from the repo root of the tree that holds mod/ (the main
# checkout), after native\build.ps1 (it ships native\build\Release\aether_core.dll).
#   .\<aether worktree>\native\java\mod-integration\move_in.ps1 [-Apply] [-Mod <tree>\mod]
# Every patch inserts text after one anchor that must occur exactly once, and is skipped when its marker is already
# there, so a second run changes nothing. Coordinate with whoever is editing mod/ before -Apply.
param([switch]$Apply, [string]$Mod = 'mod')
$ErrorActionPreference = 'Stop'
$glue = $PSScriptRoot
$native = Split-Path (Split-Path $glue -Parent) -Parent  # ...\native
if (-not (Test-Path "$Mod\src\main\java\com\skycraft\Skycraft.java")) { throw "no mod at $Mod" }
$utf8 = New-Object Text.UTF8Encoding $false
$java = "$Mod\src\main\java\com\skycraft"
$mode = if ($Apply) { 'APPLY' } else { 'dry run' }
Write-Host "aether move-in ($mode) -> $Mod"

# 1. sources: the glue and the FFM binding (not the probe, the harness or the compile stubs)
$dst = "$java\aether"
$files = @(Get-ChildItem "$glue\src\com\skycraft\aether" -Filter *.java) + @(Get-Item "$native\java\src\com\skycraft\aether\AetherNative.java")
foreach ($f in $files) {
    Write-Host "  copy $($f.Name) -> $dst"
    if ($Apply) {
        New-Item -ItemType Directory -Force $dst | Out-Null
        Copy-Item $f.FullName (Join-Path $dst $f.Name) -Force
    }
}

# 2. the native library, where AetherLibrary unpacks it from
$dll = "$native\build\Release\aether_core.dll"
$dllDst = "$Mod\src\main\resources\natives\windows-x86_64\aether_core.dll"
if (-not (Test-Path $dll)) { throw "build aether first: $dll is missing" }
Write-Host "  copy aether_core.dll -> $dllDst"
if ($Apply) {
    New-Item -ItemType Directory -Force (Split-Path $dllDst -Parent) | Out-Null
    Copy-Item $dll $dllDst -Force
}

# 3. hooks
function Patch([string]$file, [string]$anchor, [string]$insert, [string]$marker, [switch]$Before) {
    $text = [IO.File]::ReadAllText($file, $utf8)
    $nl = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $a = $anchor.Replace("`n", $nl)
    $ins = $insert.Replace("`n", $nl)
    $rel = $file.Substring($file.IndexOf('src'))
    if ($text.Contains($marker)) { Write-Host "  patch $rel : already applied"; return }
    $at = $text.IndexOf($a)
    if ($at -lt 0 -or $text.IndexOf($a, $at + 1) -ge 0) { throw "patch $rel : anchor not found exactly once:`n$anchor" }
    $where = if ($Before) { 'before' } else { 'after' }
    Write-Host "  patch $rel : $where '$($anchor.Trim().Split("`n")[-1].Trim())'"
    $pos = if ($Before) { $at } else { $at + $a.Length }
    if ($Apply) { [IO.File]::WriteAllText($file, $text.Insert($pos, $ins), $utf8) }
}

Patch "$java\Skycraft.java" "        SkyNetwork.init(modBus);`n" @'
        // aether: the native far field (server tile stream + far actors; the client renderer on clients)
        com.skycraft.aether.AetherServer.init();
        com.skycraft.platform.Sides.runOn(net.neoforged.api.distmarker.Dist.CLIENT,
                () -> () -> com.skycraft.aether.AetherClient.init(modBus));

'@ 'com.skycraft.aether.AetherServer.init()'

Patch "$java\network\SkyNetwork.java" "        com.skycraft.combat.Threats.registerPackets();`n" @'
        com.skycraft.aether.AetherNet.register();

'@ 'com.skycraft.aether.AetherNet.register()'

$wft = "$java\atmosphere\client\WeatherFieldTexture.java"
Patch $wft "    private static WeatherFieldTexture farInstance;`n" @'
    /** Bumped on every upload of the four fields: aether copies them when it changed (copyForAether). */
    private static final java.util.concurrent.atomic.AtomicLong AETHER_VERSION = new java.util.concurrent.atomic.AtomicLong();

'@ 'AETHER_VERSION = new'
Patch $wft "            DataTexture.write(dynamicsLocation, dynamicsImage);`n" @'
            AETHER_VERSION.incrementAndGet();

'@ 'AETHER_VERSION.incrementAndGet()'
Patch $wft "    private static int clampByte(float v) {`n" @'
    /**
     * aether's weather feed (com.skycraft.aether.AetherWeatherFeed): copies the four fields (RGBA8, r in the low byte,
     * which is NativeImage's ABGR int) into the arrays when they changed since version `since`. Returns the version
     * now held (`since` when nothing was copied). Render thread.
     */
    public static long copyForAether(long since, int[] nearField, int[] nearDyn, int[] farField, int[] farDyn) {
        long v = AETHER_VERSION.get();
        WeatherFieldTexture n = instance, f = farInstance;
        if (v == since || n == null || f == null) return since;
        int[][] src = {n.image.getPixelsABGR(), n.dynamicsImage.getPixelsABGR(), f.image.getPixelsABGR(), f.dynamicsImage.getPixelsABGR()};
        int[][] dst = {nearField, nearDyn, farField, farDyn};
        for (int k = 0; k < 4; k++) System.arraycopy(src[k], 0, dst[k], 0, Math.min(src[k].length, dst[k].length));
        return v;
    }

'@ 'public static long copyForAether(' -Before

Write-Host ''
Write-Host 'Then (not automated):'
Write-Host '  * py -3 shaderpack/build_pack.py  (validates all programs, writes shaderpack/build/SkycraftAether.zip) and ship'
Write-Host '    the zip in the modpack''s shaderpacks/, selected in config/iris.properties (shaderPack=SkycraftAether.zip)'
Write-Host '  * build the mod (tools/dev/build_263.py), then the owner''s in-game checklist in docs/AETHER.md'
if (-not $Apply) { Write-Host "(dry run: nothing written; -Apply to make these changes)" }
