package com.skycraft.fire.aftermath;

import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.fire.FireAnchors;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Puts the people and the dead of an aftermath scene where the painters recorded them. The painters (building
 * pieces, recorded on the server thread like every settlement anchor) leave {@link AnchorType#TABLEAU} anchors whose
 * owner label is {@code pose|role|option}; the first time a player comes within 64 blocks of a settlement that has
 * them, every anchor gets its actor once (a ledger of spawned anchors is saved with the world), while its chunk is
 * entity-ticking.
 *
 * <p>Option field: for {@code body} anchors {@code l} = lootable, {@code f} = face down; for {@code haul} anchors
 * {@code dx,dz} = where the load is carried to.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class AftermathPopulation {
    /** The spawned-anchor ledger (saved with the overworld). */
    public static final class Ledger extends SavedData {
        public static final String NAME = "skycraft_aftermath";
        final LongOpenHashSet done = new LongOpenHashSet();

        public static Ledger load(CompoundTag tag) {
            Ledger l = new Ledger();
            for (long v : tag.getLongArray("done")) l.done.add(v);
            return l;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putLongArray("done", done.toLongArray());
            return tag;
        }

        public int size() {
            return done.size();
        }
    }

    private AftermathPopulation() {}

    public static Ledger ledger(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(Ledger::load, Ledger::new, Ledger.NAME);
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long t = level.getGameTime();
        try {
            if (t % 100 == 41) FireAnchors.scan(level, false);
            if (t % 40 != 21) return;
            SettlementAnchors data = SettlementAnchors.get(level);
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator()) continue;
                for (SettlementAnchors.Entry e : data.entries()) {
                    if (!e.contains(p.getBlockX(), p.getBlockZ(), 64)) continue;
                    spawnFor(level, e, false);
                }
            }
        } catch (RuntimeException ex) {
            Skycraft.LOGGER.error("Aftermath population failed", ex);
        }
    }

    /** Spawns every unspawned tableau anchor of a settlement (all of them again when {@code force}); returns how many. */
    public static int spawnFor(ServerLevel level, SettlementAnchors.Entry e, boolean force) {
        List<Anchor> list = e.anchors(AnchorType.TABLEAU);
        if (list.isEmpty()) return 0;
        Ledger ledger = ledger(level);
        int n = 0;
        for (Anchor a : list) {
            long key = a.pos().asLong() * 31L + a.owner().hashCode();
            if (!force && ledger.done.contains(key)) continue;
            if (force ? !level.isLoaded(a.pos()) : !level.isPositionEntityTicking(a.pos())) continue;
            if (spawn(level, a)) {
                ledger.done.add(key);
                ledger.setDirty();
                n++;
            }
        }
        return n;
    }

    /** Removes every tableau actor and body within {@code radius} of {@code center} and forgets the ledger entries of the settlement. */
    public static int clear(ServerLevel level, SettlementAnchors.Entry e) {
        int n = 0;
        AABB box = new AABB(e.x - e.radius - 40, level.getMinBuildHeight(), e.z - e.radius - 40, e.x + e.radius + 40,
                level.getMaxBuildHeight(), e.z + e.radius + 40);
        for (Entity en : level.getEntitiesOfClass(Entity.class, box, x -> x.getPersistentData().contains(Tableau.KEY))) {
            en.discard();
            n++;
        }
        Ledger ledger = ledger(level);
        for (Anchor a : e.anchors(AnchorType.TABLEAU)) ledger.done.remove(a.pos().asLong() * 31L + a.owner().hashCode());
        ledger.setDirty();
        return n;
    }

    // ------------------------------------------------------------------ one anchor

    private static boolean spawn(ServerLevel level, Anchor a) {
        String[] f = a.owner().split("\\|", -1);
        if (f.length < 2) return true; // malformed: forget it
        String poseId = f[0];
        NpcRole role = NpcRole.byId(f[1]);
        String opt = f.length > 2 ? f[2] : "";
        if (role == null) return true;
        BlockPos pos = settle(level, a.pos());
        float yaw = (Math.floorMod(a.pos().asLong() * 0x9E3779B97F4A7C15L >>> 40, 8)) * 45f;
        // "@x,z": look at that world column (a mourner at a body, a worshipper at an altar, a healer at a patient)
        int look = opt.indexOf('@');
        if (look >= 0) {
            String[] t = opt.substring(look + 1).split(",");
            opt = opt.substring(0, look);
            if (t.length == 2) {
                try {
                    double dx = Integer.parseInt(t[0].trim()) - a.pos().getX(), dz = Integer.parseInt(t[1].trim()) - a.pos().getZ();
                    if (dx != 0 || dz != 0) yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
                } catch (NumberFormatException ignored) {
                    // keep the hashed yaw
                }
            }
        }
        if (poseId.equals("body")) {
            boolean lootable = opt.contains("l");
            float roll = opt.contains("f") ? 40f : (opt.contains("s") ? -30f : 0f);
            return AftermathBodies.spawn(level, pos, role, yaw, lootable, roll) != null;
        }
        Tableau.Pose pose = Tableau.Pose.byId(poseId);
        if (pose == null) return true;
        NpcEntity npc = Npcs.create(level, pos, role);
        if (npc == null) return false;
        npc.setPersistenceRequired();
        npc.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
        npc.setYHeadRot(yaw);
        npc.getPersistentData().putString(Tableau.KEY, pose.id);
        npc.getPersistentData().putBoolean(HelgenFolk.BARKS, true);
        npc.getPersistentData().putLong(Tableau.AT_KEY, a.pos().asLong());
        npc.setPoseFlags(pose.flags);
        if (!pose.item.isEmpty()) {
            var item = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(pose.item));
            if (item != null) {
                if (pose == Tableau.Pose.HAUL) npc.getPersistentData().putString("skycraft_tableau_item", pose.item);
                else npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
            }
        }
        if (pose == Tableau.Pose.HAUL) {
            int dx = 6, dz = 0;
            String[] d = opt.split(",");
            if (d.length == 2) {
                try {
                    dx = Integer.parseInt(d[0].trim());
                    dz = Integer.parseInt(d[1].trim());
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            }
            BlockPos to = settle(level, a.pos().offset(dx, 0, dz));
            npc.getPersistentData().putLongArray(Tableau.WALK_KEY, new long[]{pos.asLong(), to.asLong()});
        }
        if (pose == Tableau.Pose.PATIENT) {
            BlockState bed = level.getBlockState(a.pos());
            BlockPos bedPos = a.pos();
            if (!(bed.getBlock() instanceof BedBlock)) bedPos = findBed(level, a.pos());
            if (bedPos != null) {
                npc.getNavigation().stop();
                npc.setPos(bedPos.getX() + 0.5, bedPos.getY() + 0.6, bedPos.getZ() + 0.5);
                npc.startSleeping(bedPos);
            }
        }
        if (pose.frozen) npc.setNoAi(true);
        else if (pose == Tableau.Pose.GUARD) npc.restrictTo(pos, 5);
        return level.addFreshEntity(npc);
    }

    @Nullable
    private static BlockPos findBed(ServerLevel level, BlockPos near) {
        // anchors are recorded without a level, so their height is the plan's: look a few blocks up and down
        for (int dy = -5; dy <= 5; dy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = near.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof BedBlock) return p;
                }
        return null;
    }

    /** The nearest standable cell to an anchor (anchors are recorded without a level, so their y is approximate). */
    public static BlockPos settle(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return at;
        int[] order = {0, -1, 1, -2, 2, -3, 3, -4, 4, -6, 6};
        for (int r = 0; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : order) {
                        BlockPos p = at.offset(dx, dy, dz);
                        BlockPos below = p.below();
                        BlockState here = level.getBlockState(p), head = level.getBlockState(p.above());
                        if (level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                                && here.getCollisionShape(level, p).isEmpty()
                                && head.getCollisionShape(level, p.above()).isEmpty()
                                && level.getFluidState(p).isEmpty()
                                && !com.skycraft.society.NpcPlacement.isFireHazard(level, p)) {
                            return p;
                        }
                    }
                }
            }
        }
        return at;
    }

    // ------------------------------------------------------------------ census helpers

    /** The tableau actors and bodies of a settlement (loaded chunks only), by pose id. */
    public static List<Entity> actors(ServerLevel level, SettlementAnchors.Entry e) {
        AABB box = new AABB(e.x - e.radius - 40, level.getMinBuildHeight(), e.z - e.radius - 40, e.x + e.radius + 40,
                level.getMaxBuildHeight(), e.z + e.radius + 40);
        return new ArrayList<>(level.getEntitiesOfClass(Entity.class, box, x -> x.getPersistentData().contains(Tableau.KEY)));
    }

    /** Convenience for commands: the settlement entry by place id. */
    public static Optional<SettlementAnchors.Entry> entry(ServerLevel level, String id) {
        return SettlementAnchors.find(level, id);
    }

    /** True when a corpse is one of ours. */
    public static boolean isSceneBody(Entity e) {
        return e instanceof CorpseEntity && e.getPersistentData().contains(Tableau.KEY);
    }
}
