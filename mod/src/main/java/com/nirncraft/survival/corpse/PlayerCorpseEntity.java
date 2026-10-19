package com.nirncraft.survival.corpse;

import com.nirncraft.core.Notifier;
import com.nirncraft.creatures.entity.CorpseEntity;
import com.nirncraft.inventory.BulkStore;
import com.nirncraft.inventory.ContainerOverflow;
import com.nirncraft.inventory.OverflowHost;
import com.nirncraft.quest.party.Parties;
import com.nirncraft.quest.party.Party;
import com.nirncraft.survival.SurvivalConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code nirncraft:player_corpse}: the body a player leaves where they died, holding everything they dropped (what the
 * death rules let them keep came back with them, see {@code combat/DeathHandler}). It reuses the creature corpse
 * ({@link CorpseEntity}): lies on the ground, floats, can be shoved, and the F key searches it.
 *
 * <ul>
 *     <li>Its owner, their party or anyone may loot it ({@code player_corpses.lootAccess}).</li>
 *     <li>It never despawns while it holds anything; emptied, it fades after {@code emptyDespawnSeconds}.</li>
 *     <li>It is fireproof, and if it ends up in lava or below the world it is put back on safe ground.</li>
 *     <li>The client draws the owner lying dead (their skin, see {@code survival/client/PlayerCorpseClient}).</li>
 * </ul>
 */
public class PlayerCorpseEntity extends CorpseEntity {
    public static final int SLOTS = 54;
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(PlayerCorpseEntity.class, com.nirncraft.survival.SurvivalRegistry.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> OWNER_NAME = SynchedEntityData.defineId(PlayerCorpseEntity.class, EntityDataSerializers.STRING);

    private final Items items = new Items();
    /**
     * Everything the 54 slots cannot hold: a dying player's whole inventory and pack always fit in one body
     * (inventory.OverflowHost; docs/INVENTORY.md "Corpses"). Persisted with the body, listed after the slots by the
     * Skyrim container screen.
     */
    private final BulkStore bulk = new BulkStore(ContainerOverflow.CORPSE_ENTRIES, ContainerOverflow.CORPSE_PER_ENTRY, null);
    private BlockPos safe;
    private int emptyTicks;
    // client
    @Nullable
    private Entity dummy;
    private boolean dummyTried;

    public PlayerCorpseEntity(EntityType<? extends PlayerCorpseEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
        builder.define(OWNER_NAME, "");
    }

    /** Sets whose body this is and the safe ground it returns to if it ever falls into lava or the void. */
    public void setOwner(ServerPlayer owner, BlockPos safeGround) {
        this.entityData.set(OWNER, Optional.of(owner.getUUID()));
        this.entityData.set(OWNER_NAME, owner.getGameProfile().name());
        this.setCustomName(Component.translatable("entity.nirncraft.player_corpse.named", owner.getGameProfile().name()));
        this.safe = safeGround.immutable();
    }

    @Nullable
    public UUID owner() {
        return this.entityData.get(OWNER).orElse(null);
    }

    public String ownerName() {
        return this.entityData.get(OWNER_NAME);
    }

    /** Adds a dropped stack (slots, then the overflow); returns what didn't fit (only the overflow's caps refuse). */
    public ItemStack add(ItemStack stack) {
        ItemStack rest = items.addItem(stack);
        if (!rest.isEmpty()) bulk.add(rest);
        return rest;
    }

    /** Adds a whole entry with an int count (a pack entry): straight into the overflow when the slots are full. */
    public ItemStack addBulk(ItemStack all) {
        if (all.isEmpty()) return ItemStack.EMPTY;
        // slots first while they have room, in max-size parts; the rest merges into the overflow as one row
        while (!all.isEmpty()) {
            ItemStack part = all.split(Math.min(all.getCount(), all.getMaxStackSize()));
            ItemStack rest = items.addItem(part);
            if (!rest.isEmpty()) {
                all.grow(rest.getCount());
                bulk.add(all);
                return all;
            }
        }
        return ItemStack.EMPTY;
    }

    public boolean hasItems() {
        return !items.isEmpty() || !bulk.isEmpty();
    }

    /** The overflow behind the slots (the selftest and the lead's tools read it). */
    public BulkStore overflow() {
        return bulk;
    }

    /** Everything the body holds, slots and overflow, merged by item (for tests and for spilling). */
    public java.util.List<ItemStack> contents() {
        BulkStore all = new BulkStore(Integer.MAX_VALUE, Integer.MAX_VALUE, null);
        for (int i = 0; i < items.getContainerSize(); i++) {
            ItemStack s = items.getItem(i).copy();
            if (!s.isEmpty()) all.add(s);
        }
        for (ItemStack s : bulk.entries()) {
            if (!s.isEmpty()) all.add(s);
        }
        return all.entries();
    }

    /** Taking the body away (command, creative punch) never deletes what it holds: it spills where it lies. */
    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide() && reason.shouldDestroy() && hasItems() && this.level() instanceof net.minecraft.server.level.ServerLevel level) {
            BlockPos at = this.getY() < level.getMinY() + 1 ? (safe != null ? safe : level.getRespawnData().pos()) : this.blockPosition();
            for (int i = 0; i < items.getContainerSize(); i++) {
                ItemStack s = items.removeItemNoUpdate(i);
                if (!s.isEmpty()) bulk.add(s);
            }
            ContainerOverflow.spill(level, at, bulk);
        }
        super.remove(reason);
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public boolean inUse() {
        return super.inUse() || items.openers > 0 || hasItems();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide() || this.isRemoved()) return;
        if (this.isInLava() || this.getY() < this.level().getMinY() + 1) rescue();
        if (hasItems() || items.openers > 0) {
            emptyTicks = 0;
        } else if (++emptyTicks > SurvivalConfig.EMPTY_CORPSE_SECONDS.get() * 20) {
            this.discard();
        }
    }

    /** Back onto the safe ground recorded at death. */
    private void rescue() {
        BlockPos to = safe != null ? safe : this.level().getRespawnData().pos();
        this.setDeltaMovement(0, 0, 0);
        this.teleportTo(to.getX() + 0.5, to.getY() + 0.1, to.getZ() + 0.5);
        this.clearFire();
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl && PlayerCorpses.lava(sl, to.below())) this.setNoGravity(true);
    }

    // ------------------------------------------------------------------ looting

    /** Owner, party or anyone, per config. Creative players may always look. */
    public boolean canLoot(Player player) {
        if (player.isCreative()) return true;
        UUID owner = owner();
        if (owner == null || owner.equals(player.getUUID())) return true;
        String access = SurvivalConfig.CORPSE_LOOT_ACCESS.get().toLowerCase(Locale.ROOT);
        if (access.equals("anyone")) return true;
        if (access.equals("party") && player instanceof ServerPlayer sp) {
            Party party = Parties.partyOf(sp);
            return party != null && party.has(owner);
        }
        return false;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, net.minecraft.world.phys.Vec3 hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player.isShiftKeyDown() && this.isDragged()) return super.interact(player, hand, hit);
        if (!this.level().isClientSide() && player instanceof ServerPlayer sp) {
            if (!canLoot(sp)) {
                Notifier.message(sp, Component.translatable("survival.nirncraft.corpse.not_yours", ownerName()));
                return InteractionResult.CONSUME;
            }
            sp.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.sixRows(id, inventory, items), this.getDisplayName()));
            this.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.6f, 0.9f);
        }
        return com.nirncraft.platform.Interactions.sidedSuccess(this.level().isClientSide());
    }

    // ------------------------------------------------------------------ client body

    @Override
    @Nullable
    public Entity getRenderDummy() {
        if (dummy != null || dummyTried) return dummy;
        if (owner() == null) return null; // not synced yet
        dummyTried = true;
        dummy = com.nirncraft.platform.Sides.callOn(Dist.CLIENT, () -> () -> com.nirncraft.survival.client.PlayerCorpseClient.dummy(this));
        return dummy;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (OWNER.equals(key) || OWNER_NAME.equals(key)) {
            dummy = null;
            dummyTried = false;
        }
    }

    // ------------------------------------------------------------------ save

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        ListTag list = new ListTag();
        for (int i = 0; i < items.getContainerSize(); i++) {
            ItemStack stack = items.getItem(i);
            if (stack.isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) i);
            com.nirncraft.platform.ItemTags.saveInto(stack, t);
            list.add(t);
        }
        tag.put("PlayerItems", list);
        if (!bulk.isEmpty()) tag.put("PlayerBulk", bulk.save());
        this.entityData.get(OWNER).ifPresent(u -> tag.store("Owner", net.minecraft.core.UUIDUtil.CODEC,  u));
        tag.putString("OwnerName", ownerName());
        if (safe != null) tag.put("Safe", com.nirncraft.platform.NbtPositions.write(safe));
        tag.putInt("PlayerEmptyTicks", emptyTicks);
    
        output263.store(tag);
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263 , "PlayerBulk", "PlayerItems", "Owner", "OwnerName", "Safe", "PlayerEmptyTicks");
        
        bulk.load(tag.getCompoundOrEmpty("PlayerBulk"));
        ListTag list = tag.getListOrEmpty("PlayerItems");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            int slot = t.getByteOr("Slot",(byte)0) & 255;
            ItemStack stack = com.nirncraft.inventory.ItemData.parse(t);
            if (stack.isEmpty()) continue;
            // a slot that no longer exists (the body used to be bigger) or is taken keeps its stack in the overflow:
            // nothing a body saved is ever dropped on load
            if (slot < SLOTS && items.getItem(slot).isEmpty()) items.setItem(slot, stack);
            else bulk.add(stack);
        }
        if (tag.read("Owner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) this.entityData.set(OWNER, Optional.of(tag.read("Owner", net.minecraft.core.UUIDUtil.CODEC).orElseThrow()));
        this.entityData.set(OWNER_NAME, tag.getStringOr("OwnerName",""));
        if (tag.contains("Safe")) safe = com.nirncraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Safe"));
        emptyTicks = tag.getIntOr("PlayerEmptyTicks",0);
    }

    /** The body's inventory; valid while the body exists and the looter is close. */
    private class Items extends SimpleContainer implements OverflowHost {
        int openers;

        @Override
        public BulkStore overflow() {
            return bulk;
        }

        Items() {
            super(SLOTS);
        }

        @Override
        public boolean stillValid(Player player) {
            return PlayerCorpseEntity.this.isAlive() && player.distanceToSqr(PlayerCorpseEntity.this) < 64.0;
        }

        @Override
        public void startOpen(net.minecraft.world.entity.ContainerUser player) {
            openers++;
        }

        @Override
        public void stopOpen(net.minecraft.world.entity.ContainerUser player) {
            openers = Math.max(0, openers - 1);
        }
    }
}
