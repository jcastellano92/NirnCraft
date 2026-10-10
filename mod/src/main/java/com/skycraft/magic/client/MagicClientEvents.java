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
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class MagicClientEvents {
    /** Ticks needed to charge the 2nd and 3rd Word of Power. */
    public static final int WORD2_TICKS = 7;
    public static final int WORD3_TICKS = 14;

    private static boolean rightDown;
    private static boolean leftDown;
    private static boolean useWasDown;
    /** Ticks the shout key has been held, or -1. Read by the HUD for the charge indicator. */
    public static int shoutHeld = -1;

    private MagicClientEvents() {}

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
        if (main.isEmpty() || main.getItem() instanceof TieredItem) return true;
        WeaponClass wc = WeaponClass.of(main);
        return wc != WeaponClass.OTHER && wc != WeaponClass.BOW && wc != WeaponClass.UNARMED;
    }

    private static boolean canStartRightCast(Minecraft mc, LocalPlayer player) {
        if (player.isSpectator() || player.isUsingItem()) return false;
        if (!player.getMainHandItem().isEmpty() && !(player.getMainHandItem().getItem() instanceof SpellTomeItem)) return false;
        return !MagicData.selectedSpell(player).isEmpty();
    }

    private static boolean canStartLeftCast(Minecraft mc, LocalPlayer player) {
        if (player.isSpectator() || player.isUsingItem() || !player.getOffhandItem().isEmpty()) return false;
        if (!castFriendly(player.getMainHandItem())) return false;
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return false;
        return !MagicData.selectedSpell(player).isEmpty() || !MagicData.leftSpell(player).isEmpty();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientFx.tick();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            rightDown = false;
            leftDown = false;
            useWasDown = false;
            shoutHeld = -1;
            return;
        }

        while (SkyKeys.MAGIC_MENU.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new MagicMenuScreen());
        }

        // Right hand: LMB (attack key when hand is free) or CAST key.
        while (SkyKeys.CAST.consumeClick()) {
            // state is read through isDown(); drain the click queue
        }
        boolean attackDown = mc.options.keyAttack.isDown() && mc.screen == null && canStartRightCast(mc, player);
        boolean right = (SkyKeys.CAST.isDown() || attackDown) && mc.screen == null;
        if (right != rightDown) {
            rightDown = right;
            SkyNetwork.sendToServer(new MagicPackets.Cast(SpellCasting.RIGHT, right));
        }

        // Left hand: RMB (use key), started on a fresh press while nothing is targeted, held until released.
        boolean useDown = mc.options.keyUse.isDown() && mc.screen == null;
        boolean left = leftDown ? useDown : useDown && !useWasDown && canStartLeftCast(mc, player);
        useWasDown = useDown;
        if (left != leftDown) {
            leftDown = left;
            SkyNetwork.sendToServer(new MagicPackets.Cast(SpellCasting.LEFT, left));
        }

        // A soft glow at the casting hand(s).
        if ((leftDown || rightDown) && player.tickCount % 2 == 0 && mc.level != null) {
            if (rightDown) handGlow(mc, player, SpellCasting.RIGHT);
            if (leftDown) handGlow(mc, player, SpellCasting.LEFT);
        }

        // Shouting: hold to charge more words, release to shout.
        while (SkyKeys.SHOUT.consumeClick()) {
            // drained; handled through isDown()
        }
        if (mc.screen != null) {
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
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !event.player.level().isClientSide || event.player != Minecraft.getInstance().player) return;
        LocalPlayer player = (LocalPlayer) event.player;
        if (player.hasEffect(com.skycraft.magic.MagicRegistry.WATER_WALKING.get()) && !player.isShiftKeyDown() && !player.isUnderWater()) {
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
        if (player.hasEffect(com.skycraft.magic.MagicRegistry.LEVITATE.get()) && player.getAbilities().flying
                && com.skycraft.magic.spell.Mysticism.heightAboveGround(player) > com.skycraft.magic.spell.Mysticism.levitateCeiling(player)) {
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x, Math.min(v.y, -0.08), v.z);
        }
    }

    /** The spell the local player has in a hand (left falls back to right, like the server). */
    public static Spell spellIn(LocalPlayer player, int hand) {
        String id = hand == SpellCasting.LEFT ? MagicData.leftSpell(player) : "";
        if (id.isEmpty()) id = MagicData.selectedSpell(player);
        return Spells.byId(id);
    }

    private static void handGlow(Minecraft mc, LocalPlayer player, int hand) {
        Spell spell = spellIn(player, hand);
        if (spell == null) return;
        int rgb = spell.school.color;
        Vec3 p = Targeting.handPos(player, hand == SpellCasting.LEFT ? -1 : 1);
        DustParticleOptions dust = new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f), 0.6f);
        mc.level.addParticle(dust, p.x + (player.getRandom().nextDouble() - 0.5) * 0.15, p.y + (player.getRandom().nextDouble() - 0.5) * 0.15,
                p.z + (player.getRandom().nextDouble() - 0.5) * 0.15, 0, 0.01, 0);
    }

    /** Cancel normal punch/attack swing when right-hand spell is active. */
    @SubscribeEvent
    public static void onAttackKey(net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) return;
        if (canStartRightCast(mc, player)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** Render the left/offhand arm in first person when wielding or casting magic with empty offhand. */
    @SubscribeEvent
    public static void onRenderHand(net.minecraftforge.client.event.RenderHandEvent event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.OFF_HAND) return;
        if (!event.getItemStack().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isInvisible()) return;

        String leftSpell = MagicData.leftSpell(player);
        if (leftSpell.isEmpty()) leftSpell = MagicData.selectedSpell(player);
        if (leftSpell.isEmpty() && !isCasting()) return;

        var poseStack = event.getPoseStack();
        var buffer = event.getMultiBufferSource();
        int light = event.getPackedLight();
        float partialTick = event.getPartialTick();
        float swing = player.getAttackAnim(partialTick);
        float equip = event.getEquipProgress();

        poseStack.pushPose();
        float f1 = net.minecraft.util.Mth.sqrt(swing);
        float f2 = -0.3F * net.minecraft.util.Mth.sin(f1 * (float) Math.PI);
        float f3 = 0.4F * net.minecraft.util.Mth.sin(f1 * ((float) Math.PI * 2F));
        float f4 = -0.4F * net.minecraft.util.Mth.sin(swing * (float) Math.PI);
        poseStack.translate(-(f2 + 0.64F), f3 + -0.6F + equip * -0.6F, f4 + -0.72F);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-45.0F));
        float f5 = net.minecraft.util.Mth.sin(swing * swing * (float) Math.PI);
        float f6 = net.minecraft.util.Mth.sin(f1 * (float) Math.PI);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-f6 * 70.0F));
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(f5 * -20.0F));
        poseStack.translate(1.0F, 3.6F, 3.5F);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-120.0F));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(200.0F));
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(135.0F));
        poseStack.translate(-5.6F, 0.0F, 0.0F);

        var renderer = (net.minecraft.client.renderer.entity.player.PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
        renderer.renderLeftHand(poseStack, buffer, light, player);
        poseStack.popPose();
        event.setCanceled(true);
    }
}
