package com.skycraft.world;

import com.skycraft.Skycraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;

/**
 * Soundtrack events. {@code assets/skycraft/sounds.json} points them at vanilla music by default; a resource pack
 * can replace any of them (e.g. {@code skycraft:music.combat}) with its own tracks.
 */
public final class WorldSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, Skycraft.MODID);

    public static final Supplier<SoundEvent> MUSIC_EXPLORE = register("music.explore");
    public static final Supplier<SoundEvent> MUSIC_EXPLORE_NIGHT = register("music.explore_night");
    public static final Supplier<SoundEvent> MUSIC_TOWN = register("music.town");
    public static final Supplier<SoundEvent> MUSIC_DUNGEON = register("music.dungeon");
    public static final Supplier<SoundEvent> MUSIC_COMBAT = register("music.combat");
    public static final Supplier<SoundEvent> MUSIC_OBLIVION = register("music.oblivion");
    public static final Supplier<SoundEvent> MUSIC_SOVNGARDE = register("music.sovngarde");
    public static final Supplier<SoundEvent> MUSIC_DISCOVERY = register("music.discovery");

    // Skyrim SFX: Thu'um shouting
    public static final Supplier<SoundEvent> SHOUT_FUS_RO_DAH = register("shout.fus_ro_dah");
    public static final Supplier<SoundEvent> SHOUT_WULD_NAH_KEST = register("shout.wuld_nah_kest");
    public static final Supplier<SoundEvent> SHOUT_YOL_TOOR_SHUL = register("shout.yol_toor_shul");
    public static final Supplier<SoundEvent> SHOUT_GENERIC = register("shout.generic");

    // Word walls & Dragon souls
    public static final Supplier<SoundEvent> WORDWALL_CHANT = register("wordwall.chant");
    public static final Supplier<SoundEvent> WORDWALL_LEARN = register("wordwall.learn");
    public static final Supplier<SoundEvent> MAGIC_DRAGON_SOUL = register("magic.dragon_soul");

    // Shrines & Stones
    public static final Supplier<SoundEvent> MAGIC_SHRINE_PRAY = register("magic.shrine_pray");
    public static final Supplier<SoundEvent> MAGIC_STANDING_STONE = register("magic.standing_stone");
    public static final Supplier<SoundEvent> WORLD_NIRNROOT_HUM = register("world.nirnroot_hum");

    // Skyrim UI & Lockpicking
    public static final Supplier<SoundEvent> UI_MENU_OPEN = register("ui.menu_open");
    public static final Supplier<SoundEvent> UI_MENU_CLOSE = register("ui.menu_close");
    public static final Supplier<SoundEvent> UI_MENU_CLICK = register("ui.menu_click");
    public static final Supplier<SoundEvent> UI_SKILL_UP = register("ui.skill_up");
    public static final Supplier<SoundEvent> UI_QUEST_UPDATE = register("ui.quest_update");
    public static final Supplier<SoundEvent> UI_LOCKPICK_TURN = register("ui.lockpick_turn");
    public static final Supplier<SoundEvent> UI_LOCKPICK_STRAIN = register("ui.lockpick_strain");
    public static final Supplier<SoundEvent> UI_LOCKPICK_BREAK = register("ui.lockpick_break");
    public static final Supplier<SoundEvent> UI_LOCKPICK_UNLOCK = register("ui.lockpick_unlock");

    private WorldSounds() {}

    private static Supplier<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Skycraft.MODID, name)));
    }

    public static void init(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
