package com.skycraft.creatures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.SkyCreature;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Inter-faction hostility, driven by {@code skycraft/creatures/relations.json}: bandits fight Forsworn and
 * necromancers, Falmer fight Dwemer automata, Legion fights Stormcloaks, predators hunt prey, and so on. Each mob
 * looks around once a second (staggered) for an enemy-group mob within {@code warRadius} that it can see, then
 * targets it. Cheap by design: one map lookup per mob per second for mobs with no relations; a bounded box query
 * only for grouped mobs that are healthy, idle, off cooldown and near a player. No wars to the death: a mob below
 * {@code minHealthFraction} drops the war target and cools off, and engages at most {@code maxEngagements} times.
 * Guards, crime and player stealth are untouched (guards keep their own threat logic).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class FactionWars {
    private static final String T_UNTIL = "sky_war_until";
    private static final String T_COUNT = "sky_war_count";
    private static final String T_WAR = "sky_war_active";

    private static boolean loaded;
    private static final Map<String, Integer> GROUP_INDEX = new HashMap<>();
    private static final Map<String, Integer> ID_GROUP = new HashMap<>();
    private static final Map<String, Integer> ROLE_GROUP = new HashMap<>();
    private static final Map<EntityType<?>, Integer> TYPE_GROUP = new IdentityHashMap<>();
    /** chance[a][b] > 0: group a may start a fight with group b. */
    private static float[][] chance = new float[0][0];
    private static boolean[] hasEnemies = new boolean[0];
    private static float minHealth = 0.35f;
    private static int cooldown = 900;
    private static int maxEngagements = 3;

    private FactionWars() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = FactionWars.class.getResourceAsStream("/skycraft/creatures/relations.json")) {
            if (in == null) return;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("minHealthFraction")) minHealth = root.get("minHealthFraction").getAsFloat();
            if (root.has("cooldownTicks")) cooldown = root.get("cooldownTicks").getAsInt();
            if (root.has("maxEngagements")) maxEngagements = root.get("maxEngagements").getAsInt();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("groups").entrySet()) {
                int idx = GROUP_INDEX.size();
                GROUP_INDEX.put(e.getKey(), idx);
                for (JsonElement id : e.getValue().getAsJsonArray()) ID_GROUP.put(id.getAsString(), idx);
            }
            if (root.has("npc_roles")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("npc_roles").entrySet()) {
                    Integer g = GROUP_INDEX.get(e.getValue().getAsString());
                    if (g != null) ROLE_GROUP.put(e.getKey(), g);
                }
            }
            int n = GROUP_INDEX.size();
            chance = new float[n][n];
            hasEnemies = new boolean[n];
            JsonArray rels = root.getAsJsonArray("relations");
            for (JsonElement el : rels) {
                JsonObject r = el.getAsJsonObject();
                Integer a = GROUP_INDEX.get(r.get("a").getAsString());
                Integer b = GROUP_INDEX.get(r.get("b").getAsString());
                if (a == null || b == null) continue;
                float c = r.has("chance") ? r.get("chance").getAsFloat() : 0.5f;
                chance[a][b] = Math.max(chance[a][b], c);
                hasEnemies[a] = true;
                if (!r.has("kind") || !"hunts".equals(r.get("kind").getAsString())) {
                    chance[b][a] = Math.max(chance[b][a], c);
                    hasEnemies[b] = true;
                }
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Faction relations failed to load: {}", ex.toString());
        }
    }

    private static int groupOf(LivingEntity e) {
        if (e instanceof NpcEntity npc) {
            Integer g = ROLE_GROUP.get(npc.role().name());
            return g == null ? -1 : g;
        }
        Integer g = TYPE_GROUP.get(e.getType());
        if (g == null) {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            Integer found = key == null ? null : ID_GROUP.get(key.toString());
            g = found == null ? -1 : found;
            TYPE_GROUP.put(e.getType(), g);
        }
        return g;
    }

    @SubscribeEvent
    public static void onLivingTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || !mob.isAlive()) return;
        if ((mob.tickCount + mob.getId()) % 20 != 0) return;
        if (!CreaturesConfig.FACTION_WARS.get()) return;
        if (!loaded) load();
        int g = groupOf(mob);
        if (g < 0 || !hasEnemies[g]) return;
        long now = mob.level().getGameTime();
        CompoundTag data = mob.getPersistentData();
        boolean weak = mob.getHealth() < mob.getMaxHealth() * minHealth;
        LivingEntity current = mob.getTarget();
        if (current != null) {
            // Break off a war we started once hurt; never touch a player target.
            if (weak && !(current instanceof Player) && data.getBooleanOr(T_WAR, false)) {
                mob.setTarget(null);
                data.putBoolean(T_WAR, false);
                data.putLong(T_UNTIL, now + cooldown);
            }
            return;
        }
        data.putBoolean(T_WAR, false);
        if (weak || mob.isPassenger() || (mob instanceof SkyCreature c && c.isDormant())) return;
        if (now < data.getLongOr(T_UNTIL, 0L) || data.getIntOr(T_COUNT, 0) >= maxEngagements) return;
        // Wars are fought where someone can watch: nothing to simulate with no player near.
        if (mob.level().getNearestPlayer(mob, 64.0) == null) return;
        double r = CreaturesConfig.WAR_RADIUS.get();
        AABB box = mob.getBoundingBox().inflate(r, Math.min(r, 8.0), r);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity other : mob.level().getEntitiesOfClass(LivingEntity.class, box, o -> o != mob && o.isAlive())) {
            if (other instanceof Player || other.hasCustomName()) continue;
            if (mob instanceof NpcEntity && other instanceof NpcEntity) continue; // NpcRole.opposed covers these
            int og = groupOf(other);
            if (og < 0 || chance[g][og] <= 0f) continue;
            double d = mob.distanceToSqr(other);
            if (d > r * r || d >= bestD || !mob.hasLineOfSight(other)) continue;
            if (mob.getRandom().nextFloat() > chance[g][og]) continue;
            best = other;
            bestD = d;
        }
        if (best != null) {
            mob.setTarget(best);
            data.putBoolean(T_WAR, true);
            data.putInt(T_COUNT, data.getIntOr(T_COUNT, 0) + 1);
            data.putLong(T_UNTIL, now + cooldown);
        }
    }
}
