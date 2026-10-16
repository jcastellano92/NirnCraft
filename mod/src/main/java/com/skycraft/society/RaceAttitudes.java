package com.skycraft.society;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.economy.PriceEngine;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.world.entity.LivingEntity;
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
import java.util.function.ToIntFunction;

/**
 * Hold-, region-, and settlement-level racial, religious, and political attitude evaluator
 * ({@code /skycraft/society/race_attitudes.json}).
 *
 * <p>Evaluates {@code (regionId, settlementId, npcRole, npcRace, playerRace, playerReputation)} into a
 * bounded {@link Evaluation} record carrying:</p>
 * <ul>
 *   <li>{@code dispositionDelta} in {@code [-35, +30]} (fed into {@link Relationships#disposition})</li>
 *   <li>{@code priceMultiplier} in {@code [0.85f, 1.25f]} (fed into {@link PriceEngine} / {@link com.skycraft.economy.Pricing})</li>
 *   <li>{@code guardScrutiny} in {@code [0.70f, 1.50f]}</li>
 *   <li>{@code attitudeKey} (e.g. {@code kin_welcome}, {@code wary_outlander}, {@code trusted_outlander},
 *       {@code ancestral_grudge}, {@code heretic_suspect}, {@code blood_kin_required}, {@code cosmopolitan_neutral},
 *       {@code rebel_suspect}, {@code imperial_suspect})</li>
 *   <li>{@code why}: non-empty {@code WorldSheets} L6–L9 causal field citations</li>
 * </ul>
 */
public final class RaceAttitudes {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaceAttitudes.class);
    private static final String ATTITUDES_RESOURCE = "/skycraft/society/race_attitudes.json";

    public static final String TAG_REGION_OVERRIDE = "SkycraftAttitudeRegion";
    public static final String TAG_SETTLEMENT_OVERRIDE = "SkycraftAttitudeSettlement";

    public static final String KEY_KIN_WELCOME = "kin_welcome";
    public static final String KEY_WARY_OUTLANDER = "wary_outlander";
    public static final String KEY_TRUSTED_OUTLANDER = "trusted_outlander";
    public static final String KEY_ANCESTRAL_GRUDGE = "ancestral_grudge";
    public static final String KEY_HERETIC_SUSPECT = "heretic_suspect";
    public static final String KEY_BLOOD_KIN_REQUIRED = "blood_kin_required";
    public static final String KEY_COSMOPOLITAN_NEUTRAL = "cosmopolitan_neutral";
    public static final String KEY_REBEL_SUSPECT = "rebel_suspect";
    public static final String KEY_IMPERIAL_SUSPECT = "imperial_suspect";

    public static final int MIN_DISPOSITION_DELTA = -35;
    public static final int MAX_DISPOSITION_DELTA = 30;
    public static final float MIN_PRICE_MULTIPLIER = 0.85f;
    public static final float MAX_PRICE_MULTIPLIER = 1.25f;

    /** Result of evaluating a hold/region/settlement's attitude toward a player. */
    public record Evaluation(
            int dispositionDelta,
            float priceMultiplier,
            float guardScrutiny,
            String attitudeKey,
            List<String> why
    ) {}

    /** Per-race modifier within a region or settlement attitude profile. */
    public record RaceModifier(
            int dispositionDelta,
            float priceMultiplier,
            float guardScrutiny,
            String attitudeKey,
            List<String> why
    ) {}

    /** Attitude profile for a region or settlement loaded from {@code race_attitudes.json}. */
    public record AttitudeProfile(
            String id,
            String region,
            String rulingFaction,
            String dominantFaith,
            boolean orcStronghold,
            int defaultDisposition,
            float defaultPriceMultiplier,
            float defaultScrutiny,
            String defaultAttitudeKey,
            List<String> why,
            Map<String, RaceModifier> raceModifiers
    ) {}

    private record Snapshot(
            Map<String, AttitudeProfile> regions,
            Map<String, AttitudeProfile> shortRegions,
            Map<String, AttitudeProfile> settlements
    ) {}

    private static volatile Snapshot SNAPSHOT;

    static {
        PriceEngine.setAttitudeHook(RaceAttitudes::priceMultiplier);
    }

    private RaceAttitudes() {}

    /** Ensures the attitude hook is registered with {@link PriceEngine} and warms the JSON snapshot. */
    public static void init() {
        PriceEngine.setAttitudeHook(RaceAttitudes::priceMultiplier);
        snapshot();
    }

    public static Map<String, AttitudeProfile> allRegionProfiles() {
        return snapshot().regions();
    }

    public static Map<String, AttitudeProfile> allSettlementProfiles() {
        return snapshot().settlements();
    }

    @Nullable
    public static AttitudeProfile regionProfile(@Nullable String regionId) {
        if (regionId == null || regionId.isBlank()) return null;
        String norm = regionId.trim().toLowerCase(Locale.ROOT);
        Snapshot s = snapshot();
        AttitudeProfile p = s.regions().get(norm);
        if (p != null) return p;
        int slash = norm.indexOf('/');
        String shortId = slash >= 0 ? norm.substring(slash + 1) : norm;
        return s.shortRegions().get(shortId);
    }

    @Nullable
    public static AttitudeProfile settlementProfile(@Nullable String settlementId) {
        if (settlementId == null || settlementId.isBlank()) return null;
        return snapshot().settlements().get(settlementId.trim().toLowerCase(Locale.ROOT));
    }

    /** Price multiplier in {@code [0.85f, 1.25f]} for {@link PriceEngine} and {@link com.skycraft.economy.Pricing}. */
    public static float priceMultiplier(@Nullable Player player, @Nullable LivingEntity npc) {
        if (player == null || npc == null) return 1.0f;
        return evaluate(npc, player).priceMultiplier();
    }

    /** Evaluates the attitude of {@code npc} toward {@code player} based on the NPC's region, settlement, role, and race. */
    public static Evaluation evaluate(@Nullable LivingEntity npc, @Nullable Player player) {
        if (player == null) {
            return new Evaluation(0, 1.0f, 1.0f, KEY_COSMOPOLITAN_NEUTRAL, List.of("skyrim.L6.nine_seats"));
        }
        String regionId = "";
        String settlementId = "";
        NpcRole npcRole = NpcRole.CITIZEN;
        Race npcRace = null;

        if (npc != null) {
            var tag = npc.getPersistentData();
            if (tag.contains(TAG_REGION_OVERRIDE)) {
                regionId = tag.getString(TAG_REGION_OVERRIDE);
            }
            if (tag.contains(TAG_SETTLEMENT_OVERRIDE)) {
                settlementId = tag.getString(TAG_SETTLEMENT_OVERRIDE);
            }
            if (regionId.isBlank()) {
                Region reg = Regions.at(npc.getBlockX(), npc.getBlockZ());
                regionId = reg != null ? reg.id() : "skyrim/whiterun";
            }
            if (settlementId.isBlank()) {
                Place pl = Places.nearestSettlement(npc.getBlockX(), npc.getBlockZ(), 220);
                if (pl != null) settlementId = pl.id();
            }
            if (npc instanceof NpcEntity skyNpc) {
                npcRole = skyNpc.role();
                npcRace = skyNpc.race().core();
            } else {
                npcRole = NpcRole.MERCHANT;
            }
        } else {
            Region reg = Regions.at(player.getBlockX(), player.getBlockZ());
            regionId = reg != null ? reg.id() : "skyrim/whiterun";
            Place pl = Places.nearestSettlement(player.getBlockX(), player.getBlockZ(), 220);
            if (pl != null) settlementId = pl.id();
        }

        return evaluate(regionId, settlementId, npcRole, npcRace, player);
    }

    /** Evaluates attitude for a live {@link Player} at a specified region and settlement. */
    public static Evaluation evaluate(
            @Nullable String regionId,
            @Nullable String settlementId,
            @Nullable NpcRole npcRole,
            @Nullable Race npcRace,
            @Nullable Player player
    ) {
        Race playerRace = player != null ? SkyData.get(player).getRace() : Race.NORD;
        ToIntFunction<String> repLookup = f -> player != null ? Reputation.get(player, f) : 0;
        boolean orcFlag = player != null && player.getPersistentData().getBoolean("SkycraftOrcBloodKin");
        return evaluateInternal(regionId, settlementId, npcRole, npcRace, playerRace, repLookup, orcFlag);
    }

    /** Evaluates attitude using string role/race IDs and an explicit reputation map (ideal for unit and selftest verification). */
    public static Evaluation evaluate(
            @Nullable String regionId,
            @Nullable String settlementId,
            @Nullable String npcRole,
            @Nullable String npcRace,
            @Nullable String playerRace,
            @Nullable Map<String, Integer> reputationMap
    ) {
        return evaluate(regionId, settlementId, parseRole(npcRole), parseRace(npcRace), parseRace(playerRace), reputationMap);
    }

    /** Evaluates attitude using an explicit reputation map (ideal for unit and selftest verification). */
    public static Evaluation evaluate(
            @Nullable String regionId,
            @Nullable String settlementId,
            @Nullable NpcRole npcRole,
            @Nullable Race npcRace,
            @Nullable Race playerRace,
            @Nullable Map<String, Integer> reputationMap
    ) {
        ToIntFunction<String> repLookup = f -> {
            if (reputationMap == null || f == null) return 0;
            String norm = f.trim().toLowerCase(Locale.ROOT);
            Integer v = reputationMap.get(norm);
            if (v != null) return v;
            if (Reputation.ORC_BLOOD_KIN.equals(norm)) {
                Integer alt = reputationMap.get(Reputation.ORC_STRONGHOLDS);
                if (alt != null) return alt;
            }
            return 0;
        };
        return evaluateInternal(regionId, settlementId, npcRole, npcRace, playerRace, repLookup, false);
    }

    /** Evaluates attitude using a functional reputation lookup. */
    public static Evaluation evaluate(
            @Nullable String regionId,
            @Nullable String settlementId,
            @Nullable NpcRole npcRole,
            @Nullable Race npcRace,
            @Nullable Race playerRace,
            @Nullable ToIntFunction<String> reputationLookup
    ) {
        ToIntFunction<String> repLookup = reputationLookup != null ? reputationLookup : (f -> 0);
        return evaluateInternal(regionId, settlementId, npcRole, npcRace, playerRace, repLookup, false);
    }

    @Nullable
    private static NpcRole parseRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) return NpcRole.CITIZEN;
        String r = roleId.trim().toLowerCase(Locale.ROOT);
        if ("guard".equals(r)) return NpcRole.IMPERIAL_SOLDIER;
        NpcRole found = NpcRole.byId(r);
        return found != null ? found : NpcRole.CITIZEN;
    }

    @Nullable
    private static Race parseRace(@Nullable String raceId) {
        if (raceId == null || raceId.isBlank()) return null;
        String r = raceId.trim().toLowerCase(Locale.ROOT);
        if ("orc".equals(r) || "orsimer".equals(r)) return Race.ORSIMER;
        return Race.byId(r);
    }

    private static Evaluation evaluateInternal(
            @Nullable String regionId,
            @Nullable String settlementId,
            @Nullable NpcRole npcRole,
            @Nullable Race npcRace,
            @Nullable Race playerRace,
            ToIntFunction<String> repLookup,
            boolean explicitOrcBloodKinFlag
    ) {
        Race pRace = playerRace != null ? playerRace : Race.NORD;
        String raceKey = pRace == Race.ORSIMER ? "orc" : pRace.id().toLowerCase(Locale.ROOT);

        AttitudeProfile sProf = settlementProfile(settlementId);
        AttitudeProfile rProf = regionProfile(regionId);
        if (rProf == null && sProf != null && !sProf.region().isBlank()) {
            rProf = regionProfile(sProf.region());
        }
        AttitudeProfile prof = sProf != null ? sProf : rProf;

        if (prof == null) {
            return new Evaluation(0, 1.0f, 1.0f, KEY_COSMOPOLITAN_NEUTRAL, List.of("skyrim.L6.nine_seats"));
        }

        String rulingFaction = !prof.rulingFaction().isBlank()
                ? prof.rulingFaction()
                : Reputation.rulingFaction(settlementId != null && !settlementId.isBlank() ? settlementId : regionId);

        boolean isStronghold = prof.orcStronghold() || Reputation.isOrcStronghold(settlementId);
        if (isStronghold) {
            List<String> why = !prof.why().isEmpty() ? prof.why() : List.of("skyrim.L7.orc_strongholds");
            if (pRace == Race.ORSIMER) {
                return new Evaluation(22, 0.88f, 0.80f, KEY_KIN_WELCOME, why);
            }
            int bloodKinRep = Math.max(repLookup.applyAsInt(Reputation.ORC_BLOOD_KIN), repLookup.applyAsInt(rulingFaction));
            if (explicitOrcBloodKinFlag || bloodKinRep >= 15) {
                return new Evaluation(16, 0.92f, 0.90f, KEY_TRUSTED_OUTLANDER, why);
            }
            return new Evaluation(-25, 1.20f, 1.40f, KEY_BLOOD_KIN_REQUIRED, why);
        }

        RaceModifier mod = prof.raceModifiers().get(raceKey);
        if (mod == null && pRace == Race.ORSIMER) {
            mod = prof.raceModifiers().get("orsimer");
        }
        if (mod == null && sProf != null && rProf != null) {
            mod = rProf.raceModifiers().get(raceKey);
            if (mod == null && pRace == Race.ORSIMER) {
                mod = rProf.raceModifiers().get("orsimer");
            }
        }

        int disp = mod != null ? mod.dispositionDelta() : prof.defaultDisposition();
        float price = mod != null ? mod.priceMultiplier() : prof.defaultPriceMultiplier();
        float scrutiny = mod != null ? mod.guardScrutiny() : prof.defaultScrutiny();
        String key = mod != null ? mod.attitudeKey() : prof.defaultAttitudeKey();
        List<String> why = (mod != null && !mod.why().isEmpty())
                ? mod.why()
                : (!prof.why().isEmpty() ? prof.why() : List.of("skyrim.L6.nine_seats"));

        // NPC Role adjustments
        if (npcRole == NpcRole.THALMOR) {
            if (pRace == Race.ALTMER) {
                disp = Math.max(disp, 16);
                price = Math.min(price, 0.91f);
                scrutiny = 0.85f;
                key = KEY_KIN_WELCOME;
            } else if (pRace == Race.NORD || pRace == Race.IMPERIAL || pRace == Race.REDGUARD || pRace == Race.BRETON) {
                disp = Math.min(disp, -28);
                price = Math.max(price, 1.22f);
                scrutiny = Math.max(scrutiny, 1.45f);
                key = KEY_HERETIC_SUSPECT;
            }
        } else if (npcRole == NpcRole.MAGE) {
            if (disp < 0 && (pRace == Race.ALTMER || pRace == Race.DUNMER || pRace == Race.BRETON)) {
                disp += 8;
                price -= 0.04f;
            }
        } else if (npcRole == NpcRole.PRIEST) {
            if (disp >= 10) {
                disp += 4;
                price -= 0.02f;
            } else if (disp <= -12) {
                disp -= 4;
                price += 0.02f;
            }
        }

        // Fellow minority kin warmth (e.g., Dunmer speaking to Dunmer in Windhelm's Grey Quarter)
        boolean isOfficialEnforcer = (npcRole != null && npcRole.protector)
                || npcRole == NpcRole.IMPERIAL_SOLDIER
                || npcRole == NpcRole.STORMCLOAK_SOLDIER
                || npcRole == NpcRole.THALMOR
                || npcRole == NpcRole.JARL
                || npcRole == NpcRole.HOUSECARL;
        if (npcRace != null && npcRace == pRace && disp < 0 && !isOfficialEnforcer) {
            disp = 14;
            price = 0.93f;
            scrutiny = 0.90f;
            key = KEY_KIN_WELCOME;
        }

        // Player Reputation with local ruling faction, allied factions, and rival factions
        String scopeKey = (settlementId != null && !settlementId.isBlank() && Reputation.settlementPolity(settlementId) != null)
                ? settlementId
                : (regionId != null ? regionId : "");
        List<String> allies = Reputation.alliedFactions(scopeKey.isEmpty() ? rulingFaction : scopeKey);
        List<String> rivals = Reputation.rivalFactions(scopeKey.isEmpty() ? rulingFaction : scopeKey);

        int rulingRep = repLookup.applyAsInt(rulingFaction);
        int bestAlliedRep = 0;
        for (String a : allies) {
            bestAlliedRep = Math.max(bestAlliedRep, repLookup.applyAsInt(a));
        }
        int bestRivalRep = 0;
        String topRival = "";
        for (String rv : rivals) {
            int rVal = repLookup.applyAsInt(rv);
            if (rVal > bestRivalRep) {
                bestRivalRep = rVal;
                topRival = rv;
            }
        }

        int effectiveLocalRep = Math.max(rulingRep, bestAlliedRep / 2);
        if (effectiveLocalRep >= 20) {
            int bonus = Math.min(36, 18 + (effectiveLocalRep / 3));
            disp = Math.max(14, disp + bonus);
            price = Math.min(0.92f, price - 0.12f - (effectiveLocalRep * 0.001f));
            scrutiny = Math.min(0.88f, scrutiny - 0.25f);
            if (!KEY_KIN_WELCOME.equals(key)) {
                key = KEY_TRUSTED_OUTLANDER;
            }
        } else if (effectiveLocalRep <= -20) {
            int penalty = Math.min(25, Math.abs(effectiveLocalRep) / 2);
            disp -= penalty;
            price += Math.min(0.18f, Math.abs(effectiveLocalRep) * 0.0025f);
            scrutiny += 0.25f;
            if (KEY_KIN_WELCOME.equals(key) || KEY_COSMOPOLITAN_NEUTRAL.equals(key) || KEY_TRUSTED_OUTLANDER.equals(key)) {
                key = KEY_WARY_OUTLANDER;
            }
        }

        if (bestRivalRep >= 25 && effectiveLocalRep < 20) {
            disp -= 18;
            price += 0.12f;
            scrutiny += 0.25f;
            if (Reputation.STORMCLOAKS.equals(topRival) || Reputation.FORSWORN.equals(topRival) || Reputation.RENRIJRA_KRIN.equals(topRival)) {
                key = KEY_REBEL_SUSPECT;
            } else if (Reputation.IMPERIAL_LEGION.equals(topRival) || Reputation.IMPERIALS.equals(topRival)) {
                key = KEY_IMPERIAL_SUSPECT;
            } else if (Reputation.THALMOR.equals(topRival)) {
                key = KEY_HERETIC_SUSPECT;
            } else if (KEY_KIN_WELCOME.equals(key) || KEY_COSMOPOLITAN_NEUTRAL.equals(key)) {
                key = KEY_WARY_OUTLANDER;
            }
        }

        int clampedDisp = Math.max(MIN_DISPOSITION_DELTA, Math.min(MAX_DISPOSITION_DELTA, disp));
        float clampedPrice = Math.max(MIN_PRICE_MULTIPLIER, Math.min(MAX_PRICE_MULTIPLIER, price));
        float clampedScrutiny = Math.max(0.70f, Math.min(1.50f, scrutiny));
        return new Evaluation(clampedDisp, clampedPrice, clampedScrutiny, key, why);
    }

    private static Snapshot snapshot() {
        Snapshot s = SNAPSHOT;
        if (s != null) return s;
        synchronized (RaceAttitudes.class) {
            if (SNAPSHOT == null) {
                SNAPSHOT = loadSnapshot();
            }
            return SNAPSHOT;
        }
    }

    private static Snapshot loadSnapshot() {
        Map<String, AttitudeProfile> regions = new LinkedHashMap<>();
        Map<String, AttitudeProfile> shortRegions = new LinkedHashMap<>();
        Map<String, AttitudeProfile> settlements = new LinkedHashMap<>();

        try (InputStream in = RaceAttitudes.class.getResourceAsStream(ATTITUDES_RESOURCE)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("regions") && root.get("regions").isJsonObject()) {
                    JsonObject rObj = root.getAsJsonObject("regions");
                    for (Map.Entry<String, JsonElement> e : rObj.entrySet()) {
                        String id = e.getKey().trim().toLowerCase(Locale.ROOT);
                        AttitudeProfile prof = parseProfile(id, id, e.getValue().getAsJsonObject());
                        regions.put(id, prof);
                        int slash = id.indexOf('/');
                        String shortId = slash >= 0 ? id.substring(slash + 1) : id;
                        shortRegions.put(shortId, prof);
                    }
                }
                if (root.has("settlements") && root.get("settlements").isJsonObject()) {
                    JsonObject sObj = root.getAsJsonObject("settlements");
                    for (Map.Entry<String, JsonElement> e : sObj.entrySet()) {
                        String id = e.getKey().trim().toLowerCase(Locale.ROOT);
                        JsonObject o = e.getValue().getAsJsonObject();
                        AttitudeProfile prof = parseProfile(id, str(o, "region", ""), o);
                        settlements.put(id, prof);
                    }
                }
            } else {
                LOGGER.warn("Missing {}", ATTITUDES_RESOURCE);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load {}", ATTITUDES_RESOURCE, ex);
        }

        return new Snapshot(
                Collections.unmodifiableMap(regions),
                Collections.unmodifiableMap(shortRegions),
                Collections.unmodifiableMap(settlements)
        );
    }

    private static AttitudeProfile parseProfile(String id, String region, JsonObject o) {
        List<String> baseWhy = strList(o, "why");
        Map<String, RaceModifier> mods = new LinkedHashMap<>();
        if (o.has("raceModifiers") && o.get("raceModifiers").isJsonObject()) {
            JsonObject mObj = o.getAsJsonObject("raceModifiers");
            for (Map.Entry<String, JsonElement> me : mObj.entrySet()) {
                String rKey = me.getKey().trim().toLowerCase(Locale.ROOT);
                JsonObject mo = me.getValue().getAsJsonObject();
                List<String> mWhy = strList(mo, "why");
                mods.put(rKey, new RaceModifier(
                        numInt(mo, "dispositionDelta", 0),
                        numFloat(mo, "priceMultiplier", 1.0f),
                        numFloat(mo, "guardScrutiny", 1.0f),
                        str(mo, "attitudeKey", KEY_COSMOPOLITAN_NEUTRAL),
                        mWhy.isEmpty() ? baseWhy : mWhy
                ));
            }
        }
        return new AttitudeProfile(
                id,
                region,
                str(o, "rulingFaction", Reputation.TOWNSFOLK),
                str(o, "dominantFaith", "eight_divines"),
                bool(o, "orcStronghold", false),
                numInt(o, "defaultDisposition", 0),
                numFloat(o, "defaultPriceMultiplier", 1.0f),
                numFloat(o, "defaultScrutiny", 1.0f),
                str(o, "defaultAttitudeKey", KEY_COSMOPOLITAN_NEUTRAL),
                baseWhy,
                Collections.unmodifiableMap(mods)
        );
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : def;
    }

    private static int numInt(JsonObject o, String key, int def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
    }

    private static float numFloat(JsonObject o, String key, float def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : def;
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
