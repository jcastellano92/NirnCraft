package com.skycraft.mounts;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.fauna.WildHorseTaming;
import com.skycraft.quest.story.Story;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.AnimalTameEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus glue of the mount system: stamina and gallop each tick, stale mount copies, tack applied when a mount
 * loads, no vanilla horse screen, vanilla horse taming joining the stable, the legendary steeds' quest flags,
 * stablemasters, and the {@code /skycraft-mounts} command.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class MountEvents {
    /** Story flags that bind a legendary steed (set by a quest's {@code flag:} action). */
    private static final String[][] LEGEND_FLAGS = {{"mount_shadowmere", "shadowmere"}, {"mount_arvak", "arvak"}};

    private MountEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        MountStamina.tick(player); // takes and gives back the stamina bar (cheap when not riding)
        if (player.tickCount % 40 == 7) legends(player);
        if (player.tickCount % 100 == 13) StableMasters.tick(player);
        if (player.tickCount % 200 == 31) MountGrowth.care(player);
        if (player.tickCount % 60 == 41) MountInspect.spot(player); // Wild Eye: wild mounts on the compass
    }

    private static void legends(ServerPlayer player) {
        var d = SkyData.get(player);
        for (String[] f : LEGEND_FLAGS) {
            if (!Story.hasFlag(d, f[0])) continue;
            MountKind kind = MountKind.byId(f[1]);
            if (kind == null || Mounts.owns(player, kind)) continue;
            Mounts.grant(player, kind, null);
            Notifier.title(player, kind.displayName(), Component.translatable("mounts.skycraft.legend_bound"));
        }
    }

    /** An owner's old copy of a mount (left in an unloaded chunk) doesn't come back; a loading mount gets its tack applied. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !event.loadedFromDisk()) return;
        Entity e = event.getEntity();
        if (Mounts.isStale(e)) {
            event.setCanceled(true);
            return;
        }
        // a breaking that was cut short (a crash, a chunk unloading) must not leave the animal without its mind
        if (e instanceof Mob m && m.getPersistentData().getBoolean(WildHorseTaming.NOAI_TAG)) WildHorseTaming.release(m);
        if (e instanceof Mob mob && Mounts.kindOf(mob) != null) {
            MountGrowth.syncFromRecord(mob); // the record is the truth (training happens while it is away)
            MountGear.refresh(mob);
        }
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isDismounting() || event.getEntityBeingMounted() == null || event.getLevel().isClientSide()) return;
        MountStamina.dismounted(event.getEntityBeingMounted());
        // the bar goes back to the rider's own the moment they leave the saddle
        if (event.getEntityMounting() instanceof ServerPlayer player) MountStamina.leave(player);
    }

    /** A death forfeits the parked bar: the respawn already refilled stamina, and the mount keeps its own value. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClone(PlayerEvent.Clone event) {
        if (event.isWasDeath()) MountStamina.discard(event.getEntity());
    }

    /** There is no horse screen: whatever opens one (sneak-use, the inventory key, another mod) is closed again. */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getContainer() instanceof HorseInventoryMenu) {
            player.closeContainer();
        }
    }

    /** A horse tamed the vanilla way (riding it until it calms) joins the stable too. */
    @SubscribeEvent
    public static void onTame(AnimalTameEvent event) {
        if (!(event.getTamer() instanceof ServerPlayer player) || !(event.getAnimal() instanceof AbstractHorse horse)) return;
        MountKind kind = MountTaming.wildKind(horse);
        if (kind != null) Mounts.adopt(player, horse, kind);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MountStamina.leave(player);
            MountStamina.forget(player);
            MountInspect.forget(player);
            WildHorseTaming.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        MountCommands.register(event.getDispatcher());
    }
}
