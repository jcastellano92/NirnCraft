package com.skycraft.quest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lazily spawns quest enemies: a quest stores where its enemies are; when a member of the owning party comes within
 * {@link QuestConfig#SPAWN_DISTANCE} blocks and the chunk is loaded, they are spawned once (persistent, named, tagged
 * with the owning quest so kills are credited).
 */
public final class QuestSpawner {
    public static final String TAG_OWNER = "skycraft_quest_owner";
    public static final String TAG_QUEST = "skycraft_quest_id";
    /** Entity (scoreboard) tag on quest bosses: they get their boss bar back whenever they rejoin a level. */
    public static final String BOSS_TAG = "skycraft_quest_boss";

    /** Vanilla stand-ins used when the creatures module's entity isn't registered. */
    private static final Map<String, String> FALLBACKS = Map.of(
            "skycraft:bandit", "minecraft:pillager",
            "skycraft:bandit_chief", "minecraft:vindicator",
            "skycraft:draugr", "minecraft:husk",
            "skycraft:draugr_deathlord", "minecraft:wither_skeleton",
            "skycraft:skeever", "minecraft:cave_spider",
            "skycraft:troll", "minecraft:ravager",
            "skycraft:giant", "minecraft:ravager",
            "skycraft:dragon", "minecraft:ghast",
            "skycraft:guard", "minecraft:iron_golem");

    private QuestSpawner() {}

    // ------------------------------------------------------------------ spawn specs

    /** Fluent builder of a spawn spec stored in {@link Objective#spawns}. */
    public static final class Spawn {
        private final CompoundTag tag = new CompoundTag();

        private Spawn(String type, int count) {
            tag.putString("type", type);
            tag.putInt("count", Math.max(1, count));
        }

        public static Spawn of(String type, int count) {
            return new Spawn(type, count);
        }

        public Spawn name(Component name) {
            tag.putString("name", Quest.writeComponent(name));
            return this;
        }

        /** This entity is the objective's KILL_TARGET. */
        public Spawn target() {
            tag.putBoolean("target", true);
            return this;
        }

        /** Shows a boss bar to nearby players. */
        public Spawn boss() {
            tag.putBoolean("boss", true);
            return this;
        }

        public Spawn glow() {
            tag.putBoolean("glow", true);
            return this;
        }

        public Spawn health(float mult) {
            tag.putFloat("hp", mult);
            return this;
        }

        public Spawn damage(float mult) {
            tag.putFloat("dmg", mult);
            return this;
        }

        /** Dyed leather armor (soldiers). */
        public Spawn armor(int rgb) {
            tag.putInt("armor", rgb);
            return this;
        }

        public Spawn weapon(Item item) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) tag.putString("weapon", id.toString());
            return this;
        }

        public CompoundTag build() {
            return tag;
        }
    }

    public static boolean isBossObjective(Objective o) {
        for (int i = 0; i < o.spawns.size(); i++) {
            CompoundTag s = o.spawns.getCompound(i);
            if (s.getBoolean("boss")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ entity creation

    public static Optional<EntityType<?>> resolveType(String id) {
        Optional<EntityType<?>> type = EntityType.byString(id);
        if (type.isEmpty() && FALLBACKS.containsKey(id)) type = EntityType.byString(FALLBACKS.get(id));
        return type;
    }

    @Nullable
    public static Entity create(ServerLevel level, String typeId) {
        Optional<EntityType<?>> type = resolveType(typeId);
        if (type.isEmpty()) type = EntityType.byString("minecraft:zombie");
        return type.map(t -> (Entity) t.create(level)).orElse(null);
    }

    /** Spawns the objective's enemies. Returns true if anything was spawned. */
    public static boolean spawnFor(ServerLevel level, String owner, Quest quest, Objective o) {
        RandomSource r = level.random;
        BlockPos base = resolveBase(level, o, r);
        o.pos = base;
        o.yKnown = true;
        o.spawnedIds.clear();
        o.spawned = true;
        o.missing = 0;
        boolean targetSet = false;
        for (int i = 0; i < o.spawns.size(); i++) {
            CompoundTag spec = o.spawns.getCompound(i);
            int count = Math.max(1, spec.getInt("count"));
            boolean target = spec.getBoolean("target");
            for (int n = 0; n < count; n++) {
                BlockPos p = target && n == 0 ? base : scatter(level, base, o.placement, r);
                Entity e = spawnOne(level, owner, quest, spec, p, target && n == 0);
                if (e != null) {
                    o.spawnedIds.add(e.getUUID());
                    if (target && n == 0) {
                        o.target = e.getUUID().toString();
                        targetSet = true;
                    }
                }
            }
        }
        if (o.type == Objective.Type.KILL_TARGET && !targetSet) {
            // the target couldn't be created: turn the objective into "kill whatever was spawned"
            o.type = Objective.Type.CLEAR_AREA;
            o.required = Math.max(1, o.spawnedIds.size());
            o.radius = Math.max(o.radius, 32);
        }
        return !o.spawnedIds.isEmpty();
    }

    /** Creates, decorates, tags and adds one quest entity; null if it couldn't be added. */
    @Nullable
    private static Entity spawnOne(ServerLevel level, String owner, Quest quest, CompoundTag spec, BlockPos p, boolean isTarget) {
        Entity e = create(level, spec.getString("type"));
        if (e == null) return null;
        e.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.random.nextFloat() * 360f, 0f);
        if (e instanceof Mob mob) {
            ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(p), MobSpawnType.EVENT, null, null);
            mob.setPersistenceRequired();
        }
        decorate(e, spec, isTarget);
        e.getPersistentData().putString(TAG_OWNER, owner);
        e.getPersistentData().putString(TAG_QUEST, quest.id);
        // the boss bar comes back whenever the boss is (re)loaded (see QuestEvents#onEntityJoin)
        if (isTarget && spec.getBoolean("boss")) e.addTag(BOSS_TAG);
        if (!level.addFreshEntity(e)) return null;
        if (e.getTags().contains(BOSS_TAG)) BossBars.track(e);
        return e;
    }

    /**
     * The KILL_TARGET vanished (despawned or removed by something else, confirmed while its surroundings were
     * loaded): spawns only the target again, where it was last seen. The rest of the group is left alone.
     */
    public static boolean respawnTarget(ServerLevel level, String owner, Quest quest, Objective o) {
        for (int i = 0; i < o.spawns.size(); i++) {
            CompoundTag spec = o.spawns.getCompound(i);
            if (!spec.getBoolean("target")) continue;
            BlockPos p = surface(level, o.pos.getX(), o.pos.getZ());
            if ("structure".equals(o.placement)) {
                BlockPos s = scanDown(level, o.pos.above(2), 8);
                if (s != null) p = s;
            }
            Entity e = spawnOne(level, owner, quest, spec, p, true);
            if (e == null) return false;
            o.spawnedIds.add(e.getUUID());
            o.target = e.getUUID().toString();
            return true;
        }
        return false;
    }

    private static void decorate(Entity e, CompoundTag spec, boolean isTarget) {
        if (spec.contains("name")) {
            e.setCustomName(Quest.readComponent(spec.getString("name")));
            e.setCustomNameVisible(isTarget || spec.getBoolean("boss"));
        }
        if (spec.getBoolean("glow")) e.setGlowingTag(true);
        if (e instanceof LivingEntity le) {
            float hp = spec.contains("hp") ? spec.getFloat("hp") : 1f;
            float dmg = spec.contains("dmg") ? spec.getFloat("dmg") : 1f;
            AttributeInstance maxHealth = le.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null && hp != 1f) maxHealth.setBaseValue(Math.min(1024, maxHealth.getBaseValue() * hp));
            AttributeInstance attack = le.getAttribute(Attributes.ATTACK_DAMAGE);
            if (attack != null && dmg != 1f) attack.setBaseValue(attack.getBaseValue() * dmg);
            le.setHealth(le.getMaxHealth());
        }
        if (e instanceof Mob mob) {
            if (spec.contains("armor")) {
                int color = spec.getInt("armor");
                equip(mob, EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, color));
                equip(mob, EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, color));
                equip(mob, EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, color));
                equip(mob, EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, color));
            }
            if (spec.contains("weapon")) {
                ResourceLocation id = ResourceLocation.tryParse(spec.getString("weapon"));
                Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
                if (item != null && item != Items.AIR) equip(mob, EquipmentSlot.MAINHAND, new ItemStack(item));
            }
        }
    }

    private static void equip(Mob mob, EquipmentSlot slot, ItemStack stack) {
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, 0.02f);
    }

    public static ItemStack dyed(Item item, int color) {
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTagElement("display").putInt("color", color);
        return stack;
    }

    // ------------------------------------------------------------------ placement

    private static BlockPos resolveBase(ServerLevel level, Objective o, RandomSource r) {
        if ("structure".equals(o.placement)) {
            BlockPos s = structureSpot(level, o.pos, r);
            if (s != null) return s;
        }
        if ("near".equals(o.placement)) return surface(level, o.pos.getX(), o.pos.getZ());
        return landNear(level, o.pos, r);
    }

    public static BlockPos surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, Math.max(level.getMinBuildHeight() + 1, y), z);
    }

    /** The surface at {@code pos}, or a nearby loaded land column if that's water. */
    private static BlockPos landNear(ServerLevel level, BlockPos pos, RandomSource r) {
        BlockPos first = surface(level, pos.getX(), pos.getZ());
        if (level.getFluidState(first.below()).isEmpty()) return first;
        for (int i = 0; i < 32; i++) {
            int x = pos.getX() + r.nextInt(81) - 40;
            int z = pos.getZ() + r.nextInt(81) - 40;
            BlockPos probe = new BlockPos(x, pos.getY(), z);
            if (!level.isLoaded(probe)) continue;
            BlockPos s = surface(level, x, z);
            if (level.getFluidState(s.below()).isEmpty()) return s;
        }
        return first;
    }

    /** A free spot inside the structure that starts in the chunk at {@code pos}. */
    @Nullable
    private static BlockPos structureSpot(ServerLevel level, BlockPos pos, RandomSource r) {
        if (!level.isLoaded(pos)) return null;
        LevelChunk chunk = level.getChunkAt(pos);
        for (StructureStart start : chunk.getAllStarts().values()) {
            if (start == null || !start.isValid()) continue;
            BoundingBox box = start.getBoundingBox();
            BlockPos center = box.getCenter();
            for (int i = 0; i < 300; i++) {
                int x = Mth.clamp(center.getX() + r.nextInt(41) - 20, box.minX(), box.maxX());
                int z = Mth.clamp(center.getZ() + r.nextInt(41) - 20, box.minZ(), box.maxZ());
                int y = box.minY() + r.nextInt(Math.max(1, box.getYSpan()));
                BlockPos probe = new BlockPos(x, y, z);
                if (!level.isLoaded(probe)) continue;
                BlockPos found = scanDown(level, probe, 12);
                if (found != null && box.isInside(found)) return found;
            }
        }
        return null;
    }

    /** First spot at or below {@code start} with two free blocks over a sturdy floor. */
    @Nullable
    private static BlockPos scanDown(Level level, BlockPos start, int steps) {
        BlockPos.MutableBlockPos p = start.mutable();
        for (int i = 0; i < steps && p.getY() > level.getMinBuildHeight() + 1; i++, p.move(Direction.DOWN)) {
            if (free(level, p) && free(level, p.above())) {
                BlockPos below = p.below();
                if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return p.immutable();
            }
        }
        return null;
    }

    private static boolean free(Level level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
    }

    private static BlockPos scatter(ServerLevel level, BlockPos base, String placement, RandomSource r) {
        if ("structure".equals(placement)) {
            for (int i = 0; i < 24; i++) {
                BlockPos probe = base.offset(r.nextInt(13) - 6, r.nextInt(5) - 1, r.nextInt(13) - 6);
                if (!level.isLoaded(probe)) continue;
                BlockPos found = scanDown(level, probe, 7);
                if (found != null) return found;
            }
            return base;
        }
        for (int i = 0; i < 8; i++) {
            int x = base.getX() + r.nextInt(13) - 6;
            int z = base.getZ() + r.nextInt(13) - 6;
            BlockPos probe = new BlockPos(x, base.getY(), z);
            if (!level.isLoaded(probe)) continue;
            BlockPos s = surface(level, x, z);
            if (Math.abs(s.getY() - base.getY()) <= 6 && level.getFluidState(s.below()).isEmpty()) return s;
        }
        return base;
    }

    // ------------------------------------------------------------------ boss bars

    /** Boss bars over legendary monsters and dragons, shown to players within 48 blocks. */
    public static final class BossBars {
        private record Bar(ServerBossEvent event, ResourceKey<Level> dim) {
        }

        private static final Map<UUID, Bar> BARS = new HashMap<>();

        private BossBars() {}

        public static void track(Entity e) {
            if (BARS.containsKey(e.getUUID())) return;
            ServerBossEvent event = new ServerBossEvent(e.getDisplayName(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
            BARS.put(e.getUUID(), new Bar(event, e.level().dimension()));
        }

        public static void tick(MinecraftServer server) {
            Iterator<Map.Entry<UUID, Bar>> it = BARS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Bar> entry = it.next();
                Bar bar = entry.getValue();
                ServerLevel level = server.getLevel(bar.dim());
                Entity e = level == null ? null : level.getEntity(entry.getKey());
                if (!(e instanceof LivingEntity le) || !le.isAlive()) {
                    bar.event().removeAllPlayers();
                    it.remove();
                    continue;
                }
                bar.event().setProgress(Mth.clamp(le.getHealth() / Math.max(1f, le.getMaxHealth()), 0f, 1f));
                for (ServerPlayer p : level.players()) {
                    if (p.distanceToSqr(le) < 48 * 48) bar.event().addPlayer(p);
                    else bar.event().removePlayer(p);
                }
                for (ServerPlayer p : new ArrayList<>(bar.event().getPlayers())) {
                    if (p.isRemoved() || p.level() != level) bar.event().removePlayer(p);
                }
            }
        }

        public static void clear() {
            BARS.values().forEach(b -> b.event().removeAllPlayers());
            BARS.clear();
        }
    }
}
