package com.skycraft.inventory.client;

import com.skycraft.lore.client.BookScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's lectern with a book on it, read on the same parchment as every Skycraft book ({@link BookScreen}) instead of
 * vanilla's book art. The vanilla {@link LecternMenu} stays open (it is what keeps the lectern in use and carries the
 * "take book" button): closing the screen closes the menu, [T] takes the book (the server checks that you may build
 * here). Pages are the book's own, so a written or writable book of any mod reads the same.
 */
public class SkyrimLecternScreen extends BookScreen implements MenuAccess<LecternMenu> {
    private final LecternMenu menu;
    /** The book had arrived when this screen was built (the contents packet follows the open packet by a tick). */
    private final boolean hadBook;

    public SkyrimLecternScreen(LecternMenu menu) {
        super(titleOf(menu.getBook()), authorOf(menu.getBook()), pagesOf(menu.getBook()), false,
                Component.translatable("screen.skycraft.lore.story.lectern"));
        this.menu = menu;
        this.hadBook = !menu.getBook().isEmpty();
    }

    @Override
    public LecternMenu getMenu() {
        return menu;
    }

    private static Component titleOf(ItemStack book) {
        CompoundTag tag = book.getTag();
        if (tag != null && !tag.getString("title").isBlank()) return Component.literal(tag.getString("title"));
        return Component.translatable("screen.skycraft.lore.story.untitled");
    }

    private static String authorOf(ItemStack book) {
        CompoundTag tag = book.getTag();
        return tag == null ? "" : tag.getString("author");
    }

    private static List<String> pagesOf(ItemStack book) {
        List<String> out = new ArrayList<>();
        if (book.isEmpty()) return out;
        BookViewScreen.BookAccess access = BookViewScreen.BookAccess.fromItem(book);
        for (int i = 0; i < access.getPageCount(); i++) out.add(access.getPage(i).getString());
        return out;
    }

    @Override
    public void tick() {
        super.tick();
        if (!hadBook) {
            // the contents packet arrived after the screen was built: build it again with the book (the menu stays open)
            if (!menu.getBook().isEmpty()) Minecraft.getInstance().setScreen(new SkyrimLecternScreen(menu));
            return;
        }
        // somebody took the book (or the lectern is gone): nothing left to read
        if (menu.getBook().isEmpty()) onClose();
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.closeContainer(); // sends the close packet and closes this screen
        else super.onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_T) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(menu.containerId, LecternMenu.BUTTON_TAKE_BOOK);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_TAB) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Component hint = Component.literal("[T] ").append(Component.translatable("inventory.skycraft.machine.hint.take_book"));
        g.drawCenteredString(font, hint, width / 2, height - 14, SkyUi.GOLD);
    }
}
