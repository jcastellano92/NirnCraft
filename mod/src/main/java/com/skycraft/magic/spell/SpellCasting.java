package com.skycraft.magic.spell;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.perk.Perks;
import com.skycraft.registry.ModEffects;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.Vitals;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side casting state, Skyrim style: each hand holds a spell. Clients send "hand key down/up"
 * ({@link com.skycraft.magic.MagicPackets.Cast}); R is the right hand, the use key (empty off hand) the left.
 *
 * <ul>
 *     <li>Fire-and-forget spells are released a few ticks after the press (master spells after a 2 second charge
 *     while held). If the other hand presses the same spell within that window, it is a <b>dual cast</b>:
 *     {@link Spell#DUAL_COST} times the magicka for {@link Spell#DUAL_MAGNITUDE} times the effect.</li>
 *     <li>Concentration spells run every tick while held; the same spell held in both hands is dual cast.</li>
 * </ul>
 */
public final class SpellCasting {
    public static final int RIGHT = 0;
    public static final int LEFT = 1;
    /** Global cooldown between fire-and-forget casts of one hand (Skyrim's cast animation). */
    private static final int CAST_COOLDOWN = 10;
    /** Ticks a fire-and-forget cast waits for the other hand to join in for a dual cast. */
    private static final int DUAL_WINDOW = 3;

    private static final Map<UUID, Active[]> ACTIVE = new HashMap<>();
    private static final Map<UUID, long[]> NEXT_CAST = new HashMap<>();
    private static final Map<UUID, Long> LAST_FAIL = new HashMap<>();
    private static final Map<UUID, Float> WARD = new HashMap<>();

    /** Which side the spell being executed right now comes from: -1 left, 0 both (dual), 1 right. */
    private static int castingSide = 1;

    private SpellCasting() {}

    private static final class Active {
        final Spell spell;
        final int hand;
        int ticks;
        /** Charge ticks of a charged concentration spell. */
        int charge;
        boolean held = true;
        boolean hadEffect;

        Active(Spell spell, int hand) {
            this.spell = spell;
            this.hand = hand;
        }
    }

    /** For actions that draw from the casting hand (beams, projectiles): -1 left, 0 dual, 1 right. */
    public static int castingSide() {
        return castingSide;
    }

    private static boolean hasMagicka(ServerPlayer p, float amount) {
        return p.isCreative() || SkyData.get(p).getMagicka() >= amount;
    }

    private static void fail(ServerPlayer p) {
        long now = p.level().getGameTime();
        Long last = LAST_FAIL.get(p.getUUID());
        if (last != null && now - last < 20) return;
        LAST_FAIL.put(p.getUUID(), now);
        Notifier.message(p, Component.translatable("message.skycraft.not_enough_magicka"));
        p.playNotifySound(com.skycraft.audio.AudioSounds.spell("fizzle"), SoundSource.PLAYERS, 0.7f, 1.0f);
    }

    /** The spell equipped in a hand; an empty left hand falls back to the right hand's spell. */
    @Nullable
    public static Spell spellFor(ServerPlayer p, int hand) {
        // a hand unequipped from the menu holds nothing (it no longer falls back to the other hand's spell)
        String id = hand == LEFT ? MagicData.leftEffective(p) : MagicData.selectedSpell(p);
        return id.isEmpty() ? null : Spells.byId(id);
    }

    private static Active[] hands(ServerPlayer p) {
        return ACTIVE.computeIfAbsent(p.getUUID(), k -> new Active[2]);
    }

    /** A hand's cast key was pressed. */
    public static void start(ServerPlayer p, int hand) {
        if (hand != LEFT && hand != RIGHT) return;
        if (!p.isAlive() || p.isSpectator() || p.hasEffect(ModEffects.PARALYSIS.get())) return;
        Active[] hands = hands(p);
        if (hands[hand] != null) return;
        // one hand system (magic.Hands): a hand that holds an item or a bound weapon holds no spell, and a two-handed
        // item takes both. The right hand needs to be empty, the left one free of an off-hand or two-handed item.
        if (hand == RIGHT ? !p.getMainHandItem().isEmpty()
                : !p.getOffhandItem().isEmpty() || com.skycraft.magic.Hands.twoHanded(p.getMainHandItem())) return;
        Spell spell = spellFor(p, hand);
        if (spell == null || !MagicData.knows(p, spell.id) && !p.isCreative()) {
            Notifier.message(p, Component.translatable("message.skycraft.no_spell", Component.keybind("key.skycraft.magic_menu")));
            return;
        }
        if (spell.hostile && p.hasEffect(MagicRegistry.ETHEREAL.get())) {
            Notifier.message(p, Component.translatable("message.skycraft.ethereal_no_attack"));
            return;
        }
        float cost = SpellMath.cost(p, spell);
        if (spell.isConcentration()) {
            if (!hasMagicka(p, cost / 5f)) {
                fail(p);
                return;
            }
            hands[hand] = new Active(spell, hand);
            return;
        }
        long now = p.level().getGameTime();
        long[] next = NEXT_CAST.computeIfAbsent(p.getUUID(), k -> new long[2]);
        if (next[hand] > now) return;
        if (!hasMagicka(p, cost)) {
            fail(p);
            return;
        }
        hands[hand] = new Active(spell, hand);
        if (spell.chargeTicks > 0) MagicFx.sound(p, com.skycraft.audio.AudioSounds.spell("charge"), 0.8f, 1.0f);
    }

    /** A hand's cast key was released. */
    public static void stop(ServerPlayer p, int hand) {
        Active[] hands = ACTIVE.get(p.getUUID());
        if (hands == null || hand < 0 || hand > 1 || hands[hand] == null) return;
        Active a = hands[hand];
        if (!a.spell.isConcentration() && a.spell.chargeTicks <= 0) {
            a.held = false; // a quick tap still casts once the dual-cast window closes
            return;
        }
        end(p, hands, hand);
        if (a.spell.isConcentration() && a.ticks > 4 && a.spell.element == Element.FIRE) {
            MagicFx.sound(p, SoundEvents.FIRE_EXTINGUISH, 0.3f, 1.6f);
        }
    }

    /** Stops everything this player is casting (death, dimension change, menu re-equip). */
    public static void stop(ServerPlayer p) {
        ACTIVE.remove(p.getUUID());
        WARD.remove(p.getUUID());
    }

    private static void end(ServerPlayer p, Active[] hands, int hand) {
        hands[hand] = null;
        if (!isCastingWard(p)) WARD.remove(p.getUUID());
    }

    /** The ward spells: held to absorb spell damage from the front. */
    public static final java.util.Set<String> WARDS = java.util.Set.of("lesser_ward", "steadfast_ward", "greater_ward");

    /** Whether the player is holding up any ward. */
    public static boolean isCastingWard(ServerPlayer p) {
        Active[] hands = ACTIVE.get(p.getUUID());
        if (hands == null) return false;
        for (Active a : hands) {
            if (a != null && WARDS.contains(a.spell.id)) return true;
        }
        return false;
    }

    /**
     * Runs a spell's effect without hands or magicka: staves (charges pay for it). {@code side}: 1 right hand,
     * -1 left hand. Returns the result like a normal cast; the caller pays and grants XP.
     */
    public static Spell.Result castExternal(ServerPlayer p, Spell spell, int tick, int side) {
        castingSide = side;
        try {
            return spell.action.cast(p, spell, tick);
        } finally {
            castingSide = 1;
        }
    }

    /** Called every tick for every server player. */
    public static void tick(ServerPlayer p) {
        Active[] hands = ACTIVE.get(p.getUUID());
        if (hands == null || hands[0] == null && hands[1] == null) return;
        if (!p.isAlive() || p.isSpectator() || p.hasEffect(ModEffects.PARALYSIS.get())) {
            stop(p);
            return;
        }
        Active right = hands[RIGHT];
        Active left = hands[LEFT];
        boolean hasDualPerk = right != null && (p.isCreative() || Perks.has(p, right.spell.school.id() + ".dual_casting"));
        boolean dualConcentration = right != null && left != null && right.spell.isConcentration()
                && right.spell == left.spell && right.spell.dualCastable && hasDualPerk;
        if (dualConcentration) {
            tickConcentration(p, hands, right, true);
            if (hands[LEFT] != null) hands[LEFT].ticks++;
        } else {
            for (int h = 0; h < 2; h++) {
                Active a = hands[h];
                if (a != null && a.spell.isConcentration()) tickConcentration(p, hands, a, false);
            }
        }
        for (int h = 0; h < 2; h++) {
            Active a = hands[h];
            if (a == null || a.spell.isConcentration()) continue;
            a.ticks++;
            Spell spell = a.spell;
            if (spell.chargeTicks > 0) {
                if (a.ticks % 3 == 0) {
                    MagicFx.send(p, MagicFx.CHARGE, spell.element, p.position(), p.position(), p.getId(),
                            Math.min(100, a.ticks * 100 / Math.max(1, spell.chargeTicks)));
                }
                if (a.ticks % 10 == 0) MagicFx.sound(p, SoundEvents.BEACON_AMBIENT, 1f, 0.8f + a.ticks / (float) spell.chargeTicks);
                if (a.ticks >= spell.chargeTicks) release(p, hands, a);
            } else if (a.ticks >= DUAL_WINDOW) {
                release(p, hands, a);
            }
        }
    }

    private static void tickConcentration(ServerPlayer p, Active[] hands, Active a, boolean dual) {
        Spell spell = dual ? a.spell.dualCast() : a.spell;
        // Master concentration spells (Lightning Storm) charge for two seconds first; charging costs nothing.
        if (a.charge < spell.chargeTicks) {
            a.charge++;
            if (a.charge % 3 == 0) {
                MagicFx.send(p, MagicFx.CHARGE, spell.element, p.position(), p.position(), p.getId(),
                        Math.min(100, a.charge * 100 / Math.max(1, spell.chargeTicks)));
            }
            if (a.charge % 10 == 0) MagicFx.sound(p, SpellSounds.get(SpellSounds.Slot.CHARGE), 1f, 0.8f + a.charge / (float) spell.chargeTicks);
            return;
        }
        float perTick = SpellMath.cost(p, a.spell) * (dual ? Spell.DUAL_COST : 1f) / 20f;
        if (!Vitals.consumeMagicka(p, perTick)) {
            end(p, hands, a.hand);
            if (dual) end(p, hands, 1 - a.hand);
            fail(p);
            return;
        }
        castingSide = dual ? 0 : a.hand == LEFT ? -1 : 1;
        Spell.Result result;
        try {
            result = spell.action.cast(p, spell, a.ticks);
        } finally {
            castingSide = 1;
        }
        if (result == Spell.Result.FAILED) {
            end(p, hands, a.hand);
            return;
        }
        if (result == Spell.Result.EFFECT) a.hadEffect = true;
        if (a.ticks % 20 == 19) {
            if (a.hadEffect) Progression.addSkillXp(p, spell.school.skill, spell.cost * (dual ? Spell.DUAL_COST : 1f));
            a.hadEffect = false;
        }
        if (spell.hostile && a.ticks % 20 == 0) Vitals.markInCombat(p);
        a.ticks++;
    }

    private static void release(ServerPlayer p, Active[] hands, Active a) {
        int other = 1 - a.hand;
        Active partner = hands[other];
        boolean hasDualPerk = p.isCreative() || Perks.has(p, a.spell.school.id() + ".dual_casting");
        boolean dual = a.spell.dualCastable && partner != null && partner.spell == a.spell && !partner.spell.isConcentration()
                && (a.spell.chargeTicks <= 0 || partner.held) && hasDualPerk;
        hands[a.hand] = null;
        if (dual) hands[other] = null;
        if (a.spell.chargeTicks > 0 && !a.held) return; // released before fully charged
        float cost = SpellMath.cost(p, a.spell) * (dual ? Spell.DUAL_COST : 1f);
        if (!hasMagicka(p, cost)) {
            fail(p);
            return;
        }
        Spell spell = dual ? a.spell.dualCast() : a.spell;
        castingSide = dual ? 0 : a.hand == LEFT ? -1 : 1;
        Spell.Result result;
        try {
            result = spell.action.cast(p, spell, 0);
        } finally {
            castingSide = 1;
        }
        if (result == Spell.Result.FAILED) return;
        Vitals.consumeMagicka(p, cost);
        long[] next = NEXT_CAST.computeIfAbsent(p.getUUID(), k -> new long[2]);
        long now = p.level().getGameTime();
        next[a.hand] = now + CAST_COOLDOWN;
        if (dual) next[other] = now + CAST_COOLDOWN;
        if (result == Spell.Result.EFFECT) SpellEffects.xp(p, spell);
        if (dual) {
            p.swing(InteractionHand.MAIN_HAND, true);
            p.swing(InteractionHand.OFF_HAND, true);
            MagicFx.send(p, MagicFx.BURST, spell.element, p.position(), p.position(), p.getId(), 2);
            MagicFx.sound(p, SoundEvents.EVOKER_CAST_SPELL, 0.7f, 1.4f);
        } else {
            if (a.hand == RIGHT) p.swing(InteractionHand.MAIN_HAND, true);
            if (a.hand == LEFT) p.swing(InteractionHand.OFF_HAND, true);
        }
        SkyData.get(p).addStat("spells_cast", 1);
    }

    /** Whether the player is holding/casting the given spell in either hand. */
    public static boolean isCasting(ServerPlayer p, String spellId) {
        Active[] hands = ACTIVE.get(p.getUUID());
        if (hands == null) return false;
        for (Active a : hands) {
            if (a != null && a.spell.id.equals(spellId)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ wards

    static void raiseWard(ServerPlayer p, float strength) {
        WARD.put(p.getUUID(), strength);
    }

    /** Absorbs incoming spell damage with an active ward. Returns the damage that gets through. */
    public static float absorbWithWard(ServerPlayer p, DamageSource source, float amount) {
        Float pool = WARD.get(p.getUUID());
        if (pool == null || !isCastingWard(p) || !MagicDamage.isWardable(source)) return amount;
        Vec3 from = source.getSourcePosition();
        if (from != null) {
            Vec3 dir = from.subtract(p.getEyePosition()).normalize();
            if (dir.dot(p.getViewVector(1f)) < 0.1) return amount; // wards only cover the front
        }
        float absorbed = Math.min(pool, amount);
        pool -= absorbed;
        if (Perks.has(p, "restoration.ward_absorb")) {
            PlayerData data = SkyData.get(p);
            data.setMagicka(data.getMagicka() + absorbed * CombatHandler.SKYRIM_SCALE);
        }
        Progression.addSkillXp(p, Skill.RESTORATION, absorbed * CombatHandler.SKYRIM_SCALE * 0.5f);
        MagicFx.send(p, MagicFx.WARD, Element.HOLY, p.getEyePosition(), p.getViewVector(1f), p.getId(), 1);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.PLAYERS, 1.2f, 1.4f);
        if (pool <= 0.01f) {
            Active[] hands = ACTIVE.get(p.getUUID());
            if (hands != null) {
                for (int h = 0; h < 2; h++) {
                    if (hands[h] != null && WARDS.contains(hands[h].spell.id)) hands[h] = null;
                }
            }
            WARD.remove(p.getUUID());
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 1.2f);
            Notifier.message(p, Component.translatable("message.skycraft.ward_broken"));
        } else {
            WARD.put(p.getUUID(), pool);
        }
        return amount - absorbed;
    }

    public static void forget(UUID id) {
        ACTIVE.remove(id);
        NEXT_CAST.remove(id);
        LAST_FAIL.remove(id);
        WARD.remove(id);
    }

    public static void clear() {
        ACTIVE.clear();
        NEXT_CAST.clear();
        LAST_FAIL.clear();
        WARD.clear();
    }
}
