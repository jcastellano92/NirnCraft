package com.nirncraft.curse.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.client.screen.SkillsScreen;
import com.nirncraft.curse.CurseAnims;
import com.nirncraft.curse.CursePackets;
import com.nirncraft.curse.CursePerks;
import com.nirncraft.curse.Curses;
import com.nirncraft.curse.Form;
import com.nirncraft.curse.VampireLord;
import com.nirncraft.magic.client.MagicMenuScreen;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client side of the curses: transformed players drawn as beasts (and their claws in first person), their one-shot
 * animations, the controls of the forms (Drain Life on the held attack key and a night spell on the use key while
 * levitating, no mining, no items), the low ceiling of the Vampire Lord's levitation, and the menus: while
 * transformed the magic menu opens the form's abilities and the skills menu its perk tree.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class CurseClient {
    private static final Map<Player, CurseAnims> PLAYER_ANIMS = new WeakHashMap<>();
    private static int primaryCooldown;
    private static int secondaryCooldown;

    private CurseClient() {}

    // ------------------------------------------------------------------ animation states

    /** The animation states of a creature, or of a (transformed) player. */
    public static CurseAnims anims(LivingEntity entity) {
        if (entity instanceof CurseAnims.Animated a) return a.anims();
        if (entity instanceof Player p) return PLAYER_ANIMS.computeIfAbsent(p, k -> new CurseAnims());
        return null;
    }

    /** {@link CursePackets.Sync}: a player's form changed. */
    public static void onSync(int entityId, int form) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (mc.level.getEntity(entityId) instanceof Player p && form == Form.NONE.ordinal()) PLAYER_ANIMS.remove(p);
    }

    /** {@link CursePackets.Anim}: a transformed player swiped, howled, fed, cast or changed. */
    public static void onAnim(int entityId, int anim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e instanceof Player p) {
            CurseAnims a = anims(p);
            if (a != null) a.start(CurseAnims.Anim.byOrdinal(anim), p.tickCount);
        }
    }

    // ------------------------------------------------------------------ rendering

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
        if (FormRenderer.render(event.getRenderState(),event.getPoseStack(),event.getSubmitNodeCollector())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderHand(RenderHandEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Form form = Curses.form(player);
        if (form == Form.NONE) return;
        event.setCanceled(true);
        boolean right = event.getHand() == InteractionHand.MAIN_HAND;
        FormRenderer.renderArm(player, form, right, right ? event.getSwingProgress() : 0f, event.getEquipProgress(),
                event.getPoseStack(), event.getSubmitNodeCollector(), event.getPackedLight());
    }

    // ------------------------------------------------------------------ controls

    /** Beasts don't mine, use items or pick blocks; levitating Vampire Lords cast instead of swinging. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Form form = Curses.form(player);
        if (form == Form.NONE) return;
        if (event.isPickBlock()) {
            event.setCanceled(true);
            return;
        }
        boolean levitating = form == Form.LORD && player.getAbilities().flying;
        if (event.isAttack()) {
            if (levitating) {
                // Drain Life runs from the client tick while the key is held
                event.setCanceled(true);
                event.setSwingHand(false);
            } else if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) {
                event.setCanceled(true);
                event.setSwingHand(mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS);
            }
        } else if (event.isUseItem() && levitating) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (secondaryCooldown <= 0) {
                secondaryCooldown = 15;
                SkyNetwork.sendToServer(CursePackets.Action.of(CursePackets.Action.SECONDARY));
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            Curses.ClientView.localId = Integer.MIN_VALUE;
            return;
        }
        Curses.ClientView.localId = player.getId();
        if (primaryCooldown > 0) primaryCooldown--;
        if (secondaryCooldown > 0) secondaryCooldown--;
        for (Map.Entry<Player, CurseAnims> e : PLAYER_ANIMS.entrySet()) e.getValue().tick(e.getKey().tickCount);
        Form form = Curses.form(player);
        if (form == Form.LORD && player.getAbilities().flying && mc.gui.screen() == null && mc.options.keyAttack.isDown() && primaryCooldown <= 0) {
            primaryCooldown = 8;
            SkyNetwork.sendToServer(CursePackets.Action.of(CursePackets.Action.PRIMARY));
            player.swing(InteractionHand.MAIN_HAND,player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),false);
        }
    }

    /** The Vampire Lord levitates; it doesn't fly: keep it close to the ground (movement is simulated here). */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide() || event.getEntity() != Minecraft.getInstance().player) return;
        Player player = event.getEntity();
        if (Curses.form(player) != Form.LORD || !player.getAbilities().flying) return;
        if (heightAboveGround(player) > VampireLord.CEILING) {
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x, Math.min(v.y, -0.06), v.z);
        }
    }

    private static double heightAboveGround(Player player) {
        BlockPos.MutableBlockPos pos = player.blockPosition().mutable();
        for (int i = 0; i < 12; i++) {
            if (!player.level().getBlockState(pos).getCollisionShape(player.level(), pos).isEmpty()) {
                return player.getY() - (pos.getY() + 1);
            }
            pos.move(0, -1, 0);
        }
        return 12;
    }

    // ------------------------------------------------------------------ menus

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Form form = Curses.form(player);
        if (form == Form.NONE) return;
        if (event.getNewScreen() instanceof MagicMenuScreen) event.setNewScreen(new CurseFormScreen(form));
        else if (event.getNewScreen() instanceof SkillsScreen) {
            event.setNewScreen(new CursePerkScreen(form == Form.BEAST ? CursePerks.Tree.WEREWOLF : CursePerks.Tree.VAMPIRE_LORD, null));
        }
    }

    /** The Skills menu gets a way into the curse constellations for those who carry one. */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SkillsScreen skills)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        int x = 8;
        for (CursePerks.Tree tree : CursePerks.Tree.values()) {
            if (!CursePerks.hasTree(player, tree)) continue;
            int points = CursePerks.available(player, tree);
            Component label = Component.translatable("curse.nirncraft.tree.button", tree.displayName()).append(points > 0 ? " (" + points + ")" : "");
            int w = Minecraft.getInstance().font.width(label) + 12;
            event.addListener(Button.builder(label, b -> Minecraft.getInstance().gui.setScreen(new CursePerkScreen(tree, skills)))
                    .bounds(x, 26, w, 16).build());
            x += w + 4;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        Curses.ClientView.clear();
        PLAYER_ANIMS.clear();
    }
}
