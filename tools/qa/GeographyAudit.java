package com.nirncraft.qa;

import com.google.gson.GsonBuilder;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual fixed-world region/elevation oracle, without booting Minecraft or making chunks. */
public final class GeographyAudit {
    public static void main(String[] args) throws Exception {
        if(!TamrielMap.loaded()) throw new IllegalStateException("Map rasters unavailable");
        int cell=128,minX=-24576,minZ=-15872,maxX=26112,maxZ=25600;
        int width=(maxX-minX)/cell,height=(maxZ-minZ)/cell;
        List<int[]> rows=new ArrayList<>(),elevations=new ArrayList<>();
        Map<String,Integer> areas=new LinkedHashMap<>();
        Map<Integer,String> ids=new LinkedHashMap<>();
        for(var r:Regions.all()) ids.put(Regions.indexOf(r.id()),r.id());
        var sample=new MapSample(); int points=0;
        for(int z=0;z<height;z++) {
            int[] row=new int[width],elevation=new int[width];
            for(int x=0;x<width;x++) {
                int wx=minX+x*cell+cell/2,wz=minZ+z*cell+cell/2;
                TamrielMap.sample(wx,wz,sample); points++;
                row[x]=Math.max(0,sample.region); elevation[x]=Math.round(sample.elevation*100);
                if(!sample.inTamriel && sample.region>=0 && sample.coastDistance>0) {
                    String id=ids.get(sample.region);
                    if(id!=null && !Regions.byIndex(sample.region).kind().equals("sea")) areas.merge(id,1,Integer::sum);
                }
            }
            rows.add(row); elevations.add(elevation);
        }
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("cell",cell); out.put("minX",minX); out.put("minZ",minZ);out.put("width",width);out.put("height",height);
        out.put("indices",rows); out.put("elevationCentimetres",elevations);out.put("foreignLandCells",areas);
        out.put("scope","128-block samples of actual runtime map; exact authored raster is preferred. Foreign area is sampled, not a coastline mesh; procedural endless realms excluded.");
        Path target=Path.of(args[0]); Files.createDirectories(target.getParent());
        Files.writeString(target,new GsonBuilder().create().toJson(out));
        System.out.println("Geography oracle: "+points+" actual map evaluations, "+areas.size()+" foreign land regions; "+target);
    }
}
