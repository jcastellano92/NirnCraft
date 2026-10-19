package com.nirncraft.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.nirncraft.NirnCraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Build-time bake of data that is deterministic for the shipped map: loaded from {@code nirncraft/baked/<name>.bin}
 * when its recorded input hash equals the live one, computed live otherwise (never a stale bake).
 *
 * <p>The input hash covers every map data file ({@code nirncraft/tamriel/*.png|json}, the places files) and the
 * bytecode of the classes that compute the baked data, so changing the map, the places or the algorithm invalidates
 * the bake by itself. Nothing baked depends on the world seed (the map is the same for every seed), so any seed uses
 * the bake. Regenerate with {@code tools/bake/bake.ps1} or {@code /nirncraft-bake write}; check with
 * {@code /nirncraft-bake check} or {@code qa.ps1 -Bake}.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Bake {
    /** Bump to drop every existing bake. */
    private static final int FORMAT = 1;
    private static final String DIR = "/nirncraft/baked/";
    private static final String TAMRIEL = "/nirncraft/tamriel/";
    private static final String[] MAP_FILES = {"regions.png", "elevation.png", "features.png", "terrain.png", "water.png",
            "coast.png", "hydrology.json", "peaks.json", "regions.json", "palettes.json", "ports.json"};
    /** Code that computes baked data; a change to any of them invalidates the bake. */
    private static final String[] CLASSES = {"com.nirncraft.roads.Landmass", "com.nirncraft.tamriel.TamrielMap",
            "com.nirncraft.tamriel.Places", "com.nirncraft.tamriel.Place", "com.nirncraft.tamriel.terrain.MapNoise",
            "com.nirncraft.tamriel.terrain.MapRaster", "com.nirncraft.tamriel.terrain.Beyond",
            "com.nirncraft.tamriel.terrain.Hydrology",
            // the road plan (worldplan) and what it reads: the road graph, gates, corridors, settlement plans
            "com.nirncraft.worldplan.RoadPlan", "com.nirncraft.worldplan.RoadPlan$Builder", "com.nirncraft.worldplan.RoadPlan$Hit",
            "com.nirncraft.worldplan.RoadPlanBuilder", "com.nirncraft.worldplan.RoadPlanBuilder$Run",
            "com.nirncraft.worldplan.RoadPlanBuilder$Poly", "com.nirncraft.worldplan.RoadPlanBuilder$Seg",
            "com.nirncraft.worldplan.RoadPlanBuilder$Task", "com.nirncraft.worldplan.PlanRouter",
            "com.nirncraft.worldplan.PlanRouter$Grid", "com.nirncraft.worldplan.PlanRouter$Cell", "com.nirncraft.worldplan.PlanRouter$Job",
            "com.nirncraft.worldplan.PlanTerrain", "com.nirncraft.worldplan.PlanTerrain$Col", "com.nirncraft.worldplan.Profile",
            "com.nirncraft.roads.PlanLinks", "com.nirncraft.roads.RoadGraph", "com.nirncraft.roads.RoadGraph$Links",
            "com.nirncraft.roads.GateEnds", "com.nirncraft.roads.Corridors", "com.nirncraft.tamriel.settlement.SettlementAnchors",
            "com.nirncraft.tamriel.settlement.plan.Cities", "com.nirncraft.tamriel.settlement.grammar.Zoning",
            // the scene plan (P6) and its builder
            "com.nirncraft.worldplan.scene.ScenePlan", "com.nirncraft.worldplan.scene.ScenePlanBuilder",
            "com.nirncraft.worldplan.scene.ScenePlanBuilder$Run", "com.nirncraft.worldplan.scene.ScenePlanBuilder$Layout",
            "com.nirncraft.worldplan.scene.ScenePlanBuilder$SceneB", "com.nirncraft.worldplan.scene.ScenePlanBuilder$Table",
            "com.nirncraft.worldplan.scene.ScenePlanBuilder$KindInfo", "com.nirncraft.worldplan.scene.ScenePlanBuilder$LmKind",
            "com.nirncraft.worldplan.scene.SceneKinds",
            // the street plan (P5): the settlement planner and the builders whose doors it records
            "com.nirncraft.worldplan.street.StreetPlan", "com.nirncraft.worldplan.street.StreetPlanBuilder",
            "com.nirncraft.worldplan.street.StreetPlanBuilder$Resolver", "com.nirncraft.worldplan.street.StreetPlanBuilder$Line",
            "com.nirncraft.worldplan.street.StreetPlanBuilder$Result", "com.nirncraft.worldplan.street.StreetTerrain",
            "com.nirncraft.worldplan.PlanGround", "com.nirncraft.tamriel.settlement.plan.Plan", "com.nirncraft.tamriel.settlement.plan.Spec",
            "com.nirncraft.tamriel.settlement.plan.Terrain", "com.nirncraft.tamriel.settlement.plan.Layouts",
            "com.nirncraft.tamriel.settlement.plan.CityPlan", "com.nirncraft.tamriel.settlement.plan.CityPlans",
            "com.nirncraft.tamriel.settlement.plan.Nets", "com.nirncraft.tamriel.settlement.plan.Halls",
            "com.nirncraft.tamriel.settlement.plan.HousePlots", "com.nirncraft.tamriel.settlement.plan.Archetype",
            "com.nirncraft.tamriel.settlement.grammar.Fabric", "com.nirncraft.tamriel.settlement.grammar.Features",
            "com.nirncraft.tamriel.settlement.grammar.Overlays", "com.nirncraft.tamriel.settlement.grammar.Grammar",
            "com.nirncraft.tamriel.settlement.grammar.Blueprint", "com.nirncraft.tamriel.settlement.grammar.Programs",
            "com.nirncraft.tamriel.settlement.grammar.Outdoors", "com.nirncraft.tamriel.settlement.grammar.Civic",
            "com.nirncraft.tamriel.settlement.grammar.Style", "com.nirncraft.tamriel.settlement.grammar.Styles",
            "com.nirncraft.tamriel.settlement.grammar.Tensor", "com.nirncraft.tamriel.settlement.grammar.Metrics",
            "com.nirncraft.tamriel.settlement.build.Builders", "com.nirncraft.tamriel.settlement.build.Shells",
            "com.nirncraft.tamriel.settlement.build.Works", "com.nirncraft.tamriel.settlement.build.Landmarks",
            "com.nirncraft.tamriel.settlement.build.GuildHalls", "com.nirncraft.tamriel.settlement.build.Jails",
            "com.nirncraft.tamriel.settlement.build.Canvas", "com.nirncraft.tamriel.settlement.kit.Kits",
            "com.nirncraft.tamriel.settlement.SettlementPiece"};
        /** Other data files the baked data reads. */
    private static final String[] DATA_FILES = {"/nirncraft/roads/named_roads.json", "/nirncraft/roads/corridors.json", "/nirncraft/scatter/kinds.json",
            "/nirncraft/scatter/rules.json", "/nirncraft/scatter/loreweb_clues.json", "/nirncraft/landmarks/kinds.json",
            "/nirncraft/worldplan/built_places.json",
            "/nirncraft/tamriel/styles/akaviri.json", "/nirncraft/tamriel/styles/altmer.json", "/nirncraft/tamriel/styles/argonian.json",
            "/nirncraft/tamriel/styles/atmoran.json", "/nirncraft/tamriel/styles/bosmer.json", "/nirncraft/tamriel/styles/breton.json",
            "/nirncraft/tamriel/styles/dunmer.json", "/nirncraft/tamriel/styles/imperial.json", "/nirncraft/tamriel/styles/khajiit.json",
            "/nirncraft/tamriel/styles/maormer.json", "/nirncraft/tamriel/styles/nord.json", "/nirncraft/tamriel/styles/orc.json",
            "/nirncraft/tamriel/styles/redguard.json", "/nirncraft/tamriel/styles/yokudan.json"};
    /** Everything baked, in order (the street and scene plans read the road plan). */
    private static final List<Bakeable> BAKES = List.of(
            new Bakeable("landmass", com.nirncraft.roads.Landmass::computeLive),
            new Bakeable(com.nirncraft.worldplan.RoadPlanBuilder.BAKE_NAME, com.nirncraft.worldplan.RoadPlanBuilder::computeLive),
            new Bakeable(com.nirncraft.worldplan.street.StreetPlanBuilder.BAKE_NAME, com.nirncraft.worldplan.street.StreetPlanBuilder::computeLive),
            new Bakeable(com.nirncraft.worldplan.scene.ScenePlanBuilder.BAKE_NAME, com.nirncraft.worldplan.scene.ScenePlanBuilder::computeLive));

    private static volatile String inputs;

    private Bake() {}

    /** Development JSON reloads change bake inputs; never keep the old digest. */
    public static void invalidateInputs() {
        inputs = null;
    }

    /** Hex SHA-256 of all inputs (cached). */
    public static String inputsHash() {
        String h = inputs;
        if (h != null) return h;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("format" + FORMAT).getBytes(StandardCharsets.UTF_8));
            for (String f : MAP_FILES) feed(md, TAMRIEL + f);
            byte[] index = bytes(TAMRIEL + "places/index.json");
            if (index != null) {
                md.update(index);
                JsonArray names = JsonParser.parseString(new String(index, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement e : names) feed(md, TAMRIEL + "places/" + e.getAsString());
            }
            for (String f : DATA_FILES) feed(md, f);
            // the authored city plans (the street plan's input)
            byte[] plans = bytes(TAMRIEL + "cityplans/index.json");
            if (plans != null) {
                md.update(plans);
                JsonElement list = JsonParser.parseString(new String(plans, StandardCharsets.UTF_8)).getAsJsonObject().get("plans");
                if (list != null) for (JsonElement e : list.getAsJsonArray()) feed(md, TAMRIEL + "cityplans/" + e.getAsString() + ".json");
            }
            for (String c : CLASSES) feed(md, "/" + c.replace('.', '/') + ".class");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return inputs = sb.toString();
        } catch (Exception e) {
            return inputs = "unhashable-" + System.nanoTime();
        }
    }

    private static void feed(MessageDigest md, String path) {
        md.update(path.getBytes(StandardCharsets.UTF_8));
        byte[] b = bytes(path);
        if (b != null) md.update(b);
    }

    private static byte[] bytes(String path) {
        try (InputStream in = com.nirncraft.dev.DevResources.open(Bake.class, path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** The payload of a baked file when it exists and matches the live inputs, else null (caller computes live). */
    public static byte[] load(String name) {
        return decode(bytes(DIR + name + ".bin"), name);
    }

    private static byte[] decode(byte[] raw, String name) {
        if (raw == null) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            String h = in.readUTF();
            if (!h.equals(inputsHash())) {
                NirnCraft.LOGGER.info("[bake] {} is stale (inputs changed); computing live", name);
                return null;
            }
            try (InflaterInputStream z = new InflaterInputStream(in)) {
                byte[] out = z.readAllBytes();
                NirnCraft.LOGGER.info("[bake] loaded {} ({} KB)", name, out.length / 1024);
                return out;
            }
        } catch (IOException | RuntimeException e) {
            NirnCraft.LOGGER.warn("[bake] {} is unreadable; computing live", name, e);
            return null;
        }
    }

    /** Writes {@code name.bin} (hash header + deflated payload) into {@code dir}. */
    public static void write(Path dir, String name, byte[] payload) throws IOException {
        Files.createDirectories(dir);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeUTF(inputsHash());
            try (DeflaterOutputStream z = new DeflaterOutputStream(out)) {
                z.write(payload);
            }
        }
        Files.write(dir.resolve(name + ".bin"), bo.toByteArray());
    }

    /** Bakes everything into {@code dir} and writes {@code manifest.json}. Returns a one-line summary. */
    public static String bakeAll(Path dir) throws IOException {
        StringBuilder files = new StringBuilder(), msg = new StringBuilder("baked");
        for (Bakeable b : BAKES) {
            long t0 = System.nanoTime();
            byte[] data = b.compute().run();
            write(dir, b.name(), data);
            files.append(files.length() == 0 ? "" : ", ").append('"').append(b.name()).append(".bin\"");
            msg.append(' ').append(b.name()).append(" (").append(data.length / 1024).append(" KB raw, sha ")
                    .append(sha(data), 0, 12).append(", ").append((System.nanoTime() - t0) / 1_000_000).append(" ms)");
        }
        Files.writeString(dir.resolve("manifest.json"),
                "{\n  \"format\": " + FORMAT + ",\n  \"inputs\": \"" + inputsHash() + "\",\n  \"files\": [" + files + "]\n}\n");
        return msg + " into " + dir.toAbsolutePath();
    }

    /** Hex SHA-256 of a payload (the content hash logged by bake and check). */
    public static String sha(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "unhashable";
        }
    }

    /** Checks the shipped bake against the live inputs and a fresh computation. Returns problems (empty = ok). */
    public static List<String> check() {
        List<String> bad = new java.util.ArrayList<>();
        byte[] manifest = bytes(DIR + "manifest.json");
        if (manifest == null) {
            bad.add("no baked data in the resources (run tools/bake/bake.ps1)");
            return bad;
        }
        if (!new String(manifest, StandardCharsets.UTF_8).contains(inputsHash())) bad.add("manifest is stale: inputs changed");
        for (Bakeable b : BAKES) {
            byte[] baked = load(b.name());
            if (baked == null) {
                bad.add(b.name() + ".bin missing or stale");
                continue;
            }
            try {
                long t0 = System.nanoTime();
                byte[] live = b.compute().run();
                if (!java.util.Arrays.equals(baked, live)) bad.add(b.name() + ".bin differs from live computation");
                else NirnCraft.LOGGER.info("[bake] {} matches a live computation (sha {}, {} ms)", b.name(), sha(live).substring(0, 12),
                        (System.nanoTime() - t0) / 1_000_000);
            } catch (IOException | RuntimeException e) {
                bad.add("could not recompute " + b.name() + ": " + e);
            }
        }
        return bad;
    }

    /**
     * One-boot QA (qa.ps1 -Rebake): when {@link #check()} finds the shipped bake stale or missing, bakes into
     * {@code dir} and verifies the written files against a second live computation (what bake.ps1's
     * {@code bake,verify} did across a separate boot). Returns null when the shipped bake is up to date, else the
     * problems of the rebake (empty list = rebaked and verified; the caller copies {@code dir} into the resources).
     */
    public static List<String> refresh(Path dir) {
        if (check().isEmpty()) return null;
        List<String> bad = new java.util.ArrayList<>();
        try {
            NirnCraft.LOGGER.info("[bake] {}", bakeAll(dir));
            for (Bakeable b : BAKES) {
                byte[] written = decode(Files.readAllBytes(dir.resolve(b.name() + ".bin")), b.name());
                if (written == null) {
                    bad.add(b.name() + ".bin unreadable after the rebake");
                } else {
                    byte[] repeated=b.compute().run();
                    if (!java.util.Arrays.equals(written,repeated)) {
                        Files.write(dir.resolve(b.name()+".verify-mismatch.raw"),repeated);
                        bad.add(b.name() + ".bin differs from a second live computation (non-deterministic bake)");
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            bad.add("rebake failed: " + e);
        }
        return bad;
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirncraft-bake").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER))
                .then(Commands.literal("refresh").executes(c -> {
                    List<String> bad = refresh(Path.of("nirncraft-bake"));
                    String msg = bad == null ? "Bake is up to date." : bad.isEmpty()
                            ? "Bake was stale: rebaked and verified into " + Path.of("nirncraft-bake").toAbsolutePath()
                            : "Bake problems: " + bad;
                    NirnCraft.LOGGER.info("[bake] {}", msg);
                    c.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return bad == null || bad.isEmpty() ? 1 : 0;
                }))
                .then(Commands.literal("check").executes(c -> {
                    List<String> bad = check();
                    c.getSource().sendSuccess(() -> Component.literal(bad.isEmpty() ? "Bake is up to date." : "Bake problems: " + bad), false);
                    return bad.isEmpty() ? 1 : 0;
                }))
                .then(Commands.literal("write").executes(c -> {
                    try {
                        String msg = bakeAll(Path.of("nirncraft-bake"));
                        c.getSource().sendSuccess(() -> Component.literal(msg + "; copy it to mod/src/main/resources/nirncraft/baked/"), false);
                        return 1;
                    } catch (IOException e) {
                        c.getSource().sendFailure(Component.literal("Bake failed: " + e));
                        return 0;
                    }
                })));
    }
}
