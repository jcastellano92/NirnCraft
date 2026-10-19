package com.nirncraft.render.quality;

import java.util.Locale;

/**
 * A first guess of this GPU's class from its name (before any frame is measured): it sets the cost priors
 * ({@link #speed}: how many times slower than a GTX 1080 class GPU) and the default frame-rate target. The live model
 * corrects whatever the guess gets wrong; the guess only has to start in the right neighbourhood, so a list of common
 * GPUs is enough, with the device type and the vendor as the fallback.
 *
 * <p>Classes: HIGH (RTX 3070 and up, RX 6800 and up: target 72 fps), MID (GTX 1070 .. RTX 3060, RX 5600 .. 6700:
 * 60), LOW (GTX 1050 .. 1650, RX 470 .. 590, Arc A380: 50), IGPU (Intel UHD / Iris Xe, Vega APUs, 680M / 780M: 40),
 * DECK (the Steam Deck's Van Gogh APU, or any AMD APU on a 1280 x 800 panel: 40). Linux names (Mesa: "AMD Radeon
 * RX 6800 (radeonsi, navi21, ...)", "AMD Custom GPU 0405 (radv)") and Windows names classify alike.</p>
 */
public enum HardwareTier {
    HIGH(0.45, 72), MID(1.0, 60), LOW(1.9, 50), IGPU(4.5, 40), DECK(3.5, 40);

    /** Prior cost multiplier (GTX 1080 class = 1). */
    public final double speed;
    /** Default frame-rate target (capped by the refresh rate and the game's frame limit). */
    public final int targetFps;

    HardwareTier(double speed, int targetFps) {
        this.speed = speed;
        this.targetFps = targetFps;
    }

    /** A classified device: its class, a refined speed (known models), and the name it came from. */
    public record Guess(HardwareTier tier, double speed, String name) {}

    // relative to a GTX 1080 (time per frame: smaller is faster); rough raster throughput ratios
    private static final Object[][] NVIDIA = {
            {"5090", 0.18}, {"5080", 0.25}, {"5070 ti", 0.32}, {"5070", 0.38}, {"5060 ti", 0.55}, {"5060", 0.62},
            {"4090", 0.22}, {"4080", 0.30}, {"4070 ti", 0.36}, {"4070", 0.45}, {"4060 ti", 0.62}, {"4060", 0.72},
            {"3090", 0.38}, {"3080 ti", 0.40}, {"3080", 0.43}, {"3070 ti", 0.52}, {"3070", 0.55}, {"3060 ti", 0.65},
            {"3060", 0.80}, {"3050", 1.15}, {"2080 ti", 0.58}, {"2080", 0.72}, {"2070", 0.85}, {"2060", 1.0},
            {"1660 ti", 1.15}, {"1660", 1.3}, {"1650", 2.0}, {"1080 ti", 0.75}, {"1080", 1.0}, {"1070 ti", 1.1},
            {"1070", 1.25}, {"1060", 1.7}, {"1050 ti", 2.6}, {"1050", 3.0}, {"980 ti", 1.3}, {"980", 1.6},
            {"970", 1.9}, {"960", 2.8}, {"titan rtx", 0.55}, {"titan v", 0.6}, {"titan x", 1.0}};
    private static final Object[][] AMD = {
            {"9070 xt", 0.32}, {"9070", 0.38}, {"7900 xtx", 0.28}, {"7900", 0.33}, {"7800", 0.42}, {"7700", 0.5},
            {"7600", 0.75}, {"6950", 0.38}, {"6900", 0.4}, {"6800 xt", 0.42}, {"6800", 0.48}, {"6750", 0.6},
            {"6700", 0.65}, {"6650", 0.8}, {"6600", 0.85}, {"6500", 1.9}, {"6400", 2.4}, {"5700", 0.9}, {"5600", 1.05},
            {"5500", 1.6}, {"vega 64", 1.05}, {"vega 56", 1.2}, {"radeon vii", 0.85}, {"590", 1.6}, {"580", 1.7},
            {"570", 1.9}, {"480", 1.8}, {"470", 2.0}, {"780m", 3.0}, {"760m", 3.6}, {"680m", 3.4}, {"660m", 4.5},
            {"890m", 2.6}, {"880m", 3.0}};

    /**
     * @param name       the renderer / device name (DeviceInfo.name, GL_RENDERER)
     * @param vendor     the vendor name, may be empty
     * @param integrated true when the device reports itself integrated
     * @param width      the window's width in pixels (a Deck panel is 1280 x 800)
     */
    public static Guess classify(String name, String vendor, boolean integrated, int width, int height) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT).replace('_', ' ');
        String v = (vendor == null ? "" : vendor).toLowerCase(Locale.ROOT);
        boolean amd = n.contains("amd") || n.contains("radeon") || v.contains("amd") || v.contains("ati ");
        boolean nvidia = n.contains("nvidia") || n.contains("geforce") || n.contains("quadro") || v.contains("nvidia");
        boolean intel = n.contains("intel") || v.contains("intel");
        // the Steam Deck: Van Gogh ("AMD Custom GPU 0405", Mesa "vangogh"), or Sephiroth (OLED, "0932")
        if (n.contains("vangogh") || n.contains("van gogh") || n.contains("custom gpu 0405") || n.contains("custom gpu 0932")
                || n.contains("sephiroth"))
            return new Guess(DECK, DECK.speed, name);
        if (nvidia) {
            Double s = lookup(n, NVIDIA);
            if (s != null) return new Guess(tierFor(s), s, name);
            if (n.contains("mx") || integrated) return new Guess(IGPU, 3.5, name);
            return new Guess(MID, MID.speed, name);
        }
        if (amd) {
            Double s = lookup(n, AMD);
            if (s != null) return new Guess(s >= 2.6 ? IGPU : tierFor(s), s, name);
            // an APU's "AMD Radeon(TM) Graphics" / "Vega 8": integrated; on a 1280 x 800 panel, Deck-like
            boolean apu = integrated || n.matches(".*radeon\\(tm\\) graphics.*") || n.matches(".*vega [0-9]+ graphics.*")
                    || n.matches(".*radeon graphics.*");
            if (apu) return width == 1280 && height == 800 ? new Guess(DECK, DECK.speed, name) : new Guess(IGPU, IGPU.speed, name);
            return new Guess(MID, MID.speed, name);
        }
        if (intel) {
            if (n.contains("arc")) {
                if (n.matches(".*\\b(a7[0-9]{2}|b5[0-9]{2}|b7[0-9]{2})\\b.*")) return new Guess(MID, 0.85, name);
                if (n.matches(".*\\ba5[0-9]{2}\\b.*")) return new Guess(MID, 1.2, name);
                if (n.matches(".*\\ba3[0-9]{2}\\b.*")) return new Guess(LOW, 2.4, name);
                return new Guess(IGPU, 3.2, name);  // "Arc Graphics" in a Meteor Lake laptop
            }
            if (n.contains("iris xe")) return new Guess(IGPU, 5.5, name);
            return new Guess(IGPU, 8.0, name);  // UHD / HD Graphics
        }
        if (n.contains("llvmpipe") || n.contains("softpipe") || n.contains("swiftshader") || n.contains("microsoft basic"))
            return new Guess(IGPU, 20.0, name);  // a software rasteriser: everything at the floor
        if (integrated) return new Guess(IGPU, IGPU.speed, name);
        return new Guess(MID, MID.speed, name);
    }

    private static HardwareTier tierFor(double speed) {
        return speed <= 0.6 ? HIGH : speed <= 1.35 ? MID : speed <= 3.0 ? LOW : IGPU;
    }

    private static Double lookup(String n, Object[][] table) {
        // the most specific match wins ("3080 ti" before "3080", "6800 xt" before "6800")
        String best = null;
        Double speed = null;
        for (Object[] row : table) {
            String key = (String) row[0];
            if (containsModel(n, key) && (best == null || key.length() > best.length())) {
                best = key;
                speed = (Double) row[1];
            }
        }
        return speed;
    }

    private static boolean containsModel(String n, String key) {
        int i = n.indexOf(key);
        while (i >= 0) {
            boolean startOk = i == 0 || !Character.isLetterOrDigit(n.charAt(i - 1));
            int end = i + key.length();
            boolean endOk = end >= n.length() || !Character.isLetterOrDigit(n.charAt(end));
            if (startOk && endOk) return true;
            i = n.indexOf(key, i + 1);
        }
        return false;
    }
}
