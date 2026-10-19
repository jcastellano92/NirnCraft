package com.nirncraft.society;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and indexes all 12,709 authored named residents across 446 settlements
 * from {@code /nirncraft/society/*_residents.json}.
 *
 * <p>Provides deterministic lookup by settlement key, resident ID, court role,
 * or settlement housing job slot, and stamps resident identity, appearance,
 * causal {@code why} citations, and authored dialogue lines onto spawned
 * {@link NpcEntity} instances.
 */
public final class SocietyResidents {
    public static final String RESIDENT_ID_KEY = "NirnCraftResidentId";
    public static final String RESIDENT_NAME_KEY = "NirnCraftResidentName";
    public static final String TITLE_KEY = "NirnCraftTitle";
    public static final String HOME_NAME_KEY = "NirnCraftHomeName";
    public static final String WORK_NAME_KEY = "NirnCraftWorkName";
    public static final String SCHEDULE_KEY = "NirnCraftSchedule";
    public static final String GREET_KEY = "NirnCraftGreet";
    public static final String BARK_KEY = "NirnCraftBark";
    public static final String TALK_KEY = "NirnCraftTalk";
    public static final String SECRET_KEY = "NirnCraftSecret";

    public static final List<String> RESIDENT_FILES = List.of(
            "/nirncraft/society/skyrim_residents.json",
            "/nirncraft/society/morrowind_residents.json",
            "/nirncraft/society/cyrodiil_residents.json",
            "/nirncraft/society/high_rock_residents.json",
            "/nirncraft/society/hammerfell_residents.json",
            "/nirncraft/society/valenwood_residents.json",
            "/nirncraft/society/summerset_residents.json",
            "/nirncraft/society/elsweyr_residents.json",
            "/nirncraft/society/black_marsh_residents.json",
            "/nirncraft/society/foreign_residents.json"
    );

    private static final Map<String, String> SETTLEMENT_ALIASES = Map.of(
            "addamasartus", "addamasartus_post"
    );

    public record Entry(
            String id,
            String settlementId,
            String settlementName,
            String regionId,
            String province,
            String name,
            String rawRole,
            NpcRole role,
            String title,
            @Nullable NpcNames.Race race,
            String rawRace,
            @Nullable Boolean female,
            String home,
            String work,
            String schedule,
            String greet,
            String bark,
            String talk,
            String secret,
            @Nullable String merchant,
            @Nullable String trainerSkill,
            @Nullable String trainerTier,
            @Nullable String courtRole,
            List<String> why
    ) {
        public String place() {
            return settlementId;
        }

        public String region() {
            return regionId;
        }

        public NpcRole npcRole() {
            return role;
        }

        public NpcNames.Race resolveRace(String fallbackRegion, long seed) {
            if (race != null) return race;
            String reg = (regionId != null && !regionId.isEmpty()) ? regionId : fallbackRegion;
            return Cultures.majority(com.nirncraft.tamriel.Regions.get(reg != null ? reg : "skyrim/whiterun"));
        }

        public boolean resolveFemale(long seed) {
            if (female != null) return female;
            return ((seed ^ id.hashCode()) & 1L) != 0L;
        }

        public String displayName(@Nullable NpcRole displayRole) {
            return name;
        }
    }

    private static volatile boolean loaded = false;
    private static final Map<String, List<Entry>> BY_SETTLEMENT = new LinkedHashMap<>();
    private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

    private SocietyResidents() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        reload();
    }

    public static synchronized void reload() {
        BY_SETTLEMENT.clear();
        BY_ID.clear();
        int filesLoaded = 0;
        for (String path : RESIDENT_FILES) {
            try (InputStream in = SocietyResidents.class.getResourceAsStream(path)) {
                if (in == null) {
                    NirnCraft.LOGGER.warn("[NirnCraft] Missing residents resource: {}", path);
                    continue;
                }
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                String province = getString(root, "province", "skyrim");
                JsonObject settlements = root.has("settlements") && root.get("settlements").isJsonObject()
                        ? root.getAsJsonObject("settlements")
                        : new JsonObject();
                for (Map.Entry<String, JsonElement> sEntry : settlements.entrySet()) {
                    String sid = normalizeId(sEntry.getKey());
                    if (!sEntry.getValue().isJsonObject()) continue;
                    JsonObject sObj = sEntry.getValue().getAsJsonObject();
                    String sName = cleanPlaceTokens(getString(sObj, "name", humanize(sid)));
                    String regionId = getString(sObj, "region", province);
                    List<String> settlementWhy = parseStringList(sObj.get("why"));
                    JsonArray resArr = sObj.has("residents") && sObj.get("residents").isJsonArray()
                            ? sObj.getAsJsonArray("residents")
                            : new JsonArray();
                    List<Entry> list = BY_SETTLEMENT.computeIfAbsent(sid, k -> new ArrayList<>());
                    for (JsonElement rEl : resArr) {
                        if (!rEl.isJsonObject()) continue;
                        Entry entry = parseResident(rEl.getAsJsonObject(), sid, sName, regionId, province, settlementWhy);
                        if (entry != null) {
                            list.add(entry);
                            BY_ID.put(entry.id(), entry);
                        }
                    }
                }
                filesLoaded++;
            } catch (Exception ex) {
                NirnCraft.LOGGER.error("[NirnCraft] Failed loading residents file {}: {}", path, ex.getMessage());
            }
        }
        loaded = true;
        NirnCraft.LOGGER.info("[NirnCraft] Loaded {} authored residents across {} settlements from {} files",
                BY_ID.size(), BY_SETTLEMENT.size(), filesLoaded);
    }

    public static int totalCount() {
        ensureLoaded();
        return BY_ID.size();
    }

    public static int settlementCount() {
        ensureLoaded();
        return BY_SETTLEMENT.size();
    }

    public static Set<String> settlementIds() {
        ensureLoaded();
        return Collections.unmodifiableSet(BY_SETTLEMENT.keySet());
    }

    public static Collection<Entry> all() {
        ensureLoaded();
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    public static Optional<Entry> byId(@Nullable String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        ensureLoaded();
        return Optional.ofNullable(BY_ID.get(id.trim()));
    }

    public static List<Entry> forSettlement(@Nullable String settlementId) {
        if (settlementId == null || settlementId.isBlank()) return List.of();
        ensureLoaded();
        String key = normalizeId(settlementId);
        List<Entry> primary = BY_SETTLEMENT.get(key);
        if ("aldruhn".equals(key)) {
            List<Entry> underSkar = BY_SETTLEMENT.get("ald_ruhn");
            if (primary != null && underSkar != null) {
                List<Entry> combined = new ArrayList<>(primary.size() + underSkar.size());
                combined.addAll(primary);
                combined.addAll(underSkar);
                return Collections.unmodifiableList(combined);
            }
        }
        if (primary != null && !primary.isEmpty()) {
            return Collections.unmodifiableList(primary);
        }
        String alias = SETTLEMENT_ALIASES.get(key);
        if (alias != null) {
            List<Entry> aliased = BY_SETTLEMENT.get(alias);
            if (aliased != null) return Collections.unmodifiableList(aliased);
        }
        return List.of();
    }

    @Nullable
    public static Entry innkeeperFor(@Nullable String settlementId) {
        List<Entry> list = forSettlement(settlementId);
        for (Entry e : list) {
            if ("innkeeper".equals(e.rawRole()) || "inn".equals(e.rawRole())
                    || "inn".equals(e.merchant()) || "innkeeper".equals(e.merchant())
                    || e.role() == NpcRole.INNKEEPER) {
                return e;
            }
        }
        return null;
    }

    @Nullable
    public static Entry courtMemberFor(@Nullable String settlementId, String courtRole) {
        if (courtRole == null || courtRole.isBlank()) return null;
        String target = normalizeId(courtRole);
        List<Entry> list = forSettlement(settlementId);
        for (Entry e : list) {
            if (target.equals(e.courtRole()) || target.equals(e.rawRole())) {
                return e;
            }
        }
        return null;
    }

    /**
     * Deterministically selects the best authored resident for a {@link Housing.Resident} slot
     * in {@code settlementId}, tracking already-claimed resident IDs in {@code usedIds}.
     */
    @Nullable
    public static Entry matchForSlot(
            @Nullable String settlementId,
            @Nullable String job,
            @Nullable NpcRole targetRole,
            int slotIndex,
            @Nullable Set<String> usedIds
    ) {
        List<Entry> candidates = forSettlement(settlementId);
        if (candidates.isEmpty()) return null;
        String normJob = job == null ? "" : job.trim().toUpperCase(Locale.ROOT);

        // Pass 1: exact court/job match
        for (Entry e : candidates) {
            if (usedIds != null && usedIds.contains(e.id())) continue;
            if (matchesJobExact(e, normJob)) {
                if (usedIds != null) usedIds.add(e.id());
                return e;
            }
        }

        // Pass 2: NpcRole match (excluding court leaders for generic non-court slots)
        if (targetRole != null) {
            for (Entry e : candidates) {
                if (usedIds != null && usedIds.contains(e.id())) continue;
                if (!isCourtSlot(normJob) && isCourtLeader(e)) continue;
                if (e.role() == targetRole) {
                    if (usedIds != null) usedIds.add(e.id());
                    return e;
                }
            }
        }

        // Never borrow an unrelated specialist's identity or reuse an already claimed name.
        // An unmatched slot keeps the ordinary procedural resident identity.
        return null;
    }

    /**
     * Applies an authored resident's identity, appearance, causal {@code why} links,
     * and custom dialogue tags onto a spawned {@link NpcEntity}.
     */
    public static void applyToNpc(NpcEntity npc, Entry entry) {
        if (entry == null) return;
        applyToNpc(npc, entry, entry.regionId(), entry.id().hashCode(), true);
    }

    public static void applyToNpc(NpcEntity npc, Entry entry, @Nullable String fallbackRegion, long seed, boolean overwriteCustomName) {
        if (npc == null || entry == null) return;
        if (overwriteCustomName) {
            npc.setCustomName(Component.literal(entry.name()));
        }
        NpcRole r = npc.role();
        NpcNames.Race race = entry.resolveRace(fallbackRegion != null ? fallbackRegion : "skyrim/whiterun", seed);
        boolean female = entry.resolveFemale(seed);
        Outfit outfit = Cultures.outfitFor(r, race, npc.getRandom());
        npc.setLook(NpcLook.random(race, female, r == NpcRole.CHILD, outfit, npc.getRandom()));

        CompoundTag data = npc.getPersistentData();
        data.putString(NpcRegistry.NAME_KEY, entry.name());
        data.putString(RESIDENT_ID_KEY, entry.id());
        data.putString(RESIDENT_NAME_KEY, entry.name());
        if (!entry.title().isEmpty()) data.putString(TITLE_KEY, entry.title());
        if (!entry.home().isEmpty()) data.putString(HOME_NAME_KEY, entry.home());
        if (!entry.work().isEmpty()) data.putString(WORK_NAME_KEY, entry.work());
        if (!entry.schedule().isEmpty()) data.putString(SCHEDULE_KEY, entry.schedule());
        if (!entry.greet().isEmpty()) data.putString(GREET_KEY, entry.greet());
        if (!entry.bark().isEmpty()) data.putString(BARK_KEY, entry.bark());
        if (!entry.talk().isEmpty()) data.putString(TALK_KEY, entry.talk());
        if (!entry.secret().isEmpty()) data.putString(SECRET_KEY, entry.secret());

        if (!entry.why().isEmpty()) {
            data.putString(Housing.WHY_KEY, String.join(",", entry.why()));
        }
    }

    private static boolean isCourtSlot(String normJob) {
        return "JARL".equals(normJob)
                || "STEWARD".equals(normJob)
                || "WIZARD".equals(normJob)
                || "COURT_WIZARD".equals(normJob)
                || "HOUSECARL".equals(normJob);
    }

    private static boolean isCourtLeader(Entry e) {
        if (e.courtRole() != null) {
            return "jarl".equals(e.courtRole())
                    || "steward".equals(e.courtRole())
                    || "court_wizard".equals(e.courtRole())
                    || "housecarl".equals(e.courtRole());
        }
        return e.role() == NpcRole.JARL
                || e.role() == NpcRole.STEWARD
                || e.role() == NpcRole.MAGE
                || e.role() == NpcRole.HOUSECARL;
    }

    private static boolean matchesJobExact(Entry e, String normJob) {
        String rr = e.rawRole();
        String cr = e.courtRole() != null ? e.courtRole() : "";
        String mr = e.merchant() != null ? e.merchant() : "";
        if (normJob.startsWith("TRAINER_")) {
            String skill = normJob.substring("TRAINER_".length()).toLowerCase(Locale.ROOT);
            return skill.equals(e.trainerSkill()) || ("trainer".equals(rr) && e.trainerSkill() == null);
        }
        return switch (normJob) {
            case "JARL" -> "jarl".equals(cr) || "jarl".equals(rr) || e.role() == NpcRole.JARL;
            case "STEWARD" -> "steward".equals(cr) || "steward".equals(rr) || "bailiff".equals(rr);
            case "WIZARD", "COURT_WIZARD" -> "court_wizard".equals(cr) || "court_wizard".equals(rr) || "court_wizard".equals(mr);
            case "HOUSECARL" -> "housecarl".equals(cr) || "housecarl".equals(rr);
            case "INN", "INNKEEPER", "COOK", "SERVER" -> "innkeeper".equals(rr) || "inn".equals(rr)
                    || "inn".equals(mr) || "innkeeper".equals(mr) || "hearth_cook".equals(rr) || "cellarer".equals(rr);
            case "BARD" -> "bard".equals(rr) || "tavern_bard".equals(rr);
            case "PRIEST" -> "priest".equals(rr) || "high_priest".equals(rr) || "hall_of_dead_keeper".equals(rr)
                    || "temple_curate".equals(rr) || "moon_bishop".equals(rr) || "tree_minder".equals(rr);
            case "SMITH" -> "smith".equals(rr) || "blacksmith".equals(rr) || "master_smith".equals(rr)
                    || "armorer".equals(rr) || "weaponsmith".equals(rr) || "smith".equals(mr) || "blacksmith".equals(mr);
            case "MERCHANT" -> "merchant".equals(rr) || "general_trader".equals(rr) || "general_goods".equals(rr)
                    || "spice_merchant".equals(rr) || "merchant".equals(mr) || "general".equals(mr)
                    || "general_store".equals(mr) || "general_trader".equals(mr);
            case "ALCHEMIST", "APOTHECARY" -> "alchemist".equals(rr) || "apothecary".equals(rr) || "herbalist".equals(rr)
                    || "alchemist".equals(mr) || "apothecary".equals(mr);
            case "JEWELER" -> "jeweler".equals(rr) || "jeweller".equals(rr) || "jeweler".equals(mr);
            case "BOOKSELLER" -> "bookseller".equals(rr) || "book_seller".equals(rr) || "bookseller".equals(mr)
                    || "librarian".equals(rr) || "archivist".equals(rr);
            case "TAILOR" -> "tailor".equals(rr) || "clothier".equals(rr) || "weaver".equals(rr) || "tailor".equals(mr);
            case "BUTCHER" -> "butcher".equals(rr) || "butcher".equals(mr);
            case "FLETCHER" -> "fletcher".equals(rr) || "fletcher".equals(mr);
            case "FENCE" -> "fence".equals(rr) || "fence".equals(mr) || "pawnbroker".equals(rr);
            case "FARMER", "FARMHAND" -> "farmer".equals(rr) || "ploughman".equals(rr) || "herdsman".equals(rr)
                    || "ash_yam_grower".equals(rr) || "saltrice_planter".equals(rr) || "muck_farmer".equals(rr);
            case "MILLER", "MILL_HAND" -> "miller".equals(rr);
            case "LUMBERJACK", "WOODCUTTER" -> "lumberjack".equals(rr) || "wood_hewer".equals(rr);
            case "MINER" -> "miner".equals(rr) || "kwama_egg_miner".equals(rr) || "ebony_miner".equals(rr)
                    || "quarry_foreman".equals(rr) || "quarryman".equals(rr);
            case "FISHER" -> "fisher".equals(rr) || "fisherman".equals(rr) || "fishmonger".equals(rr);
            case "HUNTER" -> "hunter".equals(rr) || "trapper".equals(rr) || "wood_scout".equals(rr) || "ash_scout".equals(rr);
            case "TRAINER", "GUILD_TRAINER" -> e.trainerSkill() != null || "trainer".equals(rr) || "guild_trainer".equals(rr);
            case "GUILD_LEADER" -> "guild_leader".equals(rr);
            case "GUILD_VENDOR" -> "guild_vendor".equals(rr);
            case "WORKER", "LABOURER", "HOUSEKEEPER", "ELDER" -> "worker".equals(rr) || "citizen".equals(rr);
            default -> false;
        };
    }

    @Nullable
    private static Entry parseResident(
            JsonObject obj,
            String settlementId,
            String settlementName,
            String regionId,
            String province,
            List<String> settlementWhy
    ) {
        String id = getString(obj, "id", "");
        String name = cleanPlaceTokens(getString(obj, "name", ""));
        if (id.isEmpty() || name.isEmpty()) return null;

        String rawRole = normalizeId(getString(obj, "role", "citizen"));
        String courtRole = parseNullableToken(obj.get("court_role"));
        String merchant = parseMerchant(obj.get("merchant"), rawRole);
        String[] trainer = parseTrainer(obj.get("trainer"));
        String trainerSkill = trainer[0];
        String trainerTier = trainer[1];

        NpcRole role = mapRole(rawRole, courtRole, merchant, trainerSkill);
        String title = cleanPlaceTokens(getString(obj, "title", humanize(rawRole)));
        String rawRace = normalizeId(getString(obj, "race", ""));
        NpcNames.Race race = parseRace(rawRace);
        Boolean female = obj.has("female") && obj.get("female").isJsonPrimitive() && obj.get("female").getAsJsonPrimitive().isBoolean()
                ? obj.get("female").getAsBoolean()
                : null;

        String home = cleanPlaceTokens(getString(obj, "home", settlementName + " " + title + " Quarters"));
        String work = cleanPlaceTokens(getString(obj, "work", settlementName + " " + title));
        String schedule = getString(obj, "schedule", "08:00-18:00 work_station; 18:00-22:00 hearth_inn; 22:00-08:00 quarters");
        String greet = cleanPlaceTokens(getString(obj, "greet", ""));
        String bark = cleanPlaceTokens(getString(obj, "bark", ""));
        String talk = cleanPlaceTokens(getString(obj, "talk", ""));
        String secret = cleanPlaceTokens(getString(obj, "secret", getString(obj, "problem", "")));

        List<String> why = parseStringList(obj.get("why"));
        if (why.isEmpty() && !settlementWhy.isEmpty()) {
            why = settlementWhy;
        }

        return new Entry(
                id,
                settlementId,
                settlementName,
                regionId,
                province,
                name,
                rawRole,
                role,
                title,
                race,
                rawRace,
                female,
                home,
                work,
                schedule,
                greet,
                bark,
                talk,
                secret,
                merchant,
                trainerSkill,
                trainerTier,
                courtRole,
                why
        );
    }

    public static NpcRole mapRole(
            String rawRole,
            @Nullable String courtRole,
            @Nullable String merchant,
            @Nullable String trainerSkill
    ) {
        if (courtRole != null) {
            switch (courtRole) {
                case "jarl" -> { return NpcRole.JARL; }
                case "steward" -> { return NpcRole.STEWARD; }
                case "court_wizard" -> { return NpcRole.MAGE; }
                case "housecarl" -> { return NpcRole.HOUSECARL; }
                default -> {}
            }
        }
        return switch (rawRole) {
            case "jarl", "ashkhan", "treethane", "clan_mother", "kinlord_justiciar" -> NpcRole.JARL;
            case "steward", "bailiff", "mouth_retainer", "telvanni_mouth", "magistrate",
                 "imperial_customs_clerk", "customs_clerk", "census_clerk", "tax_collector",
                 "granary_reeve", "guild_leader", "guild_envoy", "veteran_thane",
                 "dowager_noble", "retainer" -> NpcRole.STEWARD;
            case "court_wizard", "ash_wise_woman", "sap_speaker", "psijic_custodian" -> NpcRole.MAGE;
            case "housecarl", "gulakhan" -> NpcRole.HOUSECARL;
            case "captain_of_guard", "watch_captain", "watch_sergeant", "captain" -> NpcRole.IMPERIAL_SOLDIER;
            case "guard", "ordinators_watch", "ordinator", "buoyant_armiger", "imperial_soldier",
                 "wall_sentry", "night_watchman", "watchman", "jailer", "gate_porter",
                 "retired_legionary", "morag_tong_factor" -> NpcRole.IMPERIAL_SOLDIER;
            case "innkeeper", "inn", "cellarer", "brewmaster", "vintner", "hearth_cook",
                 "scullion", "baker" -> NpcRole.INNKEEPER;
            case "smith", "blacksmith", "master_smith", "armorer", "weaponsmith", "apprentice_smith",
                 "smelter_hand", "farrier", "locksmith", "glass_artisan", "mason",
                 "carpenter", "cooper" -> NpcRole.BLACKSMITH;
            case "merchant", "general_trader", "general_goods", "jeweler", "jeweller",
                 "tailor", "clothier", "weaver", "chandler", "pawnbroker", "spice_merchant",
                 "fence", "guild_vendor", "sugar_factor", "tally_seal_factor", "customs_factor",
                 "factor", "caravan_master", "silt_strider_caravaneer", "charter_navigator",
                 "harbormaster", "barge_master", "ferryman", "carter", "courier",
                 "dock_stevedore", "dockworker", "sailor", "rope_maker" -> NpcRole.MERCHANT;
            case "apothecary", "alchemist", "herbalist", "healer", "green_spinner" -> NpcRole.APOTHECARY;
            case "priest", "high_priest", "hall_of_dead_keeper", "temple_curate", "moon_bishop",
                 "tree_minder", "necrom_bone_binder", "acolyte", "pilgrim_guide",
                 "bell_ringer", "grave_digger" -> NpcRole.PRIEST;
            case "mage", "enchanter", "apprentice_mage", "reef_caller" -> NpcRole.MAGE;
            case "scholar", "bookseller", "book_seller", "scribe", "scroll_scribe",
                 "librarian", "archivist", "chronicler", "sapiarch_lector",
                 "dissident_scholar" -> NpcRole.SCHOLAR;
            case "bard", "tavern_bard" -> NpcRole.BARD;
            case "farmer", "ploughman", "herdsman", "herder", "guar_wrangler", "guar_herder",
                 "netch_herder", "horse_breeder", "stablemaster", "stablehand",
                 "kennel_master", "beekeeper", "ash_yam_grower", "muck_farmer",
                 "saltrice_planter" -> NpcRole.FARMER;
            case "miner", "kwama_egg_miner", "ebony_miner", "quarry_foreman", "quarryman" -> NpcRole.MINER;
            case "lumberjack", "wood_hewer" -> NpcRole.LUMBERJACK;
            case "miller" -> NpcRole.FARMER;
            case "hunter", "fletcher", "trapper", "tanner", "wood_scout", "ash_scout", "scout" -> NpcRole.HUNTER;
            case "fisher", "fisherman", "fishmonger", "net_mender" -> NpcRole.FISHER;
            case "butcher" -> NpcRole.BUTCHER;
            case "trainer", "guild_trainer" -> NpcRole.TRAINER;
            case "beggar", "mendicant" -> NpcRole.BEGGAR;
            case "child" -> NpcRole.CHILD;
            default -> {
                if (trainerSkill != null) yield NpcRole.TRAINER;
                if (merchant != null) yield NpcRole.MERCHANT;
                yield NpcRole.CITIZEN;
            }
        };
    }

    @Nullable
    public static NpcNames.Race parseRace(@Nullable String raw) {
        if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw)) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "nord", "atmoran" -> NpcNames.Race.NORD;
            case "imperial", "cyrodiilic", "colovian", "nibenese", "akaviri", "tsaesci" -> NpcNames.Race.IMPERIAL;
            case "breton", "reachman" -> NpcNames.Race.BRETON;
            case "redguard", "yokudan" -> NpcNames.Race.REDGUARD;
            case "dunmer", "dark_elf", "chimer" -> NpcNames.Race.DUNMER;
            case "altmer", "high_elf", "maormer" -> NpcNames.Race.ALTMER;
            case "bosmer", "wood_elf" -> NpcNames.Race.BOSMER;
            case "orc", "orsimer" -> NpcNames.Race.ORC;
            case "khajiit", "ka_po_tun", "tang_mo" -> NpcNames.Race.KHAJIIT;
            case "argonian", "saxhleel", "sload" -> NpcNames.Race.ARGONIAN;
            default -> null;
        };
    }

    @Nullable
    private static String parseNullableToken(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        String s = el.getAsString().trim();
        if (s.isEmpty() || "none".equalsIgnoreCase(s) || "null".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return null;
        }
        return normalizeId(s);
    }

    @Nullable
    private static String parseMerchant(@Nullable JsonElement el, String rawRole) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        if (el.getAsJsonPrimitive().isBoolean()) {
            if (!el.getAsBoolean()) return null;
            return switch (rawRole) {
                case "master_smith", "armorer", "weaponsmith", "smith", "blacksmith" -> "smith";
                case "innkeeper", "inn" -> "inn";
                case "apothecary", "alchemist", "herbalist" -> "apothecary";
                case "court_wizard", "mage", "enchanter" -> "court_wizard";
                case "bookseller", "book_seller", "scribe", "scholar" -> "bookseller";
                case "jeweler", "jeweller" -> "jeweler";
                case "tailor", "clothier", "weaver" -> "tailor";
                case "butcher" -> "butcher";
                case "fletcher" -> "fletcher";
                case "fence", "pawnbroker" -> "fence";
                default -> "merchant";
            };
        }
        return parseNullableToken(el);
    }

    private static String[] parseTrainer(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return new String[]{null, null};
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            String skill = parseNullableToken(o.get("skill"));
            String tier = parseNullableToken(o.get("tier"));
            return new String[]{skill, tier};
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String raw = el.getAsString().trim();
            if (raw.isEmpty() || "none".equalsIgnoreCase(raw) || "null".equalsIgnoreCase(raw)) {
                return new String[]{null, null};
            }
            int colon = raw.indexOf(':');
            if (colon > 0) {
                return new String[]{
                        normalizeId(raw.substring(0, colon)),
                        normalizeId(raw.substring(colon + 1))
                };
            }
            return new String[]{normalizeId(raw), null};
        }
        return new String[]{null, null};
    }

    private static List<String> parseStringList(@Nullable JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement item : arr) {
            if (item != null && item.isJsonPrimitive()) {
                String s = item.getAsString().trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return fallback;
        String val = el.getAsString().trim();
        return val.isEmpty() ? fallback : val;
    }

    private static String normalizeId(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String cleanPlaceTokens(String text) {
        if (text == null || !text.contains("place.nirncraft.")) return text == null ? "" : text;
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        String prefix = "place.nirncraft.";
        while (idx < text.length()) {
            int hit = text.indexOf(prefix, idx);
            if (hit < 0) {
                sb.append(text, idx, text.length());
                break;
            }
            sb.append(text, idx, hit);
            int start = hit + prefix.length();
            int end = start;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    end++;
                } else {
                    break;
                }
            }
            String placeId = text.substring(start, end);
            sb.append(humanize(placeId));
            idx = end;
        }
        return sb.toString();
    }

    private static String humanize(String id) {
        if (id == null || id.isEmpty()) return "";
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.toString();
    }
}
