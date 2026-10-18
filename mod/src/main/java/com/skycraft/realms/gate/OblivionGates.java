package com.skycraft.realms.gate;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.quest.story.Story;
import com.skycraft.realms.Realm;
import com.skycraft.realms.RealmBlocks;
import com.skycraft.realms.RealmItems;
import com.skycraft.realms.RealmLayout;
import com.skycraft.realms.RealmTravel;
import com.skycraft.realms.item.SigilStoneItem;
import com.skycraft.realms.worldgen.RealmPiece;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Oblivion gates of Nirn and their Sigil Stones (world data, kept with the overworld).
 *
 * <ul>
 *     <li>Every gate opens onto the Sigil Keep of the Deadlands cell picked from its position
 *     ({@link RealmLayout#deadlandsCellX}); walking through records which gate a keep is linked to.</li>
 *     <li>Taking the Sigil Stone at the top of the keep's tower closes the linked gate: the gate in Nirn collapses
 *     into scorched rubble (it never opens again), the taker gets the stone (an enchanting gem), story flags are set
 *     ({@code oblivion_gate_closed}, plus {@code <place>_closed} for a named gate such as {@code kvatch_gate_closed}),
 *     and everyone on the island is thrown home.</li>
 *     <li>A keep whose stone was taken grows a new one when another open gate leads to it.</li>
 * </ul>
 */
public final class OblivionGates extends SavedData {
    private static final String NAME = "skycraft_oblivion_gates";
    public static final Identifier GATE = Identifier.fromNamespaceAndPath(Skycraft.MODID, "nirn/oblivion_gate");
    public static final Identifier NAMED_GATE = Identifier.fromNamespaceAndPath(Skycraft.MODID, "nirn/named_gate");

    private final Set<Long> closed = new HashSet<>();
    private final Set<Long> taken = new HashSet<>();
    private final Map<Long, Long> links = new HashMap<>();

    public static OblivionGates get(ServerLevel any) {
        return com.skycraft.platform.SavedDataTypes.get(any.getServer().overworld(), NAME, OblivionGates::load, OblivionGates::new, d -> d.save(new CompoundTag()));
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public boolean isClosed(BlockPos gate) {
        return closed.contains(gate.asLong());
    }

    // ------------------------------------------------------------------ finding gates

    /** The centre (origin) of the Oblivion gate whose structure covers {@code near}, or null. */
    @Nullable
    public static BlockPos gateCenter(ServerLevel level, BlockPos near) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Structure[] gates = {registry.getValue(GATE), registry.getValue(NAMED_GATE)};
        ChunkPos c = ChunkPos.containing(near);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                if (!level.hasChunk(c.x() + dx, c.z() + dz)) continue;
                var chunk = level.getChunk(c.x() + dx, c.z() + dz);
                for (Structure s : gates) {
                    if (s == null) continue;
                    StructureStart start = chunk.getStartForStructure(s);
                    if (start == null || !start.isValid() || start.getPieces().isEmpty()) continue;
                    if (!(start.getPieces().get(0) instanceof RealmPiece piece)) continue;
                    BlockPos o = new BlockPos(piece.ox, piece.oy, piece.oz);
                    double d = o.distSqr(near);
                    if (d < bestD && d < 40 * 40) {
                        bestD = d;
                        best = o;
                    }
                }
            }
        return best;
    }

    // ------------------------------------------------------------------ entering

    /** Walking into a gate's fire in Nirn (or a quest step beside it): to the keep of the gate's cell. */
    public static void enterGate(ServerPlayer p, BlockPos portalPos) {
        ServerLevel level = p.level();
        BlockPos gate = gateCenter(level, portalPos);
        if (gate == null) gate = portalPos;
        OblivionGates data = get(level);
        if (data.isClosed(gate)) {
            Notifier.message(p, Component.translatable("realm.skycraft.gate.closed"));
            return;
        }
        enterVia(p, gate, data);
    }

    /** Quest travel to the Deadlands: through the gate the party stands at, else the keep of their cell. */
    public static void enterNear(ServerPlayer p) {
        ServerLevel level = p.level();
        BlockPos gate = Realm.of(level) == null ? gateCenter(level, p.blockPosition()) : null;
        if (gate == null) gate = p.blockPosition();
        enterVia(p, gate, get(level));
    }

    private static void enterVia(ServerPlayer p, BlockPos gate, OblivionGates data) {
        int cx = RealmLayout.deadlandsCellX(gate.getX()), cz = RealmLayout.deadlandsCellZ(gate.getZ());
        long key = cellKey(cx, cz);
        if (Realm.of(p.level()) == null) {
            data.links.put(key, gate.asLong());
            CompoundTag d = RealmTravel.data(p);
            d.putLong("gate", gate.asLong());
            SkyData.get(p).markDirty();
        }
        data.setDirty();
        RealmTravel.Arrival a = new RealmTravel.Arrival(RealmLayout.keepX(cx), RealmLayout.keepZ(cz) + RealmLayout.DL_ARRIVAL_DZ, 180f);
        boolean regrow = data.taken.remove(key);
        ServerLevel from = p.level();
        java.util.List<ServerPlayer> companions = companionsOf(p);
        if (!RealmTravel.enter(p, Realm.DEADLANDS, a)) return;
        if (regrow) regrowStone(p.level(), cx, cz);
        int i = 0;
        for (ServerPlayer c : companions) {
            int k = ++i;
            RealmTravel.schedule(p.level().getServer(), 2 * k, () -> {
                if (!c.isAlive() || c.level() != from) return;
                RealmTravel.data(c).putLong("gate", gate.asLong());
                RealmTravel.enter(c, Realm.DEADLANDS, new RealmTravel.Arrival(a.x() + (k % 2 == 0 ? 2 : -2), a.z() + k / 2, a.yaw()));
            });
        }
    }

    private static java.util.List<ServerPlayer> companionsOf(ServerPlayer p) {
        java.util.List<ServerPlayer> out = new java.util.ArrayList<>();
        var party = com.skycraft.quest.party.Parties.partyOf(p);
        if (party == null) return out;
        for (ServerPlayer m : com.skycraft.quest.party.Parties.onlineMembers(p.level().getServer(), party)) {
            if (m != p && m.level() == p.level() && m.isAlive() && !m.isSpectator() && m.distanceTo(p) <= 6) out.add(m);
        }
        return out;
    }

    /** Where a keep's Sigil Stone sits. */
    public static BlockPos stonePos(int cx, int cz) {
        return new BlockPos(RealmLayout.keepX(cx), RealmLayout.DL_SIGIL_FLOOR + 2, RealmLayout.keepZ(cz) - 4);
    }

    private static void regrowStone(ServerLevel deadlands, int cx, int cz) {
        BlockPos s = stonePos(cx, cz);
        deadlands.getChunk(s.getX() >> 4, s.getZ() >> 4);
        if (deadlands.getBlockState(s).isAir()) deadlands.setBlockAndUpdate(s, RealmBlocks.SIGIL_STONE.get().defaultBlockState());
    }

    // ------------------------------------------------------------------ the Sigil Stone

    /** A player takes a Sigil Stone: the gate collapses and everyone on the island goes home. */
    public static void takeSigilStone(ServerPlayer p, BlockPos pos) {
        ServerLevel deadlands = p.level();
        if (Realm.of(deadlands) != Realm.DEADLANDS) return;
        int cx = RealmLayout.keepCellOf(pos.getX()), cz = RealmLayout.keepCellOf(pos.getZ());
        long key = cellKey(cx, cz);
        OblivionGates data = get(deadlands);
        // which gate: the one this player came through, if it leads here; else the last gate linked to this keep
        BlockPos gate = null;
        CompoundTag d = RealmTravel.data(p);
        if (d.contains("gate")) {
            BlockPos g = BlockPos.of(d.getLongOr("gate", 0L));
            if (RealmLayout.deadlandsCellX(g.getX()) == cx && RealmLayout.deadlandsCellZ(g.getZ()) == cz) gate = g;
        }
        if (gate == null && data.links.containsKey(key)) gate = BlockPos.of(data.links.get(key));

        deadlands.removeBlock(pos, false);
        deadlands.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 2f, 0.5f);
        deadlands.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.5f, 0.6f);
        deadlands.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 120, 2, 2, 2, 0.15);
        deadlands.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 1.5, 1.5, 1.5, 0);
        ItemStack stone = SigilStoneItem.roll(RealmItems.SIGIL_STONE.get(), p.getRandom());
        com.skycraft.inventory.Pack.giveOrDrop(p, stone);
        data.taken.add(key);
        String placeFlag = null;
        if (gate != null && !data.closed.contains(gate.asLong())) {
            data.closed.add(gate.asLong());
            ServerLevel nirn = p.level().getServer().overworld();
            collapse(nirn, gate);
            Place named = nearestPlace(gate);
            if (named != null) placeFlag = named.id() + "_closed";
        }
        data.setDirty();

        // everyone on this island: titles, flags, and home after the tower shakes for a few seconds
        int kx = RealmLayout.keepX(cx), kz = RealmLayout.keepZ(cz);
        for (ServerPlayer m : deadlands.players()) {
            if (RealmLayout.dist(m.getX(), m.getZ(), kx, kz + 30) > 240) continue;
            Story.setFlag(SkyData.get(m), "oblivion_gate_closed", true);
            if (placeFlag != null) Story.setFlag(SkyData.get(m), placeFlag, true);
            SkyData.get(m).markDirty();
            Notifier.title(m, Component.translatable("realm.skycraft.sigil.taken"), Component.translatable(gate != null
                    ? "realm.skycraft.sigil.taken.sub" : "realm.skycraft.sigil.taken.sub_nogate"));
            m.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.Holder.direct(SoundEvents.WITHER_DEATH), SoundSource.AMBIENT, m.getX(), m.getY(), m.getZ(), 0.6f, 0.5f, 0));
            RealmTravel.schedule(p.level().getServer(), 70, () -> {
                if (m.isAlive() && m.level() == deadlands) RealmTravel.returnHome(m);
            });
        }
    }

    @Nullable
    private static Place nearestPlace(BlockPos gate) {
        for (Place pl : Places.overlapping(gate.getX() - 48, gate.getZ() - 48, gate.getX() + 48, gate.getZ() + 48)) {
            if ("oblivion_gate".equals(pl.variant())) return pl;
        }
        return null;
    }

    /** The gate in Nirn falls: its fire goes out, the arch breaks into rubble, the blight stays as a scar. */
    public static void collapse(ServerLevel nirn, BlockPos gate) {
        int x0 = gate.getX() - 14, x1 = gate.getX() + 14, z0 = gate.getZ() - 14, z1 = gate.getZ() + 14;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) nirn.getChunk(cx, cz);
        BlockState rubble = Blocks.BLACKSTONE.defaultBlockState();
        net.minecraft.util.RandomSource r = nirn.getRandom();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = gate.getY() - 2; y <= gate.getY() + 24; y++) {
                    m.set(x, y, z);
                    BlockState s = nirn.getBlockState(m);
                    if (s.is(RealmBlocks.OBLIVION_PORTAL.get())) {
                        nirn.setBlock(m, Blocks.AIR.defaultBlockState(), 2 | 16);
                    } else if (y > gate.getY() + 1 && (s.is(RealmBlocks.DAEDRIC_BRICKS.get()) || s.is(RealmBlocks.DAEDRIC_GLYPH_BRICKS.get())
                            || s.is(RealmBlocks.DAEDRIC_SPIKE.get()) || s.is(RealmBlocks.DEADLANDS_ROCK.get()))) {
                        // the higher, the likelier it fell
                        float fall = 0.35f + (y - gate.getY()) * 0.03f;
                        nirn.setBlock(m, r.nextFloat() < fall ? Blocks.AIR.defaultBlockState()
                                : s.is(RealmBlocks.DAEDRIC_SPIKE.get()) ? Blocks.AIR.defaultBlockState() : rubble, 2 | 16);
                    } else if (s.is(Blocks.FIRE)) {
                        nirn.setBlock(m, Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
        // the fallen arch lies around the dais
        for (int i = 0; i < 40; i++) {
            int x = gate.getX() + r.nextInt(17) - 8, z = gate.getZ() + r.nextInt(17) - 8;
            BlockPos top = nirn.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            nirn.setBlock(top, r.nextInt(3) == 0 ? RealmBlocks.DAEDRIC_BRICKS.get().defaultBlockState() : rubble, 3);
        }
        nirn.sendParticles(ParticleTypes.EXPLOSION_EMITTER, gate.getX() + 0.5, gate.getY() + 6, gate.getZ() + 0.5, 3, 3, 4, 3, 0);
        nirn.sendParticles(ParticleTypes.LARGE_SMOKE, gate.getX() + 0.5, gate.getY() + 6, gate.getZ() + 0.5, 200, 5, 6, 5, 0.05);
        nirn.playSound(null, gate, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4f, 0.5f);
    }

    // ------------------------------------------------------------------ persistence

    private static OblivionGates load(CompoundTag tag) {
        OblivionGates d = new OblivionGates();
        for (Tag t : tag.getListOrEmpty("Closed")) d.closed.add(((LongTag) t).value());
        for (Tag t : tag.getListOrEmpty("Taken")) d.taken.add(((LongTag) t).value());
        ListTag links = tag.getListOrEmpty("Links");
        for (int i = 0; i < links.size(); i++) {
            CompoundTag l = links.getCompoundOrEmpty(i);
            d.links.put(l.getLongOr("Cell", 0L), l.getLongOr("Gate", 0L));
        }
        return d;
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag c = new ListTag();
        for (long v : closed) c.add(LongTag.valueOf(v));
        tag.put("Closed", c);
        ListTag t = new ListTag();
        for (long v : taken) t.add(LongTag.valueOf(v));
        tag.put("Taken", t);
        ListTag l = new ListTag();
        links.forEach((cell, gate) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("Cell", cell);
            e.putLong("Gate", gate);
            l.add(e);
        });
        tag.put("Links", l);
        return tag;
    }
}
