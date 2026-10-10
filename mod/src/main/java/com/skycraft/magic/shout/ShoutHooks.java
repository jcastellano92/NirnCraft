package com.skycraft.magic.shout;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Static hooks other modules can listen to without the magic module knowing them. The weather/visuals module
 * registers here to clear its own fog and weather when someone shouts Clear Skies, or to darken the sky for Storm
 * Call. Listeners run on the server thread after the vanilla weather was changed.
 */
public final class ShoutHooks {
    /** {@code durationTicks}: how long the skies stay clear (or the storm lasts). */
    @FunctionalInterface
    public interface WeatherListener {
        void onWeatherShout(ServerLevel level, ServerPlayer shouter, int words, int durationTicks);
    }

    private static final List<WeatherListener> CLEAR_SKIES = new CopyOnWriteArrayList<>();
    private static final List<WeatherListener> STORM_CALL = new CopyOnWriteArrayList<>();

    private ShoutHooks() {}

    public static void onClearSkies(WeatherListener listener) {
        CLEAR_SKIES.add(listener);
    }

    public static void onStormCall(WeatherListener listener) {
        STORM_CALL.add(listener);
    }

    static void clearSkies(ServerLevel level, ServerPlayer shouter, int words, int duration) {
        for (WeatherListener l : CLEAR_SKIES) l.onWeatherShout(level, shouter, words, duration);
    }

    static void stormCall(ServerLevel level, ServerPlayer shouter, int words, int duration) {
        for (WeatherListener l : STORM_CALL) l.onWeatherShout(level, shouter, words, duration);
    }
}
