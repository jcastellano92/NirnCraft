package com.nirncraft.arsenal.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.ArsenalPackets;
import com.nirncraft.arsenal.item.ArtifactItem;
import com.nirncraft.client.SkyKeys;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/** Forge-bus client hooks: Bloodskal Blade swings into thin air and interactive arrow pickup via [F] or right-click. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class ArsenalClientEvents {
    private static boolean interactWasDown = false;

    private ArsenalClientEvents() {}

    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) return;

        if (event.isUseItem()) {
            AbstractArrow targetArrow = findAimedArrow(player, 4.0);
            if (targetArrow != null) {
                event.setCanceled(true);
                event.setSwingHand(true);
                player.swing(InteractionHand.MAIN_HAND,player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),false);
                SkyNetwork.sendToServer(new ArsenalPackets.CollectArrow(targetArrow.getId()));
                return;
            }
        }

        if (!event.isAttack()) return;
        if (!ArtifactItem.is(player.getMainHandItem(), "bloodskal_blade")) return;
        // creature hits are detected server-side (AttackEntityEvent); block hits start mining instead
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return;
        if (player.getAttackStrengthScale(0.5f) < 0.95f) return;
        SkyNetwork.sendToServer(new ArsenalPackets.BladeSwing());
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            interactWasDown = false;
            return;
        }
        boolean down = SkyKeys.INTERACT.isDown();
        if (down && !interactWasDown) {
            AbstractArrow targetArrow = findAimedArrow(player, 4.0);
            if (targetArrow != null) {
                player.swing(InteractionHand.MAIN_HAND,player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),false);
                SkyNetwork.sendToServer(new ArsenalPackets.CollectArrow(targetArrow.getId()));
            } else if (mc.crosshairPickEntity instanceof net.minecraft.world.entity.LivingEntity living
                    && living.getArrowCount() > 0) {
                player.swing(InteractionHand.MAIN_HAND,player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),false);
                SkyNetwork.sendToServer(new ArsenalPackets.CollectArrow(living.getId()));
            }
        }
        interactWasDown = down;
    }

    @Nullable
    private static AbstractArrow findAimedArrow(LocalPlayer player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(range));
        AABB searchBox = player.getBoundingBox().expandTowards(look.scale(range)).inflate(0.75);
        AbstractArrow best = null;
        double bestDistSq = range * range;
        for (AbstractArrow arrow : player.level().getEntitiesOfClass(AbstractArrow.class, searchBox,
                a -> a.isAlive() && (com.nirncraft.arsenal.entity.SkyArrow.isArrowInGround(a) || a.isInWater()))) {
            AABB box = arrow.getBoundingBox().inflate(0.42);
            if (box.contains(eye)) {
                double d = eye.distanceToSqr(arrow.position());
                if (d < bestDistSq) {
                    bestDistSq = d;
                    best = arrow;
                }
                continue;
            }
            var hit = box.clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestDistSq) {
                    bestDistSq = d;
                    best = arrow;
                }
            }
        }
        return best;
    }

    @SubscribeEvent
    public static void onTooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
        com.nirncraft.loot.affix.ItemAffixes.appendTooltip(event.getItemStack(), event.getToolTip());
    }
}
