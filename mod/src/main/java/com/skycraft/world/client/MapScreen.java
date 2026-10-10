package com.skycraft.world.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.skycraft.SkyConfig;
import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.core.Holds;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.QuestPackets;
import com.skycraft.quest.client.ClientQuestData;
import com.skycraft.world.LocationKind;
import com.skycraft.world.SkyrimCalendar;
import com.skycraft.world.WorldData;
import com.skycraft.world.WorldPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Skyrim world map on aged parchment: holds with their names, a coordinate grid, discovered locations (click to
 * fast travel), quest markers, you and the other players in this dimension. Drag to pan, scroll to zoom.
 * Terrain is intentionally not drawn (Xaero's World Map covers that).
 */
public class MapScreen extends Screen {
    private static final ResourceLocation PARCHMENT = new ResourceLocation(Skycraft.MODID, "textures/gui/map_parchment.png");
    private static final ResourceLocation ICONS = new ResourceLocation(Skycraft.MODID, "textures/gui/map_icons.png");
    private static final double MIN_SCALE = 1.0 / 64.0;
    private static final double MAX_SCALE = 4.0;
    private static final double DEFAULT_SCALE = 0.25;
    private static final int INK = 0x3A2A18;
    /** Subtle per-hold tints, indexed like {@link Holds#NAMES}. */
    private static final int[] HOLD_TINTS = {0xC8A040, 0xC06020, 0x6080A0, 0x8090B0, 0xA0B8D0, 0x7A9060, 0x507060, 0xA05040, 0x408050};

    private static double savedScale = DEFAULT_SCALE;
    public static BlockPos customWaypoint = null;

    private double centerX;
    private double centerZ;
    private double scale = savedScale;
    private boolean centered;
    private int left, top, right, bottom;
    private boolean pressedOnMap;
    private double dragDistance;
    private CompoundTag hoveredLocation;
    private CompoundTag pendingTravel;
    /** Party member under the cursor / chosen for fast travel (quest module's party sync). */
    private ClientQuestData.Member hoveredMember;
    private ClientQuestData.Member pendingMember;
    private List<Component> tooltip;
    private Button yesButton, noButton;

    public MapScreen() {
        super(Component.translatable("screen.skycraft.map"));
    }

    public static void resetView() {
        savedScale = DEFAULT_SCALE;
    }

    @Override
    protected void init() {
        left = 14;
        top = 30;
        right = width - 14;
        bottom = height - 26;
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
        if (button == 1 && onMap(mx, my)) {
            // Right-click clears custom waypoint
            if (customWaypoint != null) {
                customWaypoint = null;
                com.skycraft.client.hud.CompassMarkers.clear("custom_waypoint");
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.8f);
                }
                return true;
            }
        }
        if (button == 0 && onMap(mx, my)) {
            pressedOnMap = true;
            dragDistance = 0;
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
            if (dragDistance < 4 && hoveredMember != null) {
                pendingMember = hoveredMember;
                updateButtons();
            } else if (dragDistance < 4 && hoveredLocation != null && !hoveredLocation.getBoolean("known")) {
                pendingTravel = hoveredLocation;
                updateButtons();
            } else if (dragDistance < 4 && onMap(mx, my) && hoveredLocation == null && hoveredMember == null) {
                // If clicked close to existing custom waypoint, clear it
                if (customWaypoint != null && Math.abs(screenX(customWaypoint.getX() + 0.5) - mx) <= 8 && Math.abs(screenY(customWaypoint.getZ() + 0.5) - my) <= 8) {
                    customWaypoint = null;
                    com.skycraft.client.hud.CompassMarkers.clear("custom_waypoint");
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.8f);
                    }
                } else {
                    // Set custom waypoint at clicked location!
                    int wx = (int) Math.round(worldX(mx));
                    int wz = (int) Math.round(worldZ(my));
                    int wy = minecraft != null && minecraft.player != null ? minecraft.player.getBlockY() : 64;
                    customWaypoint = new BlockPos(wx, wy, wz);
                    com.skycraft.client.hud.CompassMarkers.set("custom_waypoint", List.of(
                            new com.skycraft.client.hud.CompassMarkers.Marker(Vec3.atBottomCenterOf(customWaypoint), com.skycraft.client.hud.CompassMarkers.Shape.QUEST, 0x38B0DE, "Custom Destination", 0)
                    ));
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
                    }
                }
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
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
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
            zoom(1.5, midX(), midY());
            return true;
        }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoom(1 / 1.5, midX(), midY());
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
        hoveredLocation = null;
        hoveredMember = null;

        // frame and paper
        g.fill(left - 5, top - 5, right + 5, bottom + 5, 0xFF1E140A);
        g.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFF6B5030);
        g.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF2A1D10);
        g.blit(PARCHMENT, left, top, right - left, bottom - top, 0f, 0f, 256, 256, 256, 256);

        g.enableScissor(left, top, right, bottom);
        // thousands of small fills: batch them into one draw instead of flushing after each
        g.drawManaged(() -> drawLandscape(g, level, player));
        drawHolds(g, level);
        drawGrid(g);
        g.drawManaged(() -> drawFog(g, level, player));
        drawLocations(g, level, player, mouseX, mouseY);
        drawQuestMarkers(g, level, mouseX, mouseY);
        drawCustomWaypoint(g, level, player, mouseX, mouseY);
        drawPlayers(g, level, player, partialTick, mouseX, mouseY);
        drawPartyMembers(g, level, player, mouseX, mouseY);
        g.disableScissor();
        drawVignette(g);

        drawHeader(g, level, player);
        drawFooter(g, mouseX, mouseY);

        if (pendingTravel != null || pendingMember != null) drawTravelDialog(g, player);
        super.render(g, mouseX, mouseY, partialTick);
        if (tooltip != null && pendingTravel == null && pendingMember == null) g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    }

    private void drawLandscape(GuiGraphics g, Level level, Player player) {
        if (level.dimension() != Level.OVERWORLD) return;
        int step = (int) Math.max(6, Math.min(24, 16 * scale));
        if (step <= 0) step = 12;

        for (int sy = top; sy < bottom; sy += step) {
            double wz = worldZ(sy + step / 2.0);
            int runStart = left, runColor = 0;
            for (int sx = left; sx < right; sx += step) {
                double wx = worldX(sx + step / 2.0);
                int bx = Mth.floor(wx);
                int bz = Mth.floor(wz);

                int color;
                if (level.hasChunk(bx >> 4, bz >> 4)) {
                    int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                    if (h <= 63) {
                        color = 0x551E4870; // fjord / river water
                    } else if (h > 120) {
                        int alpha = Math.min(180, 80 + (h - 120) * 3);
                        color = (alpha << 24) | 0xEDE8DC; // high mountain peak snow
                    } else if (h > 88) {
                        int alpha = Math.min(130, 40 + (h - 88) * 2);
                        color = (alpha << 24) | 0x8A7E72; // mountain slopes & crags
                    } else if (h > 72) {
                        color = 0x303E4A28; // pine hills & tundra uplands
                    } else {
                        color = 0x24425232; // fertile plains & river valleys
                    }
                } else {
                    double n = Math.sin(wx * 0.003) * Math.cos(wz * 0.003) + 0.5 * Math.sin(wx * 0.007 + wz * 0.005);
                    if (n > 0.5) {
                        color = 0x3882786A; // distant mountain range
                    } else if (n < -0.6) {
                        color = 0x35203858; // water body
                    } else {
                        color = 0x18485238; // forested valley
                    }
                }
                // one fill per horizontal run of equal colour
                if (sx == left) {
                    runColor = color;
                } else if (color != runColor) {
                    g.fill(runStart, sy, sx, Math.min(bottom, sy + step), runColor);
                    runStart = sx;
                    runColor = color;
                }
            }
            g.fill(runStart, sy, right, Math.min(bottom, sy + step), runColor);
        }
    }

    private void drawCustomWaypoint(GuiGraphics g, Level level, Player player, int mouseX, int mouseY) {
        if (customWaypoint == null) return;
        int sx = (int) Math.round(screenX(customWaypoint.getX() + 0.5));
        int sy = (int) Math.round(screenY(customWaypoint.getZ() + 0.5));
        if (sx < left - 10 || sx > right + 10 || sy < top - 10 || sy > bottom + 10) return;

        // Glowing Azure Skyrim Waypoint marker
        int color = 0xFF38B0DE;
        diamond(g, sx, sy, 7, 0xFF101820);
        diamond(g, sx, sy, 5, color);
        diamond(g, sx, sy, 2, 0xFFFFFFFF);

        // Downward pointer
        g.fill(sx - 1, sy + 5, sx + 2, sy + 9, color);
        g.fill(sx, sy + 9, sx + 1, sy + 11, color);

        if (Math.abs(mouseX - sx) <= 8 && Math.abs(mouseY - sy) <= 8 && onMap(mouseX, mouseY)) {
            double dx = customWaypoint.getX() - player.getX();
            double dz = customWaypoint.getZ() - player.getZ();
            int dist = (int) Math.sqrt(dx * dx + dz * dz);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Custom Destination").withStyle(ChatFormatting.AQUA));
            lines.add(Component.literal("X: " + customWaypoint.getX() + ", Z: " + customWaypoint.getZ()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("world.skycraft.map.distance", dist).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal("[Click or R-Click to Remove]").withStyle(ChatFormatting.RED));
            tooltip = lines;
        }
    }

    private void drawHolds(GuiGraphics g, Level level) {
        if (level.dimension() != Level.OVERWORLD) return;
        int size = SkyConfig.HOLD_SIZE.get();
        double px = size * scale;
        if (px < 10) return;
        int tx0 = Math.floorDiv(Mth.floor(worldX(left)), size);
        int tx1 = Math.floorDiv(Mth.floor(worldX(right)), size);
        int tz0 = Math.floorDiv(Mth.floor(worldZ(top)), size);
        int tz1 = Math.floorDiv(Mth.floor(worldZ(bottom)), size);
        if ((long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1) > 600) return;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                int x0 = Math.max(left, (int) Math.round(screenX((double) tx * size)));
                int x1 = Math.min(right, (int) Math.round(screenX((double) (tx + 1) * size)));
                int y0 = Math.max(top, (int) Math.round(screenY((double) tz * size)));
                int y1 = Math.min(bottom, (int) Math.round(screenY((double) (tz + 1) * size)));
                String hold = Holds.holdAt(level, new BlockPos(tx * size + size / 2, 64, tz * size + size / 2));
                int tint = HOLD_TINTS[baseIndex(hold) % HOLD_TINTS.length];
                if (x1 > x0 && y1 > y0) g.fill(x0, y0, x1, y1, 0x16000000 | tint);
            }
        }
        // dashed borders
        int border = 0x907A2E1A;
        for (int tx = tx0; tx <= tx1 + 1; tx++) {
            int sx = (int) Math.round(screenX((double) tx * size));
            if (sx < left || sx >= right) continue;
            for (int y = top; y < bottom; y += 7) g.fill(sx, y, sx + 1, Math.min(bottom, y + 4), border);
        }
        for (int tz = tz0; tz <= tz1 + 1; tz++) {
            int sy = (int) Math.round(screenY((double) tz * size));
            if (sy < top || sy >= bottom) continue;
            for (int x = left; x < right; x += 7) g.fill(x, sy, Math.min(right, x + 4), sy + 1, border);
        }
        // names
        if (px < 70) return;
        float textScale = px > 400 ? 2f : px > 180 ? 1.5f : 1f;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                String hold = Holds.holdAt(level, new BlockPos(tx * size + size / 2, 64, tz * size + size / 2));
                Component name = Holds.displayName(hold);
                // centre of the visible part of the hold, so its name stays readable while panning
                double vx0 = Math.max(left, screenX((double) tx * size));
                double vx1 = Math.min(right, screenX((double) (tx + 1) * size));
                double vy0 = Math.max(top, screenY((double) tz * size));
                double vy1 = Math.min(bottom, screenY((double) (tz + 1) * size));
                if (vx1 - vx0 < font.width(name) * textScale * 0.8 || vy1 - vy0 < 16) continue;
                float cx = (float) ((vx0 + vx1) / 2);
                float cy = (float) ((vy0 + vy1) / 2);
                g.pose().pushPose();
                g.pose().translate(cx, cy, 0);
                g.pose().scale(textScale, textScale, 1f);
                g.drawString(font, name, -font.width(name) / 2, -4, 0x90000000 | INK, false);
                g.pose().popPose();
            }
        }
    }

    private static int baseIndex(String holdId) {
        for (int i = 0; i < Holds.NAMES.length; i++) if (holdId.startsWith(Holds.NAMES[i])) return i;
        return 0;
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
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.6f, 0.6f, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void drawIcon(GuiGraphics g, int index, int cx, int cy) {
        int u = (index % 8) * 16;
        int v = (index / 8) * 16;
        g.blit(ICONS, cx - 8, cy - 8, (float) u, (float) v, 16, 16, 128, 64);
    }

    private void drawLocations(GuiGraphics g, Level level, Player player, int mouseX, int mouseY) {
        ListTag list = SkyData.get(player).module(WorldData.MODULE).getList("discovered", Tag.TAG_COMPOUND);
        String dim = level.dimension().location().toString();
        double best = 9 * 9;
        CompoundTag hovered = null;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            if (!loc.getString("dim").equals(dim)) continue;
            int sx = (int) Math.round(screenX(loc.getInt("x") + 0.5));
            int sy = (int) Math.round(screenY(loc.getInt("z") + 0.5));
            if (sx < left - 10 || sx > right + 10 || sy < top - 10 || sy > bottom + 10) continue;
            LocationKind kind = LocationKind.byId(loc.getString("type"));
            boolean knownOnly = loc.getBoolean("known");
            int iconIdx = knownOnly ? LocationKind.UNKNOWN_ICON : kind.icon;
            drawIcon(g, iconIdx, sx, sy);
            double d = (mouseX - sx) * (double) (mouseX - sx) + (mouseY - sy) * (double) (mouseY - sy);
            if (d < best && onMap(mouseX, mouseY)) {
                best = d;
                hovered = loc;
            }
        }
        RenderSystem.disableBlend();
        if (hovered != null) {
            hoveredLocation = hovered;
            int sx = (int) Math.round(screenX(hovered.getInt("x") + 0.5));
            int sy = (int) Math.round(screenY(hovered.getInt("z") + 0.5));
            outline(g, sx - 10, sy - 10, sx + 10, sy + 10, 0xC0000000 | INK);
            LocationKind kind = LocationKind.byId(hovered.getString("type"));
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hovered.getString("name")).withStyle(ChatFormatting.GOLD));
            lines.add(kind.displayName().copy().withStyle(ChatFormatting.GRAY));
            if (hovered.getBoolean("cleared")) lines.add(Component.translatable("world.skycraft.map.cleared").withStyle(ChatFormatting.DARK_GREEN));
            double dx = hovered.getInt("x") - player.getX();
            double dz = hovered.getInt("z") - player.getZ();
            lines.add(Component.translatable("world.skycraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)).withStyle(ChatFormatting.DARK_GRAY));
            if (hovered.getBoolean("known")) {
                lines.add(Component.translatable("world.skycraft.map.undiscovered").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.add(Component.translatable("world.skycraft.map.click_travel").withStyle(ChatFormatting.YELLOW));
            }
            tooltip = lines;
        }
    }

    /** Fog cells (world space) are this many blocks wide. */
    private static final int FOG_CELL_SHIFT = 5;
    private static final int FOG_REVEAL = 250;
    /** World cells within {@link #FOG_REVEAL} of a discovered location; rebuilt only when the discoveries change. */
    private static final LongOpenHashSet exploredCells = new LongOpenHashSet();
    private static long exploredStamp = Long.MIN_VALUE;

    private static void updateExplored(ListTag list, String dim) {
        long stamp = list.size();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            stamp = stamp * 31 + loc.getInt("x") * 7919L + loc.getInt("z") + (loc.getBoolean("known") ? 1 : 0);
        }
        stamp = stamp * 31 + dim.hashCode();
        if (stamp == exploredStamp) return;
        exploredStamp = stamp;
        exploredCells.clear();
        int cell = 1 << FOG_CELL_SHIFT;
        int r = FOG_REVEAL / cell + 1;
        long reveal2 = (long) FOG_REVEAL * FOG_REVEAL;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            if (!loc.getString("dim").equals(dim) || loc.getBoolean("known")) continue;
            int lx = loc.getInt("x"), lz = loc.getInt("z");
            int cx = lx >> FOG_CELL_SHIFT, cz = lz >> FOG_CELL_SHIFT;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long ox = ((long) (cx + dx) << FOG_CELL_SHIFT) + cell / 2 - lx;
                    long oz = ((long) (cz + dz) << FOG_CELL_SHIFT) + cell / 2 - lz;
                    if (ox * ox + oz * oz <= reveal2) exploredCells.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
    }

    private void drawFog(GuiGraphics g, Level level, Player player) {
        if (level.dimension() != Level.OVERWORLD) return;
        ListTag list = SkyData.get(player).module(WorldData.MODULE).getList("discovered", Tag.TAG_COMPOUND);
        updateExplored(list, level.dimension().location().toString());
        int step = 20;
        int fogColor = 0x70302214;

        for (int y = top; y < bottom; y += step) {
            int runStart = -1;
            for (int x = left; x < right; x += step) {
                double wx = worldX(x + step / 2.0);
                double wz = worldZ(y + step / 2.0);

                double pDx = wx - player.getX();
                double pDz = wz - player.getZ();
                boolean fogged = pDx * pDx + pDz * pDz >= 350.0 * 350.0
                        && !exploredCells.contains(ChunkPos.asLong(Mth.floor(wx) >> FOG_CELL_SHIFT, Mth.floor(wz) >> FOG_CELL_SHIFT));
                // one fill per horizontal run of fogged cells
                if (fogged && runStart < 0) runStart = x;
                if (!fogged && runStart >= 0) {
                    g.fill(runStart, y, x, Math.min(bottom, y + step), fogColor);
                    runStart = -1;
                }
            }
            if (runStart >= 0) g.fill(runStart, y, right, Math.min(bottom, y + step), fogColor);
        }
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
            int cy = Mth.clamp(sy, top + 8, bottom - 8);
            drawIcon(g, LocationKind.QUEST_ICON, cx, cy - 6);
            if (tooltip == null && Math.abs(mouseX - cx) <= 7 && Math.abs(mouseY - (cy - 6)) <= 7) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(m.getString("label")).withStyle(ChatFormatting.YELLOW));
                lines.add(Component.translatable("world.skycraft.map.quest").withStyle(ChatFormatting.GRAY));
                tooltip = lines;
            }
        }
        RenderSystem.disableBlend();
    }

    private void drawPlayers(GuiGraphics g, Level level, Player self, float partialTick, int mouseX, int mouseY) {
        for (Player other : level.players()) {
            if (other == self || other.isSpectator() || ClientQuestData.isMember(other.getUUID())) continue;
            int sx = (int) Math.round(screenX(other.getX()));
            int sy = (int) Math.round(screenY(other.getZ()));
            if (!onMap(sx, sy)) continue;
            g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF1A1A2A);
            g.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xFF5A8AD8);
            if (tooltip == null && Math.abs(mouseX - sx) <= 5 && Math.abs(mouseY - sy) <= 5) {
                tooltip = List.of(other.getDisplayName());
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
                hoveredLocation = null;
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

    private void drawHeader(GuiGraphics g, Level level, Player player) {
        Component region;
        if (level.dimension() == Level.OVERWORLD) {
            region = Holds.displayName(Holds.holdAt(level, player.blockPosition()));
        } else if (level.dimension() == Level.NETHER) {
            region = Component.translatable("world.skycraft.realm.oblivion.name");
        } else if (level.dimension() == Level.END) {
            region = Component.translatable("world.skycraft.realm.sovngarde.name");
        } else {
            region = Component.literal(level.dimension().location().getPath());
        }
        g.pose().pushPose();
        g.pose().translate(width / 2f, 8, 0);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.drawCenteredString(font, region, 0, 0, 0xFFF5EBC8);
        g.pose().popPose();
        long dayTime = level.getDayTime();
        g.drawString(font, SkyrimCalendar.date(dayTime), left, 12, 0xFFBDB59E);
        Component time = SkyrimCalendar.time(dayTime);
        g.drawString(font, time, right - font.width(time), 12, 0xFFE8C060);
    }

    private void drawFooter(GuiGraphics g, int mouseX, int mouseY) {
        String help = "[L-Click] Travel / Waypoint   [R-Click] Clear   [Scroll] Zoom   [Space] Recenter";
        g.drawString(font, help, left, bottom + 9, 0xFF8A7F66);
        if (onMap(mouseX, mouseY)) {
            String coords = "X " + Mth.floor(worldX(mouseX)) + "   Z " + Mth.floor(worldZ(mouseY));
            g.drawString(font, coords, right - font.width(coords), bottom + 9, 0xFFBDB59E);
        }
    }

    private void drawTravelDialog(GuiGraphics g, Player player) {
        int cx = width / 2;
        int cy = height / 2;
        g.fill(cx - 120, cy - 34, cx + 120, cy + 38, 0xE0100C08);
        outline(g, cx - 120, cy - 34, cx + 120, cy + 38, 0xFF8A7F66);
        String name;
        double dx, dz;
        if (pendingMember != null) {
            name = pendingMember.name();
            Vec3 pos = pendingMember.position();
            dx = pos.x - player.getX();
            dz = pos.z - player.getZ();
        } else {
            name = pendingTravel.getString("name");
            dx = pendingTravel.getInt("x") - player.getX();
            dz = pendingTravel.getInt("z") - player.getZ();
        }
        Component q = Component.translatable("world.skycraft.map.travel_confirm", name);
        g.drawCenteredString(font, q, cx, cy - 22, 0xFFF5EBC8);
        g.drawCenteredString(font, Component.translatable("world.skycraft.map.distance", (int) Math.sqrt(dx * dx + dz * dz)),
                cx, cy - 8, 0xFFA89F86);
    }
}
