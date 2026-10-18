package com.skycraft.render.quality;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The adaptive quality controller against simulated GPUs (no game): budget, smoothness, hysteresis, fallbacks. */
class QualityControllerTest {
    static final long FRAME_NS = 16_666_667L;

    /** A machine whose true costs differ from the controller's priors: per-term multipliers over the priors. */
    static final class Gpu {
        final double speed;
        final double[] jointMul = new double[QualityModel.JOINT], aetherMul = new double[QualityModel.AETHER];
        double cpuMs = 2, noise = 0.03, extraBase;
        final Random rnd = new Random(42);
        final QualityModel truth;

        Gpu(double speed) {
            this.speed = speed;
            Arrays.fill(jointMul, 1);
            Arrays.fill(aetherMul, 1);
            truth = new QualityModel(speed);
        }

        Gpu mul(int joint, double m) {
            jointMul[joint] = m;
            return this;
        }

        Gpu aetherMul(int term, double m) {
            aetherMul[term] = m;
            return this;
        }

        void apply() {
            for (int i = 0; i < QualityModel.JOINT; i++) truth.joint.theta[i] *= jointMul[i];
            for (int i = 0; i < QualityModel.AETHER; i++) truth.aether.theta[i] *= aetherMul[i];
        }

        double aether(double[] q, QualityModel.Context c) {
            return truth.aetherMs(q, c) * (1 + noise * rnd.nextGaussian());
        }

        double frame(double[] q, QualityModel.Context c) {
            return (truth.frameMs(q, c) + extraBase) * (1 + noise * rnd.nextGaussian());
        }
    }

    /** Runs the loop for `seconds` at the GPU's own pace; measurements arrive three frames late, like timestamps. */
    static long run(QualityController qc, Gpu gpu, long start, double seconds, double[] maxStep) {
        long now = start;
        ArrayDeque<double[]> inFlight = new ArrayDeque<>();
        double[] prev = null;
        int prevProbe = -1;
        long end = start + (long) (seconds * 1e9);
        while (now < end) {
            double[] q = qc.frame(now).clone();
            if (maxStep != null && prev != null) {
                for (int k = 0; k < q.length; k++) {
                    Knob knob = Knob.ALL[k];
                    if (knob.discrete() || knob == Knob.AETHER_SCALE || qc.probing() == k || prevProbe == k
                            || qc.phase() != QualityController.Phase.LIVE)
                        continue;
                    maxStep[k] = Math.max(maxStep[k], Math.abs(q[k] - prev[k]));
                }
            }
            prev = q;
            prevProbe = qc.probing();
            inFlight.add(q);
            double ms = 0;
            if (inFlight.size() > 3) {
                double[] used = inFlight.poll();
                ms = gpu.frame(used, qc.context);
                double wall = Math.max(ms, gpu.cpuMs);
                qc.observeFrame(used, ms, wall, now);
                if (qc.context.aetherActive) qc.observeAether(used, gpu.aether(used, qc.context));
            }
            now += (long) (Math.max(Math.max(ms, gpu.cpuMs), 1000.0 / 240) * 1e6);
        }
        return now;
    }

    static QualityController controller(HardwareTier tier, double fps) {
        return new QualityController(new HardwareTier.Guess(tier, tier.speed, "test"), fps, true);
    }

    @Test
    void knobValuesAreMonotoneAndLaddersReachOne() {
        for (Knob k : Knob.ALL) {
            double prev = -1;
            for (double q = 0; q <= 1.0001; q += 0.01) {
                double v = k.value(q);
                assertTrue(v >= prev - 1e-12, k + " value falls at " + q);
                prev = v;
            }
            assertEquals(k.weight, k.value(1), 1e-9, k.name());
            double q = 0;
            for (int i = 0; i < 40 && q < 1; i++) q = k.next(q);
            assertEquals(1, q, 1e-9, k + " ladder");
        }
        assertEquals(0, Knob.SSR.value(0.1), 1e-12, "below its off threshold a knob is worth nothing");
    }

    @Test
    void allocatorStaysInBudgetAndBuysCheapValueFirst() {
        QualityModel m = new QualityModel(1.0);
        QualityModel.Context c = new QualityModel.Context();
        c.megapixels = 2.07;
        double[] floor = new double[Knob.COUNT], ceil = new double[Knob.COUNT];
        for (Knob k : Knob.ALL) floor[k.ordinal()] = k.floor;
        Arrays.fill(ceil, 1);
        double floorMs = m.frameMs(floor, c), fullMs = m.frameMs(ceil, c);
        double budget = floorMs + (fullMs - floorMs) * 0.35;
        double[] q = Allocator.allocate(floor, ceil, budget, x -> m.frameMs(x, c));
        assertTrue(m.frameMs(q, c) <= budget + 1e-6, "inside the budget");
        assertTrue(Allocator.value(q) > 0.6 * Allocator.value(ceil), "most of the look for a third of the cost: " + Allocator.value(q));
        // better than scaling every knob down together to the same budget
        double lo = 0, hi = 1;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2, s = mid;
            double[] u = new double[Knob.COUNT];
            for (Knob k : Knob.ALL) u[k.ordinal()] = Math.max(k.floor, s);
            if (m.frameMs(u, c) <= budget) lo = mid; else hi = mid;
        }
        double[] uniform = new double[Knob.COUNT];
        for (Knob k : Knob.ALL) uniform[k.ordinal()] = Math.max(k.floor, lo);
        assertTrue(Allocator.value(q) > Allocator.value(uniform) * 1.1,
                "greedy " + Allocator.value(q) + " vs uniform " + Allocator.value(uniform));
        // nearly free knobs stay up
        assertEquals(1, q[Knob.BLOOM.ordinal()], 1e-9);
        // no pack: pack knobs cost nothing and stay at max
        c.packActive = false;
        double[] noPack = Allocator.allocate(floor, ceil, budget * 0.2, x -> m.frameMs(x, c));
        for (Knob k : Knob.ALL) if (k.owner == Knob.Owner.PACK) assertEquals(1, noPack[k.ordinal()], 1e-9, k.name());
    }

    @Test
    void midRangeMachineSettlesInsideTheBudgetWithoutPopping() {
        // a GTX 1080 at 1080p whose air march costs twice the prior and SSAO a third; aether a bit heavier
        Gpu gpu = new Gpu(1.0).mul(QualityModel.J_AIR, 2.0).mul(QualityModel.J_SSAO, 0.3).mul(QualityModel.J_BASE, 1.3)
                .aetherMul(QualityModel.A_TRACE, 1.4);
        gpu.apply();
        QualityController qc = controller(HardwareTier.MID, 60);
        qc.context.megapixels = 2.07;
        double[] maxStep = new double[Knob.COUNT];
        long t = run(qc, gpu, 1, 90, maxStep);
        double b = qc.budgetMs();
        assertEquals(QualityController.Phase.LIVE, qc.phase());
        assertTrue(qc.gpuMs() < b * 1.05, "over budget: " + qc.describe());
        assertTrue(qc.gpuMs() > b * 0.6, "wastes headroom: " + qc.describe());
        // the fitted base moved towards the truth (1.3x the prior), and the model predicts the frame it runs
        double fitted = qc.model().joint.theta[QualityModel.J_BASE], prior = QualityModel.JOINT_PRIOR[QualityModel.J_BASE];
        assertTrue(fitted > prior * 1.15, "base cost not learned: " + fitted + " vs prior " + prior);
        double[] now = qc.target();
        double truth = gpu.truth.frameMs(now, qc.context), predicted = qc.model().frameMs(now, qc.context);
        assertEquals(truth, predicted, truth * 0.1, "model off at the operating point");
        // slews only: no continuous knob jumps more than the fast-down rate allows in one frame (0.1 s cap x 2/s)
        for (Knob k : Knob.ALL) assertTrue(maxStep[k.ordinal()] <= 0.2 + 1e-9, k + " popped by " + maxStep[k.ordinal()]);
        // hysteresis: in a steady scene the target stops moving
        double[] before = qc.target();
        run(qc, gpu, t, 20, null);
        double[] after = qc.target();
        double moved = 0;
        for (int k = 0; k < before.length; k++) moved = Math.max(moved, Math.abs(after[k] - before[k]));
        assertTrue(moved < 0.13, "target keeps moving in a steady scene: " + moved);
        assertEquals(0, qc.fallbackTier());
    }

    @Test
    void highEndMachineKeepsNearlyEverything() {
        Gpu gpu = new Gpu(0.38);
        gpu.apply();
        QualityController qc = controller(HardwareTier.HIGH, 60);
        qc.context.megapixels = 2.07;
        run(qc, gpu, 1, 60, null);
        assertTrue(qc.level() > 0.9, qc.describe());
        assertTrue(qc.gpuMs() < qc.budgetMs(), qc.describe());
    }

    @Test
    void deckLikeMachineFallsBackAndRecovers() {
        // far slower than even the Deck prior: nothing fits at the floor, and aether is a big share of it
        Gpu gpu = new Gpu(9.0).aetherMul(QualityModel.A_TRACE, 5).aetherMul(QualityModel.A_BASE, 5);
        gpu.apply();
        QualityController qc = controller(HardwareTier.DECK, 40);
        qc.context.megapixels = 1.02;
        long t = run(qc, gpu, 1, 30, null);
        assertTrue(qc.fallbackTier() >= 1, "no fallback: " + qc.describe());
        t = run(qc, gpu, t, 40, null);
        if (qc.fallbackTier() == 2) qc.context.aetherActive = false;  // the glue stops aether at tier 2
        assertEquals(2, qc.fallbackTier(), qc.describe());
        // the scene gets far lighter (a small interior: a sixth of the work everywhere): within the first retry
        // (3 minutes) it measures the far field again and keeps it
        Gpu light = new Gpu(9.0 / 6).aetherMul(QualityModel.A_TRACE, 5).aetherMul(QualityModel.A_BASE, 5);
        light.apply();
        long end = t + 240_000_000_000L;
        while (t < end && qc.fallbackTier() == 2) t = run(qc, light, t, 1, null);
        qc.context.aetherActive = qc.fallbackTier() < 2;
        assertTrue(qc.fallbackTier() < 2, "never retried: " + qc.describe());
        run(qc, light, t, 30, null);
        assertTrue(qc.fallbackTier() < 2, "fell back again in a light scene: " + qc.describe());
    }

    @Test
    void cpuBoundFramesDoNotLowerQuality() {
        Gpu gpu = new Gpu(1.0);
        gpu.apply();
        gpu.cpuMs = 30;  // the CPU (or a busy integrated server) holds the frame at ~33 fps
        QualityController qc = controller(HardwareTier.MID, 60);
        qc.context.megapixels = 0.9;  // light on the GPU
        run(qc, gpu, 1, 40, null);
        assertTrue(qc.cpuLimited(), qc.describe());
        assertTrue(qc.level() > 0.85, "degraded a CPU-bound frame: " + qc.describe());
    }

    @Test
    void calibratesBehindALoadingCoverThenGoesLive() {
        Gpu gpu = new Gpu(1.0);
        gpu.apply();
        QualityController qc = controller(HardwareTier.MID, 60);
        qc.setCovered(true);
        List<double[]> plan = qc.calibrationPlan();
        assertTrue(plan.size() >= 10 && plan.size() * QualityController.CAL_FRAMES < 260, "plan of " + plan.size());
        long t = run(qc, gpu, 1, 0.5, null);
        assertEquals(QualityController.Phase.CALIBRATE, qc.phase());
        t = run(qc, gpu, t, 6, null);
        assertEquals(QualityController.Phase.LIVE, qc.phase(), "calibration did not finish in 6 s at 60 fps");
        // uncovered: the quick pass finishes on its own
        QualityController quick = controller(HardwareTier.MID, 60);
        run(quick, gpu, 1, 25, null);
        assertEquals(QualityController.Phase.LIVE, quick.phase());
    }

    @Test
    void profileRoundTripStartsLive() throws Exception {
        Gpu gpu = new Gpu(1.0).mul(QualityModel.J_AIR, 2.0);
        gpu.apply();
        QualityController qc = controller(HardwareTier.MID, 60);
        run(qc, gpu, 1, 30, null);
        Path dir = Files.createTempDirectory("skycraft-quality");
        Path file = dir.resolve("skycraft-quality.properties");
        String key = QualityProfiles.key("NVIDIA GeForce GTX 1080/PCIe/SSE2", "4.6.0 NVIDIA 566.36", 1920, 1080);
        QualityProfiles.save(file, qc.snapshot(key, "NVIDIA GeForce GTX 1080/PCIe/SSE2", 1920, 1080, 1000));
        for (int i = 0; i < 12; i++)  // other machines: only the newest KEEP survive, ours among them
            QualityProfiles.save(file, qc.snapshot("other-" + i, "gpu " + i, 1280, 720, 10 + i));
        List<QualityProfiles.Profile> all = QualityProfiles.load(file);
        assertEquals(QualityProfiles.KEEP, all.size());
        QualityProfiles.Profile p = QualityProfiles.find(all, key, "NVIDIA GeForce GTX 1080/PCIe/SSE2", 1920, 1080);
        assertNotNull(p);
        assertEquals(qc.model().joint.theta[QualityModel.J_AIR], p.joint()[QualityModel.J_AIR], 1e-4 * p.joint()[QualityModel.J_AIR]);
        // another window size of the same GPU seeds from the nearest
        assertNotNull(QualityProfiles.find(all, "unknown", "NVIDIA GeForce GTX 1080/PCIe/SSE2", 2560, 1440));
        QualityController next = controller(HardwareTier.MID, 60);
        next.restore(p);
        next.frame(1);
        assertEquals(QualityController.Phase.LIVE, next.phase());
        assertEquals(p.joint()[QualityModel.J_AIR], next.model().joint.theta[QualityModel.J_AIR], 1e-9);
    }

    @Test
    void classifiesTheTargetMachines() {
        assertEquals(HardwareTier.HIGH, HardwareTier.classify("NVIDIA GeForce RTX 3090/PCIe/SSE2", "NVIDIA Corporation", false, 2560, 1440).tier());
        assertEquals(HardwareTier.MID, HardwareTier.classify("NVIDIA GeForce GTX 1080/PCIe/SSE2", "NVIDIA Corporation", false, 1920, 1080).tier());
        assertEquals(HardwareTier.MID, HardwareTier.classify("NVIDIA GeForce GTX 1080 Ti/PCIe/SSE2", "", false, 1920, 1080).tier());
        assertEquals(0.75, HardwareTier.classify("NVIDIA GeForce GTX 1080 Ti/PCIe/SSE2", "", false, 1920, 1080).speed(), 1e-9);
        assertEquals(HardwareTier.LOW, HardwareTier.classify("NVIDIA GeForce GTX 1050 Ti/PCIe/SSE2", "", false, 1920, 1080).tier());
        assertEquals(HardwareTier.DECK, HardwareTier.classify("AMD Custom GPU 0405 (radv)", "AMD", true, 1280, 800).tier());
        assertEquals(HardwareTier.DECK, HardwareTier.classify("AMD Radeon Graphics (radeonsi, vangogh, LLVM 17.0.6, DRM 3.57)", "AMD", true, 1280, 800).tier());
        assertEquals(HardwareTier.DECK, HardwareTier.classify("AMD Radeon(TM) Graphics", "ATI Technologies Inc.", true, 1280, 800).tier());
        assertEquals(HardwareTier.IGPU, HardwareTier.classify("AMD Radeon(TM) Graphics", "ATI Technologies Inc.", true, 1920, 1080).tier());
        assertEquals(HardwareTier.HIGH, HardwareTier.classify("AMD Radeon RX 6800 XT (radeonsi, navi21, LLVM 17.0.6, DRM 3.57, 6.8.0)", "AMD", false, 2560, 1440).tier());
        assertEquals(HardwareTier.LOW, HardwareTier.classify("Radeon RX 580 Series", "ATI Technologies Inc.", false, 1920, 1080).tier());
        assertEquals(HardwareTier.IGPU, HardwareTier.classify("AMD Radeon 780M", "AMD", true, 1920, 1200).tier());
        assertEquals(HardwareTier.IGPU, HardwareTier.classify("Intel(R) UHD Graphics 620", "Intel", true, 1920, 1080).tier());
        assertEquals(HardwareTier.MID, HardwareTier.classify("Intel(R) Arc(TM) A770 Graphics", "Intel", false, 1920, 1080).tier());
        assertEquals(HardwareTier.IGPU, HardwareTier.classify("llvmpipe (LLVM 17.0.6, 256 bits)", "Mesa", false, 1280, 720).tier());
        assertEquals(HardwareTier.MID, HardwareTier.classify("Some Future GPU", "", false, 1920, 1080).tier());
    }

    @Test
    void hostingReservesCpuForTheServer() {
        assertEquals(4, HostLoad.clientColumnThreads(16, false, 10));
        assertEquals(2, HostLoad.clientColumnThreads(16, true, 10));
        assertEquals(1, HostLoad.clientColumnThreads(16, true, 45));
        assertEquals(1, HostLoad.clientColumnThreads(2, false, 0));
        assertEquals(4, HostLoad.serverColumnThreads(16, true, false, 10));
        assertEquals(1, HostLoad.serverColumnThreads(16, true, false, 45));
        assertEquals(2, HostLoad.serverColumnThreads(16, false, true, 10));
        assertEquals(1, HostLoad.serverColumnThreads(16, false, false, 10));
        assertEquals(20, HostLoad.pregenIntervalTicks(8));
        assertEquals(60, HostLoad.pregenIntervalTicks(20));
        assertEquals(0, HostLoad.pregenIntervalTicks(30));
        assertEquals(0, HostLoad.clientWorkerRestMs(false, 60));
        assertEquals(200, HostLoad.clientWorkerRestMs(true, 60));
    }

    @Test
    void notAdaptiveDrawsEverything() {
        QualityController qc = new QualityController(new HardwareTier.Guess(HardwareTier.IGPU, 20, "slow"), 60, false);
        double[] q = qc.frame(1);
        for (double v : q) assertEquals(1, v, 1e-12);
    }
}
