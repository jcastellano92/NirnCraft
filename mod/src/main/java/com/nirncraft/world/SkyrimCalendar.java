package com.nirncraft.world;

import net.minecraft.network.chat.Component;

/**
 * Tamrielic calendar for the day time: day 0 is Sundas, 17th of Last Seed, 4E 201 (the day Skyrim begins) and
 * Minecraft tick 0 is 6:00 AM.
 */
public final class SkyrimCalendar {
    private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final int START_MONTH = 7; // Last Seed
    private static final int START_DAY = 17;
    private static final int START_YEAR = 201;

    private SkyrimCalendar() {}

    /** Hour of day 0-23. */
    public static int hour(long dayTime) {
        return (int) (Math.floorMod(dayTime + 6000L, 24000L) / 1000L);
    }

    public static int minute(long dayTime) {
        return (int) (Math.floorMod(dayTime + 6000L, 1000L) * 60L / 1000L);
    }

    public static long dayIndex(long dayTime) {
        return Math.floorDiv(dayTime + 6000L, 24000L);
    }

    /** "8:30 PM" */
    public static Component time(long dayTime) {
        int h = hour(dayTime);
        int m = minute(dayTime);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return Component.translatable(h < 12 ? "world.nirncraft.time.am" : "world.nirncraft.time.pm",
                h12 + ":" + (m < 10 ? "0" + m : String.valueOf(m)));
    }

    /** "Sundas, 17th of Last Seed, 4E 201" */
    public static Component date(long dayTime) {
        long days = dayIndex(dayTime);
        int weekday = (int) Math.floorMod(days, 7L);
        int startDoy = START_DAY - 1;
        for (int i = 0; i < START_MONTH; i++) startDoy += MONTH_DAYS[i];
        long abs = startDoy + days;
        long year = START_YEAR + Math.floorDiv(abs, 365L);
        long day = Math.floorMod(abs, 365L);
        int month = 0;
        while (month < 11 && day >= MONTH_DAYS[month]) {
            day -= MONTH_DAYS[month];
            month++;
        }
        int dom = (int) day + 1;
        return Component.translatable("world.nirncraft.calendar",
                Component.translatable("world.nirncraft.day." + weekday),
                ordinal(dom),
                Component.translatable("world.nirncraft.month." + month),
                String.valueOf(year));
    }

    private static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
