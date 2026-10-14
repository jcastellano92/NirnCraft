package com.skycraft.mounts;

import com.skycraft.fauna.HorseBreed;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every kind of mount of Nirn: what entity carries it, its stamina, gallop, saddlebags and price, where it is sold and
 * whether it can be tamed in the wild. Ids are stored in player data ({@code module("mounts")}) and on the mount
 * ({@link Mounts#KIND}); horse kinds share their {@link HorseBreed} id.
 *
 * <ul>
 *     <li>Skyrim: the hold breeds; Shadowmere (quest reward, flag {@code mount_shadowmere}) and Arvak (Soul Cairn hook,
 *     flag {@code mount_arvak} or Arvak's Skull).</li>
 *     <li>Cyrodiil: Imperial bays, Colovian paints and Nibenese greys. High Rock: Breton destriers, sold barded.</li>
 *     <li>Morrowind: the pack guar. Elsweyr: senche-tigers. Valenwood: giant wolves. Summerset: indriks and Auridon
 *     whites. Hammerfell: camels and Alik'r steeds. Black Marsh: none (marsh-shoes and boats instead).</li>
 *     <li>Atmora: woolly mammoths (slow, strong, two seats). Akavir: tiger mounts. The endless lands: tamed realm
 *     grazers.</li>
 * </ul>
 */
public enum MountKind {
    // ---------------------------------------------------------------- horses (vanilla horse entity)
    // (stamina is in gallop ticks, see MountProfile: 700 is 35 s of gallop where a runner manages about 14 s)
    WHITERUN_BAY(Backing.HORSE, HorseBreed.WHITERUN_BAY, 700, 0.45f, 1),
    SOLITUDE_GREY(Backing.HORSE, HorseBreed.SOLITUDE_GREY, 720, 0.45f, 1),
    WINDHELM_PINTO(Backing.HORSE, HorseBreed.WINDHELM_PINTO, 800, 0.40f, 2),
    RIFTEN_CHESTNUT(Backing.HORSE, HorseBreed.RIFTEN_CHESTNUT, 680, 0.50f, 1),
    MARKARTH_BLACK(Backing.HORSE, HorseBreed.MARKARTH_BLACK, 840, 0.40f, 2),
    SHADOWMERE(Backing.HORSE, HorseBreed.SHADOWMERE, 1000, 0.55f, 2),
    CYRODILIC_BAY(Backing.HORSE, HorseBreed.CYRODILIC_BAY, 720, 0.45f, 2),
    COLOVIAN_PAINT(Backing.HORSE, HorseBreed.COLOVIAN_PAINT, 760, 0.48f, 1),
    NIBENESE_GREY(Backing.HORSE, HorseBreed.NIBENESE_GREY, 640, 0.52f, 1),
    BRETON_DESTRIER(Backing.HORSE, HorseBreed.BRETON_DESTRIER, 600, 0.38f, 2),
    ALIKR_STEED(Backing.HORSE, HorseBreed.ALIKR_STEED, 1000, 0.50f, 1),
    AURIDON_WHITE(Backing.HORSE, HorseBreed.AURIDON_WHITE, 700, 0.50f, 1),
    ARVAK(Backing.SKELETON, HorseBreed.ARVAK, 1400, 0.55f, 1),
    // ---------------------------------------------------------------- other beasts
    /** Hammerfell's dromedary (vanilla camel): two seats, slow but tireless, its own dash. */
    CAMEL(Backing.CAMEL, null, 1500, 1300, 0.25f, 3, 2, 0),
    /** Morrowind's pack guar (the bestiary's guar): the biggest saddlebags. */
    GUAR(Backing.GUAR, null, 900, 1000, 0.30f, 3, 1, 1),
    SENCHE(Backing.BEAST, null, 3500, 650, 0.50f, 2, 1, 4),
    GIANT_WOLF(Backing.BEAST, null, 2400, 900, 0.50f, 1, 1, 0),
    INDRIK(Backing.BEAST, null, 9000, 800, 0.55f, 1, 1, 3),
    MAMMOTH(Backing.BEAST, null, 7000, 1000, 0.20f, 3, 2, 5),
    AKAVIRI_TIGER(Backing.BEAST, null, 5000, 700, 0.50f, 2, 1, 0),
    REALM_GRAZER(Backing.BEAST, null, 1200, 900, 0.35f, 2, 1, 2);

    public enum Backing {
        /** A vanilla horse with a {@link HorseBreed}. */
        HORSE,
        /** A vanilla skeleton horse (Arvak). */
        SKELETON,
        /** A vanilla camel. */
        CAMEL,
        /** The bestiary's guar (pack variant). */
        GUAR,
        /** A {@link MountBeast} of {@link MountEntities}. */
        BEAST
    }

    public static final MountKind[] VALUES = values();

    public final String id;
    public final Backing backing;
    @Nullable
    public final HorseBreed breed;
    public final int cost;
    /** Stamina points: a gallop drains 1 per tick, resting regains max/240 a tick (half that while moving). */
    public final int stamina;
    /** Movement speed bonus while galloping (multiplied). */
    public final float gallop;
    /** Saddlebag rows of 9 slots. */
    public final int bagRows;
    public final int seats;
    /** Wild taming difficulty 1..5; 0 = can't be tamed (bought, granted or summoned only). */
    public final int tameDifficulty;

    MountKind(Backing backing, HorseBreed breed, int stamina, float gallop, int bagRows) {
        this(backing, breed, breed.cost, stamina, gallop, bagRows, 1, breed == HorseBreed.SHADOWMERE || breed == HorseBreed.ARVAK ? 0 : breed.difficulty);
    }

    MountKind(Backing backing, @Nullable HorseBreed breed, int cost, int stamina, float gallop, int bagRows, int seats, int tameDifficulty) {
        this.id = breed != null ? breed.id : name().toLowerCase(Locale.ROOT);
        this.backing = backing;
        this.breed = breed;
        this.cost = cost;
        this.stamina = stamina;
        this.gallop = gallop;
        this.bagRows = bagRows;
        this.seats = seats;
        this.tameDifficulty = tameDifficulty;
    }

    public Component displayName() {
        return breed != null ? breed.displayName() : Component.translatable("mounts.skycraft.kind." + id);
    }

    /** Legends are never sold: Shadowmere and Arvak. */
    public boolean unique() {
        return this == SHADOWMERE || this == ARVAK;
    }

    public boolean horseLike() {
        return backing == Backing.HORSE || backing == Backing.SKELETON || backing == Backing.CAMEL;
    }

    /** Takes horse barding (vanilla horses only) or beast barding (the beasts). */
    public boolean bardable() {
        return backing == Backing.HORSE || backing == Backing.BEAST;
    }

    /** How this kind helps its owner in a fight and what it does when it would die ({@link MountCompanion}). */
    public MountCompanion.Profile combat() {
        return MountCompanion.profile(this);
    }

    public int bagSlots() {
        return bagRows * 9;
    }

    @Nullable
    public static MountKind byId(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        for (MountKind k : VALUES) if (k.id.equals(id)) return k;
        return null;
    }

    @Nullable
    public static MountKind forBreed(HorseBreed breed) {
        for (MountKind k : VALUES) if (k.breed == breed) return k;
        return null;
    }

    /**
     * The mounts the stables of a region sell, by province (Skyrim's holds each add their own breed). Empty in Black
     * Marsh, whose stablemasters sell marsh-shoes instead.
     */
    public static List<MountKind> forSale(String regionId, String province) {
        List<MountKind> out = new ArrayList<>();
        switch (province) {
            case "skyrim" -> {
                MountKind hold = forBreed(HorseBreed.breedForHold(regionId));
                if (hold != null && hold != WHITERUN_BAY) out.add(hold);
                out.add(WHITERUN_BAY);
                out.add(CYRODILIC_BAY);
            }
            case "cyrodiil" -> {
                out.add(CYRODILIC_BAY);
                out.add(COLOVIAN_PAINT);
                out.add(NIBENESE_GREY);
            }
            case "morrowind" -> {
                out.add(GUAR);
                if (regionId.endsWith("solstheim") || regionId.endsWith("stonefalls")) out.add(WHITERUN_BAY);
            }
            case "elsweyr" -> {
                out.add(SENCHE);
                out.add(ALIKR_STEED);
            }
            case "valenwood" -> out.add(GIANT_WOLF);
            case "summerset" -> {
                out.add(AURIDON_WHITE);
                out.add(INDRIK);
            }
            case "hammerfell", "yokuda" -> {
                out.add(CAMEL);
                out.add(ALIKR_STEED);
            }
            case "high_rock" -> {
                out.add(BRETON_DESTRIER);
                if (regionId.endsWith("wrothgar")) out.add(MARKARTH_BLACK);
                else out.add(CYRODILIC_BAY);
            }
            case "atmora" -> out.add(MAMMOTH);
            case "akavir" -> out.add(AKAVIRI_TIGER);
            case "realm" -> out.add(REALM_GRAZER);
            default -> {
            }
        }
        return out;
    }
}
