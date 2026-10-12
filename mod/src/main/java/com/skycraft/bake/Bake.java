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
            "com.skycraft.tamriel.terrain.Hydrology"};

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
        byte[] raw = bytes(DIR + name + ".bin");
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
        byte[] landmass = com.skycraft.roads.Landmass.computeLive();
        write(dir, "landmass", landmass);
        Files.writeString(dir.resolve("manifest.json"),
                "{\n  \"format\": " + FORMAT + ",\n  \"inputs\": \"" + inputsHash() + "\",\n  \"files\": [\"landmass.bin\"]\n}\n");
        return "baked landmass (" + landmass.length / 1024 + " KB raw) into " + dir.toAbsolutePath();
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
        byte[] baked = load("landmass");
        if (baked == null) bad.add("landmass.bin missing or stale");
        else {
            try {
                if (!java.util.Arrays.equals(baked, com.skycraft.roads.Landmass.computeLive())) bad.add("landmass.bin differs from live computation");
            } catch (IOException e) {
                bad.add("could not recompute landmass: " + e);
            }
        }
        return bad;
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-bake").requires(s -> s.hasPermission(4))
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
