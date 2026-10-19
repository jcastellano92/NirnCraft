package com.nirncraft.crafting.arcane.client;

import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.crafting.arcane.ArcanePackets;
import com.nirncraft.crafting.arcane.enchant.Enchanting;
import com.nirncraft.crafting.arcane.item.SoulGemItem;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.perk.Perks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Arcane Enchanter: a Disenchant tab (destroy an enchanted item to learn its enchantments) and an Enchant tab
 * (item + known enchantment + filled soul gem). The screen only previews; the server validates and performs actions.
 */
public class ArcaneEnchanterScreen extends Screen {
    private static int tab = 0;

    private final BlockPos pos;
    private int left, top, pw, ph;
    private Button disTab, encTab, action;
    private int cooldown;

    private final ArcaneList<Integer> disList = new ArcaneList<>(Component.translatable("screen.nirncraft.enchanter.disenchant_items"));
    private final ArcaneList<Integer> itemList = new ArcaneList<>(Component.translatable("screen.nirncraft.enchanter.item"));
    private final ArcaneList<Enchantment> enchList = new ArcaneList<>(Component.translatable("screen.nirncraft.enchanter.enchantment"));
    private final ArcaneList<Integer> gemList = new ArcaneList<>(Component.translatable("screen.nirncraft.enchanter.soul_gem"));

    public ArcaneEnchanterScreen(BlockPos pos) {
        super(Component.translatable("block.nirncraft.arcane_enchanter"));
        this.pos = pos;
    }

    private Player player() {
        return minecraft.player;
    }

    @Override
    protected void init() {
        pw = Math.min(400, width - 16);
        ph = Math.min(240, height - 16);
        left = (width - pw) / 2;
        top = (height - ph) / 2;
        disTab = addRenderableWidget(Button.builder(Component.translatable("screen.nirncraft.enchanter.tab_disenchant"), b -> setTab(0))
                .bounds(left + pw / 2 - 102, top + 20, 100, 16).build());
        encTab = addRenderableWidget(Button.builder(Component.translatable("screen.nirncraft.enchanter.tab_enchant"), b -> setTab(1))
                .bounds(left + pw / 2 + 2, top + 20, 100, 16).build());
        action = addRenderableWidget(Button.builder(Component.empty(), b -> doAction())
                .bounds(left + pw - 112, top + ph - 28, 100, 20).build());

        int listTop = top + 44;
        disList.setBounds(left + 10, listTop, Math.min(190, pw / 2), ph - 80);
        int inner = pw - 20 - 12;
        int wItem = (int) (inner * 0.38);
        int wEnch = (int) (inner * 0.34);
        int wGem = inner - wItem - wEnch;
        int listH = ph - 100;
        itemList.setBounds(left + 10, listTop, wItem, listH);
        enchList.setBounds(left + 16 + wItem, listTop, wEnch, listH);
        gemList.setBounds(left + 22 + wItem + wEnch, listTop, wGem, listH);
        refresh();
    }

    private void setTab(int t) {
        tab = t;
        refresh();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        Player p = player();
        if (p == null || !p.isAlive() || p.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0) {
            onClose();
            return;
        }
        if (cooldown > 0) cooldown--;
        refresh();
    }

    // ------------------------------------------------------------------ model

    private void refresh() {
        Player p = player();
        if (p == null) return;
        Inventory inv = p.getInventory();
        int packSize = com.nirncraft.inventory.Pack.size(p); // overflow-pack entries follow the slots as virtual slot ids
        if (tab == 0) {
            List<ArcaneList.Entry<Integer>> list = new ArrayList<>();
            for (int n = 0; n < inv.getContainerSize() + packSize; n++) {
                int i = n < inv.getContainerSize() ? n : com.nirncraft.inventory.Pack.VIRTUAL_BASE + (n - inv.getContainerSize());
                ItemStack s = com.nirncraft.inventory.Pack.stackAt(p, i);
                List<Enchantment> unknown = Enchanting.unknownEnchantments(p, s);
                if (unknown.isEmpty()) continue;
                Component sub = Component.translatable("screen.nirncraft.enchanter.unknown_count", unknown.size());
                list.add(new ArcaneList.Entry<>(String.valueOf(i), s, s.getHoverName(), sub, i));
            }
            disList.setEntries(list);
        } else {
            List<ArcaneList.Entry<Integer>> items = new ArrayList<>();
            for (int n = 0; n < inv.getContainerSize() + packSize; n++) {
                int i = n < inv.getContainerSize() ? n : com.nirncraft.inventory.Pack.VIRTUAL_BASE + (n - inv.getContainerSize());
                ItemStack s = com.nirncraft.inventory.Pack.stackAt(p, i);
                if (s.getItem() instanceof SoulGemItem || Enchanting.applicable(p, s).isEmpty()) continue;
                Component sub = s.isEnchanted() ? Component.translatable("screen.nirncraft.enchanter.extra_effect") : Component.empty();
                items.add(new ArcaneList.Entry<>(String.valueOf(i), s, s.getHoverName(), sub, i));
            }
            itemList.setEntries(items);

            List<ArcaneList.Entry<Integer>> gems = new ArrayList<>();
            for (int n = 0; n < inv.getContainerSize() + packSize; n++) {
                int i = n < inv.getContainerSize() ? n : com.nirncraft.inventory.Pack.VIRTUAL_BASE + (n - inv.getContainerSize());
                ItemStack s = com.nirncraft.inventory.Pack.stackAt(p, i);
                if (!(s.getItem() instanceof SoulGemItem) || SoulGemItem.getSoul(s) <= 0) continue;
                gems.add(new ArcaneList.Entry<>(String.valueOf(i), s, s.getItem().getName(s), SoulGemItem.soulName(SoulGemItem.getSoul(s)), i));
            }
            gems.sort(Comparator.comparingInt(e -> SoulGemItem.getSoul(e.icon())));
            gemList.setEntries(gems);

            Integer itemSlot = itemList.selectedValue();
            List<ArcaneList.Entry<Enchantment>> enchs = new ArrayList<>();
            if (itemSlot != null) {
                ItemStack target = inv.getItem(itemSlot);
                int soul = selectedSoul();
                for (Enchantment e : Enchanting.applicable(p, target)) {
                    String id = Enchanting.id(e);
                    if (id == null) continue;
                    Component label = soul > 0 ? Enchantment.getFullname(com.nirncraft.platform.EnchantmentsAccess.holder(e),Enchanting.resultLevel(p,e,soul)).copy().withStyle(ChatFormatting.WHITE)
                            : e.description();
                    Component sub = Component.translatable("screen.nirncraft.enchanter.max_level", e.getMaxLevel());
                    if (Enchanting.perkBonus(p, e) > 1.0) sub = Component.translatable("screen.nirncraft.enchanter.perk_bonus", e.getMaxLevel());
                    enchs.add(new ArcaneList.Entry<>(id, ItemStack.EMPTY, label, sub, e));
                }
            }
            enchList.setEntries(enchs);
        }
        updateAction();
    }

    private int selectedSoul() {
        Integer gemSlot = gemList.selectedValue();
        if (gemSlot == null) return 0;
        return SoulGemItem.getSoul(com.nirncraft.inventory.Pack.stackAt(player(), gemSlot));
    }

    private void updateAction() {
        if (action == null) return;
        if (tab == 0) {
            action.setMessage(Component.translatable("screen.nirncraft.enchanter.disenchant"));
            action.active = cooldown == 0 && disList.selected() != null;
        } else {
            action.setMessage(Component.translatable("screen.nirncraft.enchanter.enchant"));
            action.active = cooldown == 0 && itemList.selected() != null && enchList.selected() != null && gemList.selected() != null;
        }
        disTab.active = tab != 0;
        encTab.active = tab != 1;
    }

    private void doAction() {
        if (cooldown > 0) return;
        if (tab == 0) {
            Integer slot = disList.selectedValue();
            if (slot == null) return;
            SkyNetwork.sendToServer(new ArcanePackets.Disenchant(pos, slot));
            disList.clearSelection();
        } else {
            Integer item = itemList.selectedValue();
            Enchantment ench = enchList.selectedValue();
            Integer gem = gemList.selectedValue();
            if (item == null || ench == null || gem == null) return;
            String id = Enchanting.id(ench);
            if (id == null) return;
            SkyNetwork.sendToServer(new ArcanePackets.Enchant(pos, item, id, gem));
            itemList.clearSelection();
            enchList.clearSelection();
            gemList.clearSelection();
        }
        cooldown = 6;
        updateAction();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        if (button != 0) return false;
        boolean hit;
        if (tab == 0) {
            hit = disList.click(mx, my) != null;
        } else {
            hit = itemList.click(mx, my) != null || enchList.click(mx, my) != null || gemList.click(mx, my) != null;
        }
        if (hit) refresh();
        return hit;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (tab == 0) return disList.scroll(mx, my, delta);
        return itemList.scroll(mx, my, delta) || enchList.scroll(mx, my, delta) || gemList.scroll(mx, my, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        ArcaneUi.panel(g, left, top, pw, ph, ArcaneUi.MAGIC);
        g.centeredText(font, title, left + pw / 2, top + 7, ArcaneUi.GOLD);
        com.nirncraft.inventory.client.SkyUi.carryLine(g, font, left + pw / 2 + font.width(title) / 2 + 10, top + 7, left + pw - 12);
        ArcaneUi.divider(g, left + 12, top + 40, pw - 24);

        Player p = player();
        int skill = SkyData.get(p).getSkill(Skill.ENCHANTING);
        Component skillLine = Component.translatable("screen.nirncraft.enchanter.skill", skill, Perks.rank(p, "enchanting.enchanter"));
        g.text(font, skillLine, left + 12, top + ph - 22, ArcaneUi.MUTED, false);

        if (tab == 0) renderDisenchant(g, mouseX, mouseY, p);
        else renderEnchant(g, mouseX, mouseY, p);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        ArcaneList<?>[] lists = tab == 0 ? new ArcaneList<?>[]{disList} : new ArcaneList<?>[]{itemList, gemList};
        for (ArcaneList<?> l : lists) {
            ArcaneList.Entry<?> e = l.hoveredEntry();
            if (e != null && !e.icon().isEmpty()) g.setTooltipForNextFrame(font, e.icon(), mouseX, mouseY);
        }
    }

    private void renderDisenchant(GuiGraphicsExtractor g, int mouseX, int mouseY, Player p) {
        disList.render(g, font, mouseX, mouseY, Component.translatable("screen.nirncraft.enchanter.nothing_to_disenchant"));
        int x = disList.x + disList.w + 12;
        int y = disList.y;
        int w = left + pw - 12 - x;
        Integer slot = disList.selectedValue();
        if (slot == null) {
            drawWrapped(g, Component.translatable("screen.nirncraft.enchanter.disenchant_help"), x, y + 4, w, ArcaneUi.MUTED);
            return;
        }
        ItemStack s = com.nirncraft.inventory.Pack.stackAt(p, slot);
        g.item(s, x, y);
        g.text(font, s.getHoverName(), x + 20, y + 4, ArcaneUi.TEXT, false);
        int ly = y + 24;
        g.text(font, Component.translatable("screen.nirncraft.enchanter.will_learn"), x, ly, ArcaneUi.GOLD, false);
        ly += 12;
        for (var e : s.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).entrySet()) {
            Enchantment ench=e.getKey().value();
            boolean known = Enchanting.knows(p, ench) || e.getKey().is(net.minecraft.tags.EnchantmentTags.CURSE);
            Component name = ench.description();
            Component line = known
                    ? Component.translatable("screen.nirncraft.enchanter.known", name).withStyle(ChatFormatting.DARK_GRAY)
                    : Component.translatable("screen.nirncraft.enchanter.new", name).withStyle(ChatFormatting.LIGHT_PURPLE);
            g.text(font, line, x + 6, ly, 0xFFFFFFFF, false);
            ly += 11;
        }
        ly += 6;
        drawWrapped(g, Component.translatable("screen.nirncraft.enchanter.destroy_warning"), x, ly, w, ArcaneUi.BAD);
    }

    private void renderEnchant(GuiGraphicsExtractor g, int mouseX, int mouseY, Player p) {
        itemList.render(g, font, mouseX, mouseY, Component.translatable("screen.nirncraft.enchanter.no_items"));
        enchList.render(g, font, mouseX, mouseY, Component.translatable(itemList.selected() == null
                ? "screen.nirncraft.enchanter.pick_item" : "screen.nirncraft.enchanter.no_enchantments"));
        gemList.render(g, font, mouseX, mouseY, Component.translatable("screen.nirncraft.enchanter.no_gems"));

        int y = itemList.y + itemList.h + 6;
        Integer item = itemList.selectedValue();
        Enchantment ench = enchList.selectedValue();
        int soul = selectedSoul();
        Component result;
        if (item != null && ench != null && soul > 0) {
            ItemStack s = com.nirncraft.inventory.Pack.stackAt(p, item);
            int resLvl = Enchanting.resultLevel(p, ench, soul);
            result = Component.translatable("screen.nirncraft.enchanter.result",
                    com.nirncraft.loot.affix.ItemAffixes.previewEnchantedName(s, ench, resLvl),
                    Enchantment.getFullname(com.nirncraft.platform.EnchantmentsAccess.holder(ench),resLvl));
        } else {
            result = Component.translatable("screen.nirncraft.enchanter.choose_all");
        }
        g.text(font, result, left + 12, y, ArcaneUi.TEXT, false);
        g.text(font, Component.translatable("screen.nirncraft.enchanter.known_total", Enchanting.known(p).size()),
                left + 12, y + 12, ArcaneUi.MUTED, false);
    }

    private void drawWrapped(GuiGraphicsExtractor g, Component text, int x, int y, int w, int color) {
        for (var line : font.split(text, Math.max(40, w))) {
            g.text(font, line, x, y, color, false);
            y += 10;
        }
    }
}
