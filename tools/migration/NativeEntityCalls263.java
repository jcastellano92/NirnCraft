import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import java.nio.file.*;
import java.util.*;

/** Bounded native entity call migrations; checked against the exact 26.3 source signatures. */
public final class NativeEntityCalls263 {
    record Edit(int start,int end,String value) {}
    static int offset(String text,Position p) {int line=1,index=0;while(line<p.line){if(text.charAt(index++)=='\n')line++;}return index+p.column-1;}
    static String text(String source,Node n) {var r=n.getRange().orElseThrow();return source.substring(offset(source,r.begin),offset(source,r.end)+1);}
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        for(String file:Files.readAllLines(Path.of(args[0]))) {
            Path p=Path.of(file);String source=Files.readString(p);var unit=StaticJavaParser.parse(source);List<Edit> edits=new ArrayList<>();
            for(var call:unit.findAll(MethodCallExpr.class)) {
                if(call.getScope().isEmpty())continue;String scope=text(source,call.getScope().get()),name=call.getNameAsString();int count=call.getArguments().size();String value=null;
                var a=call.getArguments().stream().map(n->text(source,n)).toList();
                if(name.equals("finalizeSpawn")&&count==5){if(!a.get(4).equals("null")&&!a.get(4).equals("tag"))throw new IllegalStateException("Review custom spawn tag in "+p+": "+call);value=scope+".finalizeSpawn("+String.join(", ",a.subList(0,4))+")";}
                if(name.equals("create")&&count==1&&Set.of("level","serverLevel","sl","server.overworld()").contains(a.get(0)))value=scope+".create("+a.get(0)+", net.minecraft.world.entity.EntitySpawnReason.EVENT)";
                if(name.equals("startRiding")&&count==2)value=scope+".startRiding("+String.join(", ",a)+", true)";
                if(name.equals("swing")&&count==1)value=scope+".swing("+a.get(0)+", net.minecraft.world.item.component.SwingAnimation.DEFAULT, false)";
                if(value!=null){var r=call.getRange().orElseThrow();edits.add(new Edit(offset(source,r.begin),offset(source,r.end)+1,value));}
            }
            for(var create:unit.findAll(ObjectCreationExpr.class)) {
                String type=create.getType().getNameAsString(),value=null;Node replace=create;
                if(type.equals("ServerBossEvent")&&create.getArguments().size()==3)value="new ServerBossEvent(java.util.UUID.randomUUID(), "+String.join(", ",create.getArguments().stream().map(n->text(source,n)).toList())+")";
                if(type.equals("DustParticleOptions")&&create.getArguments().size()==2&&create.getArgument(0) instanceof ObjectCreationExpr vec&&vec.getType().getNameAsString().equals("Vector3f")&&vec.getArguments().size()==3){value="net.minecraft.util.ARGB.colorFromFloat(1f, "+String.join(", ",vec.getArguments().stream().map(n->text(source,n)).toList())+")";replace=vec;}
                if(type.equals("NearestAttackableTargetGoal")&&create.getArguments().size()>=4){int n=create.getArguments().size()-1;var selector=create.getArgument(n);if(selector instanceof LambdaExpr lambda&&lambda.getParameters().size()==1){replace=lambda;value="("+lambda.getParameter(0).getNameAsString()+", targetLevel263) -> "+text(source,lambda.getBody());}else if(selector instanceof MethodReferenceExpr ref){replace=selector;value="(target263, targetLevel263) -> "+ref.getScope()+"."+ref.getIdentifier()+"(target263)";}else if(!selector.isBooleanLiteralExpr()&&!selector.isNullLiteralExpr()&&!selector.isLambdaExpr()){replace=selector;value="(target263, targetLevel263) -> "+text(source,selector)+".test(target263)";}}
                if(value!=null){var r=replace.getRange().orElseThrow();edits.add(new Edit(offset(source,r.begin),offset(source,r.end)+1,value));}
            }
            edits.sort(Comparator.comparingInt(Edit::start).thenComparing(Comparator.comparingInt(Edit::end).reversed()));List<Edit> bounded=new ArrayList<>();int end=-1;for(var e:edits)if(e.start()>=end){bounded.add(e);end=e.end();}Collections.reverse(bounded);String result=source;for(var e:bounded)result=result.substring(0,e.start())+e.value()+result.substring(e.end());
            if(!result.equals(source)){Files.writeString(p,result);System.out.println(p+": "+bounded.size()+" entity calls");}
        }
    }
}
