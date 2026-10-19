package com.nirncraft.mounts.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.HudStack;
import com.nirncraft.creatures.entity.GuarEntity;
import com.nirncraft.inventory.client.SkyUi;
import com.nirncraft.mounts.MountBeast;
import com.nirncraft.mounts.MountInspect;
import com.nirncraft.mounts.MountKind;
import com.nirncraft.mounts.MountPackets;
import com.nirncraft.mounts.MountProfile;
import com.nirncraft.mounts.MountTaming;
import com.nirncraft.mounts.MountView;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * The target card: look at a horse, camel, guar or beast (within {@link MountInspect#RANGE} blocks, further with Wild
 * Eye) and a compact card appears at the top right of the HUD: what it is, whose it is, its level, the grades of its
 * stats, its tack and health. The client only asks ({@link MountPackets.Inspect}, when the target changes and every two
 * seconds while you keep looking, so a level gained shows up); the server decides what the answer contains, which is
 * why the card needs no persistent data of its own.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class MountCardOverlay {
    private static final int REFRESH_TICKS = 40;
    private static final int ASK_GAP = 10;

    private static MountView card;
    private static int cardEntity = -1;
    private static long cardAt;
    private static int target = -1;
    private static long askedAt = -100;
    private static int askedEntity = -1;
    /** An entity the server did not answer for (out of our perks' range): not asked again for a while. */
    private static int silentEntity = -1;
    private static long silentUntil;
    private static long tick;

    private MountCardOverlay() {}

    // ------------------------------------------------------------------ from the server

    public static void update(MountPackets.Card m) {
        card = m.view();
        cardEntity = m.entityId();
        cardAt = tick;
    }

    /** Wild Eye: the wild mounts around you on the compass. */
    public static void spotted(MountPackets.Spotted m) {
        java.util.List<com.nirncraft.client.hud.CompassMarkers.Marker> out = new java.util.ArrayList<>();
        for (MountPackets.Spot s : m.spots()) {
            MountKind k = MountKind.byId(s.kindId());
            String label = k == null ? "?" : k.displayName().getString();
            if (s.grade() >= 0) label += " (" + MountViewText.gradeName(s.grade()).getString() + ")";
            out.add(new com.nirncraft.client.hud.CompassMarkers.Marker(new Vec3(s.x(), s.y(), s.z()),
                    com.nirncraft.client.hud.CompassMarkers.Shape.DISCOVERED, MountViewText.GRADE_COLORS[Math.max(0, Math.min(4, s.grade() < 0 ? 2 : s.grade()))],
                    label, 80));
        }
        if (out.isEmpty()) com.nirncraft.client.hud.CompassMarkers.clear("mounts");
        else com.nirncraft.client.hud.CompassMarkers.set("mounts", out);
    }

    public static void sheet(MountPackets.Sheet m) {
        MountClientState.sheet = m.view();
        MountClientState.openOnSheet = m.open();
    }

    // ------------------------------------------------------------------ looking

    /** What the client can tell is a mount (the server decides what it really is). */
    static boolean candidate(Entity e) {
        if (!(e instanceof Mob m) || !m.isAlive()) return false;
        return e instanceof AbstractHorse || e instanceof MountBeast || e instanceof GuarEntity || MountTaming.wildKind(m) != null;
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        
        tick++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || mc.player.isPassenger()) {
            target = -1;
            return;
        }
        if (tick % 3 != 0) return;
        double reach = MountInspect.EYE_RANGE;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(reach));
        AABB box = mc.player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.player, eye, end, box, MountCardOverlay::candidate, reach * reach);
        if (hit == null) {
            target = -1;
            return;
        }
        Entity e = hit.getEntity();
        // not through a wall: a block between the eye and the animal hides it
        net.minecraft.world.phys.BlockHitResult wall = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, hit.getLocation(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            target = -1;
            return;
        }
        if (e.getId() == silentEntity && tick < silentUntil) {
            target = -1;
            return;
        }
        boolean changed = e.getId() != target;
        target = e.getId();
        // no answer within a second: the server will not describe this one to us from here
        if (!changed && cardEntity != target && askedEntity == target && tick - askedAt > 20) {
            silentEntity = target;
            silentUntil = tick + 100;
            target = -1;
            return;
        }
        if (changed || tick - askedAt >= REFRESH_TICKS) {
            if (tick - askedAt < ASK_GAP) return;
            askedAt = tick;
            askedEntity = target;
            SkyNetwork.sendToServer(new MountPackets.Inspect(target));
        }
    }

    // ------------------------------------------------------------------ drawing

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null || card == null || target < 0 || cardEntity != target) return;
        if (tick - cardAt > REFRESH_TICKS * 3) return;
        silentEntity = -1;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        draw(g, font, card, w, h);
    }

    private static void draw(GuiGraphicsExtractor g, Font font, MountView v, int screenW, int screenH) {
        MountKind kind = v.kind();
        int cardW = 150;
        int lines = v.known() ? 6 : 2;
        int cardH = lines * 10 + 8;
        int x = screenW - cardW - 6;
        int y = HudStack.reserve(HudStack.Zone.TOP_RIGHT, HudStack.TR_TARGET, cardH, screenH);
        g.fill(x, y, x + cardW, y + cardH, 0x90000000);
        g.fill(x, y, x + 1, y + cardH, 0xC0C8BC9A);
        int tx = x + 6, ty = y + 4;
        g.text(font, SkyUi.ellipsize(font, MountViewText.name(v).getString(), cardW - 12), tx, ty, SkyUi.GOLD, true);
        ty += 10;
        if (!v.known()) {
            g.text(font, Component.translatable("mounts.nirncraft.card.plain"), tx, ty, SkyUi.DIM, false);
            return;
        }
        // owner and level
        Component who = switch (v.owner()) {
            case 1 -> Component.translatable("mounts.nirncraft.card.yours");
            case 2 -> v.ownerName().isEmpty() ? Component.translatable("mounts.nirncraft.card.owned")
                    : Component.translatable("mounts.nirncraft.card.owned_by", v.ownerName());
            default -> Component.translatable("mounts.nirncraft.card.wild");
        };
        Component lvl = v.detail() >= 1 && v.owner() != 0
                ? Component.translatable("mounts.nirncraft.card.level", (int) v.level())
                : v.owner() != 0 ? MountViewText.levelWord(v.level()) : Component.empty();
        String head = who.getString() + (lvl.getString().isEmpty() ? "" : "  -  " + lvl.getString());
        g.text(font, SkyUi.ellipsize(font, head, cardW - 12), tx, ty, SkyUi.TEXT, false);
        ty += 10;
        if (v.detail() == 0) {
            // no Horse Sense: only a sense of it
            g.text(font, SkyUi.ellipsize(font, MountViewText.vague(v).getString(), cardW - 12), tx, ty, SkyUi.TEXT, false);
            ty += 10;
            g.text(font, Component.translatable("mounts.nirncraft.card.no_sense"), tx, ty, SkyUi.DIM, false);
            ty += 10;
        } else {
            int[][] rows = {{MountProfile.SPEED, MountProfile.STAMINA}, {MountProfile.HEALTH, MountProfile.JUMP}, {MountProfile.CARRY, MountProfile.TEMPER}};
            for (int[] row : rows) {
                for (int c = 0; c < 2; c++) {
                    int stat = row[c];
                    int cx = tx + c * 72;
                    String label = MountViewText.label(kind, stat).getString();
                    String grade = MountViewText.grade(v, stat).getString();
                    g.text(font, SkyUi.ellipsize(font, label, 36), cx, ty, SkyUi.DIM, false);
                    g.text(font, SkyUi.ellipsize(font, grade, 36), cx + 36, ty, MountViewText.color(v, stat), false);
                }
                ty += 10;
            }
        }
        // tack and health
        String tack = v.saddle().isEmpty()
                ? Component.translatable("mounts.nirncraft.card.unsaddled").getString()
                : Component.translatable("mounts.nirncraft.saddle." + v.saddle()).getString() + (v.armor() > 0 ? "  +" + v.armor() + " armour" : "");
        g.text(font, SkyUi.ellipsize(font, tack, cardW - 12 - 30), tx, ty, SkyUi.TEXT, false);
        String hp = Math.round(v.health()) + "/" + Math.round(v.maxHealth());
        float frac = v.maxHealth() <= 0 ? 1f : Mth.clamp(v.health() / v.maxHealth(), 0f, 1f);
        g.text(font, hp, x + cardW - 6 - font.width(hp), ty, frac < 0.35f ? SkyUi.BAD : SkyUi.TEXT, false);
    }
}
