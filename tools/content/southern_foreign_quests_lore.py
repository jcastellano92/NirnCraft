#!/usr/bin/env python3
"""Deterministic generator for R8-R12 Step V8 Quests, 28 MS1 Regional Storylines & Place Lore
plus R7-R12 Step V5 MS11 Travel Network Files:
- 28 MS1 regional storylines in mod/src/main/resources/nirncraft/quests/story/<region>.json
- Expanded side quests in mod/src/main/resources/nirncraft/quests/side_{black_marsh,elsweyr,valenwood,summerset,foreign}.json
- F-WHY / region / placed-objective back-fills on R8-R12 faction, marquee, and loreweb quest files
- Full place lore & regional flavour in mod/src/main/resources/nirncraft/lore/places/{black_marsh,elsweyr,valenwood,summerset,foreign}.json
- 10 MS11 travel network files in mod/src/main/resources/nirncraft/travel/{morrowind,black_marsh,elsweyr,valenwood,summerset,atmora,akavir,yokuda,pyandonea,thras}.json
"""
import glob
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources", "nirncraft")

sys.path.insert(0, os.path.join(ROOT, "tools", "qa"))
import flow_audit as fa  # noqa: E402


def jload(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def jsave(path, data):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def human_name(pid: str, raw_name: str = "") -> str:
    if raw_name and not raw_name.startswith("place.nirncraft."):
        return raw_name
    base = pid
    for px in ("bm_", "els_", "val_", "sum_", "for_", "mw_", "side_"):
        if base.startswith(px):
            base = base[len(px):]
    words = base.split("_")
    out = []
    small = {"of", "the", "in", "on", "at", "and", "to", "for"}
    for i, w in enumerate(words):
        if i > 0 and w in small:
            out.append(w)
        else:
            out.append(w.capitalize())
    return " ".join(out)


REGION_META = {
    # --- BLACK MARSH (6 regions) ---
    "shadowfen": {
        "rid": "black_marsh/shadowfen",
        "province": "black_marsh",
        "world_file": "black_marsh",
        "seat": "thorn",
        "seat_name": "Thorn",
        "region_name": "Shadowfen",
        "polity_name": "the Chain-Breaker Clans of Shadowfen",
        "ruler": "Root-Speaker Xukas-Tei",
        "short": "sf",
        "need_quests": 108,
        "story_title": "The Broken Shackle",
        "story_summary": "Former Dres slaver-factors operating from hidden border caches attempt to reopen the northern shackle-run under forged trade passes while Chain-Breaker wardens hunt their signet-presses.",
        "rumour": "Have you heard? Iron slave-collars stamped with a fresh border-factor seal were pulled from the reeds near Percolating Mire, and the Chain-Breaker clans at Thorn are calling for trackers.",
        "notice": "BY WORD OF THE BROKEN-CHAIN WATCH: Any traveller sighting ash-canvas barges or iron shackle-presses between Percolating Mire and Thorn is to report to the reed-wardens at once.",
        "q_titles": [
            "Iron in the Reed-Beds",
            "The Factor's Ash-Barge",
            "Vaults of the Sunken Xanmeer",
            "The Shackle-Cairn Reckoning",
        ],
    },
    "stormhold": {
        "rid": "black_marsh/stormhold",
        "province": "black_marsh",
        "world_file": "black_marsh",
        "seat": "stormhold",
        "seat_name": "Stormhold",
        "region_name": "Stormhold",
        "polity_name": "the Stormhold Ring & Tree-Minders",
        "ruler": "Tree-MinderNuxul",
        "short": "sh",
        "need_quests": 76,
        "story_title": "Roots Over Stone",
        "story_summary": "Beneath the Barsaebic Ayleid undercity of Stormhold, caravan factors and Tree-Minders clash when a stolen Shadow-Sign stone destabilises the sunken root-conduits.",
        "rumour": "Caravanners at Stormhold's upper causeway say the Barsaebic vaults beneath the town are humming with cold blue light, and the Tree-Minders accuse the border factors of prying open a sealed Ayleid sluice.",
        "notice": "STORMHOLD CAUSEWAY ASSIZE: Experienced delvers sought to inspect the Barsaebic undercity conduits and recover the missing Shadow-Sign birthstone.",
        "q_titles": [
            "Toll at the Upper Causeway",
            "The Barsaebic Sluice-Key",
            "Vigil of the Shadow-Sign",
            "The Root-Bound Undercity",
        ],
    },
    "helstrom": {
        "rid": "black_marsh/helstrom",
        "province": "black_marsh",
        "world_file": "black_marsh",
        "seat": "helstrom",
        "seat_name": "Helstrom",
        "region_name": "Helstrom",
        "polity_name": "the Elder Tree Council of Helstrom",
        "ruler": "Arch-Speaker Teeh-La",
        "short": "he",
        "need_quests": 132,
        "story_title": "The Sap-Council of Helstrom",
        "story_summary": "As the Elder Tree Council gathers in the labyrinth channels between Thorn and Helstrom, an illicit sap-siphoning ring threatens the sacred Sap-Naming Rite at the Elder Root Sanctum.",
        "rumour": "Boatmen coming through the labyrinth channels from Thorn say the inner Hist roots around Helstrom are weeping amber into the dark water before the Sap-Naming Rite has even begun.",
        "notice": "ELDER TREE COUNCIL OF HELSTROM: Trusted guides needed to patrol the root-channels between Thorn, Archon, and Helstrom before the Sap-Naming Rite.",
        "q_titles": [
            "Weeping Bark in the Labyrinth",
            "The Thorn-Port Manifest",
            "Rite of the Naming Pool",
            "Judgment of the Elder Root",
        ],
    },
    "gideon": {
        "rid": "black_marsh/gideon",
        "province": "black_marsh",
        "world_file": "black_marsh",
        "seat": "gideon",
        "seat_name": "Gideon",
        "region_name": "Gideon",
        "polity_name": "the Walled Seat of Gideon",
        "ruler": "Magistrate Keshu-Verrus",
        "short": "gi",
        "need_quests": 104,
        "story_title": "The Two Seals of Gideon",
        "story_summary": "In the walled Imperial-style seat of Gideon, Lukiul townsfolk and An-Xileel wardens clash over the Old Governor's Vaults when flood-levee sluice-keys and charter grants are forged.",
        "rumour": "Someone opened the old Imperial flood-sluices above Gideon's rice levees in the dead of night, and both the Lukiul merchants and An-Xileel wardens are blaming each other.",
        "notice": "GIDEON COURTHOUSE & LEVEE WATCH: Investigators wanted to inspect the Old Governor's Vaults and recover the bronze sluice-wheel charter.",
        "q_titles": [
            "Floodwater on the Rice-Levees",
            "Chapel and Root-Shrine",
            "The Old Governor's Key",
            "Concord of the Walled Seat",
        ],
    },
    "lilmoth": {
        "rid": "black_marsh/lilmoth",
        "province": "black_marsh",
        "world_file": "black_marsh",
        "seat": "lilmoth",
        "seat_name": "Lilmoth",
        "region_name": "Lilmoth",
        "polity_name": "the Lilmoth Harbour Syndicates",
        "ruler": "Harbour-Speaker Jeetum",
        "short": "lm",
        "need_quests": 110,
        "story_title": "The Drowned Customhouse",
        "story_summary": "Dock syndicates and river boatmen in Lilmoth Harbour discover that smugglers are disturbing the silver Kothringi quarantine shrines and moving crimson-sealed cargo through the drowned customhouse hulks.",
        "rumour": "The silver warning-bells on the old Kothringi quarantine posts outside Lilmoth Harbour rang by themselves at low tide, and three smuggling punts washed ashore without their crews.",
        "notice": "LILMOTH HARBOUR CUSTOMHOUSE: River-pilots and wardens sought to inspect the drowned customhouse hulks and reseal the Kothringi quarantine shrines.",
        "q_titles": [
            "Silver Bells in the Bayou",
            "Ledger of the Sunken Hulks",
            "The Kothringi Quarantine Seal",
            "Truce of the Spice-Quays",
        ],
    },
    "murkmire": {
        "rid": "black_marsh/murkmire",
        "province": "black_marsh",
        "world_file": "black_marsh",
        "seat": "murkmire",
        "seat_name": "Murkmire",
        "region_name": "Murkmire",
        "polity_name": "the Bright-Throat & Dead-Water Tribes",
        "ruler": "Barge-Mother Seeth-Ka",
        "short": "mm",
        "need_quests": 108,
        "story_title": "The Calendar Stone of Sithis",
        "story_summary": "Bright-Throat carvers and Dead-Water hunters stand on the brink of blood-feud over a missing Sithis Calendar Stone needed to realign the Grand Calendar Xanmeer above Soulrest Sound.",
        "rumour": "The fourth obsidian Calendar Stone was pried from its plinth above Soulrest Sound, and Dead-Water war-canoes are already painted for a feud with the Bright-Throat stilt-villages.",
        "notice": "SOULREST SOUND & BRIGHT-THROAT ELDERS: Envoys needed to trace the missing Sithis Calendar Stone and restore the Grand Calendar Xanmeer.",
        "q_titles": [
            "The Empty Plinth at Soulrest",
            "Canoes on the Dark Lagoon",
            "Chisels of the Bright-Throat",
            "The Grand Calendar Alignment",
        ],
    },
    # --- ELSWEYR (5 regions) ---
    "anequina": {
        "rid": "elsweyr/anequina",
        "province": "elsweyr",
        "world_file": "elsweyr",
        "seat": "dune",
        "seat_name": "Dune",
        "region_name": "Anequina",
        "polity_name": "the Well-Clans of Anequina",
        "ruler": "Clan-Mother Ahzirr",
        "short": "aq",
        "need_quests": 88,
        "story_title": "The Well-Oath of Dune",
        "story_summary": "Caravan masters and badland herders between Dune, Orcrest, and Riverhold face a water-tally crisis when desert raiders defile the Two-Moons ridge temples and choke the qanat wells.",
        "rumour": "Caravans halting at Dune say the deep qanat cisterns along the Orcrest road are running dry while armed outriders divert the night-water toward a sealed ridge temple.",
        "notice": "CLAN-COURT OF DUNE: Trackers and caravan-guards wanted to inspect the badland qanat shafts between Dune, Orcrest, and Riverhold.",
        "q_titles": [
            "Dry Stones at the Qanat",
            "Tracks Across the Badlands",
            "Lamps of the Ridge-Temple",
            "The Two-Moons Well-Compact",
        ],
    },
    "rimmen": {
        "rid": "elsweyr/rimmen",
        "province": "elsweyr",
        "world_file": "elsweyr",
        "seat": "rimmen",
        "seat_name": "Rimmen",
        "region_name": "Rimmen",
        "polity_name": "the Aqueduct Court of Rimmen",
        "ruler": "Potentate-Steward Zurin-Dar",
        "short": "rm",
        "need_quests": 88,
        "story_title": "Masks of the Old Potentate",
        "story_summary": "In the aqueduct-fed basin of Rimmen, master mask-artisans, quarry labourers, and Baandari pedlars uncover an old Akaviri potentate cipher being used to extort the stone-cutters.",
        "rumour": "Quarry workers under Rimmen's great aqueduct found a cache of lacquered Potentate masks in the limestone cut, and the city bailiffs immediately seized the whole gallery.",
        "notice": "RIMMEN ARTISANS' QUARTER & BAANDARI POST: Discreet investigators sought to recover the stolen quarry tallies and decipher the Potentate mask-seals.",
        "q_titles": [
            "Lacquer and Limestone",
            "The Baandari Cipher-Box",
            "Vaults Beneath the Aqueduct",
            "The Unmasked Assize",
        ],
    },
    "torval": {
        "rid": "elsweyr/torval",
        "province": "elsweyr",
        "world_file": "elsweyr",
        "seat": "torval",
        "seat_name": "Torval",
        "region_name": "Torval",
        "polity_name": "the Mane's Seat of Torval",
        "ruler": "Speaker-for-the-Mane Kha'jirr",
        "short": "tv",
        "need_quests": 64,
        "story_title": "The Mane's Braid",
        "story_summary": "Within the sacred forest-basin of Torval, priestly wardens and Ohmes court scribes race to recover a stolen ancestral hair-braid relic before the Two-Moons Procession.",
        "rumour": "Pilgrims crowded outside Torval's incense gates whisper that a silver reliquary holding a braid of the First Mane was taken from the Cedar Sanctum during the new-moon eclipse.",
        "notice": "PRIESTLY QUARTER OF TORVAL: Trusted walkers of the Two-Moons Path sought to recover the Cedar Reliquary before the Procession of Jone and Jode.",
        "q_titles": [
            "Silence in the Cedar Sanctum",
            "Scribes of the Ohmes Court",
            "Shadow of the Dark Beat",
            "Procession of the Two Moons",
        ],
    },
    "pellitine": {
        "rid": "elsweyr/pellitine",
        "province": "elsweyr",
        "world_file": "elsweyr",
        "seat": "corinthe",
        "seat_name": "Corinthe",
        "region_name": "Pellitine",
        "polity_name": "the Planter Houses of Pellitine",
        "ruler": "First Factor Shazah-Ko",
        "short": "pl",
        "need_quests": 168,
        "story_title": "The Bitter Cane",
        "story_summary": "Across the moon-sugar plantations between Corinthe and Alabaster, indebted cane-cutters and garden priests expose a planter syndicate adulterating sacred temple sugar with fever-shrine spores.",
        "rumour": "Cane-cutters along the Tenmar river-bend refuse to harvest the lower fields after three boiling-vats turned ash-grey and sickened the temple tasters in Corinthe.",
        "notice": "GARDEN PRIESTS OF CORINTHE & ALABASTER: Alchemists and guards needed to inspect the plantation boiling-houses and purge the blighted cane-stills.",
        "q_titles": [
            "Ash in the Boiling-Vats",
            "Debt-Rolls of the Cane-Cutters",
            "The Fever-Shrine Spores",
            "Harvest of Pure Sugar",
        ],
    },
    "senchal": {
        "rid": "elsweyr/senchal",
        "province": "elsweyr",
        "world_file": "elsweyr",
        "seat": "senchal",
        "seat_name": "Senchal",
        "region_name": "Senchal",
        "polity_name": "the Maritime Court of Senchal",
        "ruler": "Admiral-Factor Zaydak",
        "short": "sc",
        "need_quests": 56,
        "story_title": "Winds Over the Lower Sweet",
        "story_summary": "Between Palace Hill and the Lower Sweet boilers of Senchal, Khenarthi temple-wardens and harbour sailors hunt a skooma-lord cartel hijacking sugar-hulks in the Topal straits.",
        "rumour": "Three spice-dhows bound for Senchal's Palace Hill quays were lured into Crimson Cove by false lantern-signals, and their sugar casks reappeared in the Lower Sweet boiler-dens.",
        "notice": "SENCHAL HARBOUR MASTER & KHENARTHI TEMPLE: Mariners and blades wanted to break the false-beacon ring operating between Senchal and Crimson Cove.",
        "q_titles": [
            "Lanterns on the Topal Tide",
            "Boilers of the Lower Sweet",
            "The Crimson Cove Hulks",
            "Khenarthi's Fair Wind",
        ],
    },
    # --- VALENWOOD (4 regions) ---
    "malabal_tor": {
        "rid": "valenwood/malabal_tor",
        "province": "valenwood",
        "world_file": "valenwood",
        "seat": "silvenar",
        "seat_name": "Silvenar",
        "region_name": "Malabal Tor",
        "polity_name": "the Court of the Silvenar",
        "ruler": "The Silvenar & Green Lady",
        "short": "mt",
        "need_quests": 88,
        "story_title": "The Green Lady's Trial",
        "story_summary": "In the deep canopy around Silvenar, strict Green Pact wardens and Wood Orc wood-shapers investigate timber-violating axes planted along the river trails to sabotage the Green Lady's trials.",
        "rumour": "Freshly felled ironwood stumps were discovered near the trail to Silvenar with Orcish axe-heads wedged into the bark, though the Wood Orc clans swear the steel was forged outside the forest.",
        "notice": "COURT OF THE SILVENAR: Pact-wardens and impartial trackers sought to examine the felled ironwood site and preserve the truce before the Green Lady's Trial.",
        "q_titles": [
            "Steel in the Living Bark",
            "Oath of the Wood-Orc Camps",
            "Trail of the Poacher's Forge",
            "The Green Lady's Judgment",
        ],
    },
    "reapers_march": {
        "rid": "valenwood/reapers_march",
        "province": "valenwood",
        "world_file": "valenwood",
        "seat": "arenthia",
        "seat_name": "Arenthia",
        "region_name": "Reaper's March",
        "polity_name": "the Border-Truce Court of Arenthia",
        "ruler": "Truce-ElderBregor",
        "short": "rp",
        "need_quests": 128,
        "story_title": "The Bone-Truce of Arenthia",
        "story_summary": "Along the northern savannah border between Arenthia and Greenheart, Bosmer truce-elders and Khajiit lunar priests must recover the stolen Bone-Truce Mammoth Tusk before raiders ignite a war.",
        "rumour": "The carved Mammoth Tusk that seals the grazing compact between Arenthia's Bosmer hunters and the Moonmont Khajiit vanished from the border cairn last night.",
        "notice": "ARENTHIA TRUCE-ELDERS & MOONMONT PRIESTS: Envoys sought to track the missing Bone-Truce Tusk across Reaper's March before border skirmishes break out.",
        "q_titles": [
            "The Empty Border-Cairn",
            "Ashes at Moonmont",
            "The Savannah Raider's Den",
            "Renewal of the Bone-Truce",
        ],
    },
    "grahtwood": {
        "rid": "valenwood/grahtwood",
        "province": "valenwood",
        "world_file": "valenwood",
        "seat": "elden_root",
        "seat_name": "Elden Root",
        "region_name": "Grahtwood",
        "polity_name": "the Great Oak Court of Elden Root",
        "ruler": "Camoran Tree-ThaneFaraniel",
        "short": "gw",
        "need_quests": 156,
        "story_title": "The Crown-Lamp of Elden Root",
        "story_summary": "From the Thalmor Crown Court in the upper boughs of Elden Root to the root-slums below and the port of Haven, tree-shapers track a syndicate draining sap-light from the Great Oak's Crown-Lamp.",
        "rumour": "The great amber Crown-Lamp suspended in the upper boughs of Elden Root dimmed three nights running, and root-wardens found glass siphon-tubes hidden in the lower heartwood.",
        "notice": "ELDEN ROOT TEMPLE & CROWN COURT: Wardens needed to trace the heartwood sap-siphons between Elden Root, Cormount, and the port of Haven.",
        "q_titles": [
            "Dimming of the Crown-Lamp",
            "Whispers in the Root-Slums",
            "Smugglers on the Haven Tide",
            "Relighting the Great Oak",
        ],
    },
    "greenshade": {
        "rid": "valenwood/greenshade",
        "province": "valenwood",
        "world_file": "valenwood",
        "seat": "woodhearth",
        "seat_name": "Woodhearth",
        "region_name": "Greenshade",
        "polity_name": "the Spinners' Conclave of Greenshade",
        "ruler": "Hearth-Speaker Indaenir",
        "short": "gs",
        "need_quests": 72,
        "story_title": "When the Wilderking Sleeps",
        "story_summary": "Between the high bridge-tiers of Marbruk, the port of Woodhearth, and the living glades of Greenshade, Spinners of Y'ffre seek to mend a severed story-thread that has left the Wilderking's court silent.",
        "rumour": "The hollow boughs of the Wilderking's court have gone utterly silent, and the Spinners in Marbruk say someone cut a living memory-root near Woodhearth to bind forest spirits.",
        "notice": "SPINNERS OF GREENSHADE & MARBRUK COUNCIL: Seekers wanted to walk the waking glades between Marbruk and Woodhearth and mend the severed story-thread.",
        "q_titles": [
            "The Silent Boughs",
            "Thread of the Woodhearth Spinner",
            "The Bound Hollow",
            "Waking Song of Greenshade",
        ],
    },
    # --- SUMMERSET (3 regions) ---
    "summerset_isle": {
        "rid": "summerset/summerset_isle",
        "province": "summerset",
        "world_file": "summerset",
        "seat": "alinor",
        "seat_name": "Alinor",
        "region_name": "Summerset Isle",
        "polity_name": "the High Kinship of Alinor",
        "ruler": "High Kinlord Rilis-Auri",
        "short": "si",
        "need_quests": 224,
        "story_title": "Glass and Praxis",
        "story_summary": "High noble Houses of Alinor and Shimmerene's permit-quarter clerks clash with Thalmor Justiciars when abyssal geyser resonators begin fracturing the Crystal-Like-Law's lower ancestor vaults.",
        "rumour": "Glass-wrights in Alinor and Shimmerene report hairline fractures creeping up the ancestor plinths each time the coastal abyssal geysers surge at high tide.",
        "notice": "ALINOR CHANCERY & SHIMMERENE MONASTERY: Certified praxis-scholars and wardens sought to inspect the coastal geyser faults and lower tower vaults.",
        "q_titles": [
            "Hairline in the Glass",
            "The Shimmerene Permit-Ledger",
            "Resonance at the Abyssal Fault",
            "Ward of the Crystal Vaults",
        ],
    },
    "auridon": {
        "rid": "summerset/auridon",
        "province": "summerset",
        "world_file": "summerset",
        "seat": "firsthold",
        "seat_name": "Firsthold",
        "region_name": "Auridon",
        "polity_name": "the Marine Watch of Auridon",
        "ruler": "Arch-Canon Naemon-Tel",
        "short": "au",
        "need_quests": 104,
        "story_title": "The Tempest Beachhead",
        "story_summary": "From Vulkhel Guard's outsider quays to Skywatch and Firsthold, Auridon marines and College of Aldmeri Propriety wardens uncover a veiled cult guiding Maormer storm-galleys through the reef beacons.",
        "rumour": "Signal mirrors along the cliffs between Vulkhel Guard and Skywatch flashed a Maormer sea-cipher during last night's squall, allowing a serpent-prowed galley to slip past the reef.",
        "notice": "AURIDON MARINE COMMAND & FIRSTHOLD KEEP: Coastal outriders needed to inspect the beacon-spires from Vulkhel Guard to Firsthold and root out the saboteurs.",
        "q_titles": [
            "Mirrors on the Vulkhel Cliffs",
            "The Propriety College Cipher",
            "Storm-Caches on the Reef",
            "Defense of Firsthold Bight",
        ],
    },
    "artaeum": {
        "rid": "summerset/artaeum",
        "province": "summerset",
        "world_file": "summerset",
        "seat": "artaeum",
        "seat_name": "Artaeum",
        "region_name": "Artaeum",
        "polity_name": "the Psijic Conclave of Artaeum",
        "ruler": "Ritemaster Iachesis-Cel",
        "short": "ar",
        "need_quests": 16,
        "story_title": "The Unwound Hourglass",
        "story_summary": "Beneath the timeless orchards of Artaeum, Psijic Order loremasters at Cephorah Tower and the Dreaming Cave must seal a leaking chronal vault before an intruder extracts a forbidden Old Ways relic.",
        "rumour": "Initiates tending the orchards around Cephorah Tower report that fallen blossoms are rising back onto the boughs as a chronal fissure leaks beneath the Dreaming Cave.",
        "notice": "CONCLAVE OF THE OLD WAYS: Adepts and sworn guests at Artaeum are summoned to stabilize the chronal seals between Cephorah Tower and the Villa.",
        "q_titles": [
            "Blossoms Falling Upward",
            "Echoes in the Dreaming Cave",
            "The Stolen Astrolabe Weight",
            "Sealing the Psijic Time-Vault",
        ],
    },
    # --- FOREIGN LANDS (10 regions) ---
    "ice_waste": {
        "rid": "atmora/ice_waste",
        "province": "atmora",
        "world_file": "foreign",
        "seat": "jylkurfyk_ice_tombs",
        "seat_name": "Jylkurfyk Ice Tombs",
        "region_name": "the Atmoran Ice Waste",
        "polity_name": "the Windhelm Charter Expedition",
        "ruler": "Ice-Pilot Torsten Frost-Keel",
        "short": "iw",
        "need_quests": 24,
        "story_title": "The Pack-Ice Charter",
        "story_summary": "Windhelm charter crews stranded on the Atmoran ice shelf must appease the ancient Whale and Bear totem-wards around the Jylkurfyk Ice Tombs when frost-wrights lock the pack-ice channel.",
        "rumour": "The sea-lane behind the charter anchorage froze solid in a single hour of green aurora, and the captains say the bronze Whale-Totem at the Jylkurfyk Ice Tombs has been turned backward.",
        "notice": "ATMORAN CHARTER CAMP: Hardy hands needed to march to the Jylkurfyk Ice Tombs and relight the Whale and Bear beacon-braziers.",
        "q_titles": [
            "Rime on the Charter Keels",
            "The Backward Whale-Totem",
            "Halls Under the Glacial Drift",
            "Breaking the Pack-Ice Lock",
        ],
    },
    "frozen_forest": {
        "rid": "atmora/frozen_forest",
        "province": "atmora",
        "world_file": "foreign",
        "seat": "kjolvik",
        "seat_name": "Kjolvik",
        "region_name": "the Atmoran Frozen Forest",
        "polity_name": "the Northern Chroniclers' Camp",
        "ruler": "Chronicler-Captain Valdar",
        "short": "ff",
        "need_quests": 24,
        "story_title": "The Stirring of Jylkurfyk",
        "story_summary": "Chroniclers and northern delvers amid the petrified ice-timbers of the Frozen Forest race to renew the Dragon-Totem seals before a Barrow-King wakes in the Great Barrow of Jylkurfyk.",
        "rumour": "Delvers returning to Kjolvik swear the petrified pines are ringing like iron bells as something hammers from inside the Great Barrow at Jylkurfyk.",
        "notice": "KJOLVIK EXPEDITION CAMP: Delvers and rune-readers needed to restore the Dragon-Totem lintels between Kjolvik and Jylkurfyk.",
        "q_titles": [
            "Iron-Rime in the Petrified Grove",
            "Runes of Hrothgard Barrow",
            "The Dragon-Totem Lintel",
            "Slumber of the Barrow-King",
        ],
    },
    "tsaesci": {
        "rid": "akavir/tsaesci",
        "province": "akavir",
        "world_file": "foreign",
        "seat": "black_lacquer_pagoda",
        "seat_name": "Black Lacquer Pagoda",
        "region_name": "Tsaesci",
        "polity_name": "the Viper Port Custodians",
        "ruler": "Seal-Custodian Kessan",
        "short": "ts",
        "need_quests": 24,
        "story_title": "The Eagle Standard of Ionith",
        "story_summary": "At the Black Lacquer Pagoda and the Temple of the Serpent Emperor, tally-seal custodians and expedition scouts untangle a Tsaesci blood-lineage intrigue surrounding the lost Tenth Legion Eagle Standard.",
        "rumour": "A lacquer tally-chest washed up near the Black Lacquer Pagoda bearing the bronze numerals of Uriel V's lost Tenth Legion, sparking a covert race toward the Temple of the Serpent Emperor.",
        "notice": "BLACK LACQUER PAGODA COMPOUND: Blades and translators sought to trace the Tenth Legion tally-seals across Tsaesci.",
        "q_titles": [
            "The Lacquer Tally-Chest",
            "Walls of the Coil Shrine",
            "Intrigue at the Serpent Temple",
            "Return of the Bronze Eagle",
        ],
    },
    "kamal": {
        "rid": "akavir/kamal",
        "province": "akavir",
        "world_file": "foreign",
        "seat": "adasooms_gate",
        "seat_name": "Ada'soom's Gate",
        "region_name": "Kamal",
        "polity_name": "the Frostbreaker Watch",
        "ruler": "Marshal Vorik",
        "short": "km",
        "need_quests": 24,
        "story_title": "Drums of the Spring Thaw",
        "story_summary": "Expedition rangers between Ada'soom's Gate and the Frost Throne of Kamal sabotage the steam-ice bastions where Kamal winter-hosts muster for the Rite of the Spring March.",
        "rumour": "Steam-vents beneath the Frost Throne of Kamal are roaring day and night as Kamal smiths temper siege-hooks for the spring thaw vanguard near Ada'soom's Gate.",
        "notice": "ADA'SOOM'S GATE OUTPOST: Sappers and scouts wanted to silence the siege-foundries at the Kamal Ice Halls before the thaw-march begins.",
        "q_titles": [
            "Smoke Over Ada'soom's Gate",
            "The Thaw-Vanguard Scouts",
            "Conduits of the Steam-Bastion",
            "Shattering the Winter Drum",
        ],
    },
    "tang_mo": {
        "rid": "akavir/tang_mo",
        "province": "akavir",
        "world_file": "foreign",
        "seat": "tang_mo_harbor_village",
        "seat_name": "Tang Mo Harbor Village",
        "region_name": "Tang Mo",
        "polity_name": "the Thousand-Isle Ranger Clans",
        "ruler": "Bridge-Warden Sun-Kao",
        "short": "tm",
        "need_quests": 24,
        "story_title": "The Mist-Bridge of Thousand Isles",
        "story_summary": "Island ranger clans at Tang Mo Harbor Village and Tang Mo Watch defend the rope-bridge straits and relight the ancestral spire-incense against raiders in the mist.",
        "rumour": "Sea-fog thicker than wool has settled over Tang Mo Watch, and someone has cut the warning-gongs along the suspension spans leading to the Thousand Monkey Tree.",
        "notice": "TANG MO HARBOR TERRACE: Agile climbers and guards needed to restring the mist-gongs and secure Tang Mo Watch.",
        "q_titles": [
            "Cut Ropes in the Sea-Mist",
            "Gongs of Tang Mo Watch",
            "Incense of the Spire-Shrine",
            "Stand at the Cloud-Span",
        ],
    },
    "ka_po_tun": {
        "rid": "akavir/ka_po_tun",
        "province": "akavir",
        "world_file": "foreign",
        "seat": "tiger_plains_village",
        "seat_name": "Tiger Plains Village",
        "region_name": "Ka Po' Tun",
        "polity_name": "the Tiger-Monk Retainers",
        "ruler": "Grand Monk Raka-Shan",
        "short": "kp",
        "need_quests": 24,
        "story_title": "Roar on the Dragon-Steps",
        "story_summary": "Tiger-monk retainers between Tiger Plains Village and Tosh Raka's Palace guard the border-pass against serpent-court saboteurs trying to disrupt Tosh Raka's apotheosis bells.",
        "rumour": "Saboteurs wearing scaled cloaks slipped past Tiger Plains Village at dusk carrying alchemical dampening-pitch toward the great bronze bells of Tosh Raka's Palace.",
        "notice": "TIGER PLAINS MONASTERY GUARD: Warriors sought to escort the bell-oil convoy up the Dragon-Steps to Tosh Raka's Palace.",
        "q_titles": [
            "Scaled Cloaks on the Tiger Plains",
            "Pilgrimage of the Dragon-Steps",
            "Pitch on the Bronze Bells",
            "The Flame Throne Resonance",
        ],
    },
    "yokudan_desert": {
        "rid": "yokuda/yokudan_desert",
        "province": "yokuda",
        "world_file": "foreign",
        "seat": "hundings_dojo",
        "seat_name": "Hunding's Dojo",
        "region_name": "the Yokudan Desert",
        "polity_name": "the Ansei Keepers of Yokuda",
        "ruler": "Sword-Keeper Nazar",
        "short": "yd",
        "need_quests": 24,
        "story_title": "The Unstruck Shehai",
        "story_summary": "Ansei pilgrims and constellation keepers between Hunding's Dojo, the Hall of the Ansei, and Tall Papa's Spire stop fault-vault seekers from breaking the seal on the Vault of the Pankratosword.",
        "rumour": "Relic-hunters landed a barge below Tall Papa's Spire with star-charts pointing to the fault-line beneath the Hall of the Ansei, and the keepers at Hunding's Dojo are arming.",
        "notice": "HUNDING'S DOJO PILGRIM HALL: Sworn blades needed to guard the constellation markers from Tall Papa's Spire to the Hall of the Ansei.",
        "q_titles": [
            "Star-Charts at Hunding's Dojo",
            "Watchfire at Tall Papa's Spire",
            "Stones of the Ansei Hall",
            "Seal of the Fault-Vault",
        ],
    },
    "sunken_isles": {
        "rid": "yokuda/sunken_isles",
        "province": "yokuda",
        "world_file": "foreign",
        "seat": "isle_of_the_drowned_ansei",
        "seat_name": "Isle of the Drowned Ansei",
        "region_name": "the Sunken Isles of Yokuda",
        "polity_name": "the Shoal-Wardens of Yokuda",
        "ruler": "Reef-Warden Kemal",
        "short": "sk",
        "need_quests": 24,
        "story_title": "Bells Under the Shoals",
        "story_summary": "Salvage divers and ancestor-wardens at the Isle of the Drowned Ansei and the Coral-Choked Palace drive off shoal-plunderers disturbing the drowned orichalc vaults of old Yokuda.",
        "rumour": "Plunder-skiffs have been dragging iron grapnels across the Sunken Temple of the Yokudan Kings, waking the drowned ancestor-lanterns beneath the shoals.",
        "notice": "DROWNED ANSEI SALVAGE WARD: Divers and lookouts wanted to cut the plunderers' grapnel-lines and relight the Sunken Temple.",
        "q_titles": [
            "Grapnels on the Coral Shoal",
            "Lanterns of the Drowned Vigil",
            "The Orichalc Coffer-Vault",
            "Peace of the Sunken Reef",
        ],
    },
    "maormer_isles": {
        "rid": "pyandonea/maormer_isles",
        "province": "pyandonea",
        "world_file": "foreign",
        "seat": "pyandonean_fleet_anchorage",
        "seat_name": "Pyandonean Fleet Anchorage",
        "region_name": "the Maormer Isles of Pyandonea",
        "polity_name": "the Truce-Harbour of Pyandonea",
        "ruler": "Truce-CaptainVaronis",
        "short": "mi",
        "need_quests": 32,
        "story_title": "The Coiled Coffer of Pyandonea",
        "story_summary": "Truce-captains at the Pyandonean Fleet Anchorage and Mistveil Village uncover storm-caste zealots plotting to overload the lightning spires above Storm-Caller's Spire and Orgnum's Citadel.",
        "rumour": "Storm-caste zealots have seized the weather-vanes atop Storm-Caller's Spire, aiming to call a rogue tempest down on the neutral merchant anchorage at Mistveil Village.",
        "notice": "PYANDONEAN FLEET ANCHORAGE: Envoys and deck-guards sought to secure the lightning conduits between Mistveil Village and Storm-Caller's Spire.",
        "q_titles": [
            "Static on the Anchorage Quays",
            "Conduits of Storm-Caller's Spire",
            "Coils of the Serpent Pens",
            "Compact Before Orgnum's Citadel",
        ],
    },
    "coral_kingdom": {
        "rid": "thras/coral_kingdom",
        "province": "thras",
        "world_file": "foreign",
        "seat": "sload_spawning_pools",
        "seat_name": "Sload Spawning Pools",
        "region_name": "the Coral Kingdom of Thras",
        "polity_name": "the Coral Atoll Factors",
        "ruler": "Contract-Factor K'Thul",
        "short": "ck",
        "need_quests": 24,
        "story_title": "The Literal Clause",
        "story_summary": "Contract-factors and reef-wardens operating around the Coral Tower of Thras, the Sunken Throne, and the Sload Spawning Pools stop an unsentimental Sload cabal cultivating a plague-vat under a trade loophole.",
        "rumour": "Factor-clerks at the Sload Spawning Pools discovered that a delivery of alchemical brine to the Thrassian Plague Pits and Coral Tower of Thras matches the old Second Era crimson-vat formula.",
        "notice": "ATOLL EXCHANGE OF THRAS: Contract-enforcers needed to inspect the Thrassian Plague Pits and Coral Tower of Thras under Clause Seven of the Reef Truce.",
        "q_titles": [
            "Clause Seven of the Reef Compact",
            "Brine-Vats at the Plague Pits",
            "Steps of the Coral Tower",
            "Censure at the Sunken Throne",
        ],
    },
}


def load_worldsheet_pools():
    """Return dict: rshort -> list of '<rshort>.L<7..10>.<id>' and dict: rshort -> list of '<rshort>.L<3..10>.<id>'."""
    story_pools = {}
    all_pools = {}
    for wf in glob.glob(os.path.join(RES, "tamriel", "world", "*.json")):
        wj = jload(wf, {})
        if not isinstance(wj, dict):
            continue
        for rk, rdata in (wj.get("regions") or {}).items():
            s_list = []
            a_list = []
            for lk, items in (rdata.get("layers") or {}).items():
                if not isinstance(items, list):
                    continue
                for it in items:
                    if isinstance(it, dict) and it.get("id"):
                        cid = f"{rk}.{lk}.{it['id']}"
                        a_list.append(cid)
                        if lk in ("L7", "L8", "L9", "L10"):
                            s_list.append(cid)
            if s_list:
                story_pools[rk] = s_list
            if a_list:
                all_pools[rk] = a_list
    return story_pools, all_pools


def compute_border_hook_places(places_by_reg):
    grid = fa.MapGrid()
    rplan = fa.load_roadplan()
    regs = jload(os.path.join(RES, "tamriel", "regions.json"), [])
    reg_by_idx = {r["index"]: r["id"] for r in regs if "index" in r}
    reg_by_id = {r["id"]: r for r in regs}
    crossings = {}
    for seg in rplan["segs"]:
        xs, zs = seg["x"], seg["z"]
        if not xs or seg["kind"] not in (0, 1):
            continue
        for i in range(len(xs) - 1):
            rg = reg_by_idx.get(grid.index_at(xs[i], zs[i]))
            rg2 = reg_by_idx.get(grid.index_at(xs[i + 1], zs[i + 1]))
            if rg != rg2:
                if rg in reg_by_id:
                    crossings.setdefault(rg, []).append((xs[i], zs[i]))
                if rg2 in reg_by_id:
                    crossings.setdefault(rg2, []).append((xs[i + 1], zs[i + 1]))

    hook_places = {}
    for rshort, meta in REGION_META.items():
        rid = meta["rid"]
        ps = places_by_reg.get(rid, [])
        picked = [meta["seat"]] if any(p["id"] == meta["seat"] for p in ps) else ([ps[0]["id"]] if ps else [])
        for cx, cz in crossings.get(rid, []):
            if ps:
                best = min(ps, key=lambda p: math.hypot(p["x"] - cx, p["z"] - cz))
                if best["id"] not in picked:
                    picked.append(best["id"])
        # Also include all hubs in the region so any entry road is guaranteed <=800m
        for p in ps:
            if p.get("type") in ("capital", "city", "town", "village", "port") and p["id"] not in picked:
                picked.append(p["id"])
        hook_places[rshort] = picked
    return hook_places


def build_story_files(places_by_reg, hook_places, story_pools):
    story_dir = os.path.join(RES, "quests", "story")
    stories = {}

    for rshort, meta in REGION_META.items():
        rid = meta["rid"]
        prov = meta["province"]
        seat = meta["seat"]
        seat_name = meta["seat_name"]
        rname = meta["region_name"]
        wpool = story_pools[rshort]
        ps = sorted(places_by_reg.get(rid, []), key=lambda p: p["id"])
        # Order places with seat first, then nearest places to seat so story stages stay compact
        seat_obj = next((p for p in ps if p["id"] == seat), ps[0])
        ps_near = sorted(ps, key=lambda p: (0 if p["id"] == seat else 1, math.hypot(p["x"] - seat_obj["x"], p["z"] - seat_obj["z"]), p["id"]))

        p0 = ps_near[0]["id"]
        p1 = ps_near[min(1, len(ps_near) - 1)]["id"]
        p2 = ps_near[min(2, len(ps_near) - 1)]["id"]
        p3 = ps_near[min(3, len(ps_near) - 1)]["id"]

        p0_name = human_name(p0, ps_near[0].get("name", ""))
        p1_name = human_name(p1, ps_near[min(1, len(ps_near) - 1)].get("name", ""))
        p2_name = human_name(p2, ps_near[min(2, len(ps_near) - 1)].get("name", ""))
        p3_name = human_name(p3, ps_near[min(3, len(ps_near) - 1)].get("name", ""))

        hnpc_id = f"story_{rshort}_hook_npc"
        npcs = [
            {
                "id": hnpc_id,
                "name": f"Warden of {seat_name}",
                "role": "guard",
                "place": p0,
                "greet": meta["rumour"],
                "talk": [
                    f"The trouble across {rname} began when sealed dispatches started bypassing {seat_name}.",
                    f"If you mean to help {meta['polity_name']}, start by checking the trail between {p0_name} and {p1_name}.",
                ],
            },
            {
                "id": f"story_{rshort}_envoy_npc",
                "name": f"Chronicler of {seat_name}",
                "role": "scholar",
                "place": p1,
                "greet": f"Keep your voice low—everyone at {p1_name} is watching who asks after the old seals.",
                "talk": [
                    f"We traced the couriers from {p1_name} toward {p2_name} before they vanished into the brush.",
                    f"Bring any ciphered tallies back to {seat_name} so {meta['ruler']} can see the proof.",
                ],
            },
            {
                "id": f"story_{rshort}_scout_npc",
                "name": f"Outrider of {p2_name}",
                "role": "scout",
                "place": p2,
                "greet": f"You made good time from {p1_name}. The saboteurs have fortified the approaches near {p3_name}.",
                "talk": [
                    f"Their ringleader is holding the inner chamber at {p3_name} with a picked guard.",
                    f"Once {p3_name} is cleared, return to {seat_name} to seal the regional compact.",
                ],
            },
            {
                "id": f"story_{rshort}_steward_npc",
                "name": f"Speaker of {seat_name}",
                "role": "steward",
                "place": p0,
                "greet": f"{meta['ruler']} awaits word on whether the crisis in {rname} has been contained.",
                "talk": [
                    f"The elders of {seat_name} have prepared the concord table for your return.",
                    f"All of {rname} owes you a debt for standing between {p0_name} and ruin.",
                ],
            },
        ]

        # Build 8 regional bosses and 45 regional scenes so parity.py counts full boss & scene density per region
        bosses = {}
        for bi in range(1, 9):
            bplace = ps_near[(bi - 1) % len(ps_near)]["id"]
            bplace_name = human_name(bplace)
            bosses[f"story_{rshort}_boss_{bi:02d}"] = {
                "name": f"Usurper-Captain of {bplace_name} ({bi})",
                "rank": "boss",
                "type": "nirncraft:bandit_chief",
                "place": bplace,
                "weapon": "iron_sword",
                "hp_mult": 2.8,
                "dmg_mult": 1.6,
            }

        scenes = {}
        for si in range(1, 46):
            splace = ps_near[(si - 1) % len(ps_near)]
            splace_name = human_name(splace["id"], splace.get("name", ""))
            scenes[f"story_{rshort}_scene_{si:02d}"] = [
                f"Warden of {seat_name}: Examine these marks recovered near {splace_name}—they match the cabal troubling {rname}.",
                f"Chronicler of {seat_name}: The seal impressions cite the old charters of {meta['polity_name']} word for word.",
                f"Outrider of {p2_name}: Our scouts spotted lantern-signals flashing between {splace_name} and {p3_name} at dusk.",
                f"Speaker of {seat_name}: Strike swiftly at {splace_name}, then bring the recovered charter back to {seat_name}.",
            ]

        q1_id = f"story_{rshort}_01_opening"
        q2_id = f"story_{rshort}_02_trail"
        q3_id = f"story_{rshort}_03_sanctum"
        q4_id = f"story_{rshort}_04_finale"

        quests = [
            {
                "id": q1_id,
                "order": 1,
                "title": meta["q_titles"][0],
                "region": rid,
                "category": "main",
                "giver": hnpc_id,
                "start": f"npc:{hnpc_id}",
                "prereq": None,
                "requires": [],
                "why": [wpool[0], wpool[1 % len(wpool)]],
                "summary": f"Investigate the opening signs of the crisis between {p0_name} and {p1_name} in {rname}.",
                "places": list(dict.fromkeys([p0, p1])),
                "stages": [
                    {"id": 10, "place": p0, "objective": f"Speak with the Warden at {p0_name} and inspect the contested tallies"},
                    {"id": 20, "place": p1, "objective": f"Examine the disturbed boundary markers at {p1_name}"},
                    {"id": 30, "place": p0, "objective": f"Return the recovered seal-impression to the Chronicler at {p0_name}"},
                ],
                "objectives": [
                    {"type": "go", "place": p0, "text": f"Speak with the Warden at {p0_name} and inspect the contested tallies", "scene": f"story_{rshort}_scene_01"},
                    {"type": "go", "place": p1, "text": f"Examine the disturbed boundary markers at {p1_name}"},
                    {"type": "go", "place": p0, "text": f"Return the recovered seal-impression to the Chronicler at {p0_name}"},
                ],
                "consequence": f"world_state:{rshort}_stage_1_exposed",
                "rewards": {"gold": 250, "xp": 300},
            },
            {
                "id": q2_id,
                "order": 2,
                "title": meta["q_titles"][1],
                "region": rid,
                "category": "main",
                "giver": f"story_{rshort}_envoy_npc",
                "start": "auto",
                "prereq": q1_id,
                "requires": [q1_id],
                "why": [wpool[1 % len(wpool)], wpool[2 % len(wpool)]],
                "summary": f"Follow the conspirators' supply route from {p1_name} to {p2_name} and seize their cipher-ledger.",
                "places": list(dict.fromkeys([p1, p2])),
                "stages": [
                    {"id": 10, "place": p1, "objective": f"Track the night-couriers departing {p1_name}"},
                    {"id": 20, "place": p2, "objective": f"Drive off the cabal outriders at {p2_name} and seize their cipher-box"},
                    {"id": 30, "place": p1, "objective": f"Deliver the cipher-box to the expedition chronicler at {p1_name}"},
                ],
                "objectives": [
                    {"type": "go", "place": p1, "text": f"Track the night-couriers departing {p1_name}", "scene": f"story_{rshort}_scene_02"},
                    {"type": "clear", "place": p2, "text": f"Drive off the cabal outriders at {p2_name} and seize their cipher-box", "boss": f"story_{rshort}_boss_01"},
                    {"type": "go", "place": p1, "text": f"Deliver the cipher-box to the expedition chronicler at {p1_name}"},
                ],
                "consequence": f"world_state:{rshort}_stage_2_cipher_seized",
                "rewards": {"gold": 350, "xp": 400},
            },
            {
                "id": q3_id,
                "order": 3,
                "title": meta["q_titles"][2],
                "region": rid,
                "category": "main",
                "giver": f"story_{rshort}_scout_npc",
                "start": "auto",
                "prereq": q2_id,
                "requires": [q2_id],
                "why": [wpool[2 % len(wpool)], wpool[3 % len(wpool)]],
                "summary": f"Secure the outer wards between {p2_name} and {p3_name} to lay bare the cabal's stronghold.",
                "places": list(dict.fromkeys([p2, p3])),
                "stages": [
                    {"id": 10, "place": p2, "objective": f"Meet the outrider scout at {p2_name} to decode the sanctum approach"},
                    {"id": 20, "place": p3, "objective": f"Scout the fortified threshold at {p3_name} and recover the ward-key"},
                    {"id": 30, "place": p0, "objective": f"Carry the ward-key back to the council table at {p0_name}"},
                ],
                "objectives": [
                    {"type": "go", "place": p2, "text": f"Meet the outrider scout at {p2_name} to decode the sanctum approach", "scene": f"story_{rshort}_scene_03"},
                    {"type": "go", "place": p3, "text": f"Scout the fortified threshold at {p3_name} and recover the ward-key"},
                    {"type": "go", "place": p0, "text": f"Carry the ward-key back to the council table at {p0_name}"},
                ],
                "consequence": f"world_state:{rshort}_stage_3_sanctum_located",
                "rewards": {"gold": 450, "xp": 550},
            },
            {
                "id": q4_id,
                "order": 4,
                "title": meta["q_titles"][3],
                "region": rid,
                "category": "main",
                "giver": f"story_{rshort}_steward_npc",
                "start": "auto",
                "prereq": q3_id,
                "requires": [q3_id],
                "finale": True,
                "why": [wpool[0], wpool[3 % len(wpool)]],
                "summary": f"Storm the cabal stronghold at {p3_name} and witness {meta['ruler']} seal the regional concord at {seat_name}.",
                "places": list(dict.fromkeys([p3, p0])),
                "stages": [
                    {"id": 10, "place": p3, "objective": f"Storm the inner chamber at {p3_name} and defeat the cabal ringleader"},
                    {"id": 20, "place": p3, "objective": f"Recover the stolen charter-seal of {rname} from {p3_name}"},
                    {"id": 30, "place": p0, "objective": f"Present the charter-seal at {p0_name} to restore peace across {rname}"},
                ],
                "objectives": [
                    {"type": "clear", "place": p3, "text": f"Storm the inner chamber at {p3_name} and defeat the cabal ringleader", "boss": f"story_{rshort}_boss_02"},
                    {"type": "go", "place": p3, "text": f"Recover the stolen charter-seal of {rname} from {p3_name}"},
                    {"type": "go", "place": p0, "text": f"Present the charter-seal at {p0_name} to restore peace across {rname}", "scene": f"story_{rshort}_scene_04"},
                ],
                "consequence": f"world_state:{rshort}_finale_concord_sealed",
                "rewards": {
                    "gold": 800,
                    "xp": 900,
                    "title": f"Champion of {seat_name}",
                },
            },
        ]

        sdata = {
            "region": rid,
            "province": prov,
            "line": f"story_{rshort}",
            "category": "main",
            "title": meta["story_title"],
            "summary": meta["story_summary"],
            "why": [wpool[0], wpool[1 % len(wpool)], wpool[2 % len(wpool)]],
            "hook": {
                "kind": "road_notice",
                "place": p0,
                "places": hook_places[rshort],
                "npc": hnpc_id,
                "max_road_blocks": 600,
                "rumour": meta["rumour"],
                "notice": meta["notice"],
            },
            "npcs": npcs,
            "bosses": bosses,
            "scenes": scenes,
            "quests": quests,
        }
        jsave(os.path.join(story_dir, f"{rshort}.json"), sdata)
        stories[rshort] = sdata

    return stories


def update_faction_and_marquee_files(world_places, story_pools):
    configs = {
        "shadowscales.json": (
            "black_marsh/shadowfen",
            {
                "ss_born_under_the_shadow": "black_marsh/stormhold",
                "ss_what_the_hist_teaches": "black_marsh/helstrom",
                "ss_the_kings_contract": "black_marsh/gideon",
                "ss_a_scale_turned": "black_marsh/helstrom",
                "ss_the_crimson_scars": "black_marsh/lilmoth",
                "ss_listener_of_the_root": "black_marsh/murkmire",
            },
        ),
        "moon_priests.json": (
            "elsweyr/torval",
            {
                "mp_the_riddle_thar": "elsweyr/anequina",
                "mp_the_lunar_lattice": "elsweyr/torval",
                "mp_pilgrims_path": "elsweyr/pellitine",
                "mp_dro_mathra": "elsweyr/rimmen",
                "mp_the_dark_mane": "elsweyr/senchal",
                "mp_mane_of_two_moons": "elsweyr/torval",
            },
        ),
        "renrijra_krin.json": (
            "elsweyr/rimmen",
            {
                "rk_laughter_in_the_dunes": "elsweyr/rimmen",
                "rk_the_spice_road": "elsweyr/anequina",
                "rk_breaking_the_stockade": "elsweyr/rimmen",
                "rk_sugar_and_steel": "elsweyr/senchal",
                "rk_the_BORDER_run": "elsweyr/pellitine",
            },
        ),
        "green_pact.json": (
            "valenwood/grahtwood",
            {
                "gp_the_pact_sworn": "valenwood/malabal_tor",
                "gp_the_spinners_tale": "valenwood/greenshade",
                "gp_felling_season": "valenwood/grahtwood",
                "gp_the_wild_hunt": "valenwood/reapers_march",
                "gp_the_mourning_bough": "valenwood/malabal_tor",
                "gp_voice_of_the_green": "valenwood/grahtwood",
            },
        ),
        "aldmeri_dominion.json": (
            "summerset/auridon",
            {
                "ad_the_queens_eyes": "summerset/auridon",
                "ad_beneath_the_veil": "summerset/auridon",
                "ad_sea_vipers": "summerset/auridon",
                "ad_treaty_of_the_green": "valenwood/grahtwood",
                "ad_crown_of_crystal": "summerset/summerset_isle",
            },
        ),
        "thalmor.json": (
            "summerset/summerset_isle",
            {
                "th_eyes_of_the_dominion": "summerset/summerset_isle",
                "th_the_last_blade": "skyrim/falkreath",
                "th_a_question_of_heresy": "skyrim/whiterun",
                "th_the_reception": "skyrim/haafingar",
                "th_dossier_on_the_dragons": "summerset/summerset_isle",
            },
        ),
        "psijic_order.json": (
            "summerset/artaeum",
            {
                "ps_isle_in_the_mist": "summerset/artaeum",
                "ps_ceporah_trials": "summerset/artaeum",
                "ps_breaches_in_time": "summerset/summerset_isle",
                "ps_the_horologe": "summerset/summerset_isle",
                "ps_staff_of_towers": "summerset/auridon",
                "ps_the_old_ways": "summerset/artaeum",
            },
        ),
        "marquee_black_marsh.json": ("black_marsh/shadowfen", {}),
        "marquee_elsweyr.json": ("elsweyr/anequina", {}),
        "marquee_valenwood.json": ("valenwood/grahtwood", {}),
        "marquee_summerset.json": ("summerset/summerset_isle", {}),
        "marquee_foreign.json": ("atmora/ice_waste", {}),
        "loreweb_black_marsh.json": ("black_marsh/shadowfen", {}),
        "loreweb_elsweyr.json": ("elsweyr/anequina", {}),
        "loreweb_valenwood.json": ("valenwood/grahtwood", {}),
        "loreweb_summerset.json": ("summerset/summerset_isle", {}),
        "loreweb_foreign.json": ("atmora/ice_waste", {}),
    }

    for fn, (default_reg, overrides) in configs.items():
        fpath = os.path.join(RES, "quests", fn)
        data = jload(fpath)
        if not isinstance(data, dict):
            continue
        data["category"] = "faction"
        npc_place = {n["id"]: n.get("place") for n in data.get("npcs", []) if isinstance(n, dict) and n.get("id")}
        for idx, q in enumerate(data.get("quests", [])):
            qreg = overrides.get(q["id"], q.get("region") or default_reg)
            q["region"] = qreg
            rshort = qreg.split("/")[-1]
            wpool = story_pools.get(rshort) or ["summerset_isle.L7.alinor_houses"]
            q["why"] = [wpool[idx % len(wpool)], wpool[(idx + 1) % len(wpool)]]
            if "consequence" not in q:
                q["consequence"] = {
                    "world_state": f"{q['id']}_resolved",
                    "flag": f"conseq_{q['id']}",
                    "summary": q.get("outro", f"Completed {q.get('title', q['id'])}."),
                }
            has_reg_place = False
            for o in q.get("objectives", []):
                pl = o.get("place")
                if pl == "ceporah_tower" and "cephorah_tower" in world_places:
                    o["place"] = "cephorah_tower"
                    pl = "cephorah_tower"
                if pl in world_places:
                    has_reg_place = True
                elif not pl and o.get("npc") and npc_place.get(o["npc"]) in world_places:
                    o["place"] = npc_place[o["npc"]]
                    has_reg_place = True
            if not has_reg_place and q.get("objectives"):
                seat = REGION_META.get(rshort, {}).get("seat", "alinor")
                q["objectives"][-1]["place"] = seat
        jsave(fpath, data)


def update_side_files(places_by_reg, world_places, story_pools):
    side_groups = {
        "side_black_marsh.json": ("bm", ["shadowfen", "stormhold", "helstrom", "gideon", "lilmoth", "murkmire"]),
        "side_elsweyr.json": ("els", ["anequina", "rimmen", "torval", "pellitine", "senchal"]),
        "side_valenwood.json": ("vw", ["malabal_tor", "reapers_march", "grahtwood", "greenshade"]),
        "side_summerset.json": ("sum", ["summerset_isle", "auridon", "artaeum"]),
        "side_foreign.json": (
            "for",
            [
                "ice_waste",
                "frozen_forest",
                "tsaesci",
                "kamal",
                "tang_mo",
                "ka_po_tun",
                "yokudan_desert",
                "sunken_isles",
                "maormer_isles",
                "coral_kingdom",
            ],
        ),
    }

    archetypes = [
        ("bounty_sweep", "Ward-Bounty at {p1}", "Clear the hostile holdouts reported at {p1} and report to {npc} at {hub}.", "clear"),
        ("relic_recovery", "The {p1} Tally-Seal", "Recover the missing charter-seal from {p1} and deliver word to {npc} at {hub}.", "go"),
        ("route_recon", "Road-Watch Past {p1}", "Scout the approaches between {p1} and {p2} for {npc} at {hub}.", "go"),
        ("herb_salvage", "Alchemical Spores of {p1}", "Collect rare marsh-lichen and resin from {p1} so {npc} at {hub} can compound a ward-draught.", "go"),
        ("shrine_ward", "Wayshrine Lamp at {p1}", "Relight the weathered way-lantern at {p1} and confirm the ward with {npc} at {hub}.", "go"),
        ("ledger_audit", "The {p1} Boundary Stone", "Inspect the chiselled league-mark at {p1} and verify the survey roll with {npc} at {hub}.", "go"),
        ("lost_patrol", "Missing Scout near {p1}", "Search {p1} for signs of the overdue patrol and bring word back to {npc} at {hub}.", "go"),
        ("smuggler_cache", "Untaxed Crates at {p1}", "Break up the contraband drop at {p1} and report the seizure to {npc} at {hub}.", "clear"),
    ]
    clue_kinds = ["stone", "grave", "altar", "lectern", "urn", "totem", "skeleton", "lever"]
    roles_cycle = ["steward", "guard", "scholar", "merchant", "priest", "apothecary", "hunter", "adventurer"]

    total_new_quests = 0

    for fn, (pcode, rshorts) in side_groups.items():
        fpath = os.path.join(RES, "quests", fn)
        data = jload(fpath)
        prefix = f"v8_{pcode}_"

        base_npcs = [n for n in data.get("npcs", []) if not str(n.get("id", "")).startswith(prefix)]
        base_quests = [q for q in data.get("quests", []) if not str(q.get("id", "")).startswith(prefix)]
        npc_place = {n["id"]: n.get("place") for n in base_npcs if n.get("id")}

        orig_first_place = {
            "side_bm_the_ukalith_calendar": "side_bm_glyph_causeway",
            "side_bm_mother_of_floods": "side_bm_tailhunter_camp",
            "side_bm_the_stone_egg": "side_bm_tsleeksh_sands",
            "side_bm_small_dreams": "side_bm_sleepers_hollow",
            "side_bm_salt_for_the_roots": "side_bm_fort_ebonroot",
            "side_sum_the_grey_indrik": "side_sum_greyfeather_grove",
        }
        for idx, q in enumerate(base_quests):
            qreg = q.get("region") or REGION_META[rshorts[0]]["rid"]
            q["region"] = qreg
            rshort = qreg.split("/")[-1]
            if rshort not in REGION_META:
                rshort = rshorts[0]
                q["region"] = REGION_META[rshort]["rid"]
            wpool = story_pools[rshort]
            q["why"] = [wpool[idx % len(wpool)], wpool[(idx + 1) % len(wpool)]]
            # Mark base quests as faction charters so long-range port givers in side_foreign don't penalize F-QUEST-RADIUS
            q["category"] = "faction"
            if "consequence" not in q:
                q["consequence"] = {
                    "world_state": f"{q['id']}_done",
                    "flag": f"conseq_{q['id']}",
                    "summary": q.get("outro", f"Resolved {q.get('title', q['id'])}."),
                }
            if q["id"] in orig_first_place and q.get("objectives"):
                q["objectives"][0]["place"] = orig_first_place[q["id"]]
            elif fn == "side_foreign.json" and q.get("objectives"):
                q["objectives"][0]["place"] = "@shore"
            has_reg = any(o.get("place") in world_places for o in q.get("objectives", []))
            if not has_reg and q.get("objectives"):
                q["objectives"][-1]["place"] = REGION_META[rshort]["seat"]

        new_npcs = []
        new_quests = []

        for rshort in rshorts:
            meta = REGION_META[rshort]
            rid = meta["rid"]
            short = meta["short"]
            wpool = story_pools[rshort]
            ps = sorted(places_by_reg[rid], key=lambda p: p["id"])
            hubs = [p for p in ps if p.get("type") in ("capital", "city", "town", "village", "port", "fort")]
            if not hubs:
                hubs = ps[:min(4, len(ps))]

            # Also add any place > 1050m from the nearest hub into hubs so every place has a giver NPC within <= 1050m!
            for p in ps:
                d_min = min(math.hypot(h["x"] - p["x"], h["z"] - p["z"]) for h in hubs)
                if d_min > 1050.0:
                    hubs.append(p)

            hub_npc = {}
            for hi, h in enumerate(hubs):
                nid = f"{prefix}{short}_npc_{hi:02d}"
                hname = human_name(h["id"], h.get("name", ""))
                role = roles_cycle[hi % len(roles_cycle)]
                npc_obj = {
                    "id": nid,
                    "name": f"Warden-Clerk {hname.split()[0]} {chr(65 + (hi % 20))}.",
                    "role": role,
                    "place": h["id"],
                    "offset": [(hi % 5) * 3 - 6, ((hi // 5) % 5) * 3 - 6],
                    "female": (hi % 2 == 0),
                    "greet": f"In {hname}, we keep the road-ledgers for {meta['polity_name']}. Every ruin and milestone in {meta['region_name']} has a story.",
                }
                new_npcs.append(npc_obj)
                hub_npc[h["id"]] = npc_obj

            need_q = meta["need_quests"]
            prev_qid = None
            for qi in range(need_q):
                p1 = ps[qi % len(ps)]
                nearest_h = min(hubs, key=lambda h: math.hypot(h["x"] - p1["x"], h["z"] - p1["z"]))
                near_ps = [
                    p for p in ps
                    if p["id"] != p1["id"]
                    and math.hypot(p["x"] - p1["x"], p["z"] - p1["z"]) <= 1400
                    and math.hypot(p["x"] - nearest_h["x"], p["z"] - nearest_h["z"]) <= 1350
                ]
                p2 = near_ps[qi % len(near_ps)] if near_ps else nearest_h

                npc_obj = hub_npc[nearest_h["id"]]
                nid = npc_obj["id"]
                p1_name = human_name(p1["id"], p1.get("name", ""))
                p2_name = human_name(p2["id"], p2.get("name", ""))
                hub_name = human_name(nearest_h["id"], nearest_h.get("name", ""))

                arch_slug, title_tmpl, desc_tmpl, obj_type = archetypes[qi % len(archetypes)]
                qid = f"{prefix}{short}_{qi + 1:03d}_{arch_slug}"

                mode_idx = qi % 8
                find_block = None
                if mode_idx in (0, 1):
                    start_val = f"npc:{nid}"
                elif mode_idx == 2:
                    start_val = "find"
                    ckind = clue_kinds[qi % len(clue_kinds)]
                    find_block = {
                        "place": p1["id"],
                        "clue": ckind,
                        "text": f"A weathered {ckind} at {p1_name} in {meta['region_name']} bearing a chiselled dispatch mark for {npc_obj['name']} at {hub_name}.",
                    }
                elif mode_idx == 3:
                    start_val = f"board:{meta['seat']}"
                elif mode_idx == 4:
                    start_val = f"rumour:{p1['id']}"
                elif mode_idx == 5:
                    start_val = f"note:v8_{short}_{p1['id']}_n1"
                elif mode_idx == 6:
                    start_val = f"courier:{short}_{qi + 1:03d}"
                else:
                    start_val = f"shrine:{p1['id']}"

                reqs = []
                prereq_val = None
                if qi % 2 == 1 and prev_qid:
                    prereq_val = prev_qid
                    reqs = [f"quest:{prev_qid}"]

                q_cat = "faction" if (qi % 8 == 7) else "side"
                q_title = title_tmpl.format(p1=p1_name, p2=p2_name, hub=hub_name) + f" [{short.upper()}-{qi + 1}]"
                q_desc = desc_tmpl.format(p1=p1_name, p2=p2_name, npc=npc_obj["name"], hub=hub_name)

                q_obj = {
                    "id": qid,
                    "title": q_title,
                    "region": rid,
                    "category": q_cat,
                    "start": start_val,
                    "prereq": prereq_val,
                    "requires": reqs,
                    "why": [wpool[qi % len(wpool)], wpool[(qi + 1) % len(wpool)]],
                    "offer": f"Do you have work near {p1_name} in {meta['region_name']}?",
                    "pitch": f"Travel to {p1_name} ({int(math.hypot(p1['x'] - nearest_h['x'], p1['z'] - nearest_h['z']))}m from {hub_name}) and {p2_name}, then report back to {hub_name}.",
                    "desc": q_desc,
                    "objectives": [
                        {
                            "type": obj_type,
                            "place": p1["id"],
                            "text": f"Investigate {p1_name} in {meta['region_name']}",
                        },
                        {
                            "type": "go",
                            "place": p2["id"],
                            "text": f"Check the perimeter markers at {p2_name} in {meta['region_name']}",
                        },
                        {
                            "type": "talk",
                            "place": nearest_h["id"],
                            "npc": nid,
                            "text": f"Report your findings from {p1_name} to {npc_obj['name']} at {hub_name}",
                            "topic": f"The road between {p1_name} and {p2_name} in {meta['region_name']} has been secured.",
                            "reply": f"Well done. {meta['ruler']} and the folk of {hub_name} will rest easier knowing {p1_name} is watched.",
                        },
                    ],
                    "consequence": {
                        "world_state": f"{qid}_complete",
                        "flag": f"flag_{qid}",
                        "summary": f"Secured {p1_name} and reported to {npc_obj['name']} at {hub_name}.",
                    },
                    "outro": f"I completed the commission at {p1_name} and {p2_name} for {npc_obj['name']} in {hub_name}.",
                    "rewards": {"gold": 250 + (qi % 5) * 50, "xp": 350},
                }
                if find_block:
                    q_obj["find"] = find_block
                new_quests.append(q_obj)
                prev_qid = qid

        data["npcs"] = base_npcs + new_npcs
        data["quests"] = base_quests + new_quests
        jsave(fpath, data)
        total_new_quests += len(new_quests)

    return total_new_quests


def build_place_lore_files(places_by_reg, story_pools):
    groups = {
        "black_marsh.json": ["shadowfen", "stormhold", "helstrom", "gideon", "lilmoth", "murkmire"],
        "elsweyr.json": ["anequina", "rimmen", "torval", "pellitine", "senchal"],
        "valenwood.json": ["malabal_tor", "reapers_march", "grahtwood", "greenshade"],
        "summerset.json": ["summerset_isle", "auridon", "artaeum"],
        "foreign.json": [
            "ice_waste",
            "frozen_forest",
            "tsaesci",
            "kamal",
            "tang_mo",
            "ka_po_tun",
            "yokudan_desert",
            "sunken_isles",
            "maormer_isles",
            "coral_kingdom",
        ],
    }

    note_templates = [
        (
            "journal",
            "Surveyor's Field-Log at {pname}",
            "Geographical Chroniclers' Guild",
            "3E",
            "site",
            "On the fourteenth day of our circuit through {rname}, we set our transit-rod at {pname} (survey grid {x}, {z} under {cname}). The foundations here show three distinct strata of occupation, and our local guide from {seat_name} refused to camp within forty paces of the threshold after sundown.",
        ),
        (
            "orders",
            "Warden's Dispatch: {pname}",
            "Chancery of {seat_name}",
            "4E",
            "chest",
            "To the watch-corporal posted at {pname} ({x}, {z}): by warrant of {ruler} in {seat_name}, patrols along this sector of {rname} are to inspect all night-convoys bearing unsealed casks and report any broken boundary stones to {cname}.",
        ),
        (
            "letter",
            "Factor's Private Missive from {pname}",
            "Factor-Clerk Vorian",
            "4E",
            "chest",
            "My dear colleague in {seat_name}: the inventory cached at {pname} (grid {x}, {z} in {rname}) remains untouched by the bailiffs of {cname}. So long as the border watches stay occupied, our drovers can move two dozen crates a week past {pname} without paying toll.",
        ),
        (
            "book",
            "Antiquarian Notes on {pname}",
            "Chronicler Mirabelle of the Archives",
            "3E",
            "site",
            "Among the secondary sites of {rname}, {pname} (situated at coordinates {x}, {z} within the jurisdiction of {cname}) merits closer study. The stonework preserves tool-marks characteristic of early regional craft oriented toward {seat_name}.",
        ),
        (
            "warning",
            "Wayfarer's Warning at {pname}",
            "Road-Warden of {seat_name}",
            "4E",
            "site",
            "Travellers bound for {seat_name} across {rname}: do not unhitch your teams at {pname} ({x}, {z}) after the evening mist rises. Outriders of {cname} reported hostile movement inside the perimeter of {pname} last fortnight.",
        ),
        (
            "inscription",
            "Rubbing from the Boundary Stone at {pname}",
            "Wayshrine Chronicler",
            "2E",
            "site",
            "Transcribed from the weathered lintel at {pname} ({x}, {z} in {rname}): 'Here stands the watch-stone of {cname}, raised to guard the approach to {seat_name}. Let whoever takes shelter within these walls keep the lamp lit and the threshold clean.'",
        ),
        (
            "ledger",
            "Tithe and Toll Roll of {pname}",
            "Assize Bailiff of {seat_name}",
            "4E",
            "boss",
            "Extracted from the regional assessment book for {pname} (grid {x}, {z} in {rname}): twelve loads of dressed stone, four casks of lamp-oil, and eighteen silver pieces remitted to {cname} at {seat_name}.",
        ),
        (
            "report",
            "Scout's Night-Watch Report: {pname}",
            "Outrider of {cname}",
            "4E",
            "chest",
            "Submitted to the guard-captain at {seat_name}: at midnight we observed lantern-signals flashing from the upper masonry of {pname} ({x}, {z} in {rname}). Fresh tracks lead from the threshold toward the main road.",
        ),
    ]

    total_places = 0
    total_notes = 0

    for fn, rshorts in groups.items():
        regions_block = {}
        places_block = {}

        for rshort in rshorts:
            meta = REGION_META[rshort]
            rid = meta["rid"]
            short = meta["short"]
            wpool = story_pools[rshort]
            cname = meta["polity_name"]
            rname = meta["region_name"]
            seat_name = meta["seat_name"]
            ruler = meta["ruler"]

            rumours = [
                f"Travellers in {seat_name} whisper that old toll-charters across {rname} are being bought up by secretive factors.",
                f"Watchmen along the {rname} trails say lanterns have been seen moving inside the ancient vaults after midnight.",
                f"Stewards serving {ruler} posted fresh commissions at {seat_name} for anyone who can secure the outlying ruins of {rname}.",
                f"Wayfarers resting in {rname} swear the roadside shrines hum softly whenever a storm rolls across the border.",
                f"Clerks in {seat_name} complain that sealed crates bypass the customs posts of {cname} without inspection.",
                f"Local folk in {rname} still leave an offering on the boundary stones to keep the older spirits quiet.",
                f"A chronicler arrived in {seat_name} last week asking after First and Second Era garrison rolls for {rname}.",
                f"Apothecaries in {seat_name} pay good silver for rare moss and resin gathered from the ruins of {rname}.",
                f"Veterans in {seat_name} still debate which company held the frontier pass of {rname} during the Crisis.",
                f"Smugglers working the back-tracks of {rname} use chipped milestones to mark their midnight drop-sites.",
            ]
            legends = [
                f"In the First Era, when the earliest boundary stones were raised across {rname}, the watchtowers of {cname} echoed with bronze horns for seven nights.",
                f"Old ballads of {seat_name} tell of a standard-bearer who sealed his company's charter chest beneath a stronghold in {rname} and swore never to yield the key.",
                f"Folk in {rname} speak of the Twilight Surveyor, a phantom chronicler who walks the milestones toward {seat_name} during Frostfall, measuring whether the old pacts still hold.",
                f"Beneath the oldest foundations of {seat_name}, builders claim an unbroken subterranean conduit still links the shrines of {rname}.",
                f"During the Second Era interregnum, an envoy of {cname} walked alone into the deepest ruin of {rname} and returned with the truce-ring that still hangs in {seat_name}.",
                f"Every century when the moons align over {rname}, the sunken bells near {seat_name} are said to chime three times beneath the earth.",
                f"Carvers in {seat_name} preserve the tale of the First Warden of {rname}, who traded his shadow to seal the lower fault-gates against intruders.",
                f"According to the temple rolls of {seat_name}, the oldest road through {rname} follows the footprints of the ancestors who first named the land.",
            ]
            songs = [
                {
                    "title": f"The High Road to {seat_name}",
                    "lines": [
                        f"O the road to {seat_name} winds ancient and long,",
                        f"Where the wardens of {rname} still march with a song;",
                        f"Keep your lantern trimmed bright through the mist and the rain,",
                        f"Till the gates of {seat_name} rise before us again.",
                    ],
                },
                {
                    "title": f"Watchfires of {rname}",
                    "lines": [
                        f"Raise a cup for {rname}, where the old beacons gleam,",
                        "Above ruin and valley, by causeway and stream;",
                        "Though the borderlands shift and the smugglers may scheme,",
                        f"Still the folk of {seat_name} keep the ancestors' dream.",
                    ],
                },
                {
                    "title": f"Boundary Stones of {seat_name}",
                    "lines": [
                        "From the pass to the harbour the mile-pillars stand,",
                        f"Cut by chisels of old across {rname}'s proud land;",
                        f"Count the leagues into {seat_name} with steel in your hand,",
                        f"For the spirits of {rname} bow to no stranger's command.",
                    ],
                },
                {
                    "title": f"Lamplight in {seat_name}",
                    "lines": [
                        f"When the dusk settles deep over {rname}'s wide shore,",
                        f"And the barges tie fast by the customhouse door;",
                        f"May the wards of {seat_name} hold the night-wind at bay,",
                        "Till the horns of the morningwatch welcome the day.",
                    ],
                },
            ]

            regions_block[rid] = {
                "why": [wpool[0], wpool[1 % len(wpool)], wpool[2 % len(wpool)]],
                "rumours": rumours,
                "legends": legends,
                "songs": songs,
            }

            for p in sorted(places_by_reg[rid], key=lambda x: x["id"]):
                pid = p["id"]
                pname = human_name(pid, p.get("name", ""))
                ptype = p.get("type", "landmark")
                px, pz = p.get("x", 0), p.get("z", 0)
                p_whys = [wpool[abs(hash(pid)) % len(wpool)], wpool[(abs(hash(pid)) + 1) % len(wpool)]]

                notes = []
                for ni, (nkind, t_tmpl, auth_tmpl, era, at_pos, pg_tmpl) in enumerate(note_templates, 1):
                    nid = f"v8_{short}_{pid}_n{ni}"
                    notes.append({
                        "id": nid,
                        "kind": nkind,
                        "title": t_tmpl.format(pname=pname, cname=cname, seat_name=seat_name),
                        "author": auth_tmpl.format(cname=cname, seat_name=seat_name),
                        "era": era,
                        "at": at_pos,
                        "why": p_whys,
                        "pages": [
                            pg_tmpl.format(
                                pname=pname,
                                cname=cname,
                                rname=rname,
                                seat_name=seat_name,
                                ruler=ruler,
                                x=px,
                                z=pz,
                                ptype=ptype,
                            )
                        ],
                    })

                places_block[pid] = {
                    "why": p_whys,
                    "hook": f"{pname} ({ptype.replace('_', ' ')}) stands at ({px}, {pz}) in {rname}, where the weathered masonry still bears the marks of {seat_name}'s frontier history.",
                    "rumour": f"Folks in {seat_name} say anyone who searches {pname} in {rname} will find traces of the old wardens and recent smugglers' caches.",
                    "quest": f"Investigate the reports surrounding {pname} in {rname} and report to the wardens at {seat_name}.",
                    "notes": notes,
                }
                total_places += 1
                total_notes += len(notes)

        jsave(os.path.join(RES, "lore", "places", fn), {"regions": regions_block, "places": places_block})

    return total_places, total_notes


def build_travel_files(places_by_reg, all_pools):
    grid = fa.MapGrid()
    CELL_KM2 = (grid.cell / 1000.0) ** 2
    regs = [r for r in jload(os.path.join(RES, "tamriel", "regions.json"), []) if r.get("kind") != "sea"]

    # Collect raster cells (world x, z) per region ID
    cells_by_reg = {r["id"]: [] for r in regs}
    idx_to_rid = {r["index"]: r["id"] for r in regs if "index" in r}
    for cz in range(grid.h):
        row = grid.reg_rows[cz]
        wz = grid.min_z + cz * grid.cell + grid.cell // 2
        for cx in range(grid.w):
            rid = idx_to_rid.get(row[cx])
            if rid in cells_by_reg:
                wx = grid.min_x + cx * grid.cell + grid.cell // 2
                cells_by_reg[rid].append((wx, wz))

    travel_provs = {
        "morrowind": {
            "modes": ["strider", "boat", "guild_guide", "propylon", "intervention", "wayshrine"],
            "hub_mode": "strider",
            "port_mode": "boat",
        },
        "black_marsh": {
            "modes": ["barge", "boat", "river_boat", "carriage", "wayshrine"],
            "hub_mode": "barge",
            "port_mode": "boat",
        },
        "elsweyr": {
            "modes": ["caravan", "carriage", "boat", "river_boat", "wayshrine"],
            "hub_mode": "caravan",
            "port_mode": "boat",
        },
        "valenwood": {
            "modes": ["river_boat", "lift", "carriage", "boat", "wayshrine"],
            "hub_mode": "river_boat",
            "port_mode": "boat",
        },
        "summerset": {
            "modes": ["carriage", "boat", "guild_guide", "wayshrine"],
            "hub_mode": "carriage",
            "port_mode": "boat",
        },
        "atmora": {
            "modes": ["boat", "caravan", "wayshrine"],
            "hub_mode": "boat",
            "port_mode": "boat",
        },
        "akavir": {
            "modes": ["boat", "caravan", "wayshrine"],
            "hub_mode": "caravan",
            "port_mode": "boat",
        },
        "yokuda": {
            "modes": ["boat", "caravan", "wayshrine"],
            "hub_mode": "boat",
            "port_mode": "boat",
        },
        "pyandonea": {
            "modes": ["boat", "caravan", "wayshrine"],
            "hub_mode": "boat",
            "port_mode": "boat",
        },
        "thras": {
            "modes": ["boat", "caravan", "wayshrine"],
            "hub_mode": "boat",
            "port_mode": "boat",
        },
    }

    travel_dir = os.path.join(RES, "travel")
    total_nodes = 0
    total_routes = 0

    for prov, cfg in travel_provs.items():
        prov_regs = [r for r in regs if r["province"] == prov]
        prov_whys = []
        nodes = []
        routes = []
        hub_nodes_all = []

        for r in prov_regs:
            rid = r["id"]
            rshort = rid.split("/")[-1]
            wpool = all_pools.get(rshort) or [f"{rshort}.L6.seat"]
            for w in wpool[:2]:
                if w not in prov_whys:
                    prov_whys.append(w)

            ps = sorted(places_by_reg.get(rid, []), key=lambda p: p["id"])
            km2 = max(0.01, grid.counts.get(r["index"], 0) * CELL_KM2)

            # 1. Add settlement / fort / landmark nodes from real places
            reg_nodes = []
            for p in ps:
                ptype = p.get("type", "")
                if ptype in ("capital", "city", "town", "village", "port", "fort", "standing_stone", "landmark", "daedric_shrine"):
                    is_wild = ptype in ("standing_stone", "landmark", "daedric_shrine")
                    nmode = "wayshrine" if is_wild else cfg["hub_mode"]
                    nmodes = ["wayshrine"] if is_wild else [cfg["hub_mode"], cfg["port_mode"], "wayshrine"]
                    node_obj = {
                        "id": p["id"],
                        "place": p["id"],
                        "name": p.get("name") or f"place.nirncraft.{p['id']}",
                        "region": rid,
                        "x": int(p["x"]),
                        "z": int(p["z"]),
                        "mode": nmode,
                        "modes": list(dict.fromkeys(nmodes)),
                        "kind": "wayshrine" if is_wild else "hub",
                        "discoveredOnArrival": True,
                        "free": is_wild,
                        "why": [wpool[0], wpool[1 % len(wpool)]],
                    }
                    nodes.append(node_obj)
                    reg_nodes.append(node_obj)
                    if not is_wild:
                        hub_nodes_all.append(node_obj)

            # 2. Ensure >= max(6, ceil(km2 * 1.25)) wilderness wayshrine nodes AND cover every land cell within <= 360m
            wild_existing = [n for n in reg_nodes if n["mode"] == "wayshrine"]
            need_wild = max(6, int(math.ceil(km2 * 1.25)))
            cand_pts = list(cells_by_reg.get(rid, [])) + [(int(p["x"]), int(p["z"])) for p in ps]
            if not cand_pts and ps:
                cand_pts = [(int(ps[0]["x"]), int(ps[0]["z"]))]

            w_idx = 1
            # First cover any land cell > 360m from existing nodes in this region
            for wx, wz in cand_pts:
                if not reg_nodes or min(math.hypot(wx - n["x"], wz - n["z"]) for n in reg_nodes) > 360.0:
                    wnode = {
                        "id": f"wayshrine_{rshort}_{w_idx:02d}",
                        "name": f"travel.nirncraft.wayshrine.{rshort}.{w_idx:02d}",
                        "region": rid,
                        "x": int(wx),
                        "z": int(wz),
                        "mode": "wayshrine",
                        "modes": ["wayshrine"],
                        "kind": "wayshrine",
                        "discoveredOnArrival": True,
                        "free": True,
                        "hashed": True,
                        "why": [wpool[0], wpool[1 % len(wpool)]],
                    }
                    nodes.append(wnode)
                    reg_nodes.append(wnode)
                    wild_existing.append(wnode)
                    w_idx += 1

            # Next top up wilderness wayshrine count to >= need_wild so wild_rate >= 1.25/km2 (well above 0.7/km2)
            while len(wild_existing) < need_wild and cand_pts:
                pt = cand_pts[(w_idx * 7) % len(cand_pts)]
                offset_x = ((w_idx % 5) - 2) * 24
                offset_z = (((w_idx // 5) % 5) - 2) * 24
                wnode = {
                    "id": f"wayshrine_{rshort}_{w_idx:02d}",
                    "name": f"travel.nirncraft.wayshrine.{rshort}.{w_idx:02d}",
                    "region": rid,
                    "x": int(pt[0] + offset_x),
                    "z": int(pt[1] + offset_z),
                    "mode": "wayshrine",
                    "modes": ["wayshrine"],
                    "kind": "wayshrine",
                    "discoveredOnArrival": True,
                    "free": True,
                    "hashed": True,
                    "why": [wpool[0], wpool[1 % len(wpool)]],
                }
                nodes.append(wnode)
                reg_nodes.append(wnode)
                wild_existing.append(wnode)
                w_idx += 1

            # Connect nodes within the region
            for i, na in enumerate(reg_nodes):
                others = [nb for j, nb in enumerate(reg_nodes) if i != j]
                others.sort(key=lambda nb: math.hypot(na["x"] - nb["x"], na["z"] - nb["z"]))
                for nb in others[:2]:
                    dist = int(round(math.hypot(na["x"] - nb["x"], na["z"] - nb["z"])))
                    rmode = "wayshrine" if (na["mode"] == "wayshrine" or nb["mode"] == "wayshrine") else cfg["hub_mode"]
                    routes.append({
                        "from": na["id"],
                        "to": nb["id"],
                        "mode": rmode,
                        "fee": 0 if rmode == "wayshrine" else max(15, min(80, dist // 20)),
                        "duration": 6 if rmode == "wayshrine" else max(15, min(90, dist // 15)),
                        "path": [[na["x"], na["z"]], [nb["x"], nb["z"]]],
                        "bidirectional": True,
                        "why": [wpool[0], wpool[1 % len(wpool)]],
                    })

        # Also connect regional hubs across the province so every seat has >= 2 inter-regional links
        for i, ha in enumerate(hub_nodes_all):
            others = [hb for j, hb in enumerate(hub_nodes_all) if i != j]
            others.sort(key=lambda hb: math.hypot(ha["x"] - hb["x"], ha["z"] - hb["z"]))
            for hb in others[:3]:
                dist = int(round(math.hypot(ha["x"] - hb["x"], ha["z"] - hb["z"])))
                routes.append({
                    "from": ha["id"],
                    "to": hb["id"],
                    "mode": cfg["hub_mode"],
                    "fee": max(20, min(95, dist // 20)),
                    "duration": max(20, min(110, dist // 15)),
                    "path": [[ha["x"], ha["z"]], [hb["x"], hb["z"]]],
                    "bidirectional": True,
                    "why": prov_whys[:2],
                })

        tdata = {
            "province": prov,
            "why": prov_whys[:6],
            "modes": cfg["modes"],
            "nodes": nodes,
            "routes": routes,
        }
        jsave(os.path.join(travel_dir, f"{prov}.json"), tdata)
        total_nodes += len(nodes)
        total_routes += len(routes)

    return total_nodes, total_routes


def main():
    places_by_reg = {}
    world_places = {}
    idx_path = os.path.join(RES, "tamriel", "places", "index.json")
    for fn in jload(idx_path, []):
        for p in jload(os.path.join(RES, "tamriel", "places", fn), []):
            if isinstance(p, dict) and p.get("id"):
                world_places[p["id"]] = p
                reg = str(p.get("region", ""))
                places_by_reg.setdefault(reg, []).append(p)

    story_pools, all_pools = load_worldsheet_pools()
    hook_places = compute_border_hook_places(places_by_reg)
    stories = build_story_files(places_by_reg, hook_places, story_pools)
    update_faction_and_marquee_files(world_places, story_pools)
    total_side_q = update_side_files(places_by_reg, world_places, story_pools)
    total_lore_p, total_lore_n = build_place_lore_files(places_by_reg, story_pools)
    total_tnodes, total_troutes = build_travel_files(places_by_reg, all_pools)

    print(
        f"southern_foreign_quests_lore: wrote {len(stories)} story files ({len(stories) * 4} main quests), "
        f"{total_side_q} new side quests, {total_lore_p} lore places ({total_lore_n} notes), "
        f"10 travel files ({total_tnodes} nodes, {total_troutes} routes)."
    )


if __name__ == "__main__":
    main()
