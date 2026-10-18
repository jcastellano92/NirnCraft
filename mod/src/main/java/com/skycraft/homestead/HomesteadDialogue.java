package com.skycraft.homestead;

import com.skycraft.core.Currency;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.inventory.Inv;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.world.Discovery;
import com.skycraft.world.WorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The house person: the builder of a city or large town (a person tagged {@value #TAG}, a merchant whose pools list
 * {@code builder}, or the settlement's steward in a capital, city or large town). They sell the drafting table that
 * founds a homestead, point out open land, and sell building materials. Houses and their furnishings are still bought
 * from the steward and the Jarl (the economy module); the home's upgrades, expansions and permissions are made at the
 * drafting table itself.
 */
public final class HomesteadDialogue {
    /** Persistent-data flag a settlement builder (or a builder's shop NPC) carries. */
    public static final String TAG = "skycraft_builder";
    private static final int ORDER = 300;
    /** How far the builder's knowledge of open land reaches. */
    private static final int LAND_RANGE = 3000;
    private static final int LAND_SHOWN = 3;

    private HomesteadDialogue() {}

    public static void register() {
        Dialogue.registerProvider(HomesteadDialogue::addOptions);
    }

    /** True for the people who sell homestead services. */
    public static boolean isBuilder(LivingEntity npc) {
        if (npc == null || !npc.isAlive()) return false;
        CompoundTag pd = npc.getPersistentData();
        if (pd.getBooleanOr(TAG, false) || "builder".equals(pd.getStringOr("skycraft_trade",""))) return true;
        ListTag pools = pd.getCompoundOrEmpty("skycraft_shop").getListOrEmpty("pools");
        for (int i = 0; i < pools.size(); i++) if ("builder".equals(pools.getStringOr(i, ""))) return true;
        if (pd.getBooleanOr("skycraft_steward",false) && npc.level() instanceof ServerLevel level) {
            // the steward of a capital, a city or a large town (tier 0 and 1); not the village's
            return com.skycraft.tamriel.settlement.SettlementPopulation.tierAt(level, npc.blockPosition()) <= 1;
        }
        return false;
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (player.level().dimension() != Level.OVERWORLD || !isBuilder(npc)) return;
        HomesteadClaim own = HomesteadData.get(player.level().getServer()).getClaim(player.getUUID());
        int price = SurvivalConfig.CLAIM_PRICE_DRAFTING_TABLE.get();
        if (own == null) {
            out.add(new DialogueOption("homestead.found",
                    Component.translatable("dialogue.skycraft.homestead.found", Currency.formatCompact(price)), ORDER,
                    (p, n) -> sellTable(p, n, price)));
            out.add(new DialogueOption("homestead.land", Component.translatable("dialogue.skycraft.homestead.land"), ORDER + 1,
                    HomesteadDialogue::showLand));
        } else {
            out.add(new DialogueOption("homestead.mine", Component.translatable("dialogue.skycraft.homestead.mine"), ORDER,
                    HomesteadDialogue::about));
        }
        for (int i = 0; i < Hearthfire.OFFERS.size(); i++) {
            Hearthfire.Offer o = Hearthfire.OFFERS.get(i);
            if (o.animal() != null) continue;
            out.add(new DialogueOption("homestead.buy." + o.id(),
                    Component.translatable("dialogue.skycraft.homestead.buy", Component.translatable("homestead.skycraft.offer." + o.id()),
                            Currency.formatCompact(o.price())), ORDER + 10 + i, (p, n) -> sellMaterial(p, n, o)));
        }
    }

    private static void sellTable(ServerPlayer player, LivingEntity npc, int price) {
        net.minecraft.world.item.Item table = com.skycraft.crafting.CraftingBlocks.DRAFTING_TABLE.get().asItem();
        if (HomesteadData.get(player.level().getServer()).getClaim(player.getUUID()) != null) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.homestead.have_claim"));
            return;
        }
        if (Inv.has(player, table)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.homestead.have_table"));
            return;
        }
        if (!Currency.take(player, price)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.homestead.no_gold", price));
            return;
        }
        Inv.giveOrDrop(player, new ItemStack(table));
        Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.homestead.sold_table"));
    }

    /** Marks the nearest unclaimed open plots on the player's map (as places heard of: a point on the compass). */
    private static void showLand(ServerPlayer player, LivingEntity npc) {
        HomesteadData data = HomesteadData.get(player.level().getServer());
        var pdata = SkyData.get(player);
        int x = player.getBlockX(), z = player.getBlockZ();
        List<Place> plots = new ArrayList<>();
        for (Place p : Places.overlapping(x - LAND_RANGE, z - LAND_RANGE, x + LAND_RANGE, z + LAND_RANGE)) {
            if (!"homestead".equals(p.variant()) || p.distSqr(x, z) > (long) LAND_RANGE * LAND_RANGE) continue;
            if (WorldData.find(pdata, WorldData.placeLocId(p.id())) != null) continue;
            boolean taken = false;
            for (HomesteadClaim c : data.allClaims()) {
                long dx = c.getCenter().getX() - p.x(), dz = c.getCenter().getZ() - p.z();
                if (dx * dx + dz * dz < (long) SurvivalConfig.CLAIM_SPACING.get() * SurvivalConfig.CLAIM_SPACING.get()) taken = true;
            }
            if (!taken) plots.add(p);
        }
        plots.sort(Comparator.comparingLong((Place p) -> p.distSqr(x, z)));
        int shown = 0;
        Component names = Component.empty();
        for (Place p : plots) {
            if (shown >= LAND_SHOWN) break;
            if (Discovery.revealPlace(player, p)) {
                names = shown == 0 ? p.name() : Component.empty().append(names).append(", ").append(p.name());
                shown++;
            }
        }
        Dialogue.open(player, npc, shown == 0
                ? Component.translatable("dialogue.skycraft.homestead.no_land")
                : Component.translatable("dialogue.skycraft.homestead.land_shown", shown, names));
    }

    private static void about(ServerPlayer player, LivingEntity npc) {
        HomesteadClaim c = HomesteadData.get(player.level().getServer()).getClaim(player.getUUID());
        if (c == null) return;
        Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.homestead.about", c.plainName(), c.getRadius(), c.getBuilt().size()));
    }

    private static void sellMaterial(ServerPlayer player, LivingEntity npc, Hearthfire.Offer offer) {
        if (!Currency.take(player, offer.price())) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.homestead.no_gold", offer.price()));
            return;
        }
        Inv.giveOrDrop(player, offer.icon().get());
        Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.homestead.sold",
                Component.translatable("homestead.skycraft.offer." + offer.id())));
    }
}
