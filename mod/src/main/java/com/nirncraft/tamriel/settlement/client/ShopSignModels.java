package com.nirncraft.tamriel.settlement.client;

import com.nirncraft.tamriel.settlement.Culture;

import java.util.EnumMap;
import java.util.Map;

/**
 * Procedural 3D model library for regional shop signs.
 *
 * <p>Each sign is composed of two 3D meshes:
 * <ul>
 *   <li><b>Static Bracket Mesh</b> (keyed by {@link Culture}): wall mounting plate, cantilevered
 *       beam, regional scroll/arch/tusk brace, outer finial, and suspension chains/rods.</li>
 *   <li><b>Swinging Plaque + 3D Trade Emblem Mesh</b> (keyed by {@link Culture} and {@link ShopSignType}):
 *       regional shaped signboard frame and a deep-relief 3D sculpted trade symbol on both sides
 *       of the plaque so players immediately recognize the building type without reading text.</li>
 * </ul>
 */
public final class ShopSignModels {
    // Material tile indices matching tools/generate_shop_sign_atlas.py
    public static final int MAT_SPRUCE_DARK   = 0;
    public static final int MAT_SPRUCE_MID    = 1;
    public static final int MAT_OAK_RICH      = 2;
    public static final int MAT_BIRCH_CREAM   = 3;
    public static final int MAT_WALNUT        = 4;
    public static final int MAT_ACACIA        = 5;
    public static final int MAT_MANGROVE      = 6;
    public static final int MAT_CHARRED       = 7;
    public static final int MAT_IRON_DARK     = 8;
    public static final int MAT_IRON_LIGHT    = 9;
    public static final int MAT_STEEL_BRIGHT  = 10;
    public static final int MAT_GOLD          = 11;
    public static final int MAT_BRASS         = 12;
    public static final int MAT_COPPER        = 13;
    public static final int MAT_BRONZE        = 14;
    public static final int MAT_SILVER_MIST   = 15;
    public static final int MAT_CRIMSON       = 16;
    public static final int MAT_COBALT        = 17;
    public static final int MAT_EMERALD       = 18;
    public static final int MAT_AMBER_GLOW    = 19;
    public static final int MAT_ARCANE_BLUE   = 20;
    public static final int MAT_PURPLE_GEM    = 21;
    public static final int MAT_PARCHMENT     = 22;
    public static final int MAT_BONEMEAL      = 23;
    public static final int MAT_CHITIN        = 24;
    public static final int MAT_SANDSTONE     = 25;
    public static final int MAT_SLATE_BLUE    = 26;
    public static final int MAT_PEARL_WHITE   = 27;
    public static final int MAT_LEATHER_WARM  = 28;
    public static final int MAT_BREAD_CRUST   = 29;
    public static final int MAT_FOAM_CREAM    = 30;
    public static final int MAT_TEAL_FISH     = 31;
    public static final int MAT_ORC_IRON      = 32;
    public static final int MAT_LIME_POTION   = 33;
    public static final int MAT_ORANGE_FIRE   = 34;
    public static final int MAT_ROPE_TAN      = 35;
    public static final int MAT_EBONY_STEEL   = 36;
    public static final int MAT_CYAN_GEM      = 37;
    public static final int MAT_MUSHROOM      = 38;
    public static final int MAT_GOLD_DARK     = 39;

    /** The 16 main shop and civic building sign archetypes. */
    public enum ShopSignType {
        SMITHY("Blacksmith"),
        APOTHECARY("Apothecary"),
        INN("Inn & Tavern"),
        GENERAL("General Goods"),
        FLETCHER("Fletcher"),
        MAGE("Arcanaeum & Books"),
        JEWELER("Jeweler"),
        TAILOR("Tailor & Clothier"),
        BUTCHER("Butcher"),
        BAKERY("Bakery & Mill"),
        FISHERY("Fishery"),
        STABLES("Stables"),
        TEMPLE("Temple"),
        BARRACKS("Barracks & Guard"),
        KEEP("Keep & Palace"),
        GUILD("Guild Hall");

        public final String displayTitle;

        ShopSignType(String displayTitle) {
            this.displayTitle = displayTitle;
        }
    }

    public record SignModelPair(ShopSignMesh bracket, ShopSignMesh swingingPlaque) {}

    private static final Map<Culture, Map<ShopSignType, SignModelPair>> CACHE = new EnumMap<>(Culture.class);

    private ShopSignModels() {}

    public static synchronized SignModelPair get(Culture culture, ShopSignType type) {
        Culture c = culture != null ? culture : Culture.NORD;
        Map<ShopSignType, SignModelPair> byType = CACHE.computeIfAbsent(c, k -> new EnumMap<>(ShopSignType.class));
        return byType.computeIfAbsent(type, t -> buildPair(c, t));
    }

    private static SignModelPair buildPair(Culture culture, ShopSignType type) {
        ShopSignMesh bracket = new ShopSignMesh();
        ShopSignMesh plaque = new ShopSignMesh();
        buildRegionalBracketAndPlaque(culture, bracket, plaque);
        buildTradeSymbol(type, plaque);
        return new SignModelPair(bracket, plaque);
    }

    // =========================================================================
    // 1. REGIONAL BRACKETS & PLAQUE FRAMES (10 TAMRIEL CULTURES)
    // =========================================================================

    private static void buildRegionalBracketAndPlaque(Culture culture, ShopSignMesh bracket, ShopSignMesh plaque) {
        // Plaque center is (Y = -2.4, Z = 0.2), half-thickness X = 0.9f
        float py = -2.4f;
        float pz = 0.2f;
        switch (culture) {
            case NORD -> {
                // Wall backing timber & iron straps
                bracket.box(-2.0f, -3.5f, -8.0f, 2.0f, 7.8f, -6.7f, MAT_SPRUCE_DARK);
                bracket.box(-2.3f, 4.8f, -8.0f, 2.3f, 7.4f, -6.4f, MAT_IRON_DARK);
                bracket.box(-2.2f, -2.5f, -8.0f, 2.2f, -0.8f, -6.5f, MAT_IRON_DARK);
                // Cantilevered carved spruce beam + iron bindings
                bracket.box(-1.5f, 5.4f, -7.0f, 1.5f, 7.4f, 6.2f, MAT_SPRUCE_DARK);
                bracket.box(-1.7f, 5.2f, -3.8f, 1.7f, 7.6f, -2.4f, MAT_IRON_DARK);
                bracket.box(-1.7f, 5.2f, 2.8f, 1.7f, 7.6f, 4.2f, MAT_IRON_DARK);
                // Diagonal brace
                bracket.strutYZ(-1.5f, -7.0f, 5.6f, -1.2f, 1.8f, 1.6f, MAT_SPRUCE_MID);
                // Carved Nordic dragon/wolf prow finial at outer tip
                bracket.angledBox(0f, 6.8f, 6.6f, 2.0f, 2.2f, 2.6f, -28f, 0f, 0f, MAT_SPRUCE_MID);
                bracket.angledBox(0f, 7.9f, 7.4f, 1.4f, 1.6f, 2.0f, -45f, 0f, 0f, MAT_IRON_LIGHT);
                // Twin heavy iron chains
                addChains(bracket, -3.1f, 3.5f, 2.6f, 5.4f, MAT_IRON_DARK);

                // Swinging Nordic plaque: dark spruce inner board, carved frame, iron corner bosses, stepped gable top
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_SPRUCE_MID);
                addFrame(plaque, py, pz, 5.4f, 5.4f, 1.25f, 0.9f, MAT_SPRUCE_DARK);
                // Stepped Nordic gable roof on plaque
                plaque.box(-1.4f, py + 4.8f, pz - 4.2f, 1.4f, py + 5.7f, pz + 4.2f, MAT_IRON_DARK);
                plaque.box(-1.2f, py + 5.5f, pz - 2.4f, 1.2f, py + 6.3f, pz + 2.4f, MAT_GOLD_DARK);
                // Iron corner studs
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_IRON_LIGHT);
            }
            case IMPERIAL -> {
                // Wrought iron wall mount with brass collars
                bracket.box(-1.8f, -3.8f, -8.0f, 1.8f, 7.8f, -6.8f, MAT_IRON_DARK);
                bracket.box(-2.1f, 5.2f, -8.0f, 2.1f, 7.2f, -6.4f, MAT_BRASS);
                // Classical wrought iron arm + spear finial
                bracket.box(-1.2f, 5.6f, -6.8f, 1.2f, 7.0f, 6.4f, MAT_IRON_DARK);
                bracket.box(-1.5f, 5.4f, -0.5f, 1.5f, 7.2f, 0.8f, MAT_BRASS);
                bracket.angledBox(0f, 6.3f, 7.0f, 1.6f, 1.6f, 2.2f, 45f, 0f, 0f, MAT_GOLD);
                // Classical scroll brace
                bracket.strutYZ(-1.8f, -7.0f, 5.6f, -0.5f, 1.3f, 1.2f, MAT_IRON_LIGHT);
                bracket.box(-1.4f, 1.2f, -5.2f, 1.4f, 2.6f, -3.8f, MAT_BRASS);
                addChains(bracket, -3.0f, 3.4f, 2.8f, 5.6f, MAT_BRASS);

                // Imperial pedimented plaque: rich oak field, walnut border, golden pediment cap
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_OAK_RICH);
                addFrame(plaque, py, pz, 5.4f, 5.4f, 1.25f, 0.9f, MAT_WALNUT);
                plaque.box(-1.4f, py + 4.8f, pz - 5.6f, 1.4f, py + 5.6f, pz + 5.6f, MAT_GOLD);
                plaque.box(-1.2f, py + 5.5f, pz - 3.2f, 1.2f, py + 6.3f, pz + 3.2f, MAT_GOLD);
                plaque.box(-1.3f, py - 5.9f, pz - 2.2f, 1.3f, py - 5.2f, pz + 2.2f, MAT_BRASS);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_GOLD);
            }
            case BRETON -> {
                // Ornate Gothic wrought iron bracket
                bracket.box(-1.7f, -4.0f, -8.0f, 1.7f, 7.8f, -6.9f, MAT_IRON_LIGHT);
                bracket.box(-1.1f, 5.6f, -6.9f, 1.1f, 6.9f, 6.4f, MAT_IRON_LIGHT);
                // Pointed Gothic arch struts
                bracket.strutYZ(-2.2f, -7.0f, 3.2f, -3.8f, 1.2f, 1.1f, MAT_IRON_LIGHT);
                bracket.strutYZ(3.2f, -3.8f, 5.6f, 0.2f, 1.2f, 1.1f, MAT_IRON_LIGHT);
                // Gold fleur-de-lis tip
                bracket.box(-1.2f, 5.4f, 6.2f, 1.2f, 7.8f, 7.5f, MAT_GOLD);
                bracket.angledBox(0f, 7.4f, 6.8f, 1.4f, 2.0f, 1.4f, 30f, 0f, 0f, MAT_GOLD);
                addChains(bracket, -3.0f, 3.4f, 2.8f, 5.6f, MAT_IRON_LIGHT);

                // Breton cream birch heraldic board with walnut & gold trim
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_BIRCH_CREAM);
                addFrame(plaque, py, pz, 5.3f, 5.3f, 1.25f, 0.85f, MAT_WALNUT);
                plaque.box(-1.35f, py + 4.9f, pz - 1.5f, 1.35f, py + 6.4f, pz + 1.5f, MAT_GOLD);
                plaque.box(-1.35f, py - 6.1f, pz - 1.2f, 1.35f, py - 5.1f, pz + 1.2f, MAT_GOLD);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_GOLD);
            }
            case REDGUARD -> {
                // Hammered bronze & brass curved scimitar bracket
                bracket.box(-1.9f, -3.5f, -8.0f, 1.9f, 7.8f, -6.8f, MAT_BRONZE);
                bracket.box(-1.4f, 5.5f, -6.8f, 1.4f, 7.0f, 6.2f, MAT_BRONZE);
                bracket.strutYZ(-1.5f, -6.8f, 5.6f, 0.6f, 1.4f, 1.3f, MAT_BRASS);
                // Crescent & dome finial at tip
                bracket.box(-1.5f, 5.3f, 6.0f, 1.5f, 7.9f, 7.6f, MAT_GOLD);
                bracket.box(-1.6f, 5.6f, -3.5f, 1.6f, 7.2f, -2.5f, MAT_CYAN_GEM);
                bracket.box(-1.6f, 5.6f, 3.0f, 1.6f, 7.2f, 4.0f, MAT_CYAN_GEM);
                addChains(bracket, -3.0f, 3.5f, 2.8f, 5.5f, MAT_GOLD);

                // Redguard acacia & hammered bronze arch plaque
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_SANDSTONE);
                addFrame(plaque, py, pz, 5.4f, 5.4f, 1.25f, 0.9f, MAT_ACACIA);
                plaque.box(-1.4f, py + 4.8f, pz - 3.8f, 1.4f, py + 5.8f, pz + 3.8f, MAT_BRONZE);
                plaque.box(-1.3f, py + 5.6f, pz - 1.6f, 1.3f, py + 6.5f, pz + 1.6f, MAT_GOLD);
                plaque.box(-1.3f, py - 6.2f, pz - 1.4f, 1.3f, py - 5.2f, pz + 1.4f, MAT_GOLD);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_CYAN_GEM);
            }
            case DUNMER -> {
                // Velothi chitin & ebony swept-horn bracket
                bracket.box(-1.9f, -3.6f, -8.0f, 1.9f, 7.8f, -6.7f, MAT_CHITIN);
                bracket.box(-1.4f, 5.5f, -6.8f, 1.4f, 7.1f, 5.8f, MAT_EBONY_STEEL);
                bracket.strutYZ(-1.8f, -6.9f, 5.6f, -0.4f, 1.5f, 1.4f, MAT_CHITIN);
                // Swept Velothi chitin horn at tip + glowing amber node
                bracket.angledBox(0f, 7.0f, 6.4f, 1.8f, 1.6f, 2.8f, -35f, 0f, 0f, MAT_CHITIN);
                bracket.box(-1.5f, 4.2f, 5.6f, 1.5f, 5.6f, 6.8f, MAT_AMBER_GLOW);
                addChains(bracket, -3.0f, 3.4f, 2.8f, 5.5f, MAT_EBONY_STEEL);

                // Dunmer hexagonal carapace plaque
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_MUSHROOM);
                addFrame(plaque, py, pz, 5.4f, 5.4f, 1.25f, 0.9f, MAT_CHITIN);
                plaque.box(-1.4f, py + 4.8f, pz - 3.4f, 1.4f, py + 5.9f, pz + 3.4f, MAT_EBONY_STEEL);
                plaque.box(-1.4f, py - 6.0f, pz - 3.0f, 1.4f, py - 5.1f, pz + 3.0f, MAT_EBONY_STEEL);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_BONEMEAL);
            }
            case ALTMER -> {
                // Alinor gilded filigree & pearl white bracket
                bracket.box(-1.7f, -3.8f, -8.0f, 1.7f, 7.9f, -6.8f, MAT_PEARL_WHITE);
                bracket.box(-1.2f, 5.7f, -6.8f, 1.2f, 7.0f, 6.5f, MAT_GOLD);
                bracket.strutYZ(-1.6f, -6.9f, 5.7f, 0.8f, 1.2f, 1.1f, MAT_GOLD);
                // Soaring wing spire & arcane crystal finial
                bracket.angledBox(0f, 7.3f, 6.8f, 1.4f, 2.4f, 1.6f, -25f, 0f, 0f, MAT_GOLD);
                bracket.box(-1.3f, 4.4f, 6.0f, 1.3f, 5.8f, 7.2f, MAT_ARCANE_BLUE);
                addChains(bracket, -3.0f, 3.4f, 2.8f, 5.7f, MAT_GOLD);

                // Altmer royal cobalt-blue & pearl plaque with sculpted sun-gold wing frame
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_COBALT);
                addFrame(plaque, py, pz, 5.3f, 5.3f, 1.25f, 0.85f, MAT_PEARL_WHITE);
                plaque.box(-1.35f, py + 4.8f, pz - 2.6f, 1.35f, py + 6.2f, pz + 2.6f, MAT_GOLD);
                plaque.box(-1.35f, py - 6.1f, pz - 2.0f, 1.35f, py - 5.0f, pz + 2.0f, MAT_ARCANE_BLUE);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_GOLD);
            }
            case BOSMER -> {
                // Living heartwood branch & stag antler bracket
                bracket.box(-1.9f, -3.4f, -8.0f, 1.9f, 7.6f, -6.7f, MAT_OAK_RICH);
                bracket.box(-1.4f, 5.4f, -6.8f, 1.4f, 7.0f, 6.0f, MAT_OAK_RICH);
                bracket.strutYZ(-1.4f, -6.8f, 5.5f, -0.8f, 1.5f, 1.4f, MAT_BONEMEAL);
                // Antler tines at outer tip
                bracket.angledBox(0f, 7.2f, 6.2f, 1.3f, 2.4f, 1.3f, -30f, 0f, 0f, MAT_BONEMEAL);
                bracket.angledBox(0f, 7.4f, 4.6f, 1.2f, 2.0f, 1.2f, -15f, 0f, 0f, MAT_BONEMEAL);
                bracket.box(-1.6f, 5.2f, -3.4f, 1.6f, 7.2f, -2.4f, MAT_EMERALD);
                bracket.box(-1.6f, 5.2f, 3.0f, 1.6f, 7.2f, 4.0f, MAT_EMERALD);
                addChains(bracket, -2.9f, 3.5f, 2.7f, 5.4f, MAT_ROPE_TAN);

                // Bosmer heartwood & bone plaque
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_BIRCH_CREAM);
                addFrame(plaque, py, pz, 5.4f, 5.4f, 1.25f, 0.9f, MAT_OAK_RICH);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_BONEMEAL);
            }
            case KHAJIIT -> {
                // Upturned temple-eave beam & lunar crescents
                bracket.box(-1.9f, -3.6f, -8.0f, 1.9f, 7.8f, -6.7f, MAT_ACACIA);
                bracket.box(-1.4f, 5.5f, -6.8f, 1.4f, 7.0f, 6.0f, MAT_ACACIA);
                bracket.strutYZ(-1.6f, -6.8f, 5.6f, -0.4f, 1.4f, 1.3f, MAT_GOLD);
                bracket.angledBox(0f, 7.2f, 6.5f, 1.8f, 1.6f, 2.6f, -32f, 0f, 0f, MAT_GOLD);
                addChains(bracket, -3.0f, 3.4f, 2.8f, 5.5f, MAT_GOLD);

                // Khajiit lantern plaque + crimson tassel
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_SANDSTONE);
                addFrame(plaque, py, pz, 5.4f, 5.4f, 1.25f, 0.9f, MAT_ACACIA);
                plaque.box(-1.4f, py + 4.8f, pz - 3.6f, 1.4f, py + 5.8f, pz + 3.6f, MAT_GOLD);
                plaque.box(-1.1f, py - 6.8f, pz - 0.9f, 1.1f, py - 5.1f, pz + 0.9f, MAT_CRIMSON);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_GOLD);
            }
            case ARGONIAN -> {
                // Mangrove timber & stepped Xanmeer stone bracket
                bracket.box(-2.0f, -3.6f, -8.0f, 2.0f, 7.8f, -6.7f, MAT_SLATE_BLUE);
                bracket.box(-1.5f, 5.4f, -6.8f, 1.5f, 7.1f, 6.0f, MAT_MANGROVE);
                bracket.strutYZ(-1.6f, -6.8f, 5.5f, -0.6f, 1.5f, 1.4f, MAT_MANGROVE);
                // Stepped Xanmeer stone pyramid tip
                bracket.box(-1.6f, 5.4f, 5.8f, 1.6f, 7.5f, 7.4f, MAT_SLATE_BLUE);
                bracket.box(-1.2f, 7.3f, 6.1f, 1.2f, 8.2f, 7.1f, MAT_EMERALD);
                addChains(bracket, -3.0f, 3.4f, 2.7f, 5.4f, MAT_ROPE_TAN);

                // Stepped Xanmeer tablet plaque
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_MANGROVE);
                addFrame(plaque, py, pz, 5.4f, 5.4f, 1.25f, 0.9f, MAT_SLATE_BLUE);
                plaque.box(-1.4f, py + 4.8f, pz - 3.4f, 1.4f, py + 5.8f, pz + 3.4f, MAT_COPPER);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.45f, MAT_EMERALD);
            }
            case ORC -> {
                // Brutalist forged pig-iron girder + twin tusks
                bracket.box(-2.2f, -3.8f, -8.0f, 2.2f, 7.9f, -6.5f, MAT_ORC_IRON);
                bracket.box(-1.6f, 5.3f, -6.6f, 1.6f, 7.4f, 6.2f, MAT_ORC_IRON);
                bracket.strutYZ(-1.8f, -6.7f, 5.4f, -0.2f, 1.8f, 1.7f, MAT_CHARRED);
                // Twin upward tusks & iron spike at tip
                bracket.angledBox(0f, 7.3f, 6.6f, 2.0f, 2.6f, 1.5f, -25f, 0f, 0f, MAT_BONEMEAL);
                bracket.box(-1.8f, 5.0f, 5.6f, 1.8f, 7.6f, 7.4f, MAT_ORC_IRON);
                addChains(bracket, -3.1f, 3.5f, 2.6f, 5.3f, MAT_ORC_IRON);

                // Spiked iron war-plaque
                plaque.box(-0.9f, py - 4.8f, pz - 4.8f, 0.9f, py + 4.6f, pz + 4.8f, MAT_CHARRED);
                addFrame(plaque, py, pz, 5.5f, 5.5f, 1.35f, 0.95f, MAT_ORC_IRON);
                // Bottom spikes & top tusks
                plaque.box(-1.1f, py - 6.4f, pz - 3.2f, 1.1f, py - 5.3f, pz - 2.0f, MAT_ORC_IRON);
                plaque.box(-1.1f, py - 6.6f, pz - 0.6f, 1.1f, py - 5.3f, pz + 0.6f, MAT_ORC_IRON);
                plaque.box(-1.1f, py - 6.4f, pz + 2.0f, 1.1f, py - 5.3f, pz + 3.2f, MAT_ORC_IRON);
                addCornerStuds(plaque, py, pz, 4.9f, 4.9f, 1.55f, MAT_BONEMEAL);
            }
        }
    }

    private static void addChains(ShopSignMesh bracket, float z0, float z1, float yBottom, float yTop, int mat) {
        bracket.box(-0.7f, yBottom, z0 - 0.6f, 0.7f, yTop, z0 + 0.6f, mat);
        bracket.box(-0.7f, yBottom, z1 - 0.6f, 0.7f, yTop, z1 + 0.6f, mat);
    }

    private static void addFrame(
            ShopSignMesh plaque, float py, float pz,
            float halfH, float halfW, float halfThick, float barWidth, int mat
    ) {
        // Top bar
        plaque.box(-halfThick, py + halfH - barWidth, pz - halfW, halfThick, py + halfH, pz + halfW, mat);
        // Bottom bar
        plaque.box(-halfThick, py - halfH, pz - halfW, halfThick, py - halfH + barWidth, pz + halfW, mat);
        // Inner Z bar (-Z)
        plaque.box(-halfThick, py - halfH + barWidth, pz - halfW, halfThick, py + halfH - barWidth, pz - halfW + barWidth, mat);
        // Outer Z bar (+Z)
        plaque.box(-halfThick, py - halfH + barWidth, pz + halfW - barWidth, halfThick, py + halfH - barWidth, pz + halfW, mat);
    }

    private static void addCornerStuds(ShopSignMesh plaque, float py, float pz, float dy, float dz, float hx, int mat) {
        float s = 0.65f;
        plaque.box(-hx, py + dy - s, pz + dz - s, hx, py + dy + s, pz + dz + s, mat);
        plaque.box(-hx, py + dy - s, pz - dz - s, hx, py + dy + s, pz - dz + s, mat);
        plaque.box(-hx, py - dy - s, pz + dz - s, hx, py - dy + s, pz + dz + s, mat);
        plaque.box(-hx, py - dy - s, pz - dz - s, hx, py - dy + s, pz - dz + s, mat);
    }

    // =========================================================================
    // 2. SCULPTED 3D TRADE SYMBOLS (16 BUILDING TYPES, DOUBLE-SIDED RELIEF)
    // =========================================================================

    private static void buildTradeSymbol(ShopSignType type, ShopSignMesh m) {
        // Center of plaque is (py = -2.4f, pz = 0.2f), plaque surface is at |X| = 0.9f
        float cy = -2.4f;
        float cz = 0.2f;
        float t = 0.9f;

        switch (type) {
            case SMITHY -> {
                // 3D Crossed Hammer & Heated Sword behind a heavy 3D Forged Anvil
                // Diagonal hammer handle & head
                m.reliefLine(cy - 1.5f, cz - 2.6f, cy + 3.2f, cz + 1.8f, 0.75f, 0.55f, t, MAT_WALNUT);
                m.reliefAngled(cy + 2.9f, cz + 1.5f, 1.3f, 2.6f, 0.9f, t, -45f, MAT_STEEL_BRIGHT);
                // Diagonal heated broadsword
                m.reliefLine(cy - 1.2f, cz + 2.6f, cy + 3.4f, cz - 1.8f, 0.8f, 0.55f, t, MAT_STEEL_BRIGHT);
                m.reliefAngled(cy + 2.7f, cz - 1.2f, 0.5f, 1.8f, 0.7f, t, 45f, MAT_ORANGE_FIRE);
                // Anvil base, waist, body, flat face, and protruding horn
                m.reliefBox(cy - 3.9f, cz - 2.6f, cy - 2.9f, cz + 2.4f, 0.9f, t, MAT_IRON_DARK);
                m.reliefBox(cy - 2.9f, cz - 1.5f, cy - 1.6f, cz + 1.4f, 0.85f, t, MAT_IRON_DARK);
                m.reliefBox(cy - 1.6f, cz - 2.8f, cy + 0.1f, cz + 2.2f, 1.15f, t, MAT_IRON_LIGHT);
                m.reliefBox(cy - 1.2f, cz + 2.0f, cy - 0.1f, cz + 3.8f, 0.95f, t, MAT_STEEL_BRIGHT);
                m.reliefBox(cy - 0.1f, cz - 2.8f, cy + 0.4f, cz + 2.4f, 1.25f, t, MAT_STEEL_BRIGHT);
            }
            case APOTHECARY -> {
                // 3D Round Potion Flask + Glowing Emerald Liquid + Cork + Herb Leaf
                // Outer glass bulb
                m.reliefBox(cy - 3.4f, cz - 2.6f, cy + 0.6f, cz + 1.8f, 0.75f, t, MAT_ARCANE_BLUE);
                m.reliefBox(cy - 2.8f, cz - 3.1f, cy + 0.0f, cz + 2.3f, 0.7f, t, MAT_ARCANE_BLUE);
                // Glowing emerald potion fill inside the bulb
                m.reliefBox(cy - 3.0f, cz - 2.3f, cy - 0.3f, cz + 1.5f, 1.15f, t, MAT_EMERALD);
                m.reliefBox(cy - 1.6f, cz - 1.9f, cy - 0.5f, cz + 1.1f, 1.3f, t, MAT_LIME_POTION);
                // Narrow flask neck, lip, and cork stopper
                m.reliefBox(cy + 0.6f, cz - 1.1f, cy + 2.4f, cz + 0.3f, 0.85f, t, MAT_ARCANE_BLUE);
                m.reliefBox(cy + 2.1f, cz - 1.4f, cy + 2.7f, cz + 0.6f, 0.95f, t, MAT_PEARL_WHITE);
                m.reliefBox(cy + 2.7f, cz - 0.9f, cy + 3.7f, cz + 0.1f, 0.8f, t, MAT_OAK_RICH);
                // Herb leaf & pestle on side
                m.reliefAngled(cy - 1.2f, cz + 2.5f, 1.4f, 2.8f, 0.95f, t, 32f, MAT_EMERALD);
                m.reliefAngled(cy - 0.9f, cz + 2.6f, 0.6f, 2.0f, 1.1f, t, 32f, MAT_LIME_POTION);
            }
            case INN -> {
                // 3D Wooden Ale Tankard with Iron Bands, Side Handle, and Overflowing White Foam Head
                // Mug body staves
                m.reliefBox(cy - 3.7f, cz - 3.0f, cy + 1.5f, cz + 1.6f, 1.05f, t, MAT_OAK_RICH);
                // Iron hoops & brass rivets
                m.reliefBox(cy - 3.3f, cz - 3.2f, cy - 2.4f, cz + 1.8f, 1.25f, t, MAT_IRON_DARK);
                m.reliefBox(cy - 0.2f, cz - 3.2f, cy + 0.6f, cz + 1.8f, 1.25f, t, MAT_IRON_DARK);
                // Golden mead shimmer at top
                m.reliefBox(cy + 1.1f, cz - 2.8f, cy + 1.8f, cz + 1.4f, 1.15f, t, MAT_AMBER_GLOW);
                // Mug handle on +Z side
                m.reliefBox(cy - 2.6f, cz + 1.6f, cy - 1.8f, cz + 3.4f, 0.85f, t, MAT_OAK_RICH);
                m.reliefBox(cy + 0.0f, cz + 1.6f, cy + 0.8f, cz + 3.4f, 0.85f, t, MAT_OAK_RICH);
                m.reliefBox(cy - 2.6f, cz + 2.6f, cy + 0.8f, cz + 3.5f, 0.85f, t, MAT_OAK_RICH);
                // Billowing 3D foam crown spilling down side
                m.reliefBox(cy + 1.4f, cz - 3.3f, cy + 3.1f, cz + 1.9f, 1.35f, t, MAT_FOAM_CREAM);
                m.reliefBox(cy + 2.4f, cz - 2.4f, cy + 3.7f, cz + 1.0f, 1.2f, t, MAT_PEARL_WHITE);
                m.reliefBox(cy + 0.4f, cz - 2.2f, cy + 1.6f, cz - 1.0f, 1.4f, t, MAT_FOAM_CREAM);
            }
            case GENERAL -> {
                // 3D Brass Balance Scales + Suspended Pans + Coin Chest Base
                // Central pillar & base
                m.reliefBox(cy - 3.8f, cz - 1.8f, cy - 2.7f, cz + 1.8f, 1.0f, t, MAT_GOLD);
                m.reliefBox(cy - 2.7f, cz - 0.5f, cy + 3.2f, cz + 0.5f, 0.95f, t, MAT_GOLD);
                // Horizontal balance beam (slightly tilted for visual dynamism)
                m.reliefAngled(cy + 2.3f, cz, 0.85f, 5.6f, 1.15f, t, 6f, MAT_GOLD);
                // Left pan (-Z) & suspension chain
                m.reliefBox(cy - 0.9f, cz - 2.6f, cy + 2.1f, cz - 2.0f, 0.7f, t, MAT_BRASS);
                m.reliefBox(cy - 1.8f, cz - 3.4f, cy - 0.8f, cz - 1.2f, 1.3f, t, MAT_GOLD);
                // Right pan (+Z) & suspension chain
                m.reliefBox(cy - 0.5f, cz + 2.0f, cy + 2.4f, cz + 2.6f, 0.7f, t, MAT_BRASS);
                m.reliefBox(cy - 1.4f, cz + 1.2f, cy - 0.4f, cz + 3.4f, 1.3f, t, MAT_GOLD);
            }
            case FLETCHER -> {
                // 3D Curved Recurve Bow + Bowstring + Crossed Feathered Arrow
                // Bow grip & curved upper/lower limbs
                m.reliefBox(cy - 1.2f, cz - 1.8f, cy + 1.2f, cz - 0.7f, 1.1f, t, MAT_GOLD);
                m.reliefLine(cy + 0.9f, cz - 1.3f, cy + 3.6f, cz + 1.4f, 0.95f, 0.95f, t, MAT_OAK_RICH);
                m.reliefLine(cy - 0.9f, cz - 1.3f, cy - 3.6f, cz + 1.4f, 0.95f, 0.95f, t, MAT_OAK_RICH);
                // Bowstring
                m.reliefBox(cy - 3.4f, cz + 1.0f, cy + 3.4f, cz + 1.5f, 0.55f, t, MAT_BONEMEAL);
                // Diagonal arrow shaft, steel broadhead, and crimson fletching
                m.reliefLine(cy - 2.6f, cz + 3.0f, cy + 2.6f, cz - 3.0f, 0.7f, 1.25f, t, MAT_BIRCH_CREAM);
                m.reliefAngled(cy + 2.6f, cz - 3.0f, 1.5f, 1.8f, 1.35f, t, -40f, MAT_STEEL_BRIGHT);
                m.reliefAngled(cy - 2.4f, cz + 2.7f, 1.4f, 1.8f, 1.3f, t, -40f, MAT_CRIMSON);
            }
            case MAGE -> {
                // 3D Open Spell Tome at bottom + Floating Faceted Arcane Crystal & Gold Rays above
                // Open book covers & parchment pages
                m.reliefAngled(cy - 2.3f, cz - 1.8f, 2.2f, 3.4f, 0.95f, t, 14f, MAT_CRIMSON);
                m.reliefAngled(cy - 2.3f, cz + 1.8f, 2.2f, 3.4f, 0.95f, t, -14f, MAT_CRIMSON);
                m.reliefAngled(cy - 1.9f, cz - 1.6f, 2.0f, 3.0f, 1.2f, t, 14f, MAT_PARCHMENT);
                m.reliefAngled(cy - 1.9f, cz + 1.6f, 2.0f, 3.0f, 1.2f, t, -14f, MAT_PARCHMENT);
                m.reliefBox(cy - 3.2f, cz - 0.6f, cy - 1.0f, cz + 0.6f, 1.3f, t, MAT_GOLD);
                // Floating Arcane Soul Gem & Starburst above the book
                m.reliefAngled(cy + 1.6f, cz, 2.8f, 2.8f, 1.35f, t, 45f, MAT_ARCANE_BLUE);
                m.reliefAngled(cy + 1.6f, cz, 1.8f, 1.8f, 1.55f, t, 0f, MAT_PURPLE_GEM);
                m.reliefBox(cy + 1.2f, cz - 3.6f, cy + 2.0f, cz + 3.6f, 0.7f, t, MAT_GOLD);
            }
            case JEWELER -> {
                // 3D Golden Ring Band + Prong-Set Faceted Diamond Crown at top
                // Octagonal gold band
                m.reliefBox(cy - 3.8f, cz - 1.8f, cy - 2.8f, cz + 1.8f, 1.05f, t, MAT_GOLD);
                m.reliefBox(cy - 2.2f, cz - 3.2f, cy + 0.4f, cz - 2.2f, 1.05f, t, MAT_GOLD);
                m.reliefBox(cy - 2.2f, cz + 2.2f, cy + 0.4f, cz + 3.2f, 1.05f, t, MAT_GOLD);
                m.reliefLine(cy - 3.3f, cz - 1.6f, cy - 1.8f, cz - 2.7f, 0.95f, 1.05f, t, MAT_GOLD);
                m.reliefLine(cy - 3.3f, cz + 1.6f, cy - 1.8f, cz + 2.7f, 0.95f, 1.05f, t, MAT_GOLD);
                m.reliefLine(cy + 0.1f, cz - 2.7f, cy + 1.4f, cz - 1.3f, 0.95f, 1.05f, t, MAT_GOLD);
                m.reliefLine(cy + 0.1f, cz + 2.7f, cy + 1.4f, cz + 1.3f, 0.95f, 1.05f, t, MAT_GOLD);
                // Gold setting prongs & large faceted cyan diamond
                m.reliefBox(cy + 1.0f, cz - 2.2f, cy + 2.0f, cz + 2.2f, 1.15f, t, MAT_GOLD);
                m.reliefAngled(cy + 2.4f, cz, 2.7f, 2.7f, 1.45f, t, 45f, MAT_CYAN_GEM);
                m.reliefAngled(cy + 2.4f, cz, 1.5f, 1.5f, 1.65f, t, 0f, MAT_PEARL_WHITE);
            }
            case TAILOR -> {
                // 3D Crossed Open Shears + Spool of Violet Silk Thread
                // Open scissor blades
                m.reliefLine(cy - 1.2f, cz - 2.2f, cy + 3.6f, cz + 2.0f, 0.9f, 1.15f, t, MAT_STEEL_BRIGHT);
                m.reliefLine(cy - 1.2f, cz + 2.2f, cy + 3.6f, cz - 2.0f, 0.9f, 1.15f, t, MAT_STEEL_BRIGHT);
                m.reliefBox(cy + 0.4f, cz - 0.6f, cy + 1.4f, cz + 0.6f, 1.35f, t, MAT_GOLD);
                // Gold finger loops at bottom left & right
                m.reliefBox(cy - 3.5f, cz - 3.4f, cy - 1.2f, cz - 1.2f, 1.1f, t, MAT_GOLD);
                m.reliefBox(cy - 3.5f, cz + 1.2f, cy - 1.2f, cz + 3.4f, 1.1f, t, MAT_GOLD);
                // Silk spool in lower center
                m.reliefBox(cy - 3.8f, cz - 1.0f, cy - 1.4f, cz + 1.0f, 1.3f, t, MAT_PURPLE_GEM);
                m.reliefBox(cy - 3.9f, cz - 1.3f, cy - 3.3f, cz + 1.3f, 1.4f, t, MAT_OAK_RICH);
                m.reliefBox(cy - 1.9f, cz - 1.3f, cy - 1.3f, cz + 1.3f, 1.4f, t, MAT_OAK_RICH);
            }
            case BUTCHER -> {
                // 3D Heavy Meat Cleaver + Roasted Haunch on Bone
                // Broad rectangular cleaver blade (tilted) & walnut handle
                m.reliefAngled(cy + 1.1f, cz - 0.8f, 3.2f, 4.4f, 1.15f, t, -18f, MAT_STEEL_BRIGHT);
                m.reliefAngled(cy + 2.1f, cz - 2.3f, 0.8f, 0.8f, 1.35f, t, -18f, MAT_IRON_DARK);
                m.reliefLine(cy + 0.2f, cz + 1.2f, cy - 1.2f, cz + 3.8f, 1.1f, 1.25f, t, MAT_WALNUT);
                // Haunch of meat & protruding white bone at lower half
                m.reliefBox(cy - 3.6f, cz - 3.4f, cy - 1.2f, cz - 1.2f, 0.95f, t, MAT_BONEMEAL);
                m.reliefBox(cy - 3.5f, cz - 1.8f, cy - 0.4f, cz + 2.4f, 1.35f, t, MAT_CRIMSON);
                m.reliefBox(cy - 2.9f, cz - 1.2f, cy - 1.0f, cz + 1.8f, 1.5f, t, MAT_LEATHER_WARM);
            }
            case BAKERY -> {
                // 3D Scored Golden Bread Loaf + Crossed Golden Wheat Sheaves
                // Wheat stalks rising behind/above
                m.reliefLine(cy - 2.8f, cz - 2.8f, cy + 3.5f, cz + 2.4f, 0.8f, 0.75f, t, MAT_GOLD);
                m.reliefLine(cy - 2.8f, cz + 2.8f, cy + 3.5f, cz - 2.4f, 0.8f, 0.75f, t, MAT_GOLD);
                m.reliefAngled(cy + 2.6f, cz + 1.7f, 1.4f, 2.4f, 1.0f, t, -40f, MAT_AMBER_GLOW);
                m.reliefAngled(cy + 2.6f, cz - 1.7f, 1.4f, 2.4f, 1.0f, t, 40f, MAT_AMBER_GLOW);
                // Plump crusty bread loaf in foreground
                m.reliefBox(cy - 3.6f, cz - 3.4f, cy - 0.4f, cz + 3.4f, 1.25f, t, MAT_BREAD_CRUST);
                m.reliefBox(cy - 1.0f, cz - 2.8f, cy + 0.4f, cz + 2.8f, 1.15f, t, MAT_BREAD_CRUST);
                // Golden scored slashes on top of loaf
                m.reliefAngled(cy - 1.4f, cz - 1.6f, 1.8f, 0.6f, 1.45f, t, 25f, MAT_GOLD);
                m.reliefAngled(cy - 1.4f, cz, 1.8f, 0.6f, 1.45f, t, 25f, MAT_GOLD);
                m.reliefAngled(cy - 1.4f, cz + 1.6f, 1.8f, 0.6f, 1.45f, t, 25f, MAT_GOLD);
            }
            case FISHERY -> {
                // 3D Leaping Curved Silver Salmon + Iron Fishhook
                // Arched salmon body segments
                m.reliefAngled(cy + 1.2f, cz - 1.4f, 2.2f, 3.6f, 1.2f, t, 28f, MAT_TEAL_FISH);
                m.reliefAngled(cy - 0.6f, cz + 0.8f, 2.4f, 3.6f, 1.25f, t, -22f, MAT_STEEL_BRIGHT);
                // Head, gill & eye at upper -Z
                m.reliefAngled(cy + 2.2f, cz - 2.6f, 1.8f, 2.0f, 1.3f, t, 38f, MAT_COPPER);
                // Forked tail fin at lower +Z
                m.reliefAngled(cy - 2.4f, cz + 2.4f, 2.6f, 1.8f, 1.1f, t, -45f, MAT_TEAL_FISH);
                // Dorsal fin & iron hook
                m.reliefAngled(cy + 2.4f, cz + 0.4f, 1.4f, 2.0f, 1.0f, t, 15f, MAT_COPPER);
                m.reliefBox(cy - 3.6f, cz - 2.6f, cy + 0.2f, cz - 1.8f, 0.75f, t, MAT_IRON_LIGHT);
                m.reliefBox(cy - 3.6f, cz - 2.6f, cy - 2.6f, cz - 0.6f, 0.75f, t, MAT_IRON_LIGHT);
            }
            case STABLES -> {
                // 3D U-Shaped Forged Steel Horseshoe + Sculpted Golden Horse Head Crest inside
                // Left arm, right arm, and curved bottom arch of horseshoe
                m.reliefBox(cy - 1.8f, cz - 3.5f, cy + 3.2f, cz - 2.3f, 1.15f, t, MAT_STEEL_BRIGHT);
                m.reliefBox(cy - 1.8f, cz + 2.3f, cy + 3.2f, cz + 3.5f, 1.15f, t, MAT_STEEL_BRIGHT);
                m.reliefBox(cy - 3.7f, cz - 2.2f, cy - 2.4f, cz + 2.2f, 1.15f, t, MAT_STEEL_BRIGHT);
                m.reliefLine(cy - 1.8f, cz - 2.9f, cy - 3.1f, cz - 1.6f, 1.15f, 1.15f, t, MAT_STEEL_BRIGHT);
                m.reliefLine(cy - 1.8f, cz + 2.9f, cy - 3.1f, cz + 1.6f, 1.15f, 1.15f, t, MAT_STEEL_BRIGHT);
                // Calkin heels & gold nail studs
                m.reliefBox(cy + 2.6f, cz - 3.8f, cy + 3.6f, cz - 2.1f, 1.3f, t, MAT_GOLD);
                m.reliefBox(cy + 2.6f, cz + 2.1f, cy + 3.6f, cz + 3.8f, 1.3f, t, MAT_GOLD);
                // Horse neck & head profile in center
                m.reliefAngled(cy - 0.4f, cz - 0.3f, 3.2f, 1.8f, 1.3f, t, -18f, MAT_BIRCH_CREAM);
                m.reliefAngled(cy + 1.4f, cz + 0.5f, 1.6f, 2.6f, 1.4f, t, 22f, MAT_GOLD);
            }
            case TEMPLE -> {
                // 3D Radiant 8-Pointed Golden Sunburst + Sacred Chalice
                m.reliefAngled(cy + 0.4f, cz, 6.8f, 1.3f, 0.75f, t, 0f, MAT_GOLD);
                m.reliefAngled(cy + 0.4f, cz, 1.3f, 6.8f, 0.75f, t, 0f, MAT_GOLD);
                m.reliefAngled(cy + 0.4f, cz, 6.4f, 1.2f, 0.7f, t, 45f, MAT_AMBER_GLOW);
                m.reliefAngled(cy + 0.4f, cz, 1.2f, 6.4f, 0.7f, t, 45f, MAT_AMBER_GLOW);
                // Sacred chalice cup, stem, and foot in high relief
                m.reliefBox(cy - 3.6f, cz - 1.8f, cy - 2.7f, cz + 1.8f, 1.2f, t, MAT_GOLD);
                m.reliefBox(cy - 2.7f, cz - 0.6f, cy - 0.6f, cz + 0.6f, 1.15f, t, MAT_GOLD);
                m.reliefBox(cy - 0.6f, cz - 2.1f, cy + 1.8f, cz + 2.1f, 1.35f, t, MAT_GOLD);
                m.reliefBox(cy + 1.6f, cz - 1.2f, cy + 3.2f, cz + 1.2f, 1.45f, t, MAT_PEARL_WHITE);
            }
            case BARRACKS -> {
                // 3D Crossed Broadswords behind a Bossed Heraldic Heater Shield
                // Crossed swords extending past shield corners
                m.reliefLine(cy - 3.6f, cz - 3.6f, cy + 3.6f, cz + 3.6f, 0.85f, 0.65f, t, MAT_STEEL_BRIGHT);
                m.reliefLine(cy - 3.6f, cz + 3.6f, cy + 3.6f, cz - 3.6f, 0.85f, 0.65f, t, MAT_STEEL_BRIGHT);
                // Heater shield rim & crimson field
                m.reliefBox(cy - 2.2f, cz - 2.7f, cy + 2.6f, cz + 2.7f, 1.05f, t, MAT_IRON_LIGHT);
                m.reliefBox(cy - 3.5f, cz - 1.7f, cy - 2.0f, cz + 1.7f, 1.05f, t, MAT_IRON_LIGHT);
                m.reliefBox(cy - 1.8f, cz - 2.1f, cy + 2.1f, cz + 2.1f, 1.25f, t, MAT_CRIMSON);
                // Central raised golden shield boss & cross
                m.reliefBox(cy - 1.8f, cz - 0.5f, cy + 2.1f, cz + 0.5f, 1.35f, t, MAT_GOLD);
                m.reliefBox(cy - 0.3f, cz - 2.1f, cy + 0.7f, cz + 2.1f, 1.35f, t, MAT_GOLD);
                m.reliefBox(cy - 0.7f, cz - 0.9f, cy + 1.1f, cz + 0.9f, 1.6f, t, MAT_GOLD);
            }
            case KEEP -> {
                // 3D Sculpted 5-Pointed Royal Crown with Jewels over Crossed Scepter
                // Crossed royal scepter behind crown
                m.reliefLine(cy - 3.4f, cz - 3.2f, cy + 3.2f, cz + 3.2f, 0.75f, 0.6f, t, MAT_GOLD_DARK);
                // Crown velvet cushion & heavy gold circlet base
                m.reliefBox(cy - 1.6f, cz - 3.2f, cy + 1.0f, cz + 3.2f, 1.0f, t, MAT_CRIMSON);
                m.reliefBox(cy - 2.6f, cz - 3.5f, cy - 1.0f, cz + 3.5f, 1.35f, t, MAT_GOLD);
                // Five crown spires (center tallest, flanking medium, outer points)
                m.reliefBox(cy - 1.0f, cz - 3.5f, cy + 2.0f, cz - 2.3f, 1.25f, t, MAT_GOLD);
                m.reliefBox(cy - 1.0f, cz - 1.9f, cy + 2.6f, cz - 0.7f, 1.3f, t, MAT_GOLD);
                m.reliefBox(cy - 1.0f, cz - 0.6f, cy + 3.5f, cz + 0.6f, 1.45f, t, MAT_GOLD);
                m.reliefBox(cy - 1.0f, cz + 0.7f, cy + 2.6f, cz + 1.9f, 1.3f, t, MAT_GOLD);
                m.reliefBox(cy - 1.0f, cz + 2.3f, cy + 2.0f, cz + 3.5f, 1.25f, t, MAT_GOLD);
                // Inlaid crown gems
                m.reliefBox(cy - 2.2f, cz - 0.6f, cy - 1.2f, cz + 0.6f, 1.6f, t, MAT_CYAN_GEM);
                m.reliefBox(cy - 2.2f, cz - 2.6f, cy - 1.3f, cz - 1.6f, 1.55f, t, MAT_EMERALD);
                m.reliefBox(cy - 2.2f, cz + 1.6f, cy - 1.3f, cz + 2.6f, 1.55f, t, MAT_EMERALD);
            }
            case GUILD -> {
                // 3D Heraldic Guild Star Medallion over Cobalt Shield & Ribbon
                m.reliefBox(cy - 2.6f, cz - 2.6f, cy + 2.6f, cz + 2.6f, 0.95f, t, MAT_COBALT);
                m.reliefAngled(cy, cz, 5.2f, 5.2f, 0.9f, t, 45f, MAT_STEEL_BRIGHT);
                // 8-pointed golden compass star
                m.reliefAngled(cy, cz, 6.8f, 1.5f, 1.25f, t, 0f, MAT_GOLD);
                m.reliefAngled(cy, cz, 1.5f, 6.8f, 1.25f, t, 0f, MAT_GOLD);
                m.reliefAngled(cy, cz, 5.8f, 1.3f, 1.15f, t, 45f, MAT_GOLD);
                m.reliefAngled(cy, cz, 1.3f, 5.8f, 1.15f, t, 45f, MAT_GOLD);
                // Raised arcane cabochon center
                m.reliefBox(cy - 1.1f, cz - 1.1f, cy + 1.1f, cz + 1.1f, 1.55f, t, MAT_ARCANE_BLUE);
            }
        }
    }
}
