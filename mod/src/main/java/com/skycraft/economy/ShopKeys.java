package com.skycraft.economy;

import com.skycraft.society.Housing;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which shop a merchant works in. A shop is an establishment (a building of a settlement), not a person: the innkeeper
 * and the cook of one inn, the three merchants of one market hall, share one stock and one gold pool
 * ({@link ShopBook}).
 *
 * <p>The key, in order:
 * <ol>
 *   <li>an explicit {@code skycraft_shop.key} (a generator that knows its building may write it; this class caches what
 *   it derives under the same name, so a shop keeps its key when the plan or the NPC's anchor list changes);</li>
 *   <li>{@code <settlement>/<owner>}: the settlement's place id and the owner label of the NPC's anchor ({@code
 *   smithy#4}, {@code inn#1}: label and building index, unique per building). The NPC's anchor is the housing slot
 *   it was spawned for ({@link Housing#SLOT_KEY}); an NPC without one (an innkeeper the survival module sent, a
 *   merchant spawned by older code) uses the nearest counter anchor around its workplace or position;</li>
 *   <li>{@code npc:<uuid>}: a traveller (caravan, tinker, stranded merchant) is a shop of its own.</li>
 * </ol>
 * The building also says which trades it is: the union of the shop types of every counter anchor with the same
 * owner ({@link ShopTrades#poolsForAnchor}), so a tailor's shop is a tailor's whatever pool the NPC was spawned with.</p>
 */
public final class ShopKeys {
    public static final String KEY = "key";
    private static final String BUILDING_POOLS = "bpools";
    private static final String INN = "inn";

    private ShopKeys() {}

    /** The establishment an NPC works in, as far as it could be resolved. */
    public record Info(String key, List<ShopType> pools, boolean inn) {}

    /** The shop key of an NPC (never null). Server side. */
    public static String of(LivingEntity npc) {
        Info i = resolve(npc);
        return i != null ? i.key() : "npc:" + npc.getUUID();
    }

    /** Whether the key is a building's (shared) rather than a traveller's own. */
    public static boolean isBuilding(String key) {
        return !key.startsWith("npc:");
    }

    /** The shop types the NPC's building says it sells; empty when unknown (use the NPC's own pools then). */
    public static List<ShopType> buildingPools(LivingEntity npc) {
        CompoundTag shop = npc.getPersistentData().getCompound(Shop.KEY);
        if (shop.contains(BUILDING_POOLS, Tag.TAG_LIST)) return read(shop.getList(BUILDING_POOLS, Tag.TAG_STRING));
        Info i = resolve(npc);
        return i == null ? List.of() : i.pools();
    }

    private static List<ShopType> read(ListTag list) {
        List<ShopType> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ShopType t = ShopType.byId(list.getString(i));
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * Resolves (and caches in the NPC's shop tag) the building of a settled NPC; null when it has none yet or is a
     * traveller. Cheap after the first success: a tag read.
     */
    @Nullable
    public static Info resolve(LivingEntity npc) {
        CompoundTag pd = npc.getPersistentData();
        CompoundTag shop = pd.getCompound(Shop.KEY);
        if (shop.contains(KEY, Tag.TAG_STRING) && !shop.getString(KEY).isEmpty()) {
            return new Info(shop.getString(KEY), read(shop.getList(BUILDING_POOLS, Tag.TAG_STRING)), shop.getBoolean(INN));
        }
        if (!(npc.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return null;
        Info info = derive(level, npc, pd);
        if (info == null) return null;
        // cache (the tag exists for every merchant that has pools; a villager has none, so create it)
        if (!pd.contains(Shop.KEY, Tag.TAG_COMPOUND)) pd.put(Shop.KEY, shop);
        CompoundTag target = pd.getCompound(Shop.KEY);
        target.putString(KEY, info.key());
        ListTag pools = new ListTag();
        for (ShopType t : info.pools()) pools.add(StringTag.valueOf(t.id()));
        target.put(BUILDING_POOLS, pools);
        target.putBoolean(INN, info.inn());
        return info;
    }

    @Nullable
    private static Info derive(ServerLevel level, LivingEntity npc, CompoundTag pd) {
        SettlementAnchors.Entry entry = null;
        Anchor mine = null;
        String slot = pd.getString(Housing.SLOT_KEY);
        int hash = slot.lastIndexOf('#');
        if (hash > 0) {
            Optional<SettlementAnchors.Entry> e = SettlementAnchors.find(level, slot.substring(0, hash));
            if (e.isPresent()) {
                try {
                    int idx = Integer.parseInt(slot.substring(hash + 1));
                    List<Anchor> anchors = e.get().anchors();
                    if (idx >= 0 && idx < anchors.size()) {
                        entry = e.get();
                        mine = anchors.get(idx);
                    }
                } catch (NumberFormatException ignored) {
                    // an unreadable slot falls through to the position search
                }
            }
        }
        if (mine == null) {
            BlockPos ref = pd.contains(Housing.WORK_KEY) ? BlockPos.of(pd.getLong(Housing.WORK_KEY)) : npc.blockPosition();
            Optional<SettlementAnchors.Entry> e = SettlementAnchors.near(level, ref, 16);
            if (e.isEmpty()) return null;
            entry = e.get();
            double best = Double.MAX_VALUE;
            boolean innkeeper = com.skycraft.survival.inn.Innkeepers.isInnkeeper(npc);
            for (Anchor a : entry.anchors()) {
                if (a.owner().isEmpty() || ShopTrades.poolsForAnchor(a.type().id()).isEmpty()) continue;
                if (innkeeper && a.type() != AnchorType.INN) continue;
                double dx = a.pos().getX() - ref.getX(), dz = a.pos().getZ() - ref.getZ(), dy = a.pos().getY() - ref.getY();
                double max = innkeeper ? 24 : 10;
                if (Math.abs(dx) > max || Math.abs(dz) > max || Math.abs(dy) > 8) continue;
                double d = dx * dx + dz * dz + dy * dy;
                if (d < best) {
                    best = d;
                    mine = a;
                }
            }
            if (mine == null) return null;
        }
        String owner = mine.owner();
        if (owner.isEmpty()) return null;
        List<ShopType> pools = new ArrayList<>();
        boolean inn = false;
        for (Anchor a : entry.anchors()) {
            if (!owner.equals(a.owner())) continue;
            if (a.type() == AnchorType.INN) inn = true;
            for (ShopType t : ShopTrades.poolsForAnchor(a.type().id())) if (!pools.contains(t)) pools.add(t);
        }
        pools.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        return new Info(entry.key + "/" + owner, pools, inn);
    }
}
