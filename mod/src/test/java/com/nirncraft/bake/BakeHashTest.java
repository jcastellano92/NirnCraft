package com.nirncraft.bake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for {@link Bake}: verifies input hashing over hashed vs unhashed data files,
 * SHA-256 content digests, and binary write/decode round-tripping.
 */
class BakeHashTest {

    private String savedInputsField;

    private static Field inputsField() throws Exception {
        Field f = Bake.class.getDeclaredField("inputs");
        f.setAccessible(true);
        return f;
    }

    private static String computeFreshInputsHash() throws Exception {
        Field f = inputsField();
        f.set(null, null);
        return Bake.inputsHash();
    }

    @BeforeEach
    void saveInputsCache() throws Exception {
        savedInputsField = (String) inputsField().get(null);
    }

    @AfterEach
    void restoreInputsCache() throws Exception {
        inputsField().set(null, savedInputsField);
    }

    @Test
    void inputsHashIs64CharHexSha256AndDeterministic() throws Exception {
        String h1 = computeFreshInputsHash();
        String h2 = computeFreshInputsHash();
        assertNotNull(h1);
        assertEquals(64, h1.length(), "SHA-256 hex digest must be 64 characters");
        assertTrue(h1.matches("[0-9a-f]{64}"), "Hash must be lowercase hex: " + h1);
        assertEquals(h1, h2);
    }

    @Test
    void inputsHashChangesWhenHashedDataFileChangesAndNotOtherwise() throws Exception {
        URL url = Bake.class.getResource("/nirncraft/scatter/rules.json");
        assertNotNull(url, "Missing /nirncraft/scatter/rules.json on test classpath");
        assertEquals("file", url.getProtocol(), "Expected file-backed resource directory in build/resources/main");

        Path hashedFile = Path.of(url.toURI());
        Path unhashedFile = hashedFile.getParent().resolve("_unhashed_bake_test_probe.json");
        byte[] origBytes = Files.readAllBytes(hashedFile);
        String baseline = computeFreshInputsHash();

        try {
            // 1. Adding/changing an unhashed file in the same resource directory must NOT change inputsHash()
            Files.writeString(unhashedFile, "{\"probe\": 1}\n", StandardCharsets.UTF_8);
            String withUnhashed = computeFreshInputsHash();
            assertEquals(baseline, withUnhashed, "inputsHash() must NOT change when an unhashed data file changes");

            // 2. Modifying a hashed data file (/nirncraft/scatter/rules.json in DATA_FILES) MUST change inputsHash()
            byte[] modified = Arrays.copyOf(origBytes, origBytes.length + 1);
            modified[origBytes.length] = (byte) '\n';
            Files.write(hashedFile, modified);
            String withChangedData = computeFreshInputsHash();
            assertNotEquals(baseline, withChangedData, "inputsHash() MUST change when a hashed data file changes");
        } finally {
            Files.write(hashedFile, origBytes);
            Files.deleteIfExists(unhashedFile);
        }

        assertEquals(baseline, computeFreshInputsHash(), "Restoring hashed data file must restore exact baseline hash");
    }

    @Test
    void inputsHashChangesWhenHashedMapOrPlacesFileChanges() throws Exception {
        URL mapUrl = Bake.class.getResource("/nirncraft/tamriel/ports.json");
        assertNotNull(mapUrl);
        Path mapFile = Path.of(mapUrl.toURI());
        byte[] origBytes = Files.readAllBytes(mapFile);
        String baseline = computeFreshInputsHash();

        try {
            byte[] modified = Arrays.copyOf(origBytes, origBytes.length + 1);
            modified[origBytes.length] = (byte) ' ';
            Files.write(mapFile, modified);
            String afterEdit = computeFreshInputsHash();
            assertNotEquals(baseline, afterEdit, "inputsHash() must change when a MAP_FILES entry (ports.json) changes");
        } finally {
            Files.write(mapFile, origBytes);
        }

        assertEquals(baseline, computeFreshInputsHash());
    }

    @Test
    void shaComputesExpectedHexDigestAndDetectsSingleBitFlip() {
        byte[] payload = "nirncraft-bake-payload-test".getBytes(StandardCharsets.UTF_8);
        String s1 = Bake.sha(payload);
        String s2 = Bake.sha(payload.clone());
        assertEquals(64, s1.length());
        assertEquals(s1, s2);

        byte[] flipped = payload.clone();
        flipped[0] ^= 1;
        assertNotEquals(s1, Bake.sha(flipped));
    }

    @Test
    void writeAndDecodeRoundTripPayloadWhenHashMatches(@TempDir Path tempDir) throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + 7);

        Bake.write(tempDir, "testbake", payload);
        Path bin = tempDir.resolve("testbake.bin");
        assertTrue(Files.exists(bin));

        Method decode = Bake.class.getDeclaredMethod("decode", byte[].class, String.class);
        decode.setAccessible(true);
        byte[] decoded = (byte[]) decode.invoke(null, Files.readAllBytes(bin), "testbake");
        assertNotNull(decoded);
        assertArrayEquals(payload, decoded);
    }

    @Test
    void decodeReturnsNullWhenRecordedHashIsStale() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeUTF("0000000000000000000000000000000000000000000000000000000000000000");
            try (DeflaterOutputStream z = new DeflaterOutputStream(out)) {
                z.write(new byte[]{1, 2, 3, 4});
            }
        }
        Method decode = Bake.class.getDeclaredMethod("decode", byte[].class, String.class);
        decode.setAccessible(true);
        byte[] decoded = (byte[]) decode.invoke(null, bo.toByteArray(), "stale_test");
        assertNull(decoded, "Bake.decode must return null when header hash does not match inputsHash()");
    }
}
