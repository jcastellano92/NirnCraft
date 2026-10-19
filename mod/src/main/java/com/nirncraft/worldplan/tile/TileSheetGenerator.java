package com.nirncraft.worldplan.tile;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Procedural 11-layer world sheet generator for 4,096 x 4,096 block endless-lands tiles
 * (pipeline stage P7, see {@code docs/WORLDGEN-PIPELINE.md} section 7 and {@code docs/DESIGN-PRINCIPLES.md} 4.1).
 *
 * <p>Derives a deterministic 11-layer causal sheet ({@code L1} geology through {@code L11} history/ruins) from:
 * <ul>
 *   <li>macro geology, elevation, relief, and coast distance sampled across the tile via {@link TamrielMap#sample},</li>
 *   <li>latitude and climate band ({@code polar}, {@code temperate}, {@code arid}, {@code tropical}),</li>
 *   <li>nearest authored province or beyond-sea culture ({@code skyrim}, {@code high_rock}, {@code hammerfell},
 *       {@code morrowind}, {@code cyrodiil}, {@code summerset}, {@code valenwood}, {@code elsweyr},
 *       {@code black_marsh}, {@code akavir}, {@code atmora}, {@code yokuda}, {@code pyandonea}).</li>
 * </ul>
 * Every generated place and scene in a tile cites field IDs from this sheet so {@code why} chains resolve cleanly.
 */
public final class TileSheetGenerator {
    public static final String[] LAYER_KEYS = {
            "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10", "L11"
    };

    private static final String[] REALM_ADJECTIVES = {
            "Wind-Scoured", "Salt-Rimed", "Basalt", "Mist-Veiled", "Sun-Bleached",
            "Gloom-Wreathed", "Shattered", "Storm-Carved", "Frost-Bound", "Amber-Dusted",
            "Cinder-Strewn", "Moss-Mantled", "Starlit", "Driftwood", "Iron-Ridged", "Coral-Fringed"
    };

    private static final String[] REALM_NOUNS = {
            "Reach", "Expanse", "Marches", "Headlands", "Barrens", "Sound",
            "Highlands", "Steppe", "Archipelago", "Basin", "Wilds", "Shelf",
            "Drifts", "Foreland", "Skerries", "Escarpment"
    };

    private TileSheetGenerator() {
    }

    /** One causal field inside a layer {@code L1..L11}. */
    public record Field(String id, String qualifiedId, int layerIndex, String text, List<String> cites) {
    }

    /** Immutable 11-layer world sheet for a single 4,096 x 4,096 tile. */
    public record TileSheet(
            String id,
            int tileX,
            int tileZ,
            String name,
            String climateBand,
            String culture,
            float landFraction,
            float meanElevation,
            float maxElevation,
            float meanRelief,
            String truth,
            Map<String, List<Field>> layers,
            Map<String, Field> fieldsByQualifiedId
    ) {
        /** Returns a qualified field ID {@code <sheetId>.L<layer>.<fieldId>}. */
        public String fieldId(String layer, String shortId) {
            return id + "." + layer + "." + shortId;
        }

        /** True when {@code qualifiedId} exists in this sheet. */
        public boolean hasField(String qualifiedId) {
            return fieldsByQualifiedId.containsKey(qualifiedId);
        }
    }

    /**
     * Deterministically generates the 11-layer {@link TileSheet} for tile {@code (tileX, tileZ)} under {@code worldSeed}.
     */
    public static TileSheet generate(long worldSeed, int tileX, int tileZ) {
        long seed = mix(worldSeed ^ ((long) tileX * 0x9E3779B97F4A7C15L) ^ ((long) tileZ * 0xC2B2AE3D27D4EB4FL));
        int x0 = tileX << TilePlan.TILE_SHIFT;
        int z0 = tileZ << TilePlan.TILE_SHIFT;
        int centerX = x0 + (TilePlan.TILE_SIZE >> 1);
        int centerZ = z0 + (TilePlan.TILE_SIZE >> 1);

        // Sample an 8x8 macro grid across the 4096x4096 tile.
        MapSample col = new MapSample();
        int landCount = 0;
        int totalSamples = 64;
        float sumElev = 0f;
        float maxElev = 0f;
        float sumRelief = 0f;
        float sumWet = 0f;
        float sumDunes = 0f;
        for (int gx = 0; gx < 8; gx++) {
            int sx = x0 + 256 + gx * 512;
            for (int gz = 0; gz < 8; gz++) {
                int sz = z0 + 256 + gz * 512;
                TamrielMap.sample(sx, sz, col);
                if (col.coastDistance > 0f && col.elevation >= TamrielMap.SEA_LEVEL) {
                    landCount++;
                }
                sumElev += col.elevation;
                if (col.elevation > maxElev) maxElev = col.elevation;
                sumRelief += col.roughness + col.ridge * 20f + col.crag * 15f;
                sumWet += col.wetland;
                sumDunes += col.dunes;
            }
        }
        float landFraction = landCount / (float) totalSamples;
        float meanElevation = sumElev / totalSamples;
        float meanRelief = sumRelief / totalSamples;
        float meanWet = sumWet / totalSamples;
        float meanDunes = sumDunes / totalSamples;

        String climateBand = classifyClimate(centerX, centerZ, meanElevation, meanDunes, meanWet);
        String culture = classifyCulture(centerX, centerZ);
        String id = tileId(tileX, tileZ);

        String adj = REALM_ADJECTIVES[(int) Math.floorMod(seed, REALM_ADJECTIVES.length)];
        String noun = REALM_NOUNS[(int) Math.floorMod(seed >>> 8, REALM_NOUNS.length)];
        String name = adj + " " + noun + " (" + tileX + ", " + tileZ + ")";

        String landformDesc = landFraction < 0.20f
                ? "pelagic shoals, wave-battered skerries, and submerged basalt ridges"
                : landFraction < 0.55f
                ? "indented fjord headlands, barrier islands, and tidal gravel spits"
                : meanRelief > 12f
                ? "faulted highland massifs, wind-carved escarpments, and stepped river terraces"
                : "broad continental benches, rolling drift plains, and meandering alluvial valleys";

        String truth = String.format(Locale.ROOT,
                "%s spans the %s outer frontier in the %s sphere (%d%% dry ground, mean elevation %.1f m, peak %.1f m), "
                        + "where %s shape every harbour, waystation, and shrine erected by long-range expeditions beyond the authored provinces.",
                name, climateBand, culture, Math.round(landFraction * 100f), meanElevation, maxElev, landformDesc);

        Map<String, List<Field>> layers = new LinkedHashMap<>();
        Map<String, Field> byId = new LinkedHashMap<>();

        // L1: Geology & Relief (cites: [])
        addField(id, layers, byId, 1, "bedrock_crust",
                String.format(Locale.ROOT,
                        "Ancient %s basement rock rises to %.1f m across %s, fractured by joint lines that govern quarry benches and pass saddles.",
                        culture, maxElev, landformDesc),
                List.of());
        addField(id, layers, byId, 1, "coastal_shelf",
                String.format(Locale.ROOT,
                        "The continental margin holds %.0f%% emergent land above sea level %d, forming natural breakwaters, gravel beaches, and steep headlands.",
                        landFraction * 100f, TamrielMap.SEA_LEVEL),
                List.of());

        // L2: Climate & Winds (cites: L1)
        addField(id, layers, byId, 2, "prevailing_winds",
                String.format(Locale.ROOT,
                        "Prevailing %s air masses sweep across the %s relief, dropping orographic moisture on windward slopes and scouring exposed ridges.",
                        climateBand, culture),
                List.of(qual(id, "L1", "bedrock_crust"), qual(id, "L1", "coastal_shelf")));
        addField(id, layers, byId, 2, "seasonal_front",
                String.format(Locale.ROOT,
                        "Seasonal squalls and %s temperature swings dictate when coastal channels freeze, flood, or bake into salt pans.",
                        climateBand),
                List.of(qual(id, "L1", "coastal_shelf")));

        // L3: Hydrology & Drainage (cites: L1, L2)
        addField(id, layers, byId, 3, "watershed_basins",
                String.format(Locale.ROOT,
                        "Runoff from the %.1f m highland divide gathers into braided headwaters and peat-stained streams that incise the bedrock fault lines.",
                        maxElev),
                List.of(qual(id, "L1", "bedrock_crust"), qual(id, "L2", "prevailing_winds")));
        addField(id, layers, byId, 3, "estuary_outfalls",
                "Freshwater channels empty through gravel bars and brackish lagoons at the coast, offering sheltered anchorages and shallow fords.",
                List.of(qual(id, "L1", "coastal_shelf"), qual(id, "L2", "seasonal_front")));

        // L4: Biomes, Flora & Fauna (cites: L2, L3)
        addField(id, layers, byId, 4, "canopy_and_scrub",
                String.format(Locale.ROOT,
                        "Hardy %s flora colonises the valley floors and lee slopes, grading from salt-tolerant sedge along estuaries to stunted upland scrub.",
                        climateBand),
                List.of(qual(id, "L2", "prevailing_winds"), qual(id, "L3", "watershed_basins")));
        addField(id, layers, byId, 4, "migratory_herds",
                "Grazing herds, seabird colonies, and fur-bearing game follow the fresh streams and seasonal melt lines across the tile.",
                List.of(qual(id, "L2", "seasonal_front"), qual(id, "L3", "estuary_outfalls")));

        // L5: Resources & Crafts (cites: L1, L4)
        addField(id, layers, byId, 5, "quarry_and_timber",
                String.format(Locale.ROOT,
                        "Outpost builders harvest dressed stone from fault scarps and seasoned driftwood or upland timber from the %s groves.",
                        climateBand),
                List.of(qual(id, "L1", "bedrock_crust"), qual(id, "L4", "canopy_and_scrub")));
        addField(id, layers, byId, 5, "frontier_artisans",
                String.format(Locale.ROOT,
                        "Trappers, salters, and smiths trained in %s traditions cure hides, smelt bog iron, andprovision passing caravans.",
                        culture),
                List.of(qual(id, "L1", "coastal_shelf"), qual(id, "L4", "migratory_herds")));

        // L6: Routes & Trade Corridors (cites: L3, L5)
        addField(id, layers, byId, 6, "wayfarer_tracks",
                "Graded pack roads and stone-curbed lanes follow the contour benches between tile boundary passes, bridging streams at narrow rock sills.",
                List.of(qual(id, "L3", "watershed_basins"), qual(id, "L5", "quarry_and_timber")));
        addField(id, layers, byId, 6, "coastal_landings",
                "Timber jetties, ford markers, and beacon cairns link the sheltered estuary mouths to the inland trail network.",
                List.of(qual(id, "L3", "estuary_outfalls"), qual(id, "L5", "frontier_artisans")));

        // L7: Factions, Law & Rule (cites: L5, L6)
        addField(id, layers, byId, 7, "charter_wardens",
                String.format(Locale.ROOT,
                        "Chartered %s wardens maintain waystations and toll bridges along the primary corridors to safeguard supply lines.",
                        culture),
                List.of(qual(id, "L5", "quarry_and_timber"), qual(id, "L6", "wayfarer_tracks")));
        addField(id, layers, byId, 7, "border_moots",
                "Independent homesteaders and ship captains settle salvage and grazing disputes at crossroads moots under customary frontier law.",
                List.of(qual(id, "L5", "frontier_artisans"), qual(id, "L6", "coastal_landings")));

        // L8: Settlements & Shrines (cites: L6, L7)
        addField(id, layers, byId, 8, "hearth_compounds",
                String.format(Locale.ROOT,
                        "Compact %s hamlets and stockaded Steadings cluster on drained pads beside road junctions and freshwater crossings.",
                        culture),
                List.of(qual(id, "L6", "wayfarer_tracks"), qual(id, "L7", "charter_wardens")));
        addField(id, layers, byId, 8, "wayshrine_precincts",
                "Roadside shrines, standing stones, and traveller shelters mark high passes and bridge heads where crews pause to make offerings.",
                List.of(qual(id, "L6", "coastal_landings"), qual(id, "L7", "border_moots")));

        // L9: Threats & Hazards (cites: L4, L7)
        addField(id, layers, byId, 9, "outlaw_redoubts",
                "Deserters, wreckers, and bandit bands fortify secluded crags and sea caves just beyond the reach of the charter patrols.",
                List.of(qual(id, "L4", "canopy_and_scrub"), qual(id, "L7", "charter_wardens")));
        addField(id, layers, byId, 9, "wild_predators",
                String.format(Locale.ROOT,
                        "Apex %s predators and territorial beasts prowl the game trails and ruined watchtowers after nightfall.",
                        climateBand),
                List.of(qual(id, "L4", "migratory_herds"), qual(id, "L7", "border_moots")));

        // L10: Sensory & Atmosphere (MUST cite L1, L4, or L9 per worldsheet_lint.py!)
        addField(id, layers, byId, 10, "horizon_light",
                String.format(Locale.ROOT,
                        "Low %s sun glints off wet stone scarps and distant smoke plumes rising from campfires along the ridge line.",
                        climateBand),
                List.of(qual(id, "L1", "bedrock_crust"), qual(id, "L4", "canopy_and_scrub"), qual(id, "L9", "outlaw_redoubts")));
        addField(id, layers, byId, 10, "wind_and_water_sound",
                "Wind hums through weathered cairn stones while surf and snowmelt rush through the ravines below the road bed.",
                List.of(qual(id, "L1", "coastal_shelf"), qual(id, "L4", "migratory_herds"), qual(id, "L9", "wild_predators")));

        // L11: History & Ruins (cites: L7, L8, L9)
        addField(id, layers, byId, 11, "expedition_cairns",
                String.format(Locale.ROOT,
                        "Weathered %s survey markers and ruined delve vaults record earlier expeditions that mapped these outer leagues.",
                        culture),
                List.of(qual(id, "L7", "charter_wardens"), qual(id, "L8", "hearth_compounds"), qual(id, "L9", "outlaw_redoubts")));
        addField(id, layers, byId, 11, "abandoned_watchposts",
                "Half-toppled signal towers and overgrown barrows stand on commanding knolls, now claimed by delves and scavenging camps.",
                List.of(qual(id, "L8", "wayshrine_precincts"), qual(id, "L9", "wild_predators")));

        return new TileSheet(
                id,
                tileX,
                tileZ,
                name,
                climateBand,
                culture,
                landFraction,
                meanElevation,
                maxElev,
                meanRelief,
                truth,
                Collections.unmodifiableMap(layers),
                Collections.unmodifiableMap(byId)
        );
    }

    public static String tileId(int tileX, int tileZ) {
        String sx = tileX < 0 ? "m" + (-tileX) : "p" + tileX;
        String sz = tileZ < 0 ? "m" + (-tileZ) : "p" + tileZ;
        return "tile_" + sx + "_" + sz;
    }

    private static String qual(String sheetId, String layer, String fieldId) {
        return sheetId + "." + layer + "." + fieldId;
    }

    private static void addField(
            String sheetId,
            Map<String, List<Field>> layers,
            Map<String, Field> byId,
            int layerNum,
            String shortId,
            String text,
            List<String> cites
    ) {
        String layerKey = "L" + layerNum;
        String qId = qual(sheetId, layerKey, shortId);
        Field f = new Field(shortId, qId, layerNum, text, List.copyOf(cites));
        layers.computeIfAbsent(layerKey, k -> new ArrayList<>(2)).add(f);
        byId.put(qId, f);
    }

    private static String classifyClimate( int x, int z, float meanElev, float meanDunes, float meanWet) {
        if (z < -7000 || meanElev > 155f) return "polar";
        if (meanDunes > 0.35f || (x < -15360 && z > -2000 && z < 12000)) return "arid";
        if (z > 15000 || meanWet > 0.45f) return "tropical";
        return "temperate";
    }

    private static String classifyCulture(int x, int z) {
        Region r = TamrielMap.region(x, z);
        if (r != null && r.province() != null && !r.province().isEmpty() && !"sea".equals(r.province())) {
            return switch (r.province()) {
                case "skyrim", "solstheim" -> "skyrim";
                case "high_rock", "orsinium" -> "high_rock";
                case "hammerfell", "stros_mkai" -> "hammerfell";
                case "morrowind", "vvardenfell" -> "morrowind";
                case "cyrodiil" -> "cyrodiil";
                case "summerset" -> "summerset";
                case "valenwood" -> "valenwood";
                case "elsweyr" -> "elsweyr";
                case "black_marsh" -> "black_marsh";
                case "atmora" -> "atmora";
                case "akavir", "tang_mo", "kamal", "tsaesci", "ka_po_tun" -> "akavir";
                case "yokuda", "sunken_isles" -> "yokuda";
                case "pyandonea", "thras" -> "pyandonea";
                default -> nearestBorderCulture(x, z);
            };
        }
        return nearestBorderCulture(x, z);
    }

    private static String nearestBorderCulture(int x, int z) {
        if (z < -6000) return "atmora";
        if (x > 14000) return "akavir";
        if (x < -16000) return z < 4000 ? "high_rock" : "yokuda";
        if (z > 17000) return "pyandonea";
        if (z < 2000) return x < 0 ? "skyrim" : "morrowind";
        return x < 0 ? "hammerfell" : "black_marsh";
    }

    /**
     * Validates a {@link TileSheet} against all causal sheet rules from {@code tools/qa/worldsheet_lint.py}:
     * <ul>
     *   <li>{@code truth} is at least 20 words,</li>
     *   <li>all 11 layers {@code L1..L11} are present and non-empty,</li>
     *   <li>every field text has at least 6 words,</li>
     *   <li>every field in {@code L2..L11} cites at least one strictly lower-numbered layer {@code L1..L(n-1)},</li>
     *   <li>every field in {@code L10} cites at least one field in {@code L1}, {@code L4}, or {@code L9},</li>
     *   <li>all citations resolve with zero cycles.</li>
     * </ul>
     *
     * @return empty list when valid, or human-readable error strings.
     */
    public static List<String> validate(TileSheet sheet) {
        List<String> errors = new ArrayList<>();
        if (sheet == null) {
            errors.add("sheet is null");
            return errors;
        }
        int truthWords = countWords(sheet.truth());
        if (truthWords < 20) {
            errors.add(sheet.id() + ": truth has " + truthWords + " words (need >= 20)");
        }
        for (int i = 1; i <= 11; i++) {
            String lk = "L" + i;
            List<Field> fields = sheet.layers().get(lk);
            if (fields == null || fields.isEmpty()) {
                errors.add(sheet.id() + ": missing or empty layer " + lk);
                continue;
            }
            for (Field f : fields) {
                if (countWords(f.text()) < 6) {
                    errors.add(f.qualifiedId() + ": text has fewer than 6 words");
                }
                if (i == 1) {
                    if (!f.cites().isEmpty()) {
                        errors.add(f.qualifiedId() + ": L1 field must not cite other fields");
                    }
                } else {
                    if (f.cites().isEmpty()) {
                        errors.add(f.qualifiedId() + ": L" + i + " field has empty cites");
                    }
                    boolean citesUpstream = false;
                    boolean citesSensorySource = false;
                    for (String c : f.cites()) {
                        Field target = sheet.fieldsByQualifiedId().get(c);
                        if (target == null) {
                            errors.add(f.qualifiedId() + ": unresolved citation " + c);
                            continue;
                        }
                        if (target.layerIndex() < i) {
                            citesUpstream = true;
                        } else {
                            errors.add(f.qualifiedId() + ": cites non-upstream layer L" + target.layerIndex() + " (" + c + ")");
                        }
                        if (target.layerIndex() == 1 || target.layerIndex() == 4 || target.layerIndex() == 9) {
                            citesSensorySource = true;
                        }
                    }
                    if (!citesUpstream) {
                        errors.add(f.qualifiedId() + ": does not cite any upstream layer L1..L" + (i - 1));
                    }
                    if (i == 10 && !citesSensorySource) {
                        errors.add(f.qualifiedId() + ": L10 field must cite at least one field in L1, L4, or L9");
                    }
                }
            }
        }
        return errors;
    }

    /** Serialises a {@link TileSheet} to deterministic JSON matching {@code nirncraft/worldsheets/*.json}. */
    public static String toJson(TileSheet sheet) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"id\": \"").append(escape(sheet.id())).append("\",\n");
        sb.append("  \"scope\": \"tile\",\n");
        sb.append("  \"name\": \"").append(escape(sheet.name())).append("\",\n");
        sb.append("  \"parent\": \"").append(escape(sheet.culture())).append("\",\n");
        sb.append("  \"climate_band\": \"").append(escape(sheet.climateBand())).append("\",\n");
        sb.append("  \"tile_x\": ").append(sheet.tileX()).append(",\n");
        sb.append("  \"tile_z\": ").append(sheet.tileZ()).append(",\n");
        sb.append("  \"truth\": \"").append(escape(sheet.truth())).append("\",\n");
        sb.append("  \"layers\": {\n");
        for (int i = 0; i < LAYER_KEYS.length; i++) {
            String lk = LAYER_KEYS[i];
            List<Field> fields = sheet.layers().getOrDefault(lk, List.of());
            sb.append("    \"").append(lk).append("\": {\n");
            for (int j = 0; j < fields.size(); j++) {
                Field f = fields.get(j);
                sb.append("      \"").append(escape(f.id())).append("\": {\n");
                sb.append("        \"text\": \"").append(escape(f.text())).append("\",\n");
                sb.append("        \"cites\": [");
                for (int c = 0; c < f.cites().size(); c++) {
                    if (c > 0) sb.append(", ");
                    sb.append("\"").append(escape(f.cites().get(c))).append("\"");
                }
                sb.append("]\n");
                sb.append("      }").append(j + 1 < fields.size() ? "," : "").append("\n");
            }
            sb.append("    }").append(i + 1 < LAYER_KEYS.length ? "," : "").append("\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static int countWords(String s) {
        if (s == null || s.isBlank()) return 0;
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                count++;
            }
        }
        return count;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
