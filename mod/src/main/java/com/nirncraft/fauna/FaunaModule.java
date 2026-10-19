package com.nirncraft.fauna;

import com.nirncraft.fauna.entity.BearEntity;
import com.nirncraft.fauna.entity.DeerEntity;
import com.nirncraft.fauna.entity.ElkEntity;
import com.nirncraft.fauna.entity.HorkerEntity;
import com.nirncraft.fauna.entity.InsectEntity;
import com.nirncraft.fauna.entity.MammothEntity;
import com.nirncraft.fauna.entity.MudcrabEntity;
import com.nirncraft.fauna.entity.SabreCatEntity;
import com.nirncraft.fauna.entity.SlaughterfishEntity;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.IEventBus;

/**
 * Skyrim wildlife, livestock, catchable insects and regrowing alchemy plants.
 *
 * <ul>
 *     <li>{@link FaunaEntities}: deer, elk, sabre cats, horkers, mudcrabs, mammoths, bears, slaughterfish and the
 *         insects (butterfly, dragonfly, torchbug, moth), plus spawn eggs; {@link FaunaItems}: animal parts and
 *         insect ingredients.</li>
 *     <li>{@link FaunaSpawns}: spawn placement rules (biome lists: {@code forge/biome_modifier/fauna_*.json});
 *         {@link InsectSpawner}: per-player ambient insect spawning.</li>
 *     <li>{@link Livestock}: village-owned farm animals ({@code Livestock.isOwned(entity)}) for the crime module.</li>
 *     <li>{@link FaunaEvents}: giants' mammoth herds, Hunting XP for slaughterfish, harvested-plant breaking. Plant
 *         harvesting and regrowth itself lives in arcane's {@code IngredientPlantBlock}.</li>
 *     <li>{@link FaunaTags#BEASTS}: {@code #nirncraft:beasts}.</li>
 *     <li>{@code fauna.client}: models and renderers.</li>
 * </ul>
 */
public final class FaunaModule {
    private FaunaModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        FaunaEntities.init(modBus);
        FaunaItems.init(modBus);
        modBus.addListener(FaunaModule::onAttributes);
        modBus.addListener(FaunaSpawns::register);
        Stables.register();
        com.nirncraft.mounts.MountsModule.init(modBus);
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        FaunaPackets.register();
        com.nirncraft.mounts.MountsModule.registerPackets();
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(FaunaEntities.DEER.get(), DeerEntity.createAttributes().build());
        event.put(FaunaEntities.ELK.get(), ElkEntity.createAttributes().build());
        event.put(FaunaEntities.SABRE_CAT.get(), SabreCatEntity.createAttributes().build());
        event.put(FaunaEntities.HORKER.get(), HorkerEntity.createAttributes().build());
        event.put(FaunaEntities.MUDCRAB.get(), MudcrabEntity.createAttributes().build());
        event.put(FaunaEntities.MAMMOTH.get(), MammothEntity.createAttributes().build());
        event.put(FaunaEntities.BEAR.get(), BearEntity.createAttributes().build());
        event.put(FaunaEntities.SLAUGHTERFISH.get(), SlaughterfishEntity.createAttributes().build());
        event.put(FaunaEntities.BUTTERFLY.get(), InsectEntity.createAttributes().build());
        event.put(FaunaEntities.DRAGONFLY.get(), InsectEntity.createAttributes().build());
        event.put(FaunaEntities.TORCHBUG.get(), InsectEntity.createAttributes().build());
        event.put(FaunaEntities.MOTH.get(), InsectEntity.createAttributes().build());
    }
}
