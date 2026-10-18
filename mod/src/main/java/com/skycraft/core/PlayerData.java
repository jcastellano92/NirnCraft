package com.skycraft.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * All persistent RPG state of a player. Lives in a NeoForge attachment on both sides; owner and appearance
 * packets populate the appropriate client copies.
 *
 * <p>Feature modules that need their own persistent per-player state store it in a named sub-tag obtained
 * through {@link #module(String)} and call {@link #markDirty()} after changing it so it gets synced.</p>
 *
 * <p>Sync is split: {@link #markDirty()} (full, owner only, coalesced), {@link #markProgressDirty()} (small
 * progress packet, throttled) and {@link #markAppearanceDirty()} (race/sheathe/apparel, sent to tracking players).</p>
 */
public class PlayerData {
    /** The attachment keeps the established RPG/module NBT inside one versioned storage field. */
    public static final com.mojang.serialization.MapCodec<PlayerData> CODEC = CompoundTag.CODEC.fieldOf("data")
            .xmap(tag -> {
                PlayerData data = new PlayerData();
                data.load(tag);
                return data;
            }, PlayerData::save);

    public static final int BASE_HEALTH = 100;
    public static final int BASE_MAGICKA = 100;
    public static final int BASE_STAMINA = 100;
    public static final int ATTRIBUTE_STEP = 10;

    private final Map<Skill, Integer> skillLevels = new EnumMap<>(Skill.class);
    private final Map<Skill, Float> skillXp = new EnumMap<>(Skill.class);
    private final Map<Skill, Integer> legendary = new EnumMap<>(Skill.class);
    private final Map<String, Integer> perks = new HashMap<>();
    private final Map<String, Integer> stats = new HashMap<>();
    private final Map<String, CompoundTag> modules = new HashMap<>();

    private int level = 1;
    private float levelXp = 0;
    private int perkPoints = 0;
    private int pendingLevelUps = 0;
    private int healthPoints = 0;
    private int magickaPoints = 0;
    private int staminaPoints = 0;
    private float magicka = BASE_MAGICKA;
    private float stamina = BASE_STAMINA;
    private long gold = 0;
    private Race race = null;
    private long powerReadyAt = 0;
    private int trainingsThisLevel = 0;

    // transient (not saved)
    private transient long lastCombatTick = -1000;
    private transient long lastStaminaUseTick = -1000;
    private transient long lastMagickaUseTick = -1000;
    private transient boolean dirty = true;
    private transient boolean progressDirty = true;
    private transient boolean appearanceDirty = true;
    private transient boolean vitalsDirty = true;
    /** Server tick of the last full sync, used to coalesce bursts of {@link #markDirty()}. */
    private transient int lastFullSync = Integer.MIN_VALUE / 2;
    /** Hash of the last appearance sent to tracking players. */
    private transient int lastAppearanceHash;

    public PlayerData() {
        for (Skill s : Skill.VALUES) {
            skillLevels.put(s, Skill.BASE_LEVEL);
            skillXp.put(s, 0f);
            legendary.put(s, 0);
        }
    }

    // ------------------------------------------------------------------ skills

    public int getSkill(Skill skill) {
        return skillLevels.getOrDefault(skill, Skill.BASE_LEVEL);
    }

    public void setSkill(Skill skill, int level) {
        skillLevels.put(skill, Math.max(0, Math.min(Skill.MAX_LEVEL, level)));
        markProgressDirty();
    }

    public float getSkillXp(Skill skill) {
        return skillXp.getOrDefault(skill, 0f);
    }

    /** Raw setter. Award XP through {@code Progression.addSkillXp} so rates, perks and level-ups apply. */
    public void setSkillXp(Skill skill, float xp) {
        skillXp.put(skill, Math.max(0, xp));
        markProgressDirty();
    }

    /** Progress 0..1 towards the next skill level. */
    public float skillProgress(Skill skill) {
        int lvl = getSkill(skill);
        if (lvl >= Skill.MAX_LEVEL) return 1f;
        return Math.min(1f, getSkillXp(skill) / skill.xpToNext(lvl));
    }

    public int getLegendary(Skill skill) {
        return legendary.getOrDefault(skill, 0);
    }

    public void setLegendary(Skill skill, int times) {
        legendary.put(skill, times);
        markProgressDirty();
    }

    // ------------------------------------------------------------------ character level

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(1, level);
        markProgressDirty();
    }

    public float getLevelXp() {
        return levelXp;
    }

    public void setLevelXp(float xp) {
        this.levelXp = Math.max(0, xp);
        markProgressDirty();
    }

    /** Skyrim-style linear ramp: easier at early levels (35 XP at level 1) and +25 XP more for each subsequent level. */
    public static float levelXpToNext(int level) {
        return 35f + (Math.max(1, level) - 1) * 25f;
    }

    public float levelProgress() {
        return Math.min(1f, levelXp / levelXpToNext(level));
    }

    public int getPerkPoints() {
        return perkPoints;
    }

    public void setPerkPoints(int points) {
        this.perkPoints = Math.max(0, points);
        markProgressDirty();
    }

    public int getPendingLevelUps() {
        return pendingLevelUps;
    }

    public void setPendingLevelUps(int n) {
        this.pendingLevelUps = Math.max(0, n);
        markProgressDirty();
    }

    public int getTrainingsThisLevel() {
        return trainingsThisLevel;
    }

    public void setTrainingsThisLevel(int n) {
        this.trainingsThisLevel = n;
        markProgressDirty();
    }

    // ------------------------------------------------------------------ perks

    public int getPerkRank(String perkId) {
        return perks.getOrDefault(perkId, 0);
    }

    public boolean hasPerk(String perkId) {
        return getPerkRank(perkId) > 0;
    }

    public void setPerkRank(String perkId, int rank) {
        if (rank <= 0) perks.remove(perkId);
        else perks.put(perkId, rank);
        markDirty();
    }

    public Map<String, Integer> perks() {
        return perks;
    }

    // ------------------------------------------------------------------ attributes / vitals

    public int getHealthPoints() {
        return healthPoints;
    }

    public int getMagickaPoints() {
        return magickaPoints;
    }

    public int getStaminaPoints() {
        return staminaPoints;
    }

    public void addAttributePoint(int which) {
        switch (which) {
            case 0 -> healthPoints++;
            case 1 -> magickaPoints++;
            default -> staminaPoints++;
        }
        markProgressDirty();
        markVitalsDirty();
    }

    /** Skyrim-scale max health (100 base). One Minecraft half-heart is 5 Skyrim health. */
    public int maxHealth() {
        return BASE_HEALTH + healthPoints * ATTRIBUTE_STEP + (race == null ? 0 : race.bonusHealth) + (int) module("bonus").getFloatOr("health", 0f);
    }

    public float maxMagicka() {
        int race = this.race == null ? 0 : this.race.bonusMagicka;
        return BASE_MAGICKA + magickaPoints * ATTRIBUTE_STEP + race + module("bonus").getFloatOr("magicka", 0f);
    }

    public float maxStamina() {
        return BASE_STAMINA + staminaPoints * ATTRIBUTE_STEP + (race == null ? 0 : race.bonusStamina) + module("bonus").getFloatOr("stamina", 0f);
    }

    public float getMagicka() {
        return magicka;
    }

    public void setMagicka(float value) {
        float v = Math.max(0, Math.min(maxMagicka(), value));
        if (v != magicka) {
            magicka = v;
            markVitalsDirty();
        }
    }

    public float getStamina() {
        return stamina;
    }

    public void setStamina(float value) {
        float v = Math.max(0, Math.min(maxStamina(), value));
        if (v != stamina) {
            stamina = v;
            markVitalsDirty();
        }
    }

    public long getLastCombatTick() {
        return lastCombatTick;
    }

    public void setLastCombatTick(long tick) {
        this.lastCombatTick = tick;
    }

    public long getLastStaminaUseTick() {
        return lastStaminaUseTick;
    }

    public void setLastStaminaUseTick(long tick) {
        this.lastStaminaUseTick = tick;
    }

    public long getLastMagickaUseTick() {
        return lastMagickaUseTick;
    }

    public void setLastMagickaUseTick(long tick) {
        this.lastMagickaUseTick = tick;
    }

    // ------------------------------------------------------------------ gold / race / power

    public long getGold() {
        return gold;
    }

    public void setGold(long gold) {
        this.gold = Math.max(0, gold);
        markProgressDirty();
    }

    public Race getRace() {
        return race;
    }

    public void setRace(Race race) {
        this.race = race;
        markDirty();
        markVitalsDirty();
        markAppearanceDirty();
    }

    public long getPowerReadyAt() {
        return powerReadyAt;
    }

    public void setPowerReadyAt(long gameTime) {
        this.powerReadyAt = gameTime;
        markProgressDirty();
    }

    // ------------------------------------------------------------------ stats

    public int getStat(String key) {
        return stats.getOrDefault(key, 0);
    }

    public void addStat(String key, int amount) {
        stats.merge(key, amount, Integer::sum);
        markProgressDirty();
    }

    public Map<String, Integer> stats() {
        return stats;
    }

    // ------------------------------------------------------------------ module storage

    /** Mutable per-module storage. Remember to call {@link #markDirty()} after modifying it. */
    public CompoundTag module(String name) {
        return modules.computeIfAbsent(name, k -> new CompoundTag());
    }

    // ------------------------------------------------------------------ dirty tracking

    /**
     * Structural change (perks, race, quests, module storage...): the whole player data is re-sent to the owner.
     * Bursts are coalesced (at most a couple of full syncs per second). Skill/level/gold/stat setters only mark
     * the small progress packet, see {@link #markProgressDirty()}.
     */
    public void markDirty() {
        dirty = true;
        appearanceDirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        if (d) progressDirty = false; // a full sync carries the progress too
        return d;
    }

    /** Skill/level/attribute/gold/stat change: only the small progress packet is sent (throttled). */
    public void markProgressDirty() {
        progressDirty = true;
    }

    public boolean consumeProgressDirty() {
        boolean d = progressDirty;
        progressDirty = false;
        return d;
    }

    /** Race, sheathe state or worn apparel changed: tracking players are told (see {@link #appearance()}). */
    public void markAppearanceDirty() {
        appearanceDirty = true;
    }

    public boolean consumeAppearanceDirty() {
        boolean d = appearanceDirty;
        appearanceDirty = false;
        return d;
    }

    public int getLastFullSync() {
        return lastFullSync;
    }

    public void setLastFullSync(int serverTick) {
        this.lastFullSync = serverTick;
    }

    /** Returns true (and remembers it) if this appearance differs from the last one sent. */
    public boolean appearanceChanged(CompoundTag appearance) {
        int hash = appearance.hashCode();
        if (hash == lastAppearanceHash) return false;
        lastAppearanceHash = hash;
        return true;
    }

    /** Forces the next {@link #appearanceChanged} to report a change (e.g. after a respawn). */
    public void resetAppearanceHash() {
        lastAppearanceHash = 0;
        appearanceDirty = true;
    }

    public void markVitalsDirty() {
        vitalsDirty = true;
    }

    public boolean consumeVitalsDirty() {
        boolean d = vitalsDirty;
        vitalsDirty = false;
        return d;
    }

    // ------------------------------------------------------------------ serialization

    /** The small, frequently changing part: skills, level, attribute pools, perk points, gold and stats. */
    public CompoundTag saveProgress() {
        CompoundTag tag = new CompoundTag();
        int n = Skill.VALUES.length;
        int[] lvl = new int[n];
        int[] leg = new int[n];
        int[] xp = new int[n];
        for (int i = 0; i < n; i++) {
            Skill s = Skill.VALUES[i];
            lvl[i] = getSkill(s);
            leg[i] = getLegendary(s);
            xp[i] = Float.floatToIntBits(getSkillXp(s));
        }
        tag.putIntArray("lvl", lvl);
        tag.putIntArray("leg", leg);
        tag.putIntArray("xp", xp);
        tag.putInt("level", level);
        tag.putFloat("levelXp", levelXp);
        tag.putInt("perkPoints", perkPoints);
        tag.putInt("pendingLevelUps", pendingLevelUps);
        tag.putInt("hp", healthPoints);
        tag.putInt("mp", magickaPoints);
        tag.putInt("sp", staminaPoints);
        tag.putLong("gold", gold);
        tag.putLong("powerReadyAt", powerReadyAt);
        tag.putInt("trainings", trainingsThisLevel);
        CompoundTag statTag = new CompoundTag();
        stats.forEach(statTag::putInt);
        tag.put("stats", statTag);
        return tag;
    }

    /** Client side: applies a {@link #saveProgress()} tag. */
    public void loadProgress(CompoundTag tag) {
        int[] lvl = tag.getIntArray("lvl").orElseGet(() -> new int[0]);
        int[] leg = tag.getIntArray("leg").orElseGet(() -> new int[0]);
        int[] xp = tag.getIntArray("xp").orElseGet(() -> new int[0]);
        for (int i = 0; i < Skill.VALUES.length; i++) {
            Skill s = Skill.VALUES[i];
            if (i < lvl.length) skillLevels.put(s, lvl[i]);
            if (i < leg.length) legendary.put(s, leg[i]);
            if (i < xp.length) skillXp.put(s, Float.intBitsToFloat(xp[i]));
        }
        level = Math.max(1, tag.getIntOr("level", 0));
        levelXp = tag.getFloatOr("levelXp", 0f);
        perkPoints = tag.getIntOr("perkPoints", 0);
        pendingLevelUps = tag.getIntOr("pendingLevelUps", 0);
        healthPoints = tag.getIntOr("hp", 0);
        magickaPoints = tag.getIntOr("mp", 0);
        staminaPoints = tag.getIntOr("sp", 0);
        gold = tag.getLongOr("gold", 0L);
        powerReadyAt = tag.getLongOr("powerReadyAt", 0L);
        trainingsThisLevel = tag.getIntOr("trainings", 0);
        stats.clear();
        CompoundTag statTag = tag.getCompoundOrEmpty("stats");
        for (String k : statTag.keySet()) stats.put(k, statTag.getIntOr(k, 0));
    }

    /** What other players need to render this player: race, sheathe state and worn apparel. */
    public CompoundTag appearance() {
        CompoundTag tag = new CompoundTag();
        if (race != null) tag.putString("race", race.id());
        // the chosen face, hair and body (society.NpcLook packing; see client.RaceSkins)
        CompoundTag look = modules.get("race");
        if (look != null && look.contains("look")) tag.putInt("look", look.getIntOr("look", 0));
        tag.putBoolean("sheathed", module("combat").getBooleanOr("sheathed", false));
        CompoundTag apparel = modules.get("apparel");
        if (apparel != null && !apparel.isEmpty()) tag.put("apparel", apparel.copy());
        return tag;
    }

    /** Client side: applies an {@link #appearance()} tag to another player's copy of the data. */
    public void loadAppearance(CompoundTag tag) {
        race = tag.contains("race") ? Race.byId(tag.getStringOr("race", "")) : null;
        if (tag.contains("look")) module("race").putInt("look", tag.getIntOr("look", 0));
        else module("race").remove("look");
        module("combat").putBoolean("sheathed", tag.getBooleanOr("sheathed", false));
        if (tag.get("apparel") instanceof CompoundTag) modules.put("apparel", tag.getCompoundOrEmpty("apparel").copy());
        else modules.remove("apparel");
    }

    public CompoundTag save() {
        return saveExcludingModules(java.util.List.of());
    }

    /** Full persistence shape, without copying modules carried by separate sync channels. */
    public CompoundTag saveExcludingModules(java.util.List<String> excluded) {
        CompoundTag tag = new CompoundTag();
        CompoundTag skills = new CompoundTag();
        for (Skill s : Skill.VALUES) {
            CompoundTag st = new CompoundTag();
            st.putInt("lvl", getSkill(s));
            st.putFloat("xp", getSkillXp(s));
            st.putInt("legendary", getLegendary(s));
            skills.put(s.id(), st);
        }
        tag.put("skills", skills);
        CompoundTag perkTag = new CompoundTag();
        perks.forEach(perkTag::putInt);
        tag.put("perks", perkTag);
        CompoundTag statTag = new CompoundTag();
        stats.forEach(statTag::putInt);
        tag.put("stats", statTag);
        CompoundTag moduleTag = new CompoundTag();
        modules.forEach((k, v) -> {
            if (!excluded.contains(k)) moduleTag.put(k, v.copy());
        });
        tag.put("modules", moduleTag);
        ListTag omitted = new ListTag();
        for (String key : excluded) {
            if (modules.containsKey(key)) omitted.add(net.minecraft.nbt.StringTag.valueOf(key));
        }
        if (!omitted.isEmpty()) tag.put("omitted", omitted);

        tag.putInt("level", level);
        tag.putFloat("levelXp", levelXp);
        tag.putInt("perkPoints", perkPoints);
        tag.putInt("pendingLevelUps", pendingLevelUps);
        tag.putInt("hp", healthPoints);
        tag.putInt("mp", magickaPoints);
        tag.putInt("sp", staminaPoints);
        tag.putFloat("magicka", magicka);
        tag.putFloat("stamina", stamina);
        tag.putLong("gold", gold);
        if (race != null) tag.putString("race", race.id());
        tag.putLong("powerReadyAt", powerReadyAt);
        tag.putInt("trainings", trainingsThisLevel);
        return tag;
    }

    public void load(CompoundTag tag) {
        CompoundTag skills = tag.getCompoundOrEmpty("skills");
        for (Skill s : Skill.VALUES) {
            if (skills.get(s.id()) instanceof CompoundTag) {
                CompoundTag st = skills.getCompoundOrEmpty(s.id());
                skillLevels.put(s, st.getIntOr("lvl", 0));
                skillXp.put(s, st.getFloatOr("xp", 0f));
                legendary.put(s, st.getIntOr("legendary", 0));
            }
        }
        perks.clear();
        CompoundTag perkTag = tag.getCompoundOrEmpty("perks");
        for (String k : perkTag.keySet()) perks.put(k, perkTag.getIntOr(k, 0));
        stats.clear();
        CompoundTag statTag = tag.getCompoundOrEmpty("stats");
        for (String k : statTag.keySet()) stats.put(k, statTag.getIntOr(k, 0));
        // A full sync that had to leave oversized modules out lists them in "omitted": keep the copy we have.
        java.util.Set<String> omitted = new java.util.HashSet<>();
        ListTag omittedTag = tag.getListOrEmpty("omitted");
        for (int i = 0; i < omittedTag.size(); i++) omitted.add(omittedTag.getStringOr(i, ""));
        modules.keySet().removeIf(k -> !omitted.contains(k));
        CompoundTag moduleTag = tag.getCompoundOrEmpty("modules");
        for (String k : moduleTag.keySet()) modules.put(k, moduleTag.getCompoundOrEmpty(k).copy());

        level = Math.max(1, tag.getIntOr("level", 0));
        levelXp = tag.getFloatOr("levelXp", 0f);
        perkPoints = tag.getIntOr("perkPoints", 0);
        pendingLevelUps = tag.getIntOr("pendingLevelUps", 0);
        healthPoints = tag.getIntOr("hp", 0);
        magickaPoints = tag.getIntOr("mp", 0);
        staminaPoints = tag.getIntOr("sp", 0);
        magicka = tag.getFloatOr("magicka", 0f);
        stamina = tag.getFloatOr("stamina", 0f);
        gold = tag.getLongOr("gold", 0L);
        race = tag.contains("race") ? Race.byId(tag.getStringOr("race", "")) : null;
        powerReadyAt = tag.getLongOr("powerReadyAt", 0L);
        trainingsThisLevel = tag.getIntOr("trainings", 0);
        markDirty();
        markProgressDirty();
        markVitalsDirty();
    }

    public void copyFrom(PlayerData other) {
        load(other.save());
    }
}
