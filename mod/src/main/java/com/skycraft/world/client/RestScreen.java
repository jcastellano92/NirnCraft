package com.skycraft.world.client;

import com.skycraft.client.SkyKeys;
import com.skycraft.world.RestLogic;
import com.skycraft.world.RestManager;
import com.skycraft.world.SkyrimCalendar;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The small prompt the wait key opens: sleep in the bed within reach, or wait sitting on the ground. The choice puts
 * the player in the pose; the hours are then picked in the non-blocking panel of {@link RestClient} (so the camera
 * stays free), and, with other players online, joining a rest that somebody started uses the same two buttons.
 */
public class RestScreen extends Screen {
    /** Reach for a bed (matches the server's check for the key). */
    private static final double REACH = 3.5;

    private final int current;
    private boolean bedNear;

    /** @param current the mode the player already rests in ({@link RestLogic#NONE} when standing) */
    public RestScreen(int current) {
        super(Component.translatable("screen.skycraft.rest"));
        this.current = current;
    }

    @Override
    protected void init() {
        var mc = minecraft;
        bedNear = mc != null && mc.level != null && mc.player != null && RestManager.findBed(mc.level, mc.player, REACH) != null;
        int cx = width / 2;
        int cy = height / 2;
        Button sleep = Button.builder(Component.translatable("world.skycraft.rest.do_sleep"), b -> choose(RestLogic.SLEEP))
                .bounds(cx - 102, cy + 2, 100, 20).build();
        sleep.active = bedNear && current != RestLogic.SLEEP;
        addRenderableWidget(sleep);
        Button wait = Button.builder(Component.translatable("world.skycraft.rest.do_wait"), b -> choose(RestLogic.WAIT))
                .bounds(cx + 2, cy + 2, 100, 20).build();
        wait.active = current != RestLogic.WAIT;
        addRenderableWidget(wait);
        if (current != RestLogic.NONE) {
            addRenderableWidget(Button.builder(Component.translatable("world.skycraft.rest.stand_up"), b -> {
                RestClient.sendCancel();
                onClose();
            }).bounds(cx - 102, cy + 26, 100, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, cy + 26, 100, 20).build());
    }

    private void choose(int mode) {
        RestClient.sendEnter(mode);
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (SkyKeys.WAIT.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // no dimming background: the world stays visible behind the small prompt
        int cx = width / 2;
        int cy = height / 2;
        g.fillGradient(cx - 112, cy - 46, cx + 112, cy + 52, 0xD0100C08, 0xD0201810);
        g.fill(cx - 112, cy - 46, cx + 112, cy - 45, 0xFF8A7F66);
        g.fill(cx - 112, cy + 51, cx + 112, cy + 52, 0xFF8A7F66);
        g.drawCenteredString(font, title, cx, cy - 40, 0xFFF5EBC8);
        long dayTime = minecraft != null && minecraft.level != null ? minecraft.level.getDayTime() : 0;
        g.drawCenteredString(font, SkyrimCalendar.date(dayTime), cx, cy - 28, 0xFFD0C8B0);
        g.drawCenteredString(font, SkyrimCalendar.time(dayTime), cx, cy - 17, 0xFFE8C060);
        Component note = bedNear ? Component.translatable("world.skycraft.rest.bed_near")
                : Component.translatable("world.skycraft.rest.no_bed_near");
        g.drawCenteredString(font, note, cx, cy - 5, bedNear ? 0xFFA8C888 : 0xFFA89F86);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
