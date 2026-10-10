package com.skycraft.magic;

import com.skycraft.Skycraft;
import com.skycraft.magic.bound.BoundBattleaxeItem;
import com.skycraft.magic.bound.BoundBowItem;
import com.skycraft.magic.bound.BoundSwordItem;
import com.skycraft.magic.spell.MagicEffect;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellProjectile;
import com.skycraft.magic.spell.SpellTomeItem;
import com.skycraft.magic.spell.Spells;
import com.skycraft.magic.wordwall.WordWallBlock;
import com.skycraft.magic.wordwall.WordWallBlockEntity;
import com.skycraft.magic.wordwall.WordWallShrineFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every registry entry of the magic module: tomes, bound weapons, word walls, the spell projectile, effects, worldgen. */
public final class MagicRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Skycraft.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Skycraft.MODID);
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Skycraft.MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);

    // ------------------------------------------------------------------ tags

    public static final TagKey<Item> SPELL_TOMES = TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "spell_tomes"));
    /** Filled by the creatures module. Killing one of these lets nearby players absorb its soul. */
    public static final TagKey<EntityType<?>> DRAGONS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "dragons"));

    // ------------------------------------------------------------------ items

    /** spell id -> tome item. */
    public static final Map<String, RegistryObject<Item>> TOMES = new LinkedHashMap<>();

    static {
        for (Spell spell : Spells.all()) {
            Rarity rarity = switch (spell.tier) {
                case NOVICE, APPRENTICE -> Rarity.COMMON;
                case ADEPT -> Rarity.UNCOMMON;
                case EXPERT -> Rarity.RARE;
                case MASTER -> Rarity.EPIC;
            };
            TOMES.put(spell.id, ITEMS.register("spell_tome_" + spell.id,
                    () -> new SpellTomeItem(spell.id, new Item.Properties().stacksTo(16).rarity(rarity))));
        }
    }

    public static final RegistryObject<Item> BOUND_SWORD = ITEMS.register("bound_sword", () -> new BoundSwordItem(new Item.Properties()));
    public static final RegistryObject<Item> BOUND_BATTLEAXE = ITEMS.register("bound_battleaxe", () -> new BoundBattleaxeItem(new Item.Properties()));
    public static final RegistryObject<Item> BOUND_BOW = ITEMS.register("bound_bow", () -> new BoundBowItem(new Item.Properties()));
    public static final RegistryObject<Item> BOUND_DAGGER = ITEMS.register("bound_dagger", () -> new com.skycraft.magic.bound.BoundDaggerItem(new Item.Properties()));

    /** Spellmaking Altar (Oblivion/Morrowind): combine known magic effects into new spells. */
    public static final RegistryObject<Block> SPELLMAKING_ALTAR = BLOCKS.register("spellmaking_altar",
            () -> new com.skycraft.magic.spell.spellmaking.SpellmakingAltarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.5f, 9f)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 7)
                    .noOcclusion()
                    .sound(SoundType.STONE)));
    public static final RegistryObject<Item> SPELLMAKING_ALTAR_ITEM = ITEMS.register("spellmaking_altar",
            () -> new BlockItem(SPELLMAKING_ALTAR.get(), new Item.Properties().rarity(Rarity.UNCOMMON)));

    // ------------------------------------------------------------------ blocks

    public static final RegistryObject<Block> WORD_WALL = BLOCKS.register("word_wall", () -> new WordWallBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(50f, 1200f)
            .lightLevel(state -> 6)
            .sound(SoundType.STONE)));
    public static final RegistryObject<Item> WORD_WALL_ITEM = ITEMS.register("word_wall",
            () -> new BlockItem(WORD_WALL.get(), new Item.Properties().rarity(Rarity.RARE)));

    public static final RegistryObject<BlockEntityType<WordWallBlockEntity>> WORD_WALL_BE = BLOCK_ENTITIES.register("word_wall",
            () -> BlockEntityType.Builder.of(WordWallBlockEntity::new, WORD_WALL.get()).build(null));

    // ------------------------------------------------------------------ entities

    public static final RegistryObject<EntityType<SpellProjectile>> SPELL_PROJECTILE = ENTITIES.register("spell_projectile",
            () -> EntityType.Builder.<SpellProjectile>of(SpellProjectile::new, MobCategory.MISC)
                    .sized(0.4f, 0.4f)
                    .clientTrackingRange(6)
                    .updateInterval(4)
                    .build(new ResourceLocation(Skycraft.MODID, "spell_projectile").toString()));

    // ------------------------------------------------------------------ effects

    /** Creatures that die under this effect have their soul captured by the caster's soul gems. */
    public static final RegistryObject<MobEffect> SOUL_TRAP = EFFECTS.register("soul_trap",
            () -> new MagicEffect(MobEffectCategory.HARMFUL, 0x8A4FD8));
    /** Armor spells: +1 armor point per amplifier level (amplifier = points - 1). */
    public static final RegistryObject<MobEffect> OAKFLESH = EFFECTS.register("oakflesh", () -> armorEffect(0x9A7040, "5a1c2e10-41b7-4d1f-8d0e-6c7b9a10f001"));
    public static final RegistryObject<MobEffect> STONEFLESH = EFFECTS.register("stoneflesh", () -> armorEffect(0x9A9A9A, "5a1c2e10-41b7-4d1f-8d0e-6c7b9a10f002"));
    public static final RegistryObject<MobEffect> IRONFLESH = EFFECTS.register("ironflesh", () -> armorEffect(0xC8C8D0, "5a1c2e10-41b7-4d1f-8d0e-6c7b9a10f003"));
    public static final RegistryObject<MobEffect> EBONYFLESH = EFFECTS.register("ebonyflesh", () -> armorEffect(0x3A2A4A, "5a1c2e10-41b7-4d1f-8d0e-6c7b9a10f004"));
    /** Become Ethereal: can't be harmed, can't harm. */
    public static final RegistryObject<MobEffect> ETHEREAL = EFFECTS.register("ethereal",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0xA8E8FF));
    /** Muffle: you are much harder to notice. */
    public static final RegistryObject<MobEffect> MUFFLE = EFFECTS.register("muffle",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0x6A7AA0));
    /** Calmed creatures won't fight. */
    public static final RegistryObject<MobEffect> CALM = EFFECTS.register("calm",
            () -> new MagicEffect(MobEffectCategory.NEUTRAL, 0x9AD0F0));
    /** Feared creatures flee from the caster. */
    public static final RegistryObject<MobEffect> FEAR = EFFECTS.register("feared",
            () -> new MagicEffect(MobEffectCategory.HARMFUL, 0x404060));
    /** Frenzied creatures attack anything nearby. */
    public static final RegistryObject<MobEffect> FRENZY = EFFECTS.register("frenzied",
            () -> new MagicEffect(MobEffectCategory.HARMFUL, 0xD02020));
    /** Marked for Death: armor reduced by 2 per level and health slowly drains. */
    public static final RegistryObject<MobEffect> MARKED_FOR_DEATH = EFFECTS.register("marked_for_death",
            () -> new MagicEffect(MobEffectCategory.HARMFUL, 0x5A0A0A)
                    .addAttributeModifier(Attributes.ARMOR, "5a1c2e10-41b7-4d1f-8d0e-6c7b9a10f010", -2.0, AttributeModifier.Operation.ADDITION));

    /** Dragonhide: 80% of physical damage is ignored. */
    public static final RegistryObject<MobEffect> DRAGONHIDE = EFFECTS.register("dragonhide",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0x6A8A5A));
    /** Reflect Spell: (amplifier + 1) * 5 percent chance to send a spell back at its caster. */
    public static final RegistryObject<MobEffect> REFLECT_SPELL = EFFECTS.register("reflect_spell",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0xC8E8F0));
    /** Spell Absorption: (amplifier + 1) * 5 percent chance to absorb a spell as magicka. */
    public static final RegistryObject<MobEffect> SPELL_ABSORPTION = EFFECTS.register("spell_absorption",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0x4A7BD8));
    /** Levitate: limited flight (Mysticism keeps the player's flying ability in sync with it). */
    public static final RegistryObject<MobEffect> LEVITATE = EFFECTS.register("levitate",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0xE8F0FF));
    /** Water Walking: water holds you up. */
    public static final RegistryObject<MobEffect> WATER_WALKING = EFFECTS.register("water_walking",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0x5AA8E0));
    public static final RegistryObject<MobEffect> FLAME_CLOAK = EFFECTS.register("flame_cloak",
            () -> new com.skycraft.magic.spell.CloakEffect(0xFF7A2A, com.skycraft.magic.spell.Element.FIRE));
    public static final RegistryObject<MobEffect> FROST_CLOAK = EFFECTS.register("frost_cloak",
            () -> new com.skycraft.magic.spell.CloakEffect(0xA8E0FF, com.skycraft.magic.spell.Element.FROST));
    public static final RegistryObject<MobEffect> LIGHTNING_CLOAK = EFFECTS.register("lightning_cloak",
            () -> new com.skycraft.magic.spell.CloakEffect(0xC8C8FF, com.skycraft.magic.spell.Element.SHOCK));
    public static final RegistryObject<MobEffect> STENDARRS_AURA = EFFECTS.register("stendarrs_aura",
            () -> new com.skycraft.magic.spell.CloakEffect(0xFFE08A, com.skycraft.magic.spell.Element.HOLY));
    /** Ash Shell: encased in ash, can't act and can't be harmed. */
    public static final RegistryObject<MobEffect> ASH_SHELL = EFFECTS.register("ash_shell",
            () -> new MagicEffect(MobEffectCategory.HARMFUL, 0x5A5550));
    /** Slow Time: the creature lives at a fraction of normal speed (ticks are skipped). Amplifier = words - 1. */
    public static final RegistryObject<MobEffect> TIME_SLOWED = EFFECTS.register("time_slowed",
            () -> new MagicEffect(MobEffectCategory.HARMFUL, 0x8A9AD0));
    /** Dragon Aspect: armor, stronger shouts, and damage resistance. */
    public static final RegistryObject<MobEffect> DRAGON_ASPECT = EFFECTS.register("dragon_aspect",
            () -> new MagicEffect(MobEffectCategory.BENEFICIAL, 0xD8A040)
                    .addAttributeModifier(Attributes.ARMOR, "5a1c2e10-41b7-4d1f-8d0e-6c7b9a10f011", 3.0, AttributeModifier.Operation.ADDITION)
                    .addAttributeModifier(Attributes.ATTACK_DAMAGE, "5a1c2e10-41b7-4d1f-8d0e-6c7b9a10f012", 1.0, AttributeModifier.Operation.ADDITION));
    /** Ice Form: frozen solid. */
    public static final RegistryObject<MobEffect> ICE_FORM = EFFECTS.register("ice_form",
            () -> new MagicEffect(MobEffectCategory.HARMFUL, 0xB8E8FF));

    private static MobEffect armorEffect(int color, String uuid) {
        return new MagicEffect(MobEffectCategory.BENEFICIAL, color).addAttributeModifier(Attributes.ARMOR, uuid, 1.0, AttributeModifier.Operation.ADDITION);
    }

    // ------------------------------------------------------------------ worldgen

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> WORD_WALL_SHRINE = FEATURES.register("word_wall_shrine",
            () -> new WordWallShrineFeature(NoneFeatureConfiguration.CODEC));

    private MagicRegistry() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        EFFECTS.register(modBus);
        FEATURES.register(modBus);
    }
}
