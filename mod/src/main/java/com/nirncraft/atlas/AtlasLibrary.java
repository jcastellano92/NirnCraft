package com.nirncraft.atlas;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unpacks atlas_core from the mod jar to a directory it can be loaded from, robustly:
 * <ul>
 *   <li><b>Platforms:</b> natives/windows-x86_64/atlas_core.dll, natives/linux-x86_64/libatlas_core.so (glibc 2.27+:
 *   Steam Deck, Bazzite, any distro; built by native/build_linux.sh). Anything else (macOS, ARM, FreeBSD ...) gets an
 *   {@link UnsupportedPlatformException} with a plain reason: the far field stays off, the shader pack draws its own
 *   clouds and sky.</li>
 *   <li><b>Per-version names:</b> {@code atlas_core-<sha256 prefix>.dll}: an update never collides with a library
 *   another running instance has loaded, and concurrent instances of the same version share one verified file.</li>
 *   <li><b>Verified:</b> the file on disk must hash to the jar's bytes (a crash mid-write, a disk error or a scanner
 *   that truncated it are repaired); a sidecar {@code <resource>.sha256} in the jar, when present, is checked too.</li>
 *   <li><b>Atomic:</b> written to a temp file in the same directory, flushed, then moved into place; a racing
 *   instance's identical file is accepted.</li>
 *   <li><b>Fallback locations</b> when the game directory is read-only or refuses executables (Flatpak sandboxes, a
 *   noexec mount, a locked-down school PC): {@code nirncraft.atlas.nativesDir} if set, the game dir's
 *   nirncraft/natives, then the user cache (%LOCALAPPDATA%\NirnCraft\natives, $XDG_CACHE_HOME or ~/.cache/nirncraft/
 *   natives), then java.io.tmpdir/nirncraft-natives-&lt;user&gt;. {@link #candidates} lists every extracted copy; the
 *   loader tries them in order until one loads.</li>
 * </ul>
 * Pure java.nio: unit-tested without the game (AtlasLibraryTest).
 */
final class AtlasLibrary {
    private AtlasLibrary() {}

    /** This platform has no atlas build: the far field stays off (not an error). */
    static final class UnsupportedPlatformException extends IOException {
        UnsupportedPlatformException(String message) {
            super(message);
        }
    }

    /** The jar resource for an OS / CPU pair (os.name, os.arch), or why there is none. */
    static String resource(String osName, String osArch) throws UnsupportedPlatformException {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        if (os.contains("mac") || os.contains("darwin"))
            throw new UnsupportedPlatformException("no macOS build of atlas_core (it needs Vulkan 1.2 with 64-bit shader "
                    + "integers and GL memory sharing, which macOS does not offer)");
        if (!x64) throw new UnsupportedPlatformException("atlas_core is built for x86-64 only (this JVM: " + osArch + ")");
        if (os.contains("win")) return "/natives/windows-x86_64/atlas_core.dll";
        if (os.contains("linux")) return "/natives/linux-x86_64/libatlas_core.so";
        throw new UnsupportedPlatformException("no atlas_core build for " + osName);
    }

    static String resource() throws UnsupportedPlatformException {
        return resource(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    /**
     * Extracts this platform's library from the jar into every usable candidate directory of the game directory (at
     * most the first two that work, so a noexec first choice still leaves a second), returning the verified files in
     * order of preference.
     */
    static List<Path> candidates(Path gameDir) throws IOException {
        // development: a freshly built atlas_core instead of the jar's, from -Dnirncraft.atlas.devLibrary=<file> or, with
        // no launcher settings needed, <game dir>/atlas-dev/<the library's name> (tools/dev/shader_watch.py --game puts
        // the build there); loaded as a copy, so the build output is never locked and native/build.ps1 can run meanwhile
        String dev = System.getProperty("nirncraft.atlas.devLibrary");
        if ((dev == null || dev.isBlank()) && gameDir != null) {
            String lib = resource();
            Path p = gameDir.resolve("atlas-dev").resolve(lib.substring(lib.lastIndexOf('/') + 1));
            if (Files.isRegularFile(p)) dev = p.toString();
        }
        if (dev != null && !dev.isBlank() && Files.isRegularFile(Path.of(dev)) && gameDir != null) {
            Path src = Path.of(dev), dir = gameDir.resolve("nirncraft").resolve("natives");
            Files.createDirectories(dir);
            String name = src.getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path copy = dir.resolve((dot > 0 ? name.substring(0, dot) : name) + "-dev-"
                    + Files.getLastModifiedTime(src).toMillis() + (dot > 0 ? name.substring(dot) : ""));
            if (!Files.exists(copy)) Files.copy(src, copy);
            return List.of(copy);
        }
        String res = resource();
        byte[] bytes;
        try (InputStream in = AtlasLibrary.class.getResourceAsStream(res)) {
            if (in == null) throw new IOException("the mod jar lacks " + res + " (a build without natives?)");
            bytes = in.readAllBytes();
        }
        String expected = null;
        try (InputStream in = AtlasLibrary.class.getResourceAsStream(res + ".sha256")) {
            if (in != null) expected = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
        }
        return extract(bytes, expected, res.substring(res.lastIndexOf('/') + 1), directories(gameDir, System.getenv()), 2);
    }

    /** Where the library may go, best first (see the class comment). */
    static List<Path> directories(Path gameDir, Map<String, String> env) {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        String override = System.getProperty("nirncraft.atlas.nativesDir");
        if (override != null && !override.isBlank()) dirs.add(Path.of(override));
        if (gameDir != null) dirs.add(gameDir.resolve("nirncraft").resolve("natives"));
        String home = System.getProperty("user.home");
        String local = env.get("LOCALAPPDATA");
        if (local != null && !local.isBlank()) dirs.add(Path.of(local, "NirnCraft", "natives"));
        String xdg = env.get("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) dirs.add(Path.of(xdg, "nirncraft", "natives"));
        else if (home != null && !home.isBlank() && !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            dirs.add(Path.of(home, ".cache", "nirncraft", "natives"));
        String tmp = System.getProperty("java.io.tmpdir");
        String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_");
        if (tmp != null && !tmp.isBlank()) dirs.add(Path.of(tmp, "nirncraft-natives-" + user));
        return new ArrayList<>(dirs);
    }

    /**
     * Writes (or finds, verified) {@code bytes} as a content-named file in up to {@code want} of {@code dirs}.
     * expectedSha256: the jar's sidecar hash (null: none); a mismatch means the jar itself is damaged.
     */
    static List<Path> extract(byte[] bytes, String expectedSha256, String fileName, List<Path> dirs, int want)
            throws IOException {
        String sha = sha256(bytes);
        if (expectedSha256 != null && !expectedSha256.isEmpty() && !sha.equalsIgnoreCase(expectedSha256.split("\\s+")[0]))
            throw new IOException("the mod jar's " + fileName + " is damaged (sha-256 " + sha.substring(0, 16)
                    + "..., expected " + expectedSha256 + "): reinstall the mod");
        int dot = fileName.lastIndexOf('.');
        String name = (dot > 0 ? fileName.substring(0, dot) : fileName) + "-" + sha.substring(0, 16)
                + (dot > 0 ? fileName.substring(dot) : "");
        List<Path> out = new ArrayList<>();
        List<String> why = new ArrayList<>();
        for (Path dir : dirs) {
            if (out.size() >= want) break;
            try {
                out.add(place(bytes, sha, dir, name));
            } catch (IOException | RuntimeException e) {
                why.add(dir + ": " + e);
            }
        }
        if (out.isEmpty()) throw new IOException("no writable directory for " + name + " (" + String.join("; ", why) + ")");
        return out;
    }

    private static Path place(byte[] bytes, String sha, Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        if (Files.isRegularFile(target) && verified(target, sha)) return target;
        cleanTemps(dir);
        Path tmp = Files.createTempFile(dir, "atlas_core-", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            if (!verified(tmp, sha)) throw new IOException("the written copy does not verify (disk full or failing?)");
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.FileSystemException e) {
                // Windows: another instance has the (identical, content-named) file loaded and locked; accept it
                // when it verifies
                if (!(Files.isRegularFile(target) && verified(target, sha))) throw e;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (!verified(target, sha)) throw new IOException(target + " does not verify after writing");
        return target;
    }

    /** Temp files a crashed extraction left behind (older than an hour: a running one may still be writing). */
    private static void cleanTemps(Path dir) {
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "atlas_core-*.tmp")) {
            for (Path p : ds) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // in use or not ours
                }
            }
        } catch (IOException ignored) {
            // nothing to clean
        }
    }

    static boolean verified(Path file, String sha) {
        try {
            return sha256(Files.readAllBytes(file)).equals(sha);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);  // every JDK has SHA-256
        }
    }
}
