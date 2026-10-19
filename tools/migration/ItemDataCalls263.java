import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.nio.file.*;
import java.util.*;

/** Applies bounded ItemStack custom-data API edits by exact source ranges, preserving unrelated formatting. */
public final class ItemDataCalls263 {
    record Edit(int start,int end,String value) {}
    static int offset(String text,Position p) {
        int line=1,index=0;
        while(line<p.line) { if(text.charAt(index++)=='\n')line++; }
        return index+p.column-1;
    }
    static String text(String source,Node n) {
        var r=n.getRange().orElseThrow();return source.substring(offset(source,r.begin),offset(source,r.end)+1);
    }
    static boolean mutates(String name) {
        return name.startsWith("put")||Set.of("remove","merge","store","storeNullable").contains(name);
    }
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        int changed=0;
        for(String file:Files.readAllLines(Path.of(args[0]))) {
            if(file.isBlank())continue;
            Path path=Path.of(file);String source=Files.readString(path);var unit=StaticJavaParser.parse(source);
            Map<String,Set<String>> declaredTypes=new HashMap<>();
            for(var variable:unit.findAll(VariableDeclarator.class))declaredTypes.computeIfAbsent(variable.getNameAsString(),key->new HashSet<>()).add(variable.getTypeAsString());
            for(var parameter:unit.findAll(Parameter.class))declaredTypes.computeIfAbsent(parameter.getNameAsString(),key->new HashSet<>()).add(parameter.getTypeAsString());
            Set<String> stackNames=new HashSet<>();
            for(var entry:declaredTypes.entrySet())if(entry.getValue().equals(Set.of("ItemStack")))stackNames.add(entry.getKey());
            List<Edit> edits=new ArrayList<>();
            for(var call:unit.findAll(MethodCallExpr.class)) {
                if(call.getScope().isEmpty())continue;
                String name=call.getNameAsString();Node replace=call;String replacement;
                String scope=text(source,call.getScope().get());
                if(name.equals("of")&&scope.equals("ItemStack")&&call.getArguments().size()==1) {
                    replacement="com.nirncraft.inventory.ItemData.parse("+text(source,call.getArgument(0))+")";
                } else if(name.equals("save")&&stackNames.contains(scope)&&call.getArguments().size()==1) {
                    String target=text(source,call.getArgument(0));
                    replacement=target.matches("new (?:net\\.minecraft\\.nbt\\.)?CompoundTag\\(\\)")
                            ? "com.nirncraft.inventory.ItemData.save("+scope+")"
                            : "com.nirncraft.platform.ItemTags.saveInto("+scope+", "+target+")";
                } else if(Set.of("getTag","getOrCreateTag").contains(name)&&call.getArguments().isEmpty()) {
                    MethodCallExpr parent=call;
                    while(parent.getParentNode().orElse(null) instanceof MethodCallExpr next && next.getScope().orElse(null)==parent) {
                        parent=next;
                        if(mutates(parent.getNameAsString()))break;
                    }
                    if(parent!=call&&mutates(parent.getNameAsString())) {
                        String body=text(source,parent);
                        String old=text(source,call);
                        body="customData263"+body.substring(old.length());
                        replacement="com.nirncraft.inventory.ItemData.updateCustom("+scope+", customData263 -> "+body+")";
                        replace=parent;
                    } else replacement="com.nirncraft.inventory.ItemData."+(name.equals("getTag")?"custom":"customOrEmpty")+"("+scope+")";
                } else if(name.equals("setTag")&&call.getArguments().size()==1) {
                    replacement="com.nirncraft.inventory.ItemData.setCustom("+scope+", "+text(source,call.getArgument(0))+")";
                } else if(name.equals("hasTag")&&call.getArguments().isEmpty()) {
                    replacement="com.nirncraft.inventory.ItemData.hasCustom("+scope+")";
                } else if(name.equals("removeTagKey")&&call.getArguments().size()==1) {
                    replacement="com.nirncraft.inventory.ItemData.updateCustom("+scope+", customData263 -> customData263.remove("+text(source,call.getArgument(0))+"))";
                } else if(name.equals("contains")&&call.getArguments().size()==2&&text(source,call.getArgument(1)).contains("Tag.TAG_")) {
                    replacement="com.nirncraft.platform.NbtData.hasType("+scope+", "+text(source,call.getArgument(0))+", "+text(source,call.getArgument(1))+")";
                } else if(call.getScope().orElse(null) instanceof FieldAccessExpr field && Set.of("offhand","armor").contains(field.getNameAsString()) && Set.of("get","set").contains(name)) {
                    String inventory=text(source,field.getScope());
                    String index=field.getNameAsString().equals("offhand")?"net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND":"36 + ("+text(source,call.getArgument(0))+")";
                    replacement=inventory+"."+(name.equals("get")?"getItem":"setItem")+"("+index;
                    if(name.equals("set"))replacement+=", "+text(source,call.getArgument(1));
                    replacement+=")";
                } else if(Set.of("hasEffect","getEffect","removeEffect","removeEffectNoUpdate").contains(name)&&call.getArguments().size()==1&&!scope.equals("com.nirncraft.platform.Effects")) {
                    String argument=text(source,call.getArgument(0));
                    if(argument.startsWith("com.nirncraft.platform.Effects.holder("))continue;
                    replacement=scope+"."+name+"(com.nirncraft.platform.Effects.holder("+argument+"))";
                } else if(name.equals("hurt")&&call.getArguments().size()==2&&!scope.equals("super")) {
                    replacement="com.nirncraft.platform.Effects.hurt("+scope+", "+text(source,call.getArgument(0))+", "+text(source,call.getArgument(1))+")";
                } else if(name.equals("playNotifySound")&&call.getArguments().size()==4) {
                    replacement="com.nirncraft.magic.MagicFx.soundToPlayer("+scope+", "+String.join(", ",call.getArguments().stream().map(a -> text(source,a)).toList())+")";
                } else if(name.equals("swing")&&call.getArguments().size()==2) {
                    if(text(source,call.getArgument(1)).contains("SwingAnimation"))continue;
                    replacement=scope+".swing("+text(source,call.getArgument(0))+", net.minecraft.world.item.component.SwingAnimation.DEFAULT, "+text(source,call.getArgument(1))+")";
                } else if(name.equals("drop")&&call.getArguments().size()==2) {
                    replacement=scope+".drop("+text(source,call.getArgument(0))+", "+text(source,call.getArgument(1))+", net.minecraft.util.Prediction.SERVER_ONLY)";
                } else if(name.equals("is")&&call.getArguments().size()==1&&call.getScope().orElse(null) instanceof MethodCallExpr typeCall&&typeCall.getNameAsString().equals("getType")) {
                    replacement=scope+".builtInRegistryHolder().is("+text(source,call.getArgument(0))+")";
                } else continue;
                var range=replace.getRange().orElseThrow();
                edits.add(new Edit(offset(source,range.begin),offset(source,range.end)+1,replacement));
            }
            for(var creation:unit.findAll(ObjectCreationExpr.class)) {
                if(!creation.getType().getNameAsString().equals("MobEffectInstance")||creation.getArguments().size()<2)continue;
                var argument=creation.getArgument(0);String value=text(source,argument);
                if(value.startsWith("com.nirncraft.platform.Effects.holder("))continue;
                var range=argument.getRange().orElseThrow();edits.add(new Edit(offset(source,range.begin),offset(source,range.end)+1,"com.nirncraft.platform.Effects.holder("+value+")"));
            }
            for(var field:unit.findAll(FieldAccessExpr.class)) {
                String name=field.getNameAsString();
                if(!name.equals("selected"))continue;
                Node replace=field;String scope=text(source,field.getScope());String replacement;
                if(field.getParentNode().orElse(null) instanceof AssignExpr assignment && assignment.getTarget()==field && assignment.getOperator()==AssignExpr.Operator.ASSIGN) {
                    replace=assignment;replacement=scope+".setSelectedSlot("+text(source,assignment.getValue())+")";
                } else replacement=scope+".getSelectedSlot()";
                var range=replace.getRange().orElseThrow();edits.add(new Edit(offset(source,range.begin),offset(source,range.end)+1,replacement));
            }
            edits.sort(Comparator.comparingInt(Edit::start).thenComparing(Comparator.comparingInt(Edit::end).reversed()));
            List<Edit> selected=new ArrayList<>();int last=-1;
            for(var edit:edits) { if(edit.start()<last)continue;selected.add(edit);last=edit.end(); }
            String result=source;
            Collections.reverse(selected);
            for(var edit:selected)result=result.substring(0,edit.start())+edit.value()+result.substring(edit.end());
            if(!result.equals(source)) { Files.writeString(path,result);changed++;System.out.println(path+": "+selected.size()+" item custom-data calls"); }
        }
        System.out.println("Changed "+changed+" files");
    }
}
