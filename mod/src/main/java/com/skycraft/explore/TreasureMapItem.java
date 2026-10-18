package com.skycraft.explore;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.world.PlaceText;
import com.skycraft.world.WorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A treasure map, like Skyrim's: a hand-drawn sketch of the land around a buried cache, with the spot marked. The
 * cache is a real {@link SiteKind#TREASURE_CACHE} site chosen when the map is looted ({@link ExploreLoot}). Using
 * the map opens the drawing (client, {@code explore.client.TreasureMapScreen}); on the server it also marks the
 * spot on the world map when {@link ExploreConfig#TREASURE_MAP_MARKERS} is on, and the marker clears when you find
 * the cairn ({@link ExploreEvents}).
 *
 * <p>NBT: {@code tx, tz} the cache, {@code ox, oz} where the drawing is centred (offset so the X is not in the
 * middle), {@code near} the place id the drawing is labelled with, {@code no} the map's number.</p>
 */
public class TreasureMapItem extends Item {
    public static final String MARKER_PREFIX = "explore|cache|";

    public TreasureMapItem(Properties props) {
        super(props);
    }

    public static ItemStack create(MinorSites.Site cache, RandomSource random) {
        ItemStack stack = new ItemStack(ExploreRegistry.TREASURE_MAP.get());
        CompoundTag tag = com.skycraft.inventory.ItemData.customOrEmpty(stack);
        tag.putInt("tx", cache.x());
        tag.putInt("tz", cache.z());
        tag.putInt("ox", cache.x() + random.nextInt(161) - 80);
        tag.putInt("oz", cache.z() + random.nextInt(161) - 80);
        Optional<Place> near = Places.nearest(null, cache.x(), cache.z(), 2500);
        near.ifPresent(p -> tag.putString("near", p.id()));
        // a stable number per cache, like Skyrim's "Treasure Map IV"
        tag.putInt("no", 1 + (int) Math.floorMod(cache.key() * 0x9E3779B97F4A7C15L >>> 40, 40L));
        com.skycraft.inventory.ItemData.setCustom(stack, tag);
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag == null || !tag.contains("no")) return super.getName(stack);
        return Component.translatable("item.skycraft.treasure_map.numbered", roman(tag.getIntOr("no",0)));
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> lines, TooltipFlag flag) {
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag == null || !tag.contains("tx")) {
            lines.accept(Component.translatable("item.skycraft.treasure_map.blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        String region = TamrielMap.region(tag.getIntOr("tx",0), tag.getIntOr("tz",0)).name().getString();
        lines.accept(Component.translatable("item.skycraft.treasure_map.where", region).withStyle(ChatFormatting.GRAY));
        lines.accept(Component.translatable("item.skycraft.treasure_map.hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag == null || !tag.contains("tx")) return InteractionResult.PASS;
        if (level.isClientSide()) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.skycraft.explore.client.ExploreClient.openTreasureMap(stack));
        } else if (player instanceof ServerPlayer sp && ExploreConfig.flag(ExploreConfig.TREASURE_MAP_MARKERS, true)) {
            mark(sp, tag.getIntOr("tx",0), tag.getIntOr("tz",0), tag.getIntOr("no",0));
        }
        return com.skycraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    /** Adds the cache to the player's map as a known (unvisited) location, once. */
    static void mark(ServerPlayer player, int x, int z, int no) {
        PlayerData data = SkyData.get(player);
        String id = MARKER_PREFIX + Math.floorDiv(x, MinorSites.CELL) + "," + Math.floorDiv(z, MinorSites.CELL);
        if (WorldData.find(data, id) != null) return;
        if (data.module("explore").getListOrEmpty("caches_found").stream()
                .anyMatch(t -> t instanceof net.minecraft.nbt.StringTag string && string.value().equals(id))) return;
        int y = Math.max(player.level().getSeaLevel() + 1, (int) TamrielMap.elevation(x, z));
        CompoundTag loc = new CompoundTag();
        loc.putString("id", id);
        loc.putString("name", Component.translatable("item.skycraft.treasure_map.numbered", roman(no)).getString());
        loc.putString("type", "landmark");
        loc.putInt("x", x);
        loc.putInt("y", y);
        loc.putInt("z", z);
        loc.putString("dim", player.level().dimension().identifier().toString());
        loc.putInt("ax", x);
        loc.putInt("ay", y);
        loc.putInt("az", z);
        loc.putBoolean("known", true);
        WorldData.discovered(data).add(loc);
        data.markDirty();
        Notifier.message(player, Component.translatable("explore.skycraft.treasure_map.marked"));
    }

    /** Label for the drawing: the nearest named place. */
    public static Component nearName(CompoundTag tag) {
        if (!tag.contains("near")) return Component.empty();
        return Places.byId(tag.getStringOr("near","")).map(p -> (Component) Component.literal(PlaceText.plain(p))).orElse(Component.empty());
    }

    public static String roman(int n) {
        int[] v = {40, 10, 9, 5, 4, 1};
        String[] s = {"XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                out.append(s[i]);
                n -= v[i];
            }
        }
        return out.toString();
    }
}
