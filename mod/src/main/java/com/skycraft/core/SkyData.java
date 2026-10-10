package com.skycraft.core;

import com.skycraft.Skycraft;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Capability plumbing for {@link PlayerData}. Use {@link #get(Player)} everywhere. */
public final class SkyData {
    public static final Capability<PlayerData> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    public static final ResourceLocation KEY = new ResourceLocation(Skycraft.MODID, "player_data");

    private SkyData() {}

    /**
     * The player's data. If the capability is somehow missing (e.g. during early client init) a fresh, throwaway
     * instance is returned so callers never NPE (never a shared object: writes to it must not leak across players
     * or sides).
     */
    public static PlayerData get(Player player) {
        if (player == null) return new PlayerData();
        PlayerData data = player.getCapability(CAP).orElse(null);
        return data != null ? data : new PlayerData();
    }

    public static boolean has(Player player) {
        return player != null && player.getCapability(CAP).isPresent();
    }

    public static class Provider implements ICapabilitySerializable<CompoundTag> {
        private final PlayerData data = new PlayerData();
        private final LazyOptional<PlayerData> optional = LazyOptional.of(() -> data);

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            return CAP.orEmpty(cap, optional);
        }

        @Override
        public CompoundTag serializeNBT() {
            return data.save();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            data.load(nbt);
        }

        public void invalidate() {
            optional.invalidate();
        }
    }
}
