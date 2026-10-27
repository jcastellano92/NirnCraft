package com.nirncraft.atlas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Pure encoding/layout for the opt-in generator ingest diagnostic. Offsets are 32-bit words. */
final class AtlasIngestDump {
    private AtlasIngestDump() {}

    static byte[] littleEndian(int[] words) {
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(words.length, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(words);
        return bytes.array();
    }

    static boolean matches(byte[] data, int wordCount, long expectedHash) {
        if ((long) data.length != (long) wordCount * Integer.BYTES) return false;
        long h = 1469598103934665603L;
        for (byte value : data) h = (h ^ (value & 255)) * 1099511628211L;
        return h == expectedHash;
    }

    static Path publish(Path parent, int[] words, String programHash, String nativeParts, String modelIdentity) throws IOException {
        String attempt = java.util.UUID.randomUUID().toString();
        Files.createDirectories(parent);
        Path root = Files.createDirectory(parent.resolve("ingest-" + attempt));
        var layout = com.google.gson.JsonParser.parseString(manifest(words)).getAsJsonObject();
        layout.addProperty("attempt", attempt);
        layout.addProperty("programFnv64", programHash);
        layout.addProperty("nativeFingerprintPartsAtSetIngest", nativeParts);
        layout.addProperty("modelIdentityProcessLocal", modelIdentity);
        Path binary = root.resolve("ingest.bin"), temporary = root.resolve("ingest.json.tmp");
        Files.write(binary, littleEndian(words));
        if (!matches(Files.readAllBytes(binary), words.length, AtlasCanonicalLooks.fingerprint(words, 0, 1, words.length)))
            throw new IOException("ingest bytes do not match the native word array");
        Files.writeString(temporary, layout.toString() + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, root.resolve("ingest.json"), StandardCopyOption.ATOMIC_MOVE);
        return root;
    }

    static String manifest(int[] words) {
        if (words.length < 2 || words[0] < 0 || words[1] < 0) throw new IllegalArgumentException("invalid ingest header");
        int states = words[0], biomes = words[1];
        long tint = 2L + 2L * states, materials = tint + 2L * biomes, end = materials + (long) states * biomes;
        if (end != words.length) throw new IllegalArgumentException("ingest length does not match its header");
        return "{\"format\":\"atlas-ingest-le32-v1\",\"words\":" + words.length
                + ",\"fnv64\":\"" + Long.toUnsignedString(AtlasCanonicalLooks.fingerprint(words, 0, 1, words.length), 16) + "\""
                + ",\"states\":" + states + ",\"biomes\":" + biomes
                + ",\"ids\":{\"offset\":2,\"stride\":2,\"count\":" + states + "}"
                + ",\"flags\":{\"offset\":3,\"stride\":2,\"count\":" + states + "}"
                + ",\"tints\":{\"offset\":" + tint + ",\"stride\":1,\"count\":" + (2L * biomes) + "}"
                + ",\"materials\":{\"offset\":" + materials + ",\"stride\":1,\"count\":" + ((long) states * biomes) + "}}\n";
    }
}
