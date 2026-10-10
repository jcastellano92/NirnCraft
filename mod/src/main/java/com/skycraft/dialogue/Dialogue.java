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

    public static List<DialogueOption> collect(ServerPlayer player, LivingEntity npc) {
        List<DialogueOption> out = new ArrayList<>();
        for (DialogueProvider p : PROVIDERS) {
            try {
                p.addOptions(player, npc, out);
            } catch (Exception e) {
                Skycraft.LOGGER.error("Dialogue provider failed", e);
            }
        }
        out.add(new DialogueOption("core.goodbye", Component.translatable("dialogue.skycraft.goodbye"), 1000, Dialogue::onGoodbye));
        out.sort(Comparator.comparingInt(DialogueOption::order));
        return out;
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
        List<DialogueOption> options = collect(player, npc);
        List<DialoguePackets.Line> lines = new ArrayList<>();
        for (DialogueOption o : options) lines.add(new DialoguePackets.Line(o.id(), o.label()));
        SkyNetwork.sendToPlayer(player, new DialoguePackets.OpenDialogue(npc.getId(), npc.getDisplayName(), greeting, lines));
    }

    public static void endConversation(ServerPlayer player, int entityId) {
        UUID partner = TALKING.get(entityId);
        if (partner != null && partner.equals(player.getUUID())) {
            TALKING.remove(entityId);
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
        for (DialogueOption option : collect(player, npc)) {
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
        if (!event.getLevel().isClientSide) TALKING.remove(event.getEntity().getId());
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
            open(player, npc, Component.empty());
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
