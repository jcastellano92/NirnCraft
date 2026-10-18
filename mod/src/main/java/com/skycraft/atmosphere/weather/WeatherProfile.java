package com.skycraft.atmosphere.weather;

import com.skycraft.tamriel.Region;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A climate: which {@link WeatherKind}s a region rolls and how often, how long each spell lasts, where rain turns
 * to snow, and the light ambient particles it always has. A region names its profile in {@link Region#weather()}
 * ({@code regions.json}); unknown names are matched by keyword so new regions always get something sensible.
 */
public final class WeatherProfile {
    /** Light particles the region shows even in clear weather (atmosphere/visual client). */
    public enum Ambient { NONE, SNOW, ASH, SAND, MARSH }

    private static final Map<String, WeatherProfile> BY_ID = new HashMap<>();

    public static final WeatherProfile SKYRIM_COLD = register("skyrim_cold", Ambient.SNOW, -10_000, true, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.SNOW, 3, WeatherKind.BLIZZARD, 1,
            WeatherKind.FOG, 1, WeatherKind.GALE, 1);
    public static final WeatherProfile SKYRIM_TEMPERATE = register("skyrim_temperate", Ambient.NONE, 250, true, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 5, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1);
    public static final WeatherProfile ASH = register("ash", Ambient.ASH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 2, WeatherKind.OVERCAST, 2, WeatherKind.ASHFALL, 3, WeatherKind.ASH_STORM, 1);
    public static final WeatherProfile VOLCANIC = ASH;
    public static final WeatherProfile DESERT = register("desert", Ambient.SAND, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 7, WeatherKind.CLOUDY, 2, WeatherKind.DUST, 2, WeatherKind.SANDSTORM, 1);
    public static final WeatherProfile ARID = DESERT;
    public static final WeatherProfile SWAMP = register("swamp", Ambient.MARSH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 3, WeatherKind.FOG, 3, WeatherKind.DRIZZLE, 3,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1);
    public static final WeatherProfile JUNGLE = register("jungle", Ambient.NONE, 10_000, false, 3600, 9600,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 4, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 2, WeatherKind.RAIN, 3,
            WeatherKind.STORM, 1);
    public static final WeatherProfile MARITIME = register("maritime", Ambient.NONE, 240, false, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1);
    public static final WeatherProfile FROZEN = register("frozen", Ambient.SNOW, -10_000, true, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.SNOW, 3, WeatherKind.BLIZZARD, 2, WeatherKind.FOG, 1,
            WeatherKind.GALE, 1);
    public static final WeatherProfile TEMPERATE = register("temperate", Ambient.NONE, 220, false, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 5, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1);
    public static final WeatherProfile ALPINE = register("alpine", Ambient.SNOW, 150, true, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.SNOW, 3,
            WeatherKind.BLIZZARD, 1, WeatherKind.RAIN, 1);
    public static final WeatherProfile SAVANNA = register("savanna", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 7, WeatherKind.CLOUDY, 4, WeatherKind.DUST, 1, WeatherKind.RAIN, 1, WeatherKind.STORM, 1);
    public static final WeatherProfile CRYSTAL = register("crystal", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 4, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 1);
    public static final WeatherProfile MUSHROOM = register("mushroom", Ambient.MARSH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.FOG, 3, WeatherKind.DRIZZLE, 2, WeatherKind.RAIN, 1);
    public static final WeatherProfile TROPICAL = register("tropical", Ambient.NONE, 10_000, false, 2400, 9600,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 4, WeatherKind.DRIZZLE, 1, WeatherKind.RAIN, 2, WeatherKind.STORM, 1).mist(0.15f);
    /** Other dimensions: no local weather at all. */
    public static final WeatherProfile NONE = register("none", Ambient.NONE, 10_000, false, 24000, 24000, WeatherKind.CLEAR, 1);

    // ---- tuned climates of particular regions (see REGIONS below; regions.json names the broad climate)
    /** Whiterun's open tundra: mostly fair or broken cumulus, the odd shower and summer storm, a breezy plain. */
    public static final WeatherProfile WHITERUN = register("whiterun", Ambient.NONE, 250, true, 4800, 16800,
            WeatherKind.CLEAR, 7, WeatherKind.CLOUDY, 6, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 1,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1, WeatherKind.GALE, 1).mist(0.15f);
    /** Falkreath's pine forests: misty and dappled with broken clouds, frequent gentle rain. */
    public static final WeatherProfile FALKREATH = register("falkreath", Ambient.NONE, 250, true, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 5, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1).mist(0.35f);
    /** The Rift's golden autumn: bright days, lake mist in the mornings. */
    public static final WeatherProfile RIFT = register("the_rift", Ambient.NONE, 250, true, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 5, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1).mist(0.35f);
    /** The Reach's crags: low cloud and mountain drizzle. */
    public static final WeatherProfile REACH = register("the_reach", Ambient.NONE, 240, true, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 3,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1).mist(0.28f);
    /** Haafingar's cliffs over the Sea of Ghosts: sea fog, rain turning to snow, gales off the water. */
    public static final WeatherProfile HAAFINGAR = register("haafingar", Ambient.NONE, 140, true, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.SNOW, 2, WeatherKind.STORM, 1, WeatherKind.GALE, 1).mist(0.30f);
    /** Hjaalmarch's marshes: fog most days, sleet and snow. */
    public static final WeatherProfile HJAALMARCH = register("hjaalmarch", Ambient.MARSH, 120, true, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 3, WeatherKind.FOG, 4, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 1, WeatherKind.SNOW, 2).mist(0.55f);
    /** The Pale: snow and blizzards off the northern sea. */
    public static final WeatherProfile PALE = register("the_pale", Ambient.SNOW, -10_000, true, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 2, WeatherKind.SNOW, 4, WeatherKind.BLIZZARD, 2,
            WeatherKind.FOG, 1, WeatherKind.GALE, 1).mist(0.1f);
    /** Winterhold: the harshest blizzards in Skyrim, clear frozen nights under the aurora. */
    public static final WeatherProfile WINTERHOLD = register("winterhold", Ambient.SNOW, -10_000, true, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.SNOW, 4, WeatherKind.BLIZZARD, 3, WeatherKind.FOG, 1,
            WeatherKind.GALE, 2).mist(0.1f);
    /** Eastmarch: snowy tundra over volcanic hot springs (steam fog), a dusting of ash blown from Morrowind. */
    public static final WeatherProfile EASTMARCH = register("eastmarch", Ambient.NONE, 150, true, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 3, WeatherKind.SNOW, 2,
            WeatherKind.BLIZZARD, 1, WeatherKind.ASHFALL, 1).mist(0.45f);
    /** Vvardenfell's ashlands: ashfall, ash storms and now and then the blight. */
    public static final WeatherProfile VVARDENFELL = register("vvardenfell", Ambient.ASH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 2, WeatherKind.OVERCAST, 1, WeatherKind.ASHFALL, 5, WeatherKind.ASH_STORM, 3,
            WeatherKind.BLIGHT, 1);
    /** Red Mountain itself: a permanent storm of ash and blight. */
    public static final WeatherProfile RED_MOUNTAIN = register("red_mountain", Ambient.ASH, 10_000, false, 2400, 9600,
            WeatherKind.CLOUDY, 1, WeatherKind.OVERCAST, 1, WeatherKind.ASHFALL, 4, WeatherKind.ASH_STORM, 5, WeatherKind.BLIGHT, 3);
    /** The West Gash and Stonefalls: rocky uplands, mostly fair, ash on the wind from the mountain. */
    public static final WeatherProfile ASHLANDS_EDGE = register("ashlands_edge", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 1, WeatherKind.RAIN, 2,
            WeatherKind.ASHFALL, 2, WeatherKind.ASH_STORM, 1);
    /** The Ascadian Isles, Deshaan and the Telvanni coast: lush, warm rain. */
    public static final WeatherProfile MORROWIND_LUSH = register("morrowind_lush", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 5, WeatherKind.CLOUDY, 4, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 2, WeatherKind.RAIN, 3,
            WeatherKind.STORM, 1).mist(0.25f);
    /** The Grazelands: open, dry and windy. */
    public static final WeatherProfile GRAZELANDS = register("grazelands", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 3, WeatherKind.GALE, 2, WeatherKind.RAIN, 1, WeatherKind.ASHFALL, 1);
    /** Solstheim: ash from the south, snow from the north. */
    public static final WeatherProfile SOLSTHEIM = register("solstheim", Ambient.ASH, 140, true, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 2, WeatherKind.FOG, 1, WeatherKind.ASHFALL, 3, WeatherKind.ASH_STORM, 2,
            WeatherKind.SNOW, 3, WeatherKind.BLIZZARD, 1);
    /** Black Marsh: humid, foggy and pouring. */
    public static final WeatherProfile BLACK_MARSH = register("black_marsh", Ambient.MARSH, 10_000, false, 3000, 9600,
            WeatherKind.CLEAR, 1, WeatherKind.CLOUDY, 2, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 4, WeatherKind.DRIZZLE, 3,
            WeatherKind.RAIN, 4, WeatherKind.STORM, 2).mist(0.7f);
    /** The Alik'r: blinding sun and great sandstorms. */
    public static final WeatherProfile ALIKR = register("alikr", Ambient.SAND, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 1, WeatherKind.DUST, 3, WeatherKind.SANDSTORM, 3, WeatherKind.GALE, 1);
    /** Valenwood's rainforest: rain most days, mist under the canopy. */
    public static final WeatherProfile VALENWOOD = register("valenwood", Ambient.NONE, 10_000, false, 3000, 9600,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 3, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2, WeatherKind.RAIN, 4,
            WeatherKind.STORM, 2).mist(0.5f);
    /** Summerset: bright and fair. */
    public static final WeatherProfile SUMMERSET = register("summerset", Ambient.NONE, 10_000, false, 6000, 18000,
            WeatherKind.CLEAR, 9, WeatherKind.CLOUDY, 3, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 1, WeatherKind.RAIN, 1).mist(0.2f);
    /** Artaeum: the mystic isle, often veiled in mist. */
    public static final WeatherProfile ARTAEUM = register("artaeum", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 5, WeatherKind.CLOUDY, 2, WeatherKind.FOG, 4, WeatherKind.DRIZZLE, 1).mist(0.6f);
    /** High Rock: Atlantic rain and mist. */
    public static final WeatherProfile HIGH_ROCK = register("high_rock", Ambient.NONE, 200, false, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 3, WeatherKind.FOG, 3, WeatherKind.DRIZZLE, 3,
            WeatherKind.RAIN, 3, WeatherKind.STORM, 1).mist(0.5f);
    /** Rivenspire: High Rock's gloomiest highlands. */
    public static final WeatherProfile RIVENSPIRE = register("rivenspire", Ambient.NONE, 180, false, 3600, 12000,
            WeatherKind.CLEAR, 1, WeatherKind.CLOUDY, 2, WeatherKind.OVERCAST, 4, WeatherKind.FOG, 4, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 3, WeatherKind.STORM, 1).mist(0.6f);
    /** Atmora: frozen waste under howling gales. */
    public static final WeatherProfile ATMORA = register("atmora", Ambient.SNOW, -10_000, true, 2400, 9600,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 1, WeatherKind.FOG, 1, WeatherKind.SNOW, 3, WeatherKind.BLIZZARD, 5,
            WeatherKind.GALE, 4);
    /** The Sea of Ghosts: sea fog, snow squalls and gales among the icebergs. */
    public static final WeatherProfile SEA_OF_GHOSTS = register("sea_of_ghosts", Ambient.SNOW, -10_000, true, 3600, 12000,
            WeatherKind.CLEAR, 1, WeatherKind.CLOUDY, 2, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 4, WeatherKind.SNOW, 3,
            WeatherKind.BLIZZARD, 2, WeatherKind.GALE, 2).mist(0.5f);
    /** Cyrodiil's Gold Coast: warm Mediterranean Abecean sun, golden cumulus, and dramatic coastal sea-squalls. */
    public static final WeatherProfile GOLD_COAST = register("gold_coast", Ambient.NONE, 10_000, false, 4800, 15600,
            WeatherKind.CLEAR, 7, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 1, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 1,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 2).mist(0.2f);
    /** Cyrodiil's Heartlands and Lake Rumare: pastoral fair skies, morning lake mist, and warm summer thunderstorms. */
    public static final WeatherProfile HEARTLANDS = register("heartlands", Ambient.NONE, 260, false, 4800, 15600,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 2).mist(0.35f);
    /** Colovian Highlands and West Weald: breezy upland cumulus, autumn drizzle, and rolling ridge thunder. */
    public static final WeatherProfile COLOVIA = register("colovia", Ambient.NONE, 210, false, 4200, 14400,
            WeatherKind.CLEAR, 5, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 3, WeatherKind.STORM, 2, WeatherKind.GALE, 1).mist(0.3f);
    /** Nibenay Valley and Blackwood: humid river fogs, Topal monsoon showers, and frequent electrical storms. */
    public static final WeatherProfile BLACKWOOD = register("blackwood", Ambient.MARSH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 3, WeatherKind.FOG, 4, WeatherKind.DRIZZLE, 3,
            WeatherKind.RAIN, 4, WeatherKind.STORM, 2).mist(0.65f);
    /** Jerall and Valus Mountains: Bruma's snowy alpine passes, frost fogs, blizzards, and ridge gales. */
    public static final WeatherProfile JERALL = register("jerall", Ambient.SNOW, 130, true, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.SNOW, 4,
            WeatherKind.BLIZZARD, 2, WeatherKind.GALE, 2).mist(0.25f);
    /** Daggerfall's Iliac Bay kingdoms: moody Atlantic sea fogs, rolling overcast, and fierce coastal thunderstorms. */
    public static final WeatherProfile DAGGERFALL_COAST = register("daggerfall_coast", Ambient.NONE, 210, false, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 3, WeatherKind.FOG, 3, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 3, WeatherKind.STORM, 2, WeatherKind.GALE, 1).mist(0.5f);
    /** Wrothgar's mountains: cold highland mists, snow squalls, and windward storms over Orsinium. */
    public static final WeatherProfile WROTHGAR = register("wrothgar", Ambient.SNOW, 155, true, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.RAIN, 2,
            WeatherKind.SNOW, 3, WeatherKind.BLIZZARD, 2, WeatherKind.STORM, 1, WeatherKind.GALE, 2).mist(0.35f);
    /** Sentinel, Stros M'Kai and Hew's Bane: bright trade-wind sun, heat haze, and sudden maritime squalls. */
    public static final WeatherProfile SENTINEL_COAST = register("sentinel_coast", Ambient.NONE, 10_000, false, 4800, 15600,
            WeatherKind.CLEAR, 7, WeatherKind.CLOUDY, 3, WeatherKind.DUST, 1, WeatherKind.RAIN, 2, WeatherKind.STORM, 2,
            WeatherKind.GALE, 1).mist(0.2f);
    /** Dragontail, Craglorn and Bangkorai: dry canyon winds, red dust, and electric high-base mountain storms. */
    public static final WeatherProfile CRAGLORN = register("craglorn", Ambient.SAND, 240, false, 4200, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 3, WeatherKind.DUST, 2, WeatherKind.SANDSTORM, 1, WeatherKind.RAIN, 1,
            WeatherKind.STORM, 2, WeatherKind.GALE, 1).mist(0.1f);
    /** Northern Elsweyr (Anequina): sun-baked badlands, amber dust veils, haboobs, and dry heat-lightning storms. */
    public static final WeatherProfile ANEQUINA = register("anequina", Ambient.SAND, 10_000, false, 4800, 15600,
            WeatherKind.CLEAR, 7, WeatherKind.CLOUDY, 2, WeatherKind.DUST, 3, WeatherKind.SANDSTORM, 2, WeatherKind.STORM, 1).mist(0.05f);
    /** Southern Elsweyr (Pellitine): humid Topal monsoon jungle, warm mist, and tropical electrical storms. */
    public static final WeatherProfile PELLITINE = register("pellitine", Ambient.NONE, 10_000, false, 3600, 10800,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 3, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2, WeatherKind.RAIN, 4,
            WeatherKind.STORM, 2).mist(0.5f);

    /** Region id -> tuned climate, checked before the region's broad {@code weather} name. */
    private static final Map<String, WeatherProfile> REGIONS = new HashMap<>();

    static {
        SKYRIM_TEMPERATE.mist(0.25f);
        SKYRIM_COLD.mist(0.15f);
        SWAMP.mist(0.6f);
        JUNGLE.mist(0.4f);
        MARITIME.mist(0.35f);
        TEMPERATE.mist(0.2f);
        ALPINE.mist(0.2f);
        FROZEN.mist(0.1f);
        CRYSTAL.mist(0.3f);
        MUSHROOM.mist(0.5f);
        SAVANNA.mist(0.05f);
        regions(WHITERUN, "skyrim/whiterun");
        regions(FALKREATH, "skyrim/falkreath");
        regions(RIFT, "skyrim/the_rift");
        regions(REACH, "skyrim/the_reach");
        regions(HAAFINGAR, "skyrim/haafingar");
        regions(HJAALMARCH, "skyrim/hjaalmarch");
        regions(PALE, "skyrim/the_pale");
        regions(WINTERHOLD, "skyrim/winterhold");
        regions(EASTMARCH, "skyrim/eastmarch");
        regions(VVARDENFELL, "morrowind/vvardenfell_ashlands", "morrowind/molag_amur");
        regions(RED_MOUNTAIN, "morrowind/red_mountain");
        regions(ASHLANDS_EDGE, "morrowind/west_gash", "morrowind/stonefalls", "sea/inner_sea");
        regions(MORROWIND_LUSH, "morrowind/ascadian_isles", "morrowind/deshaan", "morrowind/telvannis");
        regions(GRAZELANDS, "morrowind/grazelands");
        regions(SOLSTHEIM, "morrowind/solstheim");
        regions(BLACK_MARSH, "black_marsh/shadowfen", "black_marsh/stormhold", "black_marsh/helstrom", "black_marsh/gideon",
                "black_marsh/lilmoth", "black_marsh/murkmire");
        regions(ALIKR, "hammerfell/alikr_desert");
        regions(SENTINEL_COAST, "hammerfell/sentinel", "hammerfell/stros_mkai", "hammerfell/hews_bane");
        regions(CRAGLORN, "hammerfell/dragontail", "hammerfell/craglorn", "hammerfell/bangkorai");
        regions(VALENWOOD, "valenwood/grahtwood", "valenwood/greenshade", "valenwood/malabal_tor", "valenwood/reapers_march");
        regions(SUMMERSET, "summerset/summerset_isle", "summerset/auridon");
        regions(ARTAEUM, "summerset/artaeum");
        regions(DAGGERFALL_COAST, "high_rock/daggerfall", "high_rock/anticlere", "high_rock/dwynnen");
        regions(HIGH_ROCK, "high_rock/glenumbra", "high_rock/stormhaven", "high_rock/bjoulsae", "high_rock/wayrest");
        regions(RIVENSPIRE, "high_rock/rivenspire");
        regions(WROTHGAR, "high_rock/wrothgar");
        regions(GOLD_COAST, "cyrodiil/gold_coast");
        regions(HEARTLANDS, "cyrodiil/heartlands", "cyrodiil/nibenay_basin");
        regions(COLOVIA, "cyrodiil/colovian_highlands", "cyrodiil/west_weald");
        regions(BLACKWOOD, "cyrodiil/nibenay_valley", "cyrodiil/blackwood");
        regions(JERALL, "cyrodiil/jerall_mountains", "cyrodiil/valus_mountains");
        regions(ANEQUINA, "elsweyr/anequina", "elsweyr/northern_elsweyr");
        regions(PELLITINE, "elsweyr/pellitine", "elsweyr/southern_elsweyr", "elsweyr/quin_rawl");
        regions(ATMORA, "atmora/ice_waste", "atmora/frozen_forest");
        regions(SEA_OF_GHOSTS, "sea/sea_of_ghosts");
    }

    private static void regions(WeatherProfile profile, String... ids) {
        for (String id : ids) REGIONS.put(id, profile);
    }

    public final String id;
    public final Ambient ambient;
    /** Rain falls as snow above this Y. */
    public final int snowLine;
    /** Cold northern sky: aurora on clear nights. */
    public final boolean aurora;
    public final int minTicks;
    public final int maxTicks;
    /**
     * 0..1: how much mist the climate breeds around dawn and through the night (over marshes, lakes and rivers):
     * thin morning fog where the player is, fog banks in the distance, and FOG spells rolled around dawn.
     */
    public float dawnMist;
    private final WeatherKind[] kinds;
    private final int[] weights;
    private final int totalWeight;

    private WeatherProfile(String id, Ambient ambient, int snowLine, boolean aurora, int minTicks, int maxTicks, Object[] table) {
        this.id = id;
        this.ambient = ambient;
        this.snowLine = snowLine;
        this.aurora = aurora;
        this.minTicks = minTicks;
        this.maxTicks = maxTicks;
        this.kinds = new WeatherKind[table.length / 2];
        this.weights = new int[table.length / 2];
        int total = 0;
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = (WeatherKind) table[i * 2];
            weights[i] = (Integer) table[i * 2 + 1];
            total += weights[i];
        }
        this.totalWeight = total;
    }

    private static WeatherProfile register(String id, Ambient ambient, int snowLine, boolean aurora, int minTicks, int maxTicks, Object... table) {
        WeatherProfile p = new WeatherProfile(id, ambient, snowLine, aurora, minTicks, maxTicks, table);
        BY_ID.put(id, p);
        return p;
    }

    private WeatherProfile mist(float amount) {
        this.dawnMist = amount;
        return this;
    }

    /**
     * 0..1: the time of day's share of {@link #dawnMist}: full from first light to an hour after sunrise, a third of
     * it through the night, none from mid-morning to dusk.
     */
    public static float mistHour(long dayTime) {
        float t = Math.floorMod(dayTime, 24000L);
        if (t >= 22500f || t < 1000f) return 1f;
        if (t < 3500f) return 1f - (t - 1000f) / 2500f;
        if (t < 12500f) return 0f;
        if (t < 14000f) return (t - 12500f) / 1500f * 0.35f;
        if (t < 21000f) return 0.35f;
        return 0.35f + (t - 21000f) / 1500f * 0.65f;
    }

    /** True if this profile can ever roll {@code kind}. */
    public boolean allows(WeatherKind kind) {
        for (WeatherKind k : kinds) if (k == kind) return true;
        return false;
    }

    /** A weighted random kind, avoiding an immediate repeat of {@code previous} (one re-roll). */
    public WeatherKind roll(RandomSource random, WeatherKind previous) {
        return roll(random, previous, 0f);
    }

    /**
     * As {@link #roll(RandomSource, WeatherKind)}; {@code mistHour} ({@link #mistHour}) makes FOG likelier around dawn
     * in misty climates.
     */
    public WeatherKind roll(RandomSource random, WeatherKind previous, float mistHour) {
        WeatherKind k = pick(random);
        if (k == previous && kinds.length > 1) k = pick(random);
        if (k != WeatherKind.FOG && allows(WeatherKind.FOG) && random.nextFloat() < dawnMist * mistHour * 0.45f) k = WeatherKind.FOG;
        // Storms rarely follow clear skies directly: the sky clouds over first.
        if (previous == WeatherKind.CLEAR && (k == WeatherKind.STORM || k == WeatherKind.BLIZZARD
                || k == WeatherKind.ASH_STORM || k == WeatherKind.SANDSTORM || k == WeatherKind.BLIGHT) && random.nextInt(3) > 0) {
            k = allows(WeatherKind.CLOUDY) ? WeatherKind.CLOUDY : k;
        }
        return k;
    }

    private WeatherKind pick(RandomSource random) {
        int r = random.nextInt(Math.max(1, totalWeight));
        for (int i = 0; i < kinds.length; i++) {
            r -= weights[i];
            if (r < 0) return kinds[i];
        }
        return kinds[0];
    }

    /** The wettest kind this profile has (what {@code /weather rain} maps to). */
    public WeatherKind wetKind(boolean thunder) {
        // thunder: the strongest precipitation; plain rain: the one closest to a steady shower
        float target = thunder ? 1.0f : 0.7f;
        WeatherKind best = WeatherKind.CLEAR;
        float bestScore = Float.MAX_VALUE;
        for (WeatherKind k : kinds) {
            if (k.precip == WeatherKind.Precip.NONE) continue;
            float score = Math.abs(k.amount - target) - (thunder ? k.thunder : 0f);
            if (score < bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return best;
    }

    public int duration(RandomSource random) {
        return minTicks + random.nextInt(Math.max(1, maxTicks - minTicks + 1));
    }

    /** A spell length for {@code kind}: storms, gales and fog pass sooner than fair weather. */
    public int duration(RandomSource random, WeatherKind kind) {
        return Math.max(600, (int) (duration(random) * (kind == null ? 1f : kind.spellLength)));
    }

    // ------------------------------------------------------------------ lookup

    public static WeatherProfile of(Region region) {
        if (region == null || "dimension".equals(region.kind())) return NONE;
        WeatherProfile tuned = REGIONS.get(region.id());
        return tuned != null ? tuned : byName(region.weather());
    }

    /** Exact id first, then keyword matching (so {@code "skyrim_snow"} or {@code "volcanic"} still work). */
    public static WeatherProfile byName(String name) {
        if (name == null || name.isEmpty()) return TEMPERATE;
        String n = name.toLowerCase(Locale.ROOT);
        WeatherProfile exact = BY_ID.get(n);
        if (exact != null) return exact;
        if (has(n, "frozen", "ice", "arctic", "glacier", "atmora")) return FROZEN;
        if (has(n, "cold", "snow", "tundra", "winter", "pale")) return SKYRIM_COLD;
        if (has(n, "ash", "volcan", "lava", "red_mountain")) return ASH;
        if (has(n, "desert", "arid", "sand", "dune", "badland")) return DESERT;
        if (has(n, "swamp", "marsh", "bog", "mire", "fen")) return SWAMP;
        if (has(n, "jungle", "rainforest", "tropic", "monsoon")) return JUNGLE;
        if (has(n, "mushroom", "fungal")) return MUSHROOM;
        if (has(n, "sea", "coast", "maritime", "ocean", "island", "isle")) return MARITIME;
        if (has(n, "alpine", "mountain", "peak", "highland")) return ALPINE;
        if (has(n, "savanna", "steppe", "dry", "plain")) return SAVANNA;
        if (has(n, "crystal", "mystic", "fair", "summer")) return CRYSTAL;
        if (has(n, "none", "dimension", "nether", "end", "oblivion", "sovngarde")) return NONE;
        if (has(n, "skyrim")) return SKYRIM_TEMPERATE;
        return TEMPERATE;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    // ------------------------------------------------------------------ continuous latitude helpers

    public static float latitudeFactor(double blockZ) {
        return com.skycraft.atmosphere.AtmosphereMath.latitudeFactor(blockZ);
    }

    public static float auroraElevationMinDeg(double blockZ) {
        return com.skycraft.atmosphere.AtmosphereMath.auroraElevationMinDeg(blockZ);
    }

    public static float auroraElevationMaxDeg(double blockZ) {
        return com.skycraft.atmosphere.AtmosphereMath.auroraElevationMaxDeg(blockZ);
    }

    public static float auroraAlphaFactor(double blockZ) {
        return com.skycraft.atmosphere.AtmosphereMath.auroraAlphaFactor(blockZ);
    }
}
