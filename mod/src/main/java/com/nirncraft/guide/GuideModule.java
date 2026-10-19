package com.nirncraft.guide;

import com.nirncraft.NirnCraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/**
 * Onboarding: the Traveler's Handbook and the tutorial hints.
 *
 * <ul>
 *     <li>{@link HandbookItem} ({@code nirncraft:travelers_handbook}): given once on first join ({@link GuideEvents});
 *     using it opens {@code client.HandbookScreen}. The chapters are lang text ({@code guide.nirncraft.chapter.*}), so
 *     there is nothing to sync.</li>
 *     <li>{@code client.TutorialHints}: small one-time hints in the top-left corner, detected on the client from
 *     state every module already syncs. Seen hints are kept in {@code nirncraft-guide-client.toml}.</li>
 *     <li>Client command {@code /handbook} (and {@code /handbook hints reset|off|on}) works on any server.</li>
 * </ul>
 */
public final class GuideModule {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);

    public static final Supplier<Item> HANDBOOK = ITEMS.register("travelers_handbook",
            () -> new HandbookItem(com.nirncraft.platform.SkyProperties.item("travelers_handbook").stacksTo(1).rarity(Rarity.UNCOMMON)));

    private GuideModule() {}

    /** Registers this module's DeferredRegisters and configs. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, GuideConfig.COMMON_SPEC, "nirncraft-guide-common.toml");
        com.nirncraft.NirnCraft.config(ModConfig.Type.CLIENT, GuideConfig.CLIENT_SPEC, "nirncraft-guide-client.toml");
    }
}
