package com.nirncraft.arsenal;

import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.entity.SkyArrow;
import com.nirncraft.arsenal.item.ArrowKind;
import com.nirncraft.arsenal.item.SkyCrossbowItem;
import com.nirncraft.combat.CombatHandler;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Forge-bus rules of the arsenal: arrow bookkeeping (corpse arrows, contract 27), headshots, elemental arrows,
 * armor-piercing crossbows, leveled weapon drops from humanoid hostiles, kill-cam triggers and Wabbajack chickens.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class ArsenalEvents {
    /** Victim persistent data: ListTag of ItemStack NBT, the arrows stuck in its body (cap {@value #MAX_ARROWS}). */
    public static final String BODY_ARROWS = "nirncraft_arrows";
    public static final int MAX_ARROWS = 16;
    /** Arrow persistent data: the ammo item it was shot as (bows; NirnCraft arrows know it themselves). */
    public static final String AMMO = "nirncraft_ammo";
    /** Arrow persistent data: fraction of armor ignored (NirnCraft crossbows). */
    public static final String ARMOR_PIERCE = "nirncraft_armor_pierce";
    private static final String RECORDED = "nirncraft_recorded";
    private static final String PREPARED = "nirncraft_arsenal_prepared";
    private static final String BOUND_ARROW = "nirncraft_bound_arrow";
    public static final String COLLECTIBLE = "nirncraft_collectible";
    private static final net.minecraft.resources.Identifier AFFIX_WEAPON_UUID = net.minecraft.resources.Identifier.parse("nirncraft:modifier_9d4e8c12-6a3b-4f91-8e27-5c1a9f0b3d40");
    private static final net.minecraft.resources.Identifier[] AFFIX_ARMOR_UUIDS = {
            net.minecraft.resources.Identifier.parse("nirncraft:modifier_9d4e8c12-6a3b-4f91-8e27-5c1a9f0b3d41"),
            net.minecraft.resources.Identifier.parse("nirncraft:modifier_9d4e8c12-6a3b-4f91-8e27-5c1a9f0b3d42"),
            net.minecraft.resources.Identifier.parse("nirncraft:modifier_9d4e8c12-6a3b-4f91-8e27-5c1a9f0b3d43"),
            net.minecraft.resources.Identifier.parse("nirncraft:modifier_9d4e8c12-6a3b-4f91-8e27-5c1a9f0b3d44")
    };
    private static final net.minecraft.resources.Identifier AFFIX_SHIELD_UUID = net.minecraft.resources.Identifier.parse("nirncraft:modifier_9d4e8c12-6a3b-4f91-8e27-5c1a9f0b3d45");

    private ArsenalEvents() {}

    // ------------------------------------------------------------------ affix stat modifiers

    @SubscribeEvent
    public static void onAffixAttributes(net.neoforged.neoforge.event.ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        if (!com.nirncraft.loot.affix.ItemAffixes.hasAffixes(stack)) return;
        net.minecraft.world.entity.EquipmentSlot slot = com.nirncraft.inventory.EquipmentData.slot(stack);
        if (com.nirncraft.inventory.EquipmentData.isArmor(stack)) {
            float mult = com.nirncraft.loot.affix.ItemAffixes.armorMultiplier(stack);
            if (slot != null && slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR && Math.abs(mult - 1.0f) > 0.001f) {
                event.addModifier(Attributes.ARMOR, new net.minecraft.world.entity.ai.attributes.AttributeModifier(AFFIX_ARMOR_UUIDS[slot.getIndex()], mult - 1.0,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE), slotGroup263(slot));
            }
        } else if (stack.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            float mult = com.nirncraft.loot.affix.ItemAffixes.armorMultiplier(stack);
            if (Math.abs(mult - 1.0f) > 0.001f) {
                event.addModifier(Attributes.ARMOR, new net.minecraft.world.entity.ai.attributes.AttributeModifier(AFFIX_SHIELD_UUID, (mult - 1.0) * 4.0,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE), net.minecraft.world.entity.EquipmentSlotGroup.OFFHAND);
            }
        } else if (!(stack.getItem() instanceof ProjectileWeaponItem)
                && com.nirncraft.crafting.Tempering.kind(stack) == com.nirncraft.crafting.Tempering.Kind.WEAPON) {
            float mult = com.nirncraft.loot.affix.ItemAffixes.damageMultiplier(stack);
            if (Math.abs(mult - 1.0f) > 0.001f) {
                event.addModifier(Attributes.ATTACK_DAMAGE, new net.minecraft.world.entity.ai.attributes.AttributeModifier(AFFIX_WEAPON_UUID, mult - 1.0,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE), net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND);
            }
        }
    }

    private static net.minecraft.world.entity.EquipmentSlotGroup slotGroup263(net.minecraft.world.entity.EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> net.minecraft.world.entity.EquipmentSlotGroup.HEAD;
            case CHEST -> net.minecraft.world.entity.EquipmentSlotGroup.CHEST;
            case LEGS -> net.minecraft.world.entity.EquipmentSlotGroup.LEGS;
            case FEET -> net.minecraft.world.entity.EquipmentSlotGroup.FEET;
            default -> net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND;
        };
    }

    // ------------------------------------------------------------------ arrows leaving the bow

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractArrow arrow) || event.loadedFromDisk()) return;
        CompoundTag pd = arrow.getPersistentData();
        if (pd.getBooleanOr(PREPARED, false)) return;
        pd.putBoolean(PREPARED, true);

        if (!(arrow instanceof SkyArrow) && arrow.pickup == AbstractArrow.Pickup.ALLOWED) {
            pd.putBoolean(COLLECTIBLE, true);
            arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY; // disable survival walk-over auto-pickup
        }

        if (!(arrow.getOwner() instanceof LivingEntity owner)) return;

        ItemStack rangedWeapon = owner.getUseItem();
        if (!(rangedWeapon.getItem() instanceof ProjectileWeaponItem)) rangedWeapon = owner.getMainHandItem();
        if (!(rangedWeapon.getItem() instanceof ProjectileWeaponItem)) rangedWeapon = owner.getOffhandItem();
        if (com.nirncraft.loot.affix.ItemAffixes.hasAffixes(rangedWeapon)) {
            arrow.setBaseDamage(com.nirncraft.platform.EntityData.arrowDamage(arrow) * com.nirncraft.loot.affix.ItemAffixes.damageMultiplier(rangedWeapon));
        }

        if ((arrow.getWeaponItem() != null && arrow.getWeaponItem().getItem() instanceof net.minecraft.world.item.CrossbowItem)) {
            if (arrow instanceof SkyArrow sky) {
                sky.setFromCrossbow(true);
            }
            // Crossbows launch bolts at higher initial speed (4.2F vs vanilla 3.15F) with flatter near-range trajectory
            Vec3 v = arrow.getDeltaMovement();
            if (v.lengthSqr() > 1.0e-4) {
                arrow.setDeltaMovement(v.normalize().scale(4.2));
                arrow.syncVelocity = true;
            }
            SkyCrossbowItem crossbow = owner.getMainHandItem().getItem() instanceof SkyCrossbowItem c ? c
                    : owner.getOffhandItem().getItem() instanceof SkyCrossbowItem c2 ? c2 : null;
            if (crossbow != null) {
                arrow.setBaseDamage(com.nirncraft.platform.EntityData.arrowDamage(arrow) + crossbow.bonusDamage);
                pd.putFloat(ARMOR_PIERCE, crossbow.armorPierce);
            }
            return;
        }
        if (!(arrow instanceof SkyArrow) && owner instanceof Player player) {
            ItemStack weapon = player.getUseItem();
            if (weapon.getItem() instanceof ProjectileWeaponItem) {
                ItemStack ammo = player.getProjectile(weapon);
                if (!ammo.isEmpty()) {
                    ItemStack one = ammo.copy();
                    one.setCount(1);
                    pd.put(AMMO, com.nirncraft.inventory.ItemData.save(one));
                }
            }
        }
    }

    /** Break chance on block impact for vanilla non-SkyArrow projectiles. */
    @SubscribeEvent
    public static void onProjectileImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (event.getProjectile().level().isClientSide()) return;
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof SkyArrow) return;
        if (event.getRayTraceResult().getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            float breakChance = SkyArrow.breakChanceFor(null, arrow.getOwner());
            if (arrow.level().getRandom().nextFloat() < breakChance) {
                if (arrow.level() instanceof ServerLevel sl) {
                    sl.playSound(null, arrow.blockPosition(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 0.5f, 1.2f);
                    sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, net.minecraft.world.item.ItemStackTemplate.fromStack( ammoOf(arrow))),
                            arrow.getX(), arrow.getY(), arrow.getZ(), 6, 0.1, 0.1, 0.1, 0.05);
                }
                arrow.discard();
            }
        }
    }

    /** The item an arrow entity would be picked up as. */
    public static ItemStack ammoOf(AbstractArrow arrow) {
        if (arrow instanceof SkyArrow sky) return sky.pickupStack();
        CompoundTag pd = arrow.getPersistentData();
        if (com.nirncraft.platform.NbtData.hasType(pd, AMMO, Tag.TAG_COMPOUND)) {
            ItemStack stack = com.nirncraft.inventory.ItemData.parse(pd.getCompoundOrEmpty(AMMO));
            if (!stack.isEmpty()) return stack;
        }
        if (arrow instanceof SpectralArrow) return new ItemStack(Items.SPECTRAL_ARROW);
        return new ItemStack(Items.ARROW);
    }

    // ------------------------------------------------------------------ interactive arrow pickup

    public static boolean isCollectibleArrow(Player player, AbstractArrow arrow) {
        if (arrow == null || !arrow.isAlive() || (!SkyArrow.isArrowInGround(arrow) && !arrow.isInWater())) return false;
        CompoundTag pd = arrow.getPersistentData();
        if (pd.getBooleanOr(BOUND_ARROW, false)) return false;
        return arrow.pickup == AbstractArrow.Pickup.ALLOWED
                || pd.getBooleanOr(COLLECTIBLE, false)
                || (player.getAbilities().instabuild && arrow.pickup == AbstractArrow.Pickup.CREATIVE_ONLY);
    }

    public static boolean tryPickupArrow(Player player, AbstractArrow arrow) {
        if (!isCollectibleArrow(player, arrow) || player.distanceToSqr(arrow) > 25.0) return false;
        if (!player.level().isClientSide()) {
            CompoundTag pd = arrow.getPersistentData();
            boolean giveItem = arrow.pickup == AbstractArrow.Pickup.ALLOWED || pd.getBooleanOr(COLLECTIBLE, false);
            if (giveItem) {
                ItemStack stack = ammoOf(arrow);
                if (!stack.isEmpty()) {
                    com.nirncraft.vitals.ActionHandler.addToBags(player, stack);
                }
            }
            player.take(arrow, 1);
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                    0.4f, 1.1f + player.getRandom().nextFloat() * 0.3f);
            if (arrow instanceof SkyArrow sky) {
                sky.clearDynamicLight();
            }
            arrow.discard();
        }
        return true;
    }

    public static boolean tryPullArrowFromBody(Player player, LivingEntity target) {
        if (target == null || !target.isAlive() || target instanceof Player || player.distanceToSqr(target) > 20.0) return false;
        if (player.level().isClientSide()) return false;
        CompoundTag pd = target.getPersistentData();
        ListTag list = pd.getListOrEmpty(BODY_ARROWS);
        if (list.isEmpty()) return false;
        int lastIdx = list.size() - 1;
        CompoundTag itemTag = list.getCompoundOrEmpty(lastIdx);
        list.remove(lastIdx);
        pd.put(BODY_ARROWS, list);
        ItemStack stack = com.nirncraft.inventory.ItemData.parse(itemTag);
        if (!stack.isEmpty()) {
            com.nirncraft.vitals.ActionHandler.addToBags(player, stack);
            if (target.getArrowCount() > 0) {
                target.setArrowCount(target.getArrowCount() - 1);
            }
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                    0.4f, 1.0f + player.getRandom().nextFloat() * 0.3f);
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onEntityInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof AbstractArrow arrow && tryPickupArrow(event.getEntity(), arrow)) {
            event.setCancellationResult(com.nirncraft.platform.Interactions.sidedSuccess(event.getLevel().isClientSide()));
            event.setCanceled(true);
        } else if (event.getTarget() instanceof LivingEntity living
                && !(living instanceof net.minecraft.world.entity.npc.Npc)
                && !(living instanceof net.minecraft.world.entity.animal.equine.AbstractHorse)) {
            if (!event.getLevel().isClientSide() && tryPullArrowFromBody(event.getEntity(), living)) {
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Vec3 hitLoc = event.getHitVec() != null ? event.getHitVec().getLocation() : Vec3.atCenterOf(event.getPos());
        var arrows = event.getLevel().getEntitiesOfClass(AbstractArrow.class,
                new net.minecraft.world.phys.AABB(hitLoc, hitLoc).inflate(1.1),
                a -> isCollectibleArrow(player, a));
        if (!arrows.isEmpty()) {
            AbstractArrow closest = arrows.get(0);
            double bestDist = closest.position().distanceToSqr(hitLoc);
            for (int i = 1; i < arrows.size(); i++) {
                double d = arrows.get(i).position().distanceToSqr(hitLoc);
                if (d < bestDist) {
                    bestDist = d;
                    closest = arrows.get(i);
                }
            }
            if (tryPickupArrow(player, closest)) {
                event.setCancellationResult(com.nirncraft.platform.Interactions.sidedSuccess(event.getLevel().isClientSide()));
                event.setCanceled(true);
            }
        }
    }

    // ------------------------------------------------------------------ arrows hitting

    /** Headshots, elemental arrows and armor piercing (before armor is applied). */
    @SubscribeEvent
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        DamageSource source = event.getSource();
        if (!(source.getDirectEntity() instanceof AbstractArrow arrow)) return;
        float amount = event.getAmount();

        if (arrow instanceof SkyArrow sky) {
            ArrowKind kind = sky.kind();
            if (kind != null) {
                switch (kind.element) {
                    case FIRE -> {
                        if (!sky.isExtinguished() && !sky.isInWater()) {
                            amount += victim.fireImmune() ? 0 : 1.5f;
                        }
                    }
                    case FROST -> amount += 1.5f;
                    case SHOCK -> {
                        amount += 2.5f;
                        if (victim instanceof Player p) {
                            PlayerData d = SkyData.get(p);
                            d.setMagicka(d.getMagicka() - 10f);
                        }
                        if (victim.level() instanceof ServerLevel sl) {
                            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6,
                                    victim.getZ(), 12, 0.3, 0.4, 0.3, 0.2);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        if (ArsenalConfig.HEADSHOTS.get() && arrow.getOwner() instanceof ServerPlayer shooter && humanoid(victim) && headshot(arrow, victim)) {
            amount *= ArsenalConfig.HEADSHOT_MULTIPLIER.get().floatValue();
            Notifier.message(shooter, Component.translatable("message.nirncraft.arsenal.headshot"));
            if (victim.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getEyeY(), victim.getZ(), 10, 0.2, 0.15, 0.2, 0.3);
            }
            com.nirncraft.magic.MagicFx.soundToPlayer(shooter, SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.6f, 1.6f);
        }

        float pierce = arrow.getPersistentData().getFloatOr(ARMOR_PIERCE, 0f);
        if (pierce > 0 && !source.is(DamageTypeTags.BYPASSES_ARMOR) && victim.getArmorValue() > 0) {
            float armor = victim.getArmorValue();
            float toughness = (float) victim.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            float full = CombatRules.getDamageAfterAbsorb(victim, amount, source, armor, toughness);
            float pierced = CombatRules.getDamageAfterAbsorb(victim, amount, source, armor * (1f - pierce), toughness);
            if (full > 0.01f) amount *= pierced / full;
        }
        event.setAmount(amount);
    }

    /** Arrows that wounded a mob stay in its body (contract 27: the corpse adds them to its loot) if they don't break on impact. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void recordArrow(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || victim instanceof Player || event.getAmount() <= 0) return;
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow) || !ArsenalConfig.RECORD_ARROWS.get()) return;
        CompoundTag apd = arrow.getPersistentData();
        if (apd.getBooleanOr(RECORDED, false) || apd.getBooleanOr(BOUND_ARROW, false)) return;
        Entity owner = arrow.getOwner();
        boolean fromPlayer = owner instanceof Player && (arrow.pickup == AbstractArrow.Pickup.ALLOWED || apd.getBooleanOr(COLLECTIBLE, false));
        boolean fromMob = owner instanceof Mob && !(owner instanceof Player);
        if (!fromPlayer && !fromMob) return;
        ItemStack stack = ammoOf(arrow);
        if (stack.isEmpty() || (arrow instanceof SkyArrow sky && sky.element() == ArrowKind.Element.EXPLOSIVE)) return;
        apd.putBoolean(RECORDED, true);
        if (fromPlayer) {
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED; // piercing arrows can't be collected twice
            apd.remove(COLLECTIBLE);
        }
        ArrowKind kind = arrow instanceof SkyArrow sky ? sky.kind() : null;
        float breakChance = SkyArrow.breakChanceFor(kind, owner);
        if (victim.getRandom().nextFloat() < breakChance) {
            return; // arrow broke on impact
        }
        CompoundTag pd = victim.getPersistentData();
        ListTag list = pd.getListOrEmpty(BODY_ARROWS);
        if (list.size() >= MAX_ARROWS) return;
        ItemStack one = stack.copy();
        one.setCount(1);
        list.add(com.nirncraft.inventory.ItemData.save(one));
        pd.put(BODY_ARROWS, list);
    }

    private static boolean humanoid(LivingEntity e) {
        float h = e.getBbHeight();
        return h >= 1.4f && h <= 2.4f && e.getBbWidth() <= 1.0f;
    }

    /** Did the arrow enter above the victim's eyes - 0.25? The arrow is at its previous position when it hits. */
    private static boolean headshot(AbstractArrow arrow, LivingEntity victim) {
        Vec3 from = arrow.position();
        Vec3 to = from.add(arrow.getDeltaMovement());
        double y = victim.getBoundingBox().inflate(0.3).clip(from, to).map(v -> v.y).orElse(arrow.getY());
        return y >= victim.getEyeY() - 0.25;
    }

    // ------------------------------------------------------------------ leveled drops

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        CompoundTag pd = victim.getPersistentData();
        if (pd.contains(StaffEffects.WABBAJACK_ORIGINAL)) {
            event.setCanceled(true); // Wabbajack chickens leave nothing behind (summons: see magic's Summons)
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer) || !(victim instanceof Mob) || !(victim instanceof Enemy)) return;
        if (victim.isBaby() || !humanoid(victim) || victim.getType().builtInRegistryHolder().is(ArsenalTags.GUARDS) || victim.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) return;
        var random = victim.getRandom();
        double chance = ArsenalConfig.MOB_WEAPON_DROP_CHANCE.get() * (1 + 0.3 * (event.getSource().getEntity() instanceof LivingEntity killer263 ? net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel(com.nirncraft.platform.EnchantmentsAccess.holder(net.minecraft.world.item.enchantment.Enchantments.LOOTING, killer263.registryAccess()), killer263) : 0));
        if (random.nextDouble() >= chance) return;
        int level = Math.max(1, SkyData.get(killer).getLevel());
        ItemStack loot;
        if (victim instanceof AbstractSkeleton) {
            loot = random.nextBoolean() ? LeveledGear.bow(level, random) : LeveledGear.arrows(level, random);
        } else {
            loot = LeveledGear.weapon(level, random);
        }
        if (!loot.isEmpty() && loot.getMaxStackSize() == 1) {
            loot.setDamageValue(random.nextInt(Math.max(1, loot.getMaxDamage() / 3)));
            com.nirncraft.tamriel.Region region = com.nirncraft.economy.EconomyLoot.regionAt(victim.level(), victim.blockPosition());
            if (region != null) com.nirncraft.loot.affix.ItemAffixes.setRegionHint(region.id());
            LeveledGear.decorate(loot, level, random, 0.05f, 0.05f);
        }
        event.getDrops().add(new ItemEntity(victim.level(), victim.getX(), victim.getY() + 0.5, victim.getZ(), loot));
    }

    @SubscribeEvent
    public static void onXp(LivingExperienceDropEvent event) {
        CompoundTag pd = event.getEntity().getPersistentData();
        if (pd.contains(StaffEffects.WABBAJACK_ORIGINAL)) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ kill cams

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || victim instanceof Player || !ArsenalConfig.KILL_CAMS.get()) return;
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer killer) || !killer.isAlive()) return;
        Entity direct = source.getDirectEntity();
        boolean melee = direct == killer;
        boolean arrow = direct instanceof AbstractArrow;
        if (!melee && !arrow) return;
        boolean hostile = victim instanceof Enemy || victim instanceof Mob mob && mob.getTarget() == killer;
        if (!hostile || victim.distanceToSqr(killer) > (arrow ? 64 * 64 : 12 * 12)) return;
        boolean last = victim.level().getEntitiesOfClass(Mob.class, killer.getBoundingBox().inflate(24),
                m -> m != victim && m.isAlive() && (m instanceof Enemy || m.getTarget() == killer)).isEmpty();
        double chance = last ? ArsenalConfig.KILL_CAM_LAST_ENEMY_CHANCE.get() : ArsenalConfig.KILL_CAM_OTHER_CHANCE.get();
        if (killer.getRandom().nextDouble() >= chance) return;
        Vec3 dir = arrow ? direct.getDeltaMovement() : victim.position().subtract(killer.position());
        if (dir.lengthSqr() < 1.0E-4) dir = killer.getViewVector(1f);
        dir = dir.normalize();
        // Sent on the killing blow, before the loot body replaces the victim: the packet carries where the victim was.
        Vec3 mid = victim.position().add(0, victim.getBbHeight() * 0.55, 0);
        SkyNetwork.sendToPlayer(killer, new ArsenalPackets.KillCam(victim.getId(),
                arrow ? ArsenalPackets.KillCam.ARROW : ArsenalPackets.KillCam.MELEE, arrow ? direct.getId() : -1,
                (float) dir.x, (float) dir.y, (float) dir.z, (float) mid.x, (float) mid.y, (float) mid.z));
        com.nirncraft.magic.MagicFx.soundToPlayer(killer, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1f, 0.55f);
        com.nirncraft.magic.MagicFx.soundToPlayer(killer, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.8f, 0.7f);
    }

    // ------------------------------------------------------------------ staff effect lifetimes

    /** Wabbajack chickens turn back. (Summons, calm and fury are status effects / summons of the magic module.) */
    @SubscribeEvent
    public static void onLivingTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity e)) return;
        if (e.level().isClientSide() || e.tickCount % 10 != 0 || e instanceof Player) return;
        CompoundTag pd = e.getPersistentData();
        if (pd.isEmpty()) return;
        long now = e.level().getGameTime();
        if (pd.contains(StaffEffects.WABBAJACK_UNTIL) && now > pd.getLongOr(StaffEffects.WABBAJACK_UNTIL, 0L) && pd.contains(StaffEffects.WABBAJACK_ORIGINAL)) {
            StaffEffects.unchicken(e);
        }
    }
}
