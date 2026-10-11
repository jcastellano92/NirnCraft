package com.skycraft.seas.world;

import com.skycraft.Skycraft;
import com.skycraft.seas.SeasRegistry;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The inhabitants a site's {@link LairMarkerBlock} holds until a player comes within {@link #RANGE} blocks: creatures
 * by entity id (a dreugh lair's warriors, its queen, a slaughterfish nest), or society NPCs by role (a lighthouse
 * keeper, a hermit, pirates in their cove). They are placed persistent around the marker, then the marker is gone.
 *
 * <p>Entries: {@code {Id: "skycraft:dreugh", Count: 3, Spread: 4}}, {@code {Npc: "thug", Name: "seas.skycraft.crew.pirate",
 * Hostile: 1b, Count: 3}}.</p>
 */
public class LairMarkerBlockEntity extends BlockEntity {
    private static final double RANGE = 30;
    private final ListTag spawns = new ListTag();

    public LairMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(SeasRegistry.LAIR_MARKER_BE.get(), pos, state);
    }

    /** Adds creatures of {@code id}. */
    public LairMarkerBlockEntity creature(String id, int count, int spread) {
        CompoundTag t = new CompoundTag();
        t.putString("Id", id);
        t.putInt("Count", count);
        t.putInt("Spread", spread);
        spawns.add(t);
        setChanged();
        return this;
    }

    /** Adds society NPCs of {@code role} with a title (lang key), optionally hostile to whoever comes. */
    public LairMarkerBlockEntity npc(String role, int count, String nameKey, boolean hostile) {
        CompoundTag t = new CompoundTag();
        t.putString("Npc", role);
        t.putInt("Count", count);
        t.putInt("Spread", 3);
        t.putString("Name", nameKey);
        t.putBoolean("Hostile", hostile);
        spawns.add(t);
        setChanged();
        return this;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LairMarkerBlockEntity be) {
        if ((level.getGameTime() + pos.asLong()) % 20 != 0 || !(level instanceof ServerLevel server)) return;
        Player near = level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, RANGE, p -> !p.isSpectator());
        if (near == null) return;
        try {
            be.populate(server, near);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Seas: lair at {} could not place its inhabitants", pos, e);
        }
        level.setBlock(pos, state.getValue(LairMarkerBlock.WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
    }

    private void populate(ServerLevel level, Player near) {
        RandomSource r = level.getRandom();
        for (int i = 0; i < spawns.size(); i++) {
            CompoundTag t = spawns.getCompound(i);
            int count = Math.max(1, t.getInt("Count"));
            int spread = Math.max(0, t.getInt("Spread"));
            for (int n = 0; n < count; n++) {
                BlockPos at = spot(level, worldPosition, spread, r);
                Mob mob = t.contains("Npc") ? npc(level, at, t, near) : creature(level, at, t.getString("Id"));
                if (mob == null) continue;
                mob.setPersistenceRequired();
                mob.getPersistentData().putBoolean("skycraft_seas_site", true);
                level.addFreshEntityWithPassengers(mob);
            }
        }
    }

    private static Mob creature(ServerLevel level, BlockPos at, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !ForgeRegistries.ENTITY_TYPES.containsKey(rl)) return null;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        Entity e = type == null ? null : type.create(level);
        if (!(e instanceof Mob mob)) {
            if (e != null) e.discard();
            return null;
        }
        float yaw = level.getRandom().nextFloat() * 360f;
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0f);
        mob.setYHeadRot(yaw);
        net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null, null);
        if (mob.isSpawnCancelled()) {
            mob.discard();
            return null;
        }
        return mob;
    }

    private static Mob npc(ServerLevel level, BlockPos at, CompoundTag t, Player near) {
        NpcRole role = NpcRole.byId(t.getString("Npc"));
        if (role == null) return null;
        NpcEntity npc = Npcs.create(level, at, role);
        if (npc == null) return null;
        String name = t.getString("Name");
        if (!name.isEmpty()) npc.setCustomName(Component.translatable(name));
        if (t.getBoolean("Hostile")) npc.setHuntTarget(near.getUUID());
        return npc;
    }

    /** A free spot near the marker: water for the sea's folk, a floor with headroom for the rest. */
    private static BlockPos spot(ServerLevel level, BlockPos center, int spread, RandomSource r) {
        for (int i = 0; i < 10 && spread > 0; i++) {
            BlockPos p = center.offset(r.nextInt(spread * 2 + 1) - spread, r.nextInt(3) - 1, r.nextInt(spread * 2 + 1) - spread);
            if (!level.isLoaded(p)) continue;
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                return p;
            }
        }
        return center;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Spawns", spawns.copy());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        spawns.clear();
        ListTag list = tag.getList("Spawns", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) spawns.add(list.getCompound(i));
    }
}
