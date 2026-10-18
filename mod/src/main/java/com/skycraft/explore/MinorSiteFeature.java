package com.skycraft.explore;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import com.skycraft.explore.build.Camps;
import com.skycraft.explore.build.Nature;
import com.skycraft.explore.build.Palette;
import com.skycraft.explore.build.Remains;
import com.skycraft.explore.build.Shrines;
import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.world.LocationTags;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Builds the minor site whose centre lies in the decorated chunk ({@link MinorSites#inChunk}). Placed once per
 * chunk (count 1, no spreading) in every overworld biome at the last decoration step, so it sits on top of the
 * trees and plants it clears. Sites give way to surface structures already planned over the chunk (towns, camps,
 * forts), except unique sites and treasure caches, which treasure maps and notes point to.
 */
public class MinorSiteFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public MinorSiteFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos chunk = ChunkPos.containing(ctx.origin());
        MinorSites.Site site = MinorSites.inChunk(level.getSeed(), chunk.x(), chunk.z());
        if (site == null) return false;
        boolean pinned = site.unique() != null || site.kind() == SiteKind.TREASURE_CACHE;
        if (!pinned && surfaceStructureHere(level, chunk)) return false;
        RandomSource random = RandomSource.create(site.seed());
        SiteBuilder b = new SiteBuilder(level, random, site, Palette.of(site.province(), archetype(site)));
        // nothing is built in water: the map keeps sites off it, this catches what the real terrain adds (docks excepted)
        // (wrecks lie at the waterline by nature: the builder only writes to dry blocks there)
        boolean shore = site.kind() == SiteKind.FISHING_DOCK || site.kind() == SiteKind.SHIPWRECK_DEBRIS;
        if (!shore && b.footprintWet(site.kind().radius)) return false;
        boolean built;
        try {
            built = build(b);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft explore: failed to build {}", MinorSites.describe(site), e);
            return false;
        }
        if (built && com.skycraft.landmarks.Grounding.enabled) {
            // post-placement support check (same as the landmarks): whatever hovers is pillared down to the ground or removed
            try {
                int pad = site.kind().radius + 4;
                int minX = Math.max(site.x() - pad, (chunk.x() - 1) << 4), maxX = Math.min(site.x() + pad, ((chunk.x() + 1) << 4) + 15);
                int minZ = Math.max(site.z() - pad, (chunk.z() - 1) << 4), maxZ = Math.min(site.z() + pad, ((chunk.z() + 1) << 4) + 15);
                com.skycraft.landmarks.Grounding.fix(level, minX, minZ, maxX, maxZ, b::writable, b.pal.stone().defaultBlockState());
            } catch (RuntimeException e) {
                Skycraft.LOGGER.debug("Skycraft explore: support check failed for {}", MinorSites.describe(site), e);
            }
        }
        if (built && site.unique() != null && b.mainChest != null) {
            b.addItem(b.mainChest, Uniques.stack(site.unique()));
            Uniques.UniqueSite u = Uniques.get(site.unique());
            if (u != null && u.note() != null) {
                LoreBooks.Book note = LoreBooks.get(u.note());
                if (note != null) b.addItem(b.mainChest, LoreBookItem.create(note));
            }
        }
        return built;
    }

    static boolean build(SiteBuilder b) {
        return switch (b.site.kind()) {
            case ABANDONED_CAMP -> Camps.abandonedCamp(b);
            case HUNTERS_CAMP -> Camps.huntersCamp(b);
            case HERMIT_HUT -> Camps.hermitHut(b);
            case BANDIT_LOOKOUT -> Camps.banditLookout(b);
            case FISHING_DOCK -> Camps.fishingDock(b);
            case WAYSHRINE -> Shrines.wayshrine(b);
            case STONE_CIRCLE -> Shrines.stoneCircle(b);
            case BRETON_MENHIR -> Shrines.bretonMenhir(b);
            case NORDIC_CAIRN -> Shrines.nordicCairn(b);
            case AYLEID_WELL -> Shrines.ayleidWell(b);
            case VELOTHI_SHRINE -> Shrines.velothiShrine(b);
            case YOKUDAN_OBELISK -> Shrines.yokudanObelisk(b);
            case AKAVIRI_SHRINE -> Shrines.akaviriShrine(b);
            case SEA_SHRINE -> Shrines.seaShrine(b);
            case REALM_MONOLITH -> Shrines.realmMonolith(b);
            case TREASURE_CACHE -> Remains.treasureCache(b);
            case DEAD_ADVENTURER -> Remains.deadAdventurer(b);
            case ANCIENT_BATTLEFIELD -> Remains.ancientBattlefield(b);
            case SHIPWRECK_DEBRIS -> Remains.shipwreckDebris(b);
            case OVERLOOK -> Nature.overlook(b);
            case MINERAL_VEIN -> Nature.mineralVein(b);
            case HERB_GARDEN -> Nature.herbGarden(b);
            case RARE_REAGENT -> Nature.rareReagent(b);
            case HOT_SPRING -> Nature.hotSpring(b);
            case HIST_SAPLING -> Nature.histSapling(b);
            case MOON_SUGAR_FIELD -> Nature.moonSugarField(b);
            case GIANT_BEEHIVE -> Nature.giantBeehive(b);
            case CRYSTAL_OUTCROP -> Nature.crystalOutcrop(b);
            case DWEMER_VENT -> Nature.dwemerVent(b);
            case MAGE_RUIN -> Nature.mageRuin(b);
        };
    }

    static String archetype(MinorSites.Site site) {
        String pal = com.skycraft.tamriel.TamrielMap.region(site.x(), site.z()).palette();
        return pal != null && pal.startsWith("realm_") ? pal.substring(6) : "";
    }

    /**
     * True when a surface structure (a town, camp, fort or ruin above ground) has a piece over this chunk. Only the
     * chunk's own references are read: they are complete by the decoration step and need no other chunk.
     */
    static boolean surfaceStructureHere(WorldGenLevel level, ChunkPos chunk) {
        ChunkAccess access = level.getChunk(chunk.x(), chunk.z());
        var refs = access.getAllReferences();
        if (refs.isEmpty()) return false;
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (var e : refs.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Structure s = e.getKey();
            if (s.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) return true;
            Holder<Structure> h = registry.wrapAsHolder(s);
            if (h.is(LocationTags.TOWNS)) return true;
        }
        return false;
    }
}
