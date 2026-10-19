package com.nirncraft.seas.traffic;

import com.nirncraft.sailing.DeckPhysics;
import com.nirncraft.sailing.ShipDeck;
import com.nirncraft.sailing.ShipEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * What a ship's crew does on deck, where pathfinding can't help (the deck is no part of the world's blocks): walk to
 * places on their own deck between chores, and in a fight close with their enemy across the planks, strike, and leap
 * across to a ship alongside to board it. Added to any mob that crews a ship ({@link #crew}); they only act while the
 * mob is on a deck ({@link DeckPhysics}), so a crewman who swims ashore behaves as usual again.
 */
public final class CrewGoals {
    /** Persistent-data flag on the crews of the sea traffic (they leave with their ship and are never saved). */
    public static final String CREW_TAG = "nirncraft_seas_crew";

    private CrewGoals() {}

    public static void crew(Mob mob) {
        mob.getPersistentData().putBoolean(CREW_TAG, true);
        mob.goalSelector.addGoal(1, new DeckFight(mob));
        mob.goalSelector.addGoal(5, new DeckStroll(mob));
    }

    /** The ship a mob stands on (server side, deck physics). */
    @Nullable
    static ShipEntity deck(Mob mob) {
        return DeckPhysics.shipOf(mob);
    }

    /** Fighting on deck: close in, strike, and board a ship lashed alongside by leaping its rail. */
    static final class DeckFight extends Goal {
        private final Mob mob;
        private int cooldown;
        private int leapCooldown;

        DeckFight(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = mob.getTarget();
            if (t == null || !t.isAlive() || deck(mob) == null) return false;
            return DeckPhysics.combatPos(t).distanceToSqr(mob.position()) < 28 * 28;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity t = mob.getTarget();
            if (t == null) return;
            if (cooldown > 0) cooldown--;
            if (leapCooldown > 0) leapCooldown--;
            Vec3 tp = DeckPhysics.combatPos(t);
            mob.getLookControl().setLookAt(tp.x, tp.y + t.getEyeHeight(), tp.z, 30f, 30f);
            double dx = tp.x - mob.getX(), dz = tp.z - mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            ShipEntity mine = deck(mob);
            ShipEntity theirs = DeckPhysics.deckShip(t);
            if (theirs != null && theirs != mine && d < 7.5 && d > 1.5 && leapCooldown <= 0 && DeckPhysics.onDeck(mob)) {
                // over the rail and onto their deck
                double speed = Math.min(0.95, 0.22 + d * 0.13);
                mob.setDeltaMovement(dx / d * speed, 0.62, dz / d * speed);
                mob.needsSync = true;
                mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                leapCooldown = 40;
                return;
            }
            double reach = mob.getBbWidth() * 0.5 + t.getBbWidth() * 0.5 + 1.6;
            if (d > reach * 0.7) {
                double speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.2 ? 1.0 : 1.3;
                mob.getMoveControl().setWantedPosition(tp.x, tp.y, tp.z, speed);
            }
            if (d <= reach && Math.abs(tp.y - mob.getY()) < 2.5 && cooldown <= 0) {
                mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                mob.doHurtTarget((net.minecraft.server.level.ServerLevel) mob.level(), t);
                cooldown = 20 + mob.getRandom().nextInt(10);
            }
        }
    }

    /** Between fights: wander the own deck now and then, at a walk. */
    static final class DeckStroll extends Goal {
        private final Mob mob;
        private double lx, lz;
        private int ticks;

        DeckStroll(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            ShipEntity ship = deck(mob);
            if (ship == null || mob.getTarget() != null || mob.getRandom().nextInt(120) != 0 || !DeckPhysics.onDeck(mob)) return false;
            ShipDeck.Deck d = ShipDeck.of(ship.shipType);
            for (int i = 0; i < 8; i++) {
                double x = Mth.lerp(mob.getRandom().nextDouble(), d.minX + 0.8, d.maxX - 0.8);
                double z = Mth.lerp(mob.getRandom().nextDouble(), d.minZ + 1.5, d.maxZ - 1.5);
                float floor = d.floorAt(x, z, d.mainDeck + 0.1);
                if (!Float.isNaN(floor) && Math.abs(floor - d.mainDeck) < 0.6f) {
                    lx = x;
                    lz = z;
                    ticks = 0;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            ShipEntity ship = deck(mob);
            if (ship == null || mob.getTarget() != null || ++ticks > 100) return false;
            Vec3 w = ship.toWorld(lx, 0, lz);
            return w.distanceToSqr(mob.getX(), w.y, mob.getZ()) > 0.6;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ShipEntity ship = deck(mob);
            if (ship == null) return;
            Vec3 w = ship.toWorld(lx, ShipDeck.of(ship.shipType).mainDeck, lz);
            mob.getMoveControl().setWantedPosition(w.x, w.y, w.z, 0.6);
        }
    }
}
