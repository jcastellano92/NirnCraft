package com.skycraft.inventory.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.HashMap;
import java.util.Map;

/**
 * Which Skyrim screen replaces which vanilla menu screen (applied from {@code ScreenEvent.Opening}, see
 * {@link InventoryClientEvents}). The menus and their server logic are never touched; only the screen is swapped.
 *
 * <ul>
 *   <li>{@link Kind#CONTAINER}: {@link SkyrimContainerScreen}, the list of what is inside with Take / Store: chests,
 *       trapped chests, barrels, ender chests (all {@code GENERIC_9xN}), shulker boxes, hoppers, dispensers and droppers
 *       ({@code GENERIC_3x3}); also Skycraft's corpses, ship holds, lockboxes and saddlebags, which are chest menus.</li>
 *   <li>{@link Kind#MACHINE}: {@link SkyrimMachineScreen}, the workstation menus as named rows and choices.</li>
 *   <li>{@link Kind#BOOK}: {@link SkyrimLecternScreen}, the lectern read on Skycraft's parchment.</li>
 * </ul>
 *
 * <p>Every vanilla {@link MenuType} is listed here (or deliberately not, with a reason, in docs/UI-COVERAGE.md): the
 * static check {@code tools/qa/menu_coverage.py} reads the {@code skin(MenuType.X, Kind.Y)} lines below and fails when a
 * menu type has neither a skin nor an entry in the document. Skycraft's own menu types ({@code smithing_station},
 * {@code homestead_table}, {@code cooking_pot}) register their own Skyrim screens with {@code MenuScreens.register}.</p>
 */
public final class MenuSkins {
    public enum Kind { CONTAINER, MACHINE, BOOK }

    private static final Map<MenuType<?>, Kind> KINDS = new HashMap<>();

    private static void skin(MenuType<?> type, Kind kind) {
        KINDS.put(type, kind);
    }

    static {
        skin(MenuType.GENERIC_9x1, Kind.CONTAINER);
        skin(MenuType.GENERIC_9x2, Kind.CONTAINER);
        skin(MenuType.GENERIC_9x3, Kind.CONTAINER);
        skin(MenuType.GENERIC_9x4, Kind.CONTAINER);
        skin(MenuType.GENERIC_9x5, Kind.CONTAINER);
        skin(MenuType.GENERIC_9x6, Kind.CONTAINER);
        skin(MenuType.GENERIC_3x3, Kind.CONTAINER);
        skin(MenuType.HOPPER, Kind.CONTAINER);
        skin(MenuType.SHULKER_BOX, Kind.CONTAINER);
        skin(MenuType.FURNACE, Kind.MACHINE);
        skin(MenuType.BLAST_FURNACE, Kind.MACHINE);
        skin(MenuType.SMOKER, Kind.MACHINE);
        skin(MenuType.BREWING_STAND, Kind.MACHINE);
        skin(MenuType.ANVIL, Kind.MACHINE);
        skin(MenuType.GRINDSTONE, Kind.MACHINE);
        skin(MenuType.SMITHING, Kind.MACHINE);
        skin(MenuType.CARTOGRAPHY_TABLE, Kind.MACHINE);
        skin(MenuType.STONECUTTER, Kind.MACHINE);
        skin(MenuType.LOOM, Kind.MACHINE);
        skin(MenuType.ENCHANTMENT, Kind.MACHINE);
        skin(MenuType.BEACON, Kind.MACHINE);
        skin(MenuType.MERCHANT, Kind.MACHINE);
        skin(MenuType.CRAFTING, Kind.MACHINE);
        skin(MenuType.LECTERN, Kind.BOOK);
    }

    private MenuSkins() {}

    /** The kind of skin for a menu, or null (vanilla look). Menus of modded types that are chest menus count as containers. */
    public static Kind kindOf(AbstractContainerMenu menu) {
        Kind k = null;
        try {
            k = KINDS.get(menu.getType());
        } catch (UnsupportedOperationException e) {
            // a menu without a type (the player's own): never skinned here
        }
        if (k == null && menu instanceof ChestMenu) k = Kind.CONTAINER;
        return k;
    }

    /**
     * The Skyrim screen for {@code next} (a vanilla menu screen), or {@code next} itself when there is none or the
     * player is in creative / spectator mode (they keep the vanilla screens).
     */
    public static Screen replace(Screen next, LocalPlayer player) {
        if (player == null || player.isCreative() || player.isSpectator()) return next;
        if (!(next instanceof AbstractContainerScreen<?> acs)) return next;
        AbstractContainerMenu menu = acs.getMenu();
        Kind kind = kindOf(menu);
        if (kind == null) return next;
        switch (kind) {
            case CONTAINER -> {
                return new SkyrimContainerScreen(menu, player.getInventory(), acs.getTitle());
            }
            case MACHINE -> {
                if (SkyrimMachineScreen.supports(menu)) return new SkyrimMachineScreen(menu, player.getInventory(), acs.getTitle());
            }
            case BOOK -> {
                if (menu instanceof LecternMenu lectern) return new SkyrimLecternScreen(lectern);
            }
        }
        return next;
    }
}
