package com.skycraft.fire;

import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns {@link AnchorType#FIRE_SITE} anchors, which the burnt-building and aftermath painters record, into fire sites.
 * Anchor owner label: {@code intensity|height|smoke[|flags]} (for example {@code 1.2|9|3|1}). Settlements are rescanned
 * only when their anchor list grew.
 */
public final class FireAnchors {
    private static final Map<String, Integer> SEEN = new HashMap<>();

    private FireAnchors() {}

    /** Registers the fire sites of every recorded settlement that changed (all of them when {@code all}). Returns sites added. */
    public static int scan(ServerLevel level, boolean all) {
        int added = 0;
        SettlementAnchors data = SettlementAnchors.get(level);
        for (SettlementAnchors.Entry e : data.entries()) {
            int size = e.anchors().size();
            Integer seen = SEEN.get(e.key);
            if (!all && seen != null && seen == size) continue;
            SEEN.put(e.key, size);
            for (Anchor a : e.anchors(AnchorType.FIRE_SITE)) {
                if (FireSites.at(level, a.pos()) != null && !all) continue;
                String[] f = a.owner().split("\\|");
                try {
                    float intensity = Float.parseFloat(f[0]);
                    int height = Integer.parseInt(f[1]);
                    int smoke = f.length > 2 ? Integer.parseInt(f[2]) : 2;
                    int flags = f.length > 3 ? Integer.parseInt(f[3]) : 0;
                    FireSites.add(level, a.pos(), intensity, height, smoke, flags, 0);
                    added++;
                } catch (RuntimeException ex) {
                    // a malformed label is a content bug the lint will report; never break the tick
                }
            }
        }
        return added;
    }

    /** Forgets the per-settlement scan state (a dev command re-recorded anchors). */
    public static void reset() {
        SEEN.clear();
    }
}
