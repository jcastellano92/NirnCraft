package com.nirncraft.fire.client;

import com.nirncraft.fire.aftermath.Tableau;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

/**
 * Tears and the occasional puff of dust for the people of an aftermath scene who are crying (the weeping flag of
 * {@link NpcEntity}), drawn within 20 blocks of the player. Cheap: one query every 10 ticks.
 */
final class WeepParticles {
    private static final RandomSource RANDOM = RandomSource.create();

    private WeepParticles() {}

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || level.getGameTime() % 6 != 1) return;
        AABB box = mc.player.getBoundingBox().inflate(20);
        for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, box, NpcEntity::isWeeping)) {
            if (RANDOM.nextInt(3) != 0) continue;
            double eye = npc.getY() + Tableau.eyeHeight(npc);
            double yaw = Math.toRadians(npc.getYRot());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double side = RANDOM.nextBoolean() ? 0.11 : -0.11;
            level.addParticle(ParticleTypes.FALLING_WATER, npc.getX() + fx * 0.22 - fz * side, eye - 0.05,
                    npc.getZ() + fz * 0.22 + fx * side, 0, 0, 0);
        }
    }
}
