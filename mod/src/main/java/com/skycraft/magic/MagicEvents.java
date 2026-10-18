package com.skycraft.magic;

import com.skycraft.Skycraft;
import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.magic.bound.BoundWeapons;
import com.skycraft.magic.shout.DragonSouls;
import com.skycraft.magic.shout.Shouting;
import com.skycraft.magic.spell.Candlelight;
import com.skycraft.magic.spell.Element;
import com.skycraft.magic.spell.Illusion;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.SpellEffects;
import com.skycraft.magic.spell.SpellMath;
import com.skycraft.magic.spell.Summons;
import com.skycraft.perk.Perks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Forge-bus server logic of the magic module: ticking, damage rules, summons, souls, bound weapons, cleanup. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class MagicEvents {
    private static final int DAY = 24000;
    /** Set while a reflected spell is being applied, so two reflectors can't bounce a spell forever. */
    private static final ThreadLocal<Boolean> REFLECTING = ThreadLocal.withInitial(() -> false);

    private MagicEvents() {}

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer p)) return;
        SpellCasting.tick(p);
        Shouting.tick(p);
        com.skycraft.magic.spell.Mysticism.tick(p);
        if (p.tickCount % 2 == 0) Candlelight.tick(p);
        if (p.tickCount % 20 == 0) com.skycraft.magic.spell.AlterationSpells.tickMagelight(p);
        // Bound weapons can't be stored in containers or bundles (the open container reports changes, see onOpen).
        if (BOUND_IN_CONTAINER.remove(p.getUUID())) BoundWeapons.purge(p.containerMenu);
        if (p.tickCount % 20 == 0) BoundWeapons.purgeInventoryBundles(p);
        // one hand system: spells, bound weapons and held items never coexist in a hand; the HUD countdowns
        Hands.tick(p);
        ActiveMagic.tick(p);
    }

    /** Players whose open container just received a bound weapon (or a bundle with one): purged at the end of their tick. */
    private static final Set<UUID> BOUND_IN_CONTAINER = new HashSet<>();

    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        AbstractContainerMenu menu = event.getContainer();
        UUID id = p.getUUID();
        menu.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu changed, int slot, ItemStack stack) {
                if (stack.isEmpty() || !BoundWeapons.holdsBound(stack)) return;
                if (BoundWeapons.isBound(stack) && slot >= 0 && slot < changed.slots.size()
                        && changed.getSlot(slot).container instanceof Inventory) return; // still in the caster's own inventory
                BOUND_IN_CONTAINER.add(id);
            }

            @Override
            public void dataChanged(AbstractContainerMenu changed, int index, int value) {
            }
        });
    }

    @SubscribeEvent
    public static void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        BOUND_IN_CONTAINER.remove(p.getUUID());
        BoundWeapons.purge(event.getContainer());
    }

    /** Summons never grief: no fire from a Flame Atronach's fireballs, no doors broken by a reanimated zombie. */
    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity() != null && Summons.isSummon(event.getEntity())) event.setCanGrief(false);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.loadedFromDisk()) Summons.upgradeLegacy(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            MagicScheduler.tick(server);
            com.skycraft.magic.shout.SlowTime.tick(server);
            com.skycraft.magic.spell.Mysticism.serverTick(server);
        }
    }

    @SubscribeEvent
    public static void onLivingTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof LivingEntity e)) return;
        if (e.level().isClientSide()) return;
        // Slow Time: creatures in the field skip most of their ticks.
        if (com.skycraft.magic.shout.SlowTime.skipsTick(e)) {
            event.setCanceled(true);
            return;
        }
        if (e.tickCount % 10 == 0 && e.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (e.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.ASH_SHELL.get()))) {
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.ASH, e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ(), 8,
                        e.getBbWidth() / 2, e.getBbHeight() / 2, e.getBbWidth() / 2, 0.0);
            } else if (e.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.ICE_FORM.get()))) {
                e.setTicksFrozen(Math.max(e.getTicksFrozen(), e.getTicksRequiredToFreeze() + 10));
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SNOWFLAKE, e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ(), 4,
                        e.getBbWidth() / 2, e.getBbHeight() / 2, e.getBbWidth() / 2, 0.0);
            }
        }
        if (!(e instanceof Mob mob)) return;
        if (mob.tickCount % 10 == 0 && (Summons.isSummon(mob) || Summons.isAlly(mob))) {
            Summons.tick(mob);
            if (!mob.isAlive()) return;
        }
        if (mob.tickCount % 5 == 0 && (mob.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.CALM.get())) || mob.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.FEAR.get()))
                || mob.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.FRENZY.get())))) {
            Illusion.tickMob(mob);
        }
    }

    // ------------------------------------------------------------------ targeting

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity entity = event.getEntity();
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target == null || entity.level().isClientSide()) return;
        if (entity.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.CALM.get())) || entity.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.FEAR.get()))) {
            event.setCanceled(true);
            return;
        }
        UUID owner = Summons.ownerOf(entity);
        if (owner != null && entity instanceof Mob mob) {
            ServerPlayer op = entity.level().getServer() == null ? null : entity.level().getServer().getPlayerList().getPlayer(owner);
            if (!Summons.mayTarget(mob, owner, target, op)) event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ damage

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        DamageSource source = event.getSource();
        Entity attacker = source.getEntity();
        // Become Ethereal: you can't be harmed, and you can't harm.
        if (victim.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.ETHEREAL.get())) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
            return;
        }
        // Ash Shell: encased in ash, nothing gets through.
        if (victim.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.ASH_SHELL.get())) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
            return;
        }
        if (attacker instanceof LivingEntity living && living != victim && living.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.ETHEREAL.get()))) {
            event.setCanceled(true);
            return;
        }
        // Telekinesis: intercept incoming arrows in mid-air while channeling Telekinesis.
        if (victim instanceof ServerPlayer sp && source.getDirectEntity() instanceof AbstractArrow arrow
                && SpellCasting.isCasting(sp, "telekinesis")
                && com.skycraft.magic.spell.Mysticism.catchArrow(sp, arrow)) {
            event.setCanceled(true);
            return;
        }
        // Summons never hurt their owner, the owner's other allies or party members.
        if (attacker != null) {
            UUID owner = Summons.ownerOf(attacker);
            if (owner != null) {
                ServerPlayer op = victim.level().getServer() == null ? null : victim.level().getServer().getPlayerList().getPlayer(owner);
                if (victim.getUUID().equals(owner) || owner.equals(Summons.ownerOf(victim)) || op != null && Targeting.isFriendly(op, victim)) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        DamageSource source = event.getSource();
        float amount = event.getAmount();

        // Reflect Spell / Spell Absorption (Mysticism): a chance to send the spell back or drink it as magicka.
        if (amount > 0 && !REFLECTING.get() && MagicDamage.isWardable(source)) {
            MobEffectInstance reflect = victim.getEffect(com.skycraft.platform.Effects.holder(MagicRegistry.REFLECT_SPELL.get()));
            Entity caster = source.getEntity();
            if (reflect != null && caster instanceof LivingEntity back && back != victim && back.isAlive()
                    && victim.getRandom().nextInt(100) < com.skycraft.magic.spell.Mysticism.chanceOf(reflect)) {
                REFLECTING.set(true);
                try {
                    back.setInvulnerableTime(0);
                    com.skycraft.platform.Effects.hurt(back, MagicDamage.source(victim.level(), MagicDamage.SPELL, null, victim), amount);
                } finally {
                    REFLECTING.set(false);
                }
                MagicFx.send(victim, MagicFx.WARD, Element.ARCANE, victim.getEyePosition(), victim.getViewVector(1f), victim.getId(), 1);
                com.skycraft.magic.spell.SpellSounds.world((net.minecraft.server.level.ServerLevel) victim.level(), victim.position(),
                        com.skycraft.magic.spell.SpellSounds.Slot.REFLECT, 1f, 1.3f);
                if (victim instanceof ServerPlayer rp) Notifier.message(rp, Component.translatable("message.skycraft.spell_reflected"));
                event.setCanceled(true);
                return;
            }
            MobEffectInstance absorb = victim.getEffect(com.skycraft.platform.Effects.holder(MagicRegistry.SPELL_ABSORPTION.get()));
            if (absorb != null && victim instanceof ServerPlayer ap
                    && (MagicDamage.isOurSpell(source) || source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)
                    || Perks.has(ap, "mysticism.spellbinder"))
                    && ap.getRandom().nextInt(100) < com.skycraft.magic.spell.Mysticism.chanceOf(absorb)) {
                PlayerData data = SkyData.get(ap);
                data.setMagicka(Math.min(data.maxMagicka(), data.getMagicka() + amount * CombatHandler.SKYRIM_SCALE));
                MagicFx.send(ap, MagicFx.AURA, Element.ARCANE, ap.position(), ap.position(), ap.getId(), 1);
                com.skycraft.magic.spell.SpellSounds.play(ap, com.skycraft.magic.spell.SpellSounds.Slot.ABSORB, 1f, 1.5f);
                event.setCanceled(true);
                return;
            }
        }
        // Dragonhide: 80% of physical damage is ignored. Dragon Aspect: a quarter of all damage.
        if (amount > 0 && victim.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.DRAGONHIDE.get())) && !MagicDamage.isWardable(source)
                && !source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            amount *= 0.2f;
        }
        if (amount > 0 && victim.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.DRAGON_ASPECT.get()))) amount *= 0.75f;

        if (victim instanceof ServerPlayer p) {
            amount = SpellCasting.absorbWithWard(p, source, amount);
            boolean spell = MagicDamage.isOurSpell(source) || source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC);
            if (spell && MagicDamage.isOurSpell(source)) {
                // Core applies these for vanilla magic; we apply them to spell damage types.
                amount *= 1f - 0.1f * Perks.rank(p, "alteration.magic_resistance");
                Race race = SkyData.get(p).getRace();
                if (race != null) amount *= 1f - race.resistance("magic");
            }
            if (spell && amount > 0 && Perks.has(p, "alteration.atronach")) {
                float absorbed = amount * 0.3f;
                amount -= absorbed;
                PlayerData data = SkyData.get(p);
                data.setMagicka(data.getMagicka() + absorbed * CombatHandler.SKYRIM_SCALE);
            }
            if (amount <= 0) {
                event.setCanceled(true);
                return;
            }
        }

        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer p && attacker != victim) {
            // Illusion spells break when you attack.
            if (p.getPersistentData().getBooleanOr(Illusion.INVIS_FLAG, false)) {
                p.removeEffect(com.skycraft.platform.Effects.holder(MobEffects.INVISIBILITY));
                p.getPersistentData().remove(Illusion.INVIS_FLAG);
            }
            victim.removeEffect(com.skycraft.platform.Effects.holder(MagicRegistry.CALM.get()));

            boolean boundHit = source.getDirectEntity() == p && BoundWeapons.isBound(p.getMainHandItem())
                    || source.getDirectEntity() instanceof AbstractArrow arrow && arrow.getPersistentData().getBooleanOr(BoundWeapons.ARROW_TAG, false);
            if (boundHit) {
                if (Perks.has(p, "conjuration.mystic_binding")) amount *= 1.4f;
                if (Perks.has(p, "conjuration.soul_stealer")) SpellEffects.soulTrap(p, victim, 100);
                if (Perks.has(p, "conjuration.oblivion_binding") && victim instanceof Mob mob && Summons.isSummon(mob)
                        && !p.getUUID().equals(Summons.ownerOf(mob))) {
                    Summons.dismiss(mob);
                    Notifier.message(p, Component.translatable("message.skycraft.banished"));
                    event.setCanceled(true);
                    return;
                }
            }
        }

        // Atronach summons hit with their element.
        if (attacker instanceof Mob summon && Summons.isSummon(summon)) {
            String kind = summon.getPersistentData().getStringOr(Summons.KIND, "");
            switch (kind) {
                case "flaming_familiar" -> victim.igniteForSeconds(4);
                case "frost_atronach" -> {
                    victim.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 60, 1));
                    victim.setTicksFrozen(Math.min(victim.getTicksRequiredToFreeze() - 2, victim.getTicksFrozen() + 40));
                }
                case "storm_atronach" -> {
                    MagicFx.send(victim, MagicFx.ARC, Element.SHOCK, summon.position().add(0, summon.getBbHeight() * 0.7, 0),
                            victim.position().add(0, victim.getBbHeight() * 0.5, 0), -1, 0);
                    if (victim instanceof ServerPlayer tp) {
                        PlayerData data = SkyData.get(tp);
                        data.setMagicka(data.getMagicka() - amount * CombatHandler.SKYRIM_SCALE * 0.5f);
                    }
                }
                default -> {
                }
            }
        }
        event.setAmount(amount);
    }

    /** Restoration "Avoid Death": once a day, heal when about to fall below 10% health. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Perks.has(p, "restoration.avoid_death")) return;
        float after = p.getHealth() - event.getNewDamage();
        if (after > p.getMaxHealth() * 0.1f) return;
        CompoundTag tag = MagicData.tag(p);
        long now = p.level().getGameTime();
        if (tag.getLongOr("avoid_death", 0L) > now) return;
        tag.putLong("avoid_death", now + DAY);
        SkyData.get(p).markDirty();
        p.heal(50f * SpellMath.healMult(p));
        MagicFx.send(p, MagicFx.TOTEM, Element.HOLY, p.position(), p.position(), p.getId(), 0);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.7f, 1.3f);
        Notifier.message(p, Component.translatable("message.skycraft.avoid_death"));
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof Player p && p.getPersistentData().getLongOr(Shouting.NO_FALL, 0L) > p.level().getGameTime()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent event) {
        if (event.getEntity().hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.MUFFLE.get()))) {
            event.modifyVisibility(event.getEntity().isCrouching() ? 0.5 : 0.7);
        }
    }

    // ------------------------------------------------------------------ death & drops

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide()) return;

        // A dragon you called (Call Dragon, Bend Will) gives no soul.
        if (dead.getType().builtInRegistryHolder().is(MagicRegistry.DRAGONS) && !Summons.isCompanion(dead)) DragonSouls.onDragonDeath(dead);
        com.skycraft.magic.shout.ShoutEffects.onDeath(dead);

        if (dead.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.SOUL_TRAP.get())) && dead.getPersistentData().read(SpellEffects.SOUL_TRAPPER, net.minecraft.core.UUIDUtil.CODEC).isPresent() && dead.level().getServer() != null) {
            ServerPlayer trapper = dead.level().getServer().getPlayerList().getPlayer(dead.getPersistentData().read(SpellEffects.SOUL_TRAPPER, net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            if (trapper != null && trapper.level() == dead.level() && trapper.distanceToSqr(dead) < 128 * 128) {
                boolean captured = com.skycraft.crafting.SoulGems.tryCaptureSoul(trapper, dead);
                if (captured) {
                    var c = dead.position().add(0, dead.getBbHeight() / 2, 0);
                    MagicFx.sendNear(trapper.level(), c, 64, MagicFx.STREAM, Element.SOUL, c, c, trapper.getId(), 30);
                    dead.level().playSound(null, c.x, c.y, c.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.5f, 1f);
                    com.skycraft.magic.MagicFx.soundToPlayer(trapper, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.6f);
                    Notifier.message(trapper, Component.translatable("message.skycraft.soul_captured"));
                }
            }
        }

        if (dead instanceof ServerPlayer p) {
            Candlelight.end(p);
            SpellCasting.stop(p);
            com.skycraft.magic.spell.Mysticism.forget(p);
            Summons.summonsOf(p).forEach(Summons::dismiss);
        }
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity e = event.getEntity();
        if (Summons.isSummon(e)) {
            event.setCanceled(true);
        } else if (e instanceof Player) {
            event.getDrops().removeIf(item -> BoundWeapons.isBound(item.getItem()));
        }
    }

    @SubscribeEvent
    public static void onExperience(LivingExperienceDropEvent event) {
        if (Summons.isSummon(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        if (BoundWeapons.isBound(event.getEntity().getItem())) {
            Player p = event.getPlayer();
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.7f, 1.6f);
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Candlelight.remove(p);
            com.skycraft.magic.spell.AlterationSpells.removeMagelight(p);
            com.skycraft.magic.spell.Mysticism.onLogin(p);
            // a bound weapon saved by a crash or an autosave never comes back
            BoundWeapons.purgeAll(p);
            Hands.forget(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer p) Hands.onPickup(p);
    }

    /** Death: bound weapons are never kept (keepInventory copies the inventory over, see also DeathHandler). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) BoundWeapons.purgeAll(p);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            BoundWeapons.purgeAll(p);
            Hands.forget(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        // fired before the player's data is written, so the bound weapon never reaches the save file
        BoundWeapons.purgeAll(p);
        Hands.forget(p.getUUID());
        ActiveMagic.forget(p.getUUID());
        Candlelight.remove(p);
        com.skycraft.magic.spell.AlterationSpells.removeMagelight(p);
        com.skycraft.magic.spell.Mysticism.forget(p);
        com.skycraft.magic.spell.DestructionSpells.forget(p.getUUID());
        Illusion.forget(p.getUUID());
        SpellCasting.forget(p.getUUID());
        Shouting.forget(p.getUUID());
        BOUND_IN_CONTAINER.remove(p.getUUID());
        Summons.summonsOf(p).forEach(Summons::dismiss);
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Candlelight.remove(p);
            SpellCasting.stop(p);
            // a conjured weapon does not follow its caster through a portal
            BoundWeapons.purgeAll(p);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            Candlelight.remove(p);
            com.skycraft.magic.spell.AlterationSpells.removeMagelight(p);
            BoundWeapons.purgeAll(p); // the players are saved after this event
        }
        Hands.clear();
        ActiveMagic.clear();
        com.skycraft.magic.spell.Mysticism.clear();
        com.skycraft.magic.shout.SlowTime.clear();
        MagicScheduler.clear();
        SpellCasting.clear();
        BOUND_IN_CONTAINER.clear();
    }
}
