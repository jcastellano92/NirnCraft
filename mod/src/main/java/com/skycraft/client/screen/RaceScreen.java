package com.skycraft.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.skycraft.client.RaceSkins;
import com.skycraft.core.Race;
import com.skycraft.core.Skill;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import com.skycraft.society.NpcLook;
import com.skycraft.society.NpcNames;
import com.skycraft.society.Outfit;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.world.client.MapTiles;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Skyrim-styled character creator: every playable race, grouped as the peoples of Tamriel, of Akavir and the lost
 * races, in a scrolling list; a live preview of the player that can be turned all the way round; a Lore tab (homeland
 * on a little map of Nirn, the race's story, skill bonuses, passive abilities, greater power) and an Appearance tab
 * (sub-people, sex, skin, hair, beard, clothes). Fits 427x267 (Steam Deck at GUI scale 3) and larger.
 */
public class RaceScreen extends Screen {
    private static final int TEXT_COLOR = 0xFFE8E0C8;
    private static final int BRIGHT_COLOR = 0xFFFFFFFF;
    private static final int GOLD_COLOR = 0xFFE8C060;
    private static final int DIM_COLOR = 0xFF8A8478;
    private static final int ROW = 12;
    /** Where the player wakes (Helgen), shown on the homeland map. */
    private static final int START_X = -24, START_Z = 1143;
    /** The homeland map: whole width of the known world, {@link #MAP_BPP} blocks per pixel. */
    private static final int MAP_MIN_X = -22000, MAP_MAX_X = 25000, MAP_MIN_Z = -14000, MAP_MAX_Z = 23000;

    private static Race preselected;
    private static boolean preselectLook;

    /** List rows: a group header (race null) or a race. */
    private record Row(Race.Group group, Race race) {}

    private final List<Row> rows = new ArrayList<>();
    private Race selected = Race.NORD;
    private boolean lookTab;
    private float listScroll, infoScroll;
    private int maxListScroll, maxInfoScroll;
    private float modelYaw = -25f;
    private boolean isDraggingModel;
    private double lastDragX;

    // appearance
    private int raceVariant, tone, hairIdx, colorIdx, beard, outfitIdx;
    private boolean female;

    // layout (computed in init)
    private int listX, listY, listW, listH, infoX, infoY, infoW, infoH, modelX, modelY, modelScale;
    private int btnX, btnY, btnW, btnH;

    public RaceScreen() {
        super(Component.translatable("screen.skycraft.race"));
        for (Race.Group group : Race.Group.values()) {
            rows.add(new Row(group, null));
            for (Race r : Race.VALUES) if (r.group == group) rows.add(new Row(group, r));
        }
        if (preselected != null) selected = preselected;
        lookTab = preselectLook;
        preselected = null;
        preselectLook = false;
        resetLook();
    }

    /** The race the next character creator opens on (dev screenshots), optionally on the Appearance tab. */
    public static void preselect(Race race, boolean appearance) {
        preselected = race;
        preselectLook = appearance;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // First-time character creation cannot be cancelled without choosing a race
        return false;
    }

    @Override
    protected void init() {
        listX = 8;
        listY = 26;
        listW = Mth.clamp(width / 4, 96, 130);
        listH = height - listY - 8;
        infoW = Mth.clamp((int) (width * 0.36f), 150, 230);
        infoX = width - infoW - 10;
        infoY = 26;
        btnW = Math.min(120, infoW);
        btnH = 18;
        btnX = infoX + (infoW - btnW) / 2;
        btnY = height - btnH - 6;
        infoH = btnY - 6 - infoY;
        modelX = (listX + listW + infoX) / 2;
        modelY = height - 34;
        modelScale = Math.min(100, (int) ((height - 80) / 2.5f));
        updatePreview();
        scrollListTo(selected);
    }

    @Override
    public void removed() {
        super.removed();
        RaceSkins.setPreviewLook(NpcLook.UNSET);
    }

    // ------------------------------------------------------------------ look

    private NpcNames.Race npcRace() {
        return NpcNames.Race.of(selected);
    }

    private int[] hairOptions() {
        return NpcLook.hairStyles(npcRace(), female);
    }

    private int[] colorOptions() {
        return NpcLook.hairColors(npcRace());
    }

    private boolean hasBeard() {
        return !female && NpcLook.bearded(npcRace());
    }

    private int look() {
        int[] hairs = hairOptions();
        int[] colors = colorOptions();
        Outfit outfit = NpcLook.playerOutfits(npcRace())[outfitIdx];
        return NpcLook.pack(npcRace(), raceVariant, female, tone, hairs[Math.floorMod(hairIdx, hairs.length)],
                colors[Math.floorMod(colorIdx, colors.length)], hasBeard() ? beard : 0, outfit, 0, false);
    }

    /** The race's default look for this player (as the server would give it). */
    private void resetLook() {
        long seed = minecraft != null && minecraft.player != null ? minecraft.player.getUUID().getLeastSignificantBits()
                : net.minecraft.client.Minecraft.getInstance().player != null
                ? net.minecraft.client.Minecraft.getInstance().player.getUUID().getLeastSignificantBits() : 0L;
        adopt(NpcLook.defaultFor(selected, seed), false);
    }

    /** Takes a look's fields as the current choices. */
    private void adopt(int look, boolean keepSex) {
        if (!keepSex) female = NpcLook.female(look);
        raceVariant = NpcLook.raceVariant(look);
        tone = NpcLook.tone(look);
        hairIdx = Math.max(0, indexOf(hairOptions(), NpcLook.hair(look)));
        colorIdx = Math.max(0, indexOf(colorOptions(), NpcLook.hairColor(look)));
        beard = NpcLook.beard(look);
        outfitIdx = 0;
    }

    private void randomize() {
        RandomSource r = RandomSource.create();
        NpcNames.Race race = npcRace();
        female = r.nextBoolean();
        int look = NpcLook.random(race, female, false, Outfit.cultural(race), r);
        adopt(look, true);
        outfitIdx = r.nextInt(NpcLook.playerOutfits(race).length);
        updatePreview();
    }

    private static int indexOf(int[] options, int value) {
        for (int i = 0; i < options.length; i++) if (options[i] == value) return i;
        return -1;
    }

    private void updatePreview() {
        RaceSkins.setPreviewLook(look());
    }

    private void select(Race race) {
        if (race == null || race == selected) return;
        selected = race;
        infoScroll = 0f;
        resetLook();
        updatePreview();
        scrollListTo(race);
    }

    private void scrollListTo(Race race) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).race() == race) {
                int top = i * ROW;
                if (top < listScroll) listScroll = top;
                if (top + ROW > listScroll + listH - 4) listScroll = top + ROW - listH + 4;
                listScroll = Mth.clamp(listScroll, 0, Math.max(0, rows.size() * ROW - listH + 4));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Dark vignette background
        g.fillGradient(0, 0, width, height, 0xFF050505, 0xFF181410); // opaque: the world is never seen behind it
        SkyUi.smoke(g, width, height, (net.minecraft.util.Util.getMillis() % 1_000_000L) / 1000f, 0.7f);
        // a soft pool of light where the character stands
        for (int i = 6; i > 0; i--) {
            int w = 12 * i;
            g.fillGradient(modelX - w, height / 3, modelX + w, height - 30, 0, 0x08E8D8B0);
        }

        // Header
        g.centeredText(font, SkyUi.caps(Component.translatable("screen.skycraft.race_header")), width / 2, 6, GOLD_COLOR);
        SkyUi.ornament(g, width / 2, 18, Math.min(90, width / 5), 0xB0C8BC9A);

        renderList(g, mouseX, mouseY);
        renderModel(g);
        renderInfo(g, mouseX, mouseY);

        boolean btnHov = in(mouseX, mouseY, btnX, btnY, btnW, btnH);
        SkyUi.panel(g, btnX, btnY, btnX + btnW, btnY + btnH);
        SkyUi.button(g, font, Component.translatable("screen.skycraft.race_confirm"), btnX, btnY, btnW, btnH, btnHov, true, 1f);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SkyUi.panel(g, listX - 4, listY - 4, listX + listW + 4, listY + listH);
        g.enableScissor(listX - 4, listY - 2, listX + listW + 4, listY + listH - 2);
        int y = listY - (int) listScroll;
        for (Row row : rows) {
            if (y + ROW >= listY - 2 && y < listY + listH) {
                if (row.race() == null) {
                    SkyUi.caps(g, font, row.group().displayName(), listX, y + 3, 0xFFA89F86);
                    SkyUi.hline(g, listX, listX + listW, y + ROW - 1, 0x50);
                } else {
                    Race r = row.race();
                    boolean sel = r == selected;
                    boolean hov = in(mouseX, mouseY, listX, y, listW, ROW) && mouseY >= listY && mouseY < listY + listH;
                    if (sel) {
                        g.fillGradient(listX - 2, y, listX + listW + 2, y + ROW, 0x60000000, 0x90000000);
                        SkyUi.hline(g, listX - 2, listX + listW + 2, y, 0xA0);
                        SkyUi.hline(g, listX - 2, listX + listW + 2, y + ROW - 1, 0xA0);
                        SkyUi.diamond(g, listX + 1, y + ROW / 2, 2, GOLD_COLOR);
                    } else if (hov) {
                        g.fill(listX - 2, y, listX + listW + 2, y + ROW, 0x30FFFFFF);
                    }
                    int color = sel ? BRIGHT_COLOR : hov ? TEXT_COLOR : DIM_COLOR;
                    String name = SkyUi.ellipsize(font, r.displayName().getString(), listW - 10);
                    g.text(font, name, listX + 7, y + 2, color, sel);
                }
            }
            y += ROW;
        }
        g.disableScissor();
        maxListScroll = Math.max(0, rows.size() * ROW - listH + 4);
        if (maxListScroll > 0) {
            int barH = Math.max(12, listH * listH / (rows.size() * ROW));
            int barY = listY + (int) ((listH - barH - 4) * (listScroll / maxListScroll));
            g.fill(listX + listW + 2, barY, listX + listW + 4, barY + barH, 0xA0E8C060);
        }
    }

    private void renderModel(GuiGraphicsExtractor g) {
        LivingEntity player = minecraft.player;
        if (player == null) return;
        // Skyrim circular stone dais under feet
        int daisRadius = (int) (modelScale * 0.55f);
        g.fill(modelX - daisRadius, modelY - 3, modelX + daisRadius, modelY + 2, 0x50000000);
        g.fill(modelX - daisRadius + 4, modelY - 2, modelX + daisRadius - 4, modelY + 1, 0x80353028);
        g.fill(modelX - daisRadius + 2, modelY - 1, modelX + daisRadius - 2, modelY, 0x60E8C060);
        drawEntity(g, modelX, modelY, modelScale, modelYaw, player);

        // race (and sub-people) under the dais
        Component name = selected.variantCount() > 1 && raceVariant > 0
                ? Component.translatable("screen.skycraft.race_variant_of", selected.displayName(), selected.variantName(raceVariant))
                : selected.displayName();
        g.centeredText(font, name, modelX, height - 28, GOLD_COLOR);
        g.centeredText(font, Component.translatable("screen.skycraft.race_rotate"), modelX, height - 16, 0x809E9689);
    }

    /** The entity turned to {@code yaw} degrees (any angle), looking slightly down at it. */
    private static void drawEntity(GuiGraphicsExtractor g, int x, int y, int scale, float yaw, LivingEntity e) {
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf().rotateX(8f * Mth.DEG_TO_RAD);
        pose.mul(camera);
        var renderer = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
        var renderState = renderer.createRenderState(e, 1.0f);
        renderState.shadowPieces.clear();
        renderState.outlineColor = 0;
        // The intro/camera player can be a spectator. Only the menu snapshot needs a visible full avatar.
        renderState.isInvisible = false;
        if (renderState instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar)
            avatar.isSpectator = false;
        if (renderState instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
            living.isInvisibleToPlayer = false;
            living.bodyRot = 180f + yaw;
            living.yRot = 0;
            living.xRot = 0;
        }
        int halfWidth = scale * 2, previewHeight = scale * 3;
        // Native PiP starts at the viewport centre; translate the avatar's foot origin onto the dais at y.
        float feetOffset = (previewHeight - 8) / (2f * scale);
        g.entity(renderState, scale, new org.joml.Vector3f(0, feetOffset, 0),
                pose, camera, x - halfWidth, y - previewHeight, x + halfWidth, y + 8);

    }

    private void renderInfo(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SkyUi.panel(g, infoX - 4, infoY - 4, infoX + infoW + 4, infoY + infoH + 4);
        // tabs
        int tabW = infoW / 2;
        for (int i = 0; i < 2; i++) {
            boolean active = (i == 1) == lookTab;
            int tx = infoX + i * tabW;
            boolean hov = in(mouseX, mouseY, tx, infoY, tabW, ROW);
            if (active) g.fillGradient(tx, infoY, tx + tabW, infoY + ROW, 0x50E8C060, 0x10E8C060);
            Component label = Component.translatable(i == 0 ? "screen.skycraft.race_tab_lore" : "screen.skycraft.race_tab_look");
            SkyUi.capsCentered(g, font, label, tx + tabW / 2, infoY + 2, active ? GOLD_COLOR : hov ? TEXT_COLOR : DIM_COLOR);
        }
        SkyUi.hline(g, infoX - 4, infoX + infoW + 4, infoY + ROW, 0x90);

        int top = infoY + ROW + 4;
        int bottom = infoY + infoH;
        g.enableScissor(infoX - 4, top, infoX + infoW + 4, bottom);
        int y = top - (int) infoScroll;
        y = lookTab ? renderLook(g, mouseX, mouseY, y, top, bottom) : renderLore(g, y);
        g.disableScissor();
        int content = y + (int) infoScroll - top;
        maxInfoScroll = Math.max(0, content - (bottom - top));
        infoScroll = Mth.clamp(infoScroll, 0, maxInfoScroll);
        if (maxInfoScroll > 0) {
            int h = bottom - top;
            int barH = Math.max(12, h * h / content);
            int barY = top + (int) ((h - barH) * (infoScroll / maxInfoScroll));
            g.fill(infoX + infoW + 2, barY, infoX + infoW + 4, barY + barH, 0xA0E8C060);
        }
    }

    private int renderLore(GuiGraphicsExtractor g, int y) {
        // homeland on the map of Nirn
        int mapH = Mth.clamp(infoW / 2, 60, 90);
        renderMap(g, infoX, y, infoW, mapH);
        y += mapH + 3;
        Component home = TamrielMap.loaded() ? TamrielMap.region(selected.homeX, selected.homeZ).name() : Component.literal("?");
        g.text(font, Component.translatable("screen.skycraft.race_homeland", home), infoX, y, TEXT_COLOR, false);
        y += 10;
        for (FormattedCharSequence line : font.split(Component.translatable("screen.skycraft.race_start"), infoW)) {
            g.text(font, line, infoX, y, DIM_COLOR, false);
            y += 9;
        }
        y += 6;

        g.text(font, selected.displayName().getString().toUpperCase(Locale.ROOT), infoX, y, GOLD_COLOR, true);
        y += 12;
        for (FormattedCharSequence line : font.split(selected.description(), infoW)) {
            g.text(font, line, infoX, y, TEXT_COLOR, false);
            y += 10;
        }
        y += 6;

        g.text(font, Component.translatable("screen.skycraft.race_skills"), infoX, y, GOLD_COLOR, false);
        y += 11;
        g.text(font, selected.major.displayName().copy().append(" +10"), infoX + 6, y, BRIGHT_COLOR, false);
        y += 10;
        for (Skill s : selected.minors) {
            g.text(font, s.displayName().copy().append(" +5"), infoX + 6, y, TEXT_COLOR, false);
            y += 10;
        }
        for (Skill s : selected.extras) {
            g.text(font, s.displayName().copy().append(" +5"), infoX + 6, y, TEXT_COLOR, false);
            y += 10;
        }
        y += 6;

        List<String> passives = selected.passives();
        if (!passives.isEmpty()) {
            g.text(font, Component.translatable("screen.skycraft.race_passives"), infoX, y, GOLD_COLOR, false);
            y += 11;
            for (String p : passives) {
                int colon = p.indexOf(':');
                Component c = colon < 0 ? Component.translatable("race.skycraft.passive." + p)
                        : Component.translatable("race.skycraft.passive." + p.substring(0, colon), p.substring(colon + 1));
                List<FormattedCharSequence> lines = font.split(c, infoW - 10);
                for (int i = 0; i < lines.size(); i++) {
                    if (i == 0) SkyUi.diamond(g, infoX + 3, y + 3, 1, 0xFFC8BC9A);
                    g.text(font, lines.get(i), infoX + 8, y, TEXT_COLOR, false);
                    y += 10;
                }
            }
            y += 6;
        }

        g.text(font, Component.translatable("screen.skycraft.race_power"), infoX, y, GOLD_COLOR, false);
        y += 11;
        g.text(font, selected.powerName(), infoX + 6, y, BRIGHT_COLOR, false);
        y += 10;
        for (FormattedCharSequence line : font.split(selected.powerDescription(), infoW - 6)) {
            g.text(font, line, infoX + 6, y, DIM_COLOR, false);
            y += 10;
        }
        return y + 4;
    }

    /** A painted map of Nirn (the world map's tiles) with the homeland and Helgen marked. */
    private void renderMap(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF3A2E20);
        g.fill(x, y, x + w, y + h, 0xFF22303A);
        if (!TamrielMap.loaded()) return;
        MapTiles.uploadFinished();
        double bpp = (double) (MAP_MAX_X - MAP_MIN_X) / w;
        double spanZ = h * bpp;
        double z0 = Mth.clamp(selected.homeZ - spanZ / 2, MAP_MIN_Z, MAP_MAX_Z - spanZ);
        double x0 = MAP_MIN_X;
        int tb = MapTiles.tileBlocks(0);
        // clip to the map frame (scissors nest: this intersects the info panel's)
        g.enableScissor(x, y, x + w, y + h);
        
        for (int tz = Math.floorDiv((int) z0, tb); tz <= Math.floorDiv((int) (z0 + spanZ), tb); tz++) {
            for (int tx = Math.floorDiv(MAP_MIN_X, tb); tx <= Math.floorDiv(MAP_MAX_X, tb); tx++) {
                Identifier tex = MapTiles.get(0, tx, tz);
                if (tex == null) continue;
                g.pose().pushMatrix();
                g.pose().translate((float) (x + (tx * (double) tb - x0) / bpp), (float) (y + (tz * (double) tb - z0) / bpp));
                float s = (float) ((tb / bpp + 0.35) / MapTiles.SIZE);
                g.pose().scale(s, s);
                com.skycraft.inventory.client.SkyUi.blit(g, tex, 0, 0, 0f, 0f, MapTiles.SIZE, MapTiles.SIZE, MapTiles.SIZE, MapTiles.SIZE);
                g.pose().popMatrix();
            }
        }
        // the start (Helgen) and the homeland
        int sx = x + (int) ((START_X - x0) / bpp), sz = y + (int) ((START_Z - z0) / bpp);
        g.fill(sx - 1, sz - 1, sx + 2, sz + 2, 0xFFB03020);
        int hx = x + (int) ((selected.homeX - x0) / bpp), hz = y + (int) ((selected.homeZ - z0) / bpp);
        float pulse = 0.6f + 0.4f * Mth.sin(net.minecraft.util.Util.getMillis() / 250f);
        SkyUi.diamond(g, hx, hz, 4, 0xFF000000);
        SkyUi.diamond(g, hx, hz, 3, SkyUi.fade(GOLD_COLOR, pulse));
        g.disableScissor();
    }

    private int renderLook(GuiGraphicsExtractor g, int mouseX, int mouseY, int y, int top, int bottom) {
        NpcNames.Race race = npcRace();
        if (selected.variantCount() > 1) {
            y = selector(g, mouseX, mouseY, y, "variant", selected.variantName(raceVariant), -1, top, bottom);
        }
        y = selector(g, mouseX, mouseY, y, "sex", Component.translatable(female ? "screen.skycraft.race_female" : "screen.skycraft.race_male"), -1, top, bottom);
        int toneRgb = NpcLook.toneColor(look());
        y = selector(g, mouseX, mouseY, y, "skin", Component.literal((tone + 1) + " / " + NpcLook.TONES_PER_RACE), toneRgb, top, bottom);
        int[] hairs = hairOptions();
        y = selector(g, mouseX, mouseY, y, "hair", Component.translatable("race_look.skycraft.hair." + hairs[Math.floorMod(hairIdx, hairs.length)]), -1, top, bottom);
        int[] colors = colorOptions();
        int hairRgb = NpcLook.HAIR_COLORS[colors[Math.floorMod(colorIdx, colors.length)]];
        y = selector(g, mouseX, mouseY, y, "color", Component.literal((Math.floorMod(colorIdx, colors.length) + 1) + " / " + colors.length), hairRgb, top, bottom);
        if (hasBeard()) {
            y = selector(g, mouseX, mouseY, y, "beard", Component.translatable("race_look.skycraft.beard." + beard), -1, top, bottom);
        }
        y = selector(g, mouseX, mouseY, y, "outfit", Component.translatable("race_look.skycraft.outfit." + outfitIdx), -1, top, bottom);
        y += 4;
        boolean hov = in(mouseX, mouseY, infoX + 10, y, infoW - 20, 14) && mouseY >= top && mouseY < bottom;
        SkyUi.button(g, font, Component.translatable("screen.skycraft.race_randomize"), infoX + 10, y, infoW - 20, 14, hov, true, 1f);
        return y + 18;
    }

    /** One "label   < value >" row; {@code swatch} >= 0 draws a colour chip. */
    private int selector(GuiGraphicsExtractor g, int mouseX, int mouseY, int y, String key, Component value, int swatch, int top, int bottom) {
        g.text(font, Component.translatable("screen.skycraft.race_look_" + key), infoX, y + 3, DIM_COLOR, false);
        int vx = infoX + infoW / 2 - 6;
        int vw = infoW - (vx - infoX);
        boolean inside = mouseY >= top && mouseY < bottom;
        boolean hl = in(mouseX, mouseY, vx, y, 10, 13) && inside;
        boolean hr = in(mouseX, mouseY, vx + vw - 10, y, 10, 13) && inside;
        g.text(font, "<", vx + 2, y + 3, hl ? BRIGHT_COLOR : GOLD_COLOR, false);
        g.text(font, ">", vx + vw - 7, y + 3, hr ? BRIGHT_COLOR : GOLD_COLOR, false);
        int cx = vx + vw / 2;
        String text = SkyUi.ellipsize(font, value.getString(), vw - 24 - (swatch >= 0 ? 10 : 0));
        int tw = font.width(text) + (swatch >= 0 ? 10 : 0);
        int tx = cx - tw / 2;
        if (swatch >= 0) {
            g.fill(tx, y + 2, tx + 8, y + 10, 0xFF000000);
            g.fill(tx + 1, y + 3, tx + 7, y + 9, 0xFF000000 | swatch);
            tx += 10;
        }
        g.text(font, text, tx, y + 3, TEXT_COLOR, false);
        SkyUi.hline(g, infoX, infoX + infoW, y + 13, 0x30);
        return y + 15;
    }

    // ------------------------------------------------------------------ input

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0) {
            // race list
            if (in(mx, my, listX - 2, listY, listW + 4, listH)) {
                int i = (int) ((my - listY + listScroll) / ROW);
                if (i >= 0 && i < rows.size() && rows.get(i).race() != null) select(rows.get(i).race());
                return true;
            }
            // tabs
            if (in(mx, my, infoX, infoY, infoW, ROW)) {
                boolean look = mx >= infoX + infoW / 2;
                if (look != lookTab) {
                    lookTab = look;
                    infoScroll = 0f;
                }
                return true;
            }
            if (lookTab && clickLook(mx, my)) return true;
            if (in(mx, my, btnX, btnY, btnW, btnH)) {
                confirmSelection();
                return true;
            }
            // drag the model round
            if (in(mx, my, modelX - 60, modelY - (int) (2.2f * modelScale), 120, (int) (2.2f * modelScale) + 10)) {
                isDraggingModel = true;
                lastDragX = mx;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Clicks on the Appearance tab's arrows and the randomize button (same layout as {@link #renderLook}). */
    private boolean clickLook(double mx, double my) {
        int top = infoY + ROW + 4;
        if (my < top || my >= infoY + infoH) return false;
        int y = top - (int) infoScroll;
        int vx = infoX + infoW / 2 - 6;
        int vw = infoW - (vx - infoX);
        List<String> keys = new ArrayList<>();
        if (selected.variantCount() > 1) keys.add("variant");
        keys.add("sex");
        keys.add("skin");
        keys.add("hair");
        keys.add("color");
        if (hasBeard()) keys.add("beard");
        keys.add("outfit");
        for (String key : keys) {
            int dir = in(mx, my, vx - 2, y, 14, 13) ? -1 : in(mx, my, vx + vw - 12, y, 14, 13) ? 1 : 0;
            if (dir != 0) {
                step(key, dir);
                return true;
            }
            y += 15;
        }
        y += 4;
        if (in(mx, my, infoX + 10, y, infoW - 20, 14)) {
            randomize();
            return true;
        }
        return false;
    }

    private void step(String key, int dir) {
        switch (key) {
            case "variant" -> raceVariant = Math.floorMod(raceVariant + dir, selected.variantCount());
            case "sex" -> {
                female = !female;
                hairIdx = Math.floorMod(hairIdx, hairOptions().length);
            }
            case "skin" -> tone = Math.floorMod(tone + dir, NpcLook.TONES_PER_RACE);
            case "hair" -> hairIdx = Math.floorMod(hairIdx + dir, hairOptions().length);
            case "color" -> colorIdx = Math.floorMod(colorIdx + dir, colorOptions().length);
            case "beard" -> beard = Math.floorMod(beard + dir, NpcLook.BEARDS);
            case "outfit" -> outfitIdx = Math.floorMod(outfitIdx + dir, NpcLook.playerOutfits(npcRace()).length);
            default -> {
            }
        }
        updatePreview();
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (isDraggingModel && button == 0) {
            modelYaw -= (float) (mx - lastDragX) * 2.2f;
            lastDragX = mx;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0) isDraggingModel = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (mx < listX + listW + 6) {
            listScroll = Mth.clamp(listScroll - (float) delta * 18f, 0, maxListScroll);
            return true;
        }
        if (mx >= infoX - 4 && maxInfoScroll > 0) {
            infoScroll = Mth.clamp(infoScroll - (float) delta * 14f, 0, maxInfoScroll);
            return true;
        }
        if (mx > listX + listW && mx < infoX) {
            modelYaw += (float) delta * 15f;
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            confirmSelection();
            return true;
        }
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_W || key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) {
            int dir = key == InputConstants.KEY_UP || key == InputConstants.KEY_W ? -1 : 1;
            int n = Race.VALUES.length;
            List<Race> order = new ArrayList<>();
            for (Row row : rows) if (row.race() != null) order.add(row.race());
            select(order.get(Math.floorMod(order.indexOf(selected) + dir, n)));
            return true;
        }
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_A) {
            modelYaw += 20f;
            return true;
        }
        if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_D) {
            modelYaw -= 20f;
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            lookTab = !lookTab;
            infoScroll = 0f;
            return true;
        }
        if (key == InputConstants.KEY_R) {
            randomize();
            return true;
        }
        return super.keyPressed(event);
    }

    private void confirmSelection() {
        SkyNetwork.sendToServer(new CorePackets.ChooseRace(selected.id(), look()));
        com.skycraft.quest.client.IntroClient.characterConfirmed();
        onClose();
    }
}
