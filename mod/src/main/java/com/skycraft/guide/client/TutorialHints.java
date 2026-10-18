package com.skycraft.guide.client;

import com.skycraft.Skycraft;
import com.skycraft.client.hud.HudNotifications;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.guide.GuideConfig;
import com.skycraft.inventory.CarryWeight;
import com.skycraft.survival.client.ClientSurvival;
import com.skycraft.survival.mode.SurvivalMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skyrim-style tutorial hints: a few lines of small text in the top-left corner the first time the player does
 * something (sneaks, picks a lock, learns a Word of Power, feels the cold...). Each hint is shown once per player
 * (remembered in {@code skycraft-guide-client.toml}), one at a time, and only while no menu is open; the timer pauses
 * behind menus. Everything is detected on the client from state the other modules already sync, so it works on any
 * server. Text: {@code guide.skycraft.hint.<id>}, with {@code {key:...}} markup for the player's real bindings.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class TutorialHints {
    private static final TagKey<EntityType<?>> DRAGONS = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, "dragons"));
    private static final Style KEY_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(0xE8C060));
    private static final int TEXT = 0xE8E2D0;
    private static final int FADE_IN = 8, FADE_OUT = 20;

    private static final Deque<String> QUEUE = new ArrayDeque<>();
    private static final Set<String> SEEN = new HashSet<>();
    private static boolean seenLoaded;
    private static String current;
    private static int shownTicks;
    private static int worldTicks;

    // baselines for "the first time this goes up" triggers, taken on joining a world (-1 = not yet)
    private static int words = -1, souls = -1, discovered = -1;
    private static float lastHealth = -1;

    private TutorialHints() {}

    /** Queues a hint unless it was shown before or hints are off. */
    public static void trigger(String id) {
        if (!enabled()) return;
        loadSeen();
        if (SEEN.contains(id) || id.equals(current) || QUEUE.contains(id)) return;
        QUEUE.add(id);
    }

    /** Shows a hint now, whether or not it was seen (autotest and {@code /handbook hints show}). */
    public static void force(String id) {
        QUEUE.remove(id);
        current = id;
        shownTicks = 0;
    }

    public static boolean enabled() {
        try {
            return GuideConfig.HINTS.get();
        } catch (IllegalStateException e) {
            return false; // config not loaded yet
        }
    }

    public static void setEnabled(boolean on) {
        GuideConfig.HINTS.set(on);
        GuideConfig.HINTS.save();
        if (!on) {
            QUEUE.clear();
            current = null;
        }
    }

    /** Forgets every seen hint so they show again. */
    public static void reset() {
        SEEN.clear();
        seenLoaded = true;
        GuideConfig.SEEN_HINTS.set(new ArrayList<>());
        GuideConfig.SEEN_HINTS.save();
    }

    private static void loadSeen() {
        if (seenLoaded) return;
        seenLoaded = true;
        SEEN.addAll(GuideConfig.SEEN_HINTS.get());
    }

    private static void markSeen(String id) {
        loadSeen();
        if (SEEN.add(id)) {
            GuideConfig.SEEN_HINTS.set(new ArrayList<>(SEEN));
            GuideConfig.SEEN_HINTS.save();
        }
    }

    // ------------------------------------------------------------------ detection

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        QUEUE.clear();
        current = null;
        worldTicks = 0;
        words = souls = discovered = -1;
        lastHealth = -1;
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof com.skycraft.crime.client.LockpickScreen) trigger("lockpick");
        else if (event.getNewScreen() instanceof com.skycraft.crime.client.PickpocketScreen) trigger("pickpocket");
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (current != null) {
            if (mc.gui.screen() == null && !mc.isPaused()) shownTicks++;
            if (shownTicks > GuideConfig.HINT_SECONDS.get() * 20) current = null;
        }
        if (!enabled()) return;
        worldTicks++;
        if (worldTicks % 5 == 0 && player.isAlive()) detect(mc, player);
        if (current == null && mc.gui.screen() == null && !QUEUE.isEmpty()) {
            current = QUEUE.poll();
            shownTicks = 0;
            markSeen(current);
        }
    }

    private static void detect(Minecraft mc, LocalPlayer player) {
        PlayerData data = SkyData.get(player);
        boolean chosen = data.getRace() != null;
        if (!chosen) return; // the character creator comes first
        if (worldTicks > 60) trigger("welcome");
        if (worldTicks > 20 * 60) trigger("menus");

        if (player.isCrouching()) trigger("sneak");
        if (player.fishing != null) trigger("fishing");
        if (player.getVehicle() instanceof AbstractHorse) trigger("horse");
        if (mc.level.dimension() == Level.NETHER) trigger("oblivion");

        float health = player.getHealth();
        if (lastHealth >= 0 && health < lastHealth - 0.01f && player.hurtTime > 0) trigger("combat");
        if (health < player.getMaxHealth() * 0.35f) trigger("low_health");
        lastHealth = health;

        if (data.getPerkPoints() > 0 || data.getPendingLevelUps() > 0) trigger("level_up");

        CompoundTag magic = data.module("magic");
        if (!magic.getListOrEmpty("spells").isEmpty() && worldTicks > 20 * 90) trigger("magic");
        CompoundTag w = magic.getCompoundOrEmpty("words");
        int learned = 0;
        for (String k : w.keySet()) learned += w.getIntOr(k, 0);
        if (words >= 0 && learned > words) trigger("word_wall");
        words = learned;
        int s = magic.getIntOr("dragon_souls",0);
        if (souls >= 0 && s > souls) trigger("dragon_soul");
        souls = s;

        int found = data.module("world").getListOrEmpty("discovered").size();
        if (discovered >= 0 && found > discovered) trigger("discovery");
        discovered = found;

        CompoundTag bounty = data.module("crime").getCompoundOrEmpty("bounty");
        for (String hold : bounty.keySet()) {
            if (bounty.getIntOr(hold, 0) > 0) {
                trigger("bounty");
                break;
            }
        }

        if (ClientSurvival.active()) {
            if (SurvivalMode.coldStage(ClientSurvival.cold) >= 1) trigger("cold");
            if (SurvivalMode.hungerStage(ClientSurvival.hunger) >= 1) trigger("hunger");
            if (SurvivalMode.fatigueStage(ClientSurvival.fatigue) >= 1) trigger("fatigue");
        }

        if (worldTicks % 20 == 0) {
            if (CarryWeight.isOverencumbered(player)) trigger("overencumbered");
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getType().builtInRegistryHolder().is(DRAGONS) && e.distanceToSqr(player) < 96 * 96) {
                    trigger("dragon");
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    /** HUD overlay: the current hint under the top-left message log, fading in and out. */
    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (current == null || mc.player == null || mc.gui.hud.isHidden()) return;
        int total = GuideConfig.HINT_SECONDS.get() * 20;
        float t = shownTicks + (mc.gui.screen() == null && !mc.isPaused() ? partialTick : 0);
        float alpha = Mth.clamp(Math.min(t / FADE_IN, (total - t) / FADE_OUT), 0f, 1f);
        if (alpha < 0.04f) return;
        Font font = mc.font;
        // stay clear of the centred target and boss bars: at a Steam Deck's 427 px GUI this leaves ~126 px
        int wrap = Mth.clamp((width - Math.min(150, width / 3)) / 2 - 16, 100, 220);
        List<FormattedCharSequence> lines = font.split(GuideText.format(
                Component.translatable("guide.skycraft.hint." + current).getString(), KEY_STYLE), wrap);
        int x = 10;
        int y = 32 + 11 * HudNotifications.messageLines() + (HudNotifications.messageLines() > 0 ? 4 : 0);
        int h = lines.size() * 10;
        int a = (int) (alpha * 255);
        // smoky backdrop fading to the right, with a thin gold rule on the left (Skyrim's help text)
        int maxW = 0;
        for (FormattedCharSequence line : lines) maxW = Math.max(maxW, font.width(line));
        int bw = maxW + 10;
        for (int i = 0; i < 4; i++) {
            int from = x - 4 + bw * i / 4, to = x - 4 + bw * (i + 1) / 4 + 8;
            int ba = (int) (alpha * (110 - i * 22));
            g.fill(from, y - 4, to, y + h + 2, ba << 24);
        }
        g.fill(x - 4, y - 4, x - 3, y + h + 2, (int) (alpha * 200) << 24 | 0xC8A050);
        for (FormattedCharSequence line : lines) {
            g.text(font, line, x, y, a << 24 | TEXT, true);
            y += 10;
        }
    }
}
