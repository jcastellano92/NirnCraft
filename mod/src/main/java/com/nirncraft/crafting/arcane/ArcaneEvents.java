package com.nirncraft.crafting.arcane;

import com.nirncraft.NirnCraft;
import com.nirncraft.combat.ArmorClass;
import com.nirncraft.combat.WeaponClass;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.Skill;
import com.nirncraft.crafting.SoulGems;
import com.nirncraft.crafting.arcane.alchemy.Alchemy;
import com.nirncraft.crafting.arcane.alchemy.Ingredients;
import com.nirncraft.crafting.arcane.effect.ArcaneEffects;
import com.nirncraft.perk.Perks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.nirncraft.platform.ArcaneData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Forge-bus handlers of the arcane module: learning ingredient effects by eating, Snakeblood and the
 * resist/weakness/fortify alchemy effects, weapon poisons, Soul Siphon and the creature drops of alchemy salts.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class ArcaneEvents {
    /** Weapon NBT holding an applied poison: {Effects: [MobEffectInstance...], Hits: int, MaxHits: int, Name: json}. */
    public static final String WEAPON_POISON = "nirncraft_weapon_poison";
    public static final String POISON_HITS = "Hits";
    public static final String POISON_MAX_HITS = "MaxHits";
    public static final String POISON_NAME = "Name";

    public static String poisonDisplayName(CompoundTag poisonTag) {
        String raw = poisonTag.getStringOr(POISON_NAME, "");
        if (raw.isEmpty()) return Component.translatable("tooltip.nirncraft.alchemy.poison").getString();
        try {
            Component parsed = ArcaneData.readComponent(raw);
            if (parsed != null) return parsed.getString();
        } catch (Exception ignored) {
        }
        return raw;
    }

    private ArcaneEvents() {}

    @SubscribeEvent
    public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        com.nirncraft.crafting.arcane.enchant.Enchanting.clearRegistryCache();
    }

    @SubscribeEvent
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        com.nirncraft.crafting.arcane.enchant.Enchanting.clearRegistryCache();
    }


    // ------------------------------------------------------------------ learning by eating

    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack used = event.getItem();
        if (!used.has(net.minecraft.core.component.DataComponents.FOOD)) return;
        Ingredients.Ingredient ing = Ingredients.get(used);
        if (ing != null) Alchemy.discoverByTasting(player, ing);
    }

    @SubscribeEvent
    public static void onRightClickIngredient(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return;
        Ingredients.Ingredient ing = Ingredients.get(stack);
        if (ing != null) {
            Player player = event.getEntity();
            if (player instanceof ServerPlayer sp) {
                Alchemy.discoverByTasting(sp, ing);
                if (!sp.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            }
            player.playSound(SoundEvents.GENERIC_EAT.value(), 0.7f, 0.9f + player.getRandom().nextFloat() * 0.2f);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    // Green Thumb lives in exactly one place: com.nirncraft.survival.Harvesting (harvests and left-click breaks).

    // ------------------------------------------------------------------ damage: resistances, weaknesses, fortify, poisons

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        DamageSource source = event.getSource();
        float amount = event.getAmount();
        String msg = source.getMsgId().toLowerCase(Locale.ROOT);

        // ---- elemental resistances and weaknesses of the target
        if (source.is(DamageTypeTags.IS_FIRE) || ((msg.contains("fire") || msg.contains("burn")) && !msg.contains("firework"))) {
            amount *= weakness(target, ArcaneEffects.WEAKNESS_TO_FIRE);
        }
        if (source.is(DamageTypes.FREEZE) || msg.contains("frost") || msg.contains("freez") || msg.contains("ice")) {
            amount *= resist(target, ArcaneEffects.RESIST_FROST) * weakness(target, ArcaneEffects.WEAKNESS_TO_FROST);
        }
        if (source.is(DamageTypes.LIGHTNING_BOLT) || msg.contains("shock") || msg.contains("lightning")) {
            amount *= resist(target, ArcaneEffects.RESIST_SHOCK) * weakness(target, ArcaneEffects.WEAKNESS_TO_SHOCK);
        }
        boolean poison = msg.contains("poison") || (source.is(DamageTypes.MAGIC) && target.hasEffect(com.nirncraft.platform.Effects.holder(MobEffects.POISON)));
        if (poison) {
            amount *= resist(target, ArcaneEffects.RESIST_POISON) * weakness(target, ArcaneEffects.WEAKNESS_TO_POISON);
            if (target instanceof Player p && Perks.has(p, "alchemy.snakeblood")) amount *= 0.5f;
        } else if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC) || msg.contains("magic") || msg.contains("spell")) {
            amount *= resist(target, ArcaneEffects.RESIST_MAGIC) * weakness(target, ArcaneEffects.WEAKNESS_TO_MAGIC);
        }

        // ---- fortify armor skills of the target
        int light = ArcaneEffects.level(target, ArcaneEffects.FORTIFY_LIGHT_ARMOR);
        if (light > 0) amount *= 1f - Math.min(0.5f, 0.025f * light * ArmorClass.countLight(target));
        int heavy = ArcaneEffects.level(target, ArcaneEffects.FORTIFY_HEAVY_ARMOR);
        if (heavy > 0) amount *= 1f - Math.min(0.5f, 0.025f * heavy * ArmorClass.countHeavy(target));

        // ---- attacker: fortify weapon skills and weapon poisons
        if (source.getEntity() instanceof LivingEntity attacker && attacker != target) {
            if (source.getDirectEntity() == attacker) {
                WeaponClass wc = WeaponClass.of(attacker.getMainHandItem());
                if (wc.skill == Skill.ONE_HANDED) amount *= 1f + 0.1f * ArcaneEffects.level(attacker, ArcaneEffects.FORTIFY_ONE_HANDED);
                if (wc.skill == Skill.TWO_HANDED) amount *= 1f + 0.1f * ArcaneEffects.level(attacker, ArcaneEffects.FORTIFY_TWO_HANDED);
                if (attacker instanceof ServerPlayer player) amount += applyWeaponPoison(player, target);
            } else if (source.getDirectEntity() instanceof AbstractArrow) {
                amount *= 1f + 0.1f * ArcaneEffects.level(attacker, ArcaneEffects.FORTIFY_ARCHERY);
                if (attacker instanceof ServerPlayer player) amount += applyWeaponPoison(player, target);
            }
        }
        event.setAmount(Math.max(0f, amount));
    }

    private static float resist(LivingEntity e, Supplier<MobEffect> effect) {
        MobEffectInstance inst = e.getEffect(com.nirncraft.platform.Effects.holder(effect.get()));
        return inst == null ? 1f : Math.max(0f, 1f - 0.2f * (inst.getAmplifier() + 1));
    }

    private static float weakness(LivingEntity e, Supplier<MobEffect> effect) {
        MobEffectInstance inst = e.getEffect(com.nirncraft.platform.Effects.holder(effect.get()));
        return inst == null ? 1f : 1f + 0.25f * (inst.getAmplifier() + 1);
    }

    public static final int DEFAULT_POISON_HITS = 5;
    public static final int CONCENTRATED_POISON_HITS = 10;

    /** True if {@code stack} is a potion or poison with at least one harmful effect that can be coated onto a weapon. */
    public static boolean isApplicablePoison(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.PotionItem)) return false;
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        if (tag != null && tag.getBooleanOr(Alchemy.POISON_TAG, false)) return true;
        for (MobEffectInstance inst : ArcaneData.effects(stack)) {
            if (!inst.getEffect().value().isBeneficial()) return true;
        }
        return false;
    }

    /** True if {@code weapon} is a melee weapon or bow that can be coated with poison. */
    public static boolean canReceivePoison(ItemStack weapon) {
        if (weapon.isEmpty()) return false;
        WeaponClass wc = WeaponClass.of(weapon);
        return wc.skill == Skill.ONE_HANDED || wc.skill == Skill.TWO_HANDED || wc == WeaponClass.BOW;
    }

    /** Finds the player's equipped weapon to coat with poison (main hand first, then offhand). */
    public static ItemStack targetWeaponForPoison(Player player) {
        ItemStack main = player.getMainHandItem();
        if (canReceivePoison(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (canReceivePoison(off)) return off;
        return ItemStack.EMPTY;
    }

    /** Coats the player's equipped weapon with the poison in {@code poisonStack} from the inventory screen. */
    public static boolean applyPoisonFromInventory(ServerPlayer player, ItemStack poisonStack) {
        if (!isApplicablePoison(poisonStack)) return false;
        ItemStack weapon = targetWeaponForPoison(player);
        if (weapon.isEmpty()) {
            Notifier.message(player, Component.translatable("message.nirncraft.alchemy.no_weapon_for_poison"));
            return false;
        }
        if (!coatWeapon(player, poisonStack, weapon)) return false;
        if (!player.isCreative()) {
            boolean giveBottle = poisonStack.is(net.minecraft.world.item.Items.POTION);
            poisonStack.shrink(1);
            if (giveBottle) {
                com.nirncraft.inventory.Pack.giveOrDrop(player, new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
            }
        }
        return true;
    }

    private static boolean coatWeapon(Player player, ItemStack poisonStack, ItemStack weapon) {
        List<MobEffectInstance> all = ArcaneData.effects(poisonStack);
        if (all.isEmpty()) return false;
        ListTag list = new ListTag();
        for (MobEffectInstance inst : all) {
            if (!inst.getEffect().value().isBeneficial()) list.add(ArcaneData.saveEffect(inst, player.registryAccess()));
        }
        if (list.isEmpty()) {
            for (MobEffectInstance inst : all) list.add(ArcaneData.saveEffect(inst, player.registryAccess()));
        }
        int maxHits = Perks.has(player, "alchemy.concentrated_poison") ? CONCENTRATED_POISON_HITS : DEFAULT_POISON_HITS;
        CompoundTag poison = new CompoundTag();
        poison.put("Effects", list);
        poison.putInt("Hits", maxHits);
        poison.putInt("MaxHits", maxHits);
        poison.putInt("Color", ArcaneData.color(all));
        poison.putString("Name", ArcaneData.writeComponent(poisonStack.getHoverName(), player.registryAccess()));
        com.nirncraft.inventory.ItemData.updateCustom(weapon, customData263 -> customData263.put(WEAPON_POISON, poison));
        player.level().playSound(null, player.blockPosition(), SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.8f, 1f);
        if (player instanceof ServerPlayer sp) {
            Notifier.message(sp, Component.translatable("message.nirncraft.alchemy.weapon_poisoned", weapon.getHoverName()));
        }
        return true;
    }

    /** Coats a wilderness chest weapon with a leveled poison (never used on enemy mob equipment). */
    public static void applyRandomWildernessPoison(ItemStack weapon, int level, RandomSource random) {
        if (!canReceivePoison(weapon)) return;
        int amp = level >= 30 ? 2 : level >= 15 ? 1 : 0;
        MobEffectInstance effect;
        Component name;
        int color;
        switch (random.nextInt(4)) {
            case 0 -> {
                effect = new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.INSTANT_DAMAGE), 1, Math.min(1, amp));
                name = Component.translatable("item.nirncraft.poison_of",
                        com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.DAMAGE_HEALTH.displayName());
                color = 0x8A1828;
            }
            case 1 -> {
                effect = new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.POISON), (8 + amp * 4) * 20, amp);
                name = Component.translatable("item.nirncraft.poison_of", Component.translatable("effect.minecraft.poison"));
                color = 0x4E9331;
            }
            case 2 -> {
                effect = new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), (10 + amp * 5) * 20, amp);
                name = Component.translatable("item.nirncraft.poison_of",
                        com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.SLOW.displayName());
                color = 0x5A6C81;
            }
            default -> {
                effect = new MobEffectInstance(com.nirncraft.platform.Effects.holder(ArcaneEffects.DAMAGE_STAMINA.get()), 1, amp);
                name = Component.translatable("item.nirncraft.poison_of",
                        com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.DAMAGE_STAMINA.displayName());
                color = 0x589838;
            }
        }
        int maxHits = DEFAULT_POISON_HITS;
        int hits = 2 + random.nextInt(maxHits - 1);
        ListTag list = new ListTag();
        list.add(ArcaneData.saveEffect(effect));
        CompoundTag poison = new CompoundTag();
        poison.put("Effects", list);
        poison.putInt("Hits", hits);
        poison.putInt("MaxHits", maxHits);
        poison.putInt("Color", color);
        poison.putString("Name", ArcaneData.writeComponent(name));
        com.nirncraft.inventory.ItemData.updateCustom(weapon, customData263 -> customData263.put(WEAPON_POISON, poison));
    }

    /** Applies a poison coated on the attacker's weapon (main hand, else offhand); returns extra instant damage. */
    private static float applyWeaponPoison(ServerPlayer player, LivingEntity target) {
        ItemStack weapon = player.getMainHandItem();
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(weapon);
        if (tag == null || !com.nirncraft.platform.NbtData.hasType(tag, WEAPON_POISON, Tag.TAG_COMPOUND)) {
            weapon = player.getOffhandItem();
            tag = com.nirncraft.inventory.ItemData.custom(weapon);
            if (tag == null || !com.nirncraft.platform.NbtData.hasType(tag, WEAPON_POISON, Tag.TAG_COMPOUND)) return 0f;
        }
        CompoundTag poison = tag.getCompoundOrEmpty(WEAPON_POISON);
        float extra = 0f;
        ListTag effects = poison.getListOrEmpty("Effects");
        for (int i = 0; i < effects.size(); i++) {
            MobEffectInstance inst = ArcaneData.loadEffect(effects.getCompoundOrEmpty(i), player.registryAccess());
            if (inst == null) continue;
            MobEffect effect = inst.getEffect().value();
            if (effect == MobEffects.INSTANT_DAMAGE.value()) {
                // instant damage can't re-enter hurt() while the target is already being hurt: add it to this hit
                extra += 6 << Math.min(inst.getAmplifier(), 3);
            } else if (effect.isInstantaneous()) {
                effect.applyInstantaneousEffect(player.level(), player, player, target, inst.getAmplifier(), 1.0);
            } else {
                target.addEffect(new MobEffectInstance(inst), player);
            }
        }
        int hits = poison.getIntOr("Hits", 0) - 1;
        if (hits <= 0) {
            tag.remove(WEAPON_POISON);
            if (tag.isEmpty()) com.nirncraft.inventory.ItemData.setCustom(weapon, null);
            Notifier.message(player, Component.translatable("message.nirncraft.alchemy.poison_worn_off"));
        } else {
            poison.putInt("Hits", hits);
            tag.put(WEAPON_POISON, poison);
        }
        com.nirncraft.inventory.ItemData.setCustom(weapon, tag);
        return extra;
    }

    /** Sneak + use a poison while holding a weapon in the other hand to coat the weapon. */
    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        ItemStack poisonStack = event.getItemStack();
        if (!player.isShiftKeyDown() || !isApplicablePoison(poisonStack)) return;
        InteractionHand other = event.getHand() == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack weapon = player.getItemInHand(other);
        if (!canReceivePoison(weapon)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player.level().isClientSide()) return;

        if (coatWeapon(player, poisonStack, weapon) && !player.isCreative()) {
            boolean giveBottle = poisonStack.is(net.minecraft.world.item.Items.POTION);
            poisonStack.shrink(1);
            if (giveBottle && player instanceof ServerPlayer sp) {
                com.nirncraft.inventory.Pack.giveOrDrop(sp, new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
            }
        }
    }

    // ------------------------------------------------------------------ Fortify Sneak

    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent event) {
        LivingEntity entity = event.getEntity();
        int lvl = ArcaneEffects.level(entity, ArcaneEffects.FORTIFY_SNEAK);
        if (lvl > 0 && entity.isShiftKeyDown()) event.modifyVisibility(Math.max(0.2, 1.0 - 0.15 * lvl));
    }

    // ------------------------------------------------------------------ Fortify Magicka / Stamina / Carry Weight

    /** Pool keys of the core's {@code module("bonus")} and what one level of the matching fortify effect adds. */
    private static final String[] FORTIFY_KEYS = {"magicka", "stamina", "carry"};
    private static final float FORTIFY_STEP = 25f;
    private static final String APPLIED_FORTIFY = "applied_fortify";

    /**
     * Keeps this module's share of {@code module("bonus")} equal to what the active Fortify Magicka, Fortify Stamina
     * and Fortify Carry Weight effects call for (+25 per level). Only the difference to what was applied before is
     * added, so standing stones, blessings and other modules sharing the keys are left alone; when the effect ends
     * (or the player dies) the next check takes the bonus back.
     */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % 10 != 3) return;
        float[] want = {
                FORTIFY_STEP * ArcaneEffects.level(player, ArcaneEffects.FORTIFY_MAGICKA),
                FORTIFY_STEP * ArcaneEffects.level(player, ArcaneEffects.FORTIFY_STAMINA),
                FORTIFY_STEP * ArcaneEffects.level(player, ArcaneEffects.FORTIFY_CARRY_WEIGHT)
        };
        com.nirncraft.core.PlayerData data = com.nirncraft.core.SkyData.get(player);
        CompoundTag module = data.module(ArcaneModule.DATA);
        CompoundTag applied = module.getCompoundOrEmpty(APPLIED_FORTIFY);
        CompoundTag bonus = data.module("bonus");
        boolean changed = false;
        for (int i = 0; i < FORTIFY_KEYS.length; i++) {
            float diff = want[i] - applied.getFloatOr(FORTIFY_KEYS[i], 0f);
            if (diff == 0f) continue;
            bonus.putFloat(FORTIFY_KEYS[i], bonus.getFloatOr(FORTIFY_KEYS[i], 0f) + diff);
            applied.putFloat(FORTIFY_KEYS[i], want[i]);
            changed = true;
        }
        if (!changed) return;
        module.put(APPLIED_FORTIFY, applied);
        // the pools may now exceed a lowered maximum
        data.setMagicka(data.getMagicka());
        data.setStamina(data.getStamina());
        data.markDirty();
        data.markVitalsDirty();
        com.nirncraft.vitals.Vitals.refreshAttributes(player);
    }

    // ------------------------------------------------------------------ Soul Siphon

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player) || source.getDirectEntity() != player) return;
        if (!Perks.has(player, "enchanting.soul_siphon") || SoulGems.isBlackSoul(victim)) return;
        ItemStack weapon = player.getMainHandItem();
        if (!weapon.isEnchanted() || !weapon.isDamageableItem() || !weapon.isDamaged()) return;
        int soul = SoulGems.soulSize(victim);
        int repair = Math.max(1, Math.round(weapon.getMaxDamage() * 0.01f * soul));
        weapon.setDamageValue(Math.max(0, weapon.getDamageValue() - repair));
    }

    // ------------------------------------------------------------------ creature drops: salts, claws, feathers

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();
        if (level.isClientSide() || !event.isRecentlyHit()) return;
        RandomSource r = entity.getRandom();
        EntityType<?> type = entity.getType();
        float bonus = event.getSource().getEntity() instanceof LivingEntity attacker ? 0.05f * net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel(com.nirncraft.platform.EnchantmentsAccess.holder(net.minecraft.world.item.enchantment.Enchantments.LOOTING, entity.registryAccess()), attacker) : 0f;
        if (type == net.minecraft.world.entity.EntityTypes.BLAZE) drop(event, ArcaneRegistry.FIRE_SALTS.get(), 0.35f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE) drop(event, ArcaneRegistry.FIRE_SALTS.get(), 0.10f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.STRAY) drop(event, ArcaneRegistry.FROST_SALTS.get(), 0.40f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.ENDERMAN) drop(event, ArcaneRegistry.VOID_SALTS.get(), 0.20f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.WITHER_SKELETON) drop(event, ArcaneRegistry.VOID_SALTS.get(), 0.12f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.POLAR_BEAR) drop(event, ArcaneRegistry.BEAR_CLAWS.get(), 0.60f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.PARROT) drop(event, ArcaneRegistry.HAWK_FEATHER.get(), 0.50f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.CHICKEN) drop(event, ArcaneRegistry.HAWK_FEATHER.get(), 0.06f + bonus, r);
        else if (type == net.minecraft.world.entity.EntityTypes.WITCH) drop(event, ArcaneRegistry.SALT_PILE.get(), 0.30f + bonus, r);
        else {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            // NirnCraft's vampires (and their lords) leave vampire dust (curse module)
            if (key != null && key.getNamespace().equals("nirncraft") && key.getPath().startsWith("vampire")) {
                drop(event, ArcaneRegistry.VAMPIRE_DUST.get(), 0.50f + bonus, r);
            }
            if (key != null && key.getPath().contains("bear") && !key.getPath().contains("polar")) {
                drop(event, ArcaneRegistry.BEAR_CLAWS.get(), 0.60f + bonus, r);
            }
        }
    }

    private static void drop(LivingDropsEvent event, Item item, float chance, RandomSource r) {
        if (r.nextFloat() >= chance) return;
        LivingEntity e = event.getEntity();
        ItemEntity drop = new ItemEntity(e.level(), e.getX(), e.getY() + 0.3, e.getZ(), new ItemStack(item));
        drop.setDefaultPickUpDelay();
        event.getDrops().add(drop);
    }
}
