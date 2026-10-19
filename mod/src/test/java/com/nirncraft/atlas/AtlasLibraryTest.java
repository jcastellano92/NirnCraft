package com.nirncraft.atlas;

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

/** Native library extraction (AtlasLibrary): platforms, verification, repair, fallbacks, concurrent instances. */
class AtlasLibraryTest {
    static final byte[] LIB = "pretend this is atlas_core.dll".getBytes();

    @Test
    void platforms() throws IOException {
        assertEquals("/natives/windows-x86_64/atlas_core.dll", AtlasLibrary.resource("Windows 11", "amd64"));
        assertEquals("/natives/linux-x86_64/libatlas_core.so", AtlasLibrary.resource("Linux", "amd64"));
        assertEquals("/natives/linux-x86_64/libatlas_core.so", AtlasLibrary.resource("Linux", "x86_64"));
        var mac = assertThrows(AtlasLibrary.UnsupportedPlatformException.class, () -> AtlasLibrary.resource("Mac OS X", "aarch64"));
        assertTrue(mac.getMessage().contains("macOS"), mac.getMessage());
        assertThrows(AtlasLibrary.UnsupportedPlatformException.class, () -> AtlasLibrary.resource("Mac OS X", "x86_64"));
        var arm = assertThrows(AtlasLibrary.UnsupportedPlatformException.class, () -> AtlasLibrary.resource("Linux", "aarch64"));
        assertTrue(arm.getMessage().contains("x86-64"), arm.getMessage());
        assertThrows(AtlasLibrary.UnsupportedPlatformException.class, () -> AtlasLibrary.resource("FreeBSD", "amd64"));
    }

    @Test
    void extractsOnceUnderAContentName(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("natives");
        List<Path> a = AtlasLibrary.extract(LIB, null, "atlas_core.dll", List.of(dir), 1);
        assertEquals(1, a.size());
        String sha = AtlasLibrary.sha256(LIB);
        assertEquals("atlas_core-" + sha.substring(0, 16) + ".dll", a.get(0).getFileName().toString());
        assertArrayEquals(LIB, Files.readAllBytes(a.get(0)));
        long modified = Files.getLastModifiedTime(a.get(0)).toMillis();
        // a second start finds the verified file and leaves it alone
        List<Path> b = AtlasLibrary.extract(LIB, null, "atlas_core.dll", List.of(dir), 1);
        assertEquals(a, b);
        assertEquals(modified, Files.getLastModifiedTime(b.get(0)).toMillis());
        // another version gets its own file beside it
        byte[] next = "atlas_core, the next version".getBytes();
        Path c = AtlasLibrary.extract(next, null, "atlas_core.dll", List.of(dir), 1).get(0);
        assertNotEquals(a.get(0), c);
        assertTrue(Files.exists(a.get(0)));
        // no temp files left behind
        try (var s = Files.list(dir)) {
            assertEquals(0, s.filter(p -> p.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void repairsADamagedCopy(@TempDir Path tmp) throws IOException {
        Path f = AtlasLibrary.extract(LIB, null, "libatlas_core.so", List.of(tmp), 1).get(0);
        Files.write(f, new byte[] {1, 2, 3});  // truncated by a crash mid-write, or a scanner
        Path g = AtlasLibrary.extract(LIB, null, "libatlas_core.so", List.of(tmp), 1).get(0);
        assertEquals(f, g);
        assertArrayEquals(LIB, Files.readAllBytes(g));
    }

    @Test
    void checksTheJarsOwnHash(@TempDir Path tmp) throws IOException {
        String sha = AtlasLibrary.sha256(LIB);
        assertEquals(1, AtlasLibrary.extract(LIB, sha + "  atlas_core.dll\n", "atlas_core.dll", List.of(tmp), 1).size());
        IOException e = assertThrows(IOException.class,
                () -> AtlasLibrary.extract(LIB, "00" + sha.substring(2), "atlas_core.dll", List.of(tmp.resolve("x")), 1));
        assertTrue(e.getMessage().contains("damaged"), e.getMessage());
    }

    @Test
    void fallsBackWhenTheGameDirIsNotWritable(@TempDir Path tmp) throws IOException {
        // a "directory" that cannot be created (a file is in the way), as on a read-only game dir
        Path blocked = tmp.resolve("game");
        Files.writeString(blocked, "not a directory");
        Path cache = tmp.resolve("cache");
        List<Path> got = AtlasLibrary.extract(LIB, null, "atlas_core.dll", List.of(blocked.resolve("natives"), cache), 2);
        assertEquals(1, got.size());
        assertTrue(got.get(0).startsWith(cache));
        // nowhere at all: one clear error naming every place tried
        IOException e = assertThrows(IOException.class,
                () -> AtlasLibrary.extract(LIB, null, "atlas_core.dll", List.of(blocked.resolve("a"), blocked.resolve("b")), 2));
        assertTrue(e.getMessage().contains("no writable directory"), e.getMessage());
    }

    @Test
    void keepsTwoCopiesForALoaderFallback(@TempDir Path tmp) throws IOException {
        List<Path> got = AtlasLibrary.extract(LIB, null, "atlas_core.dll", List.of(tmp.resolve("a"), tmp.resolve("b"),
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
                    results.add(AtlasLibrary.extract(LIB, null, "atlas_core.dll", List.of(tmp), 1).get(0));
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
        assertTrue(AtlasLibrary.verified(results.iterator().next(), AtlasLibrary.sha256(LIB)));
    }

    @Test
    void directoriesInOrder(@TempDir Path tmp) {
        List<Path> dirs = AtlasLibrary.directories(tmp, Map.of("LOCALAPPDATA", tmp.resolve("local").toString(),
                "XDG_CACHE_HOME", tmp.resolve("xdg").toString()));
        assertEquals(tmp.resolve("nirncraft").resolve("natives"), dirs.get(0));
        assertTrue(dirs.contains(Path.of(tmp.resolve("local").toString(), "NirnCraft", "natives")));
        assertTrue(dirs.contains(Path.of(tmp.resolve("xdg").toString(), "nirncraft", "natives")));
        assertTrue(dirs.get(dirs.size() - 1).getFileName().toString().startsWith("nirncraft-natives-"));
        assertEquals(dirs.size(), Set.copyOf(dirs).size());
    }
}
