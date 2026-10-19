package com.nirncraft.magic.spell;

import com.nirncraft.magic.bound.BoundWeapons;
import com.nirncraft.magic.spell.spellmaking.CustomSpells;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every spell in the game, Skyrim names and base costs. Damage is Skyrim damage divided by 5.
 * Spell ids are stable: they are saved in player data ({@code magic.spells}) and used by the tome item ids.
 *
 * <p>Ids starting with {@value CustomSpells#PREFIX} are spells made at a Spellmaking Altar: the id itself encodes the
 * name and effects, so {@link #byId} rebuilds (and caches) them on either side without any registry.</p>
 */
public final class Spells {
    private static final Map<String, Spell> BY_ID = new LinkedHashMap<>();
    private static final Map<School, List<Spell>> BY_SCHOOL = new EnumMap<>(School.class);

    private Spells() {}

    @Nullable
    public static Spell byId(String id) {
        if (id == null) return null;
        Spell spell = BY_ID.get(id);
        if (spell == null && id.startsWith(CustomSpells.PREFIX)) spell = CustomSpells.spell(id);
        return spell;
    }

    /** Every fixed (tome) spell, in school and level order. Custom spells are not listed. */
    public static Collection<Spell> all() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    public static List<Spell> of(School school) {
        return BY_SCHOOL.getOrDefault(school, List.of());
    }

    private static void add(Spell.Builder builder) {
        Spell spell = builder.build();
        BY_ID.put(spell.id, spell);
        BY_SCHOOL.computeIfAbsent(spell.school, k -> new ArrayList<>()).add(spell);
    }

    private static Spell.Builder s(String id, School school, Tier tier) {
        return Spell.builder(id, school, tier);
    }

    static {
        School D = School.DESTRUCTION, R = School.RESTORATION, A = School.ALTERATION, C = School.CONJURATION, I = School.ILLUSION,
                M = School.MYSTICISM;
        Tier NOV = Tier.NOVICE, APP = Tier.APPRENTICE, ADE = Tier.ADEPT, EXP = Tier.EXPERT, MAS = Tier.MASTER;

        // ------------------------------------------------------------------ Destruction
        add(s("flames", D, NOV).conc(14).mag(1.6f).radius(12).element(Element.FIRE).hostile().action(SpellEffects::beam));
        add(s("frostbite", D, NOV).conc(13).mag(1.6f).radius(12).element(Element.FROST).hostile().action(SpellEffects::beam));
        add(s("sparks", D, NOV).conc(16).mag(1.6f).radius(12).element(Element.SHOCK).hostile().action(SpellEffects::beam));
        add(s("firebolt", D, APP).ff(41).mag(5).element(Element.FIRE).hostile().projectile(2.0f, SpellEffects::elementalHit));
        add(s("ice_spike", D, APP).ff(48).mag(5).element(Element.FROST).hostile().projectile(2.2f, SpellEffects::elementalHit));
        add(s("lightning_bolt", D, APP).ff(61).mag(5).element(Element.SHOCK).hostile().projectile(4.0f, SpellEffects::elementalHit));
        add(s("fire_rune", D, APP).ff(113).mag(10).radius(3.5f).seconds(300).element(Element.FIRE).hostile().action(DestructionSpells::rune));
        add(s("frost_rune", D, APP).ff(124).mag(10).radius(3.5f).seconds(300).element(Element.FROST).hostile().action(DestructionSpells::rune));
        add(s("lightning_rune", D, APP).ff(166).mag(10).radius(3.5f).seconds(300).element(Element.SHOCK).hostile().action(DestructionSpells::rune));
        add(s("fireball", D, ADE).ff(133).mag(8).radius(4).element(Element.FIRE).hostile().projectile(1.6f, SpellEffects::explosion));
        add(s("ice_storm", D, ADE).ff(117).mag(8).radius(2.5f).seconds(4).element(Element.FROST).hostile()
                .projectile(0.45f, SpellEffects::stormEnd).flight(SpellEffects::stormFlight).piercing());
        add(s("chain_lightning", D, ADE).ff(140).mag(8).radius(7).element(Element.SHOCK).hostile().projectile(4.0f, SpellEffects::chainLightning));
        add(s("flame_cloak", D, ADE).ff(144).mag(1.6f).radius(3).seconds(60).element(Element.FIRE).action(DestructionSpells::cloak));
        add(s("frost_cloak", D, ADE).ff(165).mag(1.6f).radius(3).seconds(60).element(Element.FROST).action(DestructionSpells::cloak));
        add(s("lightning_cloak", D, ADE).ff(224).mag(2f).radius(3).seconds(60).element(Element.SHOCK).action(DestructionSpells::cloak));
        add(s("incinerate", D, EXP).ff(451).mag(12).element(Element.FIRE).hostile().projectile(3.0f, SpellEffects::elementalHit));
        add(s("icy_spear", D, EXP).ff(480).mag(12).element(Element.FROST).hostile().projectile(3.0f, SpellEffects::elementalHit));
        add(s("thunderbolt", D, EXP).ff(534).mag(12).element(Element.SHOCK).hostile().projectile(5.0f, SpellEffects::elementalHit));
        add(s("wall_of_flames", D, EXP).conc(71).mag(10).radius(20).element(Element.FIRE).hostile().action(DestructionSpells::wall));
        add(s("wall_of_frost", D, EXP).conc(68).mag(10).radius(20).element(Element.FROST).hostile().action(DestructionSpells::wall));
        add(s("wall_of_storms", D, EXP).conc(80).mag(12).radius(20).element(Element.SHOCK).hostile().action(DestructionSpells::wall));
        add(s("fire_storm", D, MAS).ff(1426).mag(20).radius(10).charge(40).element(Element.FIRE).hostile().action(SpellEffects::fireStorm));
        add(s("blizzard", D, MAS).ff(1438).mag(5).radius(9).seconds(12).charge(40).element(Element.FROST).hostile().action(SpellEffects::blizzard));
        add(s("lightning_storm", D, MAS).conc(360).mag(15).radius(48).charge(40).element(Element.SHOCK).hostile().action(DestructionSpells::lightningStorm));

        // ------------------------------------------------------------------ Restoration
        add(s("healing", R, NOV).conc(12).mag(2f).element(Element.HOLY).action(SpellEffects::healingConcentration));
        add(s("lesser_ward", R, NOV).conc(34).mag(8f).element(Element.HOLY).action(SpellEffects::ward));
        add(s("healing_hands", R, APP).conc(25).mag(2f).radius(16).element(Element.HOLY).action(SpellEffects::healOther));
        add(s("fast_healing", R, APP).ff(68).mag(10f).element(Element.HOLY).action(SpellEffects::healSelf));
        add(s("steadfast_ward", R, APP).conc(50).mag(12f).element(Element.HOLY).action(SpellEffects::ward));
        add(s("turn_lesser_undead", R, APP).ff(84).seconds(30).element(Element.HOLY).projectile(2.0f, Illusion::turnUndeadHit));
        add(s("sun_fire", R, APP).ff(70).mag(5).element(Element.HOLY).hostile().projectile(2.4f, RestorationSpells::sunFireHit));
        add(s("close_wounds", R, ADE).ff(207).mag(20f).element(Element.HOLY).action(SpellEffects::healSelf));
        add(s("heal_other", R, ADE).conc(40).mag(5f).radius(16).element(Element.HOLY).action(SpellEffects::healOther));
        add(s("greater_ward", R, ADE).conc(66).mag(16f).element(Element.HOLY).action(SpellEffects::ward));
        add(s("repel_lesser_undead", R, ADE).ff(152).seconds(30).radius(6).element(Element.HOLY).projectile(2.0f, RestorationSpells::repelUndeadHit));
        add(s("turn_undead", R, ADE).ff(154).seconds(30).element(Element.HOLY).projectile(2.0f, Illusion::turnUndeadHit));
        add(s("stendarrs_aura", R, ADE).ff(203).mag(4f).radius(4).seconds(60).element(Element.HOLY).action(DestructionSpells::cloak));
        add(s("grand_healing", R, EXP).ff(287).mag(40f).radius(8).element(Element.HOLY).action(SpellEffects::grandHealing));
        add(s("circle_of_protection", R, EXP).ff(357).seconds(30).radius(6).element(Element.HOLY).action(RestorationSpells::circleOfProtection));
        add(s("repel_undead", R, EXP).ff(247).seconds(30).radius(8).element(Element.HOLY).projectile(2.0f, RestorationSpells::repelUndeadHit));
        add(s("turn_greater_undead", R, EXP).ff(316).seconds(30).element(Element.HOLY).projectile(2.0f, Illusion::turnUndeadHit));
        add(s("guardian_circle", R, MAS).ff(1043).mag(4f).radius(6).seconds(30).charge(40).element(Element.HOLY).action(SpellEffects::guardianCircle));
        add(s("bane_of_the_undead", R, MAS).ff(1002).mag(4f).seconds(30).radius(24).charge(40).element(Element.HOLY).action(RestorationSpells::baneOfTheUndead));
        add(s("vampires_bane", R, MAS).ff(931).mag(8f).radius(10).charge(40).element(Element.HOLY).hostile().action(RestorationSpells::vampiresBane));

        // ------------------------------------------------------------------ Alteration
        add(s("candlelight", A, NOV).ff(21).seconds(60).element(Element.ARCANE).action(Candlelight::cast).noDual());
        add(s("oakflesh", A, NOV).ff(98).mag(4).seconds(60).element(Element.NATURE).action(SpellEffects::flesh));
        add(s("magelight", A, APP).ff(84).seconds(60).element(Element.ARCANE).projectile(1.6f, AlterationSpells::magelightHit).noDual());
        add(s("stoneflesh", A, APP).ff(194).mag(6).seconds(60).element(Element.NATURE).action(SpellEffects::flesh));
        add(s("detect_life", A, APP).ff(114).radius(48).seconds(15).element(Element.ARCANE).action(SpellEffects::detectLife).noDual());
        add(s("waterbreathing", A, APP).ff(134).seconds(60).element(Element.ARCANE).action(SpellEffects::waterbreathing).noDual());
        add(s("ironflesh", A, ADE).ff(286).mag(8).seconds(60).element(Element.NATURE).action(SpellEffects::flesh));
        add(s("transmute", A, ADE).ff(142).element(Element.ARCANE).action(SpellEffects::transmute).noDual());
        add(s("detect_dead", A, ADE).ff(172).radius(48).seconds(15).element(Element.SOUL).action(AlterationSpells::detectDead).noDual());
        add(s("equilibrium", A, ADE).ff(0).mag(5).element(Element.ARCANE).action(AlterationSpells::equilibrium).noDual());
        add(s("ash_shell", A, ADE).ff(190).seconds(15).element(Element.NATURE).hostile().projectile(2.0f, AlterationSpells::ashShellHit));
        add(s("ebonyflesh", A, EXP).ff(335).mag(10).seconds(60).element(Element.NATURE).action(SpellEffects::flesh));
        add(s("paralyze", A, EXP).ff(444).seconds(10).element(Element.ARCANE).hostile().projectile(2.5f, SpellEffects::paralyzeHit));
        add(s("dragonhide", A, MAS).ff(1065).mag(80).seconds(30).charge(40).element(Element.NATURE).action(AlterationSpells::dragonhide).noDual());
        add(s("mass_paralysis", A, MAS).ff(1100).seconds(15).radius(6).charge(40).element(Element.ARCANE).hostile()
                .projectile(2.0f, SpellEffects::massParalysisHit));

        // ------------------------------------------------------------------ Conjuration
        add(s("conjure_familiar", C, NOV).ff(105).seconds(60).element(Element.CONJURE).action(Summons::familiar).noDual());
        add(s("bound_sword", C, NOV).ff(105).seconds(120).element(Element.CONJURE).action(BoundWeapons::sword).noDual());
        add(s("bound_dagger", C, NOV).ff(84).seconds(120).element(Element.CONJURE).action(BoundWeapons::dagger).noDual());
        add(s("raise_zombie", C, NOV).ff(80).seconds(60).element(Element.SOUL).action(Summons::zombie).noDual());
        add(s("soul_trap", C, NOV).ff(98).seconds(60).element(Element.SOUL).hostile().projectile(2.0f, SpellEffects::soulTrapHit).noDual());
        add(s("conjure_flame_atronach", C, APP).ff(122).seconds(60).element(Element.FIRE).action(Summons::flameAtronach).noDual());
        add(s("bound_bow", C, APP).ff(145).seconds(120).element(Element.CONJURE).action(BoundWeapons::bow).noDual());
        add(s("reanimate_corpse", C, APP).ff(82).seconds(60).element(Element.SOUL).action(Summons::reanimateCorpse).noDual());
        add(s("conjure_frost_atronach", C, ADE).ff(158).seconds(60).element(Element.FROST).action(Summons::frostAtronach).noDual());
        add(s("bound_battleaxe", C, ADE).ff(140).seconds(120).element(Element.CONJURE).action(BoundWeapons::battleaxe).noDual());
        add(s("revenant", C, ADE).ff(107).seconds(60).element(Element.SOUL).action(Summons::revenant).noDual());
        add(s("flaming_familiar", C, ADE).ff(140).seconds(60).element(Element.FIRE).action(Summons::flamingFamiliar).noDual());
        add(s("conjure_storm_atronach", C, EXP).ff(223).seconds(60).element(Element.SHOCK).action(Summons::stormAtronach).noDual());
        add(s("dread_zombie", C, EXP).ff(150).seconds(60).element(Element.SOUL).action(Summons::dreadZombie).noDual());
        add(s("conjure_seeker", C, EXP).ff(250).seconds(60).element(Element.CONJURE).action(Summons::seeker).noDual());
        add(s("banish_daedra", C, EXP).ff(220).element(Element.CONJURE).hostile().projectile(2.2f, Summons::banishHit));
        add(s("conjure_dremora_lord", C, MAS).ff(600).seconds(60).charge(40).element(Element.CONJURE).action(Summons::dremoraLord).noDual());
        add(s("dead_thrall", C, MAS).ff(450).charge(40).element(Element.SOUL).action(Summons::deadThrall).noDual());
        add(s("flame_thrall", C, MAS).ff(571).charge(40).element(Element.FIRE).action(Summons::flameThrall).noDual());
        add(s("frost_thrall", C, MAS).ff(715).charge(40).element(Element.FROST).action(Summons::frostThrall).noDual());
        add(s("storm_thrall", C, MAS).ff(800).charge(40).element(Element.SHOCK).action(Summons::stormThrall).noDual());
        add(s("command_daedra", C, MAS).ff(400).seconds(60).element(Element.CONJURE).hostile().projectile(2.2f, Summons::commandHit));

        // ------------------------------------------------------------------ Illusion
        add(s("clairvoyance", I, NOV).conc(9).radius(64).element(Element.MIND).action(Illusion::clairvoyance).noDual());
        add(s("fury", I, NOV).ff(47).seconds(30).element(Element.MIND).projectile(2.0f, Illusion::furyHit));
        add(s("courage", I, NOV).ff(39).seconds(60).radius(24).element(Element.MIND).action(Illusion::courage));
        add(s("calm", I, APP).ff(85).seconds(30).element(Element.MIND).projectile(2.0f, Illusion::calmHit));
        add(s("fear", I, APP).ff(95).seconds(30).element(Element.MIND).projectile(2.0f, Illusion::fearHit));
        add(s("muffle", I, APP).ff(144).seconds(180).element(Element.MIND).action(Illusion::muffle).noDual());
        add(s("pacify", I, ADE).ff(166).seconds(60).radius(7).element(Element.MIND).projectile(1.8f, Illusion::pacifyHit));
        add(s("rally", I, ADE).ff(140).seconds(60).radius(12).element(Element.MIND).action(Illusion::rally));
        add(s("frenzy", I, ADE).ff(165).seconds(60).radius(7).element(Element.MIND).projectile(1.8f, Illusion::frenzyHit));
        add(s("rout", I, ADE).ff(160).seconds(30).radius(7).element(Element.MIND).projectile(1.8f, Illusion::routHit));
        add(s("invisibility", I, EXP).ff(334).seconds(30).element(Element.MIND).action(Illusion::invisibility).noDual());
        add(s("call_to_arms", I, MAS).ff(1000).seconds(60).radius(24).charge(40).element(Element.MIND).action(Illusion::callToArms));
        add(s("harmony", I, MAS).ff(1078).seconds(60).radius(24).charge(40).element(Element.MIND).action(Illusion::harmony));
        add(s("mayhem", I, MAS).ff(1146).seconds(60).radius(24).charge(40).element(Element.MIND).action(Illusion::mayhem));
        add(s("hysteria", I, MAS).ff(1078).seconds(30).radius(24).charge(40).element(Element.MIND).action(Illusion::hysteria));

        // ------------------------------------------------------------------ Mysticism (Oblivion / Morrowind)
        add(s("telekinesis", M, NOV).conc(16).radius(16).element(Element.ARCANE).action(Mysticism::telekinesis).noDual());
        add(s("detect_life_mysticism", M, NOV).ff(40).radius(40).seconds(30).element(Element.ARCANE).action(Mysticism::senseLife).noDual());
        add(s("soul_trap_mysticism", M, NOV).ff(70).seconds(60).element(Element.SOUL).hostile().projectile(2.0f, SpellEffects::soulTrapHit).noDual());
        add(s("slowfall", M, NOV).ff(35).seconds(30).element(Element.ARCANE).action(Mysticism::slowfall).noDual());
        add(s("dispel", M, APP).ff(95).radius(8).element(Element.ARCANE).action(Mysticism::dispel));
        add(s("water_walking", M, APP).ff(70).seconds(120).element(Element.FROST).action(Mysticism::waterWalking).noDual());
        add(s("absorb_health", M, APP).conc(25).mag(1.6f).radius(12).element(Element.SOUL).hostile().action(Mysticism::absorbHealth));
        add(s("absorb_stamina", M, APP).conc(22).mag(2f).radius(12).element(Element.NATURE).hostile().action(Mysticism::absorbStamina));
        add(s("absorb_magicka", M, APP).conc(22).mag(2f).radius(12).element(Element.ARCANE).hostile().action(Mysticism::absorbMagicka));
        add(s("mark", M, APP).ff(60).element(Element.ARCANE).action(Mysticism::mark).noDual());
        add(s("divine_intervention", M, APP).ff(120).element(Element.HOLY).action(Mysticism::divineIntervention).noDual());
        add(s("almsivi_intervention", M, APP).ff(120).element(Element.FIRE).action(Mysticism::almsiviIntervention).noDual());
        add(s("recall", M, ADE).ff(180).element(Element.ARCANE).action(Mysticism::recall).noDual());
        add(s("reflect_spell", M, ADE).ff(180).mag(30).seconds(30).element(Element.ARCANE).action(Mysticism::reflect));
        add(s("spell_absorption", M, ADE).ff(200).mag(30).seconds(30).element(Element.ARCANE).action(Mysticism::absorption));
        add(s("levitate", M, ADE).ff(240).seconds(30).element(Element.ARCANE).action(Mysticism::levitate).noDual());
        add(s("telekinetic_hurl", M, EXP).ff(300).mag(10).radius(3).element(Element.ARCANE).hostile().projectile(2.6f, Mysticism::hurlHit));
        add(s("spell_turning", M, EXP).ff(450).mag(50).seconds(30).element(Element.ARCANE).action(Mysticism::spellTurning));
        add(s("mass_dispel", M, MAS).ff(900).radius(20).charge(40).element(Element.ARCANE).action(Mysticism::massDispel));
    }
}
