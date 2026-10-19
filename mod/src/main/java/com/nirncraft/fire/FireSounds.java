package com.nirncraft.fire;

import com.nirncraft.NirnCraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/**
 * Sound events of the fire module: the Helgen aftermath score and the quiet ambience of a burnt town (fire crackle,
 * timber creaks and collapses, sobbing, distant keening). Files and subtitles come from
 * {@code tools/music/sound_catalog.py}; every name here must exist there ({@code --check}).
 */
public final class FireSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, NirnCraft.MODID);

    /** The mournful, tense piece that plays inside the footprint of a place that has just been attacked. */
    public static final Supplier<SoundEvent> MUSIC_HELGEN_AFTERMATH = register("music.helgen_aftermath");
    public static final Supplier<SoundEvent> FIRE_CRACKLE = register("ambient.fire_crackle");
    public static final Supplier<SoundEvent> FIRE_ROAR = register("ambient.fire_roar");
    public static final Supplier<SoundEvent> TIMBER_CREAK = register("ambient.timber_creak");
    public static final Supplier<SoundEvent> TIMBER_COLLAPSE = register("ambient.timber_collapse");
    public static final Supplier<SoundEvent> SOBBING = register("ambient.sobbing");
    public static final Supplier<SoundEvent> MOURNING = register("ambient.mourning");

    private FireSounds() {}

    private static Supplier<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(NirnCraft.MODID, name)));
    }

    static void init(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
