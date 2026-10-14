package com.skycraft.magic.client;

import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicPackets;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.Spells;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The magic side of Skyrim's favorites: the spells, shouts and powers a player marked (stored in {@link MagicData}), as
 * rows for the favorites quick menu, the number-key hotkeys and the scroll wheel of the inventory module
 * ({@code inventory.client.FavoritesScreen}), and the equip action for them. Items are favorites too (item NBT, see
 * {@code InvCategory}); both kinds are listed together there.
 */
public final class MagicFavorites {
    public enum Kind { SPELL, SHOUT, POWER }

    public record Fav(Kind kind, String id, Component name, int color, @Nullable Spell spell) {}

    private MagicFavorites() {}

    /** The player's favorite spells, shouts and powers that they still have, in the order they were favorited. */
    public static List<Fav> list(LocalPlayer p) {
        List<Fav> out = new ArrayList<>();
        List<String> known = MagicData.knownSpells(p);
        for (String id : MagicData.favorites(p)) {
            Spell s = Spells.byId(id);
            if (s != null) {
                if (known.contains(id) || p.isCreative()) out.add(new Fav(Kind.SPELL, id, s.displayName(), s.school.color, s));
                continue;
            }
            Shout sh = Shout.byId(id);
            if (sh != null) {
                if (MagicData.wordsLearned(p, sh) > 0 || p.isCreative()) out.add(new Fav(Kind.SHOUT, id, sh.displayName(), 0xBFD8F0, null));
                continue;
            }
            if ("power:racial".equals(id)) {
                Race race = SkyData.get(p).getRace();
                if (race != null) out.add(new Fav(Kind.POWER, id, race.powerName(), 0xD0A060, null));
            } else if (id.startsWith("power:stone:")) {
                com.skycraft.lore.StandingStone stone = com.skycraft.lore.StandingStone.byId(id.substring("power:stone:".length()));
                if (stone != null && stone.hasPower) out.add(new Fav(Kind.POWER, id, stone.powerName(), stone.color, null));
            } else if (id.startsWith(com.skycraft.curse.CursePowers.PREFIX) && MagicData.hasPower(p, id)) {
                out.add(new Fav(Kind.POWER, id, com.skycraft.curse.CursePowers.name(id.substring(com.skycraft.curse.CursePowers.PREFIX.length())), 0xC8303A, null));
            }
        }
        return out;
    }

    /** 1 = right hand, 2 = left hand, 3 = both, 0 = not equipped (shouts and powers: 1 when in the voice slot). */
    public static int equipped(LocalPlayer p, Fav f) {
        switch (f.kind) {
            case SPELL -> {
                int r = com.skycraft.magic.Hands.spellIn(p, false) != null && f.id.equals(MagicData.selectedSpell(p)) ? 1 : 0;
                int l = com.skycraft.magic.Hands.spellIn(p, true) != null && f.id.equals(MagicData.leftSpell(p)) ? 2 : 0;
                return r | l;
            }
            default -> {
                return f.id.equals(MagicData.selectedVoice(p)) || "shout:".concat(f.id).equals(MagicData.selectedVoice(p)) ? 1 : 0;
            }
        }
    }

    /** Equips it: spells to the right hand ({@code left} = the left hand), shouts and powers to the voice slot. */
    public static void activate(Fav f, boolean left) {
        int action = switch (f.kind) {
            case SPELL -> left ? MagicPackets.MenuAction.SELECT_LEFT : MagicPackets.MenuAction.SELECT_SPELL;
            case SHOUT -> MagicPackets.MenuAction.SELECT_SHOUT;
            case POWER -> MagicPackets.MenuAction.SELECT_POWER;
        };
        SkyNetwork.sendToServer(new MagicPackets.MenuAction(action, f.id));
    }

    /** Toggles the favorite mark (the server validates; removing always works). */
    public static void toggle(String id) {
        SkyNetwork.sendToServer(new MagicPackets.MenuAction(MagicPackets.MenuAction.TOGGLE_FAVORITE, id));
    }
}
