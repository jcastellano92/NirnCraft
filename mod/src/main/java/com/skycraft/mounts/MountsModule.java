package com.skycraft.mounts;

import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Mounts of Nirn: every province rides something of its own.
 *
 * <ul>
 *     <li>{@link MountKind}: horses of every province (vanilla horses with {@code fauna.HorseBreed}), Shadowmere and
 *     Arvak, camels, pack guar, and the ridden beasts ({@link MountBeast}, {@link MountEntities}: senche-tiger, giant
 *     wolf, indrik, woolly mammoth, Akaviri tiger, realm grazer).</li>
 *     <li>{@link Mounts}: ownership, the stable of owned mounts, calling with H, grants (quest hooks), barding.</li>
 *     <li>{@link MountStamina}: stamina and the gallop; {@link Saddlebags}; {@link MountedCombat}; {@link MountTaming}
 *     (wild taming, with the fauna module's bucking minigame).</li>
 *     <li>{@link MountItems}: bardings, marsh-shoes, Shadowmere's bridle, Arvak's Skull. {@link StableMasters}: who sells
 *     them (the dialogue is {@code fauna.Stables}).</li>
 *     <li>{@code mounts.client}: renderers and the mount HUD.</li>
 * </ul>
 * Initialised by the fauna module ({@code FaunaModule.init}).
 */
public final class MountsModule {
    private MountsModule() {}

    public static void init(IEventBus modBus) {
        MountEntities.init(modBus);
        MountItems.init(modBus);
    }

    public static void registerPackets() {
        MountPackets.register();
    }
}
