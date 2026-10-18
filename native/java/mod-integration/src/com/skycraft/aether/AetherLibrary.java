package com.skycraft.aether;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Unpacks aether_core from the mod jar (natives/windows-x86_64/aether_core.dll) into the game directory, named by
 * content hash so an updated library never collides with a loaded old one.
 */
final class AetherLibrary {
    private static final String RESOURCE = "/natives/windows-x86_64/aether_core.dll";

    private AetherLibrary() {}

    static Path extract(Path gameDir) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new IOException("aether_core is built for Windows x64 only so far");
        }
        byte[] bytes;
        try (InputStream in = AetherLibrary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("mod jar lacks " + RESOURCE);
            bytes = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (Exception e) {
            throw new IOException(e);
        }
        Path dir = gameDir.resolve("skycraft").resolve("natives");
        Files.createDirectories(dir);
        Path dll = dir.resolve("aether_core-" + hash + ".dll");
        if (!Files.exists(dll)) {
            Path tmp = Files.createTempFile(dir, "aether_core", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dll, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return dll;
    }
}
