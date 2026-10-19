package com.nirncraft.survival;

import com.nirncraft.SkyConfig;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.Buffs;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.registry.ModEffects;
import com.nirncraft.skills.Progression;
import com.nirncraft.survival.inn.Innkeepers;
import com.nirncraft.vitals.Vitals;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime rules of the survival module: disease and blessing effects on the vitals, catching diseases from creatures,
 * curing them with the arcane {@code nirncraft:cure_disease} effect, the skooma crash and the inn's rested bonus.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SurvivalEvents {
    public static final Identifier CURE_DISEASE_ID = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "cure_disease");
    /** Buff flag set when a player slept in a rented inn room (+5% skill XP on top of Well Rested). */
    public static final String INN_RESTED = "inn_rested";
    private static final String[] POOLS = {"health", "magicka", "stamina"};

    /** Players to cure on their next tick (cures requested from inside effect callbacks). */
    private static final Set<UUID> CURE_QUEUE = new HashSet<>();

    private SurvivalEvents() {}

    public static void register() {
        Progression.registerXpModifier((player, skill) -> Buffs.active(player, INN_RESTED) ? 1.05f : 1f);
    }

    public static void queueCure(LivingEntity entity) {
        if (entity instanceof Player) CURE_QUEUE.add(entity.getUUID());
    }

    private static boolean isCureDisease(MobEffect effect) {
        return CURE_DISEASE_ID.equals(BuiltInRegistries.MOB_EFFECT.getKey(effect));
    }

    private static boolean has(Player player, java.util.function.Supplier<MobEffect> effect) {
        return player.hasEffect(com.nirncraft.platform.Effects.holder(effect.get()));
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!CURE_QUEUE.isEmpty() && CURE_QUEUE.remove(player.getUUID())) Diseases.cureAll(player);
        long now = player.level().getGameTime();
        if (now % 5 == 0) adjustRegen(player, SkyData.get(player), now);
        if (now % 20 == 0) {
            reconcileBonuses(player);
            checkSkooma(player);
        }
    }

    /**
     * Regeneration modifiers on top of the core's regen (same 5-tick cadence and conditions): Rattles/Witbane halve
     * stamina/magicka regen, stews speed up stamina regen, the Blessing of Akatosh speeds up magicka regen.
     */
    private static void adjustRegen(ServerPlayer player, PlayerData data, long now) {
        if (player.isCreative() || player.isSpectator() || !player.isAlive()) return;
        boolean combat = Vitals.inCombat(player);
        float stMul = 0f;
        if (has(player, SurvivalEffects.RATTLES)) stMul -= 0.5f;
        MobEffectInstance fortify = player.getEffect(com.nirncraft.platform.Effects.holder(SurvivalEffects.FORTIFY_STAMINA_REGEN.get()));
        if (fortify != null) stMul += 0.25f * (fortify.getAmplifier() + 1);
        if (stMul != 0f && now - data.getLastStaminaUseTick() > 20 && !player.isSprinting() && data.getStamina() < data.maxStamina()) {
            float pct = (float) (double) SkyConfig.STAMINA_REGEN_PERCENT.get() / 100f * (combat ? 0.5f : 1f);
            data.setStamina(data.getStamina() + data.maxStamina() * pct * 0.25f * stMul);
        }
        float mgMul = 0f;
        if (has(player, SurvivalEffects.WITBANE)) mgMul -= 0.5f;
        if (has(player, SurvivalEffects.BLESSINGS.get(Divine.AKATOSH))) mgMul += 0.1f;
        if (mgMul != 0f && now - data.getLastMagickaUseTick() > 20 && data.getMagicka() < data.maxMagicka()) {
            float pct = (float) (double) SkyConfig.MAGICKA_REGEN_PERCENT.get() / 100f * (combat ? 0.33f : 1f);
            data.setMagicka(data.getMagicka() + data.maxMagicka() * pct * 0.25f * mgMul);
        }
    }

    /**
     * Keeps this module's share of the core's {@code module("bonus")} health/magicka/stamina equal to what the
     * active blessings (+25) and diseases (-25) call for. Only the difference to what we applied before is added,
     * so other modules' bonuses in the same keys are left alone.
     */
    public static void reconcileBonuses(Player player) {
        if (player.level().isClientSide()) return;
        float[] want = new float[3];
        if (has(player, SurvivalEffects.BLESSINGS.get(Divine.ARKAY))) want[0] += 25;
        if (has(player, SurvivalEffects.GREENSPORE)) want[0] -= 25;
        if (has(player, SurvivalEffects.BLESSINGS.get(Divine.JULIANOS))) want[1] += 25;
        if (has(player, SurvivalEffects.BRAIN_ROT)) want[1] -= 25;
        if (has(player, SurvivalEffects.BLESSINGS.get(Divine.KYNARETH))) want[2] += 25;
        if (has(player, SurvivalEffects.BONE_BREAK_FEVER)) want[2] -= 25;

        PlayerData data = SkyData.get(player);
        CompoundTag mine = data.module("survival").getCompoundOrEmpty("applied_bonus");
        CompoundTag bonus = data.module("bonus");
        boolean changed = false;
        for (int i = 0; i < 3; i++) {
            float diff = want[i] - mine.getFloatOr(POOLS[i], 0f);
            if (diff == 0f) continue;
            bonus.putFloat(POOLS[i], bonus.getFloatOr(POOLS[i], 0f) + diff);
            mine.putFloat(POOLS[i], want[i]);
            changed = true;
        }
        if (!changed) return;
        data.module("survival").put("applied_bonus", mine);
        data.markDirty();
        data.markVitalsDirty();
        if (player instanceof ServerPlayer sp) Vitals.refreshAttributes(sp);
    }

    // ------------------------------------------------------------------ skooma

    public static void scheduleSkoomaCrash(Player player, int ticks) {
        PlayerData data = SkyData.get(player);
        data.module("survival").putLong("skooma_crash", player.level().getGameTime() + ticks);
        data.markDirty();
    }

    private static void checkSkooma(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module("survival");
        long crash = tag.getLongOr("skooma_crash",0L);
        if (crash <= 0 || player.level().getGameTime() < crash) return;
        tag.remove("skooma_crash");
        data.markDirty();
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WEAKNESS), 90 * 20, 0));
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 90 * 20, 0));
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NAUSEA), 8 * 20, 0, false, false, true));
        data.setStamina(data.getStamina() * 0.5f);
        Notifier.message(player, Component.translatable("message.nirncraft.survival.skooma_crash"));
    }

    // ------------------------------------------------------------------ cures

    @SubscribeEvent public static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (event.getEffect().value() instanceof SurvivalEffects.SurvivalEffect effect && effect.resistsMilk()
                && com.nirncraft.platform.ItemUse.consuming(event.getEntity()).is(net.minecraft.world.item.Items.MILK_BUCKET))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        MobEffect effect = event.getEffectInstance().getEffect().value();
        if (isCureDisease(effect)) {
            queueCure(entity);
        } else if (effect == ModEffects.WELL_RESTED.get() && entity instanceof ServerPlayer player) {
            Innkeepers.onSlept(player, event.getEffectInstance().getDuration());
        }
    }

    /** Drinking a potion with Cure Disease (instant effects never fire {@link MobEffectEvent.Added}). */
    @SubscribeEvent
    public static void onUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity().level().isClientSide()) return;
        if (hasCure(event.getItem())) queueCure(event.getEntity());
        if (event.getEntity() instanceof ServerPlayer player) {
            checkRawMeatInfection(player, event.getItem());
        }
    }

    private static boolean isRawMeat(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(net.minecraft.core.component.DataComponents.FOOD)) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        if (path.equals("beef") || path.equals("porkchop") || path.equals("chicken") ||
                path.equals("mutton") || path.equals("rabbit") || path.equals("rotten_flesh")) {
            return true;
        }
        return path.startsWith("raw_") && (path.endsWith("_meat") || path.contains("venison")
                || path.contains("snout") || path.contains("meat") || path.contains("flesh"));
    }

    private static void checkRawMeatInfection(ServerPlayer player, ItemStack item) {
        if (!SurvivalConfig.DISEASES.get() || player.isCreative() || player.isSpectator()) return;
        if (!isRawMeat(item)) return;
        double chance = 0.35 * (1.0 - Diseases.resistance(player));
        if (player.getRandom().nextDouble() >= chance) return;
        List<java.util.function.Supplier<MobEffect>> diseases = List.of(
                SurvivalEffects.COLLYWOBBLES, SurvivalEffects.GREENSPORE,
                SurvivalEffects.SWAMP_ROT, SurvivalEffects.ATAXIA, SurvivalEffects.RATTLES
        );
        MobEffect chosen = diseases.get(player.getRandom().nextInt(diseases.size())).get();
        Diseases.infect(player, chosen);
    }

    @SubscribeEvent
    public static void onPotionImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractThrownPotion potion) || potion.level().isClientSide()) return;
        if (!hasCure(potion.getItem())) return;
        for (Player p : potion.level().getEntitiesOfClass(Player.class, potion.getBoundingBox().inflate(4.0, 2.0, 4.0))) queueCure(p);
    }

    private static boolean hasCure(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (MobEffectInstance inst : com.nirncraft.platform.ArcaneData.effects(stack)) {
            if (isCureDisease(inst.getEffect().value())) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // death clears effects: drop the blessing/disease share of the pools right away
        reconcileBonuses(event.getEntity());
    }

    // ------------------------------------------------------------------ combat & healing

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        DamageSource source = event.getSource();
        Entity attacker = source.getEntity();
        // Rockjoint: melee damage -25%
        if (attacker instanceof Player p && source.getDirectEntity() == p && has(p, SurvivalEffects.ROCKJOINT)) {
            event.setAmount(event.getAmount() * 0.75f);
        }
        if (!(victim instanceof Player player)) return;
        // Blessing of Stendarr: blocking is 10% more effective
        if (player.isBlocking() && has(player, SurvivalEffects.BLESSINGS.get(Divine.STENDARR))) event.setAmount(event.getAmount() * 0.9f);
        // catching diseases from bites and claws
        if (event.getAmount() > 0 && player instanceof ServerPlayer sp && attacker instanceof LivingEntity carrier
                && !(attacker instanceof Player) && source.getDirectEntity() == attacker) {
            maybeInfect(sp, carrier);
        }
    }

    private static void maybeInfect(ServerPlayer player, LivingEntity carrier) {
        if (!SurvivalConfig.DISEASES.get() || player.isCreative() || player.isSpectator()) return;
        List<java.util.function.Supplier<MobEffect>> diseases = Diseases.carriedBy(carrier);
        if (diseases.isEmpty()) return;
        double chance = SurvivalConfig.DISEASE_CHANCE.get() * (1.0 - Diseases.resistance(player));
        if (player.getRandom().nextDouble() >= chance) return;
        MobEffect disease = diseases.get(player.getRandom().nextInt(diseases.size())).get();
        Diseases.infect(player, disease);
    }

    @SubscribeEvent
    public static void onHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        float amount = event.getAmount();
        if (has(player, SurvivalEffects.SWAMP_ROT)) amount *= 0.75f;
        if (has(player, SurvivalEffects.BLESSINGS.get(Divine.MARA))) amount *= 1.1f;
        if (amount != event.getAmount()) event.setAmount(amount);
    }
}
