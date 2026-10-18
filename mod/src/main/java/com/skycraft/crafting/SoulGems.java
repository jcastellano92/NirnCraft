package com.skycraft.crafting;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.arcane.item.SoulGemItem;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Cross-module soul gem contract (owned by the arcane sub-module). The magic module calls
 * {@link #tryCaptureSoul(ServerPlayer, LivingEntity)} when a creature under Soul Trap dies.
 *
 * <p>Soul size comes from the victim's max health: &lt;10 petty, &lt;20 lesser, &lt;40 common, &lt;80 greater,
 * otherwise grand. Players and entities in {@code #skycraft:black_souls} (villagers, illagers, bandits, guards...)
 * have black souls that only fit black soul gems.</p>
 */
public final class SoulGems {
    public static final TagKey<EntityType<?>> BLACK_SOULS = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, "black_souls"));

    public static final int PETTY = 1;
    public static final int LESSER = 2;
    public static final int COMMON = 3;
    public static final int GREATER = 4;
    public static final int GRAND = 5;
    public static final int BLACK = SoulGemItem.BLACK_SOUL;

    private SoulGems() {}

    public static boolean isBlackSoul(LivingEntity entity) {
        return entity instanceof Player || entity.getType().builtInRegistryHolder().is(BLACK_SOULS);
    }

    /** Creature tiers: {@code #skycraft:souls/petty} .. {@code #skycraft:souls/grand} fix a creature's soul size. */
    private static final java.util.List<TagKey<EntityType<?>>> SOUL_TIERS = java.util.List.of(
            soulTag("petty"), soulTag("lesser"), soulTag("common"), soulTag("greater"), soulTag("grand"));

    private static TagKey<EntityType<?>> soulTag(String size) {
        return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, "souls/" + size));
    }

    /**
     * Soul size 1 (petty) .. 5 (grand), or 6 for a black soul. Skyrim sizes souls by creature tier: the
     * {@code #skycraft:souls/*} tags say it for the bestiary (a skeever is petty, a frost atronach greater, a dragon
     * priest grand); anything else goes by its base max health, before Skycraft's leveling bonus.
     */
    public static int soulSize(LivingEntity entity) {
        if (isBlackSoul(entity)) return BLACK;
        for (int i = 0; i < SOUL_TIERS.size(); i++) {
            if (entity.getType().builtInRegistryHolder().is(SOUL_TIERS.get(i))) return PETTY + i;
        }
        var attr = entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        float hp = attr != null ? (float) attr.getBaseValue() : entity.getMaxHealth();
        if (hp < 10) return PETTY;
        if (hp < 20) return LESSER;
        if (hp < 40) return COMMON;
        if (hp < 80) return GREATER;
        return GRAND;
    }

    /** The soul held by a soul gem stack (0 if empty or not a soul gem). */
    public static int soulOf(ItemStack stack) {
        return stack.getItem() instanceof SoulGemItem ? SoulGemItem.getSoul(stack) : 0;
    }

    /**
     * Fills the smallest empty soul gem in the player's inventory that can hold the victim's soul.
     *
     * @return true if a soul was captured
     */
    public static boolean tryCaptureSoul(ServerPlayer player, LivingEntity victim) {
        if (player == null || victim == null || victim == player) return false;
        int soul = soulSize(victim);
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!(stack.getItem() instanceof SoulGemItem gem) || SoulGemItem.getSoul(stack) > 0 || !gem.canHold(soul)) continue;
            // smallest gem first; at equal size keep the reusable Azura's Star for last
            int rank = gem.rank() * 2 + (gem.reusable ? 1 : 0);
            if (rank < bestRank) {
                bestRank = rank;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            // an empty gem that overflowed into the pack (inventory.Pack): pull it into a slot and fill it there
            int packed = com.skycraft.inventory.Pack.find(player,
                    s -> s.getItem() instanceof SoulGemItem g && SoulGemItem.getSoul(s) <= 0 && g.canHold(soul));
            if (packed >= 0) bestSlot = com.skycraft.inventory.Pack.resolveSlot(player, com.skycraft.inventory.Pack.VIRTUAL_BASE + packed, null);
        }
        if (bestSlot < 0) return false;

        ItemStack stack = inv.getItem(bestSlot);
        if (stack.getCount() == 1) {
            SoulGemItem.setSoul(stack, soul);
        } else {
            ItemStack filled = stack.copy();
            filled.setCount(1);
            stack.shrink(1);
            SoulGemItem.setSoul(filled, soul);
            com.skycraft.inventory.Pack.giveOrDrop(player, filled);
        }
        inv.setChanged();

        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(),
                    16, 0.3, 0.4, 0.3, 0.04);
            level.playSound(null, victim.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
        }
        SkyData.get(player).addStat("souls_trapped", 1);
        Notifier.message(player, Component.translatable("message.skycraft.arcane.soul_captured", SoulGemItem.soulName(soul)));
        return true;
    }
}
