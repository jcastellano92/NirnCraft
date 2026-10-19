package com.nirncraft.society;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.SkyData;
import com.nirncraft.roads.RoadsData;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Random wilderness encounters, plus the shared machinery for temporary mobs (encounters, hit squads, summons):
 * every such mob carries the persistent-data compound {@link #TAG} ({@code until} game time, {@code group} id,
 * optional {@code hunt} player, {@code intro} bark, {@code summon} flag) and is despawned by this class once no
 * player is around (or, for summons, when its time is up). Only tagged mobs are ever removed.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Encounters {
    public static final String TAG = "nirncraft_encounter";
    private static final int DESPAWN_FAR = 128;
    private static final long LIFETIME = 20 * 60 * 20L;

    static final Identifier GUARD = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "guard");
    static final Identifier BANDIT = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "bandit");
    static final Identifier GIANT = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "giant");
    static final Identifier DRAGON = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "dragon");
    static final Identifier DEER = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "deer");
    static final Identifier ELK = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "elk");

    public enum Kind {
        HUNTER, BARD, THIEF, IMPERIAL_ESCORT, STORMCLOAK_ESCORT, THALMOR_ESCORT, MAGE_VS_NECROMANCER, ADVENTURERS_VS_BANDITS,
        GIANT_VS_HUNTER, WOLF_PACK, VAMPIRES, COURIER, BEGGAR, STRANDED_MERCHANT, DRAGON, FORSWORN_AMBUSH, ADVENTURER,
        THALMOR_PATROL, SKIRMISH, OLD_ORC, FUGITIVE_AND_HUNTER, REVELERS,
        /** The scripted highway robbery ({@link Ambush}): a demand, a clock and four ways to answer. */
        BANDIT_AMBUSH;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Kind byId(String id) {
            for (Kind k : values()) if (k.id().equals(id)) return k;
            return null;
        }
    }

    /** Encounters that put hostile creatures near the player (they obey the hostile pacing rules). */
    private static final Set<Kind> THREATENING = java.util.EnumSet.of(Kind.THIEF, Kind.MAGE_VS_NECROMANCER, Kind.ADVENTURERS_VS_BANDITS,
            Kind.GIANT_VS_HUNTER, Kind.WOLF_PACK, Kind.VAMPIRES, Kind.DRAGON, Kind.FORSWORN_AMBUSH, Kind.SKIRMISH,
            Kind.FUGITIVE_AND_HUNTER, Kind.BANDIT_AMBUSH);

    private static final Map<UUID, Mob> TRACKED = new HashMap<>();
    private static final Map<UUID, Integer> NEXT = new HashMap<>();

    private Encounters() {}

    // ------------------------------------------------------------------ tagging

    public static boolean isTagged(Entity e) {
        return com.nirncraft.platform.NbtData.hasType(e.getPersistentData(), TAG, Tag.TAG_COMPOUND);
    }

    @Nullable
    static CompoundTag tag(Entity e) {
        CompoundTag pd = e.getPersistentData();
        return com.nirncraft.platform.NbtData.hasType(pd, TAG, Tag.TAG_COMPOUND) ? pd.getCompoundOrEmpty(TAG) : null;
    }

    /** Marks a mob as temporary: despawned when no player is near, or after {@code until} when nobody watches. */
    static CompoundTag mark(Mob mob, long until, int group) {
        CompoundTag t = new CompoundTag();
        t.putLong("until", until);
        t.putInt("group", group);
        mob.getPersistentData().put(TAG, t);
        return t;
    }

    /** A summoned creature (necromancer skeletons): vanishes at {@code until} regardless of players. */
    static void tagSummon(Mob mob, long until) {
        CompoundTag t = mark(mob, until, 0);
        t.putBoolean("summon", true);
    }

    /** Non-NPC hunters (bandits sent after the player) are steered towards their prey by this class. */
    static void tagHunt(Mob mob, UUID player) {
        CompoundTag t = tag(mob);
        if (t != null) t.store("hunt", net.minecraft.core.UUIDUtil.CODEC,  player);
    }

    static void tagIntro(Mob mob, String barkCategory) {
        CompoundTag t = tag(mob);
        if (t != null) t.putString("intro", barkCategory);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || !isTagged(mob)) return;
        TRACKED.put(mob.getUUID(), mob);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        TRACKED.remove(event.getEntity().getUUID(), event.getEntity());
    }

    /** Naming or leashing an encounter mob adopts it: it is no longer despawned. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        Entity target = event.getTarget();
        if (!isTagged(target)) return;
        if (event.getItemStack().is(Items.NAME_TAG) || event.getItemStack().is(Items.LEAD)) {
            target.getPersistentData().remove(TAG);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        NEXT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.LevelTickEvent.Post) || !(event.getLevel() instanceof ServerLevel level) || TRACKED.isEmpty()) return;
        long time = level.getGameTime();
        if (time % 20 == 7) steer(level);
        if (time % 100 == 13) despawnPass(level);
    }

    /** Hunters that are not society NPCs walk towards their prey; intros are spoken when a player comes close. */
    private static void steer(ServerLevel level) {
        for (Mob mob : new ArrayList<>(TRACKED.values())) {
            if (mob.isRemoved() || mob.level() != level) continue;
            CompoundTag t = tag(mob);
            if (t == null) continue;
            if (t.contains("intro")) {
                Player near = level.getNearestPlayer(mob, 10);
                if (near != null && !near.isSpectator()) {
                    Barks.sayLineNow(mob, t.getStringOr("intro",""), near.getDisplayName());
                    t.remove("intro");
                }
            }
            if (mob instanceof NpcEntity || !t.read("hunt", net.minecraft.core.UUIDUtil.CODEC).isPresent() || mob.getTarget() != null) continue;
            Player prey = level.getPlayerByUUID(t.read("hunt", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            if (prey == null || prey.isCreative() || prey.isSpectator() || !prey.isAlive()) continue;
            double d = mob.distanceToSqr(prey);
            if (d > 160 * 160) continue;
            // a sneaking, unseen player is not tracked by their live position
            if (com.nirncraft.combat.Stealth.hiddenFrom(mob, prey)) continue;
            if (d < 24 * 24 && mob.hasLineOfSight(prey)) mob.setTarget(prey);
            else mob.getNavigation().moveTo(prey, 1.0D);
        }
    }

    private static void despawnPass(ServerLevel level) {
        long now = com.nirncraft.world.RestManager.liveTime(level);
        List<Mob> gone = new ArrayList<>();
        for (Mob mob : TRACKED.values()) {
            if (mob.isRemoved() || mob.level() != level) continue;
            CompoundTag t = tag(mob);
            if (t == null) continue;
            // adopted: tamed, leashed, recruited as a follower, made persistent, or part of a quest
            if ((mob instanceof TamableAnimal ta && ta.isTame()) || mob.getLeashHolder() instanceof Player
                    || Followers.isFollower(mob) || mob.isPersistenceRequired()
                    || mob.getPersistentData().contains(com.nirncraft.quest.QuestSpawner.TAG_QUEST)) {
                mob.getPersistentData().remove(TAG);
                continue;
            }
            long until = t.getLongOr("until",0L);
            long skirmishUntil = t.getLongOr("skirmish_until",0L);
            if (skirmishUntil > 0 && now > skirmishUntil) {
                gone.add(mob);
                continue;
            }
            if (t.getBooleanOr("summon",false)) {
                if (now > until) gone.add(mob);
                continue;
            }
            if (level.getNearestPlayer(mob, DESPAWN_FAR) == null) gone.add(mob);
            else if (now > until && level.getNearestPlayer(mob, 48) == null) gone.add(mob);
        }
        for (Mob mob : gone) {
            if (mob.entityTags().contains("nirncraft_keep")) continue;
            if (mob.level() instanceof ServerLevel sl && level.getNearestPlayer(mob, 48) != null) {
                sl.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + 0.8, mob.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
            }
            mob.discard();
        }
    }

    /** Distinct encounter groups within 128 blocks of the player. */
    static int groupsNear(Player player) {
        Set<Integer> groups = new HashSet<>();
        for (Mob m : TRACKED.values()) {
            if (m.isRemoved() || m.level() != player.level() || m.distanceToSqr(player) > 128 * 128) continue;
            CompoundTag t = tag(m);
            if (t == null || t.getBooleanOr("summon",false)) continue;
            groups.add(t.getIntOr("group",0));
        }
        return groups.size();
    }

    /** Whether a hit squad is already after this player. */
    static boolean hunted(Player player) {
        UUID id = player.getUUID();
        for (Mob m : TRACKED.values()) {
            if (m.isRemoved()) continue;
            if (m instanceof NpcEntity n && id.equals(n.getHuntTarget())) return true;
            CompoundTag t = tag(m);
            if (t != null && t.read("hunt", net.minecraft.core.UUIDUtil.CODEC).isPresent() && id.equals(t.read("hunt", net.minecraft.core.UUIDUtil.CODEC).orElseThrow())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ per-player timer (called every second)

    static void tick(ServerPlayer player) {
        if (!SocietyConfig.ENCOUNTERS.get()) return;
        UUID id = player.getUUID();
        RandomSource random = player.getRandom();
        int left = NEXT.getOrDefault(id, 60 + random.nextInt(120)) - 1;
        if (left > 0) {
            NEXT.put(id, left);
            return;
        }
        NEXT.put(id, SocietyConfig.between(SocietyConfig.ENCOUNTER_MIN_SECONDS.get(), SocietyConfig.ENCOUNTER_MAX_SECONDS.get(), random));
        try {
            if (wilderness(player)) {
                com.nirncraft.tamriel.Region region = com.nirncraft.tamriel.Regions.at(player.level(), player.blockPosition());
                boolean hasStateScene = com.nirncraft.creatures.spawn.WildScenes.eligibleFor(player, region).stream()
                        .anyMatch(com.nirncraft.creatures.spawn.WildScenes.Scene::isPoliticalOrStateFiltered);
                if (random.nextInt(hasStateScene ? 2 : 3) == 0) {
                    if (com.nirncraft.creatures.spawn.WildScenes.rollNearPlayer(player, null) != null) return;
                }
            }
            trySpawn(player, null);
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.warn("NirnCraft society: encounter failed", e);
        }
    }

    /** Whether the player stands somewhere encounters may happen (overworld wilderness, under the sky). */
    static boolean wilderness(ServerPlayer player) {
        if (player.isSpectator() || player.level().dimension() != Level.OVERWORLD) return false;
        ServerLevel level = player.level();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ());
        if (surface - player.getBlockY() > 8) return false; // underground
        if (inSettlement(level, player.getBlockX(), player.getBlockZ())) return false;
        return level.getEntitiesOfClass(AbstractVillager.class, player.getBoundingBox().inflate(48, 24, 48), e -> true).isEmpty()
                && level.getEntitiesOfClass(NpcEntity.class, player.getBoundingBox().inflate(48, 24, 48), e -> e.getHome() != null).isEmpty();
    }

    static boolean inSettlement(ServerLevel level, int x, int z) {
        if (level.dimension() != Level.OVERWORLD) return false;
        try {
            return RoadsData.get(level).zoneAt(x, z) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Rolls (or forces) an encounter near the player.
     *
     * @return the kind spawned, or null
     */
    @Nullable
    public static Kind trySpawn(ServerPlayer player, @Nullable Kind forced) {
        ServerLevel level = player.level();
        if (forced == null) {
            if (!level.getGameRules().get(GameRules.SPAWN_MOBS) || !wilderness(player)) return null;
            if (groupsNear(player) >= SocietyConfig.ENCOUNTER_CAP.get()) return null;
        }
        Kind kind = forced != null ? forced : pick(player);
        if (kind == null || !available(level, kind)) return null;
        if (kind == Kind.BANDIT_AMBUSH && forced == null && !Ambush.eligible(player)) return null;
        // pacing: threatening encounters share the hostile cooldown (and grace period) with the region spawner and
        // stay out of the calm zones around the world spawn and settlements
        boolean threat = forced == null && THREATENING.contains(kind);
        if (threat && !com.nirncraft.creatures.spawn.SpawnPacing.hostileReady(player)) return null;
        BlockPos center = findSpawn(level, player, kind == Kind.DRAGON ? 70 : 40, kind == Kind.DRAGON ? 100 : 80, forced == null);
        if (center == null) return null;
        if (threat && com.nirncraft.creatures.spawn.SpawnPacing.calm(level, center)) return null;
        Group g = new Group(level, player, center);
        boolean ok = spawn(kind, g);
        if (ok && threat) com.nirncraft.creatures.spawn.SpawnPacing.noteHostile(player);
        return ok ? kind : null;
    }

    static boolean available(ServerLevel level, Kind kind) {
        boolean hostileOk = level.getDifficulty() != Difficulty.PEACEFUL && SocietyConfig.HOSTILE_ENCOUNTERS.get();
        return switch (kind) {
            case THIEF -> exists(GUARD);
            case GIANT_VS_HUNTER -> exists(GIANT);
            case DRAGON -> exists(DRAGON) && SocietyConfig.DRAGON_FLYBYS.get() && level.getDifficulty() != Difficulty.PEACEFUL;
            case VAMPIRES, FORSWORN_AMBUSH -> hostileOk;
            case BANDIT_AMBUSH -> hostileOk && exists(BANDIT);
            default -> true;
        };
    }

    static boolean exists(Identifier id) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(id);
    }

    @Nullable
    private static Kind pick(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos pos = player.blockPosition();
        Holder<Biome> biome = level.getBiome(pos);
        com.nirncraft.tamriel.Region region = com.nirncraft.tamriel.Regions.at(level, pos);
        String hold = region.shortId();
        String ruling = Reputation.rulingFaction(region.id());
        boolean forest = biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA)
                || "falkreath".equals(hold) || "the_rift".equals(hold);
        boolean mountain = biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL)
                || "the_reach".equals(hold) || "western_reach".equals(hold) || "wrothgar".equals(hold);
        boolean snowy = biome.value().coldEnoughToSnow(pos, level.getSeaLevel())
                || "winterhold".equals(hold) || "the_pale".equals(hold) || "eastmarch".equals(hold);
        boolean night = Npcs.isNight(level);
        int lvl = SkyData.get(player).getLevel();
        long lastLetter = Cosmetics.persistedTag(player).getLongOr("nirncraft_letter_time",0L);

        boolean imperialPolity = Reputation.IMPERIAL_LEGION.equals(ruling)
                || Reputation.ELDER_COUNCIL.equals(ruling)
                || Reputation.COLOVIAN_ESTATES.equals(ruling);
        boolean stormcloakPolity = Reputation.STORMCLOAKS.equals(ruling);
        boolean dominionPolity = Reputation.THALMOR.equals(ruling)
                || Reputation.ALDMERI_DOMINION.equals(ruling)
                || Reputation.alliedFactions(region.id()).contains(Reputation.THALMOR);
        int thalmorRep = Reputation.get(player, Reputation.THALMOR);
        int stormRep = Reputation.get(player, Reputation.STORMCLOAKS);
        int legionRep = Reputation.get(player, Reputation.IMPERIAL_LEGION);
        int forswornRep = Reputation.get(player, Reputation.FORSWORN);

        Map<Kind, Integer> w = new EnumMap<>(Kind.class);
        w.put(Kind.HUNTER, forest ? 10 : 5);
        w.put(Kind.BARD, night ? 2 : ("haafingar".equals(hold) ? 9 : 6));
        // two hold guards chasing a thief across open country would be guards in the wilderness (owner, playtest 5):
        // the scene stays available when forced (the intro, /nirncraft-dev), it is not rolled on the road
        w.put(Kind.THIEF, 0);
        w.put(Kind.IMPERIAL_ESCORT, (imperialPolity || "haafingar".equals(hold) || "falkreath".equals(hold) || "the_reach".equals(hold)) ? 9 : 4);
        w.put(Kind.STORMCLOAK_ESCORT, (stormcloakPolity || "eastmarch".equals(hold) || "the_pale".equals(hold) || "winterhold".equals(hold)) ? 9 : (snowy ? 6 : 3));
        w.put(Kind.THALMOR_ESCORT, (dominionPolity || "haafingar".equals(hold) || "the_reach".equals(hold)) ? 7 : 3);
        w.put(Kind.MAGE_VS_NECROMANCER, lvl >= 3 ? ("winterhold".equals(hold) || "falkreath".equals(hold) ? 7 : (night ? 5 : 3)) : 0);
        w.put(Kind.ADVENTURERS_VS_BANDITS, 5);
        w.put(Kind.GIANT_VS_HUNTER, lvl >= 5 ? (("whiterun".equals(hold) || "eastmarch".equals(hold) || "the_pale".equals(hold)) ? 6 : (mountain ? 4 : 2)) : 0);
        w.put(Kind.WOLF_PACK, forest || snowy ? 6 : 3);
        w.put(Kind.VAMPIRES, night ? (("hjaalmarch".equals(hold) || "haafingar".equals(hold) || "rivenspire".equals(hold)) ? 10 : 7) : 0);
        w.put(Kind.COURIER, level.getGameTime() - lastLetter > 36000 || lastLetter == 0 ? (night ? 1 : 4) : 0);
        w.put(Kind.BEGGAR, night ? 1 : 3);
        w.put(Kind.STRANDED_MERCHANT, night ? 1 : 4);
        w.put(Kind.DRAGON, lvl >= 10 ? 1 : 0);
        w.put(Kind.FORSWORN_AMBUSH, ("the_reach".equals(hold) || "western_reach".equals(hold))
                ? (forswornRep < 0 ? 14 : 10) : (mountain ? 4 : 1));
        w.put(Kind.ADVENTURER, 4);
        w.put(Kind.THALMOR_PATROL, (dominionPolity || "haafingar".equals(hold) || "the_reach".equals(hold))
                ? (thalmorRep <= -20 ? 10 : 6) : (thalmorRep <= -20 ? 5 : 2));
        w.put(Kind.SKIRMISH, (legionRep >= 25 || stormRep >= 25) ? 7 : (snowy ? 4 : 3));
        w.put(Kind.OLD_ORC, ("the_rift".equals(hold) || "the_reach".equals(hold) || "eastmarch".equals(hold) || "wrothgar".equals(hold))
                ? 7 : (mountain || snowy ? 5 : 2));
        w.put(Kind.FUGITIVE_AND_HUNTER, 4);
        w.put(Kind.REVELERS, night ? 2 : ("whiterun".equals(hold) || "the_rift".equals(hold)) ? 7 : 5);
        w.put(Kind.BANDIT_AMBUSH, lvl >= 2 ? 2 : 0);

        int total = 0;
        for (Map.Entry<Kind, Integer> e : w.entrySet()) {
            if (!available(level, e.getKey())) e.setValue(0);
            total += e.getValue();
        }
        if (total <= 0) return null;
        int roll = player.getRandom().nextInt(total);
        for (Map.Entry<Kind, Integer> e : w.entrySet()) {
            if ((roll -= e.getValue()) < 0) return e.getKey();
        }
        return null;
    }

    // ------------------------------------------------------------------ positions

    /** A surface spot {@code min..max} blocks from the player, out of their sight (if {@code hidden}), not in a town. */
    @Nullable
    static BlockPos findSpawn(ServerLevel level, ServerPlayer player, int min, int max, boolean hidden) {
        RandomSource r = player.getRandom();
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = min + r.nextDouble() * (max - min);
            int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
            BlockPos ground = groundAt(level, x, z);
            if (ground == null || !level.isPositionEntityTicking(ground)) continue;
            if (Math.abs(ground.getY() - player.getBlockY()) > 30) continue;
            if (inSettlement(level, x, z)) continue;
            if (hidden && visible(level, player, ground)) continue;
            return ground;
        }
        return null;
    }

    /** Ground level at a column (top of the highest solid non-leaf block), or null over water/unloaded chunks. */
    @Nullable
    public static BlockPos groundAt(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, level.getSeaLevel(), z))) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
        if (!below.getFluidState().isEmpty() || below.is(BlockTags.LEAVES)) return null;
        return new BlockPos(x, y, z);
    }

    /** A free spot within {@code radius} of {@code center} (or the center itself). */
    static BlockPos near(ServerLevel level, BlockPos center, int radius, RandomSource r) {
        for (int i = 0; i < 6; i++) {
            int x = center.getX() + r.nextInt(radius * 2 + 1) - radius;
            int z = center.getZ() + r.nextInt(radius * 2 + 1) - radius;
            BlockPos p = groundAt(level, x, z);
            if (p != null && Math.abs(p.getY() - center.getY()) <= 3) return p;
        }
        return center;
    }

    private static boolean visible(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);
        Vec3 to = target.subtract(eye).normalize();
        if (player.getViewVector(1.0f).dot(to) < 0.3) return false;
        HitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ spawning

    /** One encounter being spawned: shared group id, lifetime, and a "walk past the player" destination. */
    static final class Group {
        final ServerLevel level;
        final ServerPlayer player;
        final BlockPos center;
        final int id;
        final long until;
        final RandomSource random;

        Group(ServerLevel level, ServerPlayer player, BlockPos center) {
            this.level = level;
            this.player = player;
            this.center = center;
            this.random = level.getRandom();
            this.id = random.nextInt(Integer.MAX_VALUE - 1) + 1;
            this.until = com.nirncraft.world.RestManager.liveTime(level) + LIFETIME;
        }

        /** A point beyond the player on the line from the spawn, so travelers walk past them. */
        BlockPos pastPlayer() {
            double dx = player.getX() - center.getX();
            double dz = player.getZ() - center.getZ();
            double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            double side = (random.nextDouble() - 0.5) * 16;
            int x = Mth.floor(player.getX() + dx / len * 60 - dz / len * side);
            int z = Mth.floor(player.getZ() + dz / len * 60 + dx / len * side);
            return new BlockPos(x, player.getBlockY(), z);
        }

        /** A point away from the player (fleeing thieves). */
        BlockPos awayFromPlayer() {
            double dx = center.getX() - player.getX();
            double dz = center.getZ() - player.getZ();
            double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            return new BlockPos(Mth.floor(center.getX() + dx / len * 80), center.getY(), Mth.floor(center.getZ() + dz / len * 80));
        }

        @Nullable
        NpcEntity npc(NpcRole role, BlockPos pos) {
            NpcEntity npc = Npcs.create(level, pos, role);
            if (npc == null) return null;
            mark(npc, until, id);
            return level.addFreshEntity(npc) ? npc : null;
        }

        @Nullable
        NpcEntity npcNear(NpcRole role, int radius) {
            return npc(role, near(level, center, radius, random));
        }

        @Nullable
        Mob mob(EntityType<?> type, BlockPos pos) {
            Entity e = type.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
            if (!(e instanceof Mob mob)) return null;
            mob.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360f, 0f);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
            mark(mob, until, id);
            return level.addFreshEntity(mob) ? mob : null;
        }

        @Nullable
        Mob mob(Identifier id, BlockPos pos) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            return type == null || !exists(id) ? null : mob(type, pos);
        }

        @Nullable
        Mob prey(int radius) {
            BlockPos p = near(level, center.offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4), radius, random);
            Mob deer = exists(DEER) ? mob(DEER, p) : exists(ELK) ? mob(ELK, p) : null;
            return deer != null ? deer : mob(net.minecraft.world.entity.EntityTypes.RABBIT, p);
        }
    }

    private static void fight(LivingEntity a, LivingEntity b) {
        if (a instanceof Mob ma) ma.setTarget(b);
        if (b instanceof Mob mb) mb.setTarget(a);
    }

    private static boolean spawn(Kind kind, Group g) {
        switch (kind) {
            case HUNTER -> {
                NpcEntity hunter = g.npc(NpcRole.HUNTER, g.center);
                if (hunter == null) return false;
                hunter.setDestination(g.pastPlayer());
                Mob prey = g.prey(10);
                if (prey != null) hunter.setTarget(prey);
                return true;
            }
            case BARD, ADVENTURER, BEGGAR -> {
                NpcRole role = kind == Kind.BARD ? NpcRole.BARD : kind == Kind.BEGGAR ? NpcRole.BEGGAR : NpcRole.ADVENTURER;
                NpcEntity npc = g.npc(role, g.center);
                if (npc == null) return false;
                npc.setDestination(g.pastPlayer());
                return true;
            }
            case THIEF -> {
                NpcEntity thief = g.npc(NpcRole.THIEF, g.center);
                if (thief == null) return false;
                thief.setDestination(g.awayFromPlayer());
                tagIntro(thief, "thief_flee");
                for (int i = 0; i < 2; i++) {
                    Mob guard = g.mob(GUARD, near(g.level, g.center.offset(i * 2 - 1, 0, 3), 3, g.random));
                    if (guard == null) continue;
                    if (guard instanceof PathfinderMob pm) pm.clearHome();
                    guard.setTarget(thief);
                    if (i == 0) tagIntro(guard, "guard_chase");
                }
                return true;
            }
            case IMPERIAL_ESCORT, STORMCLOAK_ESCORT, THALMOR_ESCORT -> {
                NpcRole captor = kind == Kind.IMPERIAL_ESCORT ? NpcRole.IMPERIAL_SOLDIER
                        : kind == Kind.STORMCLOAK_ESCORT ? NpcRole.STORMCLOAK_SOLDIER : NpcRole.THALMOR;
                NpcRole captive = kind == Kind.IMPERIAL_ESCORT ? NpcRole.STORMCLOAK_SOLDIER
                        : kind == Kind.STORMCLOAK_ESCORT ? NpcRole.IMPERIAL_SOLDIER : NpcRole.PRIEST;
                NpcEntity leader = g.npc(captor, g.center);
                if (leader == null) return false;
                leader.setDestination(g.pastPlayer());
                tagIntro(leader, "escort");
                NpcEntity prisoner = g.npcNear(captive, 2);
                if (prisoner != null) {
                    prisoner.setPrisoner(true);
                    prisoner.setLeader(leader.getUUID());
                    if (kind == Kind.THALMOR_ESCORT) {
                        prisoner.setCustomName(Component.translatable("society.nirncraft.title.talos_worshipper"));
                    }
                }
                NpcEntity second = g.npcNear(captor, 3);
                if (second != null) second.setLeader(leader.getUUID());
                return true;
            }
            case MAGE_VS_NECROMANCER -> {
                NpcEntity mage = g.npc(NpcRole.MAGE, g.center);
                NpcEntity necro = g.npc(NpcRole.NECROMANCER, near(g.level, g.center.offset(8, 0, 4), 3, g.random));
                if (mage == null || necro == null) return mage != null || necro != null;
                fight(mage, necro);
                return true;
            }
            case ADVENTURERS_VS_BANDITS -> {
                NpcEntity a = g.npc(NpcRole.ADVENTURER, g.center);
                NpcEntity b = g.npcNear(NpcRole.ADVENTURER, 2);
                List<LivingEntity> foes = new ArrayList<>();
                BlockPos camp = g.center.offset(10, 0, -6);
                for (int i = 0; i < 2; i++) {
                    BlockPos p = near(g.level, camp, 3, g.random);
                    LivingEntity foe = exists(BANDIT) ? g.mob(BANDIT, p) : g.npc(NpcRole.FORSWORN, p);
                    if (foe != null) foes.add(foe);
                }
                if (a == null && b == null) return !foes.isEmpty();
                for (int i = 0; i < foes.size(); i++) {
                    NpcEntity hero = i == 0 ? (a != null ? a : b) : (b != null ? b : a);
                    fight(hero, foes.get(i));
                }
                return true;
            }
            case GIANT_VS_HUNTER -> {
                Mob giant = g.mob(GIANT, g.center);
                NpcEntity hunter = g.npc(NpcRole.HUNTER, near(g.level, g.center.offset(9, 0, 5), 3, g.random));
                if (giant != null && hunter != null) fight(giant, hunter);
                return giant != null || hunter != null;
            }
            case WOLF_PACK -> {
                Mob prey = g.prey(4);
                int n = 2 + g.random.nextInt(2);
                boolean any = prey != null;
                for (int i = 0; i < n; i++) {
                    Mob wolf = g.mob(net.minecraft.world.entity.EntityTypes.WOLF, near(g.level, g.center.offset(-8, 0, -8), 3, g.random));
                    if (wolf == null) continue;
                    any = true;
                    if (prey != null) wolf.setTarget(prey);
                }
                if (prey instanceof PathfinderMob pm) pm.getNavigation().moveTo(g.player, 1.2D);
                return any;
            }
            case VAMPIRES, FORSWORN_AMBUSH -> {
                NpcRole role = kind == Kind.VAMPIRES ? NpcRole.VAMPIRE : NpcRole.FORSWORN;
                int n = kind == Kind.VAMPIRES ? 1 + g.random.nextInt(2) : 2 + g.random.nextInt(2);
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    NpcEntity npc = g.npcNear(role, 3);
                    if (npc == null) continue;
                    npc.setHuntTarget(g.player.getUUID());
                    any = true;
                }
                return any;
            }
            case COURIER -> {
                NpcEntity courier = g.npc(NpcRole.COURIER, g.center);
                if (courier == null) return false;
                courier.setSeekTarget(g.player.getUUID());
                courier.setLetter(Letters.pick(g.player, g.random));
                Cosmetics.persistedTag(g.player).putLong("nirncraft_letter_time", g.level.getGameTime());
                return true;
            }
            case STRANDED_MERCHANT -> {
                NpcEntity wt = g.npc(NpcRole.MERCHANT, g.center);
                if (wt == null) return false;
                wt.setHomeTo(g.center, 5);
                String merchantName = wt.getCustomName() != null ? wt.getCustomName().getString()
                        : NpcNames.generate(NpcNames.raceFor(NpcRole.INNKEEPER, g.random), g.random.nextBoolean(), g.random);
                wt.setCustomName(Component.translatable("society.nirncraft.title.stranded_merchant", merchantName));
                CompoundTag shop = new CompoundTag();
                ListTag pools = new ListTag();
                pools.add(StringTag.valueOf("general"));
                pools.add(StringTag.valueOf(g.random.nextBoolean() ? "hunter" : "apothecary"));
                shop.put("pools", pools);
                shop.putInt("level", 2);
                wt.getPersistentData().put("nirncraft_shop", shop);
                tagIntro(wt, "cart");
                Mob mule = g.mob(net.minecraft.world.entity.EntityTypes.MULE, near(g.level, g.center, 3, g.random));
                if (mule instanceof PathfinderMob pm) pm.setHomeTo(g.center, 4);
                return true;
            }
            case DRAGON -> {
                BlockPos sky = g.center.above(35 + g.random.nextInt(15));
                Mob dragon = g.mob(DRAGON, sky);
                if (dragon == null) return false;
                CompoundTag t = tag(dragon);
                if (t != null) t.putLong("until", com.nirncraft.world.RestManager.liveTime(g.level) + 3 * 60 * 20);
                // anyone nearby raises the alarm
                for (Mob m : g.level.getEntitiesOfClass(Mob.class, g.player.getBoundingBox().inflate(32),
                        x -> x instanceof NpcEntity || x instanceof AbstractVillager)) {
                    Barks.sayLineNow(m, "dragon");
                    break;
                }
                return true;
            }
            case THALMOR_PATROL -> {
                NpcEntity leader = g.npc(NpcRole.THALMOR, g.center);
                if (leader == null) return false;
                boolean hunted = Reputation.get(g.player, Reputation.THALMOR) <= -20;
                if (hunted) {
                    leader.setHuntTarget(g.player.getUUID());
                } else {
                    leader.setDestination(g.pastPlayer());
                }
                int n = 1 + g.random.nextInt(2);
                for (int i = 0; i < n; i++) {
                    NpcEntity f = g.npcNear(NpcRole.THALMOR, 3);
                    if (f != null) {
                        if (hunted) f.setHuntTarget(g.player.getUUID());
                        else f.setLeader(leader.getUUID());
                    }
                }
                return true;
            }
            case SKIRMISH -> {
                List<NpcEntity> legion = new ArrayList<>();
                List<NpcEntity> cloaks = new ArrayList<>();
                BlockPos other = near(g.level, g.center.offset(12, 0, 6), 3, g.random);
                for (int i = 0; i < 2; i++) {
                    NpcEntity a = g.npcNear(NpcRole.IMPERIAL_SOLDIER, 3);
                    if (a != null) legion.add(a);
                    NpcEntity b = g.npc(NpcRole.STORMCLOAK_SOLDIER, near(g.level, other, 3, g.random));
                    if (b != null) cloaks.add(b);
                }
                for (int i = 0; i < Math.min(legion.size(), cloaks.size()); i++) fight(legion.get(i), cloaks.get(i));
                // a skirmish is a moment, not a war: it breaks off after half a minute (despawnPass)
                long skirmishEnd = com.nirncraft.world.RestManager.liveTime(g.level) + 20 * 30;
                for (NpcEntity n : legion) { CompoundTag st = tag(n); if (st != null) st.putLong("skirmish_until", skirmishEnd); }
                for (NpcEntity n : cloaks) { CompoundTag st = tag(n); if (st != null) st.putLong("skirmish_until", skirmishEnd); }
                return !legion.isEmpty() || !cloaks.isEmpty();
            }
            case OLD_ORC -> {
                NpcEntity orc = g.npc(NpcRole.ADVENTURER, g.center);
                if (orc == null) return false;
                orc.setCustomName(Component.literal("Old Orc"));
                orc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
                orc.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                orc.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                orc.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                orc.getPersistentData().putBoolean("nirncraft_old_orc", true);
                orc.setDestination(g.pastPlayer());
                return true;
            }
            case FUGITIVE_AND_HUNTER -> {
                NpcEntity fugitive = g.npc(NpcRole.THIEF, g.center);
                if (fugitive == null) return false;
                fugitive.setCustomName(Component.literal("Panicked Fugitive"));
                fugitive.getPersistentData().putBoolean("nirncraft_fugitive", true);
                fugitive.setSeekTarget(g.player.getUUID());

                // Bounty hunter appears 12 blocks behind pursuing him
                BlockPos hunterPos = near(g.level, g.center.offset(-10, 0, -10), 4, g.random);
                NpcEntity hunter = g.npc(NpcRole.ADVENTURER, hunterPos);
                if (hunter != null) {
                    hunter.setCustomName(Component.literal("Bounty Hunter"));
                    hunter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                    hunter.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    hunter.getPersistentData().putBoolean("nirncraft_bounty_hunter", true);
                    hunter.setDestination(g.player.blockPosition());
                }
                return true;
            }
            case BANDIT_AMBUSH -> {
                return Ambush.start(g.player);
            }
            case REVELERS -> {
                int count = 2 + g.random.nextInt(2);
                NpcEntity leader = null;
                for (int i = 0; i < count; i++) {
                    BlockPos p = near(g.level, g.center, 3, g.random);
                    NpcEntity rev = g.npc(NpcRole.ADVENTURER, p);
                    if (rev == null) continue;
                    rev.setCustomName(Component.literal("Nord Reveler"));
                    ItemStack mead = new ItemStack(Items.HONEY_BOTTLE);
                    rev.setItemSlot(EquipmentSlot.MAINHAND, mead);
                    rev.getPersistentData().putBoolean("nirncraft_reveler", true);
                    if (leader == null) {
                        leader = rev;
                        rev.setDestination(g.pastPlayer());
                    } else {
                        rev.setLeader(leader.getUUID());
                    }
                }
                return leader != null;
            }
            default -> {
                return false;
            }
        }
    }
}
