package com.nirncraft.quest;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/** Procedural Skyrim-flavored names for bandit chiefs, legendary monsters, contract targets and ruins. */
public final class Names {
    private Names() {}

    private static final String[] CHIEF_FIRST = {"Krev", "Fjola", "Bjolf", "Hrodulf", "Gemma", "Ulfgar", "Sorli", "Thjar",
            "Eisa", "Grundvar", "Hjorunn", "Vigdis", "Ragna", "Torvar", "Agni", "Skuli"};
    private static final String[] CHIEF_EPITHET = {"the Skinner", "Bloodaxe", "the Cruel", "Ironhand", "the Red", "Wolfbane",
            "the Unforgiving", "Half-Ear", "Stormfist", "the Butcher", "Grey-Mane", "Coldheart"};

    private static final String[] TROLL = {"Grakk the Bonecruncher", "Old Gnasher", "Mournhide", "Frostmaw the Hungry",
            "Gorm Ice-Tooth", "The Beast of Hrothmund's Pass", "Skarr Three-Eyes", "Hollowgut"};
    private static final String[] GIANT = {"Grok the Mountain", "Hulgar Skull-Smasher", "Old Thunderfoot", "Gurmauk the Herder",
            "Brogg Stonefist", "The Titan of Silverdrift"};
    private static final String[] SKEEVER = {"The Skeever King", "Old Whiskers", "Gnawbone the Plague-Bringer",
            "Rattlefang", "The Rat of Riften Sewers"};
    private static final String[] DRAUGR = {"Vokun the Restless", "Ondolemar's Bane", "Hevnoraak the Undying",
            "Rahgot, Ancient Overlord", "Krosis the Faceless", "Morokei the Dead"};
    private static final String[] WITCH = {"The Witch of Cold Rock", "Hag of Fallowstone", "The Crone of Darkwater",
            "Mother Mournwood", "The Glenmoril Witch"};

    private static final String[] TARGETS = {"Narfi", "Hern", "Beitild", "Ennodius Papius", "Lurbuk", "Deekus", "Agnis",
            "Helvard", "Ghunzul", "Maluril", "Safia", "Ahtar", "Clarell", "Hrolfdir", "Vulwulf Snow-Shod", "Anoriath",
            "Svari", "Ingmar", "Torbjorn Shatter-Shield", "Haelga"};
    private static final String[] NECROMANCERS = {"Malyn Varen", "Sild the Warlock", "Valdar the Pale", "Kornalus Frey",
            "Silvia the Dread", "Morven Stroud", "Hamelyn", "Mzinchaleft Ghoul"};
    private static final String[] DRAGONS = {"Mirmulnir", "Sahloknir", "Vuljotnaak", "Viinturuth", "Nahagliiv",
            "Kruziikrel", "Voslaarum", "Sahrotaar", "Ahbiilok", "Vulthuryol"};
    private static final String[] RUINS = {"Bleak Falls Barrow", "Saarthal", "Ustengrav", "Dustman's Cairn", "Ragnvald",
            "Volskygge", "Shroud Hearth Barrow", "Folgunthur", "Ansilvund", "Hob's Fall Cave", "Embershard Mine",
            "Reachwater Rock", "Korvanjund", "Geirmund's Hall", "Skuldafn Ruins", "Forelhost", "Valthume", "Yngol Barrow"};
    private static final String[] CAMPS = {"Halted Stream Camp", "Valtheim Towers", "Bonechill Passage", "Fort Greymoor",
            "Broken Fang Cave", "Lost Knife Hideout", "Redoran's Retreat", "Silent Moons Camp", "Robber's Gorge",
            "Bleakwind Bluff", "Fort Amol", "Brittleshin Pass"};

    private static String pick(RandomSource r, String[] arr) {
        return arr[r.nextInt(arr.length)];
    }

    public static String banditChief(RandomSource r) {
        return pick(r, CHIEF_FIRST) + " " + pick(r, CHIEF_EPITHET);
    }

    /** Legendary monster name by kind: troll, giant, skeever, draugr, witch. */
    public static String legendary(RandomSource r, String kind) {
        return switch (kind) {
            case "giant" -> pick(r, GIANT);
            case "skeever" -> pick(r, SKEEVER);
            case "draugr" -> pick(r, DRAUGR);
            case "witch" -> pick(r, WITCH);
            default -> pick(r, TROLL);
        };
    }

    public static String contractTarget(RandomSource r) {
        return pick(r, TARGETS);
    }

    public static String necromancer(RandomSource r) {
        return pick(r, NECROMANCERS);
    }

    public static String dragon(RandomSource r) {
        return pick(r, DRAGONS);
    }

    public static String camp(RandomSource r) {
        return pick(r, CAMPS);
    }

    /** A stable ruin name for a location (the same dungeon always gets the same name). */
    public static String ruin(BlockPos pos) {
        // a named barrow or ruin of Tamriel keeps its own name
        var place = Locate.placeAt(pos);
        if (place != null) return com.nirncraft.world.PlaceText.plain(place);
        int h = Math.floorMod(pos.getX() / 16 * 31 + pos.getZ() / 16 * 17, RUINS.length);
        return RUINS[h];
    }

    /** "north", "south-east"... from {@code from} towards {@code to}. */
    public static Component direction(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north (-Z), 90 = east (+X)
        int idx = Math.floorMod((int) Math.round(angle / 45.0), 8);
        String[] keys = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        return Component.translatable("quest.nirncraft.dir." + keys[idx]);
    }
}
