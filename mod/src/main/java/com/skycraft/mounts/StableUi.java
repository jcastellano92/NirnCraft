package com.skycraft.mounts;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.fauna.Stables;
import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The server side of the stable master's screen: one screen with your own mount (wherever it is parked, read from its
 * record) beside every animal the stable has this week, and the Train tab. Everything the client shows is built here
 * ({@link MountInspect} decides what the viewer's perks let them see), and every transaction is recomputed here: the
 * figure the client confirmed must match, or nothing happens.
 *
 * <p>A stable's stock is the same for everyone in an in-game week ({@link MountStock#offers}); an animal a player
 * bought is gone from <em>their</em> view of the stock until the week turns (it is recorded in their mount record,
 * {@value #SOLD}).</p>
 */
public final class StableUi {
    static final String SOLD = "stock_sold";
    static final String KEY = "skycraft_stable_key";
    /** How far from the stablemaster you may still trade. */
    static final double REACH = 8;

    private StableUi() {}

    public static void open(ServerPlayer player, LivingEntity npc) {
        send(player, npc, Component.empty(), true);
    }

    // ------------------------------------------------------------------ state

    /** The stable's identity for its stock: fixed on the stablemaster the first time they are asked. */
    static String key(ServerPlayer player, LivingEntity npc) {
        CompoundTag t = npc.getPersistentData();
        if (!t.getStringOr(KEY, "").isEmpty()) return t.getStringOr(KEY, "");
        Region r = Regions.at(player.level(), npc.blockPosition());
        BlockPos p = npc.blockPosition();
        String key = r.id() + ":" + (p.getX() >> 6) + ":" + (p.getZ() >> 6);
        t.putString(KEY, key);
        return key;
    }

    static List<MountKind> sale(ServerPlayer player, LivingEntity npc) {
        Region r = Regions.at(player.level(), npc.blockPosition());
        return MountKind.forSale(r.id(), r.province());
    }

    /**
     * The offers this player may buy now: the animals players sold to this stable (the very individuals, with their
     * stats, level, trust and name), one animal from the sold-on pool that suits the region, then the stable's
     * generated stock for the week that this player has not already taken.
     */
    public static List<MountStock.Offer> offers(ServerPlayer player, LivingEntity npc) {
        long week = MountStock.weekOf(player.level().getGameTime());
        String key = key(player, npc);
        List<MountKind> kinds = sale(player, npc);
        List<MountStock.Offer> out = new ArrayList<>();
        MountStable stock = MountStable.get(player.level().getServer());
        for (CompoundTag token : stock.stockOf(key)) {
            MountStock.Offer o = fromToken("t:", token);
            if (o != null) out.add(o);
        }
        List<CompoundTag> pool = new ArrayList<>();
        for (CompoundTag token : stock.pool()) {
            MountKind k = MountToken.kind(token);
            if (k != null && !k.unique() && kinds.contains(k)) pool.add(token);
        }
        if (!pool.isEmpty()) {
            CompoundTag pick = pool.get(Math.floorMod((key + "|" + week).hashCode(), pool.size()));
            MountStock.Offer o = fromToken("p:", pick);
            if (o != null) out.add(o);
        }
        out.addAll(MountStock.offers(key, week, kinds, sold(player, week)));
        return out;
    }

    @Nullable
    private static MountStock.Offer fromToken(String prefix, CompoundTag token) {
        MountKind kind = MountToken.kind(token);
        if (kind == null || kind.unique()) return null;
        MountStats s = MountToken.stats(token);
        return new MountStock.Offer(prefix + MountToken.id(token), kind, s, MountStock.value(kind, s), token);
    }

    /** Takes the animal of a player-sold or pooled offer out of storage: true only for the first caller. */
    @Nullable
    private static CompoundTag takeToken(ServerPlayer player, LivingEntity npc, String offerId) {
        MountStable stock = MountStable.get(player.level().getServer());
        try {
            if (offerId.startsWith("t:")) return stock.take(key(player, npc), java.util.UUID.fromString(offerId.substring(2)));
            if (offerId.startsWith("p:")) return stock.takeFromPool(java.util.UUID.fromString(offerId.substring(2)));
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static Set<String> sold(ServerPlayer player, long week) {
        CompoundTag rec = Mounts.data(player).getCompoundOrEmpty(SOLD);
        Set<String> out = new HashSet<>();
        if (rec.getLongOr("week",0L) != week) return out;
        for (Tag t : rec.getListOrEmpty("ids")) out.add(t.asString().orElse(""));
        return out;
    }

    private static void markSold(ServerPlayer player, long week, String id) {
        CompoundTag tag = Mounts.data(player);
        CompoundTag rec = tag.getCompoundOrEmpty(SOLD);
        ListTag ids = rec.getLongOr("week",0L) == week ? rec.getListOrEmpty("ids") : new ListTag();
        ids.add(StringTag.valueOf(id));
        rec.putLong("week", week);
        rec.put("ids", ids);
        tag.put(SOLD, rec);
    }

    static void send(ServerPlayer player, LivingEntity npc, Component message, boolean open) {
        long week = MountStock.weekOf(player.level().getGameTime());
        MountView own = MountInspect.ownView(player);
        MountKind ownKind = Mounts.active(player);
        MountStats ownStats = ownKind == null ? null : MountGrowth.known(player, ownKind);
        int tradeIn = Mounts.tradeInValue(player);

        SaddleType saddle = null;
        ItemStack barding = ItemStack.EMPTY;
        if (own != null && ownKind != null) {
            saddle = own.saddle().isEmpty() ? null : SaddleType.byId(own.saddle());
            Entity e = Mounts.activeEntity(player);
            if (e instanceof Mob m) barding = MountGear.barding(m);
            else if (!Mounts.data(player).getStringOr("barding", "").isEmpty()) barding = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.parse(Mounts.data(player).getStringOr("barding",""))));
        }
        List<StablePackets.Offer> rows = new ArrayList<>();
        for (MountStock.Offer o : offers(player, npc)) {
            boolean saddleFits = saddle == null || MountGear.accepts(o.kind(), saddle);
            boolean bardFits = barding.isEmpty() || (o.kind().backing == MountKind.Backing.HORSE && MountItems.isHorseArmor(barding))
                    || (o.kind().backing == MountKind.Backing.BEAST && barding.is(MountItems.BEAST_BARDING.get()));
            rows.add(new StablePackets.Offer(o.id(), MountInspect.offerView(player, o.kind(), o.stats(), o.token() == null ? "" : MountToken.name(o.token())), o.price(), saddleFits, bardFits));
        }
        List<StablePackets.TrainRow> train = new ArrayList<>();
        int left = 0;
        if (ownStats != null && ownKind != null && !ownKind.unique()) {
            int used = ownStats.trainWeek == week ? ownStats.trainCount : 0;
            left = Math.max(0, MountStock.TRAINING_PER_WEEK - used);
            for (int stat = 0; stat <= MountProfile.TEMPER; stat++) {
                MountStock.Train can = MountStock.canTrain(ownStats, stat, week);
                int points = stat == MountProfile.TEMPER ? Math.round(ownStats.trust) : ownStats.grow[stat];
                int cap = stat == MountProfile.TEMPER ? 100 : ownStats.cap(stat);
                train.add(new StablePackets.TrainRow((byte) stat, MountStock.trainFee(ownStats, stat), (byte) can.ordinal(), (byte) points, (byte) cap));
            }
        }
        SkyNetwork.sendToPlayer(player, new StablePackets.State(npc.getId(), npc.getDisplayName(), open, Currency.balance(player), message,
                own, tradeIn, ownKind != null && !ownKind.unique(), left, rows, train));
    }

    // ------------------------------------------------------------------ transactions

    static void handle(ServerPlayer player, StablePackets.Action a) {
        Entity e = player.level().getEntity(a.npcId());
        if (!(e instanceof LivingEntity npc) || !Stables.isStableMaster(npc) || player.distanceToSqr(npc) > REACH * REACH) return;
        switch (a.op()) {
            case StablePackets.BUY -> buy(player, npc, a);
            case StablePackets.SELL -> sell(player, npc, a);
            case StablePackets.TRAIN -> train(player, npc, a);
            default -> send(player, npc, Component.empty(), false);
        }
    }

    private static void buy(ServerPlayer player, LivingEntity npc, StablePackets.Action a) {
        long week = MountStock.weekOf(player.level().getGameTime());
        MountStock.Offer offer = null;
        for (MountStock.Offer o : offers(player, npc)) if (o.id().equals(a.arg())) offer = o;
        if (offer == null) {
            send(player, npc, Component.translatable("mounts.skycraft.stable.gone"), false);
            return;
        }
        int tradeIn = Mounts.tradeInValue(player);
        int net = offer.price() - tradeIn;
        if (net != a.expected()) { // the figures moved (a level, a week): show them again
            send(player, npc, Component.translatable("mounts.skycraft.stable.stale"), false);
            return;
        }
        if (net > 0 && !Currency.take(player, net)) {
            send(player, npc, Component.translatable("dialogue.skycraft.stables.no_gold", net), false);
            return;
        }
        // the animal changes hands in one step: a sold-here animal leaves the stable's storage now (a second click, or
        // another buyer, finds nothing), and its token goes to the buyer's record in the grant below
        CompoundTag token = offer.token() != null ? takeToken(player, npc, offer.id()) : offer.tokenOrNew();
        if (token == null) {
            if (net > 0) Currency.give(player, net);
            send(player, npc, Component.translatable("mounts.skycraft.stable.gone"), false);
            return;
        }
        BlockPos spawnPos = npc.blockPosition().relative(npc.getDirection(), 2);
        Mob mob = Mounts.grant(player, offer.kind(), spawnPos, token, key(player, npc));
        if (mob == null) {
            if (offer.token() != null) MountStable.get(player.level().getServer()).consign(key(player, npc), token); // back where it was
            if (net > 0) Currency.give(player, net);
            send(player, npc, Component.translatable("mounts.skycraft.stable.nospace"), false);
            return;
        }
        if (net < 0) Currency.give(player, -net); // the old one was worth more than the new one costs
        if (offer.token() == null) markSold(player, week, offer.id());
        Saddlebags.fit(player, MountGear.bagCapacity(mob, offer.kind()));
        player.level().playSound(null, spawnPos, SoundEvents.HORSE_SADDLE.value(), SoundSource.NEUTRAL, 1.0f, 1.0f);
        Notifier.message(player, Component.translatable("fauna.skycraft.horse.bought", offer.kind().displayName()));
        send(player, npc, Component.translatable("mounts.skycraft.stable.bought", offer.kind().displayName()), false);
    }

    private static void sell(ServerPlayer player, LivingEntity npc, StablePackets.Action a) {
        int value = Mounts.tradeInValue(player);
        MountKind kind = Mounts.active(player);
        if (kind == null || kind.unique() || value <= 0) {
            send(player, npc, Component.translatable("mounts.skycraft.stable.nothing_to_sell"), false);
            return;
        }
        if (value != a.expected()) {
            send(player, npc, Component.translatable("mounts.skycraft.stable.stale"), false);
            return;
        }
        Mounts.sell(player, key(player, npc)); // the animal itself goes into this stable's stock
        send(player, npc, Component.translatable("mounts.skycraft.stable.sold", kind.displayName(), value), false);
    }

    private static void train(ServerPlayer player, LivingEntity npc, StablePackets.Action a) {
        int stat;
        try {
            stat = Integer.parseInt(a.arg());
        } catch (NumberFormatException ex) {
            return;
        }
        MountKind kind = Mounts.active(player);
        MountStats s = MountGrowth.active(player);
        if (kind == null || s == null || stat < 0 || stat > MountProfile.TEMPER) return;
        long week = MountStock.weekOf(player.level().getGameTime());
        MountStock.Train can = MountStock.canTrain(s, stat, week);
        if (can != MountStock.Train.OK) {
            send(player, npc, Component.translatable("mounts.skycraft.stable.train." + can.name().toLowerCase(java.util.Locale.ROOT)), false);
            return;
        }
        int fee = MountStock.trainFee(s, stat);
        if (fee != a.expected()) {
            send(player, npc, Component.translatable("mounts.skycraft.stable.stale"), false);
            return;
        }
        if (!Currency.take(player, fee)) {
            send(player, npc, Component.translatable("dialogue.skycraft.stables.no_gold", fee), false);
            return;
        }
        if (!MountGrowth.train(player, stat)) {
            Currency.give(player, fee);
            return;
        }
        String label = Component.translatable(MountProfile.of(kind).labelKey(stat)).getString();
        send(player, npc, Component.translatable("mounts.skycraft.stable.trained", kind.displayName(), label), false);
    }
}
