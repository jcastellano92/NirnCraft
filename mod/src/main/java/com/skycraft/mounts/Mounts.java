package com.skycraft.mounts;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.Bestiary;
import com.skycraft.creatures.entity.GuarEntity;
import com.skycraft.creatures.entity.SkyCreature;
import com.skycraft.fauna.HorseBreed;
import com.skycraft.fauna.HorseManager;
import com.skycraft.fauna.Livestock;
import com.skycraft.fauna.WildHorseTaming;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owned mounts. Every player has one active mount (called with H, {@link #call}) and a stable of others they own
 * (switched at any stablemaster). The record lives in {@code module("mounts")}: {@code active} kind id, {@code uuid}
 * of the entity in the world, {@code stable} (owned kind ids), {@code bags} (saddlebag items, {@link Saddlebags}),
 * {@code barding} (item id worn by the active mount), {@code cooldown} (game time a fallen mount may return).
 *
 * <p>Mount entities carry {@link #KIND} and {@link #OWNER} ({@code "player:<uuid>"}, the crime module's entity-owner
 * contract). A stale copy of an owner's mount (the old one, left in an unloaded chunk before it was called or
 * switched) is removed when it loads again.</p>
 *
 * <p>Quest hooks: {@link #grant} (or the story flags {@code mount_shadowmere} / {@code mount_arvak}, granted on the
 * next tick, and the items {@code skycraft:shadowmere_bridle} / {@code skycraft:arvaks_skull}).</p>
 */
public final class Mounts {
    public static final String MODULE = "mounts";
    public static final String KIND = "skycraft_mount";
    public static final String OWNER = "skycraft_owner";
    public static final long DEATH_COOLDOWN_TICKS = 1200;

    private Mounts() {}

    // ------------------------------------------------------------------ the record

    public static CompoundTag data(Player player) {
        PlayerData d = SkyData.get(player);
        CompoundTag tag = d.module(MODULE);
        migrate(player, d, tag);
        return tag;
    }

    /** The fauna module's old single-horse record becomes the active mount. */
    private static void migrate(Player player, PlayerData d, CompoundTag tag) {
        if (tag.getBoolean("migrated")) return;
        tag.putBoolean("migrated", true);
        CompoundTag old = d.module(HorseManager.MODULE);
        if (old.hasUUID(HorseManager.HORSE_UUID) && !tag.contains("active")) {
            MountKind k = MountKind.forBreed(HorseBreed.byId(old.getString(HorseManager.HORSE_BREED)));
            if (k != null) {
                tag.putString("active", k.id);
                tag.putUUID("uuid", old.getUUID(HorseManager.HORSE_UUID));
                addToStable(tag, k);
            }
        }
        d.markDirty();
    }

    @Nullable
    public static MountKind active(Player player) {
        return MountKind.byId(data(player).getString("active"));
    }

    public static List<MountKind> stable(Player player) {
        List<MountKind> out = new ArrayList<>();
        for (Tag t : data(player).getList("stable", Tag.TAG_STRING)) {
            MountKind k = MountKind.byId(t.getAsString());
            if (k != null && !out.contains(k)) out.add(k);
        }
        return out;
    }

    public static boolean owns(Player player, MountKind kind) {
        return stable(player).contains(kind);
    }

    private static void addToStable(CompoundTag tag, MountKind kind) {
        ListTag list = tag.getList("stable", Tag.TAG_STRING);
        for (Tag t : list) if (t.getAsString().equals(kind.id)) return;
        list.add(StringTag.valueOf(kind.id));
        tag.put("stable", list);
    }

    @Nullable
    public static Entity activeEntity(ServerPlayer player) {
        CompoundTag tag = data(player);
        if (!tag.hasUUID("uuid")) return null;
        Entity e = player.serverLevel().getEntity(tag.getUUID("uuid"));
        return e != null && e.isAlive() ? e : null;
    }

    // ------------------------------------------------------------------ entity facts

    @Nullable
    public static MountKind kindOf(@Nullable Entity e) {
        if (e == null) return null;
        if (e instanceof MountBeast b) return b.kind();
        MountKind k = MountKind.byId(e.getPersistentData().getString(KIND));
        if (k != null) return k;
        if (e instanceof Horse h && h.getPersistentData().contains("skycraft_breed")) {
            return MountKind.forBreed(HorseBreed.byId(h.getPersistentData().getString("skycraft_breed")));
        }
        return null;
    }

    /** True for the entities the mount system rides: owned mounts, and every horse, camel and saddled guar. */
    public static boolean isMount(Entity e) {
        return kindOf(e) != null || e instanceof AbstractHorse || e instanceof GuarEntity g && g.isSaddled();
    }

    @Nullable
    public static UUID ownerOf(Entity e) {
        String s = e.getPersistentData().getString(OWNER);
        if (s.startsWith("player:")) {
            try {
                return UUID.fromString(s.substring(7));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (e instanceof OwnableEntity o && e instanceof AbstractHorse) return o.getOwnerUUID();
        return null;
    }

    public static boolean isOwner(Player p, Entity e) {
        UUID o = ownerOf(e);
        return o != null && o.equals(p.getUUID());
    }

    /** Only the owner steers (creative players too). */
    public static boolean mayControl(Player p, Entity mount) {
        return p.isCreative() || isOwner(p, mount);
    }

    /** The owner, or a party or faction friend of the owner (they may ride behind them, the mount won't bite them). */
    public static boolean isFriend(Entity mount, Entity other) {
        if (!(other instanceof Player p)) return false;
        UUID owner = ownerOf(mount);
        if (owner == null) return false;
        if (owner.equals(p.getUUID())) return true;
        Player o = p.level().getPlayerByUUID(owner);
        return o != null && HorseManager.isSameFactionOrParty(p, o);
    }

    // ------------------------------------------------------------------ creating mounts

    /** Creates (not yet added) the entity of a mount kind at a position, saddled and owned by {@code owner}. */
    @Nullable
    public static Mob create(ServerLevel level, MountKind kind, double x, double y, double z, float yaw, @Nullable ServerPlayer owner) {
        Mob mob = switch (kind.backing) {
            case HORSE -> {
                Horse h = EntityType.HORSE.create(level);
                if (h != null) kind.breed.apply(h);
                yield h;
            }
            case SKELETON -> {
                AbstractHorse h = EntityType.SKELETON_HORSE.create(level);
                if (h != null) kind.breed.applyStats(h);
                yield h;
            }
            case CAMEL -> {
                Camel c = EntityType.CAMEL.create(level);
                if (c != null) {
                    c.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
                    c.setHealth(40);
                }
                yield c;
            }
            case GUAR -> {
                GuarEntity g = Bestiary.GUAR.get().create(level);
                if (g != null) {
                    g.setVariant(1);
                    // a pack guar: hardier and steadier than a wild one
                    g.getAttribute(Attributes.MAX_HEALTH).setBaseValue(45);
                    g.setHealth(45);
                    g.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.21);
                }
                yield g;
            }
            case BEAST -> {
                EntityType<MountBeast> t = MountEntities.type(kind);
                MountBeast b = t == null ? null : t.create(level);
                if (b != null) b.setVariant(1);
                yield b;
            }
        };
        if (mob == null) return null;
        mob.moveTo(x, y, z, yaw, 0f);
        if (mob instanceof SkyCreature) mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), MobSpawnType.EVENT, null, null);
        if (mob instanceof SkyCreature sc && kind.backing != MountKind.Backing.BEAST) sc.setVariant(1);
        if (mob instanceof MountBeast b) b.setVariant(1);
        mob.getPersistentData().putString(KIND, kind.id);
        mob.setPersistenceRequired();
        if (mob instanceof AbstractHorse h) {
            if (owner != null) h.tameWithName(owner);
            else h.setTamed(true);
            h.equipSaddle(SoundSource.NEUTRAL);
        }
        // the tack the player left this kind of mount in (a bought one comes in a plain saddle); the legends wear their own
        MountGear.setSaddle(mob, owner != null && !MountGear.fixedTack(kind) ? MountGear.remembered(owner, kind) : SaddleType.PLAIN);
        if (owner != null) setOwner(mob, owner);
        if (kind == MountKind.SHADOWMERE || kind == MountKind.ARVAK) mob.setCustomName(kind.displayName());
        if (kind == MountKind.ARVAK) mob.setInvulnerable(false);
        return mob;
    }

    public static void setOwner(Entity mob, ServerPlayer owner) {
        mob.getPersistentData().putString(OWNER, "player:" + owner.getStringUUID());
        mob.getPersistentData().putBoolean(Livestock.PLAYER_OWNED, true);
        mob.removeTag(SkyCreature.REGION_SPAWN_TAG);
    }

    /** Spawns the player's active mount near {@code near} and records it. */
    @Nullable
    public static Mob spawnActive(ServerPlayer player, MountKind kind, BlockPos near) {
        ServerLevel level = player.serverLevel();
        BlockPos at = safeSpot(level, near);
        Mob mob = create(level, kind, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getYRot(), player);
        if (mob == null) return null;
        applyBarding(mob, data(player).getString("barding"));
        // a mount brought back from the stable or from afar is as winded as it was left
        if (data(player).contains(MountStamina.SAVED)) mob.getPersistentData().putFloat(MountStamina.TAG, data(player).getFloat(MountStamina.SAVED));
        level.addFreshEntity(mob);
        CompoundTag tag = data(player);
        tag.putString("active", kind.id);
        tag.putUUID("uuid", mob.getUUID());
        tag.remove("cooldown");
        SkyData.get(player).markDirty();
        return mob;
    }

    static BlockPos safeSpot(ServerLevel level, BlockPos near) {
        for (int r = 0; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = near.offset(dx, 0, dz);
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos q = p.above(dy);
                        if (level.getBlockState(q).isAir() && level.getBlockState(q.above()).isAir()
                                && level.getBlockState(q.above(2)).isAir() && !level.getBlockState(q.below()).isAir()) return q;
                    }
                }
            }
        }
        return near;
    }

    /**
     * Gives a player a mount kind: it joins their stable, becomes active and appears beside them (or beside the
     * stablemaster {@code at}). Used by stables, taming, quests (Shadowmere) and Arvak's Skull.
     */
    @Nullable
    public static Mob grant(ServerPlayer player, MountKind kind, @Nullable BlockPos at) {
        CompoundTag tag = data(player);
        addToStable(tag, kind);
        dismissActive(player);
        Mob mob = spawnActive(player, kind, at != null ? at : player.blockPosition().relative(player.getDirection().getOpposite(), 2));
        if (kind == MountKind.BRETON_DESTRIER && mob instanceof Horse h) {
            // destriers come barded
            ItemStack barding = new ItemStack(MountItems.BRETON_BARDING.get());
            MountGear.setBarding(h, barding);
            tag.putString("barding", key(barding));
        }
        SkyData.get(player).addStat("mounts_owned", 1);
        SkyData.get(player).markDirty();
        return mob;
    }

    /** Removes the player's active mount from the world (it goes back to the stable); its barding comes back to you. */
    public static void dismissActive(ServerPlayer player) {
        CompoundTag tag = data(player);
        Entity e = activeEntity(player);
        ItemStack refund = ItemStack.EMPTY;
        if (e != null) {
            e.ejectPassengers();
            if (e instanceof Mob m) {
                refund = MountGear.barding(m);
                if (!refund.isEmpty()) MountGear.setBarding(m, ItemStack.EMPTY);
            }
            e.discard();
        } else if (!tag.getString("barding").isEmpty()) {
            // the mount is far away (its copy is dropped when it loads): the barding it wore
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(tag.getString("barding")));
            if (item != null && item != Items.AIR) refund = new ItemStack(item);
        }
        if (!refund.isEmpty() && !player.getInventory().add(refund)) player.drop(refund, false);
        tag.remove("uuid");
        tag.remove("barding");
        tag.remove(MountStamina.SAVED);
    }

    /** Keeps the record of what the active horse wears (barding put on through the horse's own screen). */
    static void syncBarding(ServerPlayer player, Entity mount) {
        if (!(mount instanceof Horse h) || !isActive(player, mount)) return;
        CompoundTag tag = data(player);
        String now = h.isWearingArmor() ? key(h.getSlot(401).get()) : "";
        if (!now.equals(tag.getString("barding"))) {
            if (now.isEmpty()) tag.remove("barding");
            else tag.putString("barding", now);
        }
    }

    /** Makes a mount the player already owns the active one (at a stable). */
    public static void switchTo(ServerPlayer player, MountKind kind, BlockPos at) {
        if (!owns(player, kind)) return;
        dismissActive(player);
        spawnActive(player, kind, at);
        Notifier.message(player, Component.translatable("mounts.skycraft.switched", kind.displayName()));
    }

    /** A tamed wild mount joins the stable as the active one (the entity is already in the world). */
    public static void adopt(ServerPlayer player, Mob mob, MountKind kind) {
        CompoundTag tag = data(player);
        if (activeEntity(player) != mob) dismissActive(player);
        addToStable(tag, kind);
        mob.getPersistentData().putString(KIND, kind.id);
        mob.setPersistenceRequired();
        setOwner(mob, player);
        tag.putString("active", kind.id);
        tag.putUUID("uuid", mob.getUUID());
        tag.remove("barding");
        tag.remove("cooldown");
        tag.remove(MountStamina.SAVED);
        // the saddle it was broken in with (or the one a vanilla taming left it in) is its saddle from now on
        SaddleType saddle = MountGear.saddle(mob);
        MountGear.setSaddle(mob, saddle);
        MountGear.remember(player, kind, saddle);
        SkyData.get(player).addStat("mounts_owned", 1);
        SkyData.get(player).markDirty();
    }

    // ------------------------------------------------------------------ calling (H)

    public static void call(ServerPlayer player) {
        CompoundTag tag = data(player);
        MountKind kind = MountKind.byId(tag.getString("active"));
        if (kind == null) {
            Notifier.message(player, Component.translatable("mounts.skycraft.none"));
            return;
        }
        if (player.isPassenger()) return;
        long now = player.level().getGameTime();
        if (tag.contains("cooldown") && now < tag.getLong("cooldown") && kind != MountKind.ARVAK) {
            int secs = (int) Math.ceil((tag.getLong("cooldown") - now) / 20.0);
            Notifier.message(player, Component.translatable("fauna.skycraft.horse.cooldown", secs));
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = safeSpot(level, player.blockPosition().relative(player.getDirection().getOpposite(), 3));
        Entity existing = activeEntity(player);
        if (existing != null && existing.level() == level && existing.distanceToSqr(player) < 16 * 16) {
            // close by: it trots over
            if (existing instanceof Mob m) m.getNavigation().moveTo(player, 1.4);
        } else if (existing != null && existing.level() == level) {
            existing.ejectPassengers();
            existing.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (existing instanceof Mob m) m.getNavigation().stop();
        } else {
            if (existing != null) existing.discard();
            if (spawnActive(player, kind, target) == null) return;
        }
        if (kind == MountKind.ARVAK) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5, 30, 0.6, 0.8, 0.6, 0.02);
            level.playSound(null, target, SoundEvents.SKELETON_HORSE_AMBIENT, SoundSource.NEUTRAL, 1.0f, 0.7f);
        } else {
            level.playSound(null, player.blockPosition(), kind.horseLike() ? SoundEvents.HORSE_GALLOP : SoundEvents.HORSE_STEP, SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        Notifier.message(player, Component.translatable("mounts.skycraft.called", kind.displayName()));
    }

    public static void onDeath(LivingEntity mount) {
        UUID owner = ownerOf(mount);
        if (owner == null || mount.level().getServer() == null) return;
        ServerPlayer p = mount.level().getServer().getPlayerList().getPlayer(owner);
        if (p == null) return;
        CompoundTag tag = data(p);
        if (!tag.hasUUID("uuid") || !tag.getUUID("uuid").equals(mount.getUUID())) return;
        tag.putLong("cooldown", p.level().getGameTime() + (kindOf(mount) == MountKind.ARVAK ? 200 : DEATH_COOLDOWN_TICKS));
        tag.remove("uuid");
        tag.remove("barding"); // dropped where it fell
        SkyData.get(p).markDirty();
        MountKind kind = kindOf(mount);
        Notifier.message(p, Component.translatable("mounts.skycraft.died", kind != null ? kind.displayName() : mount.getName()));
    }

    /** A stale copy of a player's mount (not the recorded entity) is removed when it loads. */
    public static boolean isStale(Entity e) {
        if (!e.getPersistentData().contains(KIND) || e.level().getServer() == null) return false;
        UUID owner = ownerOf(e);
        if (owner == null) return false;
        ServerPlayer p = e.level().getServer().getPlayerList().getPlayer(owner);
        if (p == null) return false;
        CompoundTag tag = data(p);
        return tag.hasUUID("uuid") && !tag.getUUID("uuid").equals(e.getUUID());
    }

    // ------------------------------------------------------------------ interaction

    /**
     * Using a mount (the beasts' {@code mobInteract}, the Forge interact event for horses, camels and guar): taming a
     * wild one with a saddle, saddlebags (sneak), barding and saddles, and who may ride. True when handled.
     */
    public static boolean interact(ServerPlayer player, Mob mount, ItemStack held) {
        UUID owner = ownerOf(mount);
        MountKind kind = kindOf(mount);
        SaddleType saddle = SaddleType.of(held);
        if (owner == null) {
            // wild: a saddle in the hand, from a sneak, starts the taming (only a wild mount is saddled this way)
            MountKind wild = MountTaming.wildKind(mount);
            if (wild != null && saddle != null) {
                if (!MountGear.accepts(wild, saddle)) {
                    MountGear.say(player, MountGear.Result.WRONG_BREED);
                    return true;
                }
                return WildHorseTaming.tryStart(player, mount, held, wild, saddle);
            }
            if (mount instanceof MountBeast b) {
                // an unowned beast (spawned by an admin): the first rider with a saddle claims it
                if (saddle != null) {
                    if (MountGear.accepts(b.kind(), saddle)) MountGear.setSaddle(b, saddle);
                    adopt(player, b, b.kind());
                    if (!player.getAbilities().instabuild) held.shrink(1);
                    return true;
                }
                return false;
            }
            return false;
        }
        boolean own = owner.equals(player.getUUID()) || player.isCreative();
        if (!own) {
            Entity first = mount.getFirstPassenger();
            if (first instanceof Player rider && isFriend(mount, player) && mount.getPassengers().size() < Math.max(2, kind != null ? kind.seats : 1)) {
                player.startRiding(mount);
                return true;
            }
            Notifier.message(player, Component.translatable("fauna.skycraft.horse.locked"));
            return true;
        }
        if (player.isSecondaryUseActive()) {
            if (kind != null) Saddlebags.open(player, mount, kind);
            return kind != null;
        }
        // tack from the hand: only a mount that wears no saddle takes one this way (a saddled one is changed in its menu),
        // and barding goes straight on
        if (kind != null && saddle != null) {
            if (MountGear.saddle(mount) == null) MountGear.say(player, MountGear.equipSaddle(player, mount, kind, held));
            else Notifier.message(player, Component.translatable("mounts.skycraft.saddle.use_menu"));
            return true;
        }
        if (kind != null && MountGear.wearsBarding(mount, kind, held)) {
            MountGear.say(player, MountGear.equipBarding(player, mount, kind, held));
            return true;
        }
        if (mount instanceof MountBeast b) {
            if (b.getVariant() == 0) {
                Notifier.message(player, Component.translatable("mounts.skycraft.needs_saddle"));
                return true;
            }
            if (!b.isVehicle()) {
                player.setYRot(b.getYRot());
                player.startRiding(b);
            }
            return true;
        }
        return false;
    }

    static boolean isActive(Player player, Entity e) {
        CompoundTag tag = data(player);
        return tag.hasUUID("uuid") && tag.getUUID("uuid").equals(e.getUUID());
    }

    // ------------------------------------------------------------------ barding

    static String key(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    /** Puts the recorded barding back on a respawned mount. */
    static void applyBarding(Mob mob, String itemId) {
        if (itemId == null || itemId.isEmpty()) return;
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null || item == Items.AIR) return;
        MountKind kind = kindOf(mob);
        ItemStack one = new ItemStack(item);
        if (kind != null && MountGear.wearsBarding(mob, kind, one)) MountGear.setBarding(mob, one);
    }
}
