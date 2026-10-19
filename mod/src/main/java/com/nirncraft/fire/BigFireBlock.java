package com.nirncraft.fire;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The "big fire" family: persistent, animated, emissive fires for burning buildings, pyres and rubble. Unlike vanilla
 * fire they never spread, never burn a neighbouring block and never burn out, so a ruined town can stay on fire for the
 * whole life of a world without costing ticks (no random or scheduled ticks at all). They only hurt what stands in
 * them. Stack {@link Kind#BASE}, {@link Kind#COLUMN} and {@link Kind#TIP} for a tall flame column
 * ({@link #column}).
 */
public class BigFireBlock extends Block {
    /** The block kinds: light level, damage per tick of contact, seconds of burning, outline shape. */
    public enum Kind {
        BASE(15, 2.0f, 6, Block.box(2, 0, 2, 14, 16, 14)),
        COLUMN(15, 2.0f, 6, Block.box(3, 0, 3, 13, 16, 13)),
        TIP(13, 1.0f, 4, Block.box(4, 0, 4, 12, 12, 12)),
        RUBBLE(12, 1.5f, 4, Block.box(1, 0, 1, 15, 8, 15)),
        EMBERS(7, 1.0f, 0, Block.box(0, 0, 0, 16, 2, 16));

        public final int light;
        public final float damage;
        public final int burnSeconds;
        final VoxelShape shape;

        Kind(int light, float damage, int burnSeconds, VoxelShape shape) {
            this.light = light;
            this.damage = damage;
            this.burnSeconds = burnSeconds;
            this.shape = shape;
        }
    }

    public final Kind kind;

    public BigFireBlock(Kind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return kind.shape;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, net.minecraft.world.entity.InsideBlockEffectApplier effects, boolean precise) {
        if (level.isClientSide() || kind.damage <= 0.0f || !FireConfig.BIG_FIRE_HURTS.get() || entity instanceof ItemEntity) return;
        if (entity.fireImmune()) return;
        if (entity instanceof com.nirncraft.society.entity.NpcEntity
                || entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager
                || entity instanceof com.nirncraft.creatures.entity.GuardEntity) {
            return;
        }
        if (kind == Kind.EMBERS) {
            // hot coals: like a magma block (a careful step is safe), they hurt but do not set you alight
            if (!entity.isSteppingCarefully() && entity instanceof net.minecraft.world.entity.LivingEntity) {
                com.nirncraft.platform.Effects.hurt(entity, level.damageSources().hotFloor(), kind.damage);
            }
            return;
        }
        entity.igniteForSeconds(kind.burnSeconds);
        com.nirncraft.platform.Effects.hurt(entity, level.damageSources().inFire(), kind.damage);
    }

    /** Pathfinders keep out of the flames (a person walks round a burning house, not through it). */
    @Nullable
    @Override
    public PathType getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        return kind.damage > 0.0f ? PathType.FIRE : null;
    }

    @Nullable
    @Override
    public PathType getAdjacentBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob, PathType originalType) {
        return kind.damage > 0.0f ? PathType.FIRE_IN_NEIGHBOR : super.getAdjacentBlockPathType(state, level, pos, mob, originalType);
    }

    @Override
    public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState b = level.getBlockState(below);
        if (kind == Kind.COLUMN || kind == Kind.TIP) {
            return b.getBlock() instanceof BigFireBlock || b.isFaceSturdy(level, below, Direction.UP);
        }
        return b.isFaceSturdy(level, below, Direction.UP) || b.getBlock() instanceof BigFireBlock;
    }

    @Override
    public BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks, BlockPos pos, Direction dir, BlockPos neighborPos, BlockState neighbor, net.minecraft.util.RandomSource random) {
        if (dir == Direction.DOWN && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, level, ticks, pos, dir, neighborPos, neighbor, random);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
        com.nirncraft.platform.Sides.runOn(net.neoforged.api.distmarker.Dist.CLIENT,
                () -> () -> com.nirncraft.fire.client.BigFireParticles.animate(kind, level, pos, r));
    }

    /**
     * Places a flame column of {@code height} blocks (1 = base only) standing on {@code pos}: a base, full flame
     * segments and a tapering tip. Replaces only air, plants and other fire. Returns how many blocks were set.
     */
    public static int column(net.minecraft.world.level.LevelAccessor level, BlockPos pos, int height) {
        int n = 0;
        for (int i = 0; i < Math.max(1, height); i++) {
            BlockPos p = pos.above(i);
            BlockState old = level.getBlockState(p);
            if (!old.isAir() && !old.canBeReplaced() && !(old.getBlock() instanceof BigFireBlock)) break;
            BlockState s;
            if (i == 0) s = FireBlocks.BIG_FIRE.get().defaultBlockState();
            else if (i == height - 1) s = FireBlocks.FLAME_TIP.get().defaultBlockState();
            else s = FireBlocks.FLAME_COLUMN.get().defaultBlockState();
            level.setBlock(p, s, 2);
            n++;
        }
        return n;
    }
}
