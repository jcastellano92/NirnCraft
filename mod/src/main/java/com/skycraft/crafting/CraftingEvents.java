package com.skycraft.crafting;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import com.skycraft.crafting.menu.StationMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Forge-bus hooks of the smithing module: tempering bonuses and animal hides. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CraftingEvents {
    private static final UUID WEAPON_UUID = UUID.fromString("5b7d3a2e-1c4f-4e8b-9a61-2f0d8c3e7a10");
    private static final UUID[] ARMOR_UUIDS = {
            UUID.fromString("5b7d3a2e-1c4f-4e8b-9a61-2f0d8c3e7a11"), // feet
            UUID.fromString("5b7d3a2e-1c4f-4e8b-9a61-2f0d8c3e7a12"), // legs
            UUID.fromString("5b7d3a2e-1c4f-4e8b-9a61-2f0d8c3e7a13"), // chest
            UUID.fromString("5b7d3a2e-1c4f-4e8b-9a61-2f0d8c3e7a14")  // head
    };
    private static final String ARROW_FLAG = "skycraft_tempered_arrow";
    /** Animals that drop Skyrim hide when killed by a player. */
    public static final TagKey<EntityType<?>> DROPS_HIDE = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "drops_hide"));

    private CraftingEvents() {}

    /** Tempered quality: +0.5 attack damage per level (weapon in main hand), +0.5 armor per level (armor in its slot). */
    @SubscribeEvent
    public static void onAttributes(ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        int quality = Tempering.quality(stack);
        if (quality <= 0) return;
        EquipmentSlot slot = event.getSlotType();
        if (stack.getItem() instanceof ArmorItem armor) {
            if (slot == armor.getEquipmentSlot() && slot.getType() == EquipmentSlot.Type.ARMOR) {
                event.addModifier(Attributes.ARMOR, new AttributeModifier(ARMOR_UUIDS[slot.getIndex()], "Skycraft tempering",
                        0.5 * quality, AttributeModifier.Operation.ADDITION));
            }
        } else if (slot == EquipmentSlot.MAINHAND && Tempering.kind(stack) == Tempering.Kind.WEAPON
                && !(stack.getItem() instanceof ProjectileWeaponItem)) {
            event.addModifier(Attributes.ATTACK_DAMAGE, new AttributeModifier(WEAPON_UUID, "Skycraft tempering",
                    0.5 * quality, AttributeModifier.Operation.ADDITION));
        }
    }

    /** Tempered bows and crossbows shoot harder arrows (+0.15 base damage per quality level, ~+0.45 at full draw). */
    @SubscribeEvent
    public static void onArrow(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (arrow.getPersistentData().getBoolean(ARROW_FLAG)) return;
        arrow.getPersistentData().putBoolean(ARROW_FLAG, true);
        if (!(arrow.getOwner() instanceof LivingEntity owner)) return;
        ItemStack weapon = owner.getUseItem();
        if (!(weapon.getItem() instanceof ProjectileWeaponItem)) weapon = owner.getMainHandItem();
        if (!(weapon.getItem() instanceof ProjectileWeaponItem)) weapon = owner.getOffhandItem();
        int quality = Tempering.quality(weapon);
        if (quality > 0) arrow.setBaseDamage(arrow.getBaseDamage() + 0.15 * quality);
    }

    /** Skyrim animals leave hides: player kills of {@code #skycraft:drops_hide} creatures drop Hide. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(event.getSource().getEntity() instanceof Player) || victim.isBaby() || !victim.getType().is(DROPS_HIDE)) return;
        var random = victim.getRandom();
        if (random.nextFloat() >= 0.6f + 0.1f * event.getLootingLevel()) return;
        event.getDrops().add(new ItemEntity(victim.level(), victim.getX(), victim.getY() + 0.5, victim.getZ(),
                new ItemStack(CraftingItems.HIDE.get())));
    }

    /**
     * The vanilla crafting stations open their Skyrim equivalents. Sneaking with an item places it as usual; sneaking
     * empty-handed opens the vanilla crafting table / furnace / smoker / anvil / grindstone / smithing table.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        var state = event.getLevel().getBlockState(event.getPos());
        var b = state.getBlock();
        if (b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.DoublePlantBlock
                || b instanceof net.minecraft.world.level.block.IronBarsBlock
                || b instanceof net.minecraft.world.level.block.FenceBlock) {
            Player player = event.getEntity();
            net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
            net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0f);
            net.minecraft.world.phys.Vec3 end = eye.add(look.scale(4.5));
            net.minecraft.world.phys.AABB searchBox = player.getBoundingBox().expandTowards(look.scale(4.5)).inflate(1.0);
            var corpses = event.getLevel().getEntitiesOfClass(com.skycraft.creatures.entity.CorpseEntity.class, searchBox,
                    c -> c.isAlive() && c.getBoundingBox().inflate(0.5).clip(eye, end).isPresent());
            if (!corpses.isEmpty()) {
                var res = corpses.get(0).interact(player, event.getHand());
                event.setCancellationResult(res);
                event.setCanceled(true);
                return;
            }
        }

        // Sneaking with something in hand places the held block / uses the item (vanilla), never a station menu.
        Player user = event.getEntity();
        boolean handsEmpty = user.getMainHandItem().isEmpty() && user.getOffhandItem().isEmpty();
        boolean sneaking = user.isSecondaryUseActive();
        if (sneaking && !handsEmpty) return;

        StationType stationType = null;
        boolean isCooking = false;
        boolean isEnchanter = false;
        boolean isAlchemy = false;

        if (b == Blocks.CARTOGRAPHY_TABLE) {
            if (sneaking) return; // vanilla cartography table
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (event.getLevel().isClientSide) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.world.client.MapClient.openMap());
            }
            return;
        }

        // Sneak + empty hand opens the vanilla block instead (3x3 crafting grid, furnace smelting, anvil, smoker,
        // grindstone, smithing table): vanilla recipes and the shipped crafting/smelting JSONs stay usable.
        if (sneaking && (b == Blocks.CRAFTING_TABLE || b == Blocks.SMITHING_TABLE || b == Blocks.FURNACE
                || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER || b == Blocks.GRINDSTONE
                || b instanceof net.minecraft.world.level.block.AnvilBlock)) {
            return;
        }

        if (b == Blocks.CRAFTING_TABLE || b == Blocks.SMITHING_TABLE) {
            stationType = StationType.ARMOR_WORKBENCH;
        } else if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE) {
            stationType = StationType.SMELTER;
        } else if (b instanceof net.minecraft.world.level.block.AnvilBlock) {
            stationType = StationType.FORGE;
        } else if (b == Blocks.GRINDSTONE) {
            stationType = StationType.GRINDSTONE;
        } else if (b == Blocks.SMOKER) {
            isCooking = true;
        } else if (b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) {
            // Only an empty-handed use cooks Skyrim meals; flint and steel, shovels and raw food on the fire stay vanilla.
            isCooking = user.getMainHandItem().isEmpty() && !sneaking;
        } else if (b == Blocks.ENCHANTING_TABLE) {
            isEnchanter = true;
        } else if (b == Blocks.BREWING_STAND) {
            isAlchemy = true;
        }

        if (stationType != null || isCooking || isEnchanter || isAlchemy) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (event.getEntity() instanceof ServerPlayer sp) {
                if (com.skycraft.crime.Ownership.isOwnedByOther(sp, event.getLevel(), event.getPos()) && !com.skycraft.economy.Shop.isOpen(event.getLevel())) {
                    com.skycraft.core.Notifier.message(sp, net.minecraft.network.chat.Component.translatable("dialogue.skycraft.economy.closed"));
                    event.getLevel().playSound(null, event.getPos(), net.minecraft.sounds.SoundEvents.CHEST_LOCKED, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f);
                    return;
                }
                if (stationType != null) {
                    StationMenu.open(sp, stationType, event.getPos());
                } else if (isCooking) {
                    com.skycraft.survival.cooking.CookingMenu.open(sp, event.getPos());
                } else if (isEnchanter) {
                    com.skycraft.network.SkyNetwork.sendToPlayer(sp, new com.skycraft.crafting.arcane.ArcanePackets.OpenStation(
                            com.skycraft.crafting.arcane.block.StationBlock.Kind.ENCHANTER.ordinal(), event.getPos()));
                } else {
                    com.skycraft.network.SkyNetwork.sendToPlayer(sp, new com.skycraft.crafting.arcane.ArcanePackets.OpenStation(
                            com.skycraft.crafting.arcane.block.StationBlock.Kind.ALCHEMY.ordinal(), event.getPos()));
                }
            }
        }
    }
}
