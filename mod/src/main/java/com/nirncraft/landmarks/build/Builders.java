package com.nirncraft.landmarks.build;

/** Dispatch from a kind's {@code builder} name to its geometry routine. */
public final class Builders {
    private Builders() {}

    /** Builds the stage's kind; false when the builder declined (nothing was meant to be there). */
    public static boolean build(Stage s) {
        return switch (s.kind.builder()) {
            // S: vignettes
            case "grave" -> Vignettes.grave(s);
            case "cairn_token" -> Vignettes.cairnToken(s);
            case "bedroll" -> Vignettes.bedroll(s);
            case "lovers_tree" -> Vignettes.loversTree(s);
            case "offering" -> Vignettes.offering(s);
            case "pack" -> Vignettes.pack(s);
            case "warning" -> Vignettes.warning(s);
            case "spring" -> Vignettes.spring(s);
            case "remains" -> Vignettes.remains(s);
            case "fire_ring" -> Vignettes.fireRing(s);
            case "charms" -> Vignettes.charms(s);
            // M: scenes
            case "pilgrim" -> Scenes.pilgrim(s);
            case "hermit" -> Scenes.hermit(s);
            case "hunters" -> Scenes.hunters(s);
            case "travellers" -> Scenes.travellers(s);
            case "smuggler" -> Scenes.smuggler(s);
            case "ghost_ruin" -> Scenes.ghostRuin(s);
            case "ambush" -> Scenes.ambush(s);
            case "skirmish" -> Scenes.skirmish(s);
            case "boat_wreck" -> Scenes.boatWreck(s);
            case "sacrifice" -> Scenes.sacrifice(s);
            case "blind" -> Scenes.blind(s);
            case "claim" -> Scenes.claim(s);
            case "dolmen" -> Scenes.dolmen(s);
            case "dig" -> Scenes.dig(s);
            case "beacon" -> Scenes.beacon(s);
            // trailside and trail's-end vignettes
            case "adit" -> Trailside.adit(s);
            case "overlook" -> Trailside.overlook(s);
            case "treehouse" -> Trailside.treehouse(s);
            case "frozen" -> Trailside.frozen(s);
            case "castaway" -> Trailside.castaway(s);
            case "angler" -> Trailside.angler(s);
            case "waystone" -> Trailside.waystone(s);
            case "den" -> Trailside.den(s);
            case "cabin" -> Trailside.cabin(s);
            case "alcove" -> Trailside.alcove(s);
            case "well" -> Trailside.well(s);
            case "apiary" -> Trailside.apiary(s);
            case "herbs" -> Trailside.herbs(s);
            case "watch_post" -> Trailside.watchPost(s);
            // L: formations
            case "rock_arch" -> Formations.rockArch(s);
            case "hoodoos" -> Formations.hoodoos(s);
            case "split_boulder" -> Formations.splitBoulder(s);
            case "menhir" -> Formations.menhir(s);
            case "sinkhole" -> Formations.sinkhole(s);
            case "tower_ruin" -> Formations.towerRuin(s);
            case "cliff_nests" -> Formations.cliffNests(s);
            case "petrified_tree" -> Formations.petrifiedTree(s);
            case "bone_field" -> Formations.boneField(s);
            case "terraces" -> Formations.terraces(s);
            case "geode" -> Formations.geode(s);
            case "ice_fall" -> Formations.iceFall(s);
            case "glass_dune" -> Formations.glassDune(s);
            case "columns" -> Formations.columns(s);
            case "sea_stacks" -> Formations.seaStacks(s);
            case "mushrooms" -> Formations.mushrooms(s);
            case "vent" -> Formations2.vent(s);
            case "crater" -> Formations2.crater(s);
            case "statue" -> Formations2.statue(s);
            case "aqueduct" -> Formations2.aqueduct(s);
            case "fallen_log" -> Formations2.fallenLog(s);
            case "rune_circle" -> Formations2.runeCircle(s);
            case "spikes" -> Formations2.spikes(s);
            case "dry_lake" -> Formations2.dryLake(s);
            case "drowned_grove" -> Formations2.drownedGrove(s);
            case "old_walls" -> Formations2.oldWalls(s);
            case "fins" -> Formations2.fins(s);
            case "butte" -> Formations2.butte(s);
            // XL: giants
            case "colossal_tree" -> Giants.colossalTree(s);
            case "fungal_spire" -> Giants.fungalSpire(s);
            case "ribcage" -> Giants.ribcage(s);
            case "bridge_arch" -> Giants.bridgeArch(s);
            case "spire_stack" -> Giants.spireStack(s);
            case "spire_twist" -> Giants.spireTwist(s);
            case "antler_spires" -> Giants.antlerSpires(s);
            case "crater_lake" -> Giants.craterLake(s);
            case "mesa_path" -> Giants.mesaPath(s);
            case "crystal_spire" -> Giants.crystalSpire(s);
            case "glacier_tower" -> Giants.glacierTower(s);
            case "obsidian_spire" -> Giants.obsidianSpire(s);
            default -> false;
        };
    }

    /** The builder names this class knows (for the data check and /nirncraft-landmarks). */
    public static final java.util.Set<String> NAMES = java.util.Set.of(
            "grave", "cairn_token", "bedroll", "lovers_tree", "offering", "pack", "warning", "spring", "remains", "fire_ring", "charms",
            "pilgrim", "hermit", "hunters", "travellers", "smuggler", "ghost_ruin", "ambush", "skirmish", "boat_wreck", "sacrifice",
            "blind", "claim", "dolmen", "dig", "beacon",
            "adit", "overlook", "treehouse", "frozen", "castaway", "angler", "waystone", "den", "cabin", "alcove", "well", "apiary",
            "herbs", "watch_post",
            "rock_arch", "hoodoos", "split_boulder", "menhir", "sinkhole", "tower_ruin", "cliff_nests", "petrified_tree", "bone_field",
            "terraces", "geode", "ice_fall", "glass_dune", "columns", "sea_stacks", "mushrooms", "vent", "crater", "statue", "aqueduct",
            "fallen_log", "rune_circle", "spikes", "dry_lake", "drowned_grove", "old_walls", "fins", "butte",
            "colossal_tree", "fungal_spire", "ribcage", "bridge_arch", "spire_stack", "spire_twist", "antler_spires", "crater_lake",
            "mesa_path", "crystal_spire", "glacier_tower", "obsidian_spire");
}
