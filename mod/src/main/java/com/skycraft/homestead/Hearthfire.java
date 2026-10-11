package com.skycraft.homestead;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.Skill;
import com.skycraft.crafting.recipe.Cost;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.common.Tags;

import java.util.List;
import java.util.function.Supplier;

/**
 * What the drafting table does (server side): buying a Hearthfire plot, building plans ({@link HomesteadPlans},
 * {@link HomesteadBuilder}), turning iron, logs and stone into building materials, hiring a steward and buying
 * materials and livestock from them. Every action re-sends the table's state to the open screen.
 */
public final class Hearthfire {
    /** A building material made at the drafting table. */
    public record Material(String id, Supplier<ItemStack> result, Cost cost) {}

    /** Something the steward sells. {@code animal}: a farm animal delivered to the pen instead of an item. */
    public record Offer(String id, Supplier<ItemStack> icon, int price, EntityType<? extends Mob> animal) {}

    public static final List<Material> MATERIALS = List.of(
            new Material("nails", () -> new ItemStack(HomesteadRegistry.NAILS.get(), 20), Cost.of(() -> Items.IRON_INGOT, 1)),
            new Material("hinge", () -> new ItemStack(HomesteadRegistry.HINGE.get(), 2), Cost.of(() -> Items.IRON_INGOT, 1)),
            new Material("lock", () -> new ItemStack(HomesteadRegistry.LOCK.get(), 1), Cost.of(() -> Items.IRON_INGOT, 1)),
            new Material("iron_fittings", () -> new ItemStack(HomesteadRegistry.IRON_FITTINGS.get(), 1), Cost.of(() -> Items.IRON_INGOT, 1)),
            new Material("sawn_log", () -> new ItemStack(HomesteadRegistry.SAWN_LOG.get(), 1), Cost.tag(ItemTags.LOGS, () -> Items.SPRUCE_LOG, 3)),
            new Material("quarried_stone", () -> new ItemStack(HomesteadRegistry.QUARRIED_STONE.get(), 1),
                    Cost.tag(Tags.Items.COBBLESTONE, () -> Items.COBBLESTONE, 4)),
            new Material("glass_pane", () -> new ItemStack(Items.GLASS_PANE, 16), Cost.of(() -> Items.GLASS, 6)));

    public static final List<Offer> OFFERS = List.of(
            new Offer("sawn_log", () -> new ItemStack(HomesteadRegistry.SAWN_LOG.get(), 10), 150, null),
            new Offer("quarried_stone", () -> new ItemStack(HomesteadRegistry.QUARRIED_STONE.get(), 10), 100, null),
            new Offer("clay", () -> new ItemStack(Items.CLAY_BALL, 10), 80, null),
            new Offer("nails", () -> new ItemStack(HomesteadRegistry.NAILS.get(), 40), 60, null),
            new Offer("cow", () -> new ItemStack(Items.LEATHER), 300, EntityType.COW),
            new Offer("chicken", () -> new ItemStack(Items.FEATHER), 50, EntityType.CHICKEN),
            new Offer("horse", () -> new ItemStack(Items.SADDLE), 1000, EntityType.HORSE));

    private Hearthfire() {}

    // ------------------------------------------------------------------ state for the screen

    public static CompoundTag state(ServerPlayer player, HomesteadClaim claim) {
        // bought through the economy module's house sales: the deed is already paid for
        if (!claim.isPurchased() && claim.isOwner(player) && Homesteads.ownsPlotElsewhere(player, claim.getPlot())) {
            claim.setPurchased(true);
            HomesteadBuilder.placeDeposits(player.serverLevel(), claim);
            HomesteadData.get(player.server).setDirty();
        }
        CompoundTag t = new CompoundTag();
        t.putString("name", Component.Serializer.toJson(claim.displayName()));
        t.putString("owner", claim.getOwnerName());
        if (claim.getPlot() != null) t.putString("plot", claim.getPlot());
        t.putBoolean("purchased", claim.isPurchased());
        t.putInt("price", SurvivalConfig.PLOT_PRICE.get());
        t.putInt("stewardPrice", SurvivalConfig.STEWARD_PRICE.get());
        ListTag built = new ListTag();
        for (String b : claim.getBuilt()) built.add(StringTag.valueOf(b));
        t.put("built", built);
        t.putString("steward", claim.getSteward() == null ? "" : claim.getStewardName());
        t.putInt("door", claim.getDoorPerm());
        t.putInt("container", claim.getContainerPerm());
        t.putInt("build", claim.getBuildPerm());
        t.putInt("radius", claim.getRadius());
        t.putInt("depth", claim.getMinDepth());
        t.putInt("height", claim.getMaxHeight());
        t.putBoolean("carpenter", carpenter(player));
        t.putLong("gold", Currency.balance(player));
        t.putBoolean("owner_view", claim.isOwner(player));
        return t;
    }

    public static void sync(ServerPlayer player, HomesteadClaim claim) {
        if (player.containerMenu instanceof HomesteadMenu menu) {
            CompoundTag state = state(player, claim);
            menu.state = state;
            SkyNetwork.sendToPlayer(player, new HomesteadPackets.State(state));
        }
    }

    /** The Hearthfire perk (Woodcutting): plans need a quarter fewer materials. */
    public static boolean carpenter(ServerPlayer player) {
        return Perks.has(player, "woodcutting.hearthfire");
    }

    // ------------------------------------------------------------------ actions

    public static void buyPlot(ServerPlayer player, HomesteadClaim claim) {
        if (claim.isPurchased()) return;
        int price = SurvivalConfig.PLOT_PRICE.get();
        if (!Currency.take(player, price)) {
            Notifier.message(player, Component.translatable("homestead.skycraft.plot.no_gold", price));
            return;
        }
        claim.setPurchased(true);
        HomesteadBuilder.placeDeposits(player.serverLevel(), claim);
        HomesteadData.get(player.server).setDirty();
        Notifier.send(player, NotifyKind.BIG_TITLE, Component.translatable("homestead.skycraft.plot.bought"), claim.displayName());
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.6f, 1.0f);
        sync(player, claim);
    }

    public static void build(ServerPlayer player, HomesteadClaim claim, String planId) {
        HomesteadPlans plan = HomesteadPlans.get(planId);
        if (plan == null) return;
        if (!claim.isPurchased()) {
            Notifier.message(player, Component.translatable("homestead.skycraft.plot.buy_first"));
            return;
        }
        String blocker = plan.blocker(claim.getBuilt());
        if (blocker != null) {
            Notifier.message(player, Component.translatable("homestead.skycraft.build.blocked." + blocker.split(":")[0]));
            return;
        }
        ServerLevel level = player.serverLevel();
        String site = HomesteadBuilder.checkSite(level, claim, planId);
        if (site != null) {
            Notifier.message(player, Component.translatable(site));
            return;
        }
        boolean carpenter = carpenter(player);
        if (!player.isCreative()) {
            for (Cost cost : plan.costs()) {
                if (cost.countIn(player.getInventory(), -1) < HomesteadPlans.needed(cost, carpenter)) {
                    Notifier.message(player, Component.translatable("message.skycraft.homestead.missing_mats", cost.display().get().getHoverName()));
                    return;
                }
            }
            for (Cost cost : plan.costs()) cost.removeFrom(player.getInventory(), HomesteadPlans.needed(cost, carpenter), -1);
        }
        HomesteadBuilder.build(level, claim, planId);
        claim.getBuilt().add(planId);
        HomesteadData.get(player.server).setDirty();
        Progression.addSkillXp(player, Skill.WOODCUTTING, 40f + 4f * plan.costs().size());
        level.playSound(null, claim.getCenter(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8f, 0.8f);
        level.playSound(null, claim.getCenter(), SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 0.9f);
        Notifier.send(player, NotifyKind.BIG_TITLE, Component.translatable("homestead.skycraft.build.done"),
                Component.translatable("homestead.skycraft.plan." + planId));
        sync(player, claim);
    }

    public static void craft(ServerPlayer player, HomesteadClaim claim, int index, int times) {
        if (index < 0 || index >= MATERIALS.size()) return;
        Material m = MATERIALS.get(index);
        int made = 0;
        for (int i = 0; i < Math.max(1, Math.min(16, times)); i++) {
            if (m.cost().countIn(player.getInventory(), -1) < m.cost().count()) break;
            m.cost().removeFrom(player.getInventory(), m.cost().count(), -1);
            ItemStack out = m.result().get();
            if (!ActionHandler.addToBags(player, out)) player.drop(out, false);
            made++;
        }
        if (made == 0) {
            Notifier.message(player, Component.translatable("message.skycraft.homestead.missing_mats", m.cost().display().get().getHoverName()));
            return;
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.4f, 1.3f);
        Notifier.message(player, Component.translatable("message.skycraft.homestead.crafted", m.result().get().getHoverName()));
        player.containerMenu.broadcastChanges();
    }

    public static void hireSteward(ServerPlayer player, HomesteadClaim claim) {
        if (claim.getSteward() != null && stewardAlive(player.serverLevel(), claim)) {
            Notifier.message(player, Component.translatable("homestead.skycraft.steward.already", claim.getStewardName()));
            return;
        }
        if (!claim.getBuilt().contains("small_house")) {
            Notifier.message(player, Component.translatable("homestead.skycraft.steward.no_house"));
            return;
        }
        int price = SurvivalConfig.STEWARD_PRICE.get();
        if (!Currency.take(player, price)) {
            Notifier.message(player, Component.translatable("homestead.skycraft.plot.no_gold", price));
            return;
        }
        ServerLevel level = player.serverLevel();
        HomesteadBuilder.Frame f = HomesteadBuilder.frame(claim);
        BlockPos home = claim.getBuilt().contains("main_hall") ? f.at(0, 0, 9) : f.at(0, 0, 3);
        NpcEntity npc = Npcs.create(level, home, com.skycraft.society.NpcRole.HOUSECARL);
        if (npc == null) {
            Currency.give(player, price);
            return;
        }
        String first = npc.getName().getString();
        npc.setCustomName(Component.translatable("homestead.skycraft.steward.title", first));
        npc.setHome(home, 12);
        npc.setPersistenceRequired();
        npc.getPersistentData().putUUID("skycraft_steward_of", player.getUUID());
        level.addFreshEntity(npc);
        claim.setSteward(npc.getUUID(), first);
        HomesteadData.get(player.server).setDirty();
        Notifier.message(player, Component.translatable("homestead.skycraft.steward.hired", first));
        sync(player, claim);
    }

    private static boolean stewardAlive(ServerLevel level, HomesteadClaim claim) {
        Entity e = level.getEntity(claim.getSteward());
        // unloaded counts as alive (we can't tell); a dead steward is gone from the level
        return e == null ? !level.isLoaded(claim.getCenter()) : e.isAlive();
    }

    public static void stewardBuy(ServerPlayer player, HomesteadClaim claim, int index) {
        if (claim.getSteward() == null || index < 0 || index >= OFFERS.size()) return;
        Offer offer = OFFERS.get(index);
        if (offer.animal() != null && !claim.getBuilt().contains("animal_pen") && offer.animal() != EntityType.HORSE) {
            Notifier.message(player, Component.translatable("homestead.skycraft.steward.no_pen"));
            return;
        }
        if (!Currency.take(player, offer.price())) {
            Notifier.message(player, Component.translatable("homestead.skycraft.plot.no_gold", offer.price()));
            return;
        }
        if (offer.animal() != null) {
            ServerLevel level = player.serverLevel();
            HomesteadBuilder.Frame f = HomesteadBuilder.frame(claim);
            BlockPos at = offer.animal() == EntityType.HORSE ? f.at(-10, 0, -3) : f.at(-10, 0, 3);
            Mob mob = offer.animal().create(level);
            if (mob != null) {
                mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360f, 0);
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);
                mob.setPersistenceRequired();
                if (mob instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse) horse.tameWithName(player);
                level.addFreshEntity(mob);
            }
        } else {
            ItemStack out = offer.icon().get();
            if (!ActionHandler.addToBags(player, out)) player.drop(out, false);
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.PLAYERS, 0.6f, 1.0f);
        Notifier.message(player, Component.translatable("homestead.skycraft.steward.bought",
                Component.translatable("homestead.skycraft.offer." + offer.id())));
        sync(player, claim);
    }

    /** Display stack of a material recipe's input (for the screen). */
    public static ItemStack icon(Supplier<? extends ItemLike> item) {
        return new ItemStack(item.get());
    }
}
