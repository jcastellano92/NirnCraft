package com.skycraft.aether;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Unpacks aether_core from the mod jar into the game directory, named by content hash so an updated library never
 * collides with a loaded old one: natives/windows-x86_64/aether_core.dll on Windows, natives/linux-x86_64/
 * libaether_core.so on Linux (Steam Deck, Bazzite, any distro with glibc 2.27+; built by native/build_linux.sh).
 */
final class AetherLibrary {
    private AetherLibrary() {}

    /** The jar resource for this OS and CPU, or an IOException naming what is unsupported. */
    static String resource() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64) throw new IOException("aether_core is built for x86-64 only (this JVM: " + arch + ")");
        if (os.contains("win")) return "/natives/windows-x86_64/aether_core.dll";
        if (os.contains("linux")) return "/natives/linux-x86_64/libaether_core.so";
        throw new IOException("aether_core is not built for " + os);
    }

    static Path extract(Path gameDir) throws IOException {
        String resource = resource();
        byte[] bytes;
        try (InputStream in = AetherLibrary.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("mod jar lacks " + resource);
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
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        Path dll = dir.resolve(name.substring(0, dot) + "-" + hash + name.substring(dot));
        if (!Files.exists(dll)) {
            Path tmp = Files.createTempFile(dir, "aether_core", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dll, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return dll;
    }
}
