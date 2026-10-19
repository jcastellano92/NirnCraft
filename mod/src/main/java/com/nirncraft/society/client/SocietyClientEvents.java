package com.nirncraft.society.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

/** Forge-bus client events: overhead speech bubbles, the reputation key, cleanup on disconnect. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class SocietyClientEvents {
    private static final int TEXT = 0xF5EBC8;
    private static final int WRAP = 110;
    private static final int MAX_BUBBLE_LINES = 4;
    private static final float FADE_IN = 4f;
    private static final float FADE_OUT = 15f;

    private SocietyClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        
        ClientSociety.tick();
        Minecraft mc = Minecraft.getInstance();
        while (SocietyClient.REPUTATION.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new ReputationScreen());
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSociety.clear();
    }

    /**
     * Overhead text must only appear above heads, not flooded into chat. Barks arrive by their own packet; this only
     * drops a <em>system</em> chat echo of a bark that is on screen right now: exactly its text, or exactly
     * "Name: text" / "[Name] text" with the speaking NPC's name. Player chat is never touched.
     */
    @SubscribeEvent
    public static void onClientChatReceived(net.neoforged.neoforge.client.event.ClientChatReceivedEvent event) {
        if (!event.isSystem()) return;
        net.minecraft.network.chat.Component message = event.getMessage();
        if (message == null) return;
        if (event instanceof net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System sys && sys.isOverlay()) {
            com.nirncraft.client.hud.HudNotifications.add(new com.nirncraft.network.CorePackets.Notify(
                    com.nirncraft.network.NotifyKind.MESSAGE, message, net.minecraft.network.chat.Component.empty(), 0, 0f));
            event.setCanceled(true);
            return;
        }
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
            String key = tc.getKey();
            if (key.startsWith("place_lore.nirncraft.") || key.startsWith("chat.type.advancement.")
                    || key.startsWith("block.minecraft.bed.") || key.equals("block.minecraft.set_spawn")
                    || key.startsWith("lootr.")) {
                event.setCanceled(true);
                return;
            }
        }
        String raw = message.getString().trim();
        if (raw.isEmpty()) return;
        if (raw.startsWith("[Distant Horizons]") || raw.contains("Distant Horizons") || raw.startsWith("[Lootr]")) {
            event.setCanceled(true);
            return;
        }
        if (ClientSociety.BARKS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        for (var entry : ClientSociety.BARKS.entrySet()) {
            String barkText = entry.getValue().text().getString().trim();
            if (barkText.isEmpty() || !raw.endsWith(barkText)) continue;
            if (raw.length() == barkText.length()) {
                event.setCanceled(true);
                return;
            }
            net.minecraft.world.entity.Entity speaker = mc.level == null ? null : mc.level.getEntity(entry.getKey());
            if (speaker == null || speaker instanceof net.minecraft.world.entity.player.Player) continue;
            String prefix = raw.substring(0, raw.length() - barkText.length()).trim();
            String name = speaker.getName().getString();
            if (prefix.equals(name + ":") || prefix.equals("[" + name + "]") || prefix.equals("[" + name + "]:")
                    || prefix.equals("<" + name + ">")) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private record Bubble(List<FormattedCharSequence> lines,float height,float scale,int color,int background,org.joml.Quaternionf orientation) {}
    private static final net.minecraft.util.context.ContextKey<Bubble> BUBBLE=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft","speech_bubble"));
    public static void extract(LivingEntity entity,net.minecraft.client.renderer.entity.state.LivingEntityRenderState state) {
        state.setRenderData(BUBBLE,null);
        ClientSociety.Bark bark=ClientSociety.BARKS.get(entity.getId());
        Minecraft mc=Minecraft.getInstance();
        if(bark==null||mc.player==null||entity.isInvisible()||entity.distanceToSqr(mc.player)>32*32) return;
        if(entity==mc.player&&mc.options.getCameraType().isFirstPerson()) return;
        float age=ClientSociety.ticks-bark.start()+state.partialTick;
        float alpha=Math.min(1,age/FADE_IN),remaining=bark.ticks()-age;
        if(remaining<FADE_OUT) alpha=Math.min(alpha,remaining/FADE_OUT);
        int a=(int)(alpha*255);if(a<8) return;
        List<FormattedCharSequence> lines=mc.font.split(bark.text(),WRAP);
        if(lines.isEmpty()) return;
        if(lines.size()>MAX_BUBBLE_LINES)lines=lines.subList(0,MAX_BUBBLE_LINES);
        float dist=(float)Math.sqrt(entity.distanceToSqr(mc.player));
        state.setRenderData(BUBBLE,new Bubble(List.copyOf(lines),entity.getBbHeight()+.8f,.025f*Math.max(.55f,Math.min(1.5f,dist/6)),(a<<24)|TEXT,(int)(alpha*.6f*255)<<24,new org.joml.Quaternionf(mc.gameRenderer.mainCamera().rotation())));
    }
    /** Bubbles submit extracted text through the native deferred text renderer. */
    @SubscribeEvent public static void onRenderLiving(RenderLivingEvent.Post<?,?,?> event) {
        var state=event.getRenderState();Bubble bubble=state.getRenderData(BUBBLE);if(bubble==null)return;
        var pose=event.getPoseStack();var collector=event.getSubmitNodeCollector();
        pose.pushPose();pose.translate(0,bubble.height(),0);pose.rotate(bubble.orientation());
        pose.scale(-bubble.scale(),-bubble.scale(),bubble.scale());
        Font font=Minecraft.getInstance().font;
        for(int i=0;i<bubble.lines().size();i++) {
            var line=bubble.lines().get(i);
            collector.submitText(pose,-font.width(line)/2f,-bubble.lines().size()*10+i*10,line,false,Font.DisplayMode.SEE_THROUGH,0xf000f0,bubble.color(),bubble.background(),0);
        }
        pose.popPose();
    }
}
