package com.nirncraft.tamriel.settlement.client;

import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind;

import java.util.EnumMap;
import java.util.Map;

import static com.nirncraft.tamriel.settlement.client.ShopSignModels.*;

/**
 * Procedural 3D model generator for Skyrim-style interior furniture, storage containers,
 * weapon racks, tabletop clutter, and wall mounts across all 10 Tamriel cultures.
 *
 * <p>All coordinates are in pixel units ({@code -8..+8} = 1 block, floor at {@code y = -8},
 * wall/back at {@code z = -8}, front facing {@code +Z}).</p>
 */
public final class InteriorFurnitureModels {
    private static final Map<Culture, Map<FurnitureKind, ShopSignMesh>> CACHE = new EnumMap<>(Culture.class);
    private static ShopSignMesh EMPTY_RACK_MESH;
    private static ShopSignMesh EMPTY_TRAY_MESH;

    public record StylePalette(
            int woodMain,
            int woodTrim,
            int metal,
            int metalAccent,
            int cloth,
            int cushion,
            int stone
    ) {}

    private InteriorFurnitureModels() {}

    public static StylePalette palette(Culture culture) {
        return switch (culture) {
            case IMPERIAL -> new StylePalette(MAT_WALNUT, MAT_OAK_RICH, MAT_GOLD, MAT_BRASS, MAT_CRIMSON, MAT_CRIMSON, MAT_PEARL_WHITE);
            case BRETON -> new StylePalette(MAT_OAK_RICH, MAT_BIRCH_CREAM, MAT_IRON_LIGHT, MAT_GOLD, MAT_COBALT, MAT_COBALT, MAT_SLATE_BLUE);
            case REDGUARD, YOKUDAN -> new StylePalette(MAT_ACACIA, MAT_WALNUT, MAT_BRASS, MAT_COPPER, MAT_CRIMSON, MAT_AMBER_GLOW, MAT_SANDSTONE);
            case DUNMER -> new StylePalette(MAT_CHITIN, MAT_MUSHROOM, MAT_EBONY_STEEL, MAT_AMBER_GLOW, MAT_CRIMSON, MAT_PURPLE_GEM, MAT_CHARRED);
            case ALTMER, MAORMER -> new StylePalette(MAT_BIRCH_CREAM, MAT_PEARL_WHITE, MAT_GOLD, MAT_ARCANE_BLUE, MAT_COBALT, MAT_ARCANE_BLUE, MAT_PEARL_WHITE);
            case BOSMER -> new StylePalette(MAT_OAK_RICH, MAT_MANGROVE, MAT_BONEMEAL, MAT_EMERALD, MAT_EMERALD, MAT_LEATHER_WARM, MAT_BONEMEAL);
            case KHAJIIT -> new StylePalette(MAT_ACACIA, MAT_ROPE_TAN, MAT_GOLD, MAT_PURPLE_GEM, MAT_PURPLE_GEM, MAT_CRIMSON, MAT_SANDSTONE);
            case ARGONIAN -> new StylePalette(MAT_MANGROVE, MAT_ROPE_TAN, MAT_COPPER, MAT_TEAL_FISH, MAT_TEAL_FISH, MAT_EMERALD, MAT_CHITIN);
            case ORC -> new StylePalette(MAT_CHARRED, MAT_SPRUCE_DARK, MAT_ORC_IRON, MAT_IRON_LIGHT, MAT_LEATHER_WARM, MAT_LEATHER_WARM, MAT_IRON_DARK);
            default -> new StylePalette(MAT_SPRUCE_DARK, MAT_SPRUCE_MID, MAT_IRON_DARK, MAT_STEEL_BRIGHT, MAT_SLATE_BLUE, MAT_LEATHER_WARM, MAT_BONEMEAL);
        };
    }

    public static synchronized ShopSignMesh get(Culture culture, FurnitureKind kind, boolean empty) {
        Culture c = culture != null ? culture : Culture.NORD;
        FurnitureKind k = kind != null ? kind : FurnitureKind.TABLE;
        if (empty) {
            if (k == FurnitureKind.WEAPON_RACK) {
                if (EMPTY_RACK_MESH == null) {
                    EMPTY_RACK_MESH = buildWeaponRack(palette(c), true);
                }
                return EMPTY_RACK_MESH;
            }
            if (k.isTakeableClutter()) {
                if (EMPTY_TRAY_MESH == null) {
                    EMPTY_TRAY_MESH = new ShopSignMesh()
                            .box(-3.5f, -8.0f, -3.5f, 3.5f, -7.2f, 3.5f, MAT_WALNUT)
                            .box(-2.8f, -7.2f, -2.8f, 2.8f, -6.9f, 2.8f, MAT_IRON_DARK);
                }
                return EMPTY_TRAY_MESH;
            }
        }
        return CACHE
                .computeIfAbsent(c, kk -> new EnumMap<>(FurnitureKind.class))
                .computeIfAbsent(k, kk -> build(c, palette(c), kk));
    }

    public static ShopSignMesh get(FurnitureKind kind, Culture culture, int variant) {
        return get(culture, kind, false);
    }

    private static ShopSignMesh build(Culture c, StylePalette p, FurnitureKind kind) {
        return switch (kind) {
            case CHAIR -> buildChair(c, p);
            case STOOL -> buildStool(c, p);
            case BENCH -> buildBench(c, p);
            case THRONE -> buildThrone(c, p);
            case TABLE -> buildTable(c, p);
            case BANQUET_TABLE -> buildBanquetTable(c, p);
            case NIGHTSTAND -> buildNightstand(c, p);
            case COUNTER -> buildCounter(c, p);
            case DESK -> buildDesk(c, p);
            case WARDROBE -> buildWardrobe(c, p);
            case DRESSER -> buildDresser(c, p);
            case CABINET -> buildCabinet(c, p);
            case WEAPON_RACK -> buildWeaponRack(p, false);
            case CLUTTER_TANKARD -> buildClutterTankard(p);
            case CLUTTER_PLATTER -> buildClutterPlatter(p);
            case CLUTTER_GOBLETS -> buildClutterGoblets(p);
            case CLUTTER_CANDELABRA -> buildClutterCandelabra(p);
            case CLUTTER_APOTHECARY -> buildClutterApothecary(p);
            case CLUTTER_SCALES -> buildClutterScales(p);
            case CLUTTER_LEDGER -> buildClutterLedger(p);
            case CLUTTER_ARMOR_STAND -> buildClutterArmorStand(c, p);
            case WALL_SHIELD_PLAQUE -> buildWallShieldPlaque(c, p);
            case WALL_ANTLER_TROPHY -> buildWallAntlerTrophy(p);
            case WALL_TAPESTRY -> buildWallTapestry(c, p);
            case WALL_SHELF -> buildWallShelf(p);
        };
    }

    // ------------------------------------------------------------------ Seating

    private static ShopSignMesh buildChair(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // 4 sturdy legs with metal/carved foot cuffs
        float[][] legs = {{-5.2f, -5.2f}, {3.6f, -5.2f}, {-5.2f, 3.6f}, {3.6f, 3.6f}};
        for (float[] l : legs) {
            m.box(l[0], -8.0f, l[1], l[0] + 1.6f, -0.5f, l[1] + 1.6f, p.woodMain);
            m.box(l[0] - 0.1f, -8.0f, l[1] - 0.1f, l[0] + 1.7f, -6.8f, l[1] + 1.7f, p.metal);
        }
        // H-stretcher rails between legs
        m.box(-4.8f, -5.2f, -4.8f, -3.8f, -4.1f, 4.8f, p.woodTrim);
        m.box(3.8f, -5.2f, -4.8f, 4.8f, -4.1f, 4.8f, p.woodTrim);
        m.box(-4.2f, -5.0f, -0.6f, 4.2f, -4.0f, 0.6f, p.woodTrim);

        // Seat frame & cultural cushion/pelt
        m.box(-5.6f, -0.6f, -5.6f, 5.6f, 1.0f, 5.6f, p.woodMain);
        m.box(-4.8f, 1.0f, -4.8f, 4.8f, 1.9f, 4.8f, p.cushion);

        // Sculpted armrests & front posts
        m.box(-5.6f, 1.0f, 3.2f, -4.2f, 4.4f, 4.6f, p.woodTrim);
        m.box(4.2f, 1.0f, 3.2f, 5.6f, 4.4f, 4.6f, p.woodTrim);
        m.box(-5.8f, 4.2f, -5.4f, -4.0f, 5.3f, 5.0f, p.woodMain);
        m.box(4.0f, 4.2f, -5.4f, 5.8f, 5.3f, 5.0f, p.woodMain);

        // Tall backrest uprights & slat frame
        m.box(-5.5f, 1.0f, -5.6f, -3.9f, 12.2f, -4.1f, p.woodMain);
        m.box(3.9f, 1.0f, -5.6f, 5.5f, 12.2f, -4.1f, p.woodMain);
        m.box(-4.2f, 2.2f, -5.3f, 4.2f, 10.8f, -4.3f, p.woodTrim);
        m.box(-3.0f, 3.2f, -5.4f, 3.0f, 9.6f, -4.1f, p.cloth);

        // Cultural crest at top of chair back
        addCulturalBackCrest(m, c, p, 11.2f, -4.85f, 1.0f);
        return m;
    }

    private static void addCulturalBackCrest(ShopSignMesh m, Culture c, StylePalette p, float topY, float cz, float scale) {
        m.box(-5.8f * scale, topY - 0.8f, cz - 0.9f, 5.8f * scale, topY + 0.8f, cz + 0.9f, p.woodMain);
        switch (c) {
            case IMPERIAL -> {
                m.angledBox(0f, topY + 1.8f, cz, 2.8f * scale, 2.8f * scale, 1.4f, 0f, 0f, 45f, p.metal);
                m.box(-5.4f * scale, topY + 0.8f, cz - 0.6f, -4.0f * scale, topY + 2.4f, cz + 0.6f, p.metalAccent);
                m.box(4.0f * scale, topY + 0.8f, cz - 0.6f, 5.4f * scale, topY + 2.4f, cz + 0.6f, p.metalAccent);
            }
            case BRETON -> {
                m.box(-3.6f * scale, topY + 0.8f, cz - 0.7f, 3.6f * scale, topY + 2.2f, cz + 0.7f, p.woodTrim);
                m.box(-1.2f * scale, topY + 2.2f, cz - 0.6f, 1.2f * scale, topY + 4.0f, cz + 0.6f, p.metalAccent);
            }
            case REDGUARD, YOKUDAN -> {
                m.box(-4.5f * scale, topY + 0.8f, cz - 0.7f, 4.5f * scale, topY + 2.6f, cz + 0.7f, p.metal);
                m.angledBox(0f, topY + 2.8f, cz, 2.2f, 2.2f, 1.2f, 0f, 0f, 45f, p.metalAccent);
            }
            case DUNMER -> {
                m.angledBox(-3.5f * scale, topY + 2.0f, cz, 1.6f, 3.8f, 1.2f, 0f, 0f, -28f, p.woodMain);
                m.angledBox(3.5f * scale, topY + 2.0f, cz, 1.6f, 3.8f, 1.2f, 0f, 0f, 28f, p.woodMain);
                m.box(-1.4f, topY + 0.8f, cz - 0.8f, 1.4f, topY + 2.8f, cz + 0.8f, p.metalAccent);
            }
            case ALTMER, MAORMER -> {
                m.box(-4.8f * scale, topY + 0.8f, cz - 0.6f, -3.4f * scale, topY + 4.2f, cz + 0.6f, p.metal);
                m.box(3.4f * scale, topY + 0.8f, cz - 0.6f, 4.8f * scale, topY + 4.2f, cz + 0.6f, p.metal);
                m.angledBox(0f, topY + 2.6f, cz, 2.4f, 3.6f, 1.3f, 0f, 0f, 45f, p.metalAccent);
            }
            case BOSMER -> {
                m.angledBox(-3.8f * scale, topY + 2.2f, cz, 1.2f, 4.2f, 1.2f, 0f, 0f, 32f, MAT_BONEMEAL);
                m.angledBox(3.8f * scale, topY + 2.2f, cz, 1.2f, 4.2f, 1.2f, 0f, 0f, -32f, MAT_BONEMEAL);
                m.angledBox(-2.0f * scale, topY + 2.8f, cz, 1.0f, 2.6f, 1.0f, 0f, 0f, -20f, MAT_BONEMEAL);
                m.angledBox(2.0f * scale, topY + 2.8f, cz, 1.0f, 2.6f, 1.0f, 0f, 0f, 20f, MAT_BONEMEAL);
            }
            case KHAJIIT -> {
                m.box(-5.8f * scale, topY - 3.5f, cz - 0.5f, -4.8f * scale, topY - 0.8f, cz + 0.5f, p.metal);
                m.box(4.8f * scale, topY - 3.5f, cz - 0.5f, 5.8f * scale, topY - 0.8f, cz + 0.5f, p.metal);
                m.box(-2.8f * scale, topY + 0.8f, cz - 0.7f, 2.8f * scale, topY + 2.4f, cz + 0.7f, p.metal);
            }
            case ARGONIAN -> {
                for (int i = -2; i <= 2; i++) {
                    m.angledBox(i * 1.8f * scale, topY + 1.8f, cz, 1.1f, 3.0f, 1.0f, 0f, 0f, i * -12f, p.metalAccent);
                }
            }
            case ORC -> {
                m.box(-5.6f * scale, topY + 0.4f, cz - 1.0f, 5.6f * scale, topY + 1.6f, cz + 1.0f, p.metal);
                for (int i = -1; i <= 1; i++) {
                    m.box((i * 3.6f - 0.6f) * scale, topY + 1.6f, cz - 0.6f, (i * 3.6f + 0.6f) * scale, topY + 3.8f, cz + 0.6f, p.metalAccent);
                }
            }
            default -> {
                // Nord carved knotwork & twin wolf/dragon horn finials
                m.angledBox(-4.2f * scale, topY + 1.9f, cz, 1.5f, 3.4f, 1.2f, 0f, 0f, 28f, p.woodTrim);
                m.angledBox(4.2f * scale, topY + 1.9f, cz, 1.5f, 3.4f, 1.2f, 0f, 0f, -28f, p.woodTrim);
                m.box(-1.3f, topY - 0.3f, cz - 1.0f, 1.3f, topY + 1.5f, cz + 1.0f, p.metalAccent);
            }
        }
    }

    private static ShopSignMesh buildStool(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        float[][] legs = {{-4.2f, -4.2f}, {2.8f, -4.2f}, {-4.2f, 2.8f}, {2.8f, 2.8f}};
        for (float[] l : legs) {
            m.box(l[0], -8.0f, l[1], l[0] + 1.4f, -0.8f, l[1] + 1.4f, p.woodMain);
        }
        // Lower iron/wood bracing ring
        m.box(-4.0f, -5.0f, -4.0f, 4.0f, -3.9f, -2.9f, p.metal);
        m.box(-4.0f, -5.0f, 2.9f, 4.0f, -3.9f, 4.0f, p.metal);
        m.box(-4.0f, -5.0f, -3.0f, -2.9f, -3.9f, 3.0f, p.metal);
        m.box(2.9f, -5.0f, -3.0f, 4.0f, -3.9f, 3.0f, p.metal);
        // Beveled seat + padded top
        m.box(-5.2f, -0.8f, -5.2f, 5.2f, 0.8f, 5.2f, p.woodTrim);
        m.box(-4.2f, 0.8f, -4.2f, 4.2f, 1.6f, 4.2f, p.cushion);
        return m;
    }

    private static ShopSignMesh buildBench(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Trestle end supports
        for (float sx : new float[]{-6.8f, 5.0f}) {
            m.box(sx, -8.0f, -4.8f, sx + 1.8f, -0.5f, 4.8f, p.woodMain);
            m.box(sx - 0.2f, -8.0f, -5.4f, sx + 2.0f, -6.4f, 5.4f, p.metal);
        }
        // Heavy stretcher beam
        m.box(-7.5f, -4.8f, -1.2f, 7.5f, -3.2f, 1.2f, p.woodTrim);
        // Full-width plank seat + central pelt/cloth runner
        m.box(-8.0f, -0.5f, -5.2f, 8.0f, 1.2f, 5.2f, p.woodMain);
        m.box(-7.6f, 1.2f, -3.8f, 7.6f, 1.7f, 3.8f, p.cushion);
        // Low backrest rail so temple pews & hall benches look like real benches
        m.box(-6.8f, 1.2f, -5.2f, -5.2f, 6.8f, -3.8f, p.woodMain);
        m.box(5.2f, 1.2f, -5.2f, 6.8f, 6.8f, -3.8f, p.woodMain);
        m.box(-8.0f, 4.2f, -5.4f, 8.0f, 6.6f, -3.8f, p.woodTrim);
        return m;
    }

    private static ShopSignMesh buildThrone(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Stepped dais plinth
        m.box(-7.6f, -8.0f, -7.4f, 7.6f, -6.2f, 7.4f, p.stone);
        // Massive side throne frames
        m.box(-7.0f, -6.2f, -6.6f, -4.8f, 6.8f, 6.4f, p.woodMain);
        m.box(4.8f, -6.2f, -6.6f, 7.0f, 6.8f, 6.4f, p.woodMain);
        // Gilded/iron armrests & claw fronts
        m.box(-7.3f, 6.4f, -6.8f, -4.5f, 8.0f, 7.0f, p.woodTrim);
        m.box(4.5f, 6.4f, -6.8f, 7.3f, 8.0f, 7.0f, p.woodTrim);
        m.box(-6.8f, -6.2f, 5.4f, -5.0f, 8.4f, 7.2f, p.metal);
        m.box(5.0f, -6.2f, 5.4f, 6.8f, 8.4f, 7.2f, p.metal);
        // Deep seat & velvet/fur cushion
        m.box(-5.0f, -1.2f, -6.2f, 5.0f, 1.4f, 6.0f, p.woodMain);
        m.box(-4.8f, 1.4f, -5.6f, 4.8f, 2.8f, 5.6f, p.cushion);
        // Towering 1.5-block carved throne back
        m.box(-6.8f, 1.4f, -6.8f, 6.8f, 19.5f, -4.6f, p.woodMain);
        m.box(-5.0f, 3.0f, -4.8f, 5.0f, 17.5f, -4.1f, p.cloth);
        m.box(-7.2f, 2.0f, -7.0f, -5.2f, 20.5f, -4.4f, p.woodTrim);
        m.box(5.2f, 2.0f, -7.0f, 7.2f, 20.5f, -4.4f, p.woodTrim);
        addCulturalBackCrest(m, c, p, 20.0f, -5.7f, 1.22f);
        return m;
    }

    // ------------------------------------------------------------------ Tables, Counters, & Desks

    private static ShopSignMesh buildTable(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        float[][] legs = {{-6.8f, -6.8f}, {4.8f, -6.8f}, {-6.8f, 4.8f}, {4.8f, 4.8f}};
        for (float[] l : legs) {
            m.box(l[0], -8.0f, l[1], l[0] + 2.0f, 5.8f, l[1] + 2.0f, p.woodMain);
            m.box(l[0] - 0.15f, -8.0f, l[1] - 0.15f, l[0] + 2.15f, -6.2f, l[1] + 2.15f, p.metal);
        }
        // Lower stretcher crossbars
        m.box(-6.2f, -5.4f, -6.2f, -4.8f, -4.0f, 6.2f, p.woodTrim);
        m.box(4.8f, -5.4f, -6.2f, 6.2f, -4.0f, 6.2f, p.woodTrim);
        m.box(-5.2f, -5.2f, -0.9f, 5.2f, -4.0f, 0.9f, p.woodTrim);
        // Upper apron frame
        m.box(-6.4f, 3.6f, -6.4f, 6.4f, 5.8f, 6.4f, p.woodTrim);
        // Beveled 16x16 tabletop + cultural inlay
        m.box(-8.0f, 5.8f, -8.0f, 8.0f, 8.0f, 8.0f, p.woodMain);
        m.box(-5.8f, 6.1f, -5.8f, 5.8f, 8.05f, 5.8f, p.woodTrim);
        m.box(-8.05f, 6.3f, -8.05f, -6.2f, 7.8f, -6.2f, p.metal);
        m.box(6.2f, 6.3f, -8.05f, 8.05f, 7.8f, -6.2f, p.metal);
        m.box(-8.05f, 6.3f, 6.2f, -6.2f, 7.8f, 8.05f, p.metal);
        m.box(6.2f, 6.3f, 6.2f, 8.05f, 7.8f, 8.05f, p.metal);
        return m;
    }

    private static ShopSignMesh buildBanquetTable(Culture c, StylePalette p) {
        ShopSignMesh m = buildTable(c, p);
        // Woven cultural table runner with metallic border
        m.box(-8.02f, 6.0f, -4.6f, 8.02f, 8.12f, 4.6f, p.metalAccent);
        m.box(-8.04f, 6.05f, -3.8f, 8.04f, 8.16f, 3.8f, p.cloth);
        // Hanging runner fringe on left/right ends
        m.box(-8.12f, 2.8f, -3.8f, -7.4f, 6.2f, 3.8f, p.cloth);
        m.box(7.4f, 2.8f, -3.8f, 8.12f, 6.2f, 3.8f, p.cloth);
        return m;
    }

    private static ShopSignMesh buildNightstand(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        float[][] legs = {{-5.2f, -5.2f}, {3.6f, -5.2f}, {-5.2f, 3.6f}, {3.6f, 3.6f}};
        for (float[] l : legs) {
            m.box(l[0], -8.0f, l[1], l[0] + 1.6f, 6.2f, l[1] + 1.6f, p.woodMain);
        }
        // Lower shelf + resting book
        m.box(-4.8f, -4.6f, -4.8f, 4.8f, -3.4f, 4.8f, p.woodTrim);
        m.angledBox(0.4f, -2.7f, 0.2f, 4.2f, 1.3f, 5.2f, 0f, 14f, 0f, p.cloth);
        // Upper cabinet box & front drawer
        m.box(-5.0f, 1.2f, -5.0f, 5.0f, 6.2f, 5.0f, p.woodMain);
        m.box(-4.0f, 2.0f, 4.8f, 4.0f, 5.4f, 5.5f, p.woodTrim);
        m.box(-1.0f, 3.3f, 5.4f, 1.0f, 4.2f, 6.1f, p.metalAccent);
        // Overhanging top
        m.box(-6.2f, 6.2f, -6.2f, 6.2f, 8.0f, 6.2f, p.woodMain);
        return m;
    }

    private static ShopSignMesh buildCounter(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Solid base plinth
        m.box(-8.0f, -8.0f, -6.2f, 8.0f, -6.2f, 6.4f, p.stone);
        // Main counter body
        m.box(-8.0f, -6.2f, -5.8f, 8.0f, 5.8f, 5.8f, p.woodMain);
        // Front customer raised panels & pilasters
        m.box(-8.0f, -6.2f, 5.4f, -6.2f, 5.8f, 6.6f, p.woodTrim);
        m.box(6.2f, -6.2f, 5.4f, 8.0f, 5.8f, 6.6f, p.woodTrim);
        m.box(-5.2f, -4.4f, 5.6f, -0.6f, 4.2f, 6.4f, p.woodTrim);
        m.box(0.6f, -4.4f, 5.6f, 5.2f, 4.2f, 6.4f, p.woodTrim);
        // Overhanging counter top with metal edge strip
        m.box(-8.0f, 5.8f, -7.2f, 8.0f, 8.0f, 7.6f, p.woodTrim);
        m.box(-8.02f, 6.3f, 7.2f, 8.02f, 7.5f, 7.85f, p.metal);
        return m;
    }

    private static ShopSignMesh buildDesk(Culture c, StylePalette p) {
        ShopSignMesh m = buildTable(c, p);
        // Twin front drawers with pulls
        m.box(-6.0f, 2.2f, -5.8f, 6.0f, 5.8f, 6.2f, p.woodMain);
        m.box(-5.4f, 2.8f, 6.0f, -0.6f, 5.2f, 6.7f, p.woodTrim);
        m.box(0.6f, 2.8f, 6.0f, 5.4f, 5.2f, 6.7f, p.woodTrim);
        m.box(-3.6f, 3.6f, 6.6f, -2.4f, 4.4f, 7.2f, p.metalAccent);
        m.box(2.4f, 3.6f, 6.6f, 3.6f, 4.4f, 7.2f, p.metalAccent);
        // Leather writing blotter
        m.box(-4.8f, 6.2f, -2.5f, 4.8f, 8.12f, 5.8f, p.cushion);
        // Raised rear pigeonhole gallery at z = -7.6 .. -3.8
        m.box(-7.4f, 8.0f, -7.6f, 7.4f, 12.2f, -3.8f, p.woodMain);
        m.box(-6.6f, 8.6f, -7.0f, -2.2f, 11.4f, -3.6f, p.woodTrim);
        m.box(2.2f, 8.6f, -7.0f, 6.6f, 11.4f, -3.6f, p.woodTrim);
        // Rolled parchment scrolls in pigeonholes + open ledger & inkwell on desk
        m.box(-5.6f, 9.2f, -6.2f, -3.2f, 10.4f, -2.8f, MAT_PARCHMENT);
        m.box(3.2f, 9.2f, -6.2f, 5.6f, 10.4f, -2.8f, MAT_PARCHMENT);
        m.angledBox(-1.0f, 8.5f, 1.5f, 6.4f, 0.9f, 4.8f, 0f, -8f, 0f, MAT_PARCHMENT);
        m.box(4.2f, 8.0f, 0.5f, 5.8f, 9.6f, 2.1f, p.metal);
        m.angledBox(5.0f, 10.8f, 1.3f, 0.5f, 3.6f, 0.8f, 18f, 0f, -24f, MAT_PEARL_WHITE);
        return m;
    }

    // ------------------------------------------------------------------ Storage Containers & Weapon Rack

    private static ShopSignMesh buildWardrobe(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Stepped base & corner feet
        m.box(-7.4f, -8.0f, -6.6f, 7.4f, -5.8f, 6.6f, p.woodTrim);
        // Tall 2-block cabinet body (y = -5.8 .. +19.5)
        m.box(-7.0f, -5.8f, -6.2f, 7.0f, 19.5f, 5.8f, p.woodMain);
        // Twin front doors with raised panels
        m.box(-6.4f, -4.8f, 5.6f, -0.4f, 18.4f, 6.5f, p.woodTrim);
        m.box(0.4f, -4.8f, 5.6f, 6.4f, 18.4f, 6.5f, p.woodTrim);
        m.box(-5.4f, -3.2f, 6.3f, -1.4f, 6.4f, 6.8f, p.woodMain);
        m.box(-5.4f, 7.8f, 6.3f, -1.4f, 17.0f, 6.8f, p.woodMain);
        m.box(1.4f, -3.2f, 6.3f, 5.4f, 6.4f, 6.8f, p.woodMain);
        m.box(1.4f, 7.8f, 6.3f, 5.4f, 17.0f, 6.8f, p.woodMain);
        // Iron/brass hinges & door handles
        for (float hy : new float[]{-2.0f, 15.5f}) {
            m.box(-6.8f, hy, 6.3f, -4.8f, hy + 1.1f, 6.95f, p.metal);
            m.box(4.8f, hy, 6.3f, 6.8f, hy + 1.1f, 6.95f, p.metal);
        }
        m.box(-1.6f, 5.5f, 6.5f, -0.7f, 8.8f, 7.3f, p.metalAccent);
        m.box(0.7f, 5.5f, 6.5f, 1.6f, 8.8f, 7.3f, p.metalAccent);
        // Stepped cornice crown molding
        m.box(-7.8f, 19.5f, -7.0f, 7.8f, 21.6f, 7.2f, p.woodTrim);
        m.box(-6.0f, 21.6f, -5.8f, 6.0f, 22.8f, 6.2f, p.metal);
        return m;
    }

    private static ShopSignMesh buildDresser(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        m.box(-7.4f, -8.0f, -6.2f, 7.4f, -6.4f, 6.2f, p.woodTrim);
        m.box(-7.0f, -6.4f, -5.8f, 7.0f, 6.4f, 5.6f, p.woodMain);
        // 3 stacked drawers with dual pulls
        for (int i = 0; i < 3; i++) {
            float y0 = -5.6f + i * 4.0f;
            float y1 = y0 + 3.3f;
            m.box(-6.0f, y0, 5.4f, 6.0f, y1, 6.3f, p.woodTrim);
            m.box(-4.2f, y0 + 1.1f, 6.2f, -2.6f, y0 + 2.1f, 6.9f, p.metalAccent);
            m.box(2.6f, y0 + 1.1f, 6.2f, 4.2f, y0 + 2.1f, 6.9f, p.metalAccent);
        }
        // Overhanging dresser top + cloth runner
        m.box(-7.6f, 6.4f, -6.4f, 7.6f, 8.0f, 6.6f, p.woodTrim);
        m.box(-5.5f, 6.6f, -4.8f, 5.5f, 8.1f, 4.8f, p.cloth);
        return m;
    }

    private static ShopSignMesh buildCabinet(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Lower closed cupboard
        m.box(-7.2f, -8.0f, -6.2f, 7.2f, 1.5f, 5.8f, p.woodMain);
        m.box(-6.2f, -7.0f, 5.6f, -0.4f, 0.8f, 6.4f, p.woodTrim);
        m.box(0.4f, -7.0f, 5.6f, 6.2f, 0.8f, 6.4f, p.woodTrim);
        m.box(-1.6f, -3.8f, 6.3f, -0.7f, -1.8f, 7.0f, p.metalAccent);
        m.box(0.7f, -3.8f, 6.3f, 1.6f, -1.8f, 7.0f, p.metalAccent);
        // Counter waist
        m.box(-7.6f, 1.5f, -6.6f, 7.6f, 3.0f, 6.6f, p.woodTrim);
        // Upper open display hutch with back panel, side walls, and shelf
        m.box(-7.0f, 3.0f, -6.2f, 7.0f, 17.5f, -4.6f, p.woodMain);
        m.box(-7.0f, 3.0f, -6.2f, -5.4f, 17.5f, 3.8f, p.woodMain);
        m.box(5.4f, 3.0f, -6.2f, 7.0f, 17.5f, 3.8f, p.woodMain);
        m.box(-6.8f, 9.6f, -5.8f, 6.8f, 10.8f, 3.4f, p.woodTrim);
        m.box(-7.5f, 17.5f, -6.6f, 7.5f, 19.2f, 4.4f, p.woodTrim);
        // Visible 3D books, goblet & potion phials on the shelves
        m.box(-4.4f, 3.0f, -2.5f, -2.2f, 7.2f, 1.2f, MAT_CRIMSON);
        m.box(-2.1f, 3.0f, -2.5f, -0.2f, 7.6f, 1.2f, MAT_COBALT);
        m.box(1.8f, 3.0f, -1.5f, 4.0f, 6.4f, 0.7f, MAT_GOLD);
        m.box(-3.8f, 10.8f, -2.0f, -1.6f, 14.8f, 0.2f, MAT_EMERALD);
        m.box(0.8f, 10.8f, -2.0f, 3.2f, 15.2f, 0.4f, MAT_ARCANE_BLUE);
        return m;
    }

    private static ShopSignMesh buildWeaponRack(StylePalette p, boolean empty) {
        ShopSignMesh m = new ShopSignMesh();
        // Heavy base & A-frame side uprights
        m.box(-7.2f, -8.0f, -5.2f, 7.2f, -6.2f, 4.8f, p.woodMain);
        m.box(-6.8f, -6.2f, -4.4f, -5.0f, 7.6f, -2.2f, p.woodMain);
        m.box(5.0f, -6.2f, -4.4f, 6.8f, 7.6f, -2.2f, p.woodMain);
        // Lower trough & upper notched crossbar with iron straps
        m.box(-6.8f, -4.8f, -3.8f, 6.8f, -3.2f, 2.2f, p.woodTrim);
        m.box(-7.2f, 4.2f, -4.8f, 7.2f, 6.0f, -1.6f, p.woodTrim);
        m.box(-7.3f, 4.4f, -4.9f, -5.2f, 5.8f, -1.5f, p.metal);
        m.box(5.2f, 4.4f, -4.9f, 7.3f, 5.8f, -1.5f, p.metal);

        if (!empty) {
            // Left & right 3D steel longswords standing in the rack
            for (float sx : new float[]{-3.8f, 3.8f}) {
                // Blade
                m.box(sx - 0.7f, -4.0f, -2.4f, sx + 0.7f, 7.8f, -1.5f, MAT_STEEL_BRIGHT);
                // Crossguard
                m.box(sx - 2.3f, 7.6f, -2.8f, sx + 2.3f, 8.6f, -1.1f, MAT_GOLD);
                // Grip & pommel
                m.box(sx - 0.6f, 8.6f, -2.5f, sx + 0.6f, 11.2f, -1.4f, MAT_LEATHER_WARM);
                m.box(sx - 1.0f, 11.2f, -2.7f, sx + 1.0f, 12.4f, -1.2f, MAT_GOLD);
            }
            // Center round shield resting on the front of the rack
            m.box(-3.6f, -2.8f, -1.4f, 3.6f, 4.4f, 0.2f, p.woodTrim);
            m.box(-2.8f, -2.0f, 0.2f, 2.8f, 3.6f, 0.8f, p.cloth);
            m.box(-1.3f, -0.5f, 0.8f, 1.3f, 2.1f, 1.7f, p.metalAccent);
        }
        return m;
    }

    // ------------------------------------------------------------------ Tabletop Clutter

    private static ShopSignMesh buildClutterTankard(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Main ale tankard with iron hoops, handle, and frothy foam head
        m.box(-4.2f, -8.0f, -2.8f, 0.4f, -2.0f, 1.8f, p.woodMain);
        m.box(-4.4f, -7.2f, -3.0f, 0.6f, -6.2f, 2.0f, MAT_IRON_DARK);
        m.box(-4.4f, -3.8f, -3.0f, 0.6f, -2.8f, 2.0f, MAT_IRON_DARK);
        m.box(-3.8f, -2.0f, -2.4f, 0.0f, -1.0f, 1.4f, MAT_FOAM_CREAM);
        m.box(-5.6f, -6.6f, -1.1f, -4.2f, -2.8f, 0.1f, MAT_IRON_DARK);
        // Second smaller pewter mug + scattered gold coins
        m.angledBox(3.0f, -5.6f, 1.4f, 3.4f, 4.8f, 3.4f, 0f, 22f, 0f, MAT_IRON_LIGHT);
        m.box(1.2f, -8.0f, -3.8f, 3.0f, -7.4f, -2.0f, MAT_GOLD);
        return m;
    }

    private static ShopSignMesh buildClutterPlatter(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Large trencher platter with raised rim
        m.box(-6.4f, -8.0f, -4.8f, 6.4f, -6.8f, 4.8f, p.metal);
        m.box(-5.4f, -6.8f, -3.8f, 5.4f, -6.3f, 3.8f, p.woodMain);
        // 3D roasted meat haunch + white bone
        m.angledBox(-1.6f, -4.8f, -0.6f, 5.2f, 3.2f, 4.0f, 0f, 15f, 0f, MAT_LEATHER_WARM);
        m.angledBox(-4.6f, -4.6f, -1.2f, 2.6f, 1.2f, 1.2f, 0f, 15f, 0f, MAT_BONEMEAL);
        // Golden bread loaf & wedge of cheese
        m.box(1.4f, -6.3f, -3.0f, 4.8f, -3.8f, 0.2f, MAT_BREAD_CRUST);
        m.box(0.8f, -6.3f, 0.8f, 4.4f, -4.2f, 3.4f, MAT_AMBER_GLOW);
        return m;
    }

    private static ShopSignMesh buildClutterGoblets(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Serving tray
        m.box(-5.4f, -8.0f, -4.2f, 5.4f, -7.2f, 4.2f, p.metal);
        // Tall dark-emerald wine bottle + neck + cork
        m.box(-4.0f, -7.2f, -2.6f, -0.8f, -1.4f, 0.6f, MAT_EMERALD);
        m.box(-3.1f, -1.4f, -1.7f, -1.7f, 1.2f, -0.3f, MAT_EMERALD);
        m.box(-2.9f, 1.2f, -1.5f, -1.9f, 2.1f, -0.5f, MAT_ROPE_TAN);
        // Two 3D stemmed golden goblets
        for (float[] g : new float[][]{{1.6f, -1.4f}, {2.8f, 1.8f}}) {
            m.box(g[0] - 1.2f, -7.2f, g[1] - 1.2f, g[0] + 1.2f, -6.4f, g[1] + 1.2f, MAT_GOLD);
            m.box(g[0] - 0.45f, -6.4f, g[1] - 0.45f, g[0] + 0.45f, -4.4f, g[1] + 0.45f, MAT_GOLD);
            m.box(g[0] - 1.3f, -4.4f, g[1] - 1.3f, g[0] + 1.3f, -2.0f, g[1] + 1.3f, MAT_GOLD);
            m.box(g[0] - 1.0f, -2.3f, g[1] - 1.0f, g[0] + 1.0f, -1.9f, g[1] + 1.0f, MAT_CRIMSON);
        }
        return m;
    }

    private static ShopSignMesh buildClutterCandelabra(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Stepped base & central column
        m.box(-3.2f, -8.0f, -3.2f, 3.2f, -6.8f, 3.2f, p.metalAccent);
        m.box(-2.0f, -6.8f, -2.0f, 2.0f, -5.8f, 2.0f, p.metal);
        m.box(-0.7f, -5.8f, -0.7f, 0.7f, 1.2f, 0.7f, p.metalAccent);
        // Sweeping 3-branch crossbar
        m.box(-5.0f, -1.2f, -0.6f, 5.0f, 0.0f, 0.6f, p.metalAccent);
        // 3 candle cups, cream wax candles & glowing flames
        for (float cx : new float[]{-4.4f, 0.0f, 4.4f}) {
            float top = cx == 0.0f ? 5.2f : 4.2f;
            m.box(cx - 1.2f, 0.0f, -1.2f, cx + 1.2f, 0.8f, 1.2f, p.metal);
            m.box(cx - 0.75f, 0.8f, -0.75f, cx + 0.75f, top, 0.75f, MAT_FOAM_CREAM);
            m.box(cx - 0.45f, top, -0.45f, cx + 0.45f, top + 1.6f, 0.45f, MAT_ORANGE_FIRE);
        }
        return m;
    }

    private static ShopSignMesh buildClutterApothecary(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Wooden vial rack with 3 glowing potion bottles
        m.box(-5.8f, -8.0f, -3.6f, 1.6f, -5.4f, -0.4f, p.woodMain);
        int[] potions = {MAT_CRIMSON, MAT_LIME_POTION, MAT_ARCANE_BLUE};
        for (int i = 0; i < 3; i++) {
            float vx = -4.6f + i * 2.4f;
            m.box(vx - 0.85f, -7.4f, -2.9f, vx + 0.85f, -2.4f, -1.1f, potions[i]);
            m.box(vx - 0.5f, -2.4f, -2.5f, vx + 0.5f, -1.2f, -1.5f, MAT_ROPE_TAN);
        }
        // Stone mortar & angled pestle
        m.box(1.8f, -8.0f, 0.2f, 5.6f, -4.6f, 4.0f, p.stone);
        m.angledBox(4.0f, -3.6f, 2.1f, 1.1f, 4.2f, 1.1f, 20f, 0f, -26f, MAT_BONEMEAL);
        return m;
    }

    private static ShopSignMesh buildClutterScales(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Base & central brass pillar
        m.box(-4.2f, -8.0f, -3.0f, 1.8f, -6.8f, 3.0f, p.woodMain);
        m.box(-1.9f, -6.8f, -0.7f, -0.5f, 1.4f, 0.7f, MAT_BRASS);
        // Balance beam & twin hanging pans
        m.box(-5.8f, 0.4f, -0.5f, 3.4f, 1.4f, 0.5f, MAT_GOLD);
        for (float px : new float[]{-5.2f, 2.8f}) {
            m.box(px - 0.35f, -3.8f, -0.35f, px + 0.35f, 0.4f, 0.35f, MAT_IRON_LIGHT);
            m.box(px - 1.8f, -4.6f, -1.8f, px + 1.8f, -3.8f, 1.8f, MAT_GOLD);
        }
        // Tied leather coin pouch & gold septim stack
        m.box(2.2f, -8.0f, -3.8f, 5.6f, -4.4f, -0.4f, MAT_LEATHER_WARM);
        m.box(3.0f, -4.4f, -3.0f, 4.8f, -3.4f, -1.2f, MAT_GOLD);
        return m;
    }

    private static ShopSignMesh buildClutterLedger(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Open leather-bound ledger with parchment pages
        m.angledBox(-1.2f, -7.2f, 0.4f, 7.6f, 1.4f, 5.6f, 0f, -10f, 0f, p.cushion);
        m.angledBox(-1.2f, -6.4f, 0.4f, 6.8f, 0.8f, 4.8f, 0f, -10f, 0f, MAT_PARCHMENT);
        // Inkpot + tall white feather quill
        m.box(3.4f, -8.0f, -3.4f, 5.4f, -5.8f, -1.4f, p.metal);
        m.angledBox(4.6f, -3.2f, -2.4f, 0.6f, 5.4f, 1.1f, 16f, 0f, -24f, MAT_PEARL_WHITE);
        // Rolled scroll with crimson wax seal
        m.box(-5.6f, -8.0f, -4.4f, 1.6f, -6.6f, -2.8f, MAT_PARCHMENT);
        m.box(-2.6f, -8.1f, -4.5f, -1.2f, -6.4f, -2.7f, MAT_CRIMSON);
        return m;
    }

    private static ShopSignMesh buildClutterArmorStand(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Base cross & vertical post
        m.box(-5.6f, -8.0f, -5.6f, 5.6f, -6.4f, 5.6f, p.woodMain);
        m.box(-1.2f, -6.4f, -1.2f, 1.2f, 16.5f, 1.2f, p.woodTrim);
        // Cuirass torso & pauldrons
        m.box(-4.6f, 0.5f, -2.8f, 4.6f, 10.2f, 2.8f, p.metal);
        m.box(-6.8f, 7.2f, -3.0f, -4.4f, 10.8f, 3.0f, p.metalAccent);
        m.box(4.4f, 7.2f, -3.0f, 6.8f, 10.8f, 3.0f, p.metalAccent);
        m.box(-4.8f, 0.0f, -3.0f, 4.8f, 1.6f, 3.0f, MAT_LEATHER_WARM);
        // Crested helm at top
        m.box(-3.0f, 12.0f, -3.0f, 3.0f, 17.2f, 3.0f, p.metal);
        m.box(-0.8f, 17.2f, -3.4f, 0.8f, 19.6f, 3.4f, p.cloth);
        return m;
    }

    // ------------------------------------------------------------------ Wall Clutter

    private static ShopSignMesh buildWallShieldPlaque(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Carved wooden wall plaque backing against z = -8
        m.box(-6.2f, -6.2f, -8.0f, 6.2f, 6.2f, -6.6f, p.woodMain);
        m.box(-5.2f, -5.2f, -6.6f, 5.2f, 5.2f, -6.1f, p.woodTrim);
        // Crossed 3D steel swords behind the shield
        m.angledBox(0f, 0f, -5.7f, 1.5f, 15.2f, 0.9f, 0f, 0f, 40f, MAT_STEEL_BRIGHT);
        m.angledBox(0f, 0f, -5.7f, 1.5f, 15.2f, 0.9f, 0f, 0f, -40f, MAT_STEEL_BRIGHT);
        m.angledBox(-3.8f, 4.5f, -5.6f, 4.0f, 1.1f, 1.2f, 0f, 0f, 40f, MAT_GOLD);
        m.angledBox(3.8f, 4.5f, -5.6f, 4.0f, 1.1f, 1.2f, 0f, 0f, -40f, MAT_GOLD);
        // Cultural shield with rim & central boss
        m.box(-4.4f, -4.8f, -5.2f, 4.4f, 4.8f, -3.8f, p.metal);
        m.box(-3.5f, -3.9f, -3.8f, 3.5f, 3.9f, -3.2f, p.cloth);
        m.box(-1.5f, -1.5f, -3.2f, 1.5f, 1.5f, -2.1f, p.metalAccent);
        return m;
    }

    private static ShopSignMesh buildWallAntlerTrophy(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Shield mount plaque
        m.box(-4.8f, -5.4f, -8.0f, 4.8f, 3.8f, -6.5f, p.woodMain);
        m.box(-3.8f, -4.4f, -6.5f, 3.8f, 2.8f, -5.9f, p.woodTrim);
        // 3D Stag skull & snout jutting out into the room
        m.box(-2.4f, -2.2f, -5.9f, 2.4f, 2.0f, -2.4f, MAT_BONEMEAL);
        m.box(-1.5f, -4.2f, -3.2f, 1.5f, -0.4f, -0.4f, MAT_BONEMEAL);
        // Sweeping multi-tine antlers
        m.angledBox(-4.4f, 3.4f, -3.8f, 1.3f, 7.6f, 1.3f, 18f, 0f, 42f, MAT_BONEMEAL);
        m.angledBox(4.4f, 3.4f, -3.8f, 1.3f, 7.6f, 1.3f, 18f, 0f, -42f, MAT_BONEMEAL);
        m.angledBox(-5.6f, 5.6f, -2.4f, 1.1f, 4.4f, 1.1f, 24f, 0f, 12f, MAT_BONEMEAL);
        m.angledBox(5.6f, 5.6f, -2.4f, 1.1f, 4.4f, 1.1f, 24f, 0f, -12f, MAT_BONEMEAL);
        m.angledBox(-2.8f, 4.6f, -2.8f, 1.0f, 3.6f, 1.0f, 22f, 0f, -16f, MAT_BONEMEAL);
        m.angledBox(2.8f, 4.6f, -2.8f, 1.0f, 3.6f, 1.0f, 22f, 0f, 16f, MAT_BONEMEAL);
        return m;
    }

    private static ShopSignMesh buildWallTapestry(Culture c, StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Top hanging rod & brackets
        m.box(-7.6f, 6.2f, -8.0f, 7.6f, 7.6f, -6.2f, p.metal);
        m.box(-8.2f, 5.9f, -7.8f, -7.2f, 7.9f, -6.0f, p.metalAccent);
        m.box(7.2f, 5.9f, -7.8f, 8.2f, 7.9f, -6.0f, p.metalAccent);
        // Rich 20px-tall woven tapestry hanging down the wall
        m.box(-6.6f, -13.0f, -7.6f, 6.6f, 6.2f, -6.6f, p.cloth);
        m.box(-5.2f, -11.4f, -6.6f, 5.2f, 4.8f, -6.2f, p.metalAccent);
        m.box(-3.8f, -9.8f, -6.2f, 3.8f, 3.2f, -5.9f, p.cushion);
        m.angledBox(0f, -3.2f, -5.8f, 4.2f, 4.2f, 0.8f, 0f, 0f, 45f, p.metal);
        // Bottom fringe
        for (int i = -3; i <= 3; i++) {
            m.box(i * 1.8f - 0.6f, -15.0f, -7.4f, i * 1.8f + 0.6f, -13.0f, -6.6f, p.metalAccent);
        }
        return m;
    }

    private static ShopSignMesh buildWallShelf(StylePalette p) {
        ShopSignMesh m = new ShopSignMesh();
        // Corbel brackets & thick shelf plank
        m.box(-6.4f, -5.4f, -8.0f, -4.8f, -1.2f, -2.6f, p.woodTrim);
        m.box(4.8f, -5.4f, -8.0f, 6.4f, -1.2f, -2.6f, p.woodTrim);
        m.box(-7.6f, -1.2f, -8.0f, 7.6f, 0.4f, -1.8f, p.woodMain);
        // Leaning 3D books, potion bottle & golden chalice on the shelf
        m.box(-6.2f, 0.4f, -7.2f, -4.4f, 5.4f, -3.0f, MAT_CRIMSON);
        m.box(-4.3f, 0.4f, -7.2f, -2.5f, 5.8f, -3.0f, MAT_COBALT);
        m.angledBox(-0.8f, 2.9f, -5.1f, 1.8f, 5.2f, 4.2f, 0f, 0f, -16f, MAT_LEATHER_WARM);
        m.box(2.0f, 0.4f, -6.2f, 4.0f, 4.4f, -4.2f, MAT_EMERALD);
        m.box(4.6f, 0.4f, -6.0f, 6.4f, 3.8f, -4.2f, MAT_GOLD);
        return m;
    }
}

