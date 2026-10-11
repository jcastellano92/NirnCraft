package com.skycraft.world.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.QuestPackets;
import com.skycraft.quest.client.ClientQuestData;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.world.LocationKind;
import com.skycraft.world.Provinces;
import com.skycraft.world.SkyrimCalendar;
import com.skycraft.world.WorldData;
import com.skycraft.world.WorldPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The world map of Nirn, Skyrim style: a painted parchment map (shaded relief, coasts, rivers, mountains, hold and
 * province borders, see {@link MapTiles}) with province and region names, the named places of Tamriel and every
 * location the player discovered (click to fast travel), quest markers, a custom marker, you, the other players
 * and your party. Unexplored land lies under fog ({@link ExploredMap}); past the known world only explored land is
 * drawn at all. Drag or WASD/arrows to pan, scroll or +/- to zoom, Space to find yourself, L for the legend.
 *
 * <p>Other realms (Oblivion, Sovngarde) show their discovered locations on blank parchment.</p>
 */
public class MapScreen extends Screen {
    private static final ResourceLocation PARCHMENT = new ResourceLocation(Skycraft.MODID, "textures/gui/map_parchment.png");
    private static final ResourceLocation ICONS = new ResourceLocation(Skycraft.MODID, "textures/gui/map_icons.png");
    private static final ResourceLocation FOG = new ResourceLocation(Skycraft.MODID, "map_fog");
    /** GUI pixels per block. */
    private static final double MIN_SCALE = 1.0 / 160.0;
    private static final double MAX_SCALE = 2.0;
    private static final double DEFAULT_SCALE = 1.0 / 6.0;
    private static final int INK = 0x3A2A18;
    private static final int FOG_RGB = 0xD6C6A0;
    /** Foreign lands beyond Tamriel's seas count as charted (dim fog) within this many blocks of their centre. */
    private static final int FOREIGN_RADIUS = 7000;
    private static final String[] FOREIGN = {"atmora", "akavir", "yokuda", "pyandonea", "thras"};

    private static double savedScale = DEFAULT_SCALE;
    public static BlockPos customWaypoint = null;
    private static boolean showLegend;

    private double centerX;
    private double centerZ;
    private double scale = savedScale;
    private boolean centered;
    private int left, top, right, bottom;
    private boolean pressedOnMap;
    private double dragDistance;
    private Pin hoveredPin;
    private CompoundTag pendingTravel;
    /** Party member under the cursor / chosen for fast travel (quest module's party sync). */
    private ClientQuestData.Member hoveredMember;
    private ClientQuestData.Member pendingMember;
    private List<Component> tooltip;
    private Button yesButton, noButton;

    /** A place or location drawn on the map. */
    private record Pin(int x, int z, int icon, int priority, Component name, LocationKind kind, boolean known,
                       boolean cleared, CompoundTag loc, Place place) {
    }

    public MapScreen() {
        super(Component.translatable("screen.skycraft.map"));
    }

    public static void resetView() {
        savedScale = DEFAULT_SCALE;
        customWaypoint = null;
        Fog.release();
    }

    @Override
    protected void init() {
        left = 10;
        top = 28;
        right = width - 10;
        bottom = height - 16;
        if (!centered && minecraft != null && minecraft.player != null) {
            centerOnPlayer();
            centered = true;
        }
        int cx = width / 2;
        int cy = height / 2;
        yesButton = addRenderableWidget(Button.builder(Component.translatable("gui.yes"), b -> confirmTravel())
                .bounds(cx - 62, cy + 10, 60, 20).build());
        noButton = addRenderableWidget(Button.builder(Component.translatable("gui.no"), b -> cancelTravel())
                .bounds(cx + 2, cy + 10, 60, 20).build());
        updateButtons();
    }

    private void centerOnPlayer() {
        centerX = minecraft.player.getX();
        centerZ = minecraft.player.getZ();
    }

    private void updateButtons() {
        if (yesButton != null) yesButton.visible = noButton.visible = pendingTravel != null || pendingMember != null;
    }

    private void cancelTravel() {
        pendingTravel = null;
        pendingMember = null;
        updateButtons();
    }

    @Override
    public void tick() {
        updateButtons();
        if (minecraft == null || pendingTravel != null || pendingMember != null) return;
        // keyboard / controller (mapped to keys) panning
        long window = minecraft.getWindow().getWindow();
        double step = 10 / scale;
        if (down(window, GLFW.GLFW_KEY_A) || down(window, GLFW.GLFW_KEY_LEFT)) centerX -= step;
        if (down(window, GLFW.GLFW_KEY_D) || down(window, GLFW.GLFW_KEY_RIGHT)) centerX += step;
        if (down(window, GLFW.GLFW_KEY_W) || down(window, GLFW.GLFW_KEY_UP)) centerZ -= step;
        if (down(window, GLFW.GLFW_KEY_S) || down(window, GLFW.GLFW_KEY_DOWN)) centerZ += step;
        if (down(window, GLFW.GLFW_KEY_Q) || down(window, GLFW.GLFW_KEY_PAGE_DOWN)) zoom(1 / 1.06, midX(), midY());
        if (down(window, GLFW.GLFW_KEY_E) || down(window, GLFW.GLFW_KEY_PAGE_UP)) zoom(1.06, midX(), midY());
    }

    private static boolean down(long window, int key) {
        return InputConstants.isKeyDown(window, key);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        savedScale = scale;
        super.removed();
    }

    private void confirmTravel() {
        if (pendingMember != null) {
            SkyNetwork.sendToServer(new QuestPackets.PartyTravel(pendingMember.id()));
            pendingMember = null;
            onClose();
            return;
        }
        if (pendingTravel != null) {
            SkyNetwork.sendToServer(new WorldPackets.FastTravel(pendingTravel.getString("id")));
            pendingTravel = null;
            onClose();
        }
    }

    // ------------------------------------------------------------------ coordinates

    /**
     * An extra layer another module draws on the overworld map, above the terrain and below the pins (e.g. the sailing
     * module's ports and sea routes). Register once on the client in {@link #LAYERS}.
     */
    public interface Layer {
        /** Draws the layer; returns tooltip lines for what is under the mouse, or null. */
        List<Component> draw(GuiGraphics g, MapScreen map, int mouseX, int mouseY);
    }

    public static final List<Layer> LAYERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Screen X of a world X (for {@link Layer}s). */
    public double toScreenX(double wx) {
        return screenX(wx);
    }

    /** Screen Y of a world Z (for {@link Layer}s). */
    public double toScreenY(double wz) {
        return screenY(wz);
    }

    /** Current zoom in GUI pixels per block (for {@link Layer}s). */
    public double mapScale() {
        return scale;
    }

    public net.minecraft.client.gui.Font mapFont() {
        return font;
    }

    private double midX() {
        return (left + right) / 2.0;
    }

    private double midY() {
        return (top + bottom) / 2.0;
    }

    private double screenX(double wx) {
        return midX() + (wx - centerX) * scale;
    }

    private double screenY(double wz) {
        return midY() + (wz - centerZ) * scale;
    }

    private double worldX(double sx) {
        return centerX + (sx - midX()) / scale;
    }

    private double worldZ(double sy) {
        return centerZ + (sy - midY()) / scale;
    }

    private boolean onMap(double sx, double sy) {
        return sx >= left && sx < right && sy >= top && sy < bottom;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (pendingTravel != null || pendingMember != null) {
            cancelTravel();
            return true;
        }
        if (button == 1 && onMap(mx, my) && customWaypoint != null) {
            clearWaypoint();
            return true;
        }
        if (button == 0 && onMap(mx, my)) {
            pressedOnMap = true;
            dragDistance = 0;
            return true;
        }
        if (button == 0 && my >= bottom + 2 && mx < left + 60) {
            showLegend = !showLegend;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pressedOnMap && button == 0) {
            centerX -= dx / scale;
            centerZ -= dy / scale;
            dragDistance += Math.abs(dx) + Math.abs(dy);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && pressedOnMap) {
            pressedOnMap = false;
            if (dragDistance >= 4) return true;
            if (hoveredMember != null) {
                pendingMember = hoveredMember;
                updateButtons();
            } else if (hoveredPin != null && hoveredPin.loc() != null && !hoveredPin.known()) {
                pendingTravel = hoveredPin.loc();
                updateButtons();
            } else if (hoveredPin != null) {
                // heard of but not discovered: mark it so the compass leads there
                setWaypoint(new BlockPos(hoveredPin.x(), playerY(), hoveredPin.z()), hoveredPin.name().getString());
            } else if (onMap(mx, my)) {
                if (customWaypoint != null && Math.abs(screenX(customWaypoint.getX() + 0.5) - mx) <= 8
                        && Math.abs(screenY(customWaypoint.getZ() + 0.5) - my) <= 8) {
                    clearWaypoint();
                } else {
                    setWaypoint(new BlockPos((int) Math.round(worldX(mx)), playerY(), (int) Math.round(worldZ(my))), null);
                }
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private int playerY() {
        return minecraft != null && minecraft.player != null ? minecraft.player.getBlockY() : 64;
    }

    private void setWaypoint(BlockPos pos, String label) {
        customWaypoint = pos;
        String text = label != null ? label : Component.translatable("world.skycraft.map.custom").getString();
        com.skycraft.client.hud.CompassMarkers.set("custom_waypoint", List.of(
                new com.skycraft.client.hud.CompassMarkers.Marker(Vec3.atBottomCenterOf(pos), com.skycraft.client.hud.CompassMarkers.Shape.QUEST,
                        0x38B0DE, text, 0)));
        click(1.2f);
    }

    private void clearWaypoint() {
        customWaypoint = null;
        com.skycraft.client.hud.CompassMarkers.clear("custom_waypoint");
        click(0.8f);
    }

    private void click(float pitch) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, pitch);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        zoom(Math.pow(1.25, delta), onMap(mx, my) ? mx : midX(), onMap(mx, my) ? my : midY());
        return true;
    }

    private void zoom(double factor, double aboutX, double aboutY) {
        double wx = worldX(aboutX);
        double wz = worldZ(aboutY);
        scale = Mth.clamp(scale * factor, MIN_SCALE, MAX_SCALE);
        // keep the world point under the cursor fixed
        centerX = wx - (aboutX - midX()) / scale;
        centerZ = wz - (aboutY - midY()) / scale;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (pendingTravel != null || pendingMember != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                confirmTravel();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelTravel();
                return true;
            }
        }
        if (SkyKeys.MAP.matches(key, scan)) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_C) {
            centerOnPlayer();
            return true;
        }
        if (key == GLFW.GLFW_KEY_L) {
            showLegend = !showLegend;
            return true;
        }
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
            zoom(1.5, midX(), midY());
            return true;
        }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoom(1 / 1.5, midX(), midY());
            return true;
        }
        if (key == GLFW.GLFW_KEY_F && customWaypoint != null) {
            centerX = customWaypoint.getX();
            centerZ = customWaypoint.getZ();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return;
        Level level = minecraft.level;
        Player player = minecraft.player;
        tooltip = null;
        hoveredPin = null;
        hoveredMember = null;
        boolean overworld = level.dimension() == Level.OVERWORLD;

        // frame and paper
        g.fill(left - 5, top - 5, right + 5, bottom + 5, 0xFF1E140A);
        g.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFF6B5030);
        g.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF2A1D10);
        g.blit(PARCHMENT, left, top, right - left, bottom - top, 0f, 0f, 256, 256, 256, 256);

        g.enableScissor(left, top, right, bottom);
        if (overworld) {
            MapTiles.uploadFinished();
            drawTiles(g);
            drawPaperGrain(g);
            drawFog(g);
            // the planned road network (roads module), drawn over the fog like Skyrim's map shows every road
            com.skycraft.roads.client.RoadMapLayer.draw(g, font, this::screenX, this::screenY, scale, left, top, right, bottom);
            drawProvinceAndRegionNames(g);
            for (Layer layer : LAYERS) {
                List<Component> tip = layer.draw(g, this, mouseX, mouseY);
                if (tip != null && onMap(mouseX, mouseY)) tooltip = tip;
            }
        } else {
            drawGrid(g);
        }
        drawPins(g, level, player, mouseX, mouseY);
        drawQuestMarkers(g, level, mouseX, mouseY);
        drawCustomWaypoint(g, player, mouseX, mouseY);
        drawPlayers(g, level, player, partialTick, mouseX, mouseY);
        drawPartyMembers(g, level, player, mouseX, mouseY);
        g.disableScissor();
        drawVignette(g);

        drawHeader(g, level, player);
        drawFooter(g, level, mouseX, mouseY);
        if (showLegend) drawLegend(g);

        if (pendingTravel != null || pendingMember != null) drawTravelDialog(g, player);
        super.render(g, mouseX, mouseY, partialTick);
        if (tooltip != null && pendingTravel == null && pendingMember == null) g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ terrain tiles

    /**
     * Draws the painted map: every coarser level that is ready (so there is always something under a tile that is
     * still being painted), then the level matching the zoom. Only the target level and the overview are requested.
     */
    private void drawTiles(GuiGraphics g) {
        int target = MapTiles.lodFor(1 / scale);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int lod = 0; lod <= target; lod++) {
            int tb = MapTiles.tileBlocks(lod);
            int tx0 = Math.floorDiv(Mth.floor(worldX(left)), tb);
            int tx1 = Math.floorDiv(Mth.floor(worldX(right)), tb);
            int tz0 = Math.floorDiv(Mth.floor(worldZ(top)), tb);
            int tz1 = Math.floorDiv(Mth.floor(worldZ(bottom)), tb);
            if ((long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1) > 400) continue;
            boolean request = lod == target || lod == 0;
            float size = (float) (tb * scale);
            for (int tz = tz0; tz <= tz1; tz++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    ResourceLocation tex = request ? MapTiles.get(lod, tx, tz) : MapTiles.ready(lod, tx, tz) ? MapTiles.get(lod, tx, tz) : null;
                    if (tex == null) continue;
                    g.pose().pushPose();
                    g.pose().translate((float) screenX((double) tx * tb), (float) screenY((double) tz * tb), 0);
                    // a hair larger so neighbouring tiles never leave a seam
                    float s = (size + 0.35f) / MapTiles.SIZE;
                    g.pose().scale(s, s, 1f);
                    g.blit(tex, 0, 0, 0f, 0f, MapTiles.SIZE, MapTiles.SIZE, MapTiles.SIZE, MapTiles.SIZE);
                    g.pose().popPose();
                }
            }
        }
    }

    /** The parchment's fibres and stains over the painted map (multiplied, so the colours stay). */
    private void drawPaperGrain(GuiGraphics g) {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.DST_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        g.setColor(1.22f, 1.22f, 1.3f, 1f);
        g.blit(PARCHMENT, left, top, right - left, bottom - top, 0f, 0f, right - left, bottom - top, 256, 256);
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
    }

    // ------------------------------------------------------------------ fog of war

    /** The fog texture: one texel per explored-map cell over a margin around the view. */
    private static final class Fog {
        static DynamicTexture texture;
        static int x0, z0, cell, w, h, level;
        static long version = Long.MIN_VALUE;

        static void release() {
            if (texture != null) {
                net.minecraft.client.Minecraft.getInstance().getTextureManager().release(FOG);
                texture = null;
            }
            version = Long.MIN_VALUE;
        }
    }

    private void drawFog(GuiGraphics g) {
        double vx0 = worldX(left), vx1 = worldX(right), vz0 = worldZ(top), vz1 = worldZ(bottom);
        double span = Math.max(vx1 - vx0, vz1 - vz0) * 1.6;
        int level = 0;
        while (level < ExploredMap.LEVELS - 1 && span / (1 << (ExploredMap.CELL_SHIFT + level)) > 320) level++;
        int cell = 1 << (ExploredMap.CELL_SHIFT + level);
        boolean inside = Fog.texture != null && Fog.level == level
                && vx0 >= Fog.x0 && vz0 >= Fog.z0 && vx1 <= Fog.x0 + (long) Fog.w * Fog.cell && vz1 <= Fog.z0 + (long) Fog.h * Fog.cell;
        if (!inside || Fog.version != ExploredMap.version()) rebuildFog(level, cell, (vx0 + vx1) / 2, (vz0 + vz1) / 2, span);
        if (Fog.texture == null) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.pose().pushPose();
        g.pose().translate((float) screenX(Fog.x0), (float) screenY(Fog.z0), 0);
        float s = (float) (Fog.cell * scale);
        g.pose().scale(s, s, 1f);
        g.blit(FOG, 0, 0, 0f, 0f, Fog.w, Fog.h, Fog.w, Fog.h);
        g.pose().popPose();
    }

    private void rebuildFog(int level, int cell, double cx, double cz, double span) {
        int n = Math.max(8, Math.min(512, (int) Math.ceil(span / cell)));
        int x0 = Math.floorDiv((int) Math.floor(cx - n * cell / 2.0), cell) * cell;
        int z0 = Math.floorDiv((int) Math.floor(cz - n * cell / 2.0), cell) * cell;
        if (Fog.texture == null || Fog.w != n || Fog.h != n) {
            Fog.release();
            Fog.texture = new DynamicTexture(n, n, false);
            Fog.texture.setFilter(true, false);
            minecraft.getTextureManager().register(FOG, Fog.texture);
        }
        NativeImage img = Fog.texture.getPixels();
        if (img == null) return;
        int rgb = FOG_RGB;
        int abgrRgb = (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
        for (int j = 0; j < n; j++) {
            int wz = z0 + j * cell;
            for (int i = 0; i < n; i++) {
                int wx = x0 + i * cell;
                int alpha;
                if (ExploredMap.explored(level, (wx >> ExploredMap.CELL_SHIFT) >> level, (wz >> ExploredMap.CELL_SHIFT) >> level)) {
                    alpha = 0;
                } else {
                    alpha = charted(wx + cell / 2, wz + cell / 2) ? 150 : 255;
                }
                img.setPixelRGBA(i, j, alpha << 24 | abgrRgb);
            }
        }
        Fog.texture.upload();
        Fog.x0 = x0;
        Fog.z0 = z0;
        Fog.cell = cell;
        Fog.w = n;
        Fog.h = n;
        Fog.level = level;
        Fog.version = ExploredMap.version();
    }

    /** Tamriel and the foreign lands are on every map; the endless lands beyond are drawn only where explored. */
    private static boolean charted(int x, int z) {
        if (TamrielMap.inTamriel(x, z)) return true;
        for (String f : FOREIGN) {
            int[] a = Provinces.anchor(f);
            if (a == null) continue;
            long dx = x - a[0], dz = z - a[1];
            if (dx * dx + dz * dz < (long) FOREIGN_RADIUS * FOREIGN_RADIUS) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ names

    private void drawProvinceAndRegionNames(GuiGraphics g) {
        // province names: large spaced capitals when zoomed out
        if (scale < 1 / 10.0) {
            float a = (float) Mth.clamp((1 / 10.0 - scale) / (1 / 10.0 - 1 / 18.0), 0, 1);
            float textScale = (float) Mth.clamp(0.9 + (1 / 40.0) / scale * 0.25, 1.1, 2.2);
            for (MapLabels.Label l : MapLabels.provinces()) {
                drawLabel(g, spaced(l.name().getString()), l.x(), l.z(), textScale, (int) (a * 0xB0) << 24 | 0x4A2A16);
            }
        }
        // region (hold) names at middle zoom
        if (scale > 1 / 60.0 && scale < 1 / 1.6) {
            float a = (float) Math.min(Mth.clamp((scale - 1 / 60.0) / (1 / 30.0 - 1 / 60.0), 0, 1),
                    Mth.clamp((1 / 1.6 - scale) / (1 / 1.6 - 1 / 2.5), 0, 1));
            for (MapLabels.Label l : MapLabels.regions()) {
                double px = Math.sqrt(l.area()) * scale;
                if (px < 50) continue;
                float textScale = (float) Mth.clamp(px / 220.0, 0.8, 1.4);
                drawLabel(g, l.name().copy().withStyle(ChatFormatting.ITALIC), l.x(), l.z(), textScale, (int) (a * 0x98) << 24 | INK);
            }
        }
    }

    private void drawLabel(GuiGraphics g, Component text, int wx, int wz, float textScale, int color) {
        if ((color >>> 24) < 8) return;
        double sx = screenX(wx);
        double sy = screenY(wz);
        int w = font.width(text);
        if (sx + w * textScale < left || sx - w * textScale > right || sy < top - 20 || sy > bottom + 20) return;
        g.pose().pushPose();
        g.pose().translate((float) sx, (float) sy, 0);
        g.pose().scale(textScale, textScale, 1f);
        g.drawString(font, text, -w / 2, -4, color, false);
        g.pose().popPose();
    }

    private static Component spaced(String s) {
        StringBuilder sb = new StringBuilder();
        String up = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < up.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(up.charAt(i));
        }
        return Component.literal(sb.toString());
    }

    private void drawGrid(GuiGraphics g) {
        int spacing = 16;
        while (spacing * scale < 70 && spacing < (1 << 24)) spacing *= 2;
        double wx0 = worldX(left), wx1 = worldX(right);
        double wz0 = worldZ(top), wz1 = worldZ(bottom);
        long startX = (long) Math.ceil(wx0 / spacing) * spacing;
        long startZ = (long) Math.ceil(wz0 / spacing) * spacing;
        int line = 0x22000000 | INK;
        int label = 0x99000000 | INK;
        for (long wx = startX; wx <= wx1; wx += spacing) {
            int sx = (int) Math.round(screenX(wx));
            g.fill(sx, top, sx + 1, bottom, line);
            drawSmall(g, String.valueOf(wx), sx + 2, top + 2, label);
        }
        for (long wz = startZ; wz <= wz1; wz += spacing) {
            int sy = (int) Math.round(screenY(wz));
            g.fill(left, sy, right, sy + 1, line);
            drawSmall(g, String.valueOf(wz), left + 2, sy + 2, label);
        }
    }

    private void drawSmall(GuiGraphics g, String text, int x, int y, int color) {
        drawScaled(g, Component.literal(text), x, y, 0.6f, color);
    }

    private void drawScaled(GuiGraphics g, Component text, float x, float y, float s, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ places and locations

    private void drawIcon(GuiGraphics g, int index, float cx, float cy, float size) {
        int u = (index % 8) * 16;
        int v = (index / 8) * 16;
        g.pose().pushPose();
        g.pose().translate(cx - size / 2, cy - size / 2, 0);
        g.pose().scale(size / 16f, size / 16f, 1f);
        g.blit(ICONS, 0, 0, (float) u, (float) v, 16, 16, 128, 64);
        g.pose().popPose();
    }

    /** Every pin to draw: discovered and rumored locations, plus the capitals and cities everyone knows. */
    private List<Pin> pins(Level level, Player player) {
        ListTag list = SkyData.get(player).module(WorldData.MODULE).getList("discovered", Tag.TAG_COMPOUND);
        String dim = level.dimension().location().toString();
        List<Pin> out = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            if (!loc.getString("dim").equals(dim)) continue;
            LocationKind kind = LocationKind.byId(loc.getString("type"));
            Place place = WorldData.place(loc).orElse(null);
            if (place != null) listed.add(place.id());
            out.add(new Pin(loc.getInt("x"), loc.getInt("z"), kind.icon, priority(place, kind), WorldData.displayName(loc), kind,
                    loc.getBoolean("known"), loc.getBoolean("cleared"), loc, place));
        }
        if (level.dimension() == Level.OVERWORLD) {
            for (Place p : Places.all()) {
                if (listed.contains(p.id()) || (p.type() != PlaceType.CAPITAL && p.type() != PlaceType.CITY)) continue;
                LocationKind kind = LocationKind.forPlace(p);
                out.add(new Pin(p.x(), p.z(), kind.icon, priority(p, kind), p.name(), kind, true, false, null, p));
            }
        }
        out.sort(Comparator.comparingInt(Pin::priority).reversed());
        return out;
    }

    private static int priority(Place place, LocationKind kind) {
        if (place == null) return 0;
        return switch (place.type()) {
            case CAPITAL -> 4;
            case CITY -> 3;
            case TOWN, STANDING_STONE, WORD_WALL, DRAGON_MOUND, DUNGEON_FIXED, DAEDRIC_SHRINE -> 2;
            default -> 1;
        };
    }

    /** The zoom (GUI pixels per block) from which pins of this priority are drawn. */
    private static double minScale(int priority) {
        return switch (priority) {
            case 4 -> 0;
            case 3 -> 1 / 48.0;
            case 2 -> 1 / 18.0;
            case 1 -> 1 / 12.0;
            default -> 1 / 9.0;
        };
    }

    private void drawPins(GuiGraphics g, Level level, Player player, int mouseX, int mouseY) {
        List<Pin> pins = pins(level, player);
        List<float[]> drawn = new ArrayList<>();
        double best = 9 * 9;
        Pin hovered = null;
        float iconSize = scale < 1 / 30.0 ? 11f : scale < 1 / 8.0 ? 13f : 16f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (Pin pin : pins) {
            if (scale < minScale(pin.priority())) continue;
            float sx = (float) screenX(pin.x() + 0.5);
            float sy = (float) screenY(pin.z() + 0.5);
            if (sx < left - 10 || sx > right + 10 || sy < top - 10 || sy > bottom + 10) continue;
            boolean crowded = false;
            for (float[] d : drawn) {
                if (Math.abs(d[0] - sx) < iconSize * 0.7f && Math.abs(d[1] - sy) < iconSize * 0.7f) {
                    crowded = true;
                    break;
                }
            }
            if (crowded) continue;
            drawn.add(new float[]{sx, sy});
            if (pin.known()) {
                g.setColor(0.42f, 0.36f, 0.3f, 0.9f); // heard of: a dark silhouette until discovered
            }
            drawIcon(g, pin.known() && pin.place() == null ? LocationKind.UNKNOWN_ICON : pin.icon(), sx, sy, iconSize);
            g.setColor(1f, 1f, 1f, 1f);
            if (showName(pin)) {
                Component name = pin.name();
                float ts = pin.priority() >= 4 ? 0.75f : 0.62f;
                int w = (int) (font.width(name) * ts);
                drawScaled(g, name, sx - w / 2f, sy + iconSize / 2f + 1, ts, (pin.known() ? 0xA0 : 0xE0) << 24 | INK);
            }
            double d = (mouseX - sx) * (double) (mouseX - sx) + (mouseY - sy) * (double) (mouseY - sy);
            if (d < best && onMap(mouseX, mouseY)) {
                best = d;
                hovered = pin;
            }
        }
        RenderSystem.disableBlend();
        if (hovered != null) {
            hoveredPin = hovered;
            int sx = (int) Math.round(screenX(hovered.x() + 0.5));
            int sy = (int) Math.round(screenY(hovered.z() + 0.5));
            int r = (int) (iconSize / 2) + 2;
            outline(g, sx - r, sy - r, sx + r, sy + r, 0xC0000000 | INK);
            tooltip = pinTooltip(hovered, player);
        }
    }

    private boolean showName(Pin pin) {
        return switch (pin.priority()) {
            case 4 -> scale >= 1 / 40.0;
            case 3 -> scale >= 1 / 20.0;
            case 2 -> scale >= 1 / 5.0;
            default -> scale >= 1 / 2.5;
        };
    }

    private List<Component> pinTooltip(Pin pin, Player player) {
        List<Component> lines = new ArrayList<>();
        lines.add(pin.name().copy().withStyle(ChatFormatting.GOLD));
        lines.add(pin.kind().displayName().copy().withStyle(ChatFormatting.GRAY));
        Region region = pin.place() != null && !pin.place().regionId().isEmpty()
                ? Regions.byId(pin.place().regionId()).orElse(null) : null;
        if (region == null && minecraft.level.dimension() == Level.OVERWORLD) region = TamrielMap.region(pin.x(), pin.z());
        if (region != null && !Provinces.isSea(region)) {
            lines.add(region.name().copy().append(", ").append(Provinces.name(region.province())).withStyle(ChatFormatting.DARK_GRAY));
        }
        // the place's one-line lore description (places of the provinces, lang key place.skycraft.<id>.desc)
        String descKey = pin.place() != null ? "place.skycraft." + pin.place().id() + ".desc" : null;
        if (descKey != null && net.minecraft.client.resources.language.I18n.exists(descKey)) {
            for (String line : wrapWords(net.minecraft.client.resources.language.I18n.get(descKey), 46)) {
                lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        }
        // the place's story (lore module)
        if (pin.place() != null) lines.addAll(com.skycraft.lore.place.client.PlaceLoreClient.hookLines(pin.place().id(), font, 200));
        if (pin.cleared()) lines.add(Component.translatable("world.skycraft.map.cleared").withStyle(ChatFormatting.DARK_GREEN));
        double dx = pin.x() - player.getX();
        double dz = pin.z() - player.getZ();
        lines.add(Component.translatable("world.skycraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)).withStyle(ChatFormatting.DARK_GRAY));
        if (pin.known() || pin.loc() == null) {
            lines.add(Component.translatable("world.skycraft.map.undiscovered").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("world.skycraft.map.click_mark").withStyle(ChatFormatting.AQUA));
        } else {
            lines.add(Component.translatable("world.skycraft.map.click_travel").withStyle(ChatFormatting.YELLOW));
        }
        return lines;
    }

    /** Splits text into lines of at most {@code width} characters at spaces. */
    private static List<String> wrapWords(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private void drawQuestMarkers(GuiGraphics g, Level level, int mouseX, int mouseY) {
        ListTag markers = SkyData.get(minecraft.player).module("quest").getList("markers", Tag.TAG_COMPOUND);
        if (markers.isEmpty()) return;
        String dim = level.dimension().location().toString();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int i = 0; i < markers.size(); i++) {
            CompoundTag m = markers.getCompound(i);
            String mdim = m.getString("dim");
            if (!mdim.isEmpty() && !mdim.equals(dim)) continue;
            int sx = (int) Math.round(screenX(m.getDouble("x")));
            int sy = (int) Math.round(screenY(m.getDouble("z")));
            // pin markers that are off-map to the edge, like Skyrim does
            int cx = Mth.clamp(sx, left + 8, right - 8);
            int cy = Mth.clamp(sy, top + 14, bottom - 8);
            drawIcon(g, LocationKind.QUEST_ICON, cx, cy - 6, 16f);
            if (Math.abs(mouseX - cx) <= 7 && Math.abs(mouseY - (cy - 6)) <= 7) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(m.getString("label")).withStyle(ChatFormatting.YELLOW));
                lines.add(Component.translatable("world.skycraft.map.quest").withStyle(ChatFormatting.GRAY));
                tooltip = lines;
                hoveredPin = null;
            }
        }
        RenderSystem.disableBlend();
    }

    private void drawCustomWaypoint(GuiGraphics g, Player player, int mouseX, int mouseY) {
        if (customWaypoint == null) return;
        int sx = (int) Math.round(screenX(customWaypoint.getX() + 0.5));
        int sy = (int) Math.round(screenY(customWaypoint.getZ() + 0.5));
        int cx = Mth.clamp(sx, left + 8, right - 8);
        int cy = Mth.clamp(sy, top + 8, bottom - 8);
        RenderSystem.enableBlend();
        drawIcon(g, LocationKind.CUSTOM_ICON, cx, cy - 7, 14f);
        RenderSystem.disableBlend();
        if (Math.abs(mouseX - cx) <= 7 && Math.abs(mouseY - (cy - 7)) <= 7 && onMap(mouseX, mouseY)) {
            double dx = customWaypoint.getX() - player.getX();
            double dz = customWaypoint.getZ() - player.getZ();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("world.skycraft.map.custom").withStyle(ChatFormatting.AQUA));
            lines.add(Component.literal("X " + customWaypoint.getX() + "   Z " + customWaypoint.getZ()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("world.skycraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("world.skycraft.map.click_remove").withStyle(ChatFormatting.RED));
            tooltip = lines;
            hoveredPin = null;
        }
    }

    private void drawPlayers(GuiGraphics g, Level level, Player self, float partialTick, int mouseX, int mouseY) {
        for (Player other : level.players()) {
            if (other == self || other.isSpectator() || ClientQuestData.isMember(other.getUUID())) continue;
            int sx = (int) Math.round(screenX(other.getX()));
            int sy = (int) Math.round(screenY(other.getZ()));
            if (!onMap(sx, sy)) continue;
            g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF1A1A2A);
            g.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xFF5A8AD8);
            if (Math.abs(mouseX - sx) <= 5 && Math.abs(mouseY - sy) <= 5) {
                tooltip = List.of(other.getDisplayName());
                hoveredPin = null;
            }
        }
        // you: an arrow pointing where you look
        double x = Mth.lerp(partialTick, self.xo, self.getX());
        double z = Mth.lerp(partialTick, self.zo, self.getZ());
        float sx = (float) screenX(x);
        float sy = (float) screenY(z);
        float yaw = self.getViewYRot(partialTick);
        g.pose().pushPose();
        g.pose().translate(sx, sy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180f));
        arrow(g, 0xFF1E140A, 1);
        arrow(g, 0xFFC8282A, 0);
        g.pose().popPose();
    }

    /**
     * Party members (synced by the server, so visible at any distance) as green diamonds with their names;
     * members off the visible map are pinned to its edge with an arrow pointing towards them. Click to fast travel.
     */
    private void drawPartyMembers(GuiGraphics g, Level level, Player self, int mouseX, int mouseY) {
        if (!ClientQuestData.inParty()) return;
        String dim = level.dimension().location().toString();
        int color = 0xFF000000 | ClientQuestData.PARTY_COLOR;
        for (ClientQuestData.Member m : ClientQuestData.members()) {
            if (!m.online() || m.id().equals(self.getUUID()) || !m.dim().equals(dim)) continue;
            Vec3 pos = m.position();
            double sx = screenX(pos.x);
            double sy = screenY(pos.z);
            int cx = (int) Math.round(Mth.clamp(sx, left + 9, right - 9));
            int cy = (int) Math.round(Mth.clamp(sy, top + 9, bottom - 9));
            boolean pinned = cx != (int) Math.round(sx) || cy != (int) Math.round(sy);
            if (pinned) {
                float angle = (float) Math.atan2(sy - cy, sx - cx);
                g.pose().pushPose();
                g.pose().translate(cx, cy, 0);
                g.pose().mulPose(Axis.ZP.rotation(angle));
                for (int i = 0; i < 4; i++) g.fill(7 + i, -3 + i, 8 + i, 4 - i, 0xFF1E140A);
                for (int i = 0; i < 3; i++) g.fill(7 + i, -2 + i, 8 + i, 3 - i, color);
                g.pose().popPose();
            }
            diamond(g, cx, cy, 5, 0xFF1E140A);
            diamond(g, cx, cy, 4, color);
            diamond(g, cx, cy, 1, 0xFFF5EBC8);
            drawSmall(g, m.name(), cx - (int) (font.width(m.name()) * 0.3f), cy - 12, 0xFF000000 | INK);
            if (Math.abs(mouseX - cx) <= 6 && Math.abs(mouseY - cy) <= 6 && onMap(mouseX, mouseY)) {
                hoveredMember = m;
                hoveredPin = null;
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(m.name()).withStyle(ChatFormatting.GREEN));
                lines.add(Component.translatable("party.skycraft.map.member").withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal((int) (m.health() * 5) + " / " + (int) (m.maxHealth() * 5) + " HP").withStyle(ChatFormatting.RED));
                lines.add(Component.translatable("world.skycraft.map.distance", (int) Math.sqrt(self.distanceToSqr(pos))).withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.translatable("world.skycraft.map.click_travel").withStyle(ChatFormatting.YELLOW));
                tooltip = lines;
            }
        }
    }

    private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** Upward arrow with a notched tail (tip at y = -7), {@code grow} pixels bigger for the outline. */
    private static void arrow(GuiGraphics g, int color, int grow) {
        for (int y = -7 - grow; y <= 5 + grow; y++) {
            int half = (y + 7) / 2 + grow;
            int notch = y - 1 - grow;
            if (notch > 0) {
                g.fill(-half, y, -notch, y + 1, color);
                g.fill(notch + 1, y, half + 1, y + 1, color);
            } else {
                g.fill(-half, y, half + 1, y + 1, color);
            }
        }
    }

    private static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    private void drawVignette(GuiGraphics g) {
        int edge = 14;
        for (int i = 0; i < edge; i++) {
            int a = (int) (90 * (1f - i / (float) edge));
            int c = a << 24 | 0x2A1A08;
            g.fill(left + i, top, left + i + 1, bottom, c);
            g.fill(right - i - 1, top, right - i, bottom, c);
            g.fill(left, top + i, right, top + i + 1, c);
            g.fill(left, bottom - i - 1, right, bottom - i, c);
        }
    }

    // ------------------------------------------------------------------ header, footer, legend

    private void drawHeader(GuiGraphics g, Level level, Player player) {
        Component region;
        if (level.dimension() == Level.OVERWORLD) {
            Region r = Regions.at(level, player.blockPosition());
            region = Provinces.isSea(r) ? r.name() : r.name().copy().append(" · ").append(Provinces.name(r.province()));
        } else if (level.dimension() == Level.NETHER) {
            region = Component.translatable("world.skycraft.realm.oblivion.name");
        } else if (level.dimension() == Level.END) {
            region = Component.translatable("world.skycraft.realm.sovngarde.name");
        } else {
            String dim = level.dimension().location().getPath();
            region = Component.translatableWithFallback("region.skycraft.dimension." + dim, dim);
        }
        float s = width < 520 ? 1.15f : 1.4f;
        g.pose().pushPose();
        g.pose().translate(width / 2f, 5, 0);
        g.pose().scale(s, s, 1f);
        g.drawCenteredString(font, region, 0, 0, 0xFFF5EBC8);
        g.pose().popPose();
        long dayTime = level.getDayTime();
        Component when = SkyrimCalendar.date(dayTime).copy().append("   ").append(SkyrimCalendar.time(dayTime));
        float ws = 0.72f;
        drawScaled(g, when, width / 2f - font.width(when) * ws / 2f, 5 + 9 * s + 2, ws, 0xFFBDB59E);
    }

    private void drawFooter(GuiGraphics g, Level level, int mouseX, int mouseY) {
        float s = 0.7f;
        Component help = Component.translatable("world.skycraft.map.help_short");
        drawScaled(g, help, left, bottom + 5, s, 0xFF8A7F66);
        if (onMap(mouseX, mouseY)) {
            int wx = Mth.floor(worldX(mouseX));
            int wz = Mth.floor(worldZ(mouseY));
            Component where = Component.literal("X " + wx + "  Z " + wz);
            if (level.dimension() == Level.OVERWORLD) {
                Region r = TamrielMap.region(wx, wz);
                where = r.name().copy().append("   ").append(where);
            }
            drawScaled(g, where, right - font.width(where) * s, bottom + 5, s, 0xFFBDB59E);
        }
    }

    private static final LocationKind[] LEGEND = {
            LocationKind.CAPITAL, LocationKind.CITY, LocationKind.TOWN, LocationKind.FORT, LocationKind.BARROW,
            LocationKind.CAVE, LocationKind.MINE, LocationKind.RUIN, LocationKind.DWEMER, LocationKind.STANDING_STONE,
            LocationKind.WORD_WALL, LocationKind.DRAGON_MOUND, LocationKind.DAEDRIC_SHRINE, LocationKind.CAMP,
            LocationKind.TOWER, LocationKind.LANDMARK};

    private void drawLegend(GuiGraphics g) {
        int rows = (LEGEND.length + 2 + 1) / 2;
        int rowH = 11;
        int colW = Math.min(110, (right - left - 16) / 2);
        int w = colW * 2 + 10;
        int h = rows * rowH + 18;
        int x0 = left + 6;
        int y0 = bottom - h - 6;
        g.fill(x0, y0, x0 + w, y0 + h, 0xE0E8D8B4);
        outline(g, x0, y0, x0 + w, y0 + h, 0xFF6B5030);
        drawScaled(g, Component.translatable("world.skycraft.map.legend"), x0 + 5, y0 + 4, 0.8f, 0xFF000000 | INK);
        RenderSystem.enableBlend();
        int i = 0;
        for (LocationKind k : LEGEND) {
            int cx = x0 + 5 + (i % 2) * colW;
            int cy = y0 + 15 + (i / 2) * rowH;
            drawIcon(g, k.icon, cx + 5, cy + 4, 10f);
            drawScaled(g, k.displayName(), cx + 13, cy + 1, 0.7f, 0xFF000000 | INK);
            i++;
        }
        int[] extra = {LocationKind.QUEST_ICON, LocationKind.CUSTOM_ICON};
        String[] keys = {"world.skycraft.map.quest", "world.skycraft.map.custom"};
        for (int k = 0; k < extra.length; k++, i++) {
            int cx = x0 + 5 + (i % 2) * colW;
            int cy = y0 + 15 + (i / 2) * rowH;
            drawIcon(g, extra[k], cx + 5, cy + 4, 10f);
            drawScaled(g, Component.translatable(keys[k]), cx + 13, cy + 1, 0.7f, 0xFF000000 | INK);
        }
        RenderSystem.disableBlend();
    }

    private void drawTravelDialog(GuiGraphics g, Player player) {
        int cx = width / 2;
        int cy = height / 2;
        g.fill(cx - 120, cy - 34, cx + 120, cy + 38, 0xE0100C08);
        outline(g, cx - 120, cy - 34, cx + 120, cy + 38, 0xFF8A7F66);
        Component name;
        double dx, dz;
        if (pendingMember != null) {
            name = Component.literal(pendingMember.name());
            Vec3 pos = pendingMember.position();
            dx = pos.x - player.getX();
            dz = pos.z - player.getZ();
        } else {
            name = WorldData.displayName(pendingTravel);
            dx = pendingTravel.getInt("x") - player.getX();
            dz = pendingTravel.getInt("z") - player.getZ();
        }
        Component q = Component.translatable("world.skycraft.map.travel_confirm", name);
        g.drawCenteredString(font, q, cx, cy - 22, 0xFFF5EBC8);
        g.drawCenteredString(font, Component.translatable("world.skycraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)),
                cx, cy - 8, 0xFFA89F86);
    }
}
