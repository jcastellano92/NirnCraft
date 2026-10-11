package com.skycraft.core;

import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.skycraft.core.Skill.*;

/**
 * Every playable people of Nirn: the ten races of Skyrim with their Skyrim starting skill bonuses, passives and
 * once-per-day greater power, plus the other folk of Tamriel, Akavir and the lost races of legend.
 *
 * <p>Each race has one +10 skill, five +5 skills and one or two +5 bonuses in the skills Skyrim doesn't have
 * (Polearms, Mysticism, Acrobatics, Riding, the life skills). Passives are read from here by the systems that apply
 * them (combat for resistances and claws, vitals for the health/magicka/stamina bonus, {@code vitals.RacePassives}
 * for water breathing, leaping, climbing and falling). {@link #scale} is the render scale of the body (hitboxes stay
 * vanilla) and {@link #variants} are cosmetic sub-peoples (Colovian/Nibenese, Wood Orcs, Cathay-raht, Naga...).</p>
 *
 * <p>Ordinals are not saved (races are stored by {@link #id()}); new races may go anywhere.</p>
 */
public enum Race {
    // ---------------------------------------------------------------- Tamriel (Skyrim's ten)
    NORD(Group.TAMRIEL, TWO_HANDED, of(ONE_HANDED, BLOCK, LIGHT_ARMOR, SMITHING, SPEECH), of(POLEARM),
            "battle_cry", 0, 0, 0, 1.04f, 1.0f, 0, 0),
    IMPERIAL(Group.TAMRIEL, RESTORATION, of(BLOCK, DESTRUCTION, ENCHANTING, HEAVY_ARMOR, ONE_HANDED), of(RIDING),
            "voice_of_the_emperor", 0, 0, 0, 1.0f, 1.0f, 900, 5900, "colovian", "nibenese"),
    BRETON(Group.TAMRIEL, CONJURATION, of(ALCHEMY, ALTERATION, ILLUSION, RESTORATION, SPEECH), of(MYSTICISM),
            "dragonskin", 0, 0, 0, 0.98f, 1.03f, -5300, 3300),
    REDGUARD(Group.TAMRIEL, ONE_HANDED, of(ARCHERY, ALTERATION, BLOCK, DESTRUCTION, SMITHING), of(RIDING),
            "adrenaline_rush", 0, 0, 0, 1.02f, 0.97f, -8200, 4900),
    ALTMER(Group.TAMRIEL, ILLUSION, of(ALTERATION, CONJURATION, DESTRUCTION, ENCHANTING, RESTORATION), of(MYSTICISM),
            "highborn", 50, 0, 0, 1.06f, 1.02f, -11200, 13000),
    BOSMER(Group.TAMRIEL, ARCHERY, of(ALCHEMY, LIGHT_ARMOR, LOCKPICKING, PICKPOCKET, SNEAK), of(ACROBATICS),
            "command_animal", 0, 0, 0, 0.92f, 1.06f, -4000, 11300),
    DUNMER(Group.TAMRIEL, DESTRUCTION, of(ALCHEMY, ALTERATION, ILLUSION, LIGHT_ARMOR, SNEAK), of(MYSTICISM),
            "ancestors_wrath", 0, 0, 0, 1.0f, 0.98f, 6300, 3300),
    ORSIMER(Group.TAMRIEL, HEAVY_ARMOR, of(BLOCK, ENCHANTING, ONE_HANDED, SMITHING, TWO_HANDED), of(MINING),
            "berserker_rage", 0, 0, 0, 1.04f, 0.9f, -4300, 300, "stronghold", "wood_orc"),
    KHAJIIT(Group.TAMRIEL, SNEAK, of(ALCHEMY, ARCHERY, LOCKPICKING, ONE_HANDED, PICKPOCKET), of(ACROBATICS),
            "night_eye", 0, 0, 0, 1.0f, 1.08f, 1200, 12600, "suthay_raht", "cathay_raht"),
    ARGONIAN(Group.TAMRIEL, LOCKPICKING, of(ALCHEMY, LIGHT_ARMOR, PICKPOCKET, RESTORATION, SNEAK), of(ATHLETICS),
            "histskin", 0, 0, 0, 1.0f, 1.0f, 6400, 6300, "saxhleel", "naga", "agaceph"),
    // ---------------------------------------------------------------- the other folk of Tamriel
    /** Sea Elves of Pyandonea: storm-callers and corsairs with chameleon skin. */
    MAORMER(Group.TAMRIEL, DESTRUCTION, of(ILLUSION, ALTERATION, POLEARM, ARCHERY, LIGHT_ARMOR), of(FISHING),
            "chameleon_skin", 25, 0, 0, 1.03f, 1.0f, -19300, 20600),
    /** The Reachfolk: Breton blood, old gods, hagraven pacts and briarhearts. */
    REACHMAN(Group.TAMRIEL, ARCHERY, of(ALCHEMY, CONJURATION, DESTRUCTION, LIGHT_ARMOR, SNEAK), of(HUNTING),
            "briarheart", 0, 0, 25, 1.0f, 0.95f, -2750, -1050),
    /** Near-human Khajiit of the Pellitine, often taken for Bosmer. */
    OHMES_RAHT(Group.TAMRIEL, SPEECH, of(ILLUSION, PICKPOCKET, LOCKPICKING, SNEAK, ALCHEMY), of(ACROBATICS),
            "moon_shadow", 0, 0, 0, 0.96f, 1.08f, 2800, 13700),
    /** The fox-folk of Black Marsh, quick and full of tricks. */
    LILMOTHIIT(Group.TAMRIEL, ILLUSION, of(SNEAK, ACROBATICS, PICKPOCKET, ARCHERY, LIGHT_ARMOR), of(SPEECH),
            "foxfire_ruse", 0, 0, 15, 0.9f, 1.18f, 7700, 10000),
    /** The great apes of Valenwood, who keep the manners of Summerset's nobility. */
    IMGA(Group.TAMRIEL, UNARMED, of(TWO_HANDED, SPEECH, BLOCK, HEAVY_ARMOR, ATHLETICS), of(ACROBATICS),
            "noble_challenge", 0, 30, 0, 1.08f, 0.82f, -5600, 10200),
    // ---------------------------------------------------------------- Akavir
    /** The serpent-folk who rule Akavir. */
    TSAESCI(Group.AKAVIR, ONE_HANDED, of(ARCHERY, DESTRUCTION, ILLUSION, SNEAK, ALCHEMY), of(POLEARM),
            "serpent_gaze", 0, 0, 0, 1.12f, 0.92f, 16200, 4200),
    /** The tiger-folk of the Ka Po' Tun steppe. */
    KA_PO_TUN(Group.AKAVIR, TWO_HANDED, of(HEAVY_ARMOR, ONE_HANDED, UNARMED, BLOCK, SMITHING), of(POLEARM),
            "tiger_roar", 0, 40, 0, 1.12f, 0.78f, 18600, 2600),
    /** The monkey-folk of the Tang Mo isles: brave, simple and very agile. */
    TANG_MO(Group.AKAVIR, ACROBATICS, of(SNEAK, ARCHERY, ONE_HANDED, LIGHT_ARMOR, PICKPOCKET), of(ATHLETICS),
            "thousand_hands", 0, 0, 25, 0.88f, 1.22f, 22200, 5200),
    // ---------------------------------------------------------------- lost races
    /** The Nords' forefathers from frozen Atmora, whose Voice came easier than breath. */
    ATMORAN(Group.LOST, TWO_HANDED, of(ONE_HANDED, HEAVY_ARMOR, BLOCK, SMITHING, SPEECH), of(ATHLETICS),
            "voice_of_atmora", 0, 0, 0, 1.06f, 0.94f, 0, -11000),
    /** The Snow Elves before their fall, priests of the sun under Skyrim's mountains. */
    FALMER(Group.LOST, RESTORATION, of(DESTRUCTION, ILLUSION, ALTERATION, ARCHERY, ENCHANTING), of(MYSTICISM),
            "auriels_dawn", 25, 0, 0, 1.04f, 1.05f, -600, -1700),
    /** The Deep Elves, tonal architects who vanished from Nirn in an instant. */
    DWEMER(Group.LOST, SMITHING, of(ENCHANTING, ALTERATION, HEAVY_ARMOR, LOCKPICKING, ONE_HANDED), of(MINING, MYSTICISM),
            "tonal_resonance", 0, 0, 0, 0.96f, 0.92f, 7100, -400),
    /** The Heartland High Elves who raised the White-Gold Tower. */
    AYLEID(Group.LOST, DESTRUCTION, of(ALTERATION, CONJURATION, ENCHANTING, ILLUSION, RESTORATION), of(MYSTICISM),
            "welkynd_radiance", 50, 0, 0, 1.04f, 1.03f, 400, 5400),
    /** The silver-skinned marsh folk of Black Marsh, all but lost to the Knahaten Flu. */
    KOTHRINGI(Group.LOST, ALCHEMY, of(RESTORATION, SPEECH, SNEAK, LIGHT_ARMOR, ARCHERY), of(FISHING),
            "quicksilver_skin", 0, 0, 0, 1.0f, 1.04f, 5800, 8300);

    /** How the character creator groups the races. */
    public enum Group {
        TAMRIEL, AKAVIR, LOST;

        public Component displayName() {
            return Component.translatable("race_group.skycraft." + name().toLowerCase(Locale.ROOT));
        }
    }

    public static final Race[] VALUES = values();
    /** Ticks between two uses of a greater power (one Skyrim day). */
    public static final int DAY = 24000;

    public final Group group;
    public final Skill major;
    public final Skill[] minors;
    /** +5 bonuses in the skills Skyrim doesn't have. */
    public final Skill[] extras;
    public final int bonusMagicka;
    /** Skyrim-scale health and stamina bonuses (on top of the 100 base). */
    public final int bonusHealth;
    public final int bonusStamina;
    public final String power;
    /** Render scale of the body (women and men share it; children are smaller anyway). */
    public final float scale;
    /** Pitch of the race's voice (shouts, racial power, roar). */
    public final float voicePitch;
    /** Where the race comes from on the map of Nirn (block coordinates); the player still starts at Helgen. */
    public final int homeX, homeZ;
    /** Cosmetic sub-peoples; index 0 is the default look. */
    public final String[] variants;

    Race(Group group, Skill major, Skill[] minors, Skill[] extras, String power, int bonusMagicka, int bonusHealth,
         int bonusStamina, float scale, float voicePitch, int homeX, int homeZ, String... variants) {
        this.group = group;
        this.major = major;
        this.minors = minors;
        this.extras = extras;
        this.power = power;
        this.bonusMagicka = bonusMagicka;
        this.bonusHealth = bonusHealth;
        this.bonusStamina = bonusStamina;
        this.scale = scale;
        this.voicePitch = voicePitch;
        this.homeX = homeX;
        this.homeZ = homeZ;
        this.variants = variants.length == 0 ? new String[]{""} : variants;
    }

    private static Skill[] of(Skill... skills) {
        return skills;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("race.skycraft." + id());
    }

    public Component description() {
        return Component.translatable("race.skycraft." + id() + ".desc");
    }

    public Component powerName() {
        return Component.translatable("power.skycraft." + power);
    }

    public Component powerDescription() {
        return Component.translatable("power.skycraft." + power + ".desc");
    }

    /** Number of cosmetic variants (1 when the race has none). */
    public int variantCount() {
        return variants.length;
    }

    public Component variantName(int variant) {
        String v = variants[Math.floorMod(variant, variants.length)];
        return v.isEmpty() ? displayName() : Component.translatable("race_variant.skycraft." + v);
    }

    /** Lang keys of the race's passive abilities, in display order ({@code race.skycraft.passive.<key>}). */
    public List<String> passives() {
        List<String> list = new ArrayList<>();
        for (String element : new String[]{"fire", "frost", "shock", "poison", "magic", "disease"}) {
            float r = resistance(element);
            if (r > 0) list.add("resist_" + element + ":" + Math.round(r * 100));
        }
        if (bonusMagicka > 0) list.add("magicka:" + bonusMagicka);
        if (bonusHealth > 0) list.add("health:" + bonusHealth);
        if (bonusStamina > 0) list.add("stamina:" + bonusStamina);
        if (waterBreathing()) list.add("water_breathing");
        if (clawDamage() > 0) list.add("claws:" + Math.round(clawDamage() * 5));
        if (this == TSAESCI) list.add("venom_bite");
        if (climbs()) list.add("climbing");
        if (leap() >= 0) list.add("leaping");
        if (fallDamage() < 1f) list.add("light_fall:" + Math.round((1f - fallDamage()) * 100));
        if (shoutCooldown() < 1f) list.add("thuum:" + Math.round((1f - shoutCooldown()) * 100));
        if (goldFind() > 1f) list.add("imperial_luck");
        return list;
    }

    public Map<Skill, Integer> startingSkills() {
        Map<Skill, Integer> map = new EnumMap<>(Skill.class);
        for (Skill s : Skill.VALUES) map.put(s, Skill.BASE_LEVEL);
        map.put(major, Skill.BASE_LEVEL + 10);
        for (Skill s : minors) map.put(s, Skill.BASE_LEVEL + 5);
        for (Skill s : extras) map.put(s, Skill.BASE_LEVEL + 5);
        return map;
    }

    /** Fraction of incoming damage of the given kind (fire, frost, shock, poison, magic, disease) this race resists. */
    public float resistance(String element) {
        return switch (element) {
            case "fire" -> this == DUNMER ? 0.5f : 0f;
            case "frost" -> switch (this) {
                case NORD, FALMER -> 0.5f;
                case ATMORAN -> 0.75f;
                case KA_PO_TUN -> 0.25f;
                default -> 0f;
            };
            case "shock" -> this == MAORMER ? 0.5f : this == DWEMER ? 0.25f : 0f;
            case "poison" -> switch (this) {
                case REDGUARD, BOSMER, ARGONIAN, KOTHRINGI -> 0.5f;
                case TSAESCI -> 0.75f;
                default -> 0f;
            };
            case "magic" -> switch (this) {
                case BRETON -> 0.25f;
                case REACHMAN, DWEMER -> 0.15f;
                default -> 0f;
            };
            case "disease" -> switch (this) {
                case ARGONIAN, BOSMER, LILMOTHIIT -> 0.5f;
                case KOTHRINGI -> 1f;
                default -> 0f;
            };
            default -> 0f;
        };
    }

    /**
     * Multiplier for damage this race takes from {@code source}: fire, frost (incl. frost spells), shock (shock spells
     * and lightning), poison (magic damage while poisoned) and other magic. Applied by the combat handler.
     */
    public float damageTaken(DamageSource source, LivingEntity victim) {
        float m = 1f;
        if (source.is(DamageTypeTags.IS_FIRE)) m *= 1f - resistance("fire");
        if (source.is(DamageTypeTags.IS_FREEZING)) m *= 1f - resistance("frost");
        if (source.is(DamageTypes.LIGHTNING_BOLT) || source.is(com.skycraft.magic.MagicDamage.SHOCK)) m *= 1f - resistance("shock");
        if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)) {
            // the poison effect hurts with plain magic damage
            m *= victim.hasEffect(MobEffects.POISON) && source.is(DamageTypes.MAGIC) ? 1f - resistance("poison") : 1f - resistance("magic");
        }
        return m;
    }

    /** Fraction of disease chances resisted (1 = immune). */
    public float diseaseResistance() {
        return resistance("disease");
    }

    /** Extra unarmed damage from claws, fists or fangs (Minecraft half-hearts). */
    public float clawDamage() {
        return switch (this) {
            case KHAJIIT -> 4f;
            case KA_PO_TUN -> 6f;
            case IMGA -> 3f;
            case TSAESCI -> 2f;
            default -> 0f;
        };
    }

    /** Breathes underwater. */
    public boolean waterBreathing() {
        return this == ARGONIAN || this == MAORMER;
    }

    /** Can climb sheer walls (hold jump against them). */
    public boolean climbs() {
        return this == TSAESCI || this == TANG_MO || this == IMGA;
    }

    /** Jump Boost amplifier of the race's natural leap, or -1. */
    public int leap() {
        return this == TANG_MO ? 1 : this == LILMOTHIIT ? 0 : -1;
    }

    /** Multiplier for fall damage. */
    public float fallDamage() {
        return switch (this) {
            case TANG_MO, LILMOTHIIT -> 0.5f;
            case KHAJIIT, OHMES_RAHT -> 0.75f;
            default -> 1f;
        };
    }

    /** Multiplier for the Voice's recharge after a shout. */
    public float shoutCooldown() {
        return this == ATMORAN ? 0.75f : 1f;
    }

    /** Multiplier for gold found as loot (Imperial luck). */
    public float goldFind() {
        return this == IMPERIAL ? 1.25f : 1f;
    }

    /** Ticks before the racial power can be used again (Night Eye is a lesser power). */
    public int powerCooldown() {
        return this == KHAJIIT ? 200 : DAY;
    }

    public static Race byId(String id) {
        for (Race r : VALUES) {
            if (r.id().equals(id)) return r;
        }
        return null;
    }
}
