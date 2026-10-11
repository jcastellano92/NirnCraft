package com.skycraft.creatures.entity;

import com.skycraft.creatures.travel.StriderPorts;
import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.CreatureAnims;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:silt_strider}: the giant insect caravans of Vvardenfell. Each waits at its port (Balmora, Vivec,
 * Ald'ruhn, Seyda Neen...); talk to it to ride to another port for a fare ({@link StriderPorts}). Peaceful, very
 * slow, never despawns; it calls out over the port now and then.
 */
public class SiltStriderEntity extends SkyBeast {
    @Nullable
    private BlockPos home;
    @Nullable
    private String port;

    public SiltStriderEntity(EntityType<? extends SiltStriderEntity> type, Level level) {
        super(type, level);
    }

    public void setHome(BlockPos pos, String portId) {
        this.home = pos.immutable();
        this.port = portId;
        this.restrictTo(home, 8);
        this.setPersistenceRequired();
    }

    @Nullable
    public String port() {
        return port;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            if (player instanceof ServerPlayer sp) StriderPorts.talk(sp, this);
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.tickCount % 600 == 300 && this.getRandom().nextInt(3) == 0) {
            CreatureAnims.play(this, AnimSlot.SPECIAL);
            this.playSound(com.skycraft.audio.AudioSounds.creature("silt_strider", "ambient"), 4.0f, 0.95f + this.getRandom().nextFloat() * 0.1f);
        }
        if (home != null && this.tickCount % 100 == 0 && this.blockPosition().distSqr(home) > 24 * 24) {
            this.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.8);
        }
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (home != null) tag.put("Home", NbtUtils.writeBlockPos(home));
        if (port != null) tag.putString("Port", port);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Home")) setHome(NbtUtils.readBlockPos(tag.getCompound("Home")), tag.getString("Port"));
    }
}
