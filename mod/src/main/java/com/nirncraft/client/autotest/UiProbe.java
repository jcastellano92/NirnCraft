package com.nirncraft.client.autotest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UI flow verbs for the live controller (harness UI tests): open any screen (the title screen too, over the running
 * world), press a button by its label, send a key, and assert which screen is showing.
 * <pre>  screen title|pause|options|&lt;fully.qualified.ScreenClass&gt;    click &lt;label text&gt;    key &lt;GLFW code&gt;
 *   expect-screen &lt;SimpleClassName&gt;|none</pre>
 */
final class UiProbe {
    private UiProbe() {}

    static void open(Minecraft mc, String name) throws ReflectiveOperationException {
        Screen parent = mc.gui.screen();
        Screen s = switch (name) {
            case "title" -> new TitleScreen();
            case "pause" -> new PauseScreen(true);
            case "options" -> new OptionsScreen(parent, mc.options);
            default -> {
                Class<?> c = Class.forName(name);
                try {
                    yield (Screen) c.getConstructor(Screen.class).newInstance(parent);
                } catch (NoSuchMethodException e) {
                    yield (Screen) c.getConstructor().newInstance();
                }
            }
        };
        mc.gui.setScreen(s);
    }

    static void widgets(GuiEventListener l, List<AbstractWidget> out) {
        if (l instanceof AbstractWidget w) out.add(w);
        if (l instanceof ContainerEventHandler c) for (GuiEventListener ch : c.children()) widgets(ch, out);
    }

    static void click(Minecraft mc, String label) {
        Screen s = mc.gui.screen();
        if (s == null) throw new IllegalStateException("click: no screen is open");
        String want = label.toLowerCase(Locale.ROOT);
        List<AbstractWidget> all = new ArrayList<>();
        for (GuiEventListener l : s.children()) widgets(l, all);
        AbstractWidget hit = null;
        for (AbstractWidget w : all) {
            if (!w.visible || !w.isActive()) continue;
            String text = w.getMessage().getString().toLowerCase(Locale.ROOT);
            if (text.equals(want)) { hit = w; break; }
            if (hit == null && text.contains(want)) hit = w;
        }
        if (hit == null) {
            StringBuilder sb = new StringBuilder();
            for (AbstractWidget w : all) if (w.visible) sb.append(" [").append(w.getMessage().getString()).append(']');
            throw new IllegalStateException("click: no active widget labelled \"" + label + "\" on "
                    + s.getClass().getSimpleName() + ";" + sb);
        }
        double x = hit.getX() + hit.getWidth() / 2.0, y = hit.getY() + hit.getHeight() / 2.0;
        hit.onClick(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
    }

    static void key(Minecraft mc, int code) {
        Screen s = mc.gui.screen();
        if (s == null) throw new IllegalStateException("key: no screen is open");
        s.keyPressed(new KeyEvent(code, 0, 0));
    }

    static void expect(Minecraft mc, String name) {
        Screen s = mc.gui.screen();
        String actual = s == null ? "none" : s.getClass().getSimpleName();
        if (!actual.equalsIgnoreCase(name))
            throw new IllegalStateException("expect-screen: wanted " + name + ", showing " + actual);
    }
}
