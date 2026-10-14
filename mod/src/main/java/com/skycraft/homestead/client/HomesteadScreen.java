package com.skycraft.homestead.client;

import com.skycraft.crafting.recipe.Cost;
import com.skycraft.homestead.Hearthfire;
import com.skycraft.homestead.HomesteadClaim;
import com.skycraft.homestead.HomesteadMenu;
import com.skycraft.homestead.HomesteadPackets;
import com.skycraft.homestead.HomesteadPlans;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The drafting table, Skyrim style: tabs on the left (Build, Materials, Land &amp; Steward, Permissions), a list in the
 * middle and the selected entry's details with have/need counts on the right.
 */
public class HomesteadScreen extends AbstractContainerScreen<HomesteadMenu> {
    private static final int TEXT = 0xFFF5EBC8;
    private static final int DIM = 0xFFBDB59E;
    private static final int GREY = 0xFF7E786C;
    private static final int GOOD = 0xFF90C090;
    private static final int BAD = 0xFFD07070;
    private static final int GOLD = 0xFFE0B040;
    private static final int BORDER = 0xFF8A7F66;
    private static final int PANEL = 0xE8100E0B;
    private static final int INSET = 0x60000000;
    private static final int ROW = 18;
    private static final int TAB_ROW = 14;
    private static final String[] TABS = {"build", "materials", "land", "permissions", "home"};
    private static int lastTab;

    private record Hot(int x, int y, int w, int h, Runnable action) {}

    private final List<Hot> hots = new ArrayList<>();
    private final List<HomesteadPlans> plans = HomesteadPlans.all();
    private int tab;
    private int selected;
    private int scroll;
    private ItemStack hoveredStack = ItemStack.EMPTY;
    private int tabX, tabW, listX, listY, listW, listH, detX, detW;
    private EditBox nameBox;

    public HomesteadScreen(HomesteadMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        tab = lastTab;
    }

    /** A state update from the server for the open table. */
    public static void receive(CompoundTag state) {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof HomesteadMenu menu) {
            menu.state = state;
        }
    }

    @Override
    protected void init() {
        imageWidth = Math.min(width - 16, 440);
        imageHeight = Math.min(height - 16, 250);
        super.init();
        tabX = leftPos + 6;
        tabW = 92;
        listX = tabX + tabW + 4;
        listY = topPos + 34;
        listH = imageHeight - 40;
        listW = Math.min(150, (imageWidth - tabW) / 2 - 6);
        detX = listX + listW + 6;
        detW = leftPos + imageWidth - 6 - detX;
        // the homestead's name (the Homestead tab)
        nameBox = new EditBox(font, listX + 4, listY + 18, Math.min(260, listW + detW - 8), 16, Component.translatable("homestead.skycraft.home.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(state().getString("home_name"));
        nameBox.setHint(Component.translatable("homestead.skycraft.home.name_hint"));
        nameBox.visible = false;
        addRenderableWidget(nameBox);
    }

    private CompoundTag state() {
        return menu.state;
    }

    private Set<String> built() {
        Set<String> out = new HashSet<>();
        ListTag list = state().getList("built", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    private int rows() {
        return switch (TABS[tab]) {
            case "build" -> plans.size();
            case "materials" -> Hearthfire.MATERIALS.size();
            case "land" -> state().getString("steward").isEmpty() ? 0 : Hearthfire.OFFERS.size();
            default -> 0;
        };
    }

    private int visibleRows() {
        return Math.max(1, listH / ROW);
    }

    private void send(String action, String arg) {
        SkyNetwork.sendToServer(new HomesteadPackets.Action(action, arg));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Hot h : List.copyOf(hots)) {
                if (SkyUi.inside(mx, my, h.x, h.y, h.x + h.w, h.y + h.h)) {
                    h.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (SkyUi.inside(mx, my, listX, listY, listX + listW, listY + listH)) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, rows() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // typing a name: the inventory key and the arrows belong to the text box
        if (nameBox != null && nameBox.visible && nameBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                onClose();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                send("rename", nameBox.getValue());
                nameBox.setFocused(false);
                return true;
            }
            return nameBox.keyPressed(key, scan, mods) || nameBox.canConsumeInput();
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            int n = rows();
            if (n > 0) {
                selected = Mth.clamp(selected + (key == GLFW.GLFW_KEY_UP ? -1 : 1), 0, n - 1);
                if (selected < scroll) scroll = selected;
                if (selected >= scroll + visibleRows()) scroll = selected - visibleRows() + 1;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            selectTab(Math.floorMod(tab + (key == GLFW.GLFW_KEY_LEFT ? -1 : 1), TABS.length));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void selectTab(int t) {
        if (t == tab) return;
        tab = t;
        lastTab = t;
        selected = 0;
        scroll = 0;
        if (nameBox != null && "home".equals(TABS[t])) nameBox.setValue(state().getString("home_name"));
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        hoveredStack = ItemStack.EMPTY;
        super.render(g, mouseX, mouseY, partialTick);
        if (!hoveredStack.isEmpty()) g.renderTooltip(font, hoveredStack, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // everything is drawn in renderBg
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        hots.clear();
        int x0 = leftPos, y0 = topPos, x1 = leftPos + imageWidth, y1 = topPos + imageHeight;
        g.fill(x0, y0, x1, y1, PANEL);
        frame(g, x0, y0, x1, y1, BORDER);
        frame(g, x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0x408A7F66);
        Component name = Component.Serializer.fromJson(state().getString("name"));
        SkyUi.scaledCentered(g, font, SkyUi.caps(name == null ? title : name), (x0 + x1) / 2f, y0 + 7, 1f, TEXT, false);
        String sub = Component.translatable(state().contains("plot")
                ? state().getBoolean("purchased") ? "homestead.skycraft.screen.plot_owned" : "homestead.skycraft.screen.plot_for_sale"
                : "homestead.skycraft.screen.claim", state().getString("owner"), state().getInt("radius")).getString();
        g.drawCenteredString(font, sub, (x0 + x1) / 2, y0 + 19, DIM);
        SkyUi.carryLine(g, font, (x0 + x1) / 2 + font.width(SkyUi.caps(name == null ? title : name)) / 2 + 10, y0 + 7, x1 - 10);
        g.fill(x0 + 6, y0 + 29, x1 - 6, y0 + 30, 0x808A7F66);
        renderTabs(g, mx, my);
        boolean home = "home".equals(TABS[tab]);
        nameBox.visible = home;
        nameBox.active = home;
        g.fill(listX, listY, home ? detX + detW : listX + listW, home ? listY + listH : listY + listH, INSET);
        switch (TABS[tab]) {
            case "build" -> renderBuild(g, mx, my);
            case "materials" -> renderMaterials(g, mx, my);
            case "land" -> renderLand(g, mx, my);
            case "home" -> renderHome(g, mx, my);
            default -> renderPermissions(g, mx, my);
        }
    }

    private void renderTabs(GuiGraphics g, int mx, int my) {
        for (int i = 0; i < TABS.length; i++) {
            int y = listY + i * TAB_ROW;
            boolean sel = i == tab;
            boolean hover = SkyUi.inside(mx, my, tabX, y, tabX + tabW, y + TAB_ROW - 1);
            if (sel) g.fill(tabX, y, tabX + tabW, y + TAB_ROW - 1, 0x50C8A050);
            else if (hover) g.fill(tabX, y, tabX + tabW, y + TAB_ROW - 1, 0x28F0E6C8);
            if (sel) g.fill(tabX, y, tabX + 2, y + TAB_ROW - 1, GOLD);
            g.drawString(font, fit(Component.translatable("homestead.skycraft.tab." + TABS[i]).getString(), tabW - 8), tabX + 5, y + 3,
                    sel || hover ? TEXT : DIM, false);
            final int t = i;
            hots.add(new Hot(tabX, y, tabW, TAB_ROW - 1, () -> selectTab(t)));
        }
        String gold = Component.translatable("homestead.skycraft.screen.gold", state().getLong("gold")).getString();
        g.drawString(font, gold, tabX + 4, topPos + imageHeight - 14, GOLD, false);
    }

    /** A list row; returns true if it is the selected one. */
    private boolean row(GuiGraphics g, int mx, int my, int i, ItemStack icon, String text, int color) {
        int y = listY + (i - scroll) * ROW;
        if (y + ROW > listY + listH + 1 || i < scroll) return false;
        boolean hover = SkyUi.inside(mx, my, listX, y, listX + listW, y + ROW);
        if (i == selected) g.fill(listX, y, listX + listW, y + ROW, 0x50C8A050);
        else if (hover) g.fill(listX, y, listX + listW, y + ROW, 0x28F0E6C8);
        int tx = listX + 4;
        if (!icon.isEmpty()) {
            g.renderItem(icon, listX + 2, y + 1);
            if (hover && mx < listX + 19) hoveredStack = icon;
            tx = listX + 22;
        }
        g.drawString(font, fit(text, listX + listW - tx - 4), tx, y + 5, color, false);
        hots.add(new Hot(listX, y, listW, ROW, () -> selected = i));
        return i == selected;
    }

    private void renderBuild(GuiGraphics g, int mx, int my) {
        Set<String> built = built();
        for (int i = scroll; i < plans.size(); i++) {
            HomesteadPlans p = plans.get(i);
            String blocker = p.blocker(built);
            int color = built.contains(p.id()) ? GOLD : blocker == null ? TEXT : GREY;
            String prefix = built.contains(p.id()) ? "✓ " : "";
            row(g, mx, my, i, ItemStack.EMPTY, prefix + Component.translatable("homestead.skycraft.plan." + p.id()).getString(), color);
        }
        scrollbar(g, plans.size());
        if (selected < 0 || selected >= plans.size()) return;
        HomesteadPlans p = plans.get(selected);
        int y = listY + 2;
        g.drawString(font, Component.translatable("homestead.skycraft.plan." + p.id()), detX + 2, y, TEXT, false);
        y += 11;
        g.drawString(font, Component.translatable("homestead.skycraft.slot." + p.slot()), detX + 2, y, DIM, false);
        y += 12;
        for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.plan." + p.id() + ".desc"), detW - 4)) {
            g.drawString(font, line, detX + 2, y, GREY, false);
            y += 10;
        }
        y += 4;
        String blocker = p.blocker(built);
        boolean carpenter = state().getBoolean("carpenter");
        Inventory inv = minecraft.player.getInventory();
        boolean haveAll = true;
        if (!"built".equals(blocker)) {
            g.drawString(font, Component.translatable("homestead.skycraft.screen.materials"), detX + 2, y, GOLD, false);
            if (carpenter) g.drawString(font, Component.translatable("homestead.skycraft.screen.carpenter"), detX + 70, y, GOOD, false);
            y += 11;
            for (Cost cost : p.costs()) {
                ItemStack icon = cost.display().get();
                int need = HomesteadPlans.needed(cost, carpenter);
                int have = cost.countIn(inv, -1);
                if (have < need) haveAll = false;
                if (y + 16 > topPos + imageHeight - 30) continue;
                g.renderItem(icon, detX + 2, y);
                if (SkyUi.inside(mx, my, detX + 2, y, detX + 18, y + 16)) hoveredStack = icon;
                String count = have + "/" + need;
                int cw = font.width(count);
                g.drawString(font, fit(icon.getHoverName().getString(), detW - cw - 30), detX + 22, y + 4, DIM, false);
                g.drawString(font, count, detX + detW - 4 - cw, y + 4, have >= need ? GOOD : BAD, false);
                y += 17;
            }
        }
        int by = topPos + imageHeight - 26;
        if (blocker != null) {
            String why = blocker.contains(":")
                    ? Component.translatable("homestead.skycraft.screen.blocked." + blocker.split(":")[0],
                    Component.translatable("homestead.skycraft.plan." + blocker.split(":")[1])).getString()
                    : Component.translatable("homestead.skycraft.screen.blocked." + blocker).getString();
            g.drawCenteredString(font, fit(why, detW), detX + detW / 2, by + 5, "built".equals(blocker) ? GOLD : BAD);
        } else if (!state().getBoolean("purchased")) {
            g.drawCenteredString(font, Component.translatable("homestead.skycraft.plot.buy_first"), detX + detW / 2, by + 5, BAD);
        } else {
            boolean ok = haveAll || minecraft.player.isCreative();
            button(g, mx, my, Component.translatable("homestead.skycraft.screen.build"), detX + (detW - 100) / 2, by, 100, 18, ok,
                    () -> send("build", p.id()));
        }
    }

    private void renderMaterials(GuiGraphics g, int mx, int my) {
        Inventory inv = minecraft.player.getInventory();
        List<Hearthfire.Material> mats = Hearthfire.MATERIALS;
        for (int i = scroll; i < mats.size(); i++) {
            Hearthfire.Material m = mats.get(i);
            ItemStack out = m.result().get();
            boolean can = m.cost().countIn(inv, -1) >= m.cost().count();
            row(g, mx, my, i, out, out.getHoverName().getString() + (out.getCount() > 1 ? " x" + out.getCount() : ""), can ? TEXT : GREY);
        }
        if (selected < 0 || selected >= mats.size()) return;
        Hearthfire.Material m = mats.get(selected);
        ItemStack out = m.result().get();
        int y = listY + 2;
        g.drawString(font, out.getHoverName(), detX + 2, y, TEXT, false);
        y += 12;
        for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.material." + m.id() + ".desc"), detW - 4)) {
            g.drawString(font, line, detX + 2, y, GREY, false);
            y += 10;
        }
        y += 4;
        g.drawString(font, Component.translatable("homestead.skycraft.screen.materials"), detX + 2, y, GOLD, false);
        y += 11;
        ItemStack icon = m.cost().display().get();
        int have = m.cost().countIn(inv, -1);
        g.renderItem(icon, detX + 2, y);
        if (SkyUi.inside(mx, my, detX + 2, y, detX + 18, y + 16)) hoveredStack = icon;
        String count = have + "/" + m.cost().count();
        int cw = font.width(count);
        g.drawString(font, fit(icon.getHoverName().getString(), detW - cw - 30), detX + 22, y + 4, DIM, false);
        g.drawString(font, count, detX + detW - 4 - cw, y + 4, have >= m.cost().count() ? GOOD : BAD, false);
        int by = topPos + imageHeight - 26;
        boolean ok = have >= m.cost().count();
        int index = selected;
        button(g, mx, my, Component.translatable("homestead.skycraft.screen.craft"), detX + detW / 2 - 92, by, 88, 18, ok,
                () -> SkyNetwork.sendToServer(new HomesteadPackets.CraftBlueprint(index, 1)));
        button(g, mx, my, Component.translatable("homestead.skycraft.screen.craft_many"), detX + detW / 2 + 4, by, 88, 18, ok,
                () -> SkyNetwork.sendToServer(new HomesteadPackets.CraftBlueprint(index, Screen.hasShiftDown() ? 16 : 5)));
    }

    private void renderLand(GuiGraphics g, int mx, int my) {
        // the list: the steward's goods
        String steward = state().getString("steward");
        if (steward.isEmpty()) {
            int y = listY + 6;
            for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.screen.no_steward"), listW - 8)) {
                g.drawString(font, line, listX + 4, y, GREY, false);
                y += 10;
            }
        } else {
            for (int i = scroll; i < Hearthfire.OFFERS.size(); i++) {
                Hearthfire.Offer o = Hearthfire.OFFERS.get(i);
                row(g, mx, my, i, o.icon().get(), Component.translatable("homestead.skycraft.offer." + o.id()).getString(), TEXT);
            }
        }
        int y = listY + 2;
        // the plot
        g.drawString(font, Component.translatable("homestead.skycraft.screen.land"), detX + 2, y, GOLD, false);
        y += 12;
        if (state().contains("plot")) {
            boolean bought = state().getBoolean("purchased");
            g.drawString(font, Component.translatable(bought ? "homestead.skycraft.screen.deed_owned" : "homestead.skycraft.screen.deed_price",
                    state().getInt("price")), detX + 2, y, bought ? GOOD : DIM, false);
            y += 12;
            if (!bought) {
                button(g, mx, my, Component.translatable("homestead.skycraft.screen.buy_plot"), detX + 2, y, 110, 18,
                        state().getLong("gold") >= state().getInt("price"), () -> send("buy_plot", ""));
                y += 22;
            }
        } else {
            for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.screen.free_claim"), detW - 4)) {
                g.drawString(font, line, detX + 2, y, GREY, false);
                y += 10;
            }
        }
        y += 6;
        g.drawString(font, Component.translatable("homestead.skycraft.screen.steward"), detX + 2, y, GOLD, false);
        y += 12;
        if (steward.isEmpty()) {
            g.drawString(font, Component.translatable("homestead.skycraft.screen.steward_price", state().getInt("stewardPrice")), detX + 2, y, DIM, false);
            y += 12;
            button(g, mx, my, Component.translatable("homestead.skycraft.screen.hire"), detX + 2, y, 110, 18,
                    state().getLong("gold") >= state().getInt("stewardPrice"), () -> send("hire_steward", ""));
        } else {
            g.drawString(font, Component.translatable("homestead.skycraft.screen.steward_name", steward), detX + 2, y, TEXT, false);
            y += 14;
            if (selected >= 0 && selected < Hearthfire.OFFERS.size()) {
                Hearthfire.Offer o = Hearthfire.OFFERS.get(selected);
                g.drawString(font, Component.translatable("homestead.skycraft.offer." + o.id()), detX + 2, y, TEXT, false);
                g.drawString(font, Component.translatable("homestead.skycraft.screen.price", o.price()), detX + detW - 60, y, GOLD, false);
                y += 14;
                int index = selected;
                button(g, mx, my, Component.translatable("homestead.skycraft.screen.buy"), detX + 2, y, 90, 18,
                        state().getLong("gold") >= o.price(), () -> send("steward_buy", Integer.toString(index)));
            }
        }
    }

    private void renderPermissions(GuiGraphics g, int mx, int my) {
        int y = listY + 6;
        CompoundTag s = state();
        y = permRow(g, mx, my, y, "doors", s.getInt("door"), 3, v -> perms("door", v));
        y = permRow(g, mx, my, y, "containers", s.getInt("container"), 3, v -> perms("container", v));
        y = permRow(g, mx, my, y, "stations", s.getInt("station"), 3, v -> perms("station", v));
        y = permRow(g, mx, my, y, "beds", s.getInt("bed"), 3, v -> perms("bed", v));
        y = permRow(g, mx, my, y, "building", s.getInt("build"), 2, v -> perms("build", v));
        y += 4;
        for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.perm.hint"), listW + detW - 8)) {
            g.drawString(font, line, listX + 4, y, GREY, false);
            y += 10;
        }
        y += 4;
        String info = Component.translatable("homestead.skycraft.screen.bounds", s.getInt("radius"), s.getInt("depth"), s.getInt("height")).getString();
        for (FormattedCharSequence line : font.split(Component.literal(info), listW + detW - 8)) {
            g.drawString(font, line, listX + 4, y, GREY, false);
            y += 10;
        }
    }

    private int permRow(GuiGraphics g, int mx, int my, int y, String what, int value, int options, java.util.function.IntConsumer set) {
        g.drawString(font, Component.translatable("homestead.skycraft.perm." + what), listX + 4, y + 5, DIM, false);
        String[] names = {"owner", "party", "public"};
        int x = listX + 90;
        for (int i = 0; i < options; i++) {
            final int v = i;
            boolean sel = value == i;
            Component label = Component.translatable("homestead.skycraft.perm." + names[i]);
            int w = font.width(label) + 14;
            button(g, mx, my, label, x, y, w, 18, true, () -> set.accept(v));
            if (sel) g.fill(x + 3, y + 15, x + w - 3, y + 16, GOLD);
            x += w + 4;
        }
        return y + 24;
    }

    private void perms(String what, int value) {
        CompoundTag s = state();
        s.putInt(what, value);
        SkyNetwork.sendToServer(new HomesteadPackets.UpdatePerms(menu.pos, s.getInt("door"), s.getInt("container"),
                s.getInt("station"), s.getInt("bed"), s.getInt("build")));
    }

    /** The Homestead tab: name, build mode, the grounds' expansion and how the homestead shows on other people's maps. */
    private void renderHome(GuiGraphics g, int mx, int my) {
        CompoundTag s = state();
        int x = listX + 4;
        int w = listW + detW - 8;
        int y = listY + 4;
        g.drawString(font, Component.translatable("homestead.skycraft.home.name"), x, y, GOLD, false);
        y = listY + 38;
        button(g, mx, my, Component.translatable("homestead.skycraft.home.rename"), x, y, 110, 18, true, () -> send("rename", nameBox.getValue()));
        for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.home.name_info"), w - 120)) {
            g.drawString(font, line, x + 118, y + 1, GREY, false);
            y += 9;
        }
        y = Math.max(y, listY + 38 + 18) + 10;

        // build mode
        boolean on = s.getBoolean("build_mode");
        g.drawString(font, Component.translatable("homestead.skycraft.home.build_mode"), x, y, GOLD, false);
        y += 11;
        button(g, mx, my, Component.translatable(on ? "homestead.skycraft.home.build_mode.turn_off" : "homestead.skycraft.home.build_mode.turn_on"),
                x, y, 130, 18, true, () -> send("build_mode", ""));
        for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.home.build_mode.info"), w - 140)) {
            g.drawString(font, line, x + 138, y + 1, on ? GOOD : GREY, false);
            y += 9;
        }
        y = Math.max(y, y) + 14;

        // the grounds
        g.drawString(font, Component.translatable("homestead.skycraft.home.grounds", s.getInt("radius")), x, y, GOLD, false);
        y += 11;
        int next = s.getInt("next_radius");
        if (next <= 0) {
            g.drawString(font, Component.translatable("homestead.skycraft.expand.max"), x, y, GREY, false);
            y += 12;
        } else {
            long gold = s.getLong("expand_gold");
            ListTag mats = s.getList("expand_mats", Tag.TAG_STRING);
            boolean ok = s.getLong("gold") >= gold;
            Inventory inv = minecraft.player.getInventory();
            StringBuilder need = new StringBuilder();
            for (int i = 0; i < mats.size(); i++) {
                String[] parts = mats.getString(i).split(":");
                int count = Integer.parseInt(parts[1]);
                ItemStack icon = switch (parts[0]) {
                    case "sawn_log" -> new ItemStack(com.skycraft.homestead.HomesteadRegistry.SAWN_LOG.get());
                    case "quarried_stone" -> new ItemStack(com.skycraft.homestead.HomesteadRegistry.QUARRIED_STONE.get());
                    default -> new ItemStack(com.skycraft.homestead.HomesteadRegistry.NAILS.get());
                };
                int have = com.skycraft.inventory.Inv.count(minecraft.player, icon.getItem());
                if (have < count) ok = false;
                if (need.length() > 0) need.append(", ");
                need.append(count).append(' ').append(icon.getHoverName().getString());
            }
            g.drawString(font, fit(Component.translatable("homestead.skycraft.home.expand_cost", next, gold).getString(), w), x, y, ok ? TEXT : BAD, false);
            y += 11;
            g.drawString(font, fit(need.toString(), w), x, y, ok ? DIM : BAD, false);
            y += 13;
            final boolean enabled = ok || minecraft.player.isCreative();
            button(g, mx, my, Component.translatable("homestead.skycraft.home.expand"), x, y, 130, 18, enabled, () -> send("expand", ""));
            y += 22;
        }
        y += 4;
        for (FormattedCharSequence line : font.split(Component.translatable("homestead.skycraft.home.map_info"), w)) {
            if (y + 9 > topPos + imageHeight - 6) break;
            g.drawString(font, line, x, y, GREY, false);
            y += 9;
        }
    }

    private void button(GuiGraphics g, int mx, int my, Component label, int x, int y, int w, int h, boolean active, Runnable action) {
        boolean hover = SkyUi.inside(mx, my, x, y, x + w, y + h);
        g.fill(x, y, x + w, y + h, active ? (hover ? 0x60C8A050 : 0x40000000) : 0x30000000);
        frame(g, x, y, x + w, y + h, active ? BORDER : 0x608A7F66);
        g.drawCenteredString(font, SkyUi.caps(label), x + w / 2, y + (h - 8) / 2, active ? (hover ? 0xFFFFFFFF : TEXT) : GREY);
        if (active) hots.add(new Hot(x, y, w, h, action));
    }

    private void scrollbar(GuiGraphics g, int total) {
        int visible = visibleRows();
        if (total <= visible) return;
        int x = listX + listW - 3;
        g.fill(x, listY, x + 2, listY + listH, 0x40FFFFFF);
        int barH = Math.max(10, listH * visible / total);
        int barY = listY + (listH - barH) * scroll / Math.max(1, total - visible);
        g.fill(x, barY, x + 2, barY + barH, BORDER);
    }

    private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    private String fit(String text, int width) {
        if (width <= 0) return "";
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    /** Owner/party/public labels of a permission value (kept for other screens). */
    public static int clampPerm(int v) {
        return Mth.clamp(v, HomesteadClaim.PERM_OWNER, HomesteadClaim.PERM_PUBLIC);
    }
}
