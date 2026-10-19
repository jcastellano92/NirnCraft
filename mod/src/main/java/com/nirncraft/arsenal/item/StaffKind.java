package com.nirncraft.arsenal.item;

import com.nirncraft.core.Skill;

/**
 * Skyrim staves. Staves don't use magicka: each use spends charges (NBT {@value StaffItem#CHARGES}); streaming staves
 * spend one charge per second of channelling. Recharged with filled soul gems.
 */
public enum StaffKind {
    FLAMES("staff_of_flames", Skill.DESTRUCTION, 40, true, 0),
    FROSTBITE("staff_of_frostbite", Skill.DESTRUCTION, 40, true, 0),
    SPARKS("staff_of_sparks", Skill.DESTRUCTION, 40, true, 0),
    FIREBALLS("staff_of_fireballs", Skill.DESTRUCTION, 25, false, 25),
    ICE_STORMS("staff_of_ice_storms", Skill.DESTRUCTION, 25, false, 30),
    CHAIN_LIGHTNING("staff_of_chain_lightning", Skill.DESTRUCTION, 25, false, 25),
    CALM("staff_of_calm", Skill.ILLUSION, 25, false, 20),
    FURY("staff_of_fury", Skill.ILLUSION, 25, false, 20),
    CONJURE_FAMILIAR("staff_of_conjure_familiar", Skill.CONJURATION, 15, false, 60),
    PARALYSIS("staff_of_paralysis", Skill.ALTERATION, 15, false, 30),
    HEALING("staff_of_healing", Skill.RESTORATION, 40, true, 0),
    WABBAJACK("wabbajack", Skill.ILLUSION, 50, false, 20),
    MAGNUS("staff_of_magnus", Skill.DESTRUCTION, 40, true, 0);

    public final String id;
    public final Skill skill;
    public final int maxCharges;
    /** Hold to channel (flames, frostbite, sparks, healing, Magnus) instead of a single cast. */
    public final boolean stream;
    /** Cooldown in ticks after a single cast. */
    public final int cooldown;

    StaffKind(String id, Skill skill, int maxCharges, boolean stream, int cooldown) {
        this.id = id;
        this.skill = skill;
        this.maxCharges = maxCharges;
        this.stream = stream;
        this.cooldown = cooldown;
    }

    public boolean artifact() {
        return this == WABBAJACK || this == MAGNUS;
    }
}
