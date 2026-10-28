package com.nirncraft.atlas;

import com.nirncraft.tamriel.settlement.SettlementPiece;
import com.nirncraft.tamriel.settlement.SettlementStructure;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The structure starts the overlays stamp beside the street plan's settlements: the seeded settlements (generic
 * settlements and working sites, random spread) and the dungeons (the wilds' random spread and the named places'
 * dungeons), found as the server finds them (ChunkGenerator.createStructures / tryGenerateStructure): the candidate
 * chunks (a random-spread set's potential chunk per spacing cell; a places set's places' chunks), the placement's
 * isStructureChunk (frequency and exclusion zones), the weighted try-in-turn choice among the set's structures, then
 * Structure.generate (findValidGenerationPoint and the pieces). Planning on the CPU, once per start (cached here; the
 * tapes recorded from its pieces are cached on disk). Not here: the site lists' sets (nirncraft:sites: the Steps,
 * Blackreach's tiles).
 */
final class AtlasOverlayStarts {
    /**
     * A start: its key (the tapes' file and the overlays' id), the structure, its source chunk, its pieces and the
     * overlays' layer its ops go to (AtlasOverlayTapes.LAYER_*: the structure's decoration step).
     */
    record Start(String key, String structure, ChunkPos chunk, List<StructurePiece> pieces, BoundingBox box, int layer) {
        /** The pieces as settlement pieces (a settlement start's: all of them). */
        List<SettlementPiece> settlementPieces() {
            List<SettlementPiece> out = new ArrayList<>(pieces.size());
            for (StructurePiece p : pieces) if (p instanceof SettlementPiece sp) out.add(sp);
            return out;
        }
    }

    /** Settlements the random spread places (SettlementStructure source generic or site). */
    static final Predicate<Structure> SEEDED_SETTLEMENTS = s -> s instanceof SettlementStructure ss
            && ("generic".equals(ss.source) || "site".equals(ss.source));

    /** The dungeons (DungeonStructure: the wilds' random spread and the named places'). */
    static final Predicate<Structure> DUNGEONS = s -> s instanceof com.nirncraft.dungeons.world.DungeonStructure;

    /** Everything the overlays stamp beside the street plan. */
    static final Predicate<Structure> ALL = SEEDED_SETTLEMENTS.or(DUNGEONS);

    /** How far a start's pieces reach from its chunk (a generic city's radius and its fields, with room). */
    static final int REACH = 320;

    private final RegistryAccess access;
    private final ChunkGenerator generator;
    private final RandomState random;
    private final Climate.Sampler climate;
    private final ChunkGeneratorStructureState state;
    private final StructureTemplateManager templates;
    private final LevelHeightAccessor heights;
    private final long seed;
    private final List<Holder<StructureSet>> sets = new ArrayList<>();
    private final List<Map<Long, List<Start>>> cache = new ArrayList<>();
    private static final List<Start> NONE = List.of();

    AtlasOverlayStarts(RegistryAccess access, ChunkGenerator generator, ChunkGeneratorStructureState state, StructureTemplateManager templates,
                       LevelHeightAccessor heights, Predicate<Structure> which) {
        this.access = access;
        this.generator = generator;
        this.state = state;
        this.random = state.randomState();
        this.climate = this.random.createClimateSampler(SamplerContext.builder().enableCaches().build());
        this.templates = templates;
        this.heights = heights;
        this.seed = state.getLevelSeed();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            var placement = set.value().placement();
            if (!(placement instanceof RandomSpreadStructurePlacement) && !(placement instanceof com.nirncraft.tamriel.PlacesPlacement)) continue;
            boolean all = !set.value().structures().isEmpty();
            for (StructureSet.StructureSelectionEntry e : set.value().structures()) all &= which.test(e.structure().value());
            if (!all) continue;
            sets.add(set);
            cache.add(new ConcurrentHashMap<>());
        }
    }

    int sets() {
        return sets.size();
    }

    /** Every start whose source chunk's middle lies in the block box (x0, z0)..(x1, z1). */
    List<Start> in(int x0, int z0, int x1, int z1) {
        List<Start> out = new ArrayList<>();
        List<com.nirncraft.tamriel.Place> places = null;
        for (int si = 0; si < sets.size(); si++) {
            Holder<StructureSet> set = sets.get(si);
            Map<Long, List<Start>> memo = cache.get(si);
            if (set.value().placement() instanceof com.nirncraft.tamriel.PlacesPlacement pp) {
                // the places' chunks (isStructureChunk: a place this set builds in the chunk)
                if (places == null) places = com.nirncraft.tamriel.Places.overlapping(x0, z0, x1, z1);
                for (com.nirncraft.tamriel.Place p : places) {
                    if (!pp.matches(p)) continue;
                    ChunkPos chunk = new ChunkPos(p.x() >> 4, p.z() >> 4);
                    int bx = chunk.getMiddleBlockX(), bz = chunk.getMiddleBlockZ();
                    if (bx < x0 || bx > x1 || bz < z0 || bz > z1) continue;
                    out.addAll(memo.computeIfAbsent(chunk.pack(), k -> startsAt(set, chunk)));
                }
                continue;
            }
            RandomSpreadStructurePlacement rs = (RandomSpreadStructurePlacement) set.value().placement();
            int spacing = rs.spacing();
            int c0x = Math.floorDiv(x0 >> 4, spacing), c1x = Math.floorDiv(x1 >> 4, spacing);
            int c0z = Math.floorDiv(z0 >> 4, spacing), c1z = Math.floorDiv(z1 >> 4, spacing);
            for (int gx = c0x; gx <= c1x; gx++)
                for (int gz = c0z; gz <= c1z; gz++) {
                    ChunkPos chunk = rs.getPotentialStructureChunk(seed, gx * spacing, gz * spacing);
                    int bx = chunk.getMiddleBlockX(), bz = chunk.getMiddleBlockZ();
                    if (bx < x0 || bx > x1 || bz < z0 || bz > z1) continue;
                    out.addAll(memo.computeIfAbsent(chunk.pack(), k -> startsAt(set, chunk)));
                }
        }
        return out;
    }

    /** The start the set makes in the chunk (createStructures' path for one set), or none. */
    private List<Start> startsAt(Holder<StructureSet> set, ChunkPos chunk) {
        if (!set.value().placement().isStructureChunk(state, chunk.x(), chunk.z())) return NONE;
        List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.value().structures());
        if (options.size() == 1) return start(options.get(0), chunk, climate);
        WorldgenRandom pick = new WorldgenRandom(new LegacyRandomSource(0L));
        pick.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
        int total = 0;
        for (StructureSet.StructureSelectionEntry e : options) total += e.weight();
        while (!options.isEmpty()) {
            int roll = pick.nextInt(total);
            StructureSet.StructureSelectionEntry chosen = options.get(options.size() - 1);
            for (StructureSet.StructureSelectionEntry e : options) {
                roll -= e.weight();
                if (roll < 0) {
                    chosen = e;
                    break;
                }
            }
            List<Start> s = start(chosen, chunk, climate);
            if (!s.isEmpty()) return s;
            options.remove(chosen);
            total -= chosen.weight();
        }
        return NONE;
    }

    private List<Start> start(StructureSet.StructureSelectionEntry entry, ChunkPos chunk, Climate.Sampler climate) {
        Structure structure = entry.structure().value();
        StructureStart start;
        try {
            start = structure.generate(entry.structure(), Level.OVERWORLD, access, generator, generator.getBiomeSource(), climate, random,
                    templates, seed, chunk, 0, heights, structure.biomes()::contains);
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.debug("atlas overlays: no start at {}", chunk, e);
            return NONE;
        }
        if (!start.isValid()) return NONE;
        List<StructurePiece> pieces = List.copyOf(start.getPieces());
        if (pieces.isEmpty()) return NONE;
        String name = entry.structure().unwrapKey().map(ResourceKey::identifier).map(Object::toString).orElse("?");
        String key = name.substring(name.indexOf(':') + 1).replace('/', '_') + "_" + chunk.x() + "_" + chunk.z();
        int layer = switch (structure.step()) {
            case UNDERGROUND_STRUCTURES -> AtlasOverlayTapes.LAYER_UNDERGROUND;
            case SURFACE_STRUCTURES -> AtlasOverlayTapes.LAYER_SURFACE;
            default -> AtlasOverlayTapes.LAYER_SETTLEMENTS;
        };
        return List.of(new Start(key, name, chunk, pieces, start.getBoundingBox(), layer));
    }
}
