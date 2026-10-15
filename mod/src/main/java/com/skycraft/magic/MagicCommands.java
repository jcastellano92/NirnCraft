package com.skycraft.magic;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.core.Skill;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.spell.School;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.Spells;
import com.skycraft.magic.spell.Summons;
import com.skycraft.magic.spell.Tier;
import com.skycraft.magic.spell.spellmaking.CustomSpells;
import com.skycraft.magic.spell.spellmaking.SpellEffectType;
import com.skycraft.perk.Perk;
import com.skycraft.perk.Perks;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Developer commands of the magic and skills workstream, merged under {@code /skycraft}:
 * <ul>
 *     <li>{@code /skycraft magic spells [school]}: every spell with its level, type and base cost</li>
 *     <li>{@code /skycraft magic shouts}: every shout's canonical id, words and recharge times</li>
 *     <li>{@code /skycraft magic skills}: every skill and its perk count</li>
 *     <li>{@code /skycraft magic selftest}: consistency checks (tomes, codecs, perk trees, summon types, places)</li>
 *     <li>{@code /skycraft magic learnall}: the caller learns every spell and every word of power, unlocked</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class MagicCommands {
    private MagicCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft").then(Commands.literal("magic")
                .then(Commands.literal("spells").executes(c -> spells(c, null))
                        .then(Commands.argument("school", StringArgumentType.word()).executes(c -> spells(c, StringArgumentType.getString(c, "school")))))
                .then(Commands.literal("shouts").executes(MagicCommands::shouts))
                .then(Commands.literal("skills").executes(MagicCommands::skills))
                .then(Commands.literal("selftest").requires(s -> s.hasPermission(2)).executes(MagicCommands::selftest))
                .then(Commands.literal("learnall").requires(s -> s.hasPermission(2)).executes(MagicCommands::learnAll))));
    }

    private static void out(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static int spells(CommandContext<CommandSourceStack> c, String school) {
        CommandSourceStack src = c.getSource();
        int n = 0;
        for (School s : School.VALUES) {
            if (school != null && !s.id().equals(school.toLowerCase(Locale.ROOT))) continue;
            List<Spell> list = Spells.of(s);
            out(src, "== " + s.id() + " (" + list.size() + ")");
            for (Spell sp : list) {
                out(src, String.format(Locale.ROOT, "  %-24s %-10s %-4s cost %6.0f%s mag %.1f", sp.id, sp.tier.id(),
                        sp.isConcentration() ? "conc" : "ff", sp.cost, sp.isConcentration() ? "/s" : "  ", sp.magnitude));
                n++;
            }
        }
        out(src, n + " spells");
        return n;
    }

    private static int shouts(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        for (Shout s : Shout.VALUES) {
            out(src, String.format(Locale.ROOT, "  %-18s %-16s %ds / %ds / %ds", s.id(), s.phrase(3), s.cooldownSeconds(1), s.cooldownSeconds(2),
                    s.cooldownSeconds(3)));
        }
        out(src, Shout.VALUES.length + " shouts");
        return Shout.VALUES.length;
    }

    private static int skills(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        int perks = 0;
        for (Skill s : Skill.VALUES) {
            int n = Perks.of(s).size();
            perks += n;
            out(src, String.format(Locale.ROOT, "  %-12s %-8s %2d perks", s.id(), s.group.name().toLowerCase(Locale.ROOT), n));
        }
        out(src, Skill.VALUES.length + " skills, " + perks + " perks");
        return Skill.VALUES.length;
    }

    /** Consistency checks; returns the number of problems (also printed and logged). */
    public static int runSelftest(java.util.function.Consumer<String> report) {
        return runSelftest(report, null);
    }

    /** As above; with a player it also runs the hand, bound weapon, tome and favorite checks on their inventory (restored). */
    public static int runSelftest(java.util.function.Consumer<String> report, @org.jetbrains.annotations.Nullable ServerPlayer player) {
        List<String> problems = new ArrayList<>();
        int spells = 0;
        for (Spell s : Spells.all()) {
            spells++;
            if (!MagicRegistry.TOMES.containsKey(s.id)) problems.add("no tome for " + s.id);
            if (s.cost < 0) problems.add("negative cost " + s.id);
            if (Spells.byId(s.id) != s) problems.add("lookup mismatch " + s.id);
        }
        for (School s : School.VALUES) {
            if (Spells.of(s).isEmpty()) problems.add("empty school " + s.id());
            for (Tier t : Tier.VALUES) {
                if (Perks.get(t.perkFor(s)) == null) problems.add("missing perk " + t.perkFor(s));
            }
            if (Perks.get(s.id() + ".dual_casting") == null) problems.add("missing dual casting perk for " + s.id());
        }
        for (Shout s : Shout.VALUES) {
            if (Shout.byId(s.id()) != s) problems.add("shout id " + s.id());
            if (Shout.byId(s.phrase(3).replace(' ', '_')) != s) problems.add("shout words alias " + s.phrase(3));
            for (int w = 1; w <= 3; w++) if (s.cooldownSeconds(w) <= 0) problems.add("cooldown " + s.id());
        }
        if (Shout.byId("no_such_shout") != null || Shout.byId(null) != null) problems.add("unknown shout id accepted");
        for (Skill sk : Skill.VALUES) {
            List<Perk> perks = Perks.of(sk);
            if (perks.size() < 5) problems.add("skill " + sk.id() + " has only " + perks.size() + " perks");
            for (Perk p : perks) {
                for (String parent : p.parents()) if (Perks.get(parent) == null) problems.add("perk " + p.id() + " parent " + parent);
            }
        }
        for (SpellEffectType t : SpellEffectType.VALUES) {
            for (String src : t.sources) if (Spells.byId(src) == null) problems.add("effect " + t.id() + " source " + src);
            if (!t.allows(t.defaultRange)) problems.add("effect " + t.id() + " default range");
        }
        // custom spell codec round trip
        CustomSpells.Def def = new CustomSpells.Def("Self Test", List.of(
                new CustomSpells.Effect(SpellEffectType.FIRE_DAMAGE, SpellEffectType.Range.TARGET, 30, 0, 2),
                new CustomSpells.Effect(SpellEffectType.SOUL_TRAP, SpellEffectType.Range.TARGET, 0, 20, 0)));
        Spell made = Spells.byId(def.id());
        if (made == null || !def.equals(CustomSpells.def(def.id())) || made.school != School.DESTRUCTION) problems.add("custom spell codec " + def.id());
        if (CustomSpells.def("sm/Bad/fire_damage:s:30:0:0") != null) problems.add("custom spell accepted self-damage");
        if (CustomSpells.def("sm/Bad/fire_damage:r:9999:0:0") != null) problems.add("custom spell accepted out-of-bounds magnitude");
        for (String kind : List.of("familiar", "flame_atronach", "frost_atronach", "storm_atronach", "dremora_lord", "seeker", "sovngarde_hero")) {
            if (Summons.entityType(kind) == null) problems.add("summon type " + kind);
        }
        if (Places.nearest(PlaceType.CAPITAL, 0, 0, 1 << 20).isEmpty()) problems.add("no capital for Divine Intervention");
        boolean tribunal = Places.all().stream().anyMatch(p -> p.regionId().startsWith("morrowind/"));
        if (!tribunal) problems.add("no Morrowind place for Almsivi Intervention");
        // eating ingredients (bite choice, four-line effect text, the Use/right-click/eat paths) and the HUD corner layout
        problems.addAll(com.skycraft.crafting.arcane.alchemy.AlchemySelfTest.run());
        problems.addAll(com.skycraft.core.HudStack.selfTest());
        problems.addAll(com.skycraft.loot.affix.ItemAffixes.selftest());
        // one hand system: bound weapon vs spell vs item, tome learning, favorites, nothing bound in a save
        problems.addAll(HandsSelfTest.run(player, report));
        int perkCount = 0;
        for (Perk ignored : Perks.all()) perkCount++;
        report.accept(String.format(Locale.ROOT, "%d spells, %d shouts, %d skills, %d perks, %d spellmaking effects", spells,
                Shout.VALUES.length, Skill.VALUES.length, perkCount, SpellEffectType.VALUES.length));
        for (String p : problems) report.accept("PROBLEM: " + p);
        report.accept(problems.isEmpty() ? "selftest OK" : "selftest FAILED: " + problems.size() + " problems");
        return problems.size();
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        int problems = runSelftest(line -> {
            out(src, line);
            Skycraft.LOGGER.info("[magic selftest] {}", line);
        }, src.getPlayer());
        return problems == 0 ? 1 : 0;
    }

    private static int learnAll(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        int n = 0;
        for (Spell s : Spells.all()) if (MagicData.learn(p, s.id)) n++;
        for (Shout s : Shout.VALUES) {
            MagicData.setWordsLearned(p, s, 3);
            MagicData.setWordsUnlocked(p, s, 3);
        }
        final int learned = n;
        c.getSource().sendSuccess(() -> Component.literal("Learned " + learned + " spells and every word of power"), true);
        return n;
    }
}
