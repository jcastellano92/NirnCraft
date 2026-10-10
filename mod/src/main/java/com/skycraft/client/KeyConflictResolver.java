package com.skycraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.skycraft.Skycraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Good default controls in a big modpack. Runs once per launch, right after the options are loaded.
 *
 * <ul>
 *     <li><b>Very first launch only</b> (marker file {@code config/skycraft-client-keys.done}): the Skyrim control
 *     defaults. Drop, swap-offhand and advancements are unbound, toggle-crouch is turned off, Oculus' R/K/O keys are
 *     unbound if still at their defaults, and any other key (vanilla or mod) still at its default that clashes with
 *     a Skycraft key's documented default is unbound, so Skycraft keys keep their documented defaults.</li>
 *     <li><b>Every launch:</b> a Skycraft key that is still at its default but clashes with another key (e.g. a mod
 *     added later) is moved to a free key. Other mods' keys and anything the player rebound are never touched.</li>
 * </ul>
 * Options are saved (and the client-info packet sent) only if a binding actually changed.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class KeyConflictResolver {
    private static final int[] FALLBACKS = {
            GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_O,
            GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_X,
            GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_PERIOD,
            GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_RIGHT_BRACKET,
            GLFW.GLFW_KEY_BACKSLASH, GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_CAPS_LOCK,
            GLFW.GLFW_KEY_KP_0, GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_KP_2, GLFW.GLFW_KEY_KP_3
    };
    /** Vanilla keys Skyrim controls don't use (Q drop is Favorites, F swap is Interact). */
    private static final Set<String> UNBIND_ON_FIRST_LAUNCH = Set.of("key.drop", "key.swapoffhand", "key.advancements");
    /** Oculus' default R (reload), K (toggle shaders) and O (pack selection) collide with Journal/Skills/Stone power. */
    private static final Set<String> OCULUS_KEYS = Set.of("iris.keybind.reload", "iris.keybind.toggleshaders", "iris.keybind.shaderpackselection");
    /** Hold-to-show overlays that may share a key with a Skycraft key (player list on Tab with the Hub). */
    private static final Set<String> SHARED_OK = Set.of("key.playerlist");

    private static boolean done;

    private KeyConflictResolver() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        done = true;
        try {
            resolve(mc);
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Could not resolve key conflicts", e);
        }
    }

    private static Path marker() {
        return FMLPaths.CONFIGDIR.get().resolve("skycraft-client-keys.done");
    }

    private static void resolve(Minecraft mc) {
        KeyMapping[] all = mc.options.keyMappings;
        boolean changed = false;
        Path marker = marker();
        boolean firstLaunch = !Files.exists(marker);

        if (firstLaunch) {
            for (KeyMapping k : all) {
                String name = k.getName().toLowerCase(Locale.ROOT);
                boolean unbind = UNBIND_ON_FIRST_LAUNCH.contains(name)
                        || OCULUS_KEYS.contains(name) && k.isDefault();
                if (unbind && !k.isUnbound()) {
                    k.setKey(InputConstants.UNKNOWN);
                    changed = true;
                }
            }
            // Skycraft keys keep their documented defaults: other keys still at their default that clash are unbound.
            for (KeyMapping ours : all) {
                if (!isOurs(ours) || !ours.isDefault() || ours.isUnbound()) continue;
                for (KeyMapping other : all) {
                    if (other == ours || isOurs(other) || other.isUnbound() || !other.isDefault()) continue;
                    if (SHARED_OK.contains(other.getName().toLowerCase(Locale.ROOT))) continue;
                    if (!other.getKey().equals(ours.getKey()) || !ours.getKeyConflictContext().conflicts(other.getKeyConflictContext())) continue;
                    Skycraft.LOGGER.info("First launch: unbinding {} ({}) so {} keeps its default", other.getName(),
                            other.getKey().getName(), ours.getName());
                    other.setKey(InputConstants.UNKNOWN);
                    changed = true;
                }
            }
            if (mc.options.toggleCrouch().get()) {
                mc.options.toggleCrouch().set(false);
                changed = true;
            }
        }

        // Every launch: move a Skycraft key still at its default off a clash (never touch anyone else's keys).
        for (KeyMapping ours : all) {
            if (!isOurs(ours) || !ours.isDefault() || ours.isUnbound()) continue;
            if (!conflicts(ours, ours.getKey(), all)) continue;
            Set<Integer> used = usedKeys(all, ours);
            for (int code : FALLBACKS) {
                InputConstants.Key candidate = InputConstants.Type.KEYSYM.getOrCreate(code);
                if (used.contains(code) || conflicts(ours, candidate, all)) continue;
                Skycraft.LOGGER.info("Rebinding {} from {} to {} to avoid a key conflict", ours.getName(),
                        ours.getKey().getName(), candidate.getName());
                ours.setKey(candidate);
                changed = true;
                break;
            }
        }

        if (changed) {
            KeyMapping.resetMapping();
            mc.options.save();
        }
        if (firstLaunch) {
            try {
                Files.writeString(marker, "Skycraft applied its default controls once. Delete this file to apply them again.\n");
            } catch (Exception e) {
                Skycraft.LOGGER.warn("Could not write {}", marker, e);
            }
        }
    }

    private static boolean isOurs(KeyMapping key) {
        return key.getName().startsWith("key.skycraft.");
    }

    private static Set<Integer> usedKeys(KeyMapping[] all, KeyMapping self) {
        Set<Integer> used = new HashSet<>();
        for (KeyMapping k : all) {
            if (k != self && !k.isUnbound() && k.getKey().getType() == InputConstants.Type.KEYSYM) used.add(k.getKey().getValue());
        }
        return used;
    }

    /** True if another mapping that can be active in game uses {@code key} (shared hold-to-show overlays excepted). */
    private static boolean conflicts(KeyMapping self, InputConstants.Key key, KeyMapping[] all) {
        for (KeyMapping other : all) {
            if (other == self || other.isUnbound()) continue;
            if (!other.getKey().equals(key)) continue;
            if (SHARED_OK.contains(other.getName().toLowerCase(Locale.ROOT))) continue;
            if (self.getKeyConflictContext().conflicts(other.getKeyConflictContext())) return true;
        }
        return false;
    }
}
