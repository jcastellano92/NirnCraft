package com.nirncraft.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** A real, collision-checked moving camera. Samples do not pause to settle fog at each step. */
final class CameraTravel {
    private static String tag;
    private static int duration,index;
    private static Vec3 start,end,target;
    private static CompletableFuture<Integer> move;
    private static JsonObject report;
    private static JsonArray samples;
    private static long began;
    private CameraTravel() {}
    static boolean active() { return tag!=null; }
    static void cancel() { tag=null; move=null; }

    static void begin(Minecraft mc,String args) {
        String[] p=args.trim().split("\\s+");
        if(p.length!=8 || !p[0].matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("camera-travel TAG TICKS END_X END_Y END_Z LOOK_X LOOK_Y LOOK_Z");
        duration=Integer.parseInt(p[1]);
        if(duration<40 || duration>1200) throw new IllegalArgumentException("Travel must last 40..1200 ticks");
        start=mc.player.position(); end=new Vec3(Double.parseDouble(p[2]),Double.parseDouble(p[3]),Double.parseDouble(p[4]));
        target=new Vec3(Double.parseDouble(p[5]),Double.parseDouble(p[6]),Double.parseDouble(p[7]));
        if(!Double.isFinite(end.x+end.y+end.z+target.x+target.y+target.z) || start.distanceTo(end)>120)
            throw new IllegalArgumentException("Travel must be finite and within loaded local geometry (max 120m)");
        if(!ClientAutotest.readinessPassed(mc)) throw new IllegalStateException("Settle the starting camera before travel");
        tag=p[0]; index=0; move=null; began=System.currentTimeMillis();
        report=VisualCamera.context(mc); report.add("start",VisualCamera.vector(start));
        report.add("end",VisualCamera.vector(end)); report.add("target",VisualCamera.vector(target));
        samples=new JsonArray(); report.add("samples",samples);
        report.addProperty("note","Moving visual sequence; screenshots can affect pacing. Not a performance benchmark.");
    }

    static void tick(Minecraft mc) {
        if(!active()) return;
        if(System.currentTimeMillis()-began>180_000) throw new IllegalStateException("Camera travel timed out");
        if(move!=null) {
            if(!move.isDone()) return;
            if(move.join()<=0) throw new IllegalStateException("Camera travel failed clearance");
            move=null;
            if(index%10==0 || index==duration) {
                String name=tag+"_"+String.format(java.util.Locale.ROOT,"%04d",index);
                var sample=new JsonObject(); sample.addProperty("tag",name); sample.addProperty("step",index);
                sample.add("position",VisualCamera.vector(mc.player.position()));
                sample.addProperty("air",com.nirncraft.atmosphere.client.InteriorAtmosphere.describe());
                samples.add(sample); ClientAutotest.travelCapture(name);
            }
        }
        if(index>=duration) {
            try {
                report.addProperty("completed",true); report.addProperty("elapsedMs",System.currentTimeMillis()-began);
                Path file=Path.of("build/autotest-travel",tag+".json"); Files.createDirectories(file.getParent());
                Files.writeString(file,report.toString());
            } catch(java.io.IOException e) { throw new IllegalStateException("Travel receipt failed",e); }
            tag=null; return;
        }
        Vec3 next=start.lerp(end,(double)++index/duration);
        move=VisualCamera.action(mc,next.x+" "+next.y+" "+next.z+" "+target.x+" "+target.y+" "+target.z,false);
    }
}
