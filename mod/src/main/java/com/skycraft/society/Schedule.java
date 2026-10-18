package com.skycraft.society;

import com.skycraft.atmosphere.weather.RegionalWeather;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.world.level.Level;

/**
 * The daily routines of townsfolk, Radiant-AI style: sleep, breakfast, work, lunch at the inn or at home, an
 * afternoon of work, an evening stroll and chat, the inn while the bard plays, then bed. Each trade keeps its own
 * hours (shopkeepers follow the economy's shop hours, innkeepers keep the inn open late, bards play into the night,
 * children play all day). Pure function of time, role, and severe regional weather.
 */
public final class Schedule {
    public enum Activity {
        IDLE, SLEEP, EAT, WORK, LUNCH, WANDER, INN, HOME, PLAY;

        /** Alias for {@link #HOME} used by shelter checks. */
        public static final Activity REST = HOME;
        /** Alias for {@link #INN} used by shelter checks. */
        public static final Activity SOCIAL = INN;

        /** Activities during which a townsperson may stop for a chat. */
        public boolean social() {
            return this == WANDER || this == INN || this == LUNCH || this == PLAY;
        }

        /** True when the activity takes place inside a home, inn, or hall rather than exposed outdoors. */
        public boolean isIndoors() {
            return this == SLEEP || this == EAT || this == LUNCH || this == INN || this == HOME;
        }
    }

    private Schedule() {}

    /** Skyrim clock hour (0-23) for the level's time of day; day time 0 is 6 am. */
    public static int hour(Level level) {
        long t = Math.floorMod(level.getDefaultClockTime(), 24000L);
        return (int) ((t / 1000L + 6) % 24);
    }

    /** What {@code npc} should be doing now, factoring in local {@link RegionalWeather}. */
    public static Activity activity(NpcEntity npc) {
        Level level = npc.level();
        WeatherKind weather = RegionalWeather.at(level, npc.blockPosition());
        return activityAt(npc, weather);
    }

    /** Alias for {@link #activity(NpcEntity)}. */
    public static Activity activityAt(NpcEntity npc) {
        return activity(npc);
    }

    /** What {@code npc} should be doing now under the given {@code weather}. */
    public static Activity activityAt(NpcEntity npc, WeatherKind weather) {
        Level level = npc.level();
        int h = hour(level);
        long day = level.getDefaultClockTime() / 24000L;
        // a stable per-person habit that changes day to day: some take lunch at the inn, some at home
        boolean social = ((npc.getUUID().getLeastSignificantBits() >>> 3) + day) % 3 != 0;
        NpcRole role = npc.role();
        if (Marriage.isSpouse(npc)) return spouse(h);
        // settlement residents with a housing-model job (inn staff, housekeepers, elders) keep that job's hours
        String job = npc.getPersistentData().getStringOr(Housing.JOB_KEY, "");
        if (role == NpcRole.CITIZEN && !job.isEmpty()) {
            if (job.equals("cook") || job.equals("server")) return h >= 1 && h < 8 ? Activity.SLEEP : Activity.WORK;
            return shelterIfSevere(role, household(h, social), social, weather);
        }
        return activityAt(role, h, social, weather);
    }

    /** Pure schedule lookup for a {@code role} and clock hour {@code h} under {@code weather}. */
    public static Activity activityAt(NpcRole role, int h, WeatherKind weather) {
        return activityAt(role, h, true, weather);
    }

    /** Pure schedule lookup for a {@code role}, clock hour {@code h}, social preference, and {@code weather}. */
    public static Activity activityAt(NpcRole role, int h, boolean social, WeatherKind weather) {
        Activity base = baseActivity(role, h, social);
        return shelterIfSevere(role, base, social, weather);
    }

    private static Activity baseActivity(NpcRole role, int h, boolean social) {
        return switch (role) {
            case INNKEEPER -> Activity.WORK;
            case BARD -> h >= 2 && h < 10 ? Activity.SLEEP : h < 12 ? Activity.WANDER : h < 14 ? Activity.WORK
                    : h < 17 ? Activity.WANDER : Activity.WORK;
            case BEGGAR -> h >= 23 || h < 6 ? Activity.SLEEP : h == 12 ? Activity.INN : Activity.WANDER;
            case CHILD -> h >= 21 || h < 7 ? Activity.SLEEP : h < 8 ? Activity.EAT : h < 12 ? Activity.PLAY
                    : h < 13 ? Activity.EAT : h < 19 ? Activity.PLAY : Activity.HOME;
            case PRIEST, JARL, STEWARD, MAGE, HOUSECARL -> h >= 22 || h < 6 ? Activity.SLEEP : h < 8 ? Activity.EAT : h < 20 ? Activity.WORK
                    : Activity.HOME;
            case TRAINER -> trainer(h, social);
            case MERCHANT, BLACKSMITH, APOTHECARY, SCHOLAR, BUTCHER -> shopkeeper(h, social);
            case FARMER, MINER, LUMBERJACK, HUNTER, FISHER -> labourer(h, social);
            case CITIZEN -> h >= 22 || h < 6 ? Activity.SLEEP : h < 8 ? Activity.EAT : h < 12 ? Activity.WANDER
                    : h < 13 ? (social ? Activity.LUNCH : Activity.EAT) : h < 18 ? Activity.WANDER
                    : h < 22 ? (social ? Activity.INN : Activity.HOME) : Activity.SLEEP;
            default -> h >= 22 || h < 6 ? Activity.SLEEP : h >= 19 ? Activity.INN : Activity.WANDER;
        };
    }

    /**
     * Routes non-guard outdoor daytime workers, strollers, and children indoors ({@link Activity#INN} or
     * {@link Activity#HOME}) when a severe storm ({@code BLIZZARD}, {@code SANDSTORM}, {@code ASH_STORM}, {@code BLIGHT})
     * hits the settlement.
     */
    private static Activity shelterIfSevere(NpcRole role, Activity base, boolean social, WeatherKind weather) {
        if (!RegionalWeather.isSevere(weather)) return base;
        if (role != null && (role.protector || role == NpcRole.HOUSECARL)) return base;
        if (base == Activity.WANDER || base == Activity.PLAY) {
            if (role == NpcRole.CHILD) return Activity.HOME;
            return social ? Activity.INN : Activity.HOME;
        }
        if (base == Activity.WORK && isOutdoorWorker(role)) {
            return social ? Activity.INN : Activity.HOME;
        }
        return base;
    }

    private static boolean isOutdoorWorker(NpcRole role) {
        if (role == null) return false;
        return switch (role) {
            case FARMER, MINER, LUMBERJACK, HUNTER, FISHER, TRAINER, BEGGAR -> true;
            default -> false;
        };
    }

    /** Trainers instruct during the day, take meals at morning/noon/evening, and sleep at night. */
    private static Activity trainer(int h, boolean social) {
        if (h >= 22 || h < 6) return Activity.SLEEP;
        if (h < 8 || h == 12 || h == 19) return Activity.EAT;
        if (h < 19) return Activity.WORK;
        return social ? Activity.INN : Activity.HOME;
    }

    /** Shops are open 8:00-20:00 (economy contract 10); lunch is eaten at the counter. */
    private static Activity shopkeeper(int h, boolean social) {
        if (h >= 22 || h < 6) return Activity.SLEEP;
        if (h < 8) return Activity.EAT;
        if (h < 20) return Activity.WORK;
        return social ? Activity.INN : Activity.HOME;
    }

    private static Activity labourer(int h, boolean social) {
        if (h >= 22 || h < 5) return Activity.SLEEP;
        if (h < 6) return Activity.EAT;
        if (h < 12) return Activity.WORK;
        if (h < 13) return social ? Activity.LUNCH : Activity.EAT;
        if (h < 18) return Activity.WORK;
        if (h < 20) return Activity.WANDER;
        return social ? Activity.INN : Activity.HOME;
    }

    /** Housekeeping adults and elders: chores indoors by day, the plaza or the inn in the evening. */
    private static Activity household(int h, boolean social) {
        if (h >= 22 || h < 6) return Activity.SLEEP;
        if (h < 8) return Activity.EAT;
        if (h < 12) return Activity.WORK;
        if (h < 13) return social ? Activity.LUNCH : Activity.EAT;
        if (h < 17) return Activity.WORK;
        if (h < 19) return Activity.WANDER;
        return social ? Activity.INN : Activity.HOME;
    }

    /** A spouse keeps house: works (tends the home or its shop) by day, cooks in the evening. */
    private static Activity spouse(int h) {
        if (h >= 22 || h < 6) return Activity.SLEEP;
        if (h < 8) return Activity.EAT;
        if (h < 18) return Activity.WORK;
        return Activity.HOME;
    }

    /** Whether a shopkeeper's counter is staffed right now (for merchants that check). */
    public static boolean atWork(NpcEntity npc) {
        return npc.activity == Activity.WORK;
    }
}
