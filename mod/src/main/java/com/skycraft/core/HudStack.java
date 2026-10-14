package com.skycraft.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collision-free placement of the HUD text that sits in the screen corners (the Skyrim HUD has no room for two
 * things in the same spot): bottom right above the stamina bar (voice words, right-hand spell, carry weight, the
 * ship panel), bottom left above the magicka bar, the strip above the health bar and top right under the status
 * effect icons.
 *
 * <p>Every element asks {@link #reserve} once per frame with its height and a fixed priority and draws at the
 * returned top y. Elements stack away from their screen edge in priority order (lowest priority nearest the
 * edge); an element that is not drawn does not reserve, so the rest close the gap. The order does not depend on
 * which overlay happens to render first: a slot is placed against the reservations of the previous frame plus
 * those already made this frame. A slot pushed outward by a new neighbour moves at once (so nothing ever
 * overlaps); a slot that drops back toward the edge because a neighbour went away glides there.</p>
 *
 * <p>This class has no Minecraft dependency (it is pure arithmetic) so it can be self-tested on the server; the
 * client calls {@link #nextFrame} once per GUI frame ({@code com.skycraft.client.hud.HudFrame}).</p>
 */
public final class HudStack {
    /** The screen regions. {@code edge} = pixels from the screen edge to the first slot. */
    public enum Zone {
        /** Right edge, rising from just above the stamina bar. */
        BOTTOM_RIGHT(17, false),
        /** Left edge, rising from just above the magicka bar. */
        BOTTOM_LEFT(17, false),
        /** Centre, rising from just above the health bar. */
        BOTTOM_CENTER(34, false),
        /** Right edge, falling from the top (below the status effect icons, see {@link #setInset}). */
        TOP_RIGHT(6, true);

        final int edge;
        final boolean fromTop;

        Zone(int edge, boolean fromTop) {
            this.edge = edge;
            this.fromTop = fromTop;
        }
    }

    // priorities: lowest sits nearest the screen edge. Gaps between values leave room for later elements.
    public static final int BR_VOICE = 0;        // shout words / power / form ability + voice meter
    public static final int BR_RIGHT_SPELL = 10;
    public static final int BR_CARRY = 20;
    public static final int BR_EFFECTS = 25;     // active spell effect countdowns (magic module)
    public static final int BR_SHIP = 30;
    public static final int BL_POWER = 0;        // racial power
    public static final int BL_LEFT_SPELL = 10;  // left spell, "unarmed" hint, vampire lord's ability
    public static final int BL_STONE = 20;       // active standing stone banner
    public static final int BC_FORM_BAR = 0;     // beast form countdown over the health bar
    public static final int BC_TAME = 5;         // the horse-breaking balance bar
    public static final int BC_MOUNT = 10;
    public static final int BC_SURVIVAL = 20;
    public static final int BC_REST = 30;        // the rest panel / "X is resting, press the key to join" line (world module)
    public static final int TR_PERKS = 0;
    public static final int TR_CURSE_PERKS = 10;
    public static final int TR_TARGET = 20;      // the target card of a mount you look at

    /** Pixels between two stacked slots. */
    public static final int GAP = 2;

    private static final HudStack GLOBAL = new HudStack();

    private final Map<Zone, ZoneState> zones = new HashMap<>();
    private long frame;
    private long nowMs;
    private float dtSeconds = 0.016f;

    public HudStack() {
        for (Zone z : Zone.values()) zones.put(z, new ZoneState());
    }

    private static final class ZoneState {
        /** priority -> height of this frame's reservations / of the previous frame's. */
        Map<Integer, Integer> cur = new TreeMap<>();
        Map<Integer, Integer> prev = new TreeMap<>();
        /** priority -> displayed offset (glides toward the target) and the frame it was last used in. */
        final Map<Integer, float[]> shown = new HashMap<>();
        int inset;
    }

    // ------------------------------------------------------------------ the shared instance

    /** Starts a GUI frame; call once before any overlay draws. */
    public static void nextFrame(long nowMs) {
        GLOBAL.beginFrame(nowMs);
    }

    /** Extra pixels between the screen edge and the first slot of a zone this frame (e.g. status effect icons). */
    public static void setInset(Zone zone, int px) {
        GLOBAL.inset(zone, px);
    }

    /** Reserves {@code heightPx} in the zone and returns the y of the slot's top edge. */
    public static int reserve(Zone zone, int priority, int heightPx, int screenHeight) {
        return GLOBAL.reserveSlot(zone, priority, heightPx, screenHeight);
    }

    // ------------------------------------------------------------------ instance logic (self-tested)

    public void beginFrame(long nowMs) {
        long dt = this.nowMs == 0 ? 16 : Math.max(0, Math.min(250, nowMs - this.nowMs));
        this.dtSeconds = dt / 1000f;
        this.nowMs = nowMs;
        frame++;
        for (ZoneState s : zones.values()) {
            Map<Integer, Integer> old = s.prev;
            s.prev = s.cur;
            s.cur = old;
            s.cur.clear();
            s.inset = 0;
        }
    }

    public void inset(Zone zone, int px) {
        zones.get(zone).inset = Math.max(0, px);
    }

    /** Offset of the slot from the zone's first slot position, in pixels, before smoothing. */
    private int targetOffset(ZoneState s, int priority) {
        int offset = 0;
        List<Integer> keys = new ArrayList<>(s.prev.keySet());
        for (Integer k : s.cur.keySet()) if (!s.prev.containsKey(k)) keys.add(k);
        for (int k : keys) {
            if (k >= priority) continue;
            Integer h = s.cur.get(k);
            if (h == null) h = s.prev.get(k);
            if (h != null && h > 0) offset += h + GAP;
        }
        return offset;
    }

    public int reserveSlot(Zone zone, int priority, int heightPx, int screenHeight) {
        ZoneState s = zones.get(zone);
        int h = Math.max(0, heightPx);
        s.cur.merge(priority, h, Math::max);
        int target = targetOffset(s, priority);
        float[] anim = s.shown.get(priority);
        if (anim == null || anim[1] < frame - 1) {
            // new on screen (or not drawn last frame): appear in place
            anim = new float[]{target, frame};
            s.shown.put(priority, anim);
        } else {
            // moving away from the edge (a neighbour appeared) is immediate so slots never overlap; coming back
            // toward the edge (a neighbour went away) glides
            if (target >= anim[0]) {
                anim[0] = target;
            } else {
                float step = 1f - (float) Math.exp(-14.0 * dtSeconds);
                anim[0] += (target - anim[0]) * step;
                if (anim[0] - target < 0.5f) anim[0] = target;
            }
            anim[1] = frame;
        }
        int offset = Math.round(anim[0]);
        if (zone.fromTop) return zone.edge + s.inset + offset;
        return screenHeight - zone.edge - s.inset - offset - h;
    }

    // ------------------------------------------------------------------ self-test

    /** Layout checks on a private instance; returns the problems found (empty when everything holds). */
    public static List<String> selfTest() {
        List<String> problems = new ArrayList<>();
        HudStack st = new HudStack();
        int screenH = 240;
        st.beginFrame(1000);
        // frame 1: voice (14) and carry (10) shown, no spell: carry sits right above the voice block
        int voice = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_VOICE, 14, screenH);
        int carry = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_CARRY, 10, screenH);
        if (voice != screenH - 17 - 14) problems.add("hud: voice slot at " + voice);
        if (carry + 10 + GAP != voice) problems.add("hud: carry " + carry + " does not sit on the voice block " + voice);
        // frame 2: the spell appears (priority between): carry must move above it, nothing overlaps
        st.beginFrame(1016);
        int voice2 = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_VOICE, 14, screenH);
        int spell = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_RIGHT_SPELL, 10, screenH);
        int carry2 = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_CARRY, 10, screenH);
        if (spell + 10 + GAP != voice2) problems.add("hud: spell " + spell + " not on voice " + voice2);
        if (carry2 != spell - 10 - GAP) problems.add("hud: carry " + carry2 + " overlaps spell " + spell);
        int c = carry2;
        for (int f = 0; f < 120; f++) {
            st.beginFrame(1032 + f * 16L);
            st.reserveSlot(Zone.BOTTOM_RIGHT, BR_VOICE, 14, screenH);
            st.reserveSlot(Zone.BOTTOM_RIGHT, BR_RIGHT_SPELL, 10, screenH);
            c = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_CARRY, 10, screenH);
        }
        if (c != spell - 10 - GAP) problems.add("hud: carry settled at " + c + ", expected " + (spell - 10 - GAP));
        // draw order must not matter: carry reserving before the voice and the spell lands in the same place
        st.beginFrame(5000);
        int cFirst = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_CARRY, 10, screenH);
        if (cFirst != c) problems.add("hud: carry moved to " + cFirst + " when drawn first (was " + c + ")");
        // zones are independent
        int left = st.reserveSlot(Zone.BOTTOM_LEFT, BL_POWER, 10, screenH);
        if (left != screenH - 17 - 10) problems.add("hud: bottom-left power at " + left);
        // top right falls from the top and honours the inset
        st.beginFrame(5016);
        st.inset(Zone.TOP_RIGHT, 26);
        int t0 = st.reserveSlot(Zone.TOP_RIGHT, TR_PERKS, 10, screenH);
        int t1 = st.reserveSlot(Zone.TOP_RIGHT, TR_CURSE_PERKS, 10, screenH);
        if (t0 != 6 + 26) problems.add("hud: top-right first slot at " + t0);
        if (t1 != t0 + 10 + GAP) problems.add("hud: top-right second slot at " + t1);
        // elements that stop drawing free their space: the carry slot glides down to the edge, never upward
        st.beginFrame(6984);
        st.reserveSlot(Zone.BOTTOM_RIGHT, BR_VOICE, 14, screenH);
        st.reserveSlot(Zone.BOTTOM_RIGHT, BR_RIGHT_SPELL, 10, screenH);
        int last = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_CARRY, 10, screenH);
        boolean glided = false;
        for (int f = 0; f < 90; f++) {
            st.beginFrame(7000 + f * 16L);
            int now = st.reserveSlot(Zone.BOTTOM_RIGHT, BR_CARRY, 10, screenH);
            if (now < last) problems.add("hud: carry moved away from the edge while its neighbours left");
            if (f == 1 && now > last && now < screenH - 17 - 10) glided = true;
            last = now;
        }
        if (!glided) problems.add("hud: carry did not glide down when its neighbours left");
        if (last != screenH - 17 - 10) problems.add("hud: lone carry slot settled at " + last);
        // the active-effects strip (several rows tall) sits between carry and ship and overlaps none of them
        HudStack full = new HudStack();
        int[] prios = {BR_VOICE, BR_RIGHT_SPELL, BR_CARRY, BR_EFFECTS, BR_SHIP};
        int[] heights = {14, 10, 10, 50, 22};
        int[] tops = new int[prios.length];
        for (int f = 0; f < 3; f++) {
            full.beginFrame(10000 + f * 16L);
            for (int i = 0; i < prios.length; i++) tops[i] = full.reserveSlot(Zone.BOTTOM_RIGHT, prios[i], heights[i], 400);
        }
        for (int i = 1; i < prios.length; i++) {
            if (tops[i] + heights[i] + GAP > tops[i - 1]) problems.add("hud: bottom-right slot " + prios[i] + " overlaps " + prios[i - 1]);
        }
        return problems;
    }
}
