package com.skycraft.world;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The structure tags that decide what a structure "is" for Skycraft. Every system asks these (through
 * {@link LocationKind#classify(Holder, ResourceKey)} or {@link #is}) instead of guessing from registry names:
 * <ul>
 *     <li>{@code #skycraft:towns}: inhabited towns, villages and taverns (settlers, innkeepers, Jarls, first spawn)</li>
 *     <li>{@code #skycraft:road_destinations}: towns plus the dungeons that roads, signposts and the compass lead to</li>
 *     <li>{@code #skycraft:dungeons}: places to clear (quests, homestead distance, loot)</li>
 *     <li>{@code #skycraft:ignored_landmarks}: decoration that is never a discoverable location</li>
 *     <li>{@code #skycraft:settlements}: legacy alias of {@code #skycraft:towns}</li>
 * </ul>
 */
public final class LocationTags {
    public static final TagKey<Structure> TOWNS = tag("towns");
    public static final TagKey<Structure> ROAD_DESTINATIONS = tag("road_destinations");
    public static final TagKey<Structure> DUNGEONS = tag("dungeons");
    public static final TagKey<Structure> IGNORED_LANDMARKS = tag("ignored_landmarks");

    private LocationTags() {}

    private static TagKey<Structure> tag(String path) {
        return TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Skycraft.MODID, path));
    }

    /** The registry holder of a structure, if it is registered. */
    public static Optional<Holder.Reference<Structure>> holder(RegistryAccess access, Structure structure) {
        Registry<Structure> registry = access.lookupOrThrow(Registries.STRUCTURE);
        return registry.getResourceKey(structure).flatMap(registry::get);
    }

    /** The registry holder of a structure id, if it is registered. */
    public static Optional<Holder.Reference<Structure>> holder(RegistryAccess access, Identifier id) {
        return access.lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, id));
    }

    public static boolean is(RegistryAccess access, Structure structure, TagKey<Structure> tag) {
        return holder(access, structure).map(h -> h.is(tag)).orElse(false);
    }

    public static boolean is(RegistryAccess access, Identifier id, TagKey<Structure> tag) {
        return holder(access, id).map(h -> h.is(tag)).orElse(false);
    }

    /**
     * True if a piece of a structure in {@code tag} covers {@code pos}. Only answers for loaded positions on the server
     * thread (never loads or generates chunks); false otherwise.
     */
    public static boolean pieceAt(ServerLevel level, BlockPos pos, TagKey<Structure> tag) {
        if (!level.getServer().isSameThread() || !level.isLoaded(pos)) return false;
        try {
            return level.structureManager().getStructureWithPieceAt(pos, tag).isValid();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@link LocationKind#classify(Holder, ResourceKey)} for a structure instance; null if unregistered or ignored. */
    @Nullable
    public static LocationKind classify(ServerLevel level, Structure structure) {
        return holder(level.registryAccess(), structure).map(h -> LocationKind.classify(h, level.dimension())).orElse(null);
    }
}
