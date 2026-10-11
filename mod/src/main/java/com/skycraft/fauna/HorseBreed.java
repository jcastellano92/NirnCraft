package com.skycraft.fauna;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Markings;
import net.minecraft.world.entity.animal.horse.Variant;

/**
 * Breeds of Tamriel's horses with distinctive speed, health, jump, coat and taming difficulty. Skyrim's hold breeds,
 * Cyrodiil's Imperial horses, High Rock's destriers, Hammerfell's Alik'r desert horses, Summerset's white horses, and
 * the two legends: Shadowmere and Arvak (a skeletal horse, see {@link #SKELETAL}). The mount system
 * ({@code com.skycraft.mounts.MountKind}) wraps every breed with stamina, saddlebags and where it is sold.
 */
public enum HorseBreed {
    WHITERUN_BAY("whiterun_bay", "Whiterun Bay", 1000, 26.0f, 0.28f, 0.7f, Variant.BROWN, Markings.NONE, 1),
    SOLITUDE_GREY("solitude_grey", "Solitude Dapple", 1200, 28.0f, 0.30f, 0.8f, Variant.GRAY, Markings.WHITE_DOTS, 2),
    WINDHELM_PINTO("windhelm_pinto", "Windhelm Paint", 1000, 32.0f, 0.26f, 0.65f, Variant.WHITE, Markings.BLACK_DOTS, 2),
    RIFTEN_CHESTNUT("riften_chestnut", "Riften Chestnut", 1100, 24.0f, 0.33f, 0.85f, Variant.CHESTNUT, Markings.WHITE, 3),
    MARKARTH_BLACK("markarth_black", "Markarth Mountain Steed", 1400, 36.0f, 0.27f, 0.95f, Variant.DARK_BROWN, Markings.NONE, 4),
    SHADOWMERE("shadowmere", "Shadowmere", 5000, 50.0f, 0.36f, 1.0f, Variant.BLACK, Markings.NONE, 5),
    // Cyrodiil: the Imperial horses of the Heartlands and the Colovian paints
    CYRODILIC_BAY("cyrodilic_bay", "Cyrodilic Bay", 1100, 30.0f, 0.30f, 0.75f, Variant.BROWN, Markings.WHITE, 2),
    COLOVIAN_PAINT("colovian_paint", "Colovian Paint", 1300, 28.0f, 0.31f, 0.8f, Variant.CHESTNUT, Markings.WHITE_FIELD, 2),
    NIBENESE_GREY("nibenese_grey", "Nibenese Grey", 1500, 26.0f, 0.33f, 0.8f, Variant.GRAY, Markings.WHITE, 3),
    // High Rock: heavy armoured warhorses
    BRETON_DESTRIER("breton_destrier", "Breton Destrier", 2600, 44.0f, 0.26f, 0.7f, Variant.DARK_BROWN, Markings.WHITE, 4),
    // Hammerfell: fast, light desert horses
    ALIKR_STEED("alikr_steed", "Alik'r Desert Steed", 1800, 24.0f, 0.35f, 0.9f, Variant.CREAMY, Markings.WHITE, 3),
    // Summerset: tall white horses of the Altmer
    AURIDON_WHITE("auridon_white", "Auridon White", 3200, 30.0f, 0.34f, 0.95f, Variant.WHITE, Markings.NONE, 3),
    // Arvak, the skeletal horse of the Soul Cairn (a skeleton horse; the coat fields are unused)
    ARVAK("arvak", "Arvak", 0, 40.0f, 0.35f, 0.95f, Variant.BLACK, Markings.NONE, 5);

    /** Breeds drawn as vanilla skeleton horses. */
    public static final HorseBreed SKELETAL = ARVAK;

    public final String id;
    public final String defaultName;
    public final int cost;
    public final float maxHealth;
    public final float movementSpeed;
    public final float jumpStrength;
    public final Variant variant;
    public final Markings markings;
    public final int difficulty; // 1 (easiest) to 5 (hardest) for taming balance minigame

    HorseBreed(String id, String defaultName, int cost, float maxHealth, float movementSpeed, float jumpStrength, Variant variant,
               Markings markings, int difficulty) {
        this.id = id;
        this.defaultName = defaultName;
        this.cost = cost;
        this.maxHealth = maxHealth;
        this.movementSpeed = movementSpeed;
        this.jumpStrength = jumpStrength;
        this.variant = variant;
        this.markings = markings;
        this.difficulty = difficulty;
    }

    public Component displayName() {
        return Component.translatable("fauna.skycraft.breed." + id);
    }

    public void apply(Horse horse) {
        applyStats(horse);
        horse.setVariant(variant);
        if (markings != Markings.NONE || horse.getMarkings() != Markings.NONE) {
            // coat markings have no public setter: round-trip the saved "Variant" field (fresh entities only)
            CompoundTag tag = new CompoundTag();
            horse.addAdditionalSaveData(tag);
            tag.putInt("Variant", variant.getId() & 255 | markings.getId() << 8 & 65280);
            horse.readAdditionalSaveData(tag);
        }
    }

    /** Health, speed, jump and the breed tag (any horse-like mount, skeletal ones included). */
    public void applyStats(AbstractHorse horse) {
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
