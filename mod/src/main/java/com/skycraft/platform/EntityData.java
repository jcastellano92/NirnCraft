package com.skycraft.platform;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Registry-aware native entity persistence for explicit snapshots, never a replacement live entity model. */
public final class EntityData {
    private EntityData() {}

    public static CompoundTag saveWithoutId(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }
    public static CompoundTag save(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        if (!entity.save(output)) throw new IllegalStateException("Entity refuses persistence: " + entity.getType());
        return output.buildResult();
    }
    public static void load(Entity entity, CompoundTag tag) {
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.registryAccess(), tag));
    }
    public static java.util.Optional<net.minecraft.world.entity.EntityType<?>> type(String id) {
        var key = net.minecraft.resources.Identifier.tryParse(id);
        return key == null ? java.util.Optional.empty() : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(key);
    }
    public static java.util.UUID ownerId(net.minecraft.world.entity.OwnableEntity entity) {
        var owner = entity.getOwnerReference();
        return owner == null ? null : owner.getUUID();
    }
    public static double arrowDamage(net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
        return saveWithoutId(arrow).getDoubleOr("damage", 2.0);
    }
}
