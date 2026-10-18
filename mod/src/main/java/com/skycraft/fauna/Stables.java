package com.skycraft.fauna;

import com.skycraft.core.Currency;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.mounts.MountGear;
import com.skycraft.mounts.MountGrowth;
import com.skycraft.mounts.MountItems;
import com.skycraft.mounts.MountKind;
import com.skycraft.mounts.Mounts;
import com.skycraft.mounts.SaddleType;
import com.skycraft.mounts.Saddlebags;
import com.skycraft.mounts.StableUi;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Stables: stablemasters show their stock in one screen ({@link StableUi}: your own mount beside every animal for sale
 * this week, grades, prices and trade-in, and a Train tab), bring out any legend you own, and sell saddles, the local
 * barding and, in Black Marsh where no beast will carry you, marsh-shoes. What a region sells is
 * {@link MountKind#forSale}: hold breeds in Skyrim, Imperial horses in Cyrodiil, guar in Morrowind, senche-tigers in
 * Elsweyr, camels in Hammerfell...
 */
public final class Stables {
    static final int SADDLE_COST = 60;
    static final int HORSE_BARDING_COST = 650;
    static final int BEAST_BARDING_COST = 900;
    static final int MARSH_SHOES_COST = 250;

    private Stables() {}

    public static void register() {
        Dialogue.registerProvider(Stables::addOptions);
    }

    public static boolean isStableMaster(LivingEntity npc) {
        if (npc == null || !npc.isAlive()) return false;
        if (com.skycraft.roads.Carriages.isCarriageDriver(npc)) return false;
        if (npc.getPersistentData().getBooleanOr("skycraft_stable_master",false)) return true;
        String desc = npc.getType().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        if (desc.contains("stable") || desc.contains("hostler")) return true;
        if (npc.hasCustomName()) {
            String name = npc.getCustomName().getString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("stable") || name.contains("hostler")) return true;
        }
        return false;
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!isStableMaster(npc)) return;

        Region region = Regions.at(player.level(), npc.blockPosition());
        List<MountKind> sale = MountKind.forSale(region.id(), region.province());
        List<MountKind> owned = Mounts.stable(player);
        MountKind active = Mounts.active(player);
        int order = 180;
        // the stock, your own mount next to it, trading and training: one screen
        if (!sale.isEmpty() || active != null) {
            out.add(new DialogueOption("fauna.horses", Component.translatable("dialogue.skycraft.stables.browse"), order++, StableUi::open));
        }
        // a legend you own (Shadowmere, Arvak) can be brought out here whichever mount is out
        int shown = 0;
        for (MountKind kind : owned) {
            if (kind == active || shown >= 4) continue;
            shown++;
            out.add(new DialogueOption("fauna.take_out." + kind.id, Component.translatable("dialogue.skycraft.stables.take_out", kind.displayName()),
                    order++, (pl, n) -> takeOut(pl, n, kind)));
        }
        // saddles: the plain one everywhere, travel and war saddles for any breed that takes them, court saddles only
        // where there is a court (the Imperial and Altmer provinces, High Rock, Haafingar)
        for (SaddleType type : SaddleType.VALUES) {
            if (type == SaddleType.NOBLE && !courtSaddles(region)) continue;
            if (type != SaddleType.PLAIN && active != null && !MountGear.accepts(active, type)) continue;
            int cost = type == SaddleType.PLAIN ? SADDLE_COST : type.cost;
            out.add(new DialogueOption("fauna.buy_saddle." + type.id, Component.translatable("dialogue.skycraft.stables.buy_saddle_type",
                    type.displayName(), cost), order++, (pl, n) -> sell(pl, n, type.stack(), cost)));
        }
        if (active != null && active.bardable()) {
            boolean beast = active.backing == MountKind.Backing.BEAST;
            Item barding = beast ? MountItems.BEAST_BARDING.get() : MountItems.bardingFor(region.province());
            int cost = beast ? BEAST_BARDING_COST : HORSE_BARDING_COST;
            out.add(new DialogueOption("fauna.buy_barding", Component.translatable("dialogue.skycraft.stables.buy_barding",
                    new ItemStack(barding).getHoverName(), cost), order++, (pl, n) -> sell(pl, n, new ItemStack(barding), cost)));
        }
        if (sale.isEmpty() && "black_marsh".equals(region.province())) {
            out.add(new DialogueOption("fauna.buy_marsh_shoes", Component.translatable("dialogue.skycraft.stables.buy_marsh_shoes", MARSH_SHOES_COST),
                    order, (pl, n) -> sell(pl, n, new ItemStack(MountItems.MARSH_SHOES.get()), MARSH_SHOES_COST)));
        }
    }

    static boolean courtSaddles(Region region) {
        return switch (region.province()) {
            case "cyrodiil", "summerset", "high_rock" -> true;
            case "skyrim" -> "haafingar".equals(com.skycraft.core.Holds.base(region.id()));
            default -> false;
        };
    }

    private static void takeOut(ServerPlayer player, LivingEntity npc, MountKind kind) {
        MountGrowth.stableRest(player, kind); // a day in the stable and it trusts you the more
        Mounts.switchTo(player, kind, npc.blockPosition().relative(npc.getDirection(), 2));
        if (Mounts.activeEntity(player) instanceof net.minecraft.world.entity.Mob m) Saddlebags.fit(player, MountGear.bagCapacity(m, kind));
        Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.stables.brought", kind.displayName()));
    }

    private static void sell(ServerPlayer player, LivingEntity npc, ItemStack stack, int cost) {
        if (!Currency.take(player, cost)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.stables.no_gold", cost));
            return;
        }
        Component name = stack.getHoverName();
        com.skycraft.inventory.Pack.giveOrDrop(player, stack);
        Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.stables.sold", name));
    }
}
