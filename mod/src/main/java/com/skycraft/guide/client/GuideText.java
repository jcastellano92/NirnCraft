package com.skycraft.guide.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Turns guide text into components. {@code {key:<mapping>}} becomes the player's current binding of that key (e.g.
 * {@code {key:key.skycraft.map}} -> "M", or "not bound"), drawn in {@code keyStyle}; {@code **bold**} spans are bold.
 * Bindings are looked up when the text is shown, so the handbook and hints always name the keys the player really uses.
 */
public final class GuideText {
    private GuideText() {}

    public static MutableComponent format(String raw, Style keyStyle) {
        MutableComponent out = Component.empty();
        int i = 0;
        boolean bold = false;
        StringBuilder run = new StringBuilder();
        while (i < raw.length()) {
            if (raw.startsWith("{key:", i)) {
                int end = raw.indexOf('}', i);
                if (end > 0) {
                    flush(out, run, bold);
                    out.append(keyName(raw.substring(i + 5, end)).copy().withStyle(keyStyle.withBold(true)));
                    i = end + 1;
                    continue;
                }
            }
            if (raw.startsWith("**", i)) {
                flush(out, run, bold);
                bold = !bold;
                i += 2;
                continue;
            }
            run.append(raw.charAt(i++));
        }
        flush(out, run, bold);
        return out;
    }

    private static void flush(MutableComponent out, StringBuilder run, boolean bold) {
        if (run.isEmpty()) return;
        out.append(Component.literal(run.toString()).withStyle(Style.EMPTY.withBold(bold)));
        run.setLength(0);
    }

    /** The display name of the key bound to {@code mapping}, or "not bound". */
    public static Component keyName(String mapping) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            for (KeyMapping key : mc.options.keyMappings) {
                if (key.getName().equals(mapping)) {
                    return key.isUnbound() ? Component.translatable("guide.skycraft.unbound") : key.getTranslatedKeyMessage();
                }
            }
        }
        return Component.literal("?");
    }

    /** Removes the markup, for plain-text uses (logs, tests). */
    public static String plain(String raw) {
        return raw.replaceAll("\\{key:[^}]*}", "?").replace("**", "");
    }
}
