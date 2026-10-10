package com.skycraft.fauna;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Variant;

/**
 * Breeds of Skyrim horses with distinctive speed, stamina/health, jump, and taming difficulty.
 */
public enum HorseBreed {
    WHITERUN_BAY("whiterun_bay", "Whiterun Bay", 1000, 26.0f, 0.28f, 0.7f, Variant.BROWN, 1),
    SOLITUDE_GREY("solitude_grey", "Solitude Dapple", 1200, 28.0f, 0.30f, 0.8f, Variant.GRAY, 2),
    WINDHELM_PINTO("windhelm_pinto", "Windhelm Paint", 1000, 32.0f, 0.26f, 0.65f, Variant.WHITE, 2),
    RIFTEN_CHESTNUT("riften_chestnut", "Riften Chestnut", 1100, 24.0f, 0.33f, 0.85f, Variant.CHESTNUT, 3),
    MARKARTH_BLACK("markarth_black", "Markarth Mountain Steed", 1400, 36.0f, 0.27f, 0.95f, Variant.DARK_BROWN, 4),
    SHADOWMERE("shadowmere", "Shadowmere", 5000, 50.0f, 0.36f, 1.0f, Variant.BLACK, 5);

    public final String id;
    public final String defaultName;
    public final int cost;
    public final float maxHealth;
    public final float movementSpeed;
    public final float jumpStrength;
    public final Variant variant;
    public final int difficulty; // 1 (easiest) to 5 (hardest) for taming balance minigame

    HorseBreed(String id, String defaultName, int cost, float maxHealth, float movementSpeed, float jumpStrength, Variant variant, int difficulty) {
        this.id = id;
        this.defaultName = defaultName;
        this.cost = cost;
        this.maxHealth = maxHealth;
        this.movementSpeed = movementSpeed;
        this.jumpStrength = jumpStrength;
        this.variant = variant;
        this.difficulty = difficulty;
    }

    public Component displayName() {
        return Component.translatable("fauna.skycraft.breed." + id);
    }

    public void apply(Horse horse) {
        if (horse.getAttribute(Attributes.MAX_HEALTH) != null) {
            horse.getAttribute(Attributes.MAX_HEALTH).setBaseValue(maxHealth);
            horse.setHealth(maxHealth);
        }
        if (horse.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(movementSpeed);
        }
        if (horse.getAttribute(Attributes.JUMP_STRENGTH) != null) {
            horse.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(jumpStrength);
        }
        horse.setVariant(variant);
        horse.getPersistentData().putString("skycraft_breed", id);
    }

    public static HorseBreed byId(String id) {
        for (HorseBreed b : values()) {
            if (b.id.equals(id)) return b;
        }
        return WHITERUN_BAY;
    }

    public static HorseBreed breedForHold(String hold) {
        if (hold == null) return WHITERUN_BAY;
        return switch (com.skycraft.core.Holds.base(hold)) {
            case "haafingar" -> SOLITUDE_GREY;
            case "eastmarch" -> WINDHELM_PINTO;
            case "the_rift" -> RIFTEN_CHESTNUT;
            case "the_reach" -> MARKARTH_BLACK;
            default -> WHITERUN_BAY;
        };
    }
}

