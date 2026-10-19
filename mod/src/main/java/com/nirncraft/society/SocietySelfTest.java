package com.nirncraft.society;

import com.mojang.authlib.GameProfile;
import com.nirncraft.NirnCraft;
import com.nirncraft.combat.RespawnPoints;
import com.nirncraft.core.PlayerDataEvents;
import com.nirncraft.creatures.entity.GuardEntity;
import com.nirncraft.perf.PerfAutorun;
import com.nirncraft.roads.Travelers;
import com.nirncraft.society.NpcNames.Race;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.settlement.Anchor;
import com.nirncraft.tamriel.settlement.AnchorType;
import com.nirncraft.tamriel.settlement.SettlementAnchors;
import com.nirncraft.tamriel.settlement.SettlementPopulation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

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
 *     right guard for the region (the table of {@link com.nirncraft.creatures.GuardProfiles});</li>
 *     <li>at night every resident is still there and sleeps in a bed;</li>
 *     <li>villages and towns are their own people, the road mix has 15% merchants;</li>
 *     <li>death respawns at the nearest discovered town, never in the intro cottage.</li>
 * </ul>
 * It takes about a minute of server time (the night needs the people to walk home). Runs as the autorun task
 * {@code society} and as {@code /nirncraft-society selftest}; the result is one log line,
 * {@code Society selftest: n/m checks passed, k problems}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SocietySelfTest {
    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("nirncraft-society-selftest".getBytes()), "[SocietyTest]");

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
            for (String l : r.lines()) NirnCraft.LOGGER.info("[society] {}", l);
            NirnCraft.LOGGER.info("{}", r.summary());
            done.run();
        }));
        PerfAutorun.register("trainer", (t, done) -> {
            Report r = runTrainerSelfTest(t.server());
            for (String l : r.lines()) NirnCraft.LOGGER.info("[trainer] {}", l);
            NirnCraft.LOGGER.info("{}", r.summary());
            done.run();
        });
    }

    /** Immediate headless self-test of R3d-1 town services: trainers, courts, and reactive dialogue. */
    public static Report runTrainerSelfTest(MinecraftServer server) {
        Run r = new Run(server, rep -> {});
        trainerChecks(r, server.overworld());
        return new Report(r.passed, r.total, r.lines, r.failed);
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
            NirnCraft.LOGGER.error("Society selftest could not start", e);
            r.check("selftest started", false, e.toString());
            finish(r);
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        Run r = running;
        if (r == null || !(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
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
            NirnCraft.LOGGER.error("Society selftest step threw", e);
            r.check("selftest step ran", false, e.toString());
        }
    }

    private static void forceSettlement(ServerLevel level, Place place, boolean forced) {
        if (place == null) return;
        int rad = place.radius() + 32;
        int cx0 = (place.x() - rad) >> 4, cx1 = (place.x() + rad) >> 4;
        int cz0 = (place.z() - rad) >> 4, cz1 = (place.z() + rad) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                level.setChunkForced(cx, cz, forced);
                if (forced) level.getChunk(cx, cz);
            }
        }
    }

    private static void finish(Run r) {
        running = null;
        try {
            if (r.server != null) {
                ServerLevel level = r.server.overworld();
                if (r.originalTime >= 0 && r.town != null) level.dimensionType().defaultClock().ifPresent(clock -> level.clockManager().setTotalTicks(clock, r.originalTime));
                forceSettlement(level, r.town, false);
                Places.byId(MINOR_SAMPLE).ifPresent(p -> forceSettlement(level, p, false));
                Places.byId(HAMLET_SAMPLE).ifPresent(p -> forceSettlement(level, p, false));
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
        r.done.accept(new Report(r.passed, r.total, r.lines, r.failed));
    }

    // ------------------------------------------------------------------ the scenario

    private static void plan(Run r) {
        ServerLevel level = r.server.overworld();
        r.originalTime = level.getDefaultClockTime();
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
        r.steps.add(new Step(0, () -> trainerChecks(r, level)));

        // the town: three visits (a populate pass each), then count
        r.steps.add(new Step(0, () -> {
            forceSettlement(level, r.town, true);
            SocietyCensus.visit(level, r.town);
        }));
        r.steps.add(new Step(40, () -> SocietyCensus.visit(level, r.town)));
        r.steps.add(new Step(40, () -> SocietyCensus.visit(level, r.town)));
        r.steps.add(new Step(150, () -> daytimeChecks(r, level)));
        r.steps.add(new Step(10, () -> gateReplaceChecks(r, level)));
        // night: everybody is still here, and asleep in a bed
        r.steps.add(new Step(40, () -> {
            long day = level.getDefaultClockTime() / 24000L;
            level.dimensionType().defaultClock().ifPresent(clock -> level.clockManager().setTotalTicks(clock, day * 24000L + 18000L));
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
                level.dimensionType().defaultClock().ifPresent(clock -> level.clockManager().setTotalTicks(clock, (level.getDefaultClockTime() / 24000L) * 24000L + 6000L));
                forceSettlement(level, sample.get(), true);
                SocietyCensus.visit(level, sample.get());
            }));
            r.steps.add(new Step(40, () -> SocietyCensus.visit(level, sample.get())));
            r.steps.add(new Step(40, () -> SocietyCensus.visit(level, sample.get())));
            r.steps.add(new Step(60, () -> {
                if (id.equals(MINOR_SAMPLE)) minorWatchChecks(r, level, sample.get());
                else noWatchChecks(r, level, sample.get());
                forceSettlement(level, sample.get(), false);
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
            String got = com.nirncraft.creatures.GuardProfiles.of(p.get()).profile().id();
            if (!got.equals(row[1])) bad.add(row[0] + " is " + got + ", wanted " + row[1]);
        }
        r.check("the guard of each region is that region's own (" + tested + " settlements over the provinces)", tested >= 6 && bad.isEmpty(),
                bad.isEmpty() ? tested + " settlements as the table says" : bad.toString());

        // nobody outside Skyrim wears a Skyrim hold's colours, unless they are Nord (or Atmoran) themselves
        java.util.Set<String> holds = new java.util.HashSet<>(java.util.Arrays.asList(com.nirncraft.core.Holds.NAMES));
        int outsiders = 0;
        List<String> wrong = new ArrayList<>();
        for (Place p : Places.all()) {
            if (GuardWatch.of(p) == GuardWatch.Watch.NONE) continue;
            outsiders++;
            String id = com.nirncraft.creatures.GuardProfiles.of(p).profile().id();
            boolean skyrimPlace = p.regionId().startsWith("skyrim/");
            boolean nordPlace = "nord".equals(p.culture()) || "atmoran".equals(p.culture());
            if (holds.contains(id) && !skyrimPlace && !nordPlace) wrong.add(p.id());
            if (skyrimPlace && !holds.contains(id)) wrong.add(p.id());
        }
        r.check("no Skyrim hold livery outside Skyrim (and every hold of Skyrim has its own)", wrong.isEmpty(),
                outsiders + " guarded settlements, wrong: " + (wrong.size() > 6 ? wrong.subList(0, 6) + "..." : wrong));

        // every profile has its skin, and the guarded settlements all resolve through the table
        List<String> noSkin = new ArrayList<>();
        for (var profile : com.nirncraft.creatures.GuardProfiles.all()) {
            if (SocietySelfTest.class.getResource("/assets/nirncraft/textures/entity/guard/guard_" + profile.id() + ".png") == null) {
                noSkin.add(profile.id());
            }
        }
        r.check("every guard profile has its skin texture", noSkin.isEmpty(),
                com.nirncraft.creatures.GuardProfiles.count() + " profiles, missing " + noSkin);
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
        String want = com.nirncraft.creatures.GuardProfiles.of(town).profile().id();
        for (GuardEntity g : guards) {
            var pd = g.getPersistentData();
            if (key.equals(pd.getStringOr(GuardWatch.PATROL_KEY, ""))) {
                patrol++;
                walking |= g.isPatrolling() && g.getPost() == null;
                ownProfile &= g.getProfile().id().equals(want);
            } else if (pd.contains(GatePosts.KEY)) {
                posted++;
            } else if (!pd.getBooleanOr(GuardWatch.SCRIPTED, false)) {
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
        for (GuardEntity g : guardsAround(level, entry.get())) if (key.equals(g.getPersistentData().getStringOr(GuardWatch.PATROL_KEY, ""))) again++;
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
                new AABB(village.x() - 300, level.getMinY(), village.z() - 300, village.x() + 300, (level.getMaxY()+1),
                        village.z() + 300), GuardEntity::isAlive)) {
            var pd = g.getPersistentData();
            if (!pd.contains(GatePosts.KEY) && !pd.contains(GuardWatch.PATROL_KEY) && !pd.getBooleanOr(GuardWatch.SCRIPTED, false)
                    && !pd.contains(com.nirncraft.quest.QuestSpawner.TAG_QUEST) && !pd.contains(com.nirncraft.quest.story.QuestNpcs.TAG)
                    && !Encounters.isTagged(g) && !g.entityTags().contains(com.nirncraft.crime.Jail.NPC_TAG)) {
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
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("nirncraft-society-respawn".getBytes()), "[RespawnTest]"));
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
            String slot = e.getPersistentData().getStringOr(Housing.SLOT_KEY, "");
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
            String k = guard.getPersistentData().getStringOr(GatePosts.KEY, "");
            if (k.isEmpty()) {
                if (!guard.getPersistentData().getBooleanOr(GuardWatch.SCRIPTED, false)) others++;
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
        return new AABB(e.x - rad, level.getMinY(), e.z - rad, e.x + rad, (level.getMaxY()+1), e.z + rad);
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
                if (keys.get(i).equals(guard.getPersistentData().getStringOr(GatePosts.KEY, ""))) victim = guard;
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
                if (keys.get(i).equals(guard.getPersistentData().getStringOr(GatePosts.KEY, ""))) back = true;
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
        var missing = new java.util.HashSet<>(r.day.residentIds());
        missing.removeAll(n.residentIds());
        r.check("nobody disappears at night", missing.isEmpty(), before + " residents by day, " + after + " at night; missing UUIDs=" + missing);
        // a resident who sleeps is in a bed; the few meant to be out (a drunk, a beggar) and the watch are not counted
        int asleep = n.sleepingInBed();
        int out = n.outdoors();
        r.check("at night the residents sleep in beds (at least 70% of those in town)", after == 0 || asleep * 100 >= after * 70,
                asleep + " asleep in a bed of " + after + " residents, " + out + " in the open");
        r.check("a few people and the guards are still about at night (a living street, not an empty one)",
                n.guardCount() >= 2, n.guardCount() + " guards");
        r.check("nobody sleeps in a bed that is not a bed", n.sleeping() == asleep, n.sleeping() + " sleeping, " + asleep + " in beds");
    }

    /**
     * R3d-1 self-test: verifies trainer placement by tier, causal why fields, fee deduction, +1 skill level,
     * 5-per-level cap, trainer cap (50 Journeyman, 75 Expert, 90 Master), court roles (F-COURT), and reactive keys (F-REACT).
     */
    private static void trainerChecks(Run r, ServerLevel level) {
        List<SocietyTrainers.Spec> capitalSpecs = SocietyTrainers.trainersForSettlement("whiterun", 0, "skyrim/whiterun");
        long distinctCapSkills = capitalSpecs.stream().map(SocietyTrainers.Spec::skill).distinct().count();
        boolean allHaveWhy = capitalSpecs.stream().allMatch(s -> !s.why().isEmpty());
        r.check("a capital has >= 5 distinct skill trainers with WorldSheets why fields",
                distinctCapSkills >= 5 && allHaveWhy, distinctCapSkills + " distinct skills, why=" + allHaveWhy);

        List<SocietyTrainers.Spec> townSpecs = SocietyTrainers.trainersForSettlement("riverwood", 1, "skyrim/whiterun");
        r.check("a town has at least 1 skill trainer", !townSpecs.isEmpty(), townSpecs.size() + " trainers");

        Courts.Spec whiterunCourt = Courts.courtFor("whiterun");
        boolean courtComplete = !whiterunCourt.jarl().isBlank() && !whiterunCourt.steward().isBlank()
                && !whiterunCourt.courtWizard().isBlank() && !whiterunCourt.housecarl().isBlank();
        r.check("each Skyrim capital/city court has >= 3 distinct court roles (jarl, steward, court_wizard, housecarl)",
                courtComplete, whiterunCourt.jarl() + " / " + whiterunCourt.steward() + " / " + whiterunCourt.courtWizard() + " / " + whiterunCourt.housecarl());

        String holdKey = ContextualDialogue.resolveReactiveKey("whiterun", "dragon_slayer");
        String nonSkyrimRegionKey = ContextualDialogue.resolveReactiveKey("heartlands", "dragon_slayer");
        String fallbackKey = ContextualDialogue.resolveReactiveKey("cyrodiil", "dragon_slayer");
        r.check("reactive dialogue key resolves react.<region>.<deed> across all regions with fallback to react.generic.<deed>",
                "react.whiterun.dragon_slayer".equals(holdKey)
                        && "react.heartlands.dragon_slayer".equals(nonSkyrimRegionKey)
                        && "react.generic.dragon_slayer".equals(fallbackKey),
                holdKey + " / " + nonSkyrimRegionKey + " / " + fallbackKey);

        residentAndVoiceChecks(r, level);
        factionAttitudeAndEncounterChecks(r, level);
        bardAndArsenalChecks(r, level);

        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("nirncraft-trainer-test".getBytes()), "[TrainerTest]"));
        com.nirncraft.core.PlayerData data = com.nirncraft.core.SkyData.get(p);
        data.setLevel(15);
        data.setLevelXp(0f);
        data.setSkill(com.nirncraft.core.Skill.SMITHING, 15);
        data.setSkillXp(com.nirncraft.core.Skill.SMITHING, 0f);
        data.setTrainingsThisLevel(0);
        int currentGold = (int) com.nirncraft.core.Currency.balance(p);
        if (currentGold > 0) com.nirncraft.core.Currency.take(p, currentGold);
        com.nirncraft.core.Currency.give(p, 10000);

        com.nirncraft.society.entity.NpcEntity trainer = Npcs.create(level, new BlockPos(0, 80, 0), NpcRole.TRAINER);
        if (trainer == null) {
            r.check("trainer entity created", false, "Npcs.create returned null");
            return;
        }
        SocietyTrainers.Spec journeymanSpec = new SocietyTrainers.Spec(
                "Eorlund Test", com.nirncraft.core.Skill.SMITHING, SocietyTrainers.Tier.JOURNEYMAN,
                SocietyTrainers.JOURNEYMAN_CAP, "smith", List.of("skyrim:whiterun:L5:skyforge_steel"));
        SocietyTrainers.configureTrainer(trainer, journeymanSpec);

        String label = SocietyTrainers.formatOptionLabel(p, trainer, com.nirncraft.core.Skill.SMITHING).getString();
        int expectedFee15 = SocietyTrainers.feeFor(15, 50); // 50 + 15 * 15 = 275
        int goldBefore = (int) com.nirncraft.core.Currency.balance(p);
        SocietyTrainers.Result first = SocietyTrainers.train(p, trainer, com.nirncraft.core.Skill.SMITHING);
        int goldAfter = (int) com.nirncraft.core.Currency.balance(p);
        r.check("training deducts scaled gold fee (" + expectedFee15 + "g) and increments skill by +1 (15 -> 16)",
                first.ok() && first.newLevel() == 16 && data.getSkill(com.nirncraft.core.Skill.SMITHING) == 16
                        && (goldBefore - goldAfter) == expectedFee15 && data.getTrainingsThisLevel() == 1,
                "skill=" + data.getSkill(com.nirncraft.core.Skill.SMITHING) + ", feePaid=" + (goldBefore - goldAfter)
                        + ", used=" + data.getTrainingsThisLevel() + ", label='" + label + "'");

        // Train 4 more times to reach 5/5 trainings this character level (16 -> 17 -> 18 -> 19 -> 20)
        for (int i = 0; i < 4; i++) {
            SocietyTrainers.train(p, trainer, com.nirncraft.core.Skill.SMITHING);
        }
        int goldBeforeSixth = (int) com.nirncraft.core.Currency.balance(p);
        SocietyTrainers.Result sixth = SocietyTrainers.train(p, trainer, com.nirncraft.core.Skill.SMITHING);
        int goldAfterSixth = (int) com.nirncraft.core.Currency.balance(p);
        r.check("5-per-level training cap blocks a 6th training at the same character level without taking gold",
                sixth.status() == SocietyTrainers.Status.AT_LEVEL_CAP
                        && data.getSkill(com.nirncraft.core.Skill.SMITHING) == 20
                        && goldBeforeSixth == goldAfterSixth,
                "status=" + sixth.status() + ", skill=" + data.getSkill(com.nirncraft.core.Skill.SMITHING) + ", used=" + data.getTrainingsThisLevel());

        // Reset trainingsThisLevel and set skill to Journeyman cap (50) to verify trainer cap enforcement
        data.setTrainingsThisLevel(0);
        data.setSkill(com.nirncraft.core.Skill.SMITHING, SocietyTrainers.JOURNEYMAN_CAP);
        int goldBeforeCap = (int) com.nirncraft.core.Currency.balance(p);
        SocietyTrainers.Result atCap = SocietyTrainers.train(p, trainer, com.nirncraft.core.Skill.SMITHING);
        int goldAfterCap = (int) com.nirncraft.core.Currency.balance(p);
        r.check("trainer cap (50 Journeyman) blocks training when skill >= cap without taking gold",
                atCap.status() == SocietyTrainers.Status.AT_TRAINER_CAP
                        && data.getSkill(com.nirncraft.core.Skill.SMITHING) == 50
                        && goldBeforeCap == goldAfterCap,
                "status=" + atCap.status() + ", cap=" + atCap.trainerCap());

        // Upgrade trainer to Expert (cap 75) and verify 50 -> 51 succeeds at Expert fee (150 + 50 * 30 = 1650g)
        SocietyTrainers.Spec expertSpec = new SocietyTrainers.Spec(
                "Eorlund Test", com.nirncraft.core.Skill.SMITHING, SocietyTrainers.Tier.EXPERT,
                SocietyTrainers.EXPERT_CAP, "smith", List.of("skyrim:whiterun:L5:skyforge_steel"));
        SocietyTrainers.configureTrainer(trainer, expertSpec);
        int expectedExpertFee = SocietyTrainers.feeFor(50, 75);
        int goldBeforeExp = (int) com.nirncraft.core.Currency.balance(p);
        SocietyTrainers.Result expertTrain = SocietyTrainers.train(p, trainer, com.nirncraft.core.Skill.SMITHING);
        int goldAfterExp = (int) com.nirncraft.core.Currency.balance(p);
        r.check("Expert trainer (cap 75) trains skill 50 -> 51 at expert fee band (" + expectedExpertFee + "g)",
                expertTrain.ok() && data.getSkill(com.nirncraft.core.Skill.SMITHING) == 51
                        && (goldBeforeExp - goldAfterExp) == expectedExpertFee,
                "skill=" + data.getSkill(com.nirncraft.core.Skill.SMITHING) + ", feePaid=" + (goldBeforeExp - goldAfterExp));
    }

    /**
     * T1 self-test: verifies 12,709 authored residents across all 10 province files, canonical Whiterun and
     * 9-province sample settlements, and 100% regional voice & gossip coverage across all Regions.all().
     */
    private static void residentAndVoiceChecks(Run r, ServerLevel level) {
        int totalResidents = SocietyResidents.totalCount();
        r.check("authored residents loaded >= 12000 across all 10 province files",
                totalResidents >= 12000, "totalResidents=" + totalResidents + ", settlements=" + SocietyResidents.settlementCount());

        String[] sampleSettlements = {
                "whiterun", "balmora", "anvil", "daggerfall", "sentinel",
                "alinor", "elden_root", "rimmen", "stormhold"
        };
        List<String> missingSamples = new ArrayList<>();
        for (String s : sampleSettlements) {
            List<SocietyResidents.Entry> list = SocietyResidents.forSettlement(s);
            if (list.isEmpty() || list.stream().anyMatch(e -> e.name().isBlank() || e.why().isEmpty() || e.greet().isBlank())) {
                missingSamples.add(s + "(" + list.size() + ")");
            }
        }
        r.check("all 9 province sample settlements have authored residents with names, why citations, and dialogue",
                missingSamples.isEmpty(), missingSamples.isEmpty() ? "all 9 sample settlements verified" : "missing=" + missingSamples);

        List<SocietyResidents.Entry> whiterunRes = SocietyResidents.forSettlement("whiterun");
        boolean hasBalgruuf = whiterunRes.stream().anyMatch(e -> e.name().contains("Balgruuf"));
        boolean hasAdrianne = whiterunRes.stream().anyMatch(e -> e.name().contains("Adrianne"));
        boolean hasHulda = whiterunRes.stream().anyMatch(e -> e.name().contains("Hulda"));
        SocietyResidents.Entry huldaInnkeeper = SocietyResidents.innkeeperFor("whiterun");
        r.check("Whiterun authored residents include canonical Balgruuf, Adrianne, and innkeeper Hulda",
                hasBalgruuf && hasAdrianne && hasHulda && huldaInnkeeper != null && huldaInnkeeper.name().contains("Hulda"),
                "Balgruuf=" + hasBalgruuf + ", Adrianne=" + hasAdrianne + ", Hulda=" + (huldaInnkeeper != null ? huldaInnkeeper.name() : "null"));

        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (var entry : whiterunRes) claimed.add(entry.id());
        r.check("exhausted authored identities never wrap into a second housing slot",
                SocietyResidents.matchForSlot("whiterun", "farmer", NpcRole.FARMER, 999, claimed) == null,
                "all authored identities already claimed");
        var bard = whiterunRes.stream().filter(e -> e.role() == NpcRole.BARD).findFirst().orElse(null);
        if (bard != null) {
            claimed.remove(bard.id());
            r.check("a farmer slot never borrows an unrelated named bard",
                    SocietyResidents.matchForSlot("whiterun", "farmer", NpcRole.FARMER, 999, claimed) == null,
                    "only unclaimed identity is " + bard.name());
        }

        SocietyResidents.Entry sampleEntry = whiterunRes.stream().filter(e -> e.name().contains("Adrianne")).findFirst().orElse(whiterunRes.get(0));
        com.nirncraft.society.entity.NpcEntity sampleNpc = Npcs.create(level, new BlockPos(0, 80, 0), sampleEntry.npcRole());
        if (sampleNpc != null) {
            SocietyResidents.applyToNpc(sampleNpc, sampleEntry);
            String appliedId = sampleNpc.getPersistentData().getStringOr(SocietyResidents.RESIDENT_ID_KEY, "");
            String appliedWhy = sampleNpc.getPersistentData().getStringOr(Housing.WHY_KEY, "");
            String appliedGreet = sampleNpc.getPersistentData().getStringOr(SocietyResidents.GREET_KEY, "");
            r.check("applying authored resident sets custom name, NirnCraftResidentId, NirnCraftWhy, and NirnCraftGreet on NPC",
                    sampleEntry.id().equals(appliedId) && !appliedWhy.isBlank() && !appliedGreet.isBlank()
                            && sampleNpc.getName().getString().contains(sampleEntry.name()),
                    "id=" + appliedId + ", name=" + sampleNpc.getName().getString() + ", why=" + appliedWhy);
        }

        List<String> unknownVoiceRegions = new ArrayList<>();
        List<String> lowGossipRegions = new ArrayList<>();
        for (com.nirncraft.tamriel.Region reg : com.nirncraft.tamriel.Regions.all()) {
            String sayKey = RegionVoice.sayKey(reg);
            if (sayKey.endsWith(".unknown")) {
                unknownVoiceRegions.add(reg.id());
            }
            if (!"sea".equals(reg.province()) && RegionVoice.gossipCount(reg.shortId()) < 2) {
                lowGossipRegions.add(reg.shortId());
            }
        }
        r.check("RegionVoice resolves every region in Regions.all() without returning region.unknown and provides >= 2 regional gossip topics",
                unknownVoiceRegions.isEmpty() && lowGossipRegions.isEmpty(),
                "unknown=" + unknownVoiceRegions + ", lowGossip=" + lowGossipRegions);
    }

    /**
     * T6 self-test: verifies provincial factions and regional polity mappings, RaceAttitudes across contrasting
     * holds/provinces, Orc stronghold Blood-Kin gate, reputation override (trusted_outlander), live disposition
     * and barter price scaling, and political/faction-gated WildScenes.
     */
    private static void factionAttitudeAndEncounterChecks(Run r, ServerLevel level) {
        // 1. Factions & regional polities across all 75 land regions + WorldSheets causal citations
        List<String> missingPolity = new ArrayList<>();
        List<String> brokenWhy = new ArrayList<>();
        int landRegions = 0;
        for (com.nirncraft.tamriel.Region reg : com.nirncraft.tamriel.Regions.all()) {
            if ("sea".equals(reg.province())) continue;
            landRegions++;
            Reputation.PolityMapping pol = Reputation.regionPolity(reg.id());
            if (pol == null || pol.rulingFaction().isEmpty() || pol.why().isEmpty()) {
                missingPolity.add(reg.id());
            } else {
                for (String w : pol.why()) {
                    if (!com.nirncraft.tamriel.world.WorldSheets.resolves(w)) brokenWhy.add(reg.id() + ":" + w);
                }
            }
            RaceAttitudes.AttitudeProfile prof = RaceAttitudes.regionProfile(reg.id());
            if (prof == null || prof.why().isEmpty()) {
                missingPolity.add("attitude:" + reg.id());
            } else {
                for (String w : prof.why()) {
                    if (!com.nirncraft.tamriel.world.WorldSheets.resolves(w)) brokenWhy.add("attitude:" + reg.id() + ":" + w);
                }
            }
        }
        for (Reputation.FactionDef f : Reputation.allFactionDefs().values()) {
            if (f.why().isEmpty()) brokenWhy.add("faction:" + f.id());
            for (String w : f.why()) {
                if (!com.nirncraft.tamriel.world.WorldSheets.resolves(w)) brokenWhy.add("faction:" + f.id() + ":" + w);
            }
        }
        r.check("all " + landRegions + " land regions have causal faction polities and RaceAttitudes profiles with valid WorldSheets citations",
                landRegions >= 75 && missingPolity.isEmpty() && brokenWhy.isEmpty(),
                "landRegions=" + landRegions + ", factions=" + Reputation.allFactionDefs().size()
                        + ", missing=" + missingPolity + ", brokenWhy=" + brokenWhy);

        // 2. Contrasting Skyrim holds: Windhelm (Eastmarch) vs. Solitude (Haafingar)
        RaceAttitudes.Evaluation nordWindhelm = RaceAttitudes.evaluate("skyrim/eastmarch", "windhelm", "merchant", "nord", "nord", Map.of());
        RaceAttitudes.Evaluation dunmerWindhelm = RaceAttitudes.evaluate("skyrim/eastmarch", "windhelm", "merchant", "nord", "dunmer", Map.of());
        RaceAttitudes.Evaluation argonianWindhelm = RaceAttitudes.evaluate("skyrim/eastmarch", "windhelm", "guard", "nord", "argonian", Map.of());
        RaceAttitudes.Evaluation altmerWindhelm = RaceAttitudes.evaluate("skyrim/eastmarch", "windhelm", "guard", "nord", "altmer", Map.of());
        RaceAttitudes.Evaluation impWindhelm = RaceAttitudes.evaluate("skyrim/eastmarch", "windhelm", "guard", "nord", "imperial", Map.of());
        RaceAttitudes.Evaluation impSolitude = RaceAttitudes.evaluate("skyrim/haafingar", "solitude", "merchant", "imperial", "imperial", Map.of());
        r.check("Windhelm (Eastmarch) welcomes Nords and penalizes Dunmer, Argonians, Altmer, and Imperials compared to Solitude (Haafingar)",
                nordWindhelm.dispositionDelta() > 0 && nordWindhelm.priceMultiplier() < 1.0f
                        && dunmerWindhelm.dispositionDelta() < 0 && dunmerWindhelm.priceMultiplier() > 1.0f
                        && argonianWindhelm.dispositionDelta() <= -15 && argonianWindhelm.guardScrutiny() > 1.15f
                        && altmerWindhelm.dispositionDelta() <= -20 && altmerWindhelm.priceMultiplier() >= 1.15f
                        && impSolitude.dispositionDelta() > impWindhelm.dispositionDelta()
                        && impSolitude.priceMultiplier() < impWindhelm.priceMultiplier(),
                "nord=" + nordWindhelm.dispositionDelta() + ", dunmer=" + dunmerWindhelm.dispositionDelta()
                        + ", argonian=" + argonianWindhelm.dispositionDelta() + ", altmer=" + altmerWindhelm.dispositionDelta()
                        + ", impSolitude=" + impSolitude.dispositionDelta() + " vs impWindhelm=" + impWindhelm.dispositionDelta());

        // 3. Contrasting Morrowind, Hammerfell, Summerset, and Black Marsh attitudes
        RaceAttitudes.Evaluation dunmerTelvanni = RaceAttitudes.evaluate("morrowind/telvannis", "sadrith_mora", "mage", "dunmer", "dunmer", Map.of());
        RaceAttitudes.Evaluation argonianTelvanni = RaceAttitudes.evaluate("morrowind/telvannis", "sadrith_mora", "mage", "dunmer", "argonian", Map.of());
        RaceAttitudes.Evaluation impTelvanni = RaceAttitudes.evaluate("morrowind/telvannis", "sadrith_mora", "merchant", "dunmer", "imperial", Map.of());
        RaceAttitudes.Evaluation impBalmora = RaceAttitudes.evaluate("morrowind/west_gash", "balmora", "merchant", "dunmer", "imperial", Map.of());
        RaceAttitudes.Evaluation altmerAlikr = RaceAttitudes.evaluate("hammerfell/alikr_desert", null, "guard", "redguard", "altmer", Map.of());
        RaceAttitudes.Evaluation impAlikr = RaceAttitudes.evaluate("hammerfell/alikr_desert", null, "merchant", "redguard", "imperial", Map.of());
        RaceAttitudes.Evaluation impSentinel = RaceAttitudes.evaluate("hammerfell/sentinel", "sentinel", "merchant", "redguard", "imperial", Map.of());
        RaceAttitudes.Evaluation nordSummerset = RaceAttitudes.evaluate("summerset/summerset_isle", "alinor", "guard", "altmer", "nord", Map.of());
        RaceAttitudes.Evaluation dunmerHelstrom = RaceAttitudes.evaluate("black_marsh/helstrom", "helstrom", "guard", "argonian", "dunmer", Map.of());
        r.check("provincial attitudes reflect Telvanni vs Hlaalu Balmora, Crown Alik'r vs Forebear Sentinel, Thalmor Alinor, and An-Xileel Helstrom",
                dunmerTelvanni.dispositionDelta() > 0
                        && argonianTelvanni.dispositionDelta() <= -20
                        && impBalmora.dispositionDelta() > impTelvanni.dispositionDelta()
                        && altmerAlikr.dispositionDelta() <= -20
                        && impSentinel.dispositionDelta() > impAlikr.dispositionDelta()
                        && nordSummerset.dispositionDelta() <= -15 && nordSummerset.priceMultiplier() >= 1.15f
                        && dunmerHelstrom.dispositionDelta() <= -20 && RaceAttitudes.KEY_ANCESTRAL_GRUDGE.equals(dunmerHelstrom.attitudeKey()),
                "telvanni(dunmer=" + dunmerTelvanni.dispositionDelta() + ",arg=" + argonianTelvanni.dispositionDelta()
                        + "), balmora vs telvanni imp(" + impBalmora.dispositionDelta() + ">" + impTelvanni.dispositionDelta()
                        + "), alikr altmer=" + altmerAlikr.dispositionDelta() + ", helstrom dunmer=" + dunmerHelstrom.dispositionDelta());

        // 4. Orc stronghold Blood-Kin gate & reputation override (trusted_outlander)
        RaceAttitudes.Evaluation outsiderStronghold = RaceAttitudes.evaluate("skyrim/eastmarch", "narzulbur", "merchant", "orc", "nord", Map.of());
        RaceAttitudes.Evaluation orcStronghold = RaceAttitudes.evaluate("skyrim/eastmarch", "narzulbur", "merchant", "orc", "orc", Map.of());
        RaceAttitudes.Evaluation bloodKinStronghold = RaceAttitudes.evaluate("skyrim/eastmarch", "narzulbur", "merchant", "orc", "nord",
                Map.of(Reputation.ORC_STRONGHOLDS, 25));
        RaceAttitudes.Evaluation trustedDunmerWindhelm = RaceAttitudes.evaluate("skyrim/eastmarch", "windhelm", "merchant", "nord", "dunmer",
                Map.of(Reputation.STORMCLOAKS, 45));
        r.check("Orc strongholds enforce blood_kin_required for non-Orc outsiders and unlock for Orcs or Blood-Kin; high local faction rep grants trusted_outlander",
                RaceAttitudes.KEY_BLOOD_KIN_REQUIRED.equals(outsiderStronghold.attitudeKey())
                        && outsiderStronghold.dispositionDelta() <= -20
                        && orcStronghold.dispositionDelta() > 0
                        && RaceAttitudes.KEY_TRUSTED_OUTLANDER.equals(bloodKinStronghold.attitudeKey())
                        && bloodKinStronghold.dispositionDelta() > 0 && bloodKinStronghold.priceMultiplier() < 1.0f
                        && RaceAttitudes.KEY_TRUSTED_OUTLANDER.equals(trustedDunmerWindhelm.attitudeKey())
                        && trustedDunmerWindhelm.dispositionDelta() > 0 && trustedDunmerWindhelm.priceMultiplier() < 1.0f,
                "outsider=" + outsiderStronghold.attitudeKey() + ", orc=" + orcStronghold.attitudeKey()
                        + ", bloodKin=" + bloodKinStronghold.attitudeKey() + ", trustedDunmer=" + trustedDunmerWindhelm.attitudeKey());

        // 5. Live NPC integration with Relationships.disposition, Pricing.buyPrice/sellPrice, and ContextualDialogue
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("nirncraft-attitude-test".getBytes()), "[AttitudeTest]"));
        com.nirncraft.core.PlayerData pData = com.nirncraft.core.SkyData.get(p);
        Reputation.clear(p);
        com.nirncraft.society.entity.NpcEntity merch = Npcs.create(level, new BlockPos(0, 80, 0), NpcRole.MERCHANT);
        if (merch != null) {
            merch.setRace(NpcNames.Race.NORD);
            merch.getPersistentData().putString(RaceAttitudes.TAG_REGION_OVERRIDE, "skyrim/eastmarch");
            merch.getPersistentData().putString(RaceAttitudes.TAG_SETTLEMENT_OVERRIDE, "windhelm");
            net.minecraft.world.item.ItemStack sword = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);

            pData.setRace(com.nirncraft.core.Race.ALTMER);
            int dispAltmer = Relationships.disposition(merch, p);
            int personalAltmer = Relationships.personalDisposition(merch, p);
            int buyAltmer = com.nirncraft.economy.Pricing.buyPrice(p, sword, merch);
            int sellAltmer = com.nirncraft.economy.Pricing.sellPrice(p, sword, merch);

            pData.setRace(com.nirncraft.core.Race.NORD);
            int dispNord = Relationships.disposition(merch, p);
            int buyNord = com.nirncraft.economy.Pricing.buyPrice(p, sword, merch);
            int sellNord = com.nirncraft.economy.Pricing.sellPrice(p, sword, merch);

            merch.getPersistentData().putString(RaceAttitudes.TAG_SETTLEMENT_OVERRIDE, "narzulbur");
            merch.setRace(NpcNames.Race.ORC);
            String strongholdGreetKey = ContextualDialogue.attitudeGreetingKey(merch, p);

            r.check("live Relationships.disposition, Pricing.buyPrice/sellPrice, and ContextualDialogue reflect RaceAttitudes without mutating personalDisposition",
                    personalAltmer == 0 && dispAltmer < 0 && dispNord > 0
                            && buyAltmer > buyNord && sellAltmer <= sellNord
                            && "society.nirncraft.attitude.blood_kin_required.0".equals(strongholdGreetKey),
                    "disp(altmer=" + dispAltmer + ",nord=" + dispNord + "), buy(" + buyAltmer + " vs " + buyNord
                            + "), sell(" + sellAltmer + " vs " + sellNord + "), greet=" + strongholdGreetKey);
        }

        // 6. Dynamic political & reputation-filtered WildScenes
        List<com.nirncraft.creatures.spawn.WildScenes.Scene> allScenes = com.nirncraft.creatures.spawn.WildScenes.all();
        long stateScenes = allScenes.stream().filter(com.nirncraft.creatures.spawn.WildScenes.Scene::isPoliticalOrStateFiltered).count();
        List<String> sceneBadWhy = new ArrayList<>();
        for (com.nirncraft.creatures.spawn.WildScenes.Scene s : allScenes) {
            for (String w : s.why()) {
                if (!com.nirncraft.tamriel.world.WorldSheets.resolves(w)) sceneBadWhy.add(s.id() + ":" + w);
            }
        }
        var thalmorSquad = com.nirncraft.creatures.spawn.WildScenes.byId("t6_sk_thalmor_inquisition_hit_squad").orElse(null);
        var compHunt = com.nirncraft.creatures.spawn.WildScenes.byId("t6_sk_companions_dragon_hunt").orElse(null);
        boolean squadMatchesHated = thalmorSquad != null && com.nirncraft.creatures.spawn.WildScenes.matchesState(
                thalmorSquad, "skyrim/haafingar", "nord", java.util.Set.of(), f -> Reputation.THALMOR.equals(f) ? -30 : 0);
        boolean squadBlockedNeutral = thalmorSquad != null && !com.nirncraft.creatures.spawn.WildScenes.matchesState(
                thalmorSquad, "skyrim/haafingar", "nord", java.util.Set.of(), f -> 0);
        boolean compMatchesHero = compHunt != null && com.nirncraft.creatures.spawn.WildScenes.matchesState(
                compHunt, "skyrim/whiterun", "nord", java.util.Set.of("dragon_slayer"), f -> Reputation.COMPANIONS.equals(f) ? 25 : 0);
        boolean compBlockedUninitiated = compHunt != null && !com.nirncraft.creatures.spawn.WildScenes.matchesState(
                compHunt, "skyrim/whiterun", "nord", java.util.Set.of(), f -> 0);
        r.check("WildScenes loads >= 30 political/reputation-gated scenes with 100% valid WorldSheets why citations and enforces reputation/deed filters",
                stateScenes >= 30 && sceneBadWhy.isEmpty()
                        && squadMatchesHated && squadBlockedNeutral && compMatchesHero && compBlockedUninitiated,
                "totalScenes=" + allScenes.size() + ", stateScenes=" + stateScenes + ", badWhy=" + sceneBadWhy);
    }

    /**
     * T11 & T12 self-test: verifies 10 cultural musical instruments, 13-culture bard repertoires, bard equipment,
     * and all 94/94 LegendaryUniques resolving to non-empty ItemStacks.
     */
    private static void bardAndArsenalChecks(Run r, ServerLevel level) {
        List<String> bardErrors = new ArrayList<>();
        for (String culture : com.nirncraft.audio.BardPerformance.CULTURES) {
            net.minecraft.world.item.ItemStack inst = com.nirncraft.audio.BardPerformance.instrumentFor(culture);
            if (inst.isEmpty() || !(inst.getItem() instanceof com.nirncraft.audio.InstrumentItem)) {
                bardErrors.add("bard instrument missing for culture " + culture);
            }
            List<String> songs = com.nirncraft.audio.BardPerformance.songsFor(culture);
            if (songs.size() < 4) {
                bardErrors.add("culture " + culture + " has fewer than 4 bard songs: " + songs.size());
            }
        }
        int instCount = com.nirncraft.audio.BardPerformance.allInstruments().size();
        if (instCount != 10) {
            bardErrors.add("expected 10 cultural instruments, got " + instCount);
        }
        com.nirncraft.society.entity.NpcEntity bard = Npcs.create(level, new BlockPos(0, 80, 0), NpcRole.BARD);
        boolean bardEquipped = bard != null && bard.getMainHandItem().getItem() instanceof com.nirncraft.audio.InstrumentItem;
        r.check("all 10 cultural instruments and 13-culture bard song repertoires (>= 4 songs/culture) load and equip on BARD NPCs",
                bardErrors.isEmpty() && bardEquipped,
                bardErrors.isEmpty() ? "instruments=" + instCount + ", bardHeld=" + (bard != null ? bard.getMainHandItem().getItem() : "null") : "errors=" + bardErrors);

        int uniqueCount = com.nirncraft.loot.LegendaryUniques.verifyAllNonEmpty();
        r.check("all " + uniqueCount + " LegendaryUniques resolve to non-empty ItemStacks (0 missing regional weapons)",
                uniqueCount >= 94, "verified=" + uniqueCount);
    }
}

