package com.skycraft.dungeons.world;

import com.skycraft.dungeons.DungeonsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * Every part of every dungeon: rooms, corridors, the entrance tunnel, the surface portal and whole surface sites.
 * A piece is pure data (theme, kind, box, a seed and a few ints); {@link #postProcess} hands it to the builder for
 * its theme, which paints it chunk by chunk through a {@link Painter}.
 */
public class DungeonPiece extends StructurePiece {
    /** Saved by ordinal: append only. SHAFT: the ladder shaft of a boss chamber's back exit. */
    public enum Kind { ROOM, CORRIDOR, TUNNEL, PORTAL, SURFACE, SHAFT }

    public static final int ROLE_START = 0, ROLE_NORMAL = 1, ROLE_BOSS = 2, ROLE_PUZZLE = 3;
    public static final int FLAG_TREASURE = 1, FLAG_DEAD_END = 2, FLAG_TRAP = 4, FLAG_OPEN_TOP = 8;
    /** SURFACE: the structure levels the ground with terrain adaptation; the box starts at ground level. */
    public static final int FLAG_PAD = 16;
    /** ROOM: the side branch's adventurer's cache. */
    public static final int FLAG_CACHE = 32;
    /** ROOM: environmental story (a dead adventurer and their journal). */
    public static final int FLAG_STORY = 64;
    /** ROOM: the theme's set piece (sarcophagus hall, steam machinery hall, waterfall cavern...). */
    public static final int FLAG_SETPIECE = 128;
    /** ROOM: an elite guard waits here, before the gate (the mini-boss beat). */
    public static final int FLAG_ELITE = 256;
    /** ROOM: the ambush beat: sleepers that wake as the player crosses. */
    public static final int FLAG_AMBUSH = 512;
    /** CORRIDOR: barred across (a window into the boss chamber, seen before it is reached). */
    public static final int FLAG_BARRED = 1024;
    /** ROOM: a grand cavern ({@link GrandCavern}): one huge irregular chamber instead of a box or a single cavern. */
    public static final int FLAG_GRAND = 2048;

    public final Theme theme;
    /** Culture kit (palette and roster), {@link Kit#NONE} for the theme's own. */
    public final Kit kit;
    /** Named-site layout, {@link Special#NONE} for a procedural dungeon. */
    public final Special special;
    /** Site parameter: Word Wall shout, Standing Stone sign, Daedric Prince... (see {@link Site#param}). */
    public final int param;
    public final Kind kind;
    public final long seed;
    /** ROOM: walking level. CORRIDOR: floor at the low-coordinate end. TUNNEL/PORTAL: walking level at the bottom. SURFACE: ground level. */
    public final int floor;
    /**
     * CORRIDOR: floor at the high-coordinate end. TUNNEL/PORTAL: walking level at the surface. ROOM: 1 + the side of a
     * raised entrance landing (the vista), 0 for none. SHAFT: walking level at the surface hatch.
     */
    public final int aux;
    /** ROOM: side the room was entered from (2D data value, -1 = start). CORRIDOR: axis (0 = x, 1 = z). TUNNEL/PORTAL/SURFACE: facing. */
    public final int dir;
    /** ROOM: gated side (-1 none; the boss chamber's back door). TUNNEL/PORTAL: depth. */
    public final int extra;
    public final int role;
    public final int doors;
    public final int variant;
    /** Dungeon-wide sub-theme (bandit cave vs animal den, ...). */
    public final int flavor;
    public final int flags;
    /** The named place this piece belongs to ({@link Site#place}), "" for random sites. */
    public final String place;

    public DungeonPiece(Site site,Kind kind, BoundingBox box, long seed, int floor, int aux, int dir, int extra,
                        int role, int doors, int variant, int flags) {
        super(DungeonsRegistry.DUNGEON_PIECE.get(), 0, box);
        this.theme = site.theme();
        this.kit = site.kit();
        this.special = site.special();
        this.param = site.param();
        this.place = site.place();
        int flavor = site.flavor();
        this.kind = kind;
        this.seed = seed;
        this.floor = floor;
        this.aux = aux;
        this.dir = dir;
        this.extra = extra;
        this.role = role;
        this.doors = doors;
        this.variant = variant;
        this.flavor = flavor;
        this.flags = flags;
    }

    public DungeonPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(DungeonsRegistry.DUNGEON_PIECE.get(), tag);
        this.theme = Theme.byOrdinal(tag.getIntOr("Theme", 0));
        this.kit = Kit.byOrdinal(tag.getIntOr("Kit", 0));
        this.special = Special.byOrdinal(tag.getIntOr("Special", 0));
        this.param = tag.getIntOr("Param", 0);
        Kind[] kinds = Kind.values();
        int k = tag.getIntOr("Kind", 0);
        this.kind = k >= 0 && k < kinds.length ? kinds[k] : Kind.ROOM;
        this.seed = tag.getLongOr("Seed", 0L);
        this.floor = tag.getIntOr("Floor", 0);
        this.aux = tag.getIntOr("Aux", 0);
        this.dir = tag.getIntOr("Dir", 0);
        this.extra = tag.getIntOr("Extra", 0);
        this.role = tag.getIntOr("Role", 0);
        this.doors = tag.getIntOr("Doors", 0);
        this.variant = tag.getIntOr("Variant", 0);
        this.flavor = tag.getIntOr("Flavor", 0);
        this.flags = tag.getIntOr("Flags", 0);
        this.place = tag.getStringOr("Place", "");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("Theme", theme.ordinal());
        tag.putInt("Kit", kit.ordinal());
        tag.putInt("Special", special.ordinal());
        tag.putInt("Param", param);
        tag.putInt("Kind", kind.ordinal());
        tag.putLong("Seed", seed);
        tag.putInt("Floor", floor);
        tag.putInt("Aux", aux);
        tag.putInt("Dir", dir);
        tag.putInt("Extra", extra);
        tag.putInt("Role", role);
        tag.putInt("Doors", doors);
        tag.putInt("Variant", variant);
        tag.putInt("Flavor", flavor);
        tag.putInt("Flags", flags);
        if (!place.isEmpty()) tag.putString("Place", place);
    }

    /** The site this piece belongs to (for builders that hand it on). */
    public Site site() {
        return new Site(theme, kit, special, flavor, param, place);
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public Direction facing() {
        return Direction.from2DDataValue(dir);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
        paint(level, chunkBox);
        // entrances and surface works cut trees: take the crowns and stubs they left hanging with them
        int cx = (chunkBox.minX() + chunkBox.maxX()) >> 1, cz = (chunkBox.minZ() + chunkBox.maxZ()) >> 1;
        if (boundingBox.maxY() >= level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, cx, cz) - 8) {
            com.skycraft.world.OrphanSweep.sweep(level, Math.max(boundingBox.minX(), chunkBox.minX()), Math.max(boundingBox.minZ(), chunkBox.minZ()),
                    Math.min(boundingBox.maxX(), chunkBox.maxX()), Math.min(boundingBox.maxZ(), chunkBox.maxZ()), 8,
                    chunkBox.minX() - 14, chunkBox.minZ() - 14, chunkBox.maxX() + 14, chunkBox.maxZ() + 14, null);
        }
    }

    private void paint(WorldGenLevel level, BoundingBox chunkBox) {
        Painter p = new Painter(level, chunkBox, this.boundingBox, seed);
        if (p.empty()) return;
        p.kit = kit;
        p.place = place;
        RandomSource r = RandomSource.create(seed);
        if (theme == Theme.BLACKREACH) {
            Blackreach.build(p, this);
            return;
        }
        if (theme == Theme.STEPS) {
            Steps.build(p, this);
            return;
        }
        if (special == Special.MARQUEE && (kind == Kind.ROOM || kind == Kind.CORRIDOR)) {
            if (kind == Kind.ROOM) com.skycraft.dungeons.marquee.MarqueeBuilder.room(p, this);
            else com.skycraft.dungeons.marquee.MarqueeBuilder.corridor(p, this);
            return;
        }
        switch (kind) {
            case ROOM -> {
                Room room = new Room(this);
                if (has(FLAG_GRAND)) GrandCavern.build(p, this);
                else if (theme.natural()) NaturalBuilder.room(p, room, r);
                else BuiltBuilder.room(p, room, r);
            }
            case CORRIDOR -> {
                if (theme.natural()) NaturalBuilder.corridor(p, this, r);
                else BuiltBuilder.corridor(p, this, r);
            }
            case TUNNEL -> {
                if (theme.natural()) NaturalBuilder.tunnel(p, this, r);
                else BuiltBuilder.tunnel(p, this, r);
            }
            case PORTAL -> {
                Portals.build(p, this, r);
                Identity.entrance(p, this);
            }
            case SHAFT -> Shafts.build(p, this);
            case SURFACE -> {
                SurfaceBuilder.build(p, this, r);
                Identity.surfaceMarker(p, this);
            }
        }
    }

    // ------------------------------------------------------------------ shared geometry

    /** Corridor length (blocks between the two rooms' walls). */
    public int corridorLength() {
        return dir == 0 ? boundingBox.getXSpan() : boundingBox.getZSpan();
    }

    /**
     * Walking level at step {@code k} (0 = low-coordinate end) of a corridor. All the climbing happens strictly inside
     * the corridor, one block per step, centred on its length.
     */
    public int corridorFloor(int k) {
        int len = corridorLength();
        int dy = aux - floor;
        int ady = Math.abs(dy);
        if (ady == 0) return floor;
        int s0 = Math.max(0, (len - 1 - ady) / 2);
        int step = Math.max(0, Math.min(ady, k - s0));
        return floor + Integer.signum(dy) * step;
    }

    /** Tunnel length: depth + 4 positions out of the start room. */
    public int tunnelLength() {
        return extra + 4;
    }

    /** Walking level at tunnel position {@code t} (1 = next to the start room, {@link #tunnelLength()} = surface). */
    public int tunnelFloor(int t) {
        return floor + Math.max(0, Math.min(extra, t - 2));
    }

    /**
     * Tunnel/portal frame: maps (t = distance out of the start room's wall, lateral offset) to world x/z.
     * For TUNNEL and PORTAL pieces {@link #variant} holds the wall's axis coordinate and {@link #doors} the lateral centre.
     */
    public int frameX(int t, int lat) {
        Direction out = facing();
        if (out.getAxis() == Direction.Axis.X) return variant + t * out.getStepX();
        return doors + lat * lateralStep(out);
    }

    public int frameZ(int t, int lat) {
        Direction out = facing();
        if (out.getAxis() == Direction.Axis.Z) return variant + t * out.getStepZ();
        return doors + lat * lateralStep(out);
    }

    /** Converts world x/z to tunnel position t. */
    public int frameT(int x, int z) {
        Direction out = facing();
        return out.getAxis() == Direction.Axis.X ? (x - variant) * out.getStepX() : (z - variant) * out.getStepZ();
    }

    /** Converts world x/z to the lateral offset. */
    public int frameLat(int x, int z) {
        Direction out = facing();
        return out.getAxis() == Direction.Axis.X ? (z - doors) * lateralStep(out) : (x - doors) * lateralStep(out);
    }

    /** Lateral axis = out rotated clockwise; its step sign along the lateral world axis. */
    private static int lateralStep(Direction out) {
        Direction right = out.getClockWise();
        return right.getAxis() == Direction.Axis.X ? right.getStepX() : right.getStepZ();
    }

    /** The world direction of positive lateral offsets. */
    public Direction right() {
        return facing().getClockWise();
    }
}
