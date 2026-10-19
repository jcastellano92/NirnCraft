package com.nirncraft.quest.faction;

import com.nirncraft.economy.Merchants;
import com.nirncraft.economy.Shop;
import com.nirncraft.quest.Faction;
import com.nirncraft.quest.story.StoryData;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.NpcRegistry;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.Npcs;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.settlement.Anchor;
import com.nirncraft.tamriel.settlement.AnchorType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * The people of the guild halls, spawned by the settlement population from the hall's {@code GUILD_*} anchors: the
 * hall's head at the desk (unless a named questline NPC lives in this hall: then that NPC stands there), two trainers
 * at the practice area whose trade decides what they teach, the quartermaster behind the counter with the faction's
 * stock, and the members' beds (owned by the faction: members sleep there freely).
 *
 * <p>Staff carry {@link #TAG} (faction id) and {@link #JOB} in their persistent data; the faction dialogue, discounts
 * and the kin-slaying rule read them.</p>
 */
public final class FactionStaff {
    public static final String TAG = "nirncraft_faction";
    public static final String JOB = "nirncraft_faction_job";

    private FactionStaff() {}

    /** The faction of a hall anchor's owner label ({@code fighters_guild#12}). */
    @Nullable
    public static Faction factionOf(Anchor a) {
        String owner = a.owner();
        int hash = owner.indexOf('#');
        return Faction.byId(hash < 0 ? owner : owner.substring(0, hash));
    }

    /** The faction an NPC works for, if it is hall staff. */
    @Nullable
    public static Faction factionOf(LivingEntity e) {
        String id = e.getPersistentData().getStringOr(TAG, "");
        return id.isEmpty() ? null : Faction.byId(id);
    }

    public static String job(LivingEntity e) {
        return e.getPersistentData().getStringOr(JOB, "");
    }

    /** Percent off a member gets from their own faction's hall staff (0 for everyone else). */
    public static int discountFor(net.minecraft.world.entity.player.Player player, @Nullable LivingEntity npc) {
        if (npc == null) return 0;
        Faction f = factionOf(npc);
        if (f == null) return 0;
        return FactionData.discount(f, com.nirncraft.quest.Factions.rank(com.nirncraft.core.SkyData.get(player), f));
    }

    /** Fills one hall anchor; true when it is done (spawned, or nothing to spawn). */
    public static boolean populate(ServerLevel level, Anchor a, BlockPos at, String settlement) {
        Faction f = factionOf(a);
        if (f == null) return true;
        FactionData.Info info = FactionData.info(f);
        if (a.type() == AnchorType.GUILD_BED) {
            com.nirncraft.crime.Ownership.setOwner(level, a.pos(), "faction:" + f.id,
                    Component.translatable("faction.nirncraft.owner", f.displayName()));
            return true;
        }
        if (info == null) return true;
        FactionData.Staff staff = info.staff();
        return switch (a.type()) {
            case GUILD_LEADER -> hasQuestHead(f, settlement) || spawn(level, at, f, staff.head(), "leader", 6, null, null) != null;
            case GUILD_TRAINER -> {
                String[] trades = staff.trainerTrades();
                int n = count(level, at, f, "trainer");
                String trade = trades.length == 0 ? null : trades[n % trades.length];
                yield spawn(level, at, f, staff.trainer(), "trainer", 5, trade, null) != null;
            }
            case GUILD_VENDOR -> staff.vendorPools().length == 0
                    || spawn(level, at, f, staff.trainer(), "vendor", 4, null, staff.vendorPools()) != null;
            default -> true;
        };
    }

    /**
     * Where the head of {@code faction}'s hall in {@code placeId} stands, once the hall has been generated (its
     * anchors recorded); null before that or if the place has no such hall.
     */
    @Nullable
    public static BlockPos hallSpot(net.minecraft.server.MinecraftServer server, String faction, String placeId) {
        var entry = com.nirncraft.tamriel.settlement.SettlementAnchors.find(server.overworld(), placeId);
        if (entry.isEmpty()) return null;
        for (Anchor a : entry.get().anchors(AnchorType.GUILD_LEADER)) {
            Faction f = factionOf(a);
            if (f != null && f.id.equals(faction)) return a.pos();
        }
        return null;
    }

    /** A questline NPC is this hall's head (it is spawned at the hall by the quest NPC system). */
    static boolean hasQuestHead(Faction f, String settlement) {
        for (StoryData.NpcDef def : StoryData.npcs()) {
            if (f.id.equals(def.hall) && settlement.equals(def.place)) return true;
        }
        return false;
    }

    private static int count(ServerLevel level, BlockPos at, Faction f, String job) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(24, 8, 24),
                e -> e.isAlive() && f.id.equals(e.getPersistentData().getStringOr(TAG, "")) && job.equals(job(e))).size();
    }

    @Nullable
    static NpcEntity spawn(ServerLevel level, BlockPos at, Faction f, NpcRole role, String job, int home,
                           @Nullable String trade, @Nullable String[] pools) {
        NpcEntity npc = Npcs.create(level, at, role);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        CompoundTag pd = npc.getPersistentData();
        pd.putString(TAG, f.id);
        pd.putString(JOB, job);
        pd.putBoolean("nirncraft_settler", true);
        String name = pd.getStringOr(NpcRegistry.NAME_KEY, "");
        if (name.isEmpty()) name = NpcNames.generate(npc.race(), npc.isFemale(), level.getRandom());
        npc.setCustomName(Component.translatable("faction.nirncraft.staff." + job, name, f.displayName()));
        // trainers teach through their trade (economy trainers), quartermasters sell the faction's stock
        CompoundTag shop = new CompoundTag();
        shop.putInt("level", job.equals("leader") ? 5 : 4);
        if (pools != null) {
            ListTag list = new ListTag();
            for (String p : pools) list.add(StringTag.valueOf(p));
            shop.put("pools", list);
        }
        pd.put(Shop.KEY, shop);
        if (trade != null) pd.putString(Merchants.PROFESSION_KEY, trade);
        else pd.remove(Merchants.PROFESSION_KEY);
        npc.setHome(at, home);
        return level.addFreshEntity(npc) ? npc : null;
    }
}
