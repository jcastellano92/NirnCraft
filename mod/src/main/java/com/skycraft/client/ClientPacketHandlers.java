package com.skycraft.client;

import com.skycraft.client.hud.HudNotifications;
import com.skycraft.client.screen.RaceScreen;
import com.skycraft.client.screen.SkillsScreen;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.CorePackets;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

/** Client-side handlers for core S2C packets (only ever class-loaded on the client). */
public final class ClientPacketHandlers {
    private ClientPacketHandlers() {}

    public static void syncData(CompoundTag tag) {
        var player = Minecraft.getInstance().player;
        if (player != null && tag != null) SkyData.get(player).load(tag);
    }

    public static void syncProgress(CompoundTag tag) {
        var player = Minecraft.getInstance().player;
        if (player != null && tag != null) SkyData.get(player).loadProgress(tag);
    }

    /** Race/sheathe/apparel of any player (others' copies of the data are otherwise empty on this client). */
    public static void syncAppearance(int entityId, CompoundTag tag) {
        var level = Minecraft.getInstance().level;
        if (level == null || tag == null) return;
        if (level.getEntity(entityId) instanceof net.minecraft.world.entity.player.Player p) {
            SkyData.get(p).loadAppearance(tag);
        }
    }

    public static void syncVitals(float magicka, float stamina, byte flags) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        PlayerData data = SkyData.get(player);
        data.setMagicka(magicka);
        data.setStamina(stamina);
        ClientState.vitalsFlags = flags;
    }

    public static void notify(CorePackets.Notify msg) {
        HudNotifications.add(msg);
    }

    public static void openScreen(int screen) {
        Minecraft mc = Minecraft.getInstance();
        switch (screen) {
            case CorePackets.OpenScreen.RACE -> {
                if (mc.gui.screen() == null) mc.gui.setScreen(new RaceScreen());
            }
            case CorePackets.OpenScreen.LEVEL_UP, CorePackets.OpenScreen.SKILLS -> mc.gui.setScreen(new SkillsScreen());
            default -> {
            }
        }
    }
}
