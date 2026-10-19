import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.body.*;
import java.nio.file.*;
import java.util.*;

/** NBT-only defaults, including dynamic keys; buffers, JFR events and JSON methods retain their own APIs. */
public final class NbtOwnedCalls263 {
    record Edit(int start, int end, String value) {}
    static int offset(String source, Position p) {
        int line=1,index=0;
        while(line<p.line)if(source.charAt(index++)=='\n')line++;
        return index+p.column-1;
    }
    static String text(String source,Node node) {
        var range=node.getRange().orElseThrow();
        return source.substring(offset(source,range.begin),offset(source,range.end)+1);
    }
    static boolean nbtScope(Expression scope,Set<String> names) {
        if(scope instanceof NameExpr name)return names.contains(name.getNameAsString());
        if(scope instanceof FieldAccessExpr field)return (field.getScope() instanceof ThisExpr && names.contains(field.getNameAsString())) || field.getNameAsString().equals("tag");
        if(scope instanceof MethodCallExpr call)return Set.of("module","getPersistentData","getCompoundOrEmpty","getListOrEmpty","custom","customOrEmpty","tag","data").contains(call.getNameAsString());
        return false;
    }
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        Map<String,String> defaults=Map.of("getBoolean","false","getByte","(byte) 0","getShort","(short) 0","getInt","0","getLong","0L","getFloat","0f","getDouble","0d","getString","\"\"");
        int changed=0;
        for(String file:Files.readAllLines(Path.of(args[0]))) {
            if(file.isBlank())continue;
            Path path=Path.of(file);String source=Files.readString(path);var unit=StaticJavaParser.parse(source);
            Map<String,Set<String>> types=new HashMap<>();
            for(var variable:unit.findAll(VariableDeclarator.class))types.computeIfAbsent(variable.getNameAsString(),k->new HashSet<>()).add(variable.getTypeAsString());
            for(var parameter:unit.findAll(Parameter.class))types.computeIfAbsent(parameter.getNameAsString(),k->new HashSet<>()).add(parameter.getTypeAsString());
            Set<String> names=new HashSet<>();
            for(var entry:types.entrySet())if(entry.getValue().stream().anyMatch(type -> Set.of("CompoundTag","ListTag","net.minecraft.nbt.CompoundTag","net.minecraft.nbt.ListTag").contains(type)))names.add(entry.getKey());
            List<Edit> edits=new ArrayList<>();
            for(var call:unit.findAll(MethodCallExpr.class)) {
                if(call.getScope().isEmpty()||!nbtScope(call.getScope().get(),names))continue;
                String name=call.getNameAsString();int count=call.getArguments().size();
                if(call.getParentNode().orElse(null) instanceof MethodCallExpr parent && parent.getScope().orElse(null)==call && Set.of("orElse","orElseGet","orElseThrow","map","flatMap","ifPresent","isPresent").contains(parent.getNameAsString()))continue;
                String prefix=text(source,call.getScope().get())+".";String replacement=null;
                if(defaults.containsKey(name)&&count==1)replacement=prefix+name+"Or("+text(source,call.getArgument(0))+", "+defaults.get(name)+")";
                if(name.equals("getCompound")&&count==1)replacement=prefix+"getCompoundOrEmpty("+text(source,call.getArgument(0))+")";
                if(name.equals("getList")&&(count==1||count==2))replacement=prefix+"getListOrEmpty("+text(source,call.getArgument(0))+")";
                if(Set.of("getIntArray","getLongArray","getByteArray").contains(name)&&count==1)replacement=text(source,call)+".orElseGet(() -> new "+name.substring(3,name.length()-5).toLowerCase(Locale.ROOT)+"[0])";
                if(name.equals("contains")&&count==2)replacement="com.nirncraft.platform.NbtData.hasType("+text(source,call.getScope().get())+", "+text(source,call.getArgument(0))+", "+text(source,call.getArgument(1))+")";
                if(replacement!=null) { var range=call.getRange().orElseThrow();edits.add(new Edit(offset(source,range.begin),offset(source,range.end)+1,replacement)); }
            }
            edits.sort(Comparator.comparingInt(Edit::start).thenComparing(Comparator.comparingInt(Edit::end).reversed()));
            List<Edit> selected=new ArrayList<>();int last=-1;
            for(var edit:edits)if(edit.start()>=last){selected.add(edit);last=edit.end();}
            Collections.reverse(selected);String result=source;
            for(var edit:selected)result=result.substring(0,edit.start())+edit.value()+result.substring(edit.end());
            if(!result.equals(source)){Files.writeString(path,result);changed++;System.out.println(path+": "+selected.size()+" NBT calls");}
        }
        System.out.println("Changed "+changed+" files");
    }
}
