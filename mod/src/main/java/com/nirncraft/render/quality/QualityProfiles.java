package com.nirncraft.render.quality;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Per-machine calibration, saved in the config directory (config/nirncraft-quality.properties) so the next session
 * starts tuned: one entry per GPU + driver + window size (a different resolution or a driver update calibrates
 * again; the nearest saved size seeds it). Holds the fitted cost terms and their variances, the last knob targets,
 * and how many frames they rest on. At most {@link #KEEP} entries (the oldest go).
 */
public final class QualityProfiles {
    static final int KEEP = 8;
    static final int VERSION = 1;

    private QualityProfiles() {}

    /** One machine's state. Arrays are copies. */
    public record Profile(String key, String gpu, int width, int height, String tier, double[] joint, double[] jointVar,
                          double[] atlas, double[] atlasVar, double[] knobs, long samples, long savedAt) {}

    /** The key for a GPU, driver and window size: stable, file-safe. */
    public static String key(String gpu, String driver, int width, int height) {
        String raw = (gpu == null ? "" : gpu) + "|" + (driver == null ? "" : driver);
        String slug = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.length() > 48) slug = slug.substring(0, 48);
        return slug + "-" + Integer.toHexString(raw.hashCode()) + "-" + width + "x" + height;
    }

    /** Every saved profile (empty when there is no file or it cannot be read). */
    public static List<Profile> load(Path file) {
        List<Profile> out = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        if (!String.valueOf(VERSION).equals(p.getProperty("version"))) return out;
        for (String name : p.stringPropertyNames()) {
            if (!name.endsWith(".gpu")) continue;
            String k = name.substring(0, name.length() - 4);
            try {
                out.add(new Profile(k, p.getProperty(k + ".gpu"), Integer.parseInt(p.getProperty(k + ".width")),
                        Integer.parseInt(p.getProperty(k + ".height")), p.getProperty(k + ".tier"),
                        doubles(p.getProperty(k + ".joint")), doubles(p.getProperty(k + ".jointVar")),
                        doubles(p.getProperty(k + ".atlas")), doubles(p.getProperty(k + ".atlasVar")),
                        doubles(p.getProperty(k + ".knobs")), Long.parseLong(p.getProperty(k + ".samples")),
                        Long.parseLong(p.getProperty(k + ".savedAt"))));
            } catch (RuntimeException ignored) {
                // a damaged entry: skipped (the machine calibrates again)
            }
        }
        return out;
    }

    /** The profile for key, else the same GPU's at the nearest window size (its costs scale per megapixel), else null. */
    public static Profile find(List<Profile> all, String key, String gpu, int width, int height) {
        for (Profile p : all) if (p.key().equals(key)) return p;
        Profile best = null;
        double bestD = Double.MAX_VALUE;
        for (Profile p : all) {
            if (gpu == null || !gpu.equals(p.gpu())) continue;
            double d = Math.abs(Math.log((double) p.width() * p.height() / Math.max(1.0, (double) width * height)));
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Saves (replacing the same key), keeping the newest {@link #KEEP}. Written to a temp file, then moved. */
    public static void save(Path file, Profile profile) throws IOException {
        List<Profile> all = load(file);
        all.removeIf(p -> p.key().equals(profile.key()));
        all.add(profile);
        all.sort(Comparator.comparingLong(Profile::savedAt).reversed());
        while (all.size() > KEEP) all.remove(all.size() - 1);
        Properties p = new Properties();
        p.setProperty("version", String.valueOf(VERSION));
        for (Profile e : all) {
            String k = e.key();
            p.setProperty(k + ".gpu", e.gpu() == null ? "" : e.gpu());
            p.setProperty(k + ".width", String.valueOf(e.width()));
            p.setProperty(k + ".height", String.valueOf(e.height()));
            p.setProperty(k + ".tier", e.tier() == null ? "" : e.tier());
            p.setProperty(k + ".joint", join(e.joint()));
            p.setProperty(k + ".jointVar", join(e.jointVar()));
            p.setProperty(k + ".atlas", join(e.atlas()));
            p.setProperty(k + ".atlasVar", join(e.atlasVar()));
            p.setProperty(k + ".knobs", join(e.knobs()));
            p.setProperty(k + ".samples", String.valueOf(e.samples()));
            p.setProperty(k + ".savedAt", String.valueOf(e.savedAt()));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "NirnCraft adaptive quality: per-machine calibration (delete to recalibrate)");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String join(double[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) b.append(',');
            b.append(String.format(Locale.ROOT, "%.6g", v[i]));
        }
        return b.toString();
    }

    static double[] doubles(String s) {
        if (s == null || s.isBlank()) return new double[0];
        String[] parts = s.split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Double.parseDouble(parts[i].trim());
        return v;
    }
}
