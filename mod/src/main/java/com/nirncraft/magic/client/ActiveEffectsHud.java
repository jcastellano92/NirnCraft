package com.nirncraft.magic.client;

import com.nirncraft.core.HudStack;
import com.nirncraft.magic.MagicPackets;
import net.minecraft.util.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The bottom-right strip of active spell effects with a countdown ("Bound Sword 1:42", "Detect Life 12s"), soonest to
 * run out first, red and blinking in the last 5 seconds. Two sources: the server's {@link MagicPackets.Timers} list for
 * the effects that are not mob effects (bound weapons, detect spells, lights, summons, areas), and the player's own
 * beneficial mob effects (Oakflesh, Waterbreathing, Invisibility, fortify...) read here. The strip is one slot of the
 * {@link HudStack} bottom-right column, so it never overprints the voice words, the spell label or the carry weight.
 */
public final class ActiveEffectsHud {
    /** Rows shown at most. */
    private static final int MAX_ROWS = 6;
    private static final int ROW = 10;
    /** Mob effects longer than this are background state (permanent, ship...), not a countdown. */
    private static final int MAX_TICKS = 20 * 60 * 30;
    private static final int WARN_SECONDS = 5;

    private record Item(Component name, long endMs, int rgb) {}

    private static List<Item> synced = List.of();

    private ActiveEffectsHud() {}

    /** The server's list (ticks left are turned into a client clock time, so it counts down smoothly). */
    public static void set(List<MagicPackets.Timers.Entry> list) {
        long now = Util.getMillis();
        List<Item> out = new ArrayList<>(list.size());
        for (MagicPackets.Timers.Entry e : list) out.add(new Item(e.name(), now + e.ticks() * 50L, e.rgb()));
        synced = out;
    }

    public static void clear() {
        synced = List.of();
    }

    /** "42s" under a minute, "1:05" from a minute on. */
    public static String format(long seconds) {
        return com.nirncraft.magic.ActiveMagic.format(seconds);
    }

    public static void render(GuiGraphicsExtractor g, Font font, LocalPlayer player, int width, int height) {
        long now = Util.getMillis();
        List<Item> items = new ArrayList<>(synced.size() + 4);
        for (Item i : synced) if (i.endMs > now) items.add(i);
        for (MobEffectInstance e : player.getActiveEffects()) {
            if (!e.getEffect().value().isBeneficial() || !e.showIcon() || e.isInfiniteDuration()) continue;
            int d = e.getDuration();
            if (d <= 0 || d > MAX_TICKS) continue;
            items.add(new Item(e.getEffect().value().getDisplayName(), now + d * 50L, 0xFF000000 | e.getEffect().value().getColor()));
        }
        if (items.isEmpty()) return;
        items.sort(Comparator.comparingLong(Item::endMs));
        int rows = Math.min(MAX_ROWS, items.size());
        int y = HudStack.reserve(HudStack.Zone.BOTTOM_RIGHT, HudStack.BR_EFFECTS, rows * ROW, height);
        int right = width - 12;
        for (int i = 0; i < rows; i++) {
            Item it = items.get(i);
            long ms = it.endMs - now;
            long seconds = (ms + 999) / 1000;
            boolean warn = seconds <= WARN_SECONDS;
            String time = format(seconds);
            int timeColor = warn ? ((now / 250) % 2 == 0 ? 0xFFFF6060 : 0xFFFFFFFF) : 0xFFE8E2D0;
            int timeW = font.width(time);
            String name = com.nirncraft.magic.client.MagicUi.ellipsize(font, it.name.getString(), 130);
            int ry = y + i * ROW;
            g.text(font, time, right - timeW, ry, timeColor, true);
            g.text(font, name, right - timeW - 5 - font.width(name), ry, warn ? 0xFFFFC8B0 : 0xFFC8C2B0, true);
            // a tiny swatch of the effect's color before the name
            int sx = right - timeW - 5 - font.width(name) - 6;
            g.fill(sx, ry + 2, sx + 3, ry + 7, 0xFF000000 | it.rgb);
        }
    }
}
