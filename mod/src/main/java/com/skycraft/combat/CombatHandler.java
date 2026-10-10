package com.skycraft.combat;

import com.skycraft.SkyConfig;
import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.perk.Perks;
import com.skycraft.registry.ModEffects;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.ActionHandler;
import com.skycraft.vitals.RacePowers;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skyrim combat rules: skill/perk damage scaling, power attacks (stamina), sneak attacks, weapon & shield
 * blocking, armor skills, racial resistances and the combat/armor/block/sneak/archery perks.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CombatHandler {
    /** Entities that take reduced sneak attack multipliers and can't be paralyzed (bosses, dragons). */
    public static final TagKey<EntityType<?>> BOSSES = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("forge", "bosses"));
    /** Creatures counted as "beasts/monsters" for the Hunting tree's Monster Hunter perk. */
    public static final TagKey<EntityType<?>> MONSTERS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "monsters"));
    /** Skyrim damage is roughly 5x Minecraft damage; XP use values are computed in Skyrim units. */
    public static final float SKYRIM_SCALE = 5f;

    /** Set while a combat side effect deals its damage, so the nested LivingHurtEvent isn't treated as a new swing. */
    private static final ThreadLocal<Boolean> SECONDARY = ThreadLocal.withInitial(() -> false);
    /** Game time of each player's last melee swing (vanilla sweeping hits arrive in the same tick). */
    private static final Map<UUID, Long> LAST_SWING = new HashMap<>();

    private CombatHandler() {}

    /**
     * Deals damage caused by a combat side effect (two-handed sweep, shield bash, reflected blows, fire cloak...).
     * It never re-runs swing logic (stamina, power attacks, skill XP) and never chains into another side effect.
     */
    public static boolean secondaryHurt(LivingEntity target, DamageSource source, float amount) {
        if (SECONDARY.get()) return false;
        SECONDARY.set(true);
        try {
            return target.hurt(source, amount);
        } finally {
            SECONDARY.set(false);
        }
    }

    /** A real melee hit: a player or mob attack dealt directly by the attacker (not a side effect). */
    private static boolean isMeleeSwing(DamageSource source, Entity attacker) {
        return !SECONDARY.get() && attacker != null && source.getDirectEntity() == attacker
                && (source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK));
    }

    public static void forget(UUID player) {
        LAST_SWING.remove(player);
    }

    // ------------------------------------------------------------------ offense & defense

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        if (target.level().isClientSide) return;

        // Party protection: members of the same party cannot damage each other with direct combat or magic
        if (target instanceof ServerPlayer defender && source.getEntity() instanceof ServerPlayer attacker && attacker != defender) {
            var pm = com.skycraft.quest.party.PartyManager.get(attacker.getServer());
            var p1 = pm.partyOf(attacker.getUUID());
            var p2 = pm.partyOf(defender.getUUID());
            if (p1 != null && p1 == p2) {
                if (source.getDirectEntity() == attacker
                        || source.getDirectEntity() instanceof AbstractArrow
                        || source.is(DamageTypeTags.IS_PROJECTILE)
                        || source.is(DamageTypes.PLAYER_ATTACK)
                        || source.is(DamageTypes.MAGIC)
                        || source.is(DamageTypes.INDIRECT_MAGIC)) {
                    event.setCanceled(true);
                    return;
                }
            }
        }

        // Secondary hits (sweep, bash, reflect, cloak...) are dealt through secondaryHurt and never count as a swing.
        if (source.getEntity() instanceof ServerPlayer attacker && attacker != target && !SECONDARY.get()) {
            Vitals.markInCombat(attacker);
            float amount = event.getAmount();
            if (isMeleeSwing(source, attacker)) {
                // Vanilla's sweeping edge hits several mobs in the same tick: only the first is the swing.
                long now = attacker.level().getGameTime();
                Long last = LAST_SWING.put(attacker.getUUID(), now);
                if (last == null || last != now) amount = meleeDamage(attacker, target, amount);
            } else if (source.getDirectEntity() instanceof AbstractArrow arrow) {
                amount = arrowDamage(attacker, target, arrow, amount);
            }
            event.setAmount(amount);
        }

        if (target instanceof ServerPlayer defender) {
            Vitals.markInCombat(defender);
            float amount = defend(defender, source, event.getAmount());
            if (amount <= 0) {
                event.setCanceled(true);
                return;
            }
            event.setAmount(amount);
        }
    }

    private static float meleeDamage(ServerPlayer player, LivingEntity target, float amount) {
        PlayerData data = SkyData.get(player);
        ItemStack weapon = player.getMainHandItem();
        WeaponClass wc = WeaponClass.of(weapon);
        float mult = 1f;
        double scale = SkyConfig.WEAPON_SKILL_DAMAGE_SCALE.get();
        var rnd = player.getRandom();

        // Unsheathe on swing
        Sheathe.setSheathed(player, false);

        // Weapon cooldown: penalize spamming
        if (player.getAttackStrengthScale(0.5f) < 0.75f) {
            mult *= 0.4f;
        }

        // Melee swing stamina drain
        float swingCost = wc.twoHanded() ? 10f : (wc == WeaponClass.UNARMED ? 4f : 6f);
        boolean dualWield = !player.getMainHandItem().isEmpty() && !player.getOffhandItem().isEmpty()
                && WeaponClass.of(player.getMainHandItem()).skill == Skill.ONE_HANDED
                && WeaponClass.of(player.getOffhandItem()).skill == Skill.ONE_HANDED;
        if (dualWield) swingCost = 8f;
        if (!Vitals.consumeStamina(player, swingCost, true)) {
            mult *= 0.5f; // Weak swing when exhausted
        }

        // Two-handed weapons stagger on hit
        if (wc.twoHanded()) {
            stagger(player, target, 20);
        }

        if (wc.skill == Skill.ONE_HANDED) {
            mult += (float) (data.getSkill(Skill.ONE_HANDED) * scale) + 0.2f * Perks.rank(data, "one_handed.armsman");
        } else if (wc.skill == Skill.TWO_HANDED) {
            mult += (float) (data.getSkill(Skill.TWO_HANDED) * scale) + 0.2f * Perks.rank(data, "two_handed.barbarian");
        } else if (wc.skill == Skill.POLEARM) {
            // Polearms skill (skills/Polearms has the other perks)
            mult += (float) (data.getSkill(Skill.POLEARM) * scale) + 0.2f * Perks.rank(data, "polearm.lancer");
        } else if (wc == WeaponClass.UNARMED) {
            int unSkill = data.getSkill(Skill.UNARMED);
            amount += unSkill * 0.12f;
            int pugilist = Perks.rank(data, "unarmed.pugilist");
            if (pugilist > 0) amount *= (1f + 0.2f * pugilist);
            if (Perks.has(data, "unarmed.iron_fist")) amount += 3f;
            if (data.getRace() == Race.KHAJIIT) amount += 4f;
            if (Perks.has(data, "heavy_armor.fists_of_steel")) {
                ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
                if (chest.getItem() instanceof ArmorItem a && ArmorClass.isHeavy(chest)) amount += a.getDefense() * 0.5f;
            }
            if (Perks.has(data, "unarmed.heavy_strikes")) stagger(player, target, 25);
            if (Perks.has(data, "unarmed.grandmaster") && rnd.nextFloat() < 0.2f) {
                mult *= 2.0f;
                stagger(player, target, 45);
                Notifier.message(player, Component.translatable("message.skycraft.critical"));
            }
            Progression.addSkillXp(player, Skill.UNARMED, Math.min(amount * mult, target.getHealth()) * SKYRIM_SCALE * 0.25f);
        }

        // Weapon-family perks
        switch (wc) {
            case SWORD, DAGGER -> {
                int r = Perks.rank(data, "one_handed.bladesman");
                if (r > 0 && rnd.nextFloat() < 0.1f * r) {
                    mult *= 1.5f;
                    Notifier.message(player, Component.translatable("message.skycraft.critical"));
                }
            }
            case GREATSWORD -> {
                int r = Perks.rank(data, "two_handed.deep_wounds");
                if (r > 0 && rnd.nextFloat() < 0.1f * r) {
                    mult *= 1.5f;
                    Notifier.message(player, Component.translatable("message.skycraft.critical"));
                }
            }
            case WAR_AXE -> bleed(target, Perks.rank(data, "one_handed.hack_and_slash"));
            case BATTLEAXE -> bleed(target, Perks.rank(data, "two_handed.limbsplitter"));
            case MACE -> mult += 0.15f * Perks.rank(data, "one_handed.bone_breaker");
            case WARHAMMER -> mult += 0.15f * Perks.rank(data, "two_handed.skullcrusher");
            default -> {
            }
        }

        // Power attacks: hold the power-attack key while swinging, or attack while sprinting.
        boolean sprinting = player.isSprinting();
        boolean power = ActionHandler.consumePowerAttack(player) || sprinting;
        if (power && wc != WeaponClass.BOW && wc != WeaponClass.OTHER) {
            float cost = (float) (double) SkyConfig.POWER_ATTACK_STAMINA.get();
            if (wc.twoHanded()) cost *= 1.3f;
            if (wc == WeaponClass.UNARMED) cost *= 0.8f;
            if (wc.skill == Skill.ONE_HANDED && Perks.has(data, "one_handed.fighting_stance")) cost *= 0.75f;
            if (wc.skill == Skill.TWO_HANDED && Perks.has(data, "two_handed.champions_stance")) cost *= 0.75f;
            if (Vitals.consumeStamina(player, cost, true)) {
                mult *= wc.twoHanded() ? 1.75f : 1.5f;
                if (wc == WeaponClass.UNARMED && Perks.has(data, "unarmed.haymaker")) mult *= 1.5f;
                if (wc.skill == Skill.ONE_HANDED && Perks.has(data, "one_handed.savage_strike")) mult *= 1.25f;
                if (wc.skill == Skill.TWO_HANDED && Perks.has(data, "two_handed.devastating_blow")) mult *= 1.25f;
                if (sprinting && (wc.skill == Skill.ONE_HANDED && Perks.has(data, "one_handed.critical_charge")
                        || wc.skill == Skill.TWO_HANDED && Perks.has(data, "two_handed.great_critical_charge"))) {
                    mult *= 1.5f;
                }
                stagger(player, target, 20);
                boolean paralyzePerk = wc.skill == Skill.ONE_HANDED ? Perks.has(data, "one_handed.paralyzing_strike")
                        : Perks.has(data, "two_handed.warmaster");
                if (paralyzePerk && rnd.nextFloat() < 0.25f && !target.getType().is(BOSSES)) {
                    target.addEffect(new MobEffectInstance(ModEffects.PARALYSIS.get(), 60));
                }
                if (wc.twoHanded() && Perks.has(data, "two_handed.sweep")) sweep(player, target, amount * mult * 0.5f);
                player.level().playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1f, 0.7f);
            } else {
                mult *= 0.5f; // exhausted power attacks are weak
            }
        }

        // Legendary weapon effects
        LegendaryItem.Prefix legPrefix = LegendaryItem.getPrefix(weapon);
        if (legPrefix != null) {
            switch (legPrefix) {
                case INSTIGATING -> {
                    if (target.getHealth() >= target.getMaxHealth() * 0.95f) mult *= 2f;
                }
                case WOUNDING -> bleed(target, 3);
                case VAMPIRIC -> player.heal(Math.max(1f, amount * mult * 0.15f));
                case INCENDIARY -> {
                    target.setSecondsOnFire(5);
                    amount += 4f;
                }
                case FREEZING -> {
                    target.addEffect(new MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 80, 1));
                    amount += 4f;
                }
                case SHOCKING -> {
                    amount += 4f;
                    if (target instanceof ServerPlayer tp) Vitals.consumeMagicka(tp, 25f);
                }
                case STAGGERING -> stagger(player, target, 30);
                case DEADEYE -> {
                    if (rnd.nextFloat() < 0.25f) {
                        mult *= 1.75f;
                        Notifier.message(player, Component.translatable("message.skycraft.critical"));
                    }
                }
                case JUGGERNAUT -> mult *= (1f + 0.3f * (player.getHealth() / player.getMaxHealth()));
                case BERSERKER -> {
                    int ac = ArmorClass.countHeavy(player) + ArmorClass.countLight(player);
                    if (ac <= 1) mult *= 1.35f;
                }
                default -> {}
            }
        }

        mult *= huntingMultiplier(data, target);
        mult *= sneakMultiplier(player, data, target, wc);

        float result = amount * mult;
        if (wc.skill != null && wc.skill != Skill.ARCHERY) {
            Progression.addSkillXp(player, wc.skill, Math.min(result, target.getHealth()) * SKYRIM_SCALE * 0.2f);
        }
        return result;
    }

    private static float arrowDamage(ServerPlayer player, LivingEntity target, AbstractArrow arrow, float amount) {
        PlayerData data = SkyData.get(player);
        var rnd = player.getRandom();
        float mult = 1f + (float) (data.getSkill(Skill.ARCHERY) * SkyConfig.WEAPON_SKILL_DAMAGE_SCALE.get())
                + 0.2f * Perks.rank(data, "archery.overdraw");
        int crit = Perks.rank(data, "archery.critical_shot");
        if (crit > 0 && rnd.nextFloat() < 0.1f * crit) {
            mult *= 1.5f;
            Notifier.message(player, Component.translatable("message.skycraft.critical"));
        }
        if (Perks.has(data, "archery.power_shot") && arrow.isCritArrow()) stagger(player, target, 20);
        if (Perks.has(data, "archery.bullseye") && rnd.nextFloat() < 0.15f && !target.getType().is(BOSSES)) {
            target.addEffect(new MobEffectInstance(ModEffects.PARALYSIS.get(), 60));
        }
        if (Perks.has(data, "archery.hunters_discipline") && arrow.pickup == AbstractArrow.Pickup.ALLOWED && rnd.nextBoolean()) {
            ActionHandler.addToBags(player, new ItemStack(Items.ARROW));
        }
        mult *= huntingMultiplier(data, target);
        mult *= sneakMultiplier(player, data, target, WeaponClass.BOW);
        float result = amount * mult;
        Progression.addSkillXp(player, Skill.ARCHERY, Math.min(result, target.getHealth()) * SKYRIM_SCALE * 0.2f);
        return result;
    }

    private static float huntingMultiplier(PlayerData data, LivingEntity target) {
        float mult = 1f;
        if (target instanceof Animal) mult += 0.2f * Perks.rank(data, "hunting.tracker");
        if (target.getType().is(MONSTERS) && Perks.has(data, "hunting.monster_hunter")) mult += 0.25f;
        if (Perks.has(data, "hunting.apex_predator") && (target instanceof Animal || target.getType().is(MONSTERS))) mult += 0.25f;
        return mult;
    }

    /** Skyrim sneak attacks against targets that aren't aware of you. */
    private static float sneakMultiplier(ServerPlayer player, PlayerData data, LivingEntity target, WeaponClass wc) {
        if (!SkyConfig.SNEAK_ATTACKS.get() || !player.isCrouching() || target instanceof Player) return 1f;
        if (target instanceof Mob mob && mob.getTarget() == player) return 1f;
        float mult = switch (wc) {
            case DAGGER -> Perks.has(data, "sneak.assassins_blade") ? 15f : Perks.has(data, "sneak.backstab") ? 6f : 3f;
            case SWORD, WAR_AXE, MACE -> Perks.has(data, "sneak.backstab") ? 6f : 3f;
            case BOW -> Perks.has(data, "sneak.deadly_aim") ? 3f : 2f;
            default -> 2f;
        };
        if (target.getType().is(BOSSES)) mult = Math.min(mult, 2f);
        Notifier.message(player, Component.translatable("message.skycraft.sneak_attack", (int) mult));
        Progression.addSkillXp(player, Skill.SNEAK, 2.5f * mult);
        data.addStat("sneak_attacks", 1);
        return mult;
    }

    private static float defend(ServerPlayer player, DamageSource source, float amount) {
        PlayerData data = SkyData.get(player);
        var rnd = player.getRandom();
        Entity attacker = source.getEntity();
        boolean melee = isMeleeSwing(source, attacker);
        boolean armorApplies = !source.is(DamageTypeTags.BYPASSES_ARMOR);

        // Racial and magical resistances
        Race race = data.getRace();
        if (race != null) {
            if (source.is(DamageTypeTags.IS_FIRE)) amount *= 1f - race.resistance("fire");
            if (source.is(DamageTypeTags.IS_FREEZING)) amount *= 1f - race.resistance("frost");
            if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)) {
                amount *= 1f - race.resistance("magic") - race.resistance("poison") * 0.5f;
            }
        }
        if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)) {
            amount *= 1f - 0.1f * Perks.rank(data, "alteration.magic_resistance");
        }

        int heavy = ArmorClass.countHeavy(player);
        int light = ArmorClass.countLight(player);

        if (melee && light >= 4 && Perks.has(data, "light_armor.deft_movement") && rnd.nextFloat() < 0.1f) {
            Notifier.message(player, Component.translatable("message.skycraft.evaded"));
            return 0;
        }
        if (melee && heavy >= 4 && Perks.has(data, "heavy_armor.reflect_blows") && rnd.nextFloat() < 0.1f
                && attacker instanceof LivingEntity living) {
            secondaryHurt(living, player.damageSources().thorns(player), amount);
            return 0;
        }

        // Weapon and unarmed blocking (hold block key or RMB with weapon or fists).
        if (armorApplies && attacker != null && ActionHandler.isWeaponBlocking(player) && facing(player, attacker)) {
            WeaponClass wc = WeaponClass.of(player.getMainHandItem());
            if (wc.skill == Skill.ONE_HANDED || wc.skill == Skill.TWO_HANDED || wc == WeaponClass.UNARMED) {
                float basePct = wc == WeaponClass.UNARMED ? 0.2f : 0.3f;
                float pct = Math.min(0.7f, basePct + data.getSkill(Skill.BLOCK) * 0.002f + 0.05f * Perks.rank(data, "block.shield_wall"));
                float blocked = amount * pct;
                if (!Vitals.consumeStamina(player, blocked * SKYRIM_SCALE * 0.4f, true)) blocked *= 0.5f;
                amount -= blocked;
                Progression.addSkillXp(player, Skill.BLOCK, blocked * SKYRIM_SCALE * 0.2f);
                player.level().playSound(null, player.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.3f, 1.8f);
            }
        }

        // Armor skills
        if (armorApplies && (heavy > 0 || light > 0)) {
            float reduction = 0f;
            if (heavy > 0) {
                float r = data.getSkill(Skill.HEAVY_ARMOR) * 0.0015f + 0.04f * Perks.rank(data, "heavy_armor.juggernaut");
                if (heavy >= 4 && Perks.has(data, "heavy_armor.well_fitted")) r += 0.05f;
                if (heavy >= 4 && Perks.has(data, "heavy_armor.matching_set") && ArmorClass.matchingSet(player)) r += 0.05f;
                reduction += r * heavy / 4f;
                Progression.addSkillXp(player, Skill.HEAVY_ARMOR, amount * SKYRIM_SCALE * 0.2f * heavy / 4f);
            }
            if (light > 0) {
                float r = data.getSkill(Skill.LIGHT_ARMOR) * 0.0015f + 0.04f * Perks.rank(data, "light_armor.agile_defender");
                if (light >= 4 && Perks.has(data, "light_armor.custom_fit")) r += 0.05f;
                if (light >= 4 && Perks.has(data, "light_armor.matching_set_light") && ArmorClass.matchingSet(player)) r += 0.05f;
                reduction += r * light / 4f;
                Progression.addSkillXp(player, Skill.LIGHT_ARMOR, amount * SKYRIM_SCALE * 0.2f * light / 4f);
            }
            amount *= 1f - Math.min(0.5f, reduction);
        }

        // Heavy armor makes you hard to knock around.
        if (heavy >= 4 && Perks.has(data, "heavy_armor.tower_of_strength")) {
            player.removeEffect(ModEffects.STAGGER.get());
        }

        // Legendary armor effects
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ARMOR) {
                LegendaryItem.Prefix lp = LegendaryItem.getPrefix(player.getItemBySlot(slot));
                if (lp != null) {
                    if (lp == LegendaryItem.Prefix.BOLSTERING) {
                        float missing = 1f - (player.getHealth() / player.getMaxHealth());
                        amount *= (1f - 0.12f * missing);
                    } else if (lp == LegendaryItem.Prefix.UNYIELDING && player.getHealth() <= player.getMaxHealth() * 0.3f) {
                        amount *= 0.8f;
                    }
                }
            }
        }

        return amount;
    }

    @SubscribeEvent
    public static void onAttackEntity(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getMainHandItem().isEmpty()) {
            net.minecraft.world.InteractionHand hand = (player.tickCount % 2 == 0)
                    ? net.minecraft.world.InteractionHand.MAIN_HAND
                    : net.minecraft.world.InteractionHand.OFF_HAND;
            player.swing(hand, true);
        }
    }

    private static boolean facing(Player player, Entity attacker) {
        Vec3 look = player.getViewVector(1f).multiply(1, 0, 1).normalize();
        Vec3 dir = attacker.position().subtract(player.position()).multiply(1, 0, 1).normalize();
        return look.dot(dir) > 0.2;
    }

    private static void bleed(LivingEntity target, int rank) {
        if (rank > 0) target.addEffect(new MobEffectInstance(ModEffects.BLEEDING.get(), 60, rank - 1));
    }

    public static void stagger(Entity source, LivingEntity target, int ticks) {
        if (target.getType().is(BOSSES)) return;
        if (target instanceof ServerPlayer p && ArmorClass.countHeavy(p) >= 4 && Perks.has(p, "heavy_armor.tower_of_strength")) return;
        target.addEffect(new MobEffectInstance(ModEffects.STAGGER.get(), ticks, 0, false, false));
        Vec3 push = target.position().subtract(source.position()).multiply(1, 0, 1).normalize().scale(0.6);
        target.push(push.x, 0.15, push.z);
        target.hurtMarked = true;
    }

    private static void sweep(ServerPlayer player, LivingEntity primary, float damage) {
        Vec3 look = player.getViewVector(1f);
        for (LivingEntity other : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.5),
                e -> e != player && e != primary && e.isAlive() && (e instanceof Enemy || e instanceof Mob m && m.getTarget() == player))) {
            Vec3 dir = other.position().subtract(player.position()).normalize();
            if (look.dot(dir) > 0.3) secondaryHurt(other, player.damageSources().playerAttack(player), damage);
        }
    }

    // ------------------------------------------------------------------ shields

    @SubscribeEvent
    public static void onShieldBlock(ShieldBlockEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerData data = SkyData.get(player);
        DamageSource source = event.getDamageSource();
        float original = event.getOriginalBlockedDamage();
        float pct = Math.min(0.95f, 0.6f + data.getSkill(Skill.BLOCK) * 0.002f + 0.05f * Perks.rank(data, "block.shield_wall"));
        if (source.getDirectEntity() instanceof AbstractArrow && Perks.has(data, "block.deflect_arrows")) pct = 1f;
        if (source.is(DamageTypeTags.IS_FIRE) && Perks.has(data, "block.elemental_protection")) pct = Math.max(pct, 0.9f);
        float blocked = original * pct;
        if (!Vitals.consumeStamina(player, blocked * SKYRIM_SCALE * 0.25f, true)) blocked *= 0.5f;
        event.setBlockedDamage(blocked);
        Progression.addSkillXp(player, Skill.BLOCK, blocked * SKYRIM_SCALE * 0.25f);
        Vitals.markInCombat(player);

        if (source.getEntity() instanceof LivingEntity attacker && isMeleeSwing(source, attacker)) {
            if (Perks.has(data, "block.quick_reflexes") || Perks.has(data, "block.power_bash")) stagger(player, attacker, 20);
            if (Perks.has(data, "block.deadly_bash")) secondaryHurt(attacker, player.damageSources().playerAttack(player), blocked * 0.25f);
            if (Perks.has(data, "block.disarming_bash") && attacker instanceof Mob mob && player.getRandom().nextFloat() < 0.1f
                    && !mob.getMainHandItem().isEmpty()) {
                mob.spawnAtLocation(mob.getMainHandItem().copy());
                mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                Notifier.message(player, Component.translatable("message.skycraft.disarmed"));
            }
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof Player player && ArmorClass.countHeavy(player) >= 4 && Perks.has(player, "heavy_armor.cushioned")) {
            event.setDamageMultiplier(event.getDamageMultiplier() * 0.5f);
        }
    }

    /** Archery "Quick Shot": draw bows 30% faster. */
    @SubscribeEvent
    public static void onUseTick(LivingEntityUseItemEvent.Tick event) {
        if (event.getEntity() instanceof Player player && event.getItem().getItem() instanceof BowItem
                && Perks.has(player, "archery.quick_shot") && player.tickCount % 3 == 0) {
            event.setDuration(event.getDuration() - 1);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 == 0) {
            RacePowers.tickFireCloak(player);
            // Block "Shield Charge": sprinting into enemies with a shield knocks them down.
            if (player.isSprinting() && Perks.has(player, "block.shield_charge") && player.getOffhandItem().getItem() instanceof ShieldItem) {
                for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(1.2),
                        e -> e != player && e instanceof Enemy)) {
                    stagger(player, e, 40);
                }
            }
        }
    }
}
