package com.skycraft.arsenal;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.entity.SkyArrow;
import com.skycraft.arsenal.entity.StaffBolt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/** Arsenal entity types: the Skyrim arrow/bolt and the staff/energy-blade projectile. */
public final class ArsenalEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Skycraft.MODID);

    public static final Supplier<EntityType<SkyArrow>> SKY_ARROW = ENTITIES.register("sky_arrow",
            () -> EntityType.Builder.<SkyArrow>of(SkyArrow::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f).clientTrackingRange(4).updateInterval(20).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse("skycraft:sky_arrow"))));

    public static final Supplier<EntityType<StaffBolt>> STAFF_BOLT = ENTITIES.register("staff_bolt",
            () -> EntityType.Builder.<StaffBolt>of(StaffBolt::new, MobCategory.MISC)
                    .sized(0.4f, 0.4f).clientTrackingRange(6).updateInterval(5).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse("skycraft:staff_bolt"))));

    private ArsenalEntities() {}
}
