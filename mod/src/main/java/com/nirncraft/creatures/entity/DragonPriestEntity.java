package com.nirncraft.creatures.entity;

import com.nirncraft.creatures.CreatureItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code nirncraft:dragon_priest}: a masked lich of the ancient Dragon Cult, floating above its tomb. Casts fire,
 * frost and lightning, shouts, blinks away and calls flame atronachs. The named priests (Morokei, Rahgot, Vokun,
 * Volsung, Nahkriin, Otar, Krosis, Hevnoraak and Konahrik: variants 1-9) are stronger and carry their masks, which
 * they drop when slain. Shows a boss bar.
 */
public class DragonPriestEntity extends SkyMonster {
    public static final String[] MASKS = {"", "morokei", "rahgot", "vokun", "volsung", "nahkriin", "otar", "krosis", "hevnoraak", "konahrik"};
    private final ServerBossEvent bossEvent = new ServerBossEvent(java.util.UUID.randomUUID(), Component.translatable("entity.nirncraft.dragon_priest"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);

    public DragonPriestEntity(EntityType<? extends DragonPriestEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public void setVariant(int variant) {
        super.setVariant(variant);
        this.bossEvent.setName(this.getDisplayName());
    }

    @Override
    @Nullable
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
                                                                   net.minecraft.world.DifficultyInstance difficulty,
                                                                   net.minecraft.world.entity.EntitySpawnReason reason,
                                                                   @Nullable net.minecraft.world.entity.SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        if (getVariant() > 0) {
            // the named priests: half again as strong as their nameless brethren (Konahrik twice)
            var hp = this.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(spec().health * (getVariant() == 9 ? 2.0 : 1.5));
            var dmg = this.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg != null) dmg.setBaseValue(spec().damage * 1.3);
            this.setHealth(this.getMaxHealth());
        }
        return data;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide()) this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        this.bossEvent.setName(this.getDisplayName());
    }

    /** Named priests drop their mask. */
    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel lootLevel263, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(lootLevel263, source, recentlyHit);
        int v = getVariant();
        if (v > 0 && v < MASKS.length) {
            CreatureItems.mask(MASKS[v]).ifPresent(item -> this.spawnAtLocation(lootLevel263, new ItemStack(item)));
        }
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263);
        
        this.bossEvent.setName(this.getDisplayName());
    }
}
