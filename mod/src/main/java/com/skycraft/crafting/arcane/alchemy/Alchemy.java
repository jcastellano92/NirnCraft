package com.skycraft.crafting.arcane.alchemy;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.arcane.ArcaneModule;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.crafting.arcane.effect.ArcaneEffects;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skyrim alchemy: 2-3 different ingredients are combined at an Alchemy Lab; every effect shared by at least two of
 * them goes into the result. The most valuable effect decides whether it is a potion (drinkable) or a poison
 * (splash). Known effects are stored per ingredient in {@code data.module("arcane").getCompound("alchemy_known")}
 * (item id -> bitmask of known effect indices 0..3).
 *
 * <p>Calculation methods are shared by the client screen (preview) and the server (authoritative).</p>
 */
public final class Alchemy {
    public static final String KNOWN = "alchemy_known";
    public static final String VALUE_TAG = "skycraft_value";
    public static final String POISON_TAG = "skycraft_poison";
    public static final String ALCHEMY_TAG = "skycraft_alchemy";

    private Alchemy() {}

    // ------------------------------------------------------------------ knowledge

    public static int knownMask(Player player, Ingredients.Ingredient ing) {
        return SkyData.get(player).module(ArcaneModule.DATA).getCompound(KNOWN).getInt(ing.id());
    }

    public static boolean knows(Player player, Ingredients.Ingredient ing, int index) {
        return (knownMask(player, ing) & (1 << index)) != 0;
    }

    /** Marks effect indices as known; returns the mask of newly learned indices. */
    public static int learn(ServerPlayer player, Ingredients.Ingredient ing, int mask) {
        PlayerData data = SkyData.get(player);
        CompoundTag module = data.module(ArcaneModule.DATA);
        CompoundTag known = module.getCompound(KNOWN);
        int old = known.getInt(ing.id());
        int now = old | (mask & 0xF);
        if (now == old) return 0;
        known.putInt(ing.id(), now);
        module.put(KNOWN, known);
        data.markDirty();
        return now & ~old;
    }

    // ------------------------------------------------------------------ eating / tasting

    /**
     * Eating an ingredient applies its effects at this share of a brewed potion's strength: the duration is halved and
     * the amplifier comes from half of the strength bonus (instant effects keep their amplifier rules). Raw plants are
     * weaker than a brewed potion, and the skill/perk multipliers of {@link #strength} still count.
     */
    public static final float TASTE_POTENCY = 0.5f;

    /**
     * What one bite does: {@code index} is the effect (0..3) that is applied, {@code learned} the mask of effects
     * newly discovered by it (a subset of the player's unknown ones; the applied effect is among them when it was a
     * discovery).
     */
    public record Taste(int index, int learned) {
    }

    /**
     * The decision for one bite, pure so it can be tested. The candidates are the effects the player has already
     * discovered, plus (while any effect is still unknown) one "discover something new" outcome; one candidate is
     * picked uniformly. An unknown effect is therefore never applied without being learned, a player who knows nothing
     * about the ingredient always discovers one (random among the four), and the rest of the ingredient is reachable
     * by further bites at odds of 1/(known+1). With every effect known a random one of the four is applied. The
     * Experimenter perk reveals one more unknown effect per rank on a discovery (not applied).
     */
    public static Taste chooseTaste(int knownMask, int experimenterRank, RandomSource rng) {
        int known = knownMask & 0xF;
        int unknown = ~known & 0xF;
        int options = Integer.bitCount(known) + (unknown != 0 ? 1 : 0);
        int pick = rng.nextInt(options);
        if (pick < Integer.bitCount(known)) {
            // the pick-th discovered effect
            for (int i = 0; i < 4; i++) {
                if ((known & (1 << i)) != 0 && pick-- == 0) return new Taste(i, 0);
            }
        }
        int first = nthBit(unknown, rng.nextInt(Integer.bitCount(unknown)));
        int learned = 1 << first;
        unknown &= ~learned;
        for (int extra = 0; extra < experimenterRank && unknown != 0; extra++) {
            int bit = 1 << nthBit(unknown, rng.nextInt(Integer.bitCount(unknown)));
            learned |= bit;
            unknown &= ~bit;
        }
        return new Taste(first, learned);
    }

    private static int nthBit(int mask, int n) {
        for (int i = 0; i < 4; i++) {
            if ((mask & (1 << i)) != 0 && n-- == 0) return i;
        }
        throw new IllegalArgumentException("mask " + mask);
    }

    /** The result of {@link #discoverByTasting}: the bite, the effect instance applied (or null), and messages sent. */
    public record Tasted(Taste taste, MobEffectInstance applied, int messages) {
    }

    /**
     * Eating / tasting an ingredient (the inventory Use action, right-click, eating a food ingredient, the lab's Taste
     * button): picks one effect ({@link #chooseTaste}), learns what the bite taught (one "You learned" message per
     * newly learned effect), and applies the picked effect to the player.
     */
    public static Tasted discoverByTasting(ServerPlayer player, Ingredients.Ingredient ing) {
        Taste taste = chooseTaste(knownMask(player, ing), Perks.rank(player, "alchemy.experimenter"), player.getRandom());
        int learned = learn(player, ing, taste.learned());
        SkyData.get(player).addStat("ingredients_eaten", 1);
        MobEffectInstance applied = applyTaste(player, ing.effects()[taste.index()]);
        return new Tasted(taste, applied, announce(player, ing, learned));
    }

    /** The effect a bite of this effect gives the player: {@link #strength} at {@link #TASTE_POTENCY}. */
    public static MobEffectInstance tasteInstance(Player player, AlchemyEffect effect) {
        float s = strength(player, effect);
        int amp = effect.amplifier(1f + (s - 1f) * TASTE_POTENCY);
        int ticks = effect.instant() ? 1 : Math.max(20, Math.round(effect.durationTicks(s) * TASTE_POTENCY));
        return new MobEffectInstance(effect.mobEffect(), ticks, amp);
    }

    /**
     * Applies a bite's effect the way a drunk potion does (instant effects act at once, the rest are added). Tasting
     * never kills: Damage Health is skipped when it would take the last of the player's health. Returns the applied
     * instance or null.
     */
    public static MobEffectInstance applyTaste(ServerPlayer player, AlchemyEffect effect) {
        MobEffectInstance inst = tasteInstance(player, effect);
        MobEffect mob = inst.getEffect();
        if (mob.isInstantenous()) {
            if (effect == AlchemyEffect.DAMAGE_HEALTH && player.getHealth() <= 6f * (1 << inst.getAmplifier()) + 1f) return null;
            mob.applyInstantenousEffect(player, player, player, inst.getAmplifier(), 1.0);
        } else {
            player.addEffect(inst);
        }
        return inst;
    }

    /** One line per effect slot of the ingredient: name and power when discovered, "?" otherwise (always four). */
    public static List<Component> effectLines(Player player, Ingredients.Ingredient ing) {
        List<Component> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            AlchemyEffect effect = ing.effects()[i];
            if (!knows(player, ing, i)) {
                out.add(Component.literal("?").withStyle(ChatFormatting.DARK_GRAY));
                continue;
            }
            MobEffectInstance inst = tasteInstance(player, effect);
            MutableComponent line = effect.instant()
                    ? Component.translatable("tooltip.skycraft.alchemy.effect_instant", effect.displayName(), inst.getAmplifier() + 1)
                    : Component.translatable("tooltip.skycraft.alchemy.effect_timed", effect.displayName(), inst.getAmplifier() + 1,
                    inst.getDuration() / 20);
            out.add(line.withStyle(effect.positive ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        return out;
    }

    private static int announced;

    /** Total "You learned" messages sent since the game started (the self-test counts them to prove "exactly once"). */
    public static int announcedTotal() {
        return announced;
    }

    /** Sends "You learned: X (from Y)" for every newly learned effect; returns how many messages went out. */
    private static int announce(ServerPlayer player, Ingredients.Ingredient ing, int learned) {
        if (learned == 0) return 0;
        announced += Integer.bitCount(learned & 0xF);
        Component name = ing.item() == null ? Component.literal(ing.id()) : new ItemStack(ing.item()).getHoverName();
        int sent = 0;
        for (int i = 0; i < 4; i++) {
            if ((learned & (1 << i)) != 0) {
                Notifier.message(player, Component.translatable("message.skycraft.alchemy.learned_effect", name, ing.effects()[i].coloredName()));
                sent++;
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------ brewing math

    /** One effect of a brewed potion. */
    public record BrewEffect(AlchemyEffect effect, float strength) {
        public int amplifier() {
            return effect.amplifier(strength);
        }

        public int duration() {
            return effect.durationTicks(strength);
        }

        public float value() {
            return effect.value(strength);
        }

        public MobEffectInstance toInstance() {
            return new MobEffectInstance(effect.mobEffect(), duration(), amplifier());
        }
    }

    /** The result of combining ingredients. {@code effects} is sorted most valuable first. */
    public record Brew(List<BrewEffect> effects, boolean poison) {
        public boolean isEmpty() {
            return effects.isEmpty();
        }

        public int value() {
            float v = 0;
            for (BrewEffect e : effects) v += e.value();
            return Math.max(1, Math.round(v));
        }

        public AlchemyEffect main() {
            return effects.get(0).effect();
        }
    }

    /** Effects carried by at least two of the ingredients, in order of first appearance. */
    public static List<AlchemyEffect> sharedEffects(List<Ingredients.Ingredient> ings) {
        Map<AlchemyEffect, Integer> counts = new LinkedHashMap<>();
        for (Ingredients.Ingredient ing : ings) {
            for (AlchemyEffect e : ing.effects()) counts.merge(e, 1, Integer::sum);
        }
        List<AlchemyEffect> out = new ArrayList<>();
        counts.forEach((e, n) -> {
            if (n >= 2) out.add(e);
        });
        return out;
    }

    /**
     * Strength multiplier for an effect: (1 + skill/100 * 1.5) * (1 + 0.2 * Alchemist rank), * 1.25 for Physician
     * (restore effects), Benefactor (beneficial) and Poisoner (harmful), * (1 + 0.1 per Fortify Alchemy level).
     */
    public static float strength(Player player, AlchemyEffect effect) {
        PlayerData data = SkyData.get(player);
        float m = (1f + data.getSkill(Skill.ALCHEMY) / 100f * 1.5f) * (1f + 0.2f * Perks.rank(player, "alchemy.alchemist"));
        if (effect.restore && Perks.has(player, "alchemy.physician")) m *= 1.25f;
        if (effect.positive && Perks.has(player, "alchemy.benefactor")) m *= 1.25f;
        if (!effect.positive && Perks.has(player, "alchemy.poisoner")) m *= 1.25f;
        m *= 1f + 0.1f * ArcaneEffects.level(player, ArcaneEffects.FORTIFY_ALCHEMY);
        return m;
    }

    public static Brew brew(Player player, List<Ingredients.Ingredient> ings) {
        List<BrewEffect> list = new ArrayList<>();
        for (AlchemyEffect e : sharedEffects(ings)) list.add(new BrewEffect(e, strength(player, e)));
        if (list.isEmpty()) return new Brew(List.of(), false);
        list.sort(Comparator.comparingDouble((BrewEffect b) -> b.value()).reversed());
        boolean poison = !list.get(0).effect().positive;
        if (Perks.has(player, "alchemy.purity")) {
            list.removeIf(b -> b.effect().positive == poison);
        }
        return new Brew(List.copyOf(list), poison);
    }

    /** Builds the potion (or splash poison) item for a brew. */
    public static ItemStack createItem(Brew brew) {
        ItemStack stack = new ItemStack(brew.poison() ? Items.SPLASH_POTION : Items.POTION);
        List<MobEffectInstance> instances = new ArrayList<>();
        for (BrewEffect e : brew.effects()) instances.add(e.toInstance());
        PotionUtils.setCustomEffects(stack, instances);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt("CustomPotionColor", PotionUtils.getColor(instances));
        tag.putInt(VALUE_TAG, brew.value());
        tag.putBoolean(ALCHEMY_TAG, true);
        if (brew.poison()) tag.putBoolean(POISON_TAG, true);
        stack.setHoverName(Component.translatable(brew.poison() ? "item.skycraft.poison_of" : "item.skycraft.potion_of",
                brew.main().displayName()).withStyle(s -> s.withItalic(false)));
        return stack;
    }

    // ------------------------------------------------------------------ server actions

    /** Combines the ingredients in the given inventory slots (2 or 3 different ingredients). */
    public static void combine(ServerPlayer player, BlockPos pos, int[] slots) {
        if (!ArcaneModule.atStation(player, pos, ArcaneRegistry.ALCHEMY_LAB.get())) return;
        if (slots.length < 2 || slots.length > 3) return;
        Inventory inv = player.getInventory();
        List<Ingredients.Ingredient> ings = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int slot : slots) {
            if (slot < 0 || slot >= inv.getContainerSize() || !used.add(slot)) return;
            Ingredients.Ingredient ing = Ingredients.get(inv.getItem(slot));
            if (ing == null || !ids.add(ing.id())) return;
            ings.add(ing);
        }

        Brew brew = brew(player, ings);
        for (int slot : slots) inv.getItem(slot).shrink(1);
        inv.setChanged();

        if (brew.isEmpty()) {
            player.level().playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.2f);
            Notifier.message(player, Component.translatable("message.skycraft.alchemy.failed"));
            return;
        }

        // learn every effect that matched (even those removed by Purity)
        List<AlchemyEffect> shared = sharedEffects(ings);
        for (Ingredients.Ingredient ing : ings) {
            int mask = 0;
            for (AlchemyEffect e : shared) {
                int idx = ing.indexOf(e);
                if (idx >= 0) mask |= 1 << idx;
            }
            announce(player, ing, learn(player, ing, mask));
        }

        ItemStack result = createItem(brew);
        if (!inv.add(result)) player.drop(result, false);
        player.level().playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 1f, 1f);
        Progression.addSkillXp(player, Skill.ALCHEMY, brew.value());
        SkyData.get(player).addStat(brew.poison() ? "poisons_mixed" : "potions_mixed", 1);
        Notifier.message(player, Component.translatable("message.skycraft.alchemy.created", result.getHoverName()));
    }

    /** Eats one ingredient from a slot to learn its effects (for ingredients that aren't food). */
    public static void taste(ServerPlayer player, BlockPos pos, int slot) {
        if (!ArcaneModule.atStation(player, pos, ArcaneRegistry.ALCHEMY_LAB.get())) return;
        Inventory inv = player.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) return;
        ItemStack stack = inv.getItem(slot);
        Ingredients.Ingredient ing = Ingredients.get(stack);
        if (ing == null) return;
        if (knownMask(player, ing) == 0xF) {
            Notifier.message(player, Component.translatable("message.skycraft.alchemy.all_known"));
            return;
        }
        stack.shrink(1);
        inv.setChanged();
        player.level().playSound(null, player.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 0.8f, 1f);
        discoverByTasting(player, ing);
    }
}
