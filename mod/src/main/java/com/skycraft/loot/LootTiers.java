package com.skycraft.loot;

import com.skycraft.crafting.SkyArmorMaterial;
import com.skycraft.crafting.SmithingTier;
import com.skycraft.crafting.item.SkyArmorItem;
import com.skycraft.crafting.item.SkyAxeItem;
import com.skycraft.crafting.item.SkyBowItem;
import com.skycraft.crafting.item.SkyMeleeItem;
import com.skycraft.crafting.item.SkyShieldItem;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Skyrim's leveled-list ladders: the character level from which each material drops ("unlock") and how a level picks a
 * material. Nothing above the loot level ever drops; the newest unlocked rung is the most likely, older rungs fade:
 *
 * <pre>
 *  newest rung 60%, the rung before 30%, everything older 10% (split by weight)
 *
 *  unlock  weapons                       | heavy armour                   | light armour
 *  1       Iron                          | Iron (Banded Iron 3)           | Hide, Fur
 *  6       Steel, Silver                 | Steel                          | Leather, Studded
 *  12      Orcish                        | Orcish                         | Elven
 *  18      Dwarven                       | Dwarven                        | Scaled
 *  22      Elven                         | Steel Plate                    | Elven Gilded
 *  27      Glass, Nordic                 | Nordic                         | Glass
 *  32      Ebony                         | Ebony                          | (Glass)
 *  40      Stalhrim                      | Stalhrim                       | Stalhrim
 *  46      Daedric                       | Daedric                        | (Glass, Stalhrim)
 *  50      Dragonbone                    | Dragonplate                    | Dragonscale
 * </pre>
 *
 * Every other material (the provinces' gear, Falmer, Ancient Nord...) keeps the leveled-list level of the gear
 * catalogue ({@link SmithingTier#minLevel}, {@link SkyArmorMaterial#minLevel}).
 */
public final class LootTiers {
    private LootTiers() {}

    private static final Map<SmithingTier, Integer> WEAPON_UNLOCK = new EnumMap<>(SmithingTier.class);
    private static final Map<SkyArmorMaterial, Integer> ARMOR_UNLOCK = new EnumMap<>(SkyArmorMaterial.class);

    /** One rung entry: a material, its unlock level and its share inside the rung. */
    private record Rung<T>(T material, int unlock, float weight) {}

    private static final List<Rung<SmithingTier>> WEAPONS = new ArrayList<>();
    private static final List<Rung<SkyArmorMaterial>> HEAVY = new ArrayList<>();
    private static final List<Rung<SkyArmorMaterial>> LIGHT = new ArrayList<>();

    static {
        weapon(SmithingTier.IRON, 1, 1f);
        weapon(SmithingTier.STEEL, 6, 1f);
        weapon(SmithingTier.SILVER, 6, 0.25f);
        weapon(SmithingTier.ORCISH, 12, 1f);
        weapon(SmithingTier.DWARVEN, 18, 1f);
        weapon(SmithingTier.ELVEN, 22, 1f);
        weapon(SmithingTier.GLASS, 27, 1f);
        weapon(SmithingTier.NORDIC, 27, 0.4f);
        weapon(SmithingTier.EBONY, 32, 1f);
        weapon(SmithingTier.STALHRIM, 40, 0.35f);
        weapon(SmithingTier.DAEDRIC, 46, 1f);
        weapon(SmithingTier.DRAGONBONE, 50, 0.6f);

        heavy(SkyArmorMaterial.IRON, 1, 1f);
        heavy(SkyArmorMaterial.BANDED_IRON, 3, 0.6f);
        heavy(SkyArmorMaterial.STEEL, 6, 1f);
        heavy(SkyArmorMaterial.ORCISH, 12, 1f);
        heavy(SkyArmorMaterial.DWARVEN, 18, 1f);
        heavy(SkyArmorMaterial.STEEL_PLATE, 22, 1f);
        heavy(SkyArmorMaterial.NORDIC, 27, 0.6f);
        heavy(SkyArmorMaterial.EBONY, 32, 1f);
        heavy(SkyArmorMaterial.STALHRIM_HEAVY, 40, 0.35f);
        heavy(SkyArmorMaterial.DAEDRIC, 46, 1f);
        heavy(SkyArmorMaterial.DRAGONPLATE, 50, 0.6f);

        light(SkyArmorMaterial.HIDE, 1, 1f);
        light(SkyArmorMaterial.FUR, 1, 0.7f);
        light(SkyArmorMaterial.LEATHER, 6, 1f);
        light(SkyArmorMaterial.STUDDED, 6, 0.6f);
        light(SkyArmorMaterial.ELVEN, 12, 1f);
        light(SkyArmorMaterial.SCALED, 18, 1f);
        light(SkyArmorMaterial.ELVEN_GILDED, 22, 0.7f);
        light(SkyArmorMaterial.GLASS, 27, 1f);
        light(SkyArmorMaterial.STALHRIM_LIGHT, 40, 0.35f);
        light(SkyArmorMaterial.DRAGONSCALE, 50, 0.6f);
    }

    private static void weapon(SmithingTier tier, int unlock, float weight) {
        WEAPON_UNLOCK.put(tier, unlock);
        WEAPONS.add(new Rung<>(tier, unlock, weight));
    }

    private static void heavy(SkyArmorMaterial mat, int unlock, float weight) {
        ARMOR_UNLOCK.put(mat, unlock);
        HEAVY.add(new Rung<>(mat, unlock, weight));
    }

    private static void light(SkyArmorMaterial mat, int unlock, float weight) {
        ARMOR_UNLOCK.put(mat, unlock);
        LIGHT.add(new Rung<>(mat, unlock, weight));
    }

    // ------------------------------------------------------------------ unlock levels

    public static int unlock(SmithingTier tier) {
        Integer u = WEAPON_UNLOCK.get(tier);
        return u != null ? u : Math.max(1, tier.minLevel);
    }

    public static int unlock(SkyArmorMaterial mat) {
        Integer u = ARMOR_UNLOCK.get(mat);
        return u != null ? u : Math.max(1, mat.minLevel);
    }

    /** Character level from which an item of the gear catalogue drops (1 for anything else). */
    public static int unlock(Item item) {
        if (item instanceof SkyArmorItem a) return unlock(a.set);
        if (item instanceof SkyShieldItem s) return unlock(s.set);
        if (item instanceof SkyMeleeItem m) return unlock(m.smithingTier);
        if (item instanceof SkyAxeItem a) return unlock(a.smithingTier);
        if (item instanceof SkyBowItem b) return unlock(b.smithingTier);
        if (item instanceof com.skycraft.arsenal.item.PolearmItem p) return unlock(p.smithingTier);
        return 1;
    }

    // ------------------------------------------------------------------ picks

    public static SmithingTier weapon(int level, RandomSource random) {
        SmithingTier t = pick(WEAPONS, level, random);
        return t == null ? SmithingTier.IRON : t;
    }

    public static SkyArmorMaterial armor(int level, boolean heavy, RandomSource random) {
        SkyArmorMaterial m = pick(heavy ? HEAVY : LIGHT, level, random);
        return m != null ? m : heavy ? SkyArmorMaterial.IRON : SkyArmorMaterial.HIDE;
    }

    /** Index (0 = newest) of the ladder rung a level's pick comes from: 60% newest, 30% the one before, 10% older. */
    public static int rollRung(RandomSource random) {
        float f = random.nextFloat();
        return f < 0.60f ? 0 : f < 0.90f ? 1 : 2;
    }

    private static <T> T pick(List<Rung<T>> ladder, int level, RandomSource random) {
        TreeSet<Integer> unlocks = new TreeSet<>();
        for (Rung<T> r : ladder) if (r.unlock <= level) unlocks.add(r.unlock);
        if (unlocks.isEmpty()) return null;
        List<Integer> rungs = new ArrayList<>(unlocks.descendingSet());
        int want = Math.min(rollRung(random), rungs.size() - 1);
        // rung 2 means "anything older than the two newest rungs"
        int lo = want < 2 ? rungs.get(want) : Integer.MIN_VALUE;
        int hi = rungs.get(want);
        List<Rung<T>> pool = new ArrayList<>();
        float total = 0;
        for (Rung<T> r : ladder) {
            if (r.unlock <= hi && r.unlock >= lo) {
                pool.add(r);
                total += r.weight;
            }
        }
        float x = random.nextFloat() * total;
        for (Rung<T> r : pool) {
            x -= r.weight;
            if (x < 0) return r.material;
        }
        return pool.get(pool.size() - 1).material;
    }
}
