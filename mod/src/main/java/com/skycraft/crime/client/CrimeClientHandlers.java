package com.skycraft.crime.client;

import com.skycraft.crime.CrimePackets;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;

/** Client-side handlers for crime S2C packets (only ever class-loaded on the client). */
public final class CrimeClientHandlers {
    /** Last answer about the container under the crosshair. */
    public static CrimePackets.ContainerInfo containerInfo;
    public static long containerInfoTime;

    private CrimeClientHandlers() {}

    public static void openPickpocket(CrimePackets.OpenPickpocket m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof PickpocketScreen screen && screen.entityId() == m.entityId()) screen.update(m);
        else mc.gui.setScreen(new PickpocketScreen(m));
    }

    /** The opening's loading cover, for an arrest whose jail is being built (title, subtitle, chunks ready of needed). */
    public static void jailCover(CrimePackets.JailCover m) {
        if (!m.show()) {
            com.skycraft.quest.client.LoadingCover.hide();
            return;
        }
        float progress = m.total() <= 0 ? 0f : Math.min(1f, m.ready() / (float) m.total());
        com.skycraft.quest.client.LoadingCover.show(m.title(), m.sub(),
                net.minecraft.network.chat.Component.translatable("crime.skycraft.jail.loading_tip"), progress);
    }

    public static void closePickpocket() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof PickpocketScreen) mc.gui.setScreen(null);
    }

    public static void openLockpick(CrimePackets.OpenLockpick m) {
        Minecraft.getInstance().gui.setScreen(new LockpickScreen(m));
    }

    public static void lockResult(CrimePackets.LockResult m) {
        if (Minecraft.getInstance().gui.screen() instanceof LockpickScreen screen) screen.onResult(m);
    }

    public static void containerInfo(CrimePackets.ContainerInfo m) {
        containerInfo = m;
        containerInfoTime = Util.getMillis();
    }
}
