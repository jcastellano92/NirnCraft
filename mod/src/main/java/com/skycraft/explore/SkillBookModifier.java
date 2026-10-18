package com.skycraft.explore;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.Skycraft;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.List;
import java.util.function.Supplier;

/**
 * Skill books in the wild: Skycraft's own dungeon chests skip the generic chest modifiers, so this one adds a roll
 * of {@code table} (the skill books) to the listed chest tables with the given chance, like the one or two skill
 * books a Skyrim dungeon keeps on a shelf or by its boss.
 *
 * <pre>{ "type": "skycraft:explore_skill_books", "conditions": [], "tables": ["skycraft:chests/dungeon_boss"],
 *   "table": "skycraft:chests/skill_books", "chance": 0.25 }</pre>
 */
public class SkillBookModifier extends LootModifier {
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Skycraft.MODID);

    public static final Supplier<com.mojang.serialization.MapCodec<SkillBookModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst -> codecStart(inst)
            .and(Identifier.CODEC.listOf().fieldOf("tables").forGetter(m -> m.tables))
            .and(Identifier.CODEC.fieldOf("table").forGetter(m -> m.table))
            .and(Codec.FLOAT.optionalFieldOf("chance", 0.1f).forGetter(m -> m.chance))
            .apply(inst, SkillBookModifier::new)));

    public static final Supplier<com.mojang.serialization.MapCodec<SkillBookModifier>> SKILL_BOOKS = SERIALIZERS.register("explore_skill_books", CODEC);

    private final List<Identifier> tables;
    private final Identifier table;
    private final float chance;

    public SkillBookModifier(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, int priority, List<Identifier> tables, Identifier table, float chance) {
        super(conditions, priority);
        this.tables = List.copyOf(tables);
        this.table = table;
        this.chance = chance;
    }

    public static void init(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        Identifier id = context.getQueriedLootTableId();
        if (id == null || !tables.contains(id) || context.getRandom().nextFloat() >= chance) return loot;
        LootTable extra = context.getResolver().lookupOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE).getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table)).value();
        extra.getRandomItemsRaw(context, loot::add);
        return loot;
    }

    @Override
    public com.mojang.serialization.MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
