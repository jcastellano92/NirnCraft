package com.skycraft.arsenal;

import com.skycraft.arsenal.entity.SkyArrow;
import com.skycraft.arsenal.item.ArrowKind;
import com.skycraft.arsenal.item.SkyArrowItem;
import net.minecraft.core.Position;
import net.minecraft.core.dispenser.AbstractProjectileDispenseBehavior;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Leveled loot, named artifacts, arrow types, crossbows, staves, arrow physics and kill cams.
 *
 * <ul>
 *     <li>{@link LeveledGear}: the {@code skycraft:leveled_gear} loot function (contract 20) and leveled rolls</li>
 *     <li>{@link ArsenalItems}: arrows, bolts, crossbows, staves, artifacts; {@link ArsenalEntities}: projectiles</li>
 *     <li>{@link ArsenalEvents}: corpse arrows (contract 27), headshots, armor piercing, leveled drops, kill-cam triggers</li>
 *     <li>{@link ArtifactEvents}: artifact powers; {@link StaffEffects}: staff spells</li>
 *     <li>{@code arsenal.client}: renderers, bow/crossbow properties, kill cams, staff visuals</li>
 * </ul>
 */
public final class ArsenalModule {
    private ArsenalModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ArsenalConfig.COMMON_SPEC, "skycraft-arsenal-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ArsenalConfig.CLIENT_SPEC, "skycraft-arsenal-client.toml");
        ArsenalBlocks.BLOCKS.register(modBus);
        ArsenalItems.ITEMS.register(modBus);
        ArsenalEntities.ENTITIES.register(modBus);
        LeveledGear.FUNCTIONS.register(modBus);
        modBus.addListener(ArsenalModule::commonSetup);
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        ArsenalPackets.register();
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Dispensers shoot Skyrim arrows and bolts like vanilla arrows.
            for (ArrowKind kind : ArrowKind.VALUES) {
                DispenserBlock.registerBehavior(ArsenalItems.arrow(kind), new AbstractProjectileDispenseBehavior() {
                    @Override
                    protected Projectile getProjectile(Level level, Position pos, ItemStack stack) {
                        SkyArrow arrow = new SkyArrow(level, pos.x(), pos.y(), pos.z(), stack);
                        arrow.pickup = AbstractArrow.Pickup.ALLOWED;
                        if (stack.getItem() instanceof SkyArrowItem item) {
                            arrow.setBaseDamage(item.kind.damage);
                            if (item.kind.element == ArrowKind.Element.FIRE) arrow.setSecondsOnFire(100);
                        }
                        return arrow;
                    }
                });
            }
        });
    }
}
