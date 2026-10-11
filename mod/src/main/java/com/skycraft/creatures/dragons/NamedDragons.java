package com.skycraft.creatures.dragons;

import com.skycraft.creatures.ModEntities;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Skyrim's named dragons and the spawn helpers other modules (quests, events) can use:
 * <ul>
 *     <li><b>Alduin</b>, the World-Eater: {@link #alduinFlyover} (raising dragons from their mounds); his battle in
 *     Sovngarde is the End's dragon, renamed and redrawn.</li>
 *     <li><b>Paarthurnax</b>: perched on the summit of the Throat of the World, peaceful, speaks with the
 *     Dragonborn ({@link #talk}). Placed by {@link DragonLairs}; once killed he stays dead.</li>
 *     <li><b>Odahviing</b>: {@link #spawnOdahviing} for the "Dragon trapped in Dragonsreach" quest hook; captured
 *     dragons stay grounded and peaceful until {@link DragonEntity#setCaptured(boolean) setCaptured(false)}.</li>
 * </ul>
 */
public final class NamedDragons {
    private NamedDragons() {}

    static {
        Dialogue.registerProvider(NamedDragons::topics);
    }

    /** Called from module init so the dialogue provider is registered. */
    public static void init() {
        // static initialiser does the work
    }

    /** A dragon of the given variant at {@code pos} (not yet added to the level). */
    @Nullable
    public static DragonEntity create(ServerLevel level, BlockPos pos, int variant, @Nullable String nameKey) {
        DragonEntity d = ModEntities.DRAGON.get().create(level);
        if (d == null) return null;
        d.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        d.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
        if (nameKey != null) d.setCustomName(Component.translatable(nameKey));
        d.applyVariant(variant);
        return d;
    }

    /** Alduin circles {@code center} for a while, roaring, then leaves. Invulnerable; returns him. */
    @Nullable
    public static DragonEntity alduinFlyover(ServerLevel level, Vec3 center, int ticks) {
        double ang = level.getRandom().nextDouble() * Math.PI * 2;
        BlockPos start = BlockPos.containing(center.x + Math.cos(ang) * 90, center.y + 40, center.z + Math.sin(ang) * 90);
        DragonEntity alduin = create(level, start, DragonEntity.ALDUIN, "entity.skycraft.alduin");
        if (alduin == null) return null;
        alduin.startFlyover(center, ticks);
        level.addFreshEntity(alduin);
        return alduin;
    }

    /** Paarthurnax, perched and peaceful at {@code perch}. */
    @Nullable
    public static DragonEntity spawnPaarthurnax(ServerLevel level, BlockPos perch) {
        DragonEntity d = create(level, perch, DragonEntity.PAARTHURNAX, "entity.skycraft.paarthurnax");
        if (d == null) return null;
        d.setRole(DragonEntity.ROLE_PEACEFUL);
        d.setFlying(false);
        d.setPerch(Vec3.atBottomCenterOf(perch));
        level.addFreshEntity(d);
        return d;
    }

    /**
     * Odahviing (quest hook): spawns him at {@code pos}; {@code captured} = held in the yoke (grounded, peaceful,
     * immune to players until freed with {@link DragonEntity#setCaptured(boolean)}).
     */
    @Nullable
    public static DragonEntity spawnOdahviing(ServerLevel level, BlockPos pos, boolean captured) {
        DragonEntity d = create(level, pos, DragonEntity.ODAHVIING, "entity.skycraft.odahviing");
        if (d == null) return null;
        if (captured) {
            d.setFlying(false);
            d.setCaptured(true);
            d.setPerch(Vec3.atBottomCenterOf(pos));
        } else {
            d.setFlying(true);
            d.setPersistenceRequired();
        }
        level.addFreshEntity(d);
        return d;
    }

    /** The highest ground within {@code radius} of (x, z), among loaded chunks only (coarse 8-block grid). */
    public static BlockPos summit(ServerLevel level, int x, int z, int radius) {
        BlockPos best = null;
        int bestY = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += 8) {
            for (int dz = -radius; dz <= radius; dz += 8) {
                int px = x + dx;
                int pz = z + dz;
                if (!level.hasChunk(px >> 4, pz >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                if (y > bestY) {
                    bestY = y;
                    best = new BlockPos(px, y, pz);
                }
            }
        }
        return best != null ? best : new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    // ------------------------------------------------------------------ conversation

    /** Opens a conversation with a peaceful or captured dragon. */
    public static void talk(ServerPlayer player, DragonEntity dragon) {
        String who = who(dragon);
        Dialogue.open(player, dragon, Component.translatable("dialogue.skycraft.dragon." + who + ".greeting"));
    }

    private static String who(DragonEntity dragon) {
        return switch (dragon.getVariant()) {
            case DragonEntity.PAARTHURNAX -> "paarthurnax";
            case DragonEntity.ODAHVIING -> "odahviing";
            default -> "dragon";
        };
    }

    private static void topics(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!(npc instanceof DragonEntity dragon) || dragon.startsFights()) return;
        String who = who(dragon);
        String[] topics = switch (who) {
            case "paarthurnax" -> new String[]{"voice", "alduin", "nature", "throat"};
            case "odahviing" -> new String[]{"yoke", "alduin"};
            default -> new String[]{"alduin"};
        };
        int order = 100;
        for (String t : topics) {
            String key = "dialogue.skycraft.dragon." + who + "." + t;
            out.add(new DialogueOption("creatures.dragon." + t, Component.translatable(key + ".ask"), order++,
                    (p, n) -> Dialogue.open(p, n, Component.translatable(key + ".answer"))));
        }
    }
}
