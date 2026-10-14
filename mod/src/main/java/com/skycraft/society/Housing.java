package com.skycraft.society;

import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The housing model of a settlement: how many people can live there, who they are and where they work. Pure function
 * of the settlement's recorded anchors (no level, no randomness: the same anchors always give the same plan, so a
 * reload never spawns anyone twice and the offline QA dump predicts the real population exactly).
 *
 * <ul>
 *     <li><b>Beds</b> are grouped by the building that owns them: palace/keep beds ({@link BedKind#COURT}), barracks
 *     beds, inn staff quarters (a BED owned by the inn), temple cells and ordinary homes. Inn rooms
 *     ({@code INN_BED}) and the dormitory ({@code GUEST_BED}) belong to travellers and are never residents' beds
 *     (the innkeeper may take a dormitory bed when the inn has no quarters, two always stay free); a member's bed of
 *     a guild hall ({@code GUILD_BED}) houses that hall's staff.</li>
 *     <li><b>Jobs</b> are one per workplace anchor (forge, counter, field, post...), capped per settlement tier.
 *     A job is only filled when a bed is free for its holder, so residents never outnumber beds. Guards without a
 *     barracks bed keep the watch on their post (at most {@link #POST_SLEEPERS}), prisoners sit in their cells.</li>
 *     <li><b>Households</b> fill what is left: about three of four free home beds hold someone, either a child, an
 *     elder keeping house or an adult lending a hand at the nearest field/mill/camp; a place with no
 *     workplace at all keeps house instead (no ghost jobs).</li>
 * </ul>
 */
public final class Housing {
    public enum BedKind { HOME, COURT, BARRACKS, INN_STAFF, TEMPLE, GUILD, NONE }

    /**
     * Upper bounds per settlement tier (0 capital/city, 1 town, 2 village): guards (only Helgen's scripted garrison
     * uses them now, see {@link #guardSlots}), merchants, ambient crowd.
     */
    public static final int[] GUARDS = {12, 4, 2}, MERCHANTS = {8, 3, 2}, AMBIENT = {26, 15, 8};
    /** Guards with no barracks bed who keep the night watch at their post. */
    public static final int POST_SLEEPERS = 2;
    /** Most household residents assigned to one meeting point for their free hours. */
    public static final int MAX_AT_PLAZA = 8;
    /** Extra pairs of hands one workplace takes from the households. */
    private static final int HANDS = 2;

    /** Persistent-data keys on a spawned resident: job id, workplace anchor position (packed). */
    public static final String JOB_KEY = "skycraft_job", WORK_KEY = "skycraft_work";
    /** Persistent-data key: {@code "<settlement>#<anchor index>"}, the housing-plan slot this person fills (one each). */
    public static final String SLOT_KEY = "skycraft_slot";

    private Housing() {}

    /**
     * One person of the settlement.
     *
     * @param job     job id ({@code innkeeper}, {@code smith}, {@code farmhand}, {@code child}, {@code housekeeper}...)
     * @param role    NPC role to spawn, null for jobs that have a dedicated spawner (innkeeper, guards, guild staff...)
     * @param slot    index of the anchor that spawns this person (the anchor's spawned flag is the persistence)
     * @param work    index of the workplace anchor, -1 for household tasks
     * @param bed     index of the bed anchor, -1 for post sleepers and prisoners
     * @param leisure index of the MEETING anchor they spend free hours at, -1 if the settlement has none
     */
    public record Resident(String job, @Nullable NpcRole role, AnchorType slotType, int slot, int work, int bed, BedKind bedKind, int leisure) {
        public boolean household() {
            return work < 0;
        }
    }

    public static final class Plan {
        public final Map<Integer, Resident> bySlot = new LinkedHashMap<>();
        public final EnumMap<BedKind, Integer> beds = new EnumMap<>(BedKind.class);
        public int guestBeds, innRooms, posts, meetings;
        /** Jobs that have a workplace but nobody to hold them because no bed was left. */
        public final List<String> unstaffed = new ArrayList<>();
        /** Workplace anchors by type (everything a job could be tied to). */
        public final Map<AnchorType, Integer> workplaces = new TreeMap<>();
        /** Household residents per MEETING anchor index (the idle clusters). */
        public final Map<Integer, Integer> idleAt = new TreeMap<>();
        public int householdIdle;

        /** Beds that can hold a resident (not rooms for travellers, not player houses). */
        public int capacity() {
            int n = 0;
            for (BedKind k : BedKind.values()) n += beds.getOrDefault(k, 0);
            return n;
        }

        /** Residents that sleep in a bed. */
        public int residents() {
            int n = 0;
            for (Resident r : bySlot.values()) if (r.bed() >= 0) n++;
            return n;
        }

        /** Guards keeping the watch on their post. */
        public int postSleepers() {
            int n = 0;
            for (Resident r : bySlot.values()) if (r.bed() < 0 && r.slotType() == AnchorType.GUARD) n++;
            return n;
        }

        /** Prisoners in their cells. */
        public int inmates() {
            int n = 0;
            for (Resident r : bySlot.values()) if (r.slotType() == AnchorType.JAIL_PRISONER) n++;
            return n;
        }

        @Nullable
        public Resident at(int slot) {
            return bySlot.get(slot);
        }
    }

    // ------------------------------------------------------------------ public API

    /** Resident capacity (beds that can hold a townsperson) of a recorded settlement. */
    public static int capacity(SettlementAnchors.Entry e) {
        return plan(e.key, e.anchors(), 2).capacity();
    }

    public static Plan plan(SettlementAnchors.Entry e, int tier) {
        return plan(e.key, e.anchors(), tier);
    }

    /** The plan for a settlement's anchors; {@code tier} 0 capital/city, 1 town, 2 village. */
    public static Plan plan(String key, List<Anchor> anchors, int tier) {
        tier = Math.max(0, Math.min(2, tier));
        Plan plan = new Plan();
        boolean capital = tier == 0;
        int n = anchors.size();

        // ---- classify buildings by what their anchors say they are
        Map<String, Set<AnchorType>> ownerTypes = new HashMap<>();
        for (Anchor a : anchors) ownerTypes.computeIfAbsent(a.owner(), k -> java.util.EnumSet.noneOf(AnchorType.class)).add(a.type());
        Map<String, BedKind> ownerKind = new HashMap<>();
        for (var en : ownerTypes.entrySet()) ownerKind.put(en.getKey(), kindOf(en.getValue()));

        // ---- beds, in anchor order; pools by kind
        Map<BedKind, List<Integer>> pool = new EnumMap<>(BedKind.class);
        for (BedKind k : BedKind.values()) pool.put(k, new ArrayList<>());
        List<Integer> guest = new ArrayList<>();
        List<Integer> meetings = new ArrayList<>();
        Map<String, List<Integer>> guildBeds = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Anchor a = anchors.get(i);
            switch (a.type()) {
                case BED -> pool.get(ownerKind.getOrDefault(a.owner(), BedKind.HOME)).add(i);
                case GUILD_BED -> {
                    pool.get(BedKind.GUILD).add(i);
                    guildBeds.computeIfAbsent(a.owner(), k -> new ArrayList<>()).add(i);
                }
                case GUEST_BED -> guest.add(i);
                case INN_BED -> plan.innRooms++;
                case MEETING -> meetings.add(i);
                case GUARD -> plan.posts++;
                default -> {
                }
            }
            if (JOB_SET.contains(a.type())) plan.workplaces.merge(a.type(), 1, Integer::sum);
        }
        for (BedKind k : BedKind.values()) plan.beds.put(k, pool.get(k).size());
        plan.guestBeds = guest.size();
        plan.meetings = meetings.size();
        Set<Integer> used = new HashSet<>();
        // the dormitory: the innkeeper may take a bed from it, but two stay free for travellers
        int dormSpare = Math.max(0, guest.size() - 2);

        // ---- jobs, most important first
        Map<AnchorType, Integer> count = new EnumMap<>(AnchorType.class);
        for (AnchorType type : JOB_ORDER) {
            int cap = type == AnchorType.GUARD ? guardSlots(key, tier) : cap(type, tier, capital);
            if (cap <= 0) continue;
            for (int i = 0; i < n && count.getOrDefault(type, 0) < cap; i++) {
                Anchor a = anchors.get(i);
                if (a.type() != type) continue;
                String job = jobId(type);
                NpcRole role = roleOf(type);
                if (type == AnchorType.JAIL_PRISONER) {
                    put(plan, new Resident(job, role, type, i, i, -1, BedKind.NONE, -1));
                    count.merge(type, 1, Integer::sum);
                    continue;
                }
                if (type == AnchorType.GUARD) {
                    boolean barracks = free(pool.get(BedKind.BARRACKS), used) > 0;
                    if (!barracks && plan.postSleepers() >= POST_SLEEPERS) continue;
                    int bed = takeNearest(pool.get(BedKind.BARRACKS), used, anchors, a, null);
                    put(plan, new Resident(job, role, type, i, i, bed, bed < 0 ? BedKind.NONE : BedKind.BARRACKS, -1));
                    count.merge(type, 1, Integer::sum);
                    continue;
                }
                int bed = -1;
                BedKind bk = BedKind.HOME;
                switch (type) {
                    case JARL, HOUSECARL, STEWARD, WIZARD -> {
                        bed = takeNearest(pool.get(BedKind.COURT), used, anchors, a, null);
                        bk = BedKind.COURT;
                    }
                    case INN -> {
                        bed = takeNearest(pool.get(BedKind.INN_STAFF), used, anchors, a, a.owner());
                        bk = BedKind.INN_STAFF;
                        if (bed < 0 && dormSpare > 0) {
                            bed = takeNearest(guest, used, anchors, a, null);
                            if (bed >= 0) {
                                dormSpare--;
                                plan.beds.merge(BedKind.INN_STAFF, 1, Integer::sum); // the dorm bed now counts as quarters
                            }
                        }
                    }
                    case PRIEST -> {
                        bed = takeNearest(pool.get(BedKind.TEMPLE), used, anchors, a, null);
                        bk = BedKind.TEMPLE;
                    }
                    case GUILD_LEADER, GUILD_TRAINER, GUILD_VENDOR -> {
                        bed = takeNearest(guildBeds.getOrDefault(a.owner(), List.of()), used, anchors, a, null);
                        bk = BedKind.GUILD;
                    }
                    default -> {
                    }
                }
                if (bed < 0) { // a bed in the workplace's own building, else the nearest free home
                    bed = takeNearest(pool.get(BedKind.HOME), used, anchors, a, a.owner());
                    if (bed < 0) bed = takeNearest(pool.get(BedKind.HOME), used, anchors, a, null);
                    bk = BedKind.HOME;
                }
                if (bed < 0) {
                    plan.unstaffed.add(job);
                    continue;
                }
                if (anchors.get(bed).type() == AnchorType.GUEST_BED) bk = BedKind.INN_STAFF;
                put(plan, new Resident(job, role, type, i, i, bed, bk, -1));
                count.merge(type, 1, Integer::sum);
                if (type == AnchorType.INN) staffQuarters(plan, anchors, pool, used, i, a);
            }
        }

        // ---- households: what is left of the home beds
        List<Integer> free = new ArrayList<>();
        for (int b : pool.get(BedKind.HOME)) if (!used.contains(b)) free.add(b);
        int ambientJobs = 0;
        for (Resident r : plan.bySlot.values()) if (ambient(r.slotType())) ambientJobs++;
        int fill = Math.min(free.size() * 3 / 4, Math.max(0, AMBIENT[tier] - ambientJobs));
        Map<Integer, Integer> hands = new HashMap<>();
        Map<String, Integer> perHome = new HashMap<>();
        int children = 0;
        int taken = 0;
        for (int k = 0; k < free.size() && taken < fill; k++) {
            if (k % 4 == 3) continue; // every fourth bed stays empty: a visiting relative, the one who moved away
            int bed = free.get(k);
            Anchor ba = anchors.get(bed);
            int inHome = perHome.merge(ba.owner(), 1, Integer::sum);
            int h = hash(key, bed);
            String job;
            NpcRole role;
            int work = -1;
            if (inHome >= 2 && h % 3 != 0 && children * 4 < free.size()) {
                job = "child";
                role = NpcRole.CHILD;
                children++;
            } else if (h % 7 == 0) {
                job = "elder";
                role = NpcRole.CITIZEN;
            } else {
                int w = nearestHandSite(anchors, ba, hands);
                if (w >= 0) {
                    hands.merge(w, 1, Integer::sum);
                    work = w;
                    job = handJob(anchors.get(w).type());
                    role = handRole(anchors.get(w).type());
                } else {
                    job = "housekeeper";
                    role = NpcRole.CITIZEN;
                }
            }
            int leisure = -1;
            if (!meetings.isEmpty() && work < 0) {
                // a plaza holds at most MAX_AT_PLAZA people at once: the rest keep to their chores indoors
                int m = nearestMeeting(anchors, meetings, ba, plan.idleAt);
                if (plan.idleAt.getOrDefault(m, 0) < MAX_AT_PLAZA) {
                    leisure = m;
                    plan.idleAt.merge(m, 1, Integer::sum);
                    plan.householdIdle++;
                }
            }
            used.add(bed);
            put(plan, new Resident(job, role, AnchorType.BED, bed, work, bed, BedKind.HOME, leisure));
            taken++;
        }
        return plan;
    }

    /** The inn's cook and server: staff-quarters beds (owned by the inn) each house one more worker behind the bar. */
    private static void staffQuarters(Plan plan, List<Anchor> anchors, Map<BedKind, List<Integer>> pool, Set<Integer> used, int innIdx, Anchor inn) {
        int served = 0;
        for (int b : pool.get(BedKind.INN_STAFF)) {
            if (served >= 2) break;
            if (used.contains(b) || !anchors.get(b).owner().equals(inn.owner())) continue;
            used.add(b);
            plan.bySlot.put(b, new Resident(served == 0 ? "cook" : "server", NpcRole.CITIZEN, AnchorType.BED, b, innIdx, b, BedKind.INN_STAFF, -1));
            served++;
        }
    }

    // ------------------------------------------------------------------ pieces

    /** Anchor types that have a person at them, in the order beds are handed out. */
    private static final AnchorType[] JOB_ORDER = {
            AnchorType.JARL, AnchorType.HOUSECARL, AnchorType.STEWARD, AnchorType.WIZARD, AnchorType.INN, AnchorType.PRIEST,
            AnchorType.SMITH, AnchorType.MERCHANT, AnchorType.ALCHEMIST, AnchorType.GUARD, AnchorType.GUILD_LEADER,
            AnchorType.GUILD_VENDOR, AnchorType.GUILD_TRAINER, AnchorType.JEWELER, AnchorType.BOOKSELLER, AnchorType.TAILOR,
            AnchorType.BUTCHER, AnchorType.FLETCHER, AnchorType.FENCE, AnchorType.FARMER, AnchorType.MILLER, AnchorType.FISHER,
            AnchorType.HUNTER, AnchorType.MINER, AnchorType.LUMBERJACK, AnchorType.WORKER, AnchorType.BARD,
            AnchorType.JAIL_PRISONER};
    private static final Set<AnchorType> JOB_SET = Set.of(JOB_ORDER);

    /**
     * Residents a settlement's guard posts hold. None: the watch is not a household ({@link GuardWatch}: two posted
     * guards at a major settlement's gate, one patrol in a minor one, kept by the watch, not by barracks and anchors).
     * The scripted garrison of Helgen is the one exception.
     */
    static int guardSlots(String key, int tier) {
        return "helgen".equals(key) ? GUARDS[Math.max(0, Math.min(2, tier))] : 0;
    }

    static int cap(AnchorType t, int tier, boolean capital) {
        return switch (t) {
            case JARL, HOUSECARL -> capital ? 1 : 0;
            case STEWARD, WIZARD -> 1;
            case GUARD -> GUARDS[tier];
            case MERCHANT -> MERCHANTS[tier];
            case INN, PRIEST, SMITH, ALCHEMIST, FARMER, MILLER, MINER, LUMBERJACK -> 3;
            case BARD -> tier <= 1 ? 3 : 1;
            case GUILD_LEADER, GUILD_VENDOR -> 12;
            case GUILD_TRAINER -> 24;
            case JAIL_PRISONER -> 4;
            case JEWELER, BOOKSELLER, TAILOR, BUTCHER, FLETCHER, FISHER, HUNTER, WORKER -> 3;
            case FENCE -> 1;
            default -> 0;
        };
    }

    private static boolean ambient(AnchorType t) {
        return switch (t) {
            case WORKER, MINER, LUMBERJACK, MILLER, FARMER, HUNTER, FISHER, BARD, FLETCHER, TAILOR, BUTCHER, BED -> true;
            default -> false;
        };
    }

    static String jobId(AnchorType t) {
        return switch (t) {
            case INN -> "innkeeper";
            case SMITH -> "smith";
            case MERCHANT -> "merchant";
            case WIZARD -> "court_wizard";
            case GUILD_LEADER -> "guild_leader";
            case GUILD_TRAINER -> "guild_trainer";
            case GUILD_VENDOR -> "guild_vendor";
            case JAIL_PRISONER -> "prisoner";
            case WORKER -> "labourer";
            default -> t.id();
        };
    }

    @Nullable
    static NpcRole roleOf(AnchorType t) {
        return switch (t) {
            case JARL -> NpcRole.JARL;
            case HOUSECARL -> NpcRole.HOUSECARL;
            case BARD -> NpcRole.BARD;
            case PRIEST -> NpcRole.PRIEST;
            case MILLER, LUMBERJACK -> NpcRole.LUMBERJACK;
            case MINER -> NpcRole.MINER;
            case JAIL_PRISONER -> NpcRole.BEGGAR;
            default -> null;
        };
    }

    private static String handJob(AnchorType t) {
        return switch (t) {
            case FARMER -> "farmhand";
            case MILLER -> "mill_hand";
            case LUMBERJACK -> "woodcutter";
            case MINER -> "miner";
            case FISHER -> "fisher";
            case HUNTER -> "hunter";
            default -> "labourer";
        };
    }

    private static NpcRole handRole(AnchorType t) {
        return switch (t) {
            case FARMER -> NpcRole.FARMER;
            case MINER -> NpcRole.MINER;
            case FISHER -> NpcRole.FISHER;
            case HUNTER -> NpcRole.HUNTER;
            default -> NpcRole.LUMBERJACK;
        };
    }

    private static BedKind kindOf(Set<AnchorType> types) {
        if (types.contains(AnchorType.JARL) || types.contains(AnchorType.HOUSECARL) || types.contains(AnchorType.STEWARD)
                || types.contains(AnchorType.WIZARD)) return BedKind.COURT;
        if (types.contains(AnchorType.INN) || types.contains(AnchorType.INN_BED) || types.contains(AnchorType.GUEST_BED)) return BedKind.INN_STAFF;
        if (types.contains(AnchorType.GUARD)) return BedKind.BARRACKS;
        if (types.contains(AnchorType.PRIEST)) return BedKind.TEMPLE;
        return BedKind.HOME;
    }

    private static void put(Plan plan, Resident r) {
        plan.bySlot.put(r.slot(), r);
    }

    private static int free(List<Integer> pool, Set<Integer> used) {
        int n = 0;
        for (int b : pool) if (!used.contains(b)) n++;
        return n;
    }

    /** Takes the free bed of {@code pool} nearest to {@code from} (restricted to one owner when given); -1 if none. */
    private static int takeNearest(Collection<Integer> pool, Set<Integer> used, List<Anchor> anchors, Anchor from, @Nullable String owner) {
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int b : pool) {
            if (used.contains(b)) continue;
            Anchor a = anchors.get(b);
            if (owner != null && !owner.equals(a.owner())) continue;
            long d = (long) a.pos().distSqr(from.pos());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        if (best >= 0) used.add(best);
        return best;
    }

    /** Nearest workplace (field, mill, camp...) within 90 blocks that still has room for a pair of hands; -1 if none. */
    private static int nearestHandSite(List<Anchor> anchors, Anchor bed, Map<Integer, Integer> hands) {
        int best = -1;
        long bestD = 90L * 90L;
        for (int i = 0; i < anchors.size(); i++) {
            AnchorType t = anchors.get(i).type();
            if (t != AnchorType.FARMER && t != AnchorType.MILLER && t != AnchorType.LUMBERJACK && t != AnchorType.MINER
                    && t != AnchorType.WORKER && t != AnchorType.FISHER && t != AnchorType.HUNTER) continue;
            if (hands.getOrDefault(i, 0) >= HANDS) continue;
            long d = (long) anchors.get(i).pos().distSqr(bed.pos());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** The meeting point near the home with the fewest idlers (so people spread over the plazas). */
    private static int nearestMeeting(List<Anchor> anchors, List<Integer> meetings, Anchor bed, Map<Integer, Integer> load) {
        int best = meetings.get(0);
        double bestScore = Double.MAX_VALUE;
        for (int m : meetings) {
            double score = Math.sqrt(anchors.get(m).pos().distSqr(bed.pos())) + load.getOrDefault(m, 0) * 12.0;
            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    static int hash(String key, int i) {
        return (key.hashCode() * 31 + i * 0x9E3779B1) >>> 1;
    }

    // ------------------------------------------------------------------ the old model (for before/after reports)

    /** What the pre-housing population code would have spawned: a person per anchor up to fixed caps, a third of the beds. */
    public static int legacyCount(List<Anchor> anchors, int tier) {
        tier = Math.max(0, Math.min(2, tier));
        boolean capital = tier == 0;
        Map<AnchorType, Integer> have = new EnumMap<>(AnchorType.class);
        for (Anchor a : anchors) have.merge(a.type(), 1, Integer::sum);
        int total = 0, amb = 0;
        for (var en : have.entrySet()) {
            AnchorType t = en.getKey();
            int cap = switch (t) {
                case JARL, STEWARD, WIZARD, HOUSECARL -> 1;
                case GUARD -> GUARDS[tier];
                case MERCHANT -> MERCHANTS[tier];
                case BED -> new int[]{14, 7, 4}[tier];
                case INN, BARD, SMITH, ALCHEMIST, PRIEST, FARMER, MILLER, MINER, LUMBERJACK -> 3;
                case GUILD_LEADER, GUILD_VENDOR -> 12;
                case GUILD_TRAINER -> 24;
                case JAIL_PRISONER -> 4;
                case JEWELER, BOOKSELLER, TAILOR, BUTCHER, FLETCHER, FISHER, HUNTER, WORKER -> 3;
                case FENCE -> 1;
                default -> 0;
            };
            int n = Math.min(cap, en.getValue());
            if ((t == AnchorType.JARL || t == AnchorType.HOUSECARL) && !capital) n = 0;
            if (t == AnchorType.BED) n = (int) Math.round(n / 3.0);
            if (ambient(t)) amb += n;
            else total += n;
        }
        return total + Math.min(amb, AMBIENT[tier]);
    }
}
