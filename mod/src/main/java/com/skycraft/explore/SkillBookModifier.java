package com.skycraft.explore;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.Skycraft;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

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
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Skycraft.MODID);

    public static final Supplier<Codec<SkillBookModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.create(inst -> codecStart(inst)
            .and(ResourceLocation.CODEC.listOf().fieldOf("tables").forGetter(m -> m.tables))
            .and(ResourceLocation.CODEC.fieldOf("table").forGetter(m -> m.table))
            .and(Codec.FLOAT.optionalFieldOf("chance", 0.1f).forGetter(m -> m.chance))
            .apply(inst, SkillBookModifier::new)));

    public static final RegistryObject<Codec<SkillBookModifier>> SKILL_BOOKS = SERIALIZERS.register("explore_skill_books", CODEC);

    private final List<ResourceLocation> tables;
    private final ResourceLocation table;
    private final float chance;

    public SkillBookModifier(LootItemCondition[] conditions, List<ResourceLocation> tables, ResourceLocation table, float chance) {
        super(conditions);
        this.tables = List.copyOf(tables);
        this.table = table;
        this.chance = chance;
    }

    public static void init(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        ResourceLocation id = context.getQueriedLootTableId();
        if (id == null || !tables.contains(id) || context.getRandom().nextFloat() >= chance) return loot;
        LootTable extra = context.getResolver().getLootTable(table);
        extra.getRandomItemsRaw(context, loot::add);
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
