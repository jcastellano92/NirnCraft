package com.skycraft.quest.story;

import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.quest.Objective;
import com.skycraft.quest.Quest;
import com.skycraft.quest.QuestDialogue;
import com.skycraft.quest.Quests;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Conversation topics of the story quests: the choices of the current step ("Follow Ralof" / "Follow Hadvar",
 * "Spare Paarthurnax"...), the quests a named NPC offers, and the NPC's small talk.
 */
public final class StoryDialogue {
    private StoryDialogue() {}

    public static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        MinecraftServer server = player.level().getServer();
        for (Quests.Ref ref : Quests.active(server, player.getUUID())) {
            Quest q = ref.quest();
            Objective o = q.current();
            if (o == null || o.type != Objective.Type.TALK_TO || !o.data.contains(Story.CHOICES) || !QuestDialogue.matchesNpc(o, npc)) continue;
            ListTag list = o.data.getListOrEmpty(Story.CHOICES);
            for (int j = 0; j < list.size(); j++) {
                CompoundTag c = list.getCompoundOrEmpty(j);
                String only = c.getStringOr("npc","");
                if (!only.isEmpty() && !QuestNpcs.matches(npc, only)) continue;
                String questId = q.id;
                int choice = j;
                out.add(new DialogueOption("story.choice." + questId + "." + j,
                        QuestDialogue.questLabel(Quest.readComponent(c.getStringOr("label",""))), 90 + j,
                        (pl, n) -> choose(pl, n, questId, choice)));
            }
        }
        String id = QuestNpcs.idOf(npc);
        if (id.isEmpty()) return;
        for (StoryData.QuestDef def : StoryData.quests()) {
            if (!id.equals(def.offeredBy()) || !Story.available(player, def)) continue;
            if (!def.isMain() && !QuestDialogue.canOfferQuest(player, npc)) continue;
            Component label = def.offer.isEmpty() ? Component.translatable("story.skycraft.default.offer", def.title.c()) : def.offer.c();
            out.add(new DialogueOption("story.offer." + def.id, QuestDialogue.questLabel(label), 95,
                    (pl, n) -> QuestDialogue.offerStory(pl, n, def, null)));
            break; // One quest offer at a time per named NPC
        }
        StoryData.NpcDef def = StoryData.npc(id);
        if (def != null && !def.greet.isEmpty()) {
            out.add(new DialogueOption("story.chat." + id, Component.translatable("story.skycraft.default.chat"), 299,
                    (pl, n) -> Dialogue.open(pl, n, def.greet.c())));
        }
    }

    private static void choose(ServerPlayer player, LivingEntity npc, String questId, int choice) {
        Quests.Ref ref = Quests.find(player.level().getServer(), player.getUUID(), questId);
        if (ref == null || !ref.quest().isActive()) return;
        Quest q = ref.quest();
        Objective o = q.current();
        if (o == null || o.type != Objective.Type.TALK_TO || !QuestDialogue.matchesNpc(o, npc)) return;
        ListTag list = o.data.getListOrEmpty(Story.CHOICES);
        if (choice < 0 || choice >= list.size()) return;
        CompoundTag c = list.getCompoundOrEmpty(choice);
        String only = c.getStringOr("npc","");
        if (!only.isEmpty() && !QuestNpcs.matches(npc, only)) return;
        // the choice's consequences first, so the next step already sees its flags
        Story.runActions(player.level().getServer(), ref.key(), q, c.getStringOr("actions",""), player);
        Quests.completeObjective(player.level().getServer(), ref.key(), q, o, player);
        Dialogue.open(player, npc, Quest.readComponent(c.getStringOr("reply","")));
    }
}
