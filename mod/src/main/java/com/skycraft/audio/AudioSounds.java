package com.skycraft.audio;

import com.skycraft.Skycraft;
import com.skycraft.magic.shout.Shout;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Every sound event of the audio module: the province / culture music sets, ambience beds and one-shots, creature
 * voices, shouts (one, two and three words), spells and the extra UI sounds. The older events ({@code music.explore},
 * {@code ui.menu_open}, {@code shout.fus_ro_dah}, ...) stay registered by {@link com.skycraft.world.WorldSounds} and
 * {@link com.skycraft.atmosphere.AtmosphereSounds}.
 *
 * <p>The catalogue of files, subtitles and attenuation lives in {@code tools/music/sound_catalog.py}, which writes
 * {@code assets/skycraft/sounds.json} and {@code lang/skycraft/audio.json}; every name below must exist there.</p>
 */
public final class AudioSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Skycraft.MODID);
    private static final Map<String, RegistryObject<SoundEvent>> BY_NAME = new HashMap<>();

    /** Exploration music sets besides Skyrim's ({@code music.explore} / {@code music.explore_night}). */
    public static final String[] MUSIC_SETS = {"cyrodiil", "morrowind", "black_marsh", "elsweyr", "valenwood", "summerset",
            "hammerfell", "high_rock", "atmora", "akavir", "sea"};
    /** Town music by culture family besides the Nord {@code music.town}. */
    public static final String[] TOWN_SETS = {"imperial", "dunmer", "desert", "elven"};
    public static final String[] BEDS = {"wind_light", "wind_strong", "blizzard", "forest_day", "forest_night", "plains_day",
            "plains_night", "marsh_day", "marsh_night", "jungle_day", "jungle_night", "desert_wind", "ash_storm", "coast_waves",
            "open_ocean", "river", "cave", "cave_deep", "glow_cave", "oblivion", "sovngarde", "swamp_night", "savanna_day", "hot_springs",
            "volcanic_rumble", "crystal_shimmer", "dwemer_machinery", "lava_cave", "ice_cave", "mushroom_grotto"};
    public static final String[] ONESHOTS = {"bird_song", "crow", "hawk", "gull", "jungle_bird", "owl", "loon", "frog", "cricket",
            "insect", "cave_drip", "cave_rumble", "rock_fall", "wind_gust", "tree_creak", "volcano", "distant_dragon", "wave_crash",
            "whale", "sand_hiss", "oblivion_wail", "sovngarde_horn", "glow_chime", "distant_screech", "machinery", "lava_bubble",
            "ice_creak", "cicada"};
    /** creature -> its sound kinds ({@code entity.<creature>.<kind>}). */
    public static final String[][] CREATURES = {
            {"dragon", "roar", "growl", "wings", "breath_fire", "breath_frost", "land", "hurt", "death"},
            {"draugr", "ambient", "attack", "hurt", "death"},
            {"skeever", "ambient", "hurt", "death"},
            {"troll", "ambient", "roar", "hurt", "death"},
            {"giant", "ambient", "stomp", "hurt", "death"},
            {"mammoth", "ambient", "hurt", "death"},
            {"frostbite_spider", "ambient", "hurt", "death"},
            {"wolf", "howl", "growl"},
            {"bear", "ambient", "roar", "hurt", "death"},
            {"sabre_cat", "ambient", "hurt", "death"},
            {"falmer", "ambient", "hurt", "death"},
            {"dwarven", "steam", "clank", "hurt", "death"},
            {"wisp", "ambient"},
            {"spriggan", "ambient", "hurt", "death"},
            {"hagraven", "ambient", "hurt", "death"},
            {"horker", "ambient", "hurt", "death"},
            {"mudcrab", "ambient", "hurt", "death"},
            {"ice_wraith", "ambient", "hurt", "death"},
    };
    /**
     * The bestiary of Nirn's voices ({@code entity.<creature>.<kind>}): besides ambient / hurt / death, the cries the
     * attack goal plays when a move starts ({@code attack}, {@code roar}, {@code cast}, {@code breath}) and specials.
     * Bestiary specs pick them up by name ({@code CreatureSpec#voice}). Alduin and Paarthurnax have dragon voices of their own.
     */
    public static final String[][] BESTIARY = {
            {"wolf", "ambient", "attack", "hurt", "death"},
            {"chaurus", "ambient", "attack", "cast", "wings", "hurt", "death"},
            {"riekling", "ambient", "attack", "roar", "hurt", "death"},
            {"ash_spawn", "ambient", "attack", "emerge", "hurt", "death"},
            {"gargoyle", "ambient", "roar", "wake", "hurt", "death"},
            {"wisp", "hurt", "death"},
            {"will_o_wisp", "ambient", "hurt", "death"},
            {"death_hound", "ambient", "attack", "roar", "hurt", "death"},
            {"dwarven_ballista", "cast"},
            {"dragon_priest", "ambient", "cast", "hurt", "death"},
            {"dremora", "ambient", "attack", "roar", "hurt", "death"},
            {"flame_atronach", "ambient", "hurt", "death"},
            {"frost_atronach", "ambient", "attack", "step", "hurt", "death"},
            {"storm_atronach", "ambient", "hurt", "death"},
            {"scamp", "ambient", "roar", "hurt", "death"},
            {"imp", "ambient", "hurt", "death"},
            {"cliff_racer", "ambient", "attack", "roar", "hurt", "death"},
            {"guar", "ambient", "hurt", "death"},
            {"kagouti", "ambient", "roar", "hurt", "death"},
            {"alit", "ambient", "attack", "roar", "hurt", "death"},
            {"nix_hound", "ambient", "roar", "hurt", "death"},
            {"netch", "ambient", "hurt", "death"},
            {"silt_strider", "ambient", "hurt", "death"},
            {"minotaur", "ambient", "attack", "roar", "hurt", "death"},
            {"ogre", "ambient", "roar", "hurt", "death"},
            {"land_dreugh", "ambient", "roar", "hurt", "death"},
            {"wamasu", "ambient", "roar", "breath", "hurt", "death"},
            {"swamp_leviathan", "ambient", "roar", "hurt", "death"},
            {"hackwing", "ambient", "hurt", "death"},
            {"senche_tiger", "ambient", "roar", "hurt", "death"},
            {"alfiq", "ambient", "hurt", "death"},
            {"sand_serpent", "ambient", "roar", "hurt", "death"},
            {"giant_scorpion", "ambient", "attack", "hurt", "death"},
            {"harpy", "ambient", "roar", "hurt", "death"},
            {"indrik", "ambient", "hurt", "death"},
            {"sea_serpent", "ambient", "hurt", "death"},
            {"echatere", "ambient", "hurt", "death"},
            {"durzog", "ambient", "roar", "hurt", "death"},
            {"frost_giant", "ambient", "roar", "hurt", "death"},
            {"kamal", "ambient", "hurt", "death"},
            {"tsaesci", "ambient", "roar", "hurt", "death"},
            {"realm_stalker", "ambient", "roar", "hurt", "death"},
            {"realm_grazer", "ambient", "hurt", "death"},
            {"realm_wyrm", "ambient", "hurt", "death"},
            {"dragon", "rise"},
            {"alduin", "roar", "growl", "hurt", "death"},
            {"paarthurnax", "roar", "growl", "hurt", "death"},
    };
    public static final String[] SPELLS = {"fire", "fire_stream", "frost", "frost_stream", "shock", "shock_stream", "heal", "conjure",
            "illusion", "alteration", "charge", "fizzle", "impact_fire", "impact_frost", "impact_shock"};
    public static final String[] UI = {"menu_back", "quest_complete", "level_up", "discover"};

    static {
        for (String s : MUSIC_SETS) {
            register("music.explore." + s);
            register("music.explore_night." + s);
        }
        for (String s : TOWN_SETS) register("music.town." + s);
        register("music.dungeon.dwemer");
        register("music.dungeon.ayleid");
        register("music.blackreach");
        register("music.boss.battle");
        for (String b : BEDS) register("ambient.bed." + b);
        for (String o : ONESHOTS) register("ambient." + o);
        for (String[] c : CREATURES) {
            for (int i = 1; i < c.length; i++) register("entity." + c[0] + "." + c[i]);
        }
        for (String[] c : BESTIARY) {
            for (int i = 1; i < c.length; i++) register("entity." + c[0] + "." + c[i]);
        }
        for (Shout s : Shout.VALUES) {
            for (int words = 1; words <= 3; words++) register("shout." + s.id() + "." + words);
        }
        for (String s : SPELLS) register("spell." + s);
        for (String u : UI) register("ui." + u);
    }

    private AudioSounds() {}

    private static void register(String name) {
        BY_NAME.put(name, SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(Skycraft.MODID, name))));
    }

    public static void init(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    /** The registered event {@code skycraft:<name>}, or a harmless vanilla fallback if the name is unknown. */
    public static SoundEvent get(String name) {
        RegistryObject<SoundEvent> ro = BY_NAME.get(name);
        if (ro == null || !ro.isPresent()) {
            Skycraft.LOGGER.warn("Unknown Skycraft sound event {}", name);
            return SoundEvents.EMPTY;
        }
        return ro.get();
    }

    /** {@code entity.<creature>.<kind>}. */
    public static SoundEvent creature(String creature, String kind) {
        return get("entity." + creature + "." + kind);
    }

    /** {@code entity.<creature>.<kind>} if it is registered, else null (optional cries). */
    @org.jetbrains.annotations.Nullable
    public static SoundEvent creatureOrNull(String creature, String kind) {
        String name = "entity." + creature + "." + kind;
        return BY_NAME.containsKey(name) ? get(name) : null;
    }

    /**
     * A dragon's voice: the named dragons with a voice of their own ({@code alduin}, {@code paarthurnax}) use theirs;
     * every other dragon, and any kind a named dragon lacks, the common {@code entity.dragon.<kind>}.
     */
    public static SoundEvent dragon(String variant, String kind) {
        String own = "entity." + variant + "." + kind;
        return BY_NAME.containsKey(own) ? get(own) : get("entity.dragon." + kind);
    }

    /** The Thu'um chant for {@code words} (1..3) words of a shout. */
    public static SoundEvent shout(Shout shout, int words) {
        return get("shout." + shout.id() + "." + Math.max(1, Math.min(3, words)));
    }

    public static SoundEvent spell(String name) {
        return get("spell." + name);
    }

    public static boolean has(String name) {
        return BY_NAME.containsKey(name);
    }
}
