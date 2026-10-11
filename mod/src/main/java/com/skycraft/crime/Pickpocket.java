package com.skycraft.crime;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Pickpocketing: sneak + right-click a villager, wandering trader or other townsperson to see what's in their
 * pockets (generated from {@code skycraft:pickpocket/<villager|guard>} once per in-game day and kept on the entity).
 * Each item shows a success chance; the server rolls when the player tries to take it.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Pickpocket {
    public static final ResourceLocation VILLAGER_TABLE = new ResourceLocation(Skycraft.MODID, "pickpocket/villager");
    public static final ResourceLocation GUARD_TABLE = new ResourceLocation(Skycraft.MODID, "pickpocket/guard");
    private static final String POCKETS = "skycraft_pockets";
    private static final String POCKETS_DAY = "skycraft_pockets_day";
    private static final String STOLEN_TODAY = "skycraft_pockets_stolen";
    private static final String WARY_UNTIL = "skycraft_pockets_wary";
    private static final double REACH_SQR = 16;

    private Pickpocket() {}

    public static boolean canPickpocket(LivingEntity npc) {
        if (!npc.isAlive()) return false;
        if (npc instanceof AbstractVillager) return true;
        if (Crimes.isGuard(npc)) return true;
        return npc.getType().is(Dialogue.TALKERS) && !(npc instanceof Enemy) && !npc.getType().is(Dialogue.NO_DIALOGUE);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) return;
        Player p = event.getEntity();
        if (!p.isShiftKeyDown() || !(event.getTarget() instanceof LivingEntity npc) || !canPickpocket(npc)) return;
        if (p.isSpectator()) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (p instanceof ServerPlayer player) open(player, npc);
    }

    // ------------------------------------------------------------------ pockets

    private static List<ItemStack> pockets(ServerPlayer player, LivingEntity npc) {
        CompoundTag data = npc.getPersistentData();
        long day = npc.level().getDayTime() / 24000L;
        if (!data.contains(POCKETS, Tag.TAG_LIST) || data.getLong(POCKETS_DAY) != day) {
            ListTag list = new ListTag();
            for (ItemStack s : generate(player, npc)) {
                if (!s.isEmpty()) list.add(s.save(new CompoundTag()));
                if (list.size() >= 8) break;
            }
            data.put(POCKETS, list);
            data.putLong(POCKETS_DAY, day);
            data.putInt(STOLEN_TODAY, 0);
        }
        ListTag list = data.getList(POCKETS, Tag.TAG_COMPOUND);
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) out.add(ItemStack.of(list.getCompound(i)));
        return out;
    }

    private static void savePockets(LivingEntity npc, List<ItemStack> items) {
        ListTag list = new ListTag();
        for (ItemStack s : items) {
            if (!s.isEmpty()) list.add(s.save(new CompoundTag()));
        }
        npc.getPersistentData().put(POCKETS, list);
    }

    private static List<ItemStack> generate(ServerPlayer player, LivingEntity npc) {
        ServerLevel level = player.serverLevel();
        ResourceLocation id = Crimes.isGuard(npc) ? GUARD_TABLE : VILLAGER_TABLE;
        LootTable table = level.getServer().getLootData().getLootTable(id);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, npc.position())
                .withParameter(LootContextParams.THIS_ENTITY, npc)
                .create(LootContextParamSets.GIFT);
        ObjectArrayList<ItemStack> items = table.getRandomItems(params);
        List<ItemStack> result = new ArrayList<>(items);
        if ((level.dimension() == Jail.JAIL || Jail.isJailArea(level, npc.blockPosition())) && Crimes.isGuard(npc)) {
            result.add(0, new ItemStack(CrimeItems.JAIL_KEY.get()));
        }
        return result;
    }

    // ------------------------------------------------------------------ chances

    static boolean isGold(ItemStack stack) {
        return Currency.valueOf(stack) > 0;
    }

    /** Success chance (1..95) of stealing {@code stack} from {@code npc} right now. */
    static int chance(ServerPlayer player, LivingEntity npc, ItemStack stack) {
        boolean gold = isGold(stack);
        long value = gold ? Currency.valueOf(stack) : Theft.unitValue(stack) * stack.getCount();
        int skill = SkyData.get(player).getSkill(Skill.PICKPOCKET);
        double c = 30 + skill * 0.6 - value * 0.1;
        // Light Fingers: +20% per rank
        c *= 1 + 0.2 * Perks.rank(player, "pickpocket.light_fingers");
        if (behind(player, npc)) c += 25;
        if (npc.isSleeping()) c += Perks.has(player, "pickpocket.night_thief") ? 25 : 10;
        if (gold && Perks.has(player, "pickpocket.cutpurse")) c *= 1.5;
        if (stack.is(CrimeItems.LOCKPICK.get()) && Perks.has(player, "pickpocket.keymaster")) c = Math.max(c, 90);
        // every theft from the same person today makes them warier; Extra Pockets lets you take more
        int stolen = npc.getPersistentData().getInt(STOLEN_TODAY);
        c -= stolen * (Perks.has(player, "pickpocket.extra_pockets") ? 3 : 8);
        return (int) Math.max(1, Math.min(95, Math.round(c)));
    }

    /** Whether the player stands behind the NPC (outside its field of view). */
    static boolean behind(ServerPlayer player, LivingEntity npc) {
        Vec3 view = Vec3.directionFromRotation(0, npc.getYRot());
        Vec3 to = player.position().subtract(npc.position());
        Vec3 flat = new Vec3(to.x, 0, to.z);
        if (flat.lengthSqr() < 1e-6) return false;
        return view.dot(flat.normalize()) < -0.3;
    }

    // ------------------------------------------------------------------ server actions

    static void open(ServerPlayer player, LivingEntity npc) {
        long now = npc.level().getGameTime();
        if (npc.getPersistentData().getLong(WARY_UNTIL) > now) {
            Notifier.message(player, Component.translatable("crime.skycraft.pickpocket.wary", npc.getDisplayName()));
            return;
        }
        send(player, npc);
    }

    private static void send(ServerPlayer player, LivingEntity npc) {
        List<ItemStack> items = pockets(player, npc);
        List<CrimePackets.PocketEntry> entries = new ArrayList<>();
        for (ItemStack s : items) entries.add(new CrimePackets.PocketEntry(s, chance(player, npc, s)));
        SkyNetwork.sendToPlayer(player, new CrimePackets.OpenPickpocket(npc.getId(), npc.getDisplayName(), entries));
    }

    static void take(ServerPlayer player, int entityId, int slot) {
        if (!(player.level().getEntity(entityId) instanceof LivingEntity npc) || !canPickpocket(npc)
                || player.distanceToSqr(npc) > REACH_SQR) {
            SkyNetwork.sendToPlayer(player, new CrimePackets.ClosePickpocket());
            return;
        }
        // a victim who just caught someone keeps a hand on their purse (the screen may still be open client-side)
        if (npc.getPersistentData().getLong(WARY_UNTIL) > npc.level().getGameTime()) {
            SkyNetwork.sendToPlayer(player, new CrimePackets.ClosePickpocket());
            Notifier.message(player, Component.translatable("crime.skycraft.pickpocket.wary", npc.getDisplayName()));
            return;
        }
        List<ItemStack> items = pockets(player, npc);
        if (slot < 0 || slot >= items.size()) {
            send(player, npc);
            return;
        }
        ItemStack stack = items.get(slot);
        int chance = chance(player, npc, stack);
        boolean success = player.isCreative() || player.getRandom().nextInt(100) < chance;
        if (success) {
            items.remove(slot);
            savePockets(npc, items);
            CompoundTag pd = npc.getPersistentData();
            pd.putInt(STOLEN_TODAY, pd.getInt(STOLEN_TODAY) + 1);
            if (isGold(stack)) {
                Currency.give(player, Currency.valueOf(stack));
            } else {
                ItemStack loot = stack.copy();
                if (!player.isCreative() && !Jail.isJailDimension(player.level())) Bounty.markStolen(loot);
                Notifier.message(player, Component.translatable("crime.skycraft.pickpocket.stole", loot.getCount(), loot.getHoverName()));
                player.getInventory().placeItemBackInInventory(loot);
            }
            if (!player.isCreative()) {
                Bounty.increment(player, "pickpockets", 1);
                long value = isGold(stack) ? Currency.valueOf(stack) : Theft.unitValue(stack) * stack.getCount();
                Progression.addSkillXp(player, Skill.PICKPOCKET, Math.max(2f, Math.min(60f, value)));
            }
            send(player, npc);
        } else {
            SkyNetwork.sendToPlayer(player, new CrimePackets.ClosePickpocket());
            npc.getPersistentData().putLong(WARY_UNTIL, npc.level().getGameTime() + 2400);
            Notifier.send(player, NotifyKind.CRIME, Component.translatable("crime.skycraft.pickpocket.caught"), Component.empty());
            Notifier.message(player, Component.translatable("crime.skycraft.pickpocket.caught_line", npc.getDisplayName()));
            npc.playSound(SoundEvents.VILLAGER_NO, 1f, 1f);
            if (npc.isSleeping()) npc.stopSleeping();
            if (npc instanceof Mob mob) mob.getLookControl().setLookAt(player, 30f, 30f);
            // the victim always notices
            Crimes.report(player, npc.blockPosition(), Bounty.PICKPOCKET, true, null);
        }
    }
}
