package com.skycraft.client;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import com.mojang.blaze3d.platform.InputConstants;

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
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "controls"));

    // Documented defaults (keep in sync with defaultoptions/keybindings.txt). Favorites (R) is in InventoryKeys, the sail
    // trim keys (= and -) in SailingClient. Owner's layout (playtest 4): R favorites, Q journal, Z shout (no chat peek).
    public static final KeyMapping SKILLS = key("skills", InputConstants.KEY_K);
    public static final KeyMapping HUB = key("hub", InputConstants.KEY_TAB);
    public static final KeyMapping JOURNAL = key("journal", InputConstants.KEY_Q);
    public static final KeyMapping MAGIC_MENU = key("magic_menu", InputConstants.KEY_P);
    public static final KeyMapping SHOUT = key("shout", InputConstants.KEY_Z);
    public static final KeyMapping WAIT = key("wait", InputConstants.KEY_I);
    public static final KeyMapping MAP = key("map", InputConstants.KEY_M);
    public static final KeyMapping SHEATHE = key("sheathe", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping DODGE = key("dodge", InputConstants.KEY_LALT);
    public static final KeyMapping CALL_HORSE = key("call_horse", InputConstants.KEY_H);
    public static final KeyMapping INTERACT = key("interact", InputConstants.KEY_F);

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
        return new KeyMapping("key.skycraft." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYBOARD, code, CATEGORY);
    }
}
