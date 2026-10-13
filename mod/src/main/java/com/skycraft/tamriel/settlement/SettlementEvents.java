package com.skycraft.tamriel.settlement;

import com.skycraft.Skycraft;
import com.skycraft.crime.Ownership;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.plan.Cities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-side life of settlements: records anchors from structure starts as their chunks load (the chunk event only
 * queues; the level tick does the work), gives household containers and beds their owners, hands finished house
 * sites to {@link com.skycraft.economy.Houses}, and populates settlements as players come near.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SettlementEvents {
    private static final ConcurrentLinkedQueue<StructureStart> FOUND = new ConcurrentLinkedQueue<>();
    private static long tick;

    private SettlementEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) return;
        for (Map.Entry<Structure, StructureStart> e : starts.entrySet()) {
            if (e.getKey() instanceof SettlementStructure && e.getValue() != null && e.getValue().isValid()) FOUND.add(e.getValue());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        tick++;
        try {
            StructureStart start;
            int budget = 4;
            while (budget-- > 0 && (start = FOUND.poll()) != null) record(level, start);
            if (tick % 40 == 11) {
                for (ServerPlayer player : level.players()) {
                    if (player.isSpectator()) continue;
                    SettlementPopulation.tick(level, player);
                    com.skycraft.society.GatePosts.tick(level, player);
                }
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Skycraft settlements tick failed", e);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FOUND.clear();
    }

    /** Records the anchors of every piece of a start (once per start), applies ownership, tells houses. */
    static void record(ServerLevel level, StructureStart start) {
        SettlementAnchors data = SettlementAnchors.get(level);
        long startKey = start.getChunkPos().toLong();
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.isEmpty() || !(pieces.get(0) instanceof SettlementPiece first)) return;
        String key = first.settlement;
        if (data.recorded(key, startKey)) return;
        int x, z, radius;
        Place place = key.startsWith("gen:") ? null : Places.byId(key).orElse(null);
        if (place != null) {
            x = place.x();
            z = place.z();
            radius = Math.max(place.radius(), Cities.layoutRadius(place));
        } else {
            var box = start.getBoundingBox();
            x = (box.minX() + box.maxX()) / 2;
            z = (box.minZ() + box.maxZ()) / 2;
            radius = Math.max(box.getXSpan(), box.getZSpan()) / 2;
        }
        SettlementAnchors.Entry entry = data.entryFor(key, x, z, radius);
        entry.starts.add(startKey);
        List<Anchor> found = new ArrayList<>();
        for (StructurePiece p : pieces) {
            if (p instanceof SettlementPiece sp) sp.record(found);
        }
        entry.anchors.addAll(found);
        data.setDirty();
        Component settlementName = place != null ? place.name() : Component.translatable("settlement.skycraft.generic");
        for (Anchor a : found) {
            if (a.type() != AnchorType.CONTAINER && a.type() != AnchorType.BED) continue;
            if (isHouseForSale(a.owner())) continue;
            Ownership.setOwner(level, a.pos(), "settlement:" + key + ":" + a.owner(), ownerName(settlementName, a.owner()));
        }
        // workplaces for the society module's townsfolk (forge, counters, fields, docks, temple, palace...)
        for (Anchor a : found) {
            com.skycraft.society.Workplaces.Kind kind = workplace(a.type());
            if (kind != null) com.skycraft.society.Workplaces.register(level, a.pos(), kind);
        }
        java.util.Set<String> houses = new java.util.HashSet<>();
        for (Anchor a : found) if (a.type() == AnchorType.HOUSE_MIN) houses.add(a.owner());
        for (String h : houses) com.skycraft.economy.Houses.onSiteBuilt(level, h);
        Skycraft.LOGGER.debug("Recorded {} anchors of settlement {} (start {})", found.size(), key, ChunkPos.getX(startKey) + "," + ChunkPos.getZ(startKey));
    }

    /** The society workplace kind an anchor stands for, or null. */
    static com.skycraft.society.Workplaces.Kind workplace(AnchorType t) {
        return switch (t) {
            case SMITH -> com.skycraft.society.Workplaces.Kind.FORGE;
            case MERCHANT, JEWELER, TAILOR, FLETCHER, FENCE, STABLES -> com.skycraft.society.Workplaces.Kind.COUNTER;
            case ALCHEMIST -> com.skycraft.society.Workplaces.Kind.ALCHEMY;
            case BOOKSELLER, WIZARD -> com.skycraft.society.Workplaces.Kind.LIBRARY;
            case BUTCHER -> com.skycraft.society.Workplaces.Kind.BUTCHER;
            case HUNTER -> com.skycraft.society.Workplaces.Kind.TANNERY;
            case FISHER -> com.skycraft.society.Workplaces.Kind.DOCK;
            case FARMER -> com.skycraft.society.Workplaces.Kind.FIELD;
            case MINER -> com.skycraft.society.Workplaces.Kind.MINE;
            case LUMBERJACK, MILLER, WORKER -> com.skycraft.society.Workplaces.Kind.LUMBER;
            case INN, BARD -> com.skycraft.society.Workplaces.Kind.INN;
            case PRIEST -> com.skycraft.society.Workplaces.Kind.TEMPLE;
            case JARL, STEWARD -> com.skycraft.society.Workplaces.Kind.PALACE;
            case MEETING -> com.skycraft.society.Workplaces.Kind.MARKET;
            default -> null;
        };
    }

    static boolean isHouseForSale(String owner) {
        return owner.indexOf('#') < 0 && com.skycraft.economy.Houses.byId(owner) != null;
    }

    /** "Whiterun Inn", "Breezehome", "Jorrvaskr"... for the crosshair owner line. */
    static Component ownerName(Component settlement, String owner) {
        int hash = owner.indexOf('#');
        String base = hash < 0 ? owner : owner.substring(0, hash);
        boolean kindName = base.equals(base.toLowerCase(Locale.ROOT)) && isKind(base);
        if (kindName) return Component.translatable("settlement.skycraft.owner", settlement,
                Component.translatable("settlement.skycraft.kind." + base));
        return Component.translatableWithFallback("settlement.skycraft.name." + base, base);
    }

    private static boolean isKind(String base) {
        for (com.skycraft.tamriel.settlement.plan.Archetype a : com.skycraft.tamriel.settlement.plan.Archetype.VALUES) {
            if (a.name().toLowerCase(Locale.ROOT).equals(base)) return true;
        }
        return com.skycraft.tamriel.settlement.grammar.Use.byId(base) != null;
    }

    /** Anchor positions of a kind near a block (for other modules: beds and posts around a point). */
    public static List<BlockPos> anchorsNear(ServerLevel level, BlockPos pos, AnchorType type, int range) {
        List<BlockPos> out = new ArrayList<>();
        SettlementAnchors.near(level, pos, range).ifPresent(e -> {
            for (Anchor a : e.anchors) if (a.type() == type && a.pos().closerThan(pos, range)) out.add(a.pos());
        });
        return out;
    }
}
