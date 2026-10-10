package com.skycraft.world.client;

import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.client.hud.CompassMarkers;
import com.skycraft.core.SkyData;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.world.LocationKind;
import com.skycraft.world.WorldConfig;
import com.skycraft.world.WorldData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** Forge-bus client events of the world module: WAIT/MAP keys, compass locations, soundtrack, realm fog. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class WorldClientEvents {
    private static int ticks;

    private WorldClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        MusicController.tick(mc);
        if (mc.player == null || mc.level == null) return;

        while (SkyKeys.WAIT.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new RestScreen(false, null));
        }
        while (SkyKeys.MAP.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new MapScreen());
        }
        ExploredMap.tick(mc);
        RegionBanner.tick(mc);
        if (++ticks % 20 == 0) updateCompass(mc);
    }

    /** Undiscovered named places closer than this (beyond their own radius) show on the compass, like Skyrim. */
    private static final int UNDISCOVERED_RANGE = 220;

    /**
     * Compass source "locations": discovered places nearby in this dimension (bright icons with their names), places
     * the player has heard of and undiscovered named places close by (dark icons).
     */
    private static void updateCompass(Minecraft mc) {
        ListTag list = SkyData.get(mc.player).module(WorldData.MODULE).getList("discovered", Tag.TAG_COMPOUND);
        String dim = mc.level.dimension().location().toString();
        double range = WorldConfig.COMPASS_RANGE.get();
        BlockPos p = mc.player.blockPosition();
        List<CompassMarkers.Marker> markers = new ArrayList<>();
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            if (!loc.getString("dim").equals(dim)) continue;
            if (!loc.getString("place").isEmpty()) listed.add(loc.getString("place"));
            double dx = loc.getInt("x") - p.getX();
            double dz = loc.getInt("z") - p.getZ();
            if (dx * dx + dz * dz > range * range) continue;
            LocationKind kind = LocationKind.byId(loc.getString("type"));
            boolean known = loc.getBoolean("known");
            markers.add(new CompassMarkers.Marker(new Vec3(loc.getInt("x") + 0.5, loc.getInt("y"), loc.getInt("z") + 0.5),
                    known ? CompassMarkers.Shape.LOCATION : CompassMarkers.Shape.DISCOVERED, kind.color,
                    WorldData.displayName(loc).getString(), range, kind.icon));
        }
        if (mc.level.dimension() == Level.OVERWORLD) {
            int reach = UNDISCOVERED_RANGE + 256;
            for (Place place : Places.overlapping(p.getX() - reach, p.getZ() - reach, p.getX() + reach, p.getZ() + reach)) {
                if (listed.contains(place.id())) continue;
                double r = UNDISCOVERED_RANGE + Math.min(256, place.radius());
                if (place.distSqr(p.getX(), p.getZ()) > r * r) continue;
                LocationKind kind = LocationKind.forPlace(place);
                markers.add(new CompassMarkers.Marker(new Vec3(place.x() + 0.5, p.getY(), place.z() + 0.5),
                        CompassMarkers.Shape.LOCATION, kind.color, place.name().getString(), r, kind.icon));
            }
        }
        CompassMarkers.set("locations", markers);
    }

    /** Vanilla background music is replaced by the Skycraft soundtrack while in a world. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        var sound = event.getSound();
        if (sound == null || sound.getSource() != SoundSource.MUSIC || MusicController.isOurs(sound)) return;
        if (!MusicController.active()) return;
        if (!"minecraft".equals(sound.getLocation().getNamespace())) return; // let other mods' music through
        event.setSound(null);
    }

    /** Oblivion burns red-orange; Sovngarde glows with a misty blue-gold. */
    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !WorldConfig.REALM_FOG.get()) return;
        if (mc.level.dimension() == Level.NETHER) {
            event.setRed(lerp(event.getRed(), 0.55f, 0.6f));
            event.setGreen(lerp(event.getGreen(), 0.12f, 0.6f));
            event.setBlue(lerp(event.getBlue(), 0.04f, 0.6f));
        } else if (mc.level.dimension() == Level.END) {
            event.setRed(lerp(event.getRed(), 0.30f, 0.45f));
            event.setGreen(lerp(event.getGreen(), 0.32f, 0.45f));
            event.setBlue(lerp(event.getBlue(), 0.42f, 0.45f));
        }
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CompassMarkers.clear("locations");
        MusicController.reset();
        MapScreen.resetView();
        ExploredMap.reset();
        RegionBanner.reset();
        MapTiles.clear();
    }
}
