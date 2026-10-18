package com.skycraft.mounts;

import com.skycraft.core.Notifier;
import com.skycraft.creatures.entity.GuarEntity;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * What a mount wears and what that does: its saddle ({@link SaddleType}) and barding (a {@link HorseArmorItem} for
 * horses, {@link MountItems#BEAST_BARDING} for the beasts), the breed-by-saddle matrix ({@link #accepts}), and the
 * numbers they add up to (carry weight, saddlebag capacity, stamina, speed, armour and health).
 *
 * <p>The state lives on the mount entity ({@link #SADDLE_TAG}, the horse's own armour slot) so it survives chunk
 * unloads; the player's mount record remembers the saddle of every mount they own ({@code saddles}) so a mount called
 * from the stable comes back in its own tack. All swaps are server side and never destroy an item: what is taken off
 * goes to the player's bags, what no longer fits in the saddlebags spills the same way ({@link Saddlebags#fit}).</p>
 */
public final class MountGear {
    /** Persistent-data string on the mount: a {@link SaddleType} id, or {@code none}. Absent on mounts of older saves. */
    public static final String SADDLE_TAG = "skycraft_saddle";
    /** Persistent-data flag: a {@link MountBeast} wears its barding (its variant shows it). */
    public static final String BARDED_TAG = "skycraft_barded";
    private static final net.minecraft.resources.Identifier SADDLE_SPEED = net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "modifier_5d2f6a14-0c7b-4e0a-9e53-7a1b3c4d5e61");
    private static final net.minecraft.resources.Identifier BARDING_HEALTH = net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "modifier_5d2f6a14-0c7b-4e0a-9e53-7a1b3c4d5e62");
    private static final net.minecraft.resources.Identifier BARDING_ARMOR = net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "modifier_6b1f3c1e-5a3d-4d61-9d0f-2f4c1d7e8a10");
    /** Armour a beast's barding adds (the barding stat of a plate horse barding of the middle tiers). */
    private static final int BEAST_BARDING_ARMOR = 8;

    private MountGear() {}

    /** Why a swap did or did not happen; {@link #key} is the message under {@code mounts.skycraft.gear.}. */
    public enum Result {
        OK, SAME, WRONG_BREED, FIXED, NOT_BARDABLE, NOT_GEAR;

        public String key() {
            return "mounts.skycraft.gear." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ the breed matrix

    /**
     * Which saddle types a breed takes (documented in docs/MOUNTS.md). Hill ponies are no war horses and wear no court
     * tack; the light breeds carry no panniers; the big beasts take what their cultures put on them; Shadowmere and
     * Arvak keep their own tack.
     */
    public static boolean accepts(MountKind kind, SaddleType type) {
        if (fixedTack(kind)) return false;
        return switch (kind) {
            case WINDHELM_PINTO, MARKARTH_BLACK -> type == SaddleType.PLAIN || type == SaddleType.PACK;
            case WHITERUN_BAY, RIFTEN_CHESTNUT -> type != SaddleType.NOBLE;
            case SOLITUDE_GREY -> type != SaddleType.WAR;
            case CYRODILIC_BAY, BRETON_DESTRIER -> true;
            case COLOVIAN_PAINT, ALIKR_STEED -> type != SaddleType.NOBLE;
            case NIBENESE_GREY, AURIDON_WHITE, INDRIK -> type == SaddleType.PLAIN || type == SaddleType.NOBLE;
            case CAMEL, GUAR, REALM_GRAZER -> type == SaddleType.PLAIN || type == SaddleType.PACK;
            case SENCHE, AKAVIRI_TIGER -> type == SaddleType.PLAIN || type == SaddleType.WAR;
            case GIANT_WOLF, MAMMOTH -> type != SaddleType.NOBLE;
            default -> false;
        };
    }

    /** The legends wear their own tack and never change it. */
    public static boolean fixedTack(MountKind kind) {
        return kind == MountKind.SHADOWMERE || kind == MountKind.ARVAK;
    }

    /** The weight (kg) a mount of this kind takes off its rider's back before any saddle: draught beasts carry most. */
    public static float baseCarry(MountKind kind) {
        return switch (kind) {
            case WINDHELM_PINTO, MARKARTH_BLACK -> 60f;
            case WHITERUN_BAY, SOLITUDE_GREY, RIFTEN_CHESTNUT, CYRODILIC_BAY, NIBENESE_GREY, AURIDON_WHITE -> 80f;
            case COLOVIAN_PAINT -> 140f;
            case BRETON_DESTRIER -> 110f;
            case ALIKR_STEED -> 70f;
            case SHADOWMERE, ARVAK -> 120f;
            case CAMEL -> 200f;
            case GUAR -> 260f;
            case SENCHE -> 120f;
            case GIANT_WOLF -> 80f;
            case INDRIK -> 90f;
            case MAMMOTH -> 320f;
            case AKAVIRI_TIGER -> 110f;
            case REALM_GRAZER -> 150f;
        };
    }

    // ------------------------------------------------------------------ the numbers

    /** Carry weight (kg) the mount adds to its rider's capacity while ridden. */
    public static float carryKg(Entity mount, MountKind kind) {
        SaddleType s = saddle(mount);
        return (baseCarry(kind) + (s == null ? 0f : s.carryKg)) * capacityMult(mount, kind);
    }

    /**
     * What this individual's build and its owner's Pack Master add to the carry weight and to the bags: the Carry
     * roll and its growth, then +10% a rank of the perk (the owner only, while they are online).
     */
    public static float capacityMult(Entity mount, MountKind kind) {
        return MountStats.of(mount, kind).mult(MountProfile.CARRY) * MountGrowth.packMult(MountGrowth.ownerPlayer(mount));
    }

    /** Saddlebag capacity (kg): the saddle's, scaled by the beast's bag rows (a pony's bags are half a guar's). */
    public static float bagKg(MountKind kind, @Nullable SaddleType saddle) {
        return saddle == null ? 0f : saddle.bagKg * kind.bagRows / 2f;
    }

    /** Saddlebag capacity (kg) of this animal in its saddle ({@link #bagKg} times its build and its owner's perk). */
    public static float bagCapacity(Entity mount, MountKind kind) {
        return bagKg(kind, saddle(mount)) * capacityMult(mount, kind);
    }

    public static int maxStamina(Entity mount, MountKind kind) {
        SaddleType s = saddle(mount);
        float mult = MountStats.of(mount, kind).mult(MountProfile.STAMINA);
        return Math.max(1, Math.round(kind.stamina * (s == null ? 1f : s.staminaMult) * mult));
    }

    /** Extra damage fraction of a strike from the saddle of the mount the attacker rides. */
    public static float damageBonus(Entity mount) {
        SaddleType s = saddle(mount);
        return s == null ? 0f : s.damageBonus;
    }

    /** The weight a player's mount takes off their back right now (0 on the client and when not steering a mount). */
    public static float carryBonus(Player player) {
        if (player.level().isClientSide() || !(player.getVehicle() instanceof LivingEntity m) || m.getControllingPassenger() != player) return 0f;
        MountKind kind = Mounts.kindOf(m);
        return kind == null ? 0f : carryKg(m, kind);
    }

    // ------------------------------------------------------------------ saddle state

    /** The saddle a mount wears, or null when it is bare (legacy saddled mounts count as plain). */
    @Nullable
    public static SaddleType saddle(Entity e) {
        CompoundTag tag = e.getPersistentData();
        if (tag.contains(SADDLE_TAG)) return SaddleType.byId(tag.getStringOr(SADDLE_TAG, ""));
        boolean legacy = e instanceof AbstractHorse h ? h.isSaddled()
                : e instanceof MountBeast b ? b.getVariant() >= 1
                : e instanceof GuarEntity g && g.isSaddled();
        return legacy ? SaddleType.PLAIN : null;
    }

    /** Puts a saddle on (or, with null, takes it off) the entity: state, the vanilla slot or variant, the modifiers. */
    public static void setSaddle(Mob mob, @Nullable SaddleType type) {
        mob.getPersistentData().putString(SADDLE_TAG, type == null ? "none" : type.id);
        if (mob instanceof AbstractHorse h) {
            h.getSlot(400).set(type == null ? ItemStack.EMPTY : new ItemStack(Items.SADDLE));
        } else if (mob instanceof GuarEntity g) {
            g.setVariant(type == null ? 0 : 1);
        }
        refresh(mob);
    }

    /** Takes the saddle off a wild mount whose taming failed: bare again, no trace of a mount's tack. */
    public static void clearSaddle(Mob mob) {
        setSaddle(mob, null);
        mob.getPersistentData().remove(SADDLE_TAG);
    }

    /** True once a player owns the entity (the taming session must not strip a mount that was just won). */
    public static boolean isOwned(Entity e) {
        return Mounts.ownerOf(e) != null;
    }

    // ------------------------------------------------------------------ barding state

    /** The barding the mount wears (a copy), or empty. */
    public static ItemStack barding(Mob mob) {
        if (mob instanceof Horse h) return h.getSlot(401).get().copy();
        if (mob instanceof MountBeast b && barded(b)) return new ItemStack(MountItems.BEAST_BARDING.get());
        return ItemStack.EMPTY;
    }

    public static boolean barded(MountBeast b) {
        return b.getPersistentData().getBooleanOr(BARDED_TAG, false) || b.getVariant() == 2;
    }

    /** Puts one barding item on (or, with empty, takes it off). Does not check that the mount takes it. */
    static void setBarding(Mob mob, ItemStack one) {
        if (mob instanceof Horse h) {
            h.getSlot(401).set(one.isEmpty() ? ItemStack.EMPTY : single(one));
        } else if (mob instanceof MountBeast b) {
            b.getPersistentData().putBoolean(BARDED_TAG, !one.isEmpty());
        }
        refresh(mob);
    }

    private static ItemStack single(ItemStack stack) {
        ItemStack one = stack.copy();
        one.setCount(1);
        return one;
    }

    /** Re-applies what the tack does to the mount (called after every change and when a mount loads). */
    public static void refresh(Mob mob) {
        if (mob.level().isClientSide()) return;
        SaddleType s = saddle(mob);
        if (mob instanceof MountBeast b) {
            int v = s == null ? 0 : barded(b) ? 2 : 1;
            if (b.getVariant() != v) b.setVariant(v);
        }
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            setModifier(speed, SADDLE_SPEED, "Saddle", s == null ? 0 : s.speedMod, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        }
        ItemStack bard = barding(mob);
        int protection = bard.isEmpty() ? 0 : MountItems.isHorseArmor(bard) ? MountItems.armorProtection(bard) : BEAST_BARDING_ARMOR;
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            float ratio = mob.getMaxHealth() <= 0 ? 1f : mob.getHealth() / mob.getMaxHealth();
            if (setModifier(hp, BARDING_HEALTH, "Barding", protection * 2.0, AttributeModifier.Operation.ADD_VALUE)) {
                mob.setHealth(Math.max(1f, Math.min(mob.getMaxHealth(), ratio * mob.getMaxHealth())));
            }
        }
        if (mob instanceof MountBeast) {
            AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
            if (armor != null) setModifier(armor, BARDING_ARMOR, "Barding", bard.isEmpty() ? 0 : BEAST_BARDING_ARMOR, AttributeModifier.Operation.ADD_VALUE);
        }
        MountKind kind = Mounts.kindOf(mob);
        if (kind != null) MountGrowth.applyAttributes(mob, kind); // the individual: speed, health and jump of its stats
    }

    /** Sets (or clears, at 0) a permanent modifier; true when its value changed. */
    static boolean setModifier(AttributeInstance attr, net.minecraft.resources.Identifier id, String name, double amount, AttributeModifier.Operation op) {
        AttributeModifier cur = attr.getModifier(id);
        if (cur == null ? amount == 0 : cur.amount() == amount) return false;
        if (cur != null) attr.removeModifier(id);
        if (amount != 0) attr.addPermanentModifier(new AttributeModifier(id, amount, op));
        return true;
    }

    // ------------------------------------------------------------------ the player's record

    /** The saddle a mount of this kind of the player's came with (the plain one when never changed). */
    @Nullable
    public static SaddleType remembered(Player player, MountKind kind) {
        CompoundTag saddles = Mounts.data(player).getCompoundOrEmpty("saddles");
        return saddles.contains(kind.id) ? SaddleType.byId(saddles.getStringOr(kind.id, "")) : SaddleType.PLAIN;
    }

    static void remember(Player player, MountKind kind, @Nullable SaddleType type) {
        CompoundTag tag = Mounts.data(player);
        CompoundTag saddles = tag.getCompoundOrEmpty("saddles");
        saddles.putString(kind.id, type == null ? "none" : type.id);
        tag.put("saddles", saddles);
        com.skycraft.core.SkyData.get(player).markDirty();
    }

    // ------------------------------------------------------------------ swaps (server)

    /** Hands an item back to the player's bags (never the held hand), dropping it at their feet when they are full. */
    public static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        com.skycraft.inventory.Inv.giveOrDrop(player, stack); // slots, then the pack; only the pack's engineering cap drops
    }

    /** Fits the saddle {@code source} (one item of it is used up) onto the mount; the old saddle goes to the player. */
    public static Result equipSaddle(ServerPlayer player, Mob mount, MountKind kind, ItemStack source) {
        SaddleType t = SaddleType.of(source);
        if (t == null) return Result.NOT_GEAR;
        if (fixedTack(kind)) return Result.FIXED;
        if (!accepts(kind, t)) return Result.WRONG_BREED;
        SaddleType old = saddle(mount);
        if (old == t) return Result.SAME;
        if (!player.getAbilities().instabuild) source.shrink(1);
        setSaddle(mount, t);
        remember(player, kind, t);
        if (old != null) give(player, old.stack());
        Saddlebags.fit(player, bagCapacity(mount, kind));
        mount.playSound(SoundEvents.HORSE_SADDLE.value(), 0.8f, 1.0f);
        return Result.OK;
    }

    /**
     * Takes the saddle off. {@code giveBack} false when the player already holds the item (taken from the menu slot).
     * The saddlebags go with it: what was in them spills into the player's bags.
     */
    public static Result unequipSaddle(ServerPlayer player, Mob mount, MountKind kind, boolean giveBack) {
        if (fixedTack(kind)) return Result.FIXED;
        SaddleType old = saddle(mount);
        if (old == null) return Result.SAME;
        mount.ejectPassengers();
        setSaddle(mount, null);
        remember(player, kind, null);
        if (giveBack) give(player, old.stack());
        Saddlebags.fit(player, 0f);
        mount.playSound(SoundEvents.HORSE_SADDLE.value(), 0.8f, 0.8f);
        return Result.OK;
    }

    /** True when this item is barding the mount can wear. */
    public static boolean wearsBarding(Mob mount, MountKind kind, ItemStack stack) {
        if (stack.isEmpty() || !kind.bardable()) return false;
        if (mount instanceof Horse) return MountItems.isHorseArmor(stack);
        return mount instanceof MountBeast && stack.is(MountItems.BEAST_BARDING.get());
    }

    public static Result equipBarding(ServerPlayer player, Mob mount, MountKind kind, ItemStack source) {
        if (!wearsBarding(mount, kind, source)) return kind.bardable() ? Result.NOT_GEAR : Result.NOT_BARDABLE;
        ItemStack old = barding(mount);
        if (!old.isEmpty() && old.getItem() == source.getItem()) return Result.SAME;
        ItemStack one = single(source);
        if (!player.getAbilities().instabuild) source.shrink(1);
        setBarding(mount, one);
        syncRecord(player, mount, one);
        give(player, old);
        mount.playSound(SoundEvents.HORSE_ARMOR.value(), 0.8f, 1.0f);
        return Result.OK;
    }

    public static Result unequipBarding(ServerPlayer player, Mob mount, MountKind kind, boolean giveBack) {
        ItemStack old = barding(mount);
        if (old.isEmpty()) return Result.SAME;
        setBarding(mount, ItemStack.EMPTY);
        syncRecord(player, mount, ItemStack.EMPTY);
        if (giveBack) give(player, old);
        mount.playSound(SoundEvents.HORSE_ARMOR.value(), 0.8f, 0.8f);
        return Result.OK;
    }

    private static void syncRecord(ServerPlayer player, Mob mount, ItemStack barding) {
        if (!Mounts.isActive(player, mount)) return;
        CompoundTag tag = Mounts.data(player);
        if (barding.isEmpty()) tag.remove("barding");
        else tag.putString("barding", Mounts.key(barding));
        com.skycraft.core.SkyData.get(player).markDirty();
    }

    /** Tells the player why a swap did not happen (nothing for OK and SAME of an automatic call). */
    public static void say(ServerPlayer player, Result r) {
        if (r != Result.OK) Notifier.message(player, Component.translatable(r.key()));
    }
}
