package com.skycraft.aether;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one line a player (or a bug report) needs about Skycraft's graphics, logged once per session when the far
 * field's state is first known and again whenever it changes:
 * <pre>
 *   Skycraft graphics: far field on (zero-copy) on NVIDIA GeForce RTX 3090, quality HIGH
 *   Skycraft graphics: far field on (host-copy, no shader pack: fallback compositor) on AMD Radeon 780M, quality IGPU
 *   Skycraft graphics: far field off: no macOS build of aether_core (...)
 * </pre>
 * and whether the player should also see it in game (once per session, the HUD's message log: Skycraft clears
 * vanilla toasts): only when the far field is off for a reason worth knowing about (safe mode after a crash, Java
 * refusing native access, no usable GPU), never for a platform that simply has no build or a deliberate setting.
 * Pure (AetherStatusLineTest).
 */
final class AetherStatusLine {
    private AetherStatusLine() {}

    /** Why the far field is off, for the notice policy. */
    enum Off { PLATFORM, SETTING, SAFE_MODE, NATIVE_ACCESS, NO_DEVICE, MEMORY, DEVICE_LOST, ERROR }

    private static final Pattern DEVICE = Pattern.compile("\"device\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern INTEROP = Pattern.compile("\"interop\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The device name from aether_info's JSON (null when absent). */
    static String device(String infoJson) {
        if (infoJson == null) return null;
        Matcher m = DEVICE.matcher(infoJson);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    /** "zero-copy" or "host-copy" from aether_info's interop field (null when not attached). */
    static String interop(String infoJson) {
        if (infoJson == null) return null;
        Matcher m = INTEROP.matcher(infoJson);
        if (!m.find()) return null;
        String v = m.group(1);
        return v.startsWith("zero-copy") ? "zero-copy" : v.startsWith("host-copy") ? "host-copy" : null;
    }

    static String on(String infoJson, boolean fallbackCompositor, String quality) {
        String mode = interop(infoJson);
        String dev = device(infoJson);
        StringBuilder b = new StringBuilder("Skycraft graphics: far field on (");
        b.append(mode != null ? mode : "not attached");
        if (fallbackCompositor) b.append(", no shader pack: fallback compositor");
        b.append(")");
        if (dev != null && !dev.isEmpty()) b.append(" on ").append(dev);
        if (quality != null && !quality.isEmpty()) b.append(", quality ").append(quality);
        return b.toString();
    }

    static String off(String reason) {
        return "Skycraft graphics: far field off: " + (reason == null || reason.isBlank() ? "unknown reason" : reason);
    }

    /** True when an in-game notice is worth showing for this reason (see the class comment). */
    static boolean notice(Off why) {
        return switch (why) {
            case SAFE_MODE, NATIVE_ACCESS, NO_DEVICE, MEMORY, DEVICE_LOST -> true;
            case PLATFORM, SETTING, ERROR -> false;
        };
    }

    /** The short in-game text for a reason (the log line has the details). */
    static String noticeText(Off why) {
        return switch (why) {
            case SAFE_MODE -> "Distant terrain is off this session (the last start crashed). See the log.";
            case NATIVE_ACCESS -> "Distant terrain is off: Java blocked native access. See the log.";
            case NO_DEVICE -> "Distant terrain is off: no suitable Vulkan GPU. See the log.";
            case MEMORY -> "Distant terrain is off: not enough video memory.";
            case DEVICE_LOST -> "Distant terrain paused: the graphics driver reset. Retrying shortly.";
            default -> "Distant terrain is off. See the log.";
        };
    }

    /** Classifies a start failure for the notice policy. */
    static Off classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof AetherLibrary.UnsupportedPlatformException) return Off.PLATFORM;
            if (c instanceof AetherNative.NativeAccessDenied || c instanceof IllegalCallerException) return Off.NATIVE_ACCESS;
            if (c instanceof AetherNative.AetherException a) {
                if (a.deviceLost()) return Off.DEVICE_LOST;
                if (a.outOfMemory()) return Off.MEMORY;
                if (a.status == AetherNative.ERR_GPU && a.getMessage() != null && a.getMessage().contains("aether_create"))
                    return Off.NO_DEVICE;
            }
        }
        return Off.ERROR;
    }
}
