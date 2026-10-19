package com.nirncraft.atmosphere;

import java.util.ArrayList;
import java.util.List;

/** Bounded crossfade: four selected rooms plus four retiring rooms, with stable identities. */
public final class VolumeBlend {
    public record Entry(DungeonAir.Volume volume, float weight) {}
    private final List<Entry> entries = new ArrayList<>();

    /** Remove artificial tile seams without filling an L-shaped gap or crossing
     * a floor/style boundary. Only unions which remain a rectangle are allowed. */
    public static List<DungeonAir.Volume> mergeAligned(List<DungeonAir.Volume> input) {
        List<DungeonAir.Volume> result=new ArrayList<>(new java.util.LinkedHashSet<>(input));
        boolean changed;
        do {
            changed=false;
            outer: for(int i=0;i<result.size();i++) for(int j=i+1;j<result.size();j++) {
                var a=result.get(i);var b=result.get(j);
                if(a.floor()!=b.floor() || a.style()!=b.style()) continue;
                boolean x=a.y0()==b.y0() && a.y1()==b.y1() && a.z0()==b.z0() && a.z1()==b.z1()
                        && a.x1()>=b.x0() && b.x1()>=a.x0();
                boolean y=a.x0()==b.x0() && a.x1()==b.x1() && a.z0()==b.z0() && a.z1()==b.z1()
                        && a.y1()>=b.y0() && b.y1()>=a.y0();
                boolean z=a.x0()==b.x0() && a.x1()==b.x1() && a.y0()==b.y0() && a.y1()==b.y1()
                        && a.z1()>=b.z0() && b.z1()>=a.z0();
                if(!x && !y && !z) continue;
                result.set(i,new DungeonAir.Volume(Math.min(a.x0(),b.x0()),Math.min(a.y0(),b.y0()),Math.min(a.z0(),b.z0()),
                        Math.max(a.x1(),b.x1()),Math.max(a.y1(),b.y1()),Math.max(a.z1(),b.z1()),a.floor(),a.style()));
                result.remove(j);changed=true;break outer;
            }
        } while(changed);
        return List.copyOf(result);
    }

    public List<Entry> update(List<DungeonAir.Volume> selected) {
        for (int i=0; i<entries.size(); i++) {
            Entry e=entries.get(i);
            float weight=selected.contains(e.volume) ? Math.min(1f,e.weight+0.1f) : Math.max(0f,e.weight-0.1f);
            entries.set(i,new Entry(e.volume,weight));
        }
        entries.removeIf(e->e.weight<=0f && !selected.contains(e.volume));
        for (var volume:selected) {
            if(entries.stream().anyMatch(e->e.volume.equals(volume))) continue;
            if(entries.size()>=8) {
                // Rapid flight may replace more than four rooms before a fade completes.
                Entry weakest=entries.stream().filter(e->!selected.contains(e.volume))
                        .min(java.util.Comparator.comparingDouble(Entry::weight)).orElse(null);
                if(weakest!=null) entries.remove(weakest);
            }
            if(entries.size()<8) entries.add(new Entry(volume,0.1f));
        }
        return List.copyOf(entries);
    }

    public void clear() { entries.clear(); }
    public boolean settled() { return entries.stream().allMatch(e->e.weight>=0.999f); }
}
