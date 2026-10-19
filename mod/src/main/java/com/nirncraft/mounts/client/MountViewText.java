package com.nirncraft.mounts.client;

import com.nirncraft.mounts.MountKind;
import com.nirncraft.mounts.MountProfile;
import com.nirncraft.mounts.MountView;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Words, grades and numbers of a {@link MountView}, shared by the target card, the sheet and the stable screen. */
public final class MountViewText {
    /** Poor, Fair, Good, Fine, Superb. */
    public static final int[] GRADE_COLORS = {0xFFD0705A, 0xFFB8AD98, 0xFFFFFFFF, 0xFF7FD27F, 0xFFE8C060};

    private MountViewText() {}

    public static Component gradeName(int grade) {
        return Component.translatable("mounts.nirncraft.grade." + Math.max(0, Math.min(4, grade)));
    }

    /** "Good", or "Fair-Good" for an estimate. */
    public static Component grade(MountView v, int stat) {
        int lo = v.gradeLo()[stat], hi = v.gradeHi()[stat];
        if (lo == hi) return stat == MountProfile.TEMPER ? temper(lo) : gradeName(lo);
        return Component.literal("").append(gradeName(lo)).append("-").append(gradeName(hi));
    }

    public static int color(MountView v, int stat) {
        int lo = v.gradeLo()[stat], hi = v.gradeHi()[stat];
        return GRADE_COLORS[Math.max(0, Math.min(4, (lo + hi) / 2))];
    }

    /** The temperament scale of the same five steps. */
    public static Component temper(int grade) {
        return Component.translatable("mounts.nirncraft.temper." + Math.max(0, Math.min(4, grade)));
    }

    /** What the kind calls the stat ("Wind", "Burden", ...). */
    public static Component label(MountKind kind, int stat) {
        if (kind == null) return Component.translatable("mounts.nirncraft.stat.horse." + MountProfile.STAT_IDS[stat]);
        return Component.translatable(MountProfile.of(kind).labelKey(stat));
    }

    public static Component trustLabel() {
        return Component.translatable("mounts.nirncraft.stat.trust");
    }

    /** One vague word of a stat at a grade: "swift", "sturdy". Empty for an unremarkable one. */
    public static Component word(int stat, int grade) {
        if (grade <= 1) return Component.translatable("mounts.nirncraft.word." + MountProfile.STAT_IDS[stat] + ".low");
        if (grade >= 3) return Component.translatable("mounts.nirncraft.word." + MountProfile.STAT_IDS[stat] + ".high");
        return Component.empty();
    }

    /** "swift, sturdy and placid": up to three remarkable traits, the most remarkable first. */
    public static Component vague(MountView v) {
        List<int[]> traits = new ArrayList<>();
        for (int s = 0; s < MountView.STATS; s++) {
            int g = v.gradeLo()[s];
            if (g != 2) traits.add(new int[]{s, g});
        }
        if (traits.isEmpty()) return Component.translatable("mounts.nirncraft.word.ordinary");
        traits.sort((a, b) -> Integer.compare(Math.abs(b[1] - 2), Math.abs(a[1] - 2)));
        List<Component> words = new ArrayList<>();
        for (int i = 0; i < Math.min(3, traits.size()); i++) words.add(word(traits.get(i)[0], traits.get(i)[1]));
        var out = Component.literal("");
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) out.append(i == words.size() - 1 ? " & " : ", ");
            out.append(words.get(i));
        }
        return out;
    }

    /** Youth of an animal in words, for a viewer without Horse Sense. */
    public static Component levelWord(int level) {
        return Component.translatable("mounts.nirncraft.levelword." + (level <= 3 ? 0 : level <= 9 ? 1 : level <= 16 ? 2 : 3));
    }

    public static String number(int stat, float v) {
        if (v < 0) return "?";
        return switch (stat) {
            case MountProfile.SPEED -> String.format(Locale.ROOT, "%.1f m/s", v);
            case MountProfile.STAMINA -> Math.round(v) + " s";
            case MountProfile.HEALTH -> Math.round(v) + "";
            case MountProfile.JUMP -> String.format(Locale.ROOT, "%.1f", v);
            case MountProfile.CARRY -> Math.round(v) + " kg";
            default -> Math.round(v) + "";
        };
    }

    /** Name for a card: its own name, else its breed; "an animal" when the viewer cannot tell what it is. */
    public static Component name(MountView v) {
        if (!v.name().isEmpty()) return Component.literal(v.name());
        MountKind k = v.kind();
        return k == null ? Component.translatable("mounts.nirncraft.unknown") : k.displayName();
    }
}
