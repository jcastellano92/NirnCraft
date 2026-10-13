package com.skycraft.dialogue;

import com.skycraft.Skycraft;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Skyrim-style conversations. Right-clicking (not sneaking) a villager, wandering trader or any entity in
 * {@code #skycraft:talkers} opens a dialogue menu. Feature modules contribute topics with
 * {@link #registerProvider(DialogueProvider)} (barter, training, work/quests, factions, bounty...).
 *
 * <p>"Goodbye" (always last) ends the conversation. Only the player an NPC is talking to can pick its lines; the
 * conversation also ends when that player logs out or walks more than 8 blocks away, or the NPC dies/unloads.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Dialogue {
    public static final TagKey<EntityType<?>> TALKERS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "talkers"));
    public static final TagKey<EntityType<?>> NO_DIALOGUE = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "no_dialogue"));

    @FunctionalInterface
    public interface DialogueProvider {
        /** Adds the topics this module offers for {@code npc}. Called server-side every time the menu is built. */
        void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out);
    }

    private static final List<DialogueProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    /** Entity ID -> player UUID currently in conversation with that entity. */
    private static final Map<Integer, UUID> TALKING = new ConcurrentHashMap<>();

    private Dialogue() {}

    public static void registerProvider(DialogueProvider provider) {
        PROVIDERS.add(provider);
    }

    public static boolean canTalk(LivingEntity entity) {
        if (entity.getType().is(NO_DIALOGUE) || !entity.isAlive()) return false;
        return entity instanceof AbstractVillager || entity.getType().is(TALKERS);
    }

    /** Most lines on the main menu (Goodbye and the submenu entries count). Tuning knob. */
    public static final int MAX_VISIBLE = 6;
    /** Most lines on a submenu page. */
    public static final int MAX_SUBMENU = 9;
    private static final int MENU_MAIN = 0, MENU_TALK = 1, MENU_MORE = 2;
    /** Entity ID -> which page of the conversation menu is showing (absent = main). */
    private static final Map<Integer, Integer> MENU = new ConcurrentHashMap<>();
    /** Ids (or id prefixes ending in '.') of generic small talk: kept under "Ask about..." when anything else is on offer. */
    private static final List<String> SMALL_TALK = List.of("society.job", "society.region", "society.news", "society.how",
            "society.favor", "society.gift", "follower.persuade_friend", "place_lore.", "crime.rumor", "survival.rumors");
    /** Different sources, same question: keep the one with the lowest order. */
    private static final Map<String, String> SAME_TOPIC = Map.of("society.favor", "help", "quest.help", "help",
            "society.news", "news", "survival.rumors", "news", "crime.rumor", "news");

    static boolean isSmallTalk(String id) {
        for (String s : SMALL_TALK) {
            if (s.endsWith(".") ? id.startsWith(s) : id.equals(s)) return true;
        }
        return false;
    }

    /** Every option the providers offer, de-duplicated (same id, same topic or same label), sorted, Goodbye last. */
    public static List<DialogueOption> collect(ServerPlayer player, LivingEntity npc) {
        List<DialogueOption> raw = new ArrayList<>();
        if (com.skycraft.fire.aftermath.HelgenFolk.barkOnly(npc)) {
            // the people of the ruined town: one line, Continue and Goodbye; no topics, trades or work
            raw.add(com.skycraft.fire.aftermath.HelgenFolk.continueOption());
        } else {
            for (DialogueProvider p : PROVIDERS) {
                try {
                    p.addOptions(player, npc, raw);
                } catch (Exception e) {
                    Skycraft.LOGGER.error("Dialogue provider failed", e);
                }
            }
            if (com.skycraft.fire.aftermath.HelgenFolk.limited(npc)) {
                raw.removeIf(o -> !com.skycraft.fire.aftermath.HelgenFolk.allowed(npc, o.id()));
            }
        }
        raw.sort(Comparator.comparingInt(DialogueOption::order)); // stable: ties keep provider order
        List<DialogueOption> out = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        Set<String> topics = new java.util.HashSet<>();
        for (DialogueOption o : raw) {
            if (o.id().equals("core.goodbye") || !offers(o, npc) || !ids.add(o.id())) continue;
            String topic = SAME_TOPIC.get(o.id());
            if (topic != null && !topics.add(topic)) continue;
            boolean sameLabel = false;
            for (DialogueOption k : out) {
                if (k.label().equals(o.label()) || k.label().getString().equals(o.label().getString())) {
                    sameLabel = true;
                    break;
                }
            }
            if (!sameLabel) out.add(o);
        }
        out.add(new DialogueOption("core.goodbye", Component.translatable("dialogue.skycraft.goodbye"), 1000, Dialogue::onGoodbye));
        return out;
    }

    /** The lines to show on a menu page: topics first, small talk under "Ask about...", Goodbye last. */
    private static List<DialogueOption> visible(List<DialogueOption> all, int menu, ServerPlayer player, LivingEntity npc) {
        DialogueOption goodbye = all.get(all.size() - 1);
        List<DialogueOption> main = new ArrayList<>();
        List<DialogueOption> talk = new ArrayList<>();
        for (int i = 0; i < all.size() - 1; i++) {
            DialogueOption o = all.get(i);
            (isSmallTalk(o.id()) ? talk : main).add(o);
        }
        // importance and place scale the budget: capitals and posts of note say more than a farmhand in a hamlet
        int tier = player.level() instanceof net.minecraft.server.level.ServerLevel sl
                ? com.skycraft.tamriel.settlement.SettlementPopulation.tierAt(sl, npc.blockPosition()) : 3;
        boolean important = important(npc) || main.size() >= 3;
        int[] topicCap = important ? TOPICS_IMPORTANT : TOPICS_ORDINARY;
        int[] talkCap = important ? TALK_IMPORTANT : TALK_ORDINARY;
        // each person carries their own few small-talk lines (stable per NPC), so neighbours don't all repeat one script
        int salt = npc.getUUID().hashCode();
        talk.sort(Comparator.comparingInt(o -> (o.id().hashCode() * 0x9E3779B1) ^ salt));
        List<DialogueOption> keptTalk = new ArrayList<>();
        for (DialogueOption o : talk) {
            if (o.id().equals("society.gift") || keptTalk.size() < talkCap[tier]) keptTalk.add(o);
        }
        talk = keptTalk;
        if (main.isEmpty()) { // nothing but chit-chat on offer: it IS the menu
            main = talk;
            talk = new ArrayList<>();
        }
        List<DialogueOption> out = new ArrayList<>();
        DialogueOption back = new DialogueOption("core.back", Component.translatable("dialogue.skycraft.menu.back"), 999, Dialogue::onBack);
        if (menu == MENU_TALK && !talk.isEmpty()) {
            out.addAll(talk.subList(0, Math.min(talk.size(), MAX_SUBMENU)));
            out.add(back);
            return out;
        }
        int budget = Math.max(2, topicCap[tier]) - (talk.isEmpty() ? 0 : 1); // minus the "Ask about" entry
        List<DialogueOption> overflow = new ArrayList<>();
        if (main.size() > budget) {
            overflow.addAll(main.subList(budget - 1, main.size())); // one slot goes to "More..."
            main = main.subList(0, budget - 1);
        }
        if (menu == MENU_MORE && !overflow.isEmpty()) {
            out.addAll(overflow.subList(0, Math.min(overflow.size(), MAX_SUBMENU)));
            out.add(back);
            return out;
        }
        out.addAll(main);
        if (!overflow.isEmpty()) out.add(new DialogueOption("core.more", Component.translatable("dialogue.skycraft.menu.more"), 900, Dialogue::onMore));
        if (!talk.isEmpty()) out.add(new DialogueOption("core.ask", Component.translatable("dialogue.skycraft.menu.ask"), 910, Dialogue::onAsk));
        out.add(goodbye);
        return out;
    }

    /** Topic budgets by settlement tier (capital, town, village, wild) for ordinary and important people. Tuning knobs. */
    private static final int[] TOPICS_ORDINARY = {4, 3, 3, 3};
    private static final int[] TOPICS_IMPORTANT = {7, 6, 5, 5};
    /** Small-talk lines kept under "Ask about..." per person. */
    private static final int[] TALK_ORDINARY = {2, 2, 1, 1};
    private static final int[] TALK_IMPORTANT = {5, 4, 3, 3};

    /** Jarls, stewards, innkeepers, priests, court mages, caravans, named quest givers: the people with something to say. */
    private static boolean important(LivingEntity npc) {
        if (npc instanceof com.skycraft.society.entity.NpcEntity n) {
            switch (n.role()) {
                case JARL, STEWARD, HOUSECARL, INNKEEPER, PRIEST, MAGE, CARAVAN_TRADER -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return !com.skycraft.quest.story.QuestNpcs.idOf(npc).isEmpty() || com.skycraft.survival.inn.Innkeepers.isInnkeeper(npc)
                || npc.getPersistentData().getBoolean("skycraft_steward");
    }

    /** Ordinary folk only offer to put you to work now and then (about one in three), so quests mean something. */
    private static boolean offers(DialogueOption o, LivingEntity npc) {
        if (!o.id().equals("quest.help") && !o.id().equals("society.favor")) return true;
        return important(npc) || Math.floorMod(npc.getUUID().hashCode(), 3) == 0;
    }

    private static void onAsk(ServerPlayer player, LivingEntity npc) {
        sendMenu(player, npc, Component.translatable("dialogue.skycraft.menu.prompt"), MENU_TALK);
    }

    private static void onMore(ServerPlayer player, LivingEntity npc) {
        sendMenu(player, npc, Component.translatable("dialogue.skycraft.menu.prompt"), MENU_MORE);
    }

    private static void onBack(ServerPlayer player, LivingEntity npc) {
        sendMenu(player, npc, Component.translatable("dialogue.skycraft.menu.prompt"), MENU_MAIN);
    }

    private static void sendMenu(ServerPlayer player, LivingEntity npc, Component greeting, int menu) {
        if (menu == MENU_MAIN) MENU.remove(npc.getId());
        else MENU.put(npc.getId(), menu);
        List<DialoguePackets.Line> lines = new ArrayList<>();
        for (DialogueOption o : visible(collect(player, npc), menu, player, npc)) lines.add(new DialoguePackets.Line(o.id(), o.label()));
        SkyNetwork.sendToPlayer(player, new DialoguePackets.OpenDialogue(npc.getId(), npc.getDisplayName(), greeting, lines));
    }

    private static void onGoodbye(ServerPlayer player, LivingEntity npc) {
        endConversation(player, npc.getId());
    }

    /** Opens (or refreshes) the dialogue menu for {@code npc}, with an optional line spoken by the NPC. */
    public static void open(ServerPlayer player, LivingEntity npc, Component greeting) {
        UUID partner = TALKING.get(npc.getId());
        if (partner != null && !partner.equals(player.getUUID())) {
            ServerPlayer currentPartner = player.server.getPlayerList().getPlayer(partner);
            if (currentPartner != null && currentPartner.isAlive() && currentPartner.distanceToSqr(npc) <= 64) {
                com.skycraft.core.Notifier.message(player, Component.translatable("society.skycraft.busy"));
                return;
            }
        }
        TALKING.put(npc.getId(), player.getUUID());
        if (npc instanceof com.skycraft.society.entity.NpcEntity n) {
            n.setConversationPartner(player.getId());
            n.getNavigation().stop();
            n.getLookControl().setLookAt(player, 30.0F, 30.0F);
        }
        sendMenu(player, npc, greeting, MENU_MAIN);
    }

    public static void endConversation(ServerPlayer player, int entityId) {
        UUID partner = TALKING.get(entityId);
        if (partner != null && partner.equals(player.getUUID())) {
            TALKING.remove(entityId);
            MENU.remove(entityId);
            if (player.level().getEntity(entityId) instanceof com.skycraft.society.entity.NpcEntity n) {
                n.setConversationPartner(-1);
            }
        }
    }

    static void choose(ServerPlayer player, int entityId, String optionId) {
        // only the player this NPC is actually talking to may pick a line
        if (!player.getUUID().equals(TALKING.get(entityId))) return;
        if (!(player.level().getEntity(entityId) instanceof LivingEntity npc) || !npc.isAlive() || player.distanceToSqr(npc) > 64) {
            endConversation(player, entityId);
            return;
        }
        for (DialogueOption option : visible(collect(player, npc), MENU.getOrDefault(entityId, MENU_MAIN), player, npc)) {
            if (option.id().equals(optionId)) {
                option.action().accept(player, npc);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ cleanup

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        TALKING.entrySet().removeIf(e -> {
            if (!e.getValue().equals(id)) return false;
            if (event.getEntity().level().getEntity(e.getKey()) instanceof com.skycraft.society.entity.NpcEntity n) n.setConversationPartner(-1);
            return true;
        });
    }

    /** NPC died, unloaded or changed dimension: whoever talked to it is done. */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide) {
            TALKING.remove(event.getEntity().getId());
            MENU.remove(event.getEntity().getId());
        }
    }

    /** Once a second: conversations whose player walked away (> 8 blocks) or is gone end. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || TALKING.isEmpty() || event.getServer().getTickCount() % 20 != 0) return;
        var players = event.getServer().getPlayerList();
        TALKING.entrySet().removeIf(e -> {
            ServerPlayer p = players.getPlayer(e.getValue());
            if (p == null) return true;
            var entity = p.level().getEntity(e.getKey());
            if (entity != null && entity.isAlive() && p.isAlive() && p.distanceToSqr(entity) <= 64) return false;
            if (entity instanceof com.skycraft.society.entity.NpcEntity n && n.getConversationPartner() == p.getId()) n.setConversationPartner(-1);
            return true;
        });
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof LivingEntity npc) || !canTalk(npc)) return;
        if (event.getEntity().isShiftKeyDown()) return; // sneaking is reserved for pickpocketing
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID current = TALKING.get(npc.getId());
            if (current != null && !current.equals(player.getUUID())) {
                ServerPlayer sp = player.server.getPlayerList().getPlayer(current);
                if (sp != null && sp.isAlive() && sp.distanceToSqr(npc) <= 64) {
                    com.skycraft.core.Notifier.message(player, Component.translatable("society.skycraft.busy"));
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                }
            }
            open(player, npc, com.skycraft.fire.aftermath.HelgenFolk.barkOnly(npc)
                    ? com.skycraft.fire.aftermath.HelgenFolk.line(player, npc) : Component.empty());
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
