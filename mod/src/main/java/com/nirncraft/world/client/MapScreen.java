package com.nirncraft.world.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.nirncraft.NirnCraft;
import com.nirncraft.client.SkyKeys;
import com.nirncraft.core.SkyData;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.quest.QuestPackets;
import com.nirncraft.quest.client.ClientQuestData;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.world.LocationKind;
import com.nirncraft.world.Provinces;
import com.nirncraft.world.SkyrimCalendar;
import com.nirncraft.world.WorldData;
import com.nirncraft.world.WorldPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

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
    private static final Identifier PARCHMENT = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/map_parchment.png");
    private static final Identifier ICONS = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/map_icons.png");
    private static final Identifier FOG = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "map_fog");
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
    private boolean devTpOpen;
    private int devTpX, devTpZ;
    private String devTpLabel = "";
    @org.jetbrains.annotations.Nullable
    private CompoundTag devTpPinLoc;

    /** A place or location drawn on the map. */
    private record Pin(int x, int z, int icon, int priority, Component name, LocationKind kind, boolean known,
                       boolean cleared, CompoundTag loc, Place place) {
    }

    public MapScreen() {
        super(Component.translatable("screen.nirncraft.map"));
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
        double step = 10 / scale;
        if (down(InputConstants.KEY_A) || down(InputConstants.KEY_LEFT)) centerX -= step;
        if (down(InputConstants.KEY_D) || down(InputConstants.KEY_RIGHT)) centerX += step;
        if (down(InputConstants.KEY_W) || down(InputConstants.KEY_UP)) centerZ -= step;
        if (down(InputConstants.KEY_S) || down(InputConstants.KEY_DOWN)) centerZ += step;
        if (down(InputConstants.KEY_Q) || down(InputConstants.KEY_PAGEDOWN)) zoom(1 / 1.06, midX(), midY());
        if (down(InputConstants.KEY_E) || down(InputConstants.KEY_PAGEUP)) zoom(1.06, midX(), midY());
    }

    private static boolean down(int key) {
        return InputConstants.isKeyDown(key);
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
            SkyNetwork.sendToServer(new WorldPackets.FastTravel(pendingTravel.getStringOr("id", "")));
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
        List<Component> draw(GuiGraphicsExtractor g, MapScreen map, int mouseX, int mouseY);
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
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        if (minecraft != null && com.nirncraft.devtest.DevTestState.isActive(minecraft.player) && handleDevMapClick(mx, my, button)) {
            return true;
        }
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
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (pressedOnMap && button == 0) {
            centerX -= dx / scale;
            centerZ -= dy / scale;
            dragDistance += Math.abs(dx) + Math.abs(dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0 && pressedOnMap) {
            pressedOnMap = false;
            if (dragDistance >= 4) return true;
            if (minecraft != null && com.nirncraft.devtest.DevTestState.isActive(minecraft.player)) {
                if (hoveredPin != null) {
                    devTpOpen = true;
                    devTpX = hoveredPin.x();
                    devTpZ = hoveredPin.z();
                    devTpLabel = hoveredPin.name().getString();
                    devTpPinLoc = hoveredPin.loc();
                    click(1.1f);
                    return true;
                } else if (onMap(mx, my)) {
                    devTpOpen = true;
                    devTpX = (int) Math.round(worldX(mx));
                    devTpZ = (int) Math.round(worldZ(my));
                    Region r = minecraft.level != null && minecraft.level.dimension() == Level.OVERWORLD
                            ? TamrielMap.region(devTpX, devTpZ) : null;
                    String rName = r != null ? r.name().getString() : "Tamriel";
                    devTpLabel = rName + " (" + devTpX + ", " + devTpZ + ")";
                    devTpPinLoc = null;
                    click(1.1f);
                    return true;
                }
            }
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
        return super.mouseReleased(event);
    }

    private int playerY() {
        return minecraft != null && minecraft.player != null ? minecraft.player.getBlockY() : 64;
    }

    private void setWaypoint(BlockPos pos, String label) {
        customWaypoint = pos;
        String text = label != null ? label : Component.translatable("world.nirncraft.map.custom").getString();
        com.nirncraft.client.hud.CompassMarkers.set("custom_waypoint", List.of(
                new com.nirncraft.client.hud.CompassMarkers.Marker(Vec3.atBottomCenterOf(pos), com.nirncraft.client.hud.CompassMarkers.Shape.QUEST,
                        0x38B0DE, text, 0)));
        click(1.2f);
    }

    private void clearWaypoint() {
        customWaypoint = null;
        com.nirncraft.client.hud.CompassMarkers.clear("custom_waypoint");
        click(0.8f);
    }

    private void click(float pitch) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, pitch);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
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
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (pendingTravel != null || pendingMember != null) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                confirmTravel();
                return true;
            }
            if (key == InputConstants.KEY_ESCAPE) {
                cancelTravel();
                return true;
            }
        }
        if (SkyKeys.MAP.matches(event)) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_C) {
            centerOnPlayer();
            return true;
        }
        if (key == InputConstants.KEY_L) {
            showLegend = !showLegend;
            return true;
        }
        if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
            zoom(1.5, midX(), midY());
            return true;
        }
        if (key == InputConstants.KEY_MINUS || key == org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_KP_MINUS) {
            zoom(1 / 1.5, midX(), midY());
            return true;
        }
        if (key == InputConstants.KEY_F && customWaypoint != null) {
            centerX = customWaypoint.getX();
            centerZ = customWaypoint.getZ();
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

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
        com.nirncraft.inventory.client.SkyUi.blit(g, PARCHMENT, left, top, right - left, bottom - top, 0f, 0f, 256, 256, 256, 256);

        g.enableScissor(left, top, right, bottom);
        if (overworld) {
            MapTiles.uploadFinished();
            drawTiles(g);
            if (com.nirncraft.devtest.DevTestState.isActive(player) && com.nirncraft.devtest.DevTestState.mapBiomeOverlay) {
                drawBiomeOverlay(g, mouseX, mouseY);
            }
            drawPaperGrain(g);
            if (!com.nirncraft.devtest.DevTestState.isActive(player) || !com.nirncraft.devtest.DevTestState.noFogOfWar) {
                drawFog(g);
            }
            // the planned road network (roads module), drawn over the fog like Skyrim's map shows every road
            com.nirncraft.roads.client.RoadMapLayer.draw(g, font, this::screenX, this::screenY, scale, left, top, right, bottom);
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
        if (com.nirncraft.devtest.DevTestState.isActive(player)) drawDevMapOverlayUi(g, player, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (tooltip != null && pendingTravel == null && pendingMember == null) g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ terrain tiles

    /**
     * Draws the painted map: every coarser level that is ready (so there is always something under a tile that is
     * still being painted), then the level matching the zoom. Only the target level and the overview are requested.
     */
    private void drawTiles(GuiGraphicsExtractor g) {
        int target = MapTiles.lodFor(1 / scale);
        
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
                    Identifier tex = request ? MapTiles.get(lod, tx, tz) : MapTiles.ready(lod, tx, tz) ? MapTiles.get(lod, tx, tz) : null;
                    if (tex == null) continue;
                    g.pose().pushMatrix();
                    g.pose().translate((float) screenX((double) tx * tb), (float) screenY((double) tz * tb));
                    // a hair larger so neighbouring tiles never leave a seam
                    float s = (size + 0.35f) / MapTiles.SIZE;
                    g.pose().scale(s, s);
                    com.nirncraft.inventory.client.SkyUi.blit(g, tex, 0, 0, 0f, 0f, MapTiles.SIZE, MapTiles.SIZE, MapTiles.SIZE, MapTiles.SIZE);
                    g.pose().popMatrix();
                }
            }
        }
    }

    /** The parchment's fibres and stains over the painted map (multiplied, so the colours stay). */
    private void drawPaperGrain(GuiGraphicsExtractor g) {
        
        g.blit(com.nirncraft.client.UiPipelines.MAP_GRAIN,PARCHMENT,left,top,0f,0f,right-left,bottom-top,right-left,bottom-top,256,256,-1);
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

    private void drawFog(GuiGraphicsExtractor g) {
        double vx0 = worldX(left), vx1 = worldX(right), vz0 = worldZ(top), vz1 = worldZ(bottom);
        double span = Math.max(vx1 - vx0, vz1 - vz0) * 1.6;
        int level = 0;
        while (level < ExploredMap.LEVELS - 1 && span / (1 << (ExploredMap.CELL_SHIFT + level)) > 320) level++;
        int cell = 1 << (ExploredMap.CELL_SHIFT + level);
        boolean inside = Fog.texture != null && Fog.level == level
                && vx0 >= Fog.x0 && vz0 >= Fog.z0 && vx1 <= Fog.x0 + (long) Fog.w * Fog.cell && vz1 <= Fog.z0 + (long) Fog.h * Fog.cell;
        if (!inside || Fog.version != ExploredMap.version()) rebuildFog(level, cell, (vx0 + vx1) / 2, (vz0 + vz1) / 2, span);
        if (Fog.texture == null) return;
        
        g.pose().pushMatrix();
        g.pose().translate((float) screenX(Fog.x0), (float) screenY(Fog.z0));
        float s = (float) (Fog.cell * scale);
        g.pose().scale(s, s);
        com.nirncraft.inventory.client.SkyUi.blit(g, FOG, 0, 0, 0f, 0f, Fog.w, Fog.h, Fog.w, Fog.h);
        g.pose().popMatrix();
    }

    private void rebuildFog(int level, int cell, double cx, double cz, double span) {
        int n = Math.max(8, Math.min(512, (int) Math.ceil(span / cell)));
        int x0 = Math.floorDiv((int) Math.floor(cx - n * cell / 2.0), cell) * cell;
        int z0 = Math.floorDiv((int) Math.floor(cz - n * cell / 2.0), cell) * cell;
        if (Fog.texture == null || Fog.w != n || Fog.h != n) {
            Fog.release();
            Fog.texture = new com.nirncraft.client.UiDynamicTexture("NirnCraft map exploration",n,n,false,true);
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
                img.setPixelABGR(i, j, alpha << 24 | abgrRgb);
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

    private void drawProvinceAndRegionNames(GuiGraphicsExtractor g) {
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

    private void drawLabel(GuiGraphicsExtractor g, Component text, int wx, int wz, float textScale, int color) {
        if ((color >>> 24) < 8) return;
        double sx = screenX(wx);
        double sy = screenY(wz);
        int w = font.width(text);
        if (sx + w * textScale < left || sx - w * textScale > right || sy < top - 20 || sy > bottom + 20) return;
        g.pose().pushMatrix();
        g.pose().translate((float) sx, (float) sy);
        g.pose().scale(textScale, textScale);
        g.text(font, text, -w / 2, -4, color, false);
        g.pose().popMatrix();
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

    private void drawGrid(GuiGraphicsExtractor g) {
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

    private void drawSmall(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        drawScaled(g, Component.literal(text), x, y, 0.6f, color);
    }

    private void drawScaled(GuiGraphicsExtractor g, Component text, float x, float y, float s, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        g.text(font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ places and locations

    private void drawIcon(GuiGraphicsExtractor g, int index, float cx, float cy, float size) {
        int u = (index % 8) * 16;
        int v = (index / 8) * 16;
        g.pose().pushMatrix();
        g.pose().translate(cx - size / 2, cy - size / 2);
        g.pose().scale(size / 16f, size / 16f);
        com.nirncraft.inventory.client.SkyUi.blit(g, ICONS, 0, 0, (float) u, (float) v, 16, 16, 128, 64);
        g.pose().popMatrix();
    }
    private void drawIcon(GuiGraphicsExtractor g,int index,float cx,float cy,float size,int color) {
        g.pose().pushMatrix();
        g.pose().translate(cx-size/2,cy-size/2);
        g.pose().scale(size/16,size/16);
        com.nirncraft.inventory.client.SkyUi.blit(g,ICONS,0,0,(index%8)*16f,(index/8)*16f,16,16,128,64,color);
        g.pose().popMatrix();
    }


    /** Every pin to draw: discovered and rumored locations, plus the capitals and cities everyone knows. */
    private List<Pin> pins(Level level, Player player) {
        ListTag list = SkyData.get(player).module(WorldData.MODULE).getListOrEmpty("discovered");
        String dim = level.dimension().identifier().toString();
        List<Pin> out = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        boolean devUnlock = com.nirncraft.devtest.DevTestState.isActive(player)
                && com.nirncraft.devtest.DevTestState.unlockAllMap;
        boolean devAllLocs = com.nirncraft.devtest.DevTestState.isActive(player)
                && com.nirncraft.devtest.DevTestState.mapShowAllLocations;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompoundOrEmpty(i);
            if (!loc.getStringOr("dim", "").equals(dim)) continue;
            if (!devAllLocs && loc.getBooleanOr("hidden", false)) continue; // a stranger's homestead not yet discovered: a point on the compass only
            LocationKind kind = LocationKind.byId(loc.getStringOr("type", ""));
            Place place = WorldData.place(loc).orElse(null);
            if (place != null) listed.add(place.id());
            boolean rumoredOnly = !devUnlock && loc.getBooleanOr("known", false);
            out.add(new Pin(loc.getIntOr("x", 0), loc.getIntOr("z", 0), kind.icon, priority(place, kind), WorldData.displayName(loc), kind,
                    rumoredOnly, loc.getBooleanOr("cleared", false), loc, place));
        }
        if (level.dimension() == Level.OVERWORLD) {
            for (Place p : Places.all()) {
                if (listed.contains(p.id())) continue;
                if (!devAllLocs && !devUnlock && p.type() != PlaceType.CAPITAL && p.type() != PlaceType.CITY) continue;
                LocationKind kind = LocationKind.forPlace(p);
                CompoundTag synthLoc = null;
                if (devUnlock) {
                    synthLoc = new CompoundTag();
                    synthLoc.putString("id", p.id());
                    synthLoc.putInt("x", p.x());
                    synthLoc.putInt("z", p.z());
                    synthLoc.putString("dim", dim);
                    synthLoc.putString("type", kind.id);
                }
                out.add(new Pin(p.x(), p.z(), kind.icon, priority(p, kind), p.name(), kind, !devUnlock, false, synthLoc, p));
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

    private void drawPins(GuiGraphicsExtractor g, Level level, Player player, int mouseX, int mouseY) {
        List<Pin> pins = pins(level, player);
        List<float[]> drawn = new ArrayList<>();
        double best = 9 * 9;
        Pin hovered = null;
        float iconSize = scale < 1 / 30.0 ? 11f : scale < 1 / 8.0 ? 13f : 16f;
        
        boolean devAllLocs = com.nirncraft.devtest.DevTestState.isActive(player)
                && com.nirncraft.devtest.DevTestState.mapShowAllLocations;
        for (Pin pin : pins) {
            if (!devAllLocs && scale < minScale(pin.priority())) continue;
            if (devAllLocs && pin.priority() <= 1 && scale < 1 / 64.0) continue;
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
            drawIcon(g,pin.known()&&pin.place()==null?LocationKind.UNKNOWN_ICON:pin.icon(),sx,sy,iconSize,pin.known()?0xe66b5c4d:-1);
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
        // the place's one-line lore description (places of the provinces, lang key place.nirncraft.<id>.desc)
        String descKey = pin.place() != null ? "place.nirncraft." + pin.place().id() + ".desc" : null;
        if (descKey != null && net.minecraft.locale.Language.getInstance().has(descKey)) {
            for (String line : wrapWords(net.minecraft.client.resources.language.I18n.get(descKey), 46)) {
                lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        }
        // the place's story (lore module)
        if (pin.place() != null) lines.addAll(com.nirncraft.lore.place.client.PlaceLoreClient.hookLines(pin.place().id(), font, 200));
        if (pin.cleared()) lines.add(Component.translatable("world.nirncraft.map.cleared").withStyle(ChatFormatting.DARK_GREEN));
        double dx = pin.x() - player.getX();
        double dz = pin.z() - player.getZ();
        lines.add(Component.translatable("world.nirncraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)).withStyle(ChatFormatting.DARK_GRAY));
        if (pin.known() || pin.loc() == null) {
            lines.add(Component.translatable("world.nirncraft.map.undiscovered").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("world.nirncraft.map.click_mark").withStyle(ChatFormatting.AQUA));
        } else {
            lines.add(Component.translatable("world.nirncraft.map.click_travel").withStyle(ChatFormatting.YELLOW));
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

    private void drawQuestMarkers(GuiGraphicsExtractor g, Level level, int mouseX, int mouseY) {
        ListTag markers = SkyData.get(minecraft.player).module("quest").getListOrEmpty("markers");
        if (markers.isEmpty()) return;
        String dim = level.dimension().identifier().toString();
        
        for (int i = 0; i < markers.size(); i++) {
            CompoundTag m = markers.getCompoundOrEmpty(i);
            String mdim = m.getStringOr("dim", "");
            if (!mdim.isEmpty() && !mdim.equals(dim)) continue;
            int sx = (int) Math.round(screenX(m.getDoubleOr("x", 0d)));
            int sy = (int) Math.round(screenY(m.getDoubleOr("z", 0d)));
            // pin markers that are off-map to the edge, like Skyrim does
            int cx = Mth.clamp(sx, left + 8, right - 8);
            int cy = Mth.clamp(sy, top + 14, bottom - 8);
            drawIcon(g, LocationKind.QUEST_ICON, cx, cy - 6, 16f);
            if (Math.abs(mouseX - cx) <= 7 && Math.abs(mouseY - (cy - 6)) <= 7) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(m.getStringOr("label", "")).withStyle(ChatFormatting.YELLOW));
                lines.add(Component.translatable("world.nirncraft.map.quest").withStyle(ChatFormatting.GRAY));
                tooltip = lines;
                hoveredPin = null;
            }
        }
        
    }

    private void drawCustomWaypoint(GuiGraphicsExtractor g, Player player, int mouseX, int mouseY) {
        if (customWaypoint == null) return;
        int sx = (int) Math.round(screenX(customWaypoint.getX() + 0.5));
        int sy = (int) Math.round(screenY(customWaypoint.getZ() + 0.5));
        int cx = Mth.clamp(sx, left + 8, right - 8);
        int cy = Mth.clamp(sy, top + 8, bottom - 8);
        
        drawIcon(g, LocationKind.CUSTOM_ICON, cx, cy - 7, 14f);
        
        if (Math.abs(mouseX - cx) <= 7 && Math.abs(mouseY - (cy - 7)) <= 7 && onMap(mouseX, mouseY)) {
            double dx = customWaypoint.getX() - player.getX();
            double dz = customWaypoint.getZ() - player.getZ();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("world.nirncraft.map.custom").withStyle(ChatFormatting.AQUA));
            lines.add(Component.literal("X " + customWaypoint.getX() + "   Z " + customWaypoint.getZ()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("world.nirncraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("world.nirncraft.map.click_remove").withStyle(ChatFormatting.RED));
            tooltip = lines;
            hoveredPin = null;
        }
    }

    private void drawPlayers(GuiGraphicsExtractor g, Level level, Player self, float partialTick, int mouseX, int mouseY) {
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
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.pose().rotate((yaw + 180f) * Mth.DEG_TO_RAD);
        arrow(g, 0xFF1E140A, 1);
        arrow(g, 0xFFC8282A, 0);
        g.pose().popMatrix();
    }

    /**
     * Party members (synced by the server, so visible at any distance) as green diamonds with their names;
     * members off the visible map are pinned to its edge with an arrow pointing towards them. Click to fast travel.
     */
    private void drawPartyMembers(GuiGraphicsExtractor g, Level level, Player self, int mouseX, int mouseY) {
        if (!ClientQuestData.inParty()) return;
        String dim = level.dimension().identifier().toString();
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
                g.pose().pushMatrix();
                g.pose().translate(cx, cy);
                g.pose().rotate(angle);
                for (int i = 0; i < 4; i++) g.fill(7 + i, -3 + i, 8 + i, 4 - i, 0xFF1E140A);
                for (int i = 0; i < 3; i++) g.fill(7 + i, -2 + i, 8 + i, 3 - i, color);
                g.pose().popMatrix();
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
                lines.add(Component.translatable("party.nirncraft.map.member").withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal((int) (m.health() * 5) + " / " + (int) (m.maxHealth() * 5) + " HP").withStyle(ChatFormatting.RED));
                lines.add(Component.translatable("world.nirncraft.map.distance", (int) Math.sqrt(self.distanceToSqr(pos))).withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.translatable("world.nirncraft.map.click_travel").withStyle(ChatFormatting.YELLOW));
                tooltip = lines;
            }
        }
    }

    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** Upward arrow with a notched tail (tip at y = -7), {@code grow} pixels bigger for the outline. */
    private static void arrow(GuiGraphicsExtractor g, int color, int grow) {
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

    private static void outline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    private void drawVignette(GuiGraphicsExtractor g) {
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

    private void drawHeader(GuiGraphicsExtractor g, Level level, Player player) {
        Component region;
        if (level.dimension() == Level.OVERWORLD) {
            Region r = Regions.at(level, player.blockPosition());
            region = Provinces.isSea(r) ? r.name() : r.name().copy().append(" · ").append(Provinces.name(r.province()));
        } else if (level.dimension() == Level.NETHER) {
            region = Component.translatable("world.nirncraft.realm.oblivion.name");
        } else if (level.dimension() == Level.END) {
            region = Component.translatable("world.nirncraft.realm.sovngarde.name");
        } else {
            String dim = level.dimension().identifier().getPath();
            region = Component.translatableWithFallback("region.nirncraft.dimension." + dim, dim);
        }
        float s = width < 520 ? 1.15f : 1.4f;
        g.pose().pushMatrix();
        g.pose().translate(width / 2f, 5);
        g.pose().scale(s, s);
        g.centeredText(font, region, 0, 0, 0xFFF5EBC8);
        g.pose().popMatrix();
        long dayTime = level.getOverworldClockTime();
        Component when = SkyrimCalendar.date(dayTime).copy().append("   ").append(SkyrimCalendar.time(dayTime));
        float ws = 0.72f;
        drawScaled(g, when, width / 2f - font.width(when) * ws / 2f, 5 + 9 * s + 2, ws, 0xFFBDB59E);
    }

    private void drawFooter(GuiGraphicsExtractor g, Level level, int mouseX, int mouseY) {
        float s = 0.7f;
        Component help = Component.translatable("world.nirncraft.map.help_short");
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

    private void drawLegend(GuiGraphicsExtractor g) {
        int rows = (LEGEND.length + 2 + 1) / 2;
        int rowH = 11;
        int colW = Math.min(110, (right - left - 16) / 2);
        int w = colW * 2 + 10;
        int h = rows * rowH + 18;
        int x0 = left + 6;
        int y0 = bottom - h - 6;
        g.fill(x0, y0, x0 + w, y0 + h, 0xE0E8D8B4);
        outline(g, x0, y0, x0 + w, y0 + h, 0xFF6B5030);
        drawScaled(g, Component.translatable("world.nirncraft.map.legend"), x0 + 5, y0 + 4, 0.8f, 0xFF000000 | INK);
        
        int i = 0;
        for (LocationKind k : LEGEND) {
            int cx = x0 + 5 + (i % 2) * colW;
            int cy = y0 + 15 + (i / 2) * rowH;
            drawIcon(g, k.icon, cx + 5, cy + 4, 10f);
            drawScaled(g, k.displayName(), cx + 13, cy + 1, 0.7f, 0xFF000000 | INK);
            i++;
        }
        int[] extra = {LocationKind.QUEST_ICON, LocationKind.CUSTOM_ICON};
        String[] keys = {"world.nirncraft.map.quest", "world.nirncraft.map.custom"};
        for (int k = 0; k < extra.length; k++, i++) {
            int cx = x0 + 5 + (i % 2) * colW;
            int cy = y0 + 15 + (i / 2) * rowH;
            drawIcon(g, extra[k], cx + 5, cy + 4, 10f);
            drawScaled(g, Component.translatable(keys[k]), cx + 13, cy + 1, 0.7f, 0xFF000000 | INK);
        }
        
    }

    private void drawTravelDialog(GuiGraphicsExtractor g, Player player) {
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
            dx = pendingTravel.getIntOr("x", 0) - player.getX();
            dz = pendingTravel.getIntOr("z", 0) - player.getZ();
        }
        Component q = Component.translatable("world.nirncraft.map.travel_confirm", name);
        g.centeredText(font, q, cx, cy - 22, 0xFFF5EBC8);
        g.centeredText(font, Component.translatable("world.nirncraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)),
                cx, cy - 8, 0xFFA89F86);
    }

    // ------------------------------------------------------------------ Dev / Creative Map Overlays & Safe Teleport

    private void drawBiomeOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int stepPx = 12;
        for (int sy = top; sy < bottom; sy += stepPx) {
            int sy2 = Math.min(bottom, sy + stepPx);
            int wz = Mth.floor(worldZ(sy + stepPx * 0.5));
            for (int sx = left; sx < right; sx += stepPx) {
                int sx2 = Math.min(right, sx + stepPx);
                int wx = Mth.floor(worldX(sx + stepPx * 0.5));
                Region r = TamrielMap.region(wx, wz);
                if (r == null || Provinces.isSea(r)) continue;
                int hash = r.id().hashCode();
                int rgb = ((hash & 0x7F) + 80) << 16 | (((hash >> 8) & 0x7F) + 80) << 8 | (((hash >> 16) & 0x7F) + 80);
                g.fill(sx, sy, sx2, sy2, 0x50000000 | rgb);
            }
        }
    }

    private void drawDevMapOverlayUi(GuiGraphicsExtractor g, Player player, int mouseX, int mouseY) {
        // Top-left Dev Overlay Toggle Pills inside map header
        String[] labels = {
                "Fog: " + (com.nirncraft.devtest.DevTestState.noFogOfWar ? "OFF" : "ON"),
                "Biomes: " + (com.nirncraft.devtest.DevTestState.mapBiomeOverlay ? "ON" : "OFF"),
                "All Locs: " + (com.nirncraft.devtest.DevTestState.mapShowAllLocations ? "ON" : "OFF"),
                "All Roads: " + (com.nirncraft.devtest.DevTestState.mapShowAllRoads ? "ON" : "OFF")
        };
        boolean[] active = {
                com.nirncraft.devtest.DevTestState.noFogOfWar,
                com.nirncraft.devtest.DevTestState.mapBiomeOverlay,
                com.nirncraft.devtest.DevTestState.mapShowAllLocations,
                com.nirncraft.devtest.DevTestState.mapShowAllRoads
        };
        int px = left + 4;
        int py = top + 4;
        for (int i = 0; i < labels.length; i++) {
            int pw = font.width(labels[i]) + 10;
            boolean hov = mouseX >= px && mouseX < px + pw && mouseY >= py && mouseY < py + 14;
            com.nirncraft.inventory.client.SkyUi.panel(g, px, py, px + pw, py + 14);
            int col = hov ? com.nirncraft.inventory.client.SkyUi.BRIGHT
                    : (active[i] ? com.nirncraft.inventory.client.SkyUi.GOLD : com.nirncraft.inventory.client.SkyUi.DIM);
            g.text(font, labels[i], px + 5, py + 3, col, false);
            px += pw + 4;
        }

        // Dev Teleport & Fast Travel Action Bar when a point or pin was clicked on the map
        if (devTpOpen) {
            int sx = (int) Math.round(screenX(devTpX + 0.5));
            int sy = (int) Math.round(screenY(devTpZ + 0.5));
            if (sx >= left && sx < right && sy >= top && sy < bottom) {
                diamond(g, sx, sy, 6, 0xFF1E140A);
                diamond(g, sx, sy, 5, com.nirncraft.inventory.client.SkyUi.GOLD);
            }

            int barW = Math.min(width - 28, 430);
            int barH = 42;
            int bx = (width - barW) / 2;
            int by = bottom - barH - 6;
            com.nirncraft.inventory.client.SkyUi.panel(g, bx, by, bx + barW, by + barH);
            String title = "TELEPORT / FAST TRAVEL: " + devTpLabel;
            g.centeredText(font, Component.literal(title), width / 2, by + 5, com.nirncraft.inventory.client.SkyUi.GOLD);

            String[] actions = {
                    "Safe Surface",
                    "Above (+35m)",
                    "Aerial (+120m)",
                    devTpPinLoc != null ? "Fast Travel" : "Set Waypoint",
                    "Close"
            };
            int btnY = by + 20;
            int totalW = 0;
            int[] btnW = new int[actions.length];
            for (int i = 0; i < actions.length; i++) {
                btnW[i] = font.width(actions[i]) + 12;
                totalW += btnW[i] + (i > 0 ? 4 : 0);
            }
            int curX = width / 2 - totalW / 2;
            for (int i = 0; i < actions.length; i++) {
                int w = btnW[i];
                boolean hov = mouseX >= curX && mouseX < curX + w && mouseY >= btnY && mouseY < btnY + 16;
                g.fill(curX, btnY, curX + w, btnY + 16, hov ? 0x44E8C060 : 0x24FFFFFF);
                com.nirncraft.inventory.client.SkyUi.hline(g, curX, curX + w, btnY, hov ? 0xE0 : 0x70);
                com.nirncraft.inventory.client.SkyUi.hline(g, curX, curX + w, btnY + 15, hov ? 0xE0 : 0x70);
                g.centeredText(font, Component.literal(actions[i]), curX + w / 2, btnY + 4,
                        hov ? com.nirncraft.inventory.client.SkyUi.BRIGHT : com.nirncraft.inventory.client.SkyUi.TEXT);
                curX += w + 4;
            }
        }
    }

    private boolean handleDevMapClick(double mx, double my, int button) {
        if (button != 0) return false;
        // 1. Check top-left overlay toggle pills
        String[] labels = {
                "Fog: " + (com.nirncraft.devtest.DevTestState.noFogOfWar ? "OFF" : "ON"),
                "Biomes: " + (com.nirncraft.devtest.DevTestState.mapBiomeOverlay ? "ON" : "OFF"),
                "All Locs: " + (com.nirncraft.devtest.DevTestState.mapShowAllLocations ? "ON" : "OFF"),
                "All Roads: " + (com.nirncraft.devtest.DevTestState.mapShowAllRoads ? "ON" : "OFF")
        };
        int px = left + 4;
        int py = top + 4;
        for (int i = 0; i < labels.length; i++) {
            int pw = font.width(labels[i]) + 10;
            if (mx >= px && mx < px + pw && my >= py && my < py + 14) {
                switch (i) {
                    case 0 -> com.nirncraft.devtest.DevTestState.noFogOfWar = !com.nirncraft.devtest.DevTestState.noFogOfWar;
                    case 1 -> com.nirncraft.devtest.DevTestState.mapBiomeOverlay = !com.nirncraft.devtest.DevTestState.mapBiomeOverlay;
                    case 2 -> com.nirncraft.devtest.DevTestState.mapShowAllLocations = !com.nirncraft.devtest.DevTestState.mapShowAllLocations;
                    case 3 -> com.nirncraft.devtest.DevTestState.mapShowAllRoads = !com.nirncraft.devtest.DevTestState.mapShowAllRoads;
                }
                click(1.0f);
                return true;
            }
            px += pw + 4;
        }

        // 2. Check Dev Teleport Bar buttons if open
        if (devTpOpen) {
            int barW = Math.min(width - 28, 430);
            int barH = 42;
            int bx = (width - barW) / 2;
            int by = bottom - barH - 6;
            String[] actions = {
                    "Safe Surface",
                    "Above (+35m)",
                    "Aerial (+120m)",
                    devTpPinLoc != null ? "Fast Travel" : "Set Waypoint",
                    "Close"
            };
            int btnY = by + 20;
            int totalW = 0;
            int[] btnW = new int[actions.length];
            for (int i = 0; i < actions.length; i++) {
                btnW[i] = font.width(actions[i]) + 12;
                totalW += btnW[i] + (i > 0 ? 4 : 0);
            }
            int curX = width / 2 - totalW / 2;
            for (int i = 0; i < actions.length; i++) {
                int w = btnW[i];
                if (mx >= curX && mx < curX + w && my >= btnY && my < btnY + 16) {
                    switch (i) {
                        case 0 -> {
                            com.nirncraft.devtest.DevTestClient.sendTeleportMapSafe(devTpX, devTpZ, com.nirncraft.devtest.DevTestCommands.TP_SURFACE_SAFE);
                            devTpOpen = false;
                            onClose();
                        }
                        case 1 -> {
                            com.nirncraft.devtest.DevTestClient.sendTeleportMapSafe(devTpX, devTpZ, com.nirncraft.devtest.DevTestCommands.TP_ABOVE_GROUND);
                            devTpOpen = false;
                            onClose();
                        }
                        case 2 -> {
                            com.nirncraft.devtest.DevTestClient.sendTeleportMapSafe(devTpX, devTpZ, com.nirncraft.devtest.DevTestCommands.TP_HIGH_AERIAL);
                            devTpOpen = false;
                            onClose();
                        }
                        case 3 -> {
                            if (devTpPinLoc != null && !devTpPinLoc.getStringOr("id", "").isEmpty()) {
                                com.nirncraft.devtest.DevTestClient.sendTeleportMapSafe(devTpX, devTpZ, com.nirncraft.devtest.DevTestCommands.TP_SURFACE_SAFE);
                                devTpOpen = false;
                                onClose();
                            } else {
                                setWaypoint(new BlockPos(devTpX, playerY(), devTpZ), devTpLabel);
                                devTpOpen = false;
                            }
                        }
                        case 4 -> devTpOpen = false;
                    }
                    return true;
                }
                curX += w + 4;
            }
            if (mx >= bx && mx < bx + barW && my >= by && my < by + barH) {
                return true;
            }
        }
        return false;
    }
}
