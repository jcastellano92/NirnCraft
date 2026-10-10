package com.skycraft.skills;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.perk.Perks;
import com.skycraft.vitals.Vitals;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Acrobatics (Oblivion/Morrowind): jumping and falling. The skill itself raises jumps a little and softens falls;
 * perks: Leaper (higher jumps), Featherfall (less fall damage), Nimble (longer running jumps), Double Jump, Tumble
 * (land a sneak-roll for less damage), Aerial Strike (hit harder in mid-air) and Cat Fall (no damage from short falls).
 *
 * <p>XP: a moving jump (not hopping in place, at most twice a second) and surviving long falls. Jump height is applied
 * where the jump happens (the client simulates its own player) and on the server alike.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Acrobatics {
    /** Last game time each player earned jump XP. */
    private static final Map<UUID, Long> LAST_JUMP_XP = new HashMap<>();
    /** Players who used their double jump this airtime. */
    private static final Map<UUID, Boolean> DOUBLE_USED = new HashMap<>();

    private Acrobatics() {}

    /** Jump velocity multiplier: +0.15% per skill level (+15% at 100) and +6% per Leaper rank. */
    public static float jumpMultiplier(PlayerData data) {
        return 1f + data.getSkill(Skill.ACROBATICS) * 0.0015f + 0.06f * Perks.rank(data, "acrobatics.leaper");
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof Player p) || p.isSpectator()) return;
        PlayerData data = SkyData.get(p);
        float mult = jumpMultiplier(data);
        Vec3 v = p.getDeltaMovement();
        double vx = v.x, vz = v.z;
        if (p.isSprinting() && Perks.has(data, "acrobatics.nimble")) {
            vx *= 1.15;
            vz *= 1.15;
        }
        p.setDeltaMovement(vx, v.y * mult, vz);
        if (p instanceof ServerPlayer sp && !sp.isCreative()) {
            DOUBLE_USED.remove(sp.getUUID());
            double horizontal = v.x * v.x + v.z * v.z;
            long now = sp.level().getGameTime();
            Long last = LAST_JUMP_XP.get(sp.getUUID());
            // hopping in place or bunny-hopping every tick trains nothing
            if (horizontal > 0.01 && (last == null || now - last >= 10)) {
                LAST_JUMP_XP.put(sp.getUUID(), now);
                Progression.addSkillXp(sp, Skill.ACROBATICS, 0.6f);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player p) || event.isCanceled()) return;
        PlayerData data = SkyData.get(p);
        float raw = event.getDistance();
        float distance = raw;
        if (Perks.has(data, "acrobatics.cat_fall")) distance -= 10;
        if (Perks.has(data, "acrobatics.tumble") && p.isShiftKeyDown()) distance -= 3;
        event.setDistance(Math.max(0, distance));
        float mult = 1f - Math.min(0.3f, data.getSkill(Skill.ACROBATICS) * 0.003f) - 0.25f * Perks.rank(data, "acrobatics.featherfall");
        if (Perks.has(data, "acrobatics.tumble") && p.isShiftKeyDown()) mult *= 0.5f;
        event.setDamageMultiplier(event.getDamageMultiplier() * Math.max(0.05f, mult));
        if (p instanceof ServerPlayer sp) {
            DOUBLE_USED.remove(sp.getUUID());
            // A long fall survived trains the skill (the raw fall, so perks don't reduce what you learn).
            if (raw >= 4 && !sp.isCreative()) Progression.addSkillXp(sp, Skill.ACROBATICS, Math.min(20f, raw) * 0.5f);
            if (Perks.has(data, "acrobatics.tumble") && p.isShiftKeyDown() && raw > 3) {
                sp.level().playSound(null, sp.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.8f, 0.8f);
            }
        }
    }

    /** Aerial Strike: +25% damage while airborne and falling. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer p) || event.getSource().getDirectEntity() != p) return;
        if (!p.onGround() && p.fallDistance > 0.5f && !p.isPassenger() && Perks.has(p, "acrobatics.aerial_strike")) {
            event.setAmount(event.getAmount() * 1.25f);
            Progression.addSkillXp(p, Skill.ACROBATICS, 1f);
        }
    }

    public static void forget(UUID id) {
        LAST_JUMP_XP.remove(id);
        DOUBLE_USED.remove(id);
    }

    // ------------------------------------------------------------------ double jump

    /** Client -> server: the local player double-jumped in mid-air (its client already applied the motion). */
    public record DoubleJump() {
        public static void encode(DoubleJump m, FriendlyByteBuf buf) {
        }

        public static DoubleJump decode(FriendlyByteBuf buf) {
            return new DoubleJump();
        }

        public static void handle(DoubleJump m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) doubleJump(p);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Stamina for a double jump. */
    public static final float DOUBLE_JUMP_STAMINA = 15f;

    /** Validates a double jump: perk, airborne, once per airtime, stamina. Resets fall distance. */
    static void doubleJump(ServerPlayer p) {
        if (!Perks.has(p, "acrobatics.double_jump") || p.onGround() || p.isInWater() || p.isPassenger() || p.isFallFlying()) return;
        if (DOUBLE_USED.containsKey(p.getUUID())) return;
        if (!p.isCreative() && !Vitals.consumeStamina(p, DOUBLE_JUMP_STAMINA, false)) return;
        DOUBLE_USED.put(p.getUUID(), true);
        p.fallDistance = 0;
        p.level().playSound(null, p.blockPosition(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.5f, 1.6f);
        p.serverLevel().sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 6, 0.3, 0.05, 0.3, 0.02);
        Progression.addSkillXp(p, Skill.ACROBATICS, 0.8f);
    }
}
