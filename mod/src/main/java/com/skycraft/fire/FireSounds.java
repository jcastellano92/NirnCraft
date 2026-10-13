package com.skycraft.fire;

import com.skycraft.Skycraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Sound events of the fire module: the Helgen aftermath score and the quiet ambience of a burnt town (fire crackle,
 * timber creaks and collapses, sobbing, distant keening). Files and subtitles come from
 * {@code tools/music/sound_catalog.py}; every name here must exist there ({@code --check}).
 */
public final class FireSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Skycraft.MODID);

    /** The mournful, tense piece that plays inside the footprint of a place that has just been attacked. */
    public static final RegistryObject<SoundEvent> MUSIC_HELGEN_AFTERMATH = register("music.helgen_aftermath");
    public static final RegistryObject<SoundEvent> FIRE_CRACKLE = register("ambient.fire_crackle");
    public static final RegistryObject<SoundEvent> FIRE_ROAR = register("ambient.fire_roar");
    public static final RegistryObject<SoundEvent> TIMBER_CREAK = register("ambient.timber_creak");
    public static final RegistryObject<SoundEvent> TIMBER_COLLAPSE = register("ambient.timber_collapse");
    public static final RegistryObject<SoundEvent> SOBBING = register("ambient.sobbing");
    public static final RegistryObject<SoundEvent> MOURNING = register("ambient.mourning");

    private FireSounds() {}

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(Skycraft.MODID, name)));
    }

    static void init(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
