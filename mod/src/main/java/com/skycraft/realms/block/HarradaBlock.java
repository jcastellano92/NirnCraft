package com.skycraft.realms.block;

import com.skycraft.tamriel.biome.block.GroundPlantBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Harrada, the Deadlands' grasping thorn-vine: it snags and cuts whatever pushes through it (daedra excepted, they
 * are used to it).
 */
public class HarradaBlock extends GroundPlantBlock.Short {
    public HarradaBlock(Properties properties, Predicate<BlockState> soil) {
        super(properties, soil);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity.getType() == EntityType.ITEM) return;
        if (living.fireImmune()) return;
        entity.makeStuckInBlock(state, new Vec3(0.75, 0.6, 0.75));
        if (!level.isClientSide && (entity.xOld != entity.getX() || entity.zOld != entity.getZ())) {
            double dx = Math.abs(entity.getX() - entity.xOld);
            double dz = Math.abs(entity.getZ() - entity.zOld);
            if (dx >= 0.003 || dz >= 0.003) entity.hurt(level.damageSources().sweetBerryBush(), 1.0f);
        }
    }
}
