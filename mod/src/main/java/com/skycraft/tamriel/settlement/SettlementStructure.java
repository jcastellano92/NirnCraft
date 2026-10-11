package com.skycraft.tamriel.settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.tamriel.settlement.plan.Layouts;
import com.skycraft.tamriel.settlement.plan.Plan;
import com.skycraft.tamriel.settlement.plan.Spec;
import com.skycraft.tamriel.settlement.plan.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code skycraft:settlement}: every Skycraft settlement, from a farm to the Imperial City. Fields:
 * <ul>
 *     <li>{@code source}: {@code place} (a named place in its anchor chunk, via {@code skycraft:places}),
 *     {@code district} (an extra start of a big named city, via {@code skycraft:settlement_districts}) or
 *     {@code generic} (random spread; culture from the region)</li>
 *     <li>{@code size}: {@code city}, {@code town}, {@code village}, {@code hamlet}, {@code farm}, {@code inn},
 *     {@code homestead}</li>
 *     <li>{@code culture}: {@code auto} (place, then region) or a culture id</li>
 * </ul>
 * {@link #findGenerationPoint} reads noise heights only; the plan is built lazily in the stub's piece builder.
 */
public class SettlementStructure extends Structure {
    public static final Codec<SettlementStructure> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            settingsCodec(instance),
            Codec.STRING.optionalFieldOf("culture", "auto").forGetter(s -> s.culture),
            Codec.STRING.fieldOf("size").forGetter(s -> s.size.name().toLowerCase(Locale.ROOT)),
            Codec.STRING.optionalFieldOf("source", "place").forGetter(s -> s.source)
    ).apply(instance, SettlementStructure::new));

    /** Skyrim's authored extent: no generic villages or inns inside it (it has its own). */
    private static final int SKYRIM_MIN_X = -3300, SKYRIM_MAX_X = 3000, SKYRIM_MIN_Z = -2600, SKYRIM_MAX_Z = 2000;

    public final String culture;
    public final Layouts.Size size;
    public final String source;

    /** Plans of named places, shared by their anchor and district starts. */
    private static final Map<String, List<Spec>> PLANS = new ConcurrentHashMap<>();

    public SettlementStructure(StructureSettings settings, String culture, String size, String source) {
        super(settings);
        this.culture = culture;
        this.size = Layouts.Size.of(size);
        this.source = source;
    }

    @Override
    public StructureType<?> type() {
        return SettlementStructures.SETTLEMENT.get();
    }

    /** Settlements choose their own sites (named places, region culture); the biome filter does not apply. */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext ctx) {
        return findGenerationPoint(ctx);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        try {
            return switch (source) {
                case "district" -> district(ctx);
                case "generic" -> generic(ctx);
                case "site" -> site(ctx);
                default -> named(ctx);
            };
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Settlement generation failed at chunk {}", ctx.chunkPos(), e);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ named places

    /** Whether this structure builds the named place (size groups never overlap, so one structure per place). */
    boolean builds(Place p) {
        String v = p.variant();
        return switch (size) {
            case CITY -> p.type() == PlaceType.CAPITAL || p.type() == PlaceType.CITY;
            case TOWN -> p.type() == PlaceType.TOWN && !v.equals("inn");
            case VILLAGE, HAMLET -> p.type() == PlaceType.VILLAGE && !v.equals("inn");
            case INN -> (p.type() == PlaceType.VILLAGE || p.type() == PlaceType.TOWN) && v.equals("inn");
            case FARM -> p.type() == PlaceType.FARM && !v.equals("homestead");
            case HOMESTEAD -> p.type() == PlaceType.FARM && v.equals("homestead");
            case SITE -> false;
        };
    }

    private Optional<GenerationStub> named(GenerationContext ctx) {
        Place place = null;
        for (Place p : Places.inChunk(ctx.chunkPos())) {
            if (builds(p)) {
                place = p;
                break;
            }
        }
        if (place == null) return Optional.empty();
        Place fp = place;
        int y = ctx.chunkGenerator().getBaseHeight(place.x(), place.z(), net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                ctx.heightAccessor(), ctx.randomState());
        return Optional.of(new GenerationStub(new BlockPos(place.x(), y, place.z()),
                builder -> addPieces(builder, fp, plan(ctx, fp), 0, 0, true)));
    }

    private Optional<GenerationStub> district(GenerationContext ctx) {
        DistrictPlacement.District d = DistrictPlacement.at(ctx.chunkPos());
        if (d == null || !builds(d.place())) return Optional.empty();
        Place p = d.place();
        List<Spec> specs = plan(ctx, p);
        // only start if this district actually holds pieces
        long want = Plan.pack(d.ix(), d.iz());
        boolean any = false;
        for (Spec s : specs) {
            if (Plan.district(s, p.x(), p.z()) == want) {
                any = true;
                break;
            }
        }
        if (!any) return Optional.empty();
        ChunkPos cp = ctx.chunkPos();
        return Optional.of(new GenerationStub(new BlockPos(cp.getMiddleBlockX(), ctx.chunkGenerator().getSeaLevel(), cp.getMiddleBlockZ()),
                builder -> addPieces(builder, p, specs, d.ix(), d.iz(), false)));
    }

    private static void addPieces(StructurePiecesBuilder builder, Place p, List<Spec> specs, int ix, int iz, boolean anchor) {
        boolean split = DistrictPlacement.cells(Cities.reach(p)) > 0;
        long want = Plan.pack(ix, iz);
        long seed = Cities.seed(p);
        int n = 0;
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            if (split) {
                if (Plan.district(s, p.x(), p.z()) != want) continue;
            } else if (!anchor) {
                continue;
            }
            builder.addPiece(new SettlementPiece(s, seed ^ (i * 0x9E3779B97F4A7C15L), p.id(), i));
            n++;
        }
        if (anchor && n == 0) Skycraft.LOGGER.warn("Settlement {} produced no pieces", p.id());
    }

    /** The plan of a named place (cached per place and world seed: every start of the place sees the same one). */
    private List<Spec> plan(GenerationContext ctx, Place p) {
        String key = p.id() + "@" + ctx.seed() + "@" + System.identityHashCode(ctx.chunkGenerator());
        List<Spec> cached = PLANS.get(key);
        if (cached != null) return cached;
        long t0 = System.nanoTime();
        Plan plan = planPlace(ctx, p, culture);
        List<Spec> specs = List.copyOf(plan.specs);
        if (PLANS.size() > 64) PLANS.clear();
        PLANS.put(key, specs);
        Skycraft.LOGGER.info("Planned settlement {} ({} pieces, culture {}, {} ms)", p.id(), specs.size(), plan.culture.id(),
                (System.nanoTime() - t0) / 1_000_000);
        return specs;
    }

    /** Plans a named place on the terrain of a generation context (the structure path and the dev tools share it). */
    public static Plan planPlace(GenerationContext ctx, Place p, String cultureOverride) {
        Culture c = Culture.of(cultureOverride.equals("auto") ? p.culture() : cultureOverride);
        int style = Cities.style(p, c);
        Plan plan = new Plan(p.id(), p.x(), p.z(), Cities.layoutRadius(p), c, style, Cities.seed(p),
                new Terrain(ctx, Cities.layoutRadius(p) > 60 ? 16 : 8), Cities.back(p));
        // the zoning planner also gives the hold's jail, the faction halls (plan/Halls) and the provincial player-home
        // plots (plan/HousePlots) their lots, before it packs the streets with houses
        com.skycraft.tamriel.settlement.grammar.Zoning.place(plan, p);
        plan.resolve();
        com.skycraft.tamriel.settlement.plan.Halls.report(plan, p);
        return plan;
    }

    // ------------------------------------------------------------------ generic settlements

    private Optional<GenerationStub> generic(GenerationContext ctx) {
        ChunkPos cp = ctx.chunkPos();
        int x = cp.getMiddleBlockX(), z = cp.getMiddleBlockZ();
        int R = size.radius();
        boolean skyrim = x > SKYRIM_MIN_X && x < SKYRIM_MAX_X && z > SKYRIM_MIN_Z && z < SKYRIM_MAX_Z;
        if (skyrim && size != Layouts.Size.FARM) return Optional.empty();
        Region region = TamrielMap.region(x, z);
        if ("sea".equals(region.province()) || "sea".equals(region.kind()) || "none".equals(region.culture())) return Optional.empty();
        if (!Places.overlapping(x - R - 96, z - R - 96, x + R + 96, z + R + 96).isEmpty()) return Optional.empty();
        Terrain t = new Terrain(ctx);
        int h = t.land(x, z);
        if (h <= t.sea || t.wet(x, z)) return Optional.empty();
        int half = Math.max(8, com.skycraft.tamriel.settlement.grammar.Zoning.builtRadius(size, R) / 2);
        if (t.anyWet(x, z, half, half) && size != Layouts.Size.FARM) return Optional.empty();
        if (t.spread(x, z, half, half) > (size == Layouts.Size.CITY ? 18 : 12)) return Optional.empty();
        Culture c = Culture.of(culture.equals("auto") ? region.culture() : culture);
        String hint = (culture.equals("auto") ? region.culture() : culture) + " " + region.id();
        int style = c == Culture.NORD ? Layouts.nordStyle(region.id()) : c == Culture.DUNMER ? Culture.dunmerStyle(hint) : 0;
        long seed = ctx.seed() ^ cp.toLong() * 0x5DEECE66DL ^ size.ordinal();
        String key = "gen:" + cp.x + "," + cp.z;
        Direction back = Direction.from2DDataValue((int) Math.floorMod(seed >>> 7, 4));
        return Optional.of(new GenerationStub(new BlockPos(x, h, z), builder -> {
            Plan plan = new Plan(key, x, z, R, c, style, seed, t, back);
            com.skycraft.tamriel.settlement.grammar.Zoning.generic(plan, size, "", Layouts.Names.NONE);
            plan.resolve();
            for (int i = 0; i < plan.specs.size(); i++) {
                builder.addPiece(new SettlementPiece(plan.specs.get(i), seed ^ (i * 0x9E3779B97F4A7C15L), key, i));
            }
        }));
    }

    // ------------------------------------------------------------------ outlying working sites

    /** A working site near a settlement or on a road (logging camp, mine, farmstead, vineyard, waystation...). */
    private Optional<GenerationStub> site(GenerationContext ctx) {
        ChunkPos cp = ctx.chunkPos();
        int x = cp.getMiddleBlockX(), z = cp.getMiddleBlockZ();
        Terrain t = new Terrain(ctx);
        long seed = ctx.seed() ^ cp.toLong() * 0x2545F4914F6CDD1DL ^ 0x517EL;
        com.skycraft.tamriel.settlement.grammar.Sites.Choice choice = com.skycraft.tamriel.settlement.grammar.Sites.choose(seed, x, z, t);
        if (choice == null) return Optional.empty();
        int h = t.land(x, z);
        String key = "site:" + cp.x + "," + cp.z;
        return Optional.of(new GenerationStub(new BlockPos(x, h, z), builder -> {
            Plan plan = new Plan(key, x, z, Layouts.Size.SITE.radius(), choice.culture(), choice.style(), seed, t, choice.back());
            com.skycraft.tamriel.settlement.grammar.Sites.layout(plan, choice.kind());
            plan.resolve();
            for (int i = 0; i < plan.specs.size(); i++) {
                builder.addPiece(new SettlementPiece(plan.specs.get(i), seed ^ (i * 0x9E3779B97F4A7C15L), key, i));
            }
        }));
    }

    static int defaultStyle(Culture c, Region r) {
        if (c == Culture.NORD) return Layouts.nordStyle(r.id());
        if (c == Culture.DUNMER) return Culture.dunmerStyle(r.id());
        return Kits.NORD_VILLAGE;
    }
}
