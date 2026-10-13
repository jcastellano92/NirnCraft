package com.skycraft.landmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code /skycraft-landmarks} (op 2): debug and QA.
 * <ul>
 *     <li>{@code list [tier]}: the kind table, and how many kinds each biome family has per tier;</li>
 *     <li>{@code near [radius]}: the sites around you;</li>
 *     <li>{@code stats [radius]} / {@code statsat <x> <z> <half>}: counts, density per km2, distinct kinds and variants;</li>
 *     <li>{@code pinned}: the pinned XL sites and where they stand;</li>
 *     <li>{@code groundcheck <x> <z> <half>}: the floating census of the area (landmarks, minor sites, scene props, control);

 *     {@code grounding on|off} switches the foundation and support pass for before/after runs;</li>
 *     <li>{@code build <kind> [x z]}: builds a kind where you stand (or at x z);</li>
 *     <li>{@code showcase <tier> <family|auto> <x> <z>}: builds every kind of a tier in a grid;</li>
 *     <li>{@code sample <x> <z> <half> <count> <name>}: generates real sites of an area and writes a list for the renderer.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class LandmarkCommands {
    private static final List<String> FAMILIES = List.of("auto", "tundra", "forest", "plains", "marsh", "desert", "savanna", "ash",
            "volcanic", "jungle", "crystal", "mushroom", "mountain");

    private LandmarkCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("skycraft-landmarks").requires(s -> s.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(c -> list(c, null))
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("S", "M", "L", "XL"), b))
                                .executes(c -> list(c, Tier.byId(StringArgumentType.getString(c, "tier"))))))
                .then(Commands.literal("near")
                        .executes(c -> near(c, 600))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 6000)).executes(c -> near(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("stats")
                        .executes(c -> stats(c, (int) c.getSource().getPosition().x, (int) c.getSource().getPosition().z, 1500))
                        .then(Commands.argument("half", IntegerArgumentType.integer(64, 8000)).executes(c -> stats(c,
                                (int) c.getSource().getPosition().x, (int) c.getSource().getPosition().z, IntegerArgumentType.getInteger(c, "half")))))
                .then(Commands.literal("statsat")
                        .then(Commands.argument("x", IntegerArgumentType.integer(-60000, 60000))
                                .then(Commands.argument("z", IntegerArgumentType.integer(-60000, 60000))
                                        .then(Commands.argument("half", IntegerArgumentType.integer(64, 8000)).executes(c -> stats(c,
                                                IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"),
                                                IntegerArgumentType.getInteger(c, "half")))))))
                .then(Commands.literal("pinned").executes(LandmarkCommands::pinned))
                .then(Commands.literal("grounding")
                        .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> {
                            Grounding.enabled = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "on");
                            say(c, "landmark grounding " + (Grounding.enabled ? "ON" : "OFF (legacy five-point settle, no foundations)"));
                            return 1;
                        })))
                .then(Commands.literal("showcheck")
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer(-60000, 60000))
                                        .then(Commands.argument("z", IntegerArgumentType.integer(-60000, 60000))
                                                .executes(c -> {
                                                    Tier t = Tier.byId(StringArgumentType.getString(c, "tier"));
                                                    if (t == null) {
                                                        c.getSource().sendFailure(Component.literal("Unknown tier"));
                                                        return 0;
                                                    }
                                                    return GroundCheck.showcase(c.getSource().getLevel(), t, IntegerArgumentType.getInteger(c, "x"),
                                                            IntegerArgumentType.getInteger(c, "z"), line -> say(c, line));
                                                })))))
                .then(Commands.literal("groundcheck")
                        .then(Commands.argument("x", IntegerArgumentType.integer(-60000, 60000))
                                .then(Commands.argument("z", IntegerArgumentType.integer(-60000, 60000))
                                        .then(Commands.argument("half", IntegerArgumentType.integer(64, 4000))
                                                .executes(c -> GroundCheck.run(c.getSource().getLevel(), IntegerArgumentType.getInteger(c, "x"),
                                                        IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "half"),
                                                        line -> say(c, line)))))))
                .then(Commands.literal("build")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Kinds.all().stream().map(Kind::id), b))
                                .executes(c -> build(c, StringArgumentType.getString(c, "kind"), (int) c.getSource().getPosition().x,
                                        (int) c.getSource().getPosition().z))
                                .then(Commands.argument("x", IntegerArgumentType.integer(-60000, 60000))
                                        .then(Commands.argument("z", IntegerArgumentType.integer(-60000, 60000))
                                                .executes(c -> build(c, StringArgumentType.getString(c, "kind"),
                                                        IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")))))))
                .then(Commands.literal("showcase")
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .then(Commands.argument("family", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(FAMILIES, b))
                                        .then(Commands.argument("x", IntegerArgumentType.integer(-60000, 60000))
                                                .then(Commands.argument("z", IntegerArgumentType.integer(-60000, 60000))
                                                        .executes(c -> showcase(c, StringArgumentType.getString(c, "tier"),
                                                                StringArgumentType.getString(c, "family"), IntegerArgumentType.getInteger(c, "x"),
                                                                IntegerArgumentType.getInteger(c, "z"))))))))
                .then(Commands.literal("sample")
                        .then(Commands.argument("x", IntegerArgumentType.integer(-60000, 60000))
                                .then(Commands.argument("z", IntegerArgumentType.integer(-60000, 60000))
                                        .then(Commands.argument("half", IntegerArgumentType.integer(64, 8000))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                        .then(Commands.argument("name", StringArgumentType.word())
                                                                .executes(c -> sample(c, IntegerArgumentType.getInteger(c, "x"),
                                                                        IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "half"),
                                                                        IntegerArgumentType.getInteger(c, "count"), StringArgumentType.getString(c, "name"))))))))));
    }

    private static void say(CommandContext<CommandSourceStack> c, String line) {
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        Skycraft.LOGGER.info("[landmarks] {}", line);
    }

    // ------------------------------------------------------------------ list

    private static int list(CommandContext<CommandSourceStack> c, Tier only) {
        List<Kind> kinds = Kinds.all();
        Map<Tier, Integer> perTier = new LinkedHashMap<>();
        for (Tier t : Tier.VALUES) perTier.put(t, 0);
        for (Kind k : kinds) perTier.merge(k.tier(), 1, Integer::sum);
        say(c, kinds.size() + " kinds: S " + perTier.get(Tier.S) + ", M " + perTier.get(Tier.M) + ", L " + perTier.get(Tier.L)
                + ", XL " + perTier.get(Tier.XL));
        // kinds per family per tier (a kind counts for a family when it has weight there)
        for (String fam : FAMILIES.subList(1, FAMILIES.size()).stream().toList()) {
            StringBuilder sb = new StringBuilder("  " + fam + ":");
            for (Tier t : Tier.VALUES) {
                int n = 0;
                for (Kind k : Kinds.of(t)) if (k.weights().getOrDefault(fam, 0.0) > 0) n++;
                sb.append(' ').append(t.id).append('=').append(n);
            }
            say(c, sb.toString());
        }
        if (only != null) {
            for (Kind k : Kinds.of(only)) {
                say(c, String.format(Locale.ROOT, "  %s (%s/%s r%d) %s", k.id(), k.builder(), k.style(), k.radius(), k.weights()));
            }
        }
        return kinds.size();
    }

    // ------------------------------------------------------------------ near

    private static int near(CommandContext<CommandSourceStack> c, int radius) {
        Vec3 p = c.getSource().getPosition();
        long seed = c.getSource().getLevel().getSeed();
        List<LandmarkSite> sites = Landmarks.within(seed, (int) p.x, (int) p.z, radius);
        say(c, sites.size() + " landmarks within " + radius + " blocks");
        for (LandmarkSite s : sites.subList(0, Math.min(25, sites.size()))) {
            int d = (int) Math.sqrt(s.distSqr((int) p.x, (int) p.z));
            say(c, String.format(Locale.ROOT, "  [%s] %s \"%s\" at %d %d (%d blocks, %s)", s.tier().id, s.kind().id(), s.name(), s.x(), s.z(), d, s.family()));
        }
        return sites.size();
    }

    // ------------------------------------------------------------------ stats

    private static int stats(CommandContext<CommandSourceStack> c, int x, int z, int half) {
        long seed = c.getSource().getLevel().getSeed();
        List<LandmarkSite> sites = Landmarks.inRect(seed, x - half, z - half, x + half, z + half);
        double km2 = (2.0 * half / 1000.0) * (2.0 * half / 1000.0);
        say(c, String.format(Locale.ROOT, "area %d,%d +-%d (%.1f km2): %d sites", x, z, half, km2, sites.size()));
        Map<Tier, List<LandmarkSite>> byTier = new LinkedHashMap<>();
        for (Tier t : Tier.VALUES) byTier.put(t, new ArrayList<>());
        for (LandmarkSite s : sites) byTier.get(s.tier()).add(s);
        for (Tier t : Tier.VALUES) {
            List<LandmarkSite> l = byTier.get(t);
            Set<String> kinds = new HashSet<>();
            Set<String> variants = new HashSet<>();
            for (LandmarkSite s : l) {
                kinds.add(s.kind().id());
                variants.add(Landmarks.variantKey(s));
            }
            say(c, String.format(Locale.ROOT, "  %-2s: %4d sites (%.1f per km2), %d kinds, %d distinct variants (%.0f per 100 sites)",
                    t.id, l.size(), l.size() / km2, kinds.size(), variants.size(), l.isEmpty() ? 0.0 : variants.size() * 100.0 / l.size()));
        }
        Map<String, Integer> counts = new TreeMap<>(Landmarks.countsByKind(sites));
        StringBuilder sb = new StringBuilder("  kinds:");
        for (var e : counts.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        say(c, sb.toString());
        return sites.size();
    }

    // ------------------------------------------------------------------ pinned

    private static int pinned(CommandContext<CommandSourceStack> c) {
        List<LandmarkSite> sites = Landmarks.pinnedSites(c.getSource().getLevel().getSeed());
        for (LandmarkSite s : sites) {
            say(c, String.format(Locale.ROOT, "  %s: %s \"%s\" at %d %d (%s)", s.pinned(), s.kind().id(), s.name(), s.x(), s.z(), s.regionId()));
        }
        say(c, sites.size() + " pinned XL sites");
        return sites.size();
    }

    // ------------------------------------------------------------------ build and showcase

    private static void load(ServerLevel level, int cx, int cz) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk(cx + dx, cz + dz);
    }

    private static int build(CommandContext<CommandSourceStack> c, String kindId, int x, int z) {
        Kind kind = Kinds.byId(kindId);
        if (kind == null) {
            c.getSource().sendFailure(Component.literal("Unknown kind " + kindId));
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        LandmarkSite site = Landmarks.custom(level.getSeed(), kind, x, z, System.nanoTime());
        load(level, site.chunkX(), site.chunkZ());
        boolean ok = LandmarkBuilder.build(level, site, true, null);
        say(c, (ok ? "built " : "failed to build ") + kind.id() + " \"" + site.name() + "\" at " + site.x() + " " + site.z());
        return ok ? 1 : 0;
    }

    private static int showcase(CommandContext<CommandSourceStack> c, String tierId, String family, int x0, int z0) {
        Tier tier = Tier.byId(tierId);
        if (tier == null) {
            c.getSource().sendFailure(Component.literal("Unknown tier " + tierId));
            return 0;
        }
        String fam = family.equals("auto") ? null : family;
        ServerLevel level = c.getSource().getLevel();
        List<Kind> kinds = new ArrayList<>(Kinds.of(tier));
        int cols = (int) Math.ceil(Math.sqrt(kinds.size()));
        int step = tier == Tier.S ? 48 : 64;
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            Kind k = kinds.get(i);
            int x = x0 + (i % cols) * step, z = z0 + (i / cols) * step;
            LandmarkSite site = Landmarks.custom(level.getSeed(), k, x, z, i + 1);
            load(level, site.chunkX(), site.chunkZ());
            boolean built = false;
            try {
                built = LandmarkBuilder.build(level, site, true, fam);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft landmarks showcase: {} threw", k.id(), e);
            }
            if (built) ok++;
            else failed.add(k.id());
            say(c, String.format(Locale.ROOT, "  %s %s at %d %d", built ? "ok  " : "FAIL", k.id(), site.x(), site.z()));
        }
        say(c, "showcase " + tier.id + " (" + (fam == null ? "auto" : fam) + "): " + ok + "/" + kinds.size() + " built"
                + (failed.isEmpty() ? "" : ", failed: " + failed));
        return ok;
    }

    // ------------------------------------------------------------------ sample

    private static int sample(CommandContext<CommandSourceStack> c, int x, int z, int half, int count, String name) {
        ServerLevel level = c.getSource().getLevel();
        long seed = level.getSeed();
        List<LandmarkSite> all = Landmarks.inRect(seed, x - half, z - half, x + half, z + half);
        // a spread of tiers and kinds: round robin over tiers, taking unseen kinds first
        List<LandmarkSite> picked = new ArrayList<>();
        Set<String> seenKinds = new HashSet<>();
        Tier[] order = {Tier.XL, Tier.L, Tier.M, Tier.S, Tier.L, Tier.M, Tier.S, Tier.L, Tier.M, Tier.S};
        Set<Long> used = new HashSet<>();
        for (int round = 0; round < 6 && picked.size() < count; round++) {
            for (Tier t : order) {
                if (picked.size() >= count) break;
                LandmarkSite best = null;
                for (LandmarkSite s : all) {
                    if (s.tier() != t || used.contains(s.key())) continue;
                    if (round < 3 && seenKinds.contains(s.kind().id())) continue;
                    best = s;
                    break;
                }
                if (best != null) {
                    picked.add(best);
                    used.add(best.key());
                    seenKinds.add(best.kind().id());
                }
            }
        }
        JsonArray arr = new JsonArray();
        int built = 0;
        for (LandmarkSite s : picked) {
            load(level, s.chunkX(), s.chunkZ());
            boolean ok = LandmarkEvents.built(level, s);
            if (ok) built++;
            JsonObject o = new JsonObject();
            o.addProperty("tier", s.tier().id);
            o.addProperty("kind", s.kind().id());
            o.addProperty("name", s.name());
            o.addProperty("x", s.x());
            o.addProperty("z", s.z());
            o.addProperty("radius", s.kind().radius());
            o.addProperty("family", s.family());
            o.addProperty("region", s.regionId());
            o.addProperty("built", ok);
            arr.add(o);
            say(c, String.format(Locale.ROOT, "  [%s] %s \"%s\" at %d %d %s", s.tier().id, s.kind().id(), s.name(), s.x(), s.z(),
                    ok ? "built" : "REFUSED"));
        }
        try {
            Path dir = c.getSource().getServer().getServerDirectory().toPath().resolve("skycraft-landmarks");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("sample_" + name + ".json"), arr.toString(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            Skycraft.LOGGER.warn("Skycraft landmarks: could not write the sample list", e);
        }
        say(c, "sample " + name + ": " + built + "/" + picked.size() + " built");
        return built;
    }
}
