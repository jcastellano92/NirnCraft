package com.nirncraft.sailing.client;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.sailing.SailingPackets;
import com.nirncraft.sailing.SailingRegistry;
import com.nirncraft.sailing.ShipType;
import com.nirncraft.sailing.Shipwright;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The shipwright's bench: pick a ship plan, see the timber, cloth, rope and iron it takes, and build it. */
public class ShipwrightScreen extends Screen {
    private static final int W = 300, H = 232;
    private final BlockPos bench;
    private ShipType selected = ShipType.ROWBOAT;
    private Button build;

    public ShipwrightScreen(BlockPos bench) {
        super(Component.translatable("sailing.nirncraft.shipwright.title"));
        this.bench = bench;
    }

    @Override
    protected void init() {
        int x = (width - W) / 2, y = (height - H) / 2;
        int by = y + 28;
        for (ShipType t : ShipType.VALUES) {
            addRenderableWidget(Button.builder(Component.translatable("item.nirncraft." + t.id), b -> {
                selected = t;
                updateBuild();
            }).bounds(x + 10, by, 96, 20).build());
            by += 24;
        }
        build = addRenderableWidget(Button.builder(Component.translatable("sailing.nirncraft.shipwright.build"), b -> {
            SkyNetwork.sendToServer(new SailingPackets.BuildShip(selected.ordinal(), bench));
            onClose();
        }).bounds(x + W - 106, y + H - 28, 96, 20).build());
        updateBuild();
    }

    private void updateBuild() {
        if (build != null && minecraft != null && minecraft.player != null) build.active = Shipwright.canBuild(minecraft.player, selected);
    }

    @Override
    public void tick() {
        updateBuild();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {

        int x = (width - W) / 2, y = (height - H) / 2;
        g.fill(x - 2, y - 2, x + W + 2, y + H + 2, 0xFF1E140A);
        g.fill(x, y, x + W, y + H, 0xF02A2016);
        g.fill(x, y + 20, x + W, y + 21, 0xFF6B5030);
        g.centeredText(font, title, x + W / 2, y + 7, 0xF0E6CC);
        int dx = x + 118, dy = y + 30;
        ItemStack ship = new ItemStack(SailingRegistry.SHIP_ITEMS.get(selected).get());
        g.item(ship, dx, dy - 4);
        g.text(font, ship.getHoverName(), dx + 20, dy, 0xF0E6CC, false);
        dy += 16;
        for (var line : font.split(Component.translatable("sailing.nirncraft.tooltip." + selected.id), W - 128)) {
            g.text(font, line, dx, dy, 0xA89C80, false);
            dy += 10;
        }
        g.text(font, Component.translatable("sailing.nirncraft.tooltip.seats", selected.seats.length, selected.horseSeats,
                selected.cargoSlots), dx, dy, 0x8A7E66, false);
        dy += 14;
        g.text(font, Component.translatable("sailing.nirncraft.shipwright.needs"), dx, dy, 0xD8C8A0, false);
        dy += 12;
        List<Shipwright.Material> plan = Shipwright.PLANS.get(selected);
        boolean creative = minecraft.player.getAbilities().instabuild;
        for (Shipwright.Material m : plan) {
            int have = Shipwright.count(minecraft.player.getInventory(), m);
            g.item(m.icon(), dx, dy - 4);
            int col = have >= m.count() || creative ? 0x9AD08A : 0xD07A6A;
            g.text(font, Component.translatable(m.key()).append(" " + Math.min(have, m.count()) + "/" + m.count()), dx + 20, dy, col, false);
            dy += 16;
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
