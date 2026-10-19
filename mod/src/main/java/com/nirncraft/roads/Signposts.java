package com.nirncraft.roads;

import com.nirncraft.NirnCraft;
import com.nirncraft.quest.NoticeBoards;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.settlement.build.Canvas;
import com.nirncraft.tamriel.settlement.kit.Palette;
import com.nirncraft.tamriel.settlement.plan.Spec;
import com.nirncraft.worldplan.RoadPlan;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Directional signposting along Tamriel's roads, junctions, and settlement exits (MS13 — {@code F-SIGNPOST}, R3d-2.2).
 *
 * <p>Every road junction and settlement exit signpost points toward:</p>
 * <ol>
 *   <li><b>Next settlement along the road</b> (with approximate distance in blocks and travel time)</li>
 *   <li><b>Next roadside inn or town</b> along the route</li>
 *   <li><b>Any dungeon or landmark within {@value #LANDMARK_RADIUS} blocks</b></li>
 * </ol>
 *
 * <p>Right-clicking a directional signpost prints the full directional readout in chat and marks the referenced
 * settlements as {@code "known"} on the player's world map and compass.</p>
 */
public final class Signposts {
    /** Maximum distance (blocks) at which a nearby dungeon or landmark is listed on a road signpost. */
    public static final int LANDMARK_RADIUS = 600;
    /** Approximate walking speed in blocks per minute (~4.3 blocks/s = ~250 blocks/min) for travel-time estimates. */
    private static final int BLOCKS_PER_MINUTE = 250;

    private static final Set<PlaceType> SETTLEMENT_TYPES = EnumSet.of(
            PlaceType.CAPITAL, PlaceType.CITY, PlaceType.TOWN, PlaceType.VILLAGE
    );

    private static final Set<PlaceType> LANDMARK_TYPES = EnumSet.of(
            PlaceType.DUNGEON_FIXED, PlaceType.FORT, PlaceType.DAEDRIC_SHRINE,
            PlaceType.DRAGON_MOUND, PlaceType.WORD_WALL, PlaceType.STANDING_STONE, PlaceType.LANDMARK
    );

    private Signposts() {}

    /**
     * Computed directional readout for a signpost at world coordinates {@code (wx, wz)}.
     *
     * @param roadName       translation key of the named road (e.g. {@code road.nirncraft.gold_road}), or empty
     * @param nextSettlement primary settlement along the road
     * @param settlementDist distance in blocks to {@code nextSettlement}
     * @param settlementDir  8-point compass direction to {@code nextSettlement} (e.g. {@code "NE"})
     * @param nextInnOrTown  secondary roadside inn or town destination
     * @param innDist        distance in blocks to {@code nextInnOrTown}
     * @param innDir         8-point compass direction to {@code nextInnOrTown}
     * @param nearbyLandmark nearest dungeon or landmark within {@value #LANDMARK_RADIUS} blocks, or {@code null}
     * @param landmarkDist   distance in blocks to {@code nearbyLandmark}
     * @param landmarkDir    8-point compass direction to {@code nearbyLandmark}
     */
    public record Readout(
            String roadName,
            @Nullable Place nextSettlement,
            int settlementDist,
            String settlementDir,
            @Nullable Place nextInnOrTown,
            int innDist,
            String innDir,
            @Nullable Place nearbyLandmark,
            int landmarkDist,
            String landmarkDir
    ) {
        public int travelMinutes(int dist) {
            return Math.max(1, (int) Math.round(dist / (double) BLOCKS_PER_MINUTE));
        }
    }

    // ------------------------------------------------------------------ destination calculation

    /** Computes the 3-way directional readout for a signpost at world column {@code (wx, wz)}. */
    public static Readout computeAt(int wx, int wz) {
        Place current = currentSettlementAt(wx, wz);
        String currentId = current != null ? current.id() : "";

        String roadKey = "";
        Place nextSettlement = null;
        Place secondRoadSettlement = null;

        // 1. Query the baked road plan at (wx, wz) or immediately outside a settlement exit
        try {
            RoadPlan plan = RoadPlan.get();
            RoadPlan.Hit hit = new RoadPlan.Hit();
            boolean foundRoad = plan.at(wx, wz, hit);
            if (!foundRoad) {
                int[][] probes = {{16, 0}, {-16, 0}, {0, 16}, {0, -16}, {28, 0}, {-28, 0}, {0, 28}, {0, -28}};
                for (int[] p : probes) {
                    if (plan.at(wx + p[0], wz + p[1], hit)) {
                        foundRoad = true;
                        break;
                    }
                }
            }
            if (foundRoad && hit.link >= 0 && hit.link < plan.linkA.length) {
                String rawName = plan.linkName[hit.link];
                if (rawName != null && !rawName.isEmpty()) {
                    roadKey = "road.nirncraft." + rawName;
                }
                Place a = Places.byId(plan.linkA[hit.link]).orElse(null);
                Place b = Places.byId(plan.linkB[hit.link]).orElse(null);
                if (a != null && !a.id().equals(currentId) && dist(a, wx, wz) >= 45) {
                    nextSettlement = a;
                }
                if (b != null && !b.id().equals(currentId) && dist(b, wx, wz) >= 45) {
                    if (nextSettlement == null) {
                        nextSettlement = b;
                    } else if (!b.id().equals(nextSettlement.id())) {
                        // Pick the closer road endpoint as primary, the other as secondary
                        if (b.distSqr(wx, wz) < nextSettlement.distSqr(wx, wz)) {
                            secondRoadSettlement = nextSettlement;
                            nextSettlement = b;
                        } else {
                            secondRoadSettlement = b;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // RoadPlan may not be loaded in unit tests; fall back to Places spatial lookup
        }

        // Fallback for nextSettlement: nearest settlement outside the current one
        if (nextSettlement == null) {
            long bestD2 = Long.MAX_VALUE;
            for (Place p : Places.all()) {
                if (!SETTLEMENT_TYPES.contains(p.type()) || p.id().equals(currentId)) continue;
                long d2 = p.distSqr(wx, wz);
                if (d2 < 45L * 45L) continue;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    nextSettlement = p;
                }
            }
        }

        // 2. Next roadside inn or town (distinct from current and nextSettlement)
        Place nextInnOrTown = secondRoadSettlement;
        if (nextInnOrTown == null || nextInnOrTown.id().equals(currentId)
                || (nextSettlement != null && nextInnOrTown.id().equals(nextSettlement.id()))) {
            nextInnOrTown = null;
            long bestInnD2 = Long.MAX_VALUE;
            long bestTownD2 = Long.MAX_VALUE;
            Place bestInn = null;
            Place bestTown = null;
            for (Place p : Places.all()) {
                if (p.id().equals(currentId)) continue;
                if (nextSettlement != null && p.id().equals(nextSettlement.id())) continue;
                long d2 = p.distSqr(wx, wz);
                if (d2 < 45L * 45L) continue;
                if (NoticeBoards.isRoadsideInn(p)) {
                    if (d2 < bestInnD2) {
                        bestInnD2 = d2;
                        bestInn = p;
                    }
                } else if (p.type() == PlaceType.TOWN || p.type() == PlaceType.CITY
                        || p.type() == PlaceType.CAPITAL || p.type() == PlaceType.VILLAGE) {
                    if (d2 < bestTownD2) {
                        bestTownD2 = d2;
                        bestTown = p;
                    }
                }
            }
            // Prefer a roadside inn within 1,200 blocks if available, else the next town/village
            if (bestInn != null && bestInnD2 <= 1200L * 1200L) {
                nextInnOrTown = bestInn;
            } else {
                nextInnOrTown = bestTown != null ? bestTown : bestInn;
            }
        }

        // 3. Nearest dungeon or landmark within 600 blocks
        Place nearbyLandmark = null;
        long bestLandmarkD2 = (long) LANDMARK_RADIUS * LANDMARK_RADIUS;
        for (Place p : Places.all()) {
            if (!LANDMARK_TYPES.contains(p.type()) || p.id().equals(currentId)) continue;
            long d2 = p.distSqr(wx, wz);
            if (d2 <= bestLandmarkD2) {
                bestLandmarkD2 = d2;
                nearbyLandmark = p;
            }
        }

        int settleDist = dist(nextSettlement, wx, wz);
        String settleDir = nextSettlement != null ? compass8(wx, wz, nextSettlement.x(), nextSettlement.z()) : "N";
        int innDist = dist(nextInnOrTown, wx, wz);
        String innDir = nextInnOrTown != null ? compass8(wx, wz, nextInnOrTown.x(), nextInnOrTown.z()) : "N";
        int landmarkDist = dist(nearbyLandmark, wx, wz);
        String landmarkDir = nearbyLandmark != null ? compass8(wx, wz, nearbyLandmark.x(), nearbyLandmark.z()) : "N";

        return new Readout(roadKey, nextSettlement, settleDist, settleDir,
                nextInnOrTown, innDist, innDir, nearbyLandmark, landmarkDist, landmarkDir);
    }

    private static int dist(@Nullable Place p, int wx, int wz) {
        return p == null ? 0 : (int) Math.round(Math.sqrt(p.distSqr(wx, wz)));
    }

    @Nullable
    private static Place currentSettlementAt(int wx, int wz) {
        Place best = null;
        long bestD2 = Long.MAX_VALUE;
        for (Place p : Places.all()) {
            if (!SETTLEMENT_TYPES.contains(p.type()) && p.type() != PlaceType.FARM) continue;
            int r = Math.max(40, p.radius() + 16);
            long d2 = p.distSqr(wx, wz);
            if (d2 <= (long) r * r && d2 < bestD2) {
                bestD2 = d2;
                best = p;
            }
        }
        return best;
    }

    /** Returns an 8-point compass abbreviation ({@code N, NE, E, SE, S, SW, W, NW}) from {@code (x0, z0)} to {@code (x1, z1)}. */
    public static String compass8(int x0, int z0, int x1, int z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        // In Minecraft: -Z is North, +X is East, +Z is South, -X is West
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360.0;
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int idx = (int) Math.floor((angle + 22.5) / 45.0) & 7;
        return dirs[idx];
    }

    /** Returns an arrow symbol for an 8-point compass direction. */
    public static String arrowFor(String dir) {
        return switch (dir) {
            case "N" -> "↑";
            case "NE" -> "↗";
            case "E" -> "→";
            case "SE" -> "↘";
            case "S" -> "↓";
            case "SW" -> "↙";
            case "W" -> "←";
            case "NW" -> "↖";
            default -> "→";
        };
    }

    // ------------------------------------------------------------------ worldgen placement (Outdoors.SIGNPOST)

    /**
     * Populates a settlement exit, junction, or waystation signpost during chunk generation.
     * Preserves district directions if {@code s.name} starts with {@code "signs:"}, and attaches regional road
     * destination signs on open faces (plus a notice board sign at roadside inns).
     */
    public static void populatePost(Canvas c, Palette p, Spec s, int g) {
        if (c.recording()) return;
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Direction[] face = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
        boolean[] used = new boolean[4];

        if (s.name.startsWith("signs:")) {
            String[] entries = s.name.substring(6).split(";");
            for (int i = 0; i < entries.length && i < 4; i++) {
                String[] kv = entries[i].split("=");
                if (kv.length != 2) continue;
                Component line = Component.translatable("settlement.nirncraft.kind." + kv[0]);
                Component dir = Component.translatable("settlement.nirncraft.sign.dir." + kv[1].toLowerCase(Locale.ROOT));
                int sx = side[i][0], sz = side[i][1];
                if (!Canvas.open(c.get(sx, g + 2, sz))) continue;
                c.sign(sx, g + 2, sz, p.sign, face[i], Component.empty(), line, dir);
                used[i] = true;
            }
        }

        int wx = c.wx(0, 0), wz = c.wz(0, 0);
        Readout readout = computeAt(wx, wz);

        List<Component[]> roadFaces = buildSignFaces(readout);
        int roadIdx = 0;
        for (int i = 0; i < 4 && roadIdx < roadFaces.size(); i++) {
            if (used[i]) continue;
            int sx = side[i][0], sz = side[i][1];
            if (!Canvas.open(c.get(sx, g + 2, sz))) continue;
            Component[] lines = roadFaces.get(roadIdx++);
            c.sign(sx, g + 2, sz, p.sign, face[i], lines[0], lines[1], lines[2], lines[3]);
            used[i] = true;
        }

        // If this signpost belongs to a roadside inn / waystation hamlet, mount a notice board sign on the remaining face
        Place here = currentSettlementAt(wx, wz);
        if (here != null && NoticeBoards.isRoadsideInn(here)) {
            for (int i = 0; i < 4; i++) {
                if (used[i]) continue;
                int sx = side[i][0], sz = side[i][1];
                if (!Canvas.open(c.get(sx, g + 2, sz))) continue;
                c.sign(sx, g + 2, sz, p.sign, face[i],
                        Component.empty(),
                        Component.translatable("settlement.nirncraft.notice.title"),
                        Component.translatable("settlement.nirncraft.notice.line"),
                        Component.translatable("quest.nirncraft.board.sign_prompt"));
                break;
            }
        }
    }

    /** Formats up to 3 sign faces (4 lines each) for a {@link Readout}. */
    public static List<Component[]> buildSignFaces(Readout r) {
        List<Component[]> faces = new ArrayList<>(3);
        if (r.nextSettlement() != null) {
            Component header = !r.roadName().isEmpty()
                    ? Component.translatable(r.roadName())
                    : Component.translatable("signpost.nirncraft.to_settlement");
            Component distLine = Component.translatable("signpost.nirncraft.dist_line",
                    arrowFor(r.settlementDir()), r.settlementDir(), r.settlementDist());
            Component timeLine = Component.translatable("signpost.nirncraft.time_line",
                    r.travelMinutes(r.settlementDist()));
            faces.add(new Component[]{header, r.nextSettlement().name(), distLine, timeLine});
        }
        if (r.nextInnOrTown() != null) {
            Component header = NoticeBoards.isRoadsideInn(r.nextInnOrTown())
                    ? Component.translatable("signpost.nirncraft.to_inn")
                    : Component.translatable("signpost.nirncraft.to_town");
            Component distLine = Component.translatable("signpost.nirncraft.dist_line",
                    arrowFor(r.innDir()), r.innDir(), r.innDist());
            faces.add(new Component[]{header, r.nextInnOrTown().name(), distLine, Component.empty()});
        }
        if (r.nearbyLandmark() != null) {
            Component header = Component.translatable("signpost.nirncraft.to_landmark");
            Component distLine = Component.translatable("signpost.nirncraft.dist_line",
                    arrowFor(r.landmarkDir()), r.landmarkDir(), r.landmarkDist());
            faces.add(new Component[]{header, r.nearbyLandmark().name(), distLine, Component.empty()});
        }
        return faces;
    }

    // ------------------------------------------------------------------ player interaction

    /** Returns true if {@code sign} at {@code pos} is a directional signpost. */
    public static boolean isDirectionalSign(Level level, BlockPos pos, SignBlockEntity sign) {
        if (NoticeBoards.isNoticeBoardSign(level, pos, sign)) return false;
        if ( inspectText(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT)) || inspectText(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK))) return true;
        return false;
    }

    private static boolean inspectText(SignText text) {
        if (text == null) return false;
        for (int i = 0; i < 4; i++) {
            Component msg = text.getMessages(false).get(i);
            if (msg.getContents() instanceof TranslatableContents tc) {
                String k = tc.getKey();
                if (k.startsWith("signpost.nirncraft.") || k.startsWith("settlement.nirncraft.sign.dir.")
                        || k.startsWith("settlement.nirncraft.kind.") || k.startsWith("road.nirncraft.")) {
                    return true;
                }
            }
            String s = msg.getString();
            if (s.contains("↑") || s.contains("↗") || s.contains("→") || s.contains("↘")
                    || s.contains("↓") || s.contains("↙") || s.contains("←") || s.contains("↖")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles right-clicking a directional signpost: prints the full directional readout in chat and marks the
     * referenced settlements as {@code "known"} on the player's world map and compass.
     */
    public static void onUseSign(ServerPlayer player, BlockPos pos, SignBlockEntity sign) {
        Readout r = computeAt(pos.getX(), pos.getZ());
        MutableComponent header = Component.translatable("signpost.nirncraft.readout.header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (!r.roadName().isEmpty()) {
            header.append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable(r.roadName()).withStyle(ChatFormatting.YELLOW));
        }
        com.nirncraft.core.Notifier.message(player, header);

        if (r.nextSettlement() != null) {
            Place p = r.nextSettlement();
            NoticeBoards.markPlaceKnown(player, p);
            com.nirncraft.core.Notifier.message(player, Component.translatable("signpost.nirncraft.readout.settlement",
                    arrowFor(r.settlementDir()), r.settlementDir(), p.name(), r.settlementDist(), r.travelMinutes(r.settlementDist()))
                    .withStyle(ChatFormatting.AQUA));
        }
        if (r.nextInnOrTown() != null) {
            Place p = r.nextInnOrTown();
            NoticeBoards.markPlaceKnown(player, p);
            String key = NoticeBoards.isRoadsideInn(p)
                    ? "signpost.nirncraft.readout.inn"
                    : "signpost.nirncraft.readout.town";
            com.nirncraft.core.Notifier.message(player, Component.translatable(key,
                    arrowFor(r.innDir()), r.innDir(), p.name(), r.innDist(), r.travelMinutes(r.innDist()))
                    .withStyle(ChatFormatting.GREEN));
        }
        if (r.nearbyLandmark() != null) {
            Place p = r.nearbyLandmark();
            NoticeBoards.markPlaceKnown(player, p);
            com.nirncraft.core.Notifier.message(player, Component.translatable("signpost.nirncraft.readout.landmark",
                    arrowFor(r.landmarkDir()), r.landmarkDir(), p.name(), r.landmarkDist())
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * Populates any blank sign block entity near a road junction around {@code player} with directional text.
     */
    public static void refreshNearbyRoadSigns(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-10, -3, -10), center.offset(10, 4, 10))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof SignBlockEntity sign)) continue;
            if (!isBlankSign(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT))) continue;
            Readout r = computeAt(pos.getX(), pos.getZ());
            List<Component[]> faces = buildSignFaces(r);
            if (faces.isEmpty()) continue;
            Component[] lines = faces.get(0);
            SignText text = SignText.EMPTY;
            for (int i = 0; i < 4; i++) text = text.asMutable().setLine(i, lines[i]).asImmutable();
            CompoundTag tag = sign.saveWithoutMetadata(level.registryAccess());
            SignText.CODEC.encodeStart(NbtOps.INSTANCE, text).result().ifPresent(t -> tag.put("front_text", t));
            tag.putBoolean("is_waxed", true);
            com.nirncraft.worldgen.WorldgenData.load(sign, tag, level.registryAccess());
            sign.setChanged();
            level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), 3);
        }
    }

    private static boolean isBlankSign(SignText text) {
        if (text == null) return false;
        for (int i = 0; i < 4; i++) {
            if (!text.getMessages(false).get(i).getString().trim().isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ selftest

    /** Verifies that directional signposts resolve valid destinations across settlement exits. */
    public static List<String> selftest() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Place p : Places.all()) {
            if (p.type() != PlaceType.CAPITAL && p.type() != PlaceType.CITY && p.type() != PlaceType.TOWN) continue;
            checked++;
            Readout r = computeAt(p.x() + p.radius() + 8, p.z());
            if (r.nextSettlement() == null) {
                problems.add(p.id() + ": signpost had no nextSettlement");
            }
            if (r.nextInnOrTown() == null) {
                problems.add(p.id() + ": signpost had no nextInnOrTown");
            }
        }
        if (checked == 0) problems.add("No settlements checked in Signposts.selftest()");
        NirnCraft.LOGGER.info("Signposts selftest checked {} settlement exits, {} problems", checked, problems.size());
        return problems;
    }
}
