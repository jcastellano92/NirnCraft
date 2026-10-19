package com.nirncraft.world.client;

import com.nirncraft.client.SkyKeys;
import com.nirncraft.world.RestLogic;
import com.nirncraft.world.SkyrimCalendar;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Skyrim's centered "Wait" / "Sleep" modal dialog: pick 1-24 hours on the slider (or with arrow keys / scroll wheel),
 * view the current Tamrielic calendar date and time, and watch the hours count down live as time passes.
 */
public class RestScreen extends Screen {
    private static int lastWaitHours = 1;
    private static int lastSleepHours = 8;

    private boolean sleep;
    private int hours;
    private boolean closedByServer;
    private HoursSlider slider;
    private Button confirmBtn;

    public RestScreen(boolean sleep) {
        super(Component.translatable(sleep ? "screen.nirncraft.sleep" : "screen.nirncraft.wait"));
        this.sleep = sleep;
        this.hours = sleep ? lastSleepHours : lastWaitHours;
    }

    public RestScreen(int mode) {
        this(mode == RestLogic.SLEEP);
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;
        slider = addRenderableWidget(new HoursSlider(cx - 100, cy + 4, 200, 20));
        confirmBtn = addRenderableWidget(Button.builder(
                Component.translatable(sleep ? "world.nirncraft.rest.do_sleep" : "world.nirncraft.rest.do_wait"),
                b -> confirm()).bounds(cx - 102, cy + 34, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, cy + 34, 100, 20).build());
        syncWidgets();
    }

    void onServerState() {
        boolean nowSleep = RestClient.mode() == RestLogic.SLEEP;
        if (nowSleep != this.sleep) {
            this.sleep = nowSleep;
            if (confirmBtn != null) {
                confirmBtn.setMessage(Component.translatable(sleep ? "world.nirncraft.rest.do_sleep" : "world.nirncraft.rest.do_wait"));
            }
        }
        syncWidgets();
    }

    void closeFromServer() {
        this.closedByServer = true;
        if (minecraft != null && minecraft.gui.screen() == this) {
            minecraft.gui.setScreen(null);
        }
    }

    private void syncWidgets() {
        int phase = RestClient.phase();
        boolean passing = phase == 2;
        if (confirmBtn != null) {
            confirmBtn.active = !passing && RestClient.canStart();
        }
        if (slider != null) {
            slider.active = !passing && !RestClient.isSkipDisabled();
            if (passing) {
                int total = Math.max(1, RestClient.hours());
                int prog = Mth.clamp(RestClient.progress(), 0, 1000);
                double remExact = total * (1.0 - prog / 1000.0);
                int remHours = Math.max(1, Mth.ceil((float) remExact));
                slider.setCountdownValue(remExact / 24.0, remHours);
            } else {
                slider.syncFromHours();
            }
        }
    }

    private void setHours(int newHours) {
        this.hours = Mth.clamp(newHours, 1, 24);
        if (sleep) lastSleepHours = this.hours;
        else lastWaitHours = this.hours;
        if (slider != null) {
            slider.syncFromHours();
        }
    }

    private void confirm() {
        if (!RestClient.canStart()) return;
        if (sleep) lastSleepHours = hours;
        else lastWaitHours = hours;
        RestClient.start(hours);
    }

    @Override
    public void onClose() {
        if (!closedByServer && RestClient.phase() != 0) {
            RestClient.sendCancel();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (RestClient.phase() == 1) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                confirm();
                return true;
            }
            if (key == InputConstants.KEY_LEFT) {
                setHours(hours - 1);
                return true;
            }
            if (key == InputConstants.KEY_RIGHT) {
                setHours(hours + 1);
                return true;
            }
            if (key == InputConstants.KEY_UP && minecraft != null && minecraft.level != null) {
                setHours(RestLogic.hoursUntil(minecraft.level.getOverworldClockTime(), 6));
                return true;
            }
            if (key == InputConstants.KEY_DOWN && minecraft != null && minecraft.level != null) {
                setHours(RestLogic.hoursUntil(minecraft.level.getOverworldClockTime(), 18));
                return true;
            }
        }
        if (SkyKeys.WAIT.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double delta) {
        if (RestClient.phase() == 1 && delta != 0) {
            setHours(hours + (delta > 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, delta);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        syncWidgets();
        if (RestClient.phase() != 2) {

        }
        int cx = width / 2;
        int cy = height / 2;

        // Skyrim-styled dark stone and bronze-framed modal panel
        int x0 = cx - 134, y0 = cy - 72, x1 = cx + 134, y1 = cy + 66;
        g.fillGradient(x0, y0, x1, y1, 0xDF100C08, 0xDF1E1710);
        // Outer & inner borders
        g.fill(x0, y0, x1, y0 + 1, 0xFF8A7F66);
        g.fill(x0, y1 - 1, x1, y1, 0xFF8A7F66);
        g.fill(x0, y0, x0 + 1, y1, 0xFF8A7F66);
        g.fill(x1 - 1, y0, x1, y1, 0xFF8A7F66);
        g.fill(x0 + 3, y0 + 3, x1 - 3, y0 + 4, 0x558A7F66);
        g.fill(x0 + 3, y1 - 4, x1 - 3, y1 - 3, 0x558A7F66);
        // Header divider rule with center diamond accent
        g.fill(cx - 96, cy - 44, cx + 96, cy - 43, 0x808A7F66);
        g.fill(cx - 2, cy - 45, cx + 2, cy - 42, 0xFFC8A850);

        Component header = Component.translatable(sleep ? "screen.nirncraft.sleep" : "screen.nirncraft.wait");
        g.pose().pushMatrix();
        g.pose().translate(cx, cy - 62);
        g.pose().scale(1.6f, 1.6f);
        g.centeredText(font, header, 0, 0, 0xFFF5EBC8);
        g.pose().popMatrix();

        long dayTime = minecraft != null && minecraft.level != null ? minecraft.level.getOverworldClockTime() : 0;
        g.centeredText(font, SkyrimCalendar.date(dayTime), cx, cy - 38, 0xFFD0C8B0);
        g.centeredText(font, SkyrimCalendar.time(dayTime), cx, cy - 26, 0xFFE8C060);

        Component subLine;
        int subColor = 0xFFA89F86;
        if (RestClient.phase() == 2) {
            int total = Math.max(1, RestClient.hours());
            int passed = Math.min(total, (int) (total * (long) RestClient.progress() / 1000L));
            subLine = Component.translatable("world.nirncraft.rest.passing", passed, total);
            subColor = 0xFFF5EBC8;
        } else if (RestClient.isSkipDisabled()) {
            subLine = Component.translatable("world.nirncraft.rest.skip_off");
        } else if (!RestClient.canStart() && RestClient.required() > 1) {
            if (!RestClient.isInitiator() && RestClient.resting() >= RestClient.required()) {
                subLine = Component.translatable("world.nirncraft.rest.chooses", RestClient.initiator());
            } else {
                subLine = Component.translatable("world.nirncraft.rest.waiting_others", RestClient.resting(), RestClient.required());
            }
        } else {
            subLine = Component.translatable("world.nirncraft.rest.until", SkyrimCalendar.time(dayTime + hours * 1000L));
        }
        g.centeredText(font, subLine, cx, cy - 12, subColor);

        if (sleep && RestClient.phase() == 1) {
            g.centeredText(font, Component.translatable("world.nirncraft.rest.vanilla_hint"), cx, cy + 72, 0xFF8A7F66);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private Component sliderLabel(int h) {
        return Component.translatable(h == 1 ? "world.nirncraft.rest.hour" : "world.nirncraft.rest.hours", h);
    }

    private class HoursSlider extends AbstractSliderButton {
        HoursSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), (hours - 1) / 23.0);
            updateMessage();
        }

        void syncFromHours() {
            this.value = Mth.clamp((hours - 1) / 23.0, 0.0, 1.0);
            updateMessage();
        }

        void setCountdownValue(double normValue, int remainingHours) {
            this.value = Mth.clamp(normValue, 0.0, 1.0);
            setMessage(sliderLabel(remainingHours));
        }

        @Override
        protected void updateMessage() {
            setMessage(sliderLabel(hours));
        }

        @Override
        protected void applyValue() {
            hours = Mth.clamp(1 + (int) Math.round(value * 23), 1, 24);
            if (sleep) lastSleepHours = hours;
            else lastWaitHours = hours;
        }
    }
}
