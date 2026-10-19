package com.nirncraft.society;

import com.nirncraft.NirnCraft;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
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
 * <p>Lines are {@code society.nirncraft.chat.<topic>.<variant>.<0..2>} (speaker A, B, A); {@link #TOPICS} holds the
 * variant count of each topic.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
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
        TOPICS.putAll(RegionVoice.gossipTopics()); // region/province gossip: nirncraft/society/region_voice.json
    }

    private static final class Talk {
        final NpcEntity a;
        final NpcEntity b;
        final String key;
        @Nullable
        final Component arg;
        @Nullable
        final List<Component> customLines;
        int step;
        long next;

        Talk(NpcEntity a, NpcEntity b, String key, @Nullable Component arg, @Nullable List<Component> customLines, long next) {
            this.a = a;
            this.b = b;
            this.key = key;
            this.arg = arg;
            this.customLines = customLines;
            this.next = next;
        }
    }

    private static final List<Talk> ACTIVE = new ArrayList<>();
    /** Blocks around a running conversation in which no other starts. Tuning knob. */
    private static final double CLUSTER_GAP = 10.0D;

    /** Bard songs ({@code society.nirncraft.song.<n>.title} and {@code .0..5}, plus regional {@code society.nirncraft.song.<culture>.<0..3>.<0..3>}). */
    public static final int SONGS = 5;
    private static final int VERSES = com.nirncraft.audio.BardPerformance.VERSES_PER_SONG;

    private static final class Song {
        final NpcEntity bard;
        final String key;
        final int verses;
        int line;
        long next;

        Song(NpcEntity bard, String key, int verses, long next) {
            this.bard = bard;
            this.key = key;
            this.verses = verses;
            this.next = next;
        }
    }

    private static final List<Song> SINGING = new ArrayList<>();

    private Chatter() {}

    /** Returns the number of configured variants for {@code topic}, or 0 if unknown. */
    public static int topicVariants(String topic) {
        if (topic == null) return 0;
        Integer v = TOPICS.get(topic);
        if (v != null && v > 0) return v;
        return RegionVoice.gossipCount(topic.startsWith("gossip_") ? topic.substring("gossip_".length()) : topic);
    }

    /** Deterministic helper for selecting a cultural/regional gossip topic for {@code region}. */
    public static String pickTopic(Region region, boolean preferRegion) {
        if (region == null) return "generic";
        String gossip = RegionVoice.gossipTopic(region.shortId(), region.province(), preferRegion);
        if (gossip != null && topicVariants(gossip) > 0) return gossip;
        return "generic";
    }

    /** Regional bard song repertoire keys for {@code culture} ({@code society.nirncraft.song.<culture>.<0..3>}). */
    public static List<String> songsFor(String culture) {
        return com.nirncraft.audio.BardPerformance.songsFor(culture);
    }

    /** Regional bard song repertoire keys for {@code bard}. */
    public static List<String> songsFor(NpcEntity bard) {
        return com.nirncraft.audio.BardPerformance.songsFor(bard);
    }

    /** A bard sings the regional lyrics of {@code song} overhead while playing it. */
    public static void sing(NpcEntity bard, int song) {
        String key = com.nirncraft.audio.BardPerformance.pickSong(bard, song);
        singKey(bard, key, VERSES);
    }

    /** A bard sings the lyrics of an explicit song base key ({@code <key>.0 .. <key>.<verses-1>}). */
    public static void singKey(NpcEntity bard, String songKey, int verses) {
        SINGING.removeIf(s -> s.bard == bard);
        SINGING.add(new Song(bard, songKey, Math.max(1, verses), bard.level().getGameTime() + 30));
    }

    private static void tickSongs() {
        for (Iterator<Song> it = SINGING.iterator(); it.hasNext(); ) {
            Song s = it.next();
            if (!s.bard.isAlive() || s.bard.isRemoved() || s.line >= s.verses || s.bard.getTarget() != null) {
                it.remove();
                continue;
            }
            long now = s.bard.level().getGameTime();
            if (now < s.next) continue;
            Barks.say(s.bard, Component.translatable(s.key + "." + s.line));
            s.line++;
            s.next = now + 75;
        }
    }

    /** Called by the routine every second or so while an NPC is out and about; rarely starts a conversation. */
    public static void tryStart(NpcEntity npc) {
        if (!SocietyConfig.CHATTER.get() || ACTIVE.size() >= MAX_ACTIVE) return;
        if (!(npc.level() instanceof ServerLevel level) || npc.isConversing() || npc.getTarget() != null) return;
        if (com.nirncraft.fire.aftermath.HelgenFolk.inHelgen(npc)) return;
        if ((npc.tickCount + npc.getId() * 7) % 9 != 0 || npc.getRandom().nextInt(4) != 0) return;
        if (level.getNearestPlayer(npc, HEAR) == null) return; // nobody to overhear it
        for (Talk t : ACTIVE) { // one speaker per cluster: no bubbles stacked over a crowd
            if (t.a.level() == level && t.a.distanceToSqr(npc) < CLUSTER_GAP * CLUSTER_GAP) return;
        }
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

    @Nullable
    private static List<Component> buildResidentExchange(NpcEntity a, NpcEntity b, RandomSource r) {
        String aBark = a.getPersistentData().getStringOr(SocietyResidents.BARK_KEY, "");
        String aTalk = a.getPersistentData().getStringOr(SocietyResidents.TALK_KEY, "");
        String aSecret = a.getPersistentData().getStringOr(SocietyResidents.SECRET_KEY, "");
        String bBark = b.getPersistentData().getStringOr(SocietyResidents.BARK_KEY, "");
        String bTalk = b.getPersistentData().getStringOr(SocietyResidents.TALK_KEY, "");
        String bSecret = b.getPersistentData().getStringOr(SocietyResidents.SECRET_KEY, "");

        String line0 = !aBark.isBlank() ? aBark : aTalk;
        String line1 = !bTalk.isBlank() ? bTalk : (!bBark.isBlank() ? bBark : bSecret);
        String line2 = !aSecret.isBlank() && r.nextBoolean() ? aSecret : (!aTalk.isBlank() ? aTalk : aBark);
        if (line0.isBlank() || line1.isBlank() || line2.isBlank()) return null;
        return List.of(
                Component.literal(line0),
                Component.literal(line1),
                Component.literal(line2)
        );
    }

    private static void start(ServerLevel level, NpcEntity a, NpcEntity b) {
        RandomSource r = a.getRandom();
        if (!a.isChild() && r.nextInt(3) == 0) {
            List<Component> residentLines = buildResidentExchange(a, b, r);
            if (residentLines != null) {
                a.setConversationPartner(b.getId());
                b.setConversationPartner(a.getId());
                a.getNavigation().stop();
                b.getNavigation().stop();
                ACTIVE.add(new Talk(a, b, "", null, residentLines, level.getGameTime() + 10));
                return;
            }
        }
        Component arg = null;
        String topic = topic(level, a, b, r);
        if ("rumor".equals(topic)) {
            Place place = rumorPlace(a.blockPosition(), r);
            if (place == null) {
                Region reg = Cultures.regionAt(level, a.blockPosition());
                topic = pickTopic(reg, true);
            } else {
                arg = place.name();
            }
        }
        int variants = topicVariants(topic);
        if (variants <= 0) return;
        String key = "society.nirncraft.chat." + topic + "." + r.nextInt(variants) + ".";
        a.setConversationPartner(b.getId());
        b.setConversationPartner(a.getId());
        a.getNavigation().stop();
        b.getNavigation().stop();
        ACTIVE.add(new Talk(a, b, key, arg, null, level.getGameTime() + 10));
    }

    private static String topic(ServerLevel level, NpcEntity a, NpcEntity b, RandomSource r) {
        if (a.isChild()) return "children";
        BlockPos pos = a.blockPosition();
        if (level.isRaining() && level.canSeeSky(pos) && r.nextInt(3) == 0) {
            Biome.Precipitation p = level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
            if (p == Biome.Precipitation.SNOW) return "snow";
            if (p == Biome.Precipitation.RAIN) return "rain";
        }
        if (Npcs.isNight(level) && r.nextInt(4) == 0) return "night";
        int roll = r.nextInt(12);
        Region region = Cultures.regionAt(level, pos);
        boolean skyrim = "skyrim".equals(region.province());
        switch (roll) {
            case 0, 1, 2, 3, 9, 10, 11 -> {
                String gossip = pickTopic(region, roll % 2 == 0);
                return topicVariants(gossip) > 0 ? gossip : "generic";
            }
            case 4 -> {
                return "rumor";
            }
            case 5, 6 -> {
                String work = "work_" + a.role().id;
                return TOPICS.containsKey(work) ? work : pickTopic(region, true);
            }
            case 7 -> {
                return skyrim ? "jarl" : pickTopic(region, false);
            }
            case 8 -> {
                return skyrim && r.nextBoolean() ? "war" : (r.nextBoolean() ? "family" : "prices");
            }
            default -> {
                return pickTopic(region, true);
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
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        ACTIVE.clear();
        SINGING.clear();
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || event.getServer().getTickCount() % 10 != 3) return;
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
                Component line;
                if (t.customLines != null && t.step < t.customLines.size()) {
                    line = t.customLines.get(t.step);
                } else {
                    line = t.arg != null
                            ? Component.translatable(t.key + t.step, t.arg, listener.getDisplayName())
                            : Component.translatable(t.key + t.step, listener.getDisplayName());
                }
                Barks.say(speaker, line);
                t.step++;
                t.next = now + 60 + speaker.getRandom().nextInt(20);
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.warn("NirnCraft society: chatter failed", e);
                end(t);
                it.remove();
            }
        }
    }
}
