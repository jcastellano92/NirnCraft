import sys, os, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from mca import World
import numpy as np
from explore import *

EXACT = set('''air cave_air void_air short_grass grass tall_grass fern large_fern dead_bush snow vine glow_lichen ladder lever
torch wall_torch soul_torch soul_wall_torch redstone_torch redstone_wall_torch lantern soul_lantern chain tripwire tripwire_hook
string sweet_berry_bush bamboo_sapling hanging_roots moss_carpet light structure_void fire soul_fire redstone_wire rail powered_rail
detector_rail activator_rail kelp seagrass tall_seagrass kelp_plant lily_pad sugar_cane flower_pot poppy dandelion cornflower
lily_of_the_valley peony lilac sunflower rose_bush wheat carrots potatoes beetroots azalea flowering_azalea candle
brown_mushroom red_mushroom crimson_roots warped_roots nether_sprouts pitcher_plant torchflower pink_petals spore_blossom
small_dripleaf big_dripleaf_stem frogspawn sculk_vein cobweb_not bell_not'''.split())
ENDS = ('_sign','_wall_sign','_hanging_sign','_banner','_wall_banner','_carpet','_button','_pressure_plate','_door','_trapdoor',
        '_sapling','_tulip','_orchid','_allium','_bluet','_daisy','_crops','_stem','_candle','_head','_skull','_wall_head','_wall_skull',
        '_torch','_lantern','_fern')
STARTS = ('potted_',)
NOSUPPORT_ENDS = ('_fence','_fence_gate','_wall','_pane','_bars')
def passable_name(s):
    return s in EXACT or s.endswith(ENDS) or s.startswith(STARTS) or s in ('iron_bars_not',)
def load_world(path=None):
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))
    return World(path or os.environ.get('NIRNCRAFT_WORLD') or os.path.join(root, 'mod', 'run', 'world'))
def classify(w):
    n=len(w.names)
    passable=np.zeros(n,bool); fluid=np.zeros(n,bool); support=np.zeros(n,bool)
    for i,name in enumerate(w.names):
        s=name.split(':')[1]
        if 'water' in s or 'lava' in s and 'cauldron' not in s: fluid[i]=True; continue
        if passable_name(s): passable[i]=True; continue
        if s.endswith(NOSUPPORT_ENDS) or s in ('iron_bars','chain_not'): continue   # obstacle, not a floor
        support[i]=True
    return passable,fluid,support
