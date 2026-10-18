package com.skycraft.inventory.client;

import com.skycraft.lore.client.BookScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;

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
        var content = book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        if (content != null && !content.title().raw().isBlank()) return Component.literal(content.title().raw());
        return Component.translatable("screen.skycraft.lore.story.untitled");
    }

    private static String authorOf(ItemStack book) {
        var content = book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        return content == null ? "" : content.author();
    }

    private static List<String> pagesOf(ItemStack book) {
        var written = book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) return written.pages().stream().map(page -> page.raw().getString()).toList();
        var writable = book.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT);
        return writable == null ? List.of() : writable.pages().stream().map(net.minecraft.server.network.Filterable::raw).toList();
    }

    @Override
    public void tick() {
        super.tick();
        if (!hadBook) {
            // the contents packet arrived after the screen was built: build it again with the book (the menu stays open)
            if (!menu.getBook().isEmpty()) Minecraft.getInstance().gui.setScreen(new SkyrimLecternScreen(menu));
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
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_T) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(menu.containerId, LecternMenu.BUTTON_TAKE_BOOK);
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_TAB) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Component hint = Component.literal("[T] ").append(Component.translatable("inventory.skycraft.machine.hint.take_book"));
        g.centeredText(font, hint, width / 2, height - 14, SkyUi.GOLD);
    }
}
