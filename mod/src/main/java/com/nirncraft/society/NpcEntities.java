package com.nirncraft.society;

import com.nirncraft.NirnCraft;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/**
 * Entity types of the society module. {@code nirncraft:npc} (contract 22) holds the civilian roles; the soldiers and
 * villains use {@code nirncraft:npc_fighter} (same class) so the crime module never counts their deaths as murders.
 */
public final class NpcEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NirnCraft.MODID);

    public static final Supplier<EntityType<NpcEntity>> NPC = ENTITIES.register("npc",
            () -> EntityType.Builder.<NpcEntity>of(NpcEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f).clientTrackingRange(10).build(net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "npc"))));
    public static final Supplier<EntityType<NpcEntity>> NPC_FIGHTER = ENTITIES.register("npc_fighter",
            () -> EntityType.Builder.<NpcEntity>of(NpcEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f).clientTrackingRange(10).build(net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "npc_fighter"))));

    public static final Supplier<Item> NPC_EGG = ITEMS.register("npc_spawn_egg",
            key -> new net.minecraft.world.item.SpawnEggItem(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(Registries.ITEM, key)).spawnEgg(NPC.get())));
    public static final Supplier<Item> NPC_FIGHTER_EGG = ITEMS.register("npc_fighter_spawn_egg",
            key -> new net.minecraft.world.item.SpawnEggItem(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(Registries.ITEM, key)).spawnEgg(NPC_FIGHTER.get())));

    /** Society NPCs that talk (civilians). Meant to be included in {@code #nirncraft:talkers}. */
    public static final TagKey<EntityType<?>> SOCIETY_TALKERS = TagKey.create(Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(NirnCraft.MODID, "society_talkers"));
    /** Animals hunters go after. */
    public static final TagKey<EntityType<?>> GAME = TagKey.create(Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(NirnCraft.MODID, "society_game"));

    private NpcEntities() {}

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }

    public static EntityType<NpcEntity> typeFor(NpcRole role) {
        return role.civilian ? NPC.get() : NPC_FIGHTER.get();
    }
}
