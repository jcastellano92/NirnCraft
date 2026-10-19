package com.nirncraft.client;

import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Keeps the player sneaking while a screen is open (a chest, a body, a pickpocket, the lockpicking, barter,
 * dialogue, a book, a station, the journal...), as Skyrim does.
 *
 * <p>Why the player stood up: {@code Minecraft#setScreen} calls {@code KeyMapping.releaseAll()}, the sneak key reads
 * as released, {@code KeyboardInput.tick} then writes {@code input.shiftKeyDown = false} on the next tick, and
 * {@code LocalPlayer.isShiftKeyDown()} (the state sent to the server in {@code sendPosition}) IS that input flag.
 * Calling {@code setShiftKeyDown(true)} on the entity does nothing for a LocalPlayer. After the screen closes the key
 * stays "up" until the operating system's key repeat sets it again (about half a second of standing).
 *
 * <p>This class: when a screen opens while the player is sneaking with the sneak key physically held, it arms. While
 * armed it forces {@code input.shiftKeyDown = true} after every input update (so the server never sees a release and
 * the pose never changes) for as long as the physical key stays down; releasing the key disarms it, and the player
 * stands as usual. When the screen is gone the key mapping is handed back to vanilla in the same tick, so there is no
 * gap.
 *
 * <p>Toggle crouch ({@code Options.toggleCrouch}): vanilla's {@code ToggleKeyMapping} ignores the release that
 * {@code releaseAll} sends, so the toggled sneak survives a screen by itself. This class never arms in that mode and
 * never touches the toggle state. Not armed either for passengers (the key dismounts), flying and swimming players
 * (the key descends or sinks), sleepers and the dead.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class SneakKeeper {
    private static boolean holding;

    private SneakKeeper() {}

    /** True while a screen is open (or was this tick) and the keeper is carrying the sneak state. */
    public static boolean holding() {
        return holding;
    }

    /** Runs last, so a screen another handler replaced or cancelled is judged as the screen that really opens. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (next == null || player == null || mc.level == null || mc.options == null) return;
        if (holding) return; // screen to screen: already carried
        if (mc.gui.screen() != null) return; // its keys were released long ago
        if (!eligible(mc, player) || !player.isShiftKeyDown() || !physicalDown(mc)) return;
        holding = true;
    }

    /** Runs before other handlers (the intro scene, which freezes the controls, writes after this and wins). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!holding) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || event.getEntity() != player || mc.options == null
                || !eligible(mc, player) || !physicalDown(mc)) {
            holding = false;
            return;
        }
        ClientInput input = event.getInput();
        var keys = input.keyPresses;
        input.keyPresses = new net.minecraft.world.entity.player.Input(keys.forward(), keys.backward(), keys.left(), keys.right(),
                keys.jump(), true, keys.sprint());
        if (mc.gui.screen() == null) {
            // The screen is gone and the key is still held: give it back to vanilla now instead of waiting for the
            // OS key repeat. (Never in toggle mode: eligible() is false there, so a toggle is never flipped.)
            mc.options.keyShift.setDown(true);
            holding = false;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        holding = false;
    }

    private static boolean eligible(Minecraft mc, LocalPlayer player) {
        if (mc.options.toggleCrouch().get()) return false;
        return !player.isPassenger() && !player.getAbilities().flying && !player.isInWater() && !player.isUnderWater()
                && !player.isSleeping() && !player.isDeadOrDying() && !player.isSpectator();
    }

    /** The sneak key is physically held (works while a screen has swallowed the key events). */
    private static boolean physicalDown(Minecraft mc) {
        InputConstants.Key key = mc.options.keyShift.getKey();
        int value = key.getValue();
        if (value == InputConstants.UNKNOWN.getValue()) return false;
        if (key.getType() == InputConstants.Type.KEYBOARD) return InputConstants.isKeyDown(value);
        if (key.getType() == InputConstants.Type.MOUSE) return (org.lwjgl.sdl.SDLMouse.SDL_GetMouseState(null, null) & (1L << (value - 1))) != 0;
        return false;
    }
}
