package com.skycraft.society;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The jobs of Skyrim's people (cross-module contract 22). A role decides the entity type ({@code skycraft:npc} for
 * townsfolk that crime treats as civilians, {@code skycraft:npc_fighter} for soldiers and villains whose death is not
 * a murder), the skin set, the gear, the fighting style and the reputation faction its members belong to.
 */
public enum NpcRole {
    //          civilian combat caster protector villain faction
    HUNTER(true, true, false, false, false, Reputation.TOWNSFOLK),
    MINER(true, true, false, false, false, Reputation.TOWNSFOLK),
    LUMBERJACK(true, true, false, false, false, Reputation.TOWNSFOLK),
    BARD(true, false, false, false, false, Reputation.TOWNSFOLK),
    PRIEST(true, false, false, false, false, Reputation.TOWNSFOLK),
    BEGGAR(true, false, false, false, false, Reputation.TOWNSFOLK),
    MAGE(true, true, true, true, false, Reputation.COLLEGE),
    ADVENTURER(true, true, false, true, false, Reputation.TOWNSFOLK),
    THALMOR(false, true, true, false, false, Reputation.THALMOR),
    IMPERIAL_SOLDIER(false, true, false, true, false, Reputation.IMPERIAL_LEGION),
    STORMCLOAK_SOLDIER(false, true, false, true, false, Reputation.STORMCLOAKS),
    FORSWORN(false, true, false, false, true, Reputation.FORSWORN),
    VAMPIRE(false, true, false, false, true, Reputation.VAMPIRES),
    NECROMANCER(false, true, true, false, true, null),
    ASSASSIN(false, true, false, false, true, Reputation.DARK_BROTHERHOOD),
    THUG(false, true, false, false, true, Reputation.BANDITS),
    COURIER(true, false, false, false, false, Reputation.TOWNSFOLK),
    INNKEEPER(true, false, false, false, false, Reputation.TOWNSFOLK),
    JARL(true, true, false, false, false, Reputation.GUARDS),
    HOUSECARL(true, true, false, true, false, Reputation.GUARDS),
    /** Not part of the contract: the pickpocket fleeing from guards in a random encounter. */
    THIEF(false, false, false, false, false, Reputation.THIEVES_GUILD),
    // townsfolk trades (they replace vanilla villagers; merchants among them barter through the economy module)
    FARMER(true, false, false, false, false, Reputation.TOWNSFOLK),
    BLACKSMITH(true, false, false, false, false, Reputation.TOWNSFOLK),
    MERCHANT(true, false, false, false, false, Reputation.TOWNSFOLK),
    APOTHECARY(true, false, false, false, false, Reputation.TOWNSFOLK),
    FISHER(true, false, false, false, false, Reputation.TOWNSFOLK),
    BUTCHER(true, false, false, false, false, Reputation.TOWNSFOLK),
    SCHOLAR(true, false, false, false, false, Reputation.TOWNSFOLK),
    STEWARD(true, false, false, false, false, Reputation.GUARDS),
    /** Khajiit caravan traders (replace wandering traders). */
    CARAVAN_TRADER(true, true, false, false, false, Reputation.TOWNSFOLK),
    /** Skill trainers: teach a specific skill up to their tier cap (Journeyman 50, Expert 75, Master 90). */
    TRAINER(true, true, false, false, false, Reputation.TOWNSFOLK),
    CITIZEN(true, false, false, false, false, Reputation.TOWNSFOLK),
    /** Children: smaller, never harmed (like Skyrim's). */
    CHILD(true, false, false, false, false, Reputation.TOWNSFOLK);

    public static final NpcRole[] VALUES = values();
    /** Skin variants per role: even = male, odd = female. */
    public static final int SKINS = 2;

    public final String id;
    /** Spawned as {@code skycraft:npc} (a civilian: talker, witness, murder victim) rather than {@code skycraft:npc_fighter}. */
    public final boolean civilian;
    /** Fights back (and attacks what it is hostile to); non-combatants flee instead. */
    public final boolean combatant;
    /** Throws spells (fire charges) from a distance. */
    public final boolean caster;
    /** Attacks monsters, bandits and villains on sight. */
    public final boolean protector;
    /** Outlaws: guards and protectors fight them. */
    public final boolean villain;
    /** Reputation faction of its members (killing one lowers it), or null. */
    @Nullable
    public final String faction;

    NpcRole(boolean civilian, boolean combatant, boolean caster, boolean protector, boolean villain, @Nullable String faction) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.civilian = civilian;
        this.combatant = combatant;
        this.caster = caster;
        this.protector = protector;
        this.villain = villain;
        this.faction = faction;
    }

    @Nullable
    public static NpcRole byId(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        for (NpcRole r : VALUES) if (r.id.equals(id)) return r;
        return null;
    }

    public Component displayName() {
        return Component.translatable("society.skycraft.role." + id);
    }

    /** Roles with a personal name ("Lucan the Hunter"); the others are named by their role ("Thalmor Justiciar"). */
    public boolean named() {
        return civilian || this == THUG;
    }

    /** Settled working townsfolk (they keep a home, a bed, a workplace and a daily routine). */
    public boolean townsfolk() {
        return civilian && this != COURIER && this != ADVENTURER && this != CARAVAN_TRADER;
    }

    /** Whether people with this role may marry the player. */
    public boolean marriageable() {
        return civilian && this != CHILD && this != JARL && this != COURIER && this != PRIEST;
    }

    /** Whether two roles fight each other on sight (symmetric). */
    public static boolean opposed(NpcRole a, NpcRole b) {
        return pair(a, b) || pair(b, a);
    }

    private static boolean pair(NpcRole a, NpcRole b) {
        return switch (a) {
            case IMPERIAL_SOLDIER -> b == STORMCLOAK_SOLDIER || b == FORSWORN || b == NECROMANCER;
            case STORMCLOAK_SOLDIER -> b == THALMOR || b == FORSWORN || b == VAMPIRE;
            case MAGE -> b == NECROMANCER || b == VAMPIRE;
            case ADVENTURER, HOUSECARL -> b == FORSWORN || b == NECROMANCER || b == VAMPIRE || b == THUG;
            case HUNTER -> b == FORSWORN;
            default -> false;
        };
    }
}
