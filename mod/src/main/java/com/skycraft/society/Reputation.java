package com.skycraft.society;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-player standing with Tamriel's factions (cross-module contract 23): {@code data.module("society")
 * .getCompound("reputation")} maps faction id -> int in -100..100 (absent = 0). Synced to the owning client with the
 * rest of the player data, so the reputation screen reads it directly.
 *
 * <p>Backed by {@code /skycraft/society/factions.json}, which maps every province, region, and major settlement
 * (including Orc strongholds) to its ruling faction, secondary factions, allied factions, rival factions,
 * dominant faith, and {@code WorldSheets} L6–L9 causal {@code why} citations.</p>
 *
 * <p>Changes come from {@link ReputationEvents} (kills, assaults, crime counters, faction ranks and quest points).
 * At or below {@link SocietyConfig#HOSTILE_THRESHOLD} the faction's NPCs attack on sight and {@link HitSquads} may
 * come for the player.</p>
 */
public final class Reputation {
    private static final Logger LOGGER = LoggerFactory.getLogger(Reputation.class);
    private static final String FACTIONS_RESOURCE = "/skycraft/society/factions.json";

    public static final String MODULE = "society";
    public static final String KEY = "reputation";

    // Core & Skyrim factions
    public static final String TOWNSFOLK = "townsfolk";
    public static final String GUARDS = "guards";
    public static final String THALMOR = "thalmor";
    public static final String ALDMERI_DOMINION = "aldmeri_dominion";
    public static final String IMPERIAL_LEGION = "imperial_legion";
    public static final String IMPERIALS = "imperials";
    public static final String STORMCLOAKS = "stormcloaks";
    public static final String FORSWORN = "forsworn";
    public static final String BANDITS = "bandits";
    public static final String VAMPIRES = "vampires";
    public static final String VOLKIHAR = "volkihar";
    public static final String DARK_BROTHERHOOD = "dark_brotherhood";
    public static final String THIEVES_GUILD = "thieves_guild";
    public static final String COMPANIONS = "companions";
    public static final String COLLEGE = "college";
    public static final String COLLEGE_OF_WINTERHOLD = "college_of_winterhold";
    public static final String BARDS_COLLEGE = "bards_college";
    public static final String BLADES = "blades";
    public static final String GREYBEARDS = "greybeards";
    public static final String DAWNGUARD = "dawnguard";
    public static final String VIGILANTS_OF_STENDARR = "vigilants_of_stendarr";
    public static final String ORC_BLOOD_KIN = "orc_blood_kin";
    public static final String ORC_STRONGHOLDS = "orc_strongholds";

    // Morrowind factions
    public static final String HOUSE_REDORAN = "house_redoran";
    public static final String HOUSE_TELVANNI = "house_telvanni";
    public static final String HOUSE_HLAALU = "house_hlaalu";
    public static final String HOUSE_INDORIL = "house_indoril";
    public static final String HOUSE_DRES = "house_dres";
    public static final String TRIBUNAL_TEMPLE = "tribunal_temple";
    public static final String ASHLANDERS = "ashlanders";
    public static final String MORAG_TONG = "morag_tong";
    public static final String CAMONNA_TONG = "camonna_tong";
    public static final String SIXTH_HOUSE = "sixth_house";

    // Cyrodiil factions
    public static final String FIGHTERS_GUILD = "fighters_guild";
    public static final String MAGES_GUILD = "mages_guild";
    public static final String SYNOD = "synod";
    public static final String COLLEGE_OF_WHISPERS = "college_of_whispers";
    public static final String ELDER_COUNCIL = "elder_council";
    public static final String COLOVIAN_ESTATES = "colovian_estates";
    public static final String EAST_EMPIRE_COMPANY = "east_empire_company";
    public static final String ORDER_OF_THE_VIRTUOUS_BLOOD = "order_of_the_virtuous_blood";
    public static final String BLACKWOOD_COMPANY = "blackwood_company";
    public static final String MYTHIC_DAWN = "mythic_dawn";

    // High Rock factions
    public static final String DAGGERFALL_COVENANT = "daggerfall_covenant";
    public static final String KNIGHTS_OF_THE_DRAGON = "knights_of_the_dragon";
    public static final String ORDER_OF_THE_CANDLE = "order_of_the_candle";
    public static final String ORDER_OF_THE_LILY = "order_of_the_lily";
    public static final String GLENMORIL_WYRD = "glenmoril_wyrd";
    public static final String CLAN_TUMNOSH = "clan_tumnosh";
    public static final String CLAN_SHATUL = "clan_shatul";
    public static final String CLAN_BAGRAKH = "clan_bagrakh";
    public static final String DIRENNI_HEGEMONY = "direnni_hegemony";

    // Hammerfell factions
    public static final String CROWNS = "crowns";
    public static final String FOREBEARS = "forebears";
    public static final String LHOTUNICS = "lhotunics";
    public static final String ORDER_OF_DIAGNA = "order_of_diagna";
    public static final String ASH_ABAH = "ash_abah";
    public static final String CORSAIRS_OF_ABAHS_LANDING = "corsairs_of_abahs_landing";

    // Summerset factions
    public static final String PSIJIC_ORDER = "psijic_order";
    public static final String SAPIARCHS = "sapiarchs";
    public static final String DIVINE_PROSECUTION = "divine_prosecution";
    public static final String VEILED_HERITANCE = "veiled_heritance";

    // Valenwood factions
    public static final String CAMORAN_DYNASTY = "camoran_dynasty";
    public static final String GREEN_PACT = "green_pact";
    public static final String GREEN_PACT_SPINNERS = "green_pact_spinners";
    public static final String TREETHANES = "treethanes";
    public static final String WILDERKING_COURT = "wilderking_court";

    // Elsweyr factions
    public static final String MANE_CLERGY = "mane_clergy";
    public static final String TWO_MOONS_ADEPTS = "two_moons_adepts";
    public static final String BAANDARI_PEDLARS = "baandari_pedlars";
    public static final String RENRIJRA_KRIN = "renrijra_krin";
    public static final String ANEQUINA_MILITIA = "anequina_militia";
    public static final String PELLITINE_MERCHANT_LORDS = "pellitine_merchant_lords";

    // Black Marsh factions
    public static final String AN_XILEEL = "an_xileel";
    public static final String HIST_SPEAKERS = "hist_speakers";
    public static final String SHADOWSCALES = "shadowscales";
    public static final String ARCHEIN_BROKERS = "archein_brokers";
    public static final String BRIGHT_THROAT_TRIBE = "bright_throat_tribe";
    public static final String DEAD_WATER_TRIBE = "dead_water_tribe";

    // Foreign / Outer Realms factions
    public static final String ATMORAN_REMNANT = "atmoran_remnant";
    public static final String TSAESCI_IMMORTAL_GUARD = "tsaesci_immortal_guard";
    public static final String KAMAL_HOST = "kamal_host";
    public static final String TANG_MO_MONKS = "tang_mo_monks";
    public static final String KA_PO_TUN_ORDER = "ka_po_tun_order";
    public static final String YOKUDAN_ANKH_WARDENS = "yokudan_ankh_wardens";
    public static final String MAORMER_ARMADA = "maormer_armada";
    public static final String SLOAD_CABAL = "sload_cabal";

    public static final String[] FACTIONS = {
            TOWNSFOLK, GUARDS, THALMOR, ALDMERI_DOMINION, IMPERIAL_LEGION, IMPERIALS, STORMCLOAKS, FORSWORN, BANDITS, VAMPIRES,
            VOLKIHAR, DARK_BROTHERHOOD, THIEVES_GUILD, COMPANIONS, COLLEGE, COLLEGE_OF_WINTERHOLD,
            BARDS_COLLEGE, BLADES, GREYBEARDS, DAWNGUARD, VIGILANTS_OF_STENDARR, ORC_BLOOD_KIN, ORC_STRONGHOLDS,
            HOUSE_REDORAN, HOUSE_TELVANNI, HOUSE_HLAALU, HOUSE_INDORIL, HOUSE_DRES, TRIBUNAL_TEMPLE,
            ASHLANDERS, MORAG_TONG, CAMONNA_TONG, SIXTH_HOUSE,
            FIGHTERS_GUILD, MAGES_GUILD, SYNOD, COLLEGE_OF_WHISPERS, ELDER_COUNCIL, COLOVIAN_ESTATES, EAST_EMPIRE_COMPANY,
            ORDER_OF_THE_VIRTUOUS_BLOOD, BLACKWOOD_COMPANY, MYTHIC_DAWN,
            DAGGERFALL_COVENANT, KNIGHTS_OF_THE_DRAGON, ORDER_OF_THE_CANDLE, ORDER_OF_THE_LILY, GLENMORIL_WYRD,
            CLAN_TUMNOSH, CLAN_SHATUL, CLAN_BAGRAKH, DIRENNI_HEGEMONY,
            CROWNS, FOREBEARS, LHOTUNICS, ORDER_OF_DIAGNA, ASH_ABAH, CORSAIRS_OF_ABAHS_LANDING,
            PSIJIC_ORDER, SAPIARCHS, DIVINE_PROSECUTION, VEILED_HERITANCE,
            CAMORAN_DYNASTY, GREEN_PACT, GREEN_PACT_SPINNERS, TREETHANES, WILDERKING_COURT,
            MANE_CLERGY, TWO_MOONS_ADEPTS, BAANDARI_PEDLARS, RENRIJRA_KRIN, ANEQUINA_MILITIA, PELLITINE_MERCHANT_LORDS,
            AN_XILEEL, HIST_SPEAKERS, SHADOWSCALES, ARCHEIN_BROKERS, BRIGHT_THROAT_TRIBE, DEAD_WATER_TRIBE,
            ATMORAN_REMNANT, TSAESCI_IMMORTAL_GUARD, KAMAL_HOST, TANG_MO_MONKS, KA_PO_TUN_ORDER,
            YOKUDAN_ANKH_WARDENS, MAORMER_ARMADA, SLOAD_CABAL
    };

    private static final Map<String, String> ALIAS_PAIRS = Map.ofEntries(
            Map.entry(COLLEGE, COLLEGE_OF_WINTERHOLD),
            Map.entry(COLLEGE_OF_WINTERHOLD, COLLEGE),
            Map.entry(IMPERIAL_LEGION, IMPERIALS),
            Map.entry(IMPERIALS, IMPERIAL_LEGION),
            Map.entry(GREEN_PACT, GREEN_PACT_SPINNERS),
            Map.entry(GREEN_PACT_SPINNERS, GREEN_PACT),
            Map.entry(VAMPIRES, VOLKIHAR),
            Map.entry(VOLKIHAR, VAMPIRES),
            Map.entry(ORC_BLOOD_KIN, ORC_STRONGHOLDS),
            Map.entry(ORC_STRONGHOLDS, ORC_BLOOD_KIN),
            Map.entry(THALMOR, ALDMERI_DOMINION),
            Map.entry(ALDMERI_DOMINION, THALMOR)
    );

    public static final int MIN = -100;
    public static final int MAX = 100;

    /** Definition of a faction loaded from {@code /skycraft/society/factions.json}. */
    public record FactionDef(
            String id,
            String province,
            String category,
            String dominantFaith,
            List<String> allies,
            List<String> rivals,
            List<String> why
    ) {}

    /** Ruling polity, faction alignments, and faith for a region or settlement. */
    public record PolityMapping(
            String id,
            String region,
            String rulingFaction,
            List<String> secondaryFactions,
            List<String> alliedFactions,
            List<String> rivalFactions,
            String dominantFaith,
            boolean orcStronghold,
            List<String> why
    ) {}

    private record Snapshot(
            Map<String, FactionDef> factions,
            Map<String, PolityMapping> regions,
            Map<String, PolityMapping> shortRegions,
            Map<String, PolityMapping> settlements
    ) {}

    private static volatile Snapshot SNAPSHOT;

    /** Standing tiers, lowest first: the lower bound of each tier. */
    public enum Tier {
        HATED(-100, 0xC03020), HOSTILE(-59, 0xD06030), DISLIKED(-39, 0xC8A050), NEUTRAL(-14, 0xB8B0A0),
        LIKED(15, 0x90C060), HONORED(50, 0x60C080), REVERED(80, 0x70D0E0);

        public final int min;
        public final int color;

        Tier(int min, int color) {
            this.min = min;
            this.color = color;
        }

        public static Tier of(int value) {
            Tier out = HATED;
            for (Tier t : values()) if (value >= t.min) out = t;
            return out;
        }

        public Component displayName() {
            return Component.translatable("society.skycraft.tier." + name().toLowerCase(Locale.ROOT));
        }
    }

    private Reputation() {}

    public static boolean isFaction(String id) {
        if (id == null || id.isBlank()) return false;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (String f : FACTIONS) if (f.equals(norm)) return true;
        return snapshot().factions().containsKey(norm);
    }

    public static Component factionName(String id) {
        return Component.translatable("society.skycraft.faction." + id);
    }

    /** The reputation compound (read-only use on the client is fine). */
    public static CompoundTag tag(PlayerData data) {
        CompoundTag mod = data.module(MODULE);
        if (!mod.contains(KEY, Tag.TAG_COMPOUND)) mod.put(KEY, new CompoundTag());
        return mod.getCompound(KEY);
    }

    public static int get(Player player, String faction) {
        if (player == null || faction == null || faction.isBlank()) return 0;
        String f = faction.trim().toLowerCase(Locale.ROOT);
        CompoundTag mod = SkyData.get(player).module(MODULE);
        if (!mod.contains(KEY, Tag.TAG_COMPOUND)) return 0;
        CompoundTag rep = mod.getCompound(KEY);
        if (rep.contains(f, Tag.TAG_INT)) return rep.getInt(f);
        String alias = ALIAS_PAIRS.get(f);
        return alias != null && rep.contains(alias, Tag.TAG_INT) ? rep.getInt(alias) : 0;
    }

    public static boolean hostile(Player player, String faction) {
        return get(player, faction) <= SocietyConfig.HOSTILE_THRESHOLD.get();
    }

    /** Sets a reputation silently (commands). Syncs paired aliases so both IDs report the same value. */
    public static void set(Player player, String faction, int value) {
        if (player == null || faction == null || faction.isBlank()) return;
        String f = faction.trim().toLowerCase(Locale.ROOT);
        PlayerData data = SkyData.get(player);
        int v = Math.max(MIN, Math.min(MAX, value));
        CompoundTag t = tag(data);
        if (v == 0) {
            t.remove(f);
            String alias = ALIAS_PAIRS.get(f);
            if (alias != null) t.remove(alias);
        } else {
            t.putInt(f, v);
            String alias = ALIAS_PAIRS.get(f);
            if (alias != null) t.putInt(alias, v);
        }
        data.markDirty();
    }

    /** Changes a reputation; tells the player when their standing tier changes. */
    public static void add(ServerPlayer player, String faction, int delta) {
        if (delta == 0 || !isFaction(faction) || player.isCreative() || player.isSpectator()) return;
        int before = get(player, faction);
        int after = Math.max(MIN, Math.min(MAX, before + delta));
        if (after == before) return;
        set(player, faction, after);
        Tier was = Tier.of(before);
        Tier now = Tier.of(after);
        if (was != now) {
            Component tier = now.displayName().copy().withStyle(s -> s.withColor(now.color));
            Notifier.message(player, Component.translatable("society.skycraft.rep.tier", factionName(faction), tier));
        }
        int threshold = SocietyConfig.HOSTILE_THRESHOLD.get();
        if (before > threshold && after <= threshold) {
            Notifier.message(player, Component.translatable("society.skycraft.rep.enemy", factionName(faction))
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** Normalized 0..1 position of a value on the -100..100 bar. */
    public static float fraction(int value) {
        return (Math.max(MIN, Math.min(MAX, value)) - MIN) / (float) (MAX - MIN);
    }

    // ------------------------------------------------------------------ Data-driven Faction & Polity Queries

    @Nullable
    public static FactionDef factionDef(String factionId) {
        if (factionId == null || factionId.isBlank()) return null;
        return snapshot().factions().get(factionId.trim().toLowerCase(Locale.ROOT));
    }

    public static Map<String, FactionDef> allFactionDefs() {
        return snapshot().factions();
    }

    public static Map<String, PolityMapping> allRegionPolities() {
        return snapshot().regions();
    }

    public static Map<String, PolityMapping> allSettlementPolities() {
        return snapshot().settlements();
    }

    @Nullable
    public static PolityMapping regionPolity(String regionId) {
        if (regionId == null || regionId.isBlank()) return null;
        String norm = regionId.trim().toLowerCase(Locale.ROOT);
        Snapshot s = snapshot();
        PolityMapping m = s.regions().get(norm);
        if (m != null) return m;
        int slash = norm.indexOf('/');
        String shortId = slash >= 0 ? norm.substring(slash + 1) : norm;
        return s.shortRegions().get(shortId);
    }

    @Nullable
    public static PolityMapping settlementPolity(String settlementId) {
        if (settlementId == null || settlementId.isBlank()) return null;
        return snapshot().settlements().get(settlementId.trim().toLowerCase(Locale.ROOT));
    }

    @Nullable
    public static PolityMapping polityFor(@Nullable String regionId, @Nullable String settlementId) {
        PolityMapping sm = settlementPolity(settlementId);
        if (sm != null) return sm;
        return regionPolity(regionId);
    }

    public static String rulingFaction(String regionOrSettlement) {
        if (regionOrSettlement == null || regionOrSettlement.isBlank()) return TOWNSFOLK;
        PolityMapping sm = settlementPolity(regionOrSettlement);
        if (sm != null && !sm.rulingFaction().isBlank()) return sm.rulingFaction();
        PolityMapping rm = regionPolity(regionOrSettlement);
        if (rm != null && !rm.rulingFaction().isBlank()) return rm.rulingFaction();
        return TOWNSFOLK;
    }

    public static List<String> alliedFactions(String regionOrSettlementOrFaction) {
        if (regionOrSettlementOrFaction == null || regionOrSettlementOrFaction.isBlank()) return List.of();
        PolityMapping sm = settlementPolity(regionOrSettlementOrFaction);
        if (sm != null) return sm.alliedFactions();
        PolityMapping rm = regionPolity(regionOrSettlementOrFaction);
        if (rm != null) return rm.alliedFactions();
        FactionDef fd = factionDef(regionOrSettlementOrFaction);
        return fd != null ? fd.allies() : List.of();
    }

    public static List<String> rivalFactions(String regionOrSettlementOrFaction) {
        if (regionOrSettlementOrFaction == null || regionOrSettlementOrFaction.isBlank()) return List.of();
        PolityMapping sm = settlementPolity(regionOrSettlementOrFaction);
        if (sm != null) return sm.rivalFactions();
        PolityMapping rm = regionPolity(regionOrSettlementOrFaction);
        if (rm != null) return rm.rivalFactions();
        FactionDef fd = factionDef(regionOrSettlementOrFaction);
        return fd != null ? fd.rivals() : List.of();
    }

    public static String dominantFaith(String regionOrSettlement) {
        if (regionOrSettlement == null || regionOrSettlement.isBlank()) return "eight_divines";
        PolityMapping sm = settlementPolity(regionOrSettlement);
        if (sm != null && !sm.dominantFaith().isBlank()) return sm.dominantFaith();
        PolityMapping rm = regionPolity(regionOrSettlement);
        if (rm != null && !rm.dominantFaith().isBlank()) return rm.dominantFaith();
        return "eight_divines";
    }

    /** Clears all faction standings on {@code player} (used by tests and reset commands). */
    public static void clear(@Nullable Player player) {
        if (player == null) return;
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module(MODULE);
        mod.put(KEY, new CompoundTag());
        player.getPersistentData().remove("SkycraftOrcBloodKin");
        data.markDirty();
    }

    public static boolean isOrcStronghold(@Nullable String settlementId) {
        if (settlementId == null || settlementId.isBlank()) return false;
        PolityMapping sm = settlementPolity(settlementId);
        return sm != null && sm.orcStronghold();
    }

    public static boolean hasOrcStrongholdAccess(@Nullable Player player) {
        if (player == null) return false;
        Race race = SkyData.get(player).getRace();
        if (race == Race.ORSIMER) return true;
        if (get(player, ORC_BLOOD_KIN) >= 15 || get(player, ORC_STRONGHOLDS) >= 15) return true;
        return player.getPersistentData().getBoolean("SkycraftOrcBloodKin");
    }

    private static Snapshot snapshot() {
        Snapshot s = SNAPSHOT;
        if (s != null) return s;
        synchronized (Reputation.class) {
            if (SNAPSHOT == null) {
                SNAPSHOT = loadSnapshot();
            }
            return SNAPSHOT;
        }
    }

    private static Snapshot loadSnapshot() {
        Map<String, FactionDef> factions = new LinkedHashMap<>();
        Map<String, PolityMapping> regions = new LinkedHashMap<>();
        Map<String, PolityMapping> shortRegions = new LinkedHashMap<>();
        Map<String, PolityMapping> settlements = new LinkedHashMap<>();

        try (InputStream in = Reputation.class.getResourceAsStream(FACTIONS_RESOURCE)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("factions") && root.get("factions").isJsonObject()) {
                    JsonObject fObj = root.getAsJsonObject("factions");
                    for (Map.Entry<String, JsonElement> e : fObj.entrySet()) {
                        String id = e.getKey().trim().toLowerCase(Locale.ROOT);
                        JsonObject o = e.getValue().getAsJsonObject();
                        factions.put(id, new FactionDef(
                                id,
                                str(o, "province", "skyrim"),
                                str(o, "category", "polity"),
                                str(o, "dominantFaith", "eight_divines"),
                                strList(o, "allies"),
                                strList(o, "rivals"),
                                strList(o, "why")
                        ));
                    }
                }
                if (root.has("regions") && root.get("regions").isJsonObject()) {
                    JsonObject rObj = root.getAsJsonObject("regions");
                    for (Map.Entry<String, JsonElement> e : rObj.entrySet()) {
                        String id = e.getKey().trim().toLowerCase(Locale.ROOT);
                        JsonObject o = e.getValue().getAsJsonObject();
                        PolityMapping pm = new PolityMapping(
                                id,
                                id,
                                str(o, "rulingFaction", TOWNSFOLK),
                                strList(o, "secondaryFactions"),
                                strList(o, "alliedFactions"),
                                strList(o, "rivalFactions"),
                                str(o, "dominantFaith", "eight_divines"),
                                bool(o, "orcStronghold", false),
                                strList(o, "why")
                        );
                        regions.put(id, pm);
                        int slash = id.indexOf('/');
                        String shortId = slash >= 0 ? id.substring(slash + 1) : id;
                        shortRegions.put(shortId, pm);
                    }
                }
                if (root.has("settlements") && root.get("settlements").isJsonObject()) {
                    JsonObject sObj = root.getAsJsonObject("settlements");
                    for (Map.Entry<String, JsonElement> e : sObj.entrySet()) {
                        String id = e.getKey().trim().toLowerCase(Locale.ROOT);
                        JsonObject o = e.getValue().getAsJsonObject();
                        settlements.put(id, new PolityMapping(
                                id,
                                str(o, "region", ""),
                                str(o, "rulingFaction", TOWNSFOLK),
                                strList(o, "secondaryFactions"),
                                strList(o, "alliedFactions"),
                                strList(o, "rivalFactions"),
                                str(o, "dominantFaith", "eight_divines"),
                                bool(o, "orcStronghold", false),
                                strList(o, "why")
                        ));
                    }
                }
            } else {
                LOGGER.warn("Missing {}", FACTIONS_RESOURCE);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load {}", FACTIONS_RESOURCE, ex);
        }

        return new Snapshot(
                Collections.unmodifiableMap(factions),
                Collections.unmodifiableMap(regions),
                Collections.unmodifiableMap(shortRegions),
                Collections.unmodifiableMap(settlements)
        );
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : def;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
    }

    private static List<String> strList(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return List.of();
        JsonArray arr = o.getAsJsonArray(key);
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (el != null && !el.isJsonNull()) {
                String s = el.getAsString().trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
