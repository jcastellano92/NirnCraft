package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Townsfolk talking among themselves: two NPCs who meet while strolling, at lunch or at the inn stop, face each other
 * and trade a few lines overhead (gossip about the region, the weather, the war, dragons, their trades, a rumour
 * about a nearby place...). Conversations only start when a player is close enough to overhear them, so an empty
 * town costs nothing; the running ones are advanced twice a second from one server tick handler.
 *
 * <p>Lines are {@code society.skycraft.chat.<topic>.<variant>.<0..2>} (speaker A, B, A); {@link #TOPICS} holds the
 * variant count of each topic.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Chatter {
    /** Topic -> number of variants (each variant is three lines). */
    static final Map<String, Integer> TOPICS = new HashMap<>();
    private static final int LINES = 3;
    private static final int HEAR = 16;
    private static final int MAX_ACTIVE = 24;

    static {
        TOPICS.put("generic", 8);
        TOPICS.put("rain", 3);
        TOPICS.put("snow", 3);
        TOPICS.put("night", 2);
        TOPICS.put("dragons", 3);
        TOPICS.put("war", 3);
        TOPICS.put("jarl", 2);
        TOPICS.put("prices", 2);
        TOPICS.put("family", 2);
        TOPICS.put("rumor", 3);
        TOPICS.put("work_farmer", 2);
        TOPICS.put("work_blacksmith", 2);
        TOPICS.put("work_merchant", 2);
        TOPICS.put("work_miner", 1);
        TOPICS.put("work_hunter", 1);
        TOPICS.put("work_fisher", 1);
        TOPICS.put("work_priest", 1);
        TOPICS.put("work_bard", 1);
        TOPICS.put("children", 4);
        TOPICS.put("gossip_skyrim", 3);
        TOPICS.put("gossip_eastmarch", 2);
        TOPICS.put("gossip_the_reach", 2);
        TOPICS.put("gossip_the_rift", 2);
        TOPICS.put("gossip_haafingar", 2);
        TOPICS.put("gossip_whiterun", 2);
        TOPICS.put("gossip_winterhold", 1);
        TOPICS.put("gossip_cyrodiil", 2);
        TOPICS.put("gossip_morrowind", 2);
        TOPICS.put("gossip_hammerfell", 1);
        TOPICS.put("gossip_high_rock", 1);
        TOPICS.put("gossip_elsweyr", 1);
        TOPICS.put("gossip_black_marsh", 1);
        TOPICS.put("gossip_valenwood", 1);
        TOPICS.put("gossip_summerset", 1);
    }

    private static final class Talk {
        final NpcEntity a;
        final NpcEntity b;
        final String key;
        @Nullable
        final Component arg;
        int step;
        long next;

        Talk(NpcEntity a, NpcEntity b, String key, @Nullable Component arg, long next) {
            this.a = a;
            this.b = b;
            this.key = key;
            this.arg = arg;
            this.next = next;
        }
    }

    private static final List<Talk> ACTIVE = new ArrayList<>();

    /** Bard songs ({@code society.skycraft.song.<n>.title} and {@code .0..5} lyric lines, all original). */
    public static final int SONGS = 5;
    private static final int VERSES = 6;

    private static final class Song {
        final NpcEntity bard;
        final int song;
        int line;
        long next;

        Song(NpcEntity bard, int song, long next) {
            this.bard = bard;
            this.song = song;
            this.next = next;
        }
    }

    private static final List<Song> SINGING = new ArrayList<>();

    private Chatter() {}

    /** A bard sings the lyrics of {@code song} overhead while playing it. */
    public static void sing(NpcEntity bard, int song) {
        SINGING.removeIf(s -> s.bard == bard);
        SINGING.add(new Song(bard, Math.floorMod(song, SONGS), bard.level().getGameTime() + 30));
    }

    private static void tickSongs() {
        for (Iterator<Song> it = SINGING.iterator(); it.hasNext(); ) {
            Song s = it.next();
            if (!s.bard.isAlive() || s.bard.isRemoved() || s.line >= VERSES || s.bard.getTarget() != null) {
                it.remove();
                continue;
            }
            long now = s.bard.level().getGameTime();
            if (now < s.next) continue;
            Barks.say(s.bard, Component.translatable("society.skycraft.song." + s.song + "." + s.line));
            s.line++;
            s.next = now + 75;
        }
    }

    /** Called by the routine every second or so while an NPC is out and about; rarely starts a conversation. */
    public static void tryStart(NpcEntity npc) {
        if (!SocietyConfig.CHATTER.get() || ACTIVE.size() >= MAX_ACTIVE) return;
        if (!(npc.level() instanceof ServerLevel level) || npc.isConversing() || npc.getTarget() != null) return;
        if ((npc.tickCount + npc.getId() * 7) % 9 != 0 || npc.getRandom().nextInt(4) != 0) return;
        if (level.getNearestPlayer(npc, HEAR) == null) return; // nobody to overhear it
        NpcEntity partner = null;
        double best = 7 * 7;
        for (NpcEntity o : level.getEntitiesOfClass(NpcEntity.class, npc.getBoundingBox().inflate(6, 2, 6),
                x -> x != npc && x.isAlive() && !x.isConversing() && !x.isSleeping() && x.getTarget() == null && !x.isBusy()
                        && x.role().civilian && x.activity.social() && x.isChild() == npc.isChild())) {
            double d = o.distanceToSqr(npc);
            if (d < best && o.hasLineOfSight(npc)) {
                best = d;
                partner = o;
            }
        }
        if (partner == null) return;
        start(level, npc, partner);
    }

    private static void start(ServerLevel level, NpcEntity a, NpcEntity b) {
        RandomSource r = a.getRandom();
        Component arg = null;
        String topic = topic(level, a, b, r);
        if ("rumor".equals(topic)) {
            Place place = rumorPlace(a.blockPosition(), r);
            if (place == null) topic = "generic";
            else arg = place.name();
        }
        Integer variants = TOPICS.get(topic);
        if (variants == null || variants <= 0) return;
        String key = "society.skycraft.chat." + topic + "." + r.nextInt(variants) + ".";
        a.setConversationPartner(b.getId());
        b.setConversationPartner(a.getId());
        a.getNavigation().stop();
        b.getNavigation().stop();
        ACTIVE.add(new Talk(a, b, key, arg, level.getGameTime() + 10));
    }

    private static String topic(ServerLevel level, NpcEntity a, NpcEntity b, RandomSource r) {
        if (a.isChild()) return "children";
        BlockPos pos = a.blockPosition();
        if (level.isRaining() && level.canSeeSky(pos) && r.nextInt(3) == 0) {
            Biome.Precipitation p = level.getBiome(pos).value().getPrecipitationAt(pos);
            if (p == Biome.Precipitation.SNOW) return "snow";
            if (p == Biome.Precipitation.RAIN) return "rain";
        }
        if (Npcs.isNight(level) && r.nextInt(4) == 0) return "night";
        int roll = r.nextInt(12);
        Region region = Cultures.regionAt(level, pos);
        boolean skyrim = "skyrim".equals(region.province());
        switch (roll) {
            case 0, 1 -> {
                String local = "gossip_" + region.shortId();
                if (TOPICS.containsKey(local) && r.nextBoolean()) return local;
                String province = "gossip_" + region.province();
                return TOPICS.containsKey(province) ? province : "generic";
            }
            case 2 -> {
                return skyrim ? "dragons" : "generic";
            }
            case 3 -> {
                return skyrim ? "war" : "prices";
            }
            case 4 -> {
                return "rumor";
            }
            case 5, 6 -> {
                String work = "work_" + a.role().id;
                return TOPICS.containsKey(work) ? work : "prices";
            }
            case 7 -> {
                return skyrim ? "jarl" : "family";
            }
            case 8 -> {
                return r.nextBoolean() ? "family" : "prices";
            }
            default -> {
                return "generic";
            }
        }
    }

    /** A named place worth talking about: a dungeon, landmark, shrine or standing stone within a long walk. */
    @Nullable
    static Place rumorPlace(BlockPos pos, RandomSource r) {
        PlaceType[] kinds = {PlaceType.DUNGEON_FIXED, PlaceType.LANDMARK, PlaceType.STANDING_STONE, PlaceType.DAEDRIC_SHRINE,
                PlaceType.FORT, PlaceType.WORD_WALL};
        PlaceType first = kinds[r.nextInt(kinds.length)];
        Place p = Places.nearest(first, pos.getX(), pos.getZ(), 2500).orElse(null);
        if (p != null && p.distSqr(pos.getX(), pos.getZ()) > 48L * 48L) return p;
        for (PlaceType k : kinds) {
            p = Places.nearest(k, pos.getX(), pos.getZ(), 2500).orElse(null);
            if (p != null && p.distSqr(pos.getX(), pos.getZ()) > 48L * 48L) return p;
        }
        return null;
    }

    /** A player addressed one of them: the other wanders off. */
    public static void interrupt(NpcEntity npc) {
        for (Iterator<Talk> it = ACTIVE.iterator(); it.hasNext(); ) {
            Talk t = it.next();
            if (t.a == npc || t.b == npc) {
                end(t);
                it.remove();
            }
        }
    }

    private static void end(Talk t) {
        if (t.a.getConversationPartner() == t.b.getId()) t.a.setConversationPartner(-1);
        if (t.b.getConversationPartner() == t.a.getId()) t.b.setConversationPartner(-1);
    }

    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        ACTIVE.clear();
        SINGING.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 10 != 3) return;
        if (!SINGING.isEmpty()) tickSongs();
        if (ACTIVE.isEmpty()) return;
        for (Iterator<Talk> it = ACTIVE.iterator(); it.hasNext(); ) {
            Talk t = it.next();
            try {
                if (!t.a.isAlive() || !t.b.isAlive() || t.a.isRemoved() || t.b.isRemoved() || t.a.level() != t.b.level()
                        || t.a.distanceToSqr(t.b) > 8 * 8 || t.a.getTarget() != null || t.b.getTarget() != null
                        || t.a.getConversationPartner() != t.b.getId() || t.b.getConversationPartner() != t.a.getId()) {
                    end(t);
                    it.remove();
                    continue;
                }
                long now = t.a.level().getGameTime();
                if (now < t.next) continue;
                if (t.step >= LINES) {
                    end(t);
                    it.remove();
                    continue;
                }
                NpcEntity speaker = t.step % 2 == 0 ? t.a : t.b;
                NpcEntity listener = speaker == t.a ? t.b : t.a;
                Component line = t.arg != null
                        ? Component.translatable(t.key + t.step, t.arg, listener.getDisplayName())
                        : Component.translatable(t.key + t.step, listener.getDisplayName());
                Barks.say(speaker, line);
                t.step++;
                t.next = now + 60 + speaker.getRandom().nextInt(20);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft society: chatter failed", e);
                end(t);
                it.remove();
            }
        }
    }
}
