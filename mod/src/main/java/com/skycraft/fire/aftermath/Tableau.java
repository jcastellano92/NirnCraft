package com.skycraft.fire.aftermath;

import com.skycraft.society.entity.NpcEntity;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The people of an aftermath scene: what pose each stands in, what it holds and says. A tableau actor is an ordinary
 * {@link NpcEntity} (so it can be talked to, healed, hurt and killed like any townsperson) with a persistent-data tag
 * naming its {@link Pose}; static poses are frozen in place (no AI): a kneeling mourner stays kneeling.
 *
 * <p>Anchors recorded by the aftermath painters carry the owner label {@code pose|role|option}; they are spawned once
 * by {@link AftermathPopulation} when a player comes within range.</p>
 */
public final class Tableau {
    /** Persistent-data key on an actor (the pose id) and on a body. */
    public static final String KEY = "skycraft_tableau";
    /** Persistent-data key: the anchor position (packed) the actor belongs to; guards stay near it. */
    public static final String AT_KEY = "skycraft_tableau_at";
    /** Persistent-data key: two packed positions a hauler walks between. */
    public static final String WALK_KEY = "skycraft_tableau_walk";

    /**
     * @param flags     {@code NpcEntity.FLAG_*} pose bits
     * @param frozen    no AI: the actor stays exactly where it is put
     * @param item      registry id of the item in the main hand ("" = none)
     * @param swingTicks swings the main hand every this many ticks (0 = never): digging, ladling, passing buckets
     * @param lines     key of the group of short lines it says to a player who comes close ("" = silent)
     * @param sobs      cries now and then (a soft, positional sound)
     */
    public enum Pose {
        KNEEL(NpcEntity.FLAG_KNEEL, true, "", 0, "survivor", false),
        KNEEL_WEEP(NpcEntity.FLAG_KNEEL | NpcEntity.FLAG_WEEP, true, "", 0, "grief", true),
        HUDDLE(NpcEntity.FLAG_HUDDLE, true, "", 0, "survivor", false),
        HUDDLE_WEEP(NpcEntity.FLAG_HUDDLE | NpcEntity.FLAG_WEEP, true, "", 0, "grief", true),
        CLING(NpcEntity.FLAG_HUDDLE | NpcEntity.FLAG_WEEP, true, "", 0, "child", true),
        MOURN(NpcEntity.FLAG_KNEEL | NpcEntity.FLAG_WEEP, true, "minecraft:poppy", 0, "mourn", true),
        STAND_WEEP(NpcEntity.FLAG_WEEP, true, "", 0, "grief", true),
        TEND(NpcEntity.FLAG_TEND, true, "minecraft:paper", 90, "healer", false),
        LADLE(NpcEntity.FLAG_CARRY, true, "minecraft:bowl", 44, "cook", false),
        DIG(NpcEntity.FLAG_CARRY, true, "minecraft:iron_shovel", 26, "worker", false),
        PICK(NpcEntity.FLAG_CARRY, true, "minecraft:iron_pickaxe", 22, "worker", false),
        AXE(NpcEntity.FLAG_CARRY, true, "minecraft:iron_axe", 30, "worker", false),
        BUCKET(NpcEntity.FLAG_CARRY, true, "minecraft:bucket", 34, "bucket", false),
        HAUL(0, false, "minecraft:oak_planks", 0, "worker", false),
        GUARD(0, false, "minecraft:iron_sword", 0, "guard", false),
        CAPTAIN(0, true, "", 0, "captain", false),
        PATIENT(0, true, "", 0, "patient", false),
        LOOKER(0, true, "", 0, "survivor", false),
        LOOTER(NpcEntity.FLAG_TEND, true, "minecraft:gold_nugget", 0, "looter", false),
        /** Kneeling in prayer before a shrine (head bowed, no tears). */
        PRAY(NpcEntity.FLAG_KNEEL, true, "", 0, "pray", false),
        /** The priest at a shrine or a vigil, standing, hands folded. */
        PRIEST(0, true, "", 0, "priest", false);

        public final int flags;
        public final boolean frozen;
        public final String item;
        public final int swingTicks;
        public final String lines;
        public final boolean sobs;
        public final String id = name().toLowerCase(Locale.ROOT);

        Pose(int flags, boolean frozen, String item, int swingTicks, String lines, boolean sobs) {
            this.flags = flags;
            this.frozen = frozen;
            this.item = item;
            this.swingTicks = swingTicks;
            this.lines = lines;
            this.sobs = sobs;
        }

        @Nullable
        public static Pose byId(String id) {
            for (Pose p : values()) if (p.id.equals(id)) return p;
            return null;
        }
    }

    /** How many lines each group has in the lang file ({@code fire.skycraft.line.<group>.<n>}, n from 1). */
    public static int lineCount(String group) {
        return switch (group) {
            case "grief" -> 6;
            case "survivor" -> 6;
            case "child" -> 4;
            case "mourn" -> 4;
            case "healer" -> 5;
            case "cook" -> 3;
            case "worker" -> 5;
            case "bucket" -> 4;
            case "guard" -> 5;
            case "captain" -> 5;
            case "patient" -> 4;
            case "looter" -> 3;
            case "pray" -> 4;
            case "priest" -> 4;
            case "night" -> 3;
            case "wary" -> 3;
            default -> 0;
        };
    }

    /** The pose tag of an entity, or null when it is not a tableau actor. */
    @Nullable
    public static Pose poseOf(Entity e) {
        if (!e.getPersistentData().contains(KEY)) return null;
        return Pose.byId(e.getPersistentData().getStringOr(KEY, ""));
    }

    /** Eye height of an actor in its pose (tears fall from there). */
    public static double eyeHeight(NpcEntity npc) {
        double h = npc.isChild() ? 1.1 : 1.62;
        if (npc.isHuddled()) return h * 0.52;
        if (npc.isKneeling()) return h * 0.72;
        if (npc.isTending()) return h * 0.78;
        return h;
    }
}
