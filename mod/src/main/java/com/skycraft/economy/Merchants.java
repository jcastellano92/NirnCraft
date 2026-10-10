package com.skycraft.economy;

import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.perk.Perks;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is a merchant and what kind of shop they run.
 *
 * <p>Merchants are villagers with a real profession (not unemployed, not nitwits, not children), wandering traders
 * (the Khajiit caravans) and any entity in {@code #skycraft:merchants}. Other modules can configure a tagged
 * merchant through its persistent data: {@code skycraft_shop: {pools: ["blacksmith", ...], level: 1..5}}.</p>
 */
public final class Merchants {
    public static final TagKey<EntityType<?>> MERCHANTS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "merchants"));
    public static final String STOLEN_NBT = "skycraft_stolen";

    private Merchants() {}

    public static boolean isMerchant(LivingEntity npc) {
        if (npc == null || !npc.isAlive()) return false;
        if (com.skycraft.roads.Carriages.isCarriageDriver(npc)) return false;
        if (npc instanceof Villager v) {
            if (v.isBaby()) return false;
            VillagerProfession p = v.getVillagerData().getProfession();
            return p != VillagerProfession.NONE && p != VillagerProfession.NITWIT;
        }
        if (npc instanceof WanderingTrader) return true;
        if (npc.isBaby()) return false;
        // Skycraft townsfolk with a shop (society module: trades, caravan traders, innkeepers)
        if (npc.getPersistentData().getCompound(Shop.KEY).contains("pools", Tag.TAG_LIST)) return true;
        return npc.getType().is(MERCHANTS);
    }

    /** Persistent-data key under which Skycraft townsfolk store the vanilla profession their trade matches. */
    public static final String PROFESSION_KEY = "skycraft_profession";

    /** Khajiit caravan traders (wandering traders and society's caravan traders). */
    public static boolean isCaravan(LivingEntity npc) {
        return npc instanceof WanderingTrader || "caravan_trader".equals(npc.getPersistentData().getString("skycraft_role"));
    }

    /** Merchant level 1..5 (villager level; wandering traders are journeymen). */
    public static int level(LivingEntity npc) {
        if (npc instanceof Villager v) return Math.max(1, Math.min(5, v.getVillagerData().getLevel()));
        if (npc instanceof WanderingTrader) return 3;
        CompoundTag shop = npc.getPersistentData().getCompound(Shop.KEY);
        int lvl = shop.getInt("level");
        return lvl <= 0 ? 2 : Math.min(5, lvl);
    }

    /** Profession of a villager, the matching profession of a Skycraft townsperson's trade, or null. */
    public static VillagerProfession profession(LivingEntity npc) {
        if (npc instanceof Villager v) return v.getVillagerData().getProfession();
        String id = npc.getPersistentData().getString(PROFESSION_KEY);
        if (id.isEmpty()) return null;
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        return rl == null ? null : net.minecraftforge.registries.ForgeRegistries.VILLAGER_PROFESSIONS.getValue(rl);
    }

    /** The shop types (stock pools) of a merchant. Never empty for a merchant. */
    public static List<ShopType> pools(LivingEntity npc) {
        List<ShopType> out = new ArrayList<>();
        CompoundTag shop = npc.getPersistentData().getCompound(Shop.KEY);
        if (shop.contains("pools", Tag.TAG_LIST)) {
            ListTag list = shop.getList("pools", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                ShopType t = ShopType.byId(list.getString(i));
                if (t != null && !out.contains(t)) out.add(t);
            }
            if (!out.isEmpty()) return out;
        }
        if (isCaravan(npc)) {
            out.add(ShopType.GENERAL);
            out.add(ShopType.FENCE);
            out.add(ShopType.EXOTIC);
            return out;
        }
        VillagerProfession p = profession(npc);
        if (p == VillagerProfession.WEAPONSMITH || p == VillagerProfession.ARMORER || p == VillagerProfession.TOOLSMITH) {
            out.add(ShopType.BLACKSMITH);
        } else if (p == VillagerProfession.CLERIC) {
            out.add(ShopType.APOTHECARY);
        } else if (p == VillagerProfession.LIBRARIAN) {
            out.add(ShopType.LIBRARY);
            out.add(ShopType.ARCANE);
        } else if (p == VillagerProfession.FARMER) {
            out.add(ShopType.FOOD);
        } else if (p == VillagerProfession.BUTCHER) {
            out.add(ShopType.FOOD);
            out.add(ShopType.HUNTER);
        } else if (p == VillagerProfession.FISHERMAN) {
            out.add(ShopType.FOOD);
            out.add(ShopType.FISHER);
        } else if (p == VillagerProfession.LEATHERWORKER) {
            out.add(ShopType.HUNTER);
        } else if (p == VillagerProfession.MASON) {
            out.add(ShopType.MASON);
        } else if (p == VillagerProfession.FLETCHER) {
            out.add(ShopType.GENERAL);
            out.add(ShopType.FLETCHER);
        } else {
            // cartographer, shepherd, modded professions, tagged merchants
            out.add(ShopType.GENERAL);
        }
        return out;
    }

    public static boolean inThievesGuild(Player player) {
        return SkyData.get(player).module("quest").getCompound("factions").contains("thieves_guild");
    }

    /** Whether this merchant fences stolen goods for this player. */
    public static boolean isFence(Player player, LivingEntity npc, Shop shop) {
        if (pools(npc).contains(ShopType.FENCE) && inThievesGuild(player)) return true;
        return shop.invested() && Perks.has(player, "speech.fence");
    }

    public static boolean isStolen(ItemStack stack) {
        return stack.getTag() != null && stack.getTag().getBoolean(STOLEN_NBT);
    }

    /** Removes the stolen flag (a fence's copy of the item). */
    public static void clearStolen(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(STOLEN_NBT)) return;
        tag.remove(STOLEN_NBT);
        if (tag.isEmpty()) stack.setTag(null);
    }

    /** Whether the merchant deals in this kind of merchandise (ignoring the Speech "Merchant" perk). */
    public static boolean dealsIn(LivingEntity npc, ItemStack stack) {
        for (ShopType t : pools(npc)) if (t.buys(stack)) return true;
        return false;
    }

    /** Whether the player may sell this item to the merchant at all (deals in it or the player has Speech "Merchant"). */
    public static boolean buysFrom(Player player, LivingEntity npc, ItemStack stack) {
        return Perks.has(player, "speech.merchant") || dealsIn(npc, stack);
    }

    /** A Skyrim-flavored greeting for the barter menu. */
    public static Component greeting(LivingEntity npc) {
        List<ShopType> pools = pools(npc);
        String kind;
        int variants;
        if (isCaravan(npc)) {
            kind = "khajiit";
            variants = 3;
        } else {
            ShopType main = pools.get(0);
            kind = switch (main) {
                case BLACKSMITH -> "blacksmith";
                case APOTHECARY -> "apothecary";
                case LIBRARY, ARCANE -> "arcane";
                case FOOD, FISHER, HUNTER -> "food";
                default -> "general";
            };
            variants = 3;
        }
        int pick = Math.floorMod(npc.getUUID().hashCode() + (int) (npc.level().getDayTime() / 24000L), variants);
        return Component.translatable("dialogue.skycraft.economy.greeting." + kind + "." + pick);
    }
}
