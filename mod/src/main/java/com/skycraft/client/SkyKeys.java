package com.skycraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Every Skycraft key binding. Modules read these in their own client tick handlers; each key is consumed by
 * exactly one module:
 * <ul>
 *     <li>core: HUB, SKILLS, SHEATHE, DODGE (power attacks: sprint-attack; blocking: right mouse)</li>
 *     <li>magic: MAGIC_MENU, CAST, SHOUT</li>
 *     <li>quest: JOURNAL, PARTY</li>
 *     <li>world: WAIT, MAP</li>
 *     <li>crime: (uses vanilla use key while sneaking for pickpocketing)</li>
 * </ul>
 */
public final class SkyKeys {
    public static final String CATEGORY = "key.categories.skycraft";

    // Documented defaults (keep in sync with defaultoptions/keybindings.txt). Favorites (R) is in InventoryKeys, the sail
    // trim keys (= and -) in SailingClient. Owner's layout (playtest 4): R favorites, Q journal, Z shout (no chat peek).
    public static final KeyMapping SKILLS = key("skills", GLFW.GLFW_KEY_K);
    public static final KeyMapping HUB = key("hub", GLFW.GLFW_KEY_TAB);
    public static final KeyMapping JOURNAL = key("journal", GLFW.GLFW_KEY_Q);
    public static final KeyMapping MAGIC_MENU = key("magic_menu", GLFW.GLFW_KEY_P);
    public static final KeyMapping SHOUT = key("shout", GLFW.GLFW_KEY_Z);
    public static final KeyMapping WAIT = key("wait", GLFW.GLFW_KEY_I);
    public static final KeyMapping MAP = key("map", GLFW.GLFW_KEY_M);
    public static final KeyMapping SHEATHE = key("sheathe", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping DODGE = key("dodge", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping CALL_HORSE = key("call_horse", GLFW.GLFW_KEY_H);
    public static final KeyMapping INTERACT = key("interact", GLFW.GLFW_KEY_F);

    /**
     * Not registered (casting is on the mouse buttons), so it can never be pressed. Still read by the magic module;
     * remove it together with those reads.
     */
    public static final KeyMapping CAST = key("cast", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping RACIAL_POWER = SHOUT;
    public static final KeyMapping PARTY = JOURNAL;

    public static final KeyMapping[] ALL = {SKILLS, HUB, JOURNAL, MAGIC_MENU, SHOUT, WAIT, MAP, SHEATHE, DODGE, CALL_HORSE, INTERACT};

    private SkyKeys() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.skycraft." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }
}
