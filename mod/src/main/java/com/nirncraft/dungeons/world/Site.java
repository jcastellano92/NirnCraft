package com.nirncraft.dungeons.world;

/**
 * What one dungeon start builds, shared by all its pieces: the theme, the culture kit, a named site's special layout,
 * the dungeon-wide sub-theme ({@code flavor}: bandit cave vs animal den, Imperial vs Stormcloak camp...) and one
 * site parameter ({@code param}: the shout a Word Wall teaches, a Standing Stone's sign, a Daedric Prince...).
 * {@code place} is the id of the named place it stands for ("" for random sites): its lore, boss name and story
 * ({@link Identity}) hang on it.
 */
public record Site(Theme theme, Kit kit, Special special, int flavor, int param, String place) {
    public Site(Theme theme, Kit kit, Special special, int flavor, int param) {
        this(theme, kit, special, flavor, param, "");
    }

    public static Site of(Theme theme, int flavor) {
        return new Site(theme, Kit.NONE, Special.NONE, flavor, 0, "");
    }

    public Site withTheme(Theme t) {
        return new Site(t, kit, special, flavor, param, place);
    }

    public Site withPlace(String id) {
        return new Site(theme, kit, special, flavor, param, id == null ? "" : id);
    }

    public boolean is(Special s) {
        return special == s;
    }
}
