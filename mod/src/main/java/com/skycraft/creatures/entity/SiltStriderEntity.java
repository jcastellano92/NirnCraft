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
        this.setHomeTo(home, 8);
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
            return com.skycraft.platform.Interactions.sidedSuccess(this.level().isClientSide());
        }
        return super.mobInteract(player, hand);
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
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
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        if (home != null) tag.put("Home", com.skycraft.platform.NbtPositions.write(home));
        if (port != null) tag.putString("Port", port);
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "Home", "Port");
        
        if (tag.contains("Home")) setHome(com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Home")), tag.getStringOr("Port",""));
    }
}
