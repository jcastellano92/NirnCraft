package com.skycraft.survival.mode;

import com.skycraft.SkyConfig;
import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.registry.ModEffects;
import com.skycraft.skills.Progression;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.survival.SurvivalEffects;
import com.skycraft.survival.SurvivalPackets;
import com.skycraft.survival.camping.BedrollBlock;
import com.skycraft.survival.item.SkyFoodItem;
import com.skycraft.vitals.Vitals;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skyrim's Survival Mode: three meters with Skyrim's stages and penalties.
 *
 * <ul>
 *     <li><b>Hunger</b> (0-240) grows with game time: Satisfied, Peckish, Hungry, Famished, Starving. It takes away
 *     maximum stamina and slows stamina regeneration. Eating brings it down (cooked food and stews far more than raw
 *     food; the core's Skyrim hunger keeps the vanilla food bar fixed).</li>
 *     <li><b>Fatigue</b> (0-240) grows while awake: Rested, Slightly Tired, Tired, Weary, Debilitated. It takes away
 *     maximum magicka, slows magicka regeneration and, when Weary, learning. Sleeping clears it: an owned bed or inn
 *     gives Well Rested, a bedroll or tent only Rested, sleeping at home with your spouse Lover's Comfort.</li>
 *     <li><b>Cold</b> (0-300) drifts toward the {@link Climate} target, slowed by the {@link Warmth} rating of what you
 *     wear and by Nord blood: Comfortable, Chilly, Very Cold, Freezing, Numb. It takes away maximum health, slows
 *     stamina regeneration, and when Numb your movement and attacks. Fires, forges and hot meals warm you; cold water
 *     is deadly.</li>
 * </ul>
 *
 * <p>Survival Mode also lowers carry weight, and optionally blocks map fast travel. It runs only when the
 * {@code survival_mode.enabled} config and the world's {@code skycraftSurvivalMode} game rule are both on, and never
 * for creative or spectator players. Meters live in {@code SkyData.module("survival_mode")}; the penalties go through
 * the core's {@code module("bonus")} pools (only the difference to what was applied before, like the shrines do).</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SurvivalMode {
    /** Per-world switch: {@code /gamerule skycraftSurvivalMode false}. */
    public static final GameRules.Key<GameRules.BooleanValue> RULE =
            GameRules.register("skycraftSurvivalMode", GameRules.Category.PLAYER, GameRules.BooleanValue.create(true));

    public static final String KEY = "survival_mode";
    public static final float MAX_HUNGER = 240f, MAX_FATIGUE = 240f, MAX_COLD = Climate.MAX;
    /** Foods that warm you (stews, soups and other hot dishes); every stew counts too. */
    public static final TagKey<Item> WARMING_FOOD = TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "warming_food"));
    public static final int WARMING_TICKS = 6000;

    private static final float[] HUNGER_STAMINA = {0f, 0.05f, 0.10f, 0.25f, 0.40f};
    private static final float[] HUNGER_REGEN = {1f, 1f, 0.9f, 0.75f, 0.5f};
    private static final float[] FATIGUE_MAGICKA = {0f, 0.05f, 0.10f, 0.25f, 0.50f};
    private static final float[] FATIGUE_REGEN = {1f, 1f, 0.9f, 0.75f, 0.5f};
    private static final float[] FATIGUE_XP = {1f, 1f, 1f, 0.9f, 0.75f};
    private static final float[] COLD_HEALTH = {0f, 0.05f, 0.15f, 0.30f, 0.45f};
    private static final float[] COLD_REGEN = {1f, 1f, 0.85f, 0.7f, 0.5f};

    private static final UUID SPEED_MOD = UUID.fromString("3b6c2f1e-8a4d-4c7b-9e15-0a1b2c3d4e51");
    private static final UUID ATTACK_MOD = UUID.fromString("3b6c2f1e-8a4d-4c7b-9e15-0a1b2c3d4e52");
    private static final String[] POOLS = {"health", "magicka", "stamina", "carry"};

    /** Last state sent to each player (only changes are sent). */
    private static final Map<UUID, Integer> SENT = new HashMap<>();
    /** Game time each player last woke from vanilla sleep, and where. */
    private static final Map<UUID, long[]> WOKE = new HashMap<>();

    /** Players in the rest system ({@code world/RestManager}): 1 lying or sitting, 2 inside a fast-forward. */
    private static final Map<UUID, Integer> RESTING = new HashMap<>();

    private SurvivalMode() {}

    /** The rest system's state of a player: 0 none, 1 lying or sitting (no fatigue), 2 fast-forward (fed by {@link #restStep}). */
    public static void setRestState(UUID player, int state) {
        if (state == 0) RESTING.remove(player);
        else RESTING.put(player, state);
    }

    /** Lowers the fatigue by {@code points} (the rest bar slowly refills while lying in a bed). Returns the new value. */
    public static float restRecover(ServerPlayer player, float points) {
        if (!active(player)) return 0f;
        CompoundTag t = tag(player);
        float v = Math.max(0f, t.getFloat("fatigue") - points);
        t.putFloat("fatigue", v);
        return v;
    }

    /** One hour (or {@code hours}) of the fast-forward passed: the meters only, no Rested effects (see {@link #restDone}). */
    public static void restStep(ServerPlayer player, int hours, boolean sleep) {
        if (!active(player) || hours < 1) return;
        advanceMeters(player, tag(player), hours, sleep);
    }

    /** The rest ended after at least an hour of sleep: Rested in a bedroll, Lover's Comfort at home with a spouse. */
    public static void restDone(ServerPlayer player, @Nullable BlockPos bed) {
        restEffects(player, bed);
        if (active(player)) update(player, 0, false);
    }

    /** Forces class loading (registers the game rule early). */
    public static void init() {
        Progression.registerXpModifier((player, skill) -> {
            // Well Rested and Lover's Comfort are the core's (skills/Progression); Rested is the bedroll's lesser one
            float m = 1f;
            if (player.hasEffect(SurvivalEffects.RESTED.get()) && !player.hasEffect(ModEffects.WELL_RESTED.get())) m *= 1.05f;
            if (active(player)) m *= FATIGUE_XP[fatigueStage(fatigue(player))];
            return m;
        });
    }

    // ------------------------------------------------------------------ public API

    /** Survival Mode is on in this world (config and game rule). */
    public static boolean enabled(Level level) {
        try {
            if (!SurvivalConfig.SURVIVAL_MODE.get()) return false;
        } catch (IllegalStateException e) {
            return false;
        }
        return level.getGameRules().getBoolean(RULE);
    }

    /** Survival Mode applies to this player now. */
    public static boolean active(Player player) {
        return !player.isCreative() && !player.isSpectator() && enabled(player.level());
    }

    public static float hunger(Player player) {
        return tag(player).getFloat("hunger");
    }

    public static float fatigue(Player player) {
        return tag(player).getFloat("fatigue");
    }

    public static float cold(Player player) {
        return tag(player).getFloat("cold");
    }

    /** Sets a meter ({@code hunger}, {@code fatigue} or {@code cold}) and re-applies the penalties now. */
    public static void set(ServerPlayer player, String meter, float value) {
        float max = "cold".equals(meter) ? MAX_COLD : MAX_HUNGER;
        tag(player).putFloat(meter, Math.max(0f, Math.min(max, value)));
        update(player, 0, false);
    }

    /** Lowers hunger, e.g. when eating outside the usual food path. */
    public static void feed(ServerPlayer player, float amount) {
        CompoundTag t = tag(player);
        t.putFloat("hunger", Math.max(0f, t.getFloat("hunger") - amount));
    }

    /** Warms the player at once by {@code amount} cold points. */
    public static void warm(ServerPlayer player, float amount) {
        CompoundTag t = tag(player);
        t.putFloat("cold", Math.max(0f, t.getFloat("cold") - amount));
    }

    /** Map fast travel is blocked (Skyrim's Survival Mode rule, off by default). */
    public static boolean blocksFastTravel(ServerPlayer player) {
        return active(player) && SurvivalConfig.BLOCK_FAST_TRAVEL.get();
    }

    public static int hungerStage(float v) {
        return v < 40 ? 0 : v < 80 ? 1 : v < 120 ? 2 : v < 180 ? 3 : 4;
    }

    public static int fatigueStage(float v) {
        return v < 40 ? 0 : v < 80 ? 1 : v < 120 ? 2 : v < 180 ? 3 : 4;
    }

    public static int coldStage(float v) {
        return v < Climate.CHILLY ? 0 : v < Climate.VERY_COLD ? 1 : v < Climate.FREEZING ? 2 : v < Climate.NUMB ? 3 : 4;
    }

    static CompoundTag tag(Player player) {
        return SkyData.get(player).module(KEY);
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (!player.isAlive()) return;
        long now = player.level().getGameTime();
        if (now % 5 == 0 && active(player)) drainRegen(player);
        if ((now + player.getId()) % 20 != 0) return;
        update(player, 20, true);
    }

    /** One step of the meters ({@code ticks} of real time) plus penalties and sync. */
    static void update(ServerPlayer player, int ticks, boolean advance) {
        PlayerData data = SkyData.get(player);
        CompoundTag t = data.module(KEY);
        boolean on = active(player);
        long clock = clock(player);
        if (!t.contains("t")) t.putLong("t", clock);
        // 2 = a fast-forward of the rest system is running: RestManager feeds the meters hour by hour (restStep)
        int restState = RESTING.getOrDefault(player.getUUID(), 0);
        if (on && advance && restState != 2) {
            long delta = clock - t.getLong("t");
            if (delta < 0 || delta > 24000L * 3) delta = ticks;
            long[] woke = WOKE.remove(player.getUUID());
            if (delta > 600 && woke != null && player.level().getGameTime() - woke[0] < 100) {
                // vanilla sleep through the night
                rested(player, (int) (delta / 1000), true, BlockPos.of(woke[1]), false);
            } else {
                float hours = delta / 1000f;
                t.putFloat("hunger", Math.min(MAX_HUNGER, t.getFloat("hunger") + hours * (float) (double) SurvivalConfig.HUNGER_PER_HOUR.get()));
                if (!player.isSleeping() && restState == 0) { // lying or sitting in the rest system does not tire you
                    t.putFloat("fatigue", Math.min(MAX_FATIGUE, t.getFloat("fatigue") + hours * (float) (double) SurvivalConfig.FATIGUE_PER_HOUR.get()));
                }
            }
            stepCold(player, t, Math.max(1, ticks), delta > 600 ? (int) Math.min(delta, 24000) : 0);
        } else if (!on) {
            t.putBoolean("heat", false);
        }
        if (restState != 2) t.putLong("t", clock);
        applyPenalties(player, data, t, on);
        if (on) notifyStages(player, t);
        sync(player, t, on);
    }

    /** The clock that drives hunger and fatigue: the day clock, or plain game time while the daylight cycle is off. */
    private static long clock(ServerPlayer player) {
        ServerLevel ow = player.server.overworld();
        return ow.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT) ? ow.getDayTime() : ow.getGameTime();
    }

    private static void stepCold(ServerPlayer player, CompoundTag t, int ticks, int skippedTicks) {
        Climate.Env env = Climate.sample(player);
        float cold = t.getFloat("cold");
        float target = env.target();
        if (player.hasEffect(SurvivalEffects.WARMING_MEAL.get())) target = Math.max(0f, target - 50f);
        int warmth = Warmth.of(player);
        float rate = (float) (double) SurvivalConfig.COLD_RATE.get();
        float seconds = ticks / 20f;
        if (skippedTicks > 0) {
            cold = target; // hours passed: the body settled to whatever the place feels like
        } else if (target > cold) {
            float warmthMul = Math.max(0.15f, 1f - warmth / 250f);
            Race race = SkyData.get(player).getRace();
            float raceMul = race == null ? 1f : 1f - 0.5f * race.resistance("frost");
            float rise = (0.8f + target / 150f) * warmthMul * raceMul * env.riseMul() * rate * seconds;
            cold = Math.min(target, cold + rise);
        } else {
            float fall = (env.heat() ? 8f : env.sheltered() ? 2.5f : 1.2f) * rate * seconds;
            cold = Math.max(target, cold - fall);
        }
        t.putFloat("cold", cold);
        t.putBoolean("heat", env.heat());
        t.putBoolean("wet", env.inWater());
        t.putShort("warmth", (short) warmth);
        t.putShort("target", (short) target);

        // numbing cold water kills
        if (env.inWater() && cold >= Climate.NUMB + 40 && SurvivalConfig.FREEZING_DAMAGE.get()
                && player.level().getGameTime() % 40 < 20) {
            player.hurt(player.damageSources().freeze(), 2.0f);
        }
    }

    // ------------------------------------------------------------------ penalties

    private static void applyPenalties(ServerPlayer player, PlayerData data, CompoundTag t, boolean on) {
        CompoundTag applied = t.getCompound("applied");
        float[] want = new float[4];
        int hs = hungerStage(t.getFloat("hunger"));
        int fs = fatigueStage(t.getFloat("fatigue"));
        int cs = coldStage(t.getFloat("cold"));
        if (on) {
            // the pools without our own share
            float baseHealth = data.maxHealth() - applied.getFloat("health");
            float baseMagicka = data.maxMagicka() - applied.getFloat("magicka");
            float baseStamina = data.maxStamina() - applied.getFloat("stamina");
            want[0] = -Math.round(baseHealth * COLD_HEALTH[cs]);
            want[1] = -Math.round(baseMagicka * FATIGUE_MAGICKA[fs]);
            want[2] = -Math.round(baseStamina * HUNGER_STAMINA[hs]);
            want[3] = -(float) (double) SurvivalConfig.CARRY_PENALTY.get();
        }
        CompoundTag bonus = data.module("bonus");
        boolean changed = false;
        for (int i = 0; i < POOLS.length; i++) {
            float diff = want[i] - applied.getFloat(POOLS[i]);
            if (Math.abs(diff) < 0.5f) continue;
            bonus.putFloat(POOLS[i], bonus.getFloat(POOLS[i]) + diff);
            applied.putFloat(POOLS[i], want[i]);
            changed = true;
        }
        if (changed) {
            t.put("applied", applied);
            data.markDirty();
            data.markVitalsDirty();
            Vitals.refreshAttributes(player);
        }
        modifier(player, Attributes.MOVEMENT_SPEED, SPEED_MOD, on ? (cs >= 4 ? -0.10 : cs >= 3 ? -0.05 : 0) : 0);
        modifier(player, Attributes.ATTACK_SPEED, ATTACK_MOD, on && cs >= 4 ? -0.10 : 0);
    }

    private static void modifier(ServerPlayer player, Attribute attribute, UUID id, double amount) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(id);
        if (existing != null && existing.getAmount() == amount) return;
        if (existing != null) inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, "Skycraft survival", amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** Hunger and cold slow stamina regeneration, fatigue magicka (same cadence and conditions as the core's regen). */
    private static void drainRegen(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        CompoundTag t = data.module(KEY);
        float st = HUNGER_REGEN[hungerStage(t.getFloat("hunger"))] * COLD_REGEN[coldStage(t.getFloat("cold"))];
        float mg = FATIGUE_REGEN[fatigueStage(t.getFloat("fatigue"))];
        long now = player.level().getGameTime();
        boolean combat = Vitals.inCombat(player);
        if (st < 1f && now - data.getLastStaminaUseTick() > 20 && !player.isSprinting() && data.getStamina() < data.maxStamina()) {
            float pct = (float) (double) SkyConfig.STAMINA_REGEN_PERCENT.get() / 100f * (combat ? 0.5f : 1f);
            data.setStamina(data.getStamina() - data.maxStamina() * pct * 0.25f * (1f - st));
        }
        if (mg < 1f && now - data.getLastMagickaUseTick() > 20 && data.getMagicka() < data.maxMagicka()) {
            float pct = (float) (double) SkyConfig.MAGICKA_REGEN_PERCENT.get() / 100f * (combat ? 0.33f : 1f);
            data.setMagicka(data.getMagicka() - data.maxMagicka() * pct * 0.25f * (1f - mg));
        }
    }

    // ------------------------------------------------------------------ messages & sync

    private static void notifyStages(ServerPlayer player, CompoundTag t) {
        int hs = hungerStage(t.getFloat("hunger"));
        int fs = fatigueStage(t.getFloat("fatigue"));
        int cs = coldStage(t.getFloat("cold"));
        int last = t.contains("stages") ? t.getInt("stages") : -1;
        int now = hs | fs << 4 | cs << 8;
        if (last == now) return;
        t.putInt("stages", now);
        if (last < 0 || !SurvivalConfig.STAGE_MESSAGES.get()) return;
        int lh = last & 15, lf = (last >> 4) & 15, lc = (last >> 8) & 15;
        if (hs != lh) stageMessage(player, "hunger", hs, hs > lh);
        if (fs != lf) stageMessage(player, "fatigue", fs, fs > lf);
        if (cs != lc) stageMessage(player, "cold", cs, cs > lc);
    }

    private static void stageMessage(ServerPlayer player, String meter, int stage, boolean worse) {
        String key = "survival.skycraft." + meter + ".msg." + stage;
        Notifier.message(player, Component.translatable(key).withStyle(worse && stage >= 3 ? ChatFormatting.RED : ChatFormatting.WHITE));
    }

    private static void sync(ServerPlayer player, CompoundTag t, boolean on) {
        int hunger = Math.round(t.getFloat("hunger"));
        int fatigue = Math.round(t.getFloat("fatigue"));
        int cold = Math.round(t.getFloat("cold"));
        int flags = (on ? SurvivalPackets.Meters.ACTIVE : 0)
                | (t.getBoolean("heat") ? SurvivalPackets.Meters.HEAT : 0)
                | (t.getBoolean("wet") ? SurvivalPackets.Meters.WET : 0)
                | (player.level().getGameTime() < t.getLong("well_fed") ? SurvivalPackets.Meters.WELL_FED : 0);
        int target = t.getShort("target");
        int trend = target > cold + 2 ? 1 : target < cold - 2 ? 2 : 0;
        int hash = ((hunger * 31 + fatigue) * 31 + cold) * 31 + t.getShort("warmth") + flags * 7919 + trend * 104729;
        Integer last = SENT.get(player.getUUID());
        if (last != null && last == hash && player.tickCount % 200 >= 20) return;
        SENT.put(player.getUUID(), hash);
        SkyNetwork.sendToPlayer(player, new SurvivalPackets.Meters((short) hunger, (short) fatigue, (short) cold,
                t.getShort("warmth"), (byte) flags, (byte) trend));
    }

    // ------------------------------------------------------------------ eating

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !active(player)) return;
        ItemStack stack = event.getItem();
        FoodProperties food = stack.getFoodProperties(player);
        if (food == null) return;
        CompoundTag t = tag(player);
        float relief = food.getNutrition() * 10f + food.getNutrition() * food.getSaturationModifier() * 10f;
        boolean cooked = com.skycraft.skills.Cookery.isMeal(stack)
                || stack.getItem() instanceof SkyFoodItem sky && (sky.spec().healTicks() > 0 || sky.spec().fortifyTicks() > 0);
        if (cooked) relief *= 1.25f;
        t.putFloat("hunger", Math.max(0f, t.getFloat("hunger") - relief));
        if (cooked) t.putLong("well_fed", player.level().getGameTime() + 6000);
        boolean hot = stack.is(WARMING_FOOD) || stack.getItem() instanceof SkyFoodItem sky && sky.spec().fortifyTicks() > 0;
        if (hot) {
            t.putFloat("cold", Math.max(0f, t.getFloat("cold") - 60f));
            player.addEffect(new MobEffectInstance(SurvivalEffects.WARMING_MEAL.get(), WARMING_TICKS, 0, false, true, true));
        }
        update(player, 0, false);
    }

    // ------------------------------------------------------------------ resting

    /**
     * The world's Wait/Sleep menu ({@code world/RestManager}) passed {@code hours}: hunger grows (slower asleep),
     * sleeping clears fatigue, waiting adds to it. Sleeping in a bedroll or tent gives Rested instead of Well
     * Rested; at home with your spouse it gives Lover's Comfort.
     */
    public static void onRest(ServerPlayer player, int hours, boolean sleep, @Nullable BlockPos bed) {
        rested(player, hours, sleep, bed, true);
    }

    private static void rested(ServerPlayer player, int hours, boolean sleep, @Nullable BlockPos bed, boolean fromMenu) {
        CompoundTag t = tag(player);
        boolean on = active(player);
        if (on) advanceMeters(player, t, hours, sleep);
        if (!sleep || hours < 1) {
            if (on) update(player, 0, false);
            return;
        }
        restEffects(player, bed);
        if (on) update(player, 0, false);
    }

    /** Hunger, fatigue and cold after {@code hours} of resting. */
    private static void advanceMeters(ServerPlayer player, CompoundTag t, int hours, boolean sleep) {
        float hungerRate = (float) (double) SurvivalConfig.HUNGER_PER_HOUR.get();
        float fatigueRate = (float) (double) SurvivalConfig.FATIGUE_PER_HOUR.get();
        t.putFloat("hunger", Math.min(MAX_HUNGER, t.getFloat("hunger") + hours * hungerRate * (sleep ? 0.6f : 1f)));
        if (sleep) {
            t.putFloat("fatigue", Math.max(0f, t.getFloat("fatigue") - hours * 30f));
        } else {
            t.putFloat("fatigue", Math.min(MAX_FATIGUE, t.getFloat("fatigue") + hours * fatigueRate));
        }
        stepCold(player, t, 20, hours * 1000);
        t.putLong("t", clock(player));
    }

    private static void restEffects(ServerPlayer player, @Nullable BlockPos bed) {
        boolean bedroll = bed != null && player.level().getBlockState(bed).getBlock() instanceof BedrollBlock;
        Entity spouse = bed == null || bedroll ? null : com.skycraft.homestead.Homesteads.spouseNear(player, bed, 48);
        int ticks = com.skycraft.world.WorldConfig.WELL_RESTED_TICKS.get();
        if (spouse != null) {
            player.removeEffect(ModEffects.WELL_RESTED.get());
            player.removeEffect(SurvivalEffects.RESTED.get());
            player.addEffect(new MobEffectInstance(ModEffects.LOVERS_COMFORT.get(), Math.max(ticks, 6000), 0, false, false, true));
            Notifier.message(player, Component.translatable("survival.skycraft.rest.lovers_comfort"));
        } else if (bedroll) {
            player.removeEffect(ModEffects.WELL_RESTED.get());
            player.addEffect(new MobEffectInstance(SurvivalEffects.RESTED.get(), Math.max(ticks, 6000), 0, false, false, true));
            Notifier.message(player, Component.translatable("survival.skycraft.rest.rested"));
        }
    }

    @SubscribeEvent
    public static void onWakeUp(PlayerWakeUpEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlockPos bed = player.getSleepingPos().orElse(player.blockPosition());
            WOKE.put(player.getUUID(), new long[]{player.level().getGameTime(), bed.asLong()});
        }
    }

    // ------------------------------------------------------------------ death & login

    /** A fresh start after death: warm, and no worse than Peckish and Slightly Tired. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) return;
        CompoundTag t = tag(player);
        t.putFloat("cold", 0f);
        t.putFloat("hunger", Math.min(t.getFloat("hunger"), 60f));
        t.putFloat("fatigue", Math.min(t.getFloat("fatigue"), 60f));
        t.remove("stages");
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SENT.remove(player.getUUID());
            // the new body has no attribute modifiers yet: re-apply the pools on the next update
            update(player, 0, false);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SENT.remove(player.getUUID());
            tag(player).remove("t");
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
        WOKE.remove(event.getEntity().getUUID());
    }

    // ------------------------------------------------------------------ tooltips

    /** Armor and clothing show their warmth (always: it is useful to know before a trip north). */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ArmorItem) && !Warmth.isCloak(stack)) return;
        int warmth = Warmth.tooltipValue(stack);
        if (warmth > 0) {
            event.getToolTip().add(Component.translatable("tooltip.skycraft.survival.warmth", warmth).withStyle(ChatFormatting.AQUA));
        }
    }

    /** Equipment slots that count for warmth. */
    public static final EquipmentSlot[] WARM_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
}
