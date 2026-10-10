package com.skycraft.lore;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicDamage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Set;
import java.util.UUID;

/**
 * The All-Maker stones' blessings, beside the birthsign:
 * <ul>
 *     <li>Beast: wild beasts leave you be unless you strike them first.</li>
 *     <li>Earth: a fifth less damage from blows and arrows, and harder to knock back.</li>
 *     <li>Sun: your blows and arrows sear the undead (a third more damage, and they burn).</li>
 *     <li>Tree: crops and saplings around you grow as you pass.</li>
 *     <li>Water: you breathe and swim freely under water.</li>
 *     <li>Wind: lighter on your feet: a little faster, and falls hurt less.</li>
 * </ul>
 * State: {@code data.module("lore")} key {@code allmaker} (stone id).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class AllMakerStones {
    public static final String KEY = "allmaker";
    private static final UUID EARTH_KNOCKBACK = UUID.fromString("8c3f2a71-5d4e-4b9a-9e21-7a0c1d2e3f11");
    private static final UUID WIND_SPEED = UUID.fromString("8c3f2a71-5d4e-4b9a-9e21-7a0c1d2e3f12");
    /** Beasts the Beast Stone calms (by entity id path), besides vanilla animals. */
    private static final Set<String> BEASTS = Set.of("wolf", "bear", "sabre_cat", "horker", "mudcrab", "skeever", "elk", "deer",
            "mammoth", "polar_bear", "fox", "frostbite_spider", "slaughterfish");

    private AllMakerStones() {}

    @Nullable
    public static AllMakerStone current(Player player) {
        return AllMakerStone.byId(SkyData.get(player).module(StandingStones.MODULE).getString(KEY));
    }

    public static void activate(ServerPlayer player, AllMakerStone stone, BlockPos base) {
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module(StandingStones.MODULE);
        AllMakerStone old = AllMakerStone.byId(tag.getString(KEY));
        ServerLevel level = player.serverLevel();
        if (old == stone) {
            Notifier.message(player, Component.translatable("message.skycraft.lore.stone_already", stone.stoneName()));
            return;
        }
        tag.putString(KEY, stone.id());
        data.addStat("allmaker_stones_activated", 1);
        data.markDirty();
        refresh(player);
        Vector3f color = new Vector3f((stone.color >> 16 & 255) / 255f, (stone.color >> 8 & 255) / 255f, (stone.color & 255) / 255f);
        DustParticleOptions dust = new DustParticleOptions(color, 1.6f);
        for (int i = 0; i < 48; i++) {
            level.sendParticles(dust, base.getX() + 0.5, base.getY() + 1.8 + i * 0.5, base.getZ() + 0.5, 2, 0.08, 0.15, 0.08, 0);
        }
        level.sendParticles(ParticleTypes.END_ROD, base.getX() + 0.5, base.getY() + 5, base.getZ() + 0.5, 40, 0.12, 4, 0.12, 0.01);
        level.playSound(null, base, com.skycraft.world.WorldSounds.MAGIC_STANDING_STONE.get(), SoundSource.BLOCKS, 1.6f, 0.8f);
        Notifier.title(player, stone.stoneName(), stone.description());
        if (old != null) Notifier.message(player, Component.translatable("message.skycraft.lore.stone_replaced", old.stoneName()));
    }

    /** Transient attribute modifiers for Earth and Wind (idempotent; once a second and on switching). */
    public static void refresh(ServerPlayer player) {
        AllMakerStone s = current(player);
        modifier(player, Attributes.KNOCKBACK_RESISTANCE, EARTH_KNOCKBACK, "Skycraft Earth Stone", s == AllMakerStone.EARTH ? 0.3 : 0.0,
                AttributeModifier.Operation.ADDITION);
        modifier(player, Attributes.MOVEMENT_SPEED, WIND_SPEED, "Skycraft Wind Stone", s == AllMakerStone.WIND ? 0.06 : 0.0,
                AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    private static void modifier(ServerPlayer player, net.minecraft.world.entity.ai.attributes.Attribute attribute, UUID id, String name,
                                 double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(id);
        if (existing != null && existing.getAmount() == amount) return;
        if (existing != null) inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, name, amount, op));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        long now = player.level().getGameTime();
        if (now % 20 != 11) return;
        refresh(player);
        AllMakerStone s = current(player);
        if (s == AllMakerStone.WATER && player.isInWater()) {
            player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 60, 0, true, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 60, 0, true, false, true));
        } else if (s == AllMakerStone.TREE && now % 100 == 11) {
            growAround(player);
        }
    }

    /** Tree Stone: a few growth ticks for plants near the player. */
    private static void growAround(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        for (int i = 0; i < 6; i++) {
            BlockPos p = center.offset(level.random.nextInt(11) - 5, level.random.nextInt(5) - 2, level.random.nextInt(11) - 5);
            BlockState state = level.getBlockState(p);
            Block b = state.getBlock();
            if ((b instanceof CropBlock || b instanceof net.minecraft.world.level.block.SaplingBlock)
                    && b instanceof BonemealableBlock bm && bm.isValidBonemealTarget(level, p, state, false)) {
                bm.performBonemeal(level, level.random, p, state);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, 3, 0.25, 0.2, 0.25, 0);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        Entity attacker = event.getSource().getEntity();
        // Sun: searing the undead
        if (attacker instanceof ServerPlayer player && current(player) == AllMakerStone.SUN
                && event.getEntity().getMobType() == MobType.UNDEAD && !MagicDamage.isWardable(event.getSource())) {
            event.setAmount(event.getAmount() * 1.33f);
            event.getEntity().setSecondsOnFire(3);
        }
        // Earth: hardened against blows and arrows
        if (event.getEntity() instanceof ServerPlayer player && current(player) == AllMakerStone.EARTH
                && !MagicDamage.isWardable(event.getSource()) && attacker != null) {
            event.setAmount(event.getAmount() * 0.8f);
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && current(player) == AllMakerStone.WIND) {
            event.setDistance(Math.max(0, event.getDistance() - 4));
            event.setDamageMultiplier(event.getDamageMultiplier() * 0.75f);
        }
    }

    /** Beast: beasts don't pick a fight with you (unless you've hurt them). */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (!(event.getNewTarget() instanceof ServerPlayer player) || current(player) != AllMakerStone.BEAST) return;
        LivingEntity mob = event.getEntity();
        if (mob.getLastHurtByMob() == player) return;
        if (isBeast(mob)) event.setCanceled(true);
    }

    private static boolean isBeast(LivingEntity e) {
        if (e instanceof Animal) return true;
        if (!(e instanceof Mob)) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return id != null && BEASTS.contains(id.getPath());
    }
}
