package com.skycraft.survival.item;

import net.minecraft.world.food.FoodProperties;

/**
 * What a Skyrim food or drink does. Nutrition is small on purpose: with the core's Skyrim hunger the food bar is
 * fixed and eating heals {@code 0.75 * nutrition} and restores {@code 4 * nutrition + 10 * saturation} stamina;
 * the over-time effects below come on top.
 *
 * @param healTicks        ticks of vanilla Regeneration I (health over time)
 * @param staminaTicks     ticks of {@code skycraft:regenerate_stamina_food}
 * @param fortifyLevel     level of {@code skycraft:fortify_stamina_regen} (+25% stamina regeneration per level)
 * @param fortifyTicks     ticks of {@code skycraft:fortify_stamina_regen} (stews: 12 minutes)
 * @param instantStamina   stamina restored at once (drinks)
 * @param alcohol          ticks of drunkenness (brief nausea)
 * @param skooma           speed rush followed by a crash
 */
public record FoodSpec(int nutrition, float saturation, boolean drink, boolean fast, int healTicks, int staminaTicks,
                       int fortifyLevel, int fortifyTicks, float instantStamina, int alcohol, boolean skooma) {

    public static final int TWELVE_MINUTES = 12 * 60 * 20;

    public static FoodSpec plain(int nutrition, float saturation) {
        return new FoodSpec(nutrition, saturation, false, false, 0, 0, 0, 0, 0, 0, false);
    }

    /** Raw produce and snacks: eaten quickly. */
    public static FoodSpec snack(int nutrition, float saturation) {
        return new FoodSpec(nutrition, saturation, false, true, 0, 0, 0, 0, 0, 0, false);
    }

    /** Cooked dishes: some health and stamina over time. */
    public static FoodSpec cooked(int nutrition, float saturation, int healSeconds, int staminaSeconds) {
        return new FoodSpec(nutrition, saturation, false, false, healSeconds * 20, staminaSeconds * 20, 0, 0, 0, 0, false);
    }

    /** Stews and soups: health and stamina over time plus 12 minutes of faster stamina regeneration. */
    public static FoodSpec stew(int nutrition, float saturation, int healSeconds, int staminaSeconds, int fortifyLevel) {
        return new FoodSpec(nutrition, saturation, false, false, healSeconds * 20, staminaSeconds * 20, fortifyLevel,
                TWELVE_MINUTES, 0, 0, false);
    }

    /** Ale, mead and wine: stamina now, some over time, and a little drunkenness. */
    public static FoodSpec alcohol(float stamina, int staminaSeconds, int drunkSeconds) {
        return new FoodSpec(1, 0.1f, true, false, 0, staminaSeconds * 20, 0, 0, stamina, drunkSeconds * 20, false);
    }

    public static FoodSpec skoomaSpec() {
        return new FoodSpec(1, 0f, true, true, 0, 0, 0, 0, 40f, 6 * 20, true);
    }

    public FoodProperties properties(boolean meat) {
        FoodProperties.Builder b = new FoodProperties.Builder().nutrition(nutrition).saturationModifier(saturation);
        if (drink) b.alwaysEdible();
        return b.build();
    }

    public boolean hasExtras() {
        return healTicks > 0 || staminaTicks > 0 || fortifyTicks > 0 || instantStamina > 0 || alcohol > 0 || skooma;
    }
}
