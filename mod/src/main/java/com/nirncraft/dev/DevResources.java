package com.nirncraft.dev;

import com.google.gson.JsonParser;
import com.nirncraft.bake.Bake;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Immutable, explicitly activated JSON snapshots for the packaged development client. */
public final class DevResources {
    private static volatile Map<String, byte[]> active = Map.of();

    private DevResources() {}

    static void clear() {
        active = Map.of();
        Bake.invalidateInputs();
    }

    public static boolean allowed(String path) {
        return path.endsWith(".json") && !path.contains("..") && !path.contains("\\")
                && (path.startsWith("/nirncraft/tamriel/places/")
                || path.startsWith("/nirncraft/tamriel/cityplans/")
                || path.startsWith("/nirncraft/tamriel/styles/")
                || path.startsWith("/nirncraft/scatter/")
                || path.startsWith("/nirncraft/quests/")
                || path.equals("/nirncraft/tamriel/palettes.json"));
    }

    /** Production always uses the packaged resource. Only the dev inbox enables overrides. */
    public static InputStream open(Class<?> owner, String path) throws IOException {
        byte[] bytes = active.get(path);
        return bytes == null ? owner.getResourceAsStream(path) : new ByteArrayInputStream(bytes);
    }

    static int activate() throws IOException {
        String configured = System.getProperty("nirncraft.preview.resources", "");
        if (!DevServer.enabled() || configured.isBlank()) return 0;
        Path root = Path.of(configured).toAbsolutePath().normalize();
        Path pointer = root.resolve("current.json");
        if (!Files.isRegularFile(pointer)) return 0;
        var manifest = JsonParser.parseString(Files.readString(pointer)).getAsJsonObject();
        String snapshot = manifest.get("snapshot").getAsString();
        if (!snapshot.matches("[a-zA-Z0-9_-]+")) throw new IOException("Invalid preview snapshot name");
        Path folder = root.resolve(snapshot).toRealPath();
        if (!folder.startsWith(root.toRealPath())) throw new IOException("Snapshot escapes preview root");
        Map<String, byte[]> next = new HashMap<>();
        for (var entry : manifest.getAsJsonObject("files").entrySet()) {
            String path = entry.getKey();
            if (!allowed(path)) throw new IOException("Resource is not live reloadable: " + path);
            Path file = folder.resolve(path.substring(1)).toRealPath();
            if (!file.startsWith(folder)) throw new IOException("Resource escapes snapshot");
            byte[] bytes = Files.readAllBytes(file);
            if (!Bake.sha(bytes).equals(entry.getValue().getAsString())) throw new IOException("Snapshot changed: " + path);
            JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            next.put(path, bytes);
        }
        // Readers see either the complete old snapshot or the complete new one.
        active = Map.copyOf(next);
        Bake.invalidateInputs();
        return next.size();
    }
}
