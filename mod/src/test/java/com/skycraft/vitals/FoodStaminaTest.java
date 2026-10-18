package com.skycraft.vitals;

import net.minecraft.world.food.FoodProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FoodStaminaTest {
    @Test void absoluteNativeSaturationDoesNotBuffLegacyStaminaRestoration() {
        assertEquals(26f, Vitals.foodStamina(new FoodProperties.Builder().nutrition(5).saturationModifier(0.6f).build()), 0.00001);
        assertEquals(40f, Vitals.foodStamina(new FoodProperties.Builder().nutrition(8).saturationModifier(0.8f).build()), 0.00001);
        assertEquals(8f, Vitals.foodStamina(new FoodProperties.Builder().nutrition(2).saturationModifier(0).build()), 0.00001);
    }

    @Test void ZeroNutritionCannotProduceNonFiniteStamina() {
        assertEquals(0f, Vitals.foodStamina(new FoodProperties.Builder().nutrition(0).saturationModifier(0.5f).build()));
    }
}
