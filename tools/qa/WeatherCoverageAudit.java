package com.nirncraft.qa;

import com.google.gson.GsonBuilder;
import com.nirncraft.atmosphere.WeatherFront;
import com.nirncraft.atmosphere.WeatherPhysics;
import com.nirncraft.atmosphere.weather.WeatherKind;
import com.nirncraft.atmosphere.weather.WeatherProfile;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.util.RandomSource;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Read-only audit of the actual map/column evaluator; no server or terrain chunks. */
public final class WeatherCoverageAudit {
    private static final Map<String, Summary> REGIONS = new LinkedHashMap<>();
    private static long evaluations;

    private record Point(int x, int z, float elevation, float coast, float water, float heat,
                         float dunes, float slope, float rainshadow, float wetland) {}

    private static final class Range {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        double sum;
        int n;
        void add(float value) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite column value");
            min = Math.min(min, value); max = Math.max(max, value); sum += value; n++;
        }
        Map<String, Object> report() {
            return n == 0 ? Map.of("samples", 0) : Map.of("min", min, "max", max, "mean", sum / n, "samples", n);
        }
    }

    private static final class Summary {
        final Region region;
        final Map<String, Range> ranges = new LinkedHashMap<>();
        final List<Point> representatives = new ArrayList<>();
        final Map<String, Integer> flags = new LinkedHashMap<>();
        int points;
        Summary(Region region) { this.region = region; }
        void value(String name, float value) { ranges.computeIfAbsent(name, k -> new Range()).add(value); }
        void flag(String name) { flags.merge(name, 1, Integer::sum); }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        for (Region region : Regions.all()) REGIONS.put(region.id(), new Summary(region));
        if (!TamrielMap.loaded()) throw new IllegalStateException("Actual map rasters failed to load");
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("spatial-samples.csv")))) {
            csv.println("region,x,z,elevation,coast_distance,water_distance,heat,dunes,slope,rainshadow,wetland,clear_noon_C,clear_night_C,clear_noon_RH,clear_night_RH,storm_noon_aerosol,storm_night_aerosol");
            // Authored raster, then all fixed foreign lands, sampled at 256-block spacing.
            scan(TamrielMap.MIN_X, TamrielMap.MIN_Z, TamrielMap.MAX_X, TamrielMap.MAX_Z, csv, false);
            int[][] lands = {{-5500,-15500,5500,-7500},{13000,-3500,25500,9200},
                    {-24000,1000,-15000,14000},{-23500,16000,-14500,25000},{-17700,14500,-12700,19500}};
            for (int[] b : lands) scan(b[0], b[1], b[2], b[3], csv, true);
        }
        Map<String, Object> regions = new LinkedHashMap<>();
        int missing = 0;
        for (Summary summary : REGIONS.values()) {
            WeatherProfile profile = WeatherProfile.of(summary.region);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("province", summary.region.province()); report.put("profile", profile.id);
            report.put("broad_weather", summary.region.weather()); report.put("snow_line", profile.snowLine);
            report.put("spatial_points", summary.points); report.put("flags", summary.flags);
            Map<String,Object> ranges = new LinkedHashMap<>();
            summary.ranges.forEach((k,v) -> ranges.put(k, v.report())); report.put("ranges", ranges);
            if (summary.points == 0) missing++;
            List<String> allowed = new ArrayList<>();
            for (WeatherKind kind : WeatherKind.values()) if (profile.allows(kind)) allowed.add(kind.id());
            report.put("allowed_kinds", allowed);
            // Spell/time shares are Monte Carlo diagnostics, not a meteorological return period.
            Map<String,Object> rolls = new LinkedHashMap<>();
            for (long dayTime : new long[]{6000, 18000, 23000}) {
                RandomSource random = RandomSource.create(0x73a119L + summary.region.id().hashCode() + dayTime);
                long[] durations = new long[WeatherKind.values().length]; int[] counts = new int[durations.length];
                WeatherKind previous = null; long total = 0;
                for (int i=0; i<20000; i++) {
                    WeatherKind kind = profile.roll(random, previous, WeatherProfile.mistHour(dayTime));
                    int duration = profile.duration(random, kind); total += duration;
                    counts[kind.ordinal()]++; durations[kind.ordinal()] += duration; previous = kind;
                }
                Map<String,Object> shares = new LinkedHashMap<>();
                for (WeatherKind kind : WeatherKind.values()) if (counts[kind.ordinal()] > 0)
                    shares.put(kind.id(), Map.of("spell_share",counts[kind.ordinal()]/20000.0,
                            "time_share",durations[kind.ordinal()]/(double)total));
                rolls.put(Long.toString(dayTime), shares);
            }
            report.put("roll_distribution_20000_spells_each", rolls);
            regions.put(summary.region.id(), report);
        }
        // Probe every allowed weather kind on spatially distributed actual terrain points.
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("weather-columns.csv")))) {
            csv.println("region,x,z,kind,day_time,temperature_C,RH,cloud_base,cloud_top,coverage,tower,rain_band,aerosol,ground_fog,electrical_proxy_charge_1");
            for (Summary s : REGIONS.values()) for (Point p : s.representatives)
                for (WeatherKind kind : WeatherKind.values()) if (WeatherProfile.of(s.region).allows(kind))
                    for (long day : new long[]{6000,18000,23000}) {
                        var c = column(p.x,p.z,kind,day);
                        csv.printf(java.util.Locale.ROOT,"%s,%d,%d,%s,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                                s.region.id(),p.x,p.z,kind.id(),day,c.temperatureC(),c.relativeHumidity(),232+c.cloudBaseOffset(),
                                304+c.cloudBaseOffset()+120*c.coverage()+300*c.stormTower(),c.coverage(),c.stormTower(),c.rainBand(),
                                c.aerosolCode(),c.groundFog(),WeatherPhysics.electricalActivity(c,1));
                    }
        }
        List<Object> basins = new ArrayList<>();
        Random random = new Random(0x51fe);
        for(int basin=0;basin<6;basin++) {
            Map<String,Integer> origins = new LinkedHashMap<>(), ends = new LinkedHashMap<>(), kinds = new LinkedHashMap<>();
            float lowX=Float.POSITIVE_INFINITY,highX=Float.NEGATIVE_INFINITY,lowZ=Float.POSITIVE_INFINITY,highZ=Float.NEGATIVE_INFINITY;
            for(int i=0;i<200;i++) {
                WeatherFront f = WeatherPhysics.spawnPhysicalFront(i+1,basin,random);
                origins.merge(TamrielMap.region((int)f.x(),(int)f.z()).id(),1,Integer::sum);
                kinds.merge(f.kind().id(),1,Integer::sum);
                lowX=Math.min(lowX,f.x());highX=Math.max(highX,f.x());lowZ=Math.min(lowZ,f.z());highZ=Math.max(highZ,f.z());
                while(!f.expired()) WeatherPhysics.advectAndEvolveFront(f,100);
                ends.merge(TamrielMap.region((int)f.x(),(int)f.z()).id(),1,Integer::sum);
            }
            basins.add(Map.of("basin",basin,"generated",200,"kinds",kinds,"origin_regions",origins,"end_regions",ends,
                    "origin_bounds",List.of(lowX,lowZ,highX,highZ)));
        }
        Map<String,Object> audit = new LinkedHashMap<>();
        audit.put("scope","Actual runtime map and weather evaluator; sampled columns do not instantiate generated biome registries or render frames.");
        audit.put("grid_spacing",256);audit.put("map_loaded",true);audit.put("regions_without_spatial_samples",missing);
        audit.put("column_evaluations",evaluations);audit.put("regions",regions);audit.put("front_basins",basins);
        Files.writeString(out.resolve("runtime.json"),new GsonBuilder().setPrettyPrinting().create().toJson(audit));
        System.out.println("Weather coverage audit: "+regions.size()+" regions, "+evaluations+" columns; "+missing+" regions without grid samples. Output: "+out);
    }

    private static WeatherPhysics.ColumnState column(int x,int z,WeatherKind kind,long day) {
        evaluations++;
        var c=WeatherPhysics.evaluate(x,z,1200,null,kind,0,0,List.of(),day);
        if (!Float.isFinite(c.temperatureC()) || !Float.isFinite(c.relativeHumidity()) || !Float.isFinite(c.coverage()))
            throw new IllegalStateException("Non-finite weather at "+x+","+z);
        return c;
    }

    private static void scan(int minX,int minZ,int maxX,int maxZ,PrintWriter csv,boolean foreignOnly) {
        MapSample map=new MapSample();
        for(int z=minZ+128;z<maxZ;z+=256) for(int x=minX+128;x<maxX;x+=256) {
            if (foreignOnly && TamrielMap.inTamriel(x,z)) continue;
            Region region=TamrielMap.region(x,z); Summary s=REGIONS.get(region.id()); if(s==null)continue;
            TamrielMap.sample(x,z,map); TamrielMap.deriveStrengths(map); s.points++;
            s.value("elevation",map.elevation);s.value("heat",map.heat);s.value("dunes",map.dunes);s.value("slope",map.slope);
            s.value("rainshadow",map.rainshadow);s.value("coast_distance",map.coastDistance);s.value("water_distance",map.waterDistance());
            s.value("wetland",map.wetland);
            Point p=new Point(x,z,map.elevation,map.coastDistance,map.waterDistance(),map.heat,map.dunes,map.slope,map.rainshadow,map.wetland);
            if(s.representatives.size()<12) s.representatives.add(p);
            else if(s.points%97==0)s.representatives.set((s.points/97)%12,p);
            var noon=column(x,z,WeatherKind.CLEAR,6000);var night=column(x,z,WeatherKind.CLEAR,18000);
            var stormDay=column(x,z,WeatherKind.STORM,6000);var stormNight=column(x,z,WeatherKind.STORM,18000);
            s.value("clear_noon_C",noon.temperatureC());s.value("clear_night_C",night.temperatureC());
            s.value("clear_noon_RH",noon.relativeHumidity());s.value("clear_night_RH",night.relativeHumidity());
            s.value("clear_noon_cloud_base",232+noon.cloudBaseOffset());s.value("clear_night_cloud_base",232+night.cloudBaseOffset());
            s.value("clear_night_fog",night.groundFog());
            if(stormDay.aerosolCode()==WeatherPhysics.AEROSOL_SAND)s.flag("forced_rain_storm_uses_sand_aerosol");
            if(stormDay.aerosolCode()==WeatherPhysics.AEROSOL_ASH)s.flag("forced_rain_storm_uses_ash_aerosol");
            if(stormDay.aerosolCode()==WeatherPhysics.AEROSOL_SNOW && stormDay.temperatureC()>5)s.flag("snow_category_above_5C");
            csv.printf(java.util.Locale.ROOT,"%s,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    region.id(),x,z,map.elevation,map.coastDistance,map.waterDistance(),map.heat,map.dunes,map.slope,map.rainshadow,map.wetland,
                    noon.temperatureC(),night.temperatureC(),noon.relativeHumidity(),night.relativeHumidity(),stormDay.aerosolCode(),stormNight.aerosolCode());
        }
    }
}
