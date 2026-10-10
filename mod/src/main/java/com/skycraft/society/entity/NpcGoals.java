package com.skycraft.society.entity;

import com.skycraft.society.Barks;
import com.skycraft.society.NpcRole;
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
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
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

    // ------------------------------------------------------------------ fighting

    static final class Melee extends MeleeAttackGoal {
        private final NpcEntity npc;
        private int spellCooldown = 40;

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
        public void tick() {
            super.tick();
            // Thalmor justiciars are spellswords: fire at whoever keeps their distance
            LivingEntity target = npc.getTarget();
            if (target == null || npc.role() != NpcRole.THALMOR) return;
            if (--spellCooldown <= 0 && npc.distanceToSqr(target) > 36 && npc.getSensing().hasLineOfSight(target)) {
                spellCooldown = 70 + npc.getRandom().nextInt(50);
                npc.castFireball(target);
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

    /** Mages and necromancers: keep at range, wind up, throw fire; necromancers raise the dead once per fight. */
    static final class Caster extends Goal {
        private final NpcEntity npc;
        private int cooldown;
        private int windup;

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
            cooldown = 10;
            windup = 0;
            LivingEntity t = npc.getTarget();
            if (t != null && npc.role() == NpcRole.NECROMANCER && !npc.hasRaisedDead()) {
                npc.setRaisedDead(true);
                Npcs.raiseDead(npc, t);
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
                    npc.setCasting(false);
                    if (sees && d < 22 * 22) npc.castFireball(t);
                    cooldown = 35 + npc.getRandom().nextInt(30);
                }
            } else if (--cooldown <= 0 && sees && d < 18 * 18) {
                windup = 12;
                npc.setCasting(true);
                npc.playSound(SoundEvents.EVOKER_PREPARE_ATTACK, 0.6f, 1.3f);
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
            if (npc.getTarget() != null || npc.isPassenger()) return false;
            return npc.getHuntTarget() != null || npc.getSeekTarget() != null || npc.getLeader() != null || npc.getDestination() != null;
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
            if (npc.getTarget() != null || npc.isBusy() || npc.isSleeping()) return false;
            if (npc.role() == NpcRole.BARD && npc.takeSongRequest()) return true;
            if (--cooldown > 0) return false;
            cooldown = 100 + npc.getRandom().nextInt(100);
            NpcRole role = npc.role();
            boolean settled = npc.getHome() != null && com.skycraft.society.SocietyConfig.ROUTINES.get() && role.townsfolk();
            BlockPos work = null;
            if (settled) {
                if (npc.activity != Schedule.Activity.WORK) return role == NpcRole.PRIEST && (patient = findPatient()) != null;
                work = Workplaces.workplaceOf(npc);
                if (work != null && Workplaces.kindFor(role) != null && npc.distanceToSqr(work.getX() + 0.5, npc.getY(), work.getZ() + 0.5) > 10 * 10) {
                    return false;
                }
            }
            return switch (role) {
                case MINER -> (spot = findBlock(true)) != null;
                case LUMBERJACK -> (spot = findBlock(false)) != null;
                case FARMER -> (spot = findCrop()) != null;
                case BLACKSMITH, BUTCHER, INNKEEPER -> (spot = work != null ? work : npc.blockPosition()) != null;
                case MERCHANT, APOTHECARY, SCHOLAR, STEWARD, JARL -> (spot = work != null ? work : npc.blockPosition()) != null;
                case FISHER -> (spot = findWater()) != null;
                case BARD -> audience() && npc.getRandom().nextInt(3) == 0;
                case PRIEST -> (patient = findPatient()) != null;
                default -> false;
            };
        }

        @Override
        public boolean canContinueToUse() {
            return ticks > 0 && npc.getTarget() == null && (npc.role() != NpcRole.PRIEST || patient != null && patient.isAlive());
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            switch (npc.role()) {
                case MINER, LUMBERJACK, FARMER, BLACKSMITH, BUTCHER -> ticks = 120 + npc.getRandom().nextInt(80);
                case MERCHANT, APOTHECARY, SCHOLAR, STEWARD, JARL, INNKEEPER -> ticks = 200 + npc.getRandom().nextInt(200);
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
                case MERCHANT, APOTHECARY, SCHOLAR, STEWARD, JARL -> attend(level);
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
                npc.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, spot, state.getSoundType(level, spot, npc).getHitSound(), SoundSource.NEUTRAL, 0.7f, 0.9f);
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
                if (s.is(BlockTags.CROPS) || s.getBlock() instanceof net.minecraft.world.level.block.FarmBlock) {
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
                npc.swing(InteractionHand.MAIN_HAND);
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
            if (!near(spot, 3.5)) return;
            Player p = level.getNearestPlayer(npc, 6);
            if (p != null && npc.getRandom().nextInt(3) > 0) npc.getLookControl().setLookAt(p, 30f, 30f);
            if (ticks % 40 == 0) {
                npc.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, npc.blockPosition(), npc.role() == NpcRole.BUTCHER ? SoundEvents.AXE_STRIP : SoundEvents.BOTTLE_FILL,
                        SoundSource.NEUTRAL, 0.4f, 1.0f);
            }
        }

        /** Shopkeepers mind the counter and greet whoever comes in. */
        private void attend(ServerLevel level) {
            if (spot == null) {
                ticks = 0;
                return;
            }
            if (!near(spot, 3.0)) return;
            Player p = level.getNearestPlayer(npc, 7);
            if (p != null && !p.isSpectator()) npc.getLookControl().setLookAt(p, 30f, 30f);
            else if (ticks % 60 == 0) npc.swing(InteractionHand.OFF_HAND);
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
                npc.swing(InteractionHand.MAIN_HAND);
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
            melody.clear();
            int len = 84 + npc.getRandom().nextInt(24);
            int idx = 3 + npc.getRandom().nextInt(4);
            for (int i = 0; i < len; i++) {
                if (npc.getRandom().nextInt(6) == 0) {
                    melody.add(-1); // rest
                    continue;
                }
                idx = Math.max(0, Math.min(SCALE.length - 1, idx + npc.getRandom().nextInt(5) - 2));
                melody.add(SCALE[idx]);
            }
            int which = npc.getRandom().nextInt(3);
            instrument = which == 0 ? SoundEvents.NOTE_BLOCK_GUITAR : which == 1 ? SoundEvents.NOTE_BLOCK_FLUTE : SoundEvents.NOTE_BLOCK_HARP;
            noteIndex = 0;
            ticks = len * 5 + 10;
        }

        private void play(ServerLevel level) {
            Player listener = level.getNearestPlayer(npc, 16);
            if (listener != null) npc.getLookControl().setLookAt(listener, 20f, 20f);
            if (ticks % 5 != 0 || noteIndex >= melody.size()) return;
            int note = melody.get(noteIndex++);
            if (note < 0) return;
            float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
            level.playSound(null, npc.getX(), npc.getY() + 1, npc.getZ(), instrument.value(), SoundSource.RECORDS, 1.0f, pitch);
            if (noteIndex % 2 == 0) npc.swing(InteractionHand.MAIN_HAND);
            level.sendParticles(ParticleTypes.NOTE, npc.getX(), npc.getY() + 2.3, npc.getZ(), 0, note / 24.0, 0, 0, 1);
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
            return com.skycraft.society.Followers.isFollower(npc) && !fighting(npc);
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
                    npc.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, npc.getYRot(), npc.getXRot());
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
            if (npc.social().hasUUID("engaged")) return false; // waiting at the temple for the wedding
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
            if (activity != Schedule.Activity.SLEEP && npc.isSleeping()) npc.stopSleeping();
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
                    go(at, 0.6D, 3);
                    if (activity == Schedule.Activity.EAT && arrived(at, 4)) eat(level);
                    else if (activity == Schedule.Activity.HOME && arrived(at, 6)) stroll(at, 4, 0.45D, 6);
                }
                case WORK -> {
                    BlockPos work = Workplaces.workplaceOf(npc);
                    if (work == null) {
                        stroll(home, 10, 0.5D, 4);
                    } else if (!arrived(work, 5)) {
                        go(work, 0.65D, 3);
                    } else {
                        // the Work goal takes over at the workplace; hang around it until it does
                        stroll(work, 3, 0.4D, 8);
                        if (npc.role() == NpcRole.MERCHANT || npc.role() == NpcRole.APOTHECARY) lookAtCustomer();
                    }
                }
                case LUNCH, INN -> {
                    BlockPos inn = Workplaces.innFor(npc);
                    if (inn == null) inn = home;
                    if (!arrived(inn, 7)) go(inn, 0.65D, 4);
                    else {
                        stroll(inn, 5, 0.45D, 6);
                        if (activity == Schedule.Activity.LUNCH && npc.getRandom().nextInt(4) == 0) eat(level);
                        com.skycraft.society.Chatter.tryStart(npc);
                    }
                }
                case WANDER -> {
                    stroll(home, Math.max(8, (int) Math.min(24, npc.getRestrictRadius() * 0.7)), 0.5D, 3);
                    com.skycraft.society.Chatter.tryStart(npc);
                }
                case PLAY -> {
                    stroll(home, 14, 0.85D, 2);
                    com.skycraft.society.Chatter.tryStart(npc);
                }
                default -> {
                }
            }
            follow();
        }

        // -------- movement

        private boolean arrived(BlockPos pos, double within) {
            return npc.distanceToSqr(pos.getX() + 0.5, npc.getY(), pos.getZ() + 0.5) <= within * within
                    && Math.abs(npc.getY() - pos.getY()) < 4;
        }

        /** Walks to {@code pos} unless already within {@code within} blocks. */
        private void go(BlockPos pos, double speed, double within) {
            if (arrived(pos, within)) {
                target = null;
                return;
            }
            if (!pos.equals(target) || npc.getNavigation().isDone()) {
                if (!npc.level().isLoaded(pos)) {
                    target = null; // never pull chunks in: wait for them to load
                    return;
                }
                target = pos;
                this.speed = speed;
                npc.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
            }
        }

        private void follow() {
            if (target != null && npc.getNavigation().isDone() && !arrived(target, 2.5)) {
                npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
            }
        }

        /** Ambles to a random spot near {@code center} every few thinks. */
        private void stroll(BlockPos center, int radius, double speed, int every) {
            if (--strollTimer > 0) return;
            strollTimer = every + npc.getRandom().nextInt(every + 1);
            if (npc.isConversing()) return;
            int x = center.getX() + npc.getRandom().nextInt(radius * 2 + 1) - radius;
            int z = center.getZ() + npc.getRandom().nextInt(radius * 2 + 1) - radius;
            BlockPos probe = new BlockPos(x, center.getY(), z);
            if (!npc.level().isLoaded(probe)) return;
            // stay on the floor the anchor is on (indoors) unless it's outside
            int y = npc.level().canSeeSky(center) ? npc.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : center.getY();
            target = new BlockPos(x, y, z);
            this.speed = speed;
            npc.getNavigation().moveTo(x + 0.5, y, z + 0.5, speed);
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
            sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, food), mouth.x, mouth.y - 0.15, mouth.z,
                    5, 0.08, 0.05, 0.08, 0.03);
            sl.playSound(null, npc.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.5f, 0.9f + npc.getRandom().nextFloat() * 0.2f);
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
                if (distSq > 2.5 * 2.5) {
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
                go(home, 0.7D, 4);
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
