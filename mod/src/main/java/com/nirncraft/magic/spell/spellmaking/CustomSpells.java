package com.nirncraft.magic.spell.spellmaking;

import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.magic.MagicData;
import com.nirncraft.magic.spell.CustomEffects;
import com.nirncraft.magic.spell.Element;
import com.nirncraft.magic.spell.School;
import com.nirncraft.magic.spell.Spell;
import com.nirncraft.magic.spell.Tier;
import com.nirncraft.magic.spell.spellmaking.SpellEffectType.Range;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Spells made at a Spellmaking Altar. A made spell's id encodes everything about it, so both sides rebuild the same
 * {@link Spell} from the id alone and nothing new has to be registered or synced:
 * {@code sm/<name>/<effect>:<range>:<magnitude>:<duration>:<area>;<effect>...}, e.g.
 * {@code sm/Ember Kiss/fire_damage:r:30:0:2;soul_trap:r:0:20:0}. Ranges are {@code s}elf, {@code t}ouch and
 * ta{@code r}get.
 *
 * <p>The spell's school is that of its most expensive effect, its level comes from its total base cost, and its cost
 * follows {@link #effectCost}. Making one at the altar costs gold (twice the spell's base cost) and needs the school's
 * skill to reach the spell's level.</p>
 */
public final class CustomSpells {
    public static final String PREFIX = "sm/";
    public static final int MAX_EFFECTS = 4;
    public static final int MAX_NAME = 24;
    /** Spells one player can have made at once (the oldest must be forgotten first). */
    public static final int MAX_PER_PLAYER = 32;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 '\\-]{1," + MAX_NAME + "}");

    /** One effect line of a made spell. */
    public record Effect(SpellEffectType type, Range range, int magnitude, int duration, int area) {
        String encode() {
            return type.id() + ":" + range.code + ":" + magnitude + ":" + duration + ":" + area;
        }
    }

    /** A made spell: its name and effects. */
    public record Def(String name, List<Effect> effects) {
        public String id() {
            StringBuilder sb = new StringBuilder(PREFIX).append(name).append('/');
            for (int i = 0; i < effects.size(); i++) {
                if (i > 0) sb.append(';');
                sb.append(effects.get(i).encode());
            }
            return sb.toString();
        }
    }

    private static final Map<String, Optional<Def>> DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Spell>> SPELLS = new ConcurrentHashMap<>();

    private CustomSpells() {}

    public static boolean isCustom(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.isBlank();
    }

    // ------------------------------------------------------------------ parsing

    /** The definition encoded in a made spell's id, or null if the id is malformed or out of bounds. */
    @Nullable
    public static Def def(String id) {
        if (!isCustom(id) || id.length() > 256) return null;
        return DEFS.computeIfAbsent(id, k -> Optional.ofNullable(parse(k))).orElse(null);
    }

    @Nullable
    private static Def parse(String id) {
        String body = id.substring(PREFIX.length());
        int slash = body.indexOf('/');
        if (slash <= 0) return null;
        String name = body.substring(0, slash);
        if (!validName(name)) return null;
        String[] parts = body.substring(slash + 1).split(";");
        if (parts.length < 1 || parts.length > MAX_EFFECTS) return null;
        List<Effect> effects = new ArrayList<>();
        Set<SpellEffectType> seen = EnumSet.noneOf(SpellEffectType.class);
        for (String part : parts) {
            String[] f = part.split(":");
            if (f.length != 5 || f[1].length() != 1) return null;
            SpellEffectType type = SpellEffectType.byId(f[0]);
            Range range = Range.byCode(f[1].charAt(0));
            if (type == null || range == null || !type.allows(range) || !seen.add(type)) return null;
            int mag, dur, area;
            try {
                mag = Integer.parseInt(f[2]);
                dur = Integer.parseInt(f[3]);
                area = Integer.parseInt(f[4]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (type.hasMagnitude() ? mag < type.minMag || mag > type.maxMag : mag != 0) return null;
            if (type.hasDuration() ? dur < type.minDur || dur > type.maxDur : dur != 0) return null;
            if (area < 0 || area > (type.hasArea() && range != Range.SELF ? SpellEffectType.MAX_AREA : 0)) return null;
            effects.add(new Effect(type, range, mag, dur, area));
        }
        return new Def(name, List.copyOf(effects));
    }

    // ------------------------------------------------------------------ cost & level

    /** Base magicka cost of one effect (Oblivion's formula, see {@link SpellEffectType}). */
    public static float effectCost(Effect e) {
        float c = e.type().base * (float) Math.pow(Math.max(1, e.magnitude()), 1.28) * Math.max(1, e.duration()) * (1f + 0.15f * e.area());
        if (e.range() == Range.TARGET) c *= 1.5f;
        return Math.max(1f, c);
    }

    public static float baseCost(Def def) {
        float total = 0;
        for (Effect e : def.effects()) total += effectCost(e);
        return total;
    }

    /** Spell level by base cost (Skyrim's tome spells sit in the same bands). */
    public static Tier tierFor(float cost) {
        if (cost <= 60) return Tier.NOVICE;
        if (cost <= 160) return Tier.APPRENTICE;
        if (cost <= 400) return Tier.ADEPT;
        if (cost <= 900) return Tier.EXPERT;
        return Tier.MASTER;
    }

    /** Skill level needed in the spell's school to make a spell of this level. */
    public static int skillFor(Tier tier) {
        return switch (tier) {
            case NOVICE -> 0;
            case APPRENTICE -> 25;
            case ADEPT -> 50;
            case EXPERT -> 75;
            case MASTER -> 90;
        };
    }

    public static Effect mostExpensive(Def def) {
        Effect best = def.effects().get(0);
        for (Effect e : def.effects()) {
            if (effectCost(e) > effectCost(best)) best = e;
        }
        return best;
    }

    /** Gold the altar charges to make this spell. */
    public static long goldCost(Def def) {
        return Math.max(10, Math.round(baseCost(def) * 2));
    }

    // ------------------------------------------------------------------ the Spell

    /** The {@link Spell} for a made spell's id, or null if the id is invalid. Cached. */
    @Nullable
    public static Spell spell(String id) {
        if (!isCustom(id)) return null;
        return SPELLS.computeIfAbsent(id, k -> Optional.ofNullable(build(k))).orElse(null);
    }

    @Nullable
    private static Spell build(String id) {
        Def def = def(id);
        if (def == null) return null;
        Effect main = mostExpensive(def);
        float cost = baseCost(def);
        School school = main.type().school;
        Spell.Builder b = Spell.builder(id, school, tierFor(cost)).ff(cost).radius(4).element(main.type().element)
                .custom(Component.literal(def.name()), describe(def));
        boolean target = false, hostile = false;
        for (Effect e : def.effects()) {
            target |= e.range() == Range.TARGET;
            hostile |= e.range() != Range.SELF && e.type().harmful();
        }
        if (target) b.projectile(2.0f, CustomEffects::impact);
        b.action(CustomEffects::cast);
        if (hostile) b.hostile();
        return b.build();
    }

    /** "Fire Damage 30 pts in 2 m on Target. Soul Trap for 20 s on Target." */
    public static Component describe(Def def) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < def.effects().size(); i++) {
            if (i > 0) out.append(" ");
            out.append(line(def.effects().get(i)));
        }
        return out;
    }

    public static MutableComponent line(Effect e) {
        MutableComponent c = Component.empty().append(e.type().displayName());
        if (e.type().hasMagnitude()) c.append(Component.translatable(magnitudeKey(e.type()), e.magnitude()));
        if (e.type().hasDuration()) c.append(Component.translatable("spellmaking.nirncraft.for_seconds", e.duration()));
        if (e.area() > 0) c.append(Component.translatable("spellmaking.nirncraft.in_area", e.area()));
        c.append(Component.translatable("spellmaking.nirncraft.on_range", e.range().displayName()));
        return c;
    }

    /** Magnitude unit: points, percent, armor, blocks or "level". */
    public static String magnitudeKey(SpellEffectType type) {
        return switch (type) {
            case REFLECT_SPELL, SPELL_ABSORPTION -> "spellmaking.nirncraft.mag_percent";
            case SHIELD -> "spellmaking.nirncraft.mag_armor";
            case DETECT_LIFE -> "spellmaking.nirncraft.mag_blocks";
            case CALM, FEAR, FRENZY, TURN_UNDEAD -> "spellmaking.nirncraft.mag_level";
            default -> "spellmaking.nirncraft.mag_points";
        };
    }

    // ------------------------------------------------------------------ players

    /** Effects this player can use: those taught by spells it knows (creative: all). */
    public static List<SpellEffectType> knownEffects(Player player) {
        List<String> known = MagicData.knownSpells(player);
        List<SpellEffectType> out = new ArrayList<>();
        for (SpellEffectType t : SpellEffectType.VALUES) {
            if (player.isCreative() || t.knownBy(known)) out.add(t);
        }
        return out;
    }

    /** Made spells the player knows. */
    public static List<String> madeSpells(Player player) {
        List<String> out = new ArrayList<>();
        for (String id : MagicData.knownSpells(player)) {
            if (isCustom(id)) out.add(id);
        }
        return out;
    }

    /** Why the player can't make this spell (lang key), or null if it can. Shared by the screen and the server check. */
    @Nullable
    public static String problem(Player player, Def def) {
        if (!validName(def.name())) return "spellmaking.nirncraft.problem.name";
        if (def.effects().isEmpty()) return "spellmaking.nirncraft.problem.empty";
        List<SpellEffectType> known = knownEffects(player);
        for (Effect e : def.effects()) {
            if (!known.contains(e.type())) return "spellmaking.nirncraft.problem.unknown_effect";
        }
        if (def(def.id()) == null) return "spellmaking.nirncraft.problem.invalid";
        Tier tier = tierFor(baseCost(def));
        School school = mostExpensive(def).type().school;
        PlayerData data = SkyData.get(player);
        if (!player.isCreative() && data.getSkill(school.skill) < skillFor(tier)) return "spellmaking.nirncraft.problem.skill";
        if (MagicData.knows(player, def.id())) return "spellmaking.nirncraft.problem.known";
        if (madeSpells(player).size() >= MAX_PER_PLAYER) return "spellmaking.nirncraft.problem.too_many";
        if (!player.isCreative() && com.nirncraft.core.Currency.balance(player) < goldCost(def)) return "spellmaking.nirncraft.problem.gold";
        return null;
    }

    /** Element of an effect line (for its damage type and visuals). */
    public static Element element(Effect e) {
        return e.type().element;
    }
}
