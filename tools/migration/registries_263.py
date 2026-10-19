"""Use public native registries and supplier contracts in a chosen migration ownership scope."""
from pathlib import Path
import argparse,re
from imports_263 import ROOT,owner
KEYS={'ITEMS':'ITEM','BLOCKS':'BLOCK','ENTITY_TYPES':'ENTITY_TYPE','MOB_EFFECTS':'MOB_EFFECT',
 'SOUND_EVENTS':'SOUND_EVENT','PARTICLE_TYPES':'PARTICLE_TYPE','MENU_TYPES':'MENU','FEATURES':'FEATURE_TYPE',
 'BLOCK_ENTITY_TYPES':'BLOCK_ENTITY_TYPE','POTIONS':'POTION','ATTRIBUTES':'ATTRIBUTE','FLUIDS':'FLUID',
 'RECIPE_SERIALIZERS':'RECIPE_SERIALIZER','RECIPE_TYPES':'RECIPE_TYPE','ENCHANTMENTS':'ENCHANTMENT',
 'VILLAGER_PROFESSIONS':'VILLAGER_PROFESSION','POI_TYPES':'POINT_OF_INTEREST_TYPE','PAINTING_VARIANTS':'PAINTING_VARIANT'}
def migrate(scope):
 count=0
 for p in (ROOT/'mod/src/main/java/com/nirncraft').rglob('*.java'):
  if owner(p)!=scope:continue
  raw=p.read_bytes();s=raw.decode('utf8');out=s
  out=out.replace('import net.neoforged.neoforge.registries.RegistryObject;','import java.util.function.Supplier;')
  out=re.sub(r'\bRegistryObject\b','Supplier',out)
  out=out.replace('import net.neoforged.neoforge.registries.ForgeRegistries;','import net.minecraft.core.registries.BuiltInRegistries;')
  for old,new in KEYS.items():out=out.replace('ForgeRegistries.'+old,'BuiltInRegistries.'+new)
  if out!=s:p.write_bytes(out.encode('utf8'));count+=1
 print(scope,'registry/supplier files:',count)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--owner',choices=['root','world','ui','network'],required=True);migrate(p.parse_args().owner)
