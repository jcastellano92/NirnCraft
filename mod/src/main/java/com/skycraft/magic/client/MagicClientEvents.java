package com.skycraft.magic.client;

import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.combat.WeaponClass;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicPackets;
import com.skycraft.magic.Targeting;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.SpellTomeItem;
import com.skycraft.magic.spell.Spells;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.joml.Vector3f;

/**
 * Magic keys, Skyrim style:
 * <ul>
 *     <li>G opens the magic menu.</li>
 *     <li>R casts the right-hand spell (tap for fire-and-forget, hold for concentration and master spells).</li>
 *     <li>The use key casts the left-hand spell when the off hand is empty and the main hand is empty or holds a
 *     weapon, and nothing is targeted (so doors, chests and villagers still work). The weapon keeps attacking
 *     with the attack key.</li>
 *     <li>Both hands at once with the same spell is a dual cast.</li>
 *     <li>Z shouts (hold longer for more words).</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class MagicClientEvents {
    /** Ticks needed to charge the 2nd and 3rd Word of Power. */
    public static final int WORD2_TICKS = 7;
    public static final int WORD3_TICKS = 14;

    /** Fists / previous loadout toggle (default B); the magic menu also has Unequip and Unarmed buttons. */
    public static final net.minecraft.client.KeyMapping HANDS_CYCLE = new net.minecraft.client.KeyMapping("key.skycraft.hands_cycle",
            net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME, com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,
            com.mojang.blaze3d.platform.InputConstants.KEY_B, com.skycraft.client.SkyKeys.CATEGORY);

    private static boolean rightDown;
    private static boolean leftDown;
    private static boolean useWasDown;
    private static boolean attackWasDown;
    private static boolean castWasDown;
    private static boolean castUnsheathed;
    /** Ticks the shout key has been held, or -1. Read by the HUD for the charge indicator. */
    public static int shoutHeld = -1;

    private MagicClientEvents() {}

    /** Called when an attack or use click was consumed to unsheathe hands, so the same click does not also start casting. */
    public static void consumeClickForUnsheathe() {
        castUnsheathed = true;
        attackWasDown = true;
        useWasDown = true;
        castWasDown = true;
    }

    public static int wordsFor(int ticks) {
        return ticks >= WORD3_TICKS ? 3 : ticks >= WORD2_TICKS ? 2 : 1;
    }

    /** Whether the local player is holding the cast key of a hand ({@link SpellCasting#RIGHT}/{@link SpellCasting#LEFT}). */
    public static boolean isCasting(int hand) {
        return hand == SpellCasting.LEFT ? leftDown : rightDown;
    }

    public static boolean isCasting() {
        return leftDown || rightDown;
    }

    /** Main-hand items that keep their attack while the left hand casts: melee weapons and tools. */
    private static boolean castFriendly(ItemStack main) {
        if (main.isEmpty()) return true;
        // two-handed items (greatswords, bows, staves, the bound battleaxe and bow) take the left hand too
        if (com.skycraft.magic.Hands.twoHanded(main)) return false;
        if (com.skycraft.inventory.EquipmentData.toolMaterial(main)!=null) return true;
        WeaponClass wc = WeaponClass.of(main);
        return wc != WeaponClass.OTHER && wc != WeaponClass.BOW && wc != WeaponClass.UNARMED;
    }

    /** The right hand casts only when it holds no item (one hand system, see {@link com.skycraft.magic.Hands}). */
    private static boolean canStartRightCast(Minecraft mc, LocalPlayer player) {
        if (player.isSpectator() || player.isUsingItem() || com.skycraft.combat.Sheathe.isSheathed(player)) return false;
        if (com.skycraft.curse.Curses.transformed(player)) return false;
        if (!player.getMainHandItem().isEmpty()) return false;
        return !MagicData.selectedSpell(player).isEmpty();
    }

    private static boolean canStartLeftCast(Minecraft mc, LocalPlayer player) {
        if (player.isSpectator() || player.isUsingItem() || !player.getOffhandItem().isEmpty()
                || com.skycraft.combat.Sheathe.isSheathed(player)) return false;
        if (com.skycraft.curse.Curses.transformed(player)) return false;
        if (!castFriendly(player.getMainHandItem())) return false;
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return false;
        return !MagicData.leftEffective(player).isEmpty();
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        
        ClientFx.tick();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            rightDown = false;
            leftDown = false;
            useWasDown = false;
            attackWasDown = false;
            castWasDown = false;
            castUnsheathed = false;
            shoutHeld = -1;
            return;
        }

        while (SkyKeys.MAGIC_MENU.consumeClick()) {
            if (mc.gui.screen() == null) mc.gui.setScreen(new MagicMenuScreen());
        }

        // Right hand: LMB (attack key when hand is free) or CAST key.
        while (SkyKeys.CAST.consumeClick()) {
            if (mc.gui.screen() == null && com.skycraft.combat.Sheathe.isSheathed(player)) {
                com.skycraft.combat.Sheathe.setSheathed(player, false);
                SkyNetwork.sendToServer(new com.skycraft.network.CorePackets.Action(com.skycraft.network.CorePackets.Action.SHEATHE_TOGGLE, 0));
                consumeClickForUnsheathe();
            }
        }
        boolean castKeyDown = SkyKeys.CAST.isDown() && mc.gui.screen() == null;
        boolean atkKeyDown = mc.options.keyAttack.isDown() && mc.gui.screen() == null;
        boolean useDown = mc.options.keyUse.isDown() && mc.gui.screen() == null;
        if (!castKeyDown && !atkKeyDown && !useDown) {
            castUnsheathed = false;
        }
        if (com.skycraft.combat.Sheathe.isSheathed(player)) {
            if (rightDown) {
                rightDown = false;
                SkyNetwork.sendToServer(new MagicPackets.Cast(SpellCasting.RIGHT, false));
            }
            if (leftDown) {
                leftDown = false;
                SkyNetwork.sendToServer(new MagicPackets.Cast(SpellCasting.LEFT, false));
            }
        }
        boolean canRight = canStartRightCast(mc, player);
        boolean right = rightDown
                ? ((castKeyDown || atkKeyDown) && canRight)
                : (!castUnsheathed && canRight && ((castKeyDown && !castWasDown) || (atkKeyDown && !attackWasDown)));
        castWasDown = castKeyDown;
        attackWasDown = atkKeyDown;
        if (right != rightDown) {
            rightDown = right;
            SkyNetwork.sendToServer(new MagicPackets.Cast(SpellCasting.RIGHT, right));
            if (right) checkAffordable(player, SpellCasting.RIGHT);
        }

        // Left hand: RMB (use key), started on a fresh press while nothing is targeted, held until released.
        boolean left = leftDown ? (useDown && !com.skycraft.combat.Sheathe.isSheathed(player))
                : (!castUnsheathed && useDown && !useWasDown && canStartLeftCast(mc, player));
        useWasDown = useDown;
        if (left != leftDown) {
            leftDown = left;
            SkyNetwork.sendToServer(new MagicPackets.Cast(SpellCasting.LEFT, left));
            if (left) checkAffordable(player, SpellCasting.LEFT);
        }

        // A soft glow at the casting hand(s).
        if ((leftDown || rightDown) && player.tickCount % 2 == 0 && mc.level != null) {
            if (rightDown) handGlow(mc, player, SpellCasting.RIGHT);
            if (leftDown) handGlow(mc, player, SpellCasting.LEFT);
        }

        // Hands cycle key: fists (no spells, nothing held) <-> your previous loadout.
        while (HANDS_CYCLE.consumeClick()) {
            if (mc.gui.screen() == null) SkyNetwork.sendToServer(new MagicPackets.MenuAction(MagicPackets.MenuAction.CYCLE_HANDS, ""));
        }

        // An equipped spell shows as a small glow in its hand (no item model, nothing drawn over the arm).
        if (!leftDown && !rightDown && player.tickCount % 12 == 0 && mc.level != null && com.skycraft.SkyConfig.HAND_SPELL_GLOW.get()
                && !com.skycraft.combat.Sheathe.isSheathed(player) && !player.isSpectator()) {
            if (spellIn(player, SpellCasting.RIGHT) != null) handGlow(mc, player, SpellCasting.RIGHT);
            if (spellIn(player, SpellCasting.LEFT) != null) handGlow(mc, player, SpellCasting.LEFT);
        }

        // Shouting: hold to charge more words, release to shout.
        while (SkyKeys.SHOUT.consumeClick()) {
            // drained; handled through isDown()
        }
        if (mc.gui.screen() != null) {
            shoutHeld = -1;
        } else if (SkyKeys.SHOUT.isDown()) {
            shoutHeld = shoutHeld < 0 ? 0 : shoutHeld + 1;
        } else if (shoutHeld >= 0) {
            SkyNetwork.sendToServer(new MagicPackets.ShoutPacket(wordsFor(shoutHeld)));
            shoutHeld = -1;
        }
    }

    /**
     * Movement magic is applied where the player's movement is simulated (the client): Water Walking holds you on the
     * water's surface (sneak to sink), and Levitate never lets you rise too far above the ground.
     */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide() || event.getEntity() != Minecraft.getInstance().player) return;
        LocalPlayer player = (LocalPlayer) event.getEntity();
        if (player.hasEffect(com.skycraft.platform.Effects.holder(com.skycraft.magic.MagicRegistry.WATER_WALKING.get())) && !player.isShiftKeyDown() && !player.isUnderWater()) {
            net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(player.getX(), player.getY() - 0.06, player.getZ());
            boolean waterBelow = player.level().getFluidState(below).is(net.minecraft.tags.FluidTags.WATER);
            Vec3 v = player.getDeltaMovement();
            if (player.isInWater()) {
                player.setDeltaMovement(v.x, Math.max(v.y, 0.14), v.z);
            } else if (waterBelow && v.y <= 0) {
                player.setDeltaMovement(v.x, 0, v.z);
                player.setOnGround(true);
                player.fallDistance = 0;
            }
        }
        if (player.hasEffect(com.skycraft.platform.Effects.holder(com.skycraft.magic.MagicRegistry.LEVITATE.get())) && player.getAbilities().flying
                && com.skycraft.magic.spell.Mysticism.heightAboveGround(player) > com.skycraft.magic.spell.Mysticism.levitateCeiling(player)) {
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x, Math.min(v.y, -0.08), v.z);
        }
    }

    /** The spell the local player has in a hand (left falls back to right, like the server). */
    public static Spell spellIn(LocalPlayer player, int hand) {
        // a hand holding an item (or a two-handed item) holds no spell
        if (hand == SpellCasting.LEFT) {
            if (!player.getOffhandItem().isEmpty() || com.skycraft.magic.Hands.twoHanded(player.getMainHandItem())) return null;
        } else if (!player.getMainHandItem().isEmpty()) {
            return null;
        }
        String id = hand == SpellCasting.LEFT ? MagicData.leftEffective(player) : MagicData.selectedSpell(player);
        return id.isEmpty() ? null : Spells.byId(id);
    }

    /** Casting a spell you cannot afford: flash the magicka bar with the cost marked on it (and the label turns red). */
    private static void checkAffordable(LocalPlayer player, int hand) {
        Spell spell = spellIn(player, hand);
        if (spell == null || player.isCreative()) return;
        float cost = com.skycraft.magic.spell.SpellMath.cost(player, spell);
        float needed = spell.isConcentration() ? cost / 5f : cost;
        if (com.skycraft.core.SkyData.get(player).getMagicka() < needed) com.skycraft.client.hud.SkyHud.flashMagicka(needed);
    }

    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ActiveEffectsHud.clear();
    }

    private static void handGlow(Minecraft mc, LocalPlayer player, int hand) {
        Spell spell = spellIn(player, hand);
        if (spell == null) return;
        int rgb = spell.school.color;
        Vec3 p = Targeting.handPos(player, hand == SpellCasting.LEFT ? -1 : 1);
        DustParticleOptions dust = new DustParticleOptions(rgb,0.6f);
        mc.level.addParticle(dust, p.x + (player.getRandom().nextDouble() - 0.5) * 0.15, p.y + (player.getRandom().nextDouble() - 0.5) * 0.15,
                p.z + (player.getRandom().nextDouble() - 0.5) * 0.15, 0, 0.01, 0);
    }

    /** Cancel normal punch/attack swing when right-hand spell is active. */
    @SubscribeEvent
    public static void onAttackKey(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) return;
        if (com.skycraft.combat.Sheathe.isSheathed(player)
                && player.getMainHandItem().isEmpty()
                && !MagicData.selectedSpell(player).isEmpty()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            consumeClickForUnsheathe();
            com.skycraft.combat.Sheathe.setSheathed(player, false);
            com.skycraft.network.SkyNetwork.sendToServer(new com.skycraft.network.CorePackets.Action(com.skycraft.network.CorePackets.Action.SHEATHE_TOGGLE, 0));
            return;
        }
        if (canStartRightCast(mc, player)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** Render the left/offhand arm in first person when wielding or casting magic with empty offhand. */
    @SubscribeEvent
    public static void onRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.OFF_HAND) return;
        if (!event.getItemStack().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isInvisible()) return;

        // Playtest 3: an equipped spell used to draw this arm over the fists all the time. The arm is only raised while
        // the left hand actually casts, and only when the main hand holds something (empty hands already show both
        // fists via core.ClientEvents); an equipped-but-idle spell is just a faint glow (see handGlows).
        if (!leftDown || player.getMainHandItem().isEmpty()) return;
        if (com.skycraft.combat.Sheathe.isSheathed(player)) return;

        var poseStack = event.getPoseStack();
        var buffer = event.getSubmitNodeCollector();
        int light = event.getPackedLight();
        float partialTick = event.getPartialTick();
        float swing = player.getSwingAnimation(partialTick);
        float equip = event.getEquipProgress();

        poseStack.pushPose();
        float f1 = net.minecraft.util.Mth.sqrt(swing);
        float f2 = -0.3F * net.minecraft.util.Mth.sin(f1 * (float) Math.PI);
        float f3 = 0.4F * net.minecraft.util.Mth.sin(f1 * ((float) Math.PI * 2F));
        float f4 = -0.4F * net.minecraft.util.Mth.sin(swing * (float) Math.PI);
        poseStack.translate(-(f2 + 0.64F), f3 + -0.6F + equip * -0.6F, f4 + -0.72F);
        poseStack.rotateDegrees(com.mojang.math.Axis.YP, -45.0F);
        float f5 = net.minecraft.util.Mth.sin(swing * swing * (float) Math.PI);
        float f6 = net.minecraft.util.Mth.sin(f1 * (float) Math.PI);
        poseStack.rotateDegrees(com.mojang.math.Axis.YP, -f6 * 70.0F);
        poseStack.rotateDegrees(com.mojang.math.Axis.ZP, f5 * -20.0F);
        poseStack.translate(1.0F, 3.6F, 3.5F);
        poseStack.rotateDegrees(com.mojang.math.Axis.ZP, -120.0F);
        poseStack.rotateDegrees(com.mojang.math.Axis.XP, 200.0F);
        poseStack.rotateDegrees(com.mojang.math.Axis.YP, 135.0F);
        poseStack.translate(-5.6F, 0.0F, 0.0F);

        var renderer = (net.minecraft.client.renderer.entity.player.AvatarRenderer<net.minecraft.client.player.AbstractClientPlayer>) mc.getEntityRenderDispatcher().getRenderer(player);
        var state=(net.minecraft.client.renderer.entity.state.AvatarRenderState) renderer.createRenderState(player,partialTick);
        renderer.renderLeftHand(poseStack,buffer,light,state.skin.body().texturePath(),state.showLeftSleeve);
        poseStack.popPose();
        event.setCanceled(true);
    }
}
