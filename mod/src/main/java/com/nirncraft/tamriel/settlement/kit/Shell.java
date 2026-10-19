package com.nirncraft.tamriel.settlement.kit;

/** Building shapes. A culture kit picks one per building size; {@link com.nirncraft.tamriel.settlement.build.Shells} draws them. */
public enum Shell {
    /** Rectangular walls, pitched gable roof (Nord, Breton, Imperial, Orc, Atmoran). */
    GABLE,
    /** Rectangular walls, hipped roof (Imperial, Altmer). */
    HIP,
    /** Flat roof with a parapet (Hlaalu, Redguard, Yokudan, Khajiit). */
    FLAT,
    /** Walls under a dome (Redguard, Khajiit, Altmer temples). */
    DOME,
    /** Round walls under a cone (Argonian, Atmoran huts, Bosmer). */
    ROUND,
    /** Khajiit caravan tent. */
    TENT,
    /** Redoran insect-shell house: an elongated rounded hull with ribs. */
    CHITIN,
    /** Telvanni mushroom tower. */
    MUSHROOM,
    /** Akaviri pagoda: stacked curved roofs. */
    PAGODA,
    /** Bosmer house in a giant tree. */
    TREE,
    /** Tower with a pointed roof (spire). */
    TOWER
}
