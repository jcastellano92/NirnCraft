package com.nirncraft.atlas;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * The Java side of atlas's error paths, against the real library (no Minecraft, no GPU needed: the CPU backend and
 * the native test hook): the status codes surface as {@link AtlasNative.AtlasException} with the right kind (out
 * of memory recovers, a lost device and a hang stay dead, destroy is clean, a new context renders), the 0.10 calls
 * bind (memory info, clipmap levels), and Java's native-access refusal becomes {@link AtlasNative.NativeAccessDenied}.
 *
 * <pre>java --enable-native-access=ALL-UNNAMED -cp native/build/java com.nirncraft.atlas.AtlasFaultProbe
 *      native/build/Release/atlas_core.dll [cpu|vulkan]
 * java --illegal-native-access=deny -cp native/build/java com.nirncraft.atlas.AtlasFaultProbe DLL denied</pre>
 * Exit code 0 when every check holds.
 */
public final class AtlasFaultProbe {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        Path dll = Path.of(args.length > 0 ? args[0] : "native/build/Release/atlas_core.dll").toAbsolutePath();
        String mode = args.length > 1 ? args[1] : "cpu";
        if (mode.equals("denied")) {
            // run with --illegal-native-access=deny and no --enable-native-access
            try {
                AtlasNative.load(dll);
                check(false, "load refused under --illegal-native-access=deny");
            } catch (AtlasNative.NativeAccessDenied e) {
                check(e.getMessage().contains("--enable-native-access="), "NativeAccessDenied names the JVM argument: "
                        + e.getMessage());
            }
            check(AtlasNative.nativeAccessAdvice() != null, "advice offered: " + AtlasNative.nativeAccessAdvice());
            System.exit(failures == 0 ? 0 : 1);
        }
        int backend = mode.equals("vulkan") ? AtlasNative.BACKEND_VULKAN : AtlasNative.BACKEND_CPU;
        AtlasNative n = AtlasNative.load(dll);
        System.out.println("loaded " + n.buildInfo() + ", abi " + AtlasNative.abiString(n.abi()));
        check(n.abi() == AtlasNative.ABI_VERSION, "abi 0.10");
        check(AtlasNative.nativeAccessAdvice() == null, "native access enabled (no advice)");

        AtlasNative.Frame f = new AtlasNative.Frame();
        float[] depth = new float[32 * 32];
        try (AtlasNative.Context ctx = scene(n, backend, f)) {
            ctx.render(f, null, null, depth);
            check(Math.abs(depth[16 * 32 + 16] - 40f) < 0.5f, "renders (depth " + depth[16 * 32 + 16] + ")");
            AtlasNative.MemoryInfo mi = ctx.memoryInfo();
            check(backend == AtlasNative.BACKEND_CPU ? mi == null : mi != null && mi.deviceLocal() > 0,
                    "memory info: " + mi);
            // out of memory: that frame, then it works again
            check(ctx.debugFault(AtlasNative.FAULT_OUT_OF_MEMORY, 0), "arm out of memory");
            AtlasNative.AtlasException oom = renderFails(ctx, f, depth);
            check(oom != null && oom.outOfMemory() && !oom.deviceLost(), "out of memory surfaces: " + oom);
            check(renderFails(ctx, f, depth) == null, "the next frame renders");
            // a lost device: that frame and every later one, then a clean close
            check(ctx.debugFault(AtlasNative.FAULT_DEVICE_LOST, 0), "arm device lost");
            AtlasNative.AtlasException lost = renderFails(ctx, f, depth);
            check(lost != null && lost.deviceLost() && lost.status == AtlasNative.ERR_DEVICE_LOST, "device lost surfaces: " + lost);
            AtlasNative.AtlasException again = renderFails(ctx, f, depth);
            check(again != null && again.deviceLost(), "and stays lost: " + again);
            check(ctx.info().contains("\"deviceLost\":true"), "atlas_info says so");
            ctx.glDetach();
        }
        try (AtlasNative.Context ctx = scene(n, backend, f)) {
            check(ctx.debugFault(AtlasNative.FAULT_TIMEOUT, 0), "arm timeout");
            AtlasNative.AtlasException hung = renderFails(ctx, f, depth);
            check(hung != null && hung.deviceLost() && hung.status == AtlasNative.ERR_TIMEOUT, "a hang surfaces as lost: " + hung);
        }
        try (AtlasNative.Context ctx = scene(n, backend, f)) {
            Arrays.fill(depth, 0);
            ctx.render(f, null, null, depth);
            check(Math.abs(depth[16 * 32 + 16] - 40f) < 0.5f, "a fresh context after a loss renders (the mod's retry)");
            boolean argChecked = false;
            try {
                ctx.setClipmap(9);
            } catch (AtlasNative.AtlasException e) {
                argChecked = e.status == AtlasNative.ERR_ARG;
            }
            check(argChecked && ctx.setClipmap(6), "clipmap levels: 9 refused, 6 taken");
        }
        System.out.println(failures == 0 ? "PASS" : "FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static AtlasNative.AtlasException renderFails(AtlasNative.Context ctx, AtlasNative.Frame f, float[] depth) {
        try {
            ctx.render(f, null, null, depth);
            return null;
        } catch (AtlasNative.AtlasException e) {
            return e;
        }
    }

    /** A flat plane 40 blocks under a camera looking straight down (as tests/test_main.cpp testAbi). */
    private static AtlasNative.Context scene(AtlasNative n, int backend, AtlasNative.Frame f) {
        AtlasNative.Context ctx = n.create(backend);
        int cols = AtlasNative.TILE * AtlasNative.TILE;
        float[] surface = new float[cols], water = new float[cols];
        short[] top = new short[cols], fill = new short[cols];
        Arrays.fill(surface, 100f);
        Arrays.fill(water, Float.NaN);
        Arrays.fill(top, (short) 2);
        Arrays.fill(fill, (short) 3);
        for (int tz = -1; tz <= 1; tz++)
            for (int tx = -1; tx <= 1; tx++) ctx.uploadColumnTile(0, tx, tz, surface, water, top, fill, null, null);
        ctx.update(10.5, 140, 10.5, 0);
        f.x = 10.5;
        f.y = 140;
        f.z = 10.5;
        float[] inv = {0.1f, 0, 0, 0, 0, 0, 0.1f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};
        System.arraycopy(inv, 0, f.invViewProj, 0, 16);
        f.width = 32;
        f.height = 32;
        f.maxDistance = 1000;
        f.sunX = 0;
        f.sunY = 1;
        f.sunZ = 0;
        f.lodBias = 1;
        f.flags = AtlasNative.FRAME_ATMOS;
        return ctx;
    }
}
