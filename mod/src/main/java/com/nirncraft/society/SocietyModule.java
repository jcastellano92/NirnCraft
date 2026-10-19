package com.nirncraft.society;

import com.nirncraft.society.entity.NpcEntity;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * NPC types, overhead dialogue barks, reputation, random encounters, faction cosmetics and PvP rules.
 *
 * <ul>
 *     <li>{@link NpcEntities}: {@code nirncraft:npc} (civilians, contract 22) and {@code nirncraft:npc_fighter} (soldiers,
 *     villains) with {@link NpcRole roles}; {@link Npcs} is the public spawn API, {@link NpcDialogue} their topics,
 *     {@link Settlers} gives every settlement its townsfolk, Jarl and housecarl.</li>
 *     <li>The people of Nirn (replacing third-party villager mods): {@link Cultures} (who lives where), {@link NpcNames}
 *     (per-race names), {@link NpcLook} and {@link Outfit} (appearance, composited client-side by
 *     {@code client.NpcSkins} with {@code client.RaceFeatureLayer}), {@link Schedule} and {@link Workplaces} (daily
 *     routines), {@link Chatter} (NPC-to-NPC talk, bard songs), {@link Relationships} and {@link Marriage},
 *     {@link NpcRegistry} (unique named NPCs), {@link VillagerConversion} (vanilla villagers become townsfolk).</li>
 *     <li>{@link Barks}: overhead speech (contract 22) and ambient lines from villagers, guards and NPCs.</li>
 *     <li>{@link Reputation} (contract 23) and {@link ReputationEvents}; {@link HitSquads} for hated players.</li>
 *     <li>{@link Encounters}: random wilderness encounters; {@link Letters} for couriers.</li>
 *     <li>{@link Cosmetics}: faction regalia drawn over player skins (client layer in {@code society.client}).</li>
 *     <li>{@link PvpRules}: witnessed PvP is assault/murder.</li>
 *     <li>{@code /nirncraft-society}: {@link SocietyCommands}.</li>
 * </ul>
 */
public final class SocietyModule {
    private SocietyModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        // Hearthfire homes find the player's spouse for Lover's Comfort and family life
        com.nirncraft.homestead.Homesteads.setSpouseLocator((player, bed, radius) -> {
            java.util.UUID spouse = Marriage.spouseOf(player);
            if (spouse == null) return null;
            net.minecraft.world.entity.Entity e = player.level().getEntity(spouse);
            return e != null && e.distanceToSqr(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5) <= radius * radius ? e : null;
        });
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, SocietyConfig.SPEC, "nirncraft-society.toml");
        NpcEntities.init(modBus);
        modBus.addListener(SocietyModule::onAttributes);
        NpcDialogue.register();
        Followers.register();
        SocietySelfTest.registerAutorun();
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        SocietyPackets.register();
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(NpcEntities.NPC.get(), NpcEntity.createAttributes().build());
        event.put(NpcEntities.NPC_FIGHTER.get(), NpcEntity.createAttributes().build());
    }
}
