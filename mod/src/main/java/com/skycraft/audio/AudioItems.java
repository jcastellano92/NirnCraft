package com.skycraft.audio;

import com.skycraft.Skycraft;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/**
 * The ten regional and cultural musical instruments of Tamriel, carried by travelling and tavern bards and playable
 * by adventurers.
 */
public final class AudioItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Skycraft.MODID);

    // Modal scales in semitones above the root (12 = 1.0x pitch)
    public static final int[] SCALE_DORIAN = {0, 2, 3, 5, 7, 9, 10, 12, 14, 15, 17, 19, 21, 24};
    public static final int[] SCALE_PASTORAL = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};
    public static final int[] SCALE_PERCUSSIVE = {0, 3, 5, 7, 10, 12, 15, 17, 19, 22, 24};
    public static final int[] SCALE_NORDIC_FIFTHS = {0, 5, 7, 10, 12, 14, 17, 19, 24};
    public static final int[] SCALE_VELOTHI_PHRYGIAN = {0, 1, 3, 5, 7, 8, 10, 12, 13, 15, 17, 19};
    public static final int[] SCALE_HIJAZ = {0, 1, 4, 5, 7, 8, 11, 12, 13, 16, 17, 19};
    public static final int[] SCALE_ALTMER_LYDIAN = {0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 23, 24};
    public static final int[] SCALE_BOSMER_PENTATONIC = {2, 4, 7, 9, 11, 14, 16, 19, 21, 23, 24};
    public static final int[] SCALE_KHAJIIT_RAGA = {0, 2, 3, 6, 7, 9, 10, 12, 14, 15, 18, 19, 21};
    public static final int[] SCALE_HIST_REED = {0, 3, 5, 7, 8, 12, 15, 17, 19, 20, 24};

    public static final Supplier<InstrumentItem> LUTE = ITEMS.register("lute",
            () -> new InstrumentItem(props("lute", Rarity.COMMON), "imperial", InstrumentItem.Pose.STRUM,
                    () -> SoundEvents.NOTE_BLOCK_GUITAR,
                    () -> SoundEvents.NOTE_BLOCK_HARP,
                    1.0f, SCALE_DORIAN));

    public static final Supplier<InstrumentItem> WOODEN_FLUTE = ITEMS.register("wooden_flute",
            () -> new InstrumentItem(props("wooden_flute", Rarity.COMMON), "breton", InstrumentItem.Pose.FLUTE,
                    () -> SoundEvents.NOTE_BLOCK_FLUTE,
                    null,
                    1.0f, SCALE_PASTORAL));

    public static final Supplier<InstrumentItem> FRAME_DRUM = ITEMS.register("frame_drum",
            () -> new InstrumentItem(props("frame_drum", Rarity.COMMON), "orc", InstrumentItem.Pose.DRUM,
                    () -> SoundEvents.NOTE_BLOCK_BASEDRUM,
                    () -> SoundEvents.NOTE_BLOCK_SNARE,
                    1.0f, SCALE_PERCUSSIVE));

    public static final Supplier<InstrumentItem> NORD_WAR_HORN = ITEMS.register("nord_war_horn",
            () -> new InstrumentItem(props("nord_war_horn", Rarity.UNCOMMON), "nord", InstrumentItem.Pose.HORN,
                    () -> SoundEvents.NOTE_BLOCK_FLUTE,
                    AudioItems::goatHornCall,
                    0.68f, SCALE_NORDIC_FIFTHS));

    public static final Supplier<InstrumentItem> VELOTHI_ASH_FLUTE = ITEMS.register("velothi_ash_flute",
            () -> new InstrumentItem(props("velothi_ash_flute", Rarity.UNCOMMON), "dunmer", InstrumentItem.Pose.FLUTE,
                    () -> SoundEvents.NOTE_BLOCK_FLUTE,
                    () -> SoundEvents.NOTE_BLOCK_DIDGERIDOO,
                    0.78f, SCALE_VELOTHI_PHRYGIAN));

    public static final Supplier<InstrumentItem> REDGUARD_OUD = ITEMS.register("redguard_oud",
            () -> new InstrumentItem(props("redguard_oud", Rarity.UNCOMMON), "redguard", InstrumentItem.Pose.STRUM,
                    () -> SoundEvents.NOTE_BLOCK_GUITAR,
                    () -> SoundEvents.NOTE_BLOCK_HARP,
                    0.94f, SCALE_HIJAZ));

    public static final Supplier<InstrumentItem> ALTMER_CRYSTAL_HARP = ITEMS.register("altmer_crystal_harp",
            () -> new InstrumentItem(props("altmer_crystal_harp", Rarity.RARE), "altmer", InstrumentItem.Pose.HARP,
                    () -> SoundEvents.NOTE_BLOCK_CHIME,
                    () -> Holder.direct(SoundEvents.AMETHYST_BLOCK_CHIME),
                    1.12f, SCALE_ALTMER_LYDIAN));

    public static final Supplier<InstrumentItem> BOSMER_BONE_PIPE = ITEMS.register("bosmer_bone_pipe",
            () -> new InstrumentItem(props("bosmer_bone_pipe", Rarity.UNCOMMON), "bosmer", InstrumentItem.Pose.FLUTE,
                    () -> SoundEvents.NOTE_BLOCK_FLUTE,
                    () -> SoundEvents.NOTE_BLOCK_BELL,
                    1.22f, SCALE_BOSMER_PENTATONIC));

    public static final Supplier<InstrumentItem> KHAJIIT_SITAR = ITEMS.register("khajiit_sitar",
            () -> new InstrumentItem(props("khajiit_sitar", Rarity.UNCOMMON), "khajiit", InstrumentItem.Pose.STRUM,
                    () -> SoundEvents.NOTE_BLOCK_BANJO,
                    () -> SoundEvents.NOTE_BLOCK_PLING,
                    1.04f, SCALE_KHAJIIT_RAGA));

    public static final Supplier<InstrumentItem> ARGONIAN_REED_MARIMBA = ITEMS.register("argonian_reed_marimba",
            () -> new InstrumentItem(props("argonian_reed_marimba", Rarity.UNCOMMON), "argonian", InstrumentItem.Pose.DRUM,
                    () -> SoundEvents.NOTE_BLOCK_XYLOPHONE,
                    () -> SoundEvents.NOTE_BLOCK_DIDGERIDOO,
                    0.92f, SCALE_HIST_REED));

    private AudioItems() {}

    private static Item.Properties props(String id, Rarity rarity) {
        return com.skycraft.platform.SkyProperties.item(id).stacksTo(1).rarity(rarity);
    }

    private static Holder<SoundEvent> goatHornCall() {
        return !SoundEvents.GOAT_HORN_SOUND_VARIANTS.isEmpty()
                ? SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0)
                : SoundEvents.RAID_HORN;
    }

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
