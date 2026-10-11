package com.skycraft.economy;

import com.skycraft.core.Currency;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.perk.Perks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.List;

/** The economy's conversation topics: barter, invest, training and bulk selling of harvested goods. */
public final class EconomyDialogue {
    private EconomyDialogue() {}

    public static void register() {
        Dialogue.registerProvider(EconomyDialogue::addOptions);
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (Merchants.isMerchant(npc)) {
            out.add(new DialogueOption("economy.barter", Component.translatable("dialogue.skycraft.economy.barter"), 100, Barter::open));

            VillagerProfession p = Merchants.profession(npc);
            if (p == VillagerProfession.FARMER && Barter.hasAny(player, ShopType.CROPS)) {
                out.add(new DialogueOption("economy.sell_crops", Component.translatable("dialogue.skycraft.economy.sell_crops"), 110,
                        (pl, n) -> Barter.bulkSell(pl, n, ShopType.CROPS)));
            }
            if ((p == VillagerProfession.BUTCHER || p == VillagerProfession.LEATHERWORKER) && Barter.hasAny(player, ShopType.PELTS)) {
                out.add(new DialogueOption("economy.sell_pelts", Component.translatable("dialogue.skycraft.economy.sell_pelts"), 110,
                        (pl, n) -> Barter.bulkSell(pl, n, ShopType.PELTS)));
            }
            if ((p == VillagerProfession.WEAPONSMITH || p == VillagerProfession.ARMORER || p == VillagerProfession.TOOLSMITH)
                    && Barter.hasAny(player, ShopType.ORES)) {
                out.add(new DialogueOption("economy.sell_ore", Component.translatable("dialogue.skycraft.economy.sell_ore"), 110,
                        (pl, n) -> Barter.bulkSell(pl, n, ShopType.ORES)));
            }

            if (Perks.has(player, "speech.investor") && !Shop.of(npc).invested()) {
                out.add(new DialogueOption("economy.invest", Component.translatable("dialogue.skycraft.economy.invest", Currency.formatCompact(Shop.INVEST_COST)), 150,
                        EconomyDialogue::invest));
            }
        }

        Skill skill = Trainers.skillFor(npc);
        if (skill != null) {
            int cost = Trainers.cost(SkyData.get(player).getSkill(skill));
            out.add(new DialogueOption("economy.train", Component.translatable("dialogue.skycraft.economy.train", skill.displayName(), Currency.formatCompact(cost)), 200,
                    Trainers::train));
        }

        // Town steward / Jarl house options - guards and regular soldiers never sell houses
        List<Houses.HouseDef> houses = isStewardOrJarl(npc) ? Houses.housesFor(player.level(), npc.blockPosition()) : List.of();
        for (Houses.HouseDef house : houses) {
            if (!Houses.isHouseOwner(player, house)) {
                // one home: the old line; several (the Imperial City's shack and Talos Plaza house): each by name
                Component label = houses.size() == 1
                        ? Component.translatable("dialogue.skycraft.economy.house.buy", Currency.formatCompact(house.cost()))
                        : Component.translatable("dialogue.skycraft.economy.house.buy_named", house.displayName(), Currency.formatCompact(house.cost()));
                out.add(new DialogueOption("economy.buy_house." + house.id(), label, 250,
                        (p, n) -> Houses.purchaseHouse(p, p.serverLevel(), house, null)));
            } else {
                Houses.HousesData data = Houses.HousesData.get(player.server.overworld().getServer());
                for (Houses.Furnishing furn : Houses.UPGRADES) {
                    if (!house.homestead() && !data.hasFurnishing(house.id(), furn.id())) {
                        out.add(new DialogueOption("economy.furnish." + house.id() + "." + furn.id(),
                                Component.translatable("dialogue.skycraft.economy.house.furnish_option", furn.name(), Currency.formatCompact(furn.cost())), 260,
                                (p, n) -> Houses.purchaseFurnishing(p, p.serverLevel(), house, furn)));
                    }
                }
            }
        }
    }

    public static boolean isStewardOrJarl(LivingEntity npc) {
        if (npc.getPersistentData().getBoolean("skycraft_steward")) return true;
        if (com.skycraft.crime.Crimes.isGuard(npc) || npc.getType().getDescriptionId().contains("guard")) {
            return false;
        }
        if (npc instanceof com.skycraft.society.entity.NpcEntity ne) {
            return ne.role() == com.skycraft.society.NpcRole.JARL || ne.role() == com.skycraft.society.NpcRole.STEWARD;
        }
        String name = npc.getName().getString().toLowerCase(java.util.Locale.ROOT);
        return name.contains("jarl") || name.contains("steward") || name.contains("proventius") || name.contains("elder");
    }

    private static void invest(ServerPlayer player, LivingEntity npc) {
        if (!Merchants.isMerchant(npc) || !Barter.canReach(player, npc) || !Perks.has(player, "speech.investor")) return;
        Shop shop = Shop.of(npc);
        if (shop.invested()) return;
        if (!Currency.take(player, Shop.INVEST_COST)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.economy.invest.no_gold"));
            return;
        }
        shop.invest();
        shop.save();
        SkyData.get(player).addStat("shops_invested", 1);
        Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.economy.invest.done"));
    }
}
