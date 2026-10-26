package com.nirncraft.render.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Adaptive quality v2: keeps the frame inside a GPU budget on any machine by giving each knob what it is worth.
 * Pure logic (no game classes), driven by the client glue once per frame; see docs/ADAPTIVE-QUALITY.md.
 *
 * <p><b>Loop.</b> Every frame the glue asks {@link #frame} for the knob vector to draw with and later reports what
 * that frame cost ({@link #observeFrame}: whole-frame GPU timestamps; {@link #observeAtlas}: atlas's own), tagged
 * with the vector it really used. {@link QualityModel} refines each knob's cost from those samples; every half second
 * {@link Allocator} re-solves "most visual value inside the budget" (budget = 90% of the frame time at the target
 * frame rate, allocated to 92% of that). The result is a target the drawn knobs slew towards (up slowly, down fast),
 * so nothing pops.</p>
 *
 * <p><b>Hysteresis.</b> A new target is taken only when the measured frame is over budget (and the new one is
 * cheaper) or well under it (below 85%, and the new one is better); inside the band the target holds. Discrete knobs
 * (atlas's cloud resolution and shadows) dwell 4 s between changes; atlas's render scale moves in 0.05 steps at
 * most every 2 s (each step reallocates its targets). A frame bound by the CPU (wall time over target while the GPU
 * is idle part of it) or a busy integrated server never lowers quality: the GPU knobs would not help.</p>
 *
 * <p><b>Calibration.</b> With no saved profile for this GPU, driver and window size: while a loading cover hides the
 * view, a short plan of settings (all max, all floor, each pack cost alone, each atlas knob off) is held 12 frames
 * each, about 200 frames; uncovered, a quick pass instead nudges each knob down 0.3 for a few frames over the first
 * seconds (while chunks are still loading in). Afterwards live probes (one knob 0.15 lower for 20 frames every 1.5 s)
 * keep the costs separable. Before any sample, the costs are the priors of the GPU's class.</p>
 *
 * <p><b>Fallbacks below the floor.</b> When even every knob at its floor does not fit and the frame stays over 115%
 * of the budget for 5 s: tier 1, the far field at its lowest (render scale 0.33, LOD bias 3, quarter-resolution
 * clouds, no far shadows, 16 km); 8 s more and atlas is at least a quarter of the frame: tier 2, the far field off
 * (the near field and the pack's own sky and cloud deck stand alone). Under 60% of the budget for 15 s steps back.</p>
 */
public final class QualityController {
    public enum Phase { WAIT, CALIBRATE, QUICK, LIVE }

    static final int N = Knob.COUNT;
    static final long SECOND = 1_000_000_000L;
    static final int CAL_FRAMES = 12;

    private final HardwareTier.Guess hardware;
    private final QualityModel model;
    /** The frame's context (window size, whether the pack / atlas draw); the glue keeps it current. */
    public final QualityModel.Context context = new QualityModel.Context();

    private double targetFps;
    private boolean covered, cpuLimited, hostBusy, profileLoaded;
    private final boolean adaptive;

    private final double[] floor = new double[N], ceil = new double[N], ones = new double[N];
    /** Ceilings from the machine's memory (atlas's scale, sun map, cloud pass): 1 unless {@link #limitAtlas}. */
    private final double[] memCeil = new double[N];
    private final double[] target = new double[N], applied = new double[N], out = new double[N];
    private final double[] discreteOut = new double[N];
    private final long[] discreteChanged = new long[N];
    private double scaleOut = 1;
    private long scaleChanged = Long.MIN_VALUE / 2;

    private double gpuEma = -1, wallEma = -1, atlasEma = -1, residual;
    private long lastFrame, lastAlloc = Long.MIN_VALUE / 2;
    private int fallback;
    private long overSince = -1, underSince = -1;
    /** Far field off: when to try it again (its cost is a guess while it is off), and how long the next wait is. */
    private long retryAt = Long.MAX_VALUE, retryWait = 180 * SECOND, lastRetry = Long.MIN_VALUE / 2;

    private Phase phase = Phase.WAIT;
    private long phaseStart;
    private List<double[]> plan;
    private int planIndex, planFrames;

    private int probeKnob = -1, probeFrames, probeCursor, probesThisPass;
    private double probeDelta;
    private long nextProbe, probeEnded = Long.MIN_VALUE / 2;
    private long frames, rejected;

    public QualityController(HardwareTier.Guess hardware, double targetFps, boolean adaptive) {
        this.hardware = hardware;
        this.model = new QualityModel(hardware.speed());
        this.adaptive = adaptive;
        setTargetFps(targetFps);
        for (Knob k : Knob.ALL) {
            floor[k.ordinal()] = k.floor;
            ceil[k.ordinal()] = 1;
            memCeil[k.ordinal()] = 1;
        }
        Arrays.fill(ones, 1);
        Arrays.fill(out, 1);
        Arrays.fill(applied, 1);
        Arrays.fill(target, 1);
        Arrays.fill(discreteOut, 1);
    }

    // ------------------------------------------------------------------ inputs

    public void setTargetFps(double fps) {
        targetFps = Math.max(20, Math.min(240, fps));
    }

    public double targetFps() {
        return targetFps;
    }

    /** GPU budget for the whole frame (ms): 90% of the target frame time. */
    public double budgetMs() {
        return 1000.0 / targetFps * 0.9;
    }

    /** True while a loading cover hides the view (calibration may show anything). */
    public void setCovered(boolean covered) {
        this.covered = covered;
    }

    /** True while an integrated server hosting guests runs long ticks: quality never drops for the CPU's sake. */
    public void setHostBusy(boolean busy) {
        hostBusy = busy;
    }

    /**
     * What this machine's memory allows atlas (AtlasMemory's plan): ceilings for its render scale and sun map knobs,
     * quarter-resolution clouds, and the fallback tier to start from (1: the far field at its lowest until the frame
     * has room). The ceilings hold in every tier and through calibration; a later call replaces them (after running
     * out of memory the plan shrinks). Callable before or after the first frame.
     */
    public void limitAtlas(double scaleCeil, double shadowCeil, boolean quarterClouds, int initialTier) {
        memCeil[Knob.ATLAS_SCALE.ordinal()] = Knob.clamp01(scaleCeil);
        memCeil[Knob.ATLAS_SHADOWS.ordinal()] = Knob.clamp01(shadowCeil);
        memCeil[Knob.ATLAS_CLOUDS.ordinal()] = quarterClouds ? 0 : 1;
        if (initialTier > fallback && initialTier <= 1) fallback = initialTier;
        applyFallbackCeilings();
    }

    /** The ceiling a knob is held under now (tier and memory). */
    public double ceiling(Knob k) {
        return ceil[k.ordinal()];
    }

    /** Starts from a saved profile (skips calibration). */
    public void restore(QualityProfiles.Profile p) {
        if (p == null) return;
        model.joint.restore(p.joint(), p.jointVar(), p.samples());
        model.atlas.restore(p.atlas(), p.atlasVar(), p.samples());
        if (p.knobs() != null && p.knobs().length == N) {
            for (int k = 0; k < N; k++) {
                double v = Math.max(floor[k], Math.min(1, p.knobs()[k]));
                target[k] = applied[k] = v;
                discreteOut[k] = Knob.ALL[k].snap(v);
            }
            scaleOut = quantScale(applied[Knob.ATLAS_SCALE.ordinal()]);
        }
        profileLoaded = true;
    }

    /** The state worth saving. */
    public QualityProfiles.Profile snapshot(String key, String gpu, int width, int height, long savedAt) {
        return new QualityProfiles.Profile(key, gpu, width, height, hardware.tier().name(), model.joint.theta.clone(),
                model.joint.variances(), model.atlas.theta.clone(), model.atlas.variances(), target.clone(),
                model.joint.samples(), savedAt);
    }

    // ------------------------------------------------------------------ per frame

    /**
     * The knobs to draw this frame with (a live array: copy it to keep it). Advances calibration, probes, slews and
     * the fallback tier.
     */
    public double[] frame(long now) {
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        frames++;
        if (!adaptive) {
            Arrays.fill(out, 1);
            return out;
        }
        if (phase == Phase.WAIT) start(now);
        if (phase == Phase.QUICK && covered && plan == null && !profileLoaded) {
            plan = calibrationPlan();  // a loading cover came up after all: calibrate properly behind it
            planIndex = planFrames = 0;
            enter(Phase.CALIBRATE, now);
        }
        if (phase == Phase.CALIBRATE) {
            if (!covered) {
                enter(Phase.QUICK, now);  // the cover went away: finish with gentle nudges
            } else {
                double[] step = plan.get(planIndex);
                // the plan's settings, under the memory ceilings (calibration must not allocate what cannot fit)
                for (int k = 0; k < N; k++) out[k] = Math.min(step[k], memCeil[k]);
                if (++planFrames >= CAL_FRAMES) {
                    planFrames = 0;
                    if (++planIndex >= plan.size()) {
                        enter(Phase.LIVE, now);
                        reallocate(now, true);
                        System.arraycopy(target, 0, applied, 0, N);  // still covered: no slew needed
                        for (int k = 0; k < N; k++) discreteOut[k] = Knob.ALL[k].snap(target[k]);
                        scaleOut = quantScale(target[Knob.ATLAS_SCALE.ordinal()]);
                    }
                }
                return out;
            }
        }
        double budget = budgetMs();
        boolean fastDue = gpuEma > budget * 1.25 && now - lastAlloc > SECOND / 10;
        if (now - lastAlloc > SECOND / 2 || fastDue) reallocate(now, false);
        updateFallback(now, budget);
        slew(dt, budget);
        probe(now, budget);
        compose(now, budget);
        return out;
    }

    private void start(long now) {
        System.arraycopy(memCeil, 0, ceil, 0, N);
        if (fallback > 0) applyFallbackCeilings();
        reallocate(now, true);  // from the priors (or the restored profile)
        System.arraycopy(target, 0, applied, 0, N);
        for (int k = 0; k < N; k++) discreteOut[k] = Knob.ALL[k].snap(target[k]);
        scaleOut = quantScale(target[Knob.ATLAS_SCALE.ordinal()]);
        if (profileLoaded) enter(Phase.LIVE, now);
        else if (covered) {
            plan = calibrationPlan();
            planIndex = planFrames = 0;
            enter(Phase.CALIBRATE, now);
        } else enter(Phase.QUICK, now);
    }

    private void enter(Phase p, long now) {
        phase = p;
        phaseStart = now;
        probeKnob = -1;
        probesThisPass = 0;
        nextProbe = now + (p == Phase.QUICK ? SECOND / 2 : SECOND * 2);
    }

    /** All max, all floor, each pack cost alone over the floor, far detail off, each atlas knob off, all max again. */
    List<double[]> calibrationPlan() {
        List<double[]> p = new ArrayList<>();
        p.add(ones.clone());
        p.add(floor.clone());
        for (Knob k : new Knob[] {Knob.AIR_MARCH, Knob.HALOS, Knob.LIGHT_SHAFTS, Knob.SSR, Knob.SSAO, Knob.SHADOW_TAPS,
                Knob.WAVES, Knob.SMOKE_COLUMNS}) {
            double[] v = floor.clone();
            v[k.ordinal()] = 1;
            p.add(v);
        }
        for (Knob k : new Knob[] {Knob.FAR_DETAIL, Knob.ATLAS_SCALE, Knob.ATLAS_LOD, Knob.ATLAS_CLOUDS,
                Knob.ATLAS_SHADOWS}) {
            double[] v = ones.clone();
            v[k.ordinal()] = 0;
            p.add(v);
        }
        p.add(ones.clone());
        return p;
    }

    private void reallocate(long now, boolean force) {
        lastAlloc = now;
        double budget = budgetMs();
        double allocBudget = budget * 0.92 - Math.max(-budget * 0.3, Math.min(budget * 0.5, residual));
        double[] cand = Allocator.allocate(floor, ceil, allocBudget, q -> model.frameMs(q, context));
        if (force || gpuEma < 0) {
            System.arraycopy(cand, 0, target, 0, N);
            return;
        }
        boolean over = gpuEma > budget * 1.02, under = gpuEma < budget * 0.85;
        boolean hold = cpuLimited || hostBusy;
        // a probe changes the frame on purpose: no decisions on its frames (nor the ones measured just after it)
        if (probeKnob >= 0 || now - probeEnded < SECOND / 2) return;
        double[] was = target.clone();
        if (over && !hold) {
            if (model.frameMs(cand, context) < model.frameMs(target, context) - 1e-6) System.arraycopy(cand, 0, target, 0, N);
        } else if (under) {
            if (Allocator.value(cand) > Allocator.value(target) + 1e-6) System.arraycopy(cand, 0, target, 0, N);
        }
        // inside the band (or CPU-bound while over): hold
        atlasDetailByMeasure(was, now, over && !hold, under);
    }

    /**
     * atlas's scale and LOD go up by what the frame measures, not by the model: their cost follows level 0's reach and its
     * fill, seconds late and far from linear (126 -> 1208 blocks: 2.5 -> 30 ms), which the model priced cheap, so each
     * reallocation under the budget sent them to the top and the frame over it (the static camera's cycle, 10-26). Under
     * the budget they rise ATLAS_RAISE_STEP at most, once the last rise has had ATLAS_RAISE_SETTLE to show its cost; over
     * it they follow the model down; in the band they hold.
     */
    static final double ATLAS_RAISE_STEP = 0.05;
    static final long ATLAS_RAISE_SETTLE = 4 * SECOND;
    private long atlasRaisedAt = Long.MIN_VALUE / 2;

    private void atlasDetailByMeasure(double[] was, long now, boolean over, boolean under) {
        boolean rose = false;
        for (Knob k : new Knob[] {Knob.ATLAS_SCALE, Knob.ATLAS_LOD}) {
            int i = k.ordinal();
            if (target[i] <= was[i]) continue;  // (down, or held: as the model says)
            if (!under || now - atlasRaisedAt < ATLAS_RAISE_SETTLE) {
                target[i] = was[i];
                continue;
            }
            target[i] = Math.min(target[i], Math.max(was[i], applied[i]) + ATLAS_RAISE_STEP);
            rose = true;
        }
        if (rose) atlasRaisedAt = now;
    }

    private void updateFallback(long now, double budget) {
        if (gpuEma < 0) return;
        boolean noRoom = model.frameMs(floor, context) + residual > budget;
        // (atlas's scale and LOD still sliding down to their target: the frame has not had what they give back yet, no fall)
        boolean sliding = applied[Knob.ATLAS_SCALE.ordinal()] > target[Knob.ATLAS_SCALE.ordinal()] + 0.02
                || applied[Knob.ATLAS_LOD.ordinal()] > target[Knob.ATLAS_LOD.ordinal()] + 0.02;
        boolean wayOver = gpuEma > budget * 1.15 && noRoom && !cpuLimited && !hostBusy && !sliding;
        boolean atlasHeavy = atlasEma > 0 && atlasEma >= 0.25 * gpuEma;
        if (wayOver && fallback < 2 && (fallback == 0 ? context.atlasActive : atlasHeavy)) {
            if (overSince < 0) overSince = now;
            if (now - overSince > (fallback == 0 ? 5 : 8) * SECOND) {
                fallback++;
                overSince = -1;
                if (fallback == 2) {
                    // a retry that failed within a minute waits longer next time (3 min, then 7.5, ... at most 30)
                    if (now - lastRetry < 60 * SECOND) retryWait = Math.min(30 * 60 * SECOND, (long) (retryWait * 2.5));
                    retryAt = now + retryWait;
                }
                applyFallbackCeilings();
            }
        } else overSince = -1;
        if (fallback == 2 && now >= retryAt) {
            fallback = 1;  // look again: the far field at its lowest, measured for real
            lastRetry = now;
            retryAt = Long.MAX_VALUE;
            overSince = underSince = -1;
            applyFallbackCeilings();
            return;
        }
        // stepping back up must leave room for what comes back: atlas's predicted cost at its floor when it is off
        double comeback = 0;
        if (fallback == 2) {
            QualityModel.Context with = new QualityModel.Context();
            with.megapixels = context.megapixels;
            with.packActive = context.packActive;
            with.atlasActive = true;
            comeback = model.atlasMs(floor, with) * Math.max(0.5, model.joint.theta[QualityModel.J_KAPPA]);
        }
        if (fallback > 0 && gpuEma + comeback < budget * 0.6) {
            if (underSince < 0) underSince = now;
            if (now - underSince > 15 * SECOND) {
                fallback--;
                underSince = -1;
                retryAt = Long.MAX_VALUE;
                if (fallback == 0) retryWait = 180 * SECOND;
                applyFallbackCeilings();
            }
        } else underSince = -1;
    }

    private void applyFallbackCeilings() {
        for (Knob k : new Knob[] {Knob.ATLAS_SCALE, Knob.ATLAS_LOD, Knob.ATLAS_CLOUDS, Knob.ATLAS_SHADOWS})
            ceil[k.ordinal()] = fallback > 0 ? 0 : memCeil[k.ordinal()];
        for (int k = 0; k < N; k++) target[k] = Math.min(target[k], ceil[k]);
        gpuEma = -1;  // the new tier measures afresh
        residual = 0;
    }

    /**
     * atlas's render scale and LOD bias move continuously and damped, in every direction and in every tier (a tier is a
     * ceiling they slide under, never a jump): up ATLAS_UP, down ATLAS_DOWN (ATLAS_DOWN_HEAVY over 120 % of the budget),
     * knob units a second. They move level 0's reach, the far trace's level choice, the streaming behind them: at the old
     * rates (up 0.12, down 2.0) a static camera cycled 2.5 -> 30 ms every 30 s (10-26).
     */
    static final double ATLAS_UP = 0.05, ATLAS_DOWN = 0.10, ATLAS_DOWN_HEAVY = 0.15;

    private void slew(double dt, double budget) {
        boolean heavy = gpuEma > budget * 1.2;
        for (int k = 0; k < N; k++) {
            double d = target[k] - applied[k];
            boolean atlasDetail = k == Knob.ATLAS_SCALE.ordinal() || k == Knob.ATLAS_LOD.ordinal();
            double rate = atlasDetail ? (d > 0 ? ATLAS_UP : heavy ? ATLAS_DOWN_HEAVY : ATLAS_DOWN)
                    : d > 0 ? 0.12 : heavy ? 2.0 : 0.6;  // per second: up gently, down quickly
            applied[k] += Math.copySign(Math.min(Math.abs(d), rate * dt), d);
        }
    }

    private void probe(long now, double budget) {
        if (probeKnob >= 0) {
            if (--probeFrames <= 0) {
                probeKnob = -1;
                probeEnded = now;
                nextProbe = now + (phase == Phase.QUICK ? SECOND / 7 : SECOND * 3 / 2);
                if (phase == Phase.QUICK && probesThisPass >= eligibleCount()) enter(Phase.LIVE, now);
            }
            return;
        }
        if (now < nextProbe || cpuLimited || hostBusy || gpuEma > budget * 1.02) {
            if (phase == Phase.QUICK && now - phaseStart > 20 * SECOND) enter(Phase.LIVE, now);
            return;
        }
        for (int i = 0; i < N; i++) {
            int k = (probeCursor + i) % N;
            // a ghost only where its predicted entry cost fits the headroom (+ a quarter of the budget for its 20
            // frames): an expensive feature that is off is measured by the covered calibration instead
            boolean ghost = ghostable(k) && gpuEma < budget * 0.95 && ghostCost(k) < budget * 1.17 - gpuEma;
            if (!probeable(k) && !ghost) continue;
            probeKnob = k;
            probeCursor = k + 1;
            // a feature that is on: briefly a little lower; one that is off: a "ghost" at 0.01, which pays its whole
            // entry cost (the setup every pixel does once it is on) while the pack fades it to a tenth or less, so an
            // off feature's real cost is still measured and it comes back when this machine can afford it
            probeDelta = ghost && !probeable(k) ? -0.01 : phase == Phase.QUICK ? 0.3 : 0.15;
            probeFrames = phase == Phase.QUICK ? 10 : 20;
            probesThisPass++;
            return;
        }
        if (phase == Phase.QUICK) enter(Phase.LIVE, now);  // nothing to probe
    }

    private boolean probeable(int k) {
        Knob knob = Knob.ALL[k];
        if (knob.discrete() || knob == Knob.ATLAS_SCALE || knob == Knob.BLOOM || knob == Knob.FALLBACK_CLOUDS) return false;
        if (knob.owner == Knob.Owner.PACK && !context.packActive) return false;
        if (knob.owner == Knob.Owner.ATLAS && !context.atlasActive) return false;
        return applied[k] - floor[k] >= 0.2;
    }

    private double ghostCost(int k) {
        double[] t = applied.clone();
        double base = model.frameMs(t, context);
        t[k] = 0.01;
        return model.frameMs(t, context) - base;
    }

    /** An off pack feature that fades in from 0 (its contribution scales with q below its first level). */
    private boolean ghostable(int k) {
        Knob knob = Knob.ALL[k];
        return knob.owner == Knob.Owner.PACK && context.packActive && knob.offBelow > 0 && !knob.discrete()
                && applied[k] <= 1e-6 && target[k] <= 1e-6 && ceil[k] > 0;
    }

    private int eligibleCount() {
        int c = 0;
        for (int k = 0; k < N; k++) if (probeable(k) || ghostable(k)) c++;
        return Math.max(1, c);
    }

    private void compose(long now, double budget) {
        boolean heavy = gpuEma > budget * 1.2;
        for (int k = 0; k < N; k++) {
            Knob knob = Knob.ALL[k];
            double v = applied[k];
            if (k == probeKnob) v -= probeDelta;
            // (atlas's scale and LOD: the slewed value as it is, under the memory's ceiling only; a tier's ceiling is their
            // target, which they slide to)
            boolean atlasDetail = knob == Knob.ATLAS_SCALE || knob == Knob.ATLAS_LOD;
            double cap = atlasDetail ? memCeil[k] : ceil[k];
            if (knob.discrete()) {
                double want = knob.snap(Math.min(target[k], ceil[k]));
                boolean down = want < discreteOut[k];
                if (want != discreteOut[k] && now - discreteChanged[k] > (down && heavy ? SECOND : 4 * SECOND)) {
                    discreteOut[k] = want;
                    discreteChanged[k] = now;
                }
                v = discreteOut[k];
            } else if (knob == Knob.ATLAS_SCALE) {
                double want = quantScale(Math.min(v, cap));
                boolean down = want < scaleOut;
                if (want != scaleOut && now - scaleChanged > (down && heavy ? SECOND / 2 : 2 * SECOND)) {
                    scaleOut = want;
                    scaleChanged = now;
                }
                v = scaleOut;
            }
            out[k] = Math.max(Math.min(floor[k], cap), Math.min(cap, Math.min(1, v)));
        }
    }

    /** ATLAS_SCALE q in steps of 0.05 of render scale (1/12 of q). */
    static double quantScale(double q) {
        return Math.round(Knob.clamp01(q) * 12) / 12.0;
    }

    // ------------------------------------------------------------------ measurements

    /** A whole frame's GPU time (ms, NaN when unknown) and wall time (ms), for the knobs that frame used. */
    public void observeFrame(double[] knobs, double gpuMs, double wallMs, long now) {
        if (wallMs > 0 && wallMs < 1000) wallEma = wallEma < 0 ? wallMs : wallEma * 0.9 + wallMs * 0.1;
        if (!(gpuMs > 0) || gpuMs > 1000 || knobs == null || knobs.length != N) return;
        // probe frames teach the model but do not count as how the frame is doing (they are not what it draws)
        boolean probeFrame = probeKnob >= 0 || now - probeEnded < SECOND / 10;
        if (!probeFrame || gpuEma < 0) gpuEma = gpuEma < 0 ? gpuMs : gpuEma * 0.9 + gpuMs * 0.1;
        if (!model.observeFrame(knobs, context, gpuMs)) rejected++;
        double pred = model.frameMs(knobs, context);
        residual = residual * 0.95 + (gpuMs - pred) * 0.05;
        // CPU-bound: the wall clock is over the target frame while the GPU sits idle part of every frame
        double frameMs = 1000.0 / targetFps;
        cpuLimited = wallEma > frameMs * 1.1 && gpuEma < wallEma * 0.8;
    }

    /** atlas's own GPU time (ms) for the knobs that frame used. */
    public void observeAtlas(double[] knobs, double ms) {
        if (!(ms > 0) || ms > 1000 || knobs == null || knobs.length != N) return;
        atlasEma = atlasEma < 0 ? ms : atlasEma * 0.9 + ms * 0.1;
        model.observeAtlas(knobs, context, ms);
    }

    // ------------------------------------------------------------------ outputs

    public Phase phase() {
        return phase;
    }

    /** The knob being probed this frame, or -1. */
    int probing() {
        return probeKnob;
    }

    /** 0 normal; 1 the far field at its lowest; 2 the far field off. */
    public int fallbackTier() {
        return fallback;
    }

    public double[] target() {
        return target.clone();
    }

    /** Overall level 0..1 (visual value of the drawn knobs over the maximum). */
    public double level() {
        return Allocator.value(out) / Allocator.value(ones);
    }

    public double gpuMs() {
        return gpuEma;
    }

    public double atlasMs() {
        return atlasEma;
    }

    public boolean cpuLimited() {
        return cpuLimited;
    }

    public QualityModel model() {
        return model;
    }

    public HardwareTier.Guess hardware() {
        return hardware;
    }

    /** One line for the log and the debug overlay. */
    /** One block for the profile line: the tier, the budget, the frame and atlas measured, atlas's knobs drawn and aimed at. */
    public String profileLine() {
        int s = Knob.ATLAS_SCALE.ordinal(), l = Knob.ATLAS_LOD.ordinal();
        return String.format(Locale.ROOT, "quality{%s tier %d budget %.1f gpu %.1f atlas %.1f scale %.2f->%.2f lod %.2f->%.2f out %.2f %.2f%s}",
                phase, fallback, budgetMs(), gpuEma, atlasEma, applied[s], target[s], applied[l], target[l], out[s], out[l],
                cpuLimited ? " cpu-bound" : "");
    }

    public String describe() {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                "quality %s %s x%.2f target %.0f fps (budget %.1f ms) gpu %.1f ms atlas %.1f ms level %.2f%s%s tier %d model %d samples, %d rejected:",
                phase, hardware.tier(), hardware.speed(), targetFps, budgetMs(), gpuEma, atlasEma, level(),
                cpuLimited ? " cpu-bound" : "", hostBusy ? " host-busy" : "", fallback, model.joint.samples(), rejected));
        for (Knob k : Knob.ALL) b.append(String.format(Locale.ROOT, " %s=%.2f", k.name().toLowerCase(Locale.ROOT), out[k.ordinal()]));
        return b.toString();
    }
}
