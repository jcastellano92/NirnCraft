import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import java.nio.file.*;
import java.util.*;

/** Assigns explicit IDs to literal properties in bounded registration lambdas; it does not infer wrapper factories. */
public final class RegistrationIds263 {
    record Edit(int start, int end, String value) {}
    static int offset(String source, Position p) { int line=1,i=0;while(line<p.line){if(source.charAt(i++)=='\n')line++;}return i+p.column-1; }
    static String text(String source, Node n) {var r=n.getRange().orElseThrow();return source.substring(offset(source,r.begin),offset(source,r.end)+1);}
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        for(String file:Files.readAllLines(Path.of(args[0]))) {
            Path p=Path.of(file);String source=Files.readString(p);var unit=StaticJavaParser.parse(source);Map<Integer,Edit> edits=new TreeMap<>();
            for(var call:unit.findAll(MethodCallExpr.class)) {
                if(!Set.of("register","item","registerFloraBlock").contains(call.getNameAsString())||call.getArguments().size()!=2||!call.getArgument(1).isLambdaExpr())continue;
                String id=text(source,call.getArgument(0));var lambda=call.getArgument(1).asLambdaExpr();
                for(var creation:lambda.findAll(ObjectCreationExpr.class)) {
                    if(!creation.getType().toString().equals("Item.Properties")||!creation.getArguments().isEmpty())continue;
                    var r=creation.getRange().orElseThrow();int start=offset(source,r.begin);edits.put(start,new Edit(start,offset(source,r.end)+1,"com.skycraft.platform.SkyProperties.item("+id+")"));
                }
                for(var properties:lambda.findAll(MethodCallExpr.class)) {
                    if(!properties.getScope().map(n->n.toString().equals("BlockBehaviour.Properties")).orElse(false)||!Set.of("of","ofFullCopy").contains(properties.getNameAsString()))continue;
                    var r=properties.getRange().orElseThrow();int start=offset(source,r.begin);String value="com.skycraft.platform.SkyProperties.block("+id+(properties.getNameAsString().equals("of")?"":", "+text(source,properties))+")";
                    edits.put(start,new Edit(start,offset(source,r.end)+1,value));
                }
            }
            String result=source;for(var edit:edits.values().stream().sorted(Comparator.comparingInt(Edit::start).reversed()).toList())result=result.substring(0,edit.start())+edit.value()+result.substring(edit.end());
            if(!result.equals(source)){Files.writeString(p,result);System.out.println(p+": "+edits.size()+" registration property IDs");}
        }
    }
}
