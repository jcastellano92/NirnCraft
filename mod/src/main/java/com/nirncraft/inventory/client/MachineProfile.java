package com.nirncraft.inventory.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.core.Holder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the Skyrim machine screen ({@link SkyrimMachineScreen}) shows for one vanilla workstation menu: its slots as
 * named rows (or a grid for the crafting table), the choices the vanilla screen offered as buttons (stonecutter
 * recipes, loom patterns, enchanting options, trades, beacon powers), progress meters and notes. Everything reads the
 * client's copy of the vanilla menu, and every action is a vanilla menu click / packet: the server's menus are not
 * touched (see {@code inventory.MachineActions}).
 */
abstract class MachineProfile {
    enum Role { INPUT, FUEL, OUTPUT, RESULT }

    /** One menu slot of the machine. {@code col}/{@code row} are only used by a grid profile. */
    record Cell(int slot, String key, Role role, int col, int row) {
        Component label() {
            return Component.translatable("inventory.nirncraft.machine.role." + key);
        }
    }

    record Choice(ItemStack icon, Component label, Component detail, boolean selected, boolean enabled) {}

    record Meter(Component label, float fill, int rgb) {}

    protected final AbstractContainerMenu menu;
    protected final Minecraft mc = Minecraft.getInstance();

    protected MachineProfile(AbstractContainerMenu menu) {
        this.menu = menu;
    }

    abstract List<Cell> cells();

    /** True: draw the cells as a grid (crafting table) and move with the arrow keys. */
    boolean grid() {
        return false;
    }

    /** Heading of {@link #choices()}, or null. */
    Component choicesTitle() {
        return null;
    }

    List<Choice> choices() {
        return List.of();
    }

    /** The choice was activated: the vanilla button click (client prediction + packet). */
    void choose(int index) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return;
        if (menu.clickMenuButton(player, index)) mc.gameMode.handleInventoryButtonClick(menu.containerId, index);
    }

    List<Meter> meters() {
        return List.of();
    }

    /** Lines under the details (costs, warnings): text and colour. */
    List<Component> notes() {
        return List.of();
    }

    /** Colour of the note at the same index (default: text). */
    int noteColor(int index) {
        return SkyUi.TEXT;
    }

    /** Whether the player may type a new name for the item (anvil). */
    boolean renameable() {
        return false;
    }

    /** True when a stack from the player's inventory belongs in the machine (the My Items list shows those first). */
    boolean accepts(ItemStack stack) {
        for (Cell c : cells()) {
            if (c.role() == Role.RESULT) continue;
            if (acceptsAt(c, stack)) return true;
        }
        return false;
    }

    boolean acceptsAt(Cell c, ItemStack stack) {
        Slot s = menu.getSlot(c.slot());
        return s.mayPlace(stack);
    }

    /** The result slot is a product, not a stock: "Take All" leaves it alone. */
    static boolean takeAllSkips(Cell c) {
        return c.role() == Role.RESULT;
    }

    // ------------------------------------------------------------------ factory

    static MachineProfile of(AbstractContainerMenu menu) {
        if (menu instanceof AbstractFurnaceMenu f) return new Furnace(f);
        if (menu instanceof BrewingStandMenu b) return new Brewing(b);
        if (menu instanceof AnvilMenu a) return new Anvil(a);
        if (menu instanceof GrindstoneMenu g) {
            return new Fixed(g, cell(0, "item", Role.INPUT), cell(1, "second_item", Role.INPUT), cell(2, "result", Role.RESULT));
        }
        if (menu instanceof SmithingMenu s) {
            return new Fixed(s, cell(0, "template", Role.INPUT), cell(1, "base", Role.INPUT), cell(2, "addition", Role.INPUT),
                    cell(3, "result", Role.RESULT));
        }
        if (menu instanceof CartographyTableMenu c) {
            return new Fixed(c, cell(0, "map", Role.INPUT), cell(1, "paper", Role.INPUT), cell(2, "result", Role.RESULT));
        }
        if (menu instanceof StonecutterMenu s) return new Stonecutter(s);
        if (menu instanceof LoomMenu l) return new Loom(l);
        if (menu instanceof EnchantmentMenu e) return new Enchanting(e);
        if (menu instanceof BeaconMenu b) return new Beacon(b);
        if (menu instanceof MerchantMenu m) return new Merchant(m);
        if (menu instanceof CraftingMenu c) return new Crafting(c);
        return null;
    }

    private static Cell cell(int slot, String key, Role role) {
        return new Cell(slot, key, role, 0, slot);
    }

    private static ItemStack one(ItemStack s) {
        ItemStack c = s.copy();
        c.setCount(1);
        return c;
    }

    // ------------------------------------------------------------------ plain: named slots

    static class Fixed extends MachineProfile {
        private final List<Cell> cells;

        Fixed(AbstractContainerMenu menu, Cell... cells) {
            super(menu);
            this.cells = List.of(cells);
        }

        @Override
        List<Cell> cells() {
            return cells;
        }
    }

    // ------------------------------------------------------------------ furnace, blast furnace, smoker

    static final class Furnace extends Fixed {
        private final AbstractFurnaceMenu furnace;
        private final RecipeType<?> recipeType;

        Furnace(AbstractFurnaceMenu menu) {
            super(menu, cell(0, "ingredient", Role.INPUT), cell(1, "fuel", Role.FUEL), cell(2, "output", Role.OUTPUT));
            this.furnace = menu;
            RecipeType<?> type = RecipeType.SMELTING;
            if (menu instanceof BlastFurnaceMenu) type = RecipeType.BLASTING;
            else if (menu instanceof SmokerMenu) type = RecipeType.SMOKING;
            this.recipeType = type;
        }

        @Override
        boolean acceptsAt(Cell c, ItemStack stack) {
            if (c.role() == Role.FUEL) return stack.has(net.minecraft.core.component.DataComponents.COOKING_FUEL);
            if (c.role() == Role.INPUT && mc.level != null) {
                var allowed=recipeType==RecipeType.BLASTING?net.minecraft.world.item.crafting.RecipePropertySet.BLAST_FURNACE_INPUT:
                    recipeType==RecipeType.SMOKING?net.minecraft.world.item.crafting.RecipePropertySet.SMOKER_INPUT:net.minecraft.world.item.crafting.RecipePropertySet.FURNACE_INPUT;
                return mc.level.recipeAccess().propertySet(allowed).test(stack);
            }
            return false;
        }

        @Override
        List<Meter> meters() {
            List<Meter> m = new ArrayList<>();
            m.add(new Meter(Component.translatable("inventory.nirncraft.machine.meter.fuel"),
                    furnace.isLit() ? Math.min(1f, furnace.getLitProgress()) : 0f, 0xE0802A));
            m.add(new Meter(Component.translatable("inventory.nirncraft.machine.meter.progress"),
                    Math.min(1f, furnace.getBurnProgress()), 0xD8C060));
            return m;
        }
    }

    // ------------------------------------------------------------------ brewing stand

    static final class Brewing extends Fixed {
        private final BrewingStandMenu brewing;

        Brewing(BrewingStandMenu menu) {
            super(menu, cell(0, "bottle", Role.OUTPUT), cell(1, "bottle", Role.OUTPUT), cell(2, "bottle", Role.OUTPUT),
                    cell(3, "ingredient", Role.INPUT), cell(4, "blaze", Role.FUEL));
            this.brewing = menu;
        }

        @Override
        List<Meter> meters() {
            int ticks = brewing.getBrewingTicks();
            return List.of(
                    new Meter(Component.translatable("inventory.nirncraft.machine.meter.brewing"),
                            ticks > 0 ? Math.max(0f, 1f - ticks / 400f) : 0f, 0x7AA8E0),
                    new Meter(Component.translatable("inventory.nirncraft.machine.meter.blaze"),
                            Math.min(1f, brewing.getFuel() / 20f), 0xE0802A));
        }
    }

    // ------------------------------------------------------------------ anvil

    static final class Anvil extends Fixed {
        private final AnvilMenu anvil;

        Anvil(AnvilMenu menu) {
            super(menu, cell(0, "item", Role.INPUT), cell(1, "material", Role.INPUT), cell(2, "result", Role.RESULT));
            this.anvil = menu;
        }

        @Override
        boolean renameable() {
            return true;
        }

        @Override
        List<Component> notes() {
            if (!menu.getSlot(2).hasItem()) return List.of();
            int cost = anvil.getCost();
            Player p = mc.player;
            boolean creative = p != null && p.getAbilities().instabuild;
            List<Component> out = new ArrayList<>();
            if (cost >= 40 && !creative) {
                out.add(Component.translatable("inventory.nirncraft.machine.too_expensive"));
            } else {
                out.add(Component.translatable("inventory.nirncraft.machine.repair_cost", cost));
                if (p != null && !creative && p.experienceLevel < cost) {
                    out.add(Component.translatable("inventory.nirncraft.machine.need_levels", cost));
                }
            }
            return out;
        }

        @Override
        int noteColor(int index) {
            Player p = mc.player;
            boolean creative = p != null && p.getAbilities().instabuild;
            int cost = anvil.getCost();
            if (creative) return SkyUi.GOLD;
            return cost >= 40 || (p != null && p.experienceLevel < cost) ? SkyUi.BAD : SkyUi.GOLD;
        }
    }

    // ------------------------------------------------------------------ stonecutter

    static final class Stonecutter extends Fixed {
        private final StonecutterMenu cutter;

        Stonecutter(StonecutterMenu menu) {
            super(menu, cell(0, "stone", Role.INPUT), cell(1, "result", Role.RESULT));
            this.cutter = menu;
        }

        @Override
        Component choicesTitle() {
            return Component.translatable("inventory.nirncraft.machine.choices.cuts");
        }

        @Override
        List<Choice> choices() {
            List<Choice> out = new ArrayList<>();
            if (mc.level == null) return out;
            var recipes = cutter.getVisibleRecipes().entries();
            for (int i = 0; i < recipes.size(); i++) {
                ItemStack r = recipes.get(i).recipe().optionDisplay().resolveForFirstStack(net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(mc.level));
                out.add(new Choice(r, r.getHoverName(), r.getCount() > 1 ? Component.literal("x" + r.getCount()) : Component.empty(),
                        i == cutter.getSelectedRecipeIndex(), true));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ loom

    static final class Loom extends Fixed {
        private final LoomMenu loom;

        Loom(LoomMenu menu) {
            super(menu, cell(0, "banner", Role.INPUT), cell(1, "dye", Role.INPUT), cell(2, "pattern", Role.INPUT),
                    cell(3, "result", Role.RESULT));
            this.loom = menu;
        }

        @Override
        Component choicesTitle() {
            return Component.translatable("inventory.nirncraft.machine.choices.patterns");
        }

        @Override
        List<Choice> choices() {
            List<Choice> out = new ArrayList<>();
            String color = "white";
            if (menu.getSlot(1).getItem().getItem() instanceof DyeItem dye) color = menu.getSlot(1).getItem().getOrDefault(net.minecraft.core.component.DataComponents.DYE,net.minecraft.world.item.DyeColor.WHITE).getName();
            ItemStack banner = menu.getSlot(0).getItem();
            ItemStack icon = banner.isEmpty() ? new ItemStack(Items.BANNER.pick(net.minecraft.world.item.DyeColor.WHITE)) : one(banner);
            List<Holder<BannerPattern>> patterns = loom.getSelectablePatterns();
            for (int i = 0; i < patterns.size(); i++) {
                String translation = patterns.get(i).value().translationKey();
                out.add(new Choice(icon, Component.translatable(translation + "." + color), Component.empty(),
                        i == loom.getSelectedBannerPatternIndex(), true));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ enchanting table

    static final class Enchanting extends Fixed {
        private final EnchantmentMenu table;

        Enchanting(EnchantmentMenu menu) {
            super(menu, cell(0, "item", Role.INPUT), cell(1, "lapis", Role.INPUT));
            this.table = menu;
        }

        @Override
        Component choicesTitle() {
            return Component.translatable("inventory.nirncraft.machine.choices.enchant");
        }

        @Override
        List<Choice> choices() {
            List<Choice> out = new ArrayList<>();
            Player p = mc.player;
            boolean creative = p != null && p.getAbilities().instabuild;
            int lapis = menu.getSlot(1).getItem().getCount();
            ItemStack item = menu.getSlot(0).getItem();
            ItemStack icon = item.isEmpty() ? new ItemStack(Items.ENCHANTING_TABLE) : one(item);
            for (int i = 0; i < 3; i++) {
                int cost = table.costs[i];
                if (cost <= 0) {
                    out.add(new Choice(icon, Component.translatable("inventory.nirncraft.machine.enchant.none"), Component.empty(), false, false));
                    continue;
                }
                Holder<Enchantment> e = table.enchantClue[i] >= 0 && mc.level!=null ? mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).get(table.enchantClue[i]).orElse(null) : null;
                Component label = e != null ? Enchantment.getFullname(e,table.levelClue[i]) : Component.translatable("inventory.nirncraft.machine.enchant.unknown");
                boolean ok = creative || (p != null && p.experienceLevel >= cost && lapis >= i + 1);
                out.add(new Choice(icon, label, Component.translatable("inventory.nirncraft.machine.enchant.cost", cost, i + 1), false, ok));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ beacon

    static final class Beacon extends Fixed {
        private final BeaconMenu beacon;
        private Holder<MobEffect> primary;
        private Holder<MobEffect> secondary;
        private boolean seeded;

        Beacon(BeaconMenu menu) {
            super(menu, cell(0, "payment", Role.INPUT));
            this.beacon = menu;
        }

        private void seed() {
            if (seeded) return;
            seeded = true;
            primary = beacon.getPrimaryEffect();
            secondary = beacon.getSecondaryEffect();
        }

        /** Effects the vanilla screen offered: the first {@code min(levels, 3)} tiers as primary powers. */
        private List<Holder<MobEffect>> primaries() {
            List<Holder<MobEffect>> out = new ArrayList<>();
            int tiers = Math.min(beacon.getLevels(), 3);
            for (int t = 0; t < tiers; t++) {
                for (Holder<MobEffect> e : BeaconBlockEntity.BEACON_EFFECTS.get(t)) out.add(e);
            }
            return out;
        }

        private List<Holder<MobEffect>> secondaries() {
            List<Holder<MobEffect>> out = new ArrayList<>();
            if (beacon.getLevels() >= 4 && primary != null) {
                out.add(BeaconBlockEntity.BEACON_EFFECTS.get(3).get(0));
                out.add(primary);
            }
            return out;
        }

        @Override
        Component choicesTitle() {
            return Component.translatable("inventory.nirncraft.machine.choices.beacon");
        }

        @Override
        List<Choice> choices() {
            seed();
            List<Choice> out = new ArrayList<>();
            ItemStack icon = new ItemStack(Items.BEACON);
            for (Holder<MobEffect> e : primaries()) {
                out.add(new Choice(icon, Component.translatable("inventory.nirncraft.machine.beacon.primary", e.value().getDisplayName()),
                        Component.empty(), e == primary, true));
            }
            for (Holder<MobEffect> e : secondaries()) {
                out.add(new Choice(icon, Component.translatable("inventory.nirncraft.machine.beacon.secondary", e.value().getDisplayName()),
                        Component.empty(), e == secondary, true));
            }
            boolean ready = beacon.hasPayment() && primary != null;
            out.add(new Choice(icon, Component.translatable("inventory.nirncraft.machine.beacon.confirm"),
                    beacon.hasPayment() ? Component.empty() : Component.translatable("inventory.nirncraft.machine.beacon.need_payment"),
                    false, ready));
            return out;
        }

        @Override
        void choose(int index) {
            seed();
            List<Holder<MobEffect>> p = primaries();
            List<Holder<MobEffect>> s = secondaries();
            if (index < p.size()) {
                primary = p.get(index);
                // a secondary that no longer fits the primary is dropped (vanilla: the buttons are rebuilt)
                if (secondary != null && !secondaries().contains(secondary)) secondary = null;
            } else if (index < p.size() + s.size()) {
                Holder<MobEffect> e = s.get(index - p.size());
                secondary = secondary == e ? null : e;
            } else if (primary != null && beacon.hasPayment() && mc.getConnection() != null) {
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetBeaconPacket(
                        Optional.of(primary), Optional.ofNullable(secondary)));
                if (mc.player != null) mc.player.closeContainer();
            }
        }
    }

    // ------------------------------------------------------------------ villager / trader (when the barter screen is not used)

    static final class Merchant extends Fixed {
        private final MerchantMenu trade;
        private int selected = -1;

        Merchant(MerchantMenu menu) {
            super(menu, cell(0, "payment", Role.INPUT), cell(1, "payment2", Role.INPUT), cell(2, "result", Role.RESULT));
            this.trade = menu;
        }

        @Override
        Component choicesTitle() {
            return Component.translatable("inventory.nirncraft.machine.choices.trades");
        }

        @Override
        List<Choice> choices() {
            List<Choice> out = new ArrayList<>();
            for (int i = 0; i < trade.getOffers().size(); i++) {
                MerchantOffer o = trade.getOffers().get(i);
                ItemStack result = o.getResult();
                Component cost = costText(o);
                out.add(new Choice(result, result.getHoverName(), cost, i == selected, !o.isOutOfStock()));
            }
            return out;
        }

        private static Component costText(MerchantOffer o) {
            ItemStack a = o.getCostA();
            ItemStack b = o.getCostB();
            String s = a.getCount() + " " + a.getHoverName().getString();
            if (!b.isEmpty()) s += " + " + b.getCount() + " " + b.getHoverName().getString();
            return Component.literal(s);
        }

        @Override
        void choose(int index) {
            if (index < 0 || index >= trade.getOffers().size() || mc.getConnection() == null) return;
            selected = index;
            trade.setSelectionHint(index);
            trade.tryMoveItems(index);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(index));
        }

        @Override
        List<Component> notes() {
            return List.of(Component.translatable("inventory.nirncraft.machine.trader_level", trade.getTraderLevel()));
        }

        @Override
        int noteColor(int index) {
            return SkyUi.DIM;
        }
    }

    // ------------------------------------------------------------------ crafting table

    static final class Crafting extends MachineProfile {
        private final List<Cell> cells = new ArrayList<>();

        Crafting(CraftingMenu menu) {
            super(menu);
            cells.add(new Cell(0, "result", Role.RESULT, 3, 1));
            for (int i = 1; i <= 9; i++) cells.add(new Cell(i, "grid", Role.INPUT, (i - 1) % 3, (i - 1) / 3));
        }

        @Override
        List<Cell> cells() {
            return cells;
        }

        @Override
        boolean grid() {
            return true;
        }
    }
}
