package com.skycraft.audio.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /skycraft-audio debug}: for 10 seconds counts every sound started within 20 blocks of the player (sound id,
 * channel, positional or not, loudest volume) and prints the busiest ones. How a runaway rustle gets found.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class AudioDebug {
    private static final class Stat {
        int count;
        float maxVolume;
        boolean relative;
    }

    private static final Map<String, Stat> STATS = new HashMap<>();
    private static long endsAt;

    private AudioDebug() {}

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("skycraft-audio")
                .then(Commands.literal("debug").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    STATS.clear();
                    endsAt = System.currentTimeMillis() + 10_000L;
                    ctx.getSource().sendSuccess(() -> Component.literal("Skycraft audio: logging sounds within 20 blocks for 10 s..."), false);
                    return 1;
                })));
    }

    /** Called for every sound that is about to play (client thread). */
    static void record(SoundInstance s) {
        if (endsAt == 0 || s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (System.currentTimeMillis() > endsAt) {
            report(mc);
            return;
        }
        boolean rel = s.isRelative();
        if (!rel && mc.player.distanceToSqr(s.getX(), s.getY(), s.getZ()) > 400) return;
        String key = s.getLocation() + " [" + s.getSource().getName() + "]";
        Stat st = STATS.computeIfAbsent(key, k -> new Stat());
        st.count++;
        st.relative = rel;
        st.maxVolume = Math.max(st.maxVolume, s.getVolume());
    }

    /** Ends the window if nothing played since it elapsed (tick). */
    static void tick(Minecraft mc) {
        if (endsAt != 0 && System.currentTimeMillis() > endsAt) report(mc);
    }

    private static void report(Minecraft mc) {
        endsAt = 0;
        List<Map.Entry<String, Stat>> list = new ArrayList<>(STATS.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
        String head = "Skycraft audio, 10 s, within 20 blocks (non-positional beds counted too): " + list.size() + " distinct sounds";
        Skycraft.LOGGER.info(head);
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(head));
        for (int i = 0; i < list.size() && i < 12; i++) {
            Stat st = list.get(i).getValue();
            String line = String.format(Locale.ROOT, "%3dx %s  vol<=%.2f %s", st.count, list.get(i).getKey(), st.maxVolume,
                    st.relative ? "non-positional" : "positional");
            Skycraft.LOGGER.info(line);
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(line));
        }
        STATS.clear();
    }
}
