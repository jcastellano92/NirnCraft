package com.skycraft.platform;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Typed 26.3 storage with an explicit, non-destructive import of Skycraft's old flat data filenames. */
public final class SavedDataTypes {
    private SavedDataTypes() {}

    public static <T extends SavedData> SavedDataType<T> type(String name, Function<CompoundTag, T> load,
                                                              Supplier<T> create, Function<T, CompoundTag> save) {
        Codec<T> codec = CompoundTag.CODEC.xmap(load, save);
        return new SavedDataType<>(Identifier.fromNamespaceAndPath("skycraft", name), create, codec);
    }

    public static <T extends SavedData> T get(ServerLevel level, String name, Function<CompoundTag, T> load,
                                               Supplier<T> create, Function<T, CompoundTag> save) {
        T value = getIfPresent(level, name, load, create, save);
        return value != null ? value : level.getDataStorage().computeIfAbsent(type(name, load, create, save));
    }

    /** Lookup/import without creating missing optional road buckets or other referenced save files. */
    public static <T extends SavedData> @org.jetbrains.annotations.Nullable T getIfPresent(
            ServerLevel level, String name, Function<CompoundTag, T> load,
            Supplier<T> create, Function<T, CompoundTag> save) {
        return getIfPresent(level.getDataStorage(), level.getServer().getWorldPath(LevelResource.ROOT),
                level.dimension(), name, load, create, save);
    }

    static <T extends SavedData> @org.jetbrains.annotations.Nullable T getIfPresent(
            SavedDataStorage storage, Path worldRoot, ResourceKey<Level> dimension, String name,
            Function<CompoundTag, T> load, Supplier<T> create, Function<T, CompoundTag> save) {
        SavedDataType<T> type = type(name, load, create, save);
        T value = storage.get(type);
        if (value != null) return value;
        Path nativeData = DimensionType.getStorageFolder(dimension, worldRoot.toAbsolutePath().normalize()).resolve("data");
        Path canonical = type.id().withSuffix(".dat").resolveAgainst(nativeData).normalize();
        if (!canonical.startsWith(nativeData.normalize())) throw new IllegalArgumentException("Invalid saved-data filename " + name);
        // Native get logs a codec/read failure and returns null. An existing
        // canonical file is authoritative even when corrupt: never replace it
        // with an older legacy copy or silently create empty gameplay state.
        if (Files.exists(canonical)) throw new IllegalStateException("Cannot decode canonical saved data " + canonical);
        // Native file fixing moves selected vanilla files, not unknown mod files.
        // Import the same dimension's original flat payload once, retaining it,
        // and let normal dirty-data saving write the canonical Skycraft ID.
        for (Path legacy : legacyFiles(worldRoot, dimension, name)) {
            if (!Files.isRegularFile(legacy)) continue;
            try {
                CompoundTag root = NbtIo.readCompressed(legacy, NbtAccounter.unlimitedHeap());
                value = load.apply(root.getCompoundOrEmpty("data"));
                storage.set(type, value);
                return value;
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot migrate saved data " + legacy, exception);
            }
        }
        return null;
    }

    static List<Path> legacyFiles(Path worldRoot, ResourceKey<Level> dimension, String name) {
        Path root = worldRoot.toAbsolutePath().normalize();
        var files = new LinkedHashSet<Path>();
        Path nativeData = DimensionType.getStorageFolder(dimension, root).resolve("data");
        addLegacyFiles(files, nativeData, name);
        // 1.20.1 stored the vanilla dimensions outside dimensions/<namespace>.
        // Other mod dimensions already used their current native folder.
        if (dimension.equals(Level.OVERWORLD)) addLegacyFiles(files, root.resolve("data"), name);
        else if (dimension.equals(Level.NETHER)) addLegacyFiles(files, root.resolve("DIM-1/data"), name);
        else if (dimension.equals(Level.END)) addLegacyFiles(files, root.resolve("DIM1/data"), name);
        return List.copyOf(files);
    }

    private static void addLegacyFiles(LinkedHashSet<Path> files, Path data, String name) {
        Path base = data.normalize();
        // Also accept the native default namespace used by intermediate ports;
        // prefer that newer location over the original flat file in its folder.
        for (Path directory : List.of(base.resolve("minecraft"), base)) {
            Path file = directory.resolve(name + ".dat").normalize();
            if (!file.startsWith(directory.normalize())) throw new IllegalArgumentException("Invalid saved-data filename " + name);
            files.add(file);
        }
    }
}
