package com.nirncraft.atlas;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AtlasIngestDumpTest {
    @Test
    void exactLittleEndianAndComponentOffsets() {
        int[] words = {2, 1, 0x01020304, 0x100, 7, 6, 0xaabbcc, 0x112233, 10, 11};
        byte[] bytes = AtlasIngestDump.littleEndian(words);
        assertEquals(40, bytes.length);
        assertArrayEquals(new byte[]{4, 3, 2, 1}, java.util.Arrays.copyOfRange(bytes, 8, 12));
        var decoded = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] read = new int[words.length]; decoded.get(read);
        assertArrayEquals(words, read);
        assertEquals(0x12af40ef1aec1b48L, AtlasCanonicalLooks.fingerprint(read, 0, 1, read.length));
        var m = JsonParser.parseString(AtlasIngestDump.manifest(words)).getAsJsonObject();
        assertEquals(10, m.get("words").getAsInt());
        assertEquals("12af40ef1aec1b48", m.get("fnv64").getAsString());
        assertEquals(2, m.getAsJsonObject("ids").get("offset").getAsInt());
        assertEquals(2, m.getAsJsonObject("ids").get("stride").getAsInt());
        assertEquals(2, m.getAsJsonObject("ids").get("count").getAsInt());
        assertEquals(3, m.getAsJsonObject("flags").get("offset").getAsInt());
        assertEquals(6, m.getAsJsonObject("tints").get("offset").getAsInt());
        assertEquals(2, m.getAsJsonObject("tints").get("count").getAsInt());
        assertEquals(8, m.getAsJsonObject("materials").get("offset").getAsInt());
        assertEquals(2, m.getAsJsonObject("materials").get("count").getAsInt());
    }

    @Test
    void rejectsWrongLayout() {
        assertThrows(IllegalArgumentException.class, () -> AtlasIngestDump.manifest(new int[]{2, 1, 0}));
        assertThrows(IllegalArgumentException.class, () -> AtlasIngestDump.manifest(new int[]{-1, 1}));
    }

    @Test
    void simultaneousAttemptsStayIndependentAndTamperingFails(@TempDir Path dir) throws Exception {
        int[] first = {1, 1, 7, 2, 10, 20, 123};
        int[] second = {1, 1, 8, 2, 10, 20, 124};
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> AtlasIngestDump.publish(dir, first, "prog-a", "native-a", "model-a"));
            var b = pool.submit(() -> AtlasIngestDump.publish(dir, second, "prog-b", "native-b", "model-b"));
            Path pa = a.get(), pb = b.get();
            assertNotEquals(pa, pb);
            byte[] ba = Files.readAllBytes(pa.resolve("ingest.bin"));
            byte[] bb = Files.readAllBytes(pb.resolve("ingest.bin"));
            var ma = JsonParser.parseString(Files.readString(pa.resolve("ingest.json"))).getAsJsonObject();
            var mb = JsonParser.parseString(Files.readString(pb.resolve("ingest.json"))).getAsJsonObject();
            assertEquals(pa.getFileName().toString(), "ingest-" + ma.get("attempt").getAsString());
            assertEquals(pb.getFileName().toString(), "ingest-" + mb.get("attempt").getAsString());
            assertEquals("prog-a", ma.get("programFnv64").getAsString());
            assertEquals("native-b", mb.get("nativeFingerprintPartsAtSetIngest").getAsString());
            assertTrue(AtlasIngestDump.matches(ba, ma.get("words").getAsInt(), Long.parseUnsignedLong(ma.get("fnv64").getAsString(), 16)));
            assertTrue(AtlasIngestDump.matches(bb, mb.get("words").getAsInt(), Long.parseUnsignedLong(mb.get("fnv64").getAsString(), 16)));
            ba[0] ^= 1;
            assertFalse(AtlasIngestDump.matches(ba, first.length, AtlasCanonicalLooks.fingerprint(first, 0, 1, first.length)));
            assertFalse(AtlasIngestDump.matches(bb, second.length + 1, AtlasCanonicalLooks.fingerprint(second, 0, 1, second.length)));
        }
    }
}
