package com.skycraft.platform;

import com.skycraft.testing.BoundedServerProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real native storage round trips for the file locations left by vanilla file fixing. */
@ExtendWith(BoundedServerProvider.class)
class SavedDataMigrationTest {
    private static final String NAME = "skycraft_fixture";
    private record Location(ResourceKey<Level> dimension, String oldFile) {}

    private static final class FixtureData extends SavedData {
        final CompoundTag payload;
        FixtureData() { this(new CompoundTag()); }
        FixtureData(CompoundTag payload) { this.payload = payload.copy(); }
        static FixtureData load(CompoundTag payload) { return new FixtureData(payload); }
        CompoundTag save() { return payload.copy(); }
    }

    private static SavedDataStorage storage(MinecraftServer server, Path world, ResourceKey<Level> dimension) {
        return new SavedDataStorage(null, DimensionType.getStorageFolder(dimension, world).resolve("data"),
                DataFixers.getDataFixer(), server.registryAccess());
    }

    private static FixtureData imported(SavedDataStorage storage, Path world, ResourceKey<Level> dimension) {
        return SavedDataTypes.getIfPresent(storage, world, dimension, NAME,
                FixtureData::load, FixtureData::new, FixtureData::save);
    }

    private static Path canonical(Path world, ResourceKey<Level> dimension) {
        return DimensionType.getStorageFolder(dimension, world).resolve("data/skycraft/" + NAME + ".dat");
    }

    private static CompoundTag payload(String label) {
        var payload = new CompoundTag();
        payload.putString("nonempty", label);
        payload.putInt("largeCount", 1_000_000_000);
        var custom = new CompoundTag();
        custom.putBoolean("keepUnknownModule", true);
        payload.put("custom", custom);
        return payload;
    }

    private static byte[] oldFile(Path file, CompoundTag payload) throws Exception {
        Files.createDirectories(file.getParent());
        var root = new CompoundTag();
        root.putInt("DataVersion", 3465);
        root.put("data", payload.copy());
        NbtIo.writeCompressed(root, file);
        return Files.readAllBytes(file);
    }

    @Test void allLegacyDimensionLocationsImportAndRoundTripWithoutChangingTheirSources(
            MinecraftServer server, @TempDir Path directory) throws Exception {
        var shivering = ResourceKey.create(Registries.DIMENSION, Identifier.parse("skycraft:shivering_isles"));
        var locations = List.of(
                new Location(Level.OVERWORLD, "data/" + NAME + ".dat"),
                new Location(Level.OVERWORLD, "data/minecraft/" + NAME + ".dat"),
                new Location(Level.NETHER, "DIM-1/data/" + NAME + ".dat"),
                new Location(Level.END, "DIM1/data/" + NAME + ".dat"),
                new Location(shivering, "dimensions/skycraft/shivering_isles/data/" + NAME + ".dat"),
                new Location(Level.OVERWORLD, "dimensions/minecraft/overworld/data/minecraft/" + NAME + ".dat"),
                new Location(Level.OVERWORLD, "dimensions/minecraft/overworld/data/" + NAME + ".dat"));
        int index = 0;
        for (var location : locations) {
            Path world = directory.resolve("world-" + index++);
            Path old = world.resolve(location.oldFile());
            CompoundTag expected = payload(location.oldFile());
            byte[] original = oldFile(old, expected);
            try (var storage = storage(server, world, location.dimension())) {
                var imported = imported(storage, world, location.dimension());
                assertNotNull(imported, location.oldFile());
                assertEquals(expected, imported.payload);
                assertTrue(imported.isDirty(), "Imported payload must be written to the canonical native ID");
                storage.saveAndJoin();
                assertEquals(expected, NbtIo.readCompressed(canonical(world, location.dimension()),
                        NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data"));
            }
            assertArrayEquals(original, Files.readAllBytes(old), "The original legacy file stays untouched");
            try (var reopened = storage(server, world, location.dimension())) {
                var nativeData = reopened.get(SavedDataTypes.type(NAME, FixtureData::load, FixtureData::new, FixtureData::save));
                assertNotNull(nativeData, "A fresh native storage must decode the canonical saved payload");
                assertEquals(expected, nativeData.payload);
            }
        }
    }

    @Test void canonicalNativeDataWinsOverAnOlderLegacyCopy(MinecraftServer server, @TempDir Path world) throws Exception {
        oldFile(world.resolve("data/" + NAME + ".dat"), payload("old"));
        var expected = payload("current");
        try (var storage = storage(server, world, Level.OVERWORLD)) {
            var type = SavedDataTypes.type(NAME, FixtureData::load, FixtureData::new, FixtureData::save);
            storage.set(type, new FixtureData(expected));
            storage.saveAndJoin();
        }
        try (var reopened = storage(server, world, Level.OVERWORLD)) {
            assertEquals(expected, imported(reopened, world, Level.OVERWORLD).payload);
        }
    }

    @Test void missingOptionalBucketsAndOtherDimensionsDoNotCreateOrImportData(
            MinecraftServer server, @TempDir Path world) throws Exception {
        oldFile(world.resolve("data/" + NAME + ".dat"), payload("overworld-only"));
        try (var storage = storage(server, world, Level.NETHER)) {
            assertNull(imported(storage, world, Level.NETHER));
            storage.saveAndJoin();
        }
        assertFalse(Files.exists(canonical(world, Level.NETHER)));
    }

    @Test void corruptLegacyPayloadFailsWithoutCreatingReplacementData(
            MinecraftServer server, @TempDir Path world) throws Exception {
        Path old = world.resolve("data/" + NAME + ".dat");
        Files.createDirectories(old.getParent());
        byte[] original = {1, 2, 3, 4};
        Files.write(old, original);
        try (var storage = storage(server, world, Level.OVERWORLD)) {
            assertThrows(IllegalStateException.class, () -> imported(storage, world, Level.OVERWORLD));
            storage.saveAndJoin();
        }
        assertArrayEquals(original, Files.readAllBytes(old));
        assertFalse(Files.exists(canonical(world, Level.OVERWORLD)));
        assertThrows(IllegalArgumentException.class,
                () -> SavedDataTypes.legacyFiles(world, Level.OVERWORLD, "../../outside"));
    }

    @Test void corruptCanonicalDataCannotBeOverwrittenByAValidOlderLegacyCopy(
            MinecraftServer server, @TempDir Path world) throws Exception {
        Path legacy = world.resolve("data/" + NAME + ".dat");
        byte[] oldBytes = oldFile(legacy, payload("valid-but-older"));
        Path canonical = canonical(world, Level.OVERWORLD);
        Files.createDirectories(canonical.getParent());
        // A genuine native codec rejection exercises get's null result without
        // Throwable formatting, whose upstream Log4J test loader tries to load
        // transformed Minecraft stack frames through the application loader.
        var malformed = new CompoundTag();
        malformed.putString("data", "malformed-canonical-fixture");
        NbtIo.writeCompressed(malformed, canonical);
        byte[] badBytes = Files.readAllBytes(canonical);
        try (var storage = storage(server, world, Level.OVERWORLD)) {
            var failure = assertThrows(IllegalStateException.class, () -> imported(storage, world, Level.OVERWORLD));
            assertTrue(failure.getMessage().contains("canonical saved data"));
            storage.saveAndJoin();
        }
        assertArrayEquals(badBytes, Files.readAllBytes(canonical));
        assertArrayEquals(oldBytes, Files.readAllBytes(legacy));
    }
}
