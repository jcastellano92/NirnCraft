package com.skycraft.society;

import com.mojang.authlib.GameProfile;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.SettlementPopulation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Developer census of a settlement's people ({@code /skycraft-society census <place> [radius]}) and a stand-in for a
 * player's presence ({@code /skycraft-society visit <place>}: a fake player stands at the settlement and runs the
 * population ticks, which only exist per real player). The census counts every townsperson, villager, guard and
 * traveller around the place by SOURCE (which spawn path made them), type, race and state, next to the housing model's
 * plan, so the crowd caps and the night rules can be measured on the dev server without a client.
 */
public final class SocietyCensus {
    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("skycraft-society-census".getBytes()), "[Census]");

    private SocietyCensus() {}

    /** One counted person. */
    public record Row(String uuid, String type, String source, String race, String role, boolean sleeping, boolean inBed, boolean outdoors,
                      boolean guard) {}

    /** Result of a census. */
    public static final class Result {
        public final List<Row> rows = new ArrayList<>();
        public int beds = -1, residents = -1, postSleepers = -1, capacity = -1;
        public int duplicateBeds, duplicateHomes;
        public final Map<String, Integer> bySource = new TreeMap<>();
        public final Map<String, Integer> byType = new TreeMap<>();
        public final Map<String, Integer> byRace = new TreeMap<>();
        /** Residents who are not asleep: "role/job/activity[+bed]" (what keeps them up at night). */
        public final Map<String, Integer> awake = new TreeMap<>();

        public int total() {
            return rows.size();
        }

        public int sleeping() {
            int n = 0;
            for (Row r : rows) if (r.sleeping) n++;
            return n;
        }

        public int sleepingInBed() {
            int n = 0;
            for (Row r : rows) if (r.inBed) n++;
            return n;
        }

        public int outdoors() {
            int n = 0;
            for (Row r : rows) if (r.outdoors) n++;
            return n;
        }

        /** People who are neither guards nor travellers (the settlement's own residents, shopkeepers included). */
        public int residentsPresent() {
            int n = 0;
            for (Row r : rows) if (!r.guard && !r.source.startsWith("traveler") && !r.source.startsWith("encounter")) n++;
            return n;
        }

        public java.util.Set<String> residentIds() {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Row row : rows) if (!row.guard && !row.source.startsWith("traveler") && !row.source.startsWith("encounter")) ids.add(row.uuid);
            return ids;
        }

        public int travellers() {
            int n = 0;
            for (Row r : rows) if (r.source.startsWith("traveler") || r.source.startsWith("encounter")) n++;
            return n;
        }

        public String summary() {
            return "total " + total() + " (residents " + residentsPresent() + ", guards " + guardCount() + ", travellers "
                    + travellers() + "), sleeping " + sleeping() + " (in a bed " + sleepingInBed() + "), outdoors " + outdoors()
                    + (capacity >= 0 ? ", beds " + capacity + ", planned residents " + residents + " (+" + postSleepers + " on post)" : "")
                    + ", duplicate beds " + duplicateBeds;
        }

        public int guardCount() {
            int n = 0;
            for (Row r : rows) if (r.guard) n++;
            return n;
        }
    }

    /** Counts the people within {@code radius} blocks (horizontal) of the place, in loaded chunks only. */
    public static Result census(ServerLevel level, Place place, int radius) {
        Result res = new Result();
        AABB box = new AABB(place.x() - radius, level.getMinBuildHeight(), place.z() - radius, place.x() + radius,
                level.getMaxBuildHeight(), place.z() + radius);
        long r2 = (long) radius * radius;
        Map<Long, Integer> bedUse = new HashMap<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                x -> x.isAlive() && (x instanceof NpcEntity || x instanceof AbstractVillager || x instanceof GuardEntity))) {
            long dx = (long) e.getX() - place.x(), dz = (long) e.getZ() - place.z();
            if (dx * dx + dz * dz > r2) continue;
            String source = source(e);
            boolean guard = e instanceof GuardEntity || e instanceof NpcEntity n && (n.role() == NpcRole.IMPERIAL_SOLDIER
                    || n.role() == NpcRole.STORMCLOAK_SOLDIER || n.role() == NpcRole.THALMOR || n.role() == NpcRole.HOUSECARL);
            String race = e instanceof NpcEntity n ? n.race().name().toLowerCase() : "-";
            String role = e instanceof NpcEntity n ? n.role().id : e instanceof GuardEntity ? "guard" : "villager";
            boolean sleeping = e.isSleeping() || e.getPose() == Pose.SLEEPING;
            boolean inBed = false;
            if (sleeping) {
                Optional<BlockPos> sp = e.getSleepingPos();
                if (sp.isPresent() && level.getBlockState(sp.get()).is(BlockTags.BEDS)) {
                    inBed = true;
                    bedUse.merge(sp.get().asLong(), 1, Integer::sum);
                }
            }
            boolean outdoors = level.canSeeSky(e.blockPosition());
            String type = e.getType().builtInRegistryHolder().key().location().getPath();
            res.rows.add(new Row(e.getUUID().toString(), type, source, race, role, sleeping, inBed, outdoors, guard));
            res.bySource.merge(source, 1, Integer::sum);
            res.byType.merge(type + "/" + role, 1, Integer::sum);
            res.byRace.merge(race, 1, Integer::sum);
            if (!sleeping && !guard && e instanceof NpcEntity an) {
                String job = e.getPersistentData().getString(Housing.JOB_KEY);
                res.awake.merge(an.role().id + "/" + job + "/" + an.activity.name().toLowerCase() + (an.getBed() != null ? "+bed" : "-nobed")
                        + (outdoors ? "/out" : "/in"), 1, Integer::sum);
            }
        }
        for (int n : bedUse.values()) if (n > 1) res.duplicateBeds++;
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(level, place.id());
        if (entry.isPresent()) {
            var plan = SettlementPopulation.housing(entry.get());
            res.capacity = plan.capacity();
            res.residents = plan.residents();
            res.postSleepers = plan.postSleepers();
            int gates = 0;
            for (var a : entry.get().anchors()) if (a.type() == AnchorType.GATE) gates++;
            res.beds = plan.capacity();
        }
        return res;
    }

    /** Which spawn path made this person (persistent-data markers other modules leave on their spawns). */
    public static String source(Entity e) {
        CompoundTag pd = e.getPersistentData();
        if (pd.contains(com.skycraft.roads.Travelers.TAG)) return "traveler:" + pd.getCompound(com.skycraft.roads.Travelers.TAG).getString("kind");
        if (Encounters.isTagged(e)) return "encounter";
        if (pd.contains(com.skycraft.quest.QuestSpawner.TAG_QUEST)) return "quest";
        if (pd.contains(com.skycraft.quest.story.QuestNpcs.TAG)) return "story";
        if (pd.contains(com.skycraft.quest.faction.FactionStaff.TAG)) return "faction-staff";
        if (pd.getBoolean("skycraft_steward")) return "steward";
        if (pd.contains(GatePosts.KEY)) return "gate-post";
        if (pd.contains(GuardWatch.PATROL_KEY)) return "patrol";
        if (pd.contains(Housing.JOB_KEY)) return "housing";
        if (pd.getBoolean("skycraft_settler")) return "settler-unadopted";
        if (e instanceof GuardEntity g) return g.getHome() != null ? "guard-spawner" : "guard";
        if (e instanceof LivingEntity l && com.skycraft.survival.inn.Innkeepers.isInnkeeper(l)) return "innkeeper";
        return "other";
    }

    /** One text line per category, for the console. */
    public static List<String> report(ServerLevel level, Place place, int radius) {
        Result r = census(level, place, radius);
        List<String> out = new ArrayList<>();
        out.add("[census] " + place.id() + " r" + radius + ": " + r.summary());
        out.add("[census]   by source: " + r.bySource);
        out.add("[census]   by type:   " + r.byType);
        out.add("[census]   by race:   " + r.byRace);
        out.add("[census]   awake:     " + r.awake);
        var entry = SettlementAnchors.find(level, place.id());
        int recorded = entry.map(en -> en.anchors(AnchorType.GATE).size()).orElse(0);
        out.add("[census]   gates:     " + SettlementAnchors.gates(place).size() + " on the map, " + recorded + " anchors recorded");
        return out;
    }

    /**
     * Puts a fake player at the settlement and runs the population ticks as a real visit would (the dev server has no
     * players). Chunks must be loaded (forceload them first). Returns what was run.
     */
    public static String visit(ServerLevel level, Place place) {
        FakePlayer fake = FakePlayerFactory.get(level, PROFILE);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, place.x(), place.z());
        fake.setPos(place.x() + 0.5, y, place.z() + 0.5);
        SettlementPopulation.tick(level, fake);
        Settlers.tick(fake);
        GatePosts.tick(level, fake);
        return "visited " + place.id() + " at " + place.x() + "," + y + "," + place.z();
    }

    /**
     * Dev: removes every townsperson, villager and guard around the place (not quest or guild people) and forgets which
     * anchors were filled, so the next {@link #visit} populates the settlement from scratch with the current code.
     */
    public static int repopulate(ServerLevel level, Place place, int radius) {
        int removed = 0;
        AABB box = new AABB(place.x() - radius, level.getMinBuildHeight(), place.z() - radius, place.x() + radius,
                level.getMaxBuildHeight(), place.z() + radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                x -> x instanceof NpcEntity || x instanceof AbstractVillager || x instanceof GuardEntity)) {
            var pd = e.getPersistentData();
            if (pd.contains(com.skycraft.quest.QuestSpawner.TAG_QUEST) || pd.contains(com.skycraft.quest.story.QuestNpcs.TAG)) continue;
            e.discard();
            removed++;
        }
        SettlementAnchors data = SettlementAnchors.get(level);
        SettlementAnchors.find(level, place.id()).ifPresent(en -> {
            en.forgetSpawned();
            data.setDirty();
        });
        GatePosts.clear();
        return removed;
    }

    public static Optional<Place> place(String id) {
        return Places.byId(id);
    }
}
