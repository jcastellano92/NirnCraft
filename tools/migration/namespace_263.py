"""Idempotent loader/name migration only; real API changes still require compilation and porting."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
REPLACEMENTS=[
 ('net.minecraftforge.eventbus.','net.neoforged.bus.'),
 ('net.minecraftforge.api.','net.neoforged.api.'),
 ('net.minecraftforge.fml.','net.neoforged.fml.'),
 ('net.minecraftforge.','net.neoforged.neoforge.'),
 ('net.neoforged.neoforge.common.ForgeConfigSpec','net.neoforged.neoforge.common.ModConfigSpec'),
 ('ForgeConfigSpec','ModConfigSpec'),
 ('net.neoforged.neoforge.common.MinecraftForge','net.neoforged.neoforge.common.NeoForge'),
 ('MinecraftForge.EVENT_BUS','NeoForge.EVENT_BUS'),
 ('Mod.EventBusSubscriber','net.neoforged.fml.common.EventBusSubscriber'),
]
count=0
for parent in ('mod/src/main/java','mod/src/test/java'):
 for path in (ROOT/parent).rglob('*.java'):
  raw=path.read_bytes(); s=raw.decode('utf8'); updated=s
  for old,new in REPLACEMENTS:updated=updated.replace(old,new)
  if updated!=s:path.write_bytes(updated.encode('utf8'));count+=1
old=ROOT/'mod/src/main/resources/META-INF/mods.toml'
new=old.with_name('neoforge.mods.toml')
if old.exists():
 source=old.read_text(encoding='utf8')
 updated=source.replace('modId="forge"','modId="neoforge"').replace('mandatory=true','type="required"').replace('${forge_version_range}','${neo_version_range}')
 new.write_text(updated,encoding='utf8')
 old.rename(ROOT/'mod/forge-1.20.1.mods.toml.disabled')
print('Changed Java files:',count)
