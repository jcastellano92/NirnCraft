package com.skycraft.crafting.arcane.client;

import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.arcane.ArcanePackets;
import com.skycraft.crafting.arcane.alchemy.Alchemy;
import com.skycraft.crafting.arcane.alchemy.AlchemyEffect;
import com.skycraft.crafting.arcane.alchemy.Ingredients;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Alchemy Lab: pick 2-3 different ingredients from your inventory; effects shared by at least two of them are
 * previewed (unknown ones as "?") and Combine brews the potion or poison on the server. Taste eats one ingredient
 * to learn its first unknown effect.
 */
public class AlchemyScreen extends Screen {
    private final BlockPos pos;
    private int left, top, pw, ph;
    private Button combine, taste, clear;
    private int cooldown;

    /** Selected ingredient ids, in selection order (max 3). */
    private final List<String> chosen = new ArrayList<>();
    /** The ingredient last clicked (target of Taste). */
    private String focus;
    /** Item id -> first inventory slot holding it. */
    private final Map<String, Integer> slotOf = new LinkedHashMap<>();
    private final ArcaneList<Ingredients.Ingredient> list = new ArcaneList<>(Component.translatable("screen.skycraft.alchemy.ingredients"));

    public AlchemyScreen(BlockPos pos) {
        super(Component.translatable("block.skycraft.alchemy_lab"));
        this.pos = pos;
    }

    private Player player() {
        return minecraft.player;
    }

    @Override
    protected void init() {
        pw = Math.min(400, width - 16);
        ph = Math.min(256, height - 16);
        left = (width - pw) / 2;
        top = (height - ph) / 2;
        list.setBounds(left + 10, top + 26, Math.min(180, pw / 2 - 10), ph - 58);
        int bx = left + pw - 12;
        combine = addRenderableWidget(Button.builder(Component.translatable("screen.skycraft.alchemy.combine"), b -> doCombine())
                .bounds(bx - 80, top + ph - 28, 80, 20).build());
        taste = addRenderableWidget(Button.builder(Component.translatable("screen.skycraft.alchemy.taste"), b -> doTaste())
                .bounds(bx - 164, top + ph - 28, 80, 20).build());
        clear = addRenderableWidget(Button.builder(Component.translatable("screen.skycraft.alchemy.clear"), b -> {
            chosen.clear();
            refresh();
        }).bounds(bx - 214, top + ph - 28, 46, 20).build());
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
        slotOf.clear();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> icons = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Ingredients.Ingredient ing = Ingredients.get(s);
            if (ing == null) continue;
            slotOf.putIfAbsent(ing.id(), i);
            counts.merge(ing.id(), s.getCount(), Integer::sum);
            icons.putIfAbsent(ing.id(), s);
        }
        List<ItemStack> packed = com.skycraft.inventory.Pack.entries(p); // the overflow pack: virtual slot ids
        for (int i = 0; i < packed.size(); i++) {
            ItemStack s = packed.get(i);
            Ingredients.Ingredient ing = Ingredients.get(s);
            if (ing == null) continue;
            slotOf.putIfAbsent(ing.id(), com.skycraft.inventory.Pack.VIRTUAL_BASE + i);
            counts.merge(ing.id(), s.getCount(), Integer::sum);
            icons.putIfAbsent(ing.id(), s);
        }
        chosen.removeIf(id -> !slotOf.containsKey(id));
        if (focus != null && !slotOf.containsKey(focus)) focus = null;

        List<ArcaneList.Entry<Ingredients.Ingredient>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : slotOf.entrySet()) {
            Ingredients.Ingredient ing = Ingredients.get(e.getKey());
            if (ing == null) continue;
            ItemStack icon = icons.get(e.getKey()).copy();
            icon.setCount(counts.get(e.getKey()));
            int known = Integer.bitCount(Alchemy.knownMask(p, ing));
            Component sub = Component.translatable("screen.skycraft.alchemy.known_effects", known);
            entries.add(new ArcaneList.Entry<>(ing.id(), icon, icon.getHoverName(), sub, ing));
        }
        entries.sort((a, b) -> a.label().getString().compareToIgnoreCase(b.label().getString()));
        list.setEntries(entries);
        list.select(focus);

        if (combine != null) {
            combine.active = cooldown == 0 && chosen.size() >= 2;
            Ingredients.Ingredient f = focus == null ? null : Ingredients.get(focus);
            taste.active = cooldown == 0 && f != null && Alchemy.knownMask(p, f) != 0xF;
            clear.active = !chosen.isEmpty();
        }
    }

    private List<Ingredients.Ingredient> chosenIngredients() {
        List<Ingredients.Ingredient> out = new ArrayList<>();
        for (String id : chosen) {
            Ingredients.Ingredient ing = Ingredients.get(id);
            if (ing != null) out.add(ing);
        }
        return out;
    }

    private void doCombine() {
        if (cooldown > 0 || chosen.size() < 2) return;
        int[] slots = new int[chosen.size()];
        for (int i = 0; i < slots.length; i++) {
            Integer s = slotOf.get(chosen.get(i));
            if (s == null) return;
            slots[i] = s;
        }
        SkyNetwork.sendToServer(new ArcanePackets.Combine(pos, slots));
        cooldown = 6;
        refresh();
    }

    private void doTaste() {
        if (cooldown > 0 || focus == null) return;
        Integer slot = slotOf.get(focus);
        if (slot == null) return;
        SkyNetwork.sendToServer(new ArcanePackets.Taste(pos, slot));
        cooldown = 6;
        refresh();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        ArcaneList.Entry<Ingredients.Ingredient> e = list.click(mx, my);
        if (e == null) return false;
        String id = e.key();
        focus = id;
        if (chosen.contains(id)) chosen.remove(id);
        else if (chosen.size() < 3) chosen.add(id);
        refresh();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return list.scroll(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (minecraft != null && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        ArcaneUi.panel(g, left, top, pw, ph, ArcaneUi.GOOD);
        g.drawCenteredString(font, title, left + pw / 2, top + 7, ArcaneUi.GOLD);
        com.skycraft.inventory.client.SkyUi.carryLine(g, font, left + pw / 2 + font.width(title) / 2 + 10, top + 7, left + pw - 12);
        Player p = player();

        list.render(g, font, mouseX, mouseY, Component.translatable("screen.skycraft.alchemy.no_ingredients"));
        // selection badges
        for (int i = 0; i < chosen.size(); i++) {
            drawBadge(g, chosen.get(i), i + 1);
        }

        int x = list.x + list.w + 12;
        int w = left + pw - 12 - x;
        int y = top + 26;
        g.drawString(font, Component.translatable("screen.skycraft.alchemy.chosen"), x, y, ArcaneUi.GOLD, false);
        y += 12;
        List<Ingredients.Ingredient> ings = chosenIngredients();
        if (ings.isEmpty()) {
            for (var line : font.split(Component.translatable("screen.skycraft.alchemy.help"), Math.max(40, w))) {
                g.drawString(font, line, x, y, ArcaneUi.MUTED, false);
                y += 10;
            }
        }
        List<AlchemyEffect> shared = Alchemy.sharedEffects(ings);
        for (Ingredients.Ingredient ing : ings) {
            Integer slot = slotOf.get(ing.id());
            if (slot == null) continue;
            ItemStack s = com.skycraft.inventory.Pack.stackAt(p, slot);
            g.renderItem(s, x, y);
            g.drawString(font, s.getHoverName(), x + 18, y + 4, ArcaneUi.TEXT, false);
            y += 17;
            int colW = Math.max(60, w / 2);
            for (int i = 0; i < 4; i++) {
                int ex = x + 6 + (i % 2) * colW;
                int ey = y + (i / 2) * 10;
                AlchemyEffect eff = ing.effects()[i];
                boolean known = Alchemy.knows(p, ing, i);
                Component name = known ? eff.displayName() : Component.literal("?");
                int color = !known ? ArcaneUi.MUTED : shared.contains(eff) ? (eff.positive ? ArcaneUi.GOOD : ArcaneUi.BAD) : 0xFFB0A890;
                g.drawString(font, font.plainSubstrByWidth(name.getString(), colW - 8), ex, ey, color, false);
            }
            y += 22;
        }

        if (ings.size() >= 2) {
            ArcaneUi.divider(g, x, y, w);
            y += 6;
            renderResult(g, p, ings, shared, x, y, w);
        }

        int skill = SkyData.get(p).getSkill(Skill.ALCHEMY);
        g.drawString(font, Component.translatable("screen.skycraft.alchemy.skill", skill, Perks.rank(p, "alchemy.alchemist")),
                left + 12, top + ph - 22, ArcaneUi.MUTED, false);

        super.render(g, mouseX, mouseY, partialTick);

        ArcaneList.Entry<Ingredients.Ingredient> hover = list.hoveredEntry();
        if (hover != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(hover.label());
            for (int i = 0; i < 4; i++) {
                tip.add(Alchemy.knows(p, hover.value(), i) ? hover.value().effects()[i].coloredName()
                        : Component.translatable("screen.skycraft.alchemy.unknown").withStyle(ChatFormatting.DARK_GRAY));
            }
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    private void renderResult(GuiGraphics g, Player p, List<Ingredients.Ingredient> ings, List<AlchemyEffect> shared, int x, int y, int w) {
        if (shared.isEmpty()) {
            g.drawString(font, Component.translatable("screen.skycraft.alchemy.no_match"), x, y + 2, ArcaneUi.BAD, false);
            return;
        }
        Alchemy.Brew brew = Alchemy.brew(p, ings);
        boolean mainKnown = displayable(p, ings, brew.main());
        Component header = mainKnown
                ? Component.translatable(brew.poison() ? "item.skycraft.poison_of" : "item.skycraft.potion_of", brew.main().displayName())
                : Component.translatable("screen.skycraft.alchemy.unknown_result");
        g.drawString(font, header, x, y + 2, brew.poison() ? ArcaneUi.BAD : ArcaneUi.GOOD, false);
        y += 14;
        for (Alchemy.BrewEffect e : brew.effects()) {
            Component line;
            if (displayable(p, ings, e.effect())) {
                line = e.effect().instant()
                        ? Component.translatable("screen.skycraft.alchemy.effect_instant", e.effect().displayName(), e.amplifier() + 1)
                        : Component.translatable("screen.skycraft.alchemy.effect_timed", e.effect().displayName(), e.amplifier() + 1, e.duration() / 20);
            } else {
                line = Component.translatable("screen.skycraft.alchemy.unknown_effect");
            }
            g.drawString(font, line, x + 6, y, e.effect().positive ? ArcaneUi.GOOD : ArcaneUi.BAD, false);
            y += 10;
        }
        if (mainKnown) {
            g.drawString(font, Component.translatable("screen.skycraft.alchemy.value", brew.value()), x, y + 3, ArcaneUi.GOLD, false);
        }
    }

    /** An effect is shown when the player knows it on at least two of the chosen ingredients carrying it. */
    private static boolean displayable(Player p, List<Ingredients.Ingredient> ings, AlchemyEffect effect) {
        int known = 0;
        for (Ingredients.Ingredient ing : ings) {
            int idx = ing.indexOf(effect);
            if (idx >= 0 && Alchemy.knows(p, ing, idx)) known++;
        }
        return known >= 2;
    }

    private void drawBadge(GuiGraphics g, String id, int number) {
        List<ArcaneList.Entry<Ingredients.Ingredient>> entries = list.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).key().equals(id)) continue;
            int ry = list.rowY(i);
            if (ry < 0) return;
            int bx = list.x + list.w - 14;
            g.fill(bx, ry + 5, bx + 10, ry + 15, 0xFFE8C060);
            g.drawCenteredString(font, String.valueOf(number), bx + 5, ry + 6, 0xFF201810);
            return;
        }
    }
}
