package com.skycraft.economy.homes;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.ModEntities;
import com.skycraft.economy.Houses;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.quest.story.Story;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.plan.HousePlots;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The player homes of Tamriel's other provinces ({@link HousePlots}): built on their reserved plots when a player first
 * comes near ({@link HomeBuilder}, in the culture's own style), with their bed and door recorded for the house system
 * ({@link Houses#site}). Also the homes' stories:
 * <ul>
 *     <li>Benirus Manor (Anvil) is haunted: the dead walk its halls at night until its owner lays them to rest
 *     (six of them), then the story flag {@code benirus_cleansed} is set.</li>
 *     <li>Uvirith's Spire (Sadrith Mora) is a Telvanni tower: the Telvanni sell only to a wizard of standing (a
 *     College of Winterhold member of rank 2, or the story flag {@code telvanni_honour}).</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class ProvincialHomes {
    public static final String HAUNTED = "benirus_manor";
    public static final String TELVANNI = "uvirith_spire";
    static final String HAUNT_TAG = "skycraft.haunt";
    static final int HAUNT_KILLS = 6;

    private ProvincialHomes() {}

    // ------------------------------------------------------------------ building

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 40 != 11) return;
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        for (HousePlots.Plot plot : HousePlots.PLOTS) {
            Optional<Place> place = Places.byId(plot.place());
            if (place.isEmpty() || place.get().distSqr(player.getBlockX(), player.getBlockZ()) > 260L * 260L) continue;
            Data data = Data.get(level);
            CompoundTag home = data.home(plot.id());
            if (!home.getBooleanOr("built",false)) tryBuild(level, data, plot);
            if (plot.id().equals(HAUNTED)) haunt(level, player, data);
        }
    }

    static void tryBuild(ServerLevel level, Data data, HousePlots.Plot plot) {
        Optional<SettlementAnchors.HouseSite> site = SettlementAnchors.houseSite(level, plot.id());
        if (site.isEmpty() || site.get().door() == null) return;
        BlockPos min = site.get().min(), max = site.get().max();
        if (!level.hasChunksAt(min.getX() - 6, min.getZ() - 6, max.getX() + 6, max.getZ() + 6)) return;
        HomeBuilder.Built b = HomeBuilder.build(level, HomeStyle.of(plot.style()), min, max, site.get().door());
        CompoundTag home = data.home(plot.id());
        home.putBoolean("built", true);
        home.putLong("door", b.door().asLong());
        home.putLong("bed", b.bed().asLong());
        if (b.spareBed() != null) home.putLong("spare", b.spareBed().asLong());
        data.setDirty();
        Skycraft.LOGGER.info("Built player home {} ({}) at {}", plot.id(), plot.style(), b.door());
        Houses.onSiteBuilt(level, plot.id());
    }

    /** The owner's bed of a built provincial home. */
    @Nullable
    public static BlockPos bed(ServerLevel level, String houseId) {
        CompoundTag home = Data.get(level).home(houseId);
        return home.getBooleanOr("built",false) ? BlockPos.of(home.getLongOr("bed",0L)) : null;
    }

    @Nullable
    public static BlockPos door(ServerLevel level, String houseId) {
        CompoundTag home = Data.get(level).home(houseId);
        return home.getBooleanOr("built",false) ? BlockPos.of(home.getLongOr("door",0L)) : null;
    }

    public static boolean isProvincial(String houseId) {
        return HousePlots.byId(houseId) != null;
    }

    /** All provincial city home plots in the given settlement ({@code placeId}). */
    public static java.util.List<HousePlots.Plot> plotsForPlace(String placeId) {
        return HousePlots.plotsForPlace(placeId);
    }

    /** All provincial city home plots in the given province ({@code cyrodiil}, {@code morrowind}, etc.). */
    public static java.util.List<HousePlots.Plot> plotsForProvince(String province) {
        if (province == null || province.isEmpty()) return java.util.List.of();
        String prov = province.toLowerCase(java.util.Locale.ROOT);
        int slash = prov.indexOf('/');
        if (slash >= 0) prov = prov.substring(0, slash);
        java.util.List<HousePlots.Plot> out = new java.util.ArrayList<>();
        for (HousePlots.Plot plot : HousePlots.PLOTS) {
            Optional<Place> place = Places.byId(plot.place());
            if (place.isPresent()) {
                String reg = place.get().regionId();
                if (reg != null && (reg.startsWith(prov + "/") || reg.equals(prov))) {
                    out.add(plot);
                }
            }
        }
        return java.util.List.copyOf(out);
    }

    // ------------------------------------------------------------------ who may buy

    /** Null when the player may buy the home, else why not. */
    @Nullable
    public static Component refusal(ServerPlayer player, String houseId) {
        if (TELVANNI.equals(houseId)) {
            var d = SkyData.get(player);
            boolean wizard = Factions.isMember(d, Faction.COLLEGE) && Factions.rank(d, Faction.COLLEGE) >= 2;
            if (!wizard && !Story.hasFlag(d, "telvanni_honour")) return Component.translatable("economy.skycraft.home.telvanni_refuse");
        }
        return null;
    }

    /** After a purchase: Benirus Manor's new owner is warned. */
    public static void onBought(ServerPlayer player, String houseId) {
        if (HAUNTED.equals(houseId)) Notifier.message(player, Component.translatable("economy.skycraft.home.haunted_warning"));
    }

    // ------------------------------------------------------------------ Benirus Manor

    static void haunt(ServerLevel level, ServerPlayer player, Data data) {
        CompoundTag home = data.home(HAUNTED);
        if (home.getBooleanOr("cleansed",false) || !home.getBooleanOr("built",false)) return;
        UUID owner = Houses.HousesData.get(level.getServer()).getOwner(HAUNTED);
        if (owner == null || !owner.equals(player.getUUID())) return;
        long time = level.getDefaultClockTime() % 24000L;
        if (time < 13000 || time > 23000) return;
        Optional<SettlementAnchors.HouseSite> site = Houses.site(level, HAUNTED);
        if (site.isEmpty() || !site.get().contains(player.blockPosition())) return;
        AABB box = AABB.encapsulatingFullBlocks(site.get().min(), site.get().max()).inflate(4);
        if (!level.getEntitiesOfClass(Mob.class, box, m -> m.entityTags().contains(HAUNT_TAG)).isEmpty()) return;
        int left = HAUNT_KILLS - home.getIntOr("laid",0);
        int n = Math.min(2, left);
        BlockPos bed = bed(level, HAUNTED);
        for (int i = 0; i < n; i++) {
            Mob m = ModEntities.DRAUGR.get().create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
            if (m == null) return;
            BlockPos at = bed != null ? bed.offset(i == 0 ? -2 : 2, 0, -1) : site.get().center();
            m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
            m.addTag(HAUNT_TAG);
            m.setCustomName(Component.translatable("entity.skycraft.restless_benirus"));
            m.setTarget(player);
            level.addFreshEntity(m);
        }
        level.playSound(null, player.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.5f, 0.6f);
        Notifier.message(player, Component.translatable("economy.skycraft.home.haunt_rises"));
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!e.entityTags().contains(HAUNT_TAG) || !(e.level() instanceof ServerLevel level)) return;
        Data data = Data.get(level);
        CompoundTag home = data.home(HAUNTED);
        int laid = home.getIntOr("laid",0) + 1;
        home.putInt("laid", laid);
        data.setDirty();
        if (laid >= HAUNT_KILLS && !home.getBooleanOr("cleansed",false)) {
            home.putBoolean("cleansed", true);
            UUID owner = Houses.HousesData.get(level.getServer()).getOwner(HAUNTED);
            ServerPlayer p = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
            if (p != null) {
                Story.setFlag(SkyData.get(p), "benirus_cleansed", true);
                Notifier.title(p, Component.translatable("settlement.skycraft.name." + HAUNTED), Component.translatable("economy.skycraft.home.haunt_laid"));
            }
        }
    }

    // ------------------------------------------------------------------ saved data

    static final class Data extends SavedData {
        final CompoundTag homes;

        Data(CompoundTag homes) {
            this.homes = homes;
        }

        static Data get(ServerLevel level) {
            return com.skycraft.platform.SavedDataTypes.get(level.getServer().overworld(), "skycraft_provincial_homes", t -> new Data(t.getCompoundOrEmpty("homes")), () -> new Data(new CompoundTag()), value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
        }

        CompoundTag home(String id) {
            if (!homes.contains(id)) homes.put(id, new CompoundTag());
            return homes.getCompoundOrEmpty(id);
        }

        public CompoundTag save(CompoundTag out) {
            out.put("homes", homes);
            return out;
        }
    }
}
