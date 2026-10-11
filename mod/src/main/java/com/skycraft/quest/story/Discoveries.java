package com.skycraft.quest.story;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.quest.Quests;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Hidden quests ({@code "start": "find"}): nobody offers them. A small clue waits in the world near a place (a
 * skeleton clutching a note, a carved stone, a lever in a ruined wall, a candlelit altar, a grave, an urn, a
 * totem): it is built once per world the first time a player comes near, and touching it (using it) or standing at
 * it starts the quest for whoever found it. Also resolves {@code land:} quest variables (a spot on the coast of a
 * foreign land, whose shape depends on the seed).
 */
public final class Discoveries {
    /** Hint distance: the clue "catches the eye" once per visit. */
    private static final int HINT = 10;
    private static final int BUILD = 40;
    private static final Map<UUID, Set<String>> HINTED = new HashMap<>();

    private Discoveries() {}

    /** A hidden quest's clue spot in the overworld, or null if its place doesn't resolve. */
    @Nullable
    static int[] spot(StoryData.QuestDef def) {
        if (def.find == null) return null;
        Optional<QuestPlaces.Spot> s = QuestPlaces.find(def.find.place);
        if (s.isEmpty() || !"minecraft:overworld".equals(s.get().dim())) return null;
        return new int[]{s.get().x() + def.find.dx, s.get().z() + def.find.dz};
    }

    private record Find(StoryData.QuestDef def, int[] at) {
    }

    @Nullable
    private static volatile List<Find> finds;

    /** Every hidden quest with its resolved clue spot (computed once the world's places are known). */
    private static List<Find> finds() {
        List<Find> f = finds;
        if (f == null) {
            f = new ArrayList<>();
            for (StoryData.QuestDef def : StoryData.quests()) {
                int[] at = spot(def);
                if (at != null) f.add(new Find(def, at));
            }
            finds = f;
        }
        return f;
    }

    public static void reset() {
        finds = null;
        HINTED.clear();
    }

    // ------------------------------------------------------------------ ticking

    /** Every second per player: build clues nearby, hint at them, start a hidden quest when its clue is reached. */
    static void tick(ServerPlayer p) {
        if (p.level().dimension() != Level.OVERWORLD || SkyData.get(p).getRace() == null) return;
        ServerLevel level = p.serverLevel();
        Clues clues = null;
        for (Find f : finds()) {
            StoryData.QuestDef def = f.def();
            int[] at = f.at();
            double dx = p.getX() - at[0], dz = p.getZ() - at[1];
            if (dx * dx + dz * dz > BUILD * BUILD) continue;
            if (clues == null) clues = Clues.get(p.server);
            BlockPos clue = clues.pos(def.id);
            if (clue == null) {
                if (!level.hasChunk(at[0] >> 4, at[1] >> 4)) continue;
                clue = build(level, at[0], at[1], def.find.clue);
                clues.put(def.id, clue);
            }
            if (!Story.available(p, def)) continue;
            double cx = p.getX() - (clue.getX() + 0.5), cz = p.getZ() - (clue.getZ() + 0.5);
            double h = Math.sqrt(cx * cx + cz * cz);
            if (h <= def.find.radius + 0.5 && Math.abs(p.getY() - clue.getY()) < 5) {
                trigger(p, def);
            } else if (h <= HINT) {
                if (HINTED.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(def.id)) {
                    p.displayClientMessage(Component.translatableWithFallback("story.skycraft.find.hint", "Something here catches your eye.")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
                }
            } else if (h > BUILD - 4) {
                Set<String> hinted = HINTED.get(p.getUUID());
                if (hinted != null) hinted.remove(def.id);
            }
        }
    }

    /** A player used a block: the clue of a hidden quest within two blocks of it starts that quest. */
    static boolean onUse(ServerPlayer p, BlockPos used) {
        if (p.level().dimension() != Level.OVERWORLD) return false;
        Clues clues = Clues.get(p.server);
        for (Find f : finds()) {
            StoryData.QuestDef def = f.def();
            double dx = used.getX() - f.at()[0], dz = used.getZ() - f.at()[1];
            if (dx * dx + dz * dz > BUILD * BUILD) continue;
            BlockPos clue = clues.pos(def.id);
            if (clue == null || clue.distSqr(used) > 2 * 2 + 1) continue;
            if (!Story.available(p, def)) continue;
            trigger(p, def);
            return true;
        }
        return false;
    }

    private static void trigger(ServerPlayer p, StoryData.QuestDef def) {
        if (!Quests.canTakeMore(p)) return;
        if (!def.find.text.isEmpty()) Notifier.message(p, def.find.text.c().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.6f);
        Story.start(p, def);
    }

    public static void forget(UUID player) {
        HINTED.remove(player);
    }

    // ------------------------------------------------------------------ the clues

    /** Builds the clue on the surface near x/z (dry land, only into air and plants); returns where it stands. */
    static BlockPos build(ServerLevel level, int x, int z, String kind) {
        BlockPos base = dryGround(level, x, z);
        Direction face = Direction.from2DDataValue(Math.floorMod(x * 31 + z * 17, 4));
        switch (kind) {
            case "skeleton" -> {
                put(level, base, Blocks.SKELETON_SKULL.defaultBlockState().setValue(SkullBlock.ROTATION, Math.floorMod(x + z, 16)));
                put(level, base.relative(face), Blocks.BONE_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, face.getAxis()));
                put(level, base.relative(face, 2), Blocks.BONE_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, face.getAxis()));
            }
            case "lever" -> {
                put(level, base, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                put(level, base.above(), Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState());
                BlockPos lever = base.relative(face);
                put(level, lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, face));
            }
            case "altar" -> {
                put(level, base, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
                put(level, base.above(), Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true));
            }
            case "grave" -> {
                put(level, base, Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState());
                BlockPos mound = base.relative(face).below();
                if (level.getBlockState(mound).is(Blocks.GRASS_BLOCK) || level.getBlockState(mound).is(Blocks.DIRT)) {
                    level.setBlock(mound, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                }
            }
            case "lectern" -> put(level, base, Blocks.LECTERN.defaultBlockState());
            case "urn" -> put(level, base, Blocks.DECORATED_POT.defaultBlockState());
            case "totem" -> {
                put(level, base, Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
                put(level, base.above(), Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
                put(level, base.above(2), Blocks.SKELETON_SKULL.defaultBlockState().setValue(SkullBlock.ROTATION, face.get2DDataValue() * 4));
            }
            default -> {
                // "stone": a carved standing stone
                put(level, base, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
                put(level, base.above(), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            }
        }
        return base;
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState at = level.getBlockState(pos);
        if (at.isAir() || at.canBeReplaced()) level.setBlock(pos, state, 3);
    }

    private static BlockPos dryGround(ServerLevel level, int x, int z) {
        for (int r = 0; r <= 8; r += 2) {
            for (int i = 0; i < (r == 0 ? 1 : 8); i++) {
                double a = i * Math.PI / 4;
                int px = x + (int) Math.round(Math.cos(a) * r), pz = z + (int) Math.round(Math.sin(a) * r);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                BlockPos pos = new BlockPos(px, y, pz);
                if (level.getFluidState(pos.below()).isEmpty() && level.getFluidState(pos).isEmpty()) return pos;
            }
        }
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    // ------------------------------------------------------------------ foreign shores

    /**
     * {@code land:<province>:<x>:<z>}: the nearest dry spot of that province (a foreign land shaped by the seed)
     * around x/z, as a place id {@code land_<x>_<z>} that {@link QuestPlaces} resolves.
     */
    @Nullable
    static String landSite(String spec) {
        String[] p = spec.split(":");
        if (p.length < 3) return null;
        String province = p[0];
        int x0, z0;
        try {
            x0 = Integer.parseInt(p[1].trim());
            z0 = Integer.parseInt(p[2].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        for (int ring = 0; ring <= 8000; ring += 256) {
            int steps = ring == 0 ? 1 : Math.max(8, ring / 128);
            for (int i = 0; i < steps; i++) {
                double a = i * Math.PI * 2 / steps;
                int x = (x0 + (int) (Math.cos(a) * ring)) & ~15, z = (z0 + (int) (Math.sin(a) * ring)) & ~15;
                Region r = TamrielMap.region(x, z);
                if (!province.equals(r.province()) || "sea".equals(r.kind())) continue;
                if (TamrielMap.elevation(x, z) <= TamrielMap.SEA_LEVEL + 3) continue;
                return "land_" + x + "_" + z;
            }
        }
        return "land_" + (x0 & ~15) + "_" + (z0 & ~15);
    }

    // ------------------------------------------------------------------ saved clue positions

    /** Where each hidden quest's clue was built (once per world). */
    public static final class Clues extends SavedData {
        private static final String NAME = "skycraft_story_clues";
        private final Map<String, Long> pos = new HashMap<>();

        static Clues get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(Clues::load, Clues::new, NAME);
        }

        @Nullable
        BlockPos pos(String questId) {
            Long l = pos.get(questId);
            return l == null ? null : BlockPos.of(l);
        }

        void put(String questId, BlockPos p) {
            pos.put(questId, p.asLong());
            setDirty();
        }

        static Clues load(CompoundTag tag) {
            Clues c = new Clues();
            for (String k : tag.getAllKeys()) c.pos.put(k, tag.getLong(k));
            return c;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            for (Map.Entry<String, Long> e : pos.entrySet()) tag.putLong(e.getKey(), e.getValue());
            return tag;
        }
    }
}
