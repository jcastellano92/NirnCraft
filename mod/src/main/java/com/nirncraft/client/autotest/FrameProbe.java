package com.nirncraft.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Fixed-view samples without screenshot encoding; includes pacing separately from render CPU work. */
final class FrameProbe {
    private static String tag;
    private static double[] intervals, renderCpu, server;
    private static int count, unfocused;
    private static long previous, frameStart, started;
    private static JsonObject initial;
    private FrameProbe() {}

    static boolean active() { return tag!=null; }
    static void cancel() { tag=null; }
    static void begin(Minecraft mc,String arguments) {
        String[] parts=arguments.trim().split("\\s+");
        if(parts.length!=2 || !parts[0].matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("measure TAG FRAMES");
        int frames=Integer.parseInt(parts[1]);
        if(frames<120 || frames>3600) throw new IllegalArgumentException("Measure 120..3600 real frames");
        tag=parts[0]; intervals=new double[frames]; renderCpu=new double[frames]; server=new double[frames];
        count=unfocused=0; previous=0; started=System.nanoTime(); initial=VisualCamera.context(mc);
        initial.addProperty("frameLimit",mc.options.framerateLimit().get());
        initial.addProperty("vsync",mc.options.enableVsync().get());
        initial.addProperty("width",mc.getWindow().getWidth()); initial.addProperty("height",mc.getWindow().getHeight());
        initial.addProperty("renderer",com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().toString());
        initial.addProperty("gpuTimerScope","Atmosphere diagnostics use whole-frame GPU timer; not isolated cloud cost");
    }
    static void startFrame() { if(active()) frameStart=System.nanoTime(); }
    static void endFrame(Minecraft mc) {
        if(!active()) return;
        long now=System.nanoTime();
        if(previous==0) { previous=now; return; }
        intervals[count]=(now-previous)*1e-6; previous=now;
        renderCpu[count]=(now-frameStart)*1e-6;
        server[count]=mc.getSingleplayerServer()==null?0:mc.getSingleplayerServer().getAverageTickTimeNanos()*1e-6;
        if(!mc.isWindowActive()) unfocused++;
        if(++count<intervals.length) return;
        try {
            initial.add("frameIntervalsMs",array(intervals)); initial.add("renderCpuMs",array(renderCpu));
            initial.add("serverAverageTickMs",array(server));
            initial.add("summary",summary(intervals)); initial.add("renderCpuSummary",summary(renderCpu));
            initial.addProperty("unfocusedFrames",unfocused);
            initial.addProperty("pacingLimited",unfocused>0 || mc.options.enableVsync().get() || mc.options.framerateLimit().get()<260);
            initial.addProperty("seconds",(now-started)*1e-9);
            initial.addProperty("atmosphere",com.nirncraft.atmosphere.client.CloudRenderer.atmosphereDiagnostics());
            initial.addProperty("fire",com.nirncraft.fire.client.FarFireRenderer.stats());
            initial.addProperty("note","Frame intervals include cap/vsync/background pacing. Unfocused runs are not GPU throughput benchmarks.");
            Path out=Path.of("build/autotest-performance",tag+".json");
            Files.createDirectories(out.getParent()); Files.writeString(out,initial.toString());
            com.nirncraft.NirnCraft.LOGGER.info("Autotest measure: {} {}",tag,initial.get("summary"));
        } catch(Exception e) { com.nirncraft.NirnCraft.LOGGER.error("Autotest measure failed",e); }
        tag=null;
    }
    private static JsonArray array(double[] samples) { var a=new JsonArray(); for(double n:samples) a.add(n); return a; }
    private static JsonObject summary(double[] samples) {
        double[] sorted=samples.clone(); Arrays.sort(sorted);
        var r=new JsonObject(); double sum=0; int stalls=0;
        for(double n:samples) { sum+=n; if(n>50) stalls++; }
        r.addProperty("samples",samples.length); r.addProperty("meanMs",sum/samples.length);
        r.addProperty("medianMs",sorted[(samples.length-1)/2]);
        r.addProperty("p95Ms",sorted[(int)Math.ceil(samples.length*.95)-1]);
        r.addProperty("p99Ms",sorted[(int)Math.ceil(samples.length*.99)-1]);
        r.addProperty("maxMs",sorted[sorted.length-1]); r.addProperty("over50Ms",stalls);
        return r;
    }
}
