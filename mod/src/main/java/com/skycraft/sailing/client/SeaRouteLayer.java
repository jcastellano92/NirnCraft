package com.skycraft.sailing.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.sailing.Harbors;
import com.skycraft.sailing.Port;
import com.skycraft.sailing.PortSites;
import com.skycraft.sailing.Ports;
import com.skycraft.sailing.Route;
import com.skycraft.sailing.SeaRoutes;
import com.skycraft.world.client.MapScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The map's sailing layer: an anchor at every port (bright once visited) and a tent at each expedition landing, and
 * the sea routes the player knows (those touching a port they have visited) drawn as dotted lines along the charted
 * course. Hovering a port lists where its ships sail.
 */
public class SeaRouteLayer implements MapScreen.Layer {
    private static final Identifier ICONS = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/gui/sailing_icons.png");
    private static final int ROUTE = 0x2E5A7A, ROUTE_EXPEDITION = 0x7A3A2E;

    @Override
    public List<Component> draw(GuiGraphicsExtractor g, MapScreen map, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !SeaRoutes.sitesReady()) return null;
        PlayerData data = SkyData.get(mc.player);
        double scale = map.mapScale();
        // routes
        for (Route r : Ports.routes()) {
            if (!Harbors.knows(data, r.a()) && !Harbors.knows(data, r.b())) continue;
            SeaRoutes.Path path = SeaRoutes.path(r, r.a());
            if (path == null) continue;
            int color = (r.expedition() ? ROUTE_EXPEDITION : ROUTE) | 0xB0000000;
            dotted(g, map, path, color, Math.max(1, (int) Math.round(Math.min(2.0, scale * 40))));
        }
        // ports
        float size = scale < 1 / 30.0 ? 9f : scale < 1 / 8.0 ? 11f : 13f;
        Port hovered = null;
        double best = 8 * 8;
        for (Port p : Ports.all()) {
            PortSites.Site s = PortSites.of(p);
            float sx = (float) map.toScreenX(s.pierX() + 0.5), sy = (float) map.toScreenY(s.pierZ() + 0.5);
            boolean known = Harbors.knows(data, p.id());
            if (!known && scale < 1 / 60.0) continue;
            int icon = p.isLanding() ? 1 : 0;
            g.pose().pushMatrix();
            g.pose().translate(sx-size/2,sy-size/2);
            g.pose().scale(size/16f,size/16f);
            com.skycraft.inventory.client.SkyUi.blit(g,ICONS,0,0,icon*16f,0f,16,16,64,16,known?-1:0xd9736659);
            g.pose().popMatrix();
            if (known && scale >= 1 / 24.0) {
                Component name = p.name();
                float ts = 0.55f;
                int w = (int) (map.mapFont().width(name) * ts);
                g.pose().pushMatrix();
                g.pose().translate(sx-w/2f,sy+size/2f+1);
                g.pose().scale(ts,ts);
                g.text(map.mapFont(), name, 0, 0, 0xE0203848, false);
                g.pose().popMatrix();
            }
            double d = (mouseX - sx) * (double) (mouseX - sx) + (mouseY - sy) * (double) (mouseY - sy);
            if (d < best) {
                best = d;
                hovered = p;
            }
        }
        if (hovered == null) return null;
        List<Component> tip = new ArrayList<>();
        tip.add(hovered.name().copy().withStyle(ChatFormatting.GOLD));
        tip.add(Component.translatable(hovered.isLanding() ? "sailing.skycraft.map.landing" : "sailing.skycraft.map.port").withStyle(ChatFormatting.GRAY));
        for (Route r : Ports.routesFrom(hovered.id())) {
            Port other = Ports.byId(r.other(hovered.id())).orElse(null);
            if (other == null) continue;
            tip.add(Component.translatable("sailing.skycraft.map.route", other.name()).withStyle(r.expedition() ? ChatFormatting.RED : ChatFormatting.AQUA));
        }
        if (!Harbors.knows(data, hovered.id())) tip.add(Component.translatable("sailing.skycraft.map.unvisited").withStyle(ChatFormatting.DARK_GRAY));
        return tip;
    }

    private static void dotted(GuiGraphicsExtractor g, MapScreen map, SeaRoutes.Path path, int color, int dot) {
        float[] xs = path.xs(), zs = path.zs();
        float carry = 0;
        for (int i = 0; i + 1 < xs.length; i++) {
            double ax = map.toScreenX(xs[i]), ay = map.toScreenY(zs[i]);
            double bx = map.toScreenX(xs[i + 1]), by = map.toScreenY(zs[i + 1]);
            double len = Math.hypot(bx - ax, by - ay);
            if (len < 0.5) continue;
            double step = dot * 3.0;
            for (double d = carry; d < len; d += step) {
                int x = (int) Math.round(ax + (bx - ax) * d / len), y = (int) Math.round(ay + (by - ay) * d / len);
                g.fill(x, y, x + dot, y + dot, color);
            }
            carry = (float) ((carry - len) % step + step) % (float) step;
        }
    }
}
