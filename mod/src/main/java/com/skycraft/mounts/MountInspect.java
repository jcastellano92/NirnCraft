package com.skycraft.mounts;

import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looking at a mount: who may know what. The client asks for the animal under its crosshair
 * ({@link MountPackets.Inspect}); the server checks the distance and what the animal is, decides from the viewer's
 * Riding perks what to tell and answers with a {@link MountView}.
 *
 * <ul>
 *     <li><b>Horse Sense</b> turns words into grades and numbers. Without it a mount is "swift and sturdy".</li>
 *     <li><b>Wild Eye</b> shows what a wild or a stranger's mount is, from afar ({@link #EYE_RANGE}, else
 *     {@link #RANGE}). Without it a wild animal is just an animal.</li>
 *     <li>A stranger's animal is judged by eye: with Riding under {@value #JUDGE_SKILL} its grades are an estimate of
 *     two neighbouring grades. Your own mount, and a stable's stock, are known exactly.</li>
 * </ul>
 */
public final class MountInspect {
    public static final double RANGE = 6;
    public static final double EYE_RANGE = 16;
    /** Riding skill at which strangers' grades are read exactly. */
    public static final int JUDGE_SKILL = 40;
    /** Ticks between two requests of one player that are answered. */
    private static final int MIN_GAP = 5;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    private MountInspect() {}

    public static double range(Player p) {
        return Perks.has(p, "riding.wild_eye") ? EYE_RANGE : RANGE;
    }

    public static void forget(ServerPlayer p) {
        LAST.remove(p.getUUID());
        SPOTTING.remove(p.getUUID());
    }

    /** The kind of creature a card may describe: an owned mount, or a creature that can be tamed into one. */
    @Nullable
    public static MountKind kindOf(Entity e) {
        MountKind k = Mounts.kindOf(e);
        if (k != null) return k;
        return e instanceof Mob m ? MountTaming.wildKind(m) : null;
    }

    // ------------------------------------------------------------------ the target card

    /** C2S handler: validates and answers one look. */
    public static void handleRequest(ServerPlayer p, int entityId) {
        long now = p.level().getGameTime();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < MIN_GAP && now >= last) return; // rate limit
        LAST.put(p.getUUID(), now);
        Entity e = p.level().getEntity(entityId);
        if (!allowed(p, e)) return;
        Mob mob = (Mob) e;
        MountKind kind = kindOf(mob);
        boolean own = Mounts.isOwner(p, mob);
        MountView view;
        if (!own && !Perks.has(p, "riding.wild_eye")) {
            view = MountView.plain("", mob.getHealth(), mob.getMaxHealth());
        } else {
            view = viewOf(p, mob, kind);
        }
        SkyNetwork.sendToPlayer(p, new MountPackets.Card(entityId, view));
    }

    /**
     * Whether the server answers a look at this entity: a living mount-like creature in the player's level, within
     * {@link #range} (plus a little slack for latency) of them, and not the one they sit on. The client's own raycast
     * is only a request.
     */
    public static boolean allowed(ServerPlayer p, @Nullable Entity e) {
        if (!(e instanceof Mob mob) || !mob.isAlive() || e == p.getVehicle() || e.level() != p.level()) return false;
        double max = range(p) + 1.5;
        if (p.distanceToSqr(mob) > max * max) return false;
        return kindOf(mob) != null;
    }

    /** What the viewer may learn of an animal standing in the world. */
    public static MountView viewOf(ServerPlayer viewer, Mob mob, MountKind kind) {
        boolean own = Mounts.isOwner(viewer, mob);
        MountStats s = MountStats.of(mob, kind);
        if (own) {
            MountStats rec = Mounts.isActive(viewer, mob) ? MountGrowth.known(viewer, kind) : null;
            if (rec != null) s = rec;
        }
        UUID owner = Mounts.ownerOf(mob);
        String ownerName = "";
        if (owner != null && !own) {
            Player o = viewer.level().getPlayerByUUID(owner);
            ownerName = o != null ? o.getGameProfile().name() : "";
        }
        String name = mob.hasCustomName() ? mob.getCustomName().getString() : "";
        return build(viewer, kind, s, own ? 1 : owner == null ? 0 : 2, ownerName, name, MountGear.saddle(mob), MountGear.barding(mob),
                MountGear.carryKg(mob, kind), MountGear.bagCapacity(mob, kind), mob.getHealth(), mob.getMaxHealth(),
                MountStats.seedOf(mob.getUUID(), kind));
    }

    /**
     * The player's own active mount, from its record (it may be parked far away, or not loaded): what a stable master
     * shows next to the stock. Null when the player has none.
     */
    @Nullable
    public static MountView ownView(ServerPlayer p) {
        MountKind kind = Mounts.active(p);
        MountStats s = kind == null ? null : MountGrowth.known(p, kind);
        if (kind == null || s == null) return null;
        Entity e = Mounts.activeEntity(p);
        if (e instanceof Mob m && !m.level().isClientSide()) return viewOf(p, m, kind);
        SaddleType saddle = MountGear.remembered(p, kind);
        ItemStack barding = ItemStack.EMPTY;
        String b = Mounts.data(p).getStringOr("barding","");
        if (!b.isEmpty()) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(b));
            if (item != null) barding = new ItemStack(item);
        }
        float pack = MountGrowth.packMult(p);
        float carry = (MountGear.baseCarry(kind) + (saddle == null ? 0f : saddle.carryKg)) * s.mult(MountProfile.CARRY) * pack;
        float bags = MountGear.bagKg(kind, saddle) * s.mult(MountProfile.CARRY) * pack;
        float hp = (float) (MountProfile.baseHealth(kind) * s.mult(MountProfile.HEALTH));
        return build(p, kind, s, 1, "", "", saddle, barding, carry, bags, hp, hp, 0L);
    }

    /** A stable's animal for the comparison screen. */
    public static MountView offerView(ServerPlayer viewer, MountKind kind, MountStats s, String name) {
        SaddleType plain = SaddleType.PLAIN;
        float carry = (MountGear.baseCarry(kind) + plain.carryKg) * s.mult(MountProfile.CARRY) * MountGrowth.packMult(viewer);
        float bags = MountGear.bagKg(kind, plain) * s.mult(MountProfile.CARRY) * MountGrowth.packMult(viewer);
        float hp = (float) (MountProfile.baseHealth(kind) * s.mult(MountProfile.HEALTH));
        return build(viewer, kind, s, 3, "", name, plain, ItemStack.EMPTY, carry, bags, hp, hp, 0L);
    }

    public static MountView build(ServerPlayer viewer, MountKind kind, MountStats s, int owner, String ownerName, String name,
                                  @Nullable SaddleType saddle, ItemStack barding, float carryKg, float bagKg, float health,
                                  float maxHealth, long salt) {
        boolean sense = Perks.has(viewer, "riding.horse_sense");
        boolean known = owner == 1 || owner == 3;
        int detail = !sense ? 0 : known ? 2 : 1;
        boolean exact = known || SkyData.get(viewer).getSkill(Skill.RIDING) >= JUDGE_SKILL;
        byte[] lo = new byte[MountView.STATS], hi = new byte[MountView.STATS];
        for (int i = 0; i < MountView.STATS; i++) {
            int g = s.grade(i);
            if (exact || detail == 0) {
                lo[i] = hi[i] = (byte) g;
            } else {
                // an estimate: the true grade and the neighbour on the side the viewer's eye is off by
                boolean up = ((salt >> i) & 1L) == 0L ? g < MountStats.GRADES - 1 : g == 0;
                lo[i] = (byte) (up ? g : g - 1);
                hi[i] = (byte) (up ? g + 1 : g);
            }
        }
        float[] value = new float[MountProfile.N];
        java.util.Arrays.fill(value, MountView.HIDDEN);
        byte[] growth = new byte[MountProfile.N], caps = new byte[MountProfile.N];
        if (detail >= 2) {
            float stamina = kind.stamina * (saddle == null ? 1f : saddle.staminaMult) * s.mult(MountProfile.STAMINA) / 20f;
            value[MountProfile.SPEED] = (float) MountProfile.speedBps(kind, s.speedMult() * (saddle == null ? 1f : 1f + saddle.speedMod));
            value[MountProfile.STAMINA] = stamina;
            value[MountProfile.HEALTH] = (float) (MountProfile.baseHealth(kind) * s.mult(MountProfile.HEALTH));
            value[MountProfile.JUMP] = (float) MountProfile.jumpHeight(MountProfile.baseJump(kind) * s.mult(MountProfile.JUMP));
            value[MountProfile.CARRY] = carryKg;
            for (int i = 0; i < MountProfile.N; i++) {
                growth[i] = s.grow[i];
                caps[i] = (byte) s.cap(i);
            }
        }
        int armor = 0;
        if (!barding.isEmpty()) armor = MountItems.isHorseArmor(barding) ? MountItems.armorProtection(barding) : 8;
        float trust = detail >= 2 && known ? s.trust : MountView.HIDDEN;
        return new MountView(kind.id, name, (byte) owner, ownerName, (byte) detail, (byte) s.level(), s.levelProgress(), lo, hi, value,
                growth, caps, trust, saddle == null ? "" : saddle.id, armor, bagKg, health, maxHealth);
    }

    // ------------------------------------------------------------------ the compass (Wild Eye)

    /** Players whose compass currently shows wild mounts (so it is cleared once when the perk or the animals are gone). */
    private static final java.util.Set<UUID> SPOTTING = ConcurrentHashMap.newKeySet();

    /**
     * Every few seconds for a rider with Wild Eye: the wild mounts within 48 blocks (the nearest dozen) go to their
     * compass, with their grade when they also have Horse Sense.
     */
    public static void spot(ServerPlayer p) {
        boolean eye = Perks.has(p, "riding.wild_eye");
        if (!eye) {
            if (SPOTTING.remove(p.getUUID())) SkyNetwork.sendToPlayer(p, new MountPackets.Spotted(java.util.List.of()));
            return;
        }
        boolean sense = Perks.has(p, "riding.horse_sense");
        java.util.List<Mob> near = p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48, 24, 48),
                m -> m.isAlive() && Mounts.ownerOf(m) == null && !(m instanceof MountBeast) && MountTaming.wildKind(m) != null);
        near.sort(java.util.Comparator.comparingDouble(p::distanceToSqr));
        java.util.List<MountPackets.Spot> spots = new java.util.ArrayList<>();
        for (Mob m : near) {
            if (spots.size() >= 12) break;
            MountKind kind = MountTaming.wildKind(m);
            int grade = -1;
            if (sense) {
                MountStats s = MountStats.of(m, kind);
                grade = Math.round(s.quality() * 2f + 2f);
                grade = Math.max(0, Math.min(4, grade));
            }
            spots.add(new MountPackets.Spot(m.getX(), m.getY(), m.getZ(), kind.id, grade));
        }
        if (spots.isEmpty() && !SPOTTING.contains(p.getUUID())) return;
        if (spots.isEmpty()) SPOTTING.remove(p.getUUID());
        else SPOTTING.add(p.getUUID());
        SkyNetwork.sendToPlayer(p, new MountPackets.Spotted(spots));
    }

    // ------------------------------------------------------------------ the sheet

    /** Sends the full sheet of the player's mount ahead of its menu ({@code open}: start the screen on the sheet). */
    public static void sendSheet(ServerPlayer player, Mob mount, MountKind kind, boolean open) {
        SkyNetwork.sendToPlayer(player, new MountPackets.Sheet(viewOf(player, mount, kind), open));
    }
}
