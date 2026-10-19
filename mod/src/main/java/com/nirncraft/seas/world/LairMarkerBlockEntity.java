package com.nirncraft.seas.world;

import com.nirncraft.NirnCraft;
import com.nirncraft.seas.SeasRegistry;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.Npcs;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The inhabitants a site's {@link LairMarkerBlock} holds until a player comes within {@link #RANGE} blocks: creatures
 * by entity id (a dreugh lair's warriors, its queen, a slaughterfish nest), or society NPCs by role (a lighthouse
 * keeper, a hermit, pirates in their cove). They are placed persistent around the marker, then the marker is gone.
 *
 * <p>Entries: {@code {Id: "nirncraft:dreugh", Count: 3, Spread: 4}}, {@code {Npc: "thug", Name: "seas.nirncraft.crew.pirate",
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
            NirnCraft.LOGGER.warn("Seas: lair at {} could not place its inhabitants", pos, e);
        }
        level.setBlock(pos, state.getValue(LairMarkerBlock.WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
    }

    private void populate(ServerLevel level, Player near) {
        RandomSource r = level.getRandom();
        for (int i = 0; i < spawns.size(); i++) {
            CompoundTag t = spawns.getCompoundOrEmpty(i);
            int count = Math.max(1, t.getIntOr("Count",0));
            int spread = Math.max(0, t.getIntOr("Spread",0));
            for (int n = 0; n < count; n++) {
                BlockPos at = spot(level, worldPosition, spread, r);
                Mob mob = t.contains("Npc") ? npc(level, at, t, near) : creature(level, at, t.getStringOr("Id",""));
                if (mob == null) continue;
                mob.setPersistenceRequired();
                mob.getPersistentData().putBoolean("nirncraft_seas_site", true);
                level.addFreshEntityWithPassengers(mob);
            }
        }
    }

    private static Mob creature(ServerLevel level, BlockPos at, String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(rl);
        Entity e = type == null ? null : type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (!(e instanceof Mob mob)) {
            if (e != null) e.discard();
            return null;
        }
        float yaw = level.getRandom().nextFloat() * 360f;
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0f);
        mob.setYHeadRot(yaw);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(at), EntitySpawnReason.STRUCTURE, null);
        if (mob.isSpawnCancelled()) {
            mob.discard();
            return null;
        }
        return mob;
    }

    private static Mob npc(ServerLevel level, BlockPos at, CompoundTag t, Player near) {
        NpcRole role = NpcRole.byId(t.getStringOr("Npc",""));
        if (role == null) return null;
        NpcEntity npc = Npcs.create(level, at, role);
        if (npc == null) return null;
        String name = t.getStringOr("Name","");
        if (!name.isEmpty()) npc.setCustomName(Component.translatable(name));
        if (t.getBooleanOr("Hostile",false)) npc.setHuntTarget(near.getUUID());
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
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output263) {
        super.saveAdditional(output263);
        CompoundTag tag = new CompoundTag();
        tag.put("Spawns", spawns.copy());
        output263.store(tag);
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input263) {
        super.loadAdditional(input263);
        CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263, "Spawns");
        spawns.clear();
        ListTag list = tag.getListOrEmpty("Spawns");
        for (int i = 0; i < list.size(); i++) spawns.add(list.getCompoundOrEmpty(i));
    }
}
