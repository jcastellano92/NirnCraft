package com.skycraft.client;

import com.skycraft.Skycraft;
import com.skycraft.client.hud.TargetHud;
import com.skycraft.client.screen.RaceScreen;
import com.skycraft.client.screen.SkillsScreen;
import com.skycraft.combat.WeaponClass;
import com.skycraft.core.Skill;
import com.skycraft.core.SkyData;
import net.minecraft.network.chat.Component;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.InputEvent;

/** Forge-bus client events for the core: keys, sprint exhaustion, climbing, dodge, sheathing, hiding vanilla HUD bars, bow zoom. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private static boolean wasBlocking;
    private static boolean unsheathedOnUse;
    private static int climbResend;
    private static boolean isWallClinging;
    private static net.minecraft.core.Direction wallFacing;
    private static boolean jumpWasDown;
    private static boolean punchLeftNext = false;
    private static float offhandFistSwing = 0f;
    private static float prevOffhandFistSwing = 0f;
    private static int dodgeRollTicks = 0;
    private static int shieldBashTicks = 0;
    private static int powerAttackTicks = 0;
    private static boolean pushedPlayerPose = false;

    private ClientEvents() {}

    public static boolean isWallClinging() {
        return isWallClinging;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        while (SkyKeys.HUB.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new com.skycraft.client.screen.HubScreen());
        }
        while (SkyKeys.SKILLS.consumeClick()) {
            if (SkyData.get(mc.player).getRace() == null) mc.setScreen(new RaceScreen());
            else mc.setScreen(new SkillsScreen());
        }
        // SHOUT key is handled by MagicClientEvents with charge levels & Shouting.executeVoice
        while (SkyKeys.SHEATHE.consumeClick()) {
            boolean nowSheathed = !com.skycraft.combat.Sheathe.isSheathed(mc.player);
            com.skycraft.combat.Sheathe.setSheathed(mc.player, nowSheathed);
            if (nowSheathed) {
                mc.player.stopUsingItem();
                if (wasBlocking) {
                    wasBlocking = false;
                    SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.BLOCK_STOP, 0));
                }
            }
            SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
        }

        // Dodge roll on Left Alt
        while (SkyKeys.DODGE.consumeClick()) {
            if (Perks.has(mc.player, "athletics.dodge_roll") && !ClientState.has(CorePackets.SyncVitals.EXHAUSTED)) {
                SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.DODGE_ROLL, 0));
                dodgeRollTicks = 12;
                Vec3 look = mc.player.getViewVector(1.0f);
                Vec3 horiz = new Vec3(look.x, 0, look.z).normalize();
                float fwd = mc.player.input.jumping ? 0 : (mc.player.input.up ? 1 : (mc.player.input.down ? -1 : 0));
                float strafe = mc.player.input.left ? 1 : (mc.player.input.right ? -1 : 0);
                Vec3 move;
                if (fwd == 0 && strafe == 0) {
                    move = horiz.scale(0.7);
                } else {
                    Vec3 side = new Vec3(-horiz.z, 0, horiz.x);
                    move = horiz.scale(fwd).add(side.scale(strafe)).normalize().scale(0.7);
                }
                mc.player.setDeltaMovement(move.x, 0.2, move.z);
            }
        }

        // Smooth step assist over blocks without constant jumping
        if (mc.player.maxUpStep() < 1.0625f) {
            mc.player.setMaxUpStep(1.0625f);
        }

        // BotW / AC-style wall climbing & sticking:
        // Transition ON: intentionally crouch (Shift) and jump towards a wall so you don't stick automatically
        // The effective sneak state (toggle crouch, and SneakKeeper while a screen is open), not the raw key.
        boolean crouching = mc.player.isShiftKeyDown();
        boolean jumping = mc.options.keyJump.isDown();
        boolean jumpTriggered = jumping && !jumpWasDown;
        jumpWasDown = jumping;

        boolean movingBack = mc.options.keyDown.isDown();
        boolean movingUp = mc.options.keyUp.isDown();
        boolean exhausted = ClientState.has(CorePackets.SyncVitals.EXHAUSTED);
        boolean canClimb = !mc.player.isInWater() && !mc.player.isPassenger()
                && !mc.player.isCreative() && !mc.player.isSpectator() && !mc.player.onClimbable();

        Direction playerFace = mc.player.getDirection();
        BlockPos playerPos = mc.player.blockPosition();

        if (!isWallClinging) {
            // Check if physically touching or facing a solid wall block within arm reach
            boolean solidFront = isSolidWall(mc.level, playerPos.relative(playerFace))
                    || isSolidWall(mc.level, playerPos.above().relative(playerFace));
            boolean touchingWall = mc.player.horizontalCollision || solidFront;

            // Intentional mount: fresh jump press while crouching (Shift) facing a solid wall
            if (canClimb && !exhausted && crouching && jumpTriggered && touchingWall) {
                isWallClinging = true;
                wallFacing = playerFace;
            }
        } else {
            // Dismount conditions:
            boolean solidAtFeet = isSolidWall(mc.level, playerPos.relative(wallFacing));
            boolean solidAtChest = isSolidWall(mc.level, playerPos.above().relative(wallFacing));
            boolean wallInFront = solidAtFeet || solidAtChest;

            if (!canClimb || exhausted || !wallInFront) {
                // Reached top of wall: mantle forward onto the ledge top instead of floating
                if (mc.level != null && wallFacing != null && !solidAtChest && !solidAtFeet) {
                    BlockPos ledge = playerPos.below().relative(wallFacing);
                    if (isSolidWall(mc.level, ledge)) {
                        mc.player.setDeltaMovement(wallFacing.getStepX() * 0.25, 0.20, wallFacing.getStepZ() * 0.25);
                    }
                }
                isWallClinging = false;
            } else if (movingBack && jumping) {
                // Leap backward off the wall!
                isWallClinging = false;
                Vec3 leap = new Vec3(-wallFacing.getStepX() * 0.45, 0.35, -wallFacing.getStepZ() * 0.45);
                mc.player.setDeltaMovement(leap);
            } else if (mc.player.onGround() && !movingUp) {
                // Landed on solid ground
                isWallClinging = false;
            }
        }

        if (isWallClinging && canClimb && !exhausted) {
            mc.player.resetFallDistance();

            // Continuously drain stamina to hold or climb
            if (climbResend-- <= 0) {
                SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.CLIMB_TICK, 0));
                climbResend = 4;
            }

            double climbSpeed = Perks.has(mc.player, "athletics.climber") ? 0.22 : 0.16;
            double vy = 0.0; // Sticking to wall in place if no vertical input!

            if (movingUp) {
                vy = climbSpeed;
            } else if (movingBack) {
                vy = -climbSpeed;
            }

            // Horizontal strafe along wall face
            double vx = wallFacing.getStepX() * 0.04; // Lightly stick into wall surface
            double vz = wallFacing.getStepZ() * 0.04;

            if (mc.options.keyLeft.isDown()) {
                Direction leftDir = wallFacing.getCounterClockWise();
                vx += leftDir.getStepX() * 0.12;
                vz += leftDir.getStepZ() * 0.12;
            } else if (mc.options.keyRight.isDown()) {
                Direction rightDir = wallFacing.getClockWise();
                vx += rightDir.getStepX() * 0.12;
                vz += rightDir.getStepZ() * 0.12;
            }

            // Underhang & 1-block outward overhang handling:
            if (movingUp && mc.level != null) {
                BlockPos head = playerPos.above(2);
                boolean ceilingAbove = isSolidWall(mc.level, head);
                if (ceilingAbove) {
                    // 1-block overhang / eave ceiling directly above head
                    BlockPos behindHead = head.relative(wallFacing.getOpposite());
                    if (!isSolidWall(mc.level, behindHead)) {
                        // Nudge slightly outward away from wall around the lip
                        vx -= wallFacing.getStepX() * 0.22;
                        vz -= wallFacing.getStepZ() * 0.22;
                        vy = 0.18;
                    }
                } else {
                    // Check if reached top of wall/ledge to mantle onto it
                    BlockPos ledge = playerPos.above().relative(wallFacing);
                    BlockPos aboveLedge = ledge.above();
                    if (isSolidWall(mc.level, ledge) && !isSolidWall(mc.level, aboveLedge)
                            && !isSolidWall(mc.level, aboveLedge.above())) {
                        // Mantle onto ledge top!
                        vx += wallFacing.getStepX() * 0.25;
                        vz += wallFacing.getStepZ() * 0.25;
                        vy = 0.28;
                    }
                }
            }

            mc.player.setDeltaMovement(vx, vy, vz);
        } else {
            climbResend = 0;
        }

        // Power attacks are sprint-attacks (CombatHandler); there is no separate power-attack key.

        // Weapon & unarmed blocking: dual-wielding has no block.
        // Block with RMB (keyUse) when offhand has shield/weapon or when two-handed/unarmed.
        if (!mc.options.keyUse.isDown()) {
            unsheathedOnUse = false;
        }
        ItemStack main = mc.player.getMainHandItem();
        ItemStack off = mc.player.getOffhandItem();
        boolean dual = !main.isEmpty() && !off.isEmpty()
                && WeaponClass.of(main).skill == Skill.ONE_HANDED
                && WeaponClass.of(off).skill == Skill.ONE_HANDED;
        boolean rmbBlock = !com.skycraft.combat.Sheathe.isSheathed(mc.player) && !unsheathedOnUse
                && mc.options.keyUse.isDown() && mc.screen == null && !dual
                && (mc.hitResult == null || mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS)
                && (off.getItem() instanceof net.minecraft.world.item.ShieldItem || off.isEmpty() || WeaponClass.of(main).twoHanded());
        boolean blocking = rmbBlock;
        if (blocking != wasBlocking) {
            SkyNetwork.sendToServer(new CorePackets.Action(blocking ? CorePackets.Action.BLOCK_START : CorePackets.Action.BLOCK_STOP, 0));
            wasBlocking = blocking;
            if (blocking) {
                if (off.getItem() instanceof net.minecraft.world.item.ShieldItem) {
                    mc.player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
                } else if (!main.isEmpty()) {
                    mc.player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
            } else {
                mc.player.stopUsingItem();
            }
        }

        // The one client-side sprint block, mirroring the server (Vitals: out of stamina; CarryWeight: over-encumbered).
        if (cannotSprint(mc.player)) {
            if (mc.player.isSprinting()) mc.player.setSprinting(false);
            mc.options.keySprint.setDown(false);
        }

        // Offhand fist swing & third-person action animation decay
        prevOffhandFistSwing = offhandFistSwing;
        if (offhandFistSwing > 0f) {
            offhandFistSwing = Math.max(0f, offhandFistSwing - 0.16f);
        }
        if (dodgeRollTicks > 0) dodgeRollTicks--;
        if (shieldBashTicks > 0) shieldBashTicks--;
        if (powerAttackTicks > 0) powerAttackTicks--;
    }

    /** Out of stamina (until it recovers) or carrying too much: no running. */
    private static boolean cannotSprint(net.minecraft.client.player.LocalPlayer player) {
        if (player.isCreative() || player.isSpectator()) return false;
        return ClientState.has(CorePackets.SyncVitals.EXHAUSTED) || com.skycraft.inventory.client.ClientInventoryHandlers.over;
    }

    /**
     * Keep forward impulse under sprint threshold when exhausted/over-encumbered, and cancel vanilla item-use
     * slowness for Archery "Ranger" (move at full speed with drawn bow/crossbow) and Block "Block Runner"
     * (move faster with shield or weapon raised).
     */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.client.player.LocalPlayer player)) return;
        Input input = event.getInput();
        if (player.isUsingItem() && !player.isPassenger()) {
            var useItem = player.getUseItem().getItem();
            boolean rangedUse = useItem instanceof BowItem || useItem instanceof net.minecraft.world.item.CrossbowItem;
            boolean rangerActive = rangedUse && Perks.has(player, "archery.ranger");
            boolean blockRunnerActive = (player.isBlocking() || wasBlocking) && Perks.has(player, "block.block_runner");
            if (rangerActive || blockRunnerActive) {
                input.forwardImpulse *= 5.0f;
                input.leftImpulse *= 5.0f;
            }
        }
        if (cannotSprint(player)) {
            if (input.forwardImpulse > 0.79f) input.forwardImpulse = 0.79f;
        }
    }

    /** Alternating punches when unarmed and not sheathed (discrete clicks only, never while mining a block). */
    @SubscribeEvent
    public static void onAttackInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        ItemStack mainItem = mc.player.getMainHandItem();
        ItemStack offItem = mc.player.getOffhandItem();
        WeaponClass mainWc = WeaponClass.of(mainItem);

        if (com.skycraft.combat.Sheathe.isSheathed(mc.player)) {
            if (event.isAttack()) {
                boolean holdingCombatItem = mainItem.getItem() instanceof BowItem
                        || mainItem.getItem() instanceof net.minecraft.world.item.CrossbowItem
                        || mainItem.getItem() instanceof com.skycraft.arsenal.item.StaffItem
                        || (mainWc != WeaponClass.OTHER && mainWc != WeaponClass.UNARMED && !(mainItem.getItem() instanceof net.minecraft.world.item.DiggerItem));
                boolean hasRightSpell = mainItem.isEmpty() && !com.skycraft.magic.MagicData.selectedSpell(mc.player).isEmpty();
                boolean hittingBlockWithTool = mc.hitResult != null
                        && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && !holdingCombatItem && !hasRightSpell;
                if (!hittingBlockWithTool) {
                    event.setSwingHand(false);
                    event.setCanceled(true);
                    com.skycraft.magic.client.MagicClientEvents.consumeClickForUnsheathe();
                    com.skycraft.combat.Sheathe.setSheathed(mc.player, false);
                    SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
                    return;
                }
            } else if (event.isUseItem()) {
                boolean holdingUseCombat = mainItem.getItem() instanceof BowItem
                        || mainItem.getItem() instanceof net.minecraft.world.item.CrossbowItem
                        || mainItem.getItem() instanceof com.skycraft.arsenal.item.StaffItem
                        || mainItem.getItem() instanceof net.minecraft.world.item.ShieldItem
                        || offItem.getItem() instanceof net.minecraft.world.item.ShieldItem
                        || (mainWc != WeaponClass.OTHER && mainWc != WeaponClass.UNARMED)
                        || !com.skycraft.magic.MagicData.leftEffective(mc.player).isEmpty();
                boolean interactableTarget = false;
                if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr) {
                    net.minecraft.world.entity.Entity hitEnt = ehr.getEntity();
                    interactableTarget = hitEnt instanceof com.skycraft.arsenal.entity.SkyArrow
                            || hitEnt instanceof com.skycraft.creatures.entity.CorpseEntity
                            || hitEnt instanceof net.minecraft.world.entity.npc.Npc
                            || hitEnt instanceof net.minecraft.world.entity.animal.horse.AbstractHorse
                            || hitEnt instanceof net.minecraft.world.entity.vehicle.Boat
                            || hitEnt instanceof net.minecraft.world.entity.vehicle.AbstractMinecart;
                } else if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr
                        && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && mc.level != null) {
                    var state = mc.level.getBlockState(bhr.getBlockPos());
                    interactableTarget = state.getMenuProvider(mc.level, bhr.getBlockPos()) != null
                            || state.hasBlockEntity()
                            || state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.AnvilBlock;
                }
                if (holdingUseCombat && !interactableTarget) {
                    event.setSwingHand(false);
                    event.setCanceled(true);
                    unsheathedOnUse = true;
                    com.skycraft.magic.client.MagicClientEvents.consumeClickForUnsheathe();
                    com.skycraft.combat.Sheathe.setSheathed(mc.player, false);
                    SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
                    return;
                }
            }
        }

        if (!event.isAttack()) return;
        // Block breaking (including Forge's per-tick continueAttack trigger) is left entirely to vanilla.
        if (mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        if (mainItem.isEmpty() && offItem.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            // Single shield equipped: shield bash instead of unarmed punch!
            event.setCanceled(true);
            shieldBashTicks = 10;
            mc.player.swing(net.minecraft.world.InteractionHand.OFF_HAND, false);
            SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHIELD_BASH, 0));
            return;
        }

        if (mc.player.isSprinting() && !ClientState.has(CorePackets.SyncVitals.EXHAUSTED)) {
            powerAttackTicks = 10;
        }

        if (mainItem.isEmpty() && offItem.isEmpty()) {
            if (punchLeftNext) {
                event.setCanceled(true);
                mc.player.swing(net.minecraft.world.InteractionHand.OFF_HAND, false);
                offhandFistSwing = 1.0f;
                prevOffhandFistSwing = 1.0f;
                // Attack raycast or hit entity with offhand punch
                if (mc.gameMode != null) {
                    if (mc.crosshairPickEntity != null) {
                        mc.gameMode.attack(mc.player, mc.crosshairPickEntity);
                    }
                }
                punchLeftNext = false;
            } else {
                punchLeftNext = true;
            }
        }
    }

    /** First-person hand rendering: hide hands when sheathed; show both fists in clean, lowered combat stance when drawn and unarmed. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;

        // When sheathed, hands and weapons are lowered / put away
        if (com.skycraft.combat.Sheathe.isSheathed(player)) {
            event.setCanceled(true);
            return;
        }

        // When unarmed and drawn, render BOTH fists in a stable, lowered brawler stance during MAIN_HAND pass
        if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && event.getItemStack().isEmpty() && player.getOffhandItem().isEmpty()) {
            event.setCanceled(true);
            if (player.tickCount <= 10) return;
            try {
                var poseStack = event.getPoseStack();
                var buffer = event.getMultiBufferSource();
                int light = event.getPackedLight();
                float pt = event.getPartialTick();
                var renderer = (net.minecraft.client.renderer.entity.player.PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);

                // 1. Render Left Fist (Offhand)
                poseStack.pushPose();
                float leftSwing = prevOffhandFistSwing + (offhandFistSwing - prevOffhandFistSwing) * pt;
                float lf1 = net.minecraft.util.Mth.sqrt(leftSwing);
                float lf2 = -0.25F * net.minecraft.util.Mth.sin(lf1 * (float) Math.PI);
                float lf3 = 0.35F * net.minecraft.util.Mth.sin(lf1 * ((float) Math.PI * 2F));
                float lf4 = -0.35F * net.minecraft.util.Mth.sin(leftSwing * (float) Math.PI);

                // Lowered and wider stance so fists sit cleanly at lower corners
                poseStack.translate(-(lf2 + 0.52F), lf3 - 0.50F + (wasBlocking ? 0.16F : 0F), lf4 - 0.60F);
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-20.0F));
                if (wasBlocking) {
                    poseStack.translate(0.10F, 0.12F, -0.08F);
                    poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-20.0F));
                    poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(25.0F));
                }
                float lf5 = net.minecraft.util.Mth.sin(leftSwing * leftSwing * (float) Math.PI);
                float lf6 = net.minecraft.util.Mth.sin(lf1 * (float) Math.PI);
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-lf6 * 45.0F));
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(lf5 * -15.0F));
                poseStack.translate(0.9F, 3.4F, 3.3F);
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-120.0F));
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(200.0F));
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(135.0F));
                poseStack.translate(-5.4F, 0.0F, 0.0F);
                try {
                    renderer.renderLeftHand(poseStack, buffer, light, player);
                } catch (Throwable ignored) {}
                poseStack.popPose();

                // 2. Render Right Fist (Main hand)
                poseStack.pushPose();
                float rightSwing = player.getAttackAnim(pt);
                float rf1 = net.minecraft.util.Mth.sqrt(rightSwing);
                float rf2 = 0.25F * net.minecraft.util.Mth.sin(rf1 * (float) Math.PI);
                float rf3 = 0.35F * net.minecraft.util.Mth.sin(rf1 * ((float) Math.PI * 2F));
                float rf4 = -0.35F * net.minecraft.util.Mth.sin(rightSwing * (float) Math.PI);

                // Lowered and wider stance so fists sit cleanly at lower corners
                poseStack.translate(rf2 + 0.52F, rf3 - 0.50F + (wasBlocking ? 0.16F : 0F), rf4 - 0.60F);
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(20.0F));
                if (wasBlocking) {
                    poseStack.translate(-0.10F, 0.12F, -0.08F);
                    poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(20.0F));
                    poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(25.0F));
                }
                float rf5 = net.minecraft.util.Mth.sin(rightSwing * rightSwing * (float) Math.PI);
                float rf6 = net.minecraft.util.Mth.sin(rf1 * (float) Math.PI);
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rf6 * 45.0F));
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rf5 * 15.0F));
                poseStack.translate(-0.9F, 3.4F, 3.3F);
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(120.0F));
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(200.0F));
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-135.0F));
                poseStack.translate(5.4F, 0.0F, 0.0F);
                try {
                    renderer.renderRightHand(poseStack, buffer, light, player);
                } catch (Throwable ignored) {}
                poseStack.popPose();
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isSolidWall(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        var state = level.getBlockState(pos);
        return !state.isAir() && state.isSolid() && state.getFluidState().isEmpty();
    }

    @SubscribeEvent
    public static void hideVanillaBars(RenderGuiOverlayEvent.Pre event) {
        var id = event.getOverlay().id();
        if (id.equals(VanillaGuiOverlay.PLAYER_HEALTH.id()) || id.equals(VanillaGuiOverlay.FOOD_LEVEL.id())
                || id.equals(VanillaGuiOverlay.ARMOR_LEVEL.id()) || id.equals(VanillaGuiOverlay.EXPERIENCE_BAR.id())
                || id.equals(VanillaGuiOverlay.HOTBAR.id())) {
            event.setCanceled(true);
        }
    }

    /**
     * Boss bars SkyBossHud draws itself (Skycraft bosses, the Ender Dragon, the Wither and whatever boss it is
     * currently showing) are hidden; raid bars and other mods' bars render normally.
     */
    @SubscribeEvent
    public static void hideReplacedBossBars(CustomizeGuiOverlayEvent.BossEventProgress event) {
        Component name = event.getBossEvent().getName();
        boolean replaced = name.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && (t.getKey().startsWith("entity.skycraft.") || t.getKey().equals("entity.minecraft.ender_dragon")
                || t.getKey().equals("entity.minecraft.wither"));
        if (replaced || SkyBossHud.isShowing(name) || TargetHud.isVisible()) {
            event.setIncrement(0);
            event.setCanceled(true);
        }
    }

    /** Archery "Eagle Eye" and "Steady Hand": sneak while drawing a bow to zoom in (deeper zoom with Steady Hand). */
    @SubscribeEvent
    public static void fov(ComputeFovModifierEvent event) {
        var player = event.getPlayer();
        if (player.isUsingItem() && player.getUseItem().getItem() instanceof BowItem && player.isCrouching()
                && Perks.has(player, "archery.eagle_eye")) {
            int steadyRank = Perks.rank(player, "archery.steady_hand");
            float zoom = steadyRank >= 2 ? 0.42f : (steadyRank == 1 ? 0.50f : 0.60f);
            event.setNewFovModifier(event.getNewFovModifier() * zoom);
        }
    }

    /**
     * Third-person player animations: sheathed relaxed arms, spell-casting gestures, wall-climbing hand-over-hand reach,
     * dodge-roll somersault, power-attack overhead windup & lunge, and shield-bash thrust.
     */
    @SubscribeEvent
    public static void onRenderPlayerPre(net.minecraftforge.client.event.RenderLivingEvent.Pre<?, ?> event) {
        pushedPlayerPose = false;
        if (!(event.getEntity() instanceof net.minecraft.world.entity.player.Player player)) return;
        if (!(event.getRenderer().getModel() instanceof net.minecraft.client.model.PlayerModel<?> playerModel)) return;

        Minecraft mc = Minecraft.getInstance();
        boolean isLocal = player == mc.player;
        float pt = event.getPartialTick();
        var poseStack = event.getPoseStack();

        // 1. Sheathed relaxed stance: lower arms to sides when weapons are stowed
        if (com.skycraft.arsenal.Sheathe.isSheathed(player)) {
            playerModel.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
            playerModel.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
        }

        // 2. Spell-casting gestures (left, right, or dual/two-handed channeling)
        if (isLocal && com.skycraft.magic.client.MagicClientEvents.isCasting()) {
            boolean leftCast = com.skycraft.magic.client.MagicClientEvents.isCasting(com.skycraft.magic.spell.SpellCasting.LEFT);
            boolean rightCast = com.skycraft.magic.client.MagicClientEvents.isCasting(com.skycraft.magic.spell.SpellCasting.RIGHT);
            String spellId = com.skycraft.magic.MagicData.selectedSpell(player);
            com.skycraft.magic.spell.Spell spell = com.skycraft.magic.spell.Spells.byId(spellId);
            boolean twoHandChannel = (leftCast && rightCast) || (spell != null && (
                    spell.school == com.skycraft.magic.spell.School.RESTORATION
                            || spell.school == com.skycraft.magic.spell.School.ALTERATION
                            || spell.school == com.skycraft.magic.spell.School.CONJURATION));
            if (twoHandChannel) {
                playerModel.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.BOW_AND_ARROW;
                playerModel.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.BOW_AND_ARROW;
            } else {
                if (rightCast) playerModel.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.BOW_AND_ARROW;
                if (leftCast) playerModel.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
        }

        // 3. Wall climbing: alternating overhead reach + slight lean into the wall
        boolean climbing = (isLocal && isWallClinging) || com.skycraft.vitals.ActionHandler.isClimbing(player);
        if (climbing) {
            boolean rightReach = ((player.tickCount / 6) & 1) == 0;
            playerModel.rightArmPose = rightReach
                    ? net.minecraft.client.model.HumanoidModel.ArmPose.THROW_SPEAR
                    : net.minecraft.client.model.HumanoidModel.ArmPose.SPYGLASS;
            playerModel.leftArmPose = rightReach
                    ? net.minecraft.client.model.HumanoidModel.ArmPose.SPYGLASS
                    : net.minecraft.client.model.HumanoidModel.ArmPose.THROW_SPEAR;
            poseStack.pushPose();
            pushedPlayerPose = true;
            float bob = net.minecraft.util.Mth.sin((player.tickCount + pt) * 0.55F) * 0.05F;
            poseStack.translate(0.0F, bob, 0.0F);
            return;
        }

        // 4. Dodge roll: compact tucked crouch + 360-degree forward somersault
        int rollRem = isLocal ? dodgeRollTicks : (com.skycraft.vitals.ActionHandler.isDodgeRolling(player) ? 6 : 0);
        if (rollRem > 0) {
            playerModel.crouching = true;
            float progress = 1.0F - ((rollRem - pt) / 12.0F);
            progress = net.minecraft.util.Mth.clamp(progress, 0.0F, 1.0F);
            poseStack.pushPose();
            pushedPlayerPose = true;
            float bodyYaw = net.minecraft.util.Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
            poseStack.translate(0.0F, 0.80F, 0.0F);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-bodyYaw));
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(progress * 360.0F));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(bodyYaw));
            poseStack.translate(0.0F, -0.80F, 0.0F);
            return;
        }

        // 5. Shield bash: forward shield lunge
        int bashRem = isLocal ? shieldBashTicks : (com.skycraft.vitals.ActionHandler.isShieldBashing(player) ? 5 : 0);
        if (bashRem > 0) {
            playerModel.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_HOLD;
            float thrust = net.minecraft.util.Mth.sin(((10.0F - bashRem + pt) / 10.0F) * (float) Math.PI);
            poseStack.pushPose();
            pushedPlayerPose = true;
            double yawRad = Math.toRadians(net.minecraft.util.Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot));
            poseStack.translate(-Math.sin(yawRad) * 0.32 * thrust, 0.0, Math.cos(yawRad) * 0.32 * thrust);
            return;
        }

        // 6. Power-attack windup & cleave lunge
        int pwrRem = isLocal ? powerAttackTicks : (com.skycraft.vitals.ActionHandler.isPowerAttacking(player) ? 5 : 0);
        if (pwrRem > 0) {
            float progress = (10.0F - pwrRem + pt) / 10.0F;
            if (progress < 0.45F) {
                playerModel.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.THROW_SPEAR;
            }
            float lunge = net.minecraft.util.Mth.sin(progress * (float) Math.PI);
            poseStack.pushPose();
            pushedPlayerPose = true;
            double yawRad = Math.toRadians(net.minecraft.util.Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot));
            poseStack.translate(-Math.sin(yawRad) * 0.25 * lunge, 0.06 * lunge, Math.cos(yawRad) * 0.25 * lunge);
            return;
        }

        // 7. Weapon blocking stance
        if (isLocal && wasBlocking) {
            if (player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem) {
                playerModel.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.BLOCK;
            } else {
                playerModel.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.BLOCK;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(net.minecraftforge.client.event.RenderLivingEvent.Post<?, ?> event) {
        if (pushedPlayerPose && event.getEntity() instanceof net.minecraft.world.entity.player.Player) {
            event.getPoseStack().popPose();
            pushedPlayerPose = false;
        }
    }

    /** No vanilla tutorials/toasts: clear toasts on every HUD render. */
    @SubscribeEvent
    public static void clearToasts(net.minecraftforge.client.event.RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getToasts() != null) {
            mc.getToasts().clear();
        }
    }

    /** Intercept screens: replace vanilla title, create world, and load screens with Skyrim suite. OptionsScreen is left unmodified so shaders and mod settings remain fully accessible. */
    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        net.minecraft.client.gui.screens.Screen screen = event.getNewScreen();
        if (screen == null) return;
        if (screen instanceof PauseScreen && !(screen instanceof com.skycraft.client.screen.title.SkyPauseScreen)) {
            event.setNewScreen(new com.skycraft.client.screen.title.SkyPauseScreen());
            return;
        }

        if (screen instanceof net.minecraft.client.gui.screens.TitleScreen && !(screen instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
            event.setNewScreen(new com.skycraft.client.screen.title.SkyTitleScreen());
            return;
        }

        if (screen instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen cws && !(screen instanceof com.skycraft.client.screen.title.SkyCreateWorldScreen)) {
            event.setNewScreen(new com.skycraft.client.screen.title.SkyCreateWorldScreen(cws));
            return;
        }

        if (screen instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen && !(screen instanceof com.skycraft.client.screen.title.SkyLoadWorldScreen)) {
            event.setNewScreen(new com.skycraft.client.screen.title.SkyLoadWorldScreen(new com.skycraft.client.screen.title.SkyTitleScreen()));
            return;
        }

        // Skycraft's world (the Tamriel preset, biomes and structures) comes from the mod's data pack, which vanilla
        // flags as "experimental settings". Answer that warning for the player, on create and on load.
        if ((screen instanceof net.minecraft.client.gui.screens.ConfirmScreen || screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen)
                && isExperimentalWarning(screen.getTitle()) && skipExperimentalWarning(screen)) {
            com.skycraft.Skycraft.LOGGER.info("Answered the experimental settings warning ({}) for the player", screen.getClass().getSimpleName());
            event.setCanceled(true);
        }
    }

    private static boolean isExperimentalWarning(net.minecraft.network.chat.Component title) {
        return title.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && (t.getKey().equals("selectWorld.warning.experimental.title") || t.getKey().equals("selectWorld.backupQuestion.experimental"));
    }

    /** Runs the warning's "proceed" answer on the next tick (instead of showing it); false if that isn't possible. */
    private static boolean skipExperimentalWarning(net.minecraft.client.gui.screens.Screen screen) {
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    if (f.getType() == it.unimi.dsi.fastutil.booleans.BooleanConsumer.class) {
                        f.setAccessible(true);
                        it.unimi.dsi.fastutil.booleans.BooleanConsumer callback = (it.unimi.dsi.fastutil.booleans.BooleanConsumer) f.get(screen);
                        Minecraft.getInstance().tell(() -> callback.accept(true));
                        return true;
                    }
                    if (f.getType() == net.minecraft.client.gui.screens.BackupConfirmScreen.Listener.class) {
                        f.setAccessible(true);
                        net.minecraft.client.gui.screens.BackupConfirmScreen.Listener listener = (net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) f.get(screen);
                        Minecraft.getInstance().tell(() -> listener.proceed(false, false));
                        return true;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    com.skycraft.Skycraft.LOGGER.warn("Could not answer the experimental settings warning", e);
                    return false;
                }
            }
        }
        return false;
    }
}

