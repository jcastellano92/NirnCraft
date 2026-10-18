package com.skycraft.society.entity;

import com.skycraft.society.Barks;
import com.skycraft.society.NpcRole;
import com.skycraft.society.NpcPlacement;
import com.skycraft.society.Npcs;
import com.skycraft.society.Reputation;
import com.skycraft.society.Schedule;
import com.skycraft.society.Workplaces;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** The AI goals of society NPCs. Each checks the NPC's role/style, so every NPC carries the same goal set. */
final class NpcGoals {
    private NpcGoals() {}

    // ------------------------------------------------------------------ fleeing

    /** Non-combatants run when hurt unless they choose to fight back. */
    static final class Panic extends PanicGoal {
        private final NpcEntity npc;

        Panic(NpcEntity npc) {
            super(npc, 1.3D);
            this.npc = npc;
        }

        @Override
        public boolean canUse() {
            if (npc.canFight() && npc.getHealth() > npc.getMaxHealth() * 0.25f) return false;
            return super.canUse();
        }
    }

    /** Non-combatants keep away from monsters and villains on the attack, or feared players until they leave. */
    static final class Avoid extends AvoidEntityGoal<LivingEntity> {
        private final NpcEntity npc;

        Avoid(NpcEntity npc) {
            super(npc, LivingEntity.class, 12.0F, 0.9D, 1.3D, e -> threat(npc, e));
            this.npc = npc;
        }

        private static boolean threat(NpcEntity npc, LivingEntity e) {
            if (npc.getFearedPlayer() != null && e.getUUID().equals(npc.getFearedPlayer())) return true;
            if (e instanceof NpcEntity o) return o.getTarget() == npc || (o.role().villain && o.getTarget() != null);
            if (e instanceof Enemy) return !(e instanceof net.minecraft.world.entity.Mob m) || !m.isNoAi();
            return false;
        }

        @Override
        public boolean canUse() {
            if (npc.getFearedPlayer() != null) return super.canUse();
            if (npc.canFight() && npc.getHealth() > npc.getMaxHealth() * 0.25f) return false;
            if (npc.role().combatant || npc.isPrisoner()) return false;
            if ((npc.tickCount + npc.getId()) % 6 != 0) return false;
            return super.canUse();
        }
    }

    /** Whether the NPC is currently standing in or immediately beside a doorframe. */
    static boolean isInDoorway(NpcEntity npc) {
        var level = npc.level();
        BlockPos pos = npc.blockPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) continue;
                BlockPos p = pos.offset(dx, 0, dz);
                if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                        || level.getBlockState(p.above()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                    if (npc.distanceToSqr(p.getX() + 0.5, npc.getY(), p.getZ() + 0.5) < 2.25D) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A door-opener with a cooldown: an NPC wedged in a doorway can't strobe a shop door open and shut every tick. */
    static final class Door extends net.minecraft.world.entity.ai.goal.OpenDoorGoal {
        /** Game ticks before the same NPC may work a door again. Tuning knob. */
        private static final int COOLDOWN = 40;
        private final NpcEntity npc;
        private long readyAt;
        private int openTicks;

        Door(NpcEntity npc) {
            super(npc, true);
            this.npc = npc;
        }

        @Override
        public boolean canUse() {
            return !npc.isBusy() && !npc.getNavigation().isDone() && npc.level().getGameTime() >= readyAt && super.canUse();
        }

        @Override
        public void start() {
            openTicks = 0;
            super.start();
        }

        @Override
        public void tick() {
            openTicks++;
            super.tick();
        }

        @Override
        public boolean canContinueToUse() {
            if (openTicks < 60 && doorPos != null
                    && npc.distanceToSqr(doorPos.getX() + 0.5, npc.getY(), doorPos.getZ() + 0.5) < 1.69D) {
                return true;
            }
            return super.canContinueToUse();
        }

        @Override
        public void stop() {
            if (doorPos == null || npc.distanceToSqr(doorPos.getX() + 0.5, npc.getY(), doorPos.getZ() + 0.5) >= 1.21D) {
                super.stop();
            }
            readyAt = npc.level().getGameTime() + COOLDOWN;
        }
    }

    /** Dissolves stacks: an NPC standing inside (or on) another is nudged apart, so gates and doorways never jam. */
    static final class Spread extends Goal {
        /** Blocks within which two idle NPCs are pushed apart. Tuning knob. */
        private static final double PERSONAL_SPACE = 1.0D;
        private final NpcEntity npc;

        Spread(NpcEntity npc) {
            this.npc = npc;
        }

        @Override
        public boolean canUse() {
            if ((npc.tickCount + npc.getId()) % 8 != 0 || npc.isConversing() || npc.isSleeping() || npc.getTarget() != null) return false;
            for (NpcEntity o : npc.level().getEntitiesOfClass(NpcEntity.class, npc.getBoundingBox().inflate(PERSONAL_SPACE, 0.6, PERSONAL_SPACE),
                    x -> x != npc && x.isAlive() && !x.isSleeping())) {
                double dx = npc.getX() - o.getX();
                double dz = npc.getZ() - o.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 0.05) { // exactly stacked: pick a stable direction from the ids
                    double a = (npc.getId() * 2.399963D);
                    dx = Math.cos(a);
                    dz = Math.sin(a);
                    d = 1.0D;
                }
                double push = 0.12D * (PERSONAL_SPACE - Math.min(PERSONAL_SPACE, d)) + 0.05D;
                npc.setDeltaMovement(npc.getDeltaMovement().add(dx / d * push, 0.0D, dz / d * push));
                npc.syncVelocity = true;
                break;
            }
            return false; // pure side effect: never takes the goal slot
        }
    }

    /** Non-combatant townsfolk run for home when something hostile (a dragon, a raider band) is close. */
    static final class Shelter extends Goal {
        /** Blocks within which a hostile sends civilians indoors. Tuning knob. */
        private static final double RANGE = 36.0D;
        private final NpcEntity npc;
        private BlockPos refuge;
        private int ticks;

        Shelter(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (npc.isBusy() || npc.getHome() == null || !npc.role().civilian || npc.role().combatant) return false;
            if ((npc.tickCount + npc.getId()) % 20 != 0) return false;
            var threats = npc.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, npc.getBoundingBox().inflate(RANGE, 24, RANGE),
                    m -> m instanceof net.minecraft.world.entity.monster.Enemy && m.isAlive() && !m.isNoAi() && m.getTarget() != null);
            if (threats.isEmpty()) return false;
            refuge = npc.getHome();
            return npc.distanceToSqr(refuge.getX() + 0.5, refuge.getY(), refuge.getZ() + 0.5) > 9.0D;
        }

        @Override
        public boolean canContinueToUse() {
            return refuge != null && !npc.isBusy() && ticks < 400
                    && npc.distanceToSqr(refuge.getX() + 0.5, refuge.getY(), refuge.getZ() + 0.5) > 6.0D;
        }

        @Override
        public void start() {
            ticks = 0;
            npc.getNavigation().moveTo(refuge.getX() + 0.5, refuge.getY(), refuge.getZ() + 0.5, 1.15D);
        }

        @Override
        public void tick() {
            ticks++;
            if (npc.getNavigation().isDone()) npc.getNavigation().moveTo(refuge.getX() + 0.5, refuge.getY(), refuge.getZ() + 0.5, 1.15D);
        }

        @Override
        public void stop() {
            refuge = null;
        }
    }

    // ------------------------------------------------------------------ fighting

    static final class Melee extends MeleeAttackGoal {
        private final NpcEntity npc;
        private int spellCooldown = 40;
        private int spellWindup = 0;
        private String queuedSpell = "";

        Melee(NpcEntity npc) {
            super(npc, 1.1D, true);
            this.npc = npc;
        }

        @Override
        public boolean canUse() {
            return npc.style() == NpcEntity.Style.MELEE && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return npc.style() == NpcEntity.Style.MELEE && super.canContinueToUse();
        }

        @Override
        public void stop() {
            super.stop();
            if (spellWindup > 0) {
                spellWindup = 0;
                npc.setCasting(false);
            }
        }

        @Override
        public void tick() {
            super.tick();
            LivingEntity target = npc.getTarget();
            NpcRole role = npc.role();
            if (target == null || (role != NpcRole.THALMOR && role != NpcRole.VAMPIRE && role != NpcRole.PRIEST)) return;
            if (spellWindup > 0) {
                if (--spellWindup == 0) {
                    String toCast = queuedSpell;
                    npc.setCasting(false);
                    if (npc.getSensing().hasLineOfSight(target)) {
                        npc.castSpell(target, toCast);
                    }
                    spellCooldown = 55 + npc.getRandom().nextInt(45);
                }
                return;
            }
            boolean needsSelfHeal = npc.getHealth() < npc.getMaxHealth() * 0.5f;
            if (--spellCooldown <= 0 && (needsSelfHeal || (npc.distanceToSqr(target) > 25 && npc.getSensing().hasLineOfSight(target)))) {
                queuedSpell = npc.chooseCombatSpell(target);
                spellWindup = 10;
                npc.startSpellWindup(queuedSpell);
            }
        }
    }

    /** Draws and looses arrows like a skeleton, keeping some distance. */
    static final class Bow extends Goal {
        private final NpcEntity npc;
        private int seeTime;
        private int cooldown;

        Bow(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = npc.getTarget();
            return t != null && t.isAlive() && npc.style() == NpcEntity.Style.BOW;
        }

        @Override
        public void start() {
            npc.setAggressive(true);
        }

        @Override
        public void stop() {
            npc.setAggressive(false);
            seeTime = 0;
            npc.stopUsingItem();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = npc.getTarget();
            if (t == null) return;
            double d = npc.distanceToSqr(t);
            boolean sees = npc.getSensing().hasLineOfSight(t);
            if (sees) seeTime = Math.max(0, seeTime) + 1;
            else seeTime = Math.min(0, seeTime) - 1;
            if (d <= 15 * 15 && seeTime >= 20) {
                npc.getNavigation().stop();
                if (d < 16) npc.getMoveControl().strafe(-0.5F, 0F);
            } else {
                npc.getNavigation().moveTo(t, 1.0D);
            }
            npc.getLookControl().setLookAt(t, 30.0F, 30.0F);
            if (npc.isUsingItem()) {
                if (!sees && seeTime < -60) {
                    npc.stopUsingItem();
                } else if (sees) {
                    int used = npc.getTicksUsingItem();
                    if (used >= 20) {
                        npc.stopUsingItem();
                        npc.performRangedAttack(t, BowItem.getPowerForTime(used));
                        cooldown = 25 + npc.getRandom().nextInt(15);
                    }
                }
            } else if (--cooldown <= 0 && seeTime >= -60 && npc.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) {
                npc.startUsingItem(InteractionHand.MAIN_HAND);
            }
        }
    }

    /** Mages and necromancers: keep at range, wind up, cast Skycraft spells (Destruction, Restoration, Alteration, Conjuration). */
    static final class Caster extends Goal {
        private final NpcEntity npc;
        private int cooldown;
        private int windup;
        private String queuedSpell = "firebolt";

        Caster(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = npc.getTarget();
            return t != null && t.isAlive() && npc.style() == NpcEntity.Style.CASTER;
        }

        @Override
        public void start() {
            npc.setAggressive(true);
            cooldown = 8;
            windup = 0;
            LivingEntity t = npc.getTarget();
            if (t != null && npc.role() == NpcRole.NECROMANCER && !npc.hasRaisedDead()) {
                npc.castSpell(t, "raise_zombie");
            }
        }

        @Override
        public void stop() {
            npc.setAggressive(false);
            npc.setCasting(false);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = npc.getTarget();
            if (t == null) return;
            double d = npc.distanceToSqr(t);
            boolean sees = npc.getSensing().hasLineOfSight(t);
            npc.getLookControl().setLookAt(t, 30.0F, 30.0F);
            if (d > 14 * 14 || !sees) {
                npc.getNavigation().moveTo(t, 1.0D);
            } else if (d < 5 * 5) {
                Vec3 away = npc.position().subtract(t.position());
                if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
                Vec3 to = npc.position().add(away.normalize().scale(6));
                npc.getNavigation().moveTo(to.x, to.y, to.z, 1.15D);
            } else if (npc.tickCount % 10 == 0) {
                npc.getNavigation().stop();
            }
            if (windup > 0) {
                if (--windup == 0) {
                    String spellToCast = queuedSpell;
                    npc.setCasting(false);
                    if (sees && d < 22 * 22) npc.castSpell(t, spellToCast);
                    cooldown = 32 + npc.getRandom().nextInt(26);
                }
            } else if (--cooldown <= 0 && sees && d < 18 * 18) {
                queuedSpell = npc.chooseCombatSpell(t);
                windup = 14;
                npc.startSpellWindup(queuedSpell);
            }
        }
    }

    // ------------------------------------------------------------------ traveling

    /** Hunts or seeks a player, follows a group leader, or walks to a destination. Steers about once a second. */
    static final class Travel extends Goal {
        private final NpcEntity npc;
        private int cooldown;
        private int lost;
        private int stuck;
        private double lastX, lastZ;

        Travel(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (npc.isBusy() || npc.getTarget() != null || npc.isPassenger()) return false;
            return npc.getHuntTarget() != null || npc.getSeekTarget() != null || npc.getLeader() != null || npc.getDestination() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            cooldown = 0;
        }

        @Override
        public void stop() {
            npc.getNavigation().stop();
        }

        @Override
        public void tick() {
            // goals without requiresUpdateEveryTick() tick every other game tick: 10 calls ~ 1 second
            if (--cooldown > 0) return;
            cooldown = 10;
            if (!(npc.level() instanceof ServerLevel level)) return;
            if (hunt(level) || seek(level) || follow(level)) return;
            walk(level);
        }

        private boolean hunt(ServerLevel level) {
            UUID id = npc.getHuntTarget();
            if (id == null) return false;
            Player p = level.getPlayerByUUID(id);
            if (p == null || !p.isAlive() || p.isSpectator() || p.distanceToSqr(npc) > 160 * 160) return false;
            if (p.distanceToSqr(npc) < 20 * 20 && npc.hasLineOfSight(p) && npc.isHostileTo(p)) {
                npc.setTarget(p);
                return true;
            }
            // a sneaking player the hunter cannot see up close is not followed by their live position
            if (p.distanceToSqr(npc) < 48 * 48 && com.skycraft.combat.Stealth.hiddenFrom(npc, p)) return false;
            npc.getNavigation().moveTo(p, 1.0D);
            return true;
        }

        private boolean seek(ServerLevel level) {
            UUID id = npc.getSeekTarget();
            if (id == null) return false;
            Player p = level.getPlayerByUUID(id);
            if (p == null || !p.isAlive() || p.isSpectator() || p.distanceToSqr(npc) > 200 * 200) return false;
            if (p.distanceToSqr(npc) < 3.2 * 3.2) {
                npc.getNavigation().stop();
                Npcs.arrive(npc, p);
                return true;
            }
            npc.getNavigation().moveTo(p, 0.95D);
            return true;
        }

        private boolean follow(ServerLevel level) {
            UUID id = npc.getLeader();
            if (id == null) return false;
            Entity leader = level.getEntity(id);
            if (leader instanceof LivingEntity l && l.isAlive() && l.distanceToSqr(npc) < 48 * 48) {
                lost = 0;
                double keep = npc.isPrisoner() ? 2.5 : 4.0;
                double d = l.distanceToSqr(npc);
                if (d > keep * keep) npc.getNavigation().moveTo(l, d > 144 ? 1.05D : 0.8D);
                else npc.getNavigation().stop();
                if (npc.getDestination() == null && leader instanceof NpcEntity ln && ln.getDestination() != null) {
                    npc.setDestination(ln.getDestination());
                }
                return true;
            }
            if (++lost >= 5) {
                npc.setLeader(null); // the leader is gone: go on alone
                lost = 0;
            }
            return true;
        }

        private void walk(ServerLevel level) {
            BlockPos dest = npc.getDestination();
            if (dest == null) return;
            double dx = dest.getX() + 0.5 - npc.getX();
            double dz = dest.getZ() + 0.5 - npc.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 6) {
                npc.setDestination(null);
                return;
            }
            double moved = (npc.getX() - lastX) * (npc.getX() - lastX) + (npc.getZ() - lastZ) * (npc.getZ() - lastZ);
            lastX = npc.getX();
            lastZ = npc.getZ();
            stuck = moved < 0.25 ? stuck + 1 : 0;
            if (stuck > 30) {
                npc.setDestination(null); // can't get there: give up and wander
                stuck = 0;
                return;
            }
            double step = Math.min(16, dist);
            double jitter = stuck > 3 ? (npc.getRandom().nextDouble() - 0.5) * 12 : 0;
            int x = (int) Math.floor(npc.getX() + dx / dist * step + jitter);
            int z = (int) Math.floor(npc.getZ() + dz / dist * step - jitter);
            BlockPos probe = new BlockPos(x, npc.getBlockY(), z);
            if (!level.isLoaded(probe)) return;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            npc.getNavigation().moveTo(x + 0.5, y, z + 0.5, 0.75D);
        }
    }

    // ------------------------------------------------------------------ work

    /**
     * Jobs: miners hack at rock, lumberjacks chop, farmers tend crops, smiths hammer at the forge, fishers fish,
     * shopkeepers mind the counter, bards play, priests heal. Settled townsfolk only work during their routine's
     * working hours and at their workplace (the Routine goal walks them there).
     */
    static final class Work extends Goal {
        private static final int[] SCALE = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};

        private final NpcEntity npc;
        private int cooldown = 60;
        private int ticks;
        @Nullable
        private BlockPos spot;
        @Nullable
        private LivingEntity patient;
        private final List<Integer> melody = new ArrayList<>();
        private Holder<SoundEvent> instrument = SoundEvents.NOTE_BLOCK_GUITAR;
        private int noteIndex;

        Work(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (npc.getTarget() != null || npc.isBusy() || npc.isSleeping() || isInDoorway(npc)) return false;
            if (npc.role() == NpcRole.BARD && npc.takeSongRequest()) return true;
            if (--cooldown > 0) return false;
            cooldown = 100 + npc.getRandom().nextInt(100);
            NpcRole role = npc.role();
            boolean settled = npc.getHome() != null && com.skycraft.society.SocietyConfig.ROUTINES.get() && role.townsfolk();
            BlockPos work = null;
            if (settled) {
                if (npc.activity != Schedule.Activity.WORK) return role == NpcRole.PRIEST && (patient = findPatient()) != null;
                work = Workplaces.workplaceOf(npc);
                if (work != null && Workplaces.kindFor(role) != null) {
                    if (npc.distanceToSqr(work.getX() + 0.5, npc.getY(), work.getZ() + 0.5) > 5.0 * 5.0) {
                        return false;
                    }
                    if (npc.level().isLoaded(work) && !npc.level().canSeeSky(work.above()) && npc.level().canSeeSky(npc.blockPosition().above())) {
                        return false;
                    }
                }
            }
            return switch (role) {
                case MINER -> (spot = findBlock(true)) != null;
                case LUMBERJACK -> (spot = findBlock(false)) != null;
                case FARMER -> (spot = findCrop()) != null;
                case BLACKSMITH, BUTCHER, INNKEEPER -> (spot = work != null ? work : npc.blockPosition()) != null;
                case MERCHANT, APOTHECARY, SCHOLAR, MAGE, STEWARD, JARL, TRAINER -> (spot = work != null ? work : npc.blockPosition()) != null;
                case FISHER -> (spot = findWater()) != null;
                case BARD -> audience() && npc.getRandom().nextInt(3) == 0;
                case PRIEST -> (patient = findPatient()) != null;
                default -> false;
            };
        }

        @Override
        public boolean canContinueToUse() {
            return ticks > 0 && !npc.isBusy() && npc.getTarget() == null && (npc.role() != NpcRole.PRIEST || patient != null && patient.isAlive());
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            switch (npc.role()) {
                case MINER, LUMBERJACK, FARMER, BLACKSMITH, BUTCHER -> ticks = 120 + npc.getRandom().nextInt(80);
                case MERCHANT, APOTHECARY, SCHOLAR, MAGE, STEWARD, JARL, INNKEEPER, TRAINER -> ticks = 200 + npc.getRandom().nextInt(200);
                case FISHER -> ticks = 300 + npc.getRandom().nextInt(200);
                case BARD -> {
                    ticks = 200;
                    composeSong();
                    npc.setPlaying(true);
                    if (npc.getRandom().nextBoolean()) com.skycraft.society.Chatter.sing(npc, npc.getRandom().nextInt(com.skycraft.society.Chatter.SONGS));
                    npc.getNavigation().stop();
                }
                case PRIEST -> ticks = 200;
                default -> ticks = 0;
            }
        }

        @Override
        public void stop() {
            npc.setPlaying(false);
            npc.setCasting(false);
            spot = null;
            patient = null;
            cooldown = 300 + npc.getRandom().nextInt(500);
        }

        @Override
        public void tick() {
            ticks--;
            if (!(npc.level() instanceof ServerLevel level)) return;
            switch (npc.role()) {
                case MINER, LUMBERJACK, FARMER -> labor(level);
                case BLACKSMITH -> smith(level);
                case BUTCHER, INNKEEPER -> chores(level);
                case MERCHANT, APOTHECARY, SCHOLAR, MAGE, STEWARD, JARL, TRAINER -> attend(level);
                case FISHER -> fish(level);
                case BARD -> play(level);
                case PRIEST -> heal(level);
                default -> ticks = 0;
            }
        }

        // -------- miners & lumberjacks

        @Nullable
        private BlockPos findBlock(boolean stone) {
            BlockPos base = npc.blockPosition();
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-5, 0, -5), base.offset(5, 2, 5))) {
                BlockState s = npc.level().getBlockState(p);
                boolean match = stone ? (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Tags.Blocks.ORES)) : s.is(BlockTags.LOGS);
                if (!match) continue;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (npc.level().isEmptyBlock(p.relative(dir))) {
                        found.add(p.immutable());
                        break;
                    }
                }
                if (found.size() >= 8) break;
            }
            return found.isEmpty() ? null : found.get(npc.getRandom().nextInt(found.size()));
        }

        private static long laborWindow;
        private static int laborCount;

        /** A global budget: at most 3 labor sounds per second in a level. */
        private static boolean laborSoundAllowed(ServerLevel level) {
            long t = level.getGameTime() / 20;
            if (t != laborWindow) {
                laborWindow = t;
                laborCount = 0;
            }
            return ++laborCount <= 3;
        }

        private void labor(ServerLevel level) {
            if (spot == null) {
                ticks = 0;
                return;
            }
            BlockState state = level.getBlockState(spot);
            if (state.isAir()) {
                ticks = 0;
                return;
            }
            double d = npc.distanceToSqr(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
            npc.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
            if (d > 2.8 * 2.8) {
                if (npc.tickCount % 20 == 0) npc.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.6D);
                return;
            }
            npc.getNavigation().stop();
            if (ticks % 14 == 0) {
                npc.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
                // The hit sound of a crop / farmland / leaf is a grass or gravel scuff: every 0.7 s per worker it was the
                // "loud shuffling and rustling" of playtest 2. Now one soft sound every ~2 s, only with a player within
                // 20 blocks, and at most a few per second across all workers.
                if (ticks % 42 == 0 && laborSoundAllowed(level)) {
                    boolean soft = state.is(BlockTags.CROPS) || state.getBlock() instanceof net.minecraft.world.level.block.FarmlandBlock
                            || state.is(BlockTags.LEAVES);
                    if (level.getNearestPlayer(npc, 20) != null) {
                        level.playSound(null, spot, state.getSoundType(level, spot, npc).getHitSound(), SoundSource.NEUTRAL,
                                soft ? 0.18f : 0.5f, 0.9f);
                    }
                }
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), spot.getX() + 0.5, spot.getY() + 0.6,
                        spot.getZ() + 0.5, 4, 0.25, 0.25, 0.25, 0.05);
            }
        }

        // -------- farmers, smiths, shopkeepers, fishers

        /** A crop or farmland near the NPC (small box scan, a few times a minute at most). */
        @Nullable
        private BlockPos findCrop() {
            BlockPos base = npc.blockPosition();
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-6, -1, -6), base.offset(6, 1, 6))) {
                BlockState s = npc.level().getBlockState(p);
                if (s.is(BlockTags.CROPS) || s.getBlock() instanceof net.minecraft.world.level.block.FarmlandBlock) {
                    found.add(p.immutable());
                    if (found.size() >= 8) break;
                }
            }
            return found.isEmpty() ? null : found.get(npc.getRandom().nextInt(found.size()));
        }

        @Nullable
        private BlockPos findWater() {
            BlockPos base = npc.blockPosition();
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-6, -2, -6), base.offset(6, 0, 6))) {
                if (npc.level().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER) && npc.level().isEmptyBlock(p.above())) {
                    return p.immutable();
                }
            }
            return null;
        }

        /** Hammering at the forge: ringing anvil, sparks. */
        private void smith(ServerLevel level) {
            if (spot == null) {
                ticks = 0;
                return;
            }
            if (!near(spot, 2.6)) return;
            npc.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 0.8, spot.getZ() + 0.5);
            if (ticks % 16 == 0) {
                npc.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
                level.playSound(null, spot, SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.35f, 0.9f + npc.getRandom().nextFloat() * 0.3f);
                level.sendParticles(ParticleTypes.CRIT, spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5, 6, 0.2, 0.1, 0.2, 0.15);
                if (npc.getRandom().nextInt(3) == 0) {
                    level.sendParticles(ParticleTypes.SMALL_FLAME, spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5, 2, 0.15, 0.1, 0.15, 0.01);
                }
            }
        }

        /** Butchers chop, innkeepers wipe the counter and pour. */
        private void chores(ServerLevel level) {
            if (spot == null) {
                ticks = 0;
                return;
            }
            BlockPos seat = findSeat(npc, level, spot);
            BlockPos post = seat != null ? seat : spot;
            if (!near(post, 1.75)) return;
            Player p = level.getNearestPlayer(npc, 6);
            if (p != null && npc.getRandom().nextInt(3) > 0) npc.getLookControl().setLookAt(p, 30f, 30f);
            else if (seat != null) faceSeat(npc, level, seat);
            else faceStation(npc, level, post);
            if (ticks % 40 == 0) {
                npc.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
                level.playSound(null, npc.blockPosition(), npc.role() == NpcRole.BUTCHER ? SoundEvents.AXE_STRIP.value() : SoundEvents.BOTTLE_FILL,
                        SoundSource.NEUTRAL, 0.4f, 1.0f);
            }
        }

        /** Shopkeepers mind the counter and greet whoever comes in. */
        private void attend(ServerLevel level) {
            if (spot == null) {
                ticks = 0;
                return;
            }
            BlockPos seat = findSeat(npc, level, spot);
            BlockPos post = seat != null ? seat : spot;
            if (!near(post, 1.75)) return;
            Player p = level.getNearestPlayer(npc, 7);
            if (p != null && !p.isSpectator()) npc.getLookControl().setLookAt(p, 30f, 30f);
            else {
                if (seat != null) faceSeat(npc, level, seat);
                else faceStation(npc, level, post);
                if (ticks % 60 == 0) npc.swing(InteractionHand.OFF_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
            }
        }

        /** Line in the water, the occasional splash. */
        private void fish(ServerLevel level) {
            if (spot == null) {
                ticks = 0;
                return;
            }
            if (!near(spot, 3.5)) return;
            npc.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
            if (ticks % 50 == 0) {
                npc.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
                level.sendParticles(ParticleTypes.FISHING, spot.getX() + 0.5, spot.getY() + 0.9, spot.getZ() + 0.5, 4, 0.2, 0, 0.2, 0.02);
                if (npc.getRandom().nextInt(4) == 0) level.playSound(null, spot, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.3f, 1.0f);
            }
        }

        /** Walks up to {@code pos}; true once within {@code reach}. */
        private boolean near(BlockPos pos, double reach) {
            double d = npc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d > reach * reach) {
                if (npc.tickCount % 20 == 0) npc.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6D);
                return false;
            }
            npc.getNavigation().stop();
            return true;
        }

        // -------- bards

        private boolean audience() {
            return npc.level().getNearestPlayer(npc, 16) != null;
        }

        private void composeSong() {
            if (!(npc.getMainHandItem().getItem() instanceof com.skycraft.audio.InstrumentItem)) {
                npc.setItemInHand(InteractionHand.MAIN_HAND, com.skycraft.audio.BardPerformance.instrumentFor(npc));
            }
            melody.clear();
            melody.addAll(com.skycraft.audio.BardPerformance.composeMelody(npc));
            instrument = com.skycraft.audio.BardPerformance.soundFor(npc);
            noteIndex = 0;
            ticks = melody.size() * 5 + 10;
        }

        private void play(ServerLevel level) {
            Player listener = level.getNearestPlayer(npc, 16);
            if (listener != null) npc.getLookControl().setLookAt(listener, 20f, 20f);
            if (ticks % 5 != 0 || noteIndex >= melody.size()) return;
            int step = noteIndex++;
            int note = melody.get(step);
            if (note < 0) return;
            com.skycraft.audio.BardPerformance.playStep(level, npc, note, step);
            if (noteIndex % 2 == 0) npc.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
        }

        // -------- priests

        @Nullable
        private LivingEntity findPatient() {
            for (LivingEntity e : npc.level().getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(8),
                    x -> x != npc && x.isAlive() && x.getHealth() < x.getMaxHealth() * 0.8f)) {
                if (e instanceof Player p) {
                    if (!p.isSpectator() && Reputation.get(p, Reputation.TOWNSFOLK) > -20) return e;
                } else if (e instanceof AbstractVillager || (e instanceof NpcEntity o && !o.isHostileTo(npc) && o.getTarget() == null)) {
                    return e;
                }
            }
            return null;
        }

        private void heal(ServerLevel level) {
            if (patient == null) {
                ticks = 0;
                return;
            }
            npc.getLookControl().setLookAt(patient, 30f, 30f);
            if (npc.distanceToSqr(patient) > 9) {
                if (npc.tickCount % 20 == 0) npc.getNavigation().moveTo(patient, 0.7D);
                return;
            }
            npc.getNavigation().stop();
            npc.setCasting(true);
            if (ticks % 20 == 0) {
                patient.heal(patient instanceof Player ? 4.0f : 6.0f);
                level.sendParticles(ParticleTypes.HEART, patient.getX(), patient.getY() + patient.getBbHeight() + 0.3, patient.getZ(),
                        3, 0.3, 0.2, 0.3, 0.02);
                level.playSound(null, patient.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.8f, 1.4f);
                if (patient instanceof Player) Barks.sayLine(npc, "priest_heal");
                if (patient.getHealth() >= patient.getMaxHealth() - 0.01f) {
                    npc.setCasting(false);
                    ticks = 0;
                }
            }
        }
    }

    // ------------------------------------------------------------------ followers

    /** Whether a follower has a live enemy close enough to fight (then the fighting goals take over). */
    static boolean fighting(NpcEntity npc) {
        LivingEntity t = npc.getTarget();
        return t != null && t.isAlive() && npc.distanceToSqr(t) <= 24 * 24;
    }

    /**
     * Contract 11: A follower stays with their player or waits when told. It yields to the melee/bow/caster goals
     * while it has a live target nearby ({@link FollowerDefend} picks that target).
     */
    static final class Follower extends Goal {
        private final NpcEntity npc;
        private int checkCooldown;

        Follower(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return !npc.isBusy() && com.skycraft.society.Followers.isFollower(npc) && !fighting(npc);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            if (--checkCooldown > 0) return;
            checkCooldown = 10;
            if (!(npc.level() instanceof ServerLevel level)) return;
            UUID ownerId = com.skycraft.society.Followers.getOwnerId(npc);
            if (ownerId == null) return;
            Player owner = level.getPlayerByUUID(ownerId);
            if (owner == null || !owner.isAlive()) return;
            // one follower per player, even across restarts: a stale one lets go here
            if (!com.skycraft.society.Followers.validate(npc, owner)) return;
            // scale with the owner/region as they level up (no-op unless the level changed)
            if (npc.tickCount % 200 < 20) com.skycraft.society.Followers.rescale(npc, owner, false);
            // a far-away target isn't worth leaving the owner for
            if (npc.getTarget() != null && !fighting(npc)) npc.setTarget(null);

            if (com.skycraft.society.Followers.isWaiting(npc)) {
                npc.getNavigation().stop();
                return;
            }

            double distSq = npc.distanceToSqr(owner);
            // Teleport if too far (> 36 blocks away and owner is on solid ground)
            if (distSq > 36 * 36 && owner.onGround()) {
                BlockPos target = owner.blockPosition().offset(npc.getRandom().nextInt(3) - 1, 0, npc.getRandom().nextInt(3) - 1);
                if (level.getBlockState(target).isAir() && level.getBlockState(target.above()).isAir()) {
                    npc.snapTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, npc.getYRot(), npc.getXRot());
                    npc.getNavigation().stop();
                    return;
                }
            }

            // Follow distance: keep within 3-4 blocks
            if (distSq > 5 * 5) {
                npc.getNavigation().moveTo(owner, distSq > 12 * 12 ? 1.25D : 1.0D);
            } else if (distSq < 2.5 * 2.5) {
                npc.getNavigation().stop();
            }
        }
    }

    /**
     * Target goal: a following (not waiting) follower engages whoever hurt its owner, or whom the owner hit, in the
     * last 10 s (see {@link com.skycraft.society.Followers#defendTarget}). Never players or allies.
     */
    static final class FollowerDefend extends Goal {
        private final NpcEntity npc;
        @Nullable
        private LivingEntity pick;

        FollowerDefend(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if ((npc.tickCount + npc.getId()) % 10 != 0) return false;
            if (!com.skycraft.society.Followers.isFollower(npc) || com.skycraft.society.Followers.isWaiting(npc) || !npc.canFight()) return false;
            if (fighting(npc)) return false;
            UUID ownerId = com.skycraft.society.Followers.getOwnerId(npc);
            Player owner = ownerId == null ? null : npc.level().getPlayerByUUID(ownerId);
            if (owner == null || !owner.isAlive()) return false;
            pick = com.skycraft.society.Followers.defendTarget(npc, owner);
            return pick != null;
        }

        @Override
        public boolean canContinueToUse() {
            return false;
        }

        @Override
        public void start() {
            npc.setTarget(pick);
            pick = null;
        }
    }

    // ------------------------------------------------------------------ routines

    static boolean stationRole(NpcRole role) {
        return switch (role) {
            case MERCHANT, APOTHECARY, INNKEEPER, JARL, STEWARD, MAGE, SCHOLAR, BUTCHER -> true;
            default -> false;
        };
    }

    private static boolean isThroneBlock(BlockState state) {
        var b = state.getBlock();
        if (b instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock) {
            var cat = state.getValue(com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock.SHAPE);
            return cat == com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.ShapeCat.THRONE;
        }
        return false;
    }

    @Nullable
    static BlockPos findSeat(NpcEntity npc, net.minecraft.world.level.Level level, BlockPos work) {
        if (npc.role() != NpcRole.JARL || !level.isLoaded(work)) return null;
        for (int dy : new int[]{0, -1}) {
            BlockPos b = work.offset(0, dy, 0);
            if (isThroneBlock(level.getBlockState(b))) return b;
        }
        for (int dy : new int[]{0, -1}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos b = work.offset(d.getStepX(), dy, d.getStepZ());
                if (isThroneBlock(level.getBlockState(b))) return b;
            }
        }
        return null;
    }

    static void faceSeat(NpcEntity npc, net.minecraft.world.level.Level level, BlockPos seat) {
        BlockState s = level.getBlockState(seat);
        if (s.hasProperty(net.minecraft.world.level.block.StairBlock.FACING)) {
            Direction out = s.getValue(net.minecraft.world.level.block.StairBlock.FACING).getOpposite();
            npc.getLookControl().setLookAt(seat.getX() + 0.5 + out.getStepX() * 3, npc.getEyeY(), seat.getZ() + 0.5 + out.getStepZ() * 3, 25f, 25f);
        } else if (s.hasProperty(com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock.FACING)) {
            Direction out = s.getValue(com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock.FACING);
            npc.getLookControl().setLookAt(seat.getX() + 0.5 + out.getStepX() * 3, npc.getEyeY(), seat.getZ() + 0.5 + out.getStepZ() * 3, 25f, 25f);
        }
    }

    /** Faces the shopkeeper/innkeeper/steward across their counter, desk, or stall toward customers. */
    static void faceStation(NpcEntity npc, net.minecraft.world.level.Level level, BlockPos work) {
        BlockPos here = npc.blockPosition();
        for (BlockPos base : new BlockPos[]{here, work}) {
            if (base == null || !level.isLoaded(base)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos adj = base.relative(d);
                if (NpcPlacement.isFurnitureOrStation(level, adj)) {
                    npc.getLookControl().setLookAt(base.getX() + 0.5 + d.getStepX() * 3, npc.getEyeY(), base.getZ() + 0.5 + d.getStepZ() * 3, 25f, 25f);
                    return;
                }
            }
        }
    }

    private static boolean isSpotClaimedByOther(NpcEntity npc, BlockPos spot) {
        var level = npc.level();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(spot).inflate(0.75, 1.0, 0.75);
        for (NpcEntity other : level.getEntitiesOfClass(NpcEntity.class, box, e -> e != npc && e.isAlive())) {
            // Stationary occupant or older/lower-ID worker keeps the spot; the newcomer steps aside
            if (other.getNavigation().isDone() || other.getId() < npc.getId()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The daily routine (Radiant AI-like, see {@link Schedule}): bed at night, breakfast and supper at home, the
     * workplace during working hours, lunch and evenings at the inn, strolling and chatting in between. Thinks about
     * once a second (offset per NPC so a town doesn't think on the same tick), only repaths when the destination
     * changes or the path ran out, and never walks into unloaded chunks. NPCs without a home only rest at night.
     */
    static final class Routine extends Goal {
        private final NpcEntity npc;
        private int think;
        private int warnTimer = 0;
        private BlockPos bedPos = null;
        private int nextBedSearch;
        @Nullable
        private UUID reported;
        @Nullable
        private BlockPos target;
        private double speed = 0.6D;
        private int strollTimer;
        private int eatTimer;
        /** A destination the pathfinder couldn't reach (blocked door, walled-in anchor): ignored for a while. */
        @Nullable
        private BlockPos unreachable;
        private int unreachableUntil;
        private int pathFails;

        Routine(NpcEntity npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        private boolean routines() {
            return com.skycraft.society.SocietyConfig.ROUTINES.get() && (npc.role().townsfolk() || com.skycraft.society.Marriage.isSpouse(npc))
                    && npc.getHome() != null;
        }

        @Override
        public boolean canUse() {
            if (npc.isBusy() || npc.getTarget() != null || !npc.role().civilian) return false;
            if (npc.social().read("engaged", net.minecraft.core.UUIDUtil.CODEC).isPresent()) return false; // waiting at the temple for the wedding
            // people on the road (roads.Travelers) are walking, not keeping a townsperson's day: no beds, no plaza
            if (npc.getPersistentData().contains("skycraft_traveler")) return false;
            if (routines()) return true;
            return Npcs.isNight(npc.level());
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            warnTimer = 0;
            think = 0;
            target = null;
        }

        @Override
        public void stop() {
            if (npc.isSleeping()) npc.stopSleeping();
            npc.activity = Schedule.Activity.IDLE;
            target = null;
        }

        @Override
        public void tick() {
            var level = npc.level();
            Schedule.Activity activity = routines() ? Schedule.activity(npc) : Schedule.Activity.SLEEP;
            // at night only the few who are meant to be out (watchmen are not townsfolk; a drunk, a late vendor) stay in the street
            if (Npcs.isNight(level) && routines() && !NpcPlacement.outAtNight(npc)
                    && (activity == Schedule.Activity.WANDER || activity == Schedule.Activity.PLAY)) {
                activity = Schedule.Activity.HOME;
            }
            if (activity != Schedule.Activity.SLEEP && npc.isSleeping()) npc.stopSleeping();
            if (activity != Schedule.Activity.SLEEP || npc.isSleeping()) sleepSince = -1;
            watchdog(activity);
            if (activity == Schedule.Activity.SLEEP || activity == Schedule.Activity.HOME) {
                if (npc.tickCount % 10 == 0 && guardHome()) return;
            }
            if (npc.isSleeping()) return;
            if (--think > 0) {
                follow();
                return;
            }
            think = 18 + Math.floorMod(npc.getId(), 7);
            if (activity != npc.activity) {
                npc.activity = activity;
                target = null;
                strollTimer = 0;
            }
            BlockPos home = npc.getHome() != null ? npc.getHome() : npc.blockPosition();
            switch (activity) {
                case SLEEP -> sleep(home);
                case EAT, HOME -> {
                    BlockPos at = npc.getBed() != null ? npc.getBed() : home;
                    go(at, 0.6D, 2);
                    if (activity == Schedule.Activity.EAT && arrived(at, 3)) eat(level);
                    else if (activity == Schedule.Activity.HOME && arrived(at, 4)) stroll(at, 3, 0.45D, 6);
                }
                case WORK -> {
                    BlockPos work = Workplaces.workplaceOf(npc);
                    if (work == null) {
                        chores(home); // no workplace: household tasks indoors, never loitering in the street
                    } else if (stationRole(npc.role())) {
                        BlockPos seat = findSeat(npc, level, work);
                        BlockPos post = seat != null ? seat : work;
                        if (seat == null && level.isLoaded(post) && NpcPlacement.isFurnitureHazard(level, post)) {
                            BlockPos safePost = NpcPlacement.freeSpot(npc, post, 3);
                            if (safePost != null) post = safePost;
                        }
                        if (isSpotClaimedByOther(npc, post)) {
                            // Another seller/NPC is already occupying this exact counter spot: stand beside it or stroll the shop
                            if (!arrived(work, 4.0)) go(work, 0.6D, 2.5);
                            else stroll(work, 3, 0.4D, 6);
                        } else if (!arrived(post, 1.75)) {
                            go(post, 0.65D, 1.25);
                        } else {
                            npc.getNavigation().stop();
                            target = null;
                            if (seat != null && npc.distanceToSqr(seat.getX() + 0.5, npc.getY(), seat.getZ() + 0.5) > 0.25) {
                                npc.getNavigation().moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 0.5D);
                            }
                            Player p = level.getNearestPlayer(npc, 6);
                            if (p != null && !p.isSpectator()) npc.getLookControl().setLookAt(p, 30f, 30f);
                            else if (seat != null) faceSeat(npc, level, seat);
                            else faceStation(npc, level, post);
                        }
                    } else if (!arrived(work, 3.5)) {
                        go(work, 0.65D, 2);
                    } else {
                        // the Work goal takes over at the workplace; hang around it until it does
                        stroll(work, 3, 0.4D, 8);
                    }
                }
                case LUNCH, INN -> {
                    BlockPos inn = Workplaces.innFor(npc);
                    if (inn == null) inn = home;
                    if (!arrived(inn, 4.5)) go(inn, 0.65D, 2.5);
                    else {
                        stroll(inn, 4, 0.45D, 6);
                        if (activity == Schedule.Activity.LUNCH && npc.getRandom().nextInt(4) == 0) eat(level);
                        com.skycraft.society.Chatter.tryStart(npc);
                    }
                }
                case WANDER -> {
                    // free hours: the plaza (their own slot around a meeting point) only in their own market hour of the
                    // day and while it is not full; chores at home otherwise
                    BlockPos spot = leisureSpot();
                    if (spot == null || !com.skycraft.society.PlazaHours.mayGo(npc, spot, com.skycraft.society.Schedule.hour(level))) chores(home);
                    else if (!arrived(spot, 4)) go(spot, 0.55D, 3);
                    else stroll(spot, 2, 0.4D, 8);
                    com.skycraft.society.Chatter.tryStart(npc);
                }
                case PLAY -> {
                    BlockPos spot = leisureSpot();
                    if (spot == null || com.skycraft.society.PlazaHours.crowded(npc, spot)) {
                        stroll(npc.getBed() != null ? npc.getBed() : home, 5, 0.85D, 2);
                    } else if (!arrived(spot, 6)) go(spot, 0.8D, 4);
                    else stroll(spot, 5, 0.85D, 2);
                    com.skycraft.society.Chatter.tryStart(npc);
                }
                default -> {
                }
            }
            follow();
        }

        // -------- unsticking

        private net.minecraft.world.phys.Vec3 lastPos;
        private int stuck;
        private int stuckRounds;
        private int outdoorNight;
        /** Game tick (npc.tickCount) at which this night's walk to bed began, or -1 when not on the way to bed. */
        private int sleepSince = -1;

        /**
         * No progress towards a destination for 40 ticks sends the person to it (or the nearest free spot beside
         * it); a person on a roof is put back down; at night a resident left in the street goes indoors.
         */
        private void watchdog(Schedule.Activity activity) {
            if (npc.tickCount % 10 != 0 || npc.isSleeping() || npc.isConversing()) return;
            var level = npc.level();
            NpcPlacement.rescueFromFireOrSlab(npc);
            if (npc.tickCount % 20 == 0) NpcPlacement.rescueFromRoof(npc);
            // If two stationary NPCs ever end up standing on the exact same spot, the higher-ID one steps aside
            if (npc.getNavigation().isDone()) {
                for (NpcEntity other : level.getEntitiesOfClass(NpcEntity.class, npc.getBoundingBox().inflate(0.25),
                        e -> e != npc && e.isAlive() && !e.isSleeping() && e.getNavigation().isDone())) {
                    if (npc.distanceToSqr(other) < 0.64 && npc.getId() > other.getId()) {
                        BlockPos aside = NpcPlacement.freeSpot(npc, npc.blockPosition(), 2);
                        if (aside != null && !aside.equals(npc.blockPosition())) {
                            npc.getNavigation().moveTo(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, 0.55D);
                        }
                        break;
                    }
                }
            }
            // stuck against a door, a wall or another body
            BlockPos t = target;
            if (t != null && level.isLoaded(t) && (NpcPlacement.isFireHazard(level, t)
                    || NpcPlacement.isSlabHazard(level, t)
                    || NpcPlacement.isFurnitureHazard(level, t)
                    || NpcPlacement.isRoof(npc, t))) {
                target = null;
                t = null;
            }
            if (t != null && !arrived(t, 2.5) && level.isLoaded(t)) {
                net.minecraft.world.phys.Vec3 p = npc.position();
                if (lastPos != null && p.distanceToSqr(lastPos) < 0.04) stuck += 10;
                else {
                    stuck = 0;
                    stuckRounds = 0;
                }
                lastPos = p;
                if (stuck >= 40) {
                    stuck = 0;
                    boolean watched = level.getNearestPlayer(npc, 7.0) != null;
                    if (watched && stuckRounds++ < 2) {
                        // someone is looking: try the pathfinder once more before popping across
                        npc.getNavigation().stop();
                        npc.getNavigation().moveTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5, speed);
                    } else {
                        BlockPos spot = NpcPlacement.freeSpot(npc, t, 2);
                        if (spot != null && npc.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) < 56.0 * 56.0) {
                            NpcPlacement.moveTo(npc, spot);
                        } else {
                            BlockPos near = NpcPlacement.freeSpot(npc, npc.blockPosition().relative(npc.getDirection(), 2), 2);
                            if (near != null) NpcPlacement.moveTo(npc, near);
                        }
                        stuckRounds = 0;
                    }
                }
            } else {
                stuck = 0;
                lastPos = null;
            }
            // the street at night
            if (Npcs.isNight(level) && routines() && !NpcPlacement.outAtNight(npc) && level.canSeeSky(npc.blockPosition())
                    && activity != Schedule.Activity.WORK) {
                if ((outdoorNight += 10) >= 300) {
                    outdoorNight = 0;
                    BlockPos in = NpcPlacement.anchorFor(npc, Schedule.Activity.SLEEP);
                    if (in != null && level.isLoaded(in) && !level.canSeeSky(in)) {
                        BlockPos spot = NpcPlacement.freeSpot(npc, in, 3);
                        if (spot != null) NpcPlacement.moveTo(npc, spot);
                    }
                }
            } else {
                outdoorNight = 0;
            }
        }

        // -------- idle places

        @Nullable
        private BlockPos leisure;
        private int nextLeisure;

        /** This person's own spot at the settlement's plaza/meeting point (see {@link Workplaces#leisureFor}). */
        @Nullable
        private BlockPos leisureSpot() {
            if (leisure == null && npc.tickCount >= nextLeisure) {
                leisure = Workplaces.leisureFor(npc);
                if (leisure == null) nextLeisure = npc.tickCount + 1200;
            }
            return leisure;
        }

        /** Household tasks: stay at (or in) the home, by the bed, instead of standing about in the street. */
        private void chores(BlockPos home) {
            BlockPos at = npc.getBed() != null ? npc.getBed() : home;
            if (!arrived(at, 3.5)) go(at, 0.55D, 2);
            else stroll(at, 3, 0.4D, 6);
        }

        // -------- movement

        private boolean arrived(BlockPos pos, double within) {
            if (isInDoorway(npc)) return false;
            var level = npc.level();
            if (level.isLoaded(pos) && !level.canSeeSky(pos.above()) && level.canSeeSky(npc.blockPosition().above())) {
                return false;
            }
            return npc.distanceToSqr(pos.getX() + 0.5, npc.getY(), npc.getZ() + 0.5) <= within * within
                    && Math.abs(npc.getY() - pos.getY()) < 4;
        }

        /** Walks to {@code pos} unless already within {@code within} blocks. */
        private void go(BlockPos pos, double speed, double within) {
            if (arrived(pos, within)) {
                target = null;
                return;
            }
            if (pos.equals(unreachable) && npc.tickCount < unreachableUntil) {
                // can't get in: loiter at a nearby spot instead of grinding on the wall/door
                stroll(npc.blockPosition(), 5, 0.45D, 4);
                return;
            }
            if (!pos.equals(target) || npc.getNavigation().isDone()) {
                if (!npc.level().isLoaded(pos)) {
                    target = null; // never pull chunks in: wait for them to load
                    return;
                }
                target = pos;
                this.speed = speed;
                if (npc.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed)) {
                    pathFails = 0;
                } else if (++pathFails >= 3) {
                    pathFails = 0;
                    unreachable = pos;
                    unreachableUntil = npc.tickCount + 1200;
                    target = null;
                }
            }
        }

        private void follow() {
            if (target != null && npc.getNavigation().isDone() && !arrived(target, 2.5)) {
                npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
            }
        }

        /** Ambles to a random safe floor spot near {@code center} every few thinks (never onto half-slabs, furniture, roofs, or fire pits). */
        private void stroll(BlockPos center, int radius, double speed, int every) {
            if (--strollTimer > 0) return;
            strollTimer = every + npc.getRandom().nextInt(every + 1);
            if (npc.isConversing()) return;
            var level = npc.level();
            boolean outside = level.canSeeSky(center);
            for (int attempt = 0; attempt < 6; attempt++) {
                int x = center.getX() + npc.getRandom().nextInt(radius * 2 + 1) - radius;
                int z = center.getZ() + npc.getRandom().nextInt(radius * 2 + 1) - radius;
                BlockPos probe = new BlockPos(x, center.getY(), z);
                if (!level.isLoaded(probe)) return;
                // stay on the floor the anchor is on (indoors) unless it's outside
                int y = outside ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : center.getY();
                if (y > center.getY() + 3) continue; // the cell is a rooftop: never a place to stroll to
                BlockPos cand = new BlockPos(x, y, z);
                if (NpcPlacement.isFireHazard(level, cand) || NpcPlacement.isSlabHazard(level, cand)
                        || NpcPlacement.isFurnitureHazard(level, cand) || NpcPlacement.isRoof(npc, cand)) continue;
                if (!level.getBlockState(cand).getCollisionShape(level, cand).isEmpty()) continue;
                if (!level.getBlockState(cand.below()).isFaceSturdy(level, cand.below(), Direction.UP)) continue;
                target = cand;
                this.speed = speed;
                npc.getNavigation().moveTo(x + 0.5, y, z + 0.5, speed);
                return;
            }
        }

        private void lookAtCustomer() {
            Player p = npc.level().getNearestPlayer(npc, 6);
            if (p != null && !p.isSpectator()) npc.getLookControl().setLookAt(p, 30f, 30f);
        }

        /** A bite of bread or a bowl of stew: particles and munching. */
        private void eat(net.minecraft.world.level.Level level) {
            if (--eatTimer > 0 || !(level instanceof ServerLevel sl)) return;
            eatTimer = 2 + npc.getRandom().nextInt(3);
            net.minecraft.world.item.ItemStack food = new net.minecraft.world.item.ItemStack(switch (npc.getRandom().nextInt(4)) {
                case 0 -> net.minecraft.world.item.Items.BREAD;
                case 1 -> net.minecraft.world.item.Items.COOKED_MUTTON;
                case 2 -> net.minecraft.world.item.Items.APPLE;
                default -> net.minecraft.world.item.Items.BAKED_POTATO;
            });
            Vec3 mouth = npc.getEyePosition().add(npc.getLookAngle().scale(0.35));
            sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(food)), mouth.x, mouth.y - 0.15, mouth.z,
                    5, 0.08, 0.05, 0.08, 0.03);
            sl.playSound(null, npc.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.5f, 0.9f + npc.getRandom().nextFloat() * 0.2f);
        }

        // -------- night

        private void sleep(BlockPos home) {
            var level = npc.level();
            // each NPC has its own bed claim (POI lookup, cached on the NPC); retry a failed search every ~30 s
            if (bedPos == null || !bedPos.equals(npc.getBed())) {
                if (npc.getBed() != null || npc.tickCount >= nextBedSearch) {
                    bedPos = com.skycraft.society.Settlers.claimBed(npc, home);
                    if (bedPos == null) nextBedSearch = npc.tickCount + 600;
                }
            }
            if (bedPos != null) {
                double distSq = npc.distanceToSqr(bedPos.getX() + 0.5, bedPos.getY(), bedPos.getZ() + 0.5);
                if (sleepSince < 0) sleepSince = npc.tickCount;
                if (distSq > 2.5 * 2.5) {
                    // half a minute and still not there (a door that will not open, a stair that is not a path): the bed
                    // is where they are; nobody spends the night awake in the wrong room
                    if (npc.tickCount - sleepSince > 600 && distSq < 90.0 * 90.0 && NpcPlacement.putToBed(npc, bedPos)) return;
                    go(bedPos, 0.65D, 1.5);
                } else {
                    npc.getNavigation().stop();
                    target = null;
                    BlockState bed = level.getBlockState(bedPos);
                    if (!bed.is(BlockTags.BEDS)) {
                        com.skycraft.society.Settlers.releaseBed(npc);
                        bedPos = null;
                    } else if (!(bed.hasProperty(BedBlock.OCCUPIED) && bed.getValue(BedBlock.OCCUPIED))) {
                        try {
                            npc.startSleeping(bedPos);
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                // no free bed: spend the night indoors at the inn (anchor, innkeeper or meeting bell), else at home
                BlockPos shelter = Workplaces.innFor(npc);
                if (shelter == null) shelter = home;
                if (!arrived(shelter, 6)) go(shelter, 0.7D, 5);
                else stroll(shelter, 3, 0.35D, 10);
            }
        }

        /** Wakes up and shouts for the guards when a stranger stands in the home at night. Returns true while busy. */
        private boolean guardHome() {
            Player trespasser = findTrespasser();
            if (trespasser != null) {
                if (npc.isSleeping()) {
                    npc.stopSleeping();
                    npc.getLookControl().setLookAt(trespasser, 30f, 30f);
                    Barks.say(npc, net.minecraft.network.chat.Component.translatable("society.skycraft.say.trespass.wake"));
                }
                warnTimer += 10;
                npc.getLookControl().setLookAt(trespasser, 30f, 30f);
                if (warnTimer == 40) {
                    Barks.say(npc, net.minecraft.network.chat.Component.translatable("society.skycraft.say.trespass.warn"));
                } else if (warnTimer > 200 && !trespasser.getUUID().equals(reported)) {
                    // reported once per intrusion; leaving and coming back is a new one
                    reported = trespasser.getUUID();
                    Barks.say(npc, net.minecraft.network.chat.Component.translatable("society.skycraft.say.trespass.guards"));
                    if (trespasser instanceof net.minecraft.server.level.ServerPlayer sp) {
                        com.skycraft.crime.Crimes.report(sp, npc.blockPosition(), 25, true, null);
                    }
                    if (npc.role().combatant) {
                        npc.setTarget(trespasser);
                    }
                }
                return true;
            }
            warnTimer = 0;
            reported = null;
            return false;
        }

        private Player findTrespasser() {
            BlockPos home = npc.getBed();
            if (home == null) return null;
            var level = npc.level();
            // Only indoor trespassing at night
            if (level.canSeeSky(npc.blockPosition())) return null;

            for (Player p : level.getEntitiesOfClass(Player.class, npc.getBoundingBox().inflate(5))) {
                if (!p.isSpectator() && !p.isCreative() && p.isAlive()) {
                    if (com.skycraft.crime.Jail.isJailArea(level, p.blockPosition())) continue;
                    // While asleep, a sneaking or hidden player does not automatically wake the resident
                    if (npc.isSleeping() && (p.isShiftKeyDown() || com.skycraft.combat.Stealth.hiddenFrom(npc, p))) continue;
                    // Player must also be indoors under roof
                    if (level.canSeeSky(p.blockPosition())) continue;
                    // Player must be within 4 blocks of the NPC's bed
                    if (p.distanceToSqr(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5) > 16.0) continue;
                    // the spouse's player lives here
                    if (com.skycraft.society.Marriage.isSpouseOf(npc, p)) continue;
                    if (com.skycraft.crime.Ownership.isOwnedByOther(p, level, p.blockPosition())) {
                        return p;
                    }
                }
            }
            return null;
        }
    }
}
