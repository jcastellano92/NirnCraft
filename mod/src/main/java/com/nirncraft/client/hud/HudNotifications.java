package com.nirncraft.client.hud;

import com.nirncraft.network.CorePackets;
import com.nirncraft.network.NotifyKind;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/** Queues of on-screen notifications, drawn by {@link SkyHud#renderNotifications}. */
public final class HudNotifications {
    /** One-at-a-time banners (skill increases, level up, quests, discoveries, big titles). */
    static final Deque<Entry> BANNERS = new ArrayDeque<>();
    /** Top-left message log. */
    static final List<Entry> MESSAGES = new ArrayList<>();
    static Entry currentBanner;

    private HudNotifications() {}

    public record Entry(CorePackets.Notify msg, long start, long duration) {
        float age() {
            return (Util.getMillis() - start) / (float) duration;
        }
    }

    public static void add(CorePackets.Notify msg) {
        long now = Util.getMillis();
        switch (msg.kind()) {
            case CRIME -> {
                MESSAGES.removeIf(e -> e.msg.kind() == NotifyKind.CRIME);
                MESSAGES.add(new Entry(msg, now, 5000));
            }
            case MESSAGE -> {
                String text = msg.title().getString();
                MESSAGES.removeIf(e -> e.msg.title().getString().equals(text));
                MESSAGES.add(new Entry(msg, now, 5000));
                while (MESSAGES.size() > 5) MESSAGES.remove(0);
            }
            default -> {
                // Merge consecutive increases of the same skill or duplicate banners so spamming doesn't flood the queue.
                if (msg.kind() == NotifyKind.SKILL_UP) {
                    BANNERS.removeIf(e -> e.msg.kind() == NotifyKind.SKILL_UP && e.msg.value() == msg.value());
                } else {
                    String title = msg.title().getString();
                    BANNERS.removeIf(e -> e.msg.kind() == msg.kind() && e.msg.title().getString().equals(title));
                }
                while (BANNERS.size() >= 4) BANNERS.pollFirst();
                BANNERS.add(new Entry(msg, 0, durationOf(msg.kind())));
            }
        }

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            switch (msg.kind()) {
                // NirnCraft UI sounds (audio module); skill-ups are heard from the server's ui.skill_up
                case LEVEL_UP -> {
                    // the music fanfare (atmosphere module) covers it when it plays
                    if (!com.nirncraft.atmosphere.AtmosphereConfig.LEVEL_UP_STING.get()
                            || mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MUSIC) <= 0f) {
                        uiSound(com.nirncraft.audio.AudioSounds.get("ui.level_up"));
                    }
                }
                case QUEST_COMPLETED -> uiSound(com.nirncraft.audio.AudioSounds.get("ui.quest_complete"));
                case QUEST_UPDATED, QUEST_STARTED -> uiSound(com.nirncraft.world.WorldSounds.UI_QUEST_UPDATE.get());
                case LOCATION_DISCOVERED -> uiSound(com.nirncraft.audio.AudioSounds.get("ui.discover"));
                default -> {}
            }
        }
    }

    private static void uiSound(net.minecraft.sounds.SoundEvent sound) {
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
                .play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, 1.0f, 1.0f));
    }

    private static long durationOf(NotifyKind kind) {
        return switch (kind) {
            case SKILL_UP -> 2600;
            case LEVEL_UP -> 4500;
            case BIG_TITLE -> 4000;
            default -> 3500;
        };
    }

    static Entry banner() {
        long now = Util.getMillis();
        if (currentBanner != null && now - currentBanner.start > currentBanner.duration) currentBanner = null;
        if (currentBanner == null && !BANNERS.isEmpty()) {
            Entry next = BANNERS.poll();
            currentBanner = new Entry(next.msg, now, next.duration);
        }
        return currentBanner;
    }

    /** Lines the top-left message log currently shows (the guide's tutorial hints sit below them). */
    public static int messageLines() {
        long now = Util.getMillis();
        int n = 0;
        for (Entry e : MESSAGES) {
            if (now - e.start <= e.duration) n++;
        }
        return n;
    }

    static void prune() {
        long now = Util.getMillis();
        for (Iterator<Entry> it = MESSAGES.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (now - e.start > e.duration) it.remove();
        }
    }
}

