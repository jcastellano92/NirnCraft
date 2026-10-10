package com.skycraft.core;

import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Optional;

/**
 * Facade over the Tamriel regions (docs/TAMRIEL.md section 7). A "hold" is now a {@link Region}: one of Skyrim's
 * nine holds, a Cyrodiil county, a Morrowind district, a foreign or generated region. Bounties, Jarls, guards and
 * radiant quests are per region and keyed by the region's stable id ({@code "skyrim/whiterun"}). Other dimensions
 * use the dimension name ({@code "the_nether"}).
 *
 * <p>Ids saved before the Tamriel map ({@code "whiterun"}, {@code "whiterun_2"} from the old 2048-block grid) are
 * mapped to their Skyrim hold by {@link #migrate}.</p>
 */
public final class Holds {
    /**
     * Short names of Skyrim's nine holds, used for lang keys ({@code hold.skycraft.<name>},
     * {@code entity.skycraft.guard.<name>}) and guard textures. The region id is {@code "skyrim/" + name}; see
     * {@link #IDS}.
     */
    public static final String[] NAMES = {
            "whiterun", "the_rift", "eastmarch", "the_pale", "winterhold", "haafingar", "hjaalmarch", "the_reach", "falkreath"
    };
    /** Region ids of Skyrim's nine holds, index-aligned with {@link #NAMES}. */
    public static final String[] IDS = new String[NAMES.length];

    static {
        for (int i = 0; i < NAMES.length; i++) IDS[i] = "skyrim/" + NAMES[i];
    }

    private Holds() {}

    /** Stable region id for a position, e.g. {@code "skyrim/whiterun"}, {@code "cyrodiil/heartlands"}. */
    public static String holdAt(Level level, BlockPos pos) {
        return Regions.at(level, pos).id();
    }

    /** The region at a position (same as {@link Regions#at}). */
    public static Region regionAt(Level level, BlockPos pos) {
        return Regions.at(level, pos);
    }

    /** The region's name ("Whiterun Hold", "The Heartlands"); also accepts pre-Tamriel ids. */
    public static Component displayName(String holdId) {
        String id = migrate(holdId);
        Optional<Region> region = Regions.byId(id);
        if (region.isPresent()) return region.get().name();
        String base = base(id);
        if (index(id) >= 0) return Component.translatable("hold.skycraft." + base);
        if (id.startsWith("realm/")) return Component.translatable("world.skycraft.region.uncharted");
        return Component.translatableWithFallback("region.skycraft." + id.replace('/', '.'), prettify(base));
    }

    /**
     * Maps a stored hold id to the current region id: {@code "whiterun"} and {@code "whiterun_2"} become
     * {@code "skyrim/whiterun"}, {@code "reach"} becomes {@code "skyrim/the_reach"}. Region ids, dimension ids and
     * empty strings are returned unchanged.
     */
    public static String migrate(String holdId) {
        if (holdId == null || holdId.isEmpty() || holdId.indexOf('/') >= 0) return holdId == null ? "" : holdId;
        String base = holdId;
        int underscore = holdId.lastIndexOf('_');
        if (underscore > 0 && underscore < holdId.length() - 1
                && holdId.substring(underscore + 1).chars().allMatch(Character::isDigit)) {
            base = holdId.substring(0, underscore);
        }
        base = base.toLowerCase(Locale.ROOT);
        if (base.equals("reach")) base = "the_reach";
        if (base.equals("rift")) base = "the_rift";
        if (base.equals("pale")) base = "the_pale";
        for (String name : NAMES) if (name.equals(base)) return "skyrim/" + name;
        return holdId;
    }

    /** Short id without the province ({@code "skyrim/the_reach"} gives {@code "the_reach"}). */
    public static String base(String holdId) {
        String id = migrate(holdId);
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    /** Province of a region id ({@code "skyrim"}), or the id itself for dimensions. */
    public static String province(String holdId) {
        String id = migrate(holdId);
        Optional<Region> region = Regions.byId(id);
        if (region.isPresent()) return region.get().province();
        int slash = id.indexOf('/');
        return slash < 0 ? id : id.substring(0, slash);
    }

    /** Index of a Skyrim hold into {@link #NAMES}/{@link #IDS}, or -1 for any other region. */
    public static int index(String holdId) {
        String id = migrate(holdId);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    /** Whether the id is one of Skyrim's nine holds. */
    public static boolean isSkyrimHold(String holdId) {
        return index(holdId) >= 0;
    }

    private static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : id.toCharArray()) {
            if (c == '_' || c == '/') {
                sb.append(' ');
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }
}
