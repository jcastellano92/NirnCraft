package com.skycraft.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
 * Build-time bake of data that is deterministic for the shipped map: loaded from {@code skycraft/baked/<name>.bin}
 * when its recorded input hash equals the live one, computed live otherwise (never a stale bake).
 *
 * <p>The input hash covers every map data file ({@code skycraft/tamriel/*.png|json}, the places files) and the
 * bytecode of the classes that compute the baked data, so changing the map, the places or the algorithm invalidates
 * the bake by itself. Nothing baked depends on the world seed (the map is the same for every seed), so any seed uses
 * the bake. Regenerate with {@code tools/bake/bake.ps1} or {@code /skycraft-bake write}; check with
 * {@code /skycraft-bake check} or {@code qa.ps1 -Bake}.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Bake {
    /** Bump to drop every existing bake. */
    private static final int FORMAT = 1;
    private static final String DIR = "/skycraft/baked/";
    private static final String TAMRIEL = "/skycraft/tamriel/";
    private static final String[] MAP_FILES = {"regions.png", "elevation.png", "features.png", "terrain.png", "water.png",
            "coast.png", "hydrology.json", "peaks.json", "regions.json", "palettes.json", "ports.json"};
    /** Code that computes baked data; a change to any of them invalidates the bake. */
    private static final String[] CLASSES = {"com.skycraft.roads.Landmass", "com.skycraft.tamriel.TamrielMap",
            "com.skycraft.tamriel.Places", "com.skycraft.tamriel.Place", "com.skycraft.tamriel.terrain.MapNoise",
            "com.skycraft.tamriel.terrain.MapRaster", "com.skycraft.tamriel.terrain.Beyond",
            "com.skycraft.tamriel.terrain.Hydrology",
            // the road plan (worldplan) and what it reads: the road graph, gates, corridors, settlement plans
            "com.skycraft.worldplan.RoadPlan", "com.skycraft.worldplan.RoadPlan$Builder", "com.skycraft.worldplan.RoadPlan$Hit",
            "com.skycraft.worldplan.RoadPlanBuilder", "com.skycraft.worldplan.RoadPlanBuilder$Run",
            "com.skycraft.worldplan.RoadPlanBuilder$Poly", "com.skycraft.worldplan.RoadPlanBuilder$Seg",
            "com.skycraft.worldplan.RoadPlanBuilder$Task", "com.skycraft.worldplan.PlanRouter",
            "com.skycraft.worldplan.PlanRouter$Grid", "com.skycraft.worldplan.PlanRouter$Cell", "com.skycraft.worldplan.PlanRouter$Job",
            "com.skycraft.worldplan.PlanTerrain", "com.skycraft.worldplan.PlanTerrain$Col", "com.skycraft.worldplan.Profile",
            "com.skycraft.roads.PlanLinks", "com.skycraft.roads.RoadGraph", "com.skycraft.roads.RoadGraph$Links",
            "com.skycraft.roads.GateEnds", "com.skycraft.roads.Corridors", "com.skycraft.tamriel.settlement.SettlementAnchors",
            "com.skycraft.tamriel.settlement.plan.Cities", "com.skycraft.tamriel.settlement.grammar.Zoning",
            // the scene plan (P6) and its builder
            "com.skycraft.worldplan.scene.ScenePlan", "com.skycraft.worldplan.scene.ScenePlanBuilder",
            "com.skycraft.worldplan.scene.ScenePlanBuilder$Run", "com.skycraft.worldplan.scene.ScenePlanBuilder$Layout",
            "com.skycraft.worldplan.scene.ScenePlanBuilder$SceneB", "com.skycraft.worldplan.scene.ScenePlanBuilder$Table",
            "com.skycraft.worldplan.scene.ScenePlanBuilder$KindInfo", "com.skycraft.worldplan.scene.ScenePlanBuilder$LmKind",
            "com.skycraft.worldplan.scene.SceneKinds",
            // the street plan (P5): the settlement planner and the builders whose doors it records
            "com.skycraft.worldplan.street.StreetPlan", "com.skycraft.worldplan.street.StreetPlanBuilder",
            "com.skycraft.worldplan.street.StreetPlanBuilder$Resolver", "com.skycraft.worldplan.street.StreetPlanBuilder$Line",
            "com.skycraft.worldplan.street.StreetPlanBuilder$Result", "com.skycraft.worldplan.street.StreetTerrain",
            "com.skycraft.worldplan.PlanGround", "com.skycraft.tamriel.settlement.plan.Plan", "com.skycraft.tamriel.settlement.plan.Spec",
            "com.skycraft.tamriel.settlement.plan.Terrain", "com.skycraft.tamriel.settlement.plan.Layouts",
            "com.skycraft.tamriel.settlement.plan.CityPlan", "com.skycraft.tamriel.settlement.plan.CityPlans",
            "com.skycraft.tamriel.settlement.plan.Nets", "com.skycraft.tamriel.settlement.plan.Halls",
            "com.skycraft.tamriel.settlement.plan.HousePlots", "com.skycraft.tamriel.settlement.plan.Archetype",
            "com.skycraft.tamriel.settlement.grammar.Fabric", "com.skycraft.tamriel.settlement.grammar.Features",
            "com.skycraft.tamriel.settlement.grammar.Overlays", "com.skycraft.tamriel.settlement.grammar.Grammar",
            "com.skycraft.tamriel.settlement.grammar.Blueprint", "com.skycraft.tamriel.settlement.grammar.Programs",
            "com.skycraft.tamriel.settlement.grammar.Outdoors", "com.skycraft.tamriel.settlement.grammar.Civic",
            "com.skycraft.tamriel.settlement.grammar.Style", "com.skycraft.tamriel.settlement.grammar.Styles",
            "com.skycraft.tamriel.settlement.grammar.Tensor", "com.skycraft.tamriel.settlement.grammar.Metrics",
            "com.skycraft.tamriel.settlement.build.Builders", "com.skycraft.tamriel.settlement.build.Shells",
            "com.skycraft.tamriel.settlement.build.Works", "com.skycraft.tamriel.settlement.build.Landmarks",
            "com.skycraft.tamriel.settlement.build.GuildHalls", "com.skycraft.tamriel.settlement.build.Jails",
            "com.skycraft.tamriel.settlement.build.Canvas", "com.skycraft.tamriel.settlement.kit.Kits",
            "com.skycraft.tamriel.settlement.SettlementPiece"};
        /** Other data files the baked data reads. */
    private static final String[] DATA_FILES = {"/skycraft/roads/named_roads.json", "/skycraft/roads/corridors.json", "/skycraft/scatter/kinds.json",
            "/skycraft/scatter/rules.json", "/skycraft/scatter/loreweb_clues.json", "/skycraft/landmarks/kinds.json",
            "/skycraft/tamriel/styles/akaviri.json", "/skycraft/tamriel/styles/altmer.json", "/skycraft/tamriel/styles/argonian.json",
            "/skycraft/tamriel/styles/atmoran.json", "/skycraft/tamriel/styles/bosmer.json", "/skycraft/tamriel/styles/breton.json",
            "/skycraft/tamriel/styles/dunmer.json", "/skycraft/tamriel/styles/imperial.json", "/skycraft/tamriel/styles/khajiit.json",
            "/skycraft/tamriel/styles/maormer.json", "/skycraft/tamriel/styles/nord.json", "/skycraft/tamriel/styles/orc.json",
            "/skycraft/tamriel/styles/redguard.json", "/skycraft/tamriel/styles/yokudan.json"};
    /** Everything baked, in order (the street and scene plans read the road plan). */
    private static final List<Bakeable> BAKES = List.of(
            new Bakeable("landmass", com.skycraft.roads.Landmass::computeLive),
            new Bakeable(com.skycraft.worldplan.RoadPlanBuilder.BAKE_NAME, com.skycraft.worldplan.RoadPlanBuilder::computeLive),
            new Bakeable(com.skycraft.worldplan.street.StreetPlanBuilder.BAKE_NAME, com.skycraft.worldplan.street.StreetPlanBuilder::computeLive),
            new Bakeable(com.skycraft.worldplan.scene.ScenePlanBuilder.BAKE_NAME, com.skycraft.worldplan.scene.ScenePlanBuilder::computeLive));

    private static volatile String inputs;

    private Bake() {}

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
        try (InputStream in = Bake.class.getResourceAsStream(path)) {
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
                Skycraft.LOGGER.info("[bake] {} is stale (inputs changed); computing live", name);
                return null;
            }
            try (InflaterInputStream z = new InflaterInputStream(in)) {
                byte[] out = z.readAllBytes();
                Skycraft.LOGGER.info("[bake] loaded {} ({} KB)", name, out.length / 1024);
                return out;
            }
        } catch (IOException | RuntimeException e) {
            Skycraft.LOGGER.warn("[bake] {} is unreadable; computing live", name, e);
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
                else Skycraft.LOGGER.info("[bake] {} matches a live computation (sha {}, {} ms)", b.name(), sha(live).substring(0, 12),
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
            Skycraft.LOGGER.info("[bake] {}", bakeAll(dir));
            for (Bakeable b : BAKES) {
                byte[] written = decode(Files.readAllBytes(dir.resolve(b.name() + ".bin")), b.name());
                if (written == null) {
                    bad.add(b.name() + ".bin unreadable after the rebake");
                } else if (!java.util.Arrays.equals(written, b.compute().run())) {
                    bad.add(b.name() + ".bin differs from a second live computation (non-deterministic bake)");
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
        d.register(Commands.literal("skycraft-bake").requires(s -> s.hasPermission(4))
                .then(Commands.literal("refresh").executes(c -> {
                    List<String> bad = refresh(Path.of("skycraft-bake"));
                    String msg = bad == null ? "Bake is up to date." : bad.isEmpty()
                            ? "Bake was stale: rebaked and verified into " + Path.of("skycraft-bake").toAbsolutePath()
                            : "Bake problems: " + bad;
                    Skycraft.LOGGER.info("[bake] {}", msg);
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
                        String msg = bakeAll(Path.of("skycraft-bake"));
                        c.getSource().sendSuccess(() -> Component.literal(msg + "; copy it to mod/src/main/resources/skycraft/baked/"), false);
                        return 1;
                    } catch (IOException e) {
                        c.getSource().sendFailure(Component.literal("Bake failed: " + e));
                        return 0;
                    }
                })));
    }
}
