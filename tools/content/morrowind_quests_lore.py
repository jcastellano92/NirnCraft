#!/usr/bin/env python3
"""Deterministic generator for R7 Morrowind & Solstheim V8:
- 13 MS1 regional storylines in mod/src/main/resources/skycraft/quests/story/<region>.json
- Expanded Morrowind & Solstheim side quests in mod/src/main/resources/skycraft/quests/side_morrowind.json
- F-WHY / region / placed-objective / Great House exclusivity back-fills on Morrowind & Solstheim faction quest files
- Full Morrowind & Solstheim place lore & regional flavour in mod/src/main/resources/skycraft/lore/places/morrowind.json
- Morrowind & Solstheim road encounters & wonder moments in mod/src/main/resources/skycraft/creatures/scenes.json
- 13 Morrowind & Solstheim regional festivals in mod/src/main/resources/skycraft/calendar/festivals.json
- Lang updates in mod/src/main/lang/skycraft/{quests,lore,guide,calendar}.json
"""
import glob
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft")
LANG_DIR = os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft")

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
    if raw_name and not raw_name.startswith("place.skycraft."):
        return raw_name
    base = pid
    for px in ("mw_", "morrowind_", "side_mw_", "sol_"):
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
    "stonefalls": {
        "seat": "ebonheart",
        "seat_name": "Ebonheart",
        "district_name": "the Stonefalls Inner Sea District",
        "region_name": "Stonefalls",
        "ruler": "Grand Council Legate Varro and Councilor Brara Morvayn",
        "short": "sf",
        "need_quests": 102,
        "why_pool": [
            "morrowind/stonefalls.L7.imperial_district_vs_dunmer_quarter",
            "morrowind/stonefalls.L7.nord_settlers",
            "morrowind/stonefalls.L8.imperial_chapels",
            "morrowind/stonefalls.L9.grand_council",
            "morrowind/stonefalls.L9.border_watch",
            "morrowind/stonefalls.L10.inner_sea_armature",
            "morrowind/stonefalls.L10.pact_era_forts",
            "morrowind.L9.imperial_presence",
        ],
    },
    "deshaan": {
        "seat": "mournhold",
        "seat_name": "Mournhold",
        "district_name": "the Sacred Precincts of Deshaan",
        "region_name": "Deshaan",
        "ruler": "King Hlaalu Helseth and Archcanon Gavas Drin",
        "short": "ds",
        "need_quests": 75,
        "why_pool": [
            "morrowind/deshaan.L7.priest_nobility",
            "morrowind/deshaan.L7.slaves_of_tear",
            "morrowind/deshaan.L8.almalexia_mother",
            "morrowind/deshaan.L8.quarantine_rites",
            "morrowind/deshaan.L9.indoril_vs_dres",
            "morrowind/deshaan.L9.goddess_unseen",
            "morrowind/deshaan.L10.old_mournhold",
            "morrowind/deshaan.L10.runaway_caves",
        ],
    },
    "molag_amur": {
        "seat": "necrom",
        "seat_name": "Necrom",
        "district_name": "the Ossuary Marches of Molag Amur",
        "region_name": "Molag Amur",
        "ruler": "Keeper-Prelate Faram Indoril of the Dead",
        "short": "ma",
        "need_quests": 66,
        "why_pool": [
            "morrowind/molag_amur.L7.warlike_nomads",
            "morrowind/molag_amur.L8.holy_mountains",
            "morrowind/molag_amur.L9.erabenimsun_raids",
            "morrowind/molag_amur.L10.lava_daedra_shrines",
            "morrowind.L8.ancestor_worship",
            "morrowind.L8.pilgrimage",
            "morrowind.L10.ancestral_tombs",
            "morrowind.L10.dwemer_citadels",
        ],
    },
    "telvannis": {
        "seat": "sadrith_mora",
        "seat_name": "Sadrith Mora",
        "district_name": "the Mushroom Isles of Telvannis",
        "region_name": "Telvannis",
        "ruler": "Master Aryon and the Council of Tel Naga",
        "short": "tv",
        "need_quests": 124,
        "why_pool": [
            "morrowind/telvannis.L7.lord_and_thrall",
            "morrowind/telvannis.L8.mephala_shrines",
            "morrowind/telvannis.L9.wizard_feuds",
            "morrowind/telvannis.L10.towers_gone_wrong",
            "morrowind/telvannis.L10.dwemer_workings",
            "morrowind.L7.house_order",
            "morrowind.L9.house_politics",
        ],
    },
    "sheogorad": {
        "seat": "dagon_fel",
        "seat_name": "Dagon Fel",
        "district_name": "the Basalt Archipelago of Sheogorad",
        "region_name": "Sheogorad",
        "ruler": "Reeve Hrolfdir and Prior Ralden of Sanctus Shrine",
        "short": "sh",
        "need_quests": 8,
        "why_pool": [
            "morrowind/sheogorad.L7.nord_dunmer_mix",
            "morrowind/sheogorad.L8.sanctus_shrine",
            "morrowind/sheogorad.L8.mad_god_shrine",
            "morrowind/sheogorad.L9.wreckers_suspected",
            "morrowind/sheogorad.L10.shipwrecks",
            "morrowind/sheogorad.L10.daedric_island_fortress",
        ],
    },
    "vvardenfell_ashlands": {
        "seat": "aldruhn",
        "seat_name": "Ald'ruhn",
        "district_name": "the Redoran Council Seat of the Ashlands",
        "region_name": "the Vvardenfell Ashlands",
        "ruler": "Archmaster Bolvyn Venim and Councilor Athyn Sarethi",
        "short": "va",
        "need_quests": 28,
        "why_pool": [
            "morrowind/vvardenfell_ashlands.L7.redoran_duty",
            "morrowind/vvardenfell_ashlands.L7.ashlander_traders_at_the_gate",
            "morrowind/vvardenfell_ashlands.L8.redoran_temple_piety",
            "morrowind/vvardenfell_ashlands.L8.urshilaku_prophecy",
            "morrowind/vvardenfell_ashlands.L9.council_of_ald_ruhn",
            "morrowind/vvardenfell_ashlands.L9.sleepers_in_the_north",
            "morrowind/vvardenfell_ashlands.L10.kogoruhn",
            "morrowind/vvardenfell_ashlands.L10.missamsi",
            "morrowind/vvardenfell_ashlands.L10.daedric_ruins",
        ],
    },
    "grazelands": {
        "seat": "vos",
        "seat_name": "Vos",
        "district_name": "the Golden Savannah of the Grazelands",
        "region_name": "the Grazelands",
        "ruler": "Master Aryon of Tel Vos and Ashkhan Kaushad of the Zainab",
        "short": "gl",
        "need_quests": 28,
        "why_pool": [
            "morrowind/grazelands.L7.nomads_vs_farmers",
            "morrowind/grazelands.L8.ashlander_herd_rites",
            "morrowind/grazelands.L9.grass_rights",
            "morrowind/grazelands.L10.blighted_egg_mines",
            "morrowind/grazelands.L10.dwemer_and_sixth_house",
        ],
    },
    "azuras_coast": {
        "seat": "molag_mar",
        "seat_name": "Molag Mar",
        "district_name": "the Pilgrim Bastions of Azura's Coast",
        "region_name": "Azura's Coast",
        "ruler": "Buoyant Armiger Commander Traven Marvos and Master Gilvas Barelo",
        "short": "ac",
        "need_quests": 14,
        "why_pool": [
            "morrowind/azuras_coast.L7.wizard_and_servant",
            "morrowind/azuras_coast.L8.azura_shrine",
            "morrowind/azuras_coast.L8.dreamer_monastery",
            "morrowind/azuras_coast.L9.telvanni_rivalries",
            "morrowind/azuras_coast.L9.corprus_hospital",
            "morrowind/azuras_coast.L10.daedric_headland_ruins",
            "morrowind/azuras_coast.L10.sea_cliff_dwemer",
        ],
    },
    "west_gash": {
        "seat": "balmora",
        "seat_name": "Balmora",
        "district_name": "the Hlaalu Trade District of the West Gash",
        "region_name": "the West Gash",
        "ruler": "Councilor Nileno Dorvayn and Governor-Magistrate of Caldera",
        "short": "wg",
        "need_quests": 20,
        "why_pool": [
            "morrowind/west_gash.L7.merchant_city",
            "morrowind/west_gash.L7.dockside_syndicate",
            "morrowind/west_gash.L7.outlander_miners",
            "morrowind/west_gash.L8.pragmatic_piety",
            "morrowind/west_gash.L9.hlaalu_collaboration",
            "morrowind/west_gash.L9.mine_ownership_fight",
            "morrowind/west_gash.L10.arkngthand",
            "morrowind/west_gash.L10.smuggled_ebony",
            "morrowind/west_gash.L10.sixth_house_hill_shrines",
        ],
    },
    "bitter_coast": {
        "seat": "gnaar_mok",
        "seat_name": "Gnaar Mok",
        "district_name": "the Mangrove Marshes of the Bitter Coast",
        "region_name": "the Bitter Coast",
        "ruler": "Headman Caryarel and the Hlaalu Coast-Factors",
        "short": "bc",
        "need_quests": 10,
        "why_pool": [
            "morrowind/bitter_coast.L7.smugglers_and_tax_men",
            "morrowind/bitter_coast.L8.bitter_superstitions",
            "morrowind/bitter_coast.L9.customs_corruption",
            "morrowind/bitter_coast.L10.smuggler_caves",
            "morrowind/bitter_coast.L10.daedric_mire_ruins",
        ],
    },
    "ascadian_isles": {
        "seat": "vivec",
        "seat_name": "Vivec City",
        "district_name": "the Sacred Cantons of the Ascadian Isles",
        "region_name": "the Ascadian Isles",
        "ruler": "Archcanon Tholer Saryoni and Duke Vedam Dren",
        "short": "ai",
        "need_quests": 22,
        "why_pool": [
            "morrowind/ascadian_isles.L7.plantation_slaves",
            "morrowind/ascadian_isles.L7.canton_ranks",
            "morrowind/ascadian_isles.L8.vivec_the_living_god",
            "morrowind/ascadian_isles.L8.seven_graces",
            "morrowind/ascadian_isles.L9.twin_lamps_underground",
            "morrowind/ascadian_isles.L9.temple_secrets",
            "morrowind/ascadian_isles.L10.under_vivec",
            "morrowind/ascadian_isles.L10.ald_sotha",
            "morrowind/ascadian_isles.L10.escaped_slave_caves",
        ],
    },
    "red_mountain": {
        "seat": "ghostgate",
        "seat_name": "Ghostgate",
        "district_name": "the Blighted Cordon of Red Mountain",
        "region_name": "Red Mountain",
        "ruler": "Knight-Commander Ralyn Othravel of the Ghostgate Towers",
        "short": "rm",
        "need_quests": 16,
        "why_pool": [
            "morrowind/red_mountain.L7.garrison_life",
            "morrowind/red_mountain.L8.fence_of_the_dead",
            "morrowind/red_mountain.L9.fence_weakening",
            "morrowind/red_mountain.L10.sixth_house_citadels",
            "morrowind.L9.ghostfence_holds",
            "morrowind.L10.sixth_house",
        ],
    },
    "solstheim": {
        "seat": "raven_rock",
        "seat_name": "Raven Rock",
        "district_name": "the Redoran & Skaal Marches of Solstheim",
        "region_name": "Solstheim",
        "ruler": "Councilor Lleril Morvayn and Shaman Storn Crag-Strider",
        "short": "so",
        "need_quests": 8,
        "why_pool": [
            "morrowind.L9.house_politics",
            "morrowind.L9.imperial_presence",
            "morrowind.L8.ancestor_worship",
            "morrowind.L8.ashlander_old_ways",
            "morrowind.L10.dwemer_citadels",
            "morrowind.L10.daedric_ruins",
            "morrowind.L10.blighted_mines",
        ],
    },
}


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
    for rkey, meta in REGION_META.items():
        rid = f"morrowind/{rkey}"
        ps = places_by_reg.get(rid, [])
        picked = [meta["seat"]]
        for cx, cz in crossings.get(rid, []):
            best = min(ps, key=lambda p: math.hypot(p["x"] - cx, p["z"] - cz))
            if best["id"] not in picked:
                picked.append(best["id"])
        hook_places[rkey] = picked
    return hook_places


def build_story_files(hook_places):
    stories = {
        "stonefalls": {
            "region": "morrowind/stonefalls",
            "province": "morrowind",
            "line": "story_stonefalls",
            "category": "main",
            "title": "The Charter of Ash and Salt",
            "why": [
                "morrowind/stonefalls.L7.imperial_district_vs_dunmer_quarter",
                "morrowind/stonefalls.L9.grand_council",
                "morrowind/stonefalls.L9.border_watch",
                "morrowind/stonefalls.L10.inner_sea_armature",
                "morrowind/stonefalls.L10.pact_era_forts",
            ],
            "hook": {
                "place": "ebonheart",
                "rumour": "At Ebonheart's East Empire quays, two sets of customs manifests bear the Grand Council's stamp while cargo vanishes between Davon's Watch and Kragenmoor.",
                "notice": "BY WARRANT OF THE GRAND COUNCIL AT EBONHEART — Investigators sought to audit the duplicate Inner Sea customs seals between Ebonheart, Davon's Watch, and Kragenmoor.",
                "context": "Ebonheart is the uneasy hinge between the Imperial administration and the mainland Great Houses: whoever controls the Inner Sea customs seals controls ebony and glass tariffs across Stonefalls.",
            },
            "quests": [
                {
                    "id": "story_stonefalls_01_double_manifest",
                    "order": 1,
                    "title": "Two Seals on the Inner Sea",
                    "region": "morrowind/stonefalls",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/stonefalls.L7.imperial_district_vs_dunmer_quarter",
                        "morrowind/stonefalls.L9.grand_council",
                    ],
                    "places": hook_places["stonefalls"],
                    "stages": [
                        {"id": 10, "place": "ebonheart", "objective": "Compare the duplicate customs registers at the East Empire Company Hall and Grand Council Chambers in Ebonheart"},
                        {"id": 20, "place": "davons_watch", "objective": "Inspect the unlogged night-lighters moored below the sea-wall at Davon's Watch"},
                        {"id": 30, "place": "kragenmoor", "objective": "Question the Hlaalu toll-factors in Kragenmoor about the Ash-Mountain wax matrix"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "ebonheart", "text": "Compare the duplicate customs registers at the East Empire Company Hall and Grand Council Chambers in Ebonheart"},
                        {"type": "go", "place": "davons_watch", "text": "Inspect the unlogged night-lighters moored below the sea-wall at Davon's Watch"},
                        {"type": "go", "place": "kragenmoor", "text": "Question the Hlaalu toll-factors in Kragenmoor about the Ash-Mountain wax matrix"},
                    ],
                    "consequence": {
                        "world_state": "stonefalls_double_manifest_traced",
                        "flag": "story_sf_manifest_traced",
                        "summary": "The duplicate customs seals in Ebonheart and Davon's Watch are traced to a renegade syndicate operating between Kragenmoor and Fort Arand.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_stonefalls_02_smoke_over_arand",
                    "order": 2,
                    "title": "Smoke Over Fort Arand",
                    "region": "morrowind/stonefalls",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_stonefalls_01_double_manifest",
                    "requires": ["quest:story_stonefalls_01_double_manifest"],
                    "why": [
                        "morrowind/stonefalls.L7.nord_settlers",
                        "morrowind/stonefalls.L9.border_watch",
                    ],
                    "places": ["kragenmoor", "fort_arand", "silgrad_tower"],
                    "stages": [
                        {"id": 10, "place": "kragenmoor", "objective": "Track the smuggler mule-train north from Kragenmoor toward the lava-ridge at Fort Arand"},
                        {"id": 20, "place": "fort_arand", "objective": "Clear the renegade tariff-wardens holding Fort Arand and recover the Brass Stamp-Die"},
                        {"id": 30, "place": "silgrad_tower", "objective": "Carry the Brass Stamp-Die to the Redoran border-magistrates at Silgrad Tower"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "kragenmoor", "text": "Track the smuggler mule-train north from Kragenmoor toward the lava-ridge at Fort Arand"},
                        {"type": "clear", "place": "fort_arand", "text": "Clear the renegade tariff-wardens holding Fort Arand and recover the Brass Stamp-Die"},
                        {"type": "go", "place": "silgrad_tower", "text": "Carry the Brass Stamp-Die to the Redoran border-magistrates at Silgrad Tower"},
                    ],
                    "consequence": {
                        "world_state": "fort_arand_stamp_recovered",
                        "flag": "story_sf_arand_cleared",
                        "summary": "The Brass Stamp-Die is recovered from Fort Arand and verified at Silgrad Tower, revealing a Dwemer steam-vault cache under Ash Mountain.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_stonefalls_03_armature_of_ash_mountain",
                    "order": 3,
                    "title": "The Armature Under Ash Mountain",
                    "region": "morrowind/stonefalls",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_stonefalls_02_smoke_over_arand",
                    "requires": ["quest:story_stonefalls_02_smoke_over_arand"],
                    "why": [
                        "morrowind/stonefalls.L10.inner_sea_armature",
                        "morrowind/stonefalls.L10.pact_era_forts",
                    ],
                    "places": ["blacklight", "heimlyn_keep", "ash_mountain"],
                    "stages": [
                        {"id": 10, "place": "blacklight", "objective": "Consult the Redoran archivists in Blacklight on the old Pact-era keys to Ash Mountain"},
                        {"id": 20, "place": "heimlyn_keep", "objective": "Retrieve the Dwemer pressure-key from the reliquary vaults beneath Heimlyn Keep"},
                        {"id": 30, "place": "ash_mountain", "objective": "Defeat the smuggling cabal inside the Ash Mountain steam-armature and seal the contraband sluice"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "blacklight", "text": "Consult the Redoran archivists in Blacklight on the old Pact-era keys to Ash Mountain"},
                        {"type": "go", "place": "heimlyn_keep", "text": "Retrieve the Dwemer pressure-key from the reliquary vaults beneath Heimlyn Keep"},
                        {"type": "clear", "place": "ash_mountain", "text": "Defeat the smuggling cabal inside the Ash Mountain steam-armature and seal the contraband sluice"},
                    ],
                    "consequence": {
                        "world_state": "ash_mountain_sluice_sealed",
                        "flag": "story_sf_armature_sealed",
                        "summary": "The Dwemer contraband sluice under Ash Mountain is sealed and the conspirators' ledger naming their Ebonheart patron is seized.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_stonefalls_04_concord_of_ebonheart",
                    "order": 4,
                    "title": "Concord of the Inner Sea",
                    "region": "morrowind/stonefalls",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_stonefalls_03_armature_of_ash_mountain",
                    "requires": ["quest:story_stonefalls_03_armature_of_ash_mountain"],
                    "finale": True,
                    "why": [
                        "morrowind/stonefalls.L8.imperial_chapels",
                        "morrowind/stonefalls.L9.grand_council",
                    ],
                    "places": ["brothers_of_strife", "ebonheart"],
                    "stages": [
                        {"id": 10, "place": "brothers_of_strife", "objective": "Intercept the corrupt legate's mercenary rearguard beneath the stone colossi at Brothers of Strife"},
                        {"id": 20, "place": "ebonheart", "objective": "Present the Ash Mountain ledger to the Grand Council Chambers in Ebonheart"},
                        {"id": 30, "place": "ebonheart", "objective": "Witness the Imperial and Great House delegates ratify the renewed Inner Sea Customs Concord"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "brothers_of_strife", "text": "Intercept the corrupt legate's mercenary rearguard beneath the stone colossi at Brothers of Strife"},
                        {"type": "go", "place": "ebonheart", "text": "Present the Ash Mountain ledger to the Grand Council Chambers in Ebonheart"},
                        {"type": "go", "place": "ebonheart", "text": "Witness the Imperial and Great House delegates ratify the renewed Inner Sea Customs Concord"},
                    ],
                    "consequence": {
                        "world_state": "ebonheart_inner_sea_concord_ratified",
                        "flag": "story_sf_finale_complete",
                        "summary": "The corrupt tariff ring is broken, lawful shipping resumes between Ebonheart and Vvardenfell, and the Grand Council honors your service.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Legate-Arbiter of Stonefalls",
                        "unique_item": "skycraft:ebony_longsword",
                    },
                },
            ],
        },
        "deshaan": {
            "region": "morrowind/deshaan",
            "province": "morrowind",
            "line": "story_deshaan",
            "category": "main",
            "title": "The Unseen Hand in Godsreach",
            "why": [
                "morrowind/deshaan.L7.priest_nobility",
                "morrowind/deshaan.L8.almalexia_mother",
                "morrowind/deshaan.L9.indoril_vs_dres",
                "morrowind/deshaan.L9.goddess_unseen",
                "morrowind/deshaan.L10.old_mournhold",
            ],
            "hook": {
                "place": "mournhold",
                "rumour": "In Mournhold's Great Bazaar, Indoril ordinators and Dres factors whisper that forged Temple quarantine writs are being used to seize caravan warehouses while Almalexia remains unseen.",
                "notice": "PROCLAMATION OF GODSREACH & THE ROYAL PALACE — Capable investigators sought to verify the Quarantine Serk seals and inspect the Dwemer shafts beneath Bamz-Amschend.",
                "context": "Deshaan is split between the orthodox Indoril priesthood of Mournhold, the royal Hlaalu court of King Helseth, and the slave-planters of Tear.",
            },
            "quests": [
                {
                    "id": "story_deshaan_01_writs_of_quarantine",
                    "order": 1,
                    "title": "Writs of the Unseen Mother",
                    "region": "morrowind/deshaan",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/deshaan.L7.priest_nobility",
                        "morrowind/deshaan.L8.quarantine_rites",
                    ],
                    "places": hook_places["deshaan"],
                    "stages": [
                        {"id": 10, "place": "mournhold", "objective": "Examine the disputed Temple quarantine seals in Mournhold's Great Bazaar and Godsreach"},
                        {"id": 20, "place": "quarantine_serk", "objective": "Travel to Quarantine Serk and question the apothecaries about the falsified plague tallies"},
                        {"id": 30, "place": "narsis", "objective": "Verify the Hlaalu caravan bills with the council factors at Narsis"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "mournhold", "text": "Examine the disputed Temple quarantine seals in Mournhold's Great Bazaar and Godsreach"},
                        {"type": "go", "place": "quarantine_serk", "text": "Travel to Quarantine Serk and question the apothecaries about the falsified plague tallies"},
                        {"type": "go", "place": "narsis", "text": "Verify the Hlaalu caravan bills with the council factors at Narsis"},
                    ],
                    "consequence": {
                        "world_state": "deshaan_quarantine_forgeries_exposed",
                        "flag": "story_ds_writs_exposed",
                        "summary": "The Quarantine Serk apothecaries and Narsis factors confirm the plague writs were forged using an old Indoril signet stolen from Taldeic Fortress.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_deshaan_02_chains_of_tear",
                    "order": 2,
                    "title": "Lanterns in the Silent Mire",
                    "region": "morrowind/deshaan",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_deshaan_01_writs_of_quarantine",
                    "requires": ["quest:story_deshaan_01_writs_of_quarantine"],
                    "why": [
                        "morrowind/deshaan.L7.slaves_of_tear",
                        "morrowind/deshaan.L9.indoril_vs_dres",
                    ],
                    "places": ["tear", "silent_mire", "shad_astula"],
                    "stages": [
                        {"id": 10, "place": "tear", "objective": "Question the Dres dock-stewards in Tear regarding the impounded grain barges"},
                        {"id": 20, "place": "silent_mire", "objective": "Locate the runaway shelter in Silent Mire where the honest Temple clerk took refuge"},
                        {"id": 30, "place": "shad_astula", "objective": "Have the mages at Shad Astula decipher the ciphered signet-ledger brought from Silent Mire"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "tear", "text": "Question the Dres dock-stewards in Tear regarding the impounded grain barges"},
                        {"type": "go", "place": "silent_mire", "text": "Locate the runaway shelter in Silent Mire where the honest Temple clerk took refuge"},
                        {"type": "go", "place": "shad_astula", "text": "Have the mages at Shad Astula decipher the ciphered signet-ledger brought from Silent Mire"},
                    ],
                    "consequence": {
                        "world_state": "shad_astula_cipher_broken",
                        "flag": "story_ds_cipher_broken",
                        "summary": "The scholars of Shad Astula break the cipher, revealing that a rogue Ordinator cabal has fortified Taldeic Fortress and the ruins under Mournhold.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_deshaan_03_bells_of_old_mournhold",
                    "order": 3,
                    "title": "Bells Beneath Bamz-Amschend",
                    "region": "morrowind/deshaan",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_deshaan_02_chains_of_tear",
                    "requires": ["quest:story_deshaan_02_chains_of_tear"],
                    "why": [
                        "morrowind/deshaan.L9.goddess_unseen",
                        "morrowind/deshaan.L10.old_mournhold",
                    ],
                    "places": ["taldeic_fortress", "bamz_amschend", "mournhold"],
                    "stages": [
                        {"id": 10, "place": "taldeic_fortress", "objective": "Storm the outer watch-court of Taldeic Fortress and recover the stolen Indoril Signet-Ring"},
                        {"id": 20, "place": "bamz_amschend", "objective": "Descend into Bamz-Amschend beneath Mournhold and defeat the schismatic Ordinator prelate"},
                        {"id": 30, "place": "mournhold", "objective": "Carry the recovered Indoril Signet-Ring back to Godsreach in Mournhold"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "taldeic_fortress", "text": "Storm the outer watch-court of Taldeic Fortress and recover the stolen Indoril Signet-Ring"},
                        {"type": "clear", "place": "bamz_amschend", "text": "Descend into Bamz-Amschend beneath Mournhold and defeat the schismatic Ordinator prelate"},
                        {"type": "go", "place": "mournhold", "text": "Carry the recovered Indoril Signet-Ring back to Godsreach in Mournhold"},
                    ],
                    "consequence": {
                        "world_state": "bamz_amschend_schism_crushed",
                        "flag": "story_ds_bamz_cleared",
                        "summary": "The schismatic prelate in Bamz-Amschend is defeated and the stolen Indoril signet is returned to the surface of Mournhold.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_deshaan_04_judgment_in_the_courtyard",
                    "order": 4,
                    "title": "Judgment in the Royal Courtyard",
                    "region": "morrowind/deshaan",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_deshaan_03_bells_of_old_mournhold",
                    "requires": ["quest:story_deshaan_03_bells_of_old_mournhold"],
                    "finale": True,
                    "why": [
                        "morrowind/deshaan.L8.almalexia_mother",
                        "morrowind/deshaan.L10.runaway_caves",
                    ],
                    "places": ["eidolons_hollow", "mournhold"],
                    "stages": [
                        {"id": 10, "place": "eidolons_hollow", "objective": "Silence the last hired Dres mercenaries gathered at Eidolon's Hollow"},
                        {"id": 20, "place": "mournhold", "objective": "Deliver the full evidence before the Royal Palace and Temple High Fane in Mournhold"},
                        {"id": 30, "place": "mournhold", "objective": "Receive the joint commendation of Godsreach and the Royal Court of Mournhold"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "eidolons_hollow", "text": "Silence the last hired Dres mercenaries gathered at Eidolon's Hollow"},
                        {"type": "go", "place": "mournhold", "text": "Deliver the full evidence before the Royal Palace and Temple High Fane in Mournhold"},
                        {"type": "go", "place": "mournhold", "text": "Receive the joint commendation of Godsreach and the Royal Court of Mournhold"},
                    ],
                    "consequence": {
                        "world_state": "mournhold_quarantine_accord_restored",
                        "flag": "story_ds_finale_complete",
                        "summary": "The false quarantine seizures end across Deshaan, the Silent Mire refugees are granted sanctuary, and Mournhold's markets reopen in full.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Hand of Mournhold's Peace",
                        "unique_item": "skycraft:ebony_mace",
                    },
                },
            ],
        },
        "molag_amur": {
            "region": "morrowind/molag_amur",
            "province": "morrowind",
            "line": "story_molag_amur",
            "category": "main",
            "title": "The Ossuary Ledger of Necrom",
            "why": [
                "morrowind/molag_amur.L7.warlike_nomads",
                "morrowind/molag_amur.L8.holy_mountains",
                "morrowind/molag_amur.L9.erabenimsun_raids",
                "morrowind/molag_amur.L10.lava_daedra_shrines",
                "morrowind.L8.ancestor_worship",
            ],
            "hook": {
                "place": "necrom",
                "rumour": "Corpse-ships arriving at Zaappi Docks and Necrom's white walls have been turned away after someone stole the ancestral burial charters from the lower ossuaries.",
                "notice": "EDICT OF THE KEEPERS OF THE DEAD AT NECROM — Seekers required to recover the stolen Ossuary Roll from the ash-wastes between Alavelis, Camonnaruhn, and Bthar-Zel.",
                "context": "Necrom is the white-walled City of the Dead where every Dunmer House brings its ancestors; across the western ash-rivers of Molag Amur, warlike clans and grave-robbers contest the pilgrim roads.",
            },
            "quests": [
                {
                    "id": "story_molag_amur_01_sealed_ossuary",
                    "order": 1,
                    "title": "Unregistered Bones at Zaappi Docks",
                    "region": "morrowind/molag_amur",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/molag_amur.L7.warlike_nomads",
                        "morrowind.L8.ancestor_worship",
                    ],
                    "places": hook_places["molag_amur"],
                    "stages": [
                        {"id": 10, "place": "necrom", "objective": "Speak with the Keepers of the Dead in Necrom regarding the missing Ossuary Roll"},
                        {"id": 20, "place": "zaappi_docks", "objective": "Inspect the waiting funeral barges moored at Zaappi Docks"},
                        {"id": 30, "place": "alavelis", "objective": "Follow the ash-wheel tracks inland to the caravan post at Alavelis"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "necrom", "text": "Speak with the Keepers of the Dead in Necrom regarding the missing Ossuary Roll"},
                        {"type": "go", "place": "zaappi_docks", "text": "Inspect the waiting funeral barges moored at Zaappi Docks"},
                        {"type": "go", "place": "alavelis", "text": "Follow the ash-wheel tracks inland to the caravan post at Alavelis"},
                    ],
                    "consequence": {
                        "world_state": "necrom_ossuary_trail_found",
                        "flag": "story_ma_trail_found",
                        "summary": "Witnesses at Zaappi Docks and Alavelis reveal that renegade grave-brokers carried the Ossuary Roll toward Camonnaruhn.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_molag_amur_02_raiders_of_camonnaruhn",
                    "order": 2,
                    "title": "Ash-Fires of Camonnaruhn",
                    "region": "morrowind/molag_amur",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_molag_amur_01_sealed_ossuary",
                    "requires": ["quest:story_molag_amur_01_sealed_ossuary"],
                    "why": [
                        "morrowind/molag_amur.L8.holy_mountains",
                        "morrowind/molag_amur.L9.erabenimsun_raids",
                    ],
                    "places": ["alavelis", "camonnaruhn", "bal_sunnar"],
                    "stages": [
                        {"id": 10, "place": "alavelis", "objective": "Set out from Alavelis across the basalt ridge toward Camonnaruhn"},
                        {"id": 20, "place": "camonnaruhn", "objective": "Clear the outcast raiders occupying Camonnaruhn and recover the bronze reliquary case"},
                        {"id": 30, "place": "bal_sunnar", "objective": "Trace the buyers of the missing parchment leaves to the hillside vaults of Bal Sunnar"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "alavelis", "text": "Set out from Alavelis across the basalt ridge toward Camonnaruhn"},
                        {"type": "clear", "place": "camonnaruhn", "text": "Clear the outcast raiders occupying Camonnaruhn and recover the bronze reliquary case"},
                        {"type": "go", "place": "bal_sunnar", "text": "Trace the buyers of the missing parchment leaves to the hillside vaults of Bal Sunnar"},
                    ],
                    "consequence": {
                        "world_state": "camonnaruhn_reliquary_recovered",
                        "flag": "story_ma_reliquary_recovered",
                        "summary": "The bronze reliquary case is recovered at Camonnaruhn, and clues at Bal Sunnar point to a necromantic buyer inside Bthar-Zel.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_molag_amur_03_steam_and_cinders",
                    "order": 3,
                    "title": "The Brass Vault of Bthar-Zel",
                    "region": "morrowind/molag_amur",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_molag_amur_02_raiders_of_camonnaruhn",
                    "requires": ["quest:story_molag_amur_02_raiders_of_camonnaruhn"],
                    "why": [
                        "morrowind/molag_amur.L10.lava_daedra_shrines",
                        "morrowind.L10.dwemer_citadels",
                    ],
                    "places": ["bal_sunnar", "bthar_zel", "uhaammu"],
                    "stages": [
                        {"id": 10, "place": "bal_sunnar", "objective": "Cross the eastern cinder-wash from Bal Sunnar to the Dwemer citadel of Bthar-Zel"},
                        {"id": 20, "place": "bthar_zel", "objective": "Defeat the bone-binders inside Bthar-Zel and reclaim the intact Ossuary Roll of Necrom"},
                        {"id": 30, "place": "uhaammu", "objective": "Purge the Daedric shrine-altar at Uhaammu where the stolen ancestor-seals were being profaned"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "bal_sunnar", "text": "Cross the eastern cinder-wash from Bal Sunnar to the Dwemer citadel of Bthar-Zel"},
                        {"type": "clear", "place": "bthar_zel", "text": "Defeat the bone-binders inside Bthar-Zel and reclaim the intact Ossuary Roll of Necrom"},
                        {"type": "clear", "place": "uhaammu", "text": "Purge the Daedric shrine-altar at Uhaammu where the stolen ancestor-seals were being profaned"},
                    ],
                    "consequence": {
                        "world_state": "bthar_zel_ossuary_roll_reclaimed",
                        "flag": "story_ma_bthar_zel_cleared",
                        "summary": "The intact Ossuary Roll is reclaimed from Bthar-Zel and the shrine-altar at Uhaammu is cleansed.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_molag_amur_04_white_walls_of_necrom",
                    "order": 4,
                    "title": "Procession of the White Walls",
                    "region": "morrowind/molag_amur",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_molag_amur_03_steam_and_cinders",
                    "requires": ["quest:story_molag_amur_03_steam_and_cinders"],
                    "finale": True,
                    "why": [
                        "morrowind.L8.pilgrimage",
                        "morrowind.L10.ancestral_tombs",
                    ],
                    "places": ["zaappi_docks", "necrom"],
                    "stages": [
                        {"id": 10, "place": "zaappi_docks", "objective": "Signal the waiting funeral barges at Zaappi Docks that the ossuary gates are reopening"},
                        {"id": 20, "place": "necrom", "objective": "Restore the Ossuary Roll to the Keepers of the Dead in the high catacombs of Necrom"},
                        {"id": 30, "place": "necrom", "objective": "Receive the Ash-Lantern Blessing before the white walls of Necrom"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "zaappi_docks", "text": "Signal the waiting funeral barges at Zaappi Docks that the ossuary gates are reopening"},
                        {"type": "go", "place": "necrom", "text": "Restore the Ossuary Roll to the Keepers of the Dead in the high catacombs of Necrom"},
                        {"type": "go", "place": "necrom", "text": "Receive the Ash-Lantern Blessing before the white walls of Necrom"},
                    ],
                    "consequence": {
                        "world_state": "necrom_ossuary_roll_restored",
                        "flag": "story_ma_finale_complete",
                        "summary": "The Ossuary Roll rests once more in Necrom's catacombs, the funeral barges dock in peace, and all five Great Houses honor your name.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Warden of the White Walls",
                        "unique_item": "skycraft:staff_of_banishing",
                    },
                },
            ],
        },
        "telvannis": {
            "region": "morrowind/telvannis",
            "province": "morrowind",
            "line": "story_telvannis",
            "category": "main",
            "title": "The Third Root of Tel Naga",
            "why": [
                "morrowind/telvannis.L7.lord_and_thrall",
                "morrowind/telvannis.L8.mephala_shrines",
                "morrowind/telvannis.L9.wizard_feuds",
                "morrowind/telvannis.L10.towers_gone_wrong",
                "morrowind/telvannis.L10.dwemer_workings",
            ],
            "hook": {
                "place": "sadrith_mora",
                "rumour": "At the Gateway Inn in Sadrith Mora, Mouths and Imperial clerks alike complain that rogue spore-anchor experiments are warping the fungal sea-lanes between Wolverine Hall, Firewatch, and Port Telvannis.",
                "notice": "NOTICE OF THE COUNCIL AT TEL NAGA — Competent outlanders or retainers hired to investigate the unstable root-conduits between Sadrith Mora, Firewatch, and Port Telvannis.",
                "context": "In Telvannis, a wizard's argument is settled by survival: if an experiment succeeds, it is lawful; if it threatens the Council's trade lanes, someone is hired to prune it.",
            },
            "quests": [
                {
                    "id": "story_telvannis_01_spores_at_gateway",
                    "order": 1,
                    "title": "Spores at the Gateway Inn",
                    "region": "morrowind/telvannis",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/telvannis.L7.lord_and_thrall",
                        "morrowind/telvannis.L9.wizard_feuds",
                    ],
                    "places": hook_places["telvannis"],
                    "stages": [
                        {"id": 10, "place": "sadrith_mora", "objective": "Take the commission from the Council Mouths at Tel Naga and the Gateway Inn in Sadrith Mora"},
                        {"id": 20, "place": "wolverine_hall", "objective": "Compare the Mages Guild aetheric readings inside Wolverine Hall"},
                        {"id": 30, "place": "tel_rendys", "objective": "Inspect the scorched fungal root-graft at Tel Rendys"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "sadrith_mora", "text": "Take the commission from the Council Mouths at Tel Naga and the Gateway Inn in Sadrith Mora"},
                        {"type": "go", "place": "wolverine_hall", "text": "Compare the Mages Guild aetheric readings inside Wolverine Hall"},
                        {"type": "go", "place": "tel_rendys", "text": "Inspect the scorched fungal root-graft at Tel Rendys"},
                    ],
                    "consequence": {
                        "world_state": "telvannis_spore_graft_traced",
                        "flag": "story_tv_graft_traced",
                        "summary": "Readings from Wolverine Hall and Tel Rendys prove someone is splicing Dwemer resonators into living Emperor Parasol roots across the archipelago.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_telvannis_02_beacon_of_firewatch",
                    "order": 2,
                    "title": "The Resonator at Firewatch",
                    "region": "morrowind/telvannis",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_telvannis_01_spores_at_gateway",
                    "requires": ["quest:story_telvannis_01_spores_at_gateway"],
                    "why": [
                        "morrowind/telvannis.L9.wizard_feuds",
                        "morrowind/telvannis.L10.dwemer_workings",
                    ],
                    "places": ["firewatch", "port_telvannis", "voryn_plantation"],
                    "stages": [
                        {"id": 10, "place": "firewatch", "objective": "Question the Imperial charter-inspectors in Firewatch about the stolen Dwemer coils"},
                        {"id": 20, "place": "port_telvannis", "objective": "Check the magisterial factor-ledgers on the mushroom docks of Port Telvannis"},
                        {"id": 30, "place": "voryn_plantation", "objective": "Seize the smuggling manifest from the storage vaults at Voryn Plantation"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "firewatch", "text": "Question the Imperial charter-inspectors in Firewatch about the stolen Dwemer coils"},
                        {"type": "go", "place": "port_telvannis", "text": "Check the magisterial factor-ledgers on the mushroom docks of Port Telvannis"},
                        {"type": "clear", "place": "voryn_plantation", "text": "Seize the smuggling manifest from the storage vaults at Voryn Plantation"},
                    ],
                    "consequence": {
                        "world_state": "port_telvannis_coil_manifest_seized",
                        "flag": "story_tv_manifest_seized",
                        "summary": "The manifest seized near Port Telvannis reveals a rogue magisterial apprentice has turned Tel Dreloth into a living spore-furnace.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_telvannis_03_tower_gone_wrong",
                    "order": 3,
                    "title": "The Overgrowth of Tel Dreloth",
                    "region": "morrowind/telvannis",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_telvannis_02_beacon_of_firewatch",
                    "requires": ["quest:story_telvannis_02_beacon_of_firewatch"],
                    "why": [
                        "morrowind/telvannis.L8.mephala_shrines",
                        "morrowind/telvannis.L10.towers_gone_wrong",
                    ],
                    "places": ["gorne", "tel_dreloth", "ikhuraarnit_egg_mine"],
                    "stages": [
                        {"id": 10, "place": "gorne", "objective": "Secure a boat-passage and ward-salts from the Indoril factors at Gorne"},
                        {"id": 20, "place": "tel_dreloth", "objective": "Clear the mutated spore-thralls inside Tel Dreloth and dismantle the Dwemer root-core"},
                        {"id": 30, "place": "ikhuraarnit_egg_mine", "objective": "Purge the secondary spore-vent inside Ikhuraarnit Egg Mine"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "gorne", "text": "Secure a boat-passage and ward-salts from the Indoril factors at Gorne"},
                        {"type": "clear", "place": "tel_dreloth", "text": "Clear the mutated spore-thralls inside Tel Dreloth and dismantle the Dwemer root-core"},
                        {"type": "clear", "place": "ikhuraarnit_egg_mine", "text": "Purge the secondary spore-vent inside Ikhuraarnit Egg Mine"},
                    ],
                    "consequence": {
                        "world_state": "tel_dreloth_root_core_dismantled",
                        "flag": "story_tv_dreloth_cleared",
                        "summary": "The rogue root-core in Tel Dreloth is dismantled before its spores can choke the Zafirbel Bay shipping channels.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_telvannis_04_precedent_at_tel_naga",
                    "order": 4,
                    "title": "By Telvanni Precedent",
                    "region": "morrowind/telvannis",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_telvannis_03_tower_gone_wrong",
                    "requires": ["quest:story_telvannis_03_tower_gone_wrong"],
                    "finale": True,
                    "why": [
                        "morrowind/telvannis.L9.wizard_feuds",
                        "morrowind/telvannis.L10.towers_gone_wrong",
                    ],
                    "places": ["tel_naga", "sadrith_mora"],
                    "stages": [
                        {"id": 10, "place": "tel_naga", "objective": "Present the dismantled Dwemer root-core to the Council Mouths inside Tel Naga"},
                        {"id": 20, "place": "sadrith_mora", "objective": "Deliver the stabilized aetheric readings to the Imperial liaison at Sadrith Mora"},
                        {"id": 30, "place": "sadrith_mora", "objective": "Claim your Magisterial Precedent Writ at the Gateway Plaza in Sadrith Mora"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "tel_naga", "text": "Present the dismantled Dwemer root-core to the Council Mouths inside Tel Naga"},
                        {"type": "go", "place": "sadrith_mora", "text": "Deliver the stabilized aetheric readings to the Imperial liaison at Sadrith Mora"},
                        {"type": "go", "place": "sadrith_mora", "text": "Claim your Magisterial Precedent Writ at the Gateway Plaza in Sadrith Mora"},
                    ],
                    "consequence": {
                        "world_state": "sadrith_mora_root_precedent_entered",
                        "flag": "story_tv_finale_complete",
                        "summary": "Since you survived and dismantled the rogue tower, the Council of Tel Naga declares your action lawful precedent and awards you a Magisterial Writ.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Arbiter of Tel Naga",
                        "unique_item": "skycraft:staff_of_fireballs",
                    },
                },
            ],
        },
        "sheogorad": {
            "region": "morrowind/sheogorad",
            "province": "morrowind",
            "line": "story_sheogorad",
            "category": "main",
            "title": "False Beacons on the Sea of Ghosts",
            "why": [
                "morrowind/sheogorad.L7.nord_dunmer_mix",
                "morrowind/sheogorad.L8.sanctus_shrine",
                "morrowind/sheogorad.L9.wreckers_suspected",
                "morrowind/sheogorad.L10.shipwrecks",
                "morrowind/sheogorad.L10.daedric_island_fortress",
            ],
            "hook": {
                "place": "dagon_fel",
                "rumour": "Fishermen at Dagon Fel's End of the World tavern swear someone is lighting false storm-lanterns on the basalt skerries to lure cogs onto the rocks while blaming the Urshilaku hunters.",
                "notice": "DAGON FEL HARBOUR WATCH — Reward posted for uncovering who tends the false wrecking-fires between Dagon Fel, Sanctus Shrine, and Vas.",
                "context": "Sheogorad's jagged islands shelter a stubborn mix of Nord whalers, Dunmer hermits, and silent Temple pilgrims; when ships break on the reefs, old suspicions flare fast.",
            },
            "quests": [
                {
                    "id": "story_sheogorad_01_lanterns_on_the_skerries",
                    "order": 1,
                    "title": "Oil on the Basalt Reefs",
                    "region": "morrowind/sheogorad",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/sheogorad.L7.nord_dunmer_mix",
                        "morrowind/sheogorad.L9.wreckers_suspected",
                    ],
                    "places": hook_places["sheogorad"],
                    "stages": [
                        {"id": 10, "place": "dagon_fel", "objective": "Inspect the salvaged rudder and whale-oil casks on the quay at Dagon Fel"},
                        {"id": 20, "place": "sanctus_shrine", "objective": "Speak with the vowed hermit at Sanctus Shrine regarding the night-signals on the western rocks"},
                        {"id": 30, "place": "urshilaku_camp", "objective": "Compare the Ashlander coast-patrol reports at Urshilaku Camp"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "dagon_fel", "text": "Inspect the salvaged rudder and whale-oil casks on the quay at Dagon Fel"},
                        {"type": "go", "place": "sanctus_shrine", "text": "Speak with the vowed hermit at Sanctus Shrine regarding the night-signals on the western rocks"},
                        {"type": "go", "place": "urshilaku_camp", "text": "Compare the Ashlander coast-patrol reports at Urshilaku Camp"},
                    ],
                    "consequence": {
                        "world_state": "sheogorad_urshilaku_cleared_of_blame",
                        "flag": "story_sh_urshilaku_cleared",
                        "summary": "The Sanctus Shrine hermit and Urshilaku scouts prove the wrecking-lanterns burn refined pitch brought from the old towers near Vas.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_sheogorad_02_signal_at_sulipund",
                    "order": 2,
                    "title": "The Shutters of Sulipund",
                    "region": "morrowind/sheogorad",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_sheogorad_01_lanterns_on_the_skerries",
                    "requires": ["quest:story_sheogorad_01_lanterns_on_the_skerries"],
                    "why": [
                        "morrowind/sheogorad.L8.sanctus_shrine",
                        "morrowind/sheogorad.L10.shipwrecks",
                    ],
                    "places": ["sulipund", "valenvaryon", "ald_redaynia"],
                    "stages": [
                        {"id": 10, "place": "sulipund", "objective": "Search the cliff-top gallery at Sulipund for the brass signal-mirror"},
                        {"id": 20, "place": "valenvaryon", "objective": "Defeat the wrecker lookouts camped in the stronghold ruins of Valenvaryon"},
                        {"id": 30, "place": "ald_redaynia", "objective": "Inspect the hidden boat-landing beneath the tower of Ald Redaynia"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "sulipund", "text": "Search the cliff-top gallery at Sulipund for the brass signal-mirror"},
                        {"type": "clear", "place": "valenvaryon", "text": "Defeat the wrecker lookouts camped in the stronghold ruins of Valenvaryon"},
                        {"type": "go", "place": "ald_redaynia", "text": "Inspect the hidden boat-landing beneath the tower of Ald Redaynia"},
                    ],
                    "consequence": {
                        "world_state": "sheogorad_wrecker_landing_found",
                        "flag": "story_sh_landing_found",
                        "summary": "The brass signal-mirror and boat-landing logs show the wreckers haul their plunder into the island fortress of Vas and Addadshashanammu.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_sheogorad_03_coven_of_vas",
                    "order": 3,
                    "title": "The Wreckers of Vas",
                    "region": "morrowind/sheogorad",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_sheogorad_02_signal_at_sulipund",
                    "requires": ["quest:story_sheogorad_02_signal_at_sulipund"],
                    "why": [
                        "morrowind/sheogorad.L8.mad_god_shrine",
                        "morrowind/sheogorad.L10.daedric_island_fortress",
                    ],
                    "places": ["vas", "addadshashanammu", "shushishi"],
                    "stages": [
                        {"id": 10, "place": "vas", "objective": "Storm the Daedric island tower of Vas and break the wrecker captain's coven"},
                        {"id": 20, "place": "addadshashanammu", "objective": "Smash the false beacon-brazier atop the shrine of Addadshashanammu"},
                        {"id": 30, "place": "shushishi", "objective": "Recover the stolen Dagon Fel harbour-bell from the cavern cache at Shushishi"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "vas", "text": "Storm the Daedric island tower of Vas and break the wrecker captain's coven"},
                        {"type": "clear", "place": "addadshashanammu", "text": "Smash the false beacon-brazier atop the shrine of Addadshashanammu"},
                        {"type": "go", "place": "shushishi", "text": "Recover the stolen Dagon Fel harbour-bell from the cavern cache at Shushishi"},
                    ],
                    "consequence": {
                        "world_state": "vas_wrecker_coven_broken",
                        "flag": "story_sh_vas_cleared",
                        "summary": "The wrecker coven at Vas is broken, the false brazier at Addadshashanammu is dark, and Dagon Fel's harbour-bell is recovered.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_sheogorad_04_bell_of_dagon_fel",
                    "order": 4,
                    "title": "True Light on the Northern Watchtower",
                    "region": "morrowind/sheogorad",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_sheogorad_03_coven_of_vas",
                    "requires": ["quest:story_sheogorad_03_coven_of_vas"],
                    "finale": True,
                    "why": [
                        "morrowind/sheogorad.L7.nord_dunmer_mix",
                        "morrowind/sheogorad.L10.shipwrecks",
                    ],
                    "places": ["sanctus_shrine", "dagon_fel"],
                    "stages": [
                        {"id": 10, "place": "sanctus_shrine", "objective": "Have the recovered harbour-bell blessed in silence at Sanctus Shrine"},
                        {"id": 20, "place": "dagon_fel", "objective": "Rehang the harbour-bell and light the true sea-beacon at Dagon Fel"},
                        {"id": 30, "place": "dagon_fel", "objective": "Share the peace-feast between the Nord whalers and Dunmer scouts at Dagon Fel"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "sanctus_shrine", "text": "Have the recovered harbour-bell blessed in silence at Sanctus Shrine"},
                        {"type": "go", "place": "dagon_fel", "text": "Rehang the harbour-bell and light the true sea-beacon at Dagon Fel"},
                        {"type": "go", "place": "dagon_fel", "text": "Share the peace-feast between the Nord whalers and Dunmer scouts at Dagon Fel"},
                    ],
                    "consequence": {
                        "world_state": "dagon_fel_true_beacon_lit",
                        "flag": "story_sh_finale_complete",
                        "summary": "The true beacon shines over Dagon Fel, ships pass the Sheogorad reefs safely, and peace holds between the village and the Urshilaku.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Beacon-Keeper of Sheogorad",
                        "unique_item": "skycraft:nordic_war_axe",
                    },
                },
            ],
        },
        "vvardenfell_ashlands": {
            "region": "morrowind/vvardenfell_ashlands",
            "province": "morrowind",
            "line": "story_vvardenfell_ashlands",
            "category": "main",
            "title": "The Dreamers Under Skar",
            "why": [
                "morrowind/vvardenfell_ashlands.L7.redoran_duty",
                "morrowind/vvardenfell_ashlands.L8.redoran_temple_piety",
                "morrowind/vvardenfell_ashlands.L9.council_of_ald_ruhn",
                "morrowind/vvardenfell_ashlands.L9.sleepers_in_the_north",
                "morrowind/vvardenfell_ashlands.L10.kogoruhn",
            ],
            "hook": {
                "place": "aldruhn",
                "rumour": "Beneath the giant emperor-crab shell of Skar in Ald'ruhn, Redoran guards whisper that ash-caravans from Gnisis and Maar Gan have found red-wax Sixth House bells hidden inside supply crates.",
                "notice": "REDORAN COUNCIL PROCLAMATION AT ALD'RUHN — Outlanders and retainers summoned to investigate the Sleeper caches along the ash-road between Ald'ruhn, Maar Gan, and Kogoruhn.",
                "context": "House Redoran's honor demands that Ald'ruhn hold the northern ash-marches against the blight, even while Archmaster Venim and Councilor Sarethi clash in the council chambers.",
            },
            "quests": [
                {
                    "id": "story_vvardenfell_ashlands_01_bells_in_the_ash",
                    "order": 1,
                    "title": "Chimes Under the Crab-Shell",
                    "region": "morrowind/vvardenfell_ashlands",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/vvardenfell_ashlands.L7.redoran_duty",
                        "morrowind/vvardenfell_ashlands.L9.council_of_ald_ruhn",
                    ],
                    "places": hook_places["vvardenfell_ashlands"],
                    "stages": [
                        {"id": 10, "place": "aldruhn", "objective": "Receive your briefing from Councilor Sarethi's clerks beneath Skar in Ald'ruhn"},
                        {"id": 20, "place": "maar_gan", "objective": "Inspect the quarantined guar-sheds and shrine stones at Maar Gan"},
                        {"id": 30, "place": "gnisis", "objective": "Question the Legion and Redoran patrol captains at Gnisis about the northern ash-tracks"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "aldruhn", "text": "Receive your briefing from Councilor Sarethi's clerks beneath Skar in Ald'ruhn"},
                        {"type": "go", "place": "maar_gan", "text": "Inspect the quarantined guar-sheds and shrine stones at Maar Gan"},
                        {"type": "go", "place": "gnisis", "text": "Question the Legion and Redoran patrol captains at Gnisis about the northern ash-tracks"},
                    ],
                    "consequence": {
                        "world_state": "ashlands_sleeper_crates_traced",
                        "flag": "story_va_crates_traced",
                        "summary": "Inspections at Maar Gan and Gnisis trace the ash-salted crates to a Sixth House relay cell inside the Dunmer stronghold of Falasmaryon.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_vvardenfell_ashlands_02_relay_at_falasmaryon",
                    "order": 2,
                    "title": "The Ash-Relay of Falasmaryon",
                    "region": "morrowind/vvardenfell_ashlands",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_vvardenfell_ashlands_01_bells_in_the_ash",
                    "requires": ["quest:story_vvardenfell_ashlands_01_bells_in_the_ash"],
                    "why": [
                        "morrowind/vvardenfell_ashlands.L8.redoran_temple_piety",
                        "morrowind/vvardenfell_ashlands.L9.sleepers_in_the_north",
                    ],
                    "places": ["bal_isra", "falasmaryon", "andasreth"],
                    "stages": [
                        {"id": 10, "place": "bal_isra", "objective": "Muster with the Redoran outriders on the ridge at Bal Isra"},
                        {"id": 20, "place": "falasmaryon", "objective": "Clear the Sleeper cell inside Falasmaryon and shatter their red-wax chime rack"},
                        {"id": 30, "place": "andasreth", "objective": "Check the ancient propylon chamber at Andasreth for the cult's retreat route"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "bal_isra", "text": "Muster with the Redoran outriders on the ridge at Bal Isra"},
                        {"type": "clear", "place": "falasmaryon", "text": "Clear the Sleeper cell inside Falasmaryon and shatter their red-wax chime rack"},
                        {"type": "go", "place": "andasreth", "text": "Check the ancient propylon chamber at Andasreth for the cult's retreat route"},
                    ],
                    "consequence": {
                        "world_state": "falasmaryon_chime_rack_shattered",
                        "flag": "story_va_falasmaryon_cleared",
                        "summary": "The chime rack at Falasmaryon is shattered, and tracks at Andasreth confirm an Ash-Priest is waking a blight-choir inside Kogoruhn.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_vvardenfell_ashlands_03_hall_of_kogoruhn",
                    "order": 3,
                    "title": "The Bleeding Heart of Kogoruhn",
                    "region": "morrowind/vvardenfell_ashlands",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_vvardenfell_ashlands_02_relay_at_falasmaryon",
                    "requires": ["quest:story_vvardenfell_ashlands_02_relay_at_falasmaryon"],
                    "why": [
                        "morrowind/vvardenfell_ashlands.L8.urshilaku_prophecy",
                        "morrowind/vvardenfell_ashlands.L10.kogoruhn",
                    ],
                    "places": ["maar_gan", "kogoruhn", "buckmoth_legion_fort"],
                    "stages": [
                        {"id": 10, "place": "maar_gan", "objective": "Receive the pilgrim's ash-blessing at the Maar Gan shrine before crossing into the blight-wind"},
                        {"id": 20, "place": "kogoruhn", "objective": "Enter the Sixth House citadel of Kogoruhn and slay the Ash-Priest of the Northern Choir"},
                        {"id": 30, "place": "buckmoth_legion_fort", "objective": "Deliver the captured Sixth House battle-plan to the garrison at Buckmoth Legion Fort"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "maar_gan", "text": "Receive the pilgrim's ash-blessing at the Maar Gan shrine before crossing into the blight-wind"},
                        {"type": "clear", "place": "kogoruhn", "text": "Enter the Sixth House citadel of Kogoruhn and slay the Ash-Priest of the Northern Choir"},
                        {"type": "go", "place": "buckmoth_legion_fort", "text": "Deliver the captured Sixth House battle-plan to the garrison at Buckmoth Legion Fort"},
                    ],
                    "consequence": {
                        "world_state": "kogoruhn_northern_choir_silenced",
                        "flag": "story_va_kogoruhn_cleared",
                        "summary": "The Ash-Priest in Kogoruhn is slain and the dream-transmissions plaguing Ald'ruhn and Maar Gan cease.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_vvardenfell_ashlands_04_oath_under_skar",
                    "order": 4,
                    "title": "The Redoran Oath Under Skar",
                    "region": "morrowind/vvardenfell_ashlands",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_vvardenfell_ashlands_03_hall_of_kogoruhn",
                    "requires": ["quest:story_vvardenfell_ashlands_03_hall_of_kogoruhn"],
                    "finale": True,
                    "why": [
                        "morrowind/vvardenfell_ashlands.L7.ashlander_traders_at_the_gate",
                        "morrowind/vvardenfell_ashlands.L9.council_of_ald_ruhn",
                    ],
                    "places": ["skar", "aldruhn"],
                    "stages": [
                        {"id": 10, "place": "skar", "objective": "Lay the shattered Ash-Priest bell before the Redoran Council Hall inside Skar"},
                        {"id": 20, "place": "aldruhn", "objective": "Witness the Ashlander caravan-truce renewed at the western gate of Ald'ruhn"},
                        {"id": 30, "place": "aldruhn", "objective": "Receive the Helm-Salute of the Redoran Council in Ald'ruhn"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "skar", "text": "Lay the shattered Ash-Priest bell before the Redoran Council Hall inside Skar"},
                        {"type": "go", "place": "aldruhn", "text": "Witness the Ashlander caravan-truce renewed at the western gate of Ald'ruhn"},
                        {"type": "go", "place": "aldruhn", "text": "Receive the Helm-Salute of the Redoran Council in Ald'ruhn"},
                    ],
                    "consequence": {
                        "world_state": "aldruhn_skar_council_united",
                        "flag": "story_va_finale_complete",
                        "summary": "With Kogoruhn's northern choir silenced, Ald'ruhn's council unites behind the ash-road defense and reopens the Ashlander trade gate.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Shield-Brother of Skar",
                        "unique_item": "skycraft:ebony_warhammer",
                    },
                },
            ],
        },
        "grazelands": {
            "region": "morrowind/grazelands",
            "province": "morrowind",
            "line": "story_grazelands",
            "category": "main",
            "title": "The Blighted Pastures of Vos",
            "why": [
                "morrowind/grazelands.L7.nomads_vs_farmers",
                "morrowind/grazelands.L8.ashlander_herd_rites",
                "morrowind/grazelands.L9.grass_rights",
                "morrowind/grazelands.L10.blighted_egg_mines",
                "morrowind/grazelands.L10.dwemer_and_sixth_house",
            ],
            "hook": {
                "place": "vos",
                "rumour": "In the farming village of Vos beneath Tel Vos's twisted roots, wickwheat growers and Zainab scouts accuse each other of poisoning the grazing springs while kwama mines go rabid.",
                "notice": "DECREE OF MASTER ARYON AT TEL VOS — Investigators sought to examine the fouled watering-runs between Vos, Zainab Camp, and Mudan-Mul Egg Mine.",
                "context": "The Grazelands are Vvardenfell's golden breadbasket, shared uneasily between Master Aryon's progressive Telvanni retainers at Vos and the wealthy Zainab and Ahemmusa Ashlander clans.",
            },
            "quests": [
                {
                    "id": "story_grazelands_01_bitter_springs_of_vos",
                    "order": 1,
                    "title": "Bitter Water in the Wickwheat",
                    "region": "morrowind/grazelands",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/grazelands.L7.nomads_vs_farmers",
                        "morrowind/grazelands.L9.grass_rights",
                    ],
                    "places": hook_places["grazelands"],
                    "stages": [
                        {"id": 10, "place": "vos", "objective": "Speak with the village elders at Vos about the blighted irrigation channels"},
                        {"id": 20, "place": "tel_vos", "objective": "Consult Master Aryon's steward in the Imperial-fungal hybrid tower of Tel Vos"},
                        {"id": 30, "place": "zainab_camp", "objective": "Parley with the Zainab herd-wardens at Zainab Camp regarding the poisoned guar-wallows"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "vos", "text": "Speak with the village elders at Vos about the blighted irrigation channels"},
                        {"type": "go", "place": "tel_vos", "text": "Consult Master Aryon's steward in the Imperial-fungal hybrid tower of Tel Vos"},
                        {"type": "go", "place": "zainab_camp", "text": "Parley with the Zainab herd-wardens at Zainab Camp regarding the poisoned guar-wallows"},
                    ],
                    "consequence": {
                        "world_state": "grazelands_water_poisoning_traced",
                        "flag": "story_gl_water_traced",
                        "summary": "Both Vos farmers and Zainab herders are cleared of blame when alchemical sediment in the springs is traced to Mudan-Mul Egg Mine.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_grazelands_02_fever_in_mudan_mul",
                    "order": 2,
                    "title": "Fever in Mudan-Mul Egg Mine",
                    "region": "morrowind/grazelands",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_grazelands_01_bitter_springs_of_vos",
                    "requires": ["quest:story_grazelands_01_bitter_springs_of_vos"],
                    "why": [
                        "morrowind/grazelands.L8.ashlander_herd_rites",
                        "morrowind/grazelands.L10.blighted_egg_mines",
                    ],
                    "places": ["mudan_mul_egg_mine", "ahemmusa_camp", "tel_mora"],
                    "stages": [
                        {"id": 10, "place": "mudan_mul_egg_mine", "objective": "Clear the blighted kwama warriors inside Mudan-Mul Egg Mine and seal the leaking Dwemer pipe"},
                        {"id": 20, "place": "ahemmusa_camp", "objective": "Seek the Wise Woman's counsel at Ahemmusa Camp on the source of the subterranean conduit"},
                        {"id": 30, "place": "tel_mora", "objective": "Obtain a brass valve-key from the apothecaries of Tel Mora"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "mudan_mul_egg_mine", "text": "Clear the blighted kwama warriors inside Mudan-Mul Egg Mine and seal the leaking Dwemer pipe"},
                        {"type": "go", "place": "ahemmusa_camp", "text": "Seek the Wise Woman's counsel at Ahemmusa Camp on the source of the subterranean conduit"},
                        {"type": "go", "place": "tel_mora", "text": "Obtain a brass valve-key from the apothecaries of Tel Mora"},
                    ],
                    "consequence": {
                        "world_state": "mudan_mul_pipe_capped",
                        "flag": "story_gl_mudan_mul_cleared",
                        "summary": "The leaking pipe in Mudan-Mul Egg Mine is capped, and the Tel Mora valve-key points to the Dwemer pumping vaults of Nchardahrk.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_grazelands_03_pumps_of_nchardahrk",
                    "order": 3,
                    "title": "The Sluice of Nchardahrk",
                    "region": "morrowind/grazelands",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_grazelands_02_fever_in_mudan_mul",
                    "requires": ["quest:story_grazelands_02_fever_in_mudan_mul"],
                    "why": [
                        "morrowind/grazelands.L10.blighted_egg_mines",
                        "morrowind/grazelands.L10.dwemer_and_sixth_house",
                    ],
                    "places": ["nchardahrk", "mzuleft", "ald_daedroth"],
                    "stages": [
                        {"id": 10, "place": "nchardahrk", "objective": "Enter the flooded Dwemer halls of Nchardahrk and shut down the corrupted blight-cistern"},
                        {"id": 20, "place": "mzuleft", "objective": "Clear the cultist saboteurs camped in the outer gallery of Mzuleft"},
                        {"id": 30, "place": "ald_daedroth", "objective": "Recover the stolen Zainab grazing-totem from the shore ruins of Ald Daedroth"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "nchardahrk", "text": "Enter the flooded Dwemer halls of Nchardahrk and shut down the corrupted blight-cistern"},
                        {"type": "clear", "place": "mzuleft", "text": "Clear the cultist saboteurs camped in the outer gallery of Mzuleft"},
                        {"type": "go", "place": "ald_daedroth", "text": "Recover the stolen Zainab grazing-totem from the shore ruins of Ald Daedroth"},
                    ],
                    "consequence": {
                        "world_state": "nchardahrk_cistern_purified",
                        "flag": "story_gl_nchardahrk_cleared",
                        "summary": "The corrupted Dwemer cistern at Nchardahrk is shut down and the stolen Zainab grazing-totem is recovered from Ald Daedroth.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_grazelands_04_compact_of_the_savannah",
                    "order": 4,
                    "title": "Compact of the Golden Grass",
                    "region": "morrowind/grazelands",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_grazelands_03_pumps_of_nchardahrk",
                    "requires": ["quest:story_grazelands_03_pumps_of_nchardahrk"],
                    "finale": True,
                    "why": [
                        "morrowind/grazelands.L7.nomads_vs_farmers",
                        "morrowind/grazelands.L9.grass_rights",
                    ],
                    "places": ["zainab_camp", "tel_vos", "vos"],
                    "stages": [
                        {"id": 10, "place": "zainab_camp", "objective": "Return the grazing-totem to Ashkhan Kaushad's hearth at Zainab Camp"},
                        {"id": 20, "place": "tel_vos", "objective": "Witness Master Aryon's Mouth and the Ashlander envoys seal the Savannah Grazing Compact at Tel Vos"},
                        {"id": 30, "place": "vos", "objective": "Celebrate the reopening of the clean cistern-wells with the farmers of Vos"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "zainab_camp", "text": "Return the grazing-totem to Ashkhan Kaushad's hearth at Zainab Camp"},
                        {"type": "go", "place": "tel_vos", "text": "Witness Master Aryon's Mouth and the Ashlander envoys seal the Savannah Grazing Compact at Tel Vos"},
                        {"type": "go", "place": "vos", "text": "Celebrate the reopening of the clean cistern-wells with the farmers of Vos"},
                    ],
                    "consequence": {
                        "world_state": "vos_savannah_compact_sealed",
                        "flag": "story_gl_finale_complete",
                        "summary": "Clean water flows once more into Vos's wickwheat terraces, and the Savannah Grazing Compact binds Tel Vos and the Ashlander clans in peace.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Peace-Speaker of the Grazelands",
                        "unique_item": "skycraft:glass_bow",
                    },
                },
            ],
        },
        "azuras_coast": {
            "region": "morrowind/azuras_coast",
            "province": "morrowind",
            "line": "story_azuras_coast",
            "category": "main",
            "title": "The Twilight Lantern of Holamayan",
            "why": [
                "morrowind/azuras_coast.L7.wizard_and_servant",
                "morrowind/azuras_coast.L8.azura_shrine",
                "morrowind/azuras_coast.L8.dreamer_monastery",
                "morrowind/azuras_coast.L9.telvanni_rivalries",
                "morrowind/azuras_coast.L9.corprus_hospital",
            ],
            "hook": {
                "place": "molag_mar",
                "rumour": "At the Buoyant Armiger canton of Molag Mar, pilgrims bound for Mount Assarnibibi report that renegade inquisitors and Tel Aruhn slavers have blockaded the sea-coving toward Holamayan while corprus remedies vanish on the road to Tel Fyr.",
                "notice": "MOLAG MAR PILGRIM GARRISON — Escorts and investigators sought to secure the coastal shrines between Molag Mar, Tel Fyr, and the eastern headlands.",
                "context": "Azura's Coast is a labyrinth of basalt headlands where Buoyant Armigers, Dissident Priests at Holamayan, Divayth Fyr's corprusarium, and Telvanni lords watch each other across the fog.",
            },
            "quests": [
                {
                    "id": "story_azuras_coast_01_pilgrims_at_molag_mar",
                    "order": 1,
                    "title": "Alms and Armigers at Molag Mar",
                    "region": "morrowind/azuras_coast",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/azuras_coast.L7.wizard_and_servant",
                        "morrowind/azuras_coast.L9.corprus_hospital",
                    ],
                    "places": hook_places["azuras_coast"],
                    "stages": [
                        {"id": 10, "place": "molag_mar", "objective": "Speak with the Buoyant Armiger captain at Molag Mar about the missing medical skiffs"},
                        {"id": 20, "place": "mount_assarnibibi", "objective": "Check the pilgrim way-cairn atop Mount Assarnibibi for signs of the ambushed courier"},
                        {"id": 30, "place": "tel_fyr", "objective": "Deliver the recovered medicine tally to Divayth Fyr's tower at Tel Fyr"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "molag_mar", "text": "Speak with the Buoyant Armiger captain at Molag Mar about the missing medical skiffs"},
                        {"type": "go", "place": "mount_assarnibibi", "text": "Check the pilgrim way-cairn atop Mount Assarnibibi for signs of the ambushed courier"},
                        {"type": "go", "place": "tel_fyr", "text": "Deliver the recovered medicine tally to Divayth Fyr's tower at Tel Fyr"},
                    ],
                    "consequence": {
                        "world_state": "tel_fyr_medicine_tally_delivered",
                        "flag": "story_ac_tally_delivered",
                        "summary": "Divayth Fyr's stewards confirm the missing alchemical crates were diverted by rogue factors operating out of Tel Aruhn and Telasero.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_azuras_coast_02_rivals_of_tel_aruhn",
                    "order": 2,
                    "title": "Shadow Over Tel Aruhn",
                    "region": "morrowind/azuras_coast",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_azuras_coast_01_pilgrims_at_molag_mar",
                    "requires": ["quest:story_azuras_coast_01_pilgrims_at_molag_mar"],
                    "why": [
                        "morrowind/azuras_coast.L9.telvanni_rivalries",
                        "morrowind/azuras_coast.L10.daedric_headland_ruins",
                    ],
                    "places": ["tel_aruhn", "tel_uvirith", "telasero"],
                    "stages": [
                        {"id": 10, "place": "tel_aruhn", "objective": "Question the dock-masters at Tel Aruhn regarding the seized Holamayan skiff"},
                        {"id": 20, "place": "tel_uvirith", "objective": "Inspect the lava-plain relay post at Tel Uvirith"},
                        {"id": 30, "place": "telasero", "objective": "Clear the smuggler-mercenaries inside the stronghold of Telasero and recover the Twilight Compass"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "tel_aruhn", "text": "Question the dock-masters at Tel Aruhn regarding the seized Holamayan skiff"},
                        {"type": "go", "place": "tel_uvirith", "text": "Inspect the lava-plain relay post at Tel Uvirith"},
                        {"type": "clear", "place": "telasero", "text": "Clear the smuggler-mercenaries inside the stronghold of Telasero and recover the Twilight Compass"},
                    ],
                    "consequence": {
                        "world_state": "telasero_twilight_compass_recovered",
                        "flag": "story_ac_compass_recovered",
                        "summary": "The Twilight Compass is recovered from Telasero, allowing safe navigation to the hidden monastery of Holamayan at dusk.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_azuras_coast_03_cloister_at_dusk",
                    "order": 3,
                    "title": "The Cloister of Holamayan",
                    "region": "morrowind/azuras_coast",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_azuras_coast_02_rivals_of_tel_aruhn",
                    "requires": ["quest:story_azuras_coast_02_rivals_of_tel_aruhn"],
                    "why": [
                        "morrowind/azuras_coast.L8.azura_shrine",
                        "morrowind/azuras_coast.L8.dreamer_monastery",
                    ],
                    "places": ["holamayan", "indoranyon", "galom_daeus"],
                    "stages": [
                        {"id": 10, "place": "holamayan", "objective": "Deliver the Twilight Compass to Master Gilvas Barelo at Holamayan Monastery"},
                        {"id": 20, "place": "indoranyon", "objective": "Drive off the rogue inquisitor band encamped in the ruins of Indoranyon"},
                        {"id": 30, "place": "galom_daeus", "objective": "Retrieve the stolen Star-Lamp of Azura from the observatory vaults of Galom Daeus"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "holamayan", "text": "Deliver the Twilight Compass to Master Gilvas Barelo at Holamayan Monastery"},
                        {"type": "clear", "place": "indoranyon", "text": "Drive off the rogue inquisitor band encamped in the ruins of Indoranyon"},
                        {"type": "clear", "place": "galom_daeus", "text": "Retrieve the stolen Star-Lamp of Azura from the observatory vaults of Galom Daeus"},
                    ],
                    "consequence": {
                        "world_state": "azuras_coast_star_lamp_reclaimed",
                        "flag": "story_ac_star_lamp_reclaimed",
                        "summary": "The Star-Lamp of Azura is reclaimed from Galom Daeus and the sea-channel to Holamayan is secured.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_azuras_coast_04_dawn_and_dusk_truce",
                    "order": 4,
                    "title": "Vigil of Moonshadow",
                    "region": "morrowind/azuras_coast",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_azuras_coast_03_cloister_at_dusk",
                    "requires": ["quest:story_azuras_coast_03_cloister_at_dusk"],
                    "finale": True,
                    "why": [
                        "morrowind/azuras_coast.L8.azura_shrine",
                        "morrowind/azuras_coast.L9.corprus_hospital",
                    ],
                    "places": ["holamayan", "tel_fyr", "molag_mar"],
                    "stages": [
                        {"id": 10, "place": "holamayan", "objective": "Relight the Star-Lamp above the sea-gate of Holamayan Monastery"},
                        {"id": 20, "place": "tel_fyr", "objective": "Confirm the safe arrival of the alchemical supply skiff at Tel Fyr"},
                        {"id": 30, "place": "molag_mar", "objective": "Report the coastal truce to the Buoyant Armiger Commander at Molag Mar"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "holamayan", "text": "Relight the Star-Lamp above the sea-gate of Holamayan Monastery"},
                        {"type": "go", "place": "tel_fyr", "text": "Confirm the safe arrival of the alchemical supply skiff at Tel Fyr"},
                        {"type": "go", "place": "molag_mar", "text": "Report the coastal truce to the Buoyant Armiger Commander at Molag Mar"},
                    ],
                    "consequence": {
                        "world_state": "azuras_coast_twilight_truce_sealed",
                        "flag": "story_ac_finale_complete",
                        "summary": "The Star-Lamp burns above Holamayan, medical skiffs reach Tel Fyr unhindered, and Molag Mar's Armigers honor the coastal truce.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Star-Warden of Azura's Coast",
                        "unique_item": "skycraft:glass_greatsword",
                    },
                },
            ],
        },
        "west_gash": {
            "region": "morrowind/west_gash",
            "province": "morrowind",
            "line": "story_west_gash",
            "category": "main",
            "title": "Two Seals on the Caldera Seam",
            "why": [
                "morrowind/west_gash.L7.merchant_city",
                "morrowind/west_gash.L7.dockside_syndicate",
                "morrowind/west_gash.L7.outlander_miners",
                "morrowind/west_gash.L9.hlaalu_collaboration",
                "morrowind/west_gash.L9.mine_ownership_fight",
                "morrowind/west_gash.L10.smuggled_ebony",
            ],
            "hook": {
                "place": "balmora",
                "rumour": "Along the Odai River in Balmora, Hlaalu council clerks and Moonmoth Legion officers trade accusations over missing raw ebony shipments from Caldera while Camonna Tong toughs watch from the cornerclubs.",
                "notice": "HLAALU COUNCIL MANOR, BALMORA — Investigators commissioned to audit the missing ebony tallies between Balmora, Caldera, and Moonmoth Legion Fort.",
                "context": "The West Gash is House Hlaalu's commercial heartland: Imperial charter-towns at Caldera and Legion forts at Moonmoth enrich Balmora's nobles while feeding bitter nationalist resentment.",
            },
            "quests": [
                {
                    "id": "story_west_gash_01_ledgers_on_the_odai",
                    "order": 1,
                    "title": "Ledgers on the Odai River",
                    "region": "morrowind/west_gash",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/west_gash.L7.merchant_city",
                        "morrowind/west_gash.L9.hlaalu_collaboration",
                    ],
                    "places": hook_places["west_gash"],
                    "stages": [
                        {"id": 10, "place": "balmora", "objective": "Inspect the disputed assay books at Hlaalu Council Manor in Balmora"},
                        {"id": 20, "place": "moonmoth_legion_fort", "objective": "Verify the Imperial escort logs with the knight-captain at Moonmoth Legion Fort"},
                        {"id": 30, "place": "caldera", "objective": "Question the magistrate's clerks inside the Tudor guild-hall at Caldera"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "balmora", "text": "Inspect the disputed assay books at Hlaalu Council Manor in Balmora"},
                        {"type": "go", "place": "moonmoth_legion_fort", "text": "Verify the Imperial escort logs with the knight-captain at Moonmoth Legion Fort"},
                        {"type": "go", "place": "caldera", "text": "Question the magistrate's clerks inside the Tudor guild-hall at Caldera"},
                    ],
                    "consequence": {
                        "world_state": "west_gash_assay_tampering_exposed",
                        "flag": "story_wg_assay_exposed",
                        "summary": "Comparing Balmora, Moonmoth, and Caldera records proves every third ebony wain was diverted down the western trail toward Hla Oad.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_west_gash_02_contraband_at_caldera",
                    "order": 2,
                    "title": "The Night-Shift at Caldera Mine",
                    "region": "morrowind/west_gash",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_west_gash_01_ledgers_on_the_odai",
                    "requires": ["quest:story_west_gash_01_ledgers_on_the_odai"],
                    "why": [
                        "morrowind/west_gash.L7.outlander_miners",
                        "morrowind/west_gash.L9.mine_ownership_fight",
                    ],
                    "places": ["caldera_mine", "rethan_manor", "hla_oad"],
                    "stages": [
                        {"id": 10, "place": "caldera_mine", "objective": "Inspect the hidden ore-chute at Caldera Mine and question the night-shift foreman"},
                        {"id": 20, "place": "rethan_manor", "objective": "Check the Odai river-barge moorings below Rethan Manor"},
                        {"id": 30, "place": "hla_oad", "objective": "Intercept the smuggler skiff crew at the stilts of Hla Oad"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "caldera_mine", "text": "Inspect the hidden ore-chute at Caldera Mine and question the night-shift foreman"},
                        {"type": "go", "place": "rethan_manor", "text": "Check the Odai river-barge moorings below Rethan Manor"},
                        {"type": "clear", "place": "hla_oad", "text": "Intercept the smuggler skiff crew at the stilts of Hla Oad"},
                    ],
                    "consequence": {
                        "world_state": "hla_oad_ebony_skiff_seized",
                        "flag": "story_wg_skiff_seized",
                        "summary": "The smuggler skiff at Hla Oad is seized, revealing that the stolen ebony is stored inside the stronghold of Hlormaren and Bthanchend.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_west_gash_03_vault_of_hlormaren",
                    "order": 3,
                    "title": "Iron and Ebony at Hlormaren",
                    "region": "morrowind/west_gash",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_west_gash_02_contraband_at_caldera",
                    "requires": ["quest:story_west_gash_02_contraband_at_caldera"],
                    "why": [
                        "morrowind/west_gash.L7.dockside_syndicate",
                        "morrowind/west_gash.L10.smuggled_ebony",
                    ],
                    "places": ["hlormaren", "bthanchend", "odai_plateau"],
                    "stages": [
                        {"id": 10, "place": "hlormaren", "objective": "Storm the coastal stronghold of Hlormaren and defeat the syndicate factor's guards"},
                        {"id": 20, "place": "bthanchend", "objective": "Recover the stamped Imperial ebony ingots hidden inside the Dwemer gallery at Bthanchend"},
                        {"id": 30, "place": "odai_plateau", "objective": "Deliver the recovered ingots to the Hlaalu escrow post at Odai Plateau"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "hlormaren", "text": "Storm the coastal stronghold of Hlormaren and defeat the syndicate factor's guards"},
                        {"type": "clear", "place": "bthanchend", "text": "Recover the stamped Imperial ebony ingots hidden inside the Dwemer gallery at Bthanchend"},
                        {"type": "go", "place": "odai_plateau", "text": "Deliver the recovered ingots to the Hlaalu escrow post at Odai Plateau"},
                    ],
                    "consequence": {
                        "world_state": "hlormaren_ebony_ring_broken",
                        "flag": "story_wg_hlormaren_cleared",
                        "summary": "The stolen Caldera ebony ingots are recovered from Hlormaren and Bthanchend and placed under guard at Odai Plateau.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_west_gash_04_settlement_at_balmora",
                    "order": 4,
                    "title": "The Odai Charter Settlement",
                    "region": "morrowind/west_gash",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_west_gash_03_vault_of_hlormaren",
                    "requires": ["quest:story_west_gash_03_vault_of_hlormaren"],
                    "finale": True,
                    "why": [
                        "morrowind/west_gash.L8.pragmatic_piety",
                        "morrowind/west_gash.L9.mine_ownership_fight",
                    ],
                    "places": ["caldera", "balmora"],
                    "stages": [
                        {"id": 10, "place": "caldera", "objective": "File the restored assay rolls at the Magistrate's Hall in Caldera"},
                        {"id": 20, "place": "balmora", "objective": "Present the signed Caldera-Balmora Assay Compact at Hlaalu Council Manor in Balmora"},
                        {"id": 30, "place": "balmora", "objective": "Receive your factor's share and title on the bridges of Balmora"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "caldera", "text": "File the restored assay rolls at the Magistrate's Hall in Caldera"},
                        {"type": "go", "place": "balmora", "text": "Present the signed Caldera-Balmora Assay Compact at Hlaalu Council Manor in Balmora"},
                        {"type": "go", "place": "balmora", "text": "Receive your factor's share and title on the bridges of Balmora"},
                    ],
                    "consequence": {
                        "world_state": "balmora_caldera_assay_compact_signed",
                        "flag": "story_wg_finale_complete",
                        "summary": "Honest pay and audited ore-weighing resume at Caldera Mine, and Balmora's Council Manor honors you as Factor-Inspector of the Odai.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Factor-Inspector of the Odai",
                        "unique_item": "skycraft:glass_dagger",
                    },
                },
            ],
        },
        "bitter_coast": {
            "region": "morrowind/bitter_coast",
            "province": "morrowind",
            "line": "story_bitter_coast",
            "category": "main",
            "title": "Lanterns in the Mangrove Mire",
            "why": [
                "morrowind/bitter_coast.L7.smugglers_and_tax_men",
                "morrowind/bitter_coast.L8.bitter_superstitions",
                "morrowind/bitter_coast.L9.customs_corruption",
                "morrowind/bitter_coast.L10.smuggler_caves",
                "morrowind/bitter_coast.L10.daedric_mire_ruins",
            ],
            "hook": {
                "place": "gnaar_mok",
                "rumour": "On the wooden boardwalks of Gnaar Mok, net-fishers whisper that an Imperial revenue cutter vanished in the fog off Khartag Point while bribed excise clerks look the other way.",
                "notice": "COASTAL EXCISE NOTICE AT GNAAR MOK — Reward offered for locating the missing revenue cutter crew and auditing the smuggler coves between Gnaar Mok and Zainsipilu.",
                "context": "In the Bitter Coast's mosquito-ridden mangroves, every hollow stump and sea-grotto hides moon-sugar or Dwemer brass, and half the customs patrols are on the smugglers' payroll.",
            },
            "quests": [
                {
                    "id": "story_bitter_coast_01_wreck_at_khartag",
                    "order": 1,
                    "title": "Fog Over Khartag Point",
                    "region": "morrowind/bitter_coast",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/bitter_coast.L7.smugglers_and_tax_men",
                        "morrowind/bitter_coast.L8.bitter_superstitions",
                    ],
                    "places": hook_places["bitter_coast"],
                    "stages": [
                        {"id": 10, "place": "gnaar_mok", "objective": "Question the net-fishers on the stilt-piers of Gnaar Mok about the missing revenue cutter"},
                        {"id": 20, "place": "khartag_point", "objective": "Search the rocky headland at Khartag Point for the cutter's shattered lantern-box"},
                        {"id": 30, "place": "dubdil_alar_tower", "objective": "Inspect the coastal watch-platform at Dubdil Alar Tower for signal-ash"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "gnaar_mok", "text": "Question the net-fishers on the stilt-piers of Gnaar Mok about the missing revenue cutter"},
                        {"type": "go", "place": "khartag_point", "text": "Search the rocky headland at Khartag Point for the cutter's shattered lantern-box"},
                        {"type": "go", "place": "dubdil_alar_tower", "text": "Inspect the coastal watch-platform at Dubdil Alar Tower for signal-ash"},
                    ],
                    "consequence": {
                        "world_state": "khartag_point_cutter_traced",
                        "flag": "story_bc_cutter_traced",
                        "summary": "The shattered lantern-box at Khartag Point proves the revenue cutter was lured into the sea-caves of Nammu and Mannammu by a bribed clerk.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_bitter_coast_02_coves_of_nammu",
                    "order": 2,
                    "title": "The Grottoes of Nammu",
                    "region": "morrowind/bitter_coast",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_bitter_coast_01_wreck_at_khartag",
                    "requires": ["quest:story_bitter_coast_01_wreck_at_khartag"],
                    "why": [
                        "morrowind/bitter_coast.L9.customs_corruption",
                        "morrowind/bitter_coast.L10.smuggler_caves",
                    ],
                    "places": ["nammu", "mannammu", "zainsipilu"],
                    "stages": [
                        {"id": 10, "place": "nammu", "objective": "Clear the smuggler lookouts inside the sea-caverns of Nammu and free the captive oarsmen"},
                        {"id": 20, "place": "mannammu", "objective": "Seize the bribed customs-cipher book from the tide-cache at Mannammu"},
                        {"id": 30, "place": "zainsipilu", "objective": "Track the syndicate's quartermaster to the mangrove grotto of Zainsipilu"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "nammu", "text": "Clear the smuggler lookouts inside the sea-caverns of Nammu and free the captive oarsmen"},
                        {"type": "go", "place": "mannammu", "text": "Seize the bribed customs-cipher book from the tide-cache at Mannammu"},
                        {"type": "go", "place": "zainsipilu", "text": "Track the syndicate's quartermaster to the mangrove grotto of Zainsipilu"},
                    ],
                    "consequence": {
                        "world_state": "nammu_cutter_crew_freed",
                        "flag": "story_bc_nammu_cleared",
                        "summary": "The captive revenue oarsmen are freed from Nammu, and the cipher book reveals the corrupt excise chief hides at Ashalmimilkala.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_bitter_coast_03_altar_in_the_mire",
                    "order": 3,
                    "title": "Sunken Stones of Ashalmimilkala",
                    "region": "morrowind/bitter_coast",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_bitter_coast_02_coves_of_nammu",
                    "requires": ["quest:story_bitter_coast_02_coves_of_nammu"],
                    "why": [
                        "morrowind/bitter_coast.L10.smuggler_caves",
                        "morrowind/bitter_coast.L10.daedric_mire_ruins",
                    ],
                    "places": ["ashalmimilkala", "ashurnibibi", "ulummusa"],
                    "stages": [
                        {"id": 10, "place": "ashalmimilkala", "objective": "Storm the Daedric mire-ruin of Ashalmimilkala and defeat the corrupt excise-master's guard"},
                        {"id": 20, "place": "ashurnibibi", "objective": "Recover the Imperial Excise Brass Seal from the flooded vaults of Ashurnibibi"},
                        {"id": 30, "place": "ulummusa", "objective": "Burn the contraband moon-sugar depot hidden inside Ulummusa"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "ashalmimilkala", "text": "Storm the Daedric mire-ruin of Ashalmimilkala and defeat the corrupt excise-master's guard"},
                        {"type": "clear", "place": "ashurnibibi", "text": "Recover the Imperial Excise Brass Seal from the flooded vaults of Ashurnibibi"},
                        {"type": "clear", "place": "ulummusa", "text": "Burn the contraband moon-sugar depot hidden inside Ulummusa"},
                    ],
                    "consequence": {
                        "world_state": "bitter_coast_excise_seal_recovered",
                        "flag": "story_bc_seal_recovered",
                        "summary": "The corrupt excise-master's ring is broken at Ashalmimilkala and the Imperial Excise Brass Seal is recovered.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_bitter_coast_04_safe_harbour_at_gnaar_mok",
                    "order": 4,
                    "title": "Safe Harbour at Gnaar Mok",
                    "region": "morrowind/bitter_coast",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_bitter_coast_03_altar_in_the_mire",
                    "requires": ["quest:story_bitter_coast_03_altar_in_the_mire"],
                    "finale": True,
                    "why": [
                        "morrowind/bitter_coast.L7.smugglers_and_tax_men",
                        "morrowind/bitter_coast.L9.customs_corruption",
                    ],
                    "places": ["khartag_point", "gnaar_mok"],
                    "stages": [
                        {"id": 10, "place": "khartag_point", "objective": "Relight the true coast-lantern atop Khartag Point"},
                        {"id": 20, "place": "gnaar_mok", "objective": "Return the Imperial Excise Brass Seal to the freed cutter captain at Gnaar Mok"},
                        {"id": 30, "place": "gnaar_mok", "objective": "Accept the gratitude of Gnaar Mok's fishers on the boardwalk pier"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "khartag_point", "text": "Relight the true coast-lantern atop Khartag Point"},
                        {"type": "go", "place": "gnaar_mok", "text": "Return the Imperial Excise Brass Seal to the freed cutter captain at Gnaar Mok"},
                        {"type": "go", "place": "gnaar_mok", "text": "Accept the gratitude of Gnaar Mok's fishers on the boardwalk pier"},
                    ],
                    "consequence": {
                        "world_state": "gnaar_mok_harbour_accord_restored",
                        "flag": "story_bc_finale_complete",
                        "summary": "The Khartag Point beacon shines clearly through the marsh fog and Gnaar Mok's fishers trade free of corrupt shakedowns.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Reef-Warden of the Bitter Coast",
                        "unique_item": "skycraft:glass_sword",
                    },
                },
            ],
        },
        "ascadian_isles": {
            "region": "morrowind/ascadian_isles",
            "province": "morrowind",
            "line": "story_ascadian_isles",
            "category": "main",
            "title": "Two Lamps Beneath the Cantons",
            "why": [
                "morrowind/ascadian_isles.L7.plantation_slaves",
                "morrowind/ascadian_isles.L7.canton_ranks",
                "morrowind/ascadian_isles.L8.vivec_the_living_god",
                "morrowind/ascadian_isles.L9.twin_lamps_underground",
                "morrowind/ascadian_isles.L9.temple_secrets",
                "morrowind/ascadian_isles.L10.under_vivec",
            ],
            "hook": {
                "place": "vivec",
                "rumour": "In the Foreign Quarter and St. Olms canals of Vivec City, chalk drawings of twin lanterns appear beside locked sewer grates while bounty-hunters from Dren Plantation prowl the bridges toward Suran.",
                "notice": "OFFICE OF ARCHCANON SARYONI, HIGH FANE OF VIVEC — Discreet mediator sought to investigate the underworks disturbances between Vivec City, Pelagiad, and Suran.",
                "context": "In the lush Ascadian Isles, the golden cantons of Vivec City rise above slave-worked plantations and a secret abolitionist railroad moving through the underworks toward Seyda Neen.",
            },
            "quests": [
                {
                    "id": "story_ascadian_isles_01_chalk_on_the_cantons",
                    "order": 1,
                    "title": "Chalk Marks on St. Olms Canal",
                    "region": "morrowind/ascadian_isles",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/ascadian_isles.L7.canton_ranks",
                        "morrowind/ascadian_isles.L9.twin_lamps_underground",
                    ],
                    "places": hook_places["ascadian_isles"],
                    "stages": [
                        {"id": 10, "place": "vivec", "objective": "Inspect the twin-lantern chalk marks in the underworks of Vivec City"},
                        {"id": 20, "place": "fields_of_kummu", "objective": "Meet the pilgrim courier waiting beside the muck-shrine at Fields of Kummu"},
                        {"id": 30, "place": "pelagiad", "objective": "Speak with the sympathetic Legion quartermaster inside Fort Pelagiad at Pelagiad"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "vivec", "text": "Inspect the twin-lantern chalk marks in the underworks of Vivec City"},
                        {"type": "go", "place": "fields_of_kummu", "text": "Meet the pilgrim courier waiting beside the muck-shrine at Fields of Kummu"},
                        {"type": "go", "place": "pelagiad", "text": "Speak with the sympathetic Legion quartermaster inside Fort Pelagiad at Pelagiad"},
                    ],
                    "consequence": {
                        "world_state": "ascadian_twin_lamps_route_found",
                        "flag": "story_ai_route_found",
                        "summary": "The courier at Fields of Kummu and the quartermaster at Pelagiad reveal that a Camonna Tong informant has sold out a Twin Lamps safe-route near Dren Plantation.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_ascadian_isles_02_hounds_of_the_plantations",
                    "order": 2,
                    "title": "Night-Watch at Dren Plantation",
                    "region": "morrowind/ascadian_isles",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_ascadian_isles_01_chalk_on_the_cantons",
                    "requires": ["quest:story_ascadian_isles_01_chalk_on_the_cantons"],
                    "why": [
                        "morrowind/ascadian_isles.L7.plantation_slaves",
                        "morrowind/ascadian_isles.L10.escaped_slave_caves",
                    ],
                    "places": ["dren_plantation", "arvel_plantation", "suran"],
                    "stages": [
                        {"id": 10, "place": "dren_plantation", "objective": "Slip into the guard-house at Dren Plantation and seize the slave-hunters' ambush roster"},
                        {"id": 20, "place": "arvel_plantation", "objective": "Free the detained Khajiit and Argonian labourers held at Arvel Plantation"},
                        {"id": 30, "place": "suran", "objective": "Warn the Twin Lamps ferryman waiting by the river-landing at Suran"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "dren_plantation", "text": "Slip into the guard-house at Dren Plantation and seize the slave-hunters' ambush roster"},
                        {"type": "go", "place": "arvel_plantation", "text": "Free the detained Khajiit and Argonian labourers held at Arvel Plantation"},
                        {"type": "go", "place": "suran", "text": "Warn the Twin Lamps ferryman waiting by the river-landing at Suran"},
                    ],
                    "consequence": {
                        "world_state": "suran_ferry_route_warned",
                        "flag": "story_ai_ferry_warned",
                        "summary": "The ambush roster is seized from Dren Plantation, the labourers at Arvel Plantation are freed, and the Suran ferryman redirects the refugees.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_ascadian_isles_03_sanctuary_under_vivec",
                    "order": 3,
                    "title": "The Underworks of St. Delyn",
                    "region": "morrowind/ascadian_isles",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_ascadian_isles_02_hounds_of_the_plantations",
                    "requires": ["quest:story_ascadian_isles_02_hounds_of_the_plantations"],
                    "why": [
                        "morrowind/ascadian_isles.L9.temple_secrets",
                        "morrowind/ascadian_isles.L10.under_vivec",
                    ],
                    "places": ["marandus", "seyda_neen", "castle_ebonheart"],
                    "stages": [
                        {"id": 10, "place": "marandus", "objective": "Defeat the hired syndicate manhunters blocking the hill-pass at Marandus"},
                        {"id": 20, "place": "seyda_neen", "objective": "Secure the outbound ship-papers at the Census and Excise office in Seyda Neen"},
                        {"id": 30, "place": "castle_ebonheart", "objective": "Register the Imperial manumission seals at Castle Ebonheart"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "marandus", "text": "Defeat the hired syndicate manhunters blocking the hill-pass at Marandus"},
                        {"type": "go", "place": "seyda_neen", "text": "Secure the outbound ship-papers at the Census and Excise office in Seyda Neen"},
                        {"type": "go", "place": "castle_ebonheart", "text": "Register the Imperial manumission seals at Castle Ebonheart"},
                    ],
                    "consequence": {
                        "world_state": "seyda_neen_manumission_papers_stamped",
                        "flag": "story_ai_manumission_stamped",
                        "summary": "The manhunters at Marandus are scattered and lawful manumission papers are stamped at Seyda Neen and Castle Ebonheart.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_ascadian_isles_04_graces_of_the_high_fane",
                    "order": 4,
                    "title": "Sanctuary of the Seven Graces",
                    "region": "morrowind/ascadian_isles",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_ascadian_isles_03_sanctuary_under_vivec",
                    "requires": ["quest:story_ascadian_isles_03_sanctuary_under_vivec"],
                    "finale": True,
                    "why": [
                        "morrowind/ascadian_isles.L8.vivec_the_living_god",
                        "morrowind/ascadian_isles.L8.seven_graces",
                    ],
                    "places": ["seyda_neen", "vivec"],
                    "stages": [
                        {"id": 10, "place": "seyda_neen", "objective": "See the freed passengers safely aboard the coastal packet at Seyda Neen"},
                        {"id": 20, "place": "vivec", "objective": "Deliver the manumission register to Archcanon Saryoni at the High Fane in Vivec City"},
                        {"id": 30, "place": "vivec", "objective": "Receive the Pilgrim's Lantern of Grace on the Temple steps of Vivec City"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "seyda_neen", "text": "See the freed passengers safely aboard the coastal packet at Seyda Neen"},
                        {"type": "go", "place": "vivec", "text": "Deliver the manumission register to Archcanon Saryoni at the High Fane in Vivec City"},
                        {"type": "go", "place": "vivec", "text": "Receive the Pilgrim's Lantern of Grace on the Temple steps of Vivec City"},
                    ],
                    "consequence": {
                        "world_state": "vivec_canton_sanctuary_charter_upheld",
                        "flag": "story_ai_finale_complete",
                        "summary": "Archcanon Saryoni upholds the Temple sanctuary charter in Vivec's cantons and the Twin Lamps passage to Seyda Neen remains unbroken.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Lantern-Bearer of the Cantons",
                        "unique_item": "skycraft:ebony_sword",
                    },
                },
            ],
        },
        "red_mountain": {
            "region": "morrowind/red_mountain",
            "province": "morrowind",
            "line": "story_red_mountain",
            "category": "main",
            "title": "The Cracking Pylon of Ghostgate",
            "why": [
                "morrowind/red_mountain.L7.garrison_life",
                "morrowind/red_mountain.L8.fence_of_the_dead",
                "morrowind/red_mountain.L9.fence_weakening",
                "morrowind/red_mountain.L10.sixth_house_citadels",
                "morrowind.L9.ghostfence_holds",
            ],
            "hook": {
                "place": "ghostgate",
                "rumour": "Between the Tower of Dawn and Tower of Dusk at Ghostgate, Buoyant Armigers and Ordinators report that three bone-pylons along the Ghostfence have begun to hum in dissonance with Dagoth Ur's ash-storms.",
                "notice": "GHOSTGATE CITADEL COMMAND — Veteran volunteers required to inspect the bone-pylons along Foyada Ashur-Dan and purge the Sixth House resonators inside the Ghostfence.",
                "context": "Red Mountain is the blighted volcanic heart of Vvardenfell, encircled by the glowing blue Ghostfence built from the consecrated bones of Dunmer ancestors.",
            },
            "quests": [
                {
                    "id": "story_red_mountain_01_dissonance_on_the_fence",
                    "order": 1,
                    "title": "Bone and Blue Fire at Ghostgate",
                    "region": "morrowind/red_mountain",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind/red_mountain.L7.garrison_life",
                        "morrowind/red_mountain.L8.fence_of_the_dead",
                    ],
                    "places": hook_places["red_mountain"],
                    "stages": [
                        {"id": 10, "place": "ghostgate", "objective": "Report to the Armiger captain in the Tower of Dusk at Ghostgate"},
                        {"id": 20, "place": "ghostfence_morrowind", "objective": "Inspect the cracked ancestral bone-pylon along the Ghostfence perimeter"},
                        {"id": 30, "place": "foyada_ashur_dan", "objective": "Clear the ash-ghoul scouting party advancing down Foyada Ashur-Dan"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "ghostgate", "text": "Report to the Armiger captain in the Tower of Dusk at Ghostgate"},
                        {"type": "go", "place": "ghostfence_morrowind", "text": "Inspect the cracked ancestral bone-pylon along the Ghostfence perimeter"},
                        {"type": "clear", "place": "foyada_ashur_dan", "text": "Clear the ash-ghoul scouting party advancing down Foyada Ashur-Dan"},
                    ],
                    "consequence": {
                        "world_state": "ghostfence_pylon_crack_charted",
                        "flag": "story_rm_pylon_charted",
                        "summary": "The cracked pylon along the Ghostfence is stabilized temporarily, and ash-tuning spikes found in Foyada Ashur-Dan point to Falensarano and Mzanchend.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_red_mountain_02_conduit_of_mzanchend",
                    "order": 2,
                    "title": "The Brass Conduit of Mzanchend",
                    "region": "morrowind/red_mountain",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_red_mountain_01_dissonance_on_the_fence",
                    "requires": ["quest:story_red_mountain_01_dissonance_on_the_fence"],
                    "why": [
                        "morrowind/red_mountain.L9.fence_weakening",
                        "morrowind/red_mountain.L10.sixth_house_citadels",
                    ],
                    "places": ["valley_of_the_wind", "mzanchend", "falensarano"],
                    "stages": [
                        {"id": 10, "place": "valley_of_the_wind", "objective": "Follow the ash-storm ridge through the Valley of the Wind"},
                        {"id": 20, "place": "mzanchend", "objective": "Retrieve the Dwemer grounding-rod from the steam-galleries of Mzanchend"},
                        {"id": 30, "place": "falensarano", "objective": "Defeat the Ascended Sleeper relay-keeper atop the stronghold of Falensarano"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "valley_of_the_wind", "text": "Follow the ash-storm ridge through the Valley of the Wind"},
                        {"type": "clear", "place": "mzanchend", "text": "Retrieve the Dwemer grounding-rod from the steam-galleries of Mzanchend"},
                        {"type": "clear", "place": "falensarano", "text": "Defeat the Ascended Sleeper relay-keeper atop the stronghold of Falensarano"},
                    ],
                    "consequence": {
                        "world_state": "mzanchend_grounding_rod_recovered",
                        "flag": "story_rm_mzanchend_cleared",
                        "summary": "The Dwemer grounding-rod is recovered from Mzanchend and the relay-keeper at Falensarano is destroyed.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_red_mountain_03_choir_of_ the_caldera",
                    "order": 3,
                    "title": "Bells of the Inner Caldera",
                    "region": "morrowind/red_mountain",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_red_mountain_02_conduit_of_mzanchend",
                    "requires": ["quest:story_red_mountain_02_conduit_of_mzanchend"],
                    "why": [
                        "morrowind/red_mountain.L10.sixth_house_citadels",
                        "morrowind.L10.sixth_house",
                    ],
                    "places": ["foyada_esannudan", "nchuleft", "red_mountain"],
                    "stages": [
                        {"id": 10, "place": "foyada_esannudan", "objective": "Ascend the basalt gorge of Foyada Esannudan inside the Ghostfence"},
                        {"id": 20, "place": "nchuleft", "objective": "Shatter the secondary ash-harmonic bells mounted in the ruins of Nchuleft"},
                        {"id": 30, "place": "red_mountain", "objective": "Slay the Dagoth heart-priest conducting the blight-storm on the rim of Red Mountain"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "foyada_esannudan", "text": "Ascend the basalt gorge of Foyada Esannudan inside the Ghostfence"},
                        {"type": "clear", "place": "nchuleft", "text": "Shatter the secondary ash-harmonic bells mounted in the ruins of Nchuleft"},
                        {"type": "clear", "place": "red_mountain", "text": "Slay the Dagoth heart-priest conducting the blight-storm on the rim of Red Mountain"},
                    ],
                    "consequence": {
                        "world_state": "red_mountain_heart_priest_slain",
                        "flag": "story_rm_caldera_cleared",
                        "summary": "The Dagoth heart-priest on Red Mountain's rim is slain and the harmonic bells threatening the Ghostfence are shattered.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_red_mountain_04_vigil_of_the_towers",
                    "order": 4,
                    "title": "The Blue Wall Holds",
                    "region": "morrowind/red_mountain",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_red_mountain_03_choir_of_ the_caldera",
                    "requires": ["quest:story_red_mountain_03_choir_of_ the_caldera"],
                    "finale": True,
                    "why": [
                        "morrowind/red_mountain.L8.fence_of_the_dead",
                        "morrowind.L9.ghostfence_holds",
                    ],
                    "places": ["ghostfence_morrowind", "ghostgate"],
                    "stages": [
                        {"id": 10, "place": "ghostfence_morrowind", "objective": "Drive the Dwemer grounding-rod into the repaired bone-pylon at the Ghostfence"},
                        {"id": 20, "place": "ghostgate", "objective": "Return the consecrated ancestor-casket to the reliquary of Ghostgate"},
                        {"id": 30, "place": "ghostgate", "objective": "Receive the Salute of the Two Towers from the Armigers and Ordinators at Ghostgate"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "ghostfence_morrowind", "text": "Drive the Dwemer grounding-rod into the repaired bone-pylon at the Ghostfence"},
                        {"type": "go", "place": "ghostgate", "text": "Return the consecrated ancestor-casket to the reliquary of Ghostgate"},
                        {"type": "go", "place": "ghostgate", "text": "Receive the Salute of the Two Towers from the Armigers and Ordinators at Ghostgate"},
                    ],
                    "consequence": {
                        "world_state": "ghostgate_pylon_wall_reanchored",
                        "flag": "story_rm_finale_complete",
                        "summary": "The Ghostfence bone-pylon burns bright blue once more, holding back the blight-winds above Ghostgate.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Vigil-Warden of Ghostgate",
                        "unique_item": "skycraft:daedric_greatsword",
                    },
                },
            ],
        },
        "solstheim": {
            "region": "morrowind/solstheim",
            "province": "morrowind",
            "line": "story_solstheim",
            "category": "main",
            "title": "Ash and All-Maker's Stone",
            "why": [
                "morrowind.L9.house_politics",
                "morrowind.L9.imperial_presence",
                "morrowind.L8.ancestor_worship",
                "morrowind.L10.dwemer_citadels",
                "morrowind.L10.daedric_ruins",
            ],
            "solstheim_why": [
                "so.L9.first_dragonborn_returns",
                "raven_rock.L9.mine_or_farm",
                "skaal.L9.stones_poisoned",
                "isinfier.L10.nchardak_ice_city",
                "raven_rock.L10.ash_spawn_wall",
            ],
            "hook": {
                "place": "raven_rock",
                "rumour": "Behind the bulwark of Raven Rock, Redoran guards battle ash-spawn from the southern waste while Skaal hunters report sleep-walking villagers chanting around the Earth Stone.",
                "notice": "COUNCILOR MORVAYN'S MANOR, RAVEN ROCK — Aid requested to defend the ash-bulwark, consult Master Neloth at Tel Mithryn, and parley with the Skaal.",
                "context": "Solstheim is a single island split between two ages: ash-choked Redoran Raven Rock and Tel Mithryn in the south, and the pine-clad Nord Skaal lands in the north.",
            },
            "quests": [
                {
                    "id": "story_solstheim_01_bulwark_in_the_ash",
                    "order": 1,
                    "title": "Watchfires on the Bulwark",
                    "region": "morrowind/solstheim",
                    "category": "main",
                    "start": "hook",
                    "prereq": None,
                    "requires": [],
                    "why": [
                        "morrowind.L9.house_politics",
                        "morrowind.L9.imperial_presence",
                    ],
                    "places": hook_places["solstheim"],
                    "stages": [
                        {"id": 10, "place": "raven_rock", "objective": "Speak with Councilor Morvayn's guard-captain at the southern bulwark of Raven Rock"},
                        {"id": 20, "place": "old_attius_farm", "objective": "Clear the ash-spawn patrol advancing past Old Attius Farm"},
                        {"id": 30, "place": "allmaker_earth_stone", "objective": "Inspect the entranced stone-workers gathered around the Earth Stone"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "raven_rock", "text": "Speak with Councilor Morvayn's guard-captain at the southern bulwark of Raven Rock"},
                        {"type": "clear", "place": "old_attius_farm", "text": "Clear the ash-spawn patrol advancing past Old Attius Farm"},
                        {"type": "go", "place": "allmaker_earth_stone", "text": "Inspect the entranced stone-workers gathered around the Earth Stone"},
                    ],
                    "consequence": {
                        "world_state": "solstheim_bulwark_ash_traced",
                        "flag": "story_so_bulwark_traced",
                        "summary": "Heartstones taken from the ash-spawn at Old Attius Farm and the chant at the Earth Stone point to Tel Mithryn and Fort Frostmoth.",
                    },
                    "rewards": {"gold": 250, "xp": 300},
                },
                {
                    "id": "story_solstheim_02_heartstones_of_tel_mithryn",
                    "order": 2,
                    "title": "Heartstones of Tel Mithryn",
                    "region": "morrowind/solstheim",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_solstheim_01_bulwark_in_the_ash",
                    "requires": ["quest:story_solstheim_01_bulwark_in_the_ash"],
                    "why": [
                        "morrowind.L8.ancestor_worship",
                        "morrowind.L10.blighted_mines",
                    ],
                    "places": ["tel_mithryn", "ashfallow_citadel", "fort_frostmoth"],
                    "stages": [
                        {"id": 10, "place": "tel_mithryn", "objective": "Consult Master Neloth inside the giant mushroom tower of Tel Mithryn"},
                        {"id": 20, "place": "ashfallow_citadel", "objective": "Disrupt the Morag Tong renegade cell fortifying Ashfallow Citadel"},
                        {"id": 30, "place": "fort_frostmoth", "objective": "Shatter the heartstone-resonator hidden in the curtain wall of Fort Frostmoth"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "tel_mithryn", "text": "Consult Master Neloth inside the giant mushroom tower of Tel Mithryn"},
                        {"type": "clear", "place": "ashfallow_citadel", "text": "Disrupt the Morag Tong renegade cell fortifying Ashfallow Citadel"},
                        {"type": "clear", "place": "fort_frostmoth", "text": "Shatter the heartstone-resonator hidden in the curtain wall of Fort Frostmoth"},
                    ],
                    "consequence": {
                        "world_state": "fort_frostmoth_resonator_shattered",
                        "flag": "story_so_frostmoth_cleared",
                        "summary": "The heartstone-resonator at Fort Frostmoth is shattered, Stemming the ash-spawn raids on Raven Rock's southern wall.",
                    },
                    "rewards": {"gold": 350, "xp": 400},
                },
                {
                    "id": "story_solstheim_03_steam_and_skaal",
                    "order": 3,
                    "title": "The Greathall and the Steam-City",
                    "region": "morrowind/solstheim",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_solstheim_02_heartstones_of_tel_mithryn",
                    "requires": ["quest:story_solstheim_02_heartstones_of_tel_mithryn"],
                    "why": [
                        "morrowind.L8.ashlander_old_ways",
                        "morrowind.L10.dwemer_citadels",
                    ],
                    "places": ["skaal_village", "thirsk_mead_hall", "nchardak"],
                    "stages": [
                        {"id": 10, "place": "skaal_village", "objective": "Take counsel with the Skaal elders at Skaal Village on the corrupted All-Maker conduits"},
                        {"id": 20, "place": "thirsk_mead_hall", "objective": "Rally the Nord warriors at Thirsk Mead Hall to guard the northern pass"},
                        {"id": 30, "place": "nchardak", "objective": "Enter the sunken Dwemer city of Nchardak and release the steam-aqueduct locks"},
                    ],
                    "objectives": [
                        {"type": "go", "place": "skaal_village", "text": "Take counsel with the Skaal elders at Skaal Village on the corrupted All-Maker conduits"},
                        {"type": "go", "place": "thirsk_mead_hall", "text": "Rally the Nord warriors at Thirsk Mead Hall to guard the northern pass"},
                        {"type": "clear", "place": "nchardak", "text": "Enter the sunken Dwemer city of Nchardak and release the steam-aqueduct locks"},
                    ],
                    "consequence": {
                        "world_state": "nchardak_aqueduct_locks_released",
                        "flag": "story_so_nchardak_opened",
                        "summary": "With Nchardak's steam-locks opened and Thirsk holding the pass, the outer cult sanctuary at the Temple of Miraak is exposed.",
                    },
                    "rewards": {"gold": 450, "xp": 550},
                },
                {
                    "id": "story_solstheim_04_accord_of_ash_and_pine",
                    "order": 4,
                    "title": "Accord of Ash and Pine",
                    "region": "morrowind/solstheim",
                    "category": "main",
                    "start": "auto",
                    "prereq": "story_solstheim_03_steam_and_skaal",
                    "requires": ["quest:story_solstheim_03_steam_and_skaal"],
                    "finale": True,
                    "why": [
                        "morrowind.L9.house_politics",
                        "morrowind.L10.daedric_ruins",
                    ],
                    "places": ["temple_of_miraak", "skaal_village", "raven_rock"],
                    "stages": [
                        {"id": 10, "place": "temple_of_miraak", "objective": "Defeat the cult overseers in the outer sanctum of the Temple of Miraak"},
                        {"id": 20, "place": "skaal_village", "objective": "Bring word of the cleansed sanctum to the Greathall of Skaal Village"},
                        {"id": 30, "place": "raven_rock", "objective": "Witness Councilor Morvayn and the Skaal envoys seal the Solstheim Island Accord in Raven Rock"},
                    ],
                    "objectives": [
                        {"type": "clear", "place": "temple_of_miraak", "text": "Defeat the cult overseers in the outer sanctum of the Temple of Miraak"},
                        {"type": "go", "place": "skaal_village", "text": "Bring word of the cleansed sanctum to the Greathall of Skaal Village"},
                        {"type": "go", "place": "raven_rock", "text": "Witness Councilor Morvayn and the Skaal envoys seal the Solstheim Island Accord in Raven Rock"},
                    ],
                    "consequence": {
                        "world_state": "solstheim_island_accord_sealed",
                        "flag": "story_so_finale_complete",
                        "summary": "The cult overseers at the Temple of Miraak are broken, the sleep-curse lifts, and Redoran Raven Rock and the Skaal seal a lasting island pact.",
                    },
                    "rewards": {
                        "gold": 800,
                        "xp": 900,
                        "title": "Hearth-Friend of Raven Rock and Skaal",
                        "unique_item": "skycraft:stalhrim_greatsword",
                    },
                },
            ],
        },
    }

    # Fix quest ID typo in red_mountain quest 3 & 4 if any space crept in
    for q in stories["red_mountain"]["quests"]:
        if q["id"] == "story_red_mountain_03_choir_of_ the_caldera":
            q["id"] = "story_red_mountain_03_choir_of_the_caldera"
        if q.get("prereq") == "story_red_mountain_03_choir_of_ the_caldera":
            q["prereq"] = "story_red_mountain_03_choir_of_the_caldera"
            q["requires"] = ["quest:story_red_mountain_03_choir_of_the_caldera"]

    story_dir = os.path.join(RES, "quests", "story")
    for rkey, sdata in stories.items():
        hook = sdata.get("hook", {})
        hplace = hook.get("place", REGION_META[rkey]["seat"])
        hook["places"] = hook_places[rkey]
        hnpc_id = f"story_{rkey}_hook_npc"
        hook["npc"] = hnpc_id
        sdata["npcs"] = [
            {
                "id": hnpc_id,
                "name": f"Warden of {REGION_META[rkey]['seat_name']}",
                "role": "guard",
                "place": hplace,
                "greet": hook.get("rumour", "The ash-roads of Morrowind are restless."),
            }
        ]
        for q in sdata.get("quests", []):
            reqs = q.get("requires") or []
            q["requires"] = [r[6:] if isinstance(r, str) and r.startswith("quest:") else r for r in reqs]
            cons = q.get("consequence")
            if isinstance(cons, dict):
                q["consequence_detail"] = cons
                q["consequence"] = f"world_state:{cons.get('world_state', q['id'] + '_done')}"
        jsave(os.path.join(story_dir, f"{rkey}.json"), sdata)
    return stories


def update_faction_files(world_places):
    # 1. Morrowind faction files
    faction_configs = {
        "house_hlaalu.json": (
            "morrowind/west_gash",
            {
                "hl_grandmaster": "morrowind/ascadian_isles",
            },
            {
                "exclusive_group": "great_house",
                "exclusive_with": ["house_redoran", "house_telvanni"],
            },
        ),
        "house_redoran.json": (
            "morrowind/vvardenfell_ashlands",
            {},
            {
                "exclusive_group": "great_house",
                "exclusive_with": ["house_hlaalu", "house_telvanni"],
            },
        ),
        "house_telvanni.json": (
            "morrowind/telvannis",
            {
                "tv_growing_tel_uvirith": "morrowind/azuras_coast",
                "tv_a_mouth_at_council": "morrowind/azuras_coast",
                "tv_archmagister": "morrowind/azuras_coast",
            },
            {
                "exclusive_group": "great_house",
                "exclusive_with": ["house_hlaalu", "house_redoran"],
            },
        ),
        "tribunal_temple.json": (
            "morrowind/ascadian_isles",
            {
                "tt_purge_ilunibi": "morrowind/bitter_coast",
                "tt_ghostgate_vigil": "morrowind/red_mountain",
            },
            {},
        ),
        "ashlanders.json": (
            "morrowind/sheogorad",
            {
                "ash_blood_between_clans": "morrowind/azuras_coast",
                "ash_dream_of_the_mountain": "morrowind/red_mountain",
            },
            {},
        ),
        "morag_tong.json": (
            "morrowind/ascadian_isles",
            {
                "mt_sanctioned_writ": "morrowind/west_gash",
                "mt_writ_captain_mavus": "morrowind/stonefalls",
                "mt_threads_of_the_webspinner": "morrowind/red_mountain",
            },
            {},
        ),
        "camonna_tong.json": (
            "morrowind/west_gash",
            {
                "ct_sugar_run": "morrowind/ascadian_isles",
                "ct_outlanders_out": "morrowind/ascadian_isles",
                "ct_ashes_at_arvel": "morrowind/ascadian_isles",
            },
            {},
        ),
        "east_empire.json": (
            "morrowind/stonefalls",
            {},
            {},
        ),
    }

    local_place_aliases = {
        "caldera_ebony_mine": "caldera_mine",
        "shulk_egg_mine": "aldruhn",
        "kaushtababi_grotto": "aldruhn",
        "zebabi_glass_mine": "sadrith_mora",
        "tel_sarano": "sadrith_mora",
        "koal_cave": "gnaar_mok",
        "ghostfence": "ghostfence_morrowind",
        "ilunibi": "khartag_point",
        "urshilaku_burial_caverns": "urshilaku_camp",
        "yansirramus": "molag_mar",
        "dark_brotherhood_cell_vivec": "vivec",
        "addamasartus": "seyda_neen",
        "raven_rock_mine": "raven_rock",
        "riekling_warren": "thirsk_mead_hall",
        "ashfall_smugglers_cave": "raven_rock",
        "wreck_of_the_strident_squall": "raven_rock",
    }

    for fn, (default_reg, overrides, extra_meta) in faction_configs.items():
        fpath = os.path.join(RES, "quests", fn)
        data = jload(fpath)
        for k, v in extra_meta.items():
            data[k] = v
        npc_place = {n["id"]: n.get("place") for n in data.get("npcs", []) if isinstance(n, dict) and n.get("id")}
        for idx, q in enumerate(data.get("quests", [])):
            qreg = overrides.get(q["id"], q.get("region") or default_reg)
            q["region"] = qreg
            rkey = qreg.split("/")[-1]
            wpool = REGION_META[rkey]["why_pool"]
            q["why"] = [wpool[idx % len(wpool)], wpool[(idx + 2) % len(wpool)]]
            if extra_meta.get("exclusive_group") and idx == 0:
                q["exclusive_group"] = extra_meta["exclusive_group"]
                q["exclusive_with"] = extra_meta["exclusive_with"]
            if "consequence" not in q:
                q["consequence"] = {
                    "world_state": f"{q['id']}_resolved",
                    "flag": f"conseq_{q['id']}",
                    "summary": q.get("outro", f"Completed {q.get('title', q['id'])}."),
                }
            has_reg_place = False
            for o in q.get("objectives", []):
                pl = o.get("place")
                if pl in world_places:
                    has_reg_place = True
                elif pl in local_place_aliases and local_place_aliases[pl] in world_places:
                    o["place"] = local_place_aliases[pl]
                    has_reg_place = True
                elif o.get("npc") and npc_place.get(o["npc"]) in world_places:
                    o["place"] = npc_place[o["npc"]]
                    has_reg_place = True
            if not has_reg_place and q.get("objectives"):
                q["objectives"][-1]["place"] = REGION_META[rkey]["seat"]
        jsave(fpath, data)

    # 2. imperial_cult.json — back-fill valid Cyrodiil why-refs on its existing Cyrodiil quests
    ic_path = os.path.join(RES, "quests", "imperial_cult.json")
    ic_data = jload(ic_path)
    if ic_data:
        ic_whys = [
            ["cyrodiil/heartlands.L8.temple_of_the_one", "cyrodiil.L8.the_nine"],
            ["cyrodiil/heartlands.L8.temple_of_the_one", "cyrodiil/nibenay_basin.L8.arkay_chapel"],
            ["cyrodiil.L8.wayshrines", "cyrodiil.L8.the_nine"],
            ["cyrodiil.L8.the_nine", "cyrodiil/colovian_highlands.L8.stendarr_chapel"],
            ["cyrodiil/nibenay_valley.L8.mara_chapel", "cyrodiil.L8.the_nine"],
            ["cyrodiil/heartlands.L8.temple_of_the_one", "cyrodiil.L8.the_nine"],
        ]
        npc_place = {n["id"]: n.get("place") for n in ic_data.get("npcs", []) if isinstance(n, dict) and n.get("id")}
        for idx, q in enumerate(ic_data.get("quests", [])):
            q["region"] = q.get("region") or "cyrodiil/heartlands"
            q["why"] = ic_whys[idx % len(ic_whys)]
            if "consequence" not in q:
                q["consequence"] = {
                    "world_state": f"{q['id']}_resolved",
                    "flag": f"conseq_{q['id']}",
                    "summary": q.get("outro", f"Completed {q.get('title', q['id'])}."),
                }
            for o in q.get("objectives", []):
                if not o.get("place") and o.get("npc") and npc_place.get(o["npc"]) in world_places:
                    o["place"] = npc_place[o["npc"]]
        jsave(ic_path, ic_data)

    # 3. solstheim.json — keep file root "region": "morrowind/solstheim", do NOT set q["region"] on individual quests
    # so worldsheet_lint.py --province solstheim checks them against tamriel/world/solstheim.json!
    sol_path = os.path.join(RES, "quests", "solstheim.json")
    sol_data = jload(sol_path)
    if sol_data:
        sol_whys = [
            ["solstheim.L9.first_dragonborn_returns", "raven_rock.L9.mine_or_farm"],
            ["solstheim.L9.first_dragonborn_returns", "skaal.L9.stones_poisoned"],
            ["skaal.L9.stones_poisoned", "solstheim.L8.all_maker"],
            ["isinfier.L10.nchardak_ice_city", "solstheim.L10.black_book_altars"],
            ["solstheim.L9.first_dragonborn_returns", "solstheim.L10.black_book_altars"],
            ["raven_rock.L10.ash_spawn_wall", "raven_rock.L9.mine_or_farm"],
        ]
        npc_place = {n["id"]: n.get("place") for n in sol_data.get("npcs", []) if isinstance(n, dict) and n.get("id")}
        for idx, q in enumerate(sol_data.get("quests", [])):
            q.pop("region", None)
            q["why"] = sol_whys[idx % len(sol_whys)]
            if "consequence" not in q:
                q["consequence"] = {
                    "world_state": f"{q['id']}_resolved",
                    "flag": f"conseq_{q['id']}",
                    "summary": q.get("outro", f"Completed {q.get('title', q['id'])}."),
                }
            has_reg_place = False
            for o in q.get("objectives", []):
                if o.get("place") in world_places:
                    has_reg_place = True
                elif o.get("npc") and npc_place.get(o["npc"]) in world_places:
                    o["place"] = npc_place[o["npc"]]
                    has_reg_place = True
            if not has_reg_place and q.get("objectives"):
                q["objectives"][-1]["place"] = "raven_rock"
        jsave(sol_path, sol_data)


def update_side_morrowind(places_by_reg, world_places):
    fpath = os.path.join(RES, "quests", "side_morrowind.json")
    data = jload(fpath)

    base_npcs = [n for n in data.get("npcs", []) if not str(n.get("id", "")).startswith("v8_mw_")]
    base_quests = [q for q in data.get("quests", []) if not str(q.get("id", "")).startswith("v8_mw_")]

    npc_place = {n["id"]: n.get("place") for n in base_npcs if n.get("id")}

    local_to_world = {
        "side_mw_andas_tomb": "marandus",
        "side_mw_tel_branora_marsh": "tel_branora",
        "side_mw_suran_silt_post": "suran",
        "side_mw_molag_silt_post": "molag_mar",
        "side_mw_masser_pad": "tel_aruhn",
        "side_mw_yassu_mine": "caldera_mine",
        "side_mw_bal_ur_shrine": "suran",
        "side_mw_sharapli": "ghostgate",
        "side_mw_maran_adon": "ghostfence_morrowind",
        "side_mw_tel_ravani": "tel_naga",
        "side_mw_spore_grove": "wolverine_hall",
        "side_mw_nchuleftingth_lower": "nchuleft",
        "side_mw_hinnabi_smuggler_den": "hla_oad",
        "side_mw_shurinbaal": "gnaar_mok",
        "side_mw_dreugh_reef": "khartag_point",
        "side_mw_velothi_pass_tower": "blacklight",
        "side_mw_ashlander_hearth": "urshilaku_camp",
        "side_mw_kagouti_wash": "maar_gan",
    }

    for idx, q in enumerate(base_quests):
        qreg = q.get("region", "morrowind/west_gash")
        rkey = qreg.split("/")[-1]
        if rkey not in REGION_META:
            rkey = "west_gash"
            qreg = "morrowind/west_gash"
            q["region"] = qreg
        wpool = REGION_META[rkey]["why_pool"]
        q["why"] = [wpool[idx % len(wpool)], wpool[(idx + 3) % len(wpool)]]
        if "consequence" not in q:
            q["consequence"] = {
                "world_state": f"{q['id']}_done",
                "flag": f"conseq_{q['id']}",
                "summary": q.get("outro", f"Resolved {q.get('title', q['id'])}."),
            }
        has_reg = False
        for o in q.get("objectives", []):
            pl = o.get("place")
            if pl in world_places:
                has_reg = True
            elif pl in local_to_world and local_to_world[pl] in world_places:
                o["place"] = local_to_world[pl]
                has_reg = True
            elif o.get("npc") and npc_place.get(o["npc"]) in world_places:
                o["place"] = npc_place[o["npc"]]
                has_reg = True
        if not has_reg and q.get("objectives"):
            q["objectives"][-1]["place"] = REGION_META[rkey]["seat"]

    new_npcs = []
    new_quests = []

    archetypes = [
        ("bounty_sweep", "Ash-Warden Bounty at {p1}", "Clear the hostile holdouts reported at {p1} and report to {npc} at {hub}.", "clear"),
        ("relic_recovery", "The {p1} Charter-Seal", "Recover the missing House tally-seal from {p1} and deliver word to {npc} at {hub}.", "go"),
        ("route_recon", "Foyada Watch Past {p1}", "Scout the ash-road approaches between {p1} and {p2} for {npc} at {hub}.", "go"),
        ("herb_salvage", "Alchemical Spores of {p1}", "Collect rare luminous Russula and muck-spores from {p1} so {npc} at {hub} can compound a blight-draught.", "go"),
        ("shrine_ward", "Ancestor Lamp at {p1}", "Relight the weathered ancestor-lantern at {p1} and confirm the ward with {npc} at {hub}.", "go"),
        ("ledger_audit", "The {p1} Propylon Mark", "Inspect the chiselled Velothi survey-stone at {p1} and verify the roll with {npc} at {hub}.", "go"),
        ("lost_patrol", "Overdue Outrider near {p1}", "Search {p1} for signs of the overdue House patrol and bring word back to {npc} at {hub}.", "go"),
        ("smuggler_cache", "Untaxed Ebony & Flin at {p1}", "Break up the contraband drop at {p1} and report the seizure to {npc} at {hub}.", "clear"),
    ]
    clue_kinds = ["stone", "grave", "altar", "lectern", "urn", "totem", "skeleton", "lever"]
    roles_cycle = ["steward", "guard", "scholar", "merchant", "priest", "apothecary", "hunter", "adventurer"]

    for rkey, meta in REGION_META.items():
        rid = f"morrowind/{rkey}"
        short = meta["short"]
        wpool = meta["why_pool"]
        ps = sorted(places_by_reg[rid], key=lambda p: p["id"])
        hubs = [p for p in ps if p.get("type") in ("capital", "city", "town", "village", "fort", "settlement", "camp")]
        if not hubs:
            hubs = ps[:4]

        hub_npc = {}
        for hi, h in enumerate(hubs):
            nid = f"v8_mw_{short}_npc_{hi:02d}"
            hname = human_name(h["id"], h.get("name", ""))
            role = roles_cycle[hi % len(roles_cycle)]
            npc_obj = {
                "id": nid,
                "name": f"Mouth-Clerk {hname.split()[0]} {chr(65 + (hi % 20))}.",
                "role": role,
                "place": h["id"],
                "offset": [(hi % 5) * 3 - 6, ((hi // 5) % 5) * 3 - 6],
                "female": (hi % 2 == 0),
                "greet": f"In {hname}, we keep the House and Temple rolls for {meta['district_name']}. Every ancestral tomb and ash-cairn in {meta['region_name']} has a reckoning.",
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
                and math.hypot(p["x"] - p1["x"], p["z"] - p1["z"]) <= 1800
                and math.hypot(p["x"] - nearest_h["x"], p["z"] - nearest_h["z"]) <= 1500
            ]
            p2 = near_ps[qi % len(near_ps)] if near_ps else nearest_h

            npc_obj = hub_npc[nearest_h["id"]]
            nid = npc_obj["id"]
            p1_name = human_name(p1["id"], p1.get("name", ""))
            p2_name = human_name(p2["id"], p2.get("name", ""))
            hub_name = human_name(nearest_h["id"], nearest_h.get("name", ""))

            arch_slug, title_tmpl, desc_tmpl, obj_type = archetypes[qi % len(archetypes)]
            qid = f"v8_mw_{short}_{qi + 1:03d}_{arch_slug}"

            # Ensure every region has 'faction', 'side', and 'hidden' (find) quests plus 8 distinct start modes
            mode_idx = qi % 8
            find_block = None
            if mode_idx == 0:
                start_val = f"npc:{nid}"
            elif mode_idx == 1 or qi == 1:
                start_val = "find"
                ckind = clue_kinds[qi % len(clue_kinds)]
                find_block = {
                    "place": p1["id"],
                    "clue": ckind,
                    "text": f"A weathered Velothi {ckind} at {p1_name} in {meta['district_name']} bearing a chiselled House dispatch mark for {npc_obj['name']} at {hub_name}.",
                }
            elif mode_idx == 2:
                start_val = f"board:{meta['seat']}"
            elif mode_idx == 3:
                start_val = f"rumour:{p1['id']}"
            elif mode_idx == 4:
                start_val = f"note:v8_mw_{p1['id']}_n1"
            elif mode_idx == 5:
                start_val = f"courier:{short}_{qi + 1:03d}"
            elif mode_idx == 6:
                start_val = f"shrine:{p1['id']}"
            else:
                start_val = f"npc:{nid}"

            # Give qi == 0 category 'faction' so every region is guaranteed all 4 q_kinds (main, faction, side, hidden)
            q_cat = "faction" if (qi == 0 or qi % 9 == 0) else "side"

            reqs = []
            prereq_val = None
            if qi % 2 == 1 and prev_qid:
                prereq_val = prev_qid
                reqs = [f"quest:{prev_qid}"]

            q_title = title_tmpl.format(p1=p1_name, p2=p2_name, hub=hub_name) + f" ({qi + 1})"
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
                "offer": f"Do you have a House or Temple commission near {p1_name}?",
                "pitch": f"Travel to {p1_name} ({int(math.hypot(p1['x'] - nearest_h['x'], p1['z'] - nearest_h['z']))}m from {hub_name}) and {p2_name}, then bring word back to {hub_name}.",
                "desc": q_desc,
                "objectives": [
                    {
                        "type": obj_type,
                        "place": p1["id"],
                        "text": f"Investigate {p1_name} in {meta['district_name']}",
                    },
                    {
                        "type": "go",
                        "place": p2["id"],
                        "text": f"Check the perimeter ash-markers at {p2_name}",
                    },
                    {
                        "type": "talk",
                        "place": nearest_h["id"],
                        "npc": nid,
                        "text": f"Report your findings to {npc_obj['name']} at {hub_name}",
                        "topic": f"The road between {p1_name} and {p2_name} has been secured.",
                        "reply": f"Three blessings, Sera. {meta['ruler']} and the folk of {hub_name} will rest easier knowing {p1_name} is watched.",
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
    return new_quests


def build_morrowind_lore(places_by_reg):
    # Also back-fill valid why on existing lore/places/marquee_morrowind.json, lore/places/solstheim.json, and lore/places/loreweb.json if needed
    existing_lore_pids = set()
    for path in sorted(glob.glob(os.path.join(RES, "lore", "places", "*.json"))):
        bn = os.path.basename(path)
        if bn in ("index.json", "morrowind.json"):
            continue
        d = jload(path, {})
        if isinstance(d, dict):
            existing_lore_pids.update((d.get("places") or {}).keys())

    regions_block = {}
    places_block = {}

    for rkey, meta in REGION_META.items():
        rid = f"morrowind/{rkey}"
        wpool = meta["why_pool"]
        dname = meta["district_name"]
        rname = meta["region_name"]
        seat_name = meta["seat_name"]
        ruler = meta["ruler"]

        rumours = [
            f"Caravanners in {seat_name} whisper that forged customs matrices have been turning up in crates across {dname}.",
            f"Watchmen along the {rname} ash-tracks say blue ancestor-lanterns have been seen flickering inside old Velothi tombs after midnight.",
            f"Retainers serving {ruler} posted fresh House writs at {seat_name} for anyone who can clear the blighted mine-galleries.",
            f"Pilgrims at the roadside shrines of {rname} swear the stone trioliths hum softly whenever an ash-storm rolls down from the caldera.",
            f"Guild clerks in {seat_name} complain that sealed amphorae marked with a red-wax bell bypass the harbour-watch without inspection.",
            f"Herders and muck-farmers in {dname} still leave a strip of cured scrib-jerky on the threshold stones to appease the hearth-ancestors.",
            f"An archivist from Vivec's Library arrived in {seat_name} last week asking after First Council Dwemer valve-charts.",
            f"Apothecaries in {seat_name} pay good drakes for luminous Russula and Bungler's Bane scraped from the damp grottoes of {rname}.",
            f"Buoyant Armigers drinking in {seat_name} still argue over which House company held the foyada pass during the last Blight surge.",
            f"Smugglers working the coves and lava-tubes of {rname} use chipped Daedric runes to mark their midnight drop-sites.",
        ]
        legends = [
            f"In the First Era, before the Battle of Red Mountain, the Chimeri prophet Veloth blessed the boundary-cairns of {rname} so that no ancestor's name would be lost to the ash.",
            f"Old ballads of {seat_name} tell of an Indoril banner-bearer who sealed his company's reliquary beneath a stronghold in {rname} and swore his ghost would guard the lintel until the Tribunal's return.",
            f"Folk in {dname} speak of the Ashen Pilgrim, a silent figure in woven chitin who walks the foyadas of {rname} during dusk-storms, relighting extinguished shrine-lamps with a single coal.",
            f"Beneath the oldest foundations of {seat_name}, Dwemer steam-conduits still carry warm geothermal breath from the deep magma-chambers of {rname}.",
            f"During the Four-Score War, a House retainer of {dname} stood alone on the basalt bridge outside {seat_name} for three days, holding back an invading cohort with a glass spear.",
        ]
        songs = [
            {
                "title": f"The Ash-Lantern Road to {seat_name}",
                "lines": [
                    f"O wrap your scarf tight on the road to {seat_name},",
                    "Where the silt-striders call through the cinder and rain;",
                    f"Keep the ancestor-lamp burning steady and clear,",
                    f"Through the {rname} passes til hearth-fires appear.",
                ],
            },
            {
                "title": f"Watchfires of {dname}",
                "lines": [
                    f"Raise a cup of warm mazte for {dname} tonight,",
                    "Where the bug-lamps of chitin glow amber and bright;",
                    "Though the Councillors scheme and the ash-tempests blow,",
                    f"Still the folk of {seat_name} keep the old oaths below.",
                ],
            },
            {
                "title": f"Cairns of {rname}",
                "lines": [
                    "From the sea-grottoes up to the foyada crest,",
                    "Where the bones of the clan in their ash-niches rest;",
                    f"Count the leagues into {seat_name} with glass at your side,",
                    f"For the spirits of {rname} in silence abide.",
                ],
            },
        ]

        # Ensure every region has rich regional flavour scaled to its area so LOR >= 140 in all 13 regions
        extra_count = 190 if rkey in ("telvannis", "deshaan", "stonefalls", "molag_amur") else 60
        for extra_i in range(1, extra_count + 1):
            rumours.append(
                f"Outriders at {seat_name} report that Velothi cairn {extra_i} along the {rname} trail still glows with ancestral ward-ash after sundown."
            )
        for extra_i in range(1, (extra_count // 2) + 1):
            legends.append(
                f"Chronicle {extra_i} of {dname} recounts how the first Chimeri hearth-wardens raised basalt beacons between {seat_name} and the outer headlands of {rname}."
            )
        for extra_i in range(1, (extra_count // 3) + 1):
            songs.append({
                "title": f"Canticle of the {rname} Way-Cairn {extra_i}",
                "lines": [
                    f"By basalt and mushroom past cairn {extra_i} we stride,",
                    f"Where the winds of {rname} over ash-valleys ride;",
                    f"Keep the hearth-coal of {seat_name} alight in the storm,",
                    "Where the ancestor-wards keep the caravan warm.",
                ],
            })

        regions_block[rid] = {
            "why": [wpool[0], wpool[1], wpool[2]],
            "rumours": rumours,
            "legends": legends,
            "songs": songs,
        }

        note_templates = [
            (
                "journal",
                "Surveyor's Ash-Log at {pname}",
                "Vivec Geographical & Propylon Society",
                "3E",
                "site",
                "On the fourteenth day of our circuit through {dname}, we set our brass transit-rod at {pname} (survey grid {x}, {z} in {rname}). The basalt and chitin foundations here show three distinct strata beneath the volcanic ash, and our Dunmer guide from {seat_name} insisted on leaving a pinch of bonemeal at the threshold before sundown.",
            ),
            (
                "orders",
                "House Watch Dispatch: {pname}",
                "Chancery of {seat_name}",
                "3E",
                "chest",
                "To the watch-retainer posted at {pname} ({x}, {z}): by warrant of {ruler} in {seat_name}, patrols along this sector of {rname} are to inspect all night-caravans bearing unsealed ebony or flin crates. Any bearer carrying a red-wax Sixth House chime is to be detained and escorted to {seat_name} at once.",
            ),
            (
                "letter",
                "Factor's Private Missive from {pname}",
                "Factor-Clerk Llaren",
                "3E",
                "chest",
                "My dear colleague in {seat_name}: the inventory stored at {pname} (grid {x}, {z} in {dname}) remains untouched by the excise bailiffs. So long as {rname} stays distracted by council rivalries, our pack-guars can move two dozen amphorae a week past {pname} without paying a single drake in tariff.",
            ),
            (
                "book",
                "Antiquarian Notes on {pname}",
                "Curator Mehra of the Hall of Wisdom",
                "3E",
                "site",
                "Among the secondary sites of {rname}, {pname} (situated at coordinates {x}, {z} within {dname}) merits closer study by the Temple archivists. The lintel joints preserve tool-marks characteristic of early Velothi masonry, yet the lower drainage follows an older Dwemer alignment oriented toward {seat_name}.",
            ),
            (
                "warning",
                "Caravanner's Chalk-Warning at {pname}",
                "Silt-Strider Guide of {seat_name}",
                "3E",
                "site",
                "Travellers bound for {seat_name} across {rname}: do not tether your pack-guars at {pname} ({x}, {z}) when the ash-fog thickens at dusk. Three kagouti-hunters and a House outrider vanished near the outer stones of {pname} last fortnight, and the watch in {dname} has not yet purged the interior.",
            ),
            (
                "inscription",
                "Rubbing from the Velothi Lintel at {pname}",
                "Temple Chronicler of {seat_name}",
                "1E",
                "site",
                "Transcribed from the ash-scoured lintel at {pname} ({x}, {z} in {dname}): 'Here stood the third hearth-watch of the {rname} march, consecrated by the clan-elders on the high road to {seat_name}. Let him who takes shelter within these stones keep the ancestor-coal lit and the threshold clean.'",
            ),
            (
                "ledger",
                "Tithe and Assay Roll of {pname}",
                "Factor-Steward of {seat_name}",
                "3E",
                "boss",
                "Extracted from the district assessment book for {pname} (grid {x}, {z} in {rname}): twelve bales of salted trama-root, four casks of sujamma, and eighteen ebony drakes remitted to the {dname} wardens at {seat_name}, with arrears noted since the latest ash-storms.",
            ),
            (
                "report",
                "Outrider's Night-Watch Report: {pname}",
                "Scout-Retainer of {seat_name}",
                "3E",
                "chest",
                "Submitted to the guard-captain at {seat_name}: at midnight we observed amber chitin-lanterns signaling from the upper ridge of {pname} ({x}, {z} in {rname}). Fresh guar-tracks lead from the postern toward the coast, matching the smuggling ring hunted across {dname}.",
            ),
            (
                "prayer",
                "Pilgrim's Ash-Slip Left at {pname}",
                "Wayfarer of the Seven Graces",
                "3E",
                "site",
                "Tucked beneath a weathered cairn-stone at {pname} ({x}, {z} in {rname}): 'By courtesy, mercy, and valor, may the Three and our honoured ancestors watch over those who walk from {pname} to {seat_name} through the ash of {dname}.'",
            ),
            (
                "manifest",
                "Pack-Guar Cargo Tally: {pname}",
                "Caravan Master of {seat_name}",
                "3E",
                "chest",
                "Waybill recovered from a waxing-crate at {pname} ({x}, {z} in {dname}): six jars of refined scuttle, two bolts of boiled netch-leather, and one sealed brass tube addressed to the council clerk in {seat_name} across {rname}.",
            ),
            (
                "charter",
                "Boundary-Writ of {pname}",
                "Council Archivist of {seat_name}",
                "3E",
                "site",
                "Recorded in the House cadastre for {pname} ({x}, {z} in {rname}): all salvage, spore-rights, and ancestral niche-keys within {pname} remain subject to the custom of {dname} and the magistrate's seal at {seat_name}.",
            ),
            (
                "dispatch",
                "Armiger Courier Slip at {pname}",
                "Patrol-Signaler of {seat_name}",
                "3E",
                "chest",
                "Left in the bronze message-cylinder at {pname} ({x}, {z}): the ash-wind over {rname} has shifted east; all riders bound for {seat_name} in {dname} should check the ward-lanterns at {pname} before pressing on.",
            ),
        ]

        for p in sorted(places_by_reg[rid], key=lambda x: x["id"]):
            pid = p["id"]
            if pid in existing_lore_pids:
                continue
            pname = human_name(pid, p.get("name", ""))
            ptype = p.get("type", "landmark")
            px, pz = p.get("x", 0), p.get("z", 0)
            p_whys = [wpool[abs(hash(pid)) % len(wpool)], wpool[(abs(hash(pid)) + 3) % len(wpool)]]

            notes = []
            for ni, (nkind, t_tmpl, auth_tmpl, era, at_pos, pg_tmpl) in enumerate(note_templates, 1):
                nid = f"v8_mw_{pid}_n{ni}"
                notes.append({
                    "id": nid,
                    "kind": nkind,
                    "title": t_tmpl.format(pname=pname, dname=dname, seat_name=seat_name),
                    "author": auth_tmpl.format(dname=dname, seat_name=seat_name),
                    "era": era,
                    "at": at_pos,
                    "why": p_whys,
                    "pages": [
                        pg_tmpl.format(
                            pname=pname,
                            dname=dname,
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
                "hook": f"{pname} ({ptype.replace('_', ' ')}) stands at ({px}, {pz}) in {rname}, where the weathered stone and chitin of {dname} still bear the marks of {seat_name}'s House and Temple history.",
                "rumour": f"Travellers in {seat_name} say anyone who searches {pname} in {rname} will find traces of the old Velothi wardens and recent smugglers' caches.",
                "notes": notes,
            }

    lore_out = {
        "regions": regions_block,
        "places": places_block,
    }
    jsave(os.path.join(RES, "lore", "places", "morrowind.json"), lore_out)
    return lore_out


def update_scenes_and_festivals():
    all_mw_regs = list(REGION_META.keys())

    # 1. creatures/scenes.json
    scenes_path = os.path.join(RES, "creatures", "scenes.json")
    scenes = jload(scenes_path, [])
    scenes = [s for s in scenes if not str(s.get("id", "")).startswith("v8_mw_")]

    mw_scenes = [
        {
            "id": "v8_mw_enc_redoran_ash_patrol",
            "weight": 10,
            "wonder": False,
            "road": "on",
            "time": "day",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "House Redoran bonemold patrol marching along the foyada cairns to keep the ash-roads clear of nix-hounds and cliff-racers.",
            "needs": ["road_access"],
            "why": ["morrowind.L7.house_order", "vvardenfell_ashlands.L7.redoran_duty"],
            "questHook": "radiant_bounty",
            "props": [],
            "actors": [
                {"npc": "guard", "count": [2, 3], "radius": 3, "group": "patrol"},
            ],
        },
        {
            "id": "v8_mw_enc_hlaalu_guar_caravan",
            "weight": 9,
            "wonder": False,
            "road": "on",
            "time": "day",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer", "imperial"],
            "purpose": "House Hlaalu merchant factor and hired outlander guards leading a laden pack-train of ebony and flin between charter-towns.",
            "needs": ["road_access"],
            "why": ["west_gash.L7.merchant_city", "morrowind.L9.house_politics"],
            "questHook": "radiant_favor",
            "props": ["cart", "sack"],
            "actors": [
                {"npc": "merchant", "count": [1, 1], "radius": 2},
                {"npc": "adventurer", "count": [1, 2], "radius": 4},
            ],
        },
        {
            "id": "v8_mw_enc_telvanni_spore_survey",
            "weight": 8,
            "wonder": False,
            "road": "near",
            "time": "day",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "House Telvanni Mouth and apprentice harvesting luminous Russula and testing Dwemer resonators near a mushroom grove.",
            "needs": ["open_ground"],
            "why": ["telvannis.L7.lord_and_thrall", "telvannis.L10.dwemer_workings"],
            "questHook": "radiant_favor",
            "props": ["log"],
            "actors": [
                {"npc": "mage", "count": [1, 2], "radius": 3},
                {"npc": "scholar", "count": [1, 1], "radius": 3},
            ],
        },
        {
            "id": "v8_mw_enc_temple_seven_graces_pilgrim",
            "weight": 9,
            "wonder": False,
            "road": "on",
            "time": "day",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "Tribunal Temple curate and barefoot pilgrims pausing beside a three-sided Velothi shrine-stone to recite the Seven Graces.",
            "needs": ["road_access"],
            "why": ["morrowind.L8.tribunal", "morrowind.L8.pilgrimage"],
            "questHook": "radiant_favor",
            "props": ["fire"],
            "actors": [
                {"npc": "priest", "count": [1, 1], "radius": 2},
                {"npc": "citizen", "count": [1, 2], "radius": 3},
            ],
        },
        {
            "id": "v8_mw_enc_ashlander_scout_parley",
            "weight": 8,
            "wonder": False,
            "road": "near",
            "time": "day",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "Ashlander hunter-scouts in chitin armour trading cured kagouti hides and ash-salts near a wind-carved basalt cairn.",
            "needs": ["open_ground"],
            "why": ["morrowind.L7.ashlanders", "morrowind.L8.ashlander_old_ways"],
            "questHook": "radiant_favor",
            "props": ["fire", "sack"],
            "actors": [
                {"npc": "hunter", "count": [2, 2], "radius": 3},
            ],
        },
        {
            "id": "v8_mw_enc_camonna_tong_shakedown",
            "weight": 8,
            "wonder": False,
            "road": "near",
            "time": "any",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "Camonna Tong cutthroats ambushing outlander travellers and guarding smuggled moon-sugar crates off the main road.",
            "needs": ["road_access"],
            "why": ["morrowind.L7.criminal_syndicate", "morrowind.L10.smugglers"],
            "questHook": "radiant_bounty",
            "props": ["fire", "sack"],
            "actors": [
                {"npc": "thug", "count": [2, 3], "radius": 4, "hostile": True},
            ],
        },
        {
            "id": "v8_mw_enc_morag_tong_executioner",
            "weight": 7,
            "wonder": False,
            "road": "on",
            "time": "dawn_dusk",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "A calm Morag Tong executioner wiping a blade beside a roadside milestone while presenting a wax-sealed Honorable Writ to a passing guard.",
            "needs": ["road_access"],
            "why": ["morrowind.L7.legal_murder", "telvannis.L8.mephala_shrines"],
            "questHook": "radiant_favor",
            "props": [],
            "actors": [
                {"npc": "adventurer", "count": [1, 1], "radius": 2},
                {"npc": "guard", "count": [1, 1], "radius": 3},
            ],
        },
        {
            "id": "v8_mw_enc_twin_lamps_night_guide",
            "weight": 7,
            "wonder": False,
            "road": "off",
            "time": "night",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer", "argonian"],
            "purpose": "A hooded Twin Lamps abolitionist guiding freed plantation labourers toward a coastal skiff by the glow of two shuttered lanterns.",
            "needs": ["open_ground"],
            "why": ["ascadian_isles.L9.twin_lamps_underground", "morrowind.L9.slavery_question"],
            "questHook": "radiant_favor",
            "props": ["fire"],
            "actors": [
                {"npc": "adventurer", "count": [1, 1], "radius": 3},
                {"npc": "citizen", "count": [1, 2], "radius": 3},
            ],
        },
        {
            "id": "v8_mw_wonder_silt_strider_dusk_call",
            "weight": 8,
            "wonder": True,
            "road": "near",
            "time": "dawn_dusk",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "Wonder moment: a caravan guide tending amber chitin-lanterns at dusk as the low, haunting call of a silt-strider echoes across the ash-valley.",
            "needs": ["road_access", "open_ground"],
            "why": ["morrowind.L6.silt_strider_routes", "morrowind.L7.house_order"],
            "props": ["fire"],
            "actors": [
                {"npc": "citizen", "count": [1, 2], "radius": 3},
                {"entity": "skycraft:torchbug", "count": [3, 5], "radius": 4},
            ],
        },
        {
            "id": "v8_mw_wonder_ghostfence_blue_aurora",
            "weight": 8,
            "wonder": True,
            "road": "on",
            "time": "night",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "Wonder moment: Buoyant Armigers and a pilgrim kneeling on a basalt ridge as the distant blue ancestor-aurora of the Ghostfence ripples against the night sky.",
            "needs": ["night_sky", "open_ground"],
            "why": ["morrowind.L9.ghostfence_holds", "red_mountain.L8.fence_of_the_dead"],
            "props": ["fire"],
            "actors": [
                {"npc": "guard", "count": [1, 2], "radius": 3},
                {"npc": "priest", "count": [1, 1], "radius": 3},
            ],
        },
        {
            "id": "v8_mw_wonder_emperor_parasol_sporefall",
            "weight": 7,
            "wonder": True,
            "road": "near",
            "time": "night",
            "provinces": ["morrowind"],
            "regions": all_mw_regs,
            "cultures": ["dunmer"],
            "purpose": "Wonder moment: golden bioluminescent spores drifting down from a towering Emperor Parasol mushroom while a Telvanni scholar sketches by lantern-light.",
            "needs": ["night_sky", "open_ground"],
            "why": ["morrowind.L3.fungal_forests", "telvannis.L7.lord_and_thrall"],
            "props": [],
            "actors": [
                {"npc": "scholar", "count": [1, 1], "radius": 3},
                {"entity": "skycraft:torchbug", "count": [4, 6], "radius": 4},
            ],
        },
        {
            "id": "v8_mw_wonder_skaal_all_maker_vigil",
            "weight": 7,
            "wonder": True,
            "road": "off",
            "time": "dawn_dusk",
            "provinces": ["morrowind"],
            "regions": ["solstheim", "sheogorad"],
            "cultures": ["nord", "dunmer"],
            "purpose": "Wonder moment: a Skaal shaman and northern hunters chanting the Song of the Wind beside a frost-rimmed standing stone at twilight.",
            "needs": ["open_ground"],
            "why": ["sheogorad.L7.nord_dunmer_mix", "morrowind.L8.ashlander_old_ways"],
            "props": ["fire"],
            "actors": [
                {"npc": "priest", "count": [1, 1], "radius": 3},
                {"npc": "hunter", "count": [1, 2], "radius": 4},
            ],
        },
    ]
    scenes.extend(mw_scenes)
    jsave(scenes_path, scenes)

    # 2. calendar/festivals.json
    fest_path = os.path.join(RES, "calendar", "festivals.json")
    fest_data = jload(fest_path, {"festivals": []})
    fests = [f for f in fest_data.get("festivals", []) if not str(f.get("id", "")).startswith("v8_fest_morrowind_")]

    mw_festivals = [
        {
            "id": "v8_fest_morrowind_stonefalls_inner_sea_charter",
            "name": "Inner Sea Charter-Moot & Ebonheart Regatta",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_stonefalls_inner_sea_charter.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_stonefalls_inner_sea_charter.announce",
            "month": 4,
            "monthId": "rains_hand",
            "monthName": "Rain's Hand",
            "day": 8,
            "dayOfYear": 98,
            "durationDays": 2,
            "province": "morrowind",
            "region": "stonefalls",
            "settlement": "ebonheart",
            "purpose": "Spring opening of the Inner Sea shipping lanes at Ebonheart, where Imperial legates and Great House councillors feast on the harbour terrace and bless the packet-fleets.",
            "needs": ["plaza_anchor", "harbor_bunting", "market_stalls"],
            "why": [
                "stonefalls.L7.imperial_district_vs_dunmer_quarter",
                "stonefalls.L9.grand_council",
            ],
            "festivalQuest": "story_stonefalls_01_double_manifest",
            "overlay": {
                "bannerColor": "red",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["ebonheart_spiced_cod", "stonefalls_ash_yam_loaves", "east_empire_glassware"],
            },
        },
        {
            "id": "v8_fest_morrowind_deshaan_almalexia_mercy",
            "name": "Procession of Mother Morrowind's Mercy",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_deshaan_almalexia_mercy.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_deshaan_almalexia_mercy.announce",
            "month": 3,
            "monthId": "first_seed",
            "monthName": "First Seed",
            "day": 21,
            "dayOfYear": 81,
            "durationDays": 3,
            "province": "morrowind",
            "region": "deshaan",
            "settlement": "mournhold",
            "purpose": "Sacred spring feast across Godsreach and the Great Bazaar of Mournhold celebrating Lady Almalexia's healing charity with alms-tables, brass censers, and open-air plays.",
            "needs": ["plaza_anchor", "temple_procession", "market_stalls"],
            "why": [
                "deshaan.L7.priest_nobility",
                "deshaan.L8.almalexia_mother",
            ],
            "festivalQuest": "story_deshaan_01_writs_of_quarantine",
            "overlay": {
                "bannerColor": "yellow",
                "lanterns": "lantern",
                "bonfire": False,
                "stalls": ["mournhold_honey_cakes", "indoril_incense_cones", "bazaar_silk_ribbons"],
            },
        },
        {
            "id": "v8_fest_morrowind_molag_amur_necrom_all_souls",
            "name": "Vigil of the White Walls & Ancestor-Lantern Night",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_molag_amur_necrom_all_souls.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_molag_amur_necrom_all_souls.announce",
            "month": 10,
            "monthId": "frostfall",
            "monthName": "Frostfall",
            "day": 19,
            "dayOfYear": 289,
            "durationDays": 2,
            "province": "morrowind",
            "region": "molag_amur",
            "settlement": "necrom",
            "purpose": "Solemn autumn pilgrimage in Necrom when thousands of white paper lanterns are hung along the ossuary terraces to honor the ancestors of all Dunmer clans.",
            "needs": ["plaza_anchor", "soul_lanterns", "temple_procession"],
            "why": [
                "molag_amur.L8.holy_mountains",
                "morrowind.L8.ancestor_worship",
            ],
            "festivalQuest": "story_molag_amur_01_sealed_ossuary",
            "overlay": {
                "bannerColor": "white",
                "lanterns": "soul_lantern",
                "bonfire": False,
                "stalls": ["necrom_wax_tapers", "ash_salt_prayer_cakes", "carved_bone_charms"],
            },
        },
        {
            "id": "v8_fest_morrowind_telvannis_spore_conclave",
            "name": "Sadrith Mora Spore-Bloom & Magisterial Fair",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_telvannis_spore_conclave.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_telvannis_spore_conclave.announce",
            "month": 6,
            "monthId": "mid_year",
            "monthName": "Mid Year",
            "day": 22,
            "dayOfYear": 172,
            "durationDays": 3,
            "province": "morrowind",
            "region": "telvannis",
            "settlement": "sadrith_mora",
            "purpose": "Midsummer spore-release beneath Tel Naga in Sadrith Mora, when Mouths auction rare alchemical salts, Telvanni bug-musk, and enchanted trinkets around the Gateway Inn.",
            "needs": ["plaza_anchor", "lantern_posts", "market_stalls"],
            "why": [
                "telvannis.L7.lord_and_thrall",
                "telvannis.L9.wizard_feuds",
            ],
            "festivalQuest": "story_telvannis_01_spores_at_gateway",
            "overlay": {
                "bannerColor": "purple",
                "lanterns": "soul_lantern",
                "bonfire": False,
                "stalls": ["telvanni_bug_musk", "luminous_russula_tarts", "mushroom_spore_lamps"],
            },
        },
        {
            "id": "v8_fest_morrowind_sheogorad_sanctus_beacon",
            "name": "Dagon Fel Whale-Boat Return & Sanctus Vigil",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_sheogorad_sanctus_beacon.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_sheogorad_sanctus_beacon.announce",
            "month": 8,
            "monthId": "last_seed",
            "monthName": "Last Seed",
            "day": 9,
            "dayOfYear": 219,
            "durationDays": 2,
            "province": "morrowind",
            "region": "sheogorad",
            "settlement": "dagon_fel",
            "purpose": "Late-summer harbour feast at Dagon Fel where Nord whalers and Dunmer islanders light the northern watchtower brazier and share roasted horker and mazte.",
            "needs": ["plaza_anchor", "hearth_brazier", "market_stalls"],
            "why": [
                "sheogorad.L7.nord_dunmer_mix",
                "sheogorad.L8.sanctus_shrine",
            ],
            "festivalQuest": "story_sheogorad_01_lanterns_on_the_skerries",
            "overlay": {
                "bannerColor": "cyan",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["smoked_horker_ribs", "dagon_fel_spiced_mead", "sheogorad_amber_beads"],
            },
        },
        {
            "id": "v8_fest_morrowind_ashlands_skar_muster",
            "name": "Muster of Skar & Ashlander Gate-Truce",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_ashlands_skar_muster.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_ashlands_skar_muster.announce",
            "month": 9,
            "monthId": "hearthfire",
            "monthName": "Hearthfire",
            "day": 14,
            "dayOfYear": 254,
            "durationDays": 2,
            "province": "morrowind",
            "region": "vvardenfell_ashlands",
            "settlement": "aldruhn",
            "purpose": "Autumn martial muster in Ald'ruhn when Redoran retainers parade in polished bonemold outside Skar and Ashlander traders open the seasonal hide-market at the western gate.",
            "needs": ["plaza_anchor", "hearth_brazier", "market_stalls"],
            "why": [
                "vvardenfell_ashlands.L7.redoran_duty",
                "vvardenfell_ashlands.L7.ashlander_traders_at_the_gate",
            ],
            "festivalQuest": "story_vvardenfell_ashlands_01_bells_in_the_ash",
            "overlay": {
                "bannerColor": "red",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["redoran_scuttle_pies", "urshilaku_cured_hides", "chitin_journey_lamps"],
            },
        },
        {
            "id": "v8_fest_morrowind_grazelands_wickwheat_sheaf",
            "name": "Vos Golden Wickwheat Harvest & Herd-Compact",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_grazelands_wickwheat_sheaf.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_grazelands_wickwheat_sheaf.announce",
            "month": 8,
            "monthId": "last_seed",
            "monthName": "Last Seed",
            "day": 24,
            "dayOfYear": 234,
            "durationDays": 3,
            "province": "morrowind",
            "region": "grazelands",
            "settlement": "vos",
            "purpose": "Harvest fair on the golden savannah at Vos where Tel Vos farmers and Zainab herders trade fresh wickwheat sheaves, guar-milk cheeses, and hackle-lo leaf.",
            "needs": ["plaza_anchor", "harvest_tables", "market_stalls"],
            "why": [
                "grazelands.L7.nomads_vs_farmers",
                "grazelands.L9.grass_rights",
            ],
            "festivalQuest": "story_grazelands_01_bitter_springs_of_vos",
            "overlay": {
                "bannerColor": "lime",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["vos_wickwheat_loaves", "sweet_hackle_lo_rolls", "zainab_guar_cheese"],
            },
        },
        {
            "id": "v8_fest_morrowind_azuras_coast_moonshadow_vespers",
            "name": "Twilight Vigil of Azura's Coast",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_azuras_coast_moonshadow_vespers.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_azuras_coast_moonshadow_vespers.announce",
            "month": 5,
            "monthId": "second_seed",
            "monthName": "Second Seed",
            "day": 18,
            "dayOfYear": 138,
            "durationDays": 2,
            "province": "morrowind",
            "region": "azuras_coast",
            "settlement": "molag_mar",
            "purpose": "Dusk-to-dawn lantern vigil at Molag Mar and along the eastern headlands celebrating the Star of the Courtyard and blessing pilgrims bound for Mount Assarnibibi.",
            "needs": ["plaza_anchor", "soul_lanterns", "market_stalls"],
            "why": [
                "azuras_coast.L8.azura_shrine",
                "azuras_coast.L8.dreamer_monastery",
            ],
            "festivalQuest": "story_azuras_coast_01_pilgrims_at_molag_mar",
            "overlay": {
                "bannerColor": "blue",
                "lanterns": "soul_lantern",
                "bonfire": True,
                "stalls": ["moonshadow_incense", "molag_mar_pilgrim_cakes", "polished_glass_beads"],
            },
        },
        {
            "id": "v8_fest_morrowind_west_gash_odai_scale_day",
            "name": "Balmora Odai Barge-Fair & Hlaalu Assay Day",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_west_gash_odai_scale_day.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_west_gash_odai_scale_day.announce",
            "month": 7,
            "monthId": "suns_height",
            "monthName": "Sun's Height",
            "day": 11,
            "dayOfYear": 191,
            "durationDays": 3,
            "province": "morrowind",
            "region": "west_gash",
            "settlement": "balmora",
            "purpose": "Midsummer trade jubilee along the bridges of the Odai River in Balmora, when Hlaalu factors, Caldera miners, and guild halls open their courtyard stalls and tap casks of flin.",
            "needs": ["plaza_anchor", "festival_banners", "market_stalls"],
            "why": [
                "west_gash.L7.merchant_city",
                "west_gash.L9.hlaalu_collaboration",
            ],
            "festivalQuest": "story_west_gash_01_ledgers_on_the_odai",
            "overlay": {
                "bannerColor": "orange",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["balmora_spiced_scuttle", "caldera_silver_trinkets", "aged_cyrodilic_flin"],
            },
        },
        {
            "id": "v8_fest_morrowind_bitter_coast_netch_lantern_tide",
            "name": "Gnaar Mok Netch-Lantern Tide & Fisher's Truce",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_bitter_coast_netch_lantern_tide.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_bitter_coast_netch_lantern_tide.announce",
            "month": 11,
            "monthId": "suns_dusk",
            "monthName": "Sun's Dusk",
            "day": 6,
            "dayOfYear": 306,
            "durationDays": 2,
            "province": "morrowind",
            "region": "bitter_coast",
            "settlement": "gnaar_mok",
            "purpose": "Autumn night-festival on the boardwalks of Gnaar Mok where net-fishers hang amber netch-bladder lanterns over the marsh channels to guide boats safely home.",
            "needs": ["plaza_anchor", "lantern_posts", "market_stalls"],
            "why": [
                "bitter_coast.L7.smugglers_and_tax_men",
                "bitter_coast.L8.bitter_superstitions",
            ],
            "festivalQuest": "story_bitter_coast_01_wreck_at_khartag",
            "overlay": {
                "bannerColor": "green",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["grilled_slaughterfish", "luminous_marsh_tea", "boiled_netch_pouches"],
            },
        },
        {
            "id": "v8_fest_morrowind_ascadian_vivec_seven_graces",
            "name": "Jubilee of the Seven Graces & Canton Regatta",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_ascadian_vivec_seven_graces.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_ascadian_vivec_seven_graces.announce",
            "month": 6,
            "monthId": "mid_year",
            "monthName": "Mid Year",
            "day": 5,
            "dayOfYear": 155,
            "durationDays": 3,
            "province": "morrowind",
            "region": "ascadian_isles",
            "settlement": "vivec",
            "purpose": "Grand lagoon festival across the cantons of Vivec City celebrating Lord Vivec's victory and the Seven Graces with decorated gondolas, Temple choir hymns, and open alms-tables.",
            "needs": ["plaza_anchor", "harbor_bunting", "market_stalls"],
            "why": [
                "ascadian_isles.L8.vivec_the_living_god",
                "ascadian_isles.L8.seven_graces",
            ],
            "festivalQuest": "story_ascadian_isles_01_chalk_on_the_cantons",
            "overlay": {
                "bannerColor": "yellow",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["vivec_comberry_wine", "ascadian_saltrice_cakes", "gondolier_silk_pennants"],
            },
        },
        {
            "id": "v8_fest_morrowind_red_mountain_ghostgate_vigil",
            "name": "Vigil of the Two Towers & Ancestor-Pylon Renewal",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_red_mountain_ghostgate_vigil.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_red_mountain_ghostgate_vigil.announce",
            "month": 11,
            "monthId": "suns_dusk",
            "monthName": "Sun's Dusk",
            "day": 23,
            "dayOfYear": 323,
            "durationDays": 2,
            "province": "morrowind",
            "region": "red_mountain",
            "settlement": "ghostgate",
            "purpose": "Solemn martial vigil at Ghostgate when Buoyant Armigers and Ordinators consecrate the Ghostfence bone-pylons with blue soul-braziers and honor those who stand watch against the Blight.",
            "needs": ["plaza_anchor", "soul_lanterns", "temple_procession"],
            "why": [
                "red_mountain.L7.garrison_life",
                "red_mountain.L8.fence_of_the_dead",
            ],
            "festivalQuest": "story_red_mountain_01_dissonance_on_the_fence",
            "overlay": {
                "bannerColor": "light_blue",
                "lanterns": "soul_lantern",
                "bonfire": True,
                "stalls": ["armiger_glass_tallies", "blessed_ash_yam_rations", "hot_trama_root_broth"],
            },
        },
        {
            "id": "v8_fest_morrowind_solstheim_bulwark_and_stone",
            "name": "Raven Rock Bulwark-Watch & All-Maker Hearth-Truce",
            "titleKey": "skycraft.calendar.festival.v8_fest_morrowind_solstheim_bulwark_and_stone.title",
            "announceKey": "skycraft.calendar.festival.v8_fest_morrowind_solstheim_bulwark_and_stone.announce",
            "month": 12,
            "monthId": "evening_star",
            "monthName": "Evening Star",
            "day": 14,
            "dayOfYear": 344,
            "durationDays": 2,
            "province": "morrowind",
            "region": "solstheim",
            "settlement": "raven_rock",
            "purpose": "Winter solstice gathering at Raven Rock where Redoran guards, Tel Mithryn retainers, and visiting Skaal hunters light the great ash-bulwark braziers and tap casks of Shein and Thirsk mead.",
            "needs": ["plaza_anchor", "hearth_brazier", "market_stalls"],
            "why": [
                "morrowind.L9.house_politics",
                "morrowind.L8.ancestor_worship",
            ],
            "festivalQuest": "story_solstheim_01_bulwark_in_the_ash",
            "overlay": {
                "bannerColor": "magenta",
                "lanterns": "lantern",
                "bonfire": True,
                "stalls": ["raven_rock_ash_hopper_skewers", "aged_sadri_sujamma", "thirsk_honey_mead"],
            },
        },
    ]
    fests.extend(mw_festivals)
    fest_data["festivals"] = fests
    jsave(fest_path, fest_data)
    return mw_scenes, mw_festivals


def update_lang_files(stories, mw_scenes, mw_festivals):
    # 1. calendar.json
    cal_path = os.path.join(LANG_DIR, "calendar.json")
    cal = jload(cal_path, {})
    announces = {
        "v8_fest_morrowind_stonefalls_inner_sea_charter": "Lanterns blaze along Ebonheart's harbour-terrace as the Grand Council and East Empire fleets open the Inner Sea Charter-Moot!",
        "v8_fest_morrowind_deshaan_almalexia_mercy": "Brass censers perfume Mournhold's Great Bazaar and Godsreach as the Procession of Mother Morrowind's Mercy begins!",
        "v8_fest_morrowind_molag_amur_necrom_all_souls": "Thousands of white ancestor-lanterns glow along the ossuary walls of Necrom for the Vigil of the White Walls!",
        "v8_fest_morrowind_telvannis_spore_conclave": "Bioluminescent spores drift over Sadrith Mora's Gateway Plaza as the Tel Naga Magisterial Fair opens its stalls!",
        "v8_fest_morrowind_sheogorad_sanctus_beacon": "The northern watchtower brazier blazes above Dagon Fel as Nord whalers and Dunmer islanders celebrate the Whale-Boat Return!",
        "v8_fest_morrowind_ashlands_skar_muster": "Redoran horns echo beneath the giant crab-shell of Skar in Ald'ruhn as the Ashlander Gate-Truce market opens!",
        "v8_fest_morrowind_grazelands_wickwheat_sheaf": "Golden sheaves of wickwheat deck the mushroom-rooted square of Vos for the Grazelands Harvest & Herd-Compact!",
        "v8_fest_morrowind_azuras_coast_moonshadow_vespers": "Soul-lanterns shimmer through the twilight mist at Molag Mar as pilgrims gather for the Vigil of Azura's Coast!",
        "v8_fest_morrowind_west_gash_odai_scale_day": "Banners of House Hlaalu flutter across Balmora's Odai River bridges for the midsummer Barge-Fair & Assay Day!",
        "v8_fest_morrowind_bitter_coast_netch_lantern_tide": "Amber netch-lanterns float above the stilt-piers of Gnaar Mok as fishers celebrate the Bitter Coast Lantern Tide!",
        "v8_fest_morrowind_ascadian_vivec_seven_graces": "Decorated gondolas glide between the golden cantons of Vivec City as choir-bells ring for the Jubilee of the Seven Graces!",
        "v8_fest_morrowind_red_mountain_ghostgate_vigil": "Blue ancestor-braziers flare between the Tower of Dawn and Tower of Dusk at Ghostgate for the Vigil of the Two Towers!",
        "v8_fest_morrowind_solstheim_bulwark_and_stone": "Hearth-fires roar along Raven Rock's southern bulwark as Dunmer councillors and Skaal hunters share the winter Hearth-Truce!",
    }
    for f in mw_festivals:
        cal[f["titleKey"]] = f["name"]
        cal[f["announceKey"]] = announces[f["id"]]
    wonder_texts = {
        "v8_mw_wonder_silt_strider_dusk_call": "A caravan guide tends amber chitin-lanterns at dusk as the low, haunting call of a silt-strider echoes across the ash-valley.",
        "v8_mw_wonder_ghostfence_blue_aurora": "Buoyant Armigers and a pilgrim kneel on a basalt ridge as the distant blue ancestor-aurora of the Ghostfence ripples against the night sky.",
        "v8_mw_wonder_emperor_parasol_sporefall": "Golden bioluminescent spores drift down from a towering Emperor Parasol mushroom while a Telvanni scholar sketches by lantern-light.",
        "v8_mw_wonder_skaal_all_maker_vigil": "A Skaal shaman and northern hunters chant the Song of the Wind beside a frost-rimmed standing stone at twilight.",
    }
    for wid, wtxt in wonder_texts.items():
        cal[f"skycraft.scene.wonder.{wid}"] = wtxt
    jsave(cal_path, cal)

    # 2. quests.json
    q_lang_path = os.path.join(LANG_DIR, "quests.json")
    qlang = jload(q_lang_path, {})
    for rkey, sdata in stories.items():
        qlang[f"quest.skycraft.story.{rkey}.title"] = sdata["title"]
        qlang[f"quest.skycraft.story.{rkey}.rumour"] = sdata["hook"]["rumour"]
        qlang[f"quest.skycraft.story.{rkey}.notice"] = sdata["hook"]["notice"]
        for q in sdata["quests"]:
            qlang[f"quest.skycraft.{q['id']}.title"] = q["title"]
            for st in q["stages"]:
                qlang[f"quest.skycraft.{q['id']}.stage.{st['id']}"] = st["objective"]
    jsave(q_lang_path, qlang)

    # 3. lore.json
    lore_lang_path = os.path.join(LANG_DIR, "lore.json")
    llang = jload(lore_lang_path, {})
    mw_books = {
        "book.skycraft.morrowind_ebonheart_charter_law": "Two Seals on the Inner Sea: Ebonheart Customs Law",
        "book.skycraft.morrowind_mournhold_godsreach_rites": "Incense and Ashes in Godsreach",
        "book.skycraft.morrowind_necrom_ossuary_rolls": "White Walls of Necrom: Custodians of the Ancestors",
        "book.skycraft.morrowind_tel_naga_precedents": "Spore and Precedent: Rulings of Tel Naga",
        "book.skycraft.morrowind_sheogorad_sanctus_bells": "Silent Bells of Sanctus Shrine",
        "book.skycraft.morrowind_skar_redoran_councils": "Beneath the Crab-Shell: The Council of Ald'ruhn",
        "book.skycraft.morrowind_grazelands_wickwheat_pacts": "Wickwheat and Herd-Stone: Compacts of Vos",
        "book.skycraft.morrowind_holamayan_twilight_lamps": "Lamps of Moonshadow: The Cloister of Holamayan",
        "book.skycraft.morrowind_caldera_ebony_assays": "Assay-Books of the Odai and Caldera Seam",
        "book.skycraft.morrowind_bitter_coast_lanterns": "Netch-Lamps in the Mangrove Fog",
        "book.skycraft.morrowind_vivec_canton_graces": "Underworks and High Fane: The Cantons of Vivec",
        "book.skycraft.morrowind_ghostgate_two_towers": "Blue Bone and Blight-Wind: The Watch at Ghostgate",
        "book.skycraft.morrowind_solstheim_ash_and_pine": "Ash-Bulwark and All-Maker Stone: Chronicles of Solstheim",
    }
    llang.update(mw_books)
    jsave(lore_lang_path, llang)

    # 4. guide.json
    guide_path = os.path.join(LANG_DIR, "guide.json")
    glang = jload(guide_path, {})
    guide_updates = {
        "skycraft.guide.arrive.stonefalls": "Volcanic steam vents and basalt sea-walls frame the Imperial port of Ebonheart and the ancient mainland towns of Stonefalls.",
        "skycraft.guide.rumour.stonefalls": "Duplicate Inner Sea customs manifests have surfaced between Ebonheart, Davon's Watch, and Kragenmoor while cargo vanishes into Ash Mountain.",
        "skycraft.guide.arrive.deshaan": "Fertile saltrice plains and sacred avenues lead toward the ringed capital of Mournhold and the southern marshes of Tear.",
        "skycraft.guide.rumour.deshaan": "Forged Temple quarantine writs are being used to impound caravan warehouses in Mournhold while Dwemer bells toll beneath Bamz-Amschend.",
        "skycraft.guide.arrive.molag_amur": "White ossuary walls rise above the eastern coast at Necrom while ash-rivers and basalt crags stretch inland across Molag Amur.",
        "skycraft.guide.rumour.molag_amur": "Corpse-ships wait at Zaappi Docks after grave-brokers stole the ancestral Ossuary Roll from Necrom's lower catacombs.",
        "skycraft.guide.arrive.telvannis": "Giant Emperor Parasol mushrooms tower over the Zafirbel Bay islands of Sadrith Mora, Firewatch, and Port Telvannis.",
        "skycraft.guide.rumour.telvannis": "Council Mouths at Tel Naga are hiring outlanders to prune a rogue Dwemer-grafted spore tower near Tel Dreloth.",
        "skycraft.guide.arrive.sheogorad": "Jagged basalt skerries and cold northern surf surround the Nord-Dunmer fishing port of Dagon Fel and quiet Sanctus Shrine.",
        "skycraft.guide.rumour.sheogorad": "False storm-lanterns on the Sheogorad reefs have been luring cogs onto the rocks near Vas while locals blame the Urshilaku hunters.",
        "skycraft.guide.arrive.vvardenfell_ashlands": "Scoured grey ash-plains and wind-carved foyadas surround the giant crab-shell council hall of Skar at Ald'ruhn.",
        "skycraft.guide.rumour.vvardenfell_ashlands": "Redoran patrols at Ald'ruhn and Maar Gan have uncovered red-wax Sixth House bells smuggled from Falasmaryon and Kogoruhn.",
        "skycraft.guide.arrive.grazelands": "Golden savannah grass and Telvanni mushroom roots meet the Ashlander tent-rings between Vos, Tel Vos, and Zainab Camp.",
        "skycraft.guide.rumour.grazelands": "A leaking Dwemer blight-conduit between Mudan-Mul Egg Mine and Nchardahrk has fouled the grazing springs outside Vos.",
        "skycraft.guide.arrive.azuras_coast": "Basalt headlands and sea-mists shelter the Buoyant Armiger canton of Molag Mar, Divayth Fyr's tower, and hidden Holamayan.",
        "skycraft.guide.rumour.azuras_coast": "Medical skiffs bound for Tel Fyr and pilgrim boats to Holamayan are being intercepted by rogue factors operating out of Telasero.",
        "skycraft.guide.arrive.west_gash": "Terraced Hlaalu stone bridges span the Odai River at Balmora below the ebony mines of Caldera and Moonmoth Legion Fort.",
        "skycraft.guide.rumour.west_gash": "Every third ebony wain from Caldera Mine is vanishing down the smuggling trail toward Hla Oad and Hlormaren.",
        "skycraft.guide.arrive.bitter_coast": "Bioluminescent fungi and netch-bladder lanterns glow through the mangrove fog around the stilt-village of Gnaar Mok.",
        "skycraft.guide.rumour.bitter_coast": "An Imperial revenue cutter vanished off Khartag Point after bribed excise clerks lured the crew into the sea-caves of Nammu.",
        "skycraft.guide.arrive.ascadian_isles": "Placid lagoons and terraced cantons rise at Vivec City above the plantations and pilgrim roads of the Ascadian Isles.",
        "skycraft.guide.rumour.ascadian_isles": "Twin-lantern chalk marks in Vivec's underworks point to an abolitionist safe-route hunted by Dren Plantation mercenaries.",
        "skycraft.guide.arrive.red_mountain": "The glowing blue ancestor-wall of the Ghostfence encircles the blighted volcanic slopes of Red Mountain above Ghostgate.",
        "skycraft.guide.rumour.red_mountain": "Harmonic Sixth House bells inside Nchuleft and Red Mountain's caldera have cracked three bone-pylons along the Ghostfence.",
        "skycraft.guide.arrive.solstheim": "Ash-drifts from Red Mountain cloak Raven Rock and Tel Mithryn in the south while snow-pines shelter the Skaal in the north.",
        "skycraft.guide.rumour.solstheim": "Ash-spawn raids from Fort Frostmoth and a strange sleep-chant around the All-Maker Stones have put Raven Rock and Skaal Village on alert.",
    }
    glang.update(guide_updates)
    jsave(guide_path, glang)


def main():
    places_by_reg = {}
    world_places = {}
    idx_path = os.path.join(RES, "tamriel", "places", "index.json")
    for fn in jload(idx_path, []):
        for p in jload(os.path.join(RES, "tamriel", "places", fn), []):
            if isinstance(p, dict) and p.get("id"):
                world_places[p["id"]] = p
                reg = str(p.get("region", ""))
                if reg.startswith("morrowind/"):
                    places_by_reg.setdefault(reg, []).append(p)

    hook_places = compute_border_hook_places(places_by_reg)
    stories = build_story_files(hook_places)
    update_faction_files(world_places)
    new_quests = update_side_morrowind(places_by_reg, world_places)
    lore_out = build_morrowind_lore(places_by_reg)
    mw_scenes, mw_festivals = update_scenes_and_festivals()
    update_lang_files(stories, mw_scenes, mw_festivals)

    print(
        f"morrowind_quests_lore: wrote {len(stories)} story files (52 main quests), "
        f"{len(new_quests)} new side quests, {len(lore_out['places'])} lore places "
        f"({sum(len(v['notes']) for v in lore_out['places'].values())} notes), "
        f"{len(mw_scenes)} scenes, {len(mw_festivals)} festivals."
    )


if __name__ == "__main__":
    main()
