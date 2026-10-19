package com.nirncraft.survival;

import com.nirncraft.core.Currency;
import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.dialogue.DialogueOption;
import com.nirncraft.economy.Barter;
import com.nirncraft.economy.Merchants;
import com.nirncraft.survival.inn.Innkeepers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

import java.util.List;

/** Conversation topics: innkeepers (room, food and drink, rumors) and priests (curing diseases). */
public final class SurvivalDialogue {
    /** Persistent-data flag other modules can set on their own priest NPCs. */
    public static final String PRIEST_TAG = "nirncraft_priest";

    private SurvivalDialogue() {}

    public static void register() {
        Dialogue.registerProvider(SurvivalDialogue::addOptions);
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (Innkeepers.isInnkeeper(npc)) {
            if (Innkeepers.hasOwnRoom(player, npc)) {
                out.add(DialogueOption.disabled("survival.rent", Component.translatable("dialogue.nirncraft.survival.rent_already"), 95));
            } else if (Innkeepers.isFullyBooked(player, npc)) {
                out.add(DialogueOption.disabled("survival.rent", Component.translatable("dialogue.nirncraft.survival.rent_full"), 95));
            } else {
                out.add(new DialogueOption("survival.rent", Component.translatable("dialogue.nirncraft.survival.rent",
                        SurvivalConfig.ROOM_PRICE.get()), 95, Innkeepers::rent));
            }
            if (Merchants.isMerchant(npc)) {
                Innkeepers.topUpStock(npc);
                out.add(new DialogueOption("survival.food", Component.translatable("dialogue.nirncraft.survival.food"), 96, Barter::open));
            }
            out.add(new DialogueOption("survival.rumors", Component.translatable("dialogue.nirncraft.survival.rumors"), 410, Innkeepers::rumor));
        }
        if (isPriest(npc) && Diseases.hasAny(player)) {
            out.add(new DialogueOption("survival.cure", Component.translatable("dialogue.nirncraft.survival.cure",
                    SurvivalConfig.CURE_PRICE.get()), 130, SurvivalDialogue::cure));
        }
    }

    public static boolean isPriest(LivingEntity npc) {
        if (npc instanceof Villager v && !v.isBaby() && v.getVillagerData().profession().is(VillagerProfession.CLERIC)) return true;
        if (npc instanceof com.nirncraft.society.entity.NpcEntity n
                && (n.role() == com.nirncraft.society.NpcRole.PRIEST || n.role() == com.nirncraft.society.NpcRole.APOTHECARY)) return true;
        String job = npc.getPersistentData().getStringOr(com.nirncraft.society.Housing.JOB_KEY, "");
        if ("priest".equalsIgnoreCase(job) || "healer".equalsIgnoreCase(job) || "apothecary".equalsIgnoreCase(job)) return true;
        return npc.getPersistentData().getBooleanOr(PRIEST_TAG, false) || "priest".equals(npc.getPersistentData().getStringOr("nirncraft_role",""));
    }

    private static void cure(ServerPlayer player, LivingEntity npc) {
        if (!npc.isAlive() || player.distanceToSqr(npc) > 64 || !Diseases.hasAny(player)) return;
        if (!Currency.take(player, SurvivalConfig.CURE_PRICE.get())) {
            Dialogue.open(player, npc, Component.translatable("survival.nirncraft.priest.no_gold"));
            return;
        }
        Diseases.cureAll(player);
        Dialogue.open(player, npc, Component.translatable("survival.nirncraft.priest.cured"));
    }
}
