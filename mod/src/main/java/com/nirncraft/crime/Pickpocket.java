package com.nirncraft.crime;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Currency;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.network.NotifyKind;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.perk.Perks;
import com.nirncraft.skills.Progression;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Pickpocketing: sneak + interact (the Interact key) with a villager, wandering trader or other townsperson to see what's in their
 * pockets (generated from {@code nirncraft:pickpocket/<villager|guard>} and kept on the entity for several in-game days).
 * Each item shows a success chance; the server rolls when the player tries to take it.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Pickpocket {
    public static final Identifier VILLAGER_TABLE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "pickpocket/villager");
    public static final Identifier GUARD_TABLE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "pickpocket/guard");
    private static final String POCKETS = "nirncraft_pockets";
    private static final String POCKETS_DAY = "nirncraft_pockets_day";
    private static final String STOLEN_TODAY = "nirncraft_pockets_stolen";
    private static final String WARY_UNTIL = "nirncraft_pockets_wary";
    private static final double REACH_SQR = 16;
    /** In-game days before a person's emptied pockets hold something again. */
    private static final long REFILL_DAYS = 5;

    private Pickpocket() {}

    public static boolean canPickpocket(LivingEntity npc) {
        if (!npc.isAlive()) return false;
        if (npc instanceof AbstractVillager) return true;
        if (Crimes.isGuard(npc)) return true;
        return npc.getType().builtInRegistryHolder().is(Dialogue.TALKERS) && !(npc instanceof Enemy) && !npc.getType().builtInRegistryHolder().is(Dialogue.NO_DIALOGUE);
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

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) return;
        Player p = event.getEntity();
        if (!p.isShiftKeyDown() || p.isSpectator()) return;
        var level = event.getLevel();
        var pos = event.getPos();
        if (!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.BEDS)) return;
        var area = new net.minecraft.world.phys.AABB(pos).inflate(2.5, 1.5, 2.5);
        LivingEntity sleeper = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, l -> l.isSleeping() && canPickpocket(l))) {
            double d = e.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                sleeper = e;
            }
        }
        if (sleeper == null || p.distanceToSqr(sleeper) > REACH_SQR) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (p instanceof ServerPlayer player) open(player, sleeper);
    }

    // ------------------------------------------------------------------ pockets

    private static List<ItemStack> pockets(ServerPlayer player, LivingEntity npc) {
        CompoundTag data = npc.getPersistentData();
        long day = npc.level().getOverworldClockTime() / 24000L;
        long since = day - data.getLongOr(POCKETS_DAY, 0L);
        // pockets refill on a long cooldown per person, not every dawn: sleeping through days must not farm them
        // a jail guard's key is what the prison break needs: his ring is refilled every day
        boolean jailGuard = Crimes.isGuard(npc) && (npc.entityTags().contains(Jail.NPC_TAG) || Jail.isJailArea(npc.level(), npc.blockPosition()));
        if (!com.nirncraft.platform.NbtData.hasType(data, POCKETS, Tag.TAG_LIST) || since < 0 || since >= (jailGuard ? 1 : REFILL_DAYS)) {
            ListTag list = new ListTag();
            for (ItemStack s : generate(player, npc)) {
                if (!s.isEmpty()) list.add(com.nirncraft.inventory.ItemData.save(s));
                if (list.size() >= 8) break;
            }
            data.put(POCKETS, list);
            data.putLong(POCKETS_DAY, day);
            data.putInt(STOLEN_TODAY, 0);
        }
        ListTag list = data.getListOrEmpty(POCKETS);
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) out.add(com.nirncraft.inventory.ItemData.parse(list.getCompoundOrEmpty(i)));
        return out;
    }

    private static void savePockets(LivingEntity npc, List<ItemStack> items) {
        ListTag list = new ListTag();
        for (ItemStack s : items) {
            if (!s.isEmpty()) list.add(com.nirncraft.inventory.ItemData.save(s));
        }
        npc.getPersistentData().put(POCKETS, list);
    }

    private static List<ItemStack> generate(ServerPlayer player, LivingEntity npc) {
        ServerLevel level = player.level();
        Identifier id = Crimes.isGuard(npc) ? GUARD_TABLE : VILLAGER_TABLE;
        LootTable table = com.nirncraft.platform.LootTables.get(level, id);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, npc.position())
                .withParameter(LootContextParams.THIS_ENTITY, npc)
                .create(LootContextParamSets.GIFT);
        ObjectArrayList<ItemStack> items = table.getRandomItems(params);
        List<ItemStack> result = new ArrayList<>(items);
        if ((level.dimension() == Jail.JAIL || Jail.isJailArea(level, npc.blockPosition())) && Crimes.isGuard(npc)) {
            ItemStack key = new ItemStack(CrimeItems.JAIL_KEY.get());
            com.nirncraft.inventory.ItemData.updateCustom(key, customData263 -> customData263.putBoolean(CrimeItems.JAIL_PROPERTY, true));
            result.add(0, key);
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
        if (npc.isSleeping()) c += Perks.has(player, "pickpocket.night_thief") ? 35 : 20;
        if (gold && Perks.has(player, "pickpocket.cutpurse")) c *= 1.5;
        if (stack.is(CrimeItems.LOCKPICK.get()) && Perks.has(player, "pickpocket.keymaster")) c = Math.max(c, 90);
        // every theft from the same person today makes them warier; Extra Pockets lets you take more
        int stolen = npc.getPersistentData().getIntOr(STOLEN_TODAY, 0);
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
        if (npc.getPersistentData().getLongOr(WARY_UNTIL, 0L) > now) {
            Notifier.message(player, Component.translatable("crime.nirncraft.pickpocket.wary", npc.getDisplayName()));
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
        if (npc.getPersistentData().getLongOr(WARY_UNTIL, 0L) > npc.level().getGameTime()) {
            SkyNetwork.sendToPlayer(player, new CrimePackets.ClosePickpocket());
            Notifier.message(player, Component.translatable("crime.nirncraft.pickpocket.wary", npc.getDisplayName()));
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
            pd.putInt(STOLEN_TODAY, pd.getIntOr(STOLEN_TODAY, 0) + 1);
            if (isGold(stack)) {
                Currency.give(player, Currency.valueOf(stack));
            } else {
                ItemStack loot = stack.copy();
                // the jail's key is the jail's property, not stolen goods (it stays with the thief until they leave)
                if (!player.isCreative() && !Jail.isJailDimension(player.level()) && !loot.is(CrimeItems.JAIL_KEY.get())) Bounty.markStolen(loot);
                Notifier.message(player, Component.translatable("crime.nirncraft.pickpocket.stole", loot.getCount(), loot.getHoverName()));
                com.nirncraft.inventory.Pack.giveOrDrop(player, loot);
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
            Notifier.send(player, NotifyKind.CRIME, Component.translatable("crime.nirncraft.pickpocket.caught"), Component.empty());
            Notifier.message(player, Component.translatable("crime.nirncraft.pickpocket.caught_line", npc.getDisplayName()));
            npc.playSound(SoundEvents.VILLAGER_NO, 1f, 1f);
            boolean wasSleeping = npc.isSleeping();
            if (wasSleeping) npc.stopSleeping();
            if (npc instanceof Mob mob) mob.getLookControl().setLookAt(player, 30f, 30f);
            long itemVal = isGold(stack) ? Currency.valueOf(stack) : Theft.unitValue(stack) * stack.getCount();
            if (npc instanceof com.nirncraft.society.entity.NpcEntity societyNpc) {
                int disp = com.nirncraft.society.Relationships.disposition(societyNpc, player);
                com.nirncraft.society.NpcRole role = societyNpc.role();
                boolean aggressive = role.combatant || (societyNpc.canFight()
                        && role != com.nirncraft.society.NpcRole.JARL
                        && role != com.nirncraft.society.NpcRole.STEWARD
                        && role != com.nirncraft.society.NpcRole.SCHOLAR
                        && role != com.nirncraft.society.NpcRole.PRIEST
                        && (disp < 0 || itemVal >= 50
                        || role == com.nirncraft.society.NpcRole.BLACKSMITH
                        || role == com.nirncraft.society.NpcRole.MINER
                        || role == com.nirncraft.society.NpcRole.LUMBERJACK));
                if (aggressive) {
                    societyNpc.setTarget(player);
                    com.nirncraft.society.Barks.say(societyNpc, Component.literal(wasSleeping
                            ? "Cutpurse! You dare rob me in my sleep?! You'll pay with your blood!"
                            : "Keep your hands out of my pockets, cutpurse!"));
                } else {
                    societyNpc.setFearedPlayer(player.getUUID());
                    com.nirncraft.society.Barks.say(societyNpc, Component.literal(wasSleeping
                            ? "Thief! Somebody help! Guards!"
                            : "Help! Thief! Guards, seize them!"));
                }
            } else if (Crimes.isGuard(npc) && npc instanceof Mob guardMob) {
                guardMob.setTarget(player);
            }
            // the victim always notices
            Crimes.report(player, npc.blockPosition(), Bounty.PICKPOCKET, true, null);
        }
    }
}
