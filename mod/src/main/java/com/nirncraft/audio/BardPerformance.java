package com.nirncraft.audio;

import com.nirncraft.society.Cultures;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.Region;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps Tamrielic cultures, regions, and bard NPCs to their regional 3D instruments, modal melodies, and 4-verse
 * regional song repertoires ({@code society.nirncraft.song.<culture>.<0..3>.<0..3>}).
 */
public final class BardPerformance {
    public static final List<String> CULTURES = List.of(
            "nord", "imperial", "breton", "redguard", "dunmer", "altmer",
            "bosmer", "khajiit", "argonian", "orc", "reachman", "skaal", "foreign"
    );

    public static final int SONGS_PER_CULTURE = 4;
    public static final int VERSES_PER_SONG = 4;

    private BardPerformance() {}

    /** Normalizes any culture, province, or race id into one of the 13 canonical bard song cultures. */
    public static String normalizeCulture(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return "nord";
        String c = raw.trim().toLowerCase(Locale.ROOT);
        if (CULTURES.contains(c)) return c;
        return switch (c) {
            case "skyrim" -> "nord";
            case "cyrodiil", "colovian", "nibenese" -> "imperial";
            case "high_rock" -> "breton";
            case "hammerfell", "yokuda", "yokudan" -> "redguard";
            case "morrowind", "vvardenfell", "velothi", "ashlander" -> "dunmer";
            case "summerset", "auridon", "arteum" -> "altmer";
            case "valenwood", "imga" -> "bosmer";
            case "elsweyr", "anequina", "pellitine", "ohmes_raht" -> "khajiit";
            case "black_marsh", "argonia", "lilmothiit", "kothringi" -> "argonian";
            case "orsinium", "wrothgar", "orsimer" -> "orc";
            case "the_reach", "reach", "forsworn" -> "reachman";
            case "solstheim", "atmora", "atmoran" -> "skaal";
            default -> "foreign";
        };
    }

    /** Resolves the regional/cultural repertoire key for {@code npc} from its region and race. */
    public static String cultureFor(@Nullable NpcEntity npc) {
        if (npc == null) return "nord";
        try {
            if (npc.level() != null) {
                Region region = Cultures.regionAt(npc.level(), npc.blockPosition());
                if (region != null) {
                    String rid = region.id();
                    if ("skyrim/the_reach".equals(rid)) return "reachman";
                    if ("morrowind/solstheim".equals(rid) || "atmora".equals(region.province())) return "skaal";
                    if ("high_rock/wrothgar".equals(rid)) return "orc";
                    String rc = region.culture();
                    if (rc != null && !rc.isBlank()) {
                        String norm = normalizeCulture(rc);
                        if (!"nord".equals(norm) || "skyrim".equals(region.province())) {
                            return norm;
                        }
                    }
                    String prov = region.province();
                    if (prov != null && !prov.isBlank()) {
                        return normalizeCulture(prov);
                    }
                }
            }
            NpcNames.Race race = npc.race();
            if (race != null) {
                return switch (race) {
                    case NORD -> "nord";
                    case IMPERIAL -> "imperial";
                    case BRETON -> "breton";
                    case REDGUARD -> "redguard";
                    case DUNMER -> "dunmer";
                    case ALTMER -> "altmer";
                    case BOSMER, IMGA -> "bosmer";
                    case KHAJIIT, OHMES_RAHT -> "khajiit";
                    case ARGONIAN, LILMOTHIIT, KOTHRINGI -> "argonian";
                    case ORC -> "orc";
                    case REACHMAN -> "reachman";
                    case ATMORAN -> "skaal";
                    default -> "foreign";
                };
            }
        } catch (RuntimeException ignored) {
        }
        return "nord";
    }

    /** Returns the primary cultural {@link InstrumentItem} for a normalized or raw culture name. */
    public static InstrumentItem instrumentItemFor(@Nullable String culture) {
        String c = normalizeCulture(culture);
        return switch (c) {
            case "imperial" -> AudioItems.LUTE.get();
            case "breton" -> AudioItems.WOODEN_FLUTE.get();
            case "orc", "reachman" -> AudioItems.FRAME_DRUM.get();
            case "skaal" -> AudioItems.NORD_WAR_HORN.get();
            case "dunmer" -> AudioItems.VELOTHI_ASH_FLUTE.get();
            case "redguard" -> AudioItems.REDGUARD_OUD.get();
            case "altmer" -> AudioItems.ALTMER_CRYSTAL_HARP.get();
            case "bosmer" -> AudioItems.BOSMER_BONE_PIPE.get();
            case "khajiit" -> AudioItems.KHAJIIT_SITAR.get();
            case "argonian" -> AudioItems.ARGONIAN_REED_MARIMBA.get();
            case "foreign" -> AudioItems.WOODEN_FLUTE.get();
            default -> AudioItems.LUTE.get();
        };
    }

    /**
     * Resolves the {@link InstrumentItem} held by {@code npc}, or picks the regional/cultural instrument for {@code npc}.
     */
    public static InstrumentItem instrumentItemFor(@Nullable NpcEntity npc) {
        if (npc != null && npc.getMainHandItem().getItem() instanceof InstrumentItem held) {
            return held;
        }
        String culture = cultureFor(npc);
        if (npc != null && "nord".equals(culture)) {
            int roll = Math.floorMod(npc.getUUID().hashCode(), 4);
            if (roll == 0) return AudioItems.NORD_WAR_HORN.get();
            if (roll == 1) return AudioItems.FRAME_DRUM.get();
            return AudioItems.LUTE.get();
        }
        return instrumentItemFor(culture);
    }

    /** Creates a non-empty {@link ItemStack} of the cultural instrument for {@code culture}. */
    public static ItemStack instrumentFor(@Nullable String culture) {
        return new ItemStack(instrumentItemFor(culture));
    }

    /** Creates a non-empty {@link ItemStack} of the cultural instrument for {@code npc}. */
    public static ItemStack instrumentFor(@Nullable NpcEntity npc) {
        return new ItemStack(instrumentItemFor(npc));
    }

    /** All 10 cultural instrument items registered by the audio module. */
    public static List<InstrumentItem> allInstruments() {
        return List.of(
                AudioItems.LUTE.get(),
                AudioItems.WOODEN_FLUTE.get(),
                AudioItems.FRAME_DRUM.get(),
                AudioItems.NORD_WAR_HORN.get(),
                AudioItems.VELOTHI_ASH_FLUTE.get(),
                AudioItems.REDGUARD_OUD.get(),
                AudioItems.ALTMER_CRYSTAL_HARP.get(),
                AudioItems.BOSMER_BONE_PIPE.get(),
                AudioItems.KHAJIIT_SITAR.get(),
                AudioItems.ARGONIAN_REED_MARIMBA.get()
        );
    }

    /**
     * Returns the {@link #SONGS_PER_CULTURE} regional song base keys ({@code society.nirncraft.song.<culture>.<0..3>})
     * for {@code culture}. Each song has a {@code .title} and 4 lyric lines ({@code .0} .. {@code .3}).
     */
    public static List<String> songsFor(@Nullable String culture) {
        String c = normalizeCulture(culture);
        List<String> out = new ArrayList<>(SONGS_PER_CULTURE);
        for (int i = 0; i < SONGS_PER_CULTURE; i++) {
            out.add("society.nirncraft.song." + c + "." + i);
        }
        return List.copyOf(out);
    }

    /** Returns the regional song base keys for {@code npc}. */
    public static List<String> songsFor(@Nullable NpcEntity npc) {
        return songsFor(cultureFor(npc));
    }

    /** Picks the regional song base key for {@code npc} and song index {@code song}. */
    public static String pickSong(@Nullable NpcEntity npc, int song) {
        List<String> songs = songsFor(npc);
        return songs.get(Math.floorMod(song, songs.size()));
    }

    /** Returns the sound event holder for {@code npc}'s held or regional instrument. */
    public static Holder<SoundEvent> soundFor(@Nullable NpcEntity npc) {
        return instrumentItemFor(npc).sound();
    }

    /** Composes a modal melody in the scale of {@code npc}'s held or regional instrument. */
    public static List<Integer> composeMelody(NpcEntity npc) {
        return composeMelody(instrumentItemFor(npc), npc.getRandom());
    }

    /** Composes an 84..108-step modal melody in {@code instrument}'s scale. */
    public static List<Integer> composeMelody(InstrumentItem instrument, RandomSource random) {
        int[] scale = instrument.scale();
        int len = 84 + random.nextInt(24);
        List<Integer> melody = new ArrayList<>(len);
        int idx = Math.min(scale.length - 1, 2 + random.nextInt(Math.max(1, scale.length / 2)));
        int restMod = instrument.pose() == InstrumentItem.Pose.HORN ? 4 : 6;
        for (int i = 0; i < len; i++) {
            if (random.nextInt(restMod) == 0) {
                melody.add(-1);
                continue;
            }
            // Phrase cadence every 16 steps returns toward root or fifth
            if (i % 16 == 15) {
                idx = random.nextBoolean() ? 0 : Math.min(scale.length - 1, 4);
            } else {
                idx = Math.max(0, Math.min(scale.length - 1, idx + random.nextInt(5) - 2));
            }
            melody.add(scale[idx]);
        }
        return melody;
    }

    /** Plays a single melody step from {@code npc}'s instrument in {@code level}. */
    public static void playStep(ServerLevel level, NpcEntity npc, int semitone, int step) {
        if (semitone < 0) return;
        InstrumentItem inst = instrumentItemFor(npc);
        inst.playNote(level, npc.getX(), npc.getY() + 1.0, npc.getZ(), semitone, step);
    }
}
