package com.nirncraft.quest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One step of a {@link Quest}. Objectives of a quest are completed in order; the first one that isn't done is the
 * "current" objective (shown on the compass, checked for progress).
 */
public class Objective {
    public enum Type {
        /** Kill one specific (usually spawned) entity; {@link #target} holds its UUID once spawned. */
        KILL_TARGET,
        /** Kill {@link #required} entities whose type matches {@link #target} (comma separated ids / #tags), optionally in an area. */
        KILL_TYPE_COUNT,
        /** Kill {@link #required} hostile mobs within {@link #radius} of {@link #pos}. */
        CLEAR_AREA,
        /** Bring {@link #required} of item {@link #target} to the NPC ({@link #npc} or {@link #npcFilter}). */
        FETCH,
        /** Reach {@link #pos} (within {@link #radius}) in {@link #dim}; without a position, just enter the dimension. */
        GO_TO,
        /** Talk to the NPC {@link #npc} (or any NPC matching {@link #npcFilter} near {@link #pos}) using topic {@link #topic}. */
        TALK_TO,
        /** A condition checked every second, e.g. {@code word} (learned a Word of Power) or {@code crime:items_stolen}. */
        CONDITION,
        /** Lead the quest NPC in {@code data.npcId} to {@link #pos} (within {@link #radius}). */
        ESCORT,
        /** Reach {@link #pos} once its guardians are dealt with, and take the relic {@code data.relic} (or item {@link #target}). */
        RETRIEVE
    }

    public Type type = Type.GO_TO;
    public Component text = Component.empty();
    public String target = "";
    public int required = 1;
    public int progress = 0;
    public boolean hasPos;
    public BlockPos pos = BlockPos.ZERO;
    /** False while the Y coordinate of {@link #pos} is only a guess (target area not yet loaded). */
    public boolean yKnown = true;
    public String dim = "minecraft:overworld";
    public int radius = 0;
    public boolean done;
    /** What the player says for TALK_TO / FETCH turn-ins. */
    public Component topic = Component.empty();
    /** What the NPC answers when the TALK_TO / FETCH objective is completed. */
    public Component reply = Component.empty();
    @Nullable
    public UUID npc;
    /** For TALK_TO / FETCH without a specific NPC: {@code villager}, {@code guard}, {@code trader} or {@code any}. */
    public String npcFilter = "";
    /** Enemies spawned lazily when a member of the owning party comes close. See {@link QuestSpawner}. */
    public ListTag spawns = new ListTag();
    /** {@code surface}, {@code near} (exact surface spot) or {@code structure} (inside a located structure). */
    public String placement = "surface";
    public boolean spawned;
    public List<UUID> spawnedIds = new ArrayList<>();
    /** Set when a KILL_TARGET was finished by a sneaking player (Dark Brotherhood bonus). */
    public boolean sneakKill;
    /** Action run once when the objective completes, e.g. {@code give:nirncraft:ancient_tome}. */
    public String onComplete = "";
    /** Plain-text marker label for the world map. */
    public String label = "";
    /** Seconds a spawned target has been missing while players were nearby (respawn after a while). */
    public int missing;
    /** Free-form extra data (condition baselines...). */
    public CompoundTag data = new CompoundTag();

    public Objective() {}

    public Objective(Type type, Component text) {
        this.type = type;
        this.text = text;
    }

    // ------------------------------------------------------------------ builders

    public Objective at(BlockPos pos, String dim, int radius) {
        this.hasPos = true;
        this.pos = pos.immutable();
        this.dim = dim;
        this.radius = radius;
        return this;
    }

    public Objective guessY() {
        this.yKnown = false;
        return this;
    }

    public Objective target(String target) {
        this.target = target;
        return this;
    }

    public Objective count(int required) {
        this.required = Math.max(1, required);
        return this;
    }

    public Objective npc(@Nullable UUID npc) {
        this.npc = npc;
        return this;
    }

    public Objective filter(String npcFilter) {
        this.npcFilter = npcFilter;
        return this;
    }

    public Objective topic(Component topic, Component reply) {
        this.topic = topic;
        this.reply = reply;
        return this;
    }

    public Objective spawn(CompoundTag spec) {
        spawns.add(spec);
        return this;
    }

    public Objective placement(String placement) {
        this.placement = placement;
        return this;
    }

    public Objective onComplete(String action) {
        this.onComplete = action;
        return this;
    }

    public Objective label(String label) {
        this.label = label;
        return this;
    }

    public int spawnCount() {
        int n = 0;
        for (int i = 0; i < spawns.size(); i++) n += Math.max(1, spawns.getCompoundOrEmpty(i).getIntOr("count",0));
        return n;
    }

    // ------------------------------------------------------------------ nbt

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("type", type.name());
        t.putString("text", Quest.writeComponent(text));
        t.putString("target", target);
        t.putInt("required", required);
        t.putInt("progress", progress);
        t.putBoolean("hasPos", hasPos);
        t.putInt("x", pos.getX());
        t.putInt("y", pos.getY());
        t.putInt("z", pos.getZ());
        t.putBoolean("yKnown", yKnown);
        t.putString("dim", dim);
        t.putInt("radius", radius);
        t.putBoolean("done", done);
        t.putString("topic", Quest.writeComponent(topic));
        t.putString("reply", Quest.writeComponent(reply));
        if (npc != null) t.store("npc", net.minecraft.core.UUIDUtil.CODEC,  npc);
        t.putString("npcFilter", npcFilter);
        t.put("spawns", spawns.copy());
        t.putString("placement", placement);
        t.putBoolean("spawned", spawned);
        ListTag ids = new ListTag();
        for (UUID id : spawnedIds) ids.add(net.minecraft.core.UUIDUtil.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, id).getOrThrow());
        t.put("spawnedIds", ids);
        t.putBoolean("sneakKill", sneakKill);
        t.putString("onComplete", onComplete);
        t.putString("label", label);
        t.putInt("missing", missing);
        t.put("data", data.copy());
        return t;
    }

    public static Objective load(CompoundTag t) {
        Objective o = new Objective();
        try {
            o.type = Type.valueOf(t.getStringOr("type",""));
        } catch (IllegalArgumentException e) {
            o.type = Type.GO_TO;
        }
        o.text = Quest.readComponent(t.getStringOr("text",""));
        o.target = t.getStringOr("target","");
        o.required = Math.max(1, t.getIntOr("required",0));
        o.progress = t.getIntOr("progress",0);
        o.hasPos = t.getBooleanOr("hasPos",false);
        o.pos = new BlockPos(t.getIntOr("x",0), t.getIntOr("y",0), t.getIntOr("z",0));
        o.yKnown = !t.contains("yKnown") || t.getBooleanOr("yKnown",false);
        o.dim = t.contains("dim") ? t.getStringOr("dim","") : "minecraft:overworld";
        o.radius = t.getIntOr("radius",0);
        o.done = t.getBooleanOr("done",false);
        o.topic = Quest.readComponent(t.getStringOr("topic",""));
        o.reply = Quest.readComponent(t.getStringOr("reply",""));
        o.npc = t.read("npc", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? t.read("npc", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
        o.npcFilter = t.getStringOr("npcFilter","");
        o.spawns = t.getListOrEmpty("spawns").copy();
        o.placement = t.contains("placement") ? t.getStringOr("placement","") : "surface";
        o.spawned = t.getBooleanOr("spawned",false);
        ListTag ids = t.getListOrEmpty("spawnedIds");
        for (Tag id : ids) {
            try {
                o.spawnedIds.add(net.minecraft.core.UUIDUtil.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, id).getOrThrow());
            } catch (IllegalArgumentException ignored) {
            }
        }
        o.sneakKill = t.getBooleanOr("sneakKill",false);
        o.onComplete = t.getStringOr("onComplete","");
        o.label = t.getStringOr("label","");
        o.missing = t.getIntOr("missing",0);
        o.data = t.getCompoundOrEmpty("data").copy();
        return o;
    }
}
