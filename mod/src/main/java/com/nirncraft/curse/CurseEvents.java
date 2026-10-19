package com.nirncraft.curse;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.entity.CorpseEntity;
import com.nirncraft.creatures.entity.VampireEntity;
import com.nirncraft.crime.Crimes;
import com.nirncraft.curse.entity.DeathHoundEntity;
import com.nirncraft.curse.entity.GargoyleEntity;
import com.nirncraft.curse.entity.VampireLordEntity;
import com.nirncraft.curse.entity.WerewolfEntity;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.registry.ModEffects;
import com.nirncraft.society.Followers;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Forge events of the curse module: the once-a-second curse tick, Sanguinare from bites, damage modifiers, feeding
 * (sleepers, corpses, bites), what a beast can't do (items, blocks, mining), the end of transformations on death and
 * logout, tracking sync, and who attacks whom (vampires leave vampires alone, everyone hunts the shunned).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class CurseEvents {
    private static final String NO_FALL = "nirncraft_curse_nofall";
    private static final Map<String, Long> THROTTLE = new HashMap<>();

    private CurseEvents() {}

    /** Rate limit for client-driven actions: true at most once per {@code ticks} per player and action. */
    static boolean throttle(ServerPlayer player, String action, int ticks) {
        String key = player.getUUID() + "|" + action;
        long now = player.level().getGameTime();
        Long last = THROTTLE.get(key);
        if (last != null && now - last < ticks && now >= last) return false;
        THROTTLE.put(key, now);
        return true;
    }

    /** No fall damage for the next {@code ticks} (reverting in mid-air, Bats). */
    static void noFall(ServerPlayer player, int ticks) {
        player.getPersistentData().putLong(NO_FALL, player.level().getGameTime() + ticks);
        player.fallDistance = 0;
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if ((player.tickCount + player.getId()) % 20 != 0) return;
        long now = player.level().getGameTime();
        Vampirism.tick(player, now);
        Lycanthropy.tickDisease(player, now);
        Form form = Curses.form(player);
        if (form == Form.BEAST) Lycanthropy.tick(player, now);
        else if (form == Form.LORD) VampireLord.tick(player, now);
        if (Curses.shunned(player) && !player.isCreative() && !player.isSpectator()) provoke(player);
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        if (event.getServer().overworld() == null) return;
        Howls.tick(event.getServer());
        VampireLord.tickBats(event.getServer().overworld().getGameTime());
        WildWerewolves.tick(event.getServer());
    }

    /** Townsfolk who see a beast (or a starved vampire) attack it, or run if they can't fight; guards always attack. */
    private static void provoke(ServerPlayer player) {
        boolean vampire = Curses.isVampire(player);
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(20), m -> m.isAlive() && !m.isSleeping()
                && m.getTarget() != player && (m instanceof NpcEntity || Crimes.isGuard(m)))) {
            if (Followers.isFollowerOf(mob, player) || !mob.hasLineOfSight(player)) continue;
            if (mob instanceof NpcEntity npc) {
                if (npc.isPrisoner() || vampire && npc.role() == NpcRole.VAMPIRE) continue;
                if (npc.canFight()) npc.setTarget(player);
                else npc.setFearedPlayer(player.getUUID());
            } else {
                mob.setTarget(player);
            }
        }
    }

    // ------------------------------------------------------------------ combat

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        Entity attacker = event.getSource().getEntity();
        if (victim instanceof ServerPlayer player) {
            float mult = Vampirism.damageTaken(player, event.getSource());
            if (mult != 1f) event.setAmount(event.getAmount() * mult);
            // Sanguinare Vampiris from the claws and bites of vampires and their hounds
            if (event.getAmount() > 0 && attacker instanceof LivingEntity carrier && event.getSource().getDirectEntity() == attacker) {
                if (carriesSanguinare(carrier) && player.getRandom().nextFloat() < Vampirism.INFECTION_CHANCE) {
                    Vampirism.infect(player);
                } else if (carriesSaniesLupinus(carrier) && player.getRandom().nextFloat() < Lycanthropy.INFECTION_CHANCE) {
                    Lycanthropy.infect(player);
                }
            }
        }
    }

    public static boolean carriesSanguinare(Entity e) {
        return e instanceof VampireEntity || e instanceof VampireLordEntity || e instanceof DeathHoundEntity
                || e instanceof NpcEntity npc && npc.role() == NpcRole.VAMPIRE;
    }

    public static boolean carriesSaniesLupinus(Entity e) {
        return e instanceof WerewolfEntity || (e instanceof Player p && Curses.form(p) == Form.BEAST);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            // transformations end with death: the body that respawns is the player's own
            if (Curses.form(player) == Form.BEAST) Lycanthropy.revert(player, false);
            else if (Curses.form(player) == Form.LORD) VampireLord.revert(player, false);
        }
        if (event.getSource().getEntity() instanceof ServerPlayer killer && killer != event.getEntity()) {
            CurseCombat.onKill(killer, event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (Curses.form(player) == Form.LORD
                || player.getPersistentData().getLongOr(NO_FALL, 0L) > player.level().getGameTime()) {
            event.setCanceled(true);
        }
    }

    /** Milk clears ordinary effects while Skyrim disease markers keep their authored cures. */
    @SubscribeEvent
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (event.getEffect().value() instanceof CurseRegistry.CurseEffect
                && com.nirncraft.platform.ItemUse.consuming(event.getEntity()).is(net.minecraft.world.item.Items.MILK_BUCKET)) {
            event.setCanceled(true);
        }
    }

    /** Werewolves can't rest well; vampires shrug off poison. */
    @SubscribeEvent
    public static void onEffectApplicable(MobEffectEvent.Applicable event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        MobEffect effect = event.getEffectInstance().getEffect().value();
        if (effect == ModEffects.WELL_RESTED.get() && Curses.isWerewolf(player)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            if (player instanceof ServerPlayer sp) com.nirncraft.core.Notifier.message(sp, net.minecraft.network.chat.Component.translatable("curse.nirncraft.beast.no_rest"));
        } else if (effect == MobEffects.POISON.value() && Curses.isVampire(player)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }

    /**
     * Vampires, their hounds and gargoyles leave a fellow vampire alone (unless it attacked them), wild werewolves
     * leave a werewolf in Beast Form alone, and a terrified creature attacks nobody.
     */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        if (Howls.feared(mob) && event.getNewAboutToBeSetTarget() != null) {
            event.setCanceled(true);
            return;
        }
        if (!(event.getNewAboutToBeSetTarget() instanceof Player player)) return;
        if (mob.getLastHurtByMob() == player) return;
        boolean kin = carriesSanguinare(mob) || mob instanceof GargoyleEntity;
        if (kin && Curses.isVampire(player)) event.setCanceled(true);
        else if (mob instanceof WerewolfEntity && Curses.form(player) == Form.BEAST) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ feeding and what beasts can't do

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        Entity target = event.getTarget();
        Form form = Curses.form(player);
        boolean feed = false;
        if (form == Form.BEAST) feed = target instanceof CorpseEntity;
        else if (form == Form.LORD) feed = target instanceof LivingEntity l && CurseCombat.isPerson(l);
        else if (!player.isShiftKeyDown() && Curses.isVampire(player) && target instanceof LivingEntity l && l.isSleeping() && CurseCombat.isPerson(l)) feed = true;
        if (!feed && form == Form.NONE) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        // the client asks for the feeding; the server validates it (CursePackets.Action)
        if (feed && player.level().isClientSide() && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            SkyNetwork.sendToServer(new CursePackets.Action(CursePackets.Action.FEED, target.getId()));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (Curses.transformed(event.getEntity())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!Curses.transformed(event.getEntity())) return;
        BlockState state = event.getLevel().getBlockState(event.getPos());
        // a beast still opens doors and gates
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock) {
            event.setUseItem(net.minecraft.util.TriState.FALSE);
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (Curses.transformed(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (event.getPlayer() != null && Curses.transformed(event.getPlayer())) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ lifecycle and sync

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // a transformation never survives a logout (or a crash)
        if (Curses.form(player) == Form.BEAST) Lycanthropy.revert(player, false);
        else if (Curses.form(player) == Form.LORD) VampireLord.revert(player, false);
        Vampirism.refreshBonuses(player);
        CursePackets.sync(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (Curses.form(player) == Form.BEAST) Lycanthropy.revert(player, false);
        else if (Curses.form(player) == Form.LORD) VampireLord.revert(player, false);
        Vampirism.forget(player.getUUID());
        Vampirism.refreshBonuses(player);
        THROTTLE.keySet().removeIf(k -> k.startsWith(player.getUUID().toString()));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Vampirism.refreshBonuses(player);
            CursePackets.sync(player);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) CursePackets.sync(player);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer viewer) {
            CursePackets.syncTo(viewer, target);
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CurseCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        THROTTLE.clear();
        Howls.clear();
        VampireLord.clearBats();
        WildWerewolves.clear();
    }

    static UUID id(Player p) {
        return p.getUUID();
    }
}
