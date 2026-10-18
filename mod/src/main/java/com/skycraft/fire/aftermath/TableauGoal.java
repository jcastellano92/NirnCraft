package com.skycraft.fire.aftermath;

import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * A hauler of an aftermath scene: carries planks (or whatever it holds) from a rubble pile to a cart, over and over,
 * arms out in front while loaded and empty-handed on the way back. Only runs for actors whose tag has a walk
 * ({@link Tableau#WALK_KEY}); everything else about the NPC (fear, fighting) takes priority over it.
 */
public final class TableauGoal extends Goal {
    private final NpcEntity npc;
    private BlockPos from, to;
    private boolean loaded;
    private int wait;

    public TableauGoal(NpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (npc.level().isClientSide() || npc.isPrisoner() || npc.getTarget() != null) return false;
        long[] walk = npc.getPersistentData().getLongArray(Tableau.WALK_KEY).orElseGet(() -> new long[0]);
        if (walk.length < 2) return false;
        from = BlockPos.of(walk[0]);
        to = BlockPos.of(walk[1]);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        wait = 0;
        setLoaded(false);
    }

    @Override
    public void stop() {
        setLoaded(false);
        npc.getNavigation().stop();
    }

    private void setLoaded(boolean on) {
        loaded = on;
        npc.setPoseFlag(NpcEntity.FLAG_CARRY, on);
        if (on) {
            ItemStack stack = new ItemStack(Items.OAK_PLANKS);
            String id = npc.getPersistentData().getStringOr("skycraft_tableau_item","");
            if (!id.isEmpty()) {
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
                if (item != null && item != Items.AIR) stack = new ItemStack(item);
            }
            npc.setItemSlot(EquipmentSlot.MAINHAND, stack);
        } else {
            npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    @Override
    public void tick() {
        BlockPos goal = loaded ? to : from;
        double dx = npc.getX() - (goal.getX() + 0.5), dz = npc.getZ() - (goal.getZ() + 0.5);
        if (dx * dx + dz * dz < 2.6 && Math.abs(npc.getY() - goal.getY()) < 3) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(goal.getX() + 0.5, goal.getY() + 0.6, goal.getZ() + 0.5);
            if (++wait >= 30) {
                wait = 0;
                npc.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                setLoaded(!loaded);
            }
            return;
        }
        if (npc.getNavigation().isDone() || npc.tickCount % 40 == 0) {
            npc.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 0.6);
        }
    }
}
