package com.skycraft.lore.place;

import com.skycraft.Skycraft;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The default placer's lectern half: the first time a player stands inside a named place whose lore has notes
 * marked {@code "at": "site"} (or any notes, at shrines, standing stones, word walls, dragon mounds and landmarks,
 * which have no chests), a lectern with the first of them is set on open ground a few steps from the place's centre,
 * facing it. Reading it hands the reader every lectern note of the place ({@link PlaceLoreEvents#onRightClickBlock}).
 * Once per world and place (world data {@code skycraft_place_lore}); places another module claimed are skipped.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class PlaceLorePlacer {
    private static final int CHECK_INTERVAL = 100;

    private PlaceLorePlacer() {}

    /** Places with no containers to hold their notes: every note waits on the lectern. */
    static boolean siteOnly(Place p) {
        PlaceType t = p.type();
        return t == PlaceType.STANDING_STONE || t == PlaceType.WORD_WALL || t == PlaceType.DRAGON_MOUND
                || t == PlaceType.DAEDRIC_SHRINE || t == PlaceType.LANDMARK;
    }

    /** The notes of a place that wait on its lectern. */
    static List<PlaceLore.Note> lecternNotes(PlaceLore.Content c) {
        boolean all = Places.byId(c.placeId()).map(PlaceLorePlacer::siteOnly).orElse(false);
        List<PlaceLore.Note> out = new ArrayList<>();
        for (PlaceLore.Note n : c.notes()) if (all || n.at() == PlaceLore.At.SITE) out.add(n);
        return out;
    }

    // ------------------------------------------------------------------ world data

    static final class Store extends SavedData {
        private static final String NAME = "skycraft_place_lore";
        final Set<String> placed = new HashSet<>();

        static Store get(ServerLevel level) {
            return com.skycraft.platform.SavedDataTypes.get(level.getServer().overworld(), NAME, Store::load, Store::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
        }

        static Store load(CompoundTag tag) {
            Store s = new Store();
            ListTag list = tag.getListOrEmpty("placed");
            for (int i = 0; i < list.size(); i++) s.placed.add(list.getStringOr(i, ""));
            return s;
        }

        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (String id : placed) list.add(StringTag.valueOf(id));
            tag.put("placed", list);
            return tag;
        }
    }

    // ------------------------------------------------------------------ triggers

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) return;
        if ((player.tickCount + (player.getId() & 63)) % CHECK_INTERVAL != 0) return;
        if (player.level().dimension() != Level.OVERWORLD) return;
        int x = player.getBlockX(), z = player.getBlockZ();
        for (Place p : Places.overlapping(x, z, x, z)) {
            long r = p.radius();
            if (p.distSqr(x, z) <= r * r) onEnter(player, p);
        }
    }

    /** A player stands inside the place: make sure its lectern exists (once per world). */
    public static void onEnter(ServerPlayer player, Place place) {
        if (player.level().dimension() != Level.OVERWORLD || PlaceLore.claimed(place.id())) return;
        PlaceLore.Content c = PlaceLore.contentFor(place.id()).orElse(null);
        if (c == null) return;
        List<PlaceLore.Note> notes = lecternNotes(c);
        if (notes.isEmpty()) return;
        ServerLevel level = player.level();
        Store store = Store.get(level);
        if (store.placed.contains(place.id())) return;
        try {
            if (placeLectern(level, place, notes.get(0))) {
                store.placed.add(place.id());
                store.setDirty();
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Couldn't place the lore lectern of {}", place.id(), e);
        }
    }

    /** Sets a lectern holding the note on open ground near the place's centre. False if no spot is loaded and free. */
    static boolean placeLectern(ServerLevel level, Place place, PlaceLore.Note note) {
        int cx = place.x(), cz = place.z();
        if (!level.hasChunkAt(new BlockPos(cx, 64, cz))) return false;
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        int start = Math.floorMod(place.id().hashCode(), 16);
        for (int dist = 4; dist <= 12; dist += 2) {
            for (int k = 0; k < 16; k++) {
                double a = (start + k) * Math.PI / 8;
                int x = cx + (int) Math.round(Math.sin(a) * dist);
                int z = cz - (int) Math.round(Math.cos(a) * dist);
                BlockPos probe = new BlockPos(x, 64, z);
                if (!level.hasChunkAt(probe)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(y - groundY) > 5) continue;
                BlockPos pos = new BlockPos(x, y, z);
                if (!free(level, pos) || !free(level, pos.above()) || !level.canSeeSky(pos)) continue;
                BlockState below = level.getBlockState(pos.below());
                if (!below.isFaceSturdy(level, pos.below(), Direction.UP) || below.is(BlockTags.LEAVES) || !below.getFluidState().isEmpty()) continue;
                // the lectern's front (where the reader stands) looks toward the centre
                Direction facing = Direction.fromYRot(Math.toDegrees(Math.atan2(-(cx - x), cz - z)));
                level.setBlock(pos, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, facing)
                        .setValue(LecternBlock.HAS_BOOK, true), 3);
                if (level.getBlockEntity(pos) instanceof LecternBlockEntity lectern) {
                    lectern.setBook(LoreBookItem.create(note.book()));
                    lectern.setChanged();
                }
                return true;
            }
        }
        return false;
    }

    private static boolean free(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }
}
