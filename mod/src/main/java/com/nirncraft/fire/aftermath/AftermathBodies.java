package com.nirncraft.fire.aftermath;

import com.nirncraft.core.Currency;
import com.nirncraft.creatures.ModEntities;
import com.nirncraft.creatures.entity.CorpseEntity;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.Npcs;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * The dead of an aftermath scene: persistent {@link CorpseEntity} bodies of townspeople and soldiers lying where they
 * fell, wearing what that person wore. One rule for all of them: every body can be searched, with the same prompt, and
 * carries very little: a few coins, what they wore (a soldier's weapon and mail with it) and one personal thing. API for
 * other systems (a battle scene, a quest) too: {@link #spawn}.
 */
public final class AftermathBodies {
    private AftermathBodies() {}

    /** Personal things of a civilian (vanilla ids: always present). */
    private static final String[] CIVILIAN = {"minecraft:bread", "minecraft:apple", "minecraft:candle", "minecraft:poppy", "minecraft:bowl",
            "minecraft:paper", "minecraft:string", "minecraft:wheat", "minecraft:carrot", "minecraft:stick", "minecraft:leather"};
    /** Personal things of a soldier. */
    private static final String[] SOLDIER = {"minecraft:bread", "minecraft:arrow", "minecraft:iron_nugget", "minecraft:cooked_beef",
            "minecraft:flint", "minecraft:paper", "minecraft:string"};

    /**
     * Lays a body of a person of {@code role} at {@code pos} (feet position), turned to {@code yaw}. Every body is
     * lootable (the {@code lootable} flag is kept for old callers and ignored: one rule, one prompt).
     *
     * @param roll tumble about the long axis, degrees (-45..45): face down, on the side
     * @return the corpse, or null if the entity could not be created
     */
    @Nullable
    public static CorpseEntity spawn(ServerLevel level, BlockPos pos, NpcRole role, float yaw, boolean lootable, float roll) {
        NpcEntity dead = Npcs.create(level, pos, role);
        if (dead == null) return null;
        CorpseEntity corpse = ModEntities.CORPSE.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (corpse == null) {
            dead.discard();
            return null;
        }
        corpse.addClothes(dead);
        int coins = 1 + level.getRandom().nextInt(role.civilian ? 6 : 14);
        corpse.addLoot(Currency.coins(coins));
        String[] table = role.civilian ? CIVILIAN : SOLDIER;
        Item thing = BuiltInRegistries.ITEM.getValue(Identifier.parse(table[level.getRandom().nextInt(table.length)]));
        if (thing != null) corpse.addLoot(new ItemStack(thing, 1 + (thing == net.minecraft.world.item.Items.ARROW ? level.getRandom().nextInt(4) : 0)));
        corpse.setBody(dead);
        corpse.setPersistent(true);
        corpse.setLocked(false);
        corpse.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
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
