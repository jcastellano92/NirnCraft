package com.skycraft.survival;

import com.skycraft.core.Currency;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.economy.Barter;
import com.skycraft.economy.Merchants;
import com.skycraft.survival.inn.Innkeepers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.List;

/** Conversation topics: innkeepers (room, food and drink, rumors) and priests (curing diseases). */
public final class SurvivalDialogue {
    /** Persistent-data flag other modules can set on their own priest NPCs. */
    public static final String PRIEST_TAG = "skycraft_priest";

    private SurvivalDialogue() {}

    public static void register() {
        Dialogue.registerProvider(SurvivalDialogue::addOptions);
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (Innkeepers.isInnkeeper(npc)) {
            if (Innkeepers.hasOwnRoom(player, npc)) {
                out.add(new DialogueOption("survival.rent", Component.translatable("dialogue.skycraft.survival.rent_already"), 95, Innkeepers::rent));
            } else if (Innkeepers.isFullyBooked(player, npc)) {
                out.add(new DialogueOption("survival.rent", Component.translatable("dialogue.skycraft.survival.rent_full")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), 95, Innkeepers::rent));
            } else {
                out.add(new DialogueOption("survival.rent", Component.translatable("dialogue.skycraft.survival.rent",
                        SurvivalConfig.ROOM_PRICE.get()), 95, Innkeepers::rent));
            }
            if (Merchants.isMerchant(npc)) {
                Innkeepers.topUpStock(npc);
                out.add(new DialogueOption("survival.food", Component.translatable("dialogue.skycraft.survival.food"), 96, Barter::open));
            }
            out.add(new DialogueOption("survival.rumors", Component.translatable("dialogue.skycraft.survival.rumors"), 410, Innkeepers::rumor));
        }
        if (isPriest(npc) && Diseases.hasAny(player)) {
            out.add(new DialogueOption("survival.cure", Component.translatable("dialogue.skycraft.survival.cure",
                    SurvivalConfig.CURE_PRICE.get()), 130, SurvivalDialogue::cure));
        }
    }

    public static boolean isPriest(LivingEntity npc) {
        if (npc instanceof Villager v && !v.isBaby() && v.getVillagerData().getProfession() == VillagerProfession.CLERIC) return true;
        if (npc instanceof com.skycraft.society.entity.NpcEntity n
                && (n.role() == com.skycraft.society.NpcRole.PRIEST || n.role() == com.skycraft.society.NpcRole.APOTHECARY)) return true;
        String job = npc.getPersistentData().getString(com.skycraft.society.Housing.JOB_KEY);
        if ("priest".equalsIgnoreCase(job) || "healer".equalsIgnoreCase(job) || "apothecary".equalsIgnoreCase(job)) return true;
        return npc.getPersistentData().getBoolean(PRIEST_TAG) || "priest".equals(npc.getPersistentData().getString("skycraft_role"));
    }

    private static void cure(ServerPlayer player, LivingEntity npc) {
        if (!npc.isAlive() || player.distanceToSqr(npc) > 64 || !Diseases.hasAny(player)) return;
        if (!Currency.take(player, SurvivalConfig.CURE_PRICE.get())) {
            Dialogue.open(player, npc, Component.translatable("survival.skycraft.priest.no_gold"));
            return;
        }
        Diseases.cureAll(player);
        Dialogue.open(player, npc, Component.translatable("survival.skycraft.priest.cured"));
    }
}
