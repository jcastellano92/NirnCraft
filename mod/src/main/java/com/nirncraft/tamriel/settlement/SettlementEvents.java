package com.nirncraft.tamriel.settlement;

import com.nirncraft.NirnCraft;
import com.nirncraft.crime.Ownership;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.settlement.plan.Cities;
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
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;

import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-side life of settlements: records anchors from structure starts as their chunks load (the chunk event only
 * queues; the level tick does the work), gives household containers and beds their owners, hands finished house
 * sites to {@link com.nirncraft.economy.Houses}, and populates settlements as players come near.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
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
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        tick++;
        try {
            StructureStart start;
            int budget = 4;
            while (budget-- > 0 && (start = FOUND.poll()) != null) record(level, start);
            if (tick % 40 == 11) {
                for (ServerPlayer player : level.players()) {
                    if (player.isSpectator()) continue;
                    SettlementPopulation.tick(level, player);
                    com.nirncraft.society.GatePosts.tick(level, player);
                }
            }
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.error("NirnCraft settlements tick failed", e);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FOUND.clear();
    }

    /** Records the anchors of every piece of a start (once per start), applies ownership, tells houses. */
    static void record(ServerLevel level, StructureStart start) {
        SettlementAnchors data = SettlementAnchors.get(level);
        long startKey = start.getChunkPos().pack();
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
        Component settlementName = place != null ? place.name() : Component.translatable("settlement.nirncraft.generic");
        for (Anchor a : found) {
            if (a.type() != AnchorType.CONTAINER && a.type() != AnchorType.BED) continue;
            if (isHouseForSale(a.owner())) continue;
            Ownership.setOwner(level, a.pos(), "settlement:" + key + ":" + a.owner(), ownerName(settlementName, a.owner()));
        }
        // workplaces for the society module's townsfolk (forge, counters, fields, docks, temple, palace...)
        for (Anchor a : found) {
            com.nirncraft.society.Workplaces.Kind kind = workplace(a.type());
            if (kind != null) com.nirncraft.society.Workplaces.register(level, a.pos(), kind);
        }
        java.util.Set<String> houses = new java.util.HashSet<>();
        for (Anchor a : found) if (a.type() == AnchorType.HOUSE_MIN) houses.add(a.owner());
        for (String h : houses) com.nirncraft.economy.Houses.onSiteBuilt(level, h);
        NirnCraft.LOGGER.debug("Recorded {} anchors of settlement {} (start {})", found.size(), key, ChunkPos.getX(startKey) + "," + ChunkPos.getZ(startKey));
    }

    /** The society workplace kind an anchor stands for, or null. */
    static com.nirncraft.society.Workplaces.Kind workplace(AnchorType t) {
        return switch (t) {
            case SMITH -> com.nirncraft.society.Workplaces.Kind.FORGE;
            case MERCHANT, JEWELER, TAILOR, FLETCHER, FENCE, STABLES -> com.nirncraft.society.Workplaces.Kind.COUNTER;
            case ALCHEMIST -> com.nirncraft.society.Workplaces.Kind.ALCHEMY;
            case BOOKSELLER, WIZARD -> com.nirncraft.society.Workplaces.Kind.LIBRARY;
            case BUTCHER -> com.nirncraft.society.Workplaces.Kind.BUTCHER;
            case HUNTER -> com.nirncraft.society.Workplaces.Kind.TANNERY;
            case FISHER -> com.nirncraft.society.Workplaces.Kind.DOCK;
            case FARMER -> com.nirncraft.society.Workplaces.Kind.FIELD;
            case MINER -> com.nirncraft.society.Workplaces.Kind.MINE;
            case LUMBERJACK, MILLER, WORKER -> com.nirncraft.society.Workplaces.Kind.LUMBER;
            case INN, BARD -> com.nirncraft.society.Workplaces.Kind.INN;
            case PRIEST -> com.nirncraft.society.Workplaces.Kind.TEMPLE;
            case JARL, STEWARD -> com.nirncraft.society.Workplaces.Kind.PALACE;
            case MEETING -> com.nirncraft.society.Workplaces.Kind.MARKET;
            default -> null;
        };
    }

    static boolean isHouseForSale(String owner) {
        return owner.indexOf('#') < 0 && com.nirncraft.economy.Houses.byId(owner) != null;
    }

    /** "Whiterun Inn", "Breezehome", "Jorrvaskr"... for the crosshair owner line. */
    static Component ownerName(Component settlement, String owner) {
        int hash = owner.indexOf('#');
        String base = hash < 0 ? owner : owner.substring(0, hash);
        boolean kindName = base.equals(base.toLowerCase(Locale.ROOT)) && isKind(base);
        if (kindName) return Component.translatable("settlement.nirncraft.owner", settlement,
                Component.translatable("settlement.nirncraft.kind." + base));
        return Component.translatableWithFallback("settlement.nirncraft.name." + base, base);
    }

    private static boolean isKind(String base) {
        for (com.nirncraft.tamriel.settlement.plan.Archetype a : com.nirncraft.tamriel.settlement.plan.Archetype.VALUES) {
            if (a.name().toLowerCase(Locale.ROOT).equals(base)) return true;
        }
        return com.nirncraft.tamriel.settlement.grammar.Use.byId(base) != null;
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
