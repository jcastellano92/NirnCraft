package com.skycraft.explore;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.skills.Progression;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.world.Discovery;
import com.skycraft.world.WorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server side of the minor sites:
 * <ul>
 *     <li>"Found: Hunter's Camp" the first time a player comes upon a site (a little Athletics experience);</li>
 *     <li>treasure-map markers clear when the player reaches the cache;</li>
 *     <li>hermits and some hunters move into their huts and camps the first time a player comes near
 *     (spawned on the server thread, not in worldgen: NPCs register with the townsfolk registry);</li>
 *     <li>reading a lead note marks the nearest unknown location to where the note was found.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ExploreEvents {
    private static final int INTERVAL = 40;

    private ExploreEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.isSpectator() || player.level().dimension() != Level.OVERWORLD) return;
        if ((player.tickCount + (player.getId() & 31) + 17) % INTERVAL != 0) return;
        try {
            ServerLevel level = player.serverLevel();
            long seed = level.getSeed();
            int x = player.getBlockX(), z = player.getBlockZ();
            for (MinorSites.Site s : MinorSites.within(seed, x, z, 72)) {
                double d = Math.sqrt(s.distSqr(x, z));
                if (d <= s.kind().radius + 8) foundSite(player, level, s);
                if (s.kind() == SiteKind.HERMIT_HUT || (s.kind() == SiteKind.HUNTERS_CAMP && (s.seed() & 1) == 0)) {
                    populate(level, s);
                }
            }
            checkCacheMarkers(player);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft explore check failed for {}", player.getName().getString(), e);
        }
    }

    // ------------------------------------------------------------------ found a site

    private static void foundSite(ServerPlayer player, ServerLevel level, MinorSites.Site s) {
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module("explore");
        long[] found = mod.getLongArray("found");
        long key = s.key();
        for (long k : found) if (k == key) return;
        if (!built(level, s)) return;
        long[] grown = java.util.Arrays.copyOf(found, found.length + 1);
        grown[found.length] = key;
        mod.putLongArray("found", grown);
        data.addStat("minor_sites_found", 1);
        data.markDirty();
        if (ExploreConfig.flag(ExploreConfig.FOUND_MESSAGES, true)) {
            Notifier.message(player, Component.translatable("explore.skycraft.found", s.kind().displayName()));
        }
        double xp = ExploreConfig.value(ExploreConfig.MINOR_SITE_XP, 2.0);
        if (xp > 0) Progression.addSkillXp(player, Skill.ATHLETICS, (float) xp);
    }

    /** True when the site was actually built: some container of it stands within its footprint. */
    static boolean built(ServerLevel level, MinorSites.Site s) {
        int r = s.kind().radius + 3;
        for (int cx = (s.x() - r) >> 4; cx <= (s.x() + r) >> 4; cx++) {
            for (int cz = (s.z() - r) >> 4; cz <= (s.z() + r) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) return false;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof Container)) continue;
                    BlockPos p = be.getBlockPos();
                    long dx = p.getX() - s.x(), dz = p.getZ() - s.z();
                    if (dx * dx + dz * dz <= (long) r * r) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ residents

    private static void populate(ServerLevel level, MinorSites.Site s) {
        ExploreWorldData world = ExploreWorldData.get(level);
        if (world.isPopulated(s.key())) return;
        if (!built(level, s)) return;
        BlockPos spot = standingSpot(level, s);
        if (spot == null) return;
        world.markPopulated(s.key());
        NpcRole role = s.kind() == SiteKind.HERMIT_HUT ? ((s.seed() >>> 3) % 3 == 0 ? NpcRole.HUNTER : NpcRole.APOTHECARY) : NpcRole.HUNTER;
        NpcEntity npc = Npcs.create(level, spot, role);
        if (npc == null) return;
        npc.setPersistenceRequired();
        if (level.addFreshEntity(npc)) npc.setHome(spot, s.kind() == SiteKind.HERMIT_HUT ? 10 : 14);
    }

    /** A floor spot with headroom at the site's centre: inside the hut, or by the camp fire. */
    private static BlockPos standingSpot(ServerLevel level, MinorSites.Site s) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.x(), s.z());
        for (int[] o : new int[][]{{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {2, 2}, {-2, 2}}) {
            for (int y = top + 1; y > top - 14; y--) {
                BlockPos p = new BlockPos(s.x() + o[0], y, s.z() + o[1]);
                if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                        && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) {
                    return p;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ treasure markers

    private static void checkCacheMarkers(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        ListTag list = WorldData.discovered(data);
        String dim = player.level().dimension().location().toString();
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag loc = list.getCompound(i);
            String id = loc.getString("id");
            if (!id.startsWith(TreasureMapItem.MARKER_PREFIX) || !dim.equals(loc.getString("dim"))) continue;
            double dx = loc.getInt("x") + 0.5 - player.getX(), dz = loc.getInt("z") + 0.5 - player.getZ();
            if (dx * dx + dz * dz > 7 * 7) continue;
            list.remove(i);
            ListTag done = data.module("explore").getList("caches_found", Tag.TAG_STRING);
            done.add(StringTag.valueOf(id));
            data.module("explore").put("caches_found", done);
            data.addStat("treasure_caches_found", 1);
            data.markDirty();
            Notifier.message(player, Component.translatable("explore.skycraft.treasure_map.found", loc.getString("name")));
        }
    }

    // ------------------------------------------------------------------ lead notes

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof LoreBookItem)) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ExploreLoot.LEAD_TAG)) return;
        onReadLead(player, stack, tag);
    }

    /** Marks the nearest location this player doesn't know yet, from where the note was found; once per reader. */
    static void onReadLead(ServerPlayer player, ItemStack stack, CompoundTag tag) {
        ListTag readers = tag.getList("explore_readers", Tag.TAG_STRING);
        String me = player.getUUID().toString();
        for (int i = 0; i < readers.size(); i++) if (readers.getString(i).equals(me)) return;
        readers.add(StringTag.valueOf(me));
        tag.put("explore_readers", readers);
        int[] at = tag.getIntArray(ExploreLoot.LEAD_TAG);
        if (at.length < 2) return;
        BlockPos from = new BlockPos(at[0], player.getBlockY(), at[1]);
        Component name = Discovery.revealNear(player, from, 1200);
        if (name != null) {
            Notifier.message(player, Component.translatable("explore.skycraft.lead.revealed", name));
        }
    }

    // ------------------------------------------------------------------ discovery reward

    /** Called by {@link Discovery} when a player discovers a named place or a dungeon (not minor sites). */
    public static void onDiscovered(ServerPlayer player) {
        double xp = ExploreConfig.value(ExploreConfig.DISCOVERY_XP, 6.0);
        if (xp > 0) Progression.addSkillXp(player, Skill.ATHLETICS, (float) xp);
    }
}
