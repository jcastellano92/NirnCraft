package com.skycraft.inventory.client;

import com.mojang.math.Axis;
import com.skycraft.combat.ArmorClass;
import com.skycraft.combat.WeaponClass;
import com.skycraft.crime.Ownership;
import com.skycraft.economy.ItemValues;
import com.skycraft.inventory.ItemWeights;
import com.skycraft.inventory.Pack;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
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
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Skyrim-style container looting / transfer menu.
 * Replaces ChestScreen for chests, barrels, Lootr containers, and corpses.
 * Shows container items on the left, 3D rotating preview and item details on the right,
 * with Take / Steal indication based on Ownership contract, and Take All (R key).
 */
public class SkyrimContainerScreen extends AbstractContainerScreen<ChestMenu> {
    private static final int ROW_HEIGHT = 18;
    private static final int PANEL_PADDING = 8;

    private final boolean isOwned;
    /** The menu of a mount's saddlebags (com.skycraft.mounts): slots 0 and 1 are its saddle and barding, cargo is weight-limited. */
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

    /**
     * {@code packIndex}: the overflow-pack entry of this row (My Items tab), or -1. {@code overflowIndex}: the entry
     * of the container's own overflow (Container tab, {@code inventory.ContainerOverflow}), or -1. A row may hold only
     * an overflow entry: then {@code slots} is empty.
     */
    public record ContainerEntry(List<Slot> slots, ItemStack stack, int totalCount, float totalWeight, int value, int packIndex,
                                 int overflowIndex) {
        public Slot primarySlot() { return slots.isEmpty() ? null : slots.get(0); }

        /** True when this row is one of a mount's gear slots (saddle, barding). */
        public boolean isGearSlot() { return !slots.isEmpty() && slots.get(0).index < com.skycraft.mounts.Saddlebags.GEAR; }
    }

    public SkyrimContainerScreen(ChestMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // the server sends this container's overflow right after the open packet: drop any copy of an earlier menu
        ContainerOverflowClient.clear();
        this.containerSlotCount = menu.getRowCount() * 9;
        this.isOwned = checkIsOwned();
        boolean bag = false;
        float scale = 1f;
        if (title.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                && "mounts.skycraft.bags.title".equals(tc.getKey())) {
            bag = true;
            try {
                if (tc.getArgs().length > 1) scale = Float.parseFloat(String.valueOf(tc.getArgs()[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        this.isBag = bag;
        this.bagScale = scale;
        // Sneaking through a screen is com.skycraft.client.SneakKeeper's job (it works for every screen).
        // an empty-handed sneak-use opens a mount's menu on its stat sheet
        this.showSheet = bag && com.skycraft.mounts.client.MountClientState.takeOpenOnSheet();
        this.imageWidth = 380;
        this.imageHeight = 220;
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
        java.util.Map<String, List<Slot>> groups = new java.util.LinkedHashMap<>();
        java.util.Map<String, ItemStack> repStacks = new java.util.LinkedHashMap<>();
        int start = viewMode == 0 ? 0 : containerSlotCount;
        int end = viewMode == 0 ? containerSlotCount : menu.slots.size();

        for (int i = start; i < end; i++) {
            Slot slot = menu.getSlot(i);
            if (slot.hasItem()) {
                ItemStack stack = slot.getItem();
                String key = stack.getItem().getDescriptionId() + ":" + (stack.getTag() != null ? stack.getTag().toString() : "");
                groups.computeIfAbsent(key, k -> {
                    repStacks.put(k, stack.copy());
                    return new ArrayList<>();
                }).add(slot);
            }
        }

        // My Items tab: the overflow pack (inventory.Pack) joins the same rows as the slots
        java.util.Map<String, Integer> packIndex = new java.util.HashMap<>();
        java.util.Map<String, Integer> packCount = new java.util.HashMap<>();
        if (viewMode == 1 && net.minecraft.client.Minecraft.getInstance().player != null) {
            List<ItemStack> packed = Pack.entries(net.minecraft.client.Minecraft.getInstance().player);
            for (int i = 0; i < packed.size(); i++) {
                ItemStack stack = packed.get(i);
                if (stack.isEmpty()) continue;
                String key = stack.getItem().getDescriptionId() + ":" + (stack.getTag() != null ? stack.getTag().toString() : "");
                if (!groups.containsKey(key)) {
                    groups.put(key, new ArrayList<>());
                    repStacks.put(key, stack.copy());
                }
                packIndex.put(key, i);
                packCount.put(key, stack.getCount());
            }
        }

        // Container tab: the container's own overflow (inventory.ContainerOverflow) joins the same rows as the slots
        java.util.Map<String, Integer> overflowIndex = new java.util.HashMap<>();
        if (viewMode == 0) {
            List<ItemStack> over = ContainerOverflowClient.entries(menu.containerId);
            for (int i = 0; i < over.size(); i++) {
                ItemStack stack = over.get(i);
                if (stack.isEmpty()) continue;
                String key = stack.getItem().getDescriptionId() + ":" + (stack.getTag() != null ? stack.getTag().toString() : "");
                if (!groups.containsKey(key)) {
                    groups.put(key, new ArrayList<>());
                    repStacks.put(key, stack.copy());
                }
                overflowIndex.put(key, i);
                packCount.put(key, stack.getCount());
            }
        }

        List<ContainerEntry> list = new ArrayList<>();
        for (java.util.Map.Entry<String, List<Slot>> e : groups.entrySet()) {
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
        return list;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Suppress vanilla texture rendering; full custom Skyrim panel drawn in render()
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Suppress vanilla container/inventory labels
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        int px = leftPos;
        int py = topPos;
        int pw = imageWidth;
        int ph = imageHeight;

        // Dark translucent background with gold borders
        SkyUi.panel(g, px, py, px + pw, py + ph);

        // Header: Title and ownership tag
        int headerY = py + 8;
        Component displayTitle = viewMode == 0 ? this.title : Component.translatable("inventory.skycraft.my_items");
        g.drawString(font, displayTitle, px + 12, headerY, SkyUi.TEXT, false);

        if (viewMode == 0 && isOwned) {
            boolean detected = com.skycraft.client.ClientState.has(com.skycraft.network.CorePackets.SyncVitals.DETECTED);
            int stealX = px + 16 + font.width(displayTitle);
            String warn = "Taking is STEALING. Status: " + (detected ? "[DETECTED]" : "[HIDDEN]");
            int warnColor = detected ? SkyUi.STOLEN : 0xFF55FF55;
            g.drawString(font, warn, stealX, headerY, warnColor, false);
        }
        if (viewMode == 0 && isBag) {
            // saddlebag load against what the saddle in slot 0 allows (the mount's bag scale rides in the title)
            float load = 0f;
            for (int i = com.skycraft.mounts.Saddlebags.GEAR; i < containerSlotCount; i++) load += ItemWeights.getStack(menu.getSlot(i).getItem());
            com.skycraft.mounts.SaddleType worn = com.skycraft.mounts.SaddleType.of(menu.getSlot(0).getItem());
            float cap = worn == null ? 0f : worn.bagKg * bagScale;
            String loadText = ItemWeights.format(load) + " / " + ItemWeights.format(cap) + " kg";
            g.drawString(font, loadText, px + 16 + font.width(displayTitle), headerY, load > cap + 0.05f ? SkyUi.STOLEN : SkyUi.GOLD, false);
        }

        // View toggle tabs (Container vs My Items)
        String tabContainer = "[1] Container";
        String tabPlayer = "[2] My Items";
        int tabX = px + pw - font.width(tabContainer) - font.width(tabPlayer) - 24;
        g.drawString(font, tabContainer, tabX, headerY, viewMode == 0 ? SkyUi.GOLD : SkyUi.DIM, false);
        g.drawString(font, tabPlayer, tabX + font.width(tabContainer) + 12, headerY, viewMode == 1 ? SkyUi.GOLD : SkyUi.DIM, false);

        SkyUi.hline(g, px + 8, px + pw - 8, py + 22, 0x80);

        List<ContainerEntry> entries = getCurrentEntries();
        if (selectedIndex >= entries.size()) {
            selectedIndex = Math.max(0, entries.size() - 1);
        }

        // Left column: Items list
        int listX = px + 10;
        int listY = py + 26;
        int listW = pw / 2 + 10;
        int listH = ph - 56;
        int maxVisible = listH / ROW_HEIGHT;

        if (scrollOffset > Math.max(0, entries.size() - maxVisible)) {
            scrollOffset = Math.max(0, entries.size() - maxVisible);
        }

        hoveredIndex = -1;
        if (entries.isEmpty()) {
            Component emptyText = Component.translatable("inventory.skycraft.empty_container");
            g.drawString(font, emptyText, listX + 8, listY + 12, SkyUi.DIM, false);
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
                g.renderItem(entry.stack(), listX + 2, rowY + 1);

                // Name and count
                String name = entry.stack().getHoverName().getString();
                if (entry.totalCount() > 1) {
                    name += " (" + entry.totalCount() + ")";
                }
                if (isBag && viewMode == 0 && entry.isGearSlot()) {
                    name = Component.translatable("mounts.skycraft.gear.worn", name).getString();
                }
                String ellipsized = SkyUi.ellipsize(font, name, listW - 75);
                int nameColor = (viewMode == 0 && isOwned) ? SkyUi.STOLEN : (isSelected ? SkyUi.BRIGHT : (isHovered ? SkyUi.TEXT : SkyUi.DIM));
                g.drawString(font, ellipsized, listX + 22, rowY + 5, nameColor, false);

                // Weight and Value
                String wtStr = ItemWeights.format(entry.totalWeight());
                String valStr = entry.value() + "g";
                int infoX = listX + listW - font.width(valStr) - 6;
                g.drawString(font, valStr, infoX, rowY + 5, SkyUi.GOLD, false);
                g.drawString(font, wtStr, infoX - font.width(wtStr) - 6, rowY + 5, SkyUi.DIM, false);
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
            g.drawString(font, selName, cx - font.width(selName) / 2, infoY, SkyUi.BRIGHT, false);
            infoY += 12;

            String kind = SkyUi.ellipsize(font, kindLine(selStack).getString(), detW);
            g.drawString(font, kind, cx - font.width(kind) / 2, infoY, SkyUi.DIM, false);
            infoY += 12;

            SkyUi.divider(g, cx, infoY, detW / 2 - 6);
            infoY += 8;

            // Damage / Armor / Weight / Value
            double dmg = damage(selStack);
            double arm = armor(selStack);
            if (arm > 0) {
                g.drawString(font, "ARMOR", detX, infoY, SkyUi.HEADER, false);
                g.drawString(font, String.valueOf((int) arm), detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
            } else if (dmg > 1) {
                g.drawString(font, "DAMAGE", detX, infoY, SkyUi.HEADER, false);
                g.drawString(font, String.valueOf((int) dmg), detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
            }

            g.drawString(font, "WEIGHT", detX, infoY, SkyUi.HEADER, false);
            g.drawString(font, ItemWeights.format(sel.totalWeight()), detX + 50, infoY, SkyUi.BRIGHT, false);
            infoY += 11;

            com.skycraft.mounts.SaddleType saddle = com.skycraft.mounts.SaddleType.of(selStack);
            if (saddle != null) {
                g.drawString(font, "CARRY", detX, infoY, SkyUi.HEADER, false);
                g.drawString(font, "+" + ItemWeights.format(saddle.carryKg), detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
                g.drawString(font, "BAGS", detX, infoY, SkyUi.HEADER, false);
                g.drawString(font, ItemWeights.format(saddle.bagKg * bagScale) + " kg", detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
                g.drawString(font, "STAMINA", detX, infoY, SkyUi.HEADER, false);
                g.drawString(font, "x" + saddle.staminaMult, detX + 50, infoY, SkyUi.BRIGHT, false);
                infoY += 11;
            }

            g.drawString(font, "VALUE", detX, infoY, SkyUi.HEADER, false);
            g.drawString(font, String.valueOf(sel.value()), detX + 50, infoY, SkyUi.GOLD, false);
        }

        // Bottom Hints bar
        int bottomY = py + ph - 22;
        SkyUi.hline(g, px + 8, px + pw - 8, bottomY - 4, 0x80);

        String actionHint = viewMode == 0 ? (isOwned ? "[F] Steal" : "[F] Take") : "[F] Store";
        if (isBag && selectedIndex >= 0 && selectedIndex < entries.size()) {
            // on a mount's menu "take" removes its gear and "store" fits your saddle or barding
            ContainerEntry cur = entries.get(selectedIndex);
            if (viewMode == 0 && cur.isGearSlot()) actionHint = "[F] Unequip";
            else if (viewMode == 1 && (com.skycraft.mounts.SaddleType.of(cur.stack()) != null
                    || cur.stack().getItem() instanceof net.minecraft.world.item.HorseArmorItem
                    || cur.stack().is(com.skycraft.mounts.MountItems.BEAST_BARDING.get()))) actionHint = "[F] Equip";
        }
        int actionColor = (viewMode == 0 && isOwned) ? SkyUi.STOLEN : SkyUi.GOLD;
        g.drawString(font, actionHint, px + 12, bottomY, actionColor, false);

        int allX = px + 12 + font.width(actionHint) + 16;
        String allHint = isBag ? (viewMode == 0 ? "[R] All" : "[R] Store All") : viewMode == 0 ? "[R] Take All" : "[R] Store All";
        g.drawString(font, allHint, allX, bottomY, SkyUi.GOLD, false);

        int spaceX = allX + font.width(allHint) + 16;
        String spaceHint = isBag ? "[Space] Tab" : "[Space] Switch Tab";
        g.drawString(font, spaceHint, spaceX, bottomY, SkyUi.DIM, false);

        int closeX = px + pw - font.width("[Tab/Esc] Close") - 12;
        g.drawString(font, "[Tab/Esc] Close", closeX, bottomY, SkyUi.DIM, false);

        if (isBag) {
            // a mount's stat sheet (com.skycraft.mounts.client.MountSheetPanel) covers the body of the screen
            statsHintX = spaceX + font.width(spaceHint) + 12;
            g.drawString(font, "[3] Stats", statsHintX, bottomY, sheetUp() ? SkyUi.GOLD : SkyUi.DIM, false);
            if (sheetUp()) com.skycraft.mounts.client.MountSheetPanel.draw(g, font, px + 4, py + 24, pw - 8, ph - 52);
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

    private static void renderPreview(GuiGraphics g, ItemStack stack, int cx, int cy, float scale) {
        float t = (Util.getMillis() % 1_000_000L) / 1000f;
        float angle = (float) Math.sin(t * 0.8) * 30f;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 200);
        g.pose().mulPose(Axis.YP.rotationDegrees(angle));
        g.pose().scale(scale, scale, scale);
        g.pose().translate(0, 0, -150);
        g.renderItem(stack, -8, -8);
        g.pose().popPose();
    }

    private static double damage(ItemStack stack) {
        try {
            var mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
            double sum = 0;
            boolean any = false;
            for (AttributeModifier m : mods.get(Attributes.ATTACK_DAMAGE)) {
                if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                    sum += m.getAmount();
                    any = true;
                }
            }
            return any ? 1 + sum : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static double armor(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.HorseArmorItem horse) return horse.getProtection();
        if (!(stack.getItem() instanceof ArmorItem armor)) return 0;
        return armor.getDefense();
    }

    private static Component kindLine(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem) {
            return Component.translatable(ArmorClass.isHeavy(stack) ? "inventory.skycraft.heavy_armor" : "inventory.skycraft.light_armor");
        }
        if (stack.getItem() instanceof ShieldItem) return Component.translatable("inventory.skycraft.shield");
        WeaponClass wc = WeaponClass.of(stack);
        if (wc != WeaponClass.OTHER && wc != WeaponClass.UNARMED) {
            return Component.translatable("inventory.skycraft.weapon." + wc.name().toLowerCase(Locale.ROOT));
        }
        return stack.getItem().getDescription();
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
                        com.skycraft.inventory.ContainerOverflow.SLOT_BASE + entry.overflowIndex(), false));
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isBag) {
            int tx = px3Tab();
            if (SkyUi.inside((int) mouseX, (int) mouseY, tx, topPos + imageHeight - 24,tx + font.width("[3] Stats"), topPos + imageHeight - 10)) {
                viewMode = 0;
                showSheet = true;
                return true;
            }
        }
        if (button == 0 && sheetUp()) return super.mouseClicked(mouseX, mouseY, button);
        if (button == 0) {
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
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            return true;
        } else if (delta < 0) {
            int maxVisible = (imageHeight - 56) / ROW_HEIGHT;
            int maxOffset = Math.max(0, getCurrentEntries().size() - maxVisible);
            scrollOffset = Math.min(maxOffset, scrollOffset + 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isBag && keyCode == GLFW.GLFW_KEY_3) {
            // a mount's menu: key 3 is its stat sheet (com.skycraft.mounts.client)
            viewMode = 0;
            showSheet = true;
            return true;
        }
        if (sheetUp()) {
            // the sheet covers the list: it takes the keys that would act on the hidden rows
            switch (keyCode) {
                case GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                    return true;
                }
                case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_1 -> {
                    showSheet = false;
                    return true;
                }
                case GLFW.GLFW_KEY_2 -> showSheet = false;
                default -> {
                }
            }
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_E -> {
                takeOrStoreSelected();
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                takeOrStoreAll();
                return true;
            }
            case GLFW.GLFW_KEY_SPACE -> {
                viewMode = 1 - viewMode;
                selectedIndex = 0;
                scrollOffset = 0;
                return true;
            }
            case GLFW.GLFW_KEY_1 -> {
                viewMode = 0;
                selectedIndex = 0;
                scrollOffset = 0;
                return true;
            }
            case GLFW.GLFW_KEY_2 -> {
                viewMode = 1;
                selectedIndex = 0;
                scrollOffset = 0;
                return true;
            }
            case GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9 -> {
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                if (selectedIndex > 0) {
                    selectedIndex--;
                    if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
                }
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                List<ContainerEntry> entries = getCurrentEntries();
                if (selectedIndex < entries.size() - 1) {
                    selectedIndex++;
                    int maxVisible = (imageHeight - 56) / ROW_HEIGHT;
                    if (selectedIndex >= scrollOffset + maxVisible) scrollOffset = selectedIndex - maxVisible + 1;
                }
                return true;
            }
            case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ESCAPE -> {
                onClose();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
        ContainerOverflowClient.clear();
    }
}
