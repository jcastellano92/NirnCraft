package com.skycraft.realms;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.arcane.item.SoulGemItem;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.party.Parties;
import com.skycraft.quest.party.Party;
import com.skycraft.realms.block.BlackBookBlock;
import com.skycraft.realms.block.RealmPortalBlock;
import com.skycraft.realms.gate.OblivionGates;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.world.FastTravelHandler;
import com.skycraft.world.WorldPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Travel between Nirn and the realms (server side).
 *
 * <ul>
 *     <li><b>Return points.</b> Whenever a player leaves a non-realm dimension for a realm (gate, door, book, quest,
 *     vanilla portal, party travel, command), {@code RealmEvents} stores where they stood in the player's
 *     {@code realms} data; every way out ({@link #returnHome}) leads back there (stepping clear of the gate), or to their
 *     spawn when there is none.</li>
 *     <li><b>Arrivals</b> are each realm's fixed landing ({@link RealmLayout}); the Deadlands' depends on the gate:
 *     every Oblivion gate opens onto its own keep. The landing chunk is generated before the move, the landing spot is
 *     checked for safety (a small platform is built if it isn't), and a fade hides the jump.</li>
 *     <li><b>Parties.</b> Party members standing right beside a traveller go along; the Sigil Stone sends everybody on
 *     its island home.</li>
 * </ul>
 */
public final class RealmTravel {
    public static final String MODULE = "realms";
    private static final double FOLLOW_RANGE = 6.0;

    private RealmTravel() {}

    /** Where a traveller lands in a realm: a column and a facing. */
    public record Arrival(int x, int z, float yaw) {}

    // ------------------------------------------------------------------ entry points (blocks, items, quests)

    /** An Oblivion gate's fire, a Soul Cairn or Coldharbour portal, Sovngarde's golden way. */
    public static void usePortal(ServerPlayer p, Realm realm, boolean ret, BlockPos pos) {
        if (p.isOnPortalCooldown()) {
            p.setPortalCooldown();
            return;
        }
        if (ret) {
            leaveWithCompanions(p);
            return;
        }
        ServerLevel from = p.serverLevel();
        switch (realm) {
            case DEADLANDS -> OblivionGates.enterGate(p, pos);
            case SOUL_CAIRN -> {
                if (!payToll(p)) {
                    p.setPortalCooldown();
                    return;
                }
                enterWithCompanions(p, Realm.SOUL_CAIRN, arrival(Realm.SOUL_CAIRN, p, 0), from);
            }
            default -> enterWithCompanions(p, realm, arrival(realm, p, 0), from);
        }
    }

    /** The Door to the Shivering Isles. */
    public static void useDoor(ServerPlayer p, Realm realm, boolean ret, BlockPos pos) {
        if (p.isOnPortalCooldown()) return;
        if (ret) {
            leaveWithCompanions(p);
            return;
        }
        p.level().playSound(null, pos, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1f, 0.6f);
        enterWithCompanions(p, realm, arrival(realm, p, 0), p.serverLevel());
    }

    /** A Black Book open on a pedestal. */
    public static void readBlackBook(ServerPlayer p, BlackBookBlock.Mode mode, BlockPos pos) {
        if (p.isOnPortalCooldown()) return;
        p.level().playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 0.7f);
        switch (mode) {
            case EXIT -> leaveWithCompanions(p);
            case NEXT -> {
                int cx = Math.floorDiv(pos.getX() + RealmLayout.AP_SPACING / 2, RealmLayout.AP_SPACING);
                int next = cx >= RealmLayout.APOCRYPHA_CHAPTERS || cx < 1 ? 0 : cx + 1;
                apocryphaEffects(p);
                enterWithCompanions(p, Realm.APOCRYPHA, chapterArrival(next), p.serverLevel());
            }
            case ENTER -> {
                apocryphaEffects(p);
                enterWithCompanions(p, Realm.APOCRYPHA, chapterArrival(altarChapter(pos)), p.serverLevel());
            }
        }
    }

    /** A Black Book read from the inventory. */
    public static void readBlackBookItem(ServerPlayer p, int chapter) {
        if (p.isOnPortalCooldown()) {
            Notifier.message(p, Component.translatable("realm.skycraft.book.wait"));
            return;
        }
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 0.6f);
        apocryphaEffects(p);
        enterWithCompanions(p, Realm.APOCRYPHA, chapterArrival(chapter), p.serverLevel());
    }

    /**
     * Quest travel ({@code teleport_dim}): into a realm at its landing (the Deadlands at the nearest gate's keep),
     * anywhere else the vanilla way.
     */
    public static void travel(ServerPlayer p, ServerLevel dest) {
        Realm realm = Realm.of(dest.dimension());
        if (realm == null) {
            if (Realm.of(p.level()) != null && dest.dimension() == Level.OVERWORLD) {
                returnHome(p);
            } else {
                p.changeDimension(dest);
            }
            return;
        }
        if (realm == Realm.DEADLANDS) {
            OblivionGates.enterNear(p);
            return;
        }
        if (realm == Realm.APOCRYPHA) {
            // Waking Dreams opens on the Summit; every other Black Book on its first chapter
            int chapter = com.skycraft.quest.story.Story.test(p, "active:sol_summit_of_apocrypha") ? 0 : 1;
            apocryphaEffects(p);
            enter(p, realm, chapterArrival(chapter));
            return;
        }
        enter(p, realm, arrival(realm, p, 0));
    }

    // ------------------------------------------------------------------ arrivals

    public static Arrival arrival(Realm realm, ServerPlayer p, int param) {
        return switch (realm) {
            case DEADLANDS -> {
                int cx = RealmLayout.deadlandsCellX(p.getBlockX()), cz = RealmLayout.deadlandsCellZ(p.getBlockZ());
                yield new Arrival(RealmLayout.keepX(cx), RealmLayout.keepZ(cz) + RealmLayout.DL_ARRIVAL_DZ, 180f);
            }
            case SOVNGARDE -> new Arrival(0, RealmLayout.SG_ARRIVAL_Z, 180f);
            case SHIVERING_ISLES -> new Arrival(RealmLayout.SI_FRINGE_X, 0, 90f);
            case APOCRYPHA -> chapterArrival(param);
            case SOUL_CAIRN -> new Arrival(0, RealmLayout.SC_ARRIVAL_Z, 180f);
            case COLDHARBOUR -> new Arrival(0, RealmLayout.CH_ARRIVAL_Z, 180f);
        };
    }

    /** Chapter 0 is the Summit (cell 0, 0); chapters 1.. lie along +x. */
    public static Arrival chapterArrival(int chapter) {
        int c = Math.max(0, Math.min(RealmLayout.APOCRYPHA_CHAPTERS, chapter));
        return new Arrival(RealmLayout.chapterX(c), RealmLayout.chapterZ(0), 180f);
    }

    /** The chapter a Solstheim altar's book opens: from the nearest altar place's id, else from the position. */
    static int altarChapter(BlockPos pos) {
        for (Place pl : Places.overlapping(pos.getX() - 48, pos.getZ() - 48, pos.getX() + 48, pos.getZ() + 48)) {
            if (pl.type() != PlaceType.LANDMARK || !"black_book_altar".equals(pl.variant())) continue;
            String id = pl.id();
            if (id.contains("epistolary")) return 1;
            if (id.contains("sallow")) return 2;
            if (id.contains("filament")) return 3;
            if (id.contains("untold")) return 4;
            if (id.contains("winds")) return 5;
        }
        return 1 + Math.floorMod((pos.getX() >> 4) * 31 + (pos.getZ() >> 4), RealmLayout.APOCRYPHA_CHAPTERS);
    }

    // ------------------------------------------------------------------ moving people

    /** Enters a realm with the party members right beside the traveller. */
    static void enterWithCompanions(ServerPlayer p, Realm realm, Arrival a, ServerLevel from) {
        List<ServerPlayer> companions = companions(p);
        enter(p, realm, a);
        int i = 0;
        for (ServerPlayer c : companions) {
            i++;
            final int k = i;
            schedule(p.server, 2 * k, () -> {
                if (c.isAlive() && c.level() == from) enter(c, realm, new Arrival(a.x() + (k % 2 == 0 ? 2 : -2), a.z() + k / 2, a.yaw()));
            });
        }
    }

    /** Leaves for home with the party members right beside the traveller (each to their own return point). */
    static void leaveWithCompanions(ServerPlayer p) {
        List<ServerPlayer> companions = companions(p);
        returnHome(p);
        for (ServerPlayer c : companions) schedule(p.server, 3, () -> {
            if (c.isAlive() && Realm.of(c.level()) != null) returnHome(c);
        });
    }

    static List<ServerPlayer> companions(ServerPlayer p) {
        List<ServerPlayer> out = new ArrayList<>();
        Party party = Parties.partyOf(p);
        if (party == null) return out;
        for (ServerPlayer m : Parties.onlineMembers(p.server, party)) {
            if (m != p && m.level() == p.level() && m.isAlive() && !m.isSpectator() && m.distanceTo(p) <= FOLLOW_RANGE) out.add(m);
        }
        return out;
    }

    /** Moves a player into a realm at an arrival column (generates the landing first; builds a footing if unsafe). */
    public static boolean enter(ServerPlayer p, Realm realm, Arrival a) {
        ServerLevel level = p.server.getLevel(realm.dimension);
        if (level == null) {
            Skycraft.LOGGER.warn("Realm {} has no dimension loaded", realm.id);
            Notifier.message(p, Component.translatable("realm.skycraft.missing", realm.displayName()));
            return false;
        }
        level.getChunk(a.x() >> 4, a.z() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a.x(), a.z());
        BlockPos spot = safeSpot(level, new BlockPos(a.x(), y, a.z()), true, realm);
        teleport(p, level, spot, a.yaw());
        return true;
    }

    /** Back to the return point (or the player's spawn), never into the gate itself. */
    public static void returnHome(ServerPlayer p) {
        CompoundTag d = data(p);
        ServerLevel level = null;
        BlockPos pos = null;
        float yaw = p.getYRot();
        if (d.contains("ret")) {
            CompoundTag r = d.getCompound("ret");
            ResourceLocation dim = ResourceLocation.tryParse(r.getString("dim"));
            if (dim != null) level = p.server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
            if (level != null && Realm.of(level.dimension()) != null) level = null;
            pos = new BlockPos(r.getInt("x"), r.getInt("y"), r.getInt("z"));
            yaw = r.getFloat("yaw") + 180f;
        }
        if (level == null) {
            level = p.server.getLevel(p.getRespawnDimension());
            if (level == null || Realm.of(level.dimension()) != null) level = p.server.overworld();
            BlockPos respawn = p.getRespawnPosition();
            pos = respawn != null && level.dimension() == p.getRespawnDimension() ? respawn : level.getSharedSpawnPos();
        }
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        BlockPos spot = safeSpot(level, pos, false, null);
        d.remove("gate");
        SkyData.get(p).markDirty();
        teleport(p, level, spot, yaw);
    }

    static void teleport(ServerPlayer p, ServerLevel level, BlockPos spot, float yaw) {
        p.stopRiding();
        SkyNetwork.sendToPlayer(p, new WorldPackets.RestFade(0, WorldPackets.RestFade.TRAVEL));
        p.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, 0f);
        p.setPortalCooldown();
        p.resetFallDistance();
        // the client loses its effect list on a dimension change by teleport
        for (MobEffectInstance e : p.getActiveEffects()) p.connection.send(new ClientboundUpdateMobEffectPacket(p.getId(), e));
        level.playSound(null, spot, SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.25f, 1.4f);
    }

    /**
     * A safe standing spot near {@code pos}: solid floor, two free blocks, no fluid, and (when {@code avoidPortals})
     * not in or right next to a gate. Falls back to building a small footing of the realm's stone.
     */
    static BlockPos safeSpot(ServerLevel level, BlockPos pos, boolean arriving, @Nullable Realm realm) {
        for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy <= 6; dy++) {
                        for (int s = -1; s <= 1; s += 2) {
                            if (dy == 0 && s > 0) continue;
                            BlockPos c = pos.offset(dx, dy * -s, dz);
                            if (!level.isLoaded(c)) continue;
                            if (FastTravelHandler.isSafe(level, c) && !nearPortal(level, c)) return c;
                        }
                    }
                }
        }
        // nothing safe (lava sea, open air): a footing of stone
        BlockPos base = new BlockPos(pos.getX(), Math.max(pos.getY(), level.getSeaLevel() + 1), pos.getZ());
        BlockState stone = (realm == Realm.DEADLANDS ? RealmBlocks.DAEDRIC_BRICKS.get() : RealmBlocks.COLDHARBOUR_STONE.get()).defaultBlockState();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), stone);
                level.removeBlock(base.offset(dx, 0, dz), false);
                level.removeBlock(base.offset(dx, 1, dz), false);
            }
        return base;
    }

    private static boolean nearPortal(ServerLevel level, BlockPos c) {
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-2, -1, -2), c.offset(2, 2, 2))) {
            if (level.getBlockState(q).getBlock() instanceof RealmPortalBlock) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the Soul Cairn's toll

    /**
     * The first passage into the Soul Cairn costs a filled soul gem (the Ideal Masters take their due); afterwards the
     * portal knows you.
     */
    static boolean payToll(ServerPlayer p) {
        CompoundTag d = data(p);
        if (d.getBoolean("soul_toll") || p.getAbilities().instabuild) return true;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.getItem() instanceof SoulGemItem && SoulGemItem.getSoul(s) > 0) {
                s.shrink(1);
                d.putBoolean("soul_toll", true);
                SkyData.get(p).markDirty();
                Notifier.message(p, Component.translatable("realm.skycraft.soul_cairn.toll_paid"));
                p.level().playSound(null, p.blockPosition(), SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 1f, 0.6f);
                return true;
            }
        }
        Notifier.message(p, Component.translatable("realm.skycraft.soul_cairn.toll"));
        return false;
    }

    private static void apocryphaEffects(ServerPlayer p) {
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
    }

    // ------------------------------------------------------------------ player data

    public static CompoundTag data(ServerPlayer p) {
        return SkyData.get(p).module(MODULE);
    }

    /** Remembers where a player left from (called before every move into a realm from outside the realms). */
    public static void rememberReturn(ServerPlayer p) {
        CompoundTag r = new CompoundTag();
        r.putString("dim", p.level().dimension().location().toString());
        r.putInt("x", p.getBlockX());
        r.putInt("y", p.getBlockY());
        r.putInt("z", p.getBlockZ());
        r.putFloat("yaw", p.getYRot());
        PlayerData pd = SkyData.get(p);
        pd.module(MODULE).put("ret", r);
        pd.markDirty();
    }

    // ------------------------------------------------------------------ scheduled work

    private record Task(MinecraftServer server, long due, Runnable run) {}

    private static final List<Task> TASKS = new ArrayList<>();

    /** Runs {@code run} on the server thread after {@code ticks} ticks. */
    public static void schedule(MinecraftServer server, int ticks, Runnable run) {
        synchronized (TASKS) {
            TASKS.add(new Task(server, server.getTickCount() + Math.max(1, ticks), run));
        }
    }

    static void tick(MinecraftServer server) {
        List<Task> due = new ArrayList<>();
        synchronized (TASKS) {
            if (TASKS.isEmpty()) return;
            for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
                Task t = it.next();
                if (t.server != server) {
                    it.remove();
                } else if (server.getTickCount() >= t.due) {
                    due.add(t);
                    it.remove();
                }
            }
        }
        for (Task t : due) {
            try {
                t.run.run();
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Realm travel task failed", e);
            }
        }
    }
}
