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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** Forge-bus client events of the world module: WAIT/MAP keys, compass locations, soundtrack, realm fog. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class WorldClientEvents {
    private static int ticks;

    private WorldClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MusicController.tick(mc);
        if (mc.player == null || mc.level == null) return;

        while (SkyKeys.WAIT.consumeClick()) {
            RestClient.onWaitKey(mc);
        }
        while (SkyKeys.MAP.consumeClick()) {
            if (mc.gui.screen() == null) mc.gui.setScreen(new MapScreen());
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
        ListTag list = SkyData.get(mc.player).module(WorldData.MODULE).getListOrEmpty("discovered");
        String dim = mc.level.dimension().identifier().toString();
        double range = WorldConfig.COMPASS_RANGE.get();
        BlockPos p = mc.player.blockPosition();
        List<CompassMarkers.Marker> markers = new ArrayList<>();
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompoundOrEmpty(i);
            if (!loc.getStringOr("dim","").equals(dim)) continue;
            if (!loc.getStringOr("place", "").isEmpty()) listed.add(loc.getStringOr("place",""));
            double dx = loc.getIntOr("x",0) - p.getX();
            double dz = loc.getIntOr("z",0) - p.getZ();
            if (dx * dx + dz * dz > range * range) continue;
            LocationKind kind = LocationKind.byId(loc.getStringOr("type",""));
            boolean known = loc.getBooleanOr("known",false);
            markers.add(new CompassMarkers.Marker(new Vec3(loc.getIntOr("x",0) + 0.5, loc.getIntOr("y",0), loc.getIntOr("z",0) + 0.5),
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

    /**
     * Vanilla background music is replaced by the Skycraft soundtrack while in a world. Also: the title screen's music
     * becomes a fading instance (so it fades under the loading screen instead of cutting off), jukebox records and
     * stings are reported to the {@link MusicController} so it can make way for them.
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        var sound = event.getSound();
        if (sound == null || MusicController.isOurs(sound)) return;
        if (sound.getSource() == SoundSource.RECORDS) {
            MusicController.recordStarted(sound);
            return;
        }
        String ns = sound.getIdentifier().getNamespace();
        String path = sound.getIdentifier().getPath();
        if (Skycraft.MODID.equals(ns) && (path.equals("music.discovery") || path.equals("music.level_up"))) {
            MusicController.stingStarted(path.equals("music.discovery") ? 9 : 4); // stings play as UI sounds
            return;
        }
        if (sound.getSource() != SoundSource.MUSIC) return;
        if (Minecraft.getInstance().level == null) {
            if (Skycraft.MODID.equals(ns) && path.startsWith("music.")) {
                event.setSound(MusicController.titleMusic(sound));
            } else if ("minecraft".equals(ns) && MusicController.titleMusicPlaying()) {
                event.setSound(null); // vanilla menu music would play over the title theme
            }
            return;
        }
        if (!MusicController.active()) return;
        if (!"minecraft".equals(ns)) return; // let other mods' music through
        event.setSound(null);
    }

    /** Oblivion burns red-orange; Sovngarde glows with a misty blue-gold. */
    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !WorldConfig.REALM_FOG.get()) return;
        // the Deadlands and Sovngarde are full realms now: their sky and fog come from the realms module
        if (com.skycraft.realms.Realm.of(mc.level) != null) return;
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
