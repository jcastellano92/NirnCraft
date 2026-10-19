package com.skycraft.aether;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Native library extraction (AetherLibrary): platforms, verification, repair, fallbacks, concurrent instances. */
class AetherLibraryTest {
    static final byte[] LIB = "pretend this is aether_core.dll".getBytes();

    @Test
    void platforms() throws IOException {
        assertEquals("/natives/windows-x86_64/aether_core.dll", AetherLibrary.resource("Windows 11", "amd64"));
        assertEquals("/natives/linux-x86_64/libaether_core.so", AetherLibrary.resource("Linux", "amd64"));
        assertEquals("/natives/linux-x86_64/libaether_core.so", AetherLibrary.resource("Linux", "x86_64"));
        var mac = assertThrows(AetherLibrary.UnsupportedPlatformException.class, () -> AetherLibrary.resource("Mac OS X", "aarch64"));
        assertTrue(mac.getMessage().contains("macOS"), mac.getMessage());
        assertThrows(AetherLibrary.UnsupportedPlatformException.class, () -> AetherLibrary.resource("Mac OS X", "x86_64"));
        var arm = assertThrows(AetherLibrary.UnsupportedPlatformException.class, () -> AetherLibrary.resource("Linux", "aarch64"));
        assertTrue(arm.getMessage().contains("x86-64"), arm.getMessage());
        assertThrows(AetherLibrary.UnsupportedPlatformException.class, () -> AetherLibrary.resource("FreeBSD", "amd64"));
    }

    @Test
    void extractsOnceUnderAContentName(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("natives");
        List<Path> a = AetherLibrary.extract(LIB, null, "aether_core.dll", List.of(dir), 1);
        assertEquals(1, a.size());
        String sha = AetherLibrary.sha256(LIB);
        assertEquals("aether_core-" + sha.substring(0, 16) + ".dll", a.get(0).getFileName().toString());
        assertArrayEquals(LIB, Files.readAllBytes(a.get(0)));
        long modified = Files.getLastModifiedTime(a.get(0)).toMillis();
        // a second start finds the verified file and leaves it alone
        List<Path> b = AetherLibrary.extract(LIB, null, "aether_core.dll", List.of(dir), 1);
        assertEquals(a, b);
        assertEquals(modified, Files.getLastModifiedTime(b.get(0)).toMillis());
        // another version gets its own file beside it
        byte[] next = "aether_core, the next version".getBytes();
        Path c = AetherLibrary.extract(next, null, "aether_core.dll", List.of(dir), 1).get(0);
        assertNotEquals(a.get(0), c);
        assertTrue(Files.exists(a.get(0)));
        // no temp files left behind
        try (var s = Files.list(dir)) {
            assertEquals(0, s.filter(p -> p.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void repairsADamagedCopy(@TempDir Path tmp) throws IOException {
        Path f = AetherLibrary.extract(LIB, null, "libaether_core.so", List.of(tmp), 1).get(0);
        Files.write(f, new byte[] {1, 2, 3});  // truncated by a crash mid-write, or a scanner
        Path g = AetherLibrary.extract(LIB, null, "libaether_core.so", List.of(tmp), 1).get(0);
        assertEquals(f, g);
        assertArrayEquals(LIB, Files.readAllBytes(g));
    }

    @Test
    void checksTheJarsOwnHash(@TempDir Path tmp) throws IOException {
        String sha = AetherLibrary.sha256(LIB);
        assertEquals(1, AetherLibrary.extract(LIB, sha + "  aether_core.dll\n", "aether_core.dll", List.of(tmp), 1).size());
        IOException e = assertThrows(IOException.class,
                () -> AetherLibrary.extract(LIB, "00" + sha.substring(2), "aether_core.dll", List.of(tmp.resolve("x")), 1));
        assertTrue(e.getMessage().contains("damaged"), e.getMessage());
    }

    @Test
    void fallsBackWhenTheGameDirIsNotWritable(@TempDir Path tmp) throws IOException {
        // a "directory" that cannot be created (a file is in the way), as on a read-only game dir
        Path blocked = tmp.resolve("game");
        Files.writeString(blocked, "not a directory");
        Path cache = tmp.resolve("cache");
        List<Path> got = AetherLibrary.extract(LIB, null, "aether_core.dll", List.of(blocked.resolve("natives"), cache), 2);
        assertEquals(1, got.size());
        assertTrue(got.get(0).startsWith(cache));
        // nowhere at all: one clear error naming every place tried
        IOException e = assertThrows(IOException.class,
                () -> AetherLibrary.extract(LIB, null, "aether_core.dll", List.of(blocked.resolve("a"), blocked.resolve("b")), 2));
        assertTrue(e.getMessage().contains("no writable directory"), e.getMessage());
    }

    @Test
    void keepsTwoCopiesForALoaderFallback(@TempDir Path tmp) throws IOException {
        List<Path> got = AetherLibrary.extract(LIB, null, "aether_core.dll", List.of(tmp.resolve("a"), tmp.resolve("b"),
                tmp.resolve("c")), 2);
        assertEquals(List.of(tmp.resolve("a"), tmp.resolve("b")), got.stream().map(Path::getParent).toList());
    }

    @Test
    void concurrentInstancesShareOneVerifiedFile(@TempDir Path tmp) throws Exception {
        int n = 6;
        CountDownLatch go = new CountDownLatch(1);
        Set<Path> results = ConcurrentHashMap.newKeySet();
        Set<Throwable> errors = ConcurrentHashMap.newKeySet();
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            ts[i] = new Thread(() -> {
                try {
                    go.await();
                    results.add(AetherLibrary.extract(LIB, null, "aether_core.dll", List.of(tmp), 1).get(0));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            ts[i].start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(1, results.size());
        assertTrue(AetherLibrary.verified(results.iterator().next(), AetherLibrary.sha256(LIB)));
    }

    @Test
    void directoriesInOrder(@TempDir Path tmp) {
        List<Path> dirs = AetherLibrary.directories(tmp, Map.of("LOCALAPPDATA", tmp.resolve("local").toString(),
                "XDG_CACHE_HOME", tmp.resolve("xdg").toString()));
        assertEquals(tmp.resolve("skycraft").resolve("natives"), dirs.get(0));
        assertTrue(dirs.contains(Path.of(tmp.resolve("local").toString(), "Skycraft", "natives")));
        assertTrue(dirs.contains(Path.of(tmp.resolve("xdg").toString(), "skycraft", "natives")));
        assertTrue(dirs.get(dirs.size() - 1).getFileName().toString().startsWith("skycraft-natives-"));
        assertEquals(dirs.size(), Set.copyOf(dirs).size());
    }
}
