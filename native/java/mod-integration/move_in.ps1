# Move the staged atlas glue into the mod once the NeoForge 26.3 conversion compiles. DRY RUN by default: prints
# every copy and patch it would make; -Apply makes them. Run from the repo root of the tree that holds mod/ (the main
# checkout), after native\build.ps1 (it ships native\build\Release\atlas_core.dll).
#   .\<atlas worktree>\native\java\mod-integration\move_in.ps1 [-Apply] [-Mod <tree>\mod]
# Every patch inserts text after one anchor that must occur exactly once, and is skipped when its marker is already
# there, so a second run changes nothing. Coordinate with whoever is editing mod/ before -Apply.
param([switch]$Apply, [string]$Mod = 'mod', [switch]$Sources)
$ErrorActionPreference = 'Stop'
$glue = $PSScriptRoot
$native = Split-Path (Split-Path $glue -Parent) -Parent  # ...\native
if (-not (Test-Path "$Mod\src\main\java\com\nirncraft\NirnCraft.java")) { throw "no mod at $Mod" }
$utf8 = New-Object Text.UTF8Encoding $false
$java = "$Mod\src\main\java\com\nirncraft"
$mode = if ($Apply) { 'APPLY' } else { 'dry run' }
Write-Host "atlas move-in ($mode) -> $Mod"

# 1. sources: the glue and the FFM binding (not the probe, the harness or the compile stubs). Since atlas moved into
# the mod (2026-10-18), mod/src/main/java/com/nirncraft/atlas is the source of truth: once it exists, only the native
# libraries below are refreshed (copying the staged glue over it once reverted newer mod-side work). -Sources forces it.
$dst = "$java\atlas"
$files = @(Get-ChildItem "$glue\src\com\nirncraft\atlas" -Filter *.java) + @(Get-Item "$native\java\src\com\nirncraft\atlas\AtlasNative.java")
if ((Test-Path "$dst\AtlasClient.java") -and -not $Sources) {
    Write-Host "  sources: $dst is the source of truth (pass -Sources to overwrite it from the staged glue)"
    $files = @()
}
foreach ($f in $files) {
    Write-Host "  copy $($f.Name) -> $dst"
    if ($Apply) {
        New-Item -ItemType Directory -Force $dst | Out-Null
        Copy-Item $f.FullName (Join-Path $dst $f.Name) -Force
    }
}

# 2. the native library, where AtlasLibrary unpacks it from
$dll = "$native\build\Release\atlas_core.dll"
$dllDst = "$Mod\src\main\resources\natives\windows-x86_64\atlas_core.dll"
if (-not (Test-Path $dll)) { throw "build atlas first: $dll is missing" }
Write-Host "  copy atlas_core.dll -> $dllDst"
if ($Apply) {
    New-Item -ItemType Directory -Force (Split-Path $dllDst -Parent) | Out-Null
    Copy-Item $dll $dllDst -Force
}
# the Linux library (Steam Deck, Bazzite ...), when one was built (native/build_linux.sh, on Linux or in WSL) and
# copied to native\build-linux\libatlas_core.so; without it the mod still runs on Linux, atlas reports itself off
$so = "$native\build-linux\libatlas_core.so"
$soDst = "$Mod\src\main\resources\natives\linux-x86_64\libatlas_core.so"
if (Test-Path $so) {
    Write-Host "  copy libatlas_core.so -> $soDst"
    if ($Apply) {
        New-Item -ItemType Directory -Force (Split-Path $soDst -Parent) | Out-Null
        Copy-Item $so $soDst -Force
    }
} else {
    Write-Host "  (no ${so}: Linux players get no far field until native/build_linux.sh has built one)"
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

Patch "$java\NirnCraft.java" "        SkyNetwork.init(modBus);`n" @'
        // atlas: the native far field (server tile stream + far actors; the client renderer on clients)
        com.nirncraft.atlas.AtlasServer.init();
        com.nirncraft.platform.Sides.runOn(net.neoforged.api.distmarker.Dist.CLIENT,
                () -> () -> com.nirncraft.atlas.AtlasClient.init(modBus));

'@ 'com.nirncraft.atlas.AtlasServer.init()'

Patch "$java\network\SkyNetwork.java" "        com.nirncraft.combat.Threats.registerPackets();`n" @'
        com.nirncraft.atlas.AtlasNet.register();

'@ 'com.nirncraft.atlas.AtlasNet.register()'

$wft = "$java\atmosphere\client\WeatherFieldTexture.java"
Patch $wft "    private static WeatherFieldTexture farInstance;`n" @'
    /** Bumped on every upload of the four fields: atlas copies them when it changed (copyForAtlas). */
    private static final java.util.concurrent.atomic.AtomicLong ATLAS_VERSION = new java.util.concurrent.atomic.AtomicLong();

'@ 'ATLAS_VERSION = new'
Patch $wft "            DataTexture.write(dynamicsLocation, dynamicsImage);`n" @'
            ATLAS_VERSION.incrementAndGet();

'@ 'ATLAS_VERSION.incrementAndGet()'
Patch $wft "    private static int clampByte(float v) {`n" @'
    /**
     * atlas's weather feed (com.nirncraft.atlas.AtlasWeatherFeed): copies the four fields (RGBA8, r in the low byte,
     * which is NativeImage's ABGR int) into the arrays when they changed since version `since`. Returns the version
     * now held (`since` when nothing was copied). Render thread.
     */
    public static long copyForAtlas(long since, int[] nearField, int[] nearDyn, int[] farField, int[] farDyn) {
        long v = ATLAS_VERSION.get();
        WeatherFieldTexture n = instance, f = farInstance;
        if (v == since || n == null || f == null) return since;
        int[][] src = {n.image.getPixelsABGR(), n.dynamicsImage.getPixelsABGR(), f.image.getPixelsABGR(), f.dynamicsImage.getPixelsABGR()};
        int[][] dst = {nearField, nearDyn, farField, farDyn};
        for (int k = 0; k < 4; k++) System.arraycopy(src[k], 0, dst[k], 0, Math.min(src[k].length, dst[k].length));
        return v;
    }

'@ 'public static long copyForAtlas(' -Before

Write-Host ''
Write-Host 'Then (not automated):'
Write-Host '  * py -3 shaderpack/build_pack.py  (validates all programs, writes shaderpack/build/InfiniteATLAS.zip) and ship'
Write-Host '    the zip in the modpack''s shaderpacks/, selected in config/iris.properties (shaderPack=InfiniteATLAS.zip)'
Write-Host '  * build the mod (tools/dev/build_263.py), then the owner''s in-game checklist in docs/ATLAS.md'
if (-not $Apply) { Write-Host "(dry run: nothing written; -Apply to make these changes)" }
