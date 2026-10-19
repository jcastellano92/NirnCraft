#!/usr/bin/env python3
"""
iliac_cityplans.py — Generates and enriches High Rock and Hammerfell cityplans
(mod/src/main/resources/nirncraft/tamriel/cityplans/*.json and index.json)
using .reference/daggerfall_ref/v2/townplan_seeds.json RMB grid roles where available
and curated Iliac Bay architectural specs from docs/world/provinces/{high_rock,hammerfell}.md.
"""

import json
import math
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLANS_DIR = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "tamriel" / "cityplans"
PLACES_DIR = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "tamriel" / "places"
OVERLAYS_DIR = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "tamriel" / "styles" / "overlays"
SEEDS_PATH = ROOT / ".reference" / "daggerfall_ref" / "v2" / "townplan_seeds.json"


def load_places():
    idx = json.loads((PLACES_DIR / "index.json").read_text(encoding="utf-8"))
    places = {}
    for fn in idx:
        p = PLACES_DIR / fn
        if p.exists():
            for entry in json.loads(p.read_text(encoding="utf-8")):
                places[entry["id"]] = entry
    return places


def load_df_seeds():
    if not SEEDS_PATH.exists():
        return {}
    raw = json.loads(SEEDS_PATH.read_text(encoding="utf-8"))
    by_slug = {}
    for item in raw:
        slug = item["name"].lower().replace("'", "").replace("-", "_").replace(" ", "_")
        by_slug[slug] = item
        reg_slug = item["region"].lower().replace("'", "").replace("-", "_").replace(" ", "_")
        if reg_slug not in by_slug:
            by_slug[reg_slug] = item
    return by_slug


def feat_sig(features):
    parts = [f"{f['type']}:{f.get('theme', '')}x{f.get('count', 1)}" for f in features]
    return "|".join(sorted(parts))


# Curated extra named buildings to upgrade existing S-tier plans to >= 24 named buildings
EXISTING_UPGRADES = {
    "wayrest": [
        ("palace", "keep", [0, -56], "south", "wayrest_castle_keep", "Castle Wayrest Keep", "noble"),
        ("inn", "inn", [-26, 22], "east", "wayrest_silver_lantern", "The Silver Lantern", "wealthy"),
        ("store", "general_store", [26, 22], "west", "wayrest_quay_provisions", "Quay Provisions", "wealthy"),
        ("smithy", "smithy", [-34, -12], "east", "wayrest_anchor_and_hammer", "Anchor and Hammer", "common"),
        ("guild_hall", "guild_hall", [-44, -28], "east", "wayrest_mages_guild", "Wayrest Mages Guildhall", "wealthy"),
        ("guild_hall", "guild_hall", [44, -28], "west", "wayrest_fighters_guild", "Wayrest Fighters Guildhall", "wealthy"),
        ("manor", "manor", [-38, -48], "south", "wayrest_gardner_house", "House Gardner Counting-Hall", "noble"),
        ("manor", "manor", [38, -48], "south", "wayrest_cumberland_manor", "House Cumberland Manor", "noble"),
        ("manor", "manor", [-54, -18], "east", "wayrest_horley_townhouse", "House Horley Townhouse", "wealthy"),
        ("manor", "manor", [54, -18], "west", "wayrest_morgiah_salon", "Lady Morgiah's Salon", "noble"),
        ("store", "jeweller", [18, -22], "west", "wayrest_bjoulsae_bullion", "Bjoulsae Bullion Exchange", "noble"),
        ("store", "bookseller", [-18, -22], "east", "wayrest_chancery_scribes", "Wayrest Chancery & Folio House", "wealthy"),
        ("alchemist", "alchemist", [-48, 8], "east", "wayrest_fen_alembic", "The Salt-Marsh Alembic", "common"),
        ("store", "tailor", [48, 8], "west", "wayrest_velvet_and_vair", "Velvet and Vair Clothiers", "wealthy"),
        ("inn", "inn", [-36, 48], "north", "wayrest_cloudy_dregs", "The Cloudy Dregs Inn", "poor"),
        ("building", "warehouse", [36, 52], "north", "wayrest_customs_house", "Bjoulsae Customs & Bonded Vault", "common"),
        ("building", "fishery", [-18, 62], "north", "wayrest_eel_pier", "Bjoulsae Eel & Herring Wharf", "poor"),
        ("temple", "temple", [28, -36], "west", "wayrest_zenithar_bourse", "Chapel-Bourse of Zenithar", "wealthy"),
        ("hall_of_dead", "hall_of_dead", [-62, -42], "east", "wayrest_necropolis_gate", "Wayrest Catacombs Chapel", "common"),
        ("stables", "stables", [0, -82], "south", "wayrest_north_gate_mews", "Royal Post & Coach Mews", "common"),
        ("building", "guardhouse", [18, 62], "north", "wayrest_harbour_watch", "Chain-Boom Admiralty Watch", "common"),
        ("house", "townhouse", [-24, -36], "east", "wayrest_corsair_factors", "Pontanian Factor's House", "wealthy"),
    ],
    "orsinium": [
        ("palace", "keep", [0, -58], "south", "orsinium_gortwog_citadel", "Gortwog's Iron Citadel", "noble"),
        ("inn", "inn", [-22, 16], "east", "orsinium_bone_and_ember", "The Bone and Ember", "common"),
        ("store", "general_store", [22, 16], "west", "orsinium_tradestone", "Tradestone Goods", "common"),
        ("smithy", "smelter", [-42, -16], "east", "orsinium_forge_pit", "The Blood-Iron Smelter Pit", "wealthy"),
        ("smithy", "smithy", [42, -16], "west", "orsinium_orichalc_anvil", "Orichalc Anvil Armoury", "wealthy"),
        ("longhouse", "longhouse", [-44, -44], "south", "orsinium_bagrakh_hall", "Clan Bagrakh Longhouse", "wealthy"),
        ("longhouse", "longhouse", [44, -44], "south", "orsinium_morkul_hall", "Clan Morkul Hearth-Hall", "wealthy"),
        ("longhouse", "longhouse", [-56, 8], "east", "orsinium_shatul_hall", "Clan Shatul Herd-Lodge", "common"),
        ("longhouse", "longhouse", [56, 8], "west", "orsinium_fharun_hall", "Clan Fharun Watch-Hall", "common"),
        ("longhouse", "longhouse", [-38, 42], "north", "orsinium_tumnosh_hall", "Clan Tumnosh Stone-Lodge", "common"),
        ("longhouse", "longhouse", [38, 42], "north", "orsinium_igrun_hall", "Clan Igrun Mason-Hall", "common"),
        ("temple", "temple", [0, -28], "south", "orsinium_trinimac_chancery", "Reforged Hall of Trinimac", "wealthy"),
        ("alchemist", "alchemist", [-28, -18], "east", "orsinium_hearth_wife_herbs", "Hearth-Wife's Crucible", "common"),
        ("store", "butcher", [28, -18], "west", "orsinium_echelitt_smoke", "Echatere Smokehouse & Salt-Vats", "common"),
        ("store", "tannery", [-48, 28], "east", "orsinium_hide_yard", "Wrothgar Bear-Hide Tannery", "common"),
        ("store", "fletcher", [48, 28], "west", "orsinium_horn_bowyer", "Horn-and-Sinew Bowyer", "common"),
        ("building", "warehouse", [-20, 56], "north", "orsinium_granite_magazine", "Siege-Timber & Ore Magazine", "common"),
        ("building", "guardhouse", [20, 56], "north", "orsinium_hammer_gate_watch", "Gate-Hammer Bastion Watch", "common"),
        ("stables", "stables", [0, 74], "north", "orsinium_echatere_pens", "High Pass Echatere Pens", "common"),
        ("hall_of_dead", "hall_of_dead", [-64, -22], "east", "orsinium_ash_cairn", "Hall of the Grudge-Urns", "common"),
        ("inn", "inn", [24, 38], "west", "orsinium_frost_tusk_tavern", "The Frost-Tusk Tavern", "common"),
    ],
    "sentinel": [
        ("palace", "keep", [0, -64], "south", "sentinel_castle_sentinel", "Palace of the Crown", "noble"),
        ("inn", "inn", [-24, 18], "east", "sentinel_oasis_rest", "The Oasis Rest", "wealthy"),
        ("store", "general_store", [24, 18], "west", "sentinel_spice_and_silk", "Spice and Silk Caravanserai", "wealthy"),
        ("smithy", "smithy", [-36, -14], "east", "sentinel_scimitar_works", "Ansei Scimitar Works", "wealthy"),
        ("temple", "temple", [36, -14], "west", "sentinel_tuwhacca_mosque", "Domed Sanctuary of Tu'whacca", "noble"),
        ("guild_hall", "guild_hall", [-46, -34], "east", "sentinel_order_of_the_candle", "Chapterhouse of the Candle", "noble"),
        ("guild_hall", "guild_hall", [46, -34], "west", "sentinel_mages_astrologers", "Hall of the Star-Readers", "wealthy"),
        ("guild_hall", "guild_hall", [-54, 4], "east", "sentinel_fighters_hall", "Wardens of the Coast Guildhall", "wealthy"),
        ("manor", "manor", [-34, -48], "south", "sentinel_camaron_villa", "Royal House Camaron Pavilion", "noble"),
        ("manor", "manor", [34, -48], "south", "sentinel_aya_court", "Vizier's Turquoise Court", "noble"),
        ("store", "jeweller", [18, -26], "west", "sentinel_gold_suq", "Goldsmiths & Filigree Suq", "noble"),
        ("store", "tailor", [-18, -26], "east", "sentinel_indigo_dyers", "Indigo & Saffron Loom-House", "wealthy"),
        ("store", "bookseller", [48, 12], "west", "sentinel_chart_house", "Iliac Pilot & Astrolabe House", "wealthy"),
        ("alchemist", "alchemist", [-48, 12], "east", "sentinel_ambergris_shop", "Ambergris & Myrrh Apothecary", "wealthy"),
        ("inn", "inn", [-32, 52], "north", "sentinel_crimson_dhow", "The Crimson Dhow Tavern", "common"),
        ("building", "warehouse", [32, 52], "north", "sentinel_admiralty_customs", "Royal Admiralty Customs Quay", "wealthy"),
        ("building", "fishery", [-54, 44], "north", "sentinel_pearl_wharf", "Pearl-Divers & Tunny Pier", "common"),
        ("building", "guardhouse", [54, 44], "north", "sentinel_mole_battery", "Harbour Chain & Lantern Battery", "common"),
        ("hall_of_dead", "hall_of_dead", [-64, -24], "east", "sentinel_na_totambu_crypt", "Na-Totambu Ancestor Vault", "noble"),
        ("stables", "stables", [0, 76], "north", "sentinel_camel_caravan_yard", "South Gate Camel & Courser Yard", "common"),
        ("house", "townhouse", [-22, 36], "east", "sentinel_factors_arcade", "Forebear Merchant Arcade", "wealthy"),
        ("house", "townhouse", [22, 36], "west", "sentinel_sky_cistern_house", "House of the Seven Cisterns", "wealthy"),
    ],
    "rihad": [
        ("palace", "keep", [0, -64], "south", "rihad_emir_palace", "Alcazar of the Emir of Rihad", "noble"),
        ("inn", "inn", [-24, 14], "east", "rihad_caravanserai", "The Courtyard Caravanserai", "wealthy"),
        ("store", "general_store", [24, 14], "west", "rihad_spice_gate_stalls", "Spice Gate Suq & Scales", "wealthy"),
        ("smithy", "smithy", [-38, -14], "east", "rihad_brass_and_steel", "Brass and Steel Bladesmiths", "wealthy"),
        ("temple", "temple", [38, -14], "west", "rihad_minaret_mosque", "Great Mosque of the Hour", "noble"),
        ("guild_hall", "guild_hall", [-48, -36], "east", "rihad_fighters_chapter", "Border-Watch Fighters Hall", "wealthy"),
        ("guild_hall", "guild_hall", [48, -36], "west", "rihad_mages_conclave", "Conclave of the Southern Stars", "wealthy"),
        ("manor", "manor", [-34, -52], "south", "rihad_forebear_factor", "Forebear Syndic's Courtyard", "noble"),
        ("manor", "manor", [34, -52], "south", "rihad_colovian_embassy", "Colovian Treaty Factor-House", "wealthy"),
        ("store", "jeweller", [18, -28], "west", "rihad_garnet_bazaar", "Garnet & Brass Assay House", "wealthy"),
        ("store", "tailor", [-18, -28], "east", "rihad_carpet_weavers", "Rihad Silk & Prayer-Rug Looms", "wealthy"),
        ("alchemist", "alchemist", [-52, 8], "east", "rihad_saffron_mortar", "The Saffron Mortar Apothecary", "common"),
        ("store", "bookseller", [52, 8], "west", "rihad_toll_chancery", "Bridge-Toll & Caravan Chancery", "common"),
        ("inn", "inn", [-32, 48], "north", "rihad_border_lantern", "The Border Lantern Hostelry", "common"),
        ("building", "warehouse", [32, 48], "north", "rihad_grain_granary", "Brena River Bonded Granary", "common"),
        ("building", "guardhouse", [0, 74], "north", "rihad_spice_gate_watch", "Spice Gate Customs Watchhouse", "common"),
        ("stables", "stables", [-28, 72], "north", "rihad_dromedary_lines", "Brena Caravan Dromedary Lines", "common"),
        ("hall_of_dead", "hall_of_dead", [-66, -18], "east", "rihad_sepulchre_of_sands", "Sepulchre of the Quiet Sands", "common"),
        ("store", "butcher", [46, 32], "west", "rihad_date_and_fig", "Date-Palm & Salt-Mutton Provisioner", "common"),
        ("store", "tannery", [-46, 32], "east", "rihad_cordovan_leather", "Cordovan Leather & Saddle Works", "common"),
        ("building", "fishery", [-58, 54], "north", "rihad_estuary_quay", "Estuary Shallop & Reed-Boat Quay", "poor"),
        ("house", "townhouse", [20, 32], "west", "rihad_water_clock_house", "House of the Brass Water-Clock", "wealthy"),
        ("house", "townhouse", [-20, 32], "east", "rihad_caravan_guides_lodge", "Desert Guides & Dragoman Lodge", "common"),
        ("shrine", "temple", [0, -36], "south", "rihad_zenithar_scales", "Pavilion of Zenithar's Scales", "wealthy"),
    ],
}


# Definitions for all 68 new High Rock and Hammerfell cityplans:
# (id, province, tier, culture, overlay, sil, lm_id, lm_title, walled, port, named_buildings)
NEW_PLANS = [
    # ==================== HIGH ROCK CITIES ====================
    (
        "daggerfall", "high_rock", "S", "breton", "daggerfall", "river-castle-twin-bank",
        "castle", "Castle Daggerfall", True, True,
        [
            ("palace", "keep", "daggerfall_castle_keep", "Castle Daggerfall Great Keep"),
            ("inn", "inn", "daggerfall_Lions_head_inn", "The Lion's Head Inn"),
            ("inn", "inn", "daggerfall_crimson_ship", "The Crimson Ship Tavern"),
            ("inn", "inn", "daggerfall_singing_kettle", "The Singing Kettle"),
            ("store", "general_store", "daggerfall_west_bay_mercantile", "West Bay Mercantile"),
            ("store", "general_store", "daggerfall_bridge_chandler", "Guild-Bridge Chandlery"),
            ("smithy", "smithy", "daggerfall_dragon_armoury", "Order of the Dragon Armoury"),
            ("smithy", "smithy", "daggerfall_iron_crown_forge", "Iron-Crown Forge & Anvil"),
            ("alchemist", "alchemist", "daggerfall_glenumbra_retort", "The Glenumbra Retort"),
            ("store", "bookseller", "daggerfall_royal_archivium", "Daggerfall Royal Scriptorium"),
            ("store", "jeweller", "daggerfall_iliac_assay", "Iliac Mint & Gold Assay"),
            ("store", "tailor", "daggerfall_tudor_drapers", "High street Wool & Broadcloth"),
            ("store", "tannery", "daggerfall_tannery_row", "Tannery Row Drying-Frames"),
            ("store", "butcher", "daggerfall_boars_head_butcher", "The Boar's Head Victualler"),
            ("store", "fletcher", "daggerfall_yew_bowyer", "Ilessan Yew Bowyer"),
            ("guild_hall", "guild_hall", "daggerfall_mages_guild", "Daggerfall Mages Guildhall"),
            ("guild_hall", "guild_hall", "daggerfall_fighters_guild", "Daggerfall Fighters Guildhall"),
            ("guild_hall", "guild_hall", "daggerfall_knights_of_dragon", "Chapterhouse of the Dragon"),
            ("temple", "temple", "daggerfall_kynareth_benevolence", "Cathedral of Kynareth"),
            ("hall_of_dead", "hall_of_dead", "daggerfall_royal_crypts", "Royal Crypts of Castle Daggerfall"),
            ("manor", "manor", "daggerfall_bridwell_manor", "Lord Bridwell's Town Manor"),
            ("manor", "manor", "daggerfall_lady_pelline_house", "Madam Pelline's Counting-House"),
            ("manor", "manor", "daggerfall_chamberlain_court", "Royal Chamberlain's Chancery"),
            ("building", "warehouse", "daggerfall_harbour_customs", "Cape Mast Customs Warehouse"),
            ("building", "guardhouse", "daggerfall_twin_span_watch", "Twin-Span Bridge Gatehouse"),
            ("stables", "stables", "daggerfall_kings_mews", "King Gothryd's Courser Stables"),
        ]
    ),
    (
        "camlorn", "high_rock", "A", "breton", "camlorn", "spire-hill-gothic",
        "chapel_spire", "Cathedral of Mara's Spires", True, False,
        [
            ("palace", "keep", "camlorn_ducal_castle", "Castle Camlorn Black-Slate Keep"),
            ("inn", "inn", "camlorn_silver_hound", "The Silver Hound Inn"),
            ("inn", "inn", "camlorn_belfry_lantern", "The Belfry Lantern Tavern"),
            ("store", "general_store", "camlorn_gable_provisions", "Seven-Gables General Store"),
            ("smithy", "smithy", "camlorn_silver_steel_forge", "Silver-Inlay Forge & Bladeworks"),
            ("alchemist", "alchemist", "camlorn_wolfsbane_still", "The Wolfsbane & Moonwort Still"),
            ("store", "bookseller", "camlorn_verse_press", "Camlorn Ballad & Folio Press"),
            ("store", "jeweller", "camlorn_filigree_hall", "Lune-Silver Filigree Workshop"),
            ("store", "tailor", "camlorn_sable_mantle", "Sable & Slate Courtier Tailors"),
            ("guild_hall", "guild_hall", "camlorn_mages_scholarium", "Camlorn Mages Scholarium"),
            ("guild_hall", "guild_hall", "camlorn_fighters_chapter", "Glenumbra Blades Chapterhouse"),
            ("temple", "temple", "camlorn_cathedral_of_mara", "Cathedral of Mara"),
            ("hall_of_dead", "hall_of_dead", "camlorn_whispering_ossuary", "The Whispering Ossuary"),
            ("manor", "manor", "camlorn_hvarine_manor", "Lady Hvarine's Spire Manor"),
            ("building", "guardhouse", "camlorn_bell_tower_watch", "Bell-Ringers' Watchtower"),
            ("stables", "stables", "camlorn_moor_gate_stables", "Moor-Gate Post Stables"),
        ]
    ),
    (
        "shornhelm", "high_rock", "A", "breton", "rivenspire", "walled-keep-grid-red",
        "castle", "The Red Keep of Shornhelm", True, False,
        [
            ("palace", "keep", "shornhelm_red_keep", "The Red Keep of Shornhelm"),
            ("inn", "inn", "shornhelm_dead_wolf_inn", "The Dead Wolf Inn"),
            ("inn", "inn", "shornhelm_iron_stirrup", "The Iron Stirrup Tavern"),
            ("store", "general_store", "shornhelm_pass_commissary", "Pass-Road Commissary"),
            ("smithy", "smithy", "shornhelm_red_anvil", "Shornhelm Red-Anvil Armoury"),
            ("store", "fletcher", "shornhelm_alders_bowyer", "Alder's Siege-Crossbow & Bowyer"),
            ("alchemist", "alchemist", "shornhelm_garlic_and_salt", "Vervain & Silver-Salt Apothecary"),
            ("store", "bookseller", "shornhelm_peerage_rolls", "Chancery of the Crag-Peerage"),
            ("guild_hall", "guild_hall", "shornhelm_fighters_muster", "Shornhelm Muster & Fighters Hall"),
            ("guild_hall", "guild_hall", "shornhelm_mages_ward", "Order of the Lamp Ward-House"),
            ("temple", "temple", "shornhelm_stendarr_basilica", "Fortified Basilica of Stendarr"),
            ("hall_of_dead", "hall_of_dead", "shornhelm_iron_grate_crypt", "Iron-Grated Vault of Arkay"),
            ("manor", "manor", "shornhelm_montclair_house", "House Montclair Upper Manor"),
            ("manor", "manor", "shornhelm_tamrith_house", "House Tamrith Garrison Hall"),
            ("barracks", "barracks", "shornhelm_marshal_barracks", "Marshal Ysolde's Pike Barracks"),
            ("stables", "stables", "shornhelm_destrier_stables", "Armoured Destrier Mews"),
        ]
    ),
    (
        "evermor", "high_rock", "A", "breton", "bjoulsae", "lake-causeway-island",
        "chapel_spire", "Evermor Island Cathedral", True, True,
        [
            ("palace", "keep", "evermor_river_citadel", "Castle Evermor Island Keep"),
            ("inn", "inn", "evermor_anchors_rest", "The River-Anchor Inn"),
            ("inn", "inn", "evermor_causeway_lantern", "The Causeway Lantern"),
            ("store", "general_store", "evermor_barge_exchange", "Bjoulsae Barge-Factors' Store"),
            ("smithy", "smithy", "evermor_white_iron_forge", "Bangkorai Gate Ironworks"),
            ("alchemist", "alchemist", "evermor_river_reed_apothecary", "River-Reed & Bog-Myrtle Apothecary"),
            ("store", "jeweller", "evermor_alluvial_gold", "Alluvial Gold & Silver Assay"),
            ("store", "bookseller", "evermor_tariff_rolls", "River-Compact Charter House"),
            ("guild_hall", "guild_hall", "evermor_fighters_hall", "Pass-Escort Fighters Guildhall"),
            ("guild_hall", "guild_hall", "evermor_mages_hall", "Evermor Mages Guildhall"),
            ("temple", "temple", "evermor_island_cathedral", "Cathedral of the River-Saints"),
            ("hall_of_dead", "hall_of_dead", "evermor_island_crypt", "Lake-Vault of Arkay"),
            ("manor", "manor", "evermor_guimard_manor", "Ducal House Guimard Manor"),
            ("building", "fishery", "evermor_fishers_quay_hall", "Fisher's Quay Weighing-Hall"),
            ("building", "guardhouse", "evermor_causeway_toll_arch", "Causeway Toll-Arch Watchhouse"),
            ("stables", "stables", "evermor_towpath_stables", "Towpath Draft-Horse Stables"),
        ]
    ),
    (
        "northpoint", "high_rock", "A", "breton", "rivenspire", "cliff-port-moor",
        "sea_stack", "Northpoint Beacon & Cliff-Keep", True, True,
        [
            ("palace", "keep", "northpoint_dorell_keep", "Castle Northpoint Sea-Keep"),
            ("inn", "inn", "northpoint_gale_and_lantern", "The Gale and Lantern Inn"),
            ("inn", "inn", "northpoint_whalers_dram", "The Whaler's Dram"),
            ("store", "general_store", "northpoint_salt_chandler", "North Sea Ship-Chandler"),
            ("smithy", "smithy", "northpoint_harpoon_forge", "Harpoon & Grapnel Ironworks"),
            ("alchemist", "alchemist", "northpoint_sea_wrack_still", "Sea-Wrack & Peat-Smoke Still"),
            ("store", "tailor", "northpoint_oilskin_loft", "Oilskin & Sail-Canvas Loft"),
            ("guild_hall", "guild_hall", "northpoint_navigators_hall", "Northern Navigators' Guildhall"),
            ("guild_hall", "guild_hall", "northpoint_fighters_watch", "Cliff-Watch Mercenary Hall"),
            ("temple", "temple", "northpoint_moor_chapel", "Half-Roofed Moor Chapel of Kynareth"),
            ("hall_of_dead", "hall_of_dead", "northpoint_drowned_cairn", "Cairn of the Lost Fleets"),
            ("manor", "manor", "northpoint_dorell_manor", "Baron Dorell's Cliffside Manor"),
            ("building", "fishery", "northpoint_fishers_smokehouse", "Herring & Cod Smokehouse"),
            ("building", "warehouse", "northpoint_blubber_works", "Whale-Oil & Timber Bonded Shed"),
            ("building", "guardhouse", "northpoint_fog_horn_watch", "Lighthouse Watch & Fog-Horn House"),
            ("stables", "stables", "northpoint_peat_pony_pens", "Moorland Pack-Pony Stables"),
        ]
    ),
    (
        "farrun", "high_rock", "A", "breton", "bjoulsae", "sea-wall-frost-keep",
        "castle", "Farrun Sea-Wall Keep", True, True,
        [
            ("palace", "keep", "farrun_frost_keep", "Castle Farrun Sea-Bastion"),
            ("inn", "inn", "farrun_frozen_hawser", "The Frozen Hawser Inn"),
            ("inn", "inn", "farrun_split_shield_hall", "The Split-Shield Meadhouse"),
            ("store", "general_store", "farrun_pack_train_outfitter", "Wrothgar Pack-Train Outfitter"),
            ("smithy", "smithy", "farrun_ice_axe_forge", "Ice-Axe & Anchor Smithy"),
            ("alchemist", "alchemist", "farrun_pine_tar_apothecary", "Pine-Tar & Bear-Gall Apothecary"),
            ("store", "tannery", "farrun_seal_pelt_yard", "Seal-Pelt & Wolf-Fur Yard"),
            ("store", "butcher", "farrun_salt_whale_market", "Salt-Whale & Venison Hall"),
            ("guild_hall", "guild_hall", "farrun_fighters_lodge", "Frost-Coast Fighters Lodge"),
            ("temple", "temple", "farrun_shrine_of_stendarr", "Chapel of Stendarr on the Breakwater"),
            ("hall_of_dead", "hall_of_dead", "farrun_frost_barrow_vault", "Frost-Vault of the Dual Rite"),
            ("manor", "manor", "farrun_reeve_longhall", "Sea-Reeve's Half-Nord Longhall"),
            ("building", "fishery", "farrun_longship_slip", "Nordic & Breton Longship Slipway"),
            ("building", "guardhouse", "farrun_breakwater_tower", "Breakwater Ice-Watch Tower"),
            ("stables", "stables", "farrun_sledge_stables", "Mountain Mule & Sledge Yard"),
        ]
    ),
    (
        "jehanna", "high_rock", "A", "breton", "wrothgar", "fjord-timber-cliff-keep",
        "cliff", "Jehanna Fjord-Crown Hall", True, True,
        [
            ("palace", "keep", "jehanna_high_hall", "Jehanna Fjord-Crown Keep"),
            ("inn", "inn", "jehanna_carved_prow", "The Carved Prow Inn"),
            ("inn", "inn", "jehanna_hearth_and_horn", "The Hearth and Horn"),
            ("store", "general_store", "jehanna_fjord_traders", "Solitude-Road Fur & Amber Traders"),
            ("smithy", "smithy", "jehanna_sky_iron_smithy", "Fjord-Iron Smithy & Foundry"),
            ("alchemist", "alchemist", "jehanna_snowberry_brew", "Snowberry & Lichen Herbalist"),
            ("store", "tailor", "jehanna_mammoth_wool_looms", "Highland Wool & Fur-Cloak Looms"),
            ("store", "fletcher", "jehanna_ash_spear_wright", "Ash-Spear & Harpoon Wright"),
            ("guild_hall", "guild_hall", "jehanna_border_wardens", "Druadach Pass Wardens' Hall"),
            ("temple", "temple", "jehanna_kynareth_wind_fane", "Wind-Fane of Kynareth & Kyne"),
            ("hall_of_dead", "hall_of_dead", "jehanna_cliff_ossuary", "Rock-Cut Fjord Ossuary"),
            ("manor", "manor", "jehanna_treaty_thane_hall", "Treaty-Thane's Timber Manor"),
            ("building", "fishery", "jehanna_salmon_weir", "Fjord Salmon & Seal-Oil Stages"),
            ("building", "guardhouse", "jehanna_east_pass_gate", "Snow-Pass Toll & Watch Gate"),
            ("stables", "stables", "jehanna_shaggy_garron_mews", "Highland Garron Stables"),
        ]
    ),
    # ==================== HIGH ROCK TOWNS (20) ====================
    (
        "glenpoint", "high_rock", "B", "breton", "glenumbra", "headland-fort-lighthouse",
        "sea_stack", "Glenpoint Headland Keep", True, True,
        [
            ("palace", "keep", "glenpoint_headland_keep", "Glenpoint Headland Keep"),
            ("inn", "inn", "glenpoint_salted_gull", "The Salted Gull Inn"),
            ("store", "general_store", "glenpoint_coaster_chandler", "Headland Coaster Chandlery"),
            ("smithy", "smithy", "glenpoint_chain_and_cleat", "Chain and Cleat Smithy"),
            ("alchemist", "alchemist", "glenpoint_kelp_ash_still", "Kelp-Ash & Samphire Still"),
            ("temple", "temple", "glenpoint_chapel_of_julianos", "Chapel of Julianos the Navigator"),
            ("building", "fishery", "glenpoint_pilots_house", "Iliac Bar Pilot's House"),
            ("building", "warehouse", "glenpoint_salt_pan_yard", "Headland Salt-Pan Boiling House"),
            ("building", "guardhouse", "glenpoint_beacon_watch", "Storm-Beacon Watchhouse"),
            ("stables", "stables", "glenpoint_cliff_post_mews", "Cliff-Road Post Stables"),
        ]
    ),
    (
        "northmoor", "high_rock", "B", "breton", "glenumbra", "peat-ridge-watch-abbey",
        "stone_circle", "Northmoor Standing-Stone Ward", True, False,
        [
            ("palace", "keep", "northmoor_baronial_hall", "Northmoor Baronial Keep"),
            ("inn", "inn", "northmoor_black_peat_inn", "The Black Peat Hearth"),
            ("store", "general_store", "northmoor_fen_provisioner", "Moorland Fen Provisioner"),
            ("smithy", "smithy", "northmoor_bog_iron_smithy", "Bog-Iron & Turf-Spade Smithy"),
            ("alchemist", "alchemist", "northmoor_witch_hazel_shop", "Witch-Hazel & Nightshade Apothecary"),
            ("temple", "temple", "northmoor_arkay_vigil", "Vigil-Chapel of Arkay"),
            ("hall_of_dead", "hall_of_dead", "northmoor_barrows_gate", "Warded Barrow-Gate Crypt"),
            ("guild_hall", "guild_hall", "northmoor_witch_hunters", "Order of the Silver Stake Lodge"),
            ("building", "guardhouse", "northmoor_fog_gate_watch", "Fen-Causeway Watchhouse"),
            ("stables", "stables", "northmoor_pack_horse_yard", "Peat-Wagon Draft Stables"),
        ]
    ),
    (
        "daenia", "high_rock", "B", "breton", "glenumbra", "fen-edge-causeway-keep",
        "castle", "Daenia Fen-Watch Keep", True, False,
        [
            ("palace", "keep", "daenia_march_keep", "Daenia Fen-Watch Keep"),
            ("inn", "inn", "daenia_bitterns_call", "The Bittern's Call Inn"),
            ("store", "general_store", "daenia_causeway_goods", "Causeway Wool & Reed Goods"),
            ("smithy", "smithy", "daenia_willow_char_forge", "Willow-Charcoal Forge"),
            ("alchemist", "alchemist", "daenia_fen_fever_draughts", "Fen-Tonic & Leech Apothecary"),
            ("temple", "temple", "daenia_chapel_of_mara", "Chapel of Mara of the Reeds"),
            ("guild_hall", "guild_hall", "daenia_rangers_lodge", "Hagfen Border-Rangers' Hall"),
            ("manor", "manor", "daenia_vidron_manor", "Lord Vidron's Dyke-Manor"),
            ("building", "guardhouse", "daenia_sluice_watch", "Fen-Dyke Sluice Watch"),
            ("stables", "stables", "daenia_causeway_stables", "Causeway Post Stables"),
        ]
    ),
    (
        "glenumbra_moors", "high_rock", "B", "breton", "glenumbra", "heather-tor-ring-keep",
        "stone_circle", "Moors Tor Ring-Stones", True, False,
        [
            ("palace", "keep", "glenumbra_moors_grimley_keep", "Grimley Tor Fortified Keep"),
            ("inn", "inn", "glenumbra_moors_hounded_stag", "The Hounded Stag Inn"),
            ("store", "general_store", "glenumbra_moors_wool_staple", "Moor-Sheep Wool Staple"),
            ("smithy", "smithy", "glenumbra_moors_shears_forge", "Iron-Shears & Horseshoe Smithy"),
            ("alchemist", "alchemist", "glenumbra_moors_heather_still", "Heather-Honey & Belladonna Still"),
            ("temple", "temple", "glenumbra_moors_stendarr_fane", "Tor-Side Fane of Stendarr"),
            ("hall_of_dead", "hall_of_dead", "glenumbra_moors_cairn_vault", "Lintel-Stone Burial Vault"),
            ("guild_hall", "guild_hall", "glenumbra_moors_hound_masters", "Moor-Hound Huntsmen's Hall"),
            ("building", "guardhouse", "glenumbra_moors_beacon_tor", "Beacon-Tor Watchhouse"),
            ("stables", "stables", "glenumbra_moors_drovers_pen", "Sheep-Drovers' Fold & Stables"),
        ]
    ),
    (
        "ilessan_hills", "high_rock", "B", "breton", "daggerfall", "quarry-terraced-hill-town",
        "headframe", "Ilessan Tin-Adit Headframe", True, False,
        [
            ("palace", "keep", "ilessan_hills_barons_hall", "Ilessan Ridge Baronial Keep"),
            ("inn", "inn", "ilessan_hills_pick_and_flagon", "The Pick and Flagon Inn"),
            ("store", "general_store", "ilessan_hills_assay_store", "Tin-Ingot & Candle Provisioner"),
            ("smithy", "smithy", "ilessan_hills_bronze_foundry", "Hill-Tin & Iron Foundry"),
            ("alchemist", "alchemist", "ilessan_hills_vitriol_works", "Copperas & Vitriol Alchemist"),
            ("temple", "temple", "ilessan_hills_zenithar_chapel", "Miners' Chapel of Zenithar"),
            ("guild_hall", "guild_hall", "ilessan_hills_sappers_guild", "Free-Miners & Sappers Guildhall"),
            ("mine", "smelter", "ilessan_hills_stamp_mill", "Upper Seam Ore-Stamp & Smelter"),
            ("building", "guardhouse", "ilessan_hills_bullion_watch", "Ore-Wagon Escort Watchhouse"),
            ("stables", "stables", "ilessan_hills_mule_lines", "Pack-Mule & Wain Stables"),
        ]
    ),
    (
        "aldcroft", "high_rock", "B", "breton", "daggerfall", "timber-palisade-river-bend",
        "castle", "Aldcroft River-Bend Keep", True, True,
        [
            ("palace", "keep", "aldcroft_laird_hall", "Aldcroft Timber-and-Stone Keep"),
            ("inn", "inn", "aldcroft_millers_measure", "The Miller's Measure Inn"),
            ("store", "general_store", "aldcroft_grain_chandler", "River-Bend Grain & Cask Store"),
            ("smithy", "smithy", "aldcroft_ploughshare_forge", "Ploughshare & Mill-Iron Smithy"),
            ("alchemist", "alchemist", "aldcroft_meadowsweet_shop", "Meadowsweet & Elderflower Herbalist"),
            ("temple", "temple", "aldcroft_dibella_chapel", "Chapel of Dibella of the May-Bough"),
            ("mill", "mill", "aldcroft_water_gristmill", "Aldcroft Weir Gristmill"),
            ("manor", "manor", "aldcroft_coopers_guild", "Coopers & Cider-Press Hall"),
            ("building", "guardhouse", "aldcroft_ford_watch", "River-Ford Toll Watchhouse"),
            ("stables", "stables", "aldcroft_wain_stables", "Cider-Wain & Teamster Stables"),
        ]
    ),
    (
        "crosswych", "high_rock", "B", "breton", "glenumbra", "gorge-bridge-quarry-camp",
        "headframe", "Crosswych Gorge Quarry Crane", True, False,
        [
            ("palace", "keep", "crosswych_bridge_keep", "Crosswych Gorge-Bridge Keep"),
            ("inn", "inn", "crosswych_split_slate_inn", "The Split-Slate Inn"),
            ("store", "general_store", "crosswych_masons_cantina", "Quarrymen's Dry-Goods Store"),
            ("smithy", "smithy", "crosswych_chisel_and_wedge", "Chisel and Wedge Tool-Smithy"),
            ("alchemist", "alchemist", "crosswych_stone_dust_salves", "Stone-Dust Salves & Poultices"),
            ("temple", "temple", "crosswych_arkay_shrine_hall", "Wayfarers' Chapel of Arkay"),
            ("building", "warehouse", "crosswych_ashlar_yard", "Dressed-Slate & Ashlar Yard"),
            ("building", "guardhouse", "crosswych_span_gatehouse", "High-Span Toll Gatehouse"),
            ("stables", "stables", "crosswych_ox_team_pens", "Stone-Sledge Ox & Horse Pens"),
        ]
    ),
    (
        "crestshade", "high_rock", "B", "breton", "rivenspire", "shaded-ravine-chapel-town",
        "chapel_spire", "Crestshade Ravine Belfry", True, False,
        [
            ("palace", "keep", "crestshade_manor_keep", "Crestshade Ravine Manor-Keep"),
            ("inn", "inn", "crestshade_dusk_lantern", "The Dusk-Lantern Inn"),
            ("store", "general_store", "crestshade_hollow_mercantile", "Ravine-Road Mercantile"),
            ("smithy", "smithy", "crestshade_silver_edge_smithy", "Silver-Edge Blade & Latch Smithy"),
            ("alchemist", "alchemist", "crestshade_rue_and_vervain", "Rue and Vervain Apothecary"),
            ("temple", "temple", "crestshade_chapel_of_mercy", "Chapel of Mara's Mercy"),
            ("hall_of_dead", "hall_of_dead", "crestshade_sealed_undercroft", "Sealed Undercroft of Arkay"),
            ("manor", "manor", "crestshade_ravine_vintner", "Crag-Terrace Cider & Wine Cellars"),
            ("building", "guardhouse", "crestshade_lanthorn_watch", "Night-Lanthorn Watchhouse"),
            ("stables", "stables", "crestshade_coach_stables", "Shornhelm-Road Coach Stables"),
        ]
    ),
    (
        "tulune", "high_rock", "B", "breton", "daggerfall", "hedgerow-manor-crossroads",
        "chapel_spire", "Tulune Chivalric Priory Spire", True, False,
        [
            ("palace", "keep", "tulune_chivalric_keep", "Castle Tulune Hedgerow Keep"),
            ("inn", "inn", "tulune_gilded_spur", "The Gilded Spur Inn"),
            ("store", "general_store", "tulune_bailiff_chandler", "Bailiff's Market & Cloth Store"),
            ("smithy", "smithy", "tulune_tilting_lance_forge", "Tilting-Lance & Mail Smithy"),
            ("alchemist", "alchemist", "tulune_rosewater_distillery", "Rosewater & Balm Distillery"),
            ("temple", "temple", "tulune_priory_of_akatosh", "Priory Chapel of Akatosh"),
            ("guild_hall", "guild_hall", "tulune_heralds_college", "West-March Heralds' Chapter"),
            ("manor", "manor", "tulune_orchard_grange", "Ducal Apple-Orchard Grange"),
            ("building", "guardhouse", "tulune_turnpike_watch", "Turnpike Gate Watchhouse"),
            ("stables", "stables", "tulune_palfrey_mews", "Palfrey & Courser Mews"),
        ]
    ),
    (
        "alcaire", "high_rock", "B", "breton", "stormhaven", "island-castle-vineyard-hills",
        "castle", "Alcaire Castle on the Hill", True, True,
        [
            ("palace", "keep", "alcaire_castle_keep", "Castle Alcaire Birthplace Keep"),
            ("inn", "inn", "alcaire_generals_birthplace", "The General's Cradle Inn"),
            ("store", "general_store", "alcaire_island_provisions", "Alcaire Island Factors"),
            ("smithy", "smithy", "alcaire_legion_standard_forge", "Old-Standard Sword & Plate Smithy"),
            ("alchemist", "alchemist", "alcaire_vine_leaf_apothecary", "Vine-Leaf & Rosemary Apothecary"),
            ("temple", "temple", "alcaire_akatosh_chapel", "Chapel of Akatosh & the Dragon-Banner"),
            ("guild_hall", "guild_hall", "alcaire_knights_of_flame", "Chapterhouse of the Knights of the Flame"),
            ("manor", "manor", "alcaire_vintage_press", "Limestone Terrace Wine-Press"),
            ("building", "guardhouse", "alcaire_ferry_watch", "Sound-Ferry Watchhouse"),
            ("stables", "stables", "alcaire_cavalry_mews", "Island Cavalry Remount Mews"),
        ]
    ),
    (
        "dwynnen", "high_rock", "B", "breton", "dwynnen", "peninsula-sea-keep-harbour",
        "castle", "Dwynnen Sea-Bastion", True, True,
        [
            ("palace", "keep", "dwynnen_baronial_bastion", "Castle Dwynnen Sea-Bastion"),
            ("inn", "inn", "dwynnen_storm_petrel_inn", "The Storm-Petrel Inn"),
            ("store", "general_store", "dwynnen_fleet_victualler", "Bay-Fleet Biscuit & Cordage Store"),
            ("smithy", "smithy", "dwynnen_iron_keel_forge", "Iron-Keel & Cutlass Forge"),
            ("alchemist", "alchemist", "dwynnen_scurvy_grass_shop", "Scurvy-Grass & Salt-Spirit Apothecary"),
            ("temple", "temple", "dwynnen_kynareth_sailors_fane", "Sailors' Fane of Kynareth"),
            ("guild_hall", "guild_hall", "dwynnen_convoy_captains_hall", "Iliac Convoy Captains' Hall"),
            ("building", "fishery", "dwynnen_rope_walk_wharf", "Dwynnen Rope-Walk & Careening Wharf"),
            ("building", "guardhouse", "dwynnen_headland_battery", "Sea-Gate Cannon & Ballista Watch"),
            ("stables", "stables", "dwynnen_post_road_stables", "Coast-Road Courier Stables"),
        ]
    ),
    (
        "kambria", "high_rock", "B", "breton", "stormhaven", "double-walled-ridge-granary",
        "castle", "Kambria Ridge Keep", True, False,
        [
            ("palace", "keep", "kambria_ridge_keep", "Castle Kambria Ridge Keep"),
            ("inn", "inn", "kambria_wheatsheaf_inn", "The Golden Wheatsheaf Inn"),
            ("store", "general_store", "kambria_granary_exchange", "Stormhaven Corn-Exchange Store"),
            ("smithy", "smithy", "kambria_sickle_and_chain", "Sickle and Trace-Chain Smithy"),
            ("alchemist", "alchemist", "kambria_chamomile_apothecary", "Chamomile & Honey-Balm Apothecary"),
            ("temple", "temple", "kambria_zenithar_tithe_chapel", "Tithe-Chapel of Zenithar"),
            ("mill", "mill", "kambria_wind_gristmill", "Ridge-Crest Stone Gristmill"),
            ("building", "warehouse", "kambria_royal_tithe_barn", "Great Limestone Tithe-Barn"),
            ("building", "guardhouse", "kambria_west_gate_watch", "Wayrest-Road Gate Watch"),
            ("stables", "stables", "kambria_carters_yard", "Grain-Carters' Team Yard"),
        ]
    ),
    (
        "shalgora", "high_rock", "B", "breton", "anticlere", "karst-hollow-walled-fief",
        "chapel_spire", "Shalgora Hollow Priory", True, False,
        [
            ("palace", "keep", "shalgora_fief_keep", "Shalgora Karst-Hollow Keep"),
            ("inn", "inn", "shalgora_cuckoo_and_crown", "The Cuckoo and Crown Inn"),
            ("store", "general_store", "shalgora_bailiwick_store", "Bailiwick Wool & Tallow Store"),
            ("smithy", "smithy", "shalgora_horseshoe_forge", "Karst-Iron Forge & Farrier"),
            ("alchemist", "alchemist", "shalgora_cave_moss_still", "Cave-Moss & Lavender Still"),
            ("temple", "temple", "shalgora_julianos_priory", "Scriptorium-Chapel of Julianos"),
            ("manor", "manor", "shalgora_vidame_manor", "Vidame's Limestone Manor"),
            ("store", "tailor", "shalgora_tapestry_looms", "Shalgora Heraldic Tapestry Looms"),
            ("building", "guardhouse", "shalgora_pass_watch", "Hollow-Gate Watchhouse"),
            ("stables", "stables", "shalgora_post_mews", "Post-Chaise Stables"),
        ]
    ),
    (
        "urvaius", "high_rock", "B", "breton", "anticlere", "terraced-Downs-shepherd-keep",
        "castle", "Urvaius Downs Keep", True, False,
        [
            ("palace", "keep", "urvaius_downs_keep", "Castle Urvaius Chalk-Ridge Keep"),
            ("inn", "inn", "urvaius_ram_and_bell", "The Ram and Bell Inn"),
            ("store", "general_store", "urvaius_fleece_hall", "Urvaius Fleece & Cheese Factor"),
            ("smithy", "smithy", "urvaius_bell_founders", "Sheep-Bell & Ironwork Smithy"),
            ("alchemist", "alchemist", "urvaius_lanolin_salve_shop", "Lanolin & Thyme Salve-Maker"),
            ("temple", "temple", "urvaius_mara_shepherds_chapel", "Shepherds' Chapel of Mara"),
            ("store", "tailor", "urvaius_broadcloth_fullers", "Chalk-Stream Fulling & Broadcloth Hall"),
            ("manor", "manor", "urvaius_castellan_grange", "Castellan's Downland Grange"),
            ("building", "guardhouse", "urvaius_ridge_watch", "Ridgeway Watchhouse"),
            ("stables", "stables", "urvaius_drover_stables", "Downland Drover Stables"),
        ]
    ),
    (
        "anticlere", "high_rock", "B", "breton", "anticlere", "warm-bay-vineyard-citadel",
        "castle", "Castle Anticlere", True, True,
        [
            ("palace", "keep", "anticlere_ducal_keep", "Castle Anticlere Bay-Citadel"),
            ("inn", "inn", "anticlere_golden_chalice", "The Golden Chalice Inn"),
            ("store", "general_store", "anticlere_vintage_mercantile", "Anticlere Wine & Olive Mercantile"),
            ("smithy", "smithy", "anticlere_gilt_hilt_smithy", "Gilt-Hilt Rapier & Mail Smithy"),
            ("alchemist", "alchemist", "anticlere_neroli_perfumery", "Orange-Blossom & Alchemical Still"),
            ("temple", "temple", "anticlere_dibella_rotunda", "Sunlit Rotunda of Dibella"),
            ("guild_hall", "guild_hall", "anticlere_vintners_guild", "Honourable Vintners' Guildhall"),
            ("manor", "manor", "anticlere_treaty_pavilion", "Bay-Treaty Envoys' Manor"),
            ("building", "guardhouse", "anticlere_harbour_gate", "Vineyard-Quay Customs Watch"),
            ("stables", "stables", "anticlere_courser_stables", "Bay-Road Courser Stables"),
        ]
    ),
    (
        "ykalon", "high_rock", "B", "breton", "anticlere", "pine-ridge-charter-town",
        "chapel_spire", "Ykalon Charter Spire", True, False,
        [
            ("palace", "keep", "ykalon_charter_keep", "Ykalon Charter-Hill Keep"),
            ("inn", "inn", "ykalon_boar_and_bugle", "The Boar and Bugle Inn"),
            ("store", "general_store", "ykalon_forest_staple", "Pine-Rosin & Timber Staple"),
            ("smithy", "smithy", "ykalon_charcoal_iron_forge", "Forest-Charcoal Ironworks"),
            ("alchemist", "alchemist", "ykalon_truffle_and_fern", "Wood-Sorrel & Agaric Apothecary"),
            ("temple", "temple", "ykalon_kynareth_grove_chapel", "Chapel of Kynareth of the Pines"),
            ("store", "fletcher", "ykalon_foresters_bowyer", "Foresters' Longbow & Shaft Shop"),
            ("manor", "manor", "ykalon_verderers_court", "Royal Verderer's Court-Hall"),
            ("building", "guardhouse", "ykalon_north_road_watch", "North-Ridge Watchhouse"),
            ("stables", "stables", "ykalon_huntsmen_stables", "Huntsmen's Relay Stables"),
        ]
    ),
    (
        "phrygias", "high_rock", "B", "breton", "bjoulsae", "hill-pass-granite-watch",
        "castle", "Phrygias Pass Keep", True, False,
        [
            ("palace", "keep", "phrygias_pass_keep", "Castle Phrygias High-Col Keep"),
            ("inn", "inn", "phrygias_windy_saddle", "The Windy Saddle Inn"),
            ("store", "general_store", "phrygias_drovers_cantina", "High-Pass Drovers' Store"),
            ("smithy", "smithy", "phrygias_calkin_forge", "Frost-Calkin & Tire Smithy"),
            ("alchemist", "alchemist", "phrygias_gentian_bitter", "Alpine Gentian & Arnica Apothecary"),
            ("temple", "temple", "phrygias_stendarr_hospitium", "Pass-Hospitium of Stendarr"),
            ("manor", "manor", "phrygias_toll_baron_hall", "Toll-Baron's Granite Hall"),
            ("building", "warehouse", "phrygias_wool_bale_shed", "Wrothgar-Road Wool Bale Shed"),
            ("building", "guardhouse", "phrygias_col_gatehouse", "Col-Gate Chain Watchhouse"),
            ("stables", "stables", "phrygias_remount_barn", "Mountain Remount Barn"),
        ]
    ),
    (
        "bhoriane", "high_rock", "B", "breton", "bjoulsae", "river-bluff-scholars-town",
        "library_terrace", "Bhoriane Bluff Athenaeum", True, True,
        [
            ("palace", "keep", "bhoriane_bluff_keep", "Bhoriane River-Bluff Keep"),
            ("inn", "inn", "bhoriane_inkhorn_and_cup", "The Inkhorn and Cup Inn"),
            ("store", "general_store", "bhoriane_parchment_chandler", "Vellum, Gall-Ink & Goods Store"),
            ("smithy", "smithy", "bhoriane_astrolabe_brass_forge", "Fine-Latch & Ironwork Smithy"),
            ("alchemist", "alchemist", "bhoriane_aludel_house", "The Glass Aludel Apothecary"),
            ("store", "bookseller", "bhoriane_bluff_bindery", "Bhoriane Scriptorium & Bindery"),
            ("temple", "temple", "bhoriane_julianos_oratory", "Oratory of Julianos"),
            ("manor", "manor", "bhoriane_chancellors_villa", "Lord Chancellor's Bluff Villa"),
            ("building", "guardhouse", "bhoriane_ferry_steps_watch", "River-Steps Watchhouse"),
            ("stables", "stables", "bhoriane_post_stables", "Evermor-Road Post Stables"),
        ]
    ),
    (
        "menevia", "high_rock", "B", "breton", "bjoulsae", "abbey-valley-orchard-wall",
        "chapel_spire", "Menevia Abbey Bell-Spire", True, False,
        [
            ("palace", "keep", "menevia_castellany", "Castle Menevia Valley Keep"),
            ("inn", "inn", "menevia_pilgrims_staff", "The Pilgrim's Staff Inn"),
            ("store", "general_store", "menevia_abbey_fair_store", "Abbey-Fair Provisioners"),
            ("smithy", "smithy", "menevia_bell_and_plough", "Bell-Metal & Plough Smithy"),
            ("alchemist", "alchemist", "menevia_monks_infirmary", "Cloister Herb-Garden Apothecary"),
            ("temple", "temple", "menevia_abbey_of_mara", "Great Abbey Church of Mara"),
            ("manor", "manor", "menevia_almoners_grange", "High Almoner's Tithe-Grange"),
            ("store", "tailor", "menevia_linen_bleachery", "Valley Flax & Linen Bleachery"),
            ("building", "guardhouse", "menevia_sanctuary_gate", "Sanctuary-Wall Gatehouse"),
            ("stables", "stables", "menevia_pilgrim_mews", "Pilgrim & Sumpter Mews"),
        ]
    ),
    (
        "gavaudon", "high_rock", "B", "breton", "wrothgar", "crag-shelf-border-bastion",
        "castle", "Gavaudon Crag-Bastion", True, False,
        [
            ("palace", "keep", "gavaudon_marcher_keep", "Gavaudon Crag-Shelf Keep"),
            ("inn", "inn", "gavaudon_iron_gauntlet_inn", "The Iron Gauntlet Inn"),
            ("store", "general_store", "gavaudon_march_sutler", "Wrothgar-March Sutler's Store"),
            ("smithy", "smithy", "gavaudon_quarry_wedge_forge", "Heavy-Bill & Armour Smithy"),
            ("alchemist", "alchemist", "gavaudon_mountain_pitch_shop", "Mountain-Pitch & Salve Shop"),
            ("temple", "temple", "gavaudon_stendarr_garrison_chapel", "Garrison Chapel of Stendarr"),
            ("barracks", "barracks", "gavaudon_marcher_barracks", "Marcher Crossbowmen Barracks"),
            ("manor", "manor", "gavaudon_warden_hall", "March-Warden's Stone Hall"),
            ("building", "guardhouse", "gavaudon_orc_gate_watch", "High-Trail Watchtower"),
            ("stables", "stables", "gavaudon_pack_garron_yard", "Crag-Garron Stables"),
        ]
    ),
    # ==================== HIGH ROCK VILLAGES (9) ====================
    (
        "koeglin_village", "high_rock", "C", "breton", "stormhaven", "bay-cove-shipwright-village",
        "chain_tower", "Koeglin Harbour Crane-Tower", False, True,
        [
            ("inn", "inn", "koeglin_anchors_aweigh", "The Anchor's Aweigh Tavern"),
            ("store", "general_store", "koeglin_pitch_and_oakum", "Pitch and Oakum Ship-Store"),
            ("smithy", "smithy", "koeglin_rivet_forge", "Ship-Rivet & Bolt Smithy"),
            ("building", "sawmill", "koeglin_ship_timber_yard", "Koeglin Knee-Timber Sawmill"),
            ("building", "fishery", "koeglin_oyster_quay", "Bay-Oyster & Herring Stages"),
            ("shrine", "temple", "koeglin_mariner_shrine", "Wayside Shrine of Kynareth"),
        ]
    ),
    (
        "dreughside", "high_rock", "C", "breton", "wayrest", "lagoon-shell-dyers-hamlet",
        "chain_tower", "Dreughside Lagoon Winch-Tower", False, True,
        [
            ("inn", "inn", "dreughside_crimson_claw_inn", "The Crimson Claw Tavern"),
            ("store", "general_store", "dreughside_shell_and_wax", "Dreugh-Wax & Shell Factors"),
            ("alchemist", "alchemist", "dreughside_purple_vat_works", "Tyrian-Shell Dye & Wax Boiling House"),
            ("building", "fishery", "dreughside_coracle_slips", "Lagoon Coracle & Net-Loft"),
            ("smithy", "smithy", "dreughside_gaff_smithy", "Barbed-Gaff & Knife Smithy"),
            ("shrine", "temple", "dreughside_zenithar_post", "Dyers' Shrine of Zenithar"),
        ]
    ),
    (
        "eagles_brook", "high_rock", "C", "breton", "glenumbra", "mill-stream-stone-bridge-village",
        "chapel_spire", "Eagle's Brook Bridge-Chapel", False, False,
        [
            ("inn", "inn", "eagles_brook_trout_and_creel", "The Trout and Creel Inn"),
            ("store", "general_store", "eagles_brook_village_chandler", "Brookside General Store"),
            ("mill", "mill", "eagles_brook_stone_mill", "Eagle's Brook Overshot Mill"),
            ("smithy", "smithy", "eagles_brook_farrier_forge", "Bridge-Foot Farrier & Smithy"),
            ("alchemist", "alchemist", "eagles_brook_watercress_herb", "Watercress & Mint Herbalist"),
            ("shrine", "temple", "eagles_brook_mara_cross", "Brook-Bridge Shrine of Mara"),
        ]
    ),
    (
        "westtry", "high_rock", "C", "breton", "glenumbra", "chalk-barrow-hollow-village",
        "stone_circle", "Westtry Chalk Menhirs", False, False,
        [
            ("inn", "inn", "westtry_shuttered_lantern", "The Shuttered Lantern Inn"),
            ("store", "general_store", "westtry_downland_store", "Westtry Salt & Candle Store"),
            ("smithy", "smithy", "westtry_iron_hasp_smithy", "Iron-Hasp & Lock Smithy"),
            ("alchemist", "alchemist", "westtry_rowan_and_salt", "Rowan-Twig & Warding Apothecary"),
            ("hall_of_dead", "hall_of_dead", "westtry_barrow_watch_chapel", "Barrow-Watch Chapel of Arkay"),
            ("farmhouse", "farmhouse", "westtry_bailiffs_cottage", "Reeve's Stone-Gabled Farmhouse"),
        ]
    ),
    (
        "hoarfrost_downs", "high_rock", "C", "breton", "rivenspire", "frost-wind-sheep-fold-village",
        "stone_circle", "Hoarfrost Wind-Cairn Circle", False, False,
        [
            ("inn", "inn", "hoarfrost_frozen_fleece_inn", "The Frozen Fleece Inn"),
            ("store", "general_store", "hoarfrost_wool_and_tallow", "Downs Wool & Tallow Store"),
            ("smithy", "smithy", "hoarfrost_frost_nail_forge", "Frost-Nail & Crook Smithy"),
            ("store", "tailor", "hoarfrost_carding_barn", "Carding & Felt-Cloak Barn"),
            ("shrine", "temple", "hoarfrost_kynareth_cairn", "Wind-Cairn of Kynareth"),
            ("stables", "barn", "hoarfrost_winter_sheep_cote", "Stone-Walled Winter Sheep-Cote"),
        ]
    ),
    (
        "fells_run", "high_rock", "C", "breton", "rivenspire", "valley-beck-pasture-village",
        "chapel_spire", "Fell's Run Croft-Belfry", False, False,
        [
            ("inn", "inn", "fells_run_plough_and_flagon", "The Plough and Flagon Inn"),
            ("store", "general_store", "fells_run_valley_provender", "Fell's Run Provender & Seed Store"),
            ("smithy", "smithy", "fells_run_scythe_smithy", "Scythe & Coulter Smithy"),
            ("alchemist", "alchemist", "fells_run_meadow_balm", "Feverfew & Meadow-Balm Herbalist"),
            ("mill", "mill", "fells_run_barley_mill", "Beckside Barley & Oat Mill"),
            ("shrine", "temple", "fells_run_mara_hearth_shrine", "Chapel-Croft of Mara"),
        ]
    ),
    (
        "morkul_stronghold_high_rock", "high_rock", "C", "orc", "orsinium", "orc-forge-cliff-palisade",
        "gate_hammer", "Morkul Forge-Anvil Gate", True, False,
        [
            ("longhouse", "longhouse", "morkul_chieftains_hall", "Clan Morkul Chieftain's Hall"),
            ("smithy", "smithy", "morkul_hand_anvil", "The Fist-of-Malacath Forge"),
            ("smithy", "smelter", "morkul_deep_slag_furnace", "Crag-Vein Blast Smelter"),
            ("alchemist", "alchemist", "morkul_blood_root_hutch", "Hearth-Wife's Blood-Root Hutch"),
            ("smithy", "smithy", "morkul_plate_racks", "Morkul Heavy-Plate Quartermaster"),
            ("shrine", "temple", "morkul_malacath_brazier", "Slag-Stone Brazier of Malacath"),
        ]
    ),
    (
        "fharun_stronghold", "high_rock", "C", "orc", "orsinium", "orc-sea-cliff-palisade",
        "gate_hammer", "Fharun Cliff-Gate", True, False,
        [
            ("longhouse", "longhouse", "fharun_iron_prow_hall", "Clan Fharun Cliff-Longhouse"),
            ("smithy", "smithy", "fharun_wind_bellows_forge", "Sea-Wind Bellows Forge"),
            ("store", "tannery", "fharun_white_bear_tannery", "Frost-Bear & Seal Tannery"),
            ("alchemist", "alchemist", "fharun_wise_woman_hearth", "Wise-Woman's Bone-Ash Hearth"),
            ("building", "guardhouse", "fharun_ravine_watch", "Ravine-Bridge Watchtower"),
            ("shrine", "temple", "fharun_grudge_pillar", "Pillar of the Sworn Grudge"),
        ]
    ),
    (
        "tumnosh_stronghold", "high_rock", "C", "orc", "orsinium", "orc-quarry-bastion-palisade",
        "headframe", "Tumnosh Granite-Hoist", True, False,
        [
            ("longhouse", "longhouse", "tumnosh_stone_hearth_hall", "Clan Tumnosh Mason-Chieftain's Hall"),
            ("smithy", "smithy", "tumnosh_wedge_and_maul", "Wedge and Heavy-Maul Smithy"),
            ("building", "warehouse", "tumnosh_dressed_basalt_yard", "Orsinium-Road Basalt Dressing Yard"),
            ("alchemist", "alchemist", "tumnosh_lichen_poultice_hut", "Crag-Lichen & Marrow Poultice Hut"),
            ("stables", "barn", "tumnosh_haul_echatere_pens", "Stone-Haul Echatere Pens"),
            ("shrine", "temple", "tumnosh_malacath_altar", "Split-Boulder Altar of Malacath"),
        ]
    ),

    # ==================== HAMMERFELL CITIES ====================
    (
        "taneth", "hammerfell", "A", "redguard", "rihad", "cliff-palace-terraces",
        "dome_palace", "The Turquoise Palace of Taneth", True, True,
        [
            ("palace", "keep", "taneth_turquoise_palace", "The Turquoise Palace of Taneth"),
            ("inn", "inn", "taneth_golden_astrolabe", "The Golden Astrolabe Inn"),
            ("inn", "inn", "taneth_sea_stair_tavern", "The Hundred-Stair Harbour Tavern"),
            ("store", "general_store", "taneth_abecean_factors", "Abecean Spice & Coral Factors"),
            ("smithy", "smithy", "taneth_curved_steel_forge", "Curved-Steel & Brass-Hilt Forge"),
            ("alchemist", "alchemist", "taneth_lotus_and_myrrh", "Blue-Lotus & Myrrh Apothecary"),
            ("store", "jeweller", "taneth_turquoise_lapidary", "Royal Turquoise & Pearl Lapidary"),
            ("store", "tailor", "taneth_brocade_bazaar", "Sun-Gilt Silk & Brocade Looms"),
            ("store", "bookseller", "taneth_navigation_rolls", "Taneth Admiralty Chart-House"),
            ("guild_hall", "guild_hall", "taneth_merchants_syndicate", "Forebear Maritime Syndicate Hall"),
            ("guild_hall", "guild_hall", "taneth_fighters_hall", "Cliff-Guard Fighters Guildhall"),
            ("temple", "temple", "taneth_tava_wind_mosque", "Cliff-Edge Sanctuary of Tava"),
            ("hall_of_dead", "hall_of_dead", "taneth_sea_cut_catacombs", "Sea-Cliff Ancestor Catacombs"),
            ("manor", "manor", "taneth_emir_jalil_pavilion", "Emir Jalil's Pool-Garden Pavilion"),
            ("building", "fishery", "taneth_harbour_stair_house", "Lower Quay Weigh-House & Slipway"),
            ("building", "guardhouse", "taneth_sea_wall_battery", "Turquoise Cliff Sea-Battery"),
        ]
    ),
    (
        "skaven", "hammerfell", "A", "redguard", "alikr_desert", "caravan-basin-arcade-fort",
        "dome_temple", "Great Cistern-Mosque of Skaven", True, False,
        [
            ("palace", "keep", "skaven_sand_citadel", "Citadel of the Basin-Prince"),
            ("inn", "inn", "skaven_seven_wells_caravanserai", "The Seven Wells Caravanserai"),
            ("inn", "inn", "skaven_red_whirlwind_inn", "The Red Whirlwind Tavern"),
            ("store", "general_store", "skaven_trans_alikr_bazaar", "Trans-Alik'r Caravan Provisioners"),
            ("smithy", "smithy", "skaven_spear_and_buckler", "Skaven Spear & Brass-Buckler Forge"),
            ("alchemist", "alchemist", "skaven_aloe_and_scorpion", "Desert-Aloe & Venom Apothecary"),
            ("store", "jeweller", "skaven_quartz_and_copper", "Sun-Quartz & Hammered-Brass Suq"),
            ("store", "tailor", "skaven_burnoose_weavers", "Woven-Wool Burnoose & Tent-Cloth Hall"),
            ("guild_hall", "guild_hall", "skaven_caravan_masters_hall", "High Pass Caravan-Masters' Hall"),
            ("guild_hall", "guild_hall", "skaven_fighters_guild", "Dune-Lancers Fighters Guildhall"),
            ("temple", "temple", "skaven_zeht_granary_fane", "Domed Fane of Zeht & the Cisterns"),
            ("hall_of_dead", "hall_of_dead", "skaven_rock_tomb_gate", "Sand-Sealed Ancestor Necropolis"),
            ("manor", "manor", "skaven_water_bailiff_court", "Court of the Qanat Water-Bailiff"),
            ("building", "warehouse", "skaven_salt_block_magazine", "Salt-Block & Date-Bale Magazine"),
            ("building", "guardhouse", "skaven_bangkorai_road_gate", "North-Pass Toll & Watch Gate"),
            ("stables", "stables", "skaven_great_camel_yard", "Great Caravan Camel & Courser Yard"),
        ]
    ),
    (
        "elinhir", "hammerfell", "A", "redguard", "craglorn", "highland-tier-spire",
        "minaret", "The Apex Spire of Elinhir", True, False,
        [
            ("palace", "keep", "elinhir_governors_citadel", "High-Tier Citadel of Elinhir"),
            ("inn", "inn", "elinhir_falling_star_inn", "The Falling Star Inn"),
            ("inn", "inn", "elinhir_colovian_cup", "The Colovian Cup Tavern"),
            ("store", "general_store", "elinhir_high_pass_factors", "Craglorn High-Pass Outfitter"),
            ("smithy", "smithy", "elinhir_meteoric_iron_forge", "Star-Metal & Heavy-Steel Forge"),
            ("alchemist", "alchemist", "elinhir_nirncrux_alembic", "Red-Crux & Mountain-Sage Alembic"),
            ("store", "bookseller", "elinhir_astrolabe_scriptorium", "The Spire Ephemeris & Folio House"),
            ("store", "jeweller", "elinhir_lodestone_cutters", "Lodestone & Amethyst Lapidary"),
            ("guild_hall", "guild_hall", "elinhir_apex_mages_conclave", "Conclave of the Apex Tower"),
            ("guild_hall", "guild_hall", "elinhir_fighters_hall", "Craglorn Escort Fighters Hall"),
            ("temple", "temple", "elinhir_julianos_star_fane", "Star-Vaulted Temple of Julianos"),
            ("hall_of_dead", "hall_of_dead", "elinhir_nedic_undercroft", "Nedic-Stone Catacombs of Arkay"),
            ("manor", "manor", "elinhir_warden_kaia_hall", "Warden Kaia's Wind-Wall Manor"),
            ("building", "warehouse", "elinhir_tiered_granary", "The Seven-Vault Tiered Granary"),
            ("building", "guardhouse", "elinhir_wind_wall_rampart", "Wind-Wall Rampart Watchhouse"),
            ("stables", "stables", "elinhir_pass_mule_stables", "Jerall-Border Mule & Horse Mews"),
        ]
    ),
    (
        "gilane", "hammerfell", "A", "redguard", "alikr_desert", "covetous-bay-dyeworks-port",
        "minaret", "Gilane Harbour Light-Minaret", True, True,
        [
            ("palace", "keep", "gilane_bay_citadel", "Castle Gilane Harbour Citadel"),
            ("inn", "inn", "gilane_purple_sail_inn", "The Purple Sail Inn"),
            ("inn", "inn", "gilane_coral_anchor", "The Coral Anchor Tavern"),
            ("store", "general_store", "gilane_bay_chandlery", "Covetous Bay Ship-Chandlery"),
            ("smithy", "smithy", "gilane_bronze_grapnel_forge", "Grapnel & Cutlass Ironworks"),
            ("alchemist", "alchemist", "gilane_murex_dye_works", "Royal Murex & Indigo Dye-Works"),
            ("store", "tailor", "gilane_sail_and_silk_looms", "Crimson-Canvas & Silk Loom-House"),
            ("store", "jeweller", "gilane_red_coral_suq", "Red-Coral & Pearl Suq"),
            ("guild_hall", "guild_hall", "gilane_shipwrights_hall", "Southern Shipwrights' Guildhall"),
            ("temple", "temple", "gilane_tava_harbour_mosque", "Sea-Breeze Mosque of Tava"),
            ("hall_of_dead", "hall_of_dead", "gilane_dune_ridge_tombs", "White-Limestone Cliff Tombs"),
            ("manor", "manor", "gilane_syndics_courtyard", "Forebear Port-Syndic's Courtyard"),
            ("building", "fishery", "gilane_sponge_divers_quay", "Sponge-Divers & Murex Quay"),
            ("building", "guardhouse", "gilane_chain_mole_watch", "Harbour-Mole Watchhouse"),
            ("stables", "stables", "gilane_coast_caravan_yard", "Taneth-Road Caravan Stables"),
        ]
    ),
    (
        "hegathe", "hammerfell", "A", "redguard", "alikr_desert", "cliff-citadel-first-landfall",
        "dome_temple", "Great Sanctuary of the Ra Gada Landfall", True, True,
        [
            ("palace", "keep", "hegathe_ancestral_citadel", "Ancestral Citadel of Hegathe"),
            ("inn", "inn", "hegathe_first_anchor_inn", "The First Anchor Inn"),
            ("inn", "inn", "hegathe_bronze_shield_rest", "The Bronze Shield Rest"),
            ("store", "general_store", "hegathe_pilgrim_provisioner", "Landfall Pilgrim Provisioners"),
            ("smithy", "smithy", "hegathe_ra_gada_blade_forge", "First-Wave Shehai-Steel Smithy"),
            ("alchemist", "alchemist", "hegathe_myrrh_and_frankincense", "Sacred Incense & Natron Apothecary"),
            ("store", "bookseller", "hegathe_yokudan_chronicle_hall", "Hall of the Yokudan Roll-Keepers"),
            ("store", "jeweller", "hegathe_votive_gold_smiths", "Votive Lamp & Gold-Leaf Smiths"),
            ("guild_hall", "guild_hall", "hegathe_crown_jurists_hall", "High College of Crown Na-Totambu"),
            ("temple", "temple", "hegathe_ruptga_great_fane", "Cliff-Crowning Fane of Ruptga & Morwha"),
            ("hall_of_dead", "hall_of_dead", "hegathe_kings_necropolis", "Cliff-Cut Necropolis of the First Kings"),
            ("manor", "manor", "hegathe_high_elder_house", "Crown High-Elder's Stone Courtyard"),
            ("building", "fishery", "hegathe_cliff_steps_landing", "Sea-Steps Galley Landing"),
            ("building", "guardhouse", "hegathe_landfall_bastion", "Landfall Sea-Wall Watchtower"),
            ("stables", "stables", "hegathe_desert_post_yard", "Alik'r Edge Camel & Horse Yard"),
        ]
    ),
    (
        "dragonstar", "hammerfell", "A", "redguard", "craglorn", "arena-ringed-fort",
        "arena", "Dragonstar Blood-Sand Arena", True, False,
        [
            ("palace", "keep", "dragonstar_palace_of_the_star", "Palace of the Star"),
            ("inn", "inn", "dragonstar_crossed_glaives", "The Crossed Glaives Inn"),
            ("inn", "inn", "dragonstar_nord_horn_tavern", "The Frozen Horn Tavern (East Gate)"),
            ("store", "general_store", "dragonstar_caravan_exchange", "Twin-Ward Border Exchange"),
            ("smithy", "smithy", "dragonstar_arena_armoury", "Blood-Sand Gladiators' Armoury"),
            ("alchemist", "alchemist", "dragonstar_stitch_and_salve", "Arena Bonesetter & Salve-House"),
            ("store", "butcher", "dragonstar_mountain_venison", "Druadach Venison & Salt-Pork Hall"),
            ("store", "tannery", "dragonstar_snow_bear_furs", "High-Pass Pelt & Harness Works"),
            ("guild_hall", "guild_hall", "dragonstar_gladiators_quarter", "Gladiators'Ludus & Training Hall"),
            ("guild_hall", "guild_hall", "dragonstar_bookmakers_arcades", "Rim's Wager-Boards & Factors Hall"),
            ("temple", "temple", "dragonstar_hoon_ding_shrine", "Shrine of the Make-Way God"),
            ("hall_of_dead", "hall_of_dead", "dragonstar_fallen_champions_vault", "Vault of the Fallen Champions"),
            ("manor", "manor", "dragonstar_arena_master_house", "Arena-Master Dahab's Pavilion"),
            ("barracks", "barracks", "dragonstar_partition_watch_barracks", "East-West Partition Watch Barracks"),
            ("building", "guardhouse", "dragonstar_skyrim_gate_watch", "Reach-Pass Gate Watchhouse"),
            ("stables", "stables", "dragonstar_chariot_stables", "Arena Chariot & Pack-Mule Stables"),
        ]
    ),
    (
        "abahs_landing", "hammerfell", "A", "redguard", "hews_bane", "stilt-pier-harbour",
        "sea_stack", "The Landing Light & Harbour Arch", True, True,
        [
            ("palace", "keep", "abahs_landing_merchant_palace", "Grand Counting-Palace of the Syndics"),
            ("inn", "inn", "abahs_landing_golden_caravel", "The Golden Caravel Inn"),
            ("inn", "inn", "abahs_landing_bilge_and_barrel", "The Bilge and Barrel"),
            ("store", "general_store", "abahs_landing_pier_market", "Stilt-Pier Free-Port Bazaar"),
            ("smithy", "smithy", "abahs_landing_cutlass_and_hook", "Cutlass and Boarding-Hook Smithy"),
            ("alchemist", "alchemist", "abahs_landing_lotus_smoke_shop", "Black-Lotus & Coral-Tonic Apothecary"),
            ("store", "jeweller", "abahs_landing_no_questions_pawn", "The Unstamped Bullion & Gem House"),
            ("store", "tailor", "abahs_landing_silks_of_abecea", "Smuggled-Silk & Sash Clothiers"),
            ("store", "bookseller", "abahs_landing_false_manifest", "Harbour Manifest & Letter-of-Marque Scribe"),
            ("guild_hall", "guild_hall", "abahs_landing_net_loft_guild", "The Net-Loft Ledger Hall"),
            ("guild_hall", "fence_den", "abahs_landing_cistern_undercroft", " Cistern-Stair Brokers' Den"),
            ("temple", "temple", "abahs_landing_zeht_tava_chapel", "Harbour-Side Sanctuary of Tava"),
            ("hall_of_dead", "hall_of_dead", "abahs_landing_sunken_ossuary", "Tide-Cut Limestone Ossuary"),
            ("manor", "manor", "abahs_landing_iron_wheel_hq", "Iron Wheel Provost's Villa"),
            ("building", "warehouse", "abahs_landing_free_port_vaults", "Hew's Bane Bonded Prize-Vaults"),
            ("building", "guardhouse", "abahs_landing_mole_lighthouse", "The Landing Light Watchhouse"),
        ]
    ),
    # ==================== HAMMERFELL TOWNS (19) ====================
    (
        "stros_mkai", "hammerfell", "A", "redguard", "stros_mkai", "island-lighthouse-citadel",
        "orrery", "Stros M'Kai Dwemer Observatory & Beacon", True, True,
        [
            ("palace", "keep", "stros_mkai_governors_palace", "Palace of the Island Headman"),
            ("inn", "inn", "stros_mkai_pirates_rest", "The Pirate's Rest Tavern"),
            ("inn", "inn", "stros_mkai_screaming_mermaid", "The Screaming Mermaid Inn"),
            ("store", "general_store", "stros_mkai_port_hundings_store", "Port Hunding Ship-Chandlery"),
            ("smithy", "smithy", "stros_mkai_brass_gear_forge", "Dwemer-Scrap & Cutlass Forge"),
            ("alchemist", "alchemist", "stros_mkai_cane_rum_still", "Cane-Spirit & Sea-Lime Apothecary"),
            ("store", "jeweller", "stros_mkai_sun_glass_cutters", "Polished Brass & Pearl Workshop"),
            ("store", "bookseller", "stros_mkai_cartographers_house", "Abecean Currents Chart-House"),
            ("guild_hall", "guild_hall", "stros_mkai_freebooters_hall", "Restless League Mariners' Hall"),
            ("temple", "temple", "stros_mkai_hundings_shrine", "Harbour Pavilion of Frandar Hunding"),
            ("hall_of_dead", "hall_of_dead", "stros_mkai_pillar_palace_tomb", "Cliff-Side Tomb of the Sailors"),
            ("manor", "manor", "stros_mkai_harbourmaster_house", "Harbourmaster Zahra's Customs House"),
            ("building", "fishery", "stros_mkai_careening_dock", "Careening Beach & Sail-Loft"),
            ("building", "guardhouse", "stros_mkai_fort_at_the_mouth", "Sea-Mouth Chain Fort"),
            ("stables", "stables", "stros_mkai_mule_caravan_pens", "Island Pack-Mule Pens"),
        ]
    ),
    (
        "kozanset", "hammerfell", "B", "redguard", "alikr_desert", "north-rim-caravan-bazaar",
        "minaret", "Kozanset Caravan-Bell Minaret", True, False,
        [
            ("palace", "keep", "kozanset_qadi_alcazar", "Alcazar of the Qadi of Kozanset"),
            ("inn", "inn", "kozanset_copper_kettle_serai", "The Copper Kettle Caravanserai"),
            ("store", "general_store", "kozanset_scale_and_measure", "Scale and Measure Caravan Store"),
            ("smithy", "smithy", "kozanset_camel_bell_forge", "Brass-Bell & Scimitar Smithy"),
            ("alchemist", "alchemist", "kozanset_myrrh_and_mint", "Myrrh and Desert-Mint Apothecary"),
            ("temple", "temple", "kozanset_zenithar_suq_mosque", "Mosque of the Honest Weight"),
            ("building", "warehouse", "kozanset_grain_and_salt_khans", "Bonded Salt & Barley Khans"),
            ("manor", "manor", "kozanset_mint_master_house", "Caravan-Toll Collector's Courtyard"),
            ("building", "guardhouse", "kozanset_east_gate_watch", "Crossroads Gate Watchhouse"),
            ("stables", "stables", "kozanset_dromedary_khan", "North-Rim Dromedary Yard"),
        ]
    ),
    (
        "bergama", "hammerfell", "B", "redguard", "alikr_desert", "oasis-palm-ring-walled-town",
        "dome_temple", "Bergama Oasis Sanctuary", True, False,
        [
            ("palace", "keep", "bergama_oasis_kasbah", "Kasbah of Bergama Oasis"),
            ("inn", "inn", "bergama_shaded_palm_serai", "The Shaded Date-Palm Serai"),
            ("store", "general_store", "bergama_waterskin_provisioner", "Waterskin & Dried-Fig Provisioners"),
            ("smithy", "smithy", "bergama_well_chain_forge", "Well-Chain & Stirrup Smithy"),
            ("alchemist", "alchemist", "bergama_tamarind_still", "Tamarind & Rosewater Apothecary"),
            ("temple", "temple", "bergama_morwha_fountain_fane", "Fountain-Sanctuary of Morwha"),
            ("manor", "manor", "bergama_qanat_keepers_hall", "Hall of the Sworn Well-Keepers"),
            ("store", "tailor", "bergama_white_linen_looms", "Oasis-Flax & Headscarf Looms"),
            ("building", "guardhouse", "bergama_dune_gate_watch", "Sand-Wall Gate Watchhouse"),
            ("stables", "stables", "bergama_watering_lines", "Caravan Watering-Trough Stables"),
        ]
    ),
    (
        "satakalaam", "hammerfell", "B", "redguard", "alikr_desert", "serpent-canyon-terraced-fief",
        "twin_domes", "Twin Domes of Satakalaam", True, False,
        [
            ("palace", "keep", "satakalaam_canyon_citadel", "Citadel of the Coiled Pass"),
            ("inn", "inn", "satakalaam_shed_skin_inn", "The Shed-Skin Inn"),
            ("store", "general_store", "satakalaam_copper_ingot_suq", "Canyon Copper & Spice Suq"),
            ("smithy", "smithy", "satakalaam_scale_mail_forge", "Scale-Mail & Khopesh Forge"),
            ("alchemist", "alchemist", "satakalaam_viper_fang_shop", "Sand-Viper Antivenin Apothecary"),
            ("temple", "temple", "satakalaam_satakal_shrine_hall", "Twin-Domed Sanctuary of the Worldskin"),
            ("hall_of_dead", "hall_of_dead", "satakalaam_cliff_niches", "Terraced Cliff-Niche Tombs"),
            ("manor", "manor", "satakalaam_copper_syndic_house", "Copper-Mine Syndic's Manor"),
            ("building", "guardhouse", "satakalaam_gorge_watch", "Wadi-Mouth Watchtower"),
            ("stables", "stables", "satakalaam_mule_terraces", "Lower-Terrace Pack Stables"),
        ]
    ),
    (
        "antiphyllos", "hammerfell", "B", "redguard", "sentinel", "olive-ridge-courtyard-fief",
        "dome_temple", "Antiphyllos Olive-Hill Mosque", True, False,
        [
            ("palace", "keep", "antiphyllos_hill_kasbah", "Kasbah of Antiphyllos"),
            ("inn", "inn", "antiphyllos_green_amphora", "The Green Amphora Inn"),
            ("store", "general_store", "antiphyllos_oil_and_grain", "Olive-Oil & Durum Wheat Factor"),
            ("smithy", "smithy", "antiphyllos_pruning_hook_forge", "Pruning-Hook & Scimitar Smithy"),
            ("alchemist", "alchemist", "antiphyllos_rosemary_balm", "Olive-Leaf & Rosemary Balm House"),
            ("temple", "temple", "antiphyllos_zeht_terrace_fane", "Terrace Sanctuary of Zeht"),
            ("manor", "manor", "antiphyllos_oil_press_court", "Great Stone Olive-Press Courtyard"),
            ("store", "tailor", "antiphyllos_dyed_wool_suq", "Saffron-Wool & Carpet Suq"),
            ("building", "guardhouse", "antiphyllos_west_ridge_watch", "Ridge-Road Watchhouse"),
            ("stables", "stables", "antiphyllos_courser_mews", "Hill-Terrace Courser Stables"),
        ]
    ),
    (
        "myrkwasa", "hammerfell", "B", "redguard", "sentinel", "coastal-bluff-date-grove-town",
        "minaret", "Myrkwasa Bluff Minaret", True, True,
        [
            ("palace", "keep", "myrkwasa_bluff_keep", "Myrkwasa Sea-Bluff Keep"),
            ("inn", "inn", "myrkwasa_west_wind_serai", "The West-Wind Serai"),
            ("store", "general_store", "myrkwasa_date_and_salt", "Date-Sugar & Sea-Salt Store"),
            ("smithy", "smithy", "myrkwasa_brass_lantern_smithy", "Pierced-Brass Lantern & Blade Smithy"),
            ("alchemist", "alchemist", "myrkwasa_amber_and_aloe", "Sea-Amber & Aloe Apothecary"),
            ("temple", "temple", "myrkwasa_tava_bluff_mosque", "Bluff-Top Mosque of Tava"),
            ("building", "fishery", "myrkwasa_lateen_wharf", "Lateen-Skiff & Tunny Wharf"),
            ("manor", "manor", "myrkwasa_grove_amir_villa", "Palm-Grove Amir's Whitewashed Villa"),
            ("building", "guardhouse", "myrkwasa_cape_watch", "Cape-Road Watchtower"),
            ("stables", "stables", "myrkwasa_post_camel_yard", "Sentinel-Road Relay Yard"),
        ]
    ),
    (
        "cybiades", "hammerfell", "B", "redguard", "sentinel", "ruin-crowned-peninsula-port",
        "dome_temple", "Cybiades Sanctuary of Tu'whacca", True, True,
        [
            ("palace", "keep", "cybiades_warden_citadel", "Cybiades Peninsula Citadel"),
            ("inn", "inn", "cybiades_silent_oar_inn", "The Silent Oar Inn"),
            ("store", "general_store", "cybiades_excavators_store", "Tomb-Wardens & Mariners' Store"),
            ("smithy", "smithy", "cybiades_silver_ward_forge", "Silver-Inlaid Scimitar Smithy"),
            ("alchemist", "alchemist", "cybiades_natron_and_bitumen", "Natron, Bitumen & Ward-Herb Shop"),
            ("temple", "temple", "cybiades_tuwhacca_lantern_fane", "Ever-Lit Fane of Tu'whacca"),
            ("hall_of_dead", "hall_of_dead", "cybiades_coastal_hypogeum", "Warded Coastal Hypogeum"),
            ("guild_hall", "guild_hall", "cybiades_ashabah_watch_post", "Ash'abah Crossing Watch-Lodge"),
            ("building", "guardhouse", "cybiades_quarantine_mole", "Cursed-Ruin Quarantine Watch"),
            ("stables", "stables", "cybiades_ferry_mule_pens", "Mainland-Road Pack Pens"),
        ]
    ),
    (
        "abibon_gora", "hammerfell", "B", "redguard", "sentinel", "cove-cliffs-pearl-port",
        "minaret", "Abibon-Gora Cliff-Beacon", True, True,
        [
            ("palace", "keep", "abibon_gora_sea_kasbah", "Kasbah of Abibon-Gora"),
            ("inn", "inn", "abibon_gora_divers_bell", "The Diver's Bell Tavern"),
            ("store", "general_store", "abibon_gora_cordage_suq", "Coir-Rope & Sailcloth Suq"),
            ("smithy", "smithy", "abibon_gora_bronze_anchor_forge", "Bronze-Anchor & Diving-Weight Forge"),
            ("alchemist", "alchemist", "abibon_gora_nacre_elixir", "Crushed-Nacre & Coral Apothecary"),
            ("store", "jeweller", "abibon_gora_pearl_exchange", "Southern Bight Pearl Exchange"),
            ("temple", "temple", "abibon_gora_tava_cove_fane", "Cove-Wall Sanctuary of Tava"),
            ("building", "fishery", "abibon_gora_oyster_stages", "Pearl-Oyster Sorting Stages"),
            ("building", "guardhouse", "abibon_gora_headland_watch", "South-Cove Watchtower"),
            ("stables", "stables", "abibon_gora_cliff_trail_mews", "Cliff-Trail Pack Mews"),
        ]
    ),
    (
        "totambu", "hammerfell", "B", "redguard", "alikr_desert", "old-crown-garden-oasis-citadel",
        "twin_domes", "Totambu Ancestral Twin Domes", True, False,
        [
            ("palace", "keep", "totambu_crown_palace", "Old-Line Palace of Na-Totambu"),
            ("inn", "inn", "totambu_jade_fountain_serai", "The Jade Fountain Serai"),
            ("store", "general_store", "totambu_silk_and_incense", "Court Silk & Frankincense Store"),
            ("smithy", "smithy", "totambu_damascene_forge", "Watered-Steel & Gold-Wire Forge"),
            ("alchemist", "alchemist", "totambu_pomegranate_still", "Pomegranate & Lotus Perfumery"),
            ("store", "bookseller", "totambu_genealogy_chancery", "House of the Yokudan Pedigrees"),
            ("temple", "temple", "totambu_ruptga_pillars", "Pillared Sanctuary of Ruptga"),
            ("hall_of_dead", "hall_of_dead", "totambu_hall_of_the_virtues", "Hall of the Ancestral Virtues"),
            ("building", "guardhouse", "totambu_lotus_gate_watch", "Lotus-Gate Honour Watch"),
            ("stables", "stables", "totambu_royal_courser_lines", "Pureblood Desert Courser Mews"),
        ]
    ),
    (
        "kairou", "hammerfell", "B", "redguard", "alikr_desert", "wadi-terraced-loom-town",
        "minaret", "Kairou Wadi Minaret", True, False,
        [
            ("palace", "keep", "kairou_wadi_kasbah", "Kasbah of the Dry Wadi"),
            ("inn", "inn", "kairou_red_shuttle_serai", "The Red Shuttle Caravanserai"),
            ("store", "general_store", "kairou_dyers_market", "Madder-Root & Alum Market"),
            ("smithy", "smithy", "kairou_copper_vat_smithy", "Copper-Vat & Loom-Weight Smithy"),
            ("alchemist", "alchemist", "kairou_henna_and_kohl", "Henna, Kohl & Desert-Herb Shop"),
            ("store", "tailor", "kairou_knotted_carpet_hall", "Kairou Knotted-Carpet Guildhall"),
            ("temple", "temple", "kairou_morwha_loom_mosque", "Sanctuary of Morwha of the Threads"),
            ("manor", "manor", "kairou_master_weaver_court", "Master-Weaver's Shaded Courtyard"),
            ("building", "guardhouse", "kairou_cistern_watch", "Wadi-Dam & Cistern Watchhouse"),
            ("stables", "stables", "kairou_wool_camel_yard", "Fleece-Caravan Camel Yard"),
        ]
    ),
    (
        "ephesus", "hammerfell", "B", "redguard", "alikr_desert", "scrub-mesa-observatory-town",
        "orrery", "Ephesus Mesa Star-Dome", True, False,
        [
            ("palace", "keep", "ephesus_mesa_citadel", "Mesa-Crown Citadel of Ephesus"),
            ("inn", "inn", "ephesus_brass_sextant_inn", "The Brass Sextant Inn"),
            ("store", "general_store", "ephesus_mesa_provisioner", "High-Mesa Caravan Provisioners"),
            ("smithy", "smithy", "ephesus_bronze_ring_smithy", "Astrolabe-Ring & Blade Smithy"),
            ("alchemist", "alchemist", "ephesus_star_thistle_still", "Star-Thistle & Salt-Crystal Still"),
            ("store", "bookseller", "ephesus_sky_table_library", "Library of the Night-Tables"),
            ("temple", "temple", "ephesus_tall_papa_dome", "Star-Pierced Dome of Tall Papa"),
            ("manor", "manor", "ephesus_astronomer_house", "Chief Astronomer's Terrace House"),
            ("building", "guardhouse", "ephesus_stair_gate_watch", "Mesa-Stair Gate Watchhouse"),
            ("stables", "stables", "ephesus_pack_mule_corrals", "Mesa-Foot Mule Corrals"),
        ]
    ),
    (
        "hallins_stand", "hammerfell", "B", "redguard", "bangkorai", "south-pass-gate-fortress-town",
        "gate_hammer", "Hallin's Stand Pass-Gate", True, False,
        [
            ("palace", "keep", "hallins_stand_citadel", "Citadel of Hallin's Stand"),
            ("inn", "inn", "hallins_stand_five_companions", "The Five Companions Inn"),
            ("store", "general_store", "hallins_stand_pass_commissary", "Bangkorai-Edge Caravan Commissary"),
            ("smithy", "smithy", "hallins_stand_ansei_memorial_forge", "Hallin's Anvil Armoury"),
            ("alchemist", "alchemist", "hallins_stand_desert_sage_shop", "Pass-Wind Sage & Poultice Shop"),
            ("temple", "temple", "hallins_stand_hall_of_virtues", "Hall of the Martial Virtues"),
            ("hall_of_dead", "hall_of_dead", "hallins_stand_ansei_crypt", "Crypt of the Sword-Saints"),
            ("barracks", "barracks", "hallins_stand_gate_garrison", "South-Gate Pikemen Barracks"),
            ("building", "guardhouse", "hallins_stand_north_wall_watch", "Garrison-Road Watchtower"),
            ("stables", "stables", "hallins_stand_remount_lines", "Pass-Guard Cavalry Stables"),
        ]
    ),
    (
        "lainlyn", "hammerfell", "B", "redguard", "bangkorai", "cliff-inlet-corsair-terrace",
        "minaret", "Lainlyn Bay Signal-Minaret", True, True,
        [
            ("palace", "keep", "lainlyn_baroness_kasbah", "Cliff-Kasbah of Baron-House Lainlyn"),
            ("inn", "inn", "lainlyn_spotted_genoese", "The Falcon's Roost Tavern"),
            ("store", "general_store", "lainlyn_inlet_factors", "Inlet Timber & Spice Factors"),
            ("smithy", "smithy", "lainlyn_cutlass_and_chain", "Boarding-Pike & Chain Smithy"),
            ("alchemist", "alchemist", "lainlyn_sea_fennel_still", "Sea-Fennel & Pitch Apothecary"),
            ("temple", "temple", "lainlyn_tava_cliff_chapel", "Wind-Cut Sanctuary of Tava"),
            ("building", "fishery", "lainlyn_hidden_slipway", "Cove-Floor Galley Slipway"),
            ("manor", "manor", "lainlyn_captains_terrace", "Corsair-Captains' Ledger House"),
            ("building", "guardhouse", "lainlyn_boom_tower", "Inlet-Boom Watchtower"),
            ("stables", "stables", "lainlyn_upper_gate_stables", "Upper-Plateau Post Stables"),
        ]
    ),
    (
        "mournoth", "hammerfell", "B", "redguard", "bangkorai", " wooded-basin-hunting-kasbah",
        "dome_temple", "Mournoth Basin Sanctuary", True, False,
        [
            ("palace", "keep", "mournoth_basin_keep", "Kasbah of Mournoth"),
            ("inn", "inn", "mournoth_speared_lion_inn", "The Speared Lion Inn"),
            ("store", "general_store", "mournoth_pelt_and_cedar", "Cedar-Timber & Pelt Store"),
            ("smithy", "smithy", "mournoth_boar_spear_forge", "Boar-Spear & Broadhead Forge"),
            ("alchemist", "alchemist", "mournoth_cedar_resin_shop", "Cedar-Resin & Balm Apothecary"),
            ("store", "fletcher", "mournoth_horn_bow_wright", "Composite Horn-Bow Wright"),
            ("temple", "temple", "mournoth_kynareth_tava_shrine", "Grove-Sanctuary of Tava"),
            ("manor", "manor", "mournoth_huntsmaster_lodge", "Grand Huntsmaster's Stone Lodge"),
            ("building", "guardhouse", "mournoth_east_trail_watch", "Foothill-Trail Watchhouse"),
            ("stables", "stables", "mournoth_coursing_hound_mews", "Coursing-Hound & Hunter Stables"),
        ]
    ),
    (
        "ayasofya", "hammerfell", "B", "redguard", "bangkorai", "great-dome-pilgrim-oasis",
        "twin_domes", "Great Pilgrim Domes of Ayasofya", True, False,
        [
            ("palace", "keep", "ayasofya_custodians_palace", "Palace of the Dome-Custodian"),
            ("inn", "inn", "ayasofya_pilgrims_lantern_serai", "The Pilgrim's Lantern Serai"),
            ("store", "general_store", "ayasofya_votive_oil_suq", "Lamp-Oil & Votive Wax Suq"),
            ("smithy", "smithy", "ayasofya_censer_brass_works", "Hanging-Censer & Grille Brassworks"),
            ("alchemist", "alchemist", "ayasofya_spikenard_shop", "Spikenard & Myrrh Apothecary"),
            ("store", "bookseller", "ayasofya_illuminators_hall", "Gilt-Scroll & Calligraphy Hall"),
            ("temple", "temple", "ayasofya_great_rotunda", "Great Rotunda of Tu'whacca & Morwha"),
            ("hall_of_dead", "hall_of_dead", "ayasofya_mosaic_catacombs", "Mosaic-Vaulted Pilgrim Catacombs"),
            ("building", "guardhouse", "ayasofya_sanctuary_gate", "Outer-Court Gate Watchhouse"),
            ("stables", "stables", "ayasofya_pilgrim_camel_pens", "Pilgrim Caravan Camel Pens"),
        ]
    ),
    (
        "belkarth", "hammerfell", "B", "redguard", "craglorn", "river-canyon-stargazer-hub",
        "orrery", "Belkarth Star-Gazer Observatory", True, False,
        [
            ("palace", "keep", "belkarth_council_hall", "Belkarth Canyon-Keep & Council Hall"),
            ("inn", "inn", "belkarth_crossroads_tavern", "The Crossroads Tavern"),
            ("store", "general_store", "belkarth_delvers_outfitter", "Craglorn Delvers' Outfitter"),
            ("smithy", "smithy", "belkarth_crux_steel_forge", "Nirncrux-Tempered Anvil & Forge"),
            ("alchemist", "alchemist", "belkarth_canyon_herb_still", "Serpent-Scale & Sage Apothecary"),
            ("store", "bookseller", "belkarth_star_gazers_lodge", "Observatory Cartographer's Lodge"),
            ("guild_hall", "guild_hall", "belkarth_undaunted_enclave", "Canyon Adventurers' Muster Hall"),
            ("temple", "temple", "belkarth_arkay_tuwhacca_chapel", "Chapel of the Passing Stars"),
            ("building", "guardhouse", "belkarth_bridge_watch", "Canyon-Span Watchhouse"),
            ("stables", "stables", "belkarth_caravan_stables", "Elinhir-Road Caravan Stables"),
        ]
    ),
    (
        "pothago", "hammerfell", "B", "redguard", "dragontail", "red-rock-copper-gorge-fief",
        "headframe", "Pothago Red-Gorge Hoist", True, False,
        [
            ("palace", "keep", "pothago_red_rock_citadel", "Red-Rock Citadel of Pothago"),
            ("inn", "inn", "pothago_smelters_dram", "The Smelter's Dram Inn"),
            ("store", "general_store", "pothago_gorge_commissary", "Copper-Gorge Miners' Commissary"),
            ("smithy", "smithy", "pothago_bronze_and_iron_forge", "Red-Copper & Pickaxe Foundry"),
            ("alchemist", "alchemist", "pothago_malachite_mortar", "Crushed-Malachite & Sulphur Shop"),
            ("temple", "temple", "pothago_onsi_forge_shrine", "Rock-Cut Shrine of Onsi the Boneshaver"),
            ("mine", "smelter", "pothago_terraced_furnaces", "Wind-Draft Copper Smelting Furnaces"),
            ("manor", "manor", "pothago_assayer_house", "Royal Ore-Assayer's Stone House"),
            ("building", "guardhouse", "pothago_canyon_chain_gate", "Narrow-Gorge Chain Watch"),
            ("stables", "stables", "pothago_ore_mule_corrals", "Ingot-Train Mule Corrals"),
        ]
    ),
    (
        "tigonus", "hammerfell", "B", "redguard", "dragontail", "canyon-step-bridge-town",
        "minaret", "Tigonus Bridge-Watch Minaret", True, False,
        [
            ("palace", "keep", "tigonus_span_keep", "Tigonus Canyon-Span Keep"),
            ("inn", "inn", "tigonus_high_arch_serai", "The High-Arch Caravanserai"),
            ("store", "general_store", "tigonus_pack_saddle_suq", "Pack-Saddle & Grain Suq"),
            ("smithy", "smithy", "tigonus_mule_shoe_forge", "Canyon-Iron Farrier & Blade Smithy"),
            ("alchemist", "alchemist", "tigonus_rock_rose_still", "Rock-Rose & Goat-Rue Apothecary"),
            ("temple", "temple", "tigonus_hoon_ding_arch_fane", "Bridge-Head Fane of the HoonDing"),
            ("store", "tannery", "tigonus_goat_hide_yard", "Red-Ochre Goat-Hide Tannery"),
            ("manor", "manor", "tigonus_bridge_reeve_hall", "Toll-Reeve's Cliff-Ledge Manor"),
            ("building", "guardhouse", "tigonus_south_arch_watch", "Span-Head Watchtower"),
            ("stables", "stables", "tigonus_step_terraces_pens", "Switchback Mule & Goat Pens"),
        ]
    ),
    (
        "dakfron", "hammerfell", "B", "redguard", "dragontail", "badlands-mesa-watch-town",
        "dome_temple", "Dak'fron Badlands Sanctuary", True, False,
        [
            ("palace", "keep", "dakfron_mesa_keep", "Dak'fron Badlands Watch-Keep"),
            ("inn", "inn", "dakfron_scorpions_tail_inn", "The Scorpion's Tail Inn"),
            ("store", "general_store", "dakfron_cistern_provisions", "Red-Mesa Water & Ration Store"),
            ("smithy", "smithy", "dakfron_flint_and_steel_forge", "Badlands Spear & Shield Smithy"),
            ("alchemist", "alchemist", "dakfron_dust_salve_shop", "Alkali-Dust & Cactus-Balm Shop"),
            ("temple", "temple", "dakfron_tuwhacca_mesa_fane", "Wind-Scoured Fane of Tu'whacca"),
            ("hall_of_dead", "hall_of_dead", "dakfron_ochre_shaft_tombs", "Ochre-Cliff Shaft Tombs"),
            ("barracks", "barracks", "dakfron_outland_patrol_hall", "Badlands Outrider Barracks"),
            ("building", "guardhouse", "dakfron_wadi_watchtower", "Dry-Wash Watchtower"),
            ("stables", "stables", "dakfron_camel_picket_yard", "Outrider Camel Picket-Yard"),
        ]
    ),
    # ==================== HAMMERFELL VILLAGES (6) ====================
    (
        "morwhas_bounty", "hammerfell", "C", "redguard", "alikr_desert", "oasis-spring-fig-grove-village",
        "dome_temple", "Shrine of Morwha's Spring", False, False,
        [
            ("inn", "inn", "morwhas_bounty_sweet_water_inn", "The Sweet-Water Oasis Rest"),
            ("store", "general_store", "morwhas_bounty_fig_and_date", "Fig-Cake & Waterskin Stall"),
            ("alchemist", "alchemist", "morwhas_bounty_lotus_pool_herbs", "Spring-Lotus & Mint Herbalist"),
            ("smithy", "smithy", "morwhas_bounty_copper_dipper", "Copper-Dipper & Farrier Smithy"),
            ("temple", "temple", "morwhas_bounty_spring_pavilion", "Four-Arch Pavilion of Morwha"),
            ("stables", "barn", "morwhas_bounty_camel_shade", "Palm-Frond Camel Shade-Barn"),
        ]
    ),
    (
        "saintsport", "hammerfell", "C", "redguard", "stros_mkai", "coral-cove-careening-village",
        "sea_stack", "Saintsport Cove Beacon-Rock", False, True,
        [
            ("inn", "inn", "saintsport_broken_rudder", "The Broken Rudder Tavern"),
            ("store", "general_store", "saintsport_tar_and_hemp", "Tar-Pot & Hemp-Cable Store"),
            ("smithy", "smithy", "saintsport_caulkers_iron_forge", "Caulker's Iron & Spike Smithy"),
            ("building", "fishery", "saintsport_turtle_crawl_pier", "Sea-Turtle & Flying-Fish Pier"),
            ("alchemist", "alchemist", "saintsport_lime_and_rum_hut", "Lime-Cordial & Salt-Poultice Hut"),
            ("shrine", "temple", "saintsport_tava_shell_shrine", "Conch-Shell Shrine of Tava"),
        ]
    ),
    (
        "thrall_cove", "hammerfell", "C", "redguard", "hews_bane", "smugglers-limestone-sea-cave-cove",
        "chain_tower", "Thrall Cove Winch-Tower", False, True,
        [
            ("inn", "inn", "thrall_cove_muffled_oar", "The Muffled Oar Taproom"),
            ("store", "general_store", "thrall_cove_prize_goods", "Unmarked-Cask & Cordage Store"),
            ("smithy", "smithy", "thrall_cove_shackle_and_file", "Cold-Chisel & Grappling Smithy"),
            ("building", "warehouse", "thrall_cove_sea_cave_vault", "Tide-Gate Smugglers' Store-Cave"),
            ("alchemist", "alchemist", "thrall_cove_night_bloom_hut", "Moon-Sugar & Coral-Dust Mixer"),
            ("building", "guardhouse", "thrall_cove_cliff_lookout", "Headland Signal-Lantern Lookout"),
        ]
    ),
    (
        "haddocks_market", "hammerfell", "C", "redguard", "craglorn", "canyon-crossroads-tent-suq",
        "awning_row", "Haddock's Canvas Suq", False, False,
        [
            ("inn", "inn", "haddocks_market_dusty_flagon", "The Dusty Flagon Tent-Inn"),
            ("store", "general_store", "haddocks_market_salvage_stalls", "Nedic-Relic & Rope Outfitter"),
            ("smithy", "smithy", "haddocks_market_tinker_anvil", "Travelling Tinker's Anvil"),
            ("alchemist", "alchemist", "haddocks_market_scorpion_oil", "Rock-Scorpion Oil & Tonic Stall"),
            ("stables", "barn", "haddocks_market_pack_lines", "Canyon Pack-Mule Tether-Lines"),
            ("shrine", "temple", "haddocks_market_wayfarer_cairn", "Wayfarers' Cairn of Zenithar"),
        ]
    ),
    (
        "tavorra_cove", "hammerfell", "C", "redguard", "stros_mkai", "leeward-shallop-anchorage",
        "sea_stack", "Tavorra Leeward Reef-Stack", False, True,
        [
            ("inn", "inn", "tavorra_cove_driftwood_cup", "The Driftwood Cup Tavern"),
            ("store", "general_store", "tavorra_cove_net_and_line", "Net-Twine & Dried-Squid Store"),
            ("building", "fishery", "tavorra_cove_drying_racks", "Coral-Shallop & Fish-Drying Racks"),
            ("smithy", "smithy", "tavorra_cove_hook_smithy", "Bronze-Hook & Harpoon Smithy"),
            ("alchemist", "alchemist", "tavorra_cove_kelp_iodine_hut", "Sea-Kelp & Salt-Balm Hut"),
            ("shrine", "temple", "tavorra_cove_wind_vane_shrine", "Copper Wind-Vane Shrine of Tava"),
        ]
    ),
    (
        "tavalaam_docks", "hammerfell", "C", "redguard", "alikr_desert", "southern-shore-dhow-wharf",
        "awning_row", "Tavalaam Wharf Shade-Awnings", False, True,
        [
            ("inn", "inn", "tavalaam_docks_lateen_spar", "The Lateen Spar Inn"),
            ("store", "general_store", "tavalaam_docks_salt_bale_store", "Salt-Bale & Amphora Wharf-Store"),
            ("building", "fishery", "tavalaam_docks_dhow_jetty", "Coaster-Dhow & Lighter Jetty"),
            ("smithy", "smithy", "tavalaam_docks_copper_nail_forge", "Copper-Sheathing & Nail Forge"),
            ("building", "warehouse", "tavalaam_docks_customs_shed", "Coast-Road Bonded Dates Shed"),
            ("shrine", "temple", "tavalaam_docks_harbour_shrine", "Breakwater Lantern-Shrine of Tava"),
        ]
    ),
]


def pick_unique_features(pid, place_type, province, culture, index_num, used_sigs):
    """Generates a deterministic, lore-fitting feature list whose count is in featRange[place_type]
    and whose sorted signature is globally unique across all cityplans."""
    ranges = {
        "capital": (15, 28),
        "city": (9, 14),
        "town": (5, 8),
        "village": (2, 4),
        "hamlet": (1, 2),
    }
    lo, hi = ranges.get(place_type, (4, 8))
    if province == "high_rock":
        tree_themes = ["oak", "birch", "spruce", "dark_oak", "willow"]
        shrines = [
            "divine:mara", "divine:stendarr", "divine:julianos", "divine:dibella",
            "divine:kynareth", "divine:akatosh", "divine:zenithar", "divine:arkay",
        ]
        if culture == "orc":
            shrines = ["daedric:malacath", "divine:stendarr", "divine:zenithar"]
    else:
        tree_themes = ["acacia", "jungle", "oak", "birch"]
        shrines = [
            "divine:zenithar", "divine:stendarr", "divine:kynareth", "divine:arkay",
            "divine:mara", "divine:julianos", "divine:dibella", "divine:akatosh",
        ]

    # Try combinations deterministically using index_num + salt until unique
    for salt in range(200):
        n = index_num * 7 + salt
        tree_t = tree_themes[n % len(tree_themes)]
        shrine_t = shrines[(n // 2) % len(shrines)]
        statue_t = shrines[(n // 3 + 1) % len(shrines)]

        if place_type == "village":
            # Total 2..4
            patterns = [
                [{"type": "well", "count": 1}, {"type": "lamps", "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}],
                [{"type": "well", "count": 1}, {"type": "banners", "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}],
                [{"type": "well", "count": 1}, {"type": "bench", "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}, {"type": "lamps", "count": 1}],
                [{"type": "well", "count": 1}, {"type": "great_tree", "theme": tree_t, "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}],
                [{"type": "well", "count": 1}, {"type": "market", "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}, {"type": "banners", "count": 1}],
                [{"type": "fountain", "count": 1}, {"type": "lamps", "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}],
                [{"type": "well", "count": 2}, {"type": "shrine", "theme": shrine_t, "count": 1}, {"type": "memorial", "count": 1}],
                [{"type": "basin", "count": 1}, {"type": "lamps", "count": 2}, {"type": "shrine", "theme": shrine_t, "count": 1}],
                [{"type": "well", "count": 1}, {"type": "garden", "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}, {"type": "bench", "count": 1}],
                [{"type": "well", "count": 1}, {"type": "flowers", "count": 1}, {"type": "shrine", "theme": shrine_t, "count": 1}, {"type": "lamps", "count": 1}],
            ]
            feats = patterns[n % len(patterns)]
        elif place_type == "town":
            # Total 4..8
            extras = [
                ("fountain", ""), ("garden", ""), ("orchard", ""), ("bench", ""),
                ("memorial", ""), ("flowers", ""), ("basin", ""), ("bell_tower", ""),
                ("gatehouse", ""), ("pond", ""), ("pool", ""), ("waterwheel", ""), ("tree_row", ""),
            ]
            ex1_t, ex1_th = extras[n % len(extras)]
            ex2_t, ex2_th = extras[(n // 3 + 2) % len(extras)]
            if ex1_t == ex2_t:
                ex2_t, ex2_th = extras[(n // 3 + 5) % len(extras)]
            lamp_c = 1 + (n % 2)
            ban_c = 1 + ((n // 2) % 2)
            feats = [
                {"type": "well", "count": 1},
                {"type": "lamps", "count": lamp_c},
                {"type": "banners", "count": ban_c},
                {"type": "shrine", "theme": shrine_t, "count": 1},
                {"type": "great_tree", "theme": tree_t, "count": 1},
                {"type": ex1_t, **({"theme": ex1_th} if ex1_th else {}), "count": 1},
            ]
            if sum(f["count"] for f in feats) < 7 and (n % 3 == 0):
                feats.append({"type": ex2_t, **({"theme": ex2_th} if ex2_th else {}), "count": 1})
        else:
            # city (8..15)
            extras = [
                ("fountain", ""), ("garden", ""), ("orchard", ""), ("bench", ""),
                ("memorial", ""), ("flowers", ""), ("basin", ""), ("bell_tower", ""),
                ("gatehouse", ""), ("pond", ""), ("pool", ""), ("waterwheel", ""), ("tree_row", ""), ("aqueduct", ""),
            ]
            ex1_t, _ = extras[n % len(extras)]
            ex2_t, _ = extras[(n + 3) % len(extras)]
            ex3_t, _ = extras[(n + 7) % len(extras)]
            feats = [
                {"type": "well", "count": 1 + (n % 2)},
                {"type": "lamps", "count": 2 + ((n // 2) % 2)},
                {"type": "banners", "count": 2 + ((n // 3) % 2)},
                {"type": "market", "count": 1},
                {"type": "shrine", "theme": shrine_t, "count": 1},
                {"type": "statue", "theme": statue_t, "count": 1},
                {"type": "great_tree", "theme": tree_t, "count": 1},
                {"type": ex1_t, "count": 1},
                {"type": ex2_t, "count": 1},
            ]
            if sum(f["count"] for f in feats) < 13:
                feats.append({"type": ex3_t, "count": 1})

        total = sum(f.get("count", 1) for f in feats)
        sig = feat_sig(feats)
        if lo <= total <= hi and sig not in used_sigs:
            used_sigs.add(sig)
            return feats

    raise RuntimeError(f"Could not find unique feature signature for {pid}")


def build_plan(spec, place, df_seed, idx_num, used_sigs):
    pid, province, tier, culture, overlay, sil, lm_id, lm_title, walled, port, named_blds = spec
    ptype = place["type"]
    prad = place["radius"]

    if ptype == "capital":
        radius = min(prad, 130)
    elif ptype == "city":
        radius = min(prad, 118 if prad >= 150 else 96)
    elif ptype == "town":
        radius = min(prad, 80 if prad >= 90 else (54 if prad >= 60 else 46))
    else:
        radius = min(prad, 34 if prad >= 38 else 24)

    reach = min(prad + 12, radius + 8)
    wall_r = radius - 4

    # Gates: use df_seed gate sides if available, else 4 cardinal gates (or 2 for small villages)
    gates = [
        [0, -wall_r],
        [wall_r, 0],
        [0, wall_r],
        [-wall_r, 0],
    ]
    if ptype == "village":
        gates = [[0, -wall_r], [0, wall_r]]

    lm_key = f"{pid}_landmark"
    titles = {lm_key: lm_title}

    landmarks = [
        {
            "id": lm_id,
            "at": [0, -int(radius * 0.42)],
            "face": "south",
            "name": lm_key,
            "overlay": overlay,
        }
    ]

    # Place named buildings on an orderly ring/grid inside radius * 0.72 so every point is well inside radius
    buildings = []
    n_blds = len(named_blds)
    max_r = max(14, int(radius * 0.70))
    min_r = max(8, int(radius * 0.24))

    inn_key = ""
    store_key = ""
    smithy_key = ""

    for i, (kind, use_id, bkey, btitle) in enumerate(named_blds):
        titles[bkey] = btitle
        if not inn_key and (kind == "inn" or use_id == "inn"):
            inn_key = bkey
        if not store_key and (kind == "store" or use_id == "general_store"):
            store_key = bkey
        if not smithy_key and (kind == "smithy" or use_id == "smithy"):
            smithy_key = bkey

        # Distribute across two concentric rings so nothing overlaps and everything is inside max_r
        ring_frac = 0.45 if (i % 2 == 0) else 0.70
        r_dist = int(radius * ring_frac)
        r_dist = max(min_r, min(max_r, r_dist))
        # Avoid angle = -pi/2 (north, where landmark sits)
        angle = -math.pi * 0.30 + (i / max(1, n_blds)) * (math.pi * 1.65)
        bx = int(round(math.cos(angle) * r_dist))
        bz = int(round(math.sin(angle) * r_dist))
        if abs(bx) > abs(bz):
            face = "west" if bx > 0 else "east"
        else:
            face = "north" if bz > 0 else "south"

        wealth = "common"
        if kind in ("palace", "manor") or use_id in ("keep", "manor", "jeweller"):
            wealth = "noble" if kind == "palace" else "wealthy"
        elif kind in ("guild_hall", "temple") or use_id in ("guild_hall", "temple", "bookseller"):
            wealth = "wealthy"

        b_entry = {
            "kind": kind,
            "use": use_id,
            "wealth": wealth,
            "at": [bx, bz],
            "face": face,
            "name": bkey,
        }
        buildings.append(b_entry)

    # Streets: cardinal cross + inner loop tag "main" and "ring"
    s_reach = int(wall_r * 0.88)
    ring_r = int(radius * 0.52)
    streets = [
        {"from": [0, -s_reach], "to": [0, s_reach], "width": 5 if ptype in ("capital", "city") else 4, "major": True, "fill": True, "tag": "main"},
        {"from": [-s_reach, 0], "to": [s_reach, 0], "width": 5 if ptype in ("capital", "city") else 4, "major": True, "fill": True, "tag": "main"},
    ]
    if ptype in ("capital", "city", "town"):
        streets.extend([
            {"from": [-ring_r, -ring_r], "to": [ring_r, -ring_r], "width": 4, "major": False, "fill": True, "tag": "ring"},
            {"from": [ring_r, -ring_r], "to": [ring_r, ring_r], "width": 4, "major": False, "fill": True, "tag": "ring"},
            {"from": [ring_r, ring_r], "to": [-ring_r, ring_r], "width": 4, "major": False, "fill": True, "tag": "ring"},
            {"from": [-ring_r, ring_r], "to": [-ring_r, -ring_r], "width": 4, "major": False, "fill": True, "tag": "ring"},
        ])

    lines = []
    if port:
        # Add a dockside canal/bridge feature inside radius
        q_z = int(radius * 0.72)
        lines.append({"kind": "bridge", "from": [-8, q_z - 6], "to": [8, q_z - 6], "width": 4})

    walls = []
    if walled:
        w_flags = ["palisade"] if culture == "orc" and ptype == "village" else []
        walls.append({
            "r": wall_r,
            "sides": 12 if ptype in ("capital", "city") else 8,
            "h": 9 if ptype in ("capital", "city") else 7,
            "gates": 4 if len(gates) >= 4 else 2,
            "towers": True,
            **({"flags": w_flags} if w_flags else {}),
        })

    plaza_key = f"{pid}_market_plaza"
    titles[plaza_key] = f"{titles[lm_key].split()[0]} Market Square"
    plazas = [
        {"at": [0, 4], "size": 12 if ptype in ("capital", "city") else 9, "stalls": 6 if ptype in ("capital", "city") else 3, "name": plaza_key}
    ]

    # Queue for lot filling (derived from Daggerfall RMB building_types count scaled to NirnCraft lot density)
    house_count = 28 if ptype in ("capital", "city") else (16 if ptype == "town" else 8)
    if df_seed and "building_types" in df_seed:
        df_houses = df_seed["building_types"].get("house2", 0) + df_seed["building_types"].get("house3", 0)
        if df_houses > 0:
            house_count = max(house_count, min(38 if ptype in ("capital", "city") else 22, df_houses // 5))

    queue = [
        {"kind": "house", "use": "townhouse" if ptype in ("capital", "city") else "house", "wealth": "wealthy", "count": max(4, house_count // 3), "on": "main", "flags": ["upper"]},
        {"kind": "house", "use": "house", "wealth": "common", "count": house_count, "on": "ring" if ptype != "village" else "main"},
        {"kind": "house", "use": "cottage", "wealth": "poor", "count": max(3, house_count // 4), "on": "ring" if ptype != "village" else "main", "flags": ["for_sale"]},
    ]

    quarters = [
        {"ring": [0, int(radius * 0.50)], "overlay": overlay},
        {"ring": [int(radius * 0.50), radius], "overlay": overlay},
    ]

    features = pick_unique_features(pid, ptype, province, culture, idx_num, used_sigs)

    plan = {
        "id": pid,
        "sil": sil,
        "base": "none",
        "overlay": overlay,
        "radius": radius,
        "reach": reach,
        "fill_gap": 2,
        "inn_key": inn_key,
        "names": {
            "inn": inn_key,
            "store": store_key,
            "smithy": smithy_key,
        },
        "titles": titles,
        "gates": gates,
        "walls": walls,
        "landmarks": landmarks,
        "buildings": buildings,
        "streets": streets,
        "lines": lines,
        "plazas": plazas,
        "queue": queue,
        "quarters": quarters,
        "features": features,
    }
    return plan


def upgrade_existing_plan(pid, extra_blds):
    fp = PLANS_DIR / f"{pid}.json"
    plan = json.loads(fp.read_text(encoding="utf-8"))
    blds = plan.get("buildings", [])
    titles = plan.get("titles", {})
    existing_names = {b.get("name") for b in blds if b.get("name")}

    for kind, use_id, at, face, bkey, btitle, wealth in extra_blds:
        titles[bkey] = btitle
        if bkey not in existing_names:
            blds.append({
                "kind": kind,
                "use": use_id,
                "wealth": wealth,
                "at": at,
                "face": face,
                "name": bkey,
            })
            existing_names.add(bkey)

    # Also ensure any unnamed buildings in the existing plan get a canonical name key
    for idx, b in enumerate(blds):
        if not b.get("name"):
            k = f"{pid}_{b['kind']}_{idx + 1}"
            b["name"] = k
            if k not in titles:
                titles[k] = f"{pid.replace('_', ' ').title()} {b['kind'].replace('_', ' ').title()} {idx + 1}"

    plan["buildings"] = blds
    plan["titles"] = titles
    fp.write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    return len([b for b in blds if b.get("name")])


SOCIETY_DIR = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "society"
WORLD_DIR = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "tamriel" / "world"
LANG_DIR = ROOT / "mod" / "src" / "main" / "lang" / "nirncraft"

SKILLS_18 = [
    "one_handed", "two_handed", "archery", "block", "smithing", "heavy_armor",
    "light_armor", "pickpocket", "lockpicking", "sneak", "alchemy", "speech",
    "alteration", "conjuration", "destruction", "illusion", "restoration", "enchanting",
]

SKILL_WORKPLACE = {
    "one_handed": "housecarl",
    "two_handed": "housecarl",
    "archery": "fletcher",
    "block": "housecarl",
    "smithing": "smith",
    "heavy_armor": "smith",
    "light_armor": "tailor",
    "pickpocket": "inn",
    "lockpicking": "inn",
    "sneak": "inn",
    "alchemy": "alchemist",
    "speech": "merchant",
    "alteration": "wizard",
    "conjuration": "wizard",
    "destruction": "wizard",
    "illusion": "wizard",
    "restoration": "priest",
    "enchanting": "wizard",
}

SETTLEMENT_REGION = {
    # High Rock (9 regions)
    "daggerfall": "daggerfall", "ilessan_hills": "daggerfall", "aldcroft": "daggerfall", "tulune": "daggerfall",
    "camlorn": "glenumbra", "glenpoint": "glenumbra", "northmoor": "glenumbra", "daenia": "glenumbra",
    "glenumbra_moors": "glenumbra", "crosswych": "glenumbra", "eagles_brook": "glenumbra", "westtry": "glenumbra",
    "alcaire": "stormhaven", "kambria": "stormhaven", "koeglin_village": "stormhaven",
    "anticlere": "anticlere", "shalgora": "anticlere", "urvaius": "anticlere", "ykalon": "anticlere",
    "dwynnen": "dwynnen",
    "wayrest": "wayrest", "dreughside": "wayrest",
    "shornhelm": "rivenspire", "northpoint": "rivenspire", "crestshade": "rivenspire",
    "hoarfrost_downs": "rivenspire", "fells_run": "rivenspire",
    "evermor": "bjoulsae", "farrun": "bjoulsae", "phrygias": "bjoulsae", "bhoriane": "bjoulsae", "menevia": "bjoulsae",
    "orsinium": "wrothgar", "jehanna": "wrothgar", "gavaudon": "wrothgar",
    "morkul_stronghold_high_rock": "wrothgar", "fharun_stronghold": "wrothgar", "tumnosh_stronghold": "wrothgar",
    # Hammerfell (7 regions)
    "sentinel": "sentinel", "antiphyllos": "sentinel", "myrkwasa": "sentinel", "cybiades": "sentinel", "abibon_gora": "sentinel",
    "skaven": "alikr_desert", "gilane": "alikr_desert", "hegathe": "alikr_desert", "kozanset": "alikr_desert",
    "bergama": "alikr_desert", "satakalaam": "alikr_desert", "totambu": "alikr_desert", "kairou": "alikr_desert",
    "ephesus": "alikr_desert", "morwhas_bounty": "alikr_desert", "tavalaam_docks": "alikr_desert",
    "hallins_stand": "bangkorai", "lainlyn": "bangkorai", "mournoth": "bangkorai", "ayasofya": "bangkorai",
    "rihad": "dragontail", "pothago": "dragontail", "tigonus": "dragontail", "dakfron": "dragontail",
    "stros_mkai": "stros_mkai", "saintsport": "stros_mkai", "tavorra_cove": "stros_mkai",
    "elinhir": "craglorn", "dragonstar": "craglorn", "belkarth": "craglorn", "haddocks_market": "craglorn",
    "taneth": "hews_bane", "abahs_landing": "hews_bane", "thrall_cove": "hews_bane",
}

NAMED_COURTS = {
    "daggerfall": ("Castle Daggerfall", "King", "King Gothryd", "Queen Aubk-i", "Medora Direnni", "Lady Bridwell"),
    "wayrest": ("Castle Wayrest", "King", "King Eadwyre", "Queen Barenziah", "Archmagister Vane", "Lord Castellian"),
    "orsinium": ("Gortwog's Iron Citadel", "War-King", "King Gortwog gro-Nagorm", "Smith-Mother Ushra", "Skald Torog", "Chief Gulmak"),
    "camlorn": ("Castle Camlorn", "Duke", "Duke Olo of Camlorn", "Lady Hvarine", "Archpriest Elric", "Bellwright Tam"),
    "shornhelm": ("The Red Keep", "High Lord", "High Lord Ranser", "Sergeant Brom", "Magister Verandis", "Marshal Ysolde"),
    "evermor": ("Castle Evermor", "Duke", "Duke Eamond Guimard", "Lamplighter Vey", "Bishop Mallory", "Sir Renaud de Guimard"),
    "northpoint": ("Castle Northpoint", "Baron", "Baron Dorell", "Reeve Maude", "Keeper Sorley", "Captain Vance Dorell"),
    "farrun": ("Farrun Sea-Wall Keep", "Sea-Reeve", "Sea-Reeve Garn", "Steward Aulde", "Runespeaker Bera", "Huscarl Torvald"),
    "jehanna": ("Jehanna Fjord-Crown Hall", "Thane-Lord", "Thane-Lord Cedric", "Steward Astrid", "Frost-Seer Ylva", "Shield-Captain Rolf"),
    "glenpoint": ("Glenpoint Headland Keep", "Reeve", "Reeve Dunstan", "Pilot Edda", "Old Corwin", "Captain Brice"),
    "sentinel": ("Palace of the Crown", "King", "King Lhotun", "Vizier Samir al-Hadid", "Grand Astrologer Azra", "Captain Layla Ashraf"),
    "rihad": ("Alcazar of Rihad", "Emir", "Emir Rashad at-Rihad", "Spice-Master Rasul", "Imam Tahir", "Captain Kemal"),
    "taneth": ("The Turquoise Palace", "Emir", "Emir Jalil", "Harbour-Reeve Ben", "Najwa the Gardener", "Captain Tariq"),
    "skaven": ("Citadel of Skaven", "Prince", "Prince-Governor Hazim", "Water-Bailiff Qadir", "Star-Reader Zulaikha", "Captain Jibril"),
    "elinhir": ("The Apex Citadel", "Warden", "Warden Kaia", "Stairs-Keeper Jul", "Brother Amir", "Captain Cassia"),
    "gilane": ("Castle Gilane", "Syndic", "Port-Syndic Bashir", "Master-Dyer Soraya", "Navigator Hakeem", "Captain Zaid"),
    "hegathe": ("Ancestral Citadel of Hegathe", "High-Elder", "High-Elder Koth at-Hegathe", "Roll-Keeper Idi", "Temple-Seer Aziza", "First-Shield Tarek"),
    "dragonstar": ("Palace of the Star", "Arena-Lord", "Arena-Master Dahab", "Bookmaker Rim", "Star-Mage Halima", "Gladiator Safiya"),
    "abahs_landing": ("Grand Counting-Palace", "Grand Syndic", "Grand Syndic Hosni", "Old Karim", "Salwa the Chart-Reader", "Captain Hadiya"),
    "stros_mkai": ("Palace of Port Hunding", "Headman", "Headman Bhisha", "Zahra the Harbourmaster", "Old Salim", "Captain Makki"),
}

BRETON_FIRST = [
    "Alaric", "Aldous", "Amaury", "Ancel", "Anselm", "Arnault", "Aubert", "Bastien", "Benoit", "Bertrand",
    "Blaise", "Brice", "Cedric", "Charles", "Christophe", "Claude", "Colin", "Conrad", "Corwin", "Damien",
    "Denis", "Didier", "Dunstan", "Edouard", "Elric", "Emile", "Etienne", "Evrard", "Fabien", "Florian",
    "Francois", "Gaspard", "Gaston", "Gauthier", "Geoffroy", "Gerard", "Gervais", "Gilbert", "Gilles", "Godfrey",
    "Guillaume", "Guy", "Henri", "Hugues", "Jacques", "Jean", "Jerome", "Jules", "Julien", "Lambert",
    "Laurent", "Leon", "Lionel", "Louis", "Luc", "Marcel", "Martin", "Mathieu", "Maurice", "Michel",
    "moreau", "Nicolas", "Odo", "Olivier", "Pascal", "Patrice", "Philippe", "Pierre", "Quentin", "Raoul",
    "Raymond", "Remy", "Renaud", "Richard", "Robert", "Roland", "Sebastien", "Simon", "Thierry", "Tristan",
    "Adelaide", "Adele", "Adrienne", "Agnes", "Alix", "Amelie", "Annette", "Ariane", "Aurore", "Beatrice",
    "Bernadette", "Blanche", "Brigitte", "Camille", "Catherine", "Cecile", "Celeste", "Charlotte", "Claire", "Claudine",
    "Colette", "Constance", "Corinne", "Delphine", "Denise", "Diane", "Edith", "Eleanor", "Elise", " Eloise",
    "Emilie", "Estelle", "Eveline", "Fabienne", "Fleur", "Florence", "Francine", "Genevieve", "Giselle", "Helene",
    "Henriette", "Hvarine", "Isabelle", "Iselle", "Jacqueline", "Jeanne", "Juliette", "Laure", "Leonie", " Lisette",
    "Louise", "Lucie", " Madeleine", "Marguerite", "Marie", "Martine", "Mathilde", "Maude", " Mireille", "Monique",
    "Nadine", "Nicole", "Odette", "Odelle", " Pauline", "Pelline", "Rosalie", "Sabine", "Simone", " Solange",
    "Sophie", "suzanne", "Sylvie", "Therese", "Valerie", "Veronique", "Vivienne", "Ysolde", "Yvette", "Yvonne",
]

BRETON_LAST = [
    "Ashcroft", "Aubert", "Balfour", "Beauchamp", "Beaumont", "Bellamont", "Benoit", "Beringer", "Blanchard", "Boistel",
    "Bonnet", "Bouchard", "Bridwell", "Brousseau", "Camber", "Caron", "Castellian", "Chauvin", "Chevalier", "Cloutier",
    "Colbert", "Corbin", "Courtenay", "Cumberland", "D'Alembert", "D'Artois", "Deleyn", "Delacroix", "Deschamps", "Devaux",
    "Dorell", "Dubois", "Dufour", "Dumont", "Dupont", "Durand", "Duval", "Faucher", "Fontaine", "Fournier",
    "Gaillard", "Gardner", "Garnier", "Gautier", "Gervais", "Girard", "Godefroy", "Granville", "Guimard", "Horley",
    "Joubert", "Lacroix", "Lambert", "Langlois", "Laurent", "Leblanc", "Leclerc", "Lefevre", "Lemaire", "Lemieux",
    "Leroux", "Levesque", "Lombard", "Lortie", "Mallet", "Marchand", "Martel", "Masson", "Mercier", "Meunier",
    "Michaud", "Monette", "Montclair", "Moreau", "Morel", "Morin", "Noel", "Olivier", "Pelletier", "Perrin",
    "Picard", "Poirier", "Pratt", "Prevost", "Quinlan", "Renaud", "Richer", "Riviere", "Roche", "Rousseau",
    "Roussel", "Savard", "St-Clair", "Tamrith", "Tessier", "Thibault", "Tremblay", "Vane", "Vasseur", "Vidron",
]

ORC_FIRST = [
    "agrob", "Azuk", "Bagamul", "Bagrakh", "akh", "Bashag", "Bazur", "Bogakh", "Bogrum", "Bolar",
    "Borba", "Brogdush", "Bugdul", "Burz", "Dul", "Dulfish", "Duma", "Durgash", "Durz", "Durzub",
    "Gashna", "Gaturn", "Ghamorz", "Gharz", "Ghob", "Ghorbash", "Ghorgdush", "Ghoth", "Gluronk", "Gnush",
    "Gogron", "Gorgo", "Gortwog", "Graklak", "Graman", "Grogmar", "Grulam", "Gulfim", "Gulmak", "Gureryne",
    "Khadba", "Khagra", "Kharzug", "Krognak", "Kurdan", "Lagrakh", "Lazgar", "Lob", "Lorzub", "Lurog",
    "Magra", "Makhel", "Mazoga", "Mogak", "Morn", "Morag", "Moth", "Mulakh", "Murag", "Muzgonk",
    "Nagorm", "Nar", "Nargol", "Ogol", "Ogrul", "Olumba", "Orakh", "Rimintil", "Rogbut", "Rogmesh",
    "Rulfim", "Shagrol", "Shakh", "Shamar", "Sharn", "Shatul", "Shobob", "Shuftharz", "Snaglak", "Torog",
    "Tumnosh", "Ugdul", "Ugor", "Ulam", "Umug", "Urag", "Urim", "Urzoth", "Ushnar", "Ushra",
    "Uzul", "Yadba", "Yamarz", "Yatur", "Yazgash", "Zubesha", "Zulba", "Atub", "Bulfim", "Ghorza",
]

ORC_CLAN = [
    "gro-Bagrakh", "gra-Bagrakh", "gro-Morkul", "gra-Morkul", "gro-Fharun", "gra-Fharun",
    "gro-Tumnosh", "gra-Tumnosh", "gro-Shatul", "gra-Shatul", "gro-Igrun", "gra-Igrun",
    "gro-Orsinium", "gra-Orsinium", "gro-Nagorm", "gra-Nagorm", "gro-Wrothgar", "gra-Wrothgar",
    "gro-Dushnikh", "gra-Dushnikh", "gro-Largash", "gra-Largash", "gro-KharGA", "gra-Kharga",
]

REDGUARD_FIRST = [
    "Abbas", "Adil", "Akram", "Amir", "Anwar", "Arslan", "Ashraf", "Asim", "Ayman", "Azad",
    "Azim", "Badr", "Bahir", "Barak", "Bashir", "Basim", "Bilal", "Cyrus", "Dahab", "Dawud",
    "Fadil", "Fahd", "Faiz", "Fakhir", "Farid", "Faruq", "Faysal", "Ghassan", "Habib", "Hadi",
    "Hakeem", "Halim", "Hamid", "Hamza", "Harun", "Hasan", "Hashim", "Hatim", "Haytham", "Hazim",
    "Hosni", "Husam", "Ibrahim", "Idris", "Ilyas", "Imad", "Imran", "Irfan", "Isa", "Ishaq",
    "Ismail", "Jabir", "Jafar", "Jalal", "Jalil", "Jamal", "Jamil", "Jawad", "Jibril", "Kamal",
    "Kamil", "Karim", "Kasim", "Koth", "Khalid", "Khalil", "Lhotun", "Luqman", "Mahir", "Mahmud",
    "Majid", "Makki", "Malik", "Mansur", "Marwan", "Mazin", "Murad", "inh", "Mustafa", "Nabil",
    "Nadim", "Nadir", "Najib", "Nasir", "Nasser", "Nawaf", "Nizar", "Numan", "Omar", "Qadir",
    "Qasim", "Rafiq", "Raheem", "Rashad", "Rashid", "Rasul", "Riyad", "Sabir", "Sadiq", "Said",
    "Salim", "Salman", "Samir", "Shakir", "Sharif", "Tahir", "Talib", "Tarek", "Tariq", "Tawfiq",
    "Walid", "Yahya", "Yasin", "Yasir", "Yusuf", "Zahir", "Zaid", "Zaki", "Zayd", "Zubayr",
    "Abla", "Adila", "Afaf", "Aisha", "Alia", "Amina", "Amira", "Anisa", "Arwa", "Asma",
    "Aziza", "Azra", "Badia", "Basma", "Bushra", "Dalal", "Dalia", "Dina", "Fadia", "Faiza",
    "Farah", "Farida", "Faten", "Fatima", "Fawzia", "Ghada", "Habiba", "Hadiya", "Hafsa", "Hala",
    "Halima", "Hana", "Hanan", "Hanifa", "Hasna", "Hawa", "Hayat", "Hiba", "Hind", "Huda",
    "Ibtisam", "Ilham", "Iman", "Inas", "Jamila", "Karima", "Khadija", "Khalida", "Laila", "Lamia",
    "Latifa", "Layla", "Lila", "Lubna", "Maha", "Mahira", "Maisa", "Malika", "Manal", "Maram",
    "Mariam", "Maysa", "Mona", "Mouna", "Munira", "Nadia", "Nadira", "Nafisa", "Nahla", "Naima",
    "Najat", "Najwa", "Nawal", "Nihal", "Noor", "Noura", "Rabab", "Rana", "Rania", "Rasha",
    "Reem", "Rima", "Ruqayya", "Sabah", "Sabriya", "Safa", "Safiya", "Sahar", "Saida", "Salma",
    "Salwa", "Samah", "Samar", "Samia", "Samira", "Sana", "Sara", "Shadia", "Shakira", "Soraya",
    "Suad", "Suhaila", "Tahira", "Thana", "Wafa", "Warda", "Yara", "Yasmin", "Yusra", "Zahra",
    "Zainab", "Zareen", "Zaynab", "Zeinab", "ZHORA", "Zulaikha",
]

REDGUARD_SURNAME = [
    "al-Sentinel", "at-Sentinel", "al-Rihad", "at-Rihad", "al-Taneth", "at-Taneth",
    "al-Skaven", "at-Skaven", "al-Elinhir", "at-Elinhir", "al-Gilane", "at-Gilane",
    "al-Hegathe", "at-Hegathe", "al-Dragonstar", "al-Bergama", "at-Bergama", "al-Kozanset",
    "al-Satakalaam", "al-Totambu", "at-Totambu", "al-Kairou", "al-Ephesus", "al-Lainlyn",
    "al-Mournoth", "al-Ayasofya", "al-Belkarth", "al-Pothago", "al-Tigonus", "al-Dakfron",
    "al-Hadid", "Ashraf", "al-aran", "at-Tura", "al-Hallin", "at-Hunding", "al-Danobia",
    "al-Morwha", "at-Zeht", "al-Tava", "at-Ruptga", "al-Sahan", "at-Leki", "al-Qadir",
]


HIGH_ROCK_REGION_LORE = {
    "daggerfall": (
        "Daggerfall rules the southwestern cape of High Rock—whitewashed Tudor gables, guild bridges over the creek, and a bay harbour that watches Sentinel across the water.",
        [
            "Castle Daggerfall's twin curtain walls have broken three sieges since the First Era, and the harbour boom still rises every dusk.",
            "Tannery Row smells of oak-bark liquor and wet hides whenever the sea-breeze drops along the creek.",
            "King Gothryd and Queen Aubk-i hold court in the great hall while the Elder Council factors count every bale on the quay.",
            "The Knights of the Dragon muster in the upper ward whenever corsair sails are sighted off Glenpoint.",
            "Cross the guild-bridge at noon and you will hear six languages argued over a single bolt of Camlorn broadcloth.",
            "Ilessan Hills tin-miners bring their ingots down the turnpike under armed escort twice a week.",
            "Aldcroft's water-mills grind half the barley that feeds the royal garrison through the winter gales.",
            "Tulune's apple-orchards and hedgerow manors look peaceful enough, until two barons dispute a boundary stone.",
            "Medora Direnni's old tower on Balfiera still draws court mages who think they can untangle High Elven wards.",
            "Every tavern on the South Quay claims its cellar was dug by smugglers before King Lysandus took the throne.",
            "Wait until the fog rolls off the Iliac Bay and you'll understand why our watchmen carry shuttered bullseye lanterns.",
            "The Mages Guild scriptorium in Daggerfall charges three septims just to verify a seal on a charter.",
            "Order of the Dragon squires polish their plate with chalk-dust from the western downs until it shines like silver.",
            "Madam Pelline's ledger-clerks know which merchantmen will clear customs before the harbourmaster even breaks the wax.",
            "No ship rounds the cape without paying light-dues toward the Glenpoint and Betony beacons.",
            "If you buy a house inside the landward wall, make sure the deed is countersigned by the Royal Chamberlain.",
            "Betony's fishing grounds have started more wars between Daggerfall and Sentinel than all the kings' marriages.",
            "Tanner Odo swears the creek water below the bridge tempers leather better than any alum vat in Wayrest.",
            "Even the beggars by Kynareth's porch can recite the lineage of three dukes if there's a copper in it.",
            "When the west wind howls off the Abecean, the slate tiles on the high street rattle like castanets.",
        ],
        {
            "dragon_slayer": "By the Dragon of Daggerfall, %1$s—the heralds at Castle Daggerfall have already entered your wyrm-slaying into the royal rolls!",
            "companion_member": "A shield-sworn warrior of Jorrvaskr? The Knights of the Dragon salute your mettle on our western roads.",
            "college_member": "Our Daggerfall Mages Guild magisters are eager to hear what arcane praxis you bring from Winterhold's towers.",
            "thieves_member": "The customs clerks on Daggerfall's South Quay double-padlock their strongboxes whenever a shadow of your repute strolls past.",
            "brotherhood_member": "Folk in the crooked timber lanes draw their shutters tight when you pass; the Listener's reach is not forgotten here.",
            "civil_war_hero": "Veterans of the Betony campaigns recognize a seasoned commander—your war record precedes you through the Lion Gate.",
            "high_bounty_cleared": "The Royal Chamberlain's chancery has stamped your pardon in red wax. Walk Daggerfall's cobbles as a free citizen.",
            "local_hero": "From Tannery Row to the Castle causeway, every guild-house in Daggerfall counts you a steadfast friend.",
            "hold_story": "Since you quieted the unrest haunting the cape, the coast packets and tin-wagons arrive right on the tide.",
            "bounty_hunter": "Sir Alaric says your bounty tallies have cleared more brigands from the Ilessan hills than two companies of pikemen.",
            "master_craftsman": "The armourers of the Dragon Chapterhouse examined your work and declared it fit for a king's champion.",
            "barrow_delver": "You have walked out of the barrows and Direnni vaults of the cape with relics the scholars thought lost for an age.",
        },
    ),
    "glenumbra": (
        "Glenumbra's heather moors and chalk headlands stretch from Camlorn's black-slate spires to the fog-shrouded standing stones of the western coast.",
        [
            "Camlorn's black-slate cathedral spires pierce the moorland mist three leagues before you reach the gates.",
            "Never stray off the flagged causeways in Daenia after sundown; the hag-lights in the reed-beds mimic travellers' lanterns.",
            "Glenpoint's headland beacon burns whale-oil through three-day gales so the coasting barks don't split on the reefs.",
            "Northmoor's standing stones were raised long before the first Breton petty-king signed a charter.",
            "Crosswych quarrymen split blue-grey roof slate that covers half the manor-houses between here and Wayrest.",
            "Sheep-drovers on the Glenumbra Moors carry silver-tipped crooks, and not just for ordinary wolves.",
            "At Eagle's Brook the overshot mill-wheel turns day and night on peaty water dark as stout.",
            "Westtry's chalk barrows are sealed with iron grates, and the village reeve checks the padlocks every New Life.",
            "Bellwright Tam in Camlorn casts bronze bells tuned to drive winter fog and ill-luck from the valley.",
            "Lady Hvarine's literary salons in Camlorn set the fashion for every minor court along the western coast.",
            "Peat-cutters around Grimley Tor still dig up bronze spearheads from the old Direnni Hegemony.",
            "The Wyrd sisters in the birch-groves ask no gold, only that you never fell a living rowan tree.",
            "Archpriest Elric's choristers sing evensong in the Cathedral of Mara while rain drums on the flying buttresses.",
            "A wool-merchant from the Moors can tell which hillside a fleece came from just by the smell of the lanolin.",
            "Roadside inns across Glenumbra hang dried rowan and horse-irons over the lintel when the moons are full.",
            "Lord Vidron's dyke-wardens in Daenia spend half the spring clearing bog-willow from the drainage sluices.",
            "Pilot Edda at Glenpoint knows every sandbar and rip-tide from the headland down to Balfiera.",
            "Even the tavern signs in Camlorn are wrought in foliated ironwork by the silver-inlay guild.",
            "When the sea-haar rolls over the moors, you can hear the Northmoor chapel bell tolling through a wall of white.",
            "Keep to the high chalk ridgeway if you value dry boots and a whole throat.",
        ],
        {
            "dragon_slayer": "Even the bards of Camlorn have set aside their old romances to compose lays of the dragon-slayer, %1$s!",
            "companion_member": "A warrior of Jorrvaskr is thrice welcome on the Glenumbra moors, where wolves and worse prowl the heather.",
            "college_member": "Camlorn's scholarium magisters welcome an arcanist who isn't afraid of ancient standing-stone wards.",
            "thieves_member": "From Glenpoint's salt-warehouses to Camlorn's silver-lofts, factors check their seals twice when you're in town.",
            "brotherhood_member": "The moor-mist hides many things, traveler, and the cold quiet in your step speaks of the Sanctuary.",
            "civil_war_hero": "Word of your campaigns has reached every watch-fire from Crosswych Gorge to the western headlands.",
            "high_bounty_cleared": "The ducal bailiff in Camlorn has struck your name from the assize roll; the watch will trouble you no more.",
            "local_hero": "Whether in the quarry-camps of Crosswych or the spires of Camlorn, folk raise their mugs to your name.",
            "hold_story": "Since you broke the dark covens troubling the moors, the drovers bring their flocks down in peace.",
            "bounty_hunter": "Reeve Dunstan says the headland roads haven't been this clear of highwaymen since his grandfather's day.",
            "master_craftsman": "Camlorn's silver-wrights and slate-masons alike speak of your craftsmanship as master-grade work.",
            "barrow_delver": "You've braved the warded barrows of Northmoor and Westtry where even the witch-hunters hesitated.",
        },
    ),
    "stormhaven": (
        "Stormhaven's limestone ridges, fertile corn-lands, and bay coves bridge the great merchant road between Daggerfall and the Bjoulsae.",
        [
            "Alcaire Island claims the birthplace of General Talos, and the innkeeper will show you three different cradles for a coin.",
            "Kambria's double-walled ridge granaries hold enough winter wheat to feed five baronies through a siege.",
            "Koeglin Village shipwrights bend seasoned Stormhaven oak over steam-pits to rib the fastest schooners on the bay.",
            "Limestone quarries along the middle road supply the pale ashlar blocks for every keep in central High Rock.",
            "The Knights of the Flame at Castle Alcaire keep their beacons primed looking out over the sound.",
            "At the Weeping Giant way-station, teamsters swap axle-grease and rumours from twenty fiefs.",
            "Stormhaven's tithe-barns are built like fortresses—slit windows, buttressed stone, and oak doors four inches thick.",
            "Oyster-dredgers off Koeglin bring up old Direnni coins whenever the spring tides scour the bay-mud.",
            "Every hilltop between Alcaire and Kambria has either a knightly watchtower or a windmill turning in the bay breeze.",
            "Chamomile and lavender from Stormhaven's abbey gardens are baled and shipped to perfumers in Wayrest.",
            "When two Stormhaven knights meet at a crossroads, they spend ten minutes establishing whose patent is older.",
            "The coast road past Koeglin floods at the equinoctial springs, sending the stage-coaches up the ridge track.",
            "Coopers in Alcaire char the inside of their oak casks so the island vintage keeps its amber colour.",
            "Mercenary companies muster at the stone bridges every spring hoping for a retainer from a feuding baron.",
            "Even the parish chapels here have crenellated bell-towers in case raiders slip up the river creeks.",
            "Grain-factors at Kambria weigh every sack on bronze steelyards stamped with the seal of Zenithar.",
            "You can smell the pitch-kettles of Koeglin's careening beach half a league down the coast road.",
            "Old legion mile-pillars still stand along the high ridge, worn smooth by centuries of iron-rimmed wains.",
            "A Stormhaven franklin would rather mortgage his plough-oxen than miss the autumn tournament at Alcaire.",
            "Blue-slate roofs and pale limestone walls make every town on the ridge gleam after a squall.",
        ],
        {
            "dragon_slayer": "On the island where Talos himself first drew breath, slaying a dragon makes you a living legend, %1$s!",
            "companion_member": "The Knights of the Flame at Alcaire honour a shield-brother of Jorrvaskr's ancient line.",
            "college_member": "Stormhaven's court enchanters are always glad to compare ward-craft with a scholar of the northern College.",
            "thieves_member": "The customs clerks at Koeglin and Kambria keep a sharp eye on the tithe-chests when you pass through.",
            "brotherhood_member": "Even along the sunny limestone roads of Stormhaven, folk step aside when they catch that cold shadow in your eyes.",
            "civil_war_hero": "In the birthplace of the Empire's greatest general, a proven commander of the field commands instant respect.",
            "high_bounty_cleared": "The castellan at Kambria recorded your composition fee; the Stormhaven road-wardens have stood down.",
            "local_hero": "From Koeglin's shipyards to the hill-keep of Alcaire, every hearth in Stormhaven welcomes you.",
            "hold_story": "Now that the ridgeway and bay-coves are free of saboteurs, the grain-fleets sail on schedule again.",
            "bounty_hunter": "Wayrest-bound caravans cross the Stormhaven hills without losing a single pack-mule thanks to your hunts.",
            "master_craftsman": "Koeglin's master shipwrights and Alcaire's blade-smiths alike praise the balance of your handiwork.",
            "barrow_delver": "The old karst vaults beneath the Stormhaven ridges have yielded their long-buried treasures to your torch.",
        },
    ),
    "anticlere": (
        "Anticlere and its sister fiefs of Shalgora, Urvaius, and Ykalon form the warm limestone heart of the Iliac bayshore—vineyards, chalk downs, and ancient charter-towns.",
        [
            "Anticlere's terraced vineyards catch the southern sun off the Iliac Bay, yielding the sweetest vintage in High Rock.",
            "Urvaius chalk-downs pasture ten thousand sheep whose fine wool keeps the fulling-mills clacking all summer.",
            "Shalgora sits in a sheltered karst hollow where heraldic tapestry-weavers work in stone-vaulted halls.",
            "Ykalon's royal foresters guard the tall pine-ridges that supply mast-timber to the bay fleets.",
            "Diplomats from Daggerfall and Wayrest meet at Anticlere's neutral pavilions whenever they need to sign a truce.",
            "The Rotunda of Dibella in Anticlere is paved with warm ochre marble brought across the bay.",
            "Shepherds on the Urvaius ridges build dry-stone folds that have stood since the first Breton charters.",
            "At the Cuckoo and Crown in Shalgora, heralds argue over quarterings of arms until the candles gutter out.",
            "Ykalon's charcoal-burners keep their turf-kilns smoking in the high clearings from Frostfall to Sun's Height.",
            "Orange-blossom and rosemary stills in Anticlere fill corked phials for every noble dressing-table in the province.",
            "A charter-burgess of Ykalon answers only to the town bailiff and the High King's circuit judge.",
            "Chalk-streams below Urvaius run clear over flint gravel, powering six fulling-stocks in a single mile.",
            "Castle Anticlere's bay-bastion flies a gold-and-azure pennon that can be seen from the Balfiera channel.",
            "Tapestry-looms in Shalgora take two winters to weave a single battle-piece for a ducal hall.",
            "Foresters in Ykalon notch their yew-staves in the autumn moon and season them three years in the rafters.",
            "Every spring fair at Urvaius opens with the blessing of the shears at Mara's downland chapel.",
            "Wine-casks rolled down Anticlere's stone quay carry the burnt-brand of the Honourable Vintners.",
            "Even the boundary ditches here are hedged with old hawthorn thick enough to turn a boar-spear.",
            "Travellers between Shalgora and Ykalon rest at stone well-heads carved with Julianos's triangle.",
            "Warm bay afternoons and petty heraldic feuds—that is Anticlere in a nutshell.",
        ],
        {
            "dragon_slayer": "The heralds of Anticlere have blazoned a dragon-slayer's crest in your honour across the treaty halls, %1$s!",
            "companion_member": "Even among Anticlere's courtly knights, a warrior of Jorrvaskr's renown is toasted with the best vintage.",
            "college_member": "The scriptorium masters of Shalgora and Ykalon are delighted to host a scholar from Winterhold.",
            "thieves_member": "Anticlere's wine-factors and Urvaius wool-staplers keep their ledger-rooms locked tight when you stroll the arcades.",
            "brotherhood_member": "In a region of diplomatic poison and velvet daggers, folk still pale when a true Speaker's blade walks by.",
            "civil_war_hero": "Envoys at Castle Anticlere study your battle tactics as closely as any First Era chronicle.",
            "high_bounty_cleared": "Your composition has been entered into the charter-rolls of Anticlere; the bailiff's men salute you peacefully.",
            "local_hero": "From the pine-woods of Ykalon to Anticlere's sunny quays, every burgess and vintner calls you friend.",
            "hold_story": "Since you settled the feud threatening the bay charters, the vintage barges and wool-wains move unmolested.",
            "bounty_hunter": "The ridgeway between Urvaius and Shalgora is safe for lone peddlers again thanks to your bounty work.",
            "master_craftsman": "Anticlere's gilt-smiths and Shalgora's master weavers marvel at the finish on your gear.",
            "barrow_delver": "You have delved the karst-tombs and pine-barrows of the middle fiefs and returned with their oldest secrets.",
        },
    ),
    "dwynnen": (
        "Dwynnen's salt-weathered granite peninsula juts into the Iliac narrows, where convoy captains and sea-bastions guard the heart of the bay.",
        [
            "Castle Dwynnen's granite sea-wall takes the full weight of the channel chop whenever the tide turns against the wind.",
            "The Convoy Captains' Hall in Dwynnen sets the sailing dates for every merchant fleet heading out of the inner bay.",
            "Rope-walks along Dwynnen's harbour stretch hemp cables a hundred fathoms long smell of Stockholm tar.",
            "Salt-spray crusts the leaded casements of the lower town by noon on any breezy day.",
            "At the Storm-Petrel Inn, retired quartermasters chart every reef and corsair cove from Stros M'Kai to Balfiera.",
            "Dwynnen's iron-keel smithy forges grapnels, swivel-mounts, and cutlasses tough enough to turn a boarding axe.",
            "Sailors' wives hang brass ship-lanterns in the Fane of Kynareth whenever the equinoctial gales blow.",
            "The headland battery above Dwynnen harbour mounts torsion engines that can reach halfway across the roadstead.",
            "Ship-biscuit baked in Dwynnen's stone ovens is hard enough to crack a walnut, but it never moulds at sea.",
            "Careening stages on the shingle beach heel whole brigantines over at low water to scrape the barnacles.",
            "Baronial customs cutters row out in all weathers to inspect the manifests of passing coasters.",
            "Everylintel in the old port is carved from grey peninsula granite that dulls a mason's chisel in an hour.",
            "Scurvy-grass and sea-wormwood tinctures from Dwynnen's apothecary stock every medicine chest in the fleet.",
            "When the fog-bell on the mole rings three strokes, a convoy is warping in through the narrows.",
            "Old sea-dogs on the jetty still argue whether Direnni galleys or Redguard dhows hold the wind closer.",
            "No captain weighs anchor from Dwynnen without pouring a measure of cider over the stem-post for Kynareth.",
            "Driftwood and wrecked spars wash into the lee coves after every winter nor'wester.",
            "The Baron of Dwynnen wears a silver whistle on his collar—the mark of Lord High Admiral of the Narrows.",
            "Herring-busses and naval pinnaces crowd the inner basin three deep when a storm warning flies.",
            "Stand on Dwynnen Point on a clear noon and you can see the white sails of twenty fiefs.",
        ],
        {
            "dragon_slayer": "The convoy captains of Dwynnen dipped their ensigns when word came that you cast a dragon from the sky, %1$s!",
            "companion_member": "A warrior of Jorrvaskr has the sea-legs and iron stomach our Dwynnen marine captains prize.",
            "college_member": "Weather-callers and navigators at Dwynnen's mole gladly welcome a mage of the northern College.",
            "thieves_member": "The bonded cordage-sheds and customs cutters in Dwynnen harbour are on double watch with you ashore.",
            "brotherhood_member": "Even seasoned boarding-crews at the Storm-Petrel lower their voices when you step across the threshold.",
            "civil_war_hero": "Dwynnen's sea-barons know what it takes to hold a line; your war deeds are toasted on every quarterdeck.",
            "high_bounty_cleared": "The Admiralty clerk in Dwynnen has cleared your name from the port black-book. Fair winds to you.",
            "local_hero": "Every shipwright, rope-maker, and captain on the Dwynnen peninsula counts you a friend of the port.",
            "hold_story": "Since you broke the wreckers and raiders preying on the narrows, the bay convoys make port without a scratch.",
            "bounty_hunter": "The coastal coves around Dwynnen are free of cutthroats at last thanks to your relentless pursuit.",
            "master_craftsman": "The master smiths of the Iron-Keel forge swear your steel could shear an anchor chain in two.",
            "barrow_delver": "You've sounded the drowned sea-caves and headland cairns of Dwynnen and brought back their lost bullion.",
        },
    ),
    "wayrest": (
        "Wayrest crowns the head of the Iliac Bay at the mouth of the Bjoulsae—a canal-laced metropolis of merchant syndicates, royal intrigue, and chained harbours.",
        [
            "Wayrest's great iron harbour chain winches up between the twin towers whenever an untaxed hull approaches the mouth.",
            "House Gardner, House Cumberland, and House Horley buy and sell baronies in the arcaded Bourse before breakfast.",
            "The Bjoulsae barges bring Wrothgar iron, Evermor wool, and Bangkorai copper straight to the Chain Quay warehouses.",
            "Step off the marble bridges of the Palace ring into the lower canals and keep one hand on your purse-strings.",
            "King Eadwyre's court is a chessboard where Queen Barenziah, Elysana, and Helseth each move three pieces at once.",
            "At the Silver Lantern on the quay, factors settle bills of exchange drawn on banks from Sentinel to the Imperial City.",
            "Dreughside's lagoon fishers boil dreugh-wax and purple shell-dye that smells across half the estuary.",
            "Wayrest's catacombs run beneath the oldest guild-halls, and not all the iron gates down there stay locked.",
            "The Chapel-Bourse of Zenithar in Wayrest weighs gold sovereigns on balances accurate to a single grain of barley.",
            "Canal-punts poles past mossy stone foundations while velvet-cloaked syndics watch from the upper loggias.",
            "Quartermaster Jules at the Admiralty dock can spot a forged bill of lading at twenty paces.",
            "Lady Morgiah's salon hears every whisper in the Western Reach before the royal couriers even change horses.",
            "Wait by the Harbour Chain Tower at flood tide and watch fifty river-barges lock through into the bay basin.",
            "The Cloudy Dregs Inn down by the sluice-gates asks no names and serves spiced wine that could strip varnish.",
            "Pontanian corsair-factors walk the Wayrest quays in broad daylight wearing letters of marque signed by the Council.",
            "Every guild-house on the Grand Canal maintains its own liveried watermen and iron-grated counting-room.",
            "Sister Odelle at the Chapel of the Divines tends the alms-box and hears the confessions of half the Chancery.",
            "Fog off the Bjoulsae fen mixes with coal-smoke from the assay furnaces to turn Wayrest evenings silver-grey.",
            "In Daggerfall they ask who your grandfather was; in Wayrest they ask what your cargo is worth.",
            "Even the stone gargoyles on Castle Wayrest look as though they are calculating interest.",
        ],
        {
            "dragon_slayer": "Even the jaded merchant-princes of Wayrest left their counting-tables when word arrived that you slew a dragon, %1$s!",
            "companion_member": "Honest steel from Jorrvaskr is a rare commodity in Wayrest's halls of intrigue—you are welcome here.",
            "college_member": "Archmagister Vane and the Wayrest Mages Guild are eager to discuss high theory with a fellow College scholar.",
            "thieves_member": "Along Wayrest's canals and the Cloudy Dregs, folk nod with professional respect to a master of the Guild.",
            "brotherhood_member": "In a city where court succession is settled in dark corridors, your presence sends a chill through Castle Wayrest.",
            "civil_war_hero": "The syndics and marshals of Wayrest respect a commander who knows how to win a campaign.",
            "high_bounty_cleared": "Your restitution has cleared the Chancery books, and the Harbour Watch has struck your description from the gates.",
            "local_hero": "From the Chain Quay to the Palace bridges, Wayrest's guilds and watermen tip their hats to you.",
            "hold_story": "Since you untangled the syndicate plot in the canals, the Bjoulsae trade-fleets move without extortion.",
            "bounty_hunter": "Estuary pirates and highwaymen around Dreughside have scattered since you started collecting the Council's bounties.",
            "master_craftsman": "The master goldsmiths of the Bjoulsae Bullion Exchange say your craft rivals the finest royal commissions.",
            "barrow_delver": "You have walked the deepest flooded vaults beneath Wayrest's catacombs and returned with the seal intact.",
        },
    ),
    "rivenspire": (
        "Rivenspire's jagged basalt crags and wind-scoured moors rise from the red-slate ramparts of Shornhelm to the storm-battered cliffs of Northpoint.",
        [
            "Shornhelm's Red Keep commands the only wagon-pass splitting the Rivenspire crags between north and south.",
            "Northpoint's lighthouse fog-horn booms across the cold northern sea whenever the pack-mist rolls in.",
            "Every manor-house in Rivenspire bolts iron grilles over its cellar vents and hangs garlic braid by the eaves.",
            "At Crestshade, the ravine road runs under basalt overhangs so steep the sun only hits the cobbles at noon.",
            "Hoarfrost Downs shepherds fold their flocks inside eight-foot stone walls before the first star appears.",
            "Fell's Run barley-fields are the one green ribbon in forty leagues of grey rock and crimson heather.",
            "Marshal Ysolde's pikemen drill on the Shornhelm muster-ground under banners the colour of dried blood.",
            "Alder the Fletcher in Shornhelm binds ash-wood crossbow prods with horn and sinew that can punch through plate.",
            "Keeper Sorley at Northpoint tends a beacon-fire fed with whale-oil and pitch from the northern fisheries.",
            "The Doomcrag's broken Ayleid spire still looms on the western horizon like a splintered black tooth.",
            "Peat-Cutter Gill swears the bogs behind Northpoint preserve oak logs that fell in the Merethic Era.",
            "House Dorell, House Tamrith, and House Montclair watch each other across the Shornhelm council table like hawks.",
            "The half-roofed Moor Chapel at Northpoint lets the sea-gale blow straight across the altar stone.",
            "Travel the ravine road after dusk without a silver-edged blade and you are asking for a short journey.",
            "Herring-smokers on Northpoint's cliff-stages cure their catch over smouldering peat and juniper brush.",
            "Even the wayside shrines of Arkay in Rivenspire are built like blockhouses with iron-studded doors.",
            "In Fell's Run they ring the croft-bell at twilight so herdsmen in the mist can find the gate.",
            "Black basalt ashlar and dark crimson slate give every town in the crags a stern, unbroken profile.",
            "Sergeant Brom at the Red Keep checks every traveller's pass before the portcullis rises an inch.",
            "The wind off the northern ocean tastes of salt, sleet, and old stone.",
        ],
        {
            "dragon_slayer": "A dragon brought down among the crags of Rivenspire! The Red Keep's trumpets sounded in your honour, %1$s!",
            "companion_member": "In a grim land of crags and night-terrors, a shield-sibling of Jorrvaskr is the finest ally Shornhelm could ask for.",
            "college_member": "The Order of the Lamp in Shornhelm welcomes an arcanist whose wards hold firm against the dark.",
            "thieves_member": "Northpoint's whale-oil factors and Shornhelm's quartermasters watch their keys closely when you walk the tiers.",
            "brotherhood_member": "Folk in Rivenspire know the difference between a crag-fiend and a silent assassin—and they fear your step more.",
            "civil_war_hero": "Marshal Ysolde and the garrison of the Red Keep salute a veteran who has stood in the shield-wall.",
            "high_bounty_cleared": "Your fine is entered in the Shornhelm peerage rolls; the crag-watch will let you pass unhindered.",
            "local_hero": "From Fell's Run's pastures to Northpoint's wind-swept quay, Rivenspire calls you its staunchest defender.",
            "hold_story": "Since you purged the blood-fiends haunting the ravines, Crestshade and the high passes sleep in peace.",
            "bounty_hunter": "Outlaws and blood-sworn raiders in the basalt crags have been driven to ground by your relentless hunting.",
            "master_craftsman": "Alder the Fletcher and the smiths of the Red Anvil say your craft is the finest in the northern marches.",
            "barrow_delver": "You have braved the sealed crypts and Ayleid undercrofts of Rivenspire where few ever return whole.",
        },
    ),
    "bjoulsae": (
        "The Bjoulsae River valley winds from the island cathedral of Evermor and the scholar-bluffs of Bhoriane to the northern sea-keep of Farrun.",
        [
            "Evermor sits on its river-lake island linked to the banks by a stone causeway arch where every barge pays toll.",
            "Bhoriane's river-bluff scriptoriums copy charters and star-tables for every court between Wayrest and Elinhir.",
            "Menevia's great abbey bell-spire rings the canonical hours across three miles of walled orchards and flax-fields.",
            "Phrygias guards the high granite col where the wool-trains climb out of the river basin toward Wrothgar.",
            "Up on the northern coast, Farrun's sea-wall keep blends Breton stone bastions with heavy Nordic timber longhalls.",
            "Horse-tribesmen and Breton drovers water their herds on opposite gravel bars along the middle Bjoulsae.",
            "Ferrywoman Iselle poles passengers across Evermor's misty reach before the causeway gates open at dawn.",
            "Bishop Mallory at Evermor Cathedral guards the illuminated river-compacts signed in the Second Era.",
            "Alluvial gold panned from the Bjoulsae tributaries is weighed and stamped at Evermor's assay house.",
            "In Farrun you will hear Nordic skalds and Breton troubadours arguing over the same mead-bench.",
            "Lamplighter Vey walks Evermor's causeway arch every evening lighting oil-cressets against the river fog.",
            "Bhoriane's ink-makers boil oak-galls and copperas from the Bangkorai foothills to make indelible chancery ink.",
            "Pilgrims bound for Menevia Abbey carry carved ash-staves blessed at the river-fords.",
            "At the Windy Saddle Inn in Phrygias, muleteers wrap their boots in sheepskin before tackling the northern pass.",
            "Towpath teams of four Shire horses haul flat-bottomed timber-barges upstream against the Bjoulsae current.",
            "Bangkorai Pass lies just south of Evermor's walls, so the ducal watch always sleeps with crossbows spanned.",
            "Farrun's ice-axe smiths temper their steel in brine so it won't snap in a Sea of Ghosts gale.",
            "Flax-bleacheries in the Menevia valley spread white linen across the riverside meadows every Sun's Height.",
            "The Bjoulsae is High Rock's silver highway—choke the river and twenty towns go hungry.",
            "Mud-brick, river cobble, and seasoned spruce give the valley towns a warmth you won't find on the crags.",
        ],
        {
            "dragon_slayer": "From Farrun's ice-wall down to Evermor's island cathedral, the Bjoulsae valley rings with the tale of your dragon-slaying, %1$s!",
            "companion_member": "In Farrun and along the Bjoulsae, folk know the honour of Jorrvaskr—your shield is welcome at every hearth.",
            "college_member": "The scriptorium masters of Bhoriane are eager to exchange arcane treatises with a mage of Winterhold.",
            "thieves_member": "The toll-clerks on Evermor's causeway arch keep both hands on the strongbox when you stroll across the span.",
            "brotherhood_member": "Even the hard-bitten river-barge crews fall silent when your shadow crosses the wharf.",
            "civil_war_hero": "At the gateway to Bangkorai Pass, Evermor's garrison salutes a warrior tested in real battle.",
            "high_bounty_cleared": "Duke Guimard's chancery has sealed your quittance; the river-wardens from Menevia to Farrun have stood down.",
            "local_hero": "Every barge-master, abbey-monk, and towpath smith along the Bjoulsae hails you as a true friend of the valley.",
            "hold_story": "Since you secured the river-fords and pass approaches, the Bjoulsae barges run without fear of ambush.",
            "bounty_hunter": "The highwaymen who used to lurk in the Phrygias col have vanished since you took up the bounty rolls.",
            "master_craftsman": "Evermor's gold-assayers and Farrun's iron-wrights alike praise the mastery of your craft.",
            "barrow_delver": "You have delved the river-bluff vaults and frost-barrows of the north and walked out with their ancient hoard.",
        },
    ),
    "wrothgar": (
        "Wrothgar's iron-rich peaks and alpine valleys house the stepped basalt bastions of Orsinium, the clan strongholds of the Orcs, and the fjord-town of Jehanna.",
        [
            "Orsinium's Great Gate-Hammer hangs over basalt ramparts raised by King Gortwog and the iron-clans of Wrothgar.",
            "The Great Forge pit at the heart of Orsinium never goes cold; its coal-smoke stains the snow on the peaks above.",
            "Clan Morkul's blast-smelters tap deep orichalcum and iron veins straight out of the mountain wall.",
            "Clan Tumnosh quarrymen dress six-ton basalt blocks and haul them on echatere sledges down the pass.",
            "Clan Fharun's cliff-longhouse watches the frozen northern sea where ice-floes grind against the rocks.",
            "In Jehanna's fjord-harbour, Breton factors and Nordic trappers trade mammoth-fur, seal-oil, and Skyrim amber.",
            "Gavaudon's crag-bastion sits on the western march where Breton crossbowmen and Orc outriders eye each other across the gorge.",
            "King Gortwog and Chief Gulmak argue day and night whether Trinimac's priesthood or Malacath's code rules the citadel.",
            "Echatere herds with shaggy coats and four curved horns carry every load that would break a lowland horse.",
            "Smith-Mother Ushra tests every breastplate forged in Orsinium with a two-handed war-maul before it leaves the anvil.",
            "Skald Torog chants the grudge-rolls in the Trophy Hall while roast echatere ribs spit over the fire-trench.",
            "Never enter an Orc stronghold uninvited unless you carry blood-kin token or a master smith's tribute.",
            "At the Bone and Ember in Orsinium, highland spiced ale is served in horn-mugs banded with blackened iron.",
            "Jehanna's wind-fane honours both Kynareth and Kyne, so neither the Breton pilots nor the Nord whalers take offence.",
            "Avalanche-sheds of heavy spruce timber roof the switchback trails between Phrygias and the Orsinium gate.",
            "In the Hall of the Grudge-Urns, the ashes of fallen warriors are sealed in iron vessels forged from their own shields.",
            "Frost-bears and crag-wolves prowl the pine-line whenever the early blizzards drive the mountain goats down.",
            "An Orsinium mason fits cyclopean stone without mortar so the frost-heave cannot crack the bastion wall.",
            "Even the border-tolls at Gavaudon are paid in weighed iron bars as often as stamped gold coin.",
            "Stand on the Trophy Gate at dawn and watch the forge-sparks rise against the glaciers of the Druadach.",
        ],
        {
            "dragon_slayer": "By Mauloch's iron hand and Trinimac's blade, %1$s—even the war-chiefs of Orsinium roar approval at your dragon-slaying!",
            "companion_member": "A warrior of Jorrvaskr carries honour that both Jehanna's thanes and Orsinium's clan-chiefs respect.",
            "college_member": "Frost-Seer Ylva in Jehanna and thehearth-wives of Wrothgar respect a mage who masters the mountain elements.",
            "thieves_member": "Careful with light fingers in Wrothgar—Stronghold law answers a picked lock with a war-axe, not a fine.",
            "brotherhood_member": "There is old blood on your steel, traveler; the guards at the Trophy Gate watch your hands closely.",
            "civil_war_hero": "Warriors in Wrothgar judge a stranger by their scars and victories, and your record stands tall.",
            "high_bounty_cleared": "Your blood-price and fine are settled in full; the watch from Gavaudon to Orsinium lets you pass.",
            "local_hero": "To be named a friend by both the clans of Orsinium and the folk of Jehanna is a feat few outlanders achieve.",
            "hold_story": "Since you ended the feud threatening the mountain truces, the iron-sledges and fur-caravans roll again.",
            "bounty_hunter": "Chief Gulmak nodded when he saw the tally of winterborn raiders and crag-beasts you cleared from the passes.",
            "master_craftsman": "Smith-Mother Ushra herself ran a thumb along your steel and grunted—high praise from the Great Forge of Orsinium!",
            "barrow_delver": "You have delved the ice-cairns and siege-ruins of old Orsinium and walked out carrying the mountain's history.",
        },
    ),
}

HAMMERFELL_REGION_LORE = {
    "sentinel": (
        "Sentinel's golden sandstone domes and cobalt-tiled arcades command the southern shore of the Iliac Bay, where Forebear fleets and royal courts meet the desert edge.",
        [
            "The Palace of the Crown in Sentinel lifts its gilded dome high above the turquoise roofs and palm-courtyards of the harbour.",
            "Under the coloured canvas of the Bazaar of Winds, factors trade Abecean pearls, High Rock wool, and Alik'r myrrh.",
            "King Lhotun walks a tightrope between the Forebear shipping syndicates and the Crown elders of the interior.",
            "The Order of the Candle keeps perpetual watch over the royal succession from their sandstone chapterhouse.",
            "At Cybiades, Ash'abah wardens and quarantine guards watch the cursed peninsula ruins across the narrow sound.",
            "Antiphyllos presses green olive-oil in stone mills that have turned since the first Redguard kings.",
            "Myrkwasa's sea-bluff date-groves are watered by deep rock-cut cisterns that never dry in the summer heat.",
            "Abibon-Gora's pearl-divers plunge six fathoms into the southern bight with bronze weights at their belts.",
            "Vizier Samir al-Hadid signs the grain-charters that keep Sentinel's granaries full when the simoom blows.",
            "Ansei Scimitar Works tempers watered steel in oil and sesameseed until the blade shows the wave-pattern of the bay.",
            "Captain Layla Ashraf's harbour galleys patrol the approaches from Betony to Stros M'Kai.",
            "In Sentinel's House of the Seven Cisterns, brass water-clocks measure the hours by the drip of filtered rain.",
            "Zareen the Dye-Seller can tell genuine Gilane murex-purple from cheap alkanet root at a single glance.",
            "Every courtyard in the upper ward has a tiled fountain-basin and a shaded arcade to catch the sea-breeze.",
            "Pilgrims light pierced-brass lamps at the Domed Sanctuary of Tu'whacca before embarking across the bay.",
            "When the warm land-wind blows off the Alik'r at night, the whole harbour smells of dry sand and wild sage.",
            "Goldsmiths in the Sentinel suq spin gold wire fine enough to embroider a vizier's sash.",
            "The harbour chain-battery at the mole salutes royal dhows with three strokes of the bronze gong.",
            "Remember Prince A'tor's name with respect if you speak to an old soldier along the sea-wall.",
            "Blue glazed tile, carved sandstone mashrabiya screens, and white lateen sails—that is Sentinel from the water.",
        ],
        {
            "dragon_slayer": "By the blade of Frandar Hunding, %1$s—the astrologers of Sentinel saw the fall of that dragon written in the stars!",
            "companion_member": "The Knights of the Candle and the sword-schools of Sentinel honour a warrior of Jorrvaskr's brotherhood.",
            "college_member": "Grand Astrologer Azra welcomes a scholar of the northern College to Sentinel's star-lit observatories.",
            "thieves_member": "The customs syndics along the Bazaar of Winds double-seal their amphorae whenever you stroll the quays.",
            "brotherhood_member": "Even in the bright sun of Sentinel's courtyards, the palace guards stiffen when your shadow falls across the tiles.",
            "civil_war_hero": "Veterans of the Iliac naval wars recognize a master of campaigns; Sentinel's captains salute you.",
            "high_bounty_cleared": "Vizier Samir's diwan has stamped your quittance in the royal ledger. Walk Sentinel's arcades in peace.",
            "local_hero": "From the pearl-stages of Abibon-Gora to the Palace of the Crown, the southern coast calls you a true friend.",
            "hold_story": "Since you broke the corsair blockade and quieted the peninsula tombs, Sentinel's dhows sail on every tide.",
            "bounty_hunter": "Captain Layla Ashraf says the coast road to Myrkwasa hasn't been this clear of dune-raiders in ten seasons.",
            "master_craftsman": "The master bladesmiths of Scimitar Works examined your steel and praised its watered temper.",
            "barrow_delver": "You have walked the warded Na-Totambu vaults and Cybiades hypogea without waking the restless dead.",
        },
    ),
    "alikr_desert": (
        "The Alik'r Desert spans the vast sun-scoured heart of Hammerfell—an inland sea of golden dunes, qanat-fed oasis kasbahs, Crown citadels, and buried Yokudan tombs.",
        [
            "Never cross the deep Alik'r between Kozanset and Bergama without three days' water and a guide who knows the stars.",
            "Hegathe's cliff-citadel marks the first landfall of the Ra Gada, and its Crown jurists still recite the laws in old Yoku.",
            "Skaven's seven deep cisterns and vaulted caravanserais anchor the northern caravan road toward Bangkorai.",
            "Gilane's dye-vats boil murex shells from Covetous Bay into the royal purple that graces every court in Tamriel.",
            "Totambu's twin-domed oasis preserves the oldest Crown pedigrees and lotus-gardens in the desert.",
            "At Satakalaam, the copper-miners carve their houses and ancestor-niches straight into the red serpent-canyon walls.",
            "Bergama's date-palm ring is fed by underground qanat channels dug ten fathoms beneath the shifting sand.",
            "Kairou's master weavers knot prayer-rugs and saddle-cloths dyed with madder-root, saffron, and indigo.",
            "High on the scrub-mesa of Ephesus, bronze astrolabes track the path of Tall Papa's stars across the clear desert night.",
            "Only the exiled Ash'abah dare enter a sand-sealed necropolis when the ancestor-wards fail and the Ra'Netu rise.",
            "At Morwha's Bounty, travellers wash the alkali dust from their faces under four sandstone arches shaded by fig-trees.",
            "Tavalaam's dhow-wharves load salt-blocks cut from the dry pans of the interior for the southern coast trade.",
            "When the red simoom rises on the horizon, even the proudest caravan-master shelters behind his camels and wraps his face.",
            "Water-theft at an Alik'r well is judged under the open sky and punished before the sun goes down.",
            "A desert courser from Totambu can gallop twelve leagues over hard-packed gravel on a single measure of barley and dates.",
            "In Kozanset's suq, every grain-merchant swears by Zenithar's scales while keeping a thumb near the balance-beam.",
            "Glass-winged dune beetles and sand-vipers hide under the salt-crust until the cool of twilight brings them out.",
            "Every tomb-door in the Alik'r bears the warning sigil of Tu'whacca: leave the offerings of the honoured dead untouched.",
            "Wind-catchers on the adobe roofs of Skaven funnel the evening breeze down into the cistern-rooms below.",
            "The desert remembers every footprint until the evening wind wipes the slate clean.",
        ],
        {
            "dragon_slayer": "By HoonDing's make-way blade, %1$s—the caravans across the Alik'r sing of how you struck a dragon into the dunes!",
            "companion_member": "The Dune-Lancers of Skaven and the sword-masters of Hegathe salute a warrior of Jorrvaskr's renown.",
            "college_member": "The star-readers of Ephesus and Totambu welcome a scholar who understands the high harmonies of the sky.",
            "thieves_member": "The caravan-khans of Kozanset and the pearl-factors of Gilane watch their saddle-chests when you pass.",
            "brotherhood_member": "Your step is quieter than wind-blown sand, traveler; folk in the oasis serais give you a wide berth.",
            "civil_war_hero": "From Hegathe's sea-walls to Skaven's basin fort, Redguard veterans recognize a commander of proven grit.",
            "high_bounty_cleared": "The Qadi's diwan has sealed your pardon; every oasis gate from Bergama to Satakalaam is open to you.",
            "local_hero": "You have earned water-right and guest-shade at every caravanserai across the Great Alik'r.",
            "hold_story": "Since you aided the Ash'abah and quieted the restless tombs, the trans-desert caravans travel without dread.",
            "bounty_hunter": "Dune-raiders and tomb-robbers between Totambu and Kairou scatter at the very mention of your name.",
            "master_craftsman": "The watered-steel smiths of Hegathe and the weavers of Kairou call your handiwork worthy of the old masters.",
            "barrow_delver": "Few outlanders descend into the Yokudan sand-tombs and return to see the sun—your courage is undeniable.",
        },
    ),
    "bangkorai": (
        "Bangkorai guards the great mountain defile and coastal cliffs where High Rock's green valleys give way to the arid scrub and kasbahs of Hammerfell.",
        [
            "Hallin's Stand sits at the southern mouth of Bangkorai Pass, where the Five Companions once held the line against the goblin hordes.",
            "Lainlyn's whitewashed terraces cling to a sheer sea-inlet where corsair galleys and timber-barks ride at anchor.",
            "Mournoth's wooded basin shelters cedar groves and hunting-lodges before the land drops into the red desert.",
            "The Great Pilgrim Domes of Ayasofya draw faithful from both sides of the pass to light votive lamps under the mosaic vaults.",
            "If the Bangkorai Garrison gates close, every caravan from Evermor to Skaven has to wait in the pass.",
            "At the Five Companions Inn in Hallin's Stand, sword-initiates still recite verses from the Book of Circles.",
            "Composite horn-bows crafted in Mournoth are backed with sinew and lacquered against both rain and desert heat.",
            "Baron-House Lainlyn flies the falcon banner from its cliff-minaret and taxes every keel entering the inlet.",
            "In Ayasofya's scriptorium-hall, calligraphers gild prayer-scrolls with gold leaf beaten in Totambu.",
            "Stone-walled terraces around Mournoth transition from Breton-style oak beams to flat Redguard mud-brick roofs.",
            "Outriders from Hallin's Stand patrol the dry washes where wasteland raiders try to outflank the garrison wall.",
            "Cedar-resin and spikenard from Bangkorai's foothills supply the temple censers of half northern Hammerfell.",
            "The Crypt of the Sword-Saints beneath Hallin's Stand is swept clean every dawn by novice bladesmen.",
            "Look north from the pass crest and you see green river-mist; look south and the sky burns bronze over the scrub.",
            "Lainlyn's boom-tower can drop an iron chain across the cove mouth in less than three minutes.",
            "Caravan-drovers swap heavy High Rock wains for pack-camels at the Hallin's Stand commissary yard.",
            "Waterfalls from the Ephesus ridge vanish into dry limestone sinkholes before they ever reach the desert floor.",
            "Even the garrison guards here wear light desert mail over Breton-weave gambesons to survive the noon glare.",
            "At the Speared Lion in Mournoth, hunters trade crag-cat pelts and stories of the old goblin wars.",
            "Bangkorai is the hinge of the west—whoever holds the pass holds the peace between two provinces.",
        ],
        {
            "dragon_slayer": "Hallin himself would have raised his blade to you, %1$s—slaying a dragon above Bangkorai Pass is a deed for the Book of Circles!",
            "companion_member": "In Hallin's Stand, where the Five Companions are revered, a shield-sibling of Jorrvaskr walks among kindred spirits.",
            "college_member": "The scroll-keepers of Ayasofya and the garrison mages welcome a scholar of the northern College.",
            "thieves_member": "Lainlyn's inlet factors and Hallin's Stand sutlers check their strong-rooms twice when you are in town.",
            "brotherhood_member": "The pass winds are sharp, traveler, but the silence that follows your blade is sharper still.",
            "civil_war_hero": "Every soldier at the Bangkorai Garrison knows what it costs to hold a border defile—we salute your valour.",
            "high_bounty_cleared": "The Citadel magistrate at Hallin's Stand has cleared your slate; pass freely between north and south.",
            "local_hero": "From Lainlyn's sea-cliffs to the domes of Ayasofya, the folk of Bangkorai count you a proven champion.",
            "hold_story": "Since you broke the raiders threatening the pass, caravans move safely from the Bjoulsae to the Alik'r.",
            "bounty_hunter": "The wasteland outlaws in the foothills around Mournoth have been routed by your tireless bounty hunting.",
            "master_craftsman": "The anvil-masters of Hallin's Stand say your weapons have the balance and bite of true Ansei steel.",
            "barrow_delver": "You have walked the deepest rock-crypts and pass-ruins of Bangkorai and emerged victorious.",
        },
    ),
    "dragontail": (
        "The Dragontail Mountains and the Brena borderlands rise in red-sandstone canyons, copper-smelting gorges, and the minaret-crowned bazaar city of Rihad.",
        [
            "Rihad's Great Minaret of the Hour calls the watch across the Brena River toward the Colovian hills of Cyrodiil.",
            "Through the Spice Gate of Rihad pass half the overland caravans trading between Hammerfell and the Gold Coast.",
            "Pothago's red-rock gorge glows at night with the wind-draft furnaces smelting Dragontail copper and iron.",
            "Tigonus straddles a high stone arch over a canyon so deep you can hear the river roaring before you see it.",
            "Dak'fron's badlands watch-keep looks out over alkali flats where mirages shimmer like inland lakes.",
            "At the Courtyard Caravanserai in Rihad, Colovian wine-merchants and Forebear spice-factors haggle over tea and dates.",
            "Spice-Master Rasul's scales at the Rihad customs-house weigh saffron, cinnamon, and hammered brass ingots.",
            "In Pothago they worship Onsi the Boneshaver at a rock-cut forge-shrine where the anvil never cools.",
            "Tigonus goat-herders climb switchback ledges that would make a mountain eagle dizzy.",
            "Red-ochre sandstone and sun-baked mud-brick make the canyon towns blend straight into the cliff walls.",
            "Imam Tahir's water-clock in Rihad chimes every watch with bronze balls dropping into a copper basin.",
            "Outriders from Dak'fron carry two waterskins for their mounts and one for themselves when patrolling the badlands.",
            "Cordovan leather tanned in Rihad's west ward is prized for sword-scabbards from Anvil to Sentinel.",
            "Orc clans and Redguard miners in the high Dragontail gorges keep a wary truce over the richest copper seams.",
            "Lila the Guide knows every dry wadi and hidden spring between the Brena estuary and the Skaven basin.",
            "When a flash-flood roars down the Tigonus gorge after a mountain storm, the canyon rises ten feet in an hour.",
            "Garnet and malachite cut in Pothago's lapidary stalls are set into brass-hilted jambiyas.",
            "The Brena bridge-watch checks every wagon for untaxed Colovian brandy and smuggled Dwemer scrap.",
            "Scorpion-oil salves brewed in Dak'fron draw the fire out of a sting before the swelling sets in.",
            "Stand by Rihad's sea-gate at sunset and watch the red cliffs turn the colour of glowing forge-iron.",
        ],
        {
            "dragon_slayer": "From the red gorges of Pothago to Rihad's Minaret of the Hour, the Dragontail borderlands hail the dragon-slayer, %1$s!",
            "companion_member": "Warriors along the Brena march and the Dragontail canyons respect the steel of Jorrvaskr's shield-companions.",
            "college_member": "The Conclave of the Southern Stars in Rihad welcomes a fellow scholar of the arcane arts.",
            "thieves_member": "Spice-Master Rasul's customs clerks at Rihad's Spice Gate watch their seals closely when you stroll the bazaar.",
            "brotherhood_member": "Along the Colovian border in Rihad, folk know the mark of the Dark Brotherhood—and step quickly out of your path.",
            "civil_war_hero": "In a borderland that has seen twenty legions march past, Rihad's garrison salutes a battle-proven hero.",
            "high_bounty_cleared": "Emir Rashad's diwan has recorded your restitution; the watch from Dak'fron to Rihad has stood down.",
            "local_hero": "Whether in the copper-pits of Pothago or the shaded suqs of Rihad, you are honoured as a friend of the realm.",
            "hold_story": "Since you cleared the canyon passes and border roads, the spice-caravans and copper-trains roll unhindered.",
            "bounty_hunter": "The badlands raiders around Dak'fron and Tigonus have been broken since you took the Emir's bounties.",
            "master_craftsman": "The bladesmiths of Brass and Steel in Rihad and the smelters of Pothago praise the perfection of your craft.",
            "barrow_delver": "You have delved the ochre shaft-tombs and cliff-vaults of the Dragontail and returned with their ancient treasures.",
        },
    ),
    "stros_mkai": (
        "Stros M'Kai rises from the turquoise Abecean Sea—an island of Dwemer brass observatories, whitewashed harbour forts, coral coves, and Restless League freebooters.",
        [
            "Port Hunding's lighthouse citadel and Dwemer brass dome are the first landmarks every Abecean sailor sights.",
            "At the Pirate's Rest tavern, half the captains drinking cane-rum have a bounty in at least one mainland port.",
            "The Dwemer Observatory on Stros M'Kai still turns its brass planetarium gears when the steam-valves are opened.",
            "Harbourmaster Zahra keeps two ledger-books at the customs quay—one for the governor and one for how things really work.",
            "Saintsport's coral cove has careening beaches where freebooter brigantines patch their hulls between cruises.",
            "Tavorra Cove fishers dry squid and flying-fish on bamboo racks along the leeward reef.",
            "Remember Cyrus the Restless and the rising that drove Tiber Septim's admiral into the harbour!",
            "Captain Makki at the sea-mouth fort can drop the harbour chain before a hostile frigate clears the headland.",
            "Old Salim by the observatory steps sells brass cogs and sea-charts that are accurate four times out of five.",
            "Saintsport caulkers boil pitch and coconut-fibre oakum that keeps a hull tight through warm tropical squalls.",
            "The Pillar Palace tomb cut into the island's limestone cliff holds the bones of the old sea-kings.",
            "Trade-winds blow steady across Stros M'Kai, smelling of cane-sugar, lime-blossom, and hot brass.",
            "No ship enters Port Hunding without dipping its topsail to the Pavilion of Frandar Hunding on the mole.",
            "Smugglers at Tavorra Cove can unload a lugger of unstamped silk between moonset and the morning watch.",
            "Even the tavern windows here are glazed with thick green bottle-glass and framed in ship's teak.",
            "Dive off the outer reef and you'll see sunken galley ribs crusted with brain-coral and pearl-oysters.",
            "Island smiths alloy Dwemer scrap-brass with mainland iron to forge cutlass-hilts that never rust in salt air.",
            "When the hurricane flag flies from the lighthouse gallery, every dhow and caravel warps into the inner basin.",
            "Headman Bhisha taxes the prize-courts lightly so the Restless League captains spend their gold ashore.",
            "On Stros M'Kai, a fast keel and a true compass are worth more than ten titles of nobility.",
        ],
        {
            "dragon_slayer": "Not since Cyrus slew Richton's dragon has Stros M'Kai cheered a wyrm-slayer like you, %1$s!",
            "companion_member": "A warrior of Jorrvaskr aboard in Port Hunding? Raise a mug of cane-spirit at the Pirate's Rest!",
            "college_member": "Old Salim and the scholars studying Stros M'Kai's Dwemer Observatory are keen to talk shop with a College mage.",
            "thieves_member": "On an island of freebooters and prize-agents, a master of the Guild is treated like visiting royalty.",
            "brotherhood_member": "Even the toughest corsairs at the Pirate's Rest stop bragging when they see the cold edge in your eyes.",
            "civil_war_hero": "Stros M'Kai honours those who fight for their banner against long odds—the harbour fort salutes you.",
            "high_bounty_cleared": "Harbourmaster Zahra crossed your name off the Port Hunding notice-board. The island watch won't trouble you.",
            "local_hero": "From Saintsport's careening beach to Port Hunding's quay, every crew on Stros M'Kai calls you shipmate.",
            "hold_story": "Since you settled the trouble haunting the island reefs and ruins, the Abecean trade-lanes are open wide.",
            "bounty_hunter": "Captain Makki says the sea-reavers and mutineers off the southern cays have vanished since you hunted them down.",
            "master_craftsman": "The brass-wrights and cutlass-smiths of Port Hunding swear your work could outlast a Dwemer gear-train.",
            "barrow_delver": "You've delved the steam-chambers and cliff-tombs of Stros M'Kai and walked back into the sea-breeze unscathed.",
        },
    ),
    "craglorn": (
        "Craglorn's star-carved highlands, red-crux canyons, and Nedic ruins rise between the Dragontail peaks and the Skyrim-Cyrodiil border, anchored by Elinhir, Dragonstar, and Belkarth.",
        [
            "Elinhir's Apex Spire and tiered wind-walls crown the eastern plateau where the Mages Conclave reads the constellations.",
            "Dragonstar's Blood-Sand Arena draws gladiators and high-stakes bookmakers from Hammerfell, Skyrim, and Cyrodiil alike.",
            "East Dragonstar speaks with a Nordic burr and drinks mead; West Dragonstar prays to the Yokudan stars and drinks mint tea.",
            "Belkarth's canyon crossroads is the muster-point for every delver and Star-Gazer heading into the red badlands.",
            "At Haddock's Market, canvas awnings shelter relic-traders selling Nedic bronze and raw nirncrux ore.",
            "Smiths in Craglorn grind crimson nirncrux dust into the charcoal to give their steel an uncanny edge.",
            "Warden Kaia's guards patrol Elinhir's seven-vaulted granary tiers against highland raiders.",
            "Arena-Master Dahab and Gladiator Safiya rule the sands of Dragonstar while Bookmaker Rim takes the odds at the gate.",
            "Ancient Nedic star-observatories carved into the canyon walls predated even the Ra Gada's arrival in the highlands.",
            "Stairs-Keeper Jul in Elinhir maintains the stone switchbacks that link the lower caravanserai to the Spire.",
            "Brother Amir at the Star-Vaulted Temple of Julianos records every meteor-fall over the Craglorn plateau.",
            "When the Warrior, the Mage, and the Thief burn bright over Belkarth, the Star-Gazers double their watches.",
            "Mule-trains climbing from Belkarth to Elinhir carry water-casks up and star-metal ingots down.",
            "Rock-scorpions and scaled haj-mota lurk in the dry washes where subterranean springs feed patches of thorn-scrub.",
            "In Dragonstar's partitioned streets, the East-West Watch keeps the peace between Nord factors and Redguard grandees.",
            "Delvers at the Crossroads Tavern in Belkarth never drink to a long life—only to a rich vault and a dry fuse.",
            "The wind-walls of Elinhir are built three blocks thick to break the freezing gales sweeping off the Jerall-Druadach saddle.",
            "Lodestones and amethyst geodes split from Craglorn's cliffs fetch high prices in the Mages Guilds of the coast.",
            "A gladiator who survives ten bouts in the Dragonstar Arena wins a silver-inlaid belt and freedom of the city.",
            "Look up from Belkarth's bridge at midnight and the constellations feel close enough to touch with a spear-tip.",
        ],
        {
            "dragon_slayer": "Under the constellations of Craglorn, felling a dragon elevates you to the company of the celestials, %1$s!",
            "companion_member": "Both the gladiators of Dragonstar and the delvers of Belkarth cheer a shield-warrior of Jorrvaskr.",
            "college_member": "The Apex Conclave of Elinhir and the Star-Gazers of Belkarth welcome a fellow seeker of arcane mysteries.",
            "thieves_member": "Bookmaker Rim in Dragonstar and the nirncrux traders at Haddock's Market lock their strongboxes when you pass.",
            "brotherhood_member": "Even the hardened pit-fighters of Dragonstar step aside when they see that silent, lethal grace in your stride.",
            "civil_war_hero": "Along the Skyrim border at Dragonstar, veterans on both sides of the wall respect your battlefield renown.",
            "high_bounty_cleared": "Warden Kaia and the Belkarth council have struck your fine from the highland rolls; walk the crags freely.",
            "local_hero": "From Haddock's Market to the Apex Spire of Elinhir, all Craglorn hails you as its foremost champion.",
            "hold_story": "Since you quelled the celestial anomalies and canyon raiders, the highland roads between Belkarth and Elinhir stand open.",
            "bounty_hunter": "Outlaws and scaled beasts in the Craglorn washes have been driven back since you answered the council's bounties.",
            "master_craftsman": "Craglorn's nirncrux-smiths marvel at the temper and enchantment-readiness of your handiwork.",
            "barrow_delver": "You have plumbed the deepest Nedic star-vaults andYokudan ruins of Craglorn and returned with their lore intact.",
        },
    ),
    "hews_bane": (
        "Hew's Bane curves into the Abecean Sea—a limestone peninsula of cliff-terraced palaces at Taneth and the stilt-pier free-port of Abah's Landing.",
        [
            "Taneth's Turquoise Palace steps down the sea-cliff in pool-garden terraces shaded by palms and blue glazed arches.",
            "Abah's Landing is built on stone piers and timber stilts over the harbour, where the Merchant Lords rule and the Thieves Guild takes its cut.",
            "The Landing Light at the harbour mouth guides merchant caravels and midnight smugglers alike through the shoals.",
            "Under the streets of Abah's Landing, flooded limestone cisterns and tide-caves form a second city for those who know the sign.",
            "At Thrall Cove, muffled oars and winch-towers haul unmarked casks into sea-caves before the Iron Wheel patrols arrive.",
            "Emir Jalil's pool-gardens in Taneth are fed by an aqueduct cut straight through the peninsula's limestone spine.",
            "Captain Hadiya on the Abah's Landing piers knows every captain's true flag, regardless of what banner flies at the masthead.",
            "Old Karim at the Net-Loft Guild can appraise a crate of smuggled Colovian silk without even prying the lid.",
            "Najwa the Gardener tends blue lotus and night-blooming jasmine on Taneth's palace terraces.",
            "The Iron Wheel mercenaries patrol Abah's Landing in disciplined pairs, yet the back-alley brokers never miss a night's trade.",
            "Down the Hundred-Stair Harbour in Taneth, porters carry spice-bales from the galley quays to the upper bazaar.",
            "No-Shira Citadel's ruined ramparts across the bay still overlook the straits where royal and syndicate warships clash.",
            "Harbour-Reeve Ben at Taneth stamps customs passes in turquoise wax for vessels bound up the Gold Coast.",
            "At the Golden Caravel Inn, merchant-syndics hire blade-escorts and privateer captains over spiced coffee.",
            "Salwa the Chart-Reader sells tide-tables of the Hew's Bane shoals that have saved twenty ships from the rocks.",
            "Whitewashed limestone, dark prismarine tile, and carved jungle-wood balconies give the peninsula ports their unmistakable silhouette.",
            "Pearl-cutters and turquoise-lapidaries in Taneth set cabochons into pierced-brassfiligree.",
            "If a coin drops through the boardwalk planks on Abah's Landing's pier market, three urchins dive for it before it hits the mud.",
            "Tava's harbour sanctuaries here hang model ships from the rafters for every crew that weathered the southern squalls.",
            "In Hew's Bane, every lock has two keys—one in the merchant-lord's pouch and one in the cistern below.",
        ],
        {
            "dragon_slayer": "From the Turquoise Palace of Taneth to the stilt-piers of Abah's Landing, the southern coast toasts the dragon-slayer, %1$s!",
            "companion_member": "Good steel and a sworn word are rare in Hew's Bane—a warrior of Jorrvaskr is worth ten hired guards here.",
            "college_member": "The chart-readers and court astrologers of Taneth welcome a fellow scholar from the northern College.",
            "thieves_member": "Down by the Net-Loft and the cistern steps of Abah's Landing, folk tip their hoods to a true peer of the Guild.",
            "brotherhood_member": "Even the Iron Wheel patrols in Abah's Landing look the other way when your shadow crosses the pier.",
            "civil_war_hero": "Emir Jalil's cliff-guards and the privateer captains of the peninsula salute a veteran of great campaigns.",
            "high_bounty_cleared": "Even the Iron Wheel's provost had to strike your name from the ledger after the syndics sealed your pardon.",
            "local_hero": "From Taneth's pool-gardens to the pier-markets of Abah's Landing, every guild and crew counts you an ally.",
            "hold_story": "Since you settled the syndicate shadow-war in the bay, Taneth and Abah's Landing prosper side by side.",
            "bounty_hunter": "Harbour-Reeve Ben says the coastal roads and coves of Hew's Bane are finally clear of cutthroats thanks to you.",
            "master_craftsman": "Taneth's turquoise-lapidaries and bladesmiths swear your craftsmanship is worthy of an Emir's treasury.",
            "barrow_delver": "You have braved the sunken limestone ossuaries and cliff-catacombs of Hew's Bane and returned with their lost hoard.",
        },
    ),
}


def load_valid_why_pool(province):
    """Loads the generated worldsheet JSON and builds per-region and per-role valid why citation lists."""
    ws = json.loads((WORLD_DIR / f"{province}.json").read_text(encoding="utf-8"))
    by_region = {}
    for rkey, rblock in ws["regions"].items():
        jobs_why = {}
        for role_id, jinfo in rblock.get("jobs", {}).items():
            if isinstance(jinfo, dict) and jinfo.get("why"):
                jobs_why[role_id] = jinfo["why"][:2]
        layer_ids = {}
        for lyr, flist in rblock.get("layers", {}).items():
            layer_ids[lyr] = [f"{rkey}.{lyr}.{f['id']}" for f in flist if f.get("id")]
        default_why = (
            layer_ids.get("L5", [])[:1]
            + layer_ids.get("L7", [])[:1]
        )
        court_why = (
            layer_ids.get("L8", [])[:1]
            + layer_ids.get("L5", [])[:1]
        )
        trainer_why = (
            layer_ids.get("L7", [])[:1]
            + layer_ids.get("L8", [])[:1]
        )
        by_region[rkey] = {
            "jobs": jobs_why,
            "default": default_why,
            "court": court_why,
            "trainer": trainer_why,
            "faith": layer_ids.get("L9", [])[:1] + layer_ids.get("L5", [])[:1],
        }
    return by_region


def make_unique_name(culture, idx, used_names):
    for attempt in range(500):
        n = idx + attempt * 31
        if culture == "orc":
            first = ORC_FIRST[n % len(ORC_FIRST)].strip().title()
            clan = ORC_CLAN[(n // 3 + attempt) % len(ORC_CLAN)]
            cand = f"{first} {clan}"
        elif culture == "redguard":
            first = REDGUARD_FIRST[n % len(REDGUARD_FIRST)].strip().title()
            sur = REDGUARD_SURNAME[(n // 5 + attempt) % len(REDGUARD_SURNAME)]
            cand = f"{first} {sur}"
        else:
            first = BRETON_FIRST[n % len(BRETON_FIRST)].strip().title()
            last = BRETON_LAST[(n // 4 + attempt) % len(BRETON_LAST)].strip()
            cand = f"{first} {last}"
        if cand not in used_names:
            used_names.add(cand)
            return cand
    cand = f"Citizen {idx}"
    used_names.add(cand)
    return cand


def generate_society_and_lang():
    places = load_places()
    hr_why_pool = load_valid_why_pool("high_rock")
    hf_why_pool = load_valid_why_pool("hammerfell")

    courts_path = SOCIETY_DIR / "courts.json"
    courts_data = json.loads(courts_path.read_text(encoding="utf-8"))
    trainers_path = SOCIETY_DIR / "trainers.json"
    trainers_data = json.loads(trainers_path.read_text(encoding="utf-8"))

    # All 72 Iliac Bay settlements in deterministic order
    all_specs = [
        ("wayrest", "high_rock", "S", "breton"),
        ("orsinium", "high_rock", "S", "orc"),
        ("sentinel", "hammerfell", "S", "redguard"),
        ("rihad", "hammerfell", "S", "redguard"),
    ] + [(s[0], s[1], s[2], s[3]) for s in NEW_PLANS]

    hr_settlements = {}
    hf_settlements = {}
    used_names = set()
    skill_cursor_hr = 0
    skill_cursor_hf = 0

    for s_idx, (sid, prov, tier_letter, culture) in enumerate(all_specs):
        place = places[sid]
        ptype = place["type"]
        rkey = SETTLEMENT_REGION[sid]
        why_pool = (hr_why_pool if prov == "high_rock" else hf_why_pool)[rkey]

        plan = json.loads((PLANS_DIR / f"{sid}.json").read_text(encoding="utf-8"))
        titles = plan.get("titles", {})
        blds = plan.get("buildings", [])
        named_bkeys = [b["name"] for b in blds if b.get("name")]
        if not named_bkeys:
            named_bkeys = [f"{sid}_hall"]

        seat_title = titles.get(named_bkeys[0], f"{sid.replace('_', ' ').title()} Keep")
        tier_num = 0 if (ptype in ("capital", "city") or tier_letter in ("S", "A")) else (1 if ptype == "town" else 2)

        # Target resident counts to guarantee >= 1,100 in High Rock and >= 950 in Hammerfell
        if tier_letter == "S":
            target_res = 65
        elif tier_letter == "A":
            target_res = 46
        elif tier_letter == "B":
            target_res = 28 if prov == "high_rock" else 26
        else:
            target_res = 14

        residents = []
        race_str = "orc" if culture == "orc" else ("redguard" if culture == "redguard" else "breton")

        # 1. Court (for all capitals, cities, and towns)
        if ptype in ("capital", "city", "town"):
            if sid in NAMED_COURTS:
                c_seat, c_title, c_jarl, c_stew, c_wiz, c_hc = NAMED_COURTS[sid]
                used_names.update([c_jarl, c_stew, c_wiz, c_hc])
            else:
                c_seat = seat_title
                c_title = "Lord" if culture == "breton" else ("War-Chief" if culture == "orc" else "Emir")
                c_jarl = f"{c_title} {make_unique_name(culture, s_idx * 100 + 1, used_names)}"
                c_stew = make_unique_name(culture, s_idx * 100 + 2, used_names)
                c_wiz = make_unique_name(culture, s_idx * 100 + 3, used_names)
                c_hc = make_unique_name(culture, s_idx * 100 + 4, used_names)

            courts_data["settlements"][sid] = {
                "seat": c_seat,
                "hold": rkey,
                "culture": culture,
                "ruler_title": c_title,
                "jarl": c_jarl,
                "steward": c_stew,
                "court_wizard": c_wiz,
                "housecarl": c_hc,
                "why": why_pool["court"],
            }

            court_defs = [
                ("jarl", c_jarl, c_title, "jarl", "court"),
                ("steward", c_stew, "Steward", "steward", "court"),
                ("court_wizard", c_wiz, "Court Mage", "court_wizard", "mage"),
                ("housecarl", c_hc, "Housecarl", "housecarl", "guard"),
            ]
            for r_i, (role_id, cname, rtitle, crole, sched) in enumerate(court_defs):
                residents.append({
                    "id": f"{sid}_{role_id}",
                    "name": cname,
                    "race": race_str,
                    "role": role_id if role_id != "court_wizard" else "mage",
                    "title": f"{rtitle} of {sid.replace('_', ' ').title()}",
                    "merchant": "court_wizard" if role_id == "court_wizard" else None,
                    "trainer": None,
                    "court_role": crole,
                    "home": named_bkeys[0],
                    "work": named_bkeys[0],
                    "schedule": sched,
                    "bark": f"{c_seat} stands watch over {rkey.replace('_', ' ').title()}.",
                    "why": why_pool["court"],
                })

        # 2. Trainers (5 for capitals/S-tier, 3 for A-tier cities, 1 for B-tier towns)
        if ptype in ("capital", "city", "town"):
            n_trainers = 5 if (ptype == "capital" or tier_letter == "S") else (3 if ptype == "city" else 1)
            t_list = []
            for t_i in range(n_trainers):
                if prov == "high_rock":
                    sk = SKILLS_18[skill_cursor_hr % len(SKILLS_18)]
                    skill_cursor_hr += 1
                else:
                    sk = SKILLS_18[skill_cursor_hf % len(SKILLS_18)]
                    skill_cursor_hf += 1

                if tier_letter == "S" and t_i < 2:
                    t_tier, t_cap = "master", 90
                elif tier_letter in ("S", "A") and t_i < 4:
                    t_tier, t_cap = "expert", 75
                else:
                    t_tier, t_cap = "journeyman", 50

                t_name = make_unique_name(culture, s_idx * 100 + 10 + t_i, used_names)
                wp = SKILL_WORKPLACE[sk]
                t_why = why_pool["jobs"].get(wp, why_pool["trainer"])
                t_list.append({
                    "name": t_name,
                    "skill": sk,
                    "tier": t_tier,
                    "cap": t_cap,
                    "workplace": wp,
                    "why": t_why,
                })
                bkey = named_bkeys[min(len(named_bkeys) - 1, 1 + t_i)]
                residents.append({
                    "id": f"{sid}_trainer_{sk}",
                    "name": t_name,
                    "race": race_str,
                    "role": "trainer",
                    "title": f"{t_tier.title()} {sk.replace('_', ' ').title()} Trainer",
                    "merchant": None,
                    "trainer": {"skill": sk, "tier": t_tier, "cap": t_cap},
                    "court_role": None,
                    "home": bkey,
                    "work": bkey,
                    "schedule": "artisan",
                    "bark": f"Discipline and practice sharpen {sk.replace('_', ' ')} faster than luck.",
                    "why": t_why,
                })
            trainers_data["settlements"][sid] = t_list

        # 3. Fill remaining named residents (merchants, artisans, priests, innkeepers, scholars, townsfolk)
        role_cycle = [
            ("innkeeper", "Innkeeper", "innkeeper", "innkeeper", "inn"),
            ("blacksmith", "Blacksmith", "blacksmith", "artisan", "smith"),
            ("merchant", "General Trader", "general", "merchant", "merchant"),
            ("apothecary", "Apothecary", "apothecary", "merchant", "alchemist"),
            ("priest", "Priest", "priest", "priest", "priest"),
            ("bard", "Troubadour" if prov == "high_rock" else " Poet-Singer", None, "bard", "inn"),
            ("jeweler", "Jeweller", "jeweler", "merchant", "jeweler"),
            ("tailor", "Clothier", "tailor", "merchant", "tailor"),
            ("bookseller", "Chancery Scribe", "bookseller", "scholar", "bookseller"),
            ("fletcher", "Bowyer", "fletcher", "artisan", "fletcher"),
            ("butcher", "Victualler", "butcher", "merchant", "butcher"),
            ("farmer", "Freeholder", None, "farmer", "farmer"),
            ("fisher", "Harbour Fisher", None, "laborer", "fisher"),
            ("miner", "Quarryman", None, "miner", "miner"),
            ("hunter", "Outrider", None, "hunter", "hunter"),
            ("scholar", "Archivist", None, "scholar", "bookseller"),
            ("worker", "Guild Artisan", None, "laborer", "worker"),
        ]

        while len(residents) < target_res:
            r_idx = len(residents)
            role_id, r_title, merch, sched, job_key = role_cycle[r_idx % len(role_cycle)]
            # Only first instance of each merchant profession gets the merchant tag so shops are cleanly staffed
            if r_idx >= len(role_cycle):
                merch = None
            rname = make_unique_name(culture, s_idx * 200 + r_idx + 20, used_names)
            home_bkey = named_bkeys[r_idx % len(named_bkeys)]
            work_bkey = named_bkeys[(r_idx + 1) % len(named_bkeys)]
            r_why = why_pool["jobs"].get(job_key, why_pool["default"])
            residents.append({
                "id": f"{sid}_resident_{r_idx + 1}",
                "name": rname,
                "race": race_str,
                "role": role_id,
                "title": f"{r_title} at {titles.get(work_bkey, sid.replace('_', ' ').title())}",
                "merchant": merch,
                "trainer": None,
                "court_role": None,
                "home": home_bkey,
                "work": work_bkey,
                "schedule": sched,
                "bark": f"Trade and watch-bells keep {sid.replace('_', ' ').title()} thriving.",
                "why": r_why,
            })

        s_entry = {
            "name": sid.replace("_", " ").title(),
            "region": f"{prov}/{rkey}",
            "hold": rkey,
            "tier": tier_num,
            "why": why_pool["default"],
            "residents": residents,
        }
        if prov == "high_rock":
            hr_settlements[sid] = s_entry
        else:
            hf_settlements[sid] = s_entry

    # Write high_rock_residents.json and hammerfell_residents.json
    hr_res_doc = {
        "schema_version": 1,
        "province": "high_rock",
        "settlements": hr_settlements,
    }
    hf_res_doc = {
        "schema_version": 1,
        "province": "hammerfell",
        "settlements": hf_settlements,
    }
    (SOCIETY_DIR / "high_rock_residents.json").write_text(json.dumps(hr_res_doc, indent=2) + "\n", encoding="utf-8")
    (SOCIETY_DIR / "hammerfell_residents.json").write_text(json.dumps(hf_res_doc, indent=2) + "\n", encoding="utf-8")
    courts_path.write_text(json.dumps(courts_data, indent=2) + "\n", encoding="utf-8")
    trainers_path.write_text(json.dumps(trainers_data, indent=2) + "\n", encoding="utf-8")

    hr_total = sum(len(s["residents"]) for s in hr_settlements.values())
    hf_total = sum(len(s["residents"]) for s in hf_settlements.values())
    print(f"Wrote high_rock_residents.json ({len(hr_settlements)} settlements, {hr_total} residents)")
    print(f"Wrote hammerfell_residents.json ({len(hf_settlements)} settlements, {hf_total} residents)")
    print(f"Updated courts.json ({len(courts_data['settlements'])} total courts) and trainers.json ({len(trainers_data['settlements'])} total settlements)")

    # Write society_high_rock.json and society_hammerfell.json
    for prov_id, lore_map, out_fn in [
        ("high_rock", HIGH_ROCK_REGION_LORE, "society_high_rock.json"),
        ("hammerfell", HAMMERFELL_REGION_LORE, "society_hammerfell.json"),
    ]:
        lang_out = {}
        for rkey, (summary_line, say_lines, react_map) in lore_map.items():
            lang_out[f"society.nirncraft.say.region.{rkey}"] = summary_line
            for idx, line_txt in enumerate(say_lines, start=1):
                lang_out[f"society.nirncraft.say.{rkey}.{idx}"] = line_txt
            for deed, r_txt in react_map.items():
                lang_out[f"react.{rkey}.{deed}"] = r_txt
                lang_out[f"society.nirncraft.react.{rkey}.{deed}"] = r_txt
        (LANG_DIR / out_fn).write_text(json.dumps(lang_out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"Wrote {out_fn} ({len(lang_out)} lang keys across {len(lore_map)} regions)")


def main():
    places = load_places()
    df_seeds = load_df_seeds()

    idx_path = PLANS_DIR / "index.json"
    idx_data = json.loads(idx_path.read_text(encoding="utf-8"))
    existing_plans = set(idx_data.get("plans", []))
    new_plan_ids = {s[0] for s in NEW_PLANS}

    # Collect feature signatures of all non-new plans so we never collide
    used_sigs = set()
    for pid in existing_plans:
        if pid in new_plan_ids:
            continue
        fp = PLANS_DIR / f"{pid}.json"
        if fp.exists():
            p = json.loads(fp.read_text(encoding="utf-8"))
            if p.get("features"):
                used_sigs.add(feat_sig(p["features"]))

    # 1. Upgrade the 4 existing Iliac Bay plans (wayrest, orsinium, sentinel, rihad)
    for pid, extras in EXISTING_UPGRADES.items():
        cnt = upgrade_existing_plan(pid, extras)
        print(f"Upgraded {pid}.json -> {cnt} named buildings")

    # 2. Generate all 68 new High Rock and Hammerfell plans
    for i, spec in enumerate(NEW_PLANS):
        pid = spec[0]
        if pid not in places:
            raise KeyError(f"Settlement {pid} not found in places/*.json!")
        place = places[pid]
        df_seed = df_seeds.get(pid)
        plan = build_plan(spec, place, df_seed, i + 1, used_sigs)
        out_fp = PLANS_DIR / f"{pid}.json"
        out_fp.write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
        existing_plans.add(pid)

    # 3. Update cityplans/index.json with all plans and all overlays
    all_overlays = sorted(p.stem for p in OVERLAYS_DIR.glob("*.json"))
    idx_out = {
        "plans": sorted(existing_plans),
        "overlays": all_overlays,
    }
    idx_path.write_text(json.dumps(idx_out, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(NEW_PLANS)} new Iliac Bay cityplans; total indexed plans = {len(idx_out['plans'])}, overlays = {len(all_overlays)}")

    # 4. Generate residents, courts, trainers, and region voice lang files
    generate_society_and_lang()


if __name__ == "__main__":
    main()

