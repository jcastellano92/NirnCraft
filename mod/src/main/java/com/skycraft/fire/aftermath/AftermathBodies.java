package com.skycraft.fire.aftermath;

import com.skycraft.core.Currency;
import com.skycraft.creatures.ModEntities;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * The dead of an aftermath scene: persistent {@link CorpseEntity} bodies of townspeople and soldiers lying where they
 * fell, wearing what that person wore. Most are unlootable (cloth, ash, a stranger's last hour); a few carry a little
 * coin and their clothes. API for other systems (a battle scene, a quest) too: {@link #spawn}.
 */
public final class AftermathBodies {
    private AftermathBodies() {}

    /**
     * Lays a body of a person of {@code role} at {@code pos} (feet position), turned to {@code yaw}.
     *
     * @param lootable whether it can be searched (clothes and a few coins); otherwise opening it only says so
     * @param roll     tumble about the long axis, degrees (-45..45): face down, on the side
     * @return the corpse, or null if the entity could not be created
     */
    @Nullable
    public static CorpseEntity spawn(ServerLevel level, BlockPos pos, NpcRole role, float yaw, boolean lootable, float roll) {
        NpcEntity dead = Npcs.create(level, pos, role);
        if (dead == null) return null;
        CorpseEntity corpse = ModEntities.CORPSE.get().create(level);
        if (corpse == null) {
            dead.discard();
            return null;
        }
        if (lootable) {
            corpse.addClothes(dead);
            int coins = 2 + level.getRandom().nextInt(role.civilian ? 8 : 18);
            corpse.addLoot(Currency.coins(coins));
        }
        corpse.setBody(dead);
        corpse.setPersistent(true);
        corpse.setLocked(!lootable);
        corpse.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
        corpse.setCentered(true);
        corpse.setAppearDelay(0);
        corpse.getPersistentData().putString(Tableau.KEY, "body");
        corpse.getPersistentData().putLong(Tableau.AT_KEY, pos.asLong());
        corpse.setRoll(roll);
        dead.discard();
        level.addFreshEntity(corpse);
        return corpse;
    }
}
