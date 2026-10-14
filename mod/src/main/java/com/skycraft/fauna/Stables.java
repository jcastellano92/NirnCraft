package com.skycraft.fauna;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.mounts.MountEntities;
import com.skycraft.mounts.MountItems;
import com.skycraft.mounts.MountKind;
import com.skycraft.mounts.Mounts;
import com.skycraft.mounts.MountGear;
import com.skycraft.mounts.SaddleType;
import com.skycraft.mounts.Saddlebags;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Stables: stablemasters sell their province's mounts ({@link MountKind#forSale}: hold breeds in Skyrim, Imperial
 * horses in Cyrodiil, guar in Morrowind, senche-tigers in Elsweyr, camels in Hammerfell...), bring out any mount you
 * already own, and sell saddles, the local barding and, in Black Marsh where no beast will carry you, marsh-shoes.
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
        if (npc.getPersistentData().getBoolean("skycraft_stable_master")) return true;
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
        for (MountKind kind : sale) {
            if (owned.contains(kind)) continue;
            out.add(new DialogueOption("fauna.buy_mount." + kind.id,
                    Component.translatable("dialogue.skycraft.stables.buy_mount", kind.displayName(), Currency.formatCompact(kind.cost),
                            speed(kind), kind.stamina, Math.round(MountGear.bagKg(kind, SaddleType.PLAIN))),
                    order++, (pl, n) -> buyMount(pl, n, kind)));
        }
        // any mount you own can be brought out here
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

    /** Riding speed in blocks a second (roughly), for the price list. */
    static int speed(MountKind kind) {
        double attr = switch (kind.backing) {
            case HORSE, SKELETON -> kind.breed.movementSpeed;
            case CAMEL -> 0.26;
            case GUAR -> 0.23;
            case BEAST -> {
                var t = MountEntities.type(kind);
                yield t == null ? 0.3 : MountEntities.spec(t).speed;
            }
        };
        return (int) Math.round(attr * 43.0);
    }

    private static void buyMount(ServerPlayer player, LivingEntity npc, MountKind kind) {
        if (!Currency.take(player, kind.cost)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.stables.no_gold", kind.cost));
            return;
        }
        BlockPos spawnPos = npc.blockPosition().relative(npc.getDirection(), 2);
        Mounts.grant(player, kind, spawnPos);
        Saddlebags.fit(player, MountGear.bagKg(kind, MountGear.remembered(player, kind)));
        player.level().playSound(null, spawnPos, SoundEvents.HORSE_SADDLE, SoundSource.NEUTRAL, 1.0f, 1.0f);
        Notifier.message(player, Component.translatable("fauna.skycraft.horse.bought", kind.displayName()));
        Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.stables.bought", kind.displayName()));
    }

    private static void takeOut(ServerPlayer player, LivingEntity npc, MountKind kind) {
        Mounts.switchTo(player, kind, npc.blockPosition().relative(npc.getDirection(), 2));
        Saddlebags.fit(player, MountGear.bagKg(kind, MountGear.remembered(player, kind)));
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
