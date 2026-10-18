package com.skycraft.tamriel.terrain;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.biome.BiomeNoise;
import com.skycraft.worldplan.PlanGround;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * World-scale subterranean karst, fault-gallery, vaulted underworld dome, and river-gorge density model for Tamriel.
 *
 * <p>Provides three subterranean carving tiers plus hard protection masking:
 * <ol>
 *   <li><b>Regional Karst &amp; Fault Galleries</b>: high-relief limestone karst galleries in Skyrim, High Rock and
 *       Cyrodiil; volcanic basalt lava tubes in Morrowind, Red Mountain, Eastmarch and Dragontail; colossal braided
 *       Graht-root hollows under Valenwood and Black Marsh; and wind/water-carved sandstone canyon vaults in
 *       Hammerfell, Yokuda and Elsweyr.</li>
 *   <li><b>Vaulted Underworld Chambers</b>: occasional large subterranean domes ({@code y = -32..40}) with flattened
 *       floor bias suitable for underground lakes, giant mushroom forests, and cultural ruin set-pieces.</li>
 *   <li><b>River-Fed Underground Gorges</b>: where surface rivers cross high karst or mountain terrain
 *       ({@code s.river > 0.1f && s.elevation > 90f}), carves subterranean river-cave branches and sinkhole throats.</li>
 *   <li><b>Hard Masking</b>: queries {@link Places} (bounded dungeon footprints and landmarks) and {@link PlanGround}
 *       (road beds, settlement pads, and street plots) so world caves smoothly pinch closed ({@code density -> +1.0})
 *       within the bounding radius of any placed dungeon or road/settlement pad.</li>
 * </ol>
 */
public final class TerrainDensity {
    /** Extra blend margin (blocks) outside a {@link Place}'s bounding radius where world caves pinch shut. */
    public static final int PLACE_MASK_MARGIN = 18;
    /** Lower and upper Y bounds of the vaulted underworld chamber tier. */
    public static final int VAULT_MIN_Y = -32;
    public static final int VAULT_MAX_Y = 40;

    private TerrainDensity() {}

    /**
     * Protection weight {@code [0..1]} around named {@link Places} (dungeons, forts, shrines, word walls, settlements).
     * Inside {@code radius + 4} blocks the weight is {@code 1.0}, fading smoothly to {@code 0.0} at
     * {@code radius + PLACE_MASK_MARGIN} so world caves never break into a {@code BuiltBuilder} dungeon room or
     * undermine a surface foundation.
     */
    public static double placeMask(int x, int z) {
        List<Place> nearby = Places.overlapping(
                x - PLACE_MASK_MARGIN, z - PLACE_MASK_MARGIN,
                x + PLACE_MASK_MARGIN, z + PLACE_MASK_MARGIN);
        if (nearby.isEmpty()) return 0.0;
        double best = 0.0;
        for (int i = 0, n = nearby.size(); i < n; i++) {
            Place p = nearby.get(i);
            double d = Math.sqrt(p.distSqr(x, z));
            double inner = p.radius() + (p.type() == PlaceType.DUNGEON_FIXED ? 6.0 : 4.0);
            double outer = inner + PLACE_MASK_MARGIN;
            if (d <= inner) return 1.0;
            if (d < outer) {
                double w = 1.0 - MapNoise.smoothstep(inner, outer, d);
                if (w > best) best = w;
            }
        }
        return best;
    }

    /**
     * Combined hard mask {@code [0..1]} from {@link PlanGround#influence(int, int)} (road beds, settlement pads,
     * street plots), map road/calm channels, and {@link #placeMask(int, int)} (dungeon and site footprints).
     */
    public static double roadAndPadMask(int x, int z) {
        MapSample s = TamrielMap.sampleScratch(x, z);
        double w = Math.max(s.road, s.calm);
        if (PlanGround.blending()) {
            w = Math.max(w, PlanGround.influence(x, z));
        }
        double pm = placeMask(x, z);
        return Mth.clamp(Math.max(w, pm), 0.0, 1.0);
    }

    /**
     * True when column {@code (x, z)} lies on or within the protection margin of a planned road, settlement pad,
     * street plot, or placed dungeon/site footprint. Used by skylight shafts and cave features to avoid piercing roads
     * or building pads.
     */
    public static boolean isRoadOrPlot(int x, int z) {
        return roadAndPadMask(x, z) > 0.05;
    }

    /**
     * True where a surface river crosses high karst or mountain terrain ({@code s.river > 0.1f && s.elevation > 90f}),
     * enabling subterranean river-cave branches and sinkhole throats.
     */
    public static boolean isRiverGorge(MapSample s) {
        return s.river > 0.1f && s.elevation > 90f;
    }

    /**
     * Regional karst &amp; fault-gallery density contribution at {@code (x, y, z)}. Negative values carve open
     * galleries; positive values preserve solid rock.
     */
    public static double karstGalleryDensity(MapSample s, int x, int y, int z, double surfaceY) {
        double depth = surfaceY - y;
        if (depth < 6.0 || y < -58) return 1.0;

        Region reg = Regions.byIndex(s.region);
        String prov = reg != null ? reg.province() : "skyrim";

        // Stratified bedding planes and fault lines
        double layer = Math.sin(y / 9.5 + BiomeNoise.value2(x / 64.0, z / 64.0, 801) * 1.8);
        double faultA = Math.abs(BiomeNoise.fbm2(x / 52.0, z / 52.0, 802));
        double faultB = Math.abs(BiomeNoise.value3(x / 36.0, y / 18.0, z / 36.0, 803));
        double tunnel = faultA * 0.65 + faultB * 0.35 + layer * layer * 0.28;

        // Regional structural modulation
        double threshold = 0.24;
        if ("morrowind".equals(prov) || s.heat > 0.25f) {
            // Volcanic basalt lava tubes: elliptical horizontal conduits in upper/mid strata
            double tube = Math.abs(BiomeNoise.fbm2(x / 44.0, z / 44.0, 804))
                    + Math.abs(Math.sin(y / 11.0)) * 0.32;
            tunnel = Math.min(tunnel, tube);
            threshold = 0.26 + 0.06 * s.heat;
        } else if ("valenwood".equals(prov) || "black_marsh".equals(prov)) {
            // Colossal shallow Graht-root and Hist-root warrens (strongest 8..55 blocks below surface)
            double rootBand = MapNoise.smoothstep(6.0, 14.0, depth) * (1.0 - MapNoise.smoothstep(42.0, 68.0, depth));
            double braid = Math.abs(BiomeNoise.value3(x / 28.0, y / 10.0, z / 28.0, 805));
            tunnel -= rootBand * (0.14 - 0.18 * braid);
            threshold = 0.27;
        } else if ("hammerfell".equals(prov) || "elsweyr".equals(prov) || "yokuda".equals(prov) || s.dunes > 0.1f) {
            // Wind-and-water carved sandstone canyon-caves and tall slot galleries
            double slot = Math.abs(BiomeNoise.fbm2(x / 38.0, z / 38.0, 806)) * 1.15
                    + Math.abs(BiomeNoise.value3(x / 48.0, y / 28.0, z / 48.0, 807)) * 0.25;
            tunnel = Math.min(tunnel, slot);
            threshold = 0.25 + 0.05 * s.crag;
        } else {
            // Skyrim, High Rock, Cyrodiil, Summerset: high-relief limestone karst & travertine galleries
            threshold = 0.24 + 0.07 * s.crag + 0.05 * s.roughness;
        }

        return (tunnel - threshold) * 1.8;
    }

    /**
     * Vaulted underworld chamber density in {@code y = -32..40}: carves occasional massive underground domes with
     * flattened floor bias for subterranean lakes, mushroom forests, and ruin set-pieces.
     */
    public static double vaultedChamberDensity(MapSample s, int x, int y, int z, double surfaceY) {
        if (y < VAULT_MIN_Y - 4 || y > VAULT_MAX_Y + 4 || surfaceY - y < 22.0) return 1.0;

        // Vertical dome envelope: flat floor bias near y = -26..-18, arched roof up to y = 40
        double floorRamp = MapNoise.smoothstep(VAULT_MIN_Y, VAULT_MIN_Y + 8, y);
        double roofRamp = 1.0 - MapNoise.smoothstep(VAULT_MAX_Y - 12, VAULT_MAX_Y, y);
        double vertical = floorRamp * roofRamp;
        if (vertical <= 0.01) return 1.0;

        double domeField = BiomeNoise.fbm2(x / 112.0, z / 112.0, 811);
        double detail = BiomeNoise.value3(x / 42.0, y / 20.0, z / 42.0, 812) * 0.22;
        // Flat floor shelving inside the dome so lakes and ruins have walkable terraces
        double floorShelf = y < -14 ? ( -14 - y ) * 0.065 : 0.0;
        double raw = 0.62 - (domeField + detail) * vertical + floorShelf;
        return Mth.clamp(raw, -0.85, 1.0);
    }

    /**
     * River-fed underground gorge density: where surface rivers cross high karst/mountain terrain
     * ({@code s.river > 0.1f && s.elevation > 90f}), carves subterranean river-cave branches and sinkhole throats.
     */
    public static double riverGorgeDensity(MapSample s, int x, int y, int z, double surfaceY) {
        if (!isRiverGorge(s)) return 1.0;
        double depth = surfaceY - y;
        if (depth < 2.0 || depth > 88.0) return 1.0;

        double riverStrength = MapNoise.smoothstep(0.10, 0.45, s.river)
                * MapNoise.smoothstep(90.0, 130.0, s.elevation);
        if (riverStrength <= 0.01) return 1.0;

        // Subterranean river conduit 14..48 blocks below the surface gorge + occasional sinkhole throat
        double gorgeBand = MapNoise.smoothstep(10.0, 20.0, depth) * (1.0 - MapNoise.smoothstep(52.0, 78.0, depth));
        double meander = Math.abs(BiomeNoise.value3(x / 32.0, y / 16.0, z / 32.0, 821));
        double conduit = (meander - 0.34 * riverStrength) / Math.max(0.15, gorgeBand);

        // Sinkhole throat where fault noise aligns with the river channel
        double throatNoise = BiomeNoise.fbm2(x / 48.0, z / 48.0, 822);
        if (throatNoise > 0.52 && depth < 42.0) {
            double throat = (0.64 - throatNoise) * 2.4;
            conduit = Math.min(conduit, throat);
        }
        return Mth.clamp(conduit, -0.8, 1.0);
    }

    /**
     * Evaluates the composite subterranean density at {@code (x, y, z)} and smoothly pinches caves closed
     * ({@code density -> +1.0}) inside the bounding radius of any placed dungeon or road/settlement pad.
     */
    public static double computeCaveDensity(MapSample s, int x, int y, int z, double slopedDensity) {
        double surfaceY = s.elevation;
        if (y > surfaceY + 8) return slopedDensity;

        double mask = roadAndPadMask(x, z);
        if (mask >= 0.999) {
            return Math.max(slopedDensity, 1.0);
        }

        double karst = karstGalleryDensity(s, x, y, z, surfaceY);
        double vault = vaultedChamberDensity(s, x, y, z, surfaceY);
        double gorge = riverGorgeDensity(s, x, y, z, surfaceY);

        // How deep this block really is: the terrain's own density grows about 0.036 per block below the true surface.
        // The map's elevation can sit tens of blocks off the real, noise-shaped surface in mountains, and the map's
        // per-pixel values step at pixel edges, so carving by map depth broke through the surface in big scoops and
        // boxy cutouts. Carving is faded in with the real depth instead: nothing within ~8 blocks of the surface,
        // everything from ~18 down.
        double solid = Mth.clamp((slopedDensity - 0.30) / 0.35, 0.0, 1.0);
        double cave = Mth.lerp(solid, 1.0, Math.min(karst, Math.min(vault, gorge)));
        double combined = Math.min(slopedDensity, cave);
        return Mth.lerp(mask, combined, Math.max(slopedDensity, 1.0));
    }
}
