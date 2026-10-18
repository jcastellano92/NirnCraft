"""Port authored loot functions to actual 26.3 MapCodecs, preserving their fields/defaults."""
from imports_263 import ROOT,owner
from java_text import matching
import re

FUNCTION='MapCodec<? extends LootItemConditionalFunction>'
CONDITION='MapCodec<? extends LootItemCondition>'

def function(cls,fields=''):
    if not fields:return f'public static final MapCodec<{cls}> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).apply(i, {cls}::new));'
    return f'public static final MapCodec<{cls}> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).and(i.group({fields})).apply(i, {cls}::new));'

def condition(cls,fields):
    return f'public static final MapCodec<{cls}> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group({fields}).apply(i, {cls}::new));'

def optional(codec,key,default,field=None):
    return f'{codec}.optionalFieldOf("{key}", {default}).forGetter(f -> f.{field or key})'

def required(codec,key,field=None):
    return f'{codec}.fieldOf("{key}").forGetter(f -> f.{field or key})'

simple='''public static MapCodec<Simple> codecFor(Op op) {
            return RecordCodecBuilder.mapCodec(i -> commonFields(i).apply(i, conditions -> new Simple(conditions, op)));
        }'''
named='''public static MapCodec<Named> codecFor(Op op) {
            return switch (op) {
                case UNIQUE -> RecordCodecBuilder.mapCodec(i -> commonFields(i).and(
                        Codec.STRING.fieldOf("id").forGetter(f -> f.id))
                        .apply(i, (c, id) -> new Named(c, op, LegendaryUniques.COLLECTION, id, false)));
                case COLLECTIBLE -> RecordCodecBuilder.mapCodec(i -> commonFields(i).and(i.group(
                        Codec.STRING.fieldOf("collection").forGetter(f -> f.collection),
                        Codec.STRING.fieldOf("entry").forGetter(f -> f.id)))
                        .apply(i, (c, collection, id) -> new Named(c, op, collection, id, false)));
                case MISSING -> RecordCodecBuilder.mapCodec(i -> commonFields(i).and(i.group(
                        Codec.STRING.fieldOf("collection").forGetter(f -> f.collection),
                        Codec.BOOL.optionalFieldOf("province", false).forGetter(f -> f.province)))
                        .apply(i, (c, collection, province) -> new Named(c, op, collection, "", province)));
            };
        }'''
strings='Codec.STRING.listOf().xmap(v -> (Set<String>) new LinkedHashSet<>(v.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()), v -> new java.util.ArrayList<>(v))'
schools='com.skycraft.platform.SkyCodecs.enumNames(School.class).listOf().xmap(v -> (Set<School>) new java.util.HashSet<>(v), v -> new java.util.ArrayList<>(v))'
CODECS={
    'ScaledGold':function('ScaledGold',', '.join([optional('Codec.INT','min','1'),optional('Codec.INT','max','10'),optional('Codec.FLOAT','growth','0.04f')])),
    'Simple':simple,
    'Named':named,
    'LevelCondition':condition('LevelCondition',', '.join([optional('Codec.INT','min','1'),optional('Codec.INT','max','Integer.MAX_VALUE')])),
    'CompleteCondition':condition('CompleteCondition',required('Codec.STRING','collection')),
    'LeveledGear':function('LeveledGear',', '.join([optional('com.skycraft.platform.SkyCodecs.enumNames(Kind.class)','kind','Kind.WEAPON'),optional('Codec.FLOAT','enchant_chance','0f','enchantChance'),optional('Codec.FLOAT','quality_chance','0f','qualityChance')])),
    'LegendaryItemFunction':function('LegendaryItemFunction'),
    'RegionCondition':condition('RegionCondition',', '.join(optional(strings,k,'Set.of()') for k in ['provinces','gear_keys','regions']).replace('f.gear_keys','f.gearKeys')),
    'RegionalTag':function('RegionalTag',required('Codec.STRING','tag')),
    'LeveledTome':function('LeveledTome',optional(schools,'schools','Set.of()')),
    'SkyrimPotion':function('SkyrimPotion',optional('Codec.BOOL','poison','false')),
    'TreasureMapFn':function('TreasureMapFn'),
    'LeadFn':function('LeadFn',optional('Codec.STRING.listOf()','ids','List.of()')),
    'NoteFunction':function('NoteFunction',optional('Codec.BOOL','boss','false')),
}

FILES=['loot/LootFunctions.java','arsenal/LeveledGear.java','combat/LegendaryItemFunction.java','economy/EconomyLoot.java','explore/ExploreLoot.java','lore/place/PlaceLoreLoot.java']
for filename in FILES:
    path=ROOT/'mod/src/main/java/com/skycraft'/filename
    if owner(path)!='root':raise ValueError('Another owner: '+filename)
    source=path.read_text(encoding='utf8')
    if 'LootItemFunctionType' not in source:continue
    edits=[]
    for m in re.finditer(r'public static class Serializer (?:extends|implements) [^{]+\{',source):
        cls_match=re.search(r'(?:Serializer<)(\w+)>',m[0]);cls=cls_match[1]
        edits.append((m.start(),matching(source,m.end()-1)+1,CODECS[cls]))
    for start,end,replacement in reversed(edits):source=source[:start]+replacement+source[end:]
    source=re.sub(r'import net\.minecraft\.world\.level\.storage\.loot\.(?:functions\.LootItemFunctionType|predicates\.LootItemConditionType);\s*','',source)
    source=source.replace('LootItemCondition[] conditions','java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions')
    source=source.replace('net.minecraft.world.level.storage.loot.predicates.LootItemConditionType',CONDITION)
    source=source.replace('LootItemConditionType',CONDITION).replace('LootItemFunctionType',FUNCTION)
    source=source.replace('public '+FUNCTION+' getType()', 'public '+FUNCTION+' codec()')
    source=source.replace('public '+CONDITION+' getType()', 'public '+CONDITION+' codec()')
    source=source.replace('DeferredRegister<'+FUNCTION+'>','DeferredRegister<MapCodec<? extends net.minecraft.world.level.storage.loot.functions.LootItemFunction>>')
    source=re.sub(r'new '+re.escape(FUNCTION)+r'\(new ([\w.]+)\.Serializer\(([^)]*)\)\)',
                  lambda m:m[1]+('.codecFor('+m[2]+')' if m[2] else '.MAP_CODEC'),source)
    source=re.sub(r'new '+re.escape(CONDITION)+r'\(new ([\w.]+)\.Serializer\(\)\)',lambda m:m[1]+'.MAP_CODEC',source)
    source=source.replace('package '+source.split('package ',1)[1].split(';',1)[0]+';',
                          'package '+source.split('package ',1)[1].split(';',1)[0]+';\n\nimport com.mojang.serialization.Codec;\nimport com.mojang.serialization.MapCodec;\nimport com.mojang.serialization.codecs.RecordCodecBuilder;',1)
    path.write_text(source,encoding='utf8');print(filename,len(edits),'codecs')
