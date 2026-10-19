package com.nirncraft.crime.client;

import com.mojang.math.Axis;
import com.nirncraft.crime.CrimePackets;
import com.nirncraft.crime.Locks;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Skyrim's lockpicking minigame. Move the mouse left/right to position the pick (-90..90 degrees), then hold
 * A/D (or the arrow keys / space) to turn the lock. The server decides how far the lock turns, when the pick
 * breaks and when the lock opens; this screen only animates its answers.
 */
public class LockpickScreen extends Screen {
    private static final int LOCK_R = 62;
    private static final int PLATE_R = 42;

    private final int tier;
    private int picks;

    private float pickAngle;
    private float lockRot;
    private float prevLockRot;
    /** How far (0..1) the server says the lock can turn with the current pick angle; -1 = not known yet. */
    private float maxFraction = -1;
    private boolean turning;
    private boolean turnStart;
    private boolean straining;
    private int sendCooldown;
    private int brokenFlash;
    private int closeIn = -1;
    private boolean unlocked;
    private int ticks;

    public LockpickScreen(CrimePackets.OpenLockpick m) {
        super(Component.translatable("screen.nirncraft.lockpick.title"));
        this.tier = m.tier();
        this.picks = m.picks();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ logic

    @Override
    public void tick() {
        ticks++;
        prevLockRot = lockRot;
        if (brokenFlash > 0) brokenFlash--;
        if (closeIn > 0 && --closeIn == 0) {
            onClose();
            return;
        }
        if (unlocked) {
            lockRot = Math.min(90f, lockRot + 12f);
            straining = false;
            return;
        }
        if (turning && picks > 0) {
            if (sendCooldown-- <= 0) {
                SkyNetwork.sendToServer(new CrimePackets.LockTurn(pickAngle, turnStart));
                turnStart = false;
                sendCooldown = 3;
            }
            float limit = maxFraction < 0 ? 0f : maxFraction * 90f;
            if (lockRot < limit) lockRot = Math.min(limit, lockRot + 7f);
            else lockRot = limit;
            straining = maxFraction >= 0 && lockRot >= limit - 0.01f;
            if (straining && ticks % 4 == 0 && minecraft != null) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(com.nirncraft.world.WorldSounds.UI_LOCKPICK_STRAIN.get(), 1.0f, 0.9f));
            } else if (turning && ticks % 5 == 0 && minecraft != null && lockRot < limit) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(com.nirncraft.world.WorldSounds.UI_LOCKPICK_TURN.get(), 1.0f, 1.0f));
            }
        } else {
            lockRot = Math.max(0f, lockRot - 10f);
            straining = false;
        }
    }

    void onResult(CrimePackets.LockResult m) {
        switch (m.result()) {
            case Locks.RESULT_PARTIAL -> {
                if (turning) maxFraction = m.value();
            }
            case Locks.RESULT_UNLOCKED -> {
                unlocked = true;
                turning = false;
                if (minecraft != null) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(com.nirncraft.world.WorldSounds.UI_LOCKPICK_UNLOCK.get(), 1.0f, 1.0f));
                }
                closeIn = 15; // the server opens the container, which replaces this screen
            }
            case Locks.RESULT_BROKEN -> {
                picks = (int) m.value();
                brokenFlash = 20;
                turning = false;
                if (minecraft != null) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(com.nirncraft.world.WorldSounds.UI_LOCKPICK_BREAK.get(), 1.0f, 1.0f));
                }
                maxFraction = -1;
                lockRot = 0;
                prevLockRot = 0;
                if (picks <= 0) closeIn = 25;
            }
            default -> onClose();
        }
    }

    private static boolean isTurnKey(int key) {
        return key == InputConstants.KEY_A || key == InputConstants.KEY_D || key == InputConstants.KEY_LEFT
                || key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_SPACE;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (isTurnKey(key)) {
            if (!turning && !unlocked && picks > 0 && closeIn < 0) {
                turning = true;
                turnStart = true;
                sendCooldown = 0;
                maxFraction = -1;
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (isTurnKey(key)) {
            turning = false;
            maxFraction = -1;
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public void removed() {
        SkyNetwork.sendToServer(new CrimePackets.LockClose());
        super.removed();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        int cx = width / 2;
        int cy = height / 2 + 6;
        if (!turning && !unlocked) {
            float span = Math.max(60f, width / 3f);
            pickAngle = Mth.clamp((mouseX - cx) / span * 90f, -90f, 90f);
        }
        float rot = Mth.lerp(partialTick, prevLockRot, lockRot);

        // lock housing
        disc(g, cx, cy, LOCK_R + 6, 0xFF1C1915);
        ring(g, cx, cy, LOCK_R + 6, 3, 0xFF8A7F66, 96);
        disc(g, cx, cy, LOCK_R, 0xFF2E2922);
        ring(g, cx, cy, LOCK_R - 10, 1, 0x60C8BC9A, 72);
        // bolts
        for (int i = 0; i < 8; i++) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().rotate((i * 45f + 22.5f) * Mth.DEG_TO_RAD);
            g.fill(-2, -LOCK_R + 3, 2, -LOCK_R + 7, 0xFF6A6050);
            g.pose().popMatrix();
        }

        // rotating cylinder with the keyhole
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate((rot) * Mth.DEG_TO_RAD);
        disc(g, 0, 0, PLATE_R, 0xFF4E473B);
        ring(g, 0, 0, PLATE_R, 2, 0xFFA89A78, 72);
        ring(g, 0, 0, PLATE_R - 8, 1, 0x50FFFFFF, 60);
        disc(g, 0, -8, 6, 0xFF0C0A08);
        g.fill(-3, -8, 3, 14, 0xFF0C0A08);
        // tension wrench (turns with the lock)
        g.fill(-2, 12, 2, LOCK_R + 18, 0xFF7C7468);
        g.fill(-2, LOCK_R + 18, 14, LOCK_R + 22, 0xFF7C7468);
        g.pose().popMatrix();

        // the pick
        float shake = straining ? (float) Math.sin(ticks * 2.7 + partialTick * 2.7) * 1.6f : 0f;
        if (brokenFlash <= 0 && picks > 0 && !unlocked) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy - 8);
            g.pose().rotate((pickAngle + shake) * Mth.DEG_TO_RAD);
            int steel = straining ? 0xFFE8D0C0 : 0xFFD8D8D8;
            g.fill(-1, -LOCK_R - 34, 1, -2, steel);
            g.fill(-1, -LOCK_R - 34, 4, -LOCK_R - 32, steel);
            g.fill(-2, -LOCK_R - 34, 2, -LOCK_R - 24, 0xFF5A4630);
            g.pose().popMatrix();
        } else if (brokenFlash > 0) {
            g.centeredText(font, Component.translatable("screen.nirncraft.lockpick.broke"), cx, cy - LOCK_R - 26, 0xFFE06060);
        }

        // texts
        g.centeredText(font, title, cx, 12, 0xFFF5EBC8);
        g.centeredText(font, Component.translatable("screen.nirncraft.lockpick.difficulty", Locks.tierName(tier)), cx, 24, 0xFFD8C8A8);
        g.text(font, Component.translatable("screen.nirncraft.lockpick.picks", picks), 12, height - 22, 0xFFD8D0B8, true);
        Component hint = unlocked ? Component.translatable("screen.nirncraft.lockpick.unlocked")
                : Component.translatable("screen.nirncraft.lockpick.hint");
        g.centeredText(font, hint, cx, height - 22, unlocked ? 0xFF9FE09F : 0xFF8A8270);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Filled circle out of horizontal spans. */
    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** Circle outline made of small rotated segments. */
    private static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int thickness, int color, int segments) {
        int seg = (int) Math.ceil(2 * Math.PI * r / segments) + 1;
        for (int i = 0; i < segments; i++) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().rotate((i * 360f / segments) * Mth.DEG_TO_RAD);
            g.fill(-seg / 2, -r, seg - seg / 2, -r + thickness, color);
            g.pose().popMatrix();
        }
    }
}
