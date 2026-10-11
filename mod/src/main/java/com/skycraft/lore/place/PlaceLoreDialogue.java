package com.skycraft.lore.place;

import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.quest.Quests;
import com.skycraft.quest.story.QuestNpcs;
import com.skycraft.quest.story.Story;
import com.skycraft.quest.story.StoryData;
import com.skycraft.society.NpcRole;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.world.Discovery;
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
    /** How far townsfolk know of trouble. */
    private static final int TROUBLE_RANGE = 2400;

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

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!isTownsfolk(player, npc)) return;
        Region region = Regions.at(npc.level(), npc.blockPosition());
        PlaceLore.RegionLore lore = PlaceLore.region(region.id());
        out.add(new DialogueOption("place_lore.trouble", Component.translatable("place_lore.skycraft.option.trouble"), 55,
                PlaceLoreDialogue::trouble));
        if (lore != null && (!lore.legends().isEmpty() || !lore.songs().isEmpty())) {
            out.add(new DialogueOption("place_lore.tales", Component.translatable("place_lore.skycraft.option.tales"), 56,
                    PlaceLoreDialogue::tales));
        }
    }

    /** The nearest place with a mini-quest the player can start now. */
    private static Place troublePlace(ServerPlayer player, LivingEntity npc) {
        int x = npc.getBlockX(), z = npc.getBlockZ();
        List<Place> near = new ArrayList<>(Places.overlapping(x - TROUBLE_RANGE, z - TROUBLE_RANGE, x + TROUBLE_RANGE, z + TROUBLE_RANGE));
        near.sort(Comparator.comparingLong(p -> p.distSqr(x, z)));
        for (Place p : near) {
            if (p.distSqr(x, z) > (long) TROUBLE_RANGE * TROUBLE_RANGE) break;
            PlaceLore.Content c = PlaceLore.contentFor(p.id()).orElse(null);
            if (c == null || c.quest().isEmpty()) continue;
            StoryData.QuestDef def = StoryData.quest(c.quest());
            if (def == null || !"manual".equals(def.start) || !Story.available(player, def)) continue;
            return p;
        }
        return null;
    }

    private static void trouble(ServerPlayer player, LivingEntity npc) {
        RandomSource r = player.getRandom();
        Place place = troublePlace(player, npc);
        if (place != null) {
            PlaceLore.Content c = PlaceLore.contentFor(place.id()).orElseThrow();
            StoryData.QuestDef def = StoryData.quest(c.quest());
            if (!Quests.canTakeMore(player)) {
                Dialogue.open(player, npc, Component.translatable("place_lore.skycraft.say.busy", c.rumour().c()));
                return;
            }
            if (def != null && PlaceLoreEvents.startQuest(player, def.id, true)) {
                Discovery.revealPlace(player, place);
                Component pitch = def.pitch.isEmpty() ? c.rumour().c() : def.pitch.c();
                Dialogue.open(player, npc, pitch);
                return;
            }
        }
        Region region = Regions.at(npc.level(), npc.blockPosition());
        Component rumour = PlaceLore.regionalRumour(region.id(), r);
        Dialogue.open(player, npc, rumour != null ? rumour : Component.translatable("place_lore.skycraft.say.quiet." + r.nextInt(3)));
    }

    private static void tales(ServerPlayer player, LivingEntity npc) {
        RandomSource r = player.getRandom();
        PlaceLore.RegionLore lore = PlaceLore.region(Regions.at(npc.level(), npc.blockPosition()).id());
        if (lore == null) {
            Dialogue.open(player, npc, Component.translatable("place_lore.skycraft.say.quiet.0"));
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
            Dialogue.open(player, npc, Component.translatable("place_lore.skycraft.say.song", s.title().c(), verse));
        } else {
            Dialogue.open(player, npc, lore.legends().get(r.nextInt(lore.legends().size())).c());
        }
    }
}
