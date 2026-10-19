package com.nirncraft.client;

import com.nirncraft.NirnCraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Good default controls in a big modpack. Runs once per launch, right after the options are loaded.
 *
 * <ul>
 *     <li><b>Very first launch only</b> (marker file {@code config/nirncraft-client-keys.done}): the Skyrim control
 *     defaults. Drop, swap-offhand and advancements are unbound, toggle-crouch is turned off, Oculus' R/K/O keys are
 *     unbound if still at their defaults, and any other key (vanilla or mod) still at its default that clashes with
 *     a NirnCraft key's documented default is unbound, so NirnCraft keys keep their documented defaults.</li>
 *     <li><b>Every launch:</b> a NirnCraft key that is still at its default but clashes with another key (e.g. a mod
 *     added later) is moved to a free key. Other mods' keys and anything the player rebound are never touched.</li>
 * </ul>
 * Options are saved (and the client-info packet sent) only if a binding actually changed.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class KeyConflictResolver {
    private static final int[] FALLBACKS = {
            InputConstants.KEY_G, InputConstants.KEY_J, InputConstants.KEY_K, InputConstants.KEY_N, InputConstants.KEY_O,
            InputConstants.KEY_P, InputConstants.KEY_U, InputConstants.KEY_I, InputConstants.KEY_Y, InputConstants.KEY_Z, InputConstants.KEY_X,
            InputConstants.KEY_B, InputConstants.KEY_V, InputConstants.KEY_R, InputConstants.KEY_C, InputConstants.KEY_COMMA, InputConstants.KEY_PERIOD,
            InputConstants.KEY_SEMICOLON, InputConstants.KEY_APOSTROPHE, InputConstants.KEY_LBRACKET, InputConstants.KEY_RBRACKET,
            InputConstants.KEY_BACKSLASH, InputConstants.KEY_MINUS, InputConstants.KEY_EQUALS, InputConstants.KEY_CAPSLOCK,
            InputConstants.KEY_NUMPAD0, InputConstants.KEY_NUMPAD1, InputConstants.KEY_NUMPAD2, InputConstants.KEY_NUMPAD3
    };
    /** Vanilla keys Skyrim controls don't use (Q drop is Favorites, F swap is Interact). */
    private static final Set<String> UNBIND_ON_FIRST_LAUNCH = Set.of("key.drop", "key.swapoffhand", "key.advancements");
    /** Oculus' default R (reload), K (toggle shaders) and O (pack selection) collide with Journal/Skills/Stone power. */
    private static final Set<String> OCULUS_KEYS = Set.of("iris.keybind.reload", "iris.keybind.toggleshaders", "iris.keybind.shaderpackselection");
    /** Hold-to-show overlays that may share a key with a NirnCraft key (player list on Tab with the Hub). */
    private static final Set<String> SHARED_OK = Set.of("key.playerlist");

    /**
     * Layout v2 (owner, playtest 4): R favorites, Q quest journal, Z shout with no chat peek. NirnCraft keys still on
     * their previous default move to the new one: {name, previous key, new key}.
     */
    private static final Object[][] LAYOUT_V2 = {
            {"key.nirncraft.favorites", InputConstants.KEY_Q, InputConstants.KEY_R},
            {"key.nirncraft.journal", InputConstants.KEY_J, InputConstants.KEY_Q},
            {"key.nirncraft.sail_up", InputConstants.KEY_R, InputConstants.KEY_EQUALS},
            {"key.nirncraft.sail_down", InputConstants.KEY_V, InputConstants.KEY_MINUS},
    };
    /** Essential's keys that sit on NirnCraft's (chat peek on Z, emote wheel on R, friends on H, zoom on C): unbound once. */
    private static final Set<String> ESSENTIAL_KEYS = Set.of("keybind.name.chat_peek", "keybind.name.emote_wheel",
            "keybind.name.zoom", "keybind.name.essential_friends");

    private static boolean done;

    private KeyConflictResolver() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        done = true;
        try {
            resolve(mc);
        } catch (Exception e) {
            NirnCraft.LOGGER.warn("Could not resolve key conflicts", e);
        }
    }

    private static Path marker() {
        return FMLPaths.CONFIGDIR.get().resolve("nirncraft-client-keys.done");
    }

    /**
     * The first-launch marker is written in a finally: whatever happens below, the one-time defaults (toggle crouch
     * off included) are never applied a second time, so a player who turns toggle crouch on keeps it on.
     */
    private static void resolve(Minecraft mc) {
        Path marker = marker();
        boolean firstLaunch = !Files.exists(marker);
        try {
            resolve(mc, firstLaunch);
        } finally {
            if (firstLaunch) {
                try {
                    Files.writeString(marker, "NirnCraft applied its default controls once. Delete this file to apply them again.\n");
                } catch (Exception e) {
                    NirnCraft.LOGGER.warn("Could not write {}", marker, e);
                }
            }
        }
    }

    private static void resolve(Minecraft mc, boolean firstLaunch) {
        KeyMapping[] all = mc.options.keyMappings;
        boolean changed = false;

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
            // NirnCraft keys keep their documented defaults: other keys still at their default that clash are unbound.
            for (KeyMapping ours : all) {
                if (!isOurs(ours) || !ours.isDefault() || ours.isUnbound()) continue;
                for (KeyMapping other : all) {
                    if (other == ours || isOurs(other) || other.isUnbound() || !other.isDefault()) continue;
                    if (SHARED_OK.contains(other.getName().toLowerCase(Locale.ROOT))) continue;
                    if (!other.getKey().equals(ours.getKey()) || !ours.getKeyConflictContext().conflicts(other.getKeyConflictContext())) continue;
                    NirnCraft.LOGGER.info("First launch: unbinding {} ({}) so {} keeps its default", other.getName(),
                            other.getKey().getName(), ours.getName());
                    other.setKey(InputConstants.UNKNOWN);
                    changed = true;
                }
            }
            // Default off, set once: this block only runs while the marker is missing, so the player can turn it on later.
            if (mc.options.toggleCrouch().get()) {
                mc.options.toggleCrouch().set(false);
                changed = true;
            }
        }

        // Layout v2, once: previous defaults move to the owner's layout and Essential's colliding keys are unbound
        Path markerV2 = FMLPaths.CONFIGDIR.get().resolve("nirncraft-client-keys.v2.done");
        boolean layoutV2 = !Files.exists(markerV2);
        if (layoutV2) {
            changed |= migrateLayoutV2(all);
            try {
                Files.writeString(markerV2, "NirnCraft key layout v2 (R favorites, Q journal, Z shout) applied once. Delete to apply again.\n");
            } catch (Exception e) {
                NirnCraft.LOGGER.warn("Could not write {}", markerV2, e);
            }
        }

        // Every launch: NirnCraft keys win. Another mod's key that shares a key with a NirnCraft key is unbound (and
        // logged once, since it is then unbound). Vanilla's own keys are only touched when the NirnCraft key is at its
        // documented default, never because a player rebound a NirnCraft key onto them.
        Set<KeyMapping> vanilla = vanillaKeys(mc);
        for (KeyMapping ours : all) {
            if (!isOurs(ours) || ours.isUnbound()) continue;
            for (KeyMapping other : all) {
                if (other == ours || isOurs(other) || other.isUnbound() || !other.getKey().equals(ours.getKey())) continue;
                if (SHARED_OK.contains(other.getName().toLowerCase(Locale.ROOT))) continue;
                if (!ours.getKeyConflictContext().conflicts(other.getKeyConflictContext())) continue;
                if (vanilla.contains(other) && !ours.isDefault()) continue;
                NirnCraft.LOGGER.info("Key conflict: unbinding {} ({}); {} keeps the key", other.getName(), other.getKey().getName(),
                        ours.getName());
                other.setKey(InputConstants.UNKNOWN);
                changed = true;
            }
        }

        // A NirnCraft key still at its default that clashes with another key (e.g. one of the player's own, or another
        // NirnCraft key) moves to a free key. Never touches anyone else's keys.
        for (KeyMapping ours : all) {
            if (!isOurs(ours) || ours.isUnbound()) continue;
            if (!ours.isDefault() && !sharesWithChat(ours, all)) continue;
            if (!conflicts(ours, ours.getKey(), all)) continue;
            Set<Integer> used = usedKeys(all, ours);
            for (int code : FALLBACKS) {
                InputConstants.Key candidate = InputConstants.Type.KEYBOARD.getOrCreate(code);
                if (used.contains(code) || conflicts(ours, candidate, all)) continue;
                NirnCraft.LOGGER.info("Rebinding {} from {} to {} to avoid a key conflict", ours.getName(),
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
    }

    /** Moves NirnCraft keys off their previous defaults and unbinds Essential's colliding keys. True if anything changed. */
    private static boolean migrateLayoutV2(KeyMapping[] all) {
        boolean changed = false;
        java.util.Map<KeyMapping, Integer> moves = new java.util.HashMap<>();
        for (Object[] row : LAYOUT_V2) {
            for (KeyMapping k : all) {
                if (!k.getName().equals(row[0]) || k.isUnbound()) continue;
                if (k.getKey().getType() == InputConstants.Type.KEYBOARD && k.getKey().getValue() == (Integer) row[1]) {
                    moves.put(k, (Integer) row[2]);
                }
            }
        }
        for (var e : moves.entrySet()) {
            InputConstants.Key to = InputConstants.Type.KEYBOARD.getOrCreate(e.getValue());
            NirnCraft.LOGGER.info("Key layout v2: {} moves from {} to {}", e.getKey().getName(), e.getKey().getKey().getName(), to.getName());
            e.getKey().setKey(to);
            changed = true;
        }
        for (KeyMapping k : all) {
            if (ESSENTIAL_KEYS.contains(k.getName().toLowerCase(Locale.ROOT)) && !k.isUnbound()) {
                NirnCraft.LOGGER.info("Key layout v2: unbinding {} ({})", k.getName(), k.getKey().getName());
                k.setKey(InputConstants.UNKNOWN);
                changed = true;
            }
        }
        return changed;
    }

    /** Vanilla's own key mappings (movement, attack, inventory, chat, hotbar...). */
    private static Set<KeyMapping> vanillaKeys(Minecraft mc) {
        var o = mc.options;
        Set<KeyMapping> set = new HashSet<>(java.util.List.of(o.keyUp, o.keyLeft, o.keyDown, o.keyRight, o.keyJump, o.keyShift,
                o.keySprint, o.keyInventory, o.keySwapOffhand, o.keyDrop, o.keyUse, o.keyAttack, o.keyPickItem, o.keyChat,
                o.keyPlayerList, o.keyCommand, o.keySocialInteractions, o.keyScreenshot, o.keyTogglePerspective,
                o.keySmoothCamera, o.keyFullscreen, o.keySpectatorOutlines, o.keyAdvancements, o.keySaveHotbarActivator,
                o.keyLoadHotbarActivator));
        set.addAll(java.util.Arrays.asList(o.keyHotbarSlots));
        return set;
    }

    private static boolean sharesWithChat(KeyMapping ours, KeyMapping[] all) {
        for (KeyMapping other : all) {
            String n = other.getName();
            if ((n.equals("key.chat") || n.equals("key.command")) && !other.isUnbound() && other.getKey().equals(ours.getKey())) return true;
        }
        return false;
    }

    private static boolean isOurs(KeyMapping key) {
        return key.getName().startsWith("key.nirncraft.");
    }

    private static Set<Integer> usedKeys(KeyMapping[] all, KeyMapping self) {
        Set<Integer> used = new HashSet<>();
        for (KeyMapping k : all) {
            if (k != self && !k.isUnbound() && k.getKey().getType() == InputConstants.Type.KEYBOARD) used.add(k.getKey().getValue());
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
