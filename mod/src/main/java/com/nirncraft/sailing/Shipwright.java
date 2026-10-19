package com.nirncraft.sailing;

import com.nirncraft.core.Notifier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Building ships at a shipwright's bench: each ship needs a stack of timber, sailcloth, rope and iron fittings.
 * The bench screen lists the plans; the server checks the bench is near and the materials are in the inventory.
 */
public final class Shipwright {
    /** One material line: an item tag (any planks, any logs, any wool) or a single item, and how many. */
    public record Material(@Nullable TagKey<Item> tag, @Nullable Item item, int count, String key) {
        public boolean matches(ItemStack s) {
            return !s.isEmpty() && (tag != null ? s.is(tag) : s.is(item));
        }

        public ItemStack icon() {
            if (item != null) return new ItemStack(item);
            if (tag == ItemTags.PLANKS) return new ItemStack(Items.SPRUCE_PLANKS);
            if (tag == ItemTags.LOGS) return new ItemStack(Items.SPRUCE_LOG);
            if (tag == ItemTags.WOOL) return new ItemStack(com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.asItem());
            return ItemStack.EMPTY;
        }
    }

    private static Material tag(TagKey<Item> tag, int count, String key) {
        return new Material(tag, null, count, key);
    }

    private static Material item(Item item, int count) {
        return new Material(null, item, count, item.getDescriptionId());
    }

    public static final Map<ShipType, List<Material>> PLANS = Map.of(
            ShipType.ROWBOAT, List.of(tag(ItemTags.PLANKS, 20, "sailing.nirncraft.material.planks"), item(Items.STRING, 4),
                    item(Items.IRON_NUGGET, 6)),
            ShipType.LONGSHIP, List.of(tag(ItemTags.PLANKS, 96, "sailing.nirncraft.material.planks"),
                    tag(ItemTags.LOGS, 16, "sailing.nirncraft.material.logs"), tag(ItemTags.WOOL, 12, "sailing.nirncraft.material.wool"),
                    item(Items.STRING, 16), item(Items.IRON_INGOT, 8)),
            ShipType.COG, List.of(tag(ItemTags.PLANKS, 128, "sailing.nirncraft.material.planks"),
                    tag(ItemTags.LOGS, 24, "sailing.nirncraft.material.logs"), tag(ItemTags.WOOL, 18, "sailing.nirncraft.material.wool"),
                    item(Items.STRING, 24), item(Items.IRON_INGOT, 12), item(Items.CHEST, 2)));

    private Shipwright() {}

    public static int count(Inventory inv, Material m) {
        return com.nirncraft.inventory.Inv.count(inv.player, m::matches);
    }

    public static boolean canBuild(Player player, ShipType type) {
        if (player.getAbilities().instabuild) return true;
        for (Material m : PLANS.get(type)) if (count(player.getInventory(), m) < m.count()) return false;
        return true;
    }

    /** Server side of the bench's Build button. */
    static void build(ServerPlayer player, ShipType type, BlockPos bench) {
        if (player.distanceToSqr(bench.getX() + 0.5, bench.getY() + 0.5, bench.getZ() + 0.5) > 8 * 8) return;
        if (!player.level().getBlockState(bench).is(SailingRegistry.SHIPWRIGHT_BENCH.get())) return;
        if (!canBuild(player, type)) {
            Notifier.message(player, Component.translatable("sailing.nirncraft.shipwright.missing"));
            return;
        }
        if (!player.getAbilities().instabuild) {
            for (Material m : PLANS.get(type)) com.nirncraft.inventory.Inv.take(player, m::matches, m.count());
        }
        ItemStack ship = new ItemStack(SailingRegistry.SHIP_ITEMS.get(type).get());
        com.nirncraft.inventory.Pack.giveOrDrop(player, ship);
        player.level().playSound(null, bench, SoundEvents.VILLAGER_WORK_FLETCHER, SoundSource.BLOCKS, 1f, 0.8f);
        player.level().playSound(null, bench, Blocks.SPRUCE_PLANKS.defaultBlockState().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.7f);
        Notifier.message(player, Component.translatable("sailing.nirncraft.shipwright.built", ship.getHoverName()));
    }
}
