package com.nirncraft.inventory.client;

import com.mojang.math.Axis;
import com.nirncraft.combat.ArmorClass;
import com.nirncraft.combat.WeaponClass;
import com.nirncraft.crime.Ownership;
import com.nirncraft.economy.ItemValues;
import com.nirncraft.inventory.ItemWeights;
import com.nirncraft.inventory.ValueDensity;
import com.nirncraft.inventory.Pack;
import com.nirncraft.network.CorePackets;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Skyrim-style container looting / transfer menu.
 * Replaces the vanilla screens of chests, barrels, ender chests, shulker boxes, hoppers, dispensers and droppers,
 * Lootr containers, corpses, ship holds and saddlebags (see {@link MenuSkins}).
 * Shows container items on the left, 3D rotating preview and item details on the right,
 * with Take / Steal indication based on Ownership contract, and Take All (R key).
 */
public class SkyrimContainerScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final int ROW_HEIGHT = 18;
    private static final int HEADER_HEIGHT = 14;
    private static final int PANEL_PADDING = 8;
    private static final int SORT_NAME = 0, SORT_WEIGHT = 1, SORT_VALUE = 2, SORT_VW = 3;

    private final boolean isOwned;
    /** The menu of a mount's saddlebags (com.nirncraft.mounts): slots 0 and 1 are its saddle and barding, cargo is weight-limited. */
    private final boolean isBag;
    private final float bagScale;
    /** A mount's menu shows its stat sheet over the list (key 3). */
    private boolean showSheet;
    private int statsHintX = -100;
    private final int containerSlotCount;

    // View mode: 0 = Container loot, 1 = Player inventory (Store)
    private int viewMode = 0;
    private int selectedIndex = 0;
    private int scrollOffset = 0;
    private int hoveredIndex = -1;
    private int sortMode = SORT_NAME;
    private boolean sortDescending = false;
    private int hoverSort = -1;
    /** Mouse position while the V/W column header is hovered, else null: its tooltip is drawn last. */
    private int[] vwTooltipAt;

    /**
     * {@code packIndex}: the overflow-pack entry of this row (My Items tab), or -1. {@code overflowIndex}: the entry
     * of the container's own overflow (Container tab, {@code inventory.ContainerOverflow}), or -1. A row may hold only
     * an overflow entry: then {@code slots} is empty.
     */
    public record ContainerEntry(List<Slot> slots, ItemStack stack, int totalCount, float totalWeight, int value, int packIndex,
                                 int overflowIndex) {
        public Slot primarySlot() { return slots.isEmpty() ? null : slots.get(0); }

        /** True when this row is one of a mount's gear slots (saddle, barding). */
        public boolean isGearSlot() { return !slots.isEmpty() && slots.get(0).index < com.nirncraft.mounts.Saddlebags.GEAR; }
    }

    public SkyrimContainerScreen(AbstractContainerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title,380,220);
        // the server sends this container's overflow right after the open packet: drop any copy of an earlier menu
        ContainerOverflowClient.clear();
        // chests, barrels, ender chests, corpses: ChestMenu rows; shulker boxes, hoppers, dispensers/droppers: the slots
        // in front of the player's own (the server counts them the same way, ActionHandler.storageSlots)
        int own = 0;
        for (Slot s : menu.slots) {
            if (s.container == playerInventory) break;
            own++;
        }
        this.containerSlotCount = menu instanceof ChestMenu cm ? cm.getRowCount() * 9 : own;
        this.isOwned = checkIsOwned();
        boolean bag = false;
        float scale = 1f;
        if (title.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                && "mounts.nirncraft.bags.title".equals(tc.getKey())) {
            bag = true;
            try {
                if (tc.getArgs().length > 1) scale = Float.parseFloat(String.valueOf(tc.getArgs()[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        this.isBag = bag;
        this.bagScale = scale;
        // Sneaking through a screen is com.nirncraft.client.SneakKeeper's job (it works for every screen).
        // an empty-handed sneak-use opens a mount's menu on its stat sheet
        this.showSheet = bag && com.nirncraft.mounts.client.MountClientState.takeOpenOnSheet();


    }

    private boolean checkIsOwned() {
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null || player.level() == null) return false;
        HitResult hit = net.minecraft.client.Minecraft.getInstance().hitResult;
        if (hit instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            if (Ownership.isOwnedByOther(player, player.level(), pos)) return true;
            // Check village vicinity as well
            if (!player.level().getEntitiesOfClass(Villager.class, new AABB(pos).inflate(24), Villager::isAlive).isEmpty()) {
                return true;
            }
        } else if (hit instanceof EntityHitResult ehr) {
            Entity ent = ehr.getEntity();
            return Ownership.isOwnedByOther(player, ent);
        }
        return false;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
    }

    private List<ContainerEntry> getCurrentEntries() {
        java.util.Map<ItemRowKey, List<Slot>> groups = new java.util.LinkedHashMap<>();
        java.util.Map<ItemRowKey, ItemStack> repStacks = new java.util.LinkedHashMap<>();
        int start = viewMode == 0 ? 0 : containerSlotCount;
        int end = viewMode == 0 ? containerSlotCount : menu.slots.size();

        for (int i = start; i < end; i++) {
            Slot slot = menu.getSlot(i);
            if (slot.hasItem()) {
                ItemStack stack = slot.getItem();
                ItemRowKey key = ItemRowKey.of(stack);
                groups.computeIfAbsent(key, k -> {
                    repStacks.put(k, stack.copy());
                    return new ArrayList<>();
                }).add(slot);
            }
        }

        // My Items tab: the overflow pack (inventory.Pack) joins the same rows as the slots
        java.util.Map<ItemRowKey, Integer> packIndex = new java.util.HashMap<>();
        java.util.Map<ItemRowKey, Integer> packCount = new java.util.HashMap<>();
        if (viewMode == 1 && net.minecraft.client.Minecraft.getInstance().player != null) {
            List<ItemStack> packed = Pack.entries(net.minecraft.client.Minecraft.getInstance().player);
            for (int i = 0; i < packed.size(); i++) {
                ItemStack stack = packed.get(i);
                if (stack.isEmpty()) continue;
                ItemRowKey key = ItemRowKey.of(stack);
                if (!groups.containsKey(key)) {
                    groups.put(key, new ArrayList<>());
                    repStacks.put(key, stack.copy());
                }
                packIndex.put(key, i);
                packCount.put(key, stack.getCount());
            }
        }

        // Container tab: the container's own overflow (inventory.ContainerOverflow) joins the same rows as the slots
        java.util.Map<ItemRowKey, Integer> overflowIndex = new java.util.HashMap<>();
        if (viewMode == 0) {
            List<ItemStack> over = ContainerOverflowClient.entries(menu.containerId);
            for (int i = 0; i < over.size(); i++) {
                ItemStack stack = over.get(i);
                if (stack.isEmpty()) continue;
                ItemRowKey key = ItemRowKey.of(stack);
                if (!groups.containsKey(key)) {
                    groups.put(key, new ArrayList<>());
                    repStacks.put(key, stack.copy());
                }
                overflowIndex.put(key, i);
                packCount.put(key, stack.getCount());
            }
        }

        List<ContainerEntry> list = new ArrayList<>();
        for (java.util.Map.Entry<ItemRowKey, List<Slot>> e : groups.entrySet()) {
            ItemStack rep = repStacks.get(e.getKey());
            int totalCount = packCount.getOrDefault(e.getKey(), 0);
            float totalWeight = ItemWeights.getStack(rep) / Math.max(1, rep.getCount()) * totalCount;
            for (Slot s : e.getValue()) {
                totalCount += s.getItem().getCount();
                totalWeight += ItemWeights.getStack(s.getItem());
            }
            int value = ItemValues.get(rep) * totalCount;
            list.add(new ContainerEntry(e.getValue(), rep, totalCount, totalWeight, value, packIndex.getOrDefault(e.getKey(), -1),
                    overflowIndex.getOrDefault(e.getKey(), -1)));
        }
        list.sort(entryComparator());
        return list;
    }

    private java.util.Comparator<ContainerEntry> entryComparator() {
        java.util.Comparator<ContainerEntry> c = switch (sortMode) {
            case SORT_WEIGHT -> java.util.Comparator.comparingDouble(ContainerEntry::totalWeight);
            case SORT_VALUE -> java.util.Comparator.comparingInt(ContainerEntry::value);
            case SORT_VW -> java.util.Comparator.comparingDouble(e -> ValueDensity.of(e.stack()));
            default -> java.util.Comparator.comparing(e -> e.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
        };
        if (sortDescending) c = c.reversed();
        return java.util.Comparator.<ContainerEntry, Boolean>comparing(e -> !(isBag && viewMode == 0 && e.isGearSlot()))
                .thenComparing(c)
                .thenComparing(e -> e.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
    }

    private void setSort(int mode) {
        if (sortMode == mode) {
            sortDescending = !sortDescending;
        } else {
            sortMode = mode;
            sortDescending = mode != SORT_NAME;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Suppress vanilla texture rendering; full custom Skyrim panel drawn in render()
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Suppress vanilla container/inventory labels
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {


        int px = leftPos;
        int py = topPos;
        int pw = imageWidth;
        int ph = imageHeight;

        // Dark translucent background with gold borders
        SkyUi.panel(g, px, py, px + pw, py + ph);

        // Header: Title and ownership tag
        int headerY = py + 8;
        Component displayTitle = viewMode == 0 ? this.title : Component.translatable("inventory.nirncraft.my_items");
        g.text(font, displayTitle, px + 12, headerY, SkyUi.TEXT, false);

        if (viewMode == 0 && isOwned) {
            boolean detected = com.nirncraft.client.ClientState.has(com.nirncraft.network.CorePackets.SyncVitals.DETECTED);
            int stealX = px + 16 + font.width(displayTitle);
            String warn = "Taking is STEALING. Status: " + (detected ? "[DETECTED]" : "[HIDDEN]");
            int warnColor = detected ? SkyUi.STOLEN : 0xFF55FF55;
            g.text(font, warn, stealX, headerY, warnColor, false);
        }
        if (viewMode == 0 && isBag) {
            // saddlebag load against what the saddle in slot 0 allows (the mount's bag scale rides in the title)
            float load = 0f;
            for (int i = com.nirncraft.mounts.Saddlebags.GEAR; i < containerSlotCount; i++) load += ItemWeights.getStack(menu.getSlot(i).getItem());
            // the bags' overflow rows weigh too (the saddle's limit covers slots and overflow together)
            for (ItemStack row : ContainerOverflowClient.entries(menu.containerId)) load += ItemWeights.getStack(row);
            com.nirncraft.mounts.SaddleType worn = com.nirncraft.mounts.SaddleType.of(menu.getSlot(0).getItem());
            float cap = worn == null ? 0f : worn.bagKg * bagScale;
            String loadText = ItemWeights.format(load) + " / " + ItemWeights.format(cap) + " kg";
            g.text(font, loadText, px + 16 + font.width(displayTitle), headerY, load > cap + 0.05f ? SkyUi.STOLEN : SkyUi.GOLD, false);
        }

        // View toggle tabs (Container vs My Items)
        String tabContainer = "[1] Container";
        String tabPlayer = "[2] My Items";
        int tabX = px + pw - font.width(tabContainer) - font.width(tabPlayer) - 24;
        g.text(font, tabContainer, tabX, headerY, viewMode == 0 ? SkyUi.GOLD : SkyUi.DIM, false);
        g.text(font, tabPlayer, tabX + font.width(tabContainer) + 12, headerY, viewMode == 1 ? SkyUi.GOLD : SkyUi.DIM, false);

        SkyUi.hline(g, px + 8, px + pw - 8, py + 22, 0x80);

        List<ContainerEntry> entries = getCurrentEntries();
        if (selectedIndex >= entries.size()) {
            selectedIndex = Math.max(0, entries.size() - 1);
        }

        // Left column: Column headers + Items list
        int listX = px + 10;
        int headerRowY = py + 26;
        int listY = py + 26 + HEADER_HEIGHT;
        int listW = pw / 2 + 10;
        int listH = ph - 56 - HEADER_HEIGHT;
        int maxVisible = listH / ROW_HEIGHT;

        if (scrollOffset > Math.max(0, entries.size() - maxVisible)) {
            scrollOffset = Math.max(0, entries.size() - maxVisible);
        }

        hoveredIndex = -1;
        hoverSort = -1;
        vwTooltipAt = null;
        String nameHead = SkyUi.caps(Component.translatable("inventory.nirncraft.column.name"));
        String wtHead = SkyUi.caps(Component.translatable("inventory.nirncraft.column.weight"));
        String valHead = SkyUi.caps(Component.translatable("inventory.nirncraft.column.value"));
        String vwHead = SkyUi.caps(Component.translatable("inventory.nirncraft.column.vw"));
        int wtW = font.width(wtHead);
        int valW = font.width(valHead);
        int vwW = Math.max(font.width(vwHead), font.width("9.9k"));
        for (int i = 0, n = Math.min(entries.size(), 400); i < n; i++) {
            ContainerEntry en = entries.get(i);
            wtW = Math.max(wtW, font.width(ItemWeights.format(en.totalWeight())));
            valW = Math.max(valW, font.width(en.value() + "g"));
            vwW = Math.max(vwW, font.width(ValueDensity.format(ValueDensity.of(en.stack()))));
        }
        int colRight = listX + listW - 6;
        boolean showVw = colRight - vwW - 8 - valW - 8 - wtW - 8 - (listX + 22) >= 50;
        int vwRight = colRight;
        int valRight = showVw ? vwRight - vwW - 8 : colRight;
        int wtRight = valRight - valW - 8;
        int nameRoom = wtRight - wtW - 8 - (listX + 22);

        // Column headers (click to sort; V/W tooltip ONLY on the V/W column header)
        int cols = showVw ? 4 : 3;
        String[] hs = {nameHead, wtHead, valHead, vwHead};
        int[] hx = {
                listX + 22,
                wtRight - font.width(wtHead),
                valRight - font.width(valHead),
                vwRight - font.width(vwHead)
        };
        for (int i = 0; i < cols; i++) {
            int x1 = i == 0 ? listX + 2 : hx[i] - 6;
            int x2 = i == 0 ? wtRight - wtW - 6 : hx[i] + font.width(hs[i]) + 2;
            boolean hov = SkyUi.inside(mouseX, mouseY, x1, headerRowY - 2, x2, headerRowY + 10);
            if (hov) hoverSort = i;
            if (hov && i == SORT_VW) vwTooltipAt = new int[]{mouseX, mouseY};
            boolean active = sortMode == i;
            g.text(font, hs[i], hx[i], headerRowY, active ? SkyUi.LINE : hov ? SkyUi.TEXT : SkyUi.HEADER, false);
            if (active) SkyUi.triangle(g, hx[i] - 5, headerRowY + 2, !sortDescending, SkyUi.LINE);
        }
        SkyUi.hline(g, listX, listX + listW, headerRowY + 11, 0x80);

        if (entries.isEmpty()) {
            Component emptyText = Component.translatable("inventory.nirncraft.empty_container");
            g.text(font, emptyText, listX + 8, listY + 12, SkyUi.DIM, false);
        } else {
            for (int i = 0; i < maxVisible; i++) {
                int idx = scrollOffset + i;
                if (idx >= entries.size()) break;
                ContainerEntry entry = entries.get(idx);
                int rowY = listY + i * ROW_HEIGHT;

                boolean isHovered = SkyUi.inside(mouseX, mouseY, listX, rowY, listX + listW, rowY + ROW_HEIGHT);
                boolean isSelected = idx == selectedIndex;

                if (isHovered) hoveredIndex = idx;

                if (isSelected || isHovered) {
                    g.fill(listX, rowY, listX + listW, rowY + ROW_HEIGHT, isSelected ? 0x40FFFFFF : 0x20FFFFFF);
                    SkyUi.hline(g, listX, listX + listW, rowY, isSelected ? 0x90 : 0x50);
                    SkyUi.hline(g, listX, listX + listW, rowY + ROW_HEIGHT - 1, isSelected ? 0x90 : 0x50);
                }

                // Render item icon
                g.item(entry.stack(), listX + 2, rowY + 1);

                // Name and count
                String name = entry.stack().getHoverName().getString();
                if (entry.totalCount() > 1) {
                    name += " (" + entry.totalCount() + ")";
                }
                if (isBag && viewMode == 0 && entry.isGearSlot()) {
                    name = Component.translatable("mounts.nirncraft.gear.worn", name).getString();
                }
                String ellipsized = SkyUi.ellipsize(font, name, Math.max(20, nameRoom));
                int nameColor = (viewMode == 0 && isOwned) ? SkyUi.STOLEN : (isSelected ? SkyUi.BRIGHT : (isHovered ? SkyUi.TEXT : SkyUi.DIM));
                g.text(font, ellipsized, listX + 22, rowY + 5, nameColor, false);

                // Weight, Value and gold per weight
                String wtStr = ItemWeights.format(entry.totalWeight());
                String valStr = entry.value() + "g";
                g.text(font, valStr, valRight - font.width(valStr), rowY + 5, SkyUi.GOLD, false);
                g.text(font, wtStr, wtRight - font.width(wtStr), rowY + 5, SkyUi.DIM, false);
                if (showVw) {
                    float ratio = ValueDensity.of(entry.stack());
                    String vwStr = ValueDensity.format(ratio);
                    g.text(font, vwStr, vwRight - font.width(vwStr), rowY + 5, ratio < 0f ? SkyUi.DIM : 0xFF98B888, false);
                }
            }
        }

        // Vertical separator
        int sepX = px + listW + 15;
        g.fill(sepX, py + 26, sepX + 1, py + ph - 30, 0x50C8BC9A);

        // Right column: Selected item 3D preview & stats
        int detX = sepX + 10;
        int detW = px + pw - detX - 10;
        int detY = py + 26;

        if (selectedIndex >= 0 && selectedIndex < entries.size()) {
            ContainerEntry sel = entries.get(selectedIndex);
            ItemStack selStack = sel.stack();

            // 3D spinning item preview
            int previewH = 46;
            int cx = detX + detW / 2;
            int cy = detY + previewH / 2;
            renderPreview(g, selStack, cx, cy, previewH / 16f * 0.9f);

            int infoY = detY + previewH + 4;
            String selName = SkyUi.ellipsize(font, selStack.getHoverName().getString(), detW);
            g.text(font, selName, cx - font.width(selName) / 2, infoY, SkyUi.BRIGHT, false);
            infoY += 12;

            String kind = SkyUi.ellipsize(font, kindLine(selStack).getString(), detW);
            g.text(font, kind, cx - font.width(kind) / 2, infoY, SkyUi.DIM, false);
            infoY += 12;

            SkyUi.divider(g, cx, infoY, detW / 2 - 6);
            infoY += 8;

            // Damage / Armor / Weight / Value
            double dmg = damage(selStack);
            double arm = armor(selStack);
            if (arm > 0) {
                g.text(font, "ARMOR", detX, infoY, SkyUi.HEADER, false);
                g.text(font, String.valueOf((int) arm), detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
            } else if (dmg > 1) {
                g.text(font, "DAMAGE", detX, infoY, SkyUi.HEADER, false);
                g.text(font, String.valueOf((int) dmg), detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
            }

            g.text(font, "WEIGHT", detX, infoY, SkyUi.HEADER, false);
            g.text(font, ItemWeights.format(sel.totalWeight()), detX + 50, infoY, SkyUi.BRIGHT, false);
            infoY += 11;

            com.nirncraft.mounts.SaddleType saddle = com.nirncraft.mounts.SaddleType.of(selStack);
            if (saddle != null) {
                g.text(font, "CARRY", detX, infoY, SkyUi.HEADER, false);
                g.text(font, "+" + ItemWeights.format(saddle.carryKg), detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
                g.text(font, "BAGS", detX, infoY, SkyUi.HEADER, false);
                g.text(font, ItemWeights.format(saddle.bagKg * bagScale) + " kg", detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
                g.text(font, "STAMINA", detX, infoY, SkyUi.HEADER, false);
                g.text(font, "x" + saddle.staminaMult, detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
            }

            g.text(font, "VALUE", detX, infoY, SkyUi.HEADER, false);
            g.text(font, String.valueOf(sel.value()), detX + 50, infoY, SkyUi.GOLD, false);
            infoY += 11;

            // gold per weight: higher is worth carrying
            float ratio = ValueDensity.of(selStack);
            String vwLabel = SkyUi.caps(Component.translatable("inventory.nirncraft.column.vw"));
            g.text(font, vwLabel, detX, infoY, SkyUi.HEADER, false);
            g.text(font, ValueDensity.format(ratio), detX + 50, infoY, ratio < 0f ? SkyUi.DIM : 0xFFB8E0A0, false);
        }

        // what you carry against what you can carry (the container tab is the one you take from)
        SkyUi.carryLine(g, font, detX, py + ph - 35, px + pw - 10);

        // Bottom Hints bar
        int bottomY = py + ph - 22;
        SkyUi.hline(g, px + 8, px + pw - 8, bottomY - 4, 0x80);

        String actionHint = viewMode == 0 ? (isOwned ? "[F] Steal" : "[F] Take") : "[F] Store";
        if (isBag && selectedIndex >= 0 && selectedIndex < entries.size()) {
            // on a mount's menu "take" removes its gear and "store" fits your saddle or barding
            ContainerEntry cur = entries.get(selectedIndex);
            if (viewMode == 0 && cur.isGearSlot()) actionHint = "[F] Unequip";
            else if (viewMode == 1 && (com.nirncraft.mounts.SaddleType.of(cur.stack()) != null
                    || com.nirncraft.inventory.EquipmentData.slot(cur.stack()) == EquipmentSlot.BODY
                    || cur.stack().is(com.nirncraft.mounts.MountItems.BEAST_BARDING.get()))) actionHint = "[F] Equip";
        }
        int actionColor = (viewMode == 0 && isOwned) ? SkyUi.STOLEN : SkyUi.GOLD;
        g.text(font, actionHint, px + 12, bottomY, actionColor, false);

        int allX = px + 12 + font.width(actionHint) + 16;
        String allHint = isBag ? (viewMode == 0 ? "[R] All" : "[R] Store All") : viewMode == 0 ? "[R] Take All" : "[R] Store All";
        g.text(font, allHint, allX, bottomY, SkyUi.GOLD, false);

        int spaceX = allX + font.width(allHint) + 16;
        String spaceHint = isBag ? "[Space] Tab" : "[Space] Switch Tab";
        g.text(font, spaceHint, spaceX, bottomY, SkyUi.DIM, false);

        int closeX = px + pw - font.width("[Tab/Esc] Close") - 12;
        g.text(font, "[Tab/Esc] Close", closeX, bottomY, SkyUi.DIM, false);

        if (isBag) {
            // a mount's stat sheet (com.nirncraft.mounts.client.MountSheetPanel) covers the body of the screen
            statsHintX = spaceX + font.width(spaceHint) + 12;
            g.text(font, "[3] Stats", statsHintX, bottomY, sheetUp() ? SkyUi.GOLD : SkyUi.DIM, false);
            if (sheetUp()) com.nirncraft.mounts.client.MountSheetPanel.draw(g, font, px + 4, py + 24, pw - 8, ph - 52);
        }
        if (vwTooltipAt != null && !sheetUp()) {
            g.setTooltipForNextFrame(font, Component.translatable("inventory.nirncraft.column.vw.tooltip"), vwTooltipAt[0], vwTooltipAt[1]);
        }
    }

    /** The stat sheet of a mount's menu is showing. */
    private boolean sheetUp() {
        return isBag && showSheet && viewMode == 0;
    }

    /** X of the "[3] Stats" hint of a mount's menu (set while rendering). */
    private int px3Tab() {
        return statsHintX;
    }

    static void renderPreview(GuiGraphicsExtractor g, ItemStack stack, int cx, int cy, float scale) {
        float t = (Util.getMillis() % 1_000_000L) / 1000f;
        float angle = (float) Math.sin(t * 0.8) * 30f;
        SkyUi.item3d(g, stack, cx, cy, scale * 16f, 0f, angle);

    }

    static double damage(ItemStack stack) {
        double[] result = {0.0, 0.0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                result[0] += modifier.amount();
                result[1] = 1;
            }
        });
        return result[1] != 0 ? 1 + result[0] : 0;
    }

    static double armor(ItemStack stack) {
        return com.nirncraft.inventory.EquipmentData.defense(stack);
    }

    static Component kindLine(ItemStack stack) {
        if (com.nirncraft.inventory.EquipmentData.isArmor(stack)) {
            return Component.translatable(ArmorClass.isHeavy(stack) ? "inventory.nirncraft.heavy_armor" : "inventory.nirncraft.light_armor");
        }
        if (stack.getItem() instanceof ShieldItem) return Component.translatable("inventory.nirncraft.shield");
        WeaponClass wc = WeaponClass.of(stack);
        if (wc != WeaponClass.OTHER && wc != WeaponClass.UNARMED) {
            return Component.translatable("inventory.nirncraft.weapon." + wc.name().toLowerCase(Locale.ROOT));
        }
        return stack.getItem().getName(stack);
    }

    private void takeOrStoreSelected() {
        List<ContainerEntry> entries = getCurrentEntries();
        if (selectedIndex >= 0 && selectedIndex < entries.size()) {
            ContainerEntry entry = entries.get(selectedIndex);
            // a list row aggregates every slot holding this item: take/store all of them, not just the first
            for (Slot slot : entry.slots()) {
                if (slot.hasItem()) {
                    if (viewMode == 0) {
                        SkyNetwork.sendToServer(new CorePackets.ContainerTake(menu.containerId, slot.index, false));
                    } else {
                        SkyNetwork.sendToServer(new CorePackets.ContainerStore(menu.containerId, slot.index, false));
                    }
                }
            }
            // the row's part in the overflow pack (a virtual slot id, see inventory.Pack)
            if (viewMode == 1 && entry.packIndex() >= 0) {
                SkyNetwork.sendToServer(new CorePackets.ContainerStore(menu.containerId, Pack.VIRTUAL_BASE + entry.packIndex(), false));
            }
            // a row that only exists in the container's overflow (the server's Take of a slot row takes the overflow
            // part too, see vitals.ActionHandler.takeGrouped)
            if (viewMode == 0 && entry.slots().isEmpty() && entry.overflowIndex() >= 0) {
                SkyNetwork.sendToServer(new CorePackets.ContainerTake(menu.containerId,
                        com.nirncraft.inventory.ContainerOverflow.SLOT_BASE + entry.overflowIndex(), false));
            }
        }
    }

    private void takeOrStoreAll() {
        if (viewMode == 0) {
            SkyNetwork.sendToServer(new CorePackets.ContainerTake(menu.containerId, -1, true));
        } else {
            SkyNetwork.sendToServer(new CorePackets.ContainerStore(menu.containerId, -1, true));
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0 && isBag) {
            int tx = px3Tab();
            if (SkyUi.inside((int) mouseX, (int) mouseY, tx, topPos + imageHeight - 24,tx + font.width("[3] Stats"), topPos + imageHeight - 10)) {
                viewMode = 0;
                showSheet = true;
                return true;
            }
        }
        if (button == 0 && sheetUp()) return true; // never vanilla slot clicking: the slots are not drawn but still sit under the panel
        if (button == 0) {
            if (hoverSort >= 0) {
                setSort(hoverSort);
                return true;
            }
            if (hoveredIndex >= 0) {
                selectedIndex = hoveredIndex;
                takeOrStoreSelected();
                return true;
            }

            int px = leftPos;
            int py = topPos;
            int pw = imageWidth;
            int headerY = py + 8;
            String tabContainer = "[1] Container";
            String tabPlayer = "[2] My Items";
            int tabX = px + pw - font.width(tabContainer) - font.width(tabPlayer) - 24;

            if (SkyUi.inside((int) mouseX, (int) mouseY, tabX, headerY - 2, tabX + font.width(tabContainer), headerY + 10)) {
                viewMode = 0;
                selectedIndex = 0;
                return true;
            }
            if (SkyUi.inside((int) mouseX, (int) mouseY, tabX + font.width(tabContainer) + 12, headerY - 2, px + pw - 8, headerY + 10)) {
                viewMode = 1;
                selectedIndex = 0;
                return true;
            }
        }
        return true; // same: a click on an empty part of the list must not pick up a hidden vanilla slot
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double delta) {
        if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            return true;
        } else if (delta < 0) {
            int maxVisible = (imageHeight - 56 - HEADER_HEIGHT) / ROW_HEIGHT;
            int maxOffset = Math.max(0, getCurrentEntries().size() - maxVisible);
            scrollOffset = Math.min(maxOffset, scrollOffset + 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key(), scanCode = event.keycode(), modifiers = event.modifiers();
        if (isBag && keyCode == InputConstants.KEY_3) {
            // a mount's menu: key 3 is its stat sheet (com.nirncraft.mounts.client)
            viewMode = 0;
            showSheet = true;
            return true;
        }
        if (sheetUp()) {
            // the sheet covers the list: it takes the keys that would act on the hidden rows
            switch (keyCode) {
                case InputConstants.KEY_F, InputConstants.KEY_E, InputConstants.KEY_R, InputConstants.KEY_UP, InputConstants.KEY_W, InputConstants.KEY_DOWN, InputConstants.KEY_S -> {
                    return true;
                }
                case InputConstants.KEY_SPACE, InputConstants.KEY_1 -> {
                    showSheet = false;
                    return true;
                }
                case InputConstants.KEY_2 -> showSheet = false;
                default -> {
                }
            }
        }
        switch (keyCode) {
            case InputConstants.KEY_F, InputConstants.KEY_E -> {
                takeOrStoreSelected();
                return true;
            }
            case InputConstants.KEY_R -> {
                takeOrStoreAll();
                return true;
            }
            case InputConstants.KEY_SPACE -> {
                viewMode = 1 - viewMode;
                selectedIndex = 0;
                scrollOffset = 0;
                return true;
            }
            case InputConstants.KEY_1 -> {
                viewMode = 0;
                selectedIndex = 0;
                scrollOffset = 0;
                return true;
            }
            case InputConstants.KEY_2 -> {
                viewMode = 1;
                selectedIndex = 0;
                scrollOffset = 0;
                return true;
            }
            case InputConstants.KEY_3, InputConstants.KEY_4, InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9 -> {
                return true;
            }
            case InputConstants.KEY_UP, InputConstants.KEY_W -> {
                if (selectedIndex > 0) {
                    selectedIndex--;
                    if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
                }
                return true;
            }
            case InputConstants.KEY_DOWN, InputConstants.KEY_S -> {
                List<ContainerEntry> entries = getCurrentEntries();
                if (selectedIndex < entries.size() - 1) {
                    selectedIndex++;
                    int maxVisible = (imageHeight - 56 - HEADER_HEIGHT) / ROW_HEIGHT;
                    if (selectedIndex >= scrollOffset + maxVisible) scrollOffset = selectedIndex - maxVisible + 1;
                }
                return true;
            }
            case InputConstants.KEY_TAB, InputConstants.KEY_ESCAPE -> {
                onClose();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        super.onClose();
        ContainerOverflowClient.clear();
    }
}
