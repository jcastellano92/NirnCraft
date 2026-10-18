package com.skycraft.society;

import com.skycraft.roads.Travelers;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.SettlementPopulation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hard cap on how many people a settlement holds (owner, playtest 4: "why so many people in general?"). Every
 * spawn path used to add to a settlement on its own (anchor retries, caravans, couriers, conversions...); this is the
 * one place that says how many are allowed and trims what is over, whichever path made them:
 * <ul>
 *     <li><b>residents</b>: at most one person per housing-plan slot ({@link Housing#SLOT_KEY}); a second claimant of a
 *     slot is a duplicate and goes; settlers nobody adopted (older saves, other modules' spawns) go when the settlement
 *     holds more people than its plan has slots;</li>
 *     <li><b>visitors</b> (couriers, caravan traders, adventurers, travellers): at most {@link #visitorCap} at once
 *     (2 + a size bonus), and never one that somebody is talking to or trading with.</li>
 * </ul>
 * Called by the settlement tick for the settlements near a player; also asked by {@link VillagerConversion} before it
 * makes one more person. Guards are not counted here (their own caps: housing plan, gate posts, patrols).
 */
public final class SettlementCrowd {
    /** Game time of the last audit per settlement key. */
    private static final Map<String, Long> LAST = new HashMap<>();
    private static final int INTERVAL = 200;

    private SettlementCrowd() {}

    /** Most visitors present at once: 2 plus a bonus for the settlement's size (capital 3, town 1, village 0). */
    public static int visitorCap(int tier) {
        return 2 + switch (tier) {
            case 0 -> 3;
            case 1 -> 1;
            default -> 0;
        };
    }

    /** Whether this person lives and works in the settlement (as opposed to passing through). */
    public static boolean isVisitor(net.minecraft.world.entity.Entity e) {
        var pd = e.getPersistentData();
        if (pd.contains(com.skycraft.quest.story.QuestNpcs.TAG) || pd.contains(com.skycraft.quest.faction.FactionStaff.TAG)) return false;
        if (pd.contains(Travelers.TAG) || Encounters.isTagged(e)) return true;
        if (e instanceof NpcEntity n) {
            NpcRole r = n.role();
            return r == NpcRole.COURIER && !e.getPersistentData().getBooleanOr("skycraft_steward",false)
                    || r == NpcRole.ADVENTURER || r == NpcRole.CARAVAN_TRADER;
        }
        return false;
    }

    /** A settler this system made (or an older save's), as opposed to quest people, followers and spouses. */
    private static boolean isSettler(NpcEntity n) {
        var pd = n.getPersistentData();
        if (Followers.isFollower(n) || Marriage.isSpouse(n) || pd.contains(com.skycraft.quest.QuestSpawner.TAG_QUEST)) return false;
        return pd.contains(Housing.SLOT_KEY) || pd.getBooleanOr("skycraft_settler",false) || com.skycraft.survival.inn.Innkeepers.isInnkeeper(n);
    }

    /** True if the settlement around {@code pos} already holds as many settlers as its plan has slots. */
    public static boolean settlementFull(ServerLevel level, BlockPos pos) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.near(level, pos, 8);
        if (entry.isEmpty() || entry.get().homestead()) return false;
        int cap = SettlementPopulation.housing(entry.get()).bySlot.size();
        if (cap <= 0) return false;
        int n = 0;
        for (NpcEntity npc : around(level, entry.get())) if (isSettler(npc) && !isVisitor(npc)) n++;
        return n >= cap;
    }

    private static List<NpcEntity> around(ServerLevel level, SettlementAnchors.Entry e) {
        int r = e.radius + 12;
        return level.getEntitiesOfClass(NpcEntity.class, new AABB(e.x - r, level.getMinY(), e.z - r, e.x + r,
                (level.getMaxY()+1), e.z + r), n -> n.isAlive() && n.distanceToSqr(e.x, n.getY(), e.z) <= (double) r * r);
    }

    /** Dedupes and trims one settlement (at most once per {@link #INTERVAL} ticks). Returns how many people it removed. */
    public static int audit(ServerLevel level, SettlementAnchors.Entry e) {
        if (e.homestead()) return 0;
        long now = level.getGameTime();
        Long last = LAST.get(e.key);
        if (last != null && now - last < INTERVAL) return 0;
        LAST.put(e.key, now);
        List<NpcEntity> people = around(level, e);
        if (people.isEmpty()) return 0;
        int removed = 0;

        int tier = SettlementPopulation.tier(e);
        boolean hasCourt = Housing.hasCourt(e.key, tier);
        Housing.Plan housingPlan = SettlementPopulation.housing(e);

        // one person per plan slot: the oldest keeps it; prune obsolete court roles in non-court towns
        Map<String, NpcEntity> slots = new HashMap<>();
        boolean hasSlottedInnkeeper = false;
        for (NpcEntity n : people) {
            if (!hasCourt && isSettler(n) && (n.role() == NpcRole.HOUSECARL || n.role() == NpcRole.JARL || n.role() == NpcRole.STEWARD)) {
                n.getPersistentData().putBoolean("skycraft_steward", false);
                if (remove(level, n)) removed++;
                continue;
            }
            String slot = n.getPersistentData().getStringOr(Housing.SLOT_KEY, "");
            if (slot.isEmpty()) continue;
            int hash = slot.indexOf('#');
            if (hash >= 0) {
                try {
                    int idx = Integer.parseInt(slot.substring(hash + 1));
                    if (housingPlan.at(idx) == null) {
                        if (remove(level, n)) removed++;
                        continue;
                    }
                } catch (NumberFormatException ignored) {}
            }
            NpcEntity held = slots.get(slot);
            if (held == null) {
                slots.put(slot, n);
                if (n.role() == NpcRole.INNKEEPER || com.skycraft.survival.inn.Innkeepers.isInnkeeper(n)) {
                    hasSlottedInnkeeper = true;
                }
            } else {
                NpcEntity loser = held.tickCount >= n.tickCount ? n : held;
                slots.put(slot, loser == n ? held : n);
                if (remove(level, loser)) removed++;
            }
        }

        // unadopted settlers beyond the plan's headcount (and any duplicate unslotted innkeeper)
        int cap = housingPlan.bySlot.size();
        List<NpcEntity> adopted = new ArrayList<>();
        List<NpcEntity> loose = new ArrayList<>();
        for (NpcEntity n : people) {
            if (n.isRemoved() || !n.isAlive() || isVisitor(n) || !isSettler(n)) continue;
            if (!n.getPersistentData().contains(Housing.SLOT_KEY) && hasSlottedInnkeeper
                    && (n.role() == NpcRole.INNKEEPER || com.skycraft.survival.inn.Innkeepers.isInnkeeper(n))) {
                if (remove(level, n)) removed++;
                continue;
            }
            (n.getPersistentData().contains(Housing.SLOT_KEY) ? adopted : loose).add(n);
        }
        int over = adopted.size() + loose.size() - Math.max(cap, 0);
        if (over > 0) {
            // furthest from any player first, never someone in the middle of a conversation
            loose.sort((a, b) -> Double.compare(playerDistance(level, b), playerDistance(level, a)));
            for (NpcEntity n : loose) {
                if (over <= 0) break;
                if (remove(level, n)) {
                    removed++;
                    over--;
                }
            }
        }

        // the night: a resident whose hour it is to sleep and who has not gone to bed for a minute is put in it
        // (independent of their goals: a conversation, a tune, a door that will not open must not keep them up)
        nightSweep(level, people);

        // visitors: a handful at a time
        int visitorCap = visitorCap(SettlementPopulation.tier(e));
        List<NpcEntity> visitors = new ArrayList<>();
        for (NpcEntity n : people) if (!n.isRemoved() && n.isAlive() && isVisitor(n)) visitors.add(n);
        if (visitors.size() > visitorCap) {
            visitors.sort((a, b) -> Double.compare(playerDistance(level, b), playerDistance(level, a)));
            int surplus = visitors.size() - visitorCap;
            for (NpcEntity n : visitors) {
                if (surplus <= 0) break;
                if (remove(level, n)) {
                    removed++;
                    surplus--;
                }
            }
        }
        return removed;
    }

    /** Persistent-data key: game time a resident was first seen awake when they should be asleep. */
    private static final String AWAKE_SINCE = "skycraft_awake_since";
    /** How long (ticks) a resident may stay up past their bedtime before the sweep puts them to bed. */
    private static final long BEDTIME_GRACE = 800;

    private static void nightSweep(ServerLevel level, List<NpcEntity> people) {
        if (!Npcs.isNight(level)) return;
        long now = level.getGameTime();
        for (NpcEntity n : people) {
            if (n.isRemoved() || !n.isAlive() || n.isSleeping() || n.getBed() == null || !n.role().townsfolk()) continue;
            var pd = n.getPersistentData();
            if (isVisitor(n) || n.isPrisoner() || n.isConversing() || n.getTarget() != null || Followers.isFollower(n)) continue;
            if (Schedule.activity(n) != Schedule.Activity.SLEEP || NpcPlacement.outAtNight(n)) {
                pd.remove(AWAKE_SINCE);
                continue;
            }
            if (!pd.contains(AWAKE_SINCE)) {
                pd.putLong(AWAKE_SINCE, now);
                continue;
            }
            if (now - pd.getLongOr(AWAKE_SINCE, 0L) < BEDTIME_GRACE) continue;
            if (level.getNearestPlayer(n, 8) != null) continue; // not in front of someone's nose
            if (NpcPlacement.putToBed(n, n.getBed())) pd.remove(AWAKE_SINCE);
        }
    }

    private static double playerDistance(ServerLevel level, NpcEntity n) {
        var p = level.getNearestPlayer(n, 256);
        return p == null ? 1.0e9 : p.distanceTo(n);
    }

    /** Removes a person nobody is talking to (with a puff if a player could see it). */
    private static boolean remove(ServerLevel level, NpcEntity n) {
        if (n.isBusy() || n.isConversing() || Followers.isFollower(n) || n.entityTags().contains("skycraft_keep")) return false;
        // story people, quest people, guild staff and stewards are somebody's content: never trimmed here
        var pd = n.getPersistentData();
        if (pd.contains(com.skycraft.quest.story.QuestNpcs.TAG) || pd.contains(com.skycraft.quest.QuestSpawner.TAG_QUEST)
                || pd.contains(com.skycraft.quest.faction.FactionStaff.TAG) || pd.getBooleanOr("skycraft_steward",false)
                || Marriage.isSpouse(n)) return false;
        if (level.getNearestPlayer(n, 6) != null) return false;
        if (level.getNearestPlayer(n, 40) != null) {
            level.sendParticles(ParticleTypes.POOF, n.getX(), n.getY() + 0.8, n.getZ(), 6, 0.3, 0.5, 0.3, 0.02);
        }
        n.discard();
        return true;
    }

    /** Forgets the audit clocks (server stop). */
    public static void clear() {
        LAST.clear();
    }
}
