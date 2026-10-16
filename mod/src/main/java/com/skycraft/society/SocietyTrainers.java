package com.skycraft.society;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Skill;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.skills.Progression;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.world.WorldSheets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Town and guild skill trainers (F-TRAINER / R3d-1.1).
 *
 * <ul>
 *     <li>Loads {@code /skycraft/society/trainers.json} for settlement-tier rules, fee bands, named settlement trainers,
 *     and per-culture fallback rosters.</li>
 *     <li>Derives regional trainer specializations and causal {@code why} field IDs from {@link WorldSheets}
 *     ({@code L5}, {@code L7}, {@code L8}, and {@link WorldSheets#whyForJob}).</li>
 *     <li>Provides server-validated skill training: checks {@code skill < cap}, {@code trainingsThisLevel < 5},
 *     deducts the level-scaled gold fee ({@code 50 + level * 15} journeyman, {@code 150 + level * 30} expert,
 *     {@code 400 + level * 50} master), increments {@code trainingsThisLevel}, and calls
 *     {@link Progression#increaseSkill(ServerPlayer, Skill, int)}.</li>
 * </ul>
 */
public final class SocietyTrainers {
    public static final int MAX_TRAININGS_PER_LEVEL = 5;
    public static final int JOURNEYMAN_CAP = 50;
    public static final int EXPERT_CAP = 75;
    public static final int MASTER_CAP = 90;

    public static final String TAG = "skycraft_trainer";
    public static final String SKILL_KEY = "skycraft_trainer_skill";
    public static final String CAP_KEY = "skycraft_trainer_cap";
    public static final String TIER_KEY = "skycraft_trainer_tier";
    public static final String WORKPLACE_KEY = "skycraft_trainer_workplace";

    public enum Tier {
        JOURNEYMAN("journeyman", JOURNEYMAN_CAP, "Journeyman"),
        EXPERT("expert", EXPERT_CAP, "Expert"),
        MASTER("master", MASTER_CAP, "Master");

        public final String id;
        public final int cap;
        public final String title;

        Tier(String id, int cap, String title) {
            this.id = id;
            this.cap = cap;
            this.title = title;
        }

        public static Tier fromCap(int cap) {
            if (cap >= MASTER_CAP) return MASTER;
            if (cap >= EXPERT_CAP) return EXPERT;
            return JOURNEYMAN;
        }

        public static Tier fromId(@Nullable String id) {
            if (id == null) return JOURNEYMAN;
            return switch (id.toLowerCase(Locale.ROOT)) {
                case "master" -> MASTER;
                case "expert" -> EXPERT;
                default -> JOURNEYMAN;
            };
        }
    }

    /** One trainer slot in a settlement's housing and service roster. */
    public record Spec(String name, Skill skill, Tier tier, int cap, String workplace, List<String> why) {
        public String jobId() {
            return "trainer_" + skill.id();
        }
    }

    /** Outcome of a server-validated training attempt. */
    public enum Status {
        TRAINED,
        NOT_A_TRAINER,
        AT_TRAINER_CAP,
        AT_LEVEL_CAP,
        NOT_ENOUGH_GOLD
    }

    public record Result(Status status, Skill skill, int oldLevel, int newLevel, int fee, int usedThisLevel, int trainerCap) {
        public boolean ok() {
            return status == Status.TRAINED;
        }
    }

    private static final Map<String, List<Spec>> SETTLEMENT_SPECS = new LinkedHashMap<>();
    private static final Map<String, List<Spec>> CULTURE_DEFAULTS = new LinkedHashMap<>();
    private static boolean loaded = false;

    private SocietyTrainers() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = SocietyTrainers.class.getResourceAsStream("/skycraft/society/trainers.json")) {
            if (in == null) {
                Skycraft.LOGGER.warn("Missing /skycraft/society/trainers.json");
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject settlements = root.getAsJsonObject("settlements");
            if (settlements != null) {
                for (var entry : settlements.entrySet()) {
                    SETTLEMENT_SPECS.put(entry.getKey(), parseSpecs(entry.getValue().getAsJsonArray()));
                }
            }
            JsonObject cultures = root.getAsJsonObject("culture_defaults");
            if (cultures != null) {
                for (var entry : cultures.entrySet()) {
                    CULTURE_DEFAULTS.put(entry.getKey(), parseSpecs(entry.getValue().getAsJsonArray()));
                }
            }
        } catch (Exception e) {
            Skycraft.LOGGER.error("Failed to load /skycraft/society/trainers.json", e);
        }
    }

    private static List<Spec> parseSpecs(JsonArray arr) {
        List<Spec> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            Skill skill = Skill.byId(obj.has("skill") ? obj.get("skill").getAsString() : "one_handed");
            if (skill == null) skill = Skill.ONE_HANDED;
            int cap = obj.has("cap") ? obj.get("cap").getAsInt() : JOURNEYMAN_CAP;
            Tier tier = obj.has("tier") ? Tier.fromId(obj.get("tier").getAsString()) : Tier.fromCap(cap);
            if (!obj.has("cap")) cap = tier.cap;
            String workplace = obj.has("workplace") ? obj.get("workplace").getAsString() : defaultWorkplaceFor(skill);
            List<String> why = new ArrayList<>();
            if (obj.has("why") && obj.get("why").isJsonArray()) {
                for (JsonElement w : obj.getAsJsonArray("why")) {
                    why.add(w.getAsString());
                }
            }
            out.add(new Spec(name, skill, tier, cap, workplace, Collections.unmodifiableList(why)));
        }
        return Collections.unmodifiableList(out);
    }

    /** Minimum required trainers for a settlement given its key and housing tier (0 capital/city, 1 town, 2 village). */
    public static int targetCount(String settlementId, int settlementTier) {
        ensureLoaded();
        List<Spec> explicit = SETTLEMENT_SPECS.get(settlementId);
        if (explicit != null) return explicit.size();
        Optional<Place> place = Places.byId(settlementId);
        if (place.isPresent()) {
            PlaceType pt = place.get().type();
            if (pt == PlaceType.CAPITAL) return 5;
            if (pt == PlaceType.CITY) return 3;
            if (pt == PlaceType.TOWN) return 1;
            return 0;
        }
        if (settlementTier == 0) return 5;
        if (settlementTier == 1) return 1;
        return 0;
    }

    /**
     * Resolves the trainer roster for a settlement. Uses explicit entries from {@code trainers.json} when defined,
     * otherwise derives distinct skills from the region's {@link WorldSheets} L5/L7/L8 fields and culture defaults.
     * Every returned {@link Spec} has causal {@code why} field IDs populated from {@link WorldSheets} when present.
     */
    public static List<Spec> trainersForSettlement(String settlementId, int settlementTier, @Nullable String regionId) {
        ensureLoaded();
        Optional<Place> place = Places.byId(settlementId);
        String resolvedRegionId = (regionId == null || regionId.isBlank())
                ? place.map(Place::regionId).orElse("skyrim/whiterun")
                : regionId;
        regionId = resolvedRegionId;
        String culture = place.map(Place::culture).orElseGet(() -> {
            Region reg = Regions.byId(resolvedRegionId).orElse(null);
            return reg != null ? reg.culture() : "nord";
        });

        List<Spec> explicit = SETTLEMENT_SPECS.get(settlementId);
        if (explicit != null) {
            List<Spec> enriched = new ArrayList<>(explicit.size());
            for (Spec s : explicit) {
                enriched.add(new Spec(s.name(), s.skill(), s.tier(), s.cap(), s.workplace(),
                        whyForTrainer(regionId, s.skill(), s.why())));
            }
            return Collections.unmodifiableList(enriched);
        }

        int want = targetCount(settlementId, settlementTier);
        if (want <= 0) return List.of();

        List<Spec> defaults = CULTURE_DEFAULTS.getOrDefault(culture, CULTURE_DEFAULTS.getOrDefault("nord", List.of()));
        List<Skill> sheetSkills = skillsFromWorldSheet(regionId);
        EnumSet<Skill> picked = EnumSet.noneOf(Skill.class);
        List<Spec> result = new ArrayList<>(want);

        // 1. Pull skills suggested by the region's WorldSheets L5/L7/L8 fields first
        for (Skill sk : sheetSkills) {
            if (result.size() >= want) break;
            if (!picked.add(sk)) continue;
            Spec match = findDefaultSpec(defaults, sk, result.size(), settlementTier);
            result.add(new Spec(match.name(), sk, match.tier(), match.cap(), match.workplace(),
                    whyForTrainer(regionId, sk, match.why())));
        }

        // 2. Fill remaining slots from the culture defaults so all skills are distinct
        for (Spec d : defaults) {
            if (result.size() >= want) break;
            if (!picked.add(d.skill())) continue;
            Tier tier = settlementTier == 0 ? d.tier() : Tier.JOURNEYMAN;
            int cap = tier.cap;
            result.add(new Spec(d.name(), d.skill(), tier, cap, d.workplace(),
                    whyForTrainer(regionId, d.skill(), d.why())));
        }

        // 3. Fallback across all skills if still short
        for (Skill sk : Skill.VALUES) {
            if (result.size() >= want) break;
            if (!picked.add(sk)) continue;
            Tier tier = settlementTier == 0 && result.isEmpty() ? Tier.EXPERT : Tier.JOURNEYMAN;
            result.add(new Spec("", sk, tier, tier.cap, defaultWorkplaceFor(sk),
                    whyForTrainer(regionId, sk, List.of())));
        }
        return Collections.unmodifiableList(result);
    }

    private static Spec findDefaultSpec(List<Spec> defaults, Skill skill, int index, int settlementTier) {
        for (Spec d : defaults) {
            if (d.skill() == skill) {
                Tier tier = settlementTier == 0 ? d.tier() : Tier.JOURNEYMAN;
                return new Spec(d.name(), skill, tier, tier.cap, d.workplace(), d.why());
            }
        }
        Tier tier = settlementTier == 0 ? (index == 0 ? Tier.MASTER : index <= 2 ? Tier.EXPERT : Tier.JOURNEYMAN) : Tier.JOURNEYMAN;
        return new Spec("", skill, tier, tier.cap, defaultWorkplaceFor(skill), List.of());
    }

    /**
     * Derives candidate trainer skills from a region's {@link WorldSheets} L5 (resources/trade),
     * L7 (architecture/infrastructure), and L8 (narrative/factions) fields.
     */
    public static List<Skill> skillsFromWorldSheet(@Nullable String regionId) {
        if (regionId == null || regionId.isBlank()) return List.of();
        List<Skill> out = new ArrayList<>();
        for (String layer : new String[]{"L5", "L7", "L8"}) {
            for (WorldSheets.Field f : WorldSheets.fieldsIn(regionId, layer)) {
                String text = (f.id() + " " + f.text()).toLowerCase(Locale.ROOT);
                if (text.contains("forge") || text.contains("smith") || text.contains("iron") || text.contains("silver") || text.contains("metal")) {
                    addUnique(out, Skill.SMITHING);
                }
                if (text.contains("herb") || text.contains("alchem") || text.contains("apothec") || text.contains("marsh") || text.contains("spore")) {
                    addUnique(out, Skill.ALCHEMY);
                }
                if (text.contains("college") || text.contains("mage") || text.contains("arcane") || text.contains("crystal") || text.contains("telvanni")) {
                    addUnique(out, Skill.DESTRUCTION);
                    addUnique(out, Skill.ENCHANTING);
                }
                if (text.contains("temple") || text.contains("shrine") || text.contains("priest") || text.contains("crypt") || text.contains("arkay")) {
                    addUnique(out, Skill.RESTORATION);
                }
                if (text.contains("thief") || text.contains("smuggl") || text.contains("shadow") || text.contains("canal") || text.contains("undervault")) {
                    addUnique(out, Skill.SNEAK);
                    addUnique(out, Skill.LOCKPICKING);
                }
                if (text.contains("port") || text.contains("market") || text.contains("caravan") || text.contains("trade") || text.contains("wine")) {
                    addUnique(out, Skill.SPEECH);
                }
                if (text.contains("hunt") || text.contains("timber") || text.contains("forest") || text.contains("bow") || text.contains("pelt")) {
                    addUnique(out, Skill.ARCHERY);
                    addUnique(out, Skill.LIGHT_ARMOR);
                }
                if (text.contains("garrison") || text.contains("legion") || text.contains("warrior") || text.contains("citadel") || text.contains("guard")) {
                    addUnique(out, Skill.ONE_HANDED);
                    addUnique(out, Skill.HEAVY_ARMOR);
                    addUnique(out, Skill.BLOCK);
                }
            }
        }
        return out;
    }

    private static void addUnique(List<Skill> list, Skill s) {
        if (!list.contains(s)) list.add(s);
    }

    /**
     * Resolves the causal world-sheet {@code why} field IDs for a trainer in {@code regionId}, combining
     * {@link WorldSheets#whyForJob} and the region's {@code L5}/{@code L7}/{@code L8} fields.
     */
    public static List<String> whyForTrainer(@Nullable String regionId, @Nullable Skill skill, List<String> fallback) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (regionId != null && !regionId.isBlank()) {
            ids.addAll(WorldSheets.whyForJob(regionId, "guild_trainer"));
            if (skill != null) {
                ids.addAll(WorldSheets.whyForJob(regionId, defaultWorkplaceFor(skill)));
            }
            for (String layer : new String[]{"L5", "L7", "L8"}) {
                List<WorldSheets.Field> fields = WorldSheets.fieldsIn(regionId, layer);
                if (!fields.isEmpty()) {
                    ids.add(fields.get(0).globalId());
                }
            }
        }
        if (fallback != null) ids.addAll(fallback);
        return List.copyOf(ids);
    }

    public static List<String> whyForTrainerJob(@Nullable String regionId, String job) {
        Skill skill = skillFromJobId(job);
        return whyForTrainer(regionId, skill, List.of());
    }

    @Nullable
    public static Skill skillFromJobId(@Nullable String job) {
        if (job == null) return null;
        if (job.startsWith("trainer_")) {
            return Skill.byId(job.substring("trainer_".length()));
        }
        return Skill.byId(job);
    }

    public static String defaultWorkplaceFor(Skill skill) {
        return switch (skill) {
            case SMITHING, HEAVY_ARMOR, TWO_HANDED, BLOCK, MINING, WOODCUTTING -> "smith";
            case ALCHEMY -> "alchemist";
            case ENCHANTING, DESTRUCTION, ALTERATION, CONJURATION, ILLUSION, MYSTICISM -> "wizard";
            case RESTORATION -> "priest";
            case SPEECH, PICKPOCKET, SNEAK, LOCKPICKING, COOKING -> "inn";
            case ARCHERY, LIGHT_ARMOR, HUNTING, ACROBATICS, ATHLETICS, FISHING, RIDING -> "hunter";
            case ONE_HANDED, POLEARM, UNARMED -> "guard";
            default -> "inn";
        };
    }

    /**
     * Gold fee to train one level from {@code currentSkillLevel} -> {@code currentSkillLevel + 1}:
     * <ul>
     *     <li>Journeyman band ({@code level < 50}): {@code 50 + level * 15}</li>
     *     <li>Expert band ({@code 50 <= level < 75}): {@code 150 + level * 30}</li>
     *     <li>Master band ({@code level >= 75}): {@code 400 + level * 50}</li>
     * </ul>
     */
    public static int feeFor(int currentSkillLevel) {
        int lvl = Math.max(1, currentSkillLevel);
        if (lvl < JOURNEYMAN_CAP) return 50 + lvl * 15;
        if (lvl < EXPERT_CAP) return 150 + lvl * 30;
        return 400 + lvl * 50;
    }

    public static int feeFor(int currentSkillLevel, int trainerCap) {
        return feeFor(currentSkillLevel);
    }

    /** True if {@code entity} is a society or guild skill trainer. */
    public static boolean isTrainer(LivingEntity entity) {
        if (entity == null) return false;
        if (entity instanceof NpcEntity npc && npc.role() == NpcRole.TRAINER) return true;
        CompoundTag pd = entity.getPersistentData();
        return pd.getBoolean(TAG) || pd.getBoolean("skycraft_guild_trainer") || pd.contains(SKILL_KEY);
    }

    /** Skills this trainer can teach. */
    public static List<Skill> skillsOf(LivingEntity entity) {
        if (entity == null) return List.of();
        CompoundTag pd = entity.getPersistentData();
        String single = pd.getString(SKILL_KEY);
        if (!single.isEmpty()) {
            Skill s = Skill.byId(single);
            if (s != null) return List.of(s);
        }
        String multi = pd.getString("skycraft_train_skills");
        if (!multi.isEmpty()) {
            List<Skill> out = new ArrayList<>();
            for (String part : multi.split(",")) {
                Skill s = Skill.byId(part.trim());
                if (s != null && !out.contains(s)) out.add(s);
            }
            if (!out.isEmpty()) return out;
        }
        if (entity instanceof NpcEntity npc && npc.role() == NpcRole.TRAINER) {
            Skill fallback = Skill.VALUES[Math.floorMod(npc.getUUID().hashCode(), Skill.VALUES.length)];
            return List.of(fallback);
        }
        return List.of();
    }

    /** Maximum skill level this trainer can teach ({@code 50} Journeyman, {@code 75} Expert, {@code 90} Master). */
    public static int capOf(LivingEntity entity) {
        if (entity == null) return JOURNEYMAN_CAP;
        CompoundTag pd = entity.getPersistentData();
        int cap = pd.getInt(CAP_KEY);
        if (cap > 0) return cap;
        if (pd.getBoolean("skycraft_guild_trainer")) return EXPERT_CAP;
        return JOURNEYMAN_CAP;
    }

    public static Tier tierOf(LivingEntity entity) {
        return Tier.fromCap(capOf(entity));
    }

    /** Configures an {@link NpcEntity} as a trainer with the given {@link Spec}. */
    public static void configureTrainer(NpcEntity npc, Spec spec) {
        CompoundTag pd = npc.getPersistentData();
        pd.putBoolean(TAG, true);
        pd.putString(SKILL_KEY, spec.skill().id());
        pd.putInt(CAP_KEY, spec.cap());
        pd.putString(TIER_KEY, spec.tier().id);
        pd.putString(WORKPLACE_KEY, spec.workplace());
        if (!spec.why().isEmpty()) {
            pd.putString(Housing.WHY_KEY, String.join(",", spec.why()));
        }
        String baseName = spec.name() != null && !spec.name().isBlank()
                ? spec.name()
                : NpcNames.generate(npc.race(), npc.isFemale(), npc.getRandom());
        npc.setCustomName(Component.translatable("society.skycraft.npc_name.trainer_skill",
                baseName, spec.tier().title, spec.skill().displayName()));
    }

    /** Spawns a settled trainer NPC for a settlement's housing plan. */
    @Nullable
    public static NpcEntity spawnSettledTrainer(ServerLevel level, BlockPos at, String settlementKey, int tier,
                                                @Nullable String regionId, String jobId, int ordinal) {
        List<Spec> specs = trainersForSettlement(settlementKey, tier, regionId);
        Skill wanted = skillFromJobId(jobId);
        Spec chosen = null;
        if (wanted != null) {
            for (Spec s : specs) {
                if (s.skill() == wanted) {
                    chosen = s;
                    break;
                }
            }
        }
        if (chosen == null && !specs.isEmpty()) {
            chosen = specs.get(Math.floorMod(ordinal, specs.size()));
        }
        if (chosen == null) {
            Skill sk = wanted != null ? wanted : Skill.ONE_HANDED;
            chosen = new Spec("", sk, Tier.JOURNEYMAN, JOURNEYMAN_CAP, defaultWorkplaceFor(sk),
                    whyForTrainer(regionId, sk, List.of()));
        }
        NpcEntity npc = Npcs.create(level, at, NpcRole.TRAINER);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        configureTrainer(npc, chosen);
        npc.setHome(at, 24);
        npc.getPersistentData().putBoolean("skycraft_settler", true);
        return level.addFreshEntity(npc) ? npc : null;
    }

    /**
     * Server-validated skill training:
     * <ul>
     *     <li>verifies {@code trainer} teaches {@code skill}</li>
     *     <li>checks {@code currentSkill < cap}</li>
     *     <li>checks {@code trainingsThisLevel < 5}</li>
     *     <li>deducts gold fee scaling with skill level ({@link #feeFor(int, int)})</li>
     *     <li>increments {@code trainingsThisLevel} and raises skill by 1 via {@link Progression#increaseSkill}</li>
     * </ul>
     */
    public static Result train(ServerPlayer player, LivingEntity trainer, Skill skill) {
        List<Skill> skills = skillsOf(trainer);
        if (!isTrainer(trainer) || skill == null || !skills.contains(skill)) {
            return new Result(Status.NOT_A_TRAINER, skill, 0, 0, 0, 0, 0);
        }
        PlayerData data = SkyData.get(player);
        int current = data.getSkill(skill);
        int cap = capOf(trainer);
        int used = data.getTrainingsThisLevel();
        int fee = feeFor(current, cap);

        if (current >= cap || current >= Skill.MAX_LEVEL) {
            return new Result(Status.AT_TRAINER_CAP, skill, current, current, fee, used, cap);
        }
        if (used >= MAX_TRAININGS_PER_LEVEL) {
            return new Result(Status.AT_LEVEL_CAP, skill, current, current, fee, used, cap);
        }
        if (!Currency.take(player, fee)) {
            return new Result(Status.NOT_ENOUGH_GOLD, skill, current, current, fee, used, cap);
        }

        int nextUsed = used + 1;
        data.setTrainingsThisLevel(nextUsed);
        Progression.increaseSkill(player, skill, 1);
        int updatedLevel = data.getSkill(skill);
        return new Result(Status.TRAINED, skill, current, updatedLevel, fee, data.getTrainingsThisLevel(), cap);
    }

    /** Convenience overload that trains the trainer's primary skill. */
    public static Result train(ServerPlayer player, LivingEntity trainer) {
        List<Skill> skills = skillsOf(trainer);
        Skill primary = skills.isEmpty() ? null : skills.get(0);
        return train(player, trainer, primary);
    }

    /** Formats the dialogue option label: {@code "Train <Skill> (<current> -> <next>, <fee>g) [<used>/5 this level]"}. */
    public static Component formatOptionLabel(ServerPlayer player, LivingEntity trainer, Skill skill) {
        PlayerData data = SkyData.get(player);
        int current = data.getSkill(skill);
        int next = Math.min(Skill.MAX_LEVEL, current + 1);
        int cap = capOf(trainer);
        int fee = feeFor(current, cap);
        int used = data.getTrainingsThisLevel();
        return Component.translatable("dialogue.skycraft.society.train_option",
                skill.displayName(), current, next, fee, used, MAX_TRAININGS_PER_LEVEL);
    }

    /** Adds server-validated training options to the dialogue menu for any trainer NPC. */
    public static void addDialogueOptions(ServerPlayer player, LivingEntity entity, List<DialogueOption> out) {
        if (!isTrainer(entity)) return;
        List<Skill> skills = skillsOf(entity);
        int priority = 12;
        for (Skill skill : skills) {
            Component label = NpcDialogue.serviceLabel(formatOptionLabel(player, entity, skill));
            out.add(new DialogueOption("society.train." + skill.id(), label, priority++, (p, n) -> {
                Result res = train(p, n, skill);
                switch (res.status()) {
                    case TRAINED -> {
                        Notifier.message(p, Component.translatable("society.skycraft.train.success",
                                skill.displayName(), res.newLevel(), res.fee(), res.usedThisLevel(), MAX_TRAININGS_PER_LEVEL));
                        Dialogue.open(p, n, Component.translatable("society.skycraft.say.train.done",
                                skill.displayName(), res.newLevel()));
                    }
                    case AT_TRAINER_CAP -> {
                        Notifier.message(p, Component.translatable("society.skycraft.train.at_cap",
                                skill.displayName(), res.trainerCap()));
                        Dialogue.open(p, n, Component.translatable("society.skycraft.say.train.at_cap",
                                skill.displayName(), res.trainerCap()));
                    }
                    case AT_LEVEL_CAP -> {
                        Notifier.message(p, Component.translatable("society.skycraft.train.level_cap",
                                MAX_TRAININGS_PER_LEVEL));
                        Dialogue.open(p, n, Component.translatable("society.skycraft.say.train.level_cap",
                                MAX_TRAININGS_PER_LEVEL));
                    }
                    case NOT_ENOUGH_GOLD -> {
                        Notifier.message(p, Component.translatable("message.skycraft.not_enough_gold"));
                        Dialogue.open(p, n, Component.translatable("society.skycraft.say.train.no_gold", res.fee()));
                    }
                    default -> {
                    }
                }
            }));
        }
    }
}
