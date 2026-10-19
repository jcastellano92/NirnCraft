"""Port root item interaction and tooltip signatures to the real component API."""
import re
from imports_263 import ROOT,owner
from java_text import matching,arguments

for path in (ROOT/'mod/src/main/java/com/nirncraft').rglob('*.java'):
    if owner(path)!='root':continue
    source=path.read_text(encoding='utf8');result=source;edits=[]
    pattern=r'void appendHoverText\(ItemStack (\w+), @Nullable Level (\w+), List<Component> (\w+), TooltipFlag (\w+)\)\s*\{'
    for m in re.finditer(pattern,result):
        end=matching(result,m.end()-1);body=result[m.end():end]
        stack,level,tooltip,flag=m.groups()
        body=body.replace(tooltip+'.add(',tooltip+'.accept(')
        body=body.replace('super.appendHoverText('+stack+', '+level+', '+tooltip+', '+flag+')',
                          'super.appendHoverText('+stack+', context263, display263, '+tooltip+', '+flag+')')
        sig='void appendHoverText(ItemStack '+stack+', net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> '+tooltip+', TooltipFlag '+flag+') {'
        if re.search(r'\b'+level+r'\b',body):body='\n        Level '+level+' = context263.level();'+body
        edits.append((m.start(),end+1,sig+body+'}'))
    for start,end,value in reversed(edits):result=result[:start]+value+result[end:]
    result=result.replace('DistExecutor.unsafeCallWhenOn(', 'com.nirncraft.platform.Sides.callOn(')
    result=result.replace('.setHoverName(','.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, ')
    result=result.replace('InteractionResultHolder<ItemStack>','InteractionResult')
    result=result.replace('import net.minecraft.world.InteractionResultHolder;','import net.minecraft.world.InteractionResult;')
    # These public vanilla fields have the same documented synchronization role.
    result=result.replace('.hurtMarked', '.syncVelocity')
    edits=[]
    for m in re.finditer(r'InteractionResultHolder\.(sidedSuccess|success|consume|fail|pass)\(',result):
        end=matching(result,m.end()-1);a=arguments(result[m.end():end]);method=m[1]
        if method=='sidedSuccess':value='com.nirncraft.platform.Interactions.sidedSuccess('+a[1].strip()+').heldItemTransformedTo('+a[0].strip()+')'
        elif method in ['success','consume']:value='InteractionResult.'+method.upper()+'.heldItemTransformedTo('+a[0].strip()+')'
        else:value='InteractionResult.'+method.upper()
        edits.append((m.start(),end+1,value))
    for start,end,value in reversed(edits):result=result[:start]+value+result[end:]
    # Old block/entity interaction calls had the same predicted/consumed split.
    result=result.replace('InteractionResult.sidedSuccess(', 'com.nirncraft.platform.Interactions.sidedSuccess(')
    if result!=source:path.write_text(result,encoding='utf8');print(path.relative_to(ROOT))

p=ROOT/'mod/src/main/java/com/nirncraft/arsenal/item/ArtifactItem.java'
s=p.read_text(encoding='utf8').replace('List<Component> tooltip','java.util.function.Consumer<Component> tooltip').replace('tooltip.add(','tooltip.accept(')
p.write_text(s,encoding='utf8')
