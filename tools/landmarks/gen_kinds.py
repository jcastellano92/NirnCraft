"""Generates mod/src/main/resources/skycraft/landmarks/kinds.json: the table of natural-landmark and vignette kinds.

A kind is a builder (a geometry routine in com.skycraft.landmarks.build), a style, the biome families it belongs to
(with weights), and a footprint. Many kinds share a builder and differ by style, rock set and environment; every
instance still differs through its seeded size, weathering, overgrowth, rock and wood.

  py -3 -I tools/landmarks/gen_kinds.py          # rewrite kinds.json
  py -3 -I tools/landmarks/gen_kinds.py --check  # validate only (also run by tools/qa/data_checks.py)
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'landmarks', 'kinds.json')

FAMILIES = ['tundra', 'forest', 'plains', 'marsh', 'desert', 'savanna', 'ash', 'volcanic', 'jungle', 'crystal', 'mushroom',
            'mountain', 'coast', 'shore', 'high']

KINDS = []


def kind(tier, kid, builder, style, radius, w, noun='', cast=None, secret=0.0, note='', sign=None, spread=None, params=None,
         provinces=None, not_provinces=None, needs=None):
    k = {'id': kid, 'tier': tier, 'builder': builder, 'radius': radius, 'w': w}
    if style:
        k['style'] = style
    if noun:
        k['noun'] = noun
    if cast:
        k['cast'] = cast
    if secret:
        k['secret'] = secret
    if note:
        k['note'] = note
    if sign is None:
        sign = tier in ('L', 'XL')
    if sign:
        k['sign'] = True
    if spread is not None:
        k['spread'] = spread
    if params:
        k['params'] = params
    if provinces:
        k['provinces'] = provinces
    if not_provinces:
        k['not'] = not_provinces
    if needs:
        k['needs'] = needs
    KINDS.append(k)


# ---------------------------------------------------------------- S: vignettes
def S(kid, builder, style, radius, w, **kw):
    kind('S', kid, builder, style, radius, w, **kw)


ALLS = {'tundra': 2, 'forest': 2, 'plains': 2, 'marsh': 2, 'desert': 2, 'savanna': 2, 'ash': 2, 'volcanic': 1, 'jungle': 2,
        'crystal': 2, 'mushroom': 1, 'mountain': 2, 'coast': 1}

S('s_grave_headstone', 'grave', 'headstone', 4, dict(ALLS, forest=4, plains=4, tundra=3))
S('s_grave_cross', 'grave', 'cross', 4, {'forest': 3, 'plains': 4, 'tundra': 2, 'mountain': 2, 'marsh': 2, 'savanna': 1, 'crystal': 1, 'coast': 1})
S('s_grave_cairn', 'grave', 'cairn', 4, {'tundra': 4, 'mountain': 4, 'plains': 1, 'ash': 2, 'volcanic': 3, 'coast': 2, 'desert': 1, 'forest': 1})
S('s_grave_slab', 'grave', 'slab', 4, {'desert': 4, 'savanna': 3, 'ash': 3, 'volcanic': 2, 'plains': 1, 'crystal': 2, 'coast': 1, 'mountain': 1})
S('s_grave_mound', 'grave', 'mound', 5, {'forest': 3, 'plains': 3, 'jungle': 2, 'marsh': 2, 'tundra': 2, 'mushroom': 1, 'coast': 1})
S('s_grave_stakes', 'grave', 'stakes', 4, {'savanna': 3, 'desert': 2, 'jungle': 3, 'marsh': 2, 'ash': 1, 'tundra': 1, 'plains': 1})
S('s_grave_sunken', 'grave', 'sunken', 4, {'marsh': 4, 'jungle': 3, 'forest': 2, 'coast': 1, 'mushroom': 2, 'shore': 2})
S('s_grave_flowers', 'grave', 'flowers', 4, {'forest': 3, 'plains': 4, 'jungle': 2, 'crystal': 3, 'savanna': 1, 'shore': 1})
S('s_cairn_pile', 'cairn_token', 'pile', 3, dict(ALLS, mountain=3, tundra=3))
S('s_cairn_spire', 'cairn_token', 'spire', 3, {'mountain': 4, 'tundra': 3, 'desert': 2, 'ash': 2, 'volcanic': 3, 'coast': 2, 'savanna': 1, 'high': 3})
S('s_cairn_ring', 'cairn_token', 'ring', 4, {'plains': 3, 'forest': 2, 'tundra': 3, 'ash': 2, 'savanna': 2, 'mountain': 1, 'marsh': 1})
S('s_cairn_marker', 'cairn_token', 'marker', 3, dict(ALLS, crystal=3, ash=3))
S('s_bedroll_cold', 'bedroll', 'roll', 5, dict(ALLS, plains=3, forest=3, desert=3))
S('s_bedroll_leanto', 'bedroll', 'lean_to', 5, {'forest': 3, 'tundra': 3, 'jungle': 3, 'mountain': 2, 'marsh': 2, 'ash': 1, 'savanna': 1})
S('s_lovers_tree', 'lovers_tree', 'carved', 4, {'forest': 4, 'plains': 3, 'jungle': 1, 'crystal': 4, 'savanna': 1, 'coast': 1, 'shore': 2})
S('s_lovers_tree_ribbon', 'lovers_tree', 'ribbon', 4, {'plains': 3, 'crystal': 3, 'forest': 2, 'savanna': 1, 'shore': 1})
S('s_offering_niche', 'offering', 'niche', 3, dict(ALLS, mountain=3, forest=3))
S('s_offering_stump', 'offering', 'stump', 3, {'forest': 4, 'jungle': 3, 'marsh': 3, 'plains': 1, 'mushroom': 3, 'tundra': 1})
S('s_offering_bowl', 'offering', 'bowl', 3, {'desert': 3, 'savanna': 3, 'mountain': 2, 'ash': 3, 'crystal': 3, 'tundra': 1, 'volcanic': 1})
S('s_offering_bones', 'offering', 'bones', 3, {'tundra': 3, 'ash': 3, 'volcanic': 3, 'desert': 2, 'marsh': 2, 'mountain': 1, 'mushroom': 1})
S('s_pack_satchel', 'pack', 'satchel', 3, dict(ALLS, plains=3, forest=3, mountain=3))
S('s_pack_crate', 'pack', 'crate', 3, {'coast': 3, 'plains': 3, 'forest': 2, 'savanna': 2, 'shore': 3, 'marsh': 1, 'desert': 1})
S('s_pack_saddlebag', 'pack', 'saddlebag', 3, {'plains': 3, 'desert': 3, 'savanna': 3, 'mountain': 2, 'tundra': 1, 'ash': 1})
S('s_warn_skull_pike', 'warning', 'skull_pike', 4, {'ash': 3, 'volcanic': 3, 'marsh': 2, 'mountain': 3, 'forest': 1, 'tundra': 1, 'desert': 1, 'jungle': 1})
S('s_warn_crow_post', 'warning', 'crow_post', 4, {'plains': 4, 'forest': 2, 'savanna': 3, 'desert': 1, 'marsh': 1, 'tundra': 1})
S('s_warn_scrawl', 'warning', 'scrawl', 4, dict(ALLS, mountain=3, marsh=3))
S('s_spring_basin', 'spring', 'basin', 4, {'forest': 3, 'mountain': 3, 'plains': 2, 'tundra': 1, 'jungle': 2, 'desert': 2, 'crystal': 2, 'savanna': 1})
S('s_spring_trough', 'spring', 'trough', 3, {'plains': 3, 'forest': 2, 'savanna': 3, 'mountain': 1, 'desert': 1, 'tundra': 1})
S('s_remains_ribcage', 'remains', 'ribcage', 5, {'tundra': 2, 'desert': 4, 'savanna': 3, 'plains': 1, 'ash': 2, 'mountain': 1, 'volcanic': 1})
S('s_remains_driftwood', 'remains', 'driftwood', 5, {'coast': 6, 'shore': 3, 'marsh': 1})
S('s_remains_antlers', 'remains', 'antlers', 4, {'tundra': 3, 'forest': 3, 'mountain': 2, 'plains': 1, 'ash': 1, 'jungle': 1})
S('s_fire_cold', 'fire_ring', 'cold', 5, dict(ALLS, plains=3, forest=3))
S('s_fire_embers', 'fire_ring', 'embers', 5, {'forest': 3, 'plains': 3, 'tundra': 2, 'coast': 2, 'savanna': 1, 'mountain': 1})
S('s_charms_tree', 'charms', 'wind', 4, {'forest': 2, 'marsh': 3, 'ash': 3, 'tundra': 1, 'mountain': 1, 'volcanic': 3, 'jungle': 1, 'savanna': 1})


# ---------------------------------------------------------------- M: scenes
def M(kid, builder, style, radius, w, noun, **kw):
    kind('M', kid, builder, style, radius, w, noun=noun, **kw)


M('m_pilgrim_stone', 'pilgrim', 'stone', 6, {'forest': 3, 'plains': 3, 'tundra': 2, 'mountain': 3, 'desert': 1, 'coast': 1, 'savanna': 1, 'ash': 1, 'volcanic': 1}, 'Rest',
  cast=['npc:priest'], note='pilgrim')
M('m_pilgrim_grove', 'pilgrim', 'grove', 6, {'forest': 3, 'jungle': 3, 'marsh': 2, 'plains': 1, 'crystal': 3, 'mushroom': 2}, 'Grove',
  cast=['npc:priest'], note='pilgrim')
M('m_pilgrim_pool', 'pilgrim', 'pool', 6, {'mountain': 2, 'tundra': 2, 'shore': 3, 'forest': 1, 'ash': 1, 'crystal': 2, 'volcanic': 1}, 'Pool',
  cast=['npc:priest'], note='pilgrim')
M('m_hermit_goats', 'hermit', '', 9, {'mountain': 4, 'tundra': 3, 'forest': 2, 'plains': 2, 'marsh': 1, 'desert': 1, 'ash': 1, 'volcanic': 1}, 'Holding',
  cast=['npc:apothecary', 'mob:minecraft:goat', 'mob:minecraft:goat'], note='hermit')
M('m_hermit_marsh', 'hermit', 'marsh', 9, {'marsh': 4, 'jungle': 3, 'mushroom': 3, 'shore': 2}, 'Hut',
  cast=['npc:apothecary'], note='hermit')
M('m_hunters_kill', 'hunters', '', 7, {'forest': 4, 'tundra': 4, 'plains': 3, 'mountain': 3, 'marsh': 1, 'jungle': 2, 'savanna': 2, 'ash': 1}, 'Camp',
  cast=['npc:hunter', 'npc:hunter'], note='hunter')
M('m_travellers_rest', 'travellers', '', 6, {'plains': 4, 'forest': 3, 'desert': 3, 'savanna': 3, 'tundra': 2, 'mountain': 1, 'ash': 2, 'coast': 2, 'marsh': 1, 'jungle': 1, 'volcanic': 1},
  'Fire', cast=['npc:citizen', 'npc:merchant'], note='traveller')
M('m_smuggler_cave', 'smuggler', '', 7, {'coast': 5, 'shore': 4, 'marsh': 2, 'ash': 1}, 'Drop', note='smuggler')
M('m_ghost_stone', 'ghost_ruin', 'stone', 8, {'forest': 3, 'plains': 3, 'tundra': 2, 'mountain': 1, 'marsh': 2, 'ash': 2, 'coast': 1, 'desert': 1, 'savanna': 1}, 'House', note='ghost')
M('m_ghost_timber', 'ghost_ruin', 'timber', 8, {'forest': 3, 'marsh': 3, 'jungle': 2, 'tundra': 2, 'plains': 1, 'mushroom': 1}, 'Hall', note='ghost')
M('m_ghost_mud', 'ghost_ruin', 'mud', 8, {'marsh': 3, 'ash': 3, 'desert': 3, 'savanna': 2, 'jungle': 1, 'volcanic': 1}, 'Hearth', note='ghost')
M('m_ambush_cart', 'ambush', '', 7, {'plains': 3, 'forest': 3, 'savanna': 2, 'desert': 2, 'tundra': 2, 'mountain': 1, 'ash': 1, 'marsh': 1}, 'Wreck', note='ambush')
M('m_skirmish', 'skirmish', '', 9, {'plains': 3, 'forest': 2, 'tundra': 2, 'mountain': 2, 'desert': 2, 'ash': 2, 'savanna': 1, 'marsh': 1, 'volcanic': 1}, 'Field')
M('m_boat_wreck', 'boat_wreck', '', 7, {'coast': 5, 'shore': 5, 'marsh': 2}, 'Wreck', note='traveller')
M('m_sacrifice', 'sacrifice', '', 6, {'ash': 3, 'volcanic': 3, 'marsh': 2, 'jungle': 2, 'tundra': 1, 'mountain': 1, 'desert': 1, 'mushroom': 2}, 'Altar', note='scholar')
M('m_hunting_blind', 'blind', '', 5, {'forest': 3, 'tundra': 3, 'jungle': 2, 'marsh': 2, 'plains': 1, 'mountain': 1, 'savanna': 1}, 'Blind')
M('m_prospector_claim', 'claim', '', 6, {'mountain': 4, 'tundra': 2, 'desert': 2, 'ash': 3, 'volcanic': 3, 'savanna': 1}, 'Claim')
M('m_dolmen', 'dolmen', '', 6, {'plains': 3, 'forest': 2, 'tundra': 3, 'mountain': 2, 'coast': 2, 'marsh': 1, 'ash': 1}, 'Dolmen')
M('m_dig_site', 'dig', '', 10, {'desert': 4, 'savanna': 3, 'plains': 2, 'ash': 2, 'forest': 1, 'tundra': 1, 'jungle': 1, 'crystal': 2, 'volcanic': 1}, 'Dig',
  note='scholar')
M('m_beacon_pyre', 'beacon', '', 5, {'mountain': 3, 'tundra': 2, 'coast': 3, 'plains': 1, 'ash': 1, 'volcanic': 1, 'desert': 1, 'high': 3}, 'Beacon')


# ---------------------------------------------------------------- L: formations
def L(kid, builder, style, radius, w, noun, secret=0.17, **kw):
    kind('L', kid, builder, style, radius, w, noun=noun, secret=secret, **kw)


L('l_rock_arch', 'rock_arch', 'natural', 20, {'tundra': 2, 'forest': 1, 'plains': 2, 'desert': 4, 'savanna': 3, 'ash': 2, 'volcanic': 1, 'coast': 3, 'mountain': 2, 'jungle': 1}, 'Arch')
L('l_rock_arch_twin', 'rock_arch', 'twin', 20, {'desert': 2, 'savanna': 2, 'coast': 2, 'mountain': 2, 'ash': 1, 'plains': 1}, 'Arches')
L('l_ice_arch', 'rock_arch', 'natural', 20, {'tundra': 3, 'mountain': 1}, 'Ice Arch', params={'rock': 'ice'})
L('l_hoodoos', 'hoodoos', '', 20, {'desert': 5, 'savanna': 4, 'ash': 3, 'mountain': 1, 'plains': 1, 'tundra': 1, 'volcanic': 2}, 'Hoodoos')
L('l_hoodoos_ash', 'hoodoos', '', 20, {'ash': 3, 'volcanic': 3, 'tundra': 1}, 'Chimneys', params={'rock': 'ashrock'})
L('l_split_boulder', 'split_boulder', '', 12, {'forest': 3, 'plains': 2, 'tundra': 3, 'mountain': 3, 'marsh': 1, 'jungle': 2, 'ash': 1, 'volcanic': 1, 'coast': 2}, 'Cleft', spread=14)
L('l_menhir_lone', 'menhir', 'lone', 15, {'plains': 4, 'forest': 2, 'tundra': 3, 'marsh': 1, 'mountain': 2, 'desert': 1, 'savanna': 2, 'ash': 2, 'coast': 2}, 'Stone')
L('l_menhir_leaning', 'menhir', 'leaning', 15, {'tundra': 2, 'plains': 2, 'forest': 1, 'mountain': 2, 'ash': 2, 'volcanic': 2, 'desert': 1}, 'Leaning Stone')
L('l_menhir_broken', 'menhir', 'broken', 15, {'forest': 1, 'plains': 2, 'tundra': 2, 'jungle': 1, 'ash': 1, 'marsh': 1, 'mountain': 1, 'desert': 1}, 'Stone')
L('l_sinkhole_dry', 'sinkhole', 'dry', 16, {'plains': 3, 'forest': 2, 'desert': 2, 'savanna': 2, 'tundra': 1, 'mountain': 1, 'ash': 1}, 'Sink')
L('l_sinkhole_pond', 'sinkhole', 'pond', 16, {'forest': 3, 'jungle': 3, 'marsh': 2, 'plains': 2, 'tundra': 1, 'coast': 1, 'savanna': 1, 'mushroom': 2}, 'Pit')
L('l_tower_ruin', 'tower_ruin', '', 16, {'forest': 3, 'plains': 3, 'tundra': 2, 'mountain': 3, 'desert': 1, 'savanna': 1, 'ash': 2, 'coast': 2, 'marsh': 1, 'jungle': 1, 'high': 3}, 'Tower')
L('l_cliff_nests', 'cliff_nests', '', 18, {'mountain': 4, 'coast': 3, 'tundra': 2, 'desert': 2, 'ash': 1, 'volcanic': 2, 'savanna': 1, 'high': 3}, 'Eyrie', spread=16)
L('l_petrified_tree', 'petrified_tree', '', 18, {'desert': 3, 'ash': 3, 'volcanic': 2, 'savanna': 2, 'tundra': 2, 'forest': 1, 'jungle': 1, 'marsh': 1, 'crystal': 2}, 'Snag')
L('l_bone_field_dragon', 'bone_field', 'dragon', 22, {'tundra': 2, 'desert': 3, 'ash': 2, 'volcanic': 2, 'mountain': 2, 'savanna': 1}, 'Bones')
L('l_bone_field_mammoth', 'bone_field', 'mammoth', 22, {'tundra': 4, 'plains': 2, 'forest': 1, 'mountain': 1, 'savanna': 2}, 'Bones')
L('l_bone_field_leviathan', 'bone_field', 'leviathan', 22, {'coast': 5, 'marsh': 1, 'desert': 1}, 'Ribs')
L('l_terraces_springs', 'terraces', '', 18, {'tundra': 3, 'mountain': 3, 'volcanic': 3, 'ash': 2, 'forest': 1, 'jungle': 1, 'marsh': 1}, 'Springs')
L('l_geode', 'geode', '', 12, {'mountain': 3, 'tundra': 2, 'desert': 2, 'crystal': 5, 'volcanic': 1, 'ash': 1, 'savanna': 1, 'plains': 1}, 'Geode')
L('l_ice_fall', 'ice_fall', '', 16, {'tundra': 5, 'mountain': 2}, 'Frostfall', spread=16)
L('l_glass_dune', 'glass_dune', '', 22, {'ash': 4, 'volcanic': 3, 'desert': 2}, 'Glassfield')
L('l_columns', 'columns', '', 16, {'volcanic': 4, 'ash': 3, 'coast': 2, 'mountain': 2, 'tundra': 1, 'plains': 1}, 'Pillars')
L('l_sea_stacks', 'sea_stacks', '', 20, {'coast': 6}, 'Stacks', needs=['coast'])
L('l_mushroom_grove', 'mushrooms', '', 18, {'mushroom': 5, 'marsh': 3, 'jungle': 2, 'forest': 2}, 'Grove')
L('l_vent_sulfur', 'vent', 'sulfur', 14, {'volcanic': 4, 'ash': 3, 'tundra': 2, 'mountain': 1, 'desert': 1}, 'Vent')
L('l_vent_tar', 'vent', 'tar', 14, {'desert': 2, 'savanna': 2, 'marsh': 2, 'ash': 1, 'plains': 1, 'volcanic': 1}, 'Tarpit')
L('l_crater_impact', 'crater', '', 18, {'plains': 2, 'desert': 3, 'tundra': 2, 'ash': 2, 'savanna': 2, 'mountain': 1, 'volcanic': 1, 'forest': 1}, 'Scar')
L('l_statue_head', 'statue', 'head', 16, {'forest': 2, 'plains': 2, 'desert': 3, 'jungle': 3, 'crystal': 2, 'ash': 1, 'tundra': 1, 'savanna': 1, 'coast': 1}, 'Head')
L('l_statue_hand', 'statue', 'hand', 16, {'desert': 2, 'plains': 2, 'savanna': 2, 'forest': 1, 'jungle': 1, 'tundra': 1, 'ash': 1}, 'Hand')
L('l_statue_torso', 'statue', 'torso', 18, {'forest': 2, 'jungle': 2, 'desert': 2, 'ash': 1, 'crystal': 1, 'mountain': 1, 'plains': 1}, 'Colossus')
L('l_aqueduct', 'aqueduct', '', 22, {'plains': 3, 'forest': 2, 'desert': 3, 'savanna': 3, 'crystal': 2, 'coast': 1}, 'Aqueduct', spread=10)
L('l_bridge_stub', 'aqueduct', 'bridge', 20, {'forest': 2, 'plains': 2, 'marsh': 1, 'mountain': 1, 'tundra': 1, 'savanna': 1}, 'Span', spread=10)
L('l_fallen_log', 'fallen_log', '', 20, {'forest': 4, 'jungle': 4, 'marsh': 3, 'tundra': 2, 'mushroom': 2}, 'Trunk')
L('l_rune_circle', 'rune_circle', '', 18, {'plains': 3, 'forest': 2, 'tundra': 3, 'mountain': 2, 'ash': 1, 'marsh': 1, 'coast': 1}, 'Circle')
L('l_ice_spires', 'spikes', '', 18, {'tundra': 4, 'mountain': 2}, 'Spires', params={'rock': 'ice'})
L('l_obsidian_fangs', 'spikes', '', 18, {'volcanic': 4, 'ash': 3}, 'Fangs', params={'rock': 'obsidian'})
L('l_dry_lake', 'dry_lake', '', 20, {'desert': 4, 'savanna': 4, 'plains': 1, 'ash': 1, 'tundra': 1}, 'Pan')
L('l_drowned_grove', 'drowned_grove', '', 16, {'marsh': 5, 'jungle': 2, 'forest': 1, 'shore': 3}, 'Grove')
L('l_old_walls', 'old_walls', '', 18, {'plains': 3, 'forest': 3, 'tundra': 2, 'marsh': 1, 'mountain': 1, 'coast': 1, 'savanna': 1}, 'Holding')
L('l_fins', 'fins', '', 18, {'desert': 4, 'savanna': 3, 'ash': 2, 'mountain': 2, 'coast': 1}, 'Fins')
L('l_butte', 'butte', '', 14, {'desert': 3, 'savanna': 4, 'plains': 1, 'ash': 2, 'mountain': 1, 'volcanic': 1}, 'Butte', spread=14)


# ---------------------------------------------------------------- XL: seen from afar
def XL(kid, builder, style, w, noun, params=None, **kw):
    kind('XL', kid, builder, style, 22, w, noun=noun, params=params, **kw)


XL('xl_colossal_tree_forest', 'colossal_tree', '', {'forest': 4, 'plains': 2, 'jungle': 1, 'coast': 1}, 'Oak', params={'shape': 'broadleaf'})
XL('xl_colossal_tree_jungle', 'colossal_tree', '', {'jungle': 5, 'marsh': 2, 'savanna': 1}, 'Giant', params={'shape': 'broadleaf'})
XL('xl_colossal_tree_marsh', 'colossal_tree', '', {'marsh': 4, 'mushroom': 2, 'jungle': 1}, 'Mangrove', params={'shape': 'broadleaf'})
XL('xl_colossal_tree_tundra', 'colossal_tree', '', {'tundra': 4, 'mountain': 1}, 'Pine', params={'shape': 'conifer'})
XL('xl_colossal_tree_dead', 'colossal_tree', '', {'ash': 4, 'volcanic': 3, 'desert': 1, 'tundra': 1, 'marsh': 2}, 'Snag', params={'shape': 'dead'})
XL('xl_colossal_tree_savanna', 'colossal_tree', '', {'savanna': 5, 'desert': 2, 'plains': 1}, 'Baobab', params={'shape': 'broadleaf'})
XL('xl_colossal_tree_crystal', 'colossal_tree', '', {'crystal': 5}, 'Blossom', params={'shape': 'broadleaf'})
XL('xl_fungal_spire', 'fungal_spire', '', {'mushroom': 5, 'marsh': 2, 'jungle': 1}, 'Spire')
XL('xl_giant_ribcage_dragon', 'ribcage', 'dragon', {'tundra': 2, 'desert': 2, 'volcanic': 2, 'mountain': 2, 'ash': 1}, 'Ribs')
XL('xl_giant_ribcage_leviathan', 'ribcage', 'leviathan', {'coast': 5, 'marsh': 1, 'desert': 1}, 'Bones')
XL('xl_giant_ribcage_giant', 'ribcage', 'giant', {'tundra': 3, 'plains': 2, 'forest': 1, 'savanna': 2, 'marsh': 1, 'jungle': 1, 'coast': 1}, 'Rest')
XL('xl_bridge_arch', 'bridge_arch', '', {'desert': 3, 'savanna': 3, 'mountain': 2, 'plains': 1, 'ash': 1, 'tundra': 1, 'coast': 1}, 'Bridge')
XL('xl_spire_stack', 'spire_stack', '', {'mountain': 4, 'tundra': 2, 'ash': 2, 'volcanic': 2, 'desert': 2, 'savanna': 1, 'coast': 2, 'forest': 1, 'jungle': 1}, 'Needles')
XL('xl_spire_twist', 'spire_twist', '', {'desert': 4, 'savanna': 2, 'ash': 1, 'volcanic': 1, 'coast': 1}, 'Twist')
XL('xl_antler_spires', 'antler_spires', '', {'ash': 3, 'tundra': 2, 'mountain': 2, 'volcanic': 2}, 'Antlers')
XL('xl_crater_lake', 'crater_lake', '', {'mountain': 3, 'volcanic': 3, 'forest': 1, 'plains': 2, 'tundra': 2, 'ash': 1, 'jungle': 1, 'marsh': 1}, 'Mirror')
XL('xl_mesa_path', 'mesa_path', '', {'desert': 4, 'savanna': 4, 'plains': 2, 'mountain': 1, 'ash': 1, 'coast': 1, 'volcanic': 1}, 'Table')
XL('xl_crystal_spire', 'crystal_spire', '', {'crystal': 5, 'tundra': 1, 'mountain': 1}, 'Crystal')
XL('xl_glacier_tower', 'glacier_tower', '', {'tundra': 5, 'mountain': 2}, 'Shard')
XL('xl_obsidian_spire', 'obsidian_spire', '', {'volcanic': 5, 'ash': 4}, 'Spire')


def check():
    errs = []
    ids = set()
    builders = set()
    src = open(os.path.join(ROOT, 'mod', 'src', 'main', 'java', 'com', 'skycraft', 'landmarks', 'build', 'Builders.java'),
               encoding='utf-8').read()
    known = set(re.findall(r'case "([a-z_]+)" ->', src))
    caps = {'S': 8, 'M': 12, 'L': 22, 'XL': 22}
    for k in KINDS:
        if k['id'] in ids:
            errs.append('duplicate id ' + k['id'])
        ids.add(k['id'])
        if k['builder'] not in known:
            errs.append('%s: unknown builder %s' % (k['id'], k['builder']))
        if k['radius'] > caps[k['tier']]:
            errs.append('%s: radius %d over the %s cap %d' % (k['id'], k['radius'], k['tier'], caps[k['tier']]))
        for f in k['w']:
            if f not in FAMILIES:
                errs.append('%s: unknown family %s' % (k['id'], f))
        if not k['w']:
            errs.append(k['id'] + ': no weights')
    # lore: sign lines are 15 characters or fewer
    lore = json.load(open(os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'landmarks', 'lore.json'),
                          encoding='utf-8'))
    for grp, lines in lore['epitaph'].items():
        for s in lines:
            if len(s) > 15:
                errs.append('epitaph too long (%d): %s/%s' % (len(s), grp, s))
    for s in lore['trades'] + lore['warnings']:
        if len(s) > 15:
            errs.append('sign line too long: ' + s)
    for cat, notes in lore['notes'].items():
        for s in notes:
            if len(s) > 330:
                errs.append('note too long for a page (%d): %s' % (len(s), s[:40]))
    # per tier x family coverage (the major families need at least 6 kinds per tier)
    for fam in ['tundra', 'marsh', 'desert', 'ash', 'volcanic', 'jungle', 'coast', 'mountain']:
        for t in ['S', 'M', 'L', 'XL']:
            n = sum(1 for k in KINDS if k['tier'] == t and k['w'].get(fam, 0) > 0)
            if n < 6:
                errs.append('coverage: %s %s has only %d kinds' % (fam, t, n))
    return errs


def main():
    errs = check()
    for e in errs:
        print('ERR', e)
    if '--check' in sys.argv:
        print('landmark kinds: %d, %d problems' % (len(KINDS), len(errs)))
        return 1 if errs else 0
    doc = {'_doc': 'Generated by tools/landmarks/gen_kinds.py. Kinds of natural landmarks and vignettes; see docs/LANDMARKS.md.',
           'version': 1, 'kinds': KINDS}
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(doc, f, indent=1)
        f.write('\n')
    # display names: "s_grave_headstone" -> "Grave: Headstone" (the lang module file of the landmarks)
    lang = {'landmarks.skycraft.found': 'Found: %s'}
    for k in KINDS:
        parts = k['id'].split('_')[1:]
        title = parts[0].capitalize() + (': ' + ' '.join(p.capitalize() for p in parts[1:]) if len(parts) > 1 else '')
        lang['landmarks.skycraft.kind.' + k['id']] = title
    lang_path = os.path.join(ROOT, 'mod', 'src', 'main', 'lang', 'skycraft', 'natural_landmarks.json')
    with open(lang_path, 'w', encoding='utf-8') as f:
        json.dump(lang, f, indent=2, sort_keys=True)
        f.write('\n')
    print('wrote %s: %d kinds (%s)' % (OUT, len(KINDS), ', '.join(
        '%s %d' % (t, sum(1 for k in KINDS if k['tier'] == t)) for t in ['S', 'M', 'L', 'XL'])))
    return 1 if errs else 0


if __name__ == '__main__':
    sys.exit(main())
