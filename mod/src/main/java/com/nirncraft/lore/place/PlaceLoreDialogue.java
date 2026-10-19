package com.nirncraft.lore.place;

import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.dialogue.DialogueOption;
import com.nirncraft.quest.Quests;
import com.nirncraft.quest.story.QuestNpcs;
import com.nirncraft.quest.story.Story;
import com.nirncraft.quest.story.StoryData;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.world.Discovery;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Townsfolk talk about the land around them:
 * <ul>
 *   <li>"Heard of any trouble hereabouts?": the nearest place mini-quest the player can still take (its pitch, the
 *   place goes on the map and the quest starts), else one of the region's rumours;</li>
 *   <li>"Know any local tales?": one of the region's legends or folk songs.</li>
 * </ul>
 * Rumours, legends and songs come from the region's lore, falling back to its province's ({@link PlaceLore#region}).
 */
public final class PlaceLoreDialogue {
    /** How far local innkeepers/stewards offer trouble quests, and how far townsfolk know local rumours. */
    private static final int TROUBLE_RANGE = 900;

    private PlaceLoreDialogue() {}

    public static void register() {
        Dialogue.registerProvider(PlaceLoreDialogue::addOptions);
    }

    static boolean isTownsfolk(ServerPlayer player, LivingEntity e) {
        if (!(e instanceof NpcEntity npc)) return false;
        NpcRole role = npc.role();
        return role != null && role.civilian && role != NpcRole.CHILD && !npc.isPrisoner() && !npc.isHostileTo(player)
                && QuestNpcs.idOf(npc).isEmpty();
    }

    private static boolean canOfferTroubleQuest(ServerPlayer player, LivingEntity e) {
        if (!(e instanceof NpcEntity npc)) return false;
        NpcRole role = npc.role();
        if (role != NpcRole.INNKEEPER && role != NpcRole.STEWARD) return false;
        return com.nirncraft.quest.QuestDialogue.canOfferQuest(player, npc);
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!isTownsfolk(player, npc)) return;
        Region region = Regions.at(npc.level(), npc.blockPosition());
        PlaceLore.RegionLore lore = PlaceLore.region(region.id());
        out.add(new DialogueOption("place_lore.trouble", Component.translatable("place_lore.nirncraft.option.trouble"), 55,
                PlaceLoreDialogue::trouble));
        if (lore != null && (!lore.legends().isEmpty() || !lore.songs().isEmpty())) {
            out.add(new DialogueOption("place_lore.tales", Component.translatable("place_lore.nirncraft.option.tales"), 56,
                    PlaceLoreDialogue::tales));
        }
    }

    /** The nearest place in the same region with a mini-quest or rumour. */
    private static Place troublePlace(ServerPlayer player, LivingEntity npc, boolean requireAvailableQuest) {
        int x = npc.getBlockX(), z = npc.getBlockZ();
        Region here = Regions.at(npc.level(), npc.blockPosition());
        List<Place> near = new ArrayList<>(Places.overlapping(x - TROUBLE_RANGE, z - TROUBLE_RANGE, x + TROUBLE_RANGE, z + TROUBLE_RANGE));
        near.sort(Comparator.comparingLong(p -> p.distSqr(x, z)));
        for (Place p : near) {
            if (p.distSqr(x, z) > (long) TROUBLE_RANGE * TROUBLE_RANGE) break;
            if (here != null && !p.regionId().isEmpty() && !here.id().equals(p.regionId())) continue;
            PlaceLore.Content c = PlaceLore.contentFor(p.id()).orElse(null);
            if (c == null) continue;
            if (requireAvailableQuest) {
                if (c.quest().isEmpty()) continue;
                StoryData.QuestDef def = StoryData.quest(c.quest());
                if (def == null || !"manual".equals(def.start) || !Story.available(player, def)) continue;
            } else if (c.rumour() == null || c.rumour().text().isEmpty()) {
                continue;
            }
            return p;
        }
        return null;
    }

    private static void trouble(ServerPlayer player, LivingEntity npc) {
        RandomSource r = player.getRandom();
        // Only innkeepers and stewards actually offer a local quest (with Accept/Decline); ordinary townsfolk share rumours
        if (canOfferTroubleQuest(player, npc)) {
            Place questPlace = troublePlace(player, npc, true);
            if (questPlace != null) {
                PlaceLore.Content c = PlaceLore.contentFor(questPlace.id()).orElseThrow();
                StoryData.QuestDef def = StoryData.quest(c.quest());
                if (def != null) {
                    com.nirncraft.quest.QuestDialogue.offerStory(player, npc, def, () -> Discovery.revealPlace(player, questPlace));
                    return;
                }
            }
        }
        Place rumourPlace = troublePlace(player, npc, false);
        if (rumourPlace != null && r.nextBoolean()) {
            PlaceLore.Content c = PlaceLore.contentFor(rumourPlace.id()).orElseThrow();
            Discovery.revealPlace(player, rumourPlace);
            Dialogue.open(player, npc, c.rumour().c());
            return;
        }
        Region region = Regions.at(npc.level(), npc.blockPosition());
        Component rumour = PlaceLore.regionalRumour(region.id(), r);
        Dialogue.open(player, npc, rumour != null ? rumour : Component.translatable("place_lore.nirncraft.say.quiet." + r.nextInt(3)));
    }

    private static void tales(ServerPlayer player, LivingEntity npc) {
        RandomSource r = player.getRandom();
        PlaceLore.RegionLore lore = PlaceLore.region(Regions.at(npc.level(), npc.blockPosition()).id());
        if (lore == null) {
            Dialogue.open(player, npc, Component.translatable("place_lore.nirncraft.say.quiet.0"));
            return;
        }
        boolean song = !lore.songs().isEmpty() && (lore.legends().isEmpty() || r.nextInt(3) == 0);
        if (song) {
            PlaceLore.Song s = lore.songs().get(r.nextInt(lore.songs().size()));
            MutableComponent verse = Component.empty();
            for (int i = 0; i < s.lines().size(); i++) {
                if (i > 0) verse.append(" / ");
                verse.append(s.lines().get(i).c());
            }
            Dialogue.open(player, npc, Component.translatable("place_lore.nirncraft.say.song", s.title().c(), verse));
        } else {
            Dialogue.open(player, npc, lore.legends().get(r.nextInt(lore.legends().size())).c());
        }
    }
}
