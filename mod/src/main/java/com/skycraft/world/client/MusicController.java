package com.skycraft.world.client;

import com.skycraft.Skycraft;
import com.skycraft.audio.AudioConfig;
import com.skycraft.audio.client.AmbienceEngine;
import com.skycraft.client.ClientState;
import com.skycraft.client.SkyBossHud;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.network.CorePackets;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.world.LocationKind;
import com.skycraft.world.WorldConfig;
import com.skycraft.world.WorldData;
import com.skycraft.world.WorldSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Random;

/**
 * Skyrim-style situational soundtrack. Once a second it decides the mood and plays the matching {@code skycraft:music.*}
 * event with fades:
 * <ul>
 *   <li><b>explore</b>, by day or night, from the music set of the region you are in ({@link Region#music()}: every
 *   Tamriel province, the lands beyond, the open sea; unknown sets fall back to Skyrim's);</li>
 *   <li><b>town</b> by the culture of the settlement (Nord, Imperial/Breton, Dunmer, Khajiit/Redguard, elven);</li>
 *   <li><b>dungeon</b> by its kind (Nordic barrows, Dwemer ruins, Ayleid ruins) and <b>Blackreach</b>-like glow caves;</li>
 *   <li><b>combat</b> only in real fights (you hit or were hit recently, or a hostile is coming at you), <b>boss</b>
 *   music near bosses and the dragon battle theme near attacking dragons;</li>
 *   <li>Oblivion and Sovngarde.</li>
 * </ul>
 * Moods within one family (day/night, one province to the next) wait for the piece to end; other changes crossfade,
 * combat at once. Pieces are followed by silence. The title music fades out under the loading screen instead of
 * cutting, stings (discovery, level up) duck the music, and a nearby jukebox or performing bard silences it. Vanilla
 * music is muted while this is active (see {@link WorldClientEvents#onPlaySound}).
 */
public final class MusicController {
    public enum Kind { EXPLORE, TOWN, DUNGEON, BLACKREACH, COMBAT, BOSS, OBLIVION, SOVNGARDE }

    /** A mood: what kind of music, which event, and its family (moods of one family don't interrupt each other). */
    public record Mood(Kind kind, String event) {
        int family() {
            return switch (kind) {
                case DUNGEON, BLACKREACH -> 2;
                default -> kind.ordinal() + 10;
            };
        }

        boolean urgent() {
            return kind == Kind.COMBAT || kind == Kind.BOSS;
        }
    }

    private static final TagKey<EntityType<?>> TALKERS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("skycraft", "talkers"));
    private static final Random RANDOM = new Random();
    /** Seconds the combat mood holds after the last sign of a fight. */
    private static final int COMBAT_HOLD_TICKS = 160;

    private static FadingMusic current;
    private static Mood currentMood;
    private static Mood pendingMood;
    private static int pendingCount;
    private static int silenceSeconds = 3;
    private static int tickCounter;
    private static long lastCombatTick = Long.MIN_VALUE / 2;

    private static FadingMusic titleMusic;
    @Nullable
    private static SoundInstance jukebox;
    private static long duckUntil;
    private static float duckLevel = 1f;

    private MusicController() {}

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && WorldConfig.MUSIC.get();
    }

    public static boolean isOurs(SoundInstance sound) {
        return sound instanceof FadingMusic;
    }

    // ------------------------------------------------------------------ hooks from WorldClientEvents

    /**
     * The title screen asked to play its music: play it as our fading instance instead (so it can fade out under the
     * loading screen) and never twice. Returns the instance to play, or null to cancel.
     */
    @Nullable
    public static SoundInstance titleMusic(SoundInstance requested) {
        Minecraft mc = Minecraft.getInstance();
        if (titleMusic != null && !titleMusic.isStopped() && mc.getSoundManager().isActive(titleMusic)) return null;
        titleMusic = new FadingMusic(requested.getLocation(), 40);
        return titleMusic;
    }

    public static boolean titleMusicPlaying() {
        return titleMusic != null && !titleMusic.isStopped() && Minecraft.getInstance().getSoundManager().isActive(titleMusic);
    }

    /** A jukebox record started: the soundtrack makes way while it plays nearby. */
    public static void recordStarted(SoundInstance record) {
        jukebox = record;
    }

    /** A sting (discovery, level up) started: duck the soundtrack under it for a few seconds. */
    public static void stingStarted(int seconds) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) duckUntil = mc.level.getGameTime() + seconds * 20L;
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft mc) {
        if (mc.level != null && titleMusic != null) {
            // the world is here: let the title music die away under the first seconds of play
            titleMusic.fadeOut(80);
            titleMusic = null;
            silenceSeconds = Math.max(silenceSeconds, 5);
        }
        if (mc.level == null || mc.player == null || !WorldConfig.MUSIC.get()) {
            stopNow(mc);
            return;
        }
        if (++tickCounter % 20 != 0) return;

        boolean audible = mc.options.getSoundSourceVolume(SoundSource.MUSIC) > 0f && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0f;
        if (!audible) {
            stopNow(mc);
            return;
        }

        trackCombat(mc);

        // a jukebox or a bard playing nearby: the soundtrack falls silent until it stops
        if (nearPerformingBard(mc) || jukeboxNearby(mc)) {
            if (current != null && !current.isStopped() && mc.getSoundManager().isActive(current)) {
                current.fadeOut(40);
                current = null;
                currentMood = null;
            }
            silenceSeconds = Math.max(silenceSeconds, 4);
            return;
        }
        duckLevel = mc.level.getGameTime() < duckUntil ? 0.3f : 1f;
        if (current != null) current.duck = duckLevel;

        Mood want = decide(mc);
        // require a mood to hold for a few seconds before switching (combat and bosses switch at once)
        if (!want.equals(pendingMood)) {
            pendingMood = want;
            pendingCount = 0;
        } else {
            pendingCount++;
        }
        boolean stable = want.urgent() || pendingCount >= 3;

        boolean playing = current != null && !current.isStopped() && mc.getSoundManager().isActive(current);
        if (playing) {
            boolean otherFamily = currentMood != null && currentMood.family() != want.family();
            boolean otherBoss = currentMood != null && currentMood.kind() == Kind.BOSS && want.kind() == Kind.BOSS
                    && !currentMood.event().equals(want.event());
            if ((otherFamily || otherBoss) && stable) {
                boolean leavingFight = currentMood.urgent() && !want.urgent();
                // crossfade: the old piece fades while the new one fades in shortly after
                current.fadeOut(want.urgent() ? 20 : (leavingFight ? 80 : 60));
                current = null;
                silenceSeconds = want.urgent() ? 0 : (leavingFight ? 6 + RANDOM.nextInt(6) : 1);
                currentMood = null;
            } else {
                return;
            }
        } else if (current != null) {
            // the piece ended on its own (or failed to start): Skyrim leaves a pause between pieces
            silenceSeconds = gapAfter(currentMood);
            current = null;
            currentMood = null;
        }

        if (current == null) {
            if (want.urgent()) silenceSeconds = 0;
            if (silenceSeconds > 0) {
                silenceSeconds--;
                return;
            }
            start(mc, want);
        }
    }

    private static int gapAfter(@Nullable Mood mood) {
        if (mood == null) return 3;
        return switch (mood.kind()) {
            case COMBAT, BOSS -> 0;
            case TOWN, OBLIVION, SOVNGARDE -> 10 + RANDOM.nextInt(20);
            case DUNGEON, BLACKREACH -> 15 + RANDOM.nextInt(30);
            case EXPLORE -> {
                int lo = AudioConfig.MIN_SILENCE.get();
                int hi = Math.max(lo, AudioConfig.MAX_SILENCE.get());
                yield lo + RANDOM.nextInt(hi - lo + 1);
            }
        };
    }

    private static void start(Minecraft mc, Mood mood) {
        mc.getMusicManager().stopPlaying();
        ResourceLocation loc = new ResourceLocation(Skycraft.MODID, mood.event());
        if (mc.getSoundManager().getSoundEvent(loc) == null) {
            loc = fallback(mood).getLocation();
        }
        current = new FadingMusic(loc, mood.urgent() ? 15 : 60);
        current.duck = duckLevel;
        currentMood = mood;
        mc.getSoundManager().play(current);
    }

    private static net.minecraft.sounds.SoundEvent fallback(Mood mood) {
        return switch (mood.kind()) {
            case TOWN -> WorldSounds.MUSIC_TOWN.get();
            case DUNGEON, BLACKREACH -> WorldSounds.MUSIC_DUNGEON.get();
            case COMBAT, BOSS -> WorldSounds.MUSIC_COMBAT.get();
            case OBLIVION -> WorldSounds.MUSIC_OBLIVION.get();
            case SOVNGARDE -> WorldSounds.MUSIC_SOVNGARDE.get();
            case EXPLORE -> mood.event().contains("night") ? WorldSounds.MUSIC_EXPLORE_NIGHT.get() : WorldSounds.MUSIC_EXPLORE.get();
        };
    }

    private static void stopNow(Minecraft mc) {
        if (current != null) {
            mc.getSoundManager().stop(current);
            current = null;
            currentMood = null;
        }
        silenceSeconds = 3;
    }

    public static void reset() {
        stopNow(Minecraft.getInstance());
        pendingMood = null;
        pendingCount = 0;
        jukebox = null;
        duckUntil = 0;
        lastCombatTick = Long.MIN_VALUE / 2;
    }

    /** Short sting on "DISCOVERED". */
    public static void playDiscoverySting() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.getSoundSourceVolume(SoundSource.MUSIC) <= 0f) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(WorldSounds.MUSIC_DISCOVERY.get(), 1.0f,
                0.6f * mc.options.getSoundSourceVolume(SoundSource.MUSIC)));
    }

    // ------------------------------------------------------------------ situation

    public static Mood decide(Minecraft mc) {
        Level level = mc.level;
        var player = mc.player;
        boolean fight = inCombat(mc);
        String dim = level.dimension().location().getPath();
        if (level.dimension() == Level.NETHER || dim.contains("oblivion")) {
            return fight ? new Mood(Kind.COMBAT, "music.combat") : new Mood(Kind.OBLIVION, "music.oblivion");
        }
        if (level.dimension() == Level.END || dim.contains("sovngarde")) {
            return fight ? new Mood(Kind.COMBAT, "music.combat") : new Mood(Kind.SOVNGARDE, "music.sovngarde");
        }
        if (AudioConfig.BOSS_MUSIC.get()) {
            Mood boss = boss(mc, fight);
            if (boss != null) return boss;
        }
        if (fight) return new Mood(Kind.COMBAT, "music.combat");
        BlockPos pos = player.blockPosition();
        Region region = region(level, pos);
        if (AmbienceEngine.inGlowCave() && pos.getY() < level.getSeaLevel() - 20) return new Mood(Kind.BLACKREACH, "music.blackreach");
        String dungeon = dungeon(mc, pos, region);
        if (dungeon != null) return new Mood(Kind.DUNGEON, dungeon);
        String town = town(mc, pos, region);
        if (town != null) return new Mood(Kind.TOWN, town);
        long time = Math.floorMod(level.getDayTime(), 24000L);
        boolean night = time >= 13000 && time < 23000;
        String set = AmbienceEngine.atSea() ? "sea" : musicSet(region);
        if ("skyrim".equals(set)) return new Mood(Kind.EXPLORE, night ? "music.explore_night" : "music.explore");
        return new Mood(Kind.EXPLORE, (night ? "music.explore_night." : "music.explore.") + set);
    }

    private static Region region(Level level, BlockPos pos) {
        try {
            return Regions.at(level, pos);
        } catch (RuntimeException e) {
            return Regions.fallback();
        }
    }

    /** The exploration music set of a region: its {@code music} field, mapped onto the sets that exist. */
    static String musicSet(Region r) {
        if (!AudioConfig.PROVINCE_MUSIC.get()) return "skyrim";
        String m = r.music() == null ? "" : r.music().toLowerCase(Locale.ROOT);
        String set = setFor(m);
        if (set == null) set = setFor(r.province() == null ? "" : r.province().toLowerCase(Locale.ROOT));
        if (set == null && "sea".equals(r.kind())) set = "sea";
        if (set == null && r.palette() != null) set = setForPalette(r.palette().toLowerCase(Locale.ROOT));
        return set == null ? "skyrim" : set;
    }

    @Nullable
    private static String setFor(String m) {
        return switch (m) {
            case "skyrim" -> "skyrim";
            case "cyrodiil", "morrowind", "black_marsh", "elsweyr", "valenwood", "summerset", "hammerfell", "high_rock", "atmora",
                    "akavir", "sea" -> m;
            case "yokuda" -> "hammerfell";
            case "pyandonea" -> "summerset";
            case "thras" -> "sea";
            default -> null;
        };
    }

    /** Generated realms: pick the set whose landscape matches the realm's palette archetype. */
    @Nullable
    private static String setForPalette(String p) {
        if (p.contains("frozen") || p.contains("ice")) return "atmora";
        if (p.contains("alpine") || p.contains("tundra")) return "skyrim";
        if (p.contains("ash") || p.contains("mushroom") || p.contains("volcan")) return "morrowind";
        if (p.contains("swamp") || p.contains("marsh")) return "black_marsh";
        if (p.contains("desert")) return "hammerfell";
        if (p.contains("savanna")) return "elsweyr";
        if (p.contains("jungle")) return "valenwood";
        if (p.contains("crystal")) return "summerset";
        if (p.contains("temperate")) return "cyrodiil";
        return null;
    }

    // ---- combat & bosses

    /** Real fights only: the server's "in combat" (you hit or were hit recently) or a hostile coming at you. */
    private static void trackCombat(Minecraft mc) {
        long now = mc.level.getGameTime();
        if (ClientState.has(CorePackets.SyncVitals.IN_COMBAT) || hostileOnYou(mc)) lastCombatTick = now;
    }

    private static boolean inCombat(Minecraft mc) {
        return mc.level.getGameTime() - lastCombatTick < COMBAT_HOLD_TICKS;
    }

    /** A hostile mob within 20 blocks in attack mode (its aggressive flag is synced) that is facing you and can see you. */
    private static boolean hostileOnYou(Minecraft mc) {
        var player = mc.player;
        if (player.isCreative() || player.isSpectator()) return false;
        var box = player.getBoundingBox().inflate(20);
        for (Mob m : mc.level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy && e.isAlive() && e.isAggressive())) {
            Vec3 look = m.getViewVector(1f);
            Vec3 to = player.getEyePosition().subtract(m.getEyePosition()).normalize();
            if (look.dot(to) > 0.8 && m.hasLineOfSight(player)) return true;
        }
        return false;
    }

    @Nullable
    private static Mood boss(Minecraft mc, boolean fight) {
        var player = mc.player;
        // dragons: attacking (aggressive), or you are fighting with one about, or it is close
        for (DragonEntity d : mc.level.getEntitiesOfClass(DragonEntity.class, player.getBoundingBox().inflate(96), LivingEntity::isAlive)) {
            double dist = d.distanceTo(player);
            if (d.isAggressive() || dist < 40 || fight && dist < 96) return new Mood(Kind.BOSS, "music.boss");
        }
        LivingEntity boss = SkyBossHud.currentBoss();
        if (boss != null && boss.isAlive() && boss.level() == mc.level && boss.distanceTo(player) < 40
                && (fight || boss instanceof Mob m && m.isAggressive())) {
            return new Mood(Kind.BOSS, "music.boss.battle");
        }
        return null;
    }

    // ---- dungeons & towns

    @Nullable
    private static String dungeon(Minecraft mc, BlockPos pos, Region region) {
        Level level = mc.level;
        // the Dwemer caverns biome is one great ruin of the Deep Elves
        if (AmbienceEngine.inDwemerCaverns()) return "music.dungeon.dwemer";
        boolean dark = level.getBrightness(LightLayer.SKY, pos) < 4;
        // inside a discovered dungeon-like place (barrows, mines, Dwemer ruins...)
        ListTag list = SkyData.get(mc.player).module(WorldData.MODULE).getList("discovered", Tag.TAG_COMPOUND);
        String dim = level.dimension().location().toString();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            if (!loc.getString("dim").equals(dim)) continue;
            LocationKind kind = LocationKind.byId(loc.getString("type"));
            if (!kind.underground()) continue;
            if (WorldData.inBox(loc, pos.getX(), pos.getY(), pos.getZ(), 0) && dark) {
                return dungeonEvent(kind, loc.getString("name"), region);
            }
        }
        // a named dungeon from the Tamriel map
        for (Place p : Places.overlapping(pos.getX(), pos.getZ(), pos.getX(), pos.getZ())) {
            if (p.type() == com.skycraft.tamriel.PlaceType.DUNGEON_FIXED && dark && pos.getY() < level.getSeaLevel() + 20) {
                String v = (p.variant() + " " + p.culture()).toLowerCase(Locale.ROOT);
                if (v.contains("dwemer") || v.contains("dwarven")) return "music.dungeon.dwemer";
                if (v.contains("ayleid") || v.contains("welkynd")) return "music.dungeon.ayleid";
                return "music.dungeon";
            }
        }
        if (pos.getY() < level.getSeaLevel() - 10 && level.getBrightness(LightLayer.SKY, pos) == 0) return "music.dungeon";
        return null;
    }

    private static String dungeonEvent(LocationKind kind, String name, Region region) {
        String n = name.toLowerCase(Locale.ROOT);
        if (kind == LocationKind.DWEMER || n.contains("dwemer") || n.contains("dwarven")) return "music.dungeon.dwemer";
        if (n.contains("ayleid") || (kind == LocationKind.RUIN || kind == LocationKind.TOMB || kind == LocationKind.SANCTUM)
                && ("imperial".equals(region.culture()) || "altmer".equals(region.culture()) || "bosmer".equals(region.culture()))) {
            return "music.dungeon.ayleid";
        }
        return "music.dungeon";
    }

    @Nullable
    private static String town(Minecraft mc, BlockPos pos, Region region) {
        // a settlement from the Tamriel map...
        for (Place p : Places.overlapping(pos.getX(), pos.getZ(), pos.getX(), pos.getZ())) {
            if (p.type().isSettlement() && p.distSqr(pos.getX(), pos.getZ()) <= (long) p.radius() * p.radius()) {
                return townEvent(p.culture() == null || p.culture().isEmpty() ? region.culture() : p.culture());
            }
        }
        // ...a discovered town, or simply people about
        ListTag list = SkyData.get(mc.player).module(WorldData.MODULE).getList("discovered", Tag.TAG_COMPOUND);
        String dim = mc.level.dimension().location().toString();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            if (!loc.getString("dim").equals(dim)) continue;
            LocationKind kind = LocationKind.byId(loc.getString("type"));
            if ((kind == LocationKind.TOWN || kind == LocationKind.INN) && WorldData.inBox(loc, pos.getX(), pos.getY(), pos.getZ(), 8)) {
                return townEvent(region.culture());
            }
        }
        var box = mc.player.getBoundingBox().inflate(32);
        int count = mc.level.getEntitiesOfClass(LivingEntity.class, box, e -> e instanceof AbstractVillager || e.getType().is(TALKERS)).size();
        return count >= 2 ? townEvent(region.culture()) : null;
    }

    /** Town music by culture family. */
    static String townEvent(@Nullable String culture) {
        String c = culture == null ? "" : culture.toLowerCase(Locale.ROOT);
        return switch (c) {
            case "imperial", "breton", "colovian", "nibenese" -> "music.town.imperial";
            case "dunmer", "hlaalu", "redoran", "telvanni" -> "music.town.dunmer";
            case "khajiit", "redguard", "yokudan" -> "music.town.desert";
            case "altmer", "bosmer", "maormer" -> "music.town.elven";
            case "argonian" -> "music.explore.black_marsh";
            case "akaviri", "tsaesci", "kamal", "ka_po_tun", "tang_mo" -> "music.explore.akavir";
            default -> "music.town";
        };
    }

    private static boolean nearPerformingBard(Minecraft mc) {
        if (mc.level == null || mc.player == null) return false;
        var box = mc.player.getBoundingBox().inflate(24);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e instanceof com.skycraft.society.entity.NpcEntity npc && npc.isPlaying()) {
                return true;
            }
        }
        return false;
    }

    private static boolean jukeboxNearby(Minecraft mc) {
        if (jukebox == null) return false;
        if (!mc.getSoundManager().isActive(jukebox)) {
            jukebox = null;
            return false;
        }
        if (!AudioConfig.JUKEBOX_DUCKING.get()) return false;
        return mc.player.distanceToSqr(jukebox.getX(), jukebox.getY(), jukebox.getZ()) < 64 * 64;
    }

    /** For debugging / the F3 overlay. */
    public static String describe() {
        return currentMood == null ? "silence (" + silenceSeconds + "s)" : currentMood.kind() + " " + currentMood.event();
    }

    // ------------------------------------------------------------------ sound instance

    /**
     * A non-positional music track whose volume fades in and out (the sound engine re-reads the volume every tick),
     * can be ducked under stings, and avoids repeating the piece that played last when its event has others.
     */
    static final class FadingMusic extends AbstractTickableSoundInstance {
        private static ResourceLocation lastTrack;
        private final int fadeInTicks;
        private float level;
        private int fadeOutTicks;
        private float fadeOutStep;
        float duck = 1f;
        private float duckNow = 1f;

        FadingMusic(ResourceLocation event, int fadeInTicks) {
            super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(event), SoundSource.MUSIC, SoundInstance.createUnseededRandom());
            this.fadeInTicks = Math.max(1, fadeInTicks);
            this.looping = false;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.level = 0.02f;
            this.volume = level;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            WeighedSoundEvents w = super.resolve(manager);
            if (w != null && w.getWeight() > 1) {
                for (int i = 0; i < 4 && sound != null && sound.getLocation().equals(lastTrack); i++) w = super.resolve(manager);
            }
            if (sound != null && sound != SoundManager.EMPTY_SOUND) lastTrack = sound.getLocation();
            return w;
        }

        void fadeOut(int ticks) {
            fadeOutTicks = Math.max(1, ticks);
            fadeOutStep = Math.max(0.001f, level / fadeOutTicks);
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (fadeOutTicks > 0) {
                level -= fadeOutStep;
                if (--fadeOutTicks <= 0 || level <= 0f) {
                    level = 0f;
                    volume = 0f;
                    stop();
                    return;
                }
            } else if (level < 1f) {
                level = Math.min(1f, level + 1f / fadeInTicks);
            }
            duckNow += (duck - duckNow) * 0.08f;
            volume = level * duckNow;
        }
    }
}
