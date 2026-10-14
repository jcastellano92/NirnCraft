package com.skycraft.society;

import com.mojang.authlib.GameProfile;
import com.skycraft.Skycraft;
import com.skycraft.combat.RespawnPoints;
import com.skycraft.core.PlayerDataEvents;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.roads.Travelers;
import com.skycraft.society.NpcNames.Race;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.SettlementPopulation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Headless self-test of the society rules the owner asked for in playtest 4, on a real settlement of the world
 * (Whiterun) with a fake player standing in it:
 * <ul>
 *     <li>the population is capped: one person per housing-plan slot, no unadopted settlers, a handful of visitors;</li>
 *     <li>the watch follows the settlement's size: two posted guards (one on each side of the main gate, replaced
 *     when killed) in a major settlement, one wandering guard in a minor one, none in a hamlet, none outside; the
 *     right guard for the region (the table of {@link com.skycraft.creatures.GuardProfiles});</li>
 *     <li>at night every resident is still there and sleeps in a bed;</li>
 *     <li>villages and towns are their own people, the road mix has 15% merchants;</li>
 *     <li>death respawns at the nearest discovered town, never in the intro cottage.</li>
 * </ul>
 * It takes about a minute of server time (the night needs the people to walk home). Runs as the autorun task
 * {@code society} and as {@code /skycraft-society selftest}; the result is one log line,
 * {@code Society selftest: n/m checks passed, k problems}.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SocietySelfTest {
    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("skycraft-society-selftest".getBytes()), "[SocietyTest]");

    private SocietySelfTest() {}

    /** Results: one line per check, {@link #summary()} for the QA log. */
    public record Report(int passed, int total, List<String> lines, List<String> failed) {
        public int problems() {
            return total - passed;
        }

        public String summary() {
            return "Society selftest: " + passed + "/" + total + " checks passed, " + problems() + " problems"
                    + (failed.isEmpty() ? "" : " (" + String.join("; ", failed) + ")");
        }
    }

    public static void registerAutorun() {
        PerfAutorun.register("society", (t, done) -> start(t.server(), r -> {
            for (String l : r.lines()) Skycraft.LOGGER.info("[society] {}", l);
            Skycraft.LOGGER.info("{}", r.summary());
            done.run();
        }));
    }

    // ------------------------------------------------------------------ the little state machine

    private record Step(int delay, Runnable action) {}

    private static final class Run {
        final MinecraftServer server;
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        final List<String> lines = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final Consumer<Report> done;
        int passed, total, wait;
        // state carried between steps
        Place town;
        FakePlayer fake;
        long originalTime;
        SocietyCensus.Result day;

        Run(MinecraftServer server, Consumer<Report> done) {
            this.server = server;
            this.done = done;
        }

        void check(String name, boolean ok, String detail) {
            total++;
            if (ok) passed++;
            else failed.add(name);
            lines.add((ok ? "ok   " : "FAIL ") + name + ": " + detail);
        }
    }

    private static Run running;

    /** Starts the test (one at a time); {@code done} gets the report when it has run to the end. */
    public static boolean start(MinecraftServer server, Consumer<Report> done) {
        if (running != null) return false;
        Run r = new Run(server, done);
        running = r;
        try {
            plan(r);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Society selftest could not start", e);
            r.check("selftest started", false, e.toString());
            finish(r);
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        Run r = running;
        if (r == null || event.phase != TickEvent.Phase.END) return;
        if (r.wait-- > 0) return;
        Step s = r.steps.poll();
        if (s == null) {
            finish(r);
            return;
        }
        r.wait = s.delay();
        try {
            s.action().run();
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Society selftest step threw", e);
            r.check("selftest step ran", false, e.toString());
        }
    }

    private static void finish(Run r) {
        running = null;
        try {
            if (r.server != null && r.originalTime >= 0 && r.town != null) r.server.overworld().setDayTime(r.originalTime);
        } catch (RuntimeException ignored) {
            // best effort
        }
        r.done.accept(new Report(r.passed, r.total, r.lines, r.failed));
    }

    // ------------------------------------------------------------------ the scenario

    private static void plan(Run r) {
        ServerLevel level = r.server.overworld();
        r.originalTime = level.getDayTime();
        r.town = Places.byId("whiterun").orElseGet(() -> Places.all().stream()
                .filter(p -> p.type().isSettlement() && !"homestead".equals(p.variant())).findFirst().orElse(null));
        if (r.town == null) {
            r.check("a town to test in", false, "no settlement place is defined");
            r.steps.add(new Step(0, () -> {}));
            return;
        }
        r.fake = FakePlayerFactory.get(level, PROFILE);

        // pure checks first (no world needed)
        r.steps.add(new Step(0, () -> peopleMixChecks(r)));
        r.steps.add(new Step(0, () -> travellerMixChecks(r, level)));
        r.steps.add(new Step(0, () -> respawnChecks(r, level)));
        r.steps.add(new Step(0, () -> watchRuleChecks(r)));
        r.steps.add(new Step(0, () -> guardTypeChecks(r)));

        // the town: three visits (a populate pass each), then count
        r.steps.add(new Step(0, () -> {
            level.getChunk(r.town.x() >> 4, r.town.z() >> 4);
            SocietyCensus.visit(level, r.town);
        }));
        r.steps.add(new Step(40, () -> SocietyCensus.visit(level, r.town)));
        r.steps.add(new Step(40, () -> SocietyCensus.visit(level, r.town)));
        r.steps.add(new Step(150, () -> daytimeChecks(r, level)));
        r.steps.add(new Step(10, () -> gateReplaceChecks(r, level)));
        // night: everybody is still here, and asleep in a bed
        r.steps.add(new Step(40, () -> {
            long day = level.getDayTime() / 24000L;
            level.setDayTime(day * 24000L + 18000L);
            SocietyCensus.visit(level, r.town);
        }));
        // the settlement tick runs every two seconds with a player about: the visits stand in for it
        for (int k = 0; k < 6; k++) r.steps.add(new Step(200, () -> SocietyCensus.visit(level, r.town)));
        r.steps.add(new Step(100, () -> nightChecks(r, level)));
        // a minor settlement (one wanderer) and a hamlet (none), each visited three times like the town
        for (String id : new String[]{MINOR_SAMPLE, HAMLET_SAMPLE}) {
            Optional<Place> sample = Places.byId(id);
            if (sample.isEmpty()) continue;
            r.steps.add(new Step(0, () -> {
                level.setDayTime((level.getDayTime() / 24000L) * 24000L + 6000L);
                level.getChunk(sample.get().x() >> 4, sample.get().z() >> 4);
                SocietyCensus.visit(level, sample.get());
            }));
            r.steps.add(new Step(40, () -> SocietyCensus.visit(level, sample.get())));
            r.steps.add(new Step(40, () -> SocietyCensus.visit(level, sample.get())));
            r.steps.add(new Step(60, () -> {
                if (id.equals(MINOR_SAMPLE)) minorWatchChecks(r, level, sample.get());
                else noWatchChecks(r, level, sample.get());
            }));
        }
    }

    /** A Riverwood-class town for the one-wanderer check, and a village for the none check. */
    private static final String MINOR_SAMPLE = "riverwood", HAMLET_SAMPLE = "old_hroldan";

    /** The rule, on the places file itself (no world needed): who gets how many guards. */
    private static void watchRuleChecks(Run r) {
        int major = 0, minor = 0, none = 0, wrong = 0;
        List<String> samples = new ArrayList<>();
        for (Place p : Places.all()) {
            GuardWatch.Watch w = GuardWatch.of(p);
            boolean ok = switch (p.type()) {
                case CAPITAL, CITY -> w == GuardWatch.Watch.MAJOR || "homestead".equals(p.variant());
                case TOWN -> w == (p.radius() >= GuardWatch.SEAT_RADIUS ? GuardWatch.Watch.MAJOR : GuardWatch.Watch.MINOR)
                        || w == GuardWatch.Watch.NONE; // the exempt ones: Helgen, the College, a homestead
                default -> w == GuardWatch.Watch.NONE; // villages, farms, forts, dungeons, landmarks: nothing
            };
            if (!ok && samples.size() < 4) samples.add(p.id());
            if (!ok) wrong++;
            switch (w) {
                case MAJOR -> major++;
                case MINOR -> minor++;
                default -> none++;
            }
        }
        r.check("a capital or city has the major watch, a town the minor one, a village, farm, fort or dungeon none", wrong == 0,
                major + " major, " + minor + " minor, " + none + " none; wrong " + samples);
        r.check("a major settlement has two posted guards, a minor one wanderer, the rest none",
                GuardWatch.count(GuardWatch.Watch.MAJOR) == 2 && GuardWatch.count(GuardWatch.Watch.MINOR) == 1
                        && GuardWatch.count(GuardWatch.Watch.NONE) == 0, "2 / 1 / 0");
        int[][] posts = GuardWatch.posts(GuardWatch.Watch.MAJOR);
        r.check("the two posts are the two sides of the main gate", posts.length == 2 && posts[0][0] == 0 && posts[1][0] == 0
                && posts[0][1] != posts[1][1], posts.length + " posts");
        r.check("a minor settlement has no gate post and a village has none", GuardWatch.posts(GuardWatch.Watch.MINOR).length == 0
                && GuardWatch.posts(GuardWatch.Watch.NONE).length == 0, "no posts");
    }

    /** Which guard stands where, on real settlements of every province (the table, no world needed). */
    private static void guardTypeChecks(Run r) {
        String[][] expect = {
                {"whiterun", "whiterun"}, {"windhelm", "eastmarch"}, {"riften", "the_rift"}, {"solitude", "haafingar"},
                {"markarth", "the_reach"}, {"imperial_city", "imperial_watch"}, {"vivec", "dunmer_ordinator"},
                {"balmora", "dunmer_hlaalu"}, {"aldruhn", "dunmer_redoran"}, {"sadrith_mora", "dunmer_telvanni"},
                {"caldera", "imperial_legion"}, {"raven_rock", "imperial_legion"}, {"wayrest", "breton_guard"},
                {"orsinium", "orc_stronghold"}, {"jehanna", "breton_guard"}, {"sentinel", "redguard_crown"},
                {"abahs_landing", "redguard_forebear"}, {"dune", "khajiit_dune"}, {"torval", "khajiit_moon"},
                {"alinor", "altmer_aldmeri"}, {"elden_root", "bosmer_wood"}, {"lilmoth", "argonian_hist"}};
        int tested = 0;
        List<String> bad = new ArrayList<>();
        for (String[] row : expect) {
            Optional<Place> p = Places.byId(row[0]);
            if (p.isEmpty()) continue;
            tested++;
            String got = com.skycraft.creatures.GuardProfiles.of(p.get()).profile().id();
            if (!got.equals(row[1])) bad.add(row[0] + " is " + got + ", wanted " + row[1]);
        }
        r.check("the guard of each region is that region's own (" + tested + " settlements over the provinces)", tested >= 6 && bad.isEmpty(),
                bad.isEmpty() ? tested + " settlements as the table says" : bad.toString());

        // nobody outside Skyrim wears a Skyrim hold's colours, unless they are Nord (or Atmoran) themselves
        java.util.Set<String> holds = new java.util.HashSet<>(java.util.Arrays.asList(com.skycraft.core.Holds.NAMES));
        int outsiders = 0;
        List<String> wrong = new ArrayList<>();
        for (Place p : Places.all()) {
            if (GuardWatch.of(p) == GuardWatch.Watch.NONE) continue;
            outsiders++;
            String id = com.skycraft.creatures.GuardProfiles.of(p).profile().id();
            boolean skyrimPlace = p.regionId().startsWith("skyrim/");
            boolean nordPlace = "nord".equals(p.culture()) || "atmoran".equals(p.culture());
            if (holds.contains(id) && !skyrimPlace && !nordPlace) wrong.add(p.id());
            if (skyrimPlace && !holds.contains(id)) wrong.add(p.id());
        }
        r.check("no Skyrim hold livery outside Skyrim (and every hold of Skyrim has its own)", wrong.isEmpty(),
                outsiders + " guarded settlements, wrong: " + (wrong.size() > 6 ? wrong.subList(0, 6) + "..." : wrong));

        // every profile has its skin, and the guarded settlements all resolve through the table
        List<String> noSkin = new ArrayList<>();
        for (var profile : com.skycraft.creatures.GuardProfiles.all()) {
            if (SocietySelfTest.class.getResource("/assets/skycraft/textures/entity/guard/guard_" + profile.id() + ".png") == null) {
                noSkin.add(profile.id());
            }
        }
        r.check("every guard profile has its skin texture", noSkin.isEmpty(),
                com.skycraft.creatures.GuardProfiles.count() + " profiles, missing " + noSkin);
    }

    private static List<GuardEntity> guardsAround(ServerLevel level, SettlementAnchors.Entry e) {
        return level.getEntitiesOfClass(GuardEntity.class, boxOf(e, level), GuardEntity::isAlive);
    }

    /** A minor settlement: exactly one guard, walking a route, belonging to no gate; and still one after more visits. */
    private static void minorWatchChecks(Run r, ServerLevel level, Place town) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(level, town.id());
        if (entry.isEmpty()) {
            r.check("the minor settlement's anchors are recorded", false, "no entry for " + town.id());
            return;
        }
        List<GuardEntity> guards = guardsAround(level, entry.get());
        String key = town.id() + "|patrol";
        int patrol = 0, posted = 0, foreign = 0;
        boolean walking = false, ownProfile = true;
        String want = com.skycraft.creatures.GuardProfiles.of(town).profile().id();
        for (GuardEntity g : guards) {
            var pd = g.getPersistentData();
            if (key.equals(pd.getString(GuardWatch.PATROL_KEY))) {
                patrol++;
                walking |= g.isPatrolling() && g.getPost() == null;
                ownProfile &= g.getProfile().id().equals(want);
            } else if (pd.contains(GatePosts.KEY)) {
                posted++;
            } else if (!pd.getBoolean(GuardWatch.SCRIPTED)) {
                foreign++;
            }
        }
        r.check(town.id() + " (a town) has exactly one guard", patrol == 1 && guards.size() == 1,
                patrol + " patrol guards, " + guards.size() + " in all, " + foreign + " unmarked");
        r.check("the one guard of a minor settlement walks a route and is posted at no gate", walking && posted == 0,
                walking ? "patrolling, " + posted + " gate posts" : "not patrolling");
        r.check("the town's guard is its own region's (" + want + ")", ownProfile, want);
        // a second look changes nothing (no duplicate patrols)
        GatePosts.tick(level, r.fake);
        GatePosts.tick(level, r.fake);
        int again = 0;
        for (GuardEntity g : guardsAround(level, entry.get())) if (key.equals(g.getPersistentData().getString(GuardWatch.PATROL_KEY))) again++;
        r.check("visiting a minor settlement again never makes a second guard", again == 1, again + " patrol guards");
    }

    /** A village: nobody keeps the watch. */
    private static void noWatchChecks(Run r, ServerLevel level, Place village) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(level, village.id());
        if (entry.isEmpty()) {
            r.check("the village's anchors are recorded", false, "no entry for " + village.id());
            return;
        }
        int n = guardsAround(level, entry.get()).size();
        r.check(village.id() + " (a village) has no guard at all", n == 0, n + " guards");
        // the open country: no guard stands anywhere that is not a settlement's, a patrol's or somebody's scripted one
        int stray = 0;
        for (GuardEntity g : level.getEntitiesOfClass(GuardEntity.class,
                new AABB(village.x() - 300, level.getMinBuildHeight(), village.z() - 300, village.x() + 300, level.getMaxBuildHeight(),
                        village.z() + 300), GuardEntity::isAlive)) {
            var pd = g.getPersistentData();
            if (!pd.contains(GatePosts.KEY) && !pd.contains(GuardWatch.PATROL_KEY) && !pd.getBoolean(GuardWatch.SCRIPTED)
                    && !pd.contains(com.skycraft.quest.QuestSpawner.TAG_QUEST) && !pd.contains(com.skycraft.quest.story.QuestNpcs.TAG)
                    && !Encounters.isTagged(g) && !g.getTags().contains(com.skycraft.crime.Jail.NPC_TAG)) {
                stray++;
            }
        }
        r.check("no hold guard roams the country round a village (300 blocks)", stray == 0, stray + " unmarked guards");
    }

    private static void peopleMixChecks(Run r) {
        RandomSource rand = RandomSource.create(4242);
        int outsiders = 0, n = 2000;
        for (int i = 0; i < n; i++) {
            Race x = Cultures.pickSettled(Race.NORD, 2, null, rand);
            if (x == Race.ORC || x == Race.ALTMER || x == Race.BOSMER || x == Race.KHAJIIT || x == Race.ARGONIAN) outsiders++;
        }
        r.check("a Nord village has no orcs, Altmer, Bosmer, Khajiit or Argonians among its people", outsiders == 0,
                outsiders + " of " + n + " rolls");
        int nord = 0;
        for (int i = 0; i < n; i++) if (Cultures.pickSettled(Race.NORD, 2, null, rand) == Race.NORD) nord++;
        r.check("a Nord village is at least 90% Nord", nord * 100 >= n * 90, nord * 100 / n + "%");
        int city = 0;
        for (int i = 0; i < n; i++) if (Cultures.pickSettled(Race.NORD, 0, null, rand) == Race.NORD) city++;
        r.check("a Nord city is mostly but not only Nord (50-92%)", city * 100 >= n * 50 && city * 100 <= n * 92, city * 100 / n + "%");
    }

    private static void travellerMixChecks(Run r, ServerLevel level) {
        Map<String, Integer> day = Travelers.sampleMix(level, 4000, false);
        int merchants = day.getOrDefault(Travelers.CARAVAN, 0) + day.getOrDefault(Travelers.TINKER, 0);
        int pct = merchants * 100 / 4000;
        r.check("travelling merchants are about 15% of the road traffic (10-21%)", pct >= 10 && pct <= 21, pct + "% " + day);
        int kinds = 0;
        for (int v : day.values()) if (v > 0) kinds++;
        r.check("the road has at least eight kinds of traveller", kinds >= 8, kinds + " kinds");
        Map<String, Integer> night = Travelers.sampleMix(level, 600, true);
        int nm = night.getOrDefault(Travelers.CARAVAN, 0) + night.getOrDefault(Travelers.TINKER, 0);
        r.check("no merchants walk the roads at night", nm == 0, night.toString());
    }

    private static void respawnChecks(Run r, ServerLevel level) {
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("skycraft-society-respawn".getBytes()), "[RespawnTest]"));
        BlockPos cottageBed = new BlockPos(r.town.x() + 700, 100, r.town.z() + 700);
        RespawnPoints.set(p, Level.OVERWORLD, cottageBed);
        r.check("the intro cottage's bed is not a home", !RespawnPoints.isEarned(p) && RespawnPoints.home(p) == null, "provisional bed set");
        r.check("the cottage bed is remembered as provisional (sleeping in it later does not make it a home)",
                RespawnPoints.isProvisional(p, cottageBed), "provisional list");
        // walk into the town: it is on the player's list of places to come back to
        p.setPos(r.town.x() + 20.5, 100, r.town.z() + 10.5);
        RespawnPoints.noteVisit(p);
        r.check("entering a settlement's radius records it as visited", RespawnPoints.visitedTowns(p).contains(r.town.id()),
                RespawnPoints.visitedTowns(p).toString());
        // die close to it: the respawn is that town, at a spot inside its reach
        BlockPos died = new BlockPos(r.town.x() + 60, 100, r.town.z() + 60);
        BlockPos spot = PlayerDataEvents.nearestVisitedSettlement(p, level, died);
        boolean ok = spot != null && r.town.distSqr(spot.getX(), spot.getZ()) <= 140L * 140L;
        r.check("dying near a discovered town respawns in that town (not the cottage)", ok,
                spot == null ? "no spot" : "spot " + spot.getX() + "," + spot.getZ() + " for " + r.town.id());
        // a nearer discovered town wins over a far one
        Optional<Place> other = Places.all().stream()
                .filter(x -> x.type().isSettlement() && !x.id().equals(r.town.id()) && !"homestead".equals(x.variant()))
                .filter(x -> x.distSqr(r.town.x(), r.town.z()) > 400L * 400L)
                .min((a, b) -> Long.compare(a.distSqr(r.town.x(), r.town.z()), b.distSqr(r.town.x(), r.town.z())));
        if (other.isPresent()) {
            Place far = other.get();
            p.setPos(far.x() + 0.5, 100, far.z() + 0.5);
            RespawnPoints.noteVisit(p);
            BlockPos nearFar = new BlockPos(far.x() + 30, 100, far.z() + 30);
            BlockPos s2 = PlayerDataEvents.nearestVisitedSettlement(p, level, nearFar);
            r.check("the nearest of several discovered towns is chosen", s2 != null && far.distSqr(s2.getX(), s2.getZ()) <= 140L * 140L,
                    "death near " + far.id() + " -> " + (s2 == null ? "none" : s2.getX() + "," + s2.getZ()));
        }
        // a bed that was slept in and may be used is a home; the cottage never becomes one
        BlockPos inn = new BlockPos(r.town.x() + 10, 100, r.town.z() + 10);
        RespawnPoints.setOwned(p, Level.OVERWORLD, inn);
        r.check("a bed slept in and usable is a home", RespawnPoints.isEarned(p) && inn.equals(RespawnPoints.home(p)), "home set");
        RespawnPoints.set(p, Level.OVERWORLD, cottageBed);
        r.check("setting the provisional bed again does not replace the home", inn.equals(RespawnPoints.home(p)),
                "home " + RespawnPoints.home(p));
    }

    private static void daytimeChecks(Run r, ServerLevel level) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(level, r.town.id());
        if (entry.isEmpty()) {
            r.check("the town's anchors are recorded", false, "no entry for " + r.town.id());
            return;
        }
        var plan = SettlementPopulation.housing(entry.get());
        SocietyCensus.Result c = SocietyCensus.census(level, r.town, entry.get().radius + 20);
        r.day = c;
        int slots = plan.bySlot.size();
        r.check("nobody lives here without a housing-plan slot", c.bySource.getOrDefault("settler-unadopted", 0) == 0, c.bySource.toString());
        r.check("residents never exceed the housing plan's slots (plan " + slots + ")", c.residentsPresent() <= slots + 4,
                c.residentsPresent() + " present: " + c.bySource);
        r.check("no bed holds two sleepers", c.duplicateBeds == 0, c.duplicateBeds + " shared beds");
        int cap = SettlementCrowd.visitorCap(SettlementPopulation.tier(entry.get()));
        r.check("at most " + cap + " visitors at a time", c.travellers() <= cap, c.travellers() + " visitors");
        // duplicates of one slot
        Map<String, Integer> perSlot = new HashMap<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, boxOf(entry.get(), level), x -> x.isAlive())) {
            String slot = e.getPersistentData().getString(Housing.SLOT_KEY);
            if (!slot.isEmpty()) perSlot.merge(slot, 1, Integer::sum);
        }
        int dup = 0;
        for (int n : perSlot.values()) if (n > 1) dup++;
        r.check("no housing-plan slot is filled twice", dup == 0, dup + " slots with two people");

        // the gate watch: two posted guards, one on each side of the main gate, and nobody else keeping a watch
        List<BlockPos> postGates = GatePosts.postGates(level, entry.get());
        List<String> keys = GatePosts.keysOf(level, entry.get());
        GuardWatch.Watch watch = GuardWatch.of(entry.get());
        r.check(r.town.id() + " has the major watch", watch == GuardWatch.Watch.MAJOR, watch.name());
        int posted = 0, others = 0, wrongSide = 0, moving = 0;
        List<String> found = new ArrayList<>();
        for (GuardEntity guard : guardsAround(level, entry.get())) {
            String k = guard.getPersistentData().getString(GatePosts.KEY);
            if (k.isEmpty()) {
                if (!guard.getPersistentData().getBoolean(GuardWatch.SCRIPTED)) others++;
                continue;
            }
            posted++;
            found.add(k);
            if (!keys.contains(k)) wrongSide++;
            if (guard.getPost() == null) moving++;
            if (!postGates.isEmpty() && guard.distanceToSqr(postGates.get(0).getX() + 0.5, postGates.get(0).getY(), postGates.get(0).getZ() + 0.5) > 36) {
                wrongSide++;
            }
        }
        boolean twoSides = keys.size() == 2 && found.contains(keys.get(0)) && found.contains(keys.get(1));
        r.check("a major settlement has exactly two posted guards, one on each side of the main gate",
                posted == 2 && twoSides && wrongSide == 0,
                posted + " posted, keys " + found + " of " + keys + ", " + wrongSide + " off the gate");
        r.check("the posted guards stand still on their posts", posted > 0 && moving == 0, moving + " without a post");
        r.check("a major settlement keeps no other guard (no barracks guards, no patrol, nobody wandering)", others == 0, others + " others");
    }

    private static AABB boxOf(SettlementAnchors.Entry e, ServerLevel level) {
        int rad = e.radius + 20;
        return new AABB(e.x - rad, level.getMinBuildHeight(), e.z - rad, e.x + rad, level.getMaxBuildHeight(), e.z + rad);
    }

    private static void gateReplaceChecks(Run r, ServerLevel level) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(level, r.town.id());
        if (entry.isEmpty()) return;
        List<BlockPos> gates = GatePosts.postGates(level, entry.get());
        List<String> keys = GatePosts.keysOf(level, entry.get());
        for (int i = 0; i < gates.size(); i++) {
            BlockPos g = gates.get(i);
            if (!level.isLoaded(g) || !level.isPositionEntityTicking(g)) continue;
            GuardEntity victim = null;
            for (GuardEntity guard : level.getEntitiesOfClass(GuardEntity.class, boxOf(entry.get(), level), GuardEntity::isAlive)) {
                if (keys.get(i).equals(guard.getPersistentData().getString(GatePosts.KEY))) victim = guard;
            }
            if (victim == null) continue;
            victim.discard(); // killed
            GatePosts.clear();
            r.fake.setPos(r.town.x() + 0.5, g.getY(), r.town.z() + 0.5);
            GatePosts.tick(level, r.fake);
            boolean back = false;
            int total = 0;
            for (GuardEntity guard : level.getEntitiesOfClass(GuardEntity.class, boxOf(entry.get(), level), GuardEntity::isAlive)) {
                if (guard.getPersistentData().contains(GatePosts.KEY)) total++;
                if (keys.get(i).equals(guard.getPersistentData().getString(GatePosts.KEY))) back = true;
            }
            r.check("a gate guard that is killed is replaced (and the gate still has exactly two)", back && total == 2,
                    "post " + i + " of " + entry.get().key + ", the visitor stands "
                    + (int) Math.sqrt(r.fake.distanceToSqr(g.getX() + 0.5, g.getY(), g.getZ() + 0.5)) + " blocks off, " + total
                    + " posted guards");
            return;
        }
        r.check("a gate guard that is killed is replaced", gates.isEmpty(), gates.isEmpty() ? "the town has no gates" : "no gate in range");
    }

    private static void nightChecks(Run r, ServerLevel level) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(level, r.town.id());
        if (entry.isEmpty() || r.day == null) return;
        SocietyCensus.Result n = SocietyCensus.census(level, r.town, entry.get().radius + 20);
        int before = r.day.residentsPresent();
        int after = n.residentsPresent();
        r.check("nobody disappears at night", after >= before, before + " residents by day, " + after + " at night");
        // a resident who sleeps is in a bed; the few meant to be out (a drunk, a beggar) and the watch are not counted
        int asleep = n.sleepingInBed();
        int out = n.outdoors();
        r.check("at night the residents sleep in beds (at least 70% of those in town)", after == 0 || asleep * 100 >= after * 70,
                asleep + " asleep in a bed of " + after + " residents, " + out + " in the open");
        r.check("a few people and the guards are still about at night (a living street, not an empty one)",
                n.guardCount() >= 2, n.guardCount() + " guards");
        r.check("nobody sleeps in a bed that is not a bed", n.sleeping() == asleep, n.sleeping() + " sleeping, " + asleep + " in beds");
    }
}
