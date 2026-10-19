package com.nirncraft.inventory;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/** Item custom-data transactions and registry-aware storage, without retaining a world's registries. */
public final class ItemData {
    /** Last shipped legacy pack was Minecraft 1.20.1 (data version 3465). */
    private static final int LEGACY_DATA_VERSION = 3465;
    private static volatile Supplier<HolderLookup.Provider> clientRegistries = () -> null;

    private ItemData() {}

    /** Physical-client setup installs a supplier that resolves only the current client level on its own thread. */
    public static void clientRegistrySupplier(Supplier<HolderLookup.Provider> registries) {
        clientRegistries = Objects.requireNonNull(registries);
    }

    /** Returns an independent copy, or null when there is no custom-data component. Commit edits through update/set. */
    public static @Nullable CompoundTag custom(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    public static CompoundTag customOrEmpty(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static boolean hasCustom(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_DATA);
    }

    /** Read-only key lookup without copying unrelated custom payloads. Typed reads still use a detached copy. */
    public static boolean containsCustom(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(key);
    }

    /** Copies the component once, applies all edits, then commits a new immutable component. */
    public static void updateCustom(ItemStack stack, Consumer<CompoundTag> changes) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, changes);
    }

    public static void setCustom(ItemStack stack, @Nullable CompoundTag tag) {
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag == null ? new CompoundTag() : tag);
    }

    /** Explicit registries are required for generation and other background work. */
    public static CompoundTag save(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return new CompoundTag();
        ItemStack identity = stack.copyWithCount(1);
        var result = ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), identity).getOrThrow();
        if (!(result instanceof CompoundTag tag)) throw new IllegalStateException("Item codec did not produce a compound");
        tag.putInt("count", stack.getCount());
        return tag;
    }

    public static ItemStack parse(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.isEmpty()) return ItemStack.EMPTY;
        // Vanilla data fixing cannot discover stacks nested under custom mod-owned save keys.
        // Only recognized legacy stacks pay this cost; native component stacks retain the direct path.
        boolean legacy = !tag.contains("count") && tag.contains("Count");
        int count = legacy ? tag.getIntOr("Count", 0) : tag.getIntOr("count", 1);
        if (count <= 0) return ItemStack.EMPTY;
        CompoundTag identity = tag.copy();
        if (legacy) {
            int version = identity.getIntOr("DataVersion", LEGACY_DATA_VERSION);
            int current = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
            if (version < LEGACY_DATA_VERSION || version > current) {
                throw new IllegalArgumentException("Unsupported legacy item data version " + version);
            }
            identity.remove("DataVersion");
            identity.putByte("Count", (byte) 1);
            var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(
                    net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                    new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE, identity), version, current).getValue();
            if (!(fixed instanceof CompoundTag fixedTag)) throw new IllegalArgumentException("Legacy item migration did not produce a compound");
            identity = fixedTag;
        }
        identity.putInt("count", 1);
        ItemStack stack = ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), identity).getOrThrow();
        stack.setCount(count);
        return stack;
    }

    public static CompoundTag save(ItemStack stack) {
        return save(stack, currentRegistries());
    }

    public static ItemStack parse(CompoundTag tag) {
        return parse(tag, currentRegistries());
    }

    /** Only game-thread callers may infer a world; old asynchronous jobs cannot pick up the next world's registries. */
    public static HolderLookup.Provider currentRegistries() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread()) return server.registryAccess();
        HolderLookup.Provider client = clientRegistries.get();
        if (client != null) return client;
        throw new IllegalStateException("Item storage requires explicit world registries outside a live game thread");
    }
}
