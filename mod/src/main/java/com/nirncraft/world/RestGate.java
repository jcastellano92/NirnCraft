package com.nirncraft.world;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * One predicate for "this player is in a state that owns the screen or the clock": the opening (cottage, cutscene), a
 * loading cover (fast travel, carriage, jail transfer: {@code perf/Prewarm}), a conversation, a scripted scene, the
 * death screen. A player in such a state cannot start or join a rest, a running skip stops for them, and nobody's
 * time skip starts while someone in the dimension is in one (their timers must not jump under a cutscene).
 * Other systems add their own forced-camera or hidden-player states with {@link #register}.
 */
public final class RestGate {
    private static final List<Function<ServerPlayer, String>> EXTRA = new CopyOnWriteArrayList<>();

    private RestGate() {}

    /** Adds a state: return a short reason (any non-null string) while the player is in it, else {@code null}. */
    public static void register(Function<ServerPlayer, String> state) {
        EXTRA.add(state);
    }

    /** Why the player cannot rest or take part in a skip right now, or {@code null}. */
    @Nullable
    public static String reason(ServerPlayer p) {
        if (!p.isAlive() || p.hasDisconnected()) return "dead";
        if (p.isSpectator()) return null; // spectators are simply outside the group
        if (com.nirncraft.quest.story.IntroFlow.inIntro(p)) return "intro";
        if (com.nirncraft.perf.Prewarm.busy(p.getUUID())) return "loading";
        if (com.nirncraft.dialogue.Dialogue.isTalking(p.getUUID())) return "dialogue";
        if (com.nirncraft.quest.story.Scenes.playing(p.getUUID())) return "scene";
        for (Function<ServerPlayer, String> f : EXTRA) {
            String r = f.apply(p);
            if (r != null) return r;
        }
        return null;
    }

    public static boolean blocked(ServerPlayer p) {
        return reason(p) != null;
    }
}
