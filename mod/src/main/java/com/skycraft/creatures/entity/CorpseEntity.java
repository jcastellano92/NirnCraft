package com.skycraft.creatures.entity;

import com.skycraft.core.Notifier;
import com.skycraft.creatures.CreaturesConfig;
import com.skycraft.vitals.Vitals;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * {@code skycraft:corpse}: the lootable body of a creature killed by a player.
 *
 * <ul>
 *     <li><b>Loot</b>: one body, one inventory shared by everybody (like Skyrim): exactly what the creature dropped
 *     (its loot table and the equipment it was allowed to drop) plus the arrows stuck in it. Slots 0-5 hold the
 *     dropped armor and held items; taking one off also takes it off the lying body.</li>
 *     <li><b>Despawn</b>: 60 s after someone opened it and it is empty, otherwise after 15 minutes (both configurable).
 *     Never while someone is looking inside or dragging it.</li>
 *     <li><b>Physics</b>: gravity, sliding with block friction, knocked around by hits and explosions (with a little
 *     tumble), falls off ledges and floats in water.</li>
 *     <li><b>Dragging</b>: sneak + right-click grabs the body; it follows in front of the player, costs stamina every
 *     second (more for bigger creatures) and is dropped when stamina runs out, on another sneak + right-click, when the
 *     player gets too far away, or after 30 s.</li>
 *     <li><b>Rendering</b>: the client builds a never-ticked dummy of the dead creature from its saved NBT.</li>
 * </ul>
 */
public class CorpseEntity extends Entity {
    public static final int SIZE = 27;
    /** Loot slots 0-5: feet, legs, chest, head, main hand, off hand (what the body is shown wearing). */
    public static final int WORN_SLOTS = 6;
    public static final int MAX_DRAG_TICKS = 600;
    private static final String DRAGON_TYPE = "skycraft:dragon";
    private static final double MAX_DRAG_DISTANCE = 8.0;

    private static final EntityDataAccessor<String> BODY_TYPE = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<CompoundTag> BODY = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Float> BODY_HEIGHT = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> APPEAR_TICKS = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> CENTERED = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DRAGGER = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.FLOAT);

    /** NBT keys stripped from the stored body: big, irrelevant for rendering, or would tint/animate the dummy. */
    private static final String[] STRIP = {"Brain", "Offers", "Gossips", "Inventory", "Items", "Passengers", "ActiveEffects",
            "Attributes", "Leash", "HurtTime", "DeathTime", "HurtByTimestamp", "CustomName", "CustomNameVisible", "UUID",
            "ForgeCaps", "ForgeData", "Motion", "FallDistance", "Fire", "Air", "PortalCooldown", "Tags", "Team", "Xp",
            "RecipeBook", "abilities", "EnderItems", "SelectedItem", "ChestedHorse", "Bees", "Trusted", "Owner", "AngryAt"};

    /** The body's loot, shared by every looter. */
    private final Loot loot = new Loot();
    /** Bit i set: loot slot i (0-5) holds the item the body is shown wearing in that slot. */
    private int wornMask;
    /** Somebody has opened the body. */
    private boolean looted;
    /** Scene bodies (a battlefield, a burnt town): never despawn, stay where they lie, and may be unlootable. */
    private boolean persistent;
    private boolean locked;
    private int age;
    private int emptyTicks;
    private float bodyWidth = 0.6f;
    private int dragTicks;

    // client-side
    @Nullable
    private Entity renderDummy;
    private boolean dummyFailed;
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private float lerpYRot;

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        this.setMaxUpStep(0.5f);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(BODY_TYPE, "");
        this.entityData.define(BODY, new CompoundTag());
        this.entityData.define(BODY_HEIGHT, 1.8f);
        this.entityData.define(APPEAR_TICKS, 0);
        this.entityData.define(CENTERED, true);
        this.entityData.define(DRAGGER, -1);
        this.entityData.define(ROLL, 0.0f);
    }

    // ------------------------------------------------------------------ setup (server)

    /**
     * Remembers what the body looks like. Call after the loot was added (and before adding the corpse to the world):
     * equipment the creature dropped into the body is shown worn again.
     */
    public void setBody(LivingEntity dead) {
        String typeId = EntityType.getKey(dead.getType()).toString();
        CompoundTag body;
        try {
            body = dead.saveWithoutId(new CompoundTag());
            for (String key : STRIP) body.remove(key);
            body.putFloat("Health", 1.0f);
            showWorn(body);
        } catch (Exception ignored) {
            body = new CompoundTag();
        }
        this.entityData.set(BODY_TYPE, typeId);
        this.entityData.set(BODY, body);
        this.entityData.set(BODY_HEIGHT, dead.getBbHeight());
        this.bodyWidth = dead.getBbWidth();
        this.setCustomName(dead.getName().copy());
    }

    /** A scene body: it never despawns, cannot be dragged or knocked about, and is not part of any loot rule. */
    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public boolean isPersistent() {
        return persistent;
    }

    /** Nothing to take: opening the body only says so (cloth, ash and nothing else). */
    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isLocked() {
        return locked;
    }

    /** Ticks during which the body stays invisible (the vanilla death animation is still playing). */
    public void setAppearDelay(int ticks) {
        this.entityData.set(APPEAR_TICKS, ticks);
    }

    /**
     * Puts an item the creature dropped from its equipment slot {@code slot} (0-5, see {@link #WORN_SLOTS}) into the
     * body; the body is then shown wearing it until somebody takes it. Returns false if that slot is taken.
     */
    public boolean addWorn(int slot, ItemStack stack) {
        if (slot < 0 || slot >= WORN_SLOTS || stack.isEmpty() || !loot.getItem(slot).isEmpty()) return false;
        loot.setItemQuietly(slot, stack);
        wornMask |= 1 << slot;
        return true;
    }

    /**
     * Townspeople and villagers wear plain clothes the game doesn't model as equipment: their body gets a set
     * (only in the clothing slots the creature didn't drop anything into). Call only when mob loot is enabled.
     */
    public void addClothes(LivingEntity dead) {
        if (dead instanceof com.skycraft.society.entity.NpcEntity npc) {
            generateNpcClothes(npc);
        } else if (dead instanceof net.minecraft.world.entity.npc.AbstractVillager) {
            generateVillagerClothes();
        }
    }

    /** Writes the worn loot items into the body's equipment NBT (ArmorItems: feet..head, HandItems: main, off). */
    private void showWorn(CompoundTag body) {
        if (wornMask == 0) return;
        ListTag armor = body.getList("ArmorItems", Tag.TAG_COMPOUND);
        ListTag hands = body.getList("HandItems", Tag.TAG_COMPOUND);
        if (armor.size() < 4 || hands.size() < 2) return; // not an equipment-wearing creature
        for (int i = 0; i < WORN_SLOTS; i++) {
            if ((wornMask & (1 << i)) == 0) continue;
            CompoundTag item = loot.getItem(i).save(new CompoundTag());
            if (i < 4) armor.set(i, item);
            else hands.set(i - 4, item);
        }
        body.put("ArmorItems", armor);
        body.put("HandItems", hands);
    }

    private void generateNpcClothes(com.skycraft.society.entity.NpcEntity npc) {
        com.skycraft.society.NpcRole role = npc.role();
        int colorChest = 0x8D6E63;
        int colorLegs = 0x4E342E;
        int colorBoots = 0x2E1C0C;
        String nameChest = "Belted Tunic";
        String nameLegs = "Work Trousers";
        String nameBoots = "Worn Boots";

        switch (role) {
            case MAGE -> {
                colorChest = 0x283593;
                colorLegs = 0x1A237E;
                colorBoots = 0x212121;
                nameChest = "Mage Robes";
                nameLegs = "Mage Breeches";
                nameBoots = "Mage Boots";
                if (loot.getItem(3).isEmpty()) {
                    loot.setItem(3, createClothingPiece(net.minecraft.world.item.Items.LEATHER_HELMET, 0x283593, "Mage Hood"));
                }
            }
            case NECROMANCER -> {
                colorChest = 0x212121;
                colorLegs = 0x1B1B1B;
                colorBoots = 0x111111;
                nameChest = "Necromancer Robes";
                nameLegs = "Dark Breeches";
                nameBoots = "Dark Boots";
                if (loot.getItem(3).isEmpty()) {
                    loot.setItem(3, createClothingPiece(net.minecraft.world.item.Items.LEATHER_HELMET, 0x212121, "Necromancer Hood"));
                }
            }
            case MINER -> {
                colorChest = 0x5D4037;
                colorLegs = 0x3E2723;
                colorBoots = 0x212121;
                nameChest = "Miner's Clothes";
                nameLegs = "Miner's Trousers";
                nameBoots = "Miner's Boots";
            }
            case LUMBERJACK, HUNTER -> {
                colorChest = 0x6D4C41;
                colorLegs = 0x4E342E;
                colorBoots = 0x3E2723;
                nameChest = "Fur-Trimmed Tunic";
                nameLegs = "Fur Trousers";
                nameBoots = "Fur Shoes";
            }
            case BARD -> {
                colorChest = 0x1B5E20;
                colorLegs = 0x33691E;
                colorBoots = 0x212121;
                nameChest = "Bard's Tunic";
                nameLegs = "Fine Trousers";
                nameBoots = "Fine Boots";
            }
            case PRIEST -> {
                colorChest = 0xE0E0E0;
                colorLegs = 0xD5D5D5;
                colorBoots = 0x4E342E;
                nameChest = "Priest Robes";
                nameLegs = "Priest Breeches";
                nameBoots = "Priest Sandals";
            }
            case BEGGAR -> {
                colorChest = 0x5D4037;
                colorLegs = 0x4E342E;
                colorBoots = 0x3E2723;
                nameChest = "Ragged Tunic";
                nameLegs = "Ragged Trousers";
                nameBoots = "Footwraps";
            }
            case JARL -> {
                colorChest = 0x880E4F;
                colorLegs = 0x311B92;
                colorBoots = 0x212121;
                nameChest = "Noble Clothes";
                nameLegs = "Fine Trousers";
                nameBoots = "Fine Boots";
            }
            case IMPERIAL_SOLDIER -> {
                colorChest = 0xB71C1C;
                colorLegs = 0x7F0000;
                colorBoots = 0x3E2723;
                nameChest = "Imperial Light Cuirass";
                nameLegs = "Imperial Bracers & Trousers";
                nameBoots = "Imperial Boots";
            }
            case STORMCLOAK_SOLDIER -> {
                colorChest = 0x1565C0;
                colorLegs = 0x0D47A1;
                colorBoots = 0x3E2723;
                nameChest = "Stormcloak Cuirass";
                nameLegs = "Stormcloak Trousers";
                nameBoots = "Fur Boots";
            }
            case THUG, THIEF, ASSASSIN -> {
                colorChest = 0x263238;
                colorLegs = 0x212121;
                colorBoots = 0x1A1A1A;
                nameChest = "Leather Brigandine";
                nameLegs = "Leather Trousers";
                nameBoots = "Muffled Boots";
            }
            default -> {}
        }

        if (loot.getItem(2).isEmpty()) {
            loot.setItem(2, createClothingPiece(net.minecraft.world.item.Items.LEATHER_CHESTPLATE, colorChest, nameChest));
        }
        if (loot.getItem(1).isEmpty()) {
            loot.setItem(1, createClothingPiece(net.minecraft.world.item.Items.LEATHER_LEGGINGS, colorLegs, nameLegs));
        }
        if (loot.getItem(0).isEmpty()) {
            loot.setItem(0, createClothingPiece(net.minecraft.world.item.Items.LEATHER_BOOTS, colorBoots, nameBoots));
        }
    }

    private void generateVillagerClothes() {
        if (loot.getItem(2).isEmpty()) {
            loot.setItem(2, createClothingPiece(net.minecraft.world.item.Items.LEATHER_CHESTPLATE, 0x8D6E63, "Belted Tunic"));
        }
        if (loot.getItem(1).isEmpty()) {
            loot.setItem(1, createClothingPiece(net.minecraft.world.item.Items.LEATHER_LEGGINGS, 0x4E342E, "Farmer's Trousers"));
        }
        if (loot.getItem(0).isEmpty()) {
            loot.setItem(0, createClothingPiece(net.minecraft.world.item.Items.LEATHER_BOOTS, 0x2E1C0C, "Worn Boots"));
        }
    }

    private static ItemStack createClothingPiece(net.minecraft.world.item.Item item, int color, String name) {
        ItemStack stack = new ItemStack(item);
        if (item instanceof net.minecraft.world.item.DyeableLeatherItem dyeable) {
            dyeable.setColor(stack, color);
        }
        stack.setHoverName(Component.literal(name));
        return stack;
    }

    /** Adds loot (in the slots after the worn ones); returns the part that didn't fit. */
    public ItemStack addLoot(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        for (int i = WORN_SLOTS; i < SIZE; i++) {
            ItemStack existing = loot.getItem(i);
            if (existing.isEmpty()) {
                loot.setItemQuietly(i, stack);
                return ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int add = Math.min(space, stack.getCount());
                    existing.grow(add);
                    stack.shrink(add);
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }
        return loot.addItem(stack);
    }

    /**
     * Takes worn items off the lying body once a looter took them (or swapped something else into their slot):
     * only the slots that hold what the body is shown wearing are linked.
     */
    private void syncEquipmentAppearance() {
        if (this.level().isClientSide || wornMask == 0) return;
        CompoundTag body = this.entityData.get(BODY).copy();
        if (body.isEmpty()) return;
        ListTag armor = body.getList("ArmorItems", Tag.TAG_COMPOUND);
        ListTag hands = body.getList("HandItems", Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int i = 0; i < WORN_SLOTS; i++) {
            if ((wornMask & (1 << i)) == 0) continue;
            ListTag list = i < 4 ? armor : hands;
            int index = i < 4 ? i : i - 4;
            if (index >= list.size()) continue;
            ItemStack shown = ItemStack.of(list.getCompound(index));
            if (ItemStack.isSameItem(loot.getItem(i), shown)) continue;
            list.set(index, new CompoundTag());
            wornMask &= ~(1 << i);
            changed = true;
        }
        if (changed) {
            body.put("ArmorItems", armor);
            body.put("HandItems", hands);
            this.entityData.set(BODY, body);
        }
    }

    /** True when the body holds no loot. */
    public boolean isEmptyOfLoot() {
        return loot.isEmpty();
    }

    /** Ticks since the body appeared. */
    public int age() {
        return age;
    }

    /** Somebody is looking inside or dragging the body (it must not be removed now). */
    public boolean inUse() {
        return loot.openers > 0 || isDragged();
    }

    public float getBodyHeight() {
        return this.entityData.get(BODY_HEIGHT);
    }

    /** True when the corpse sits at the middle of the lying body, false when it sits at the dead creature's feet. */
    public boolean isCentered() {
        return this.entityData.get(CENTERED);
    }

    public void setCentered(boolean centered) {
        this.entityData.set(CENTERED, centered);
    }

    public boolean isVisibleYet() {
        return this.tickCount >= this.entityData.get(APPEAR_TICKS);
    }

    /** Tumble angle (degrees) around the body's long axis, changed by hits. */
    public float getRoll() {
        return this.entityData.get(ROLL);
    }

    public void setRoll(float roll) {
        this.entityData.set(ROLL, Mth.clamp(roll, -45.0f, 45.0f));
    }

    public boolean isDragged() {
        return this.entityData.get(DRAGGER) >= 0;
    }

    @Nullable
    private Player getDragger() {
        int id = this.entityData.get(DRAGGER);
        if (id < 0) return null;
        return this.level().getEntity(id) instanceof Player p ? p : null;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            tickLerp();
            return;
        }
        Player dragger = getDragger();
        if (isDragged() && !tickDrag(dragger)) stopDragging();
        if (!isDragged()) tickPhysics();

        age++;
        if (looted && loot.isEmpty()) emptyTicks++;
        else emptyTicks = 0;
        if (inUse() || persistent) return;
        if (emptyTicks > CreaturesConfig.EMPTY_CORPSE_DESPAWN_SECONDS.get() * 20
                || age > CreaturesConfig.CORPSE_DESPAWN_SECONDS.get() * 20) {
            this.discard();
        }
    }

    /** Gravity, block friction, buoyancy. */
    private void tickPhysics() {
        Vec3 v = this.getDeltaMovement();
        if (!this.isNoGravity()) v = v.add(0, -0.04, 0);
        if (this.isInWater()) {
            v = new Vec3(v.x * 0.85, v.y * 0.8 + 0.055, v.z * 0.85); // bodies float
        } else if (this.isInLava()) {
            v = v.multiply(0.5, 0.6, 0.5);
        }
        this.setDeltaMovement(v);
        this.move(MoverType.SELF, v);
        v = this.getDeltaMovement();
        float friction = 0.98f;
        if (this.onGround()) {
            friction = this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction() * 0.91f;
        }
        double vy = this.onGround() && v.y < 0 ? 0 : v.y * 0.98;
        this.setDeltaMovement(v.x * friction, vy, v.z * friction);
    }

    /** Moves the body in front of its dragger; returns false when it must be dropped. */
    private boolean tickDrag(@Nullable Player player) {
        if (player == null || !player.isAlive() || player.isSpectator() || player.level() != this.level()
                || player.distanceToSqr(this) > MAX_DRAG_DISTANCE * MAX_DRAG_DISTANCE || ++dragTicks > MAX_DRAG_TICKS) {
            return false;
        }
        if (dragTicks % 20 == 0 && !Vitals.consumeStamina(player, dragCost(), false)) {
            if (player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("creatures.skycraft.corpse.too_tired"));
            return false;
        }
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0E-4) flat = Vec3.directionFromRotation(0, player.getYRot());
        flat = flat.normalize();
        double half = isCentered() ? getBodyHeight() * 0.5 : 0.0;
        double ahead = Math.max(1.5, 0.8 + half);
        Vec3 target = player.position().add(flat.scale(ahead));

        Vec3 to = target.subtract(this.position());
        double hx = to.x * 0.3;
        double hz = to.z * 0.3;
        double h = Math.sqrt(hx * hx + hz * hz);
        if (h > 0.8) {
            hx *= 0.8 / h;
            hz *= 0.8 / h;
        }
        Vec3 v = this.getDeltaMovement();
        double vy = to.y > 0.3 ? Math.min(0.4, to.y * 0.3) : (this.onGround() ? 0 : v.y - 0.04);
        if (this.isInWater()) vy = Math.max(vy, 0.02);
        this.setDeltaMovement(hx, vy, hz);
        this.move(MoverType.SELF, this.getDeltaMovement());

        // the head end points at the player: head direction is (-cos a, 0, sin a) with a = 180 - yaw
        Vec3 toPlayer = player.position().subtract(this.position());
        if (toPlayer.horizontalDistanceSqr() > 0.01) {
            double a = Math.toDegrees(Math.atan2(toPlayer.z, -toPlayer.x));
            this.setYRot(Mth.approachDegrees(this.getYRot(), (float) (180.0 - a), 8.0f));
        }
        return true;
    }

    /** Stamina per second: bigger creatures are heavier to drag. */
    private float dragCost() {
        double volume = bodyWidth * bodyWidth * getBodyHeight();
        return (float) Mth.clamp(3.0 + volume * 4.0, 3.0, 50.0);
    }

    private void startDragging(Player player) {
        if (persistent) return;
        for (CorpseEntity other : this.level().getEntitiesOfClass(CorpseEntity.class, player.getBoundingBox().inflate(16),
                c -> c != this && c.entityData.get(DRAGGER) == player.getId())) {
            other.stopDragging();
        }
        this.entityData.set(DRAGGER, player.getId());
        this.setCentered(true);
        this.dragTicks = 0;
        this.setMaxUpStep(1.0f);
        this.playSound(SoundEvents.ARMOR_EQUIP_LEATHER, 0.8f, 0.6f);
    }

    public void stopDragging() {
        if (!isDragged()) return;
        this.entityData.set(DRAGGER, -1);
        this.dragTicks = 0;
        this.setMaxUpStep(0.5f);
        this.setDeltaMovement(this.getDeltaMovement().multiply(0.3, 1.0, 0.3));
    }

    /** Client: smooth movement between the server's position updates. */
    private void tickLerp() {
        if (lerpSteps <= 0) return;
        double x = this.getX() + (lerpX - this.getX()) / lerpSteps;
        double y = this.getY() + (lerpY - this.getY()) / lerpSteps;
        double z = this.getZ() + (lerpZ - this.getZ()) / lerpSteps;
        float yaw = this.getYRot() + Mth.wrapDegrees(lerpYRot - this.getYRot()) / lerpSteps;
        lerpSteps--;
        this.setPos(x, y, z);
        this.setYRot(yaw);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpSteps = teleport ? 1 : Math.max(steps, 3);
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player.isShiftKeyDown() && this.entityData.get(DRAGGER) == player.getId()) {
            if (!this.level().isClientSide) stopDragging();
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (locked) {
            if (!this.level().isClientSide) {
                player.displayClientMessage(Component.translatable("fire.skycraft.body.nothing"), true);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (!this.level().isClientSide) {
            looted = true;
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory, loot), this.getDisplayName()));
            this.playSound(SoundEvents.ARMOR_EQUIP_LEATHER, 0.6f, 0.9f);
            if (player instanceof ServerPlayer sp) clearOwnMarker(sp);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    /**
     * Removes a map/compass marker pointing at this very body ({@code corpse|<this corpse's UUID>}), if any. Never
     * touches other markers: the player's own death marker ("Your Corpse", {@code corpse|<player UUID>}) belongs to
     * the combat module and stays until the player reaches their own grave.
     */
    private void clearOwnMarker(ServerPlayer sp) {
        com.skycraft.core.PlayerData data = com.skycraft.core.SkyData.get(sp);
        if (data == null) return;
        String id = "corpse|" + this.getStringUUID();
        ListTag list = com.skycraft.world.WorldData.discovered(data);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (id.equals(list.getCompound(i).getString("id"))) {
                list.remove(i);
                removed = true;
            }
        }
        if (removed) {
            data.markDirty();
            com.skycraft.core.PlayerDataEvents.fullSync(sp);
        }
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false; // arrows fly over bodies
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /** Punching a body shoves it; a sneaking creative-mode player removes it. */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (!this.level().isClientSide && attacker instanceof Player p) {
            if (p.isCreative() && p.isShiftKeyDown()) this.discard();
            else knock(p.position(), 0.35);
        }
        return true;
    }

    /** Bodies take no damage, but hits and blasts shove them around (explosions also push them directly). */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && !this.isRemoved() && !isDragged()) {
            Vec3 from = source.getSourcePosition();
            if (from != null) knock(from, 0.2 + Math.min(amount, 12.0f) * 0.04);
        }
        return false;
    }

    private void knock(Vec3 from, double strength) {
        if (persistent) return;
        Vec3 dir = new Vec3(this.getX() - from.x, 0, this.getZ() - from.z);
        dir = dir.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, this.random.nextFloat() * 360f) : dir.normalize();
        double weight = Math.max(0.5, Math.sqrt(bodyWidth * bodyWidth * getBodyHeight()));
        double s = strength / weight;
        this.setDeltaMovement(this.getDeltaMovement().add(dir.x * s, 0.1 + s * 0.4, dir.z * s));
        this.hasImpulse = true;
        setRoll(getRoll() + (this.random.nextFloat() - 0.5f) * 40.0f);
        this.setYRot(this.getYRot() + (this.random.nextFloat() - 0.5f) * 30.0f);
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    // ------------------------------------------------------------------ client render dummy

    /** A client-side, never-ticked instance of the dead creature loaded from its saved NBT, or null. */
    @Nullable
    public Entity getRenderDummy() {
        if (renderDummy != null || dummyFailed) return renderDummy;
        String typeId = this.entityData.get(BODY_TYPE);
        if (typeId.isEmpty()) return null; // data not synced yet
        try {
            Optional<EntityType<?>> type = EntityType.byString(typeId);
            if (type.isEmpty()) {
                dummyFailed = true;
                return null;
            }
            Entity e = type.get().create(this.level());
            if (e == null) {
                dummyFailed = true;
                return null;
            }
            CompoundTag tag = this.entityData.get(BODY).copy();
            if (!tag.isEmpty()) e.load(tag);
            e.setPos(this.getX(), this.getY(), this.getZ());
            e.setYRot(180f);
            e.yRotO = 180f;
            e.setXRot(0f);
            e.xRotO = 0f;
            if (e instanceof LivingEntity living) {
                living.hurtTime = 0;
                living.deathTime = 0;
                living.yBodyRot = 180f;
                living.yBodyRotO = 180f;
                living.yHeadRot = 180f;
                living.yHeadRotO = 180f;
            }
            if (e instanceof DragonEntity dragon) dragon.setCorpsePose(true);
            renderDummy = e;
        } catch (Throwable t) {
            dummyFailed = true;
            renderDummy = null;
        }
        return renderDummy;
    }

    /**
     * The lying body is much bigger than the corpse's own box (it reaches about one body height away, and a dragon's
     * wings and tail much further): cull with a box around all of it.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double reach = Math.max(1.0, getBodyHeight());
        if (DRAGON_TYPE.equals(this.entityData.get(BODY_TYPE))) return this.getBoundingBox().inflate(9.0, 4.0, 9.0);
        return this.getBoundingBox().inflate(reach, Math.max(0.5, reach * 0.5), reach);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BODY.equals(key) || BODY_TYPE.equals(key)) {
            renderDummy = null;
            dummyFailed = false;
        }
    }

    // ------------------------------------------------------------------ save

    private static ListTag saveItems(Container container) {
        ListTag items = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) i);
            stack.save(t);
            items.add(t);
        }
        return items;
    }

    private static void loadItems(Container container, ListTag items) {
        for (int i = 0; i < items.size(); i++) {
            CompoundTag t = items.getCompound(i);
            int slot = t.getByte("Slot") & 255;
            if (slot < container.getContainerSize()) container.setItem(slot, ItemStack.of(t));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Loot", saveItems(loot));
        tag.putByte("Worn", (byte) wornMask);
        tag.putBoolean("Looted", looted);
        tag.putString("BodyType", this.entityData.get(BODY_TYPE));
        tag.put("Body", this.entityData.get(BODY));
        tag.putFloat("BodyHeight", getBodyHeight());
        tag.putFloat("BodyWidth", bodyWidth);
        tag.putBoolean("Centered", isCentered());
        tag.putFloat("Roll", getRoll());
        tag.putInt("Age", age);
        tag.putInt("EmptyTicks", emptyTicks);
        if (persistent) tag.putBoolean("Persistent", true);
        if (locked) tag.putBoolean("Locked", true);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // (Bodies saved by older versions also had per-player copies, "Copies"/"Finished": dropped, the base loot stays.)
        loot.quiet = true;
        loadItems(loot, tag.getList("Loot", Tag.TAG_COMPOUND));
        loot.quiet = false;
        wornMask = tag.getByte("Worn") & ((1 << WORN_SLOTS) - 1);
        looted = tag.getBoolean("Looted") || tag.contains("Copies", Tag.TAG_LIST) && !tag.getList("Copies", Tag.TAG_COMPOUND).isEmpty();
        this.entityData.set(BODY_TYPE, tag.getString("BodyType"));
        this.entityData.set(BODY, tag.getCompound("Body"));
        if (tag.contains("BodyHeight")) this.entityData.set(BODY_HEIGHT, tag.getFloat("BodyHeight"));
        if (tag.contains("BodyWidth")) bodyWidth = tag.getFloat("BodyWidth");
        if (tag.contains("Centered")) setCentered(tag.getBoolean("Centered"));
        setRoll(tag.getFloat("Roll"));
        age = tag.getInt("Age");
        emptyTicks = tag.getInt("EmptyTicks");
        persistent = tag.getBoolean("Persistent");
        locked = tag.getBoolean("Locked");
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }

    /** The body's inventory, shared by all looters; valid while the corpse exists and the looter is close. */
    private class Loot extends SimpleContainer {
        int openers;
        /** Set while filling or loading: no appearance updates. */
        boolean quiet;

        Loot() {
            super(SIZE);
        }

        void setItemQuietly(int slot, ItemStack stack) {
            quiet = true;
            setItem(slot, stack);
            quiet = false;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!quiet) syncEquipmentAppearance();
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack stack = super.removeItemNoUpdate(slot);
            if (!quiet) syncEquipmentAppearance();
            return stack;
        }

        @Override
        public boolean stillValid(Player player) {
            return CorpseEntity.this.isAlive() && player.distanceToSqr(CorpseEntity.this) < 64.0;
        }

        @Override
        public void startOpen(Player player) {
            openers++;
        }

        @Override
        public void stopOpen(Player player) {
            openers = Math.max(0, openers - 1);
        }
    }
}
