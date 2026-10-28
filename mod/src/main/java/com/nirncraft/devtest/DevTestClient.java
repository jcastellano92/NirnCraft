package com.nirncraft.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.nirncraft.NirnCraft;
import com.nirncraft.atlas.AtlasClient;
import com.nirncraft.core.Race;
import com.nirncraft.inventory.client.SkyUi;
import com.nirncraft.loot.container.ContainerKind;
import com.nirncraft.loot.container.ContainerRegistry;
import com.nirncraft.loot.container.ContainerStyle;
import com.nirncraft.render.quality.Knob;
import com.nirncraft.render.quality.QualityClient;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.NpcRole;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Client-side controller and HUD/keybinding manager for the NirnCraft Dev / Creative Testing Suite.
 *
 * <ul>
 *   <li>Number keys {@code 0..9} above letters: smoothly fade Time of Day ({@code 6} = Noon, {@code 0} = Midnight, {@code 1..5}/{@code 7..9} proportional).</li>
 *   <li>Minus key ({@code -}): opens {@link DevSettingsScreen} (and Shift+{@code -} quick-cycles the Hardware Quality Profile Ceiling from Steam Deck up to Max).</li>
 *   <li>Plus / Equals key ({@code =} / {@code +}): takes a live screenshot & telemetry capture and opens {@link DevCaptureScreen} with interactive cropping and issue notes.</li>
 *   <li>One-Shot World Placement: places any chosen block, item, configured loot chest, or NPC/entity into the world on Right-Click and immediately clears the hand.</li>
 *   <li>FPS & Performance Overlay: {@code Off}, {@code Basic} (upper-left small), {@code Advanced Small}, {@code Advanced Large}.</li>
 * </ul>
 */
@EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class DevTestClient {
    private static final boolean[] NUMBER_KEY_DOWN = new boolean[10];
    private static boolean minusKeyWasDown = false;
    private static boolean equalsKeyWasDown = false;
    private static boolean cancelKeyWasDown = false;
    private static int worldJoinSyncCooldown = 0;
    @Nullable
    private static UUID lastInitializedPlayerId = null;

    private DevTestClient() {}

    // ------------------------------------------------------------------ Client -> Server Bridge

    public static void runOnServerOrCommand(Consumer<ServerPlayer> directServerAction, String fallbackCommand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    directServerAction.accept(sp);
                }
            });
            return;
        }
        if (mc.player.connection != null && fallbackCommand != null && !fallbackCommand.isBlank()) {
            mc.player.connection.sendCommand(fallbackCommand);
        }
    }

    public static void sendSetDayTime(long ticks) {
        long clamped = Math.max(0L, ticks);
        runOnServerOrCommand(
                sp -> DevTestCommands.execSetDayTime(sp, clamped),
                "nirndev time " + clamped);
    }

    public static void sendApplyFlags() {
        boolean god = DevTestState.godMode;
        boolean fly = DevTestState.flyMode;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            var ab = mc.player.getAbilities();
            ab.invulnerable = god;
            ab.mayfly = fly || mc.player.isCreative();
            ab.flying = fly;
        }
        runOnServerOrCommand(
                sp -> DevTestCommands.execApplyFlags(sp, god, fly),
                "nirndev flags " + god + " " + fly);
    }

    public static void sendGiveItem(ItemStack stack, int count) {
        if (stack == null || stack.isEmpty() || count <= 0) return;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return;
        String idStr = id.toString();
        int qty = Math.max(1, Math.min(4096, count));
        DevTestState.recordEvent("Give Inventory: " + qty + "x " + stack.getHoverName().getString() + " (" + idStr + ")");
        DevTestState.showToast(
                "ADDED TO INVENTORY",
                qty + "x " + stack.getHoverName().getString() + " placed in NirnCraft Inventory",
                2200L);
        runOnServerOrCommand(
                sp -> DevTestCommands.execGiveItem(sp, idStr, qty),
                "nirndev give \"" + idStr + "\" " + qty);
    }

    public static void sendTeleportMapSafe(int x, int z, int mode) {
        String modeLabel = switch (mode) {
            case DevTestCommands.TP_ABOVE_GROUND -> "Above Ground (+35m)";
            case DevTestCommands.TP_HIGH_AERIAL -> "High Aerial (+120m)";
            default -> "Safe Surface";
        };
        DevTestState.recordEvent("Map Teleport (" + modeLabel + ") -> (" + x + ", " + z + ")");
        DevTestState.showToast(
                "TELEPORTING TO (" + x + ", " + z + ")",
                modeLabel + " — Safe terrain & building clearance active",
                2600L);
        runOnServerOrCommand(
                sp -> DevTestCommands.execTeleportMapSafe(sp, x, z, mode),
                "nirndev tp_map " + x + " " + z + " " + mode);
    }

    public static void sendBountyAction(String action, int amount) {
        DevTestState.recordEvent("Bounty Action: " + action + " (" + amount + ")");
        runOnServerOrCommand(
                sp -> DevTestCommands.execBountyAction(sp, action, amount),
                "nirndev bounty " + action + " " + Math.max(0, amount));
    }

    public static void sendApplyRacePreview(Race race, int look) {
        if (race == null) return;
        DevTestState.recordEvent("Race Preview Selected: " + race.name() + " (look=" + look + ")");
        DevTestState.showToast(
                "RACE UPDATED: " + race.name(),
                "Applied race & appearance without resetting player state or inventory",
                2500L);
        runOnServerOrCommand(
                sp -> DevTestCommands.execApplyRaceWithoutReset(sp, race.name(), look),
                "nirndev race_preview " + race.name() + " " + look);
    }

    public static void sendSpawnNpcInFront(
            NpcRole role, NpcNames.Race race, boolean female, boolean hostile, boolean passive, String customName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        BlockPos target = targetBlockInFront(mc, 4.0);
        String safeName = (customName == null || customName.isBlank()) ? "-" : customName.trim();
        DevTestState.recordEvent("Spawned NPC: " + race.name() + " " + role.id + " at " + target.toShortString());
        DevTestState.showToast(
                "SPAWNED NPC: " + race.name() + " " + role.id.toUpperCase(Locale.ROOT),
                "Spawned at (" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")",
                2200L);
        runOnServerOrCommand(
                sp -> DevTestCommands.execSpawnNpc(sp, target, role.id, race.name(), female, hostile, passive, safeName),
                "nirndev spawn_npc " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + " " + role.id + " " + race.name() + " " + female + " " + hostile + " " + passive + " \"" + safeName + "\"");
    }

    public static void sendSpawnEntityInFront(
            String entityId, boolean hostile, boolean passive, String customName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        BlockPos target = targetBlockInFront(mc, 5.0);
        String safeName = (customName == null || customName.isBlank()) ? "-" : customName.trim();
        DevTestState.recordEvent("Spawned Entity: " + entityId + " at " + target.toShortString());
        DevTestState.showToast(
                "SPAWNED ENTITY: " + entityId.toUpperCase(Locale.ROOT),
                "Spawned at (" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")",
                2200L);
        runOnServerOrCommand(
                sp -> DevTestCommands.execSpawnEntity(sp, target, entityId, hostile, passive, safeName),
                "nirndev spawn_entity " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + " \"" + entityId + "\" " + hostile + " " + passive + " \"" + safeName + "\"");
    }

    private static BlockPos targetBlockInFront(Minecraft mc, double maxDist) {
        if (mc.player == null) return BlockPos.ZERO;
        HitResult hit = mc.player.pick(maxDist, 1.0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos().relative(bhr.getDirection());
        }
        var look = mc.player.getViewVector(1.0f);
        return BlockPos.containing(
                mc.player.getX() + look.x * 3.0,
                mc.player.getY(),
                mc.player.getZ() + look.z * 3.0);
    }

    // ------------------------------------------------------------------ One-Shot World Placement

    public static void armOneShotItemOrBlock(String label, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || stack == null || stack.isEmpty()) return;
        cancelOneShotPlacement(false);
        Inventory inv = mc.player.getInventory();
        int slot = inv.getSelectedSlot();
        ItemStack prev = inv.getItem(slot).copy();
        ItemStack single = stack.copy();
        single.setCount(1);
        inv.setItem(slot, single);
        DevTestState.activePlacement = DevTestState.OneShotPlacement.forItemOrBlock(label, single, slot, prev);
        DevTestState.recordEvent("Armed One-Shot Placement (Block/Item): " + label);
        mc.gui.setScreen(null);
    }

    public static void armOneShotLootChest(
            String label, ContainerKind kind, ContainerStyle style, String lootTable, int lootLevel) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        cancelOneShotPlacement(false);
        Inventory inv = mc.player.getInventory();
        int slot = inv.getSelectedSlot();
        ItemStack prev = inv.getItem(slot).copy();
        ItemStack display = new ItemStack(ContainerRegistry.block(kind).asItem(), 1);
        inv.setItem(slot, display);
        DevTestState.activePlacement = DevTestState.OneShotPlacement.forLootChest(
                label, display, slot, prev, kind, style, lootTable, lootLevel);
        DevTestState.recordEvent("Armed One-Shot Loot Chest: " + label + " [" + lootTable + "]");
        mc.gui.setScreen(null);
    }

    public static void armOneShotNpc(
            String label,
            NpcRole role,
            NpcNames.Race race,
            boolean female,
            String customName,
            boolean hostile,
            boolean passive) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        cancelOneShotPlacement(false);
        Inventory inv = mc.player.getInventory();
        int slot = inv.getSelectedSlot();
        ItemStack prev = inv.getItem(slot).copy();
        ItemStack display = new ItemStack(Items.ARMOR_STAND, 1);
        inv.setItem(slot, display);
        DevTestState.activePlacement = DevTestState.OneShotPlacement.forNpc(
                label, display, slot, prev, role, race, female, customName, hostile, passive);
        DevTestState.recordEvent("Armed One-Shot NPC Spawner: " + label);
        mc.gui.setScreen(null);
    }

    public static void armOneShotEntity(
            String label, String entityId, String customName, boolean hostile, boolean passive) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        cancelOneShotPlacement(false);
        Inventory inv = mc.player.getInventory();
        int slot = inv.getSelectedSlot();
        ItemStack prev = inv.getItem(slot).copy();
        ItemStack display = new ItemStack(Items.BLAZE_ROD, 1);
        inv.setItem(slot, display);
        DevTestState.activePlacement = DevTestState.OneShotPlacement.forEntity(
                label, display, slot, prev, entityId, customName, hostile, passive);
        DevTestState.recordEvent("Armed One-Shot Entity Spawner: " + label);
        mc.gui.setScreen(null);
    }

    public static void cancelOneShotPlacement() {
        cancelOneShotPlacement(true);
    }

    public static void cancelOneShotPlacement(boolean showToast) {
        DevTestState.OneShotPlacement pending = DevTestState.activePlacement;
        if (pending == null) return;
        DevTestState.activePlacement = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Inventory inv = mc.player.getInventory();
            if (pending.savedSlot >= 0 && pending.savedSlot < 9) {
                inv.setItem(pending.savedSlot, pending.savedOriginalStack.copy());
            }
        }
        if (showToast) {
            DevTestState.showToast("ONE-SHOT PLACEMENT CANCELLED", "Hand cleared", 1600L);
        }
    }

    @SubscribeEvent
    public static void onInteractionTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !DevTestState.isActive(mc.player)) return;
        DevTestState.OneShotPlacement pending = DevTestState.activePlacement;
        if (pending == null) return;

        if (event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(true);

            HitResult hit = mc.player.pick(48.0, 1.0f, false);
            BlockPos placePos;
            Direction clickedFace = Direction.UP;
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                clickedFace = bhr.getDirection();
                BlockPos hitPos = bhr.getBlockPos();
                placePos = mc.level.getBlockState(hitPos).canBeReplaced() ? hitPos : hitPos.relative(clickedFace);
            } else {
                placePos = targetBlockInFront(mc, 4.5);
            }
            Direction horizFacing = mc.player.getDirection();
            executeOneShotPlacement(mc, pending, placePos, clickedFace, horizFacing);
        }
    }

    private static void executeOneShotPlacement(
            Minecraft mc,
            DevTestState.OneShotPlacement pending,
            BlockPos placePos,
            Direction clickedFace,
            Direction horizFacing) {
        // Restore hand slot immediately so the item disappears from hand after placing!
        cancelOneShotPlacement(false);

        switch (pending.kind) {
            case ITEM_OR_BLOCK -> {
                Identifier itemId = BuiltInRegistries.ITEM.getKey(pending.displayStack.getItem());
                if (itemId != null) {
                    String idStr = itemId.toString();
                    DevTestState.recordEvent("Placed in World (One-Shot): " + pending.label + " at " + placePos.toShortString());
                    DevTestState.showToast(
                            "PLACED IN WORLD: " + pending.label.toUpperCase(Locale.ROOT),
                            "Placed at (" + placePos.getX() + ", " + placePos.getY() + ", " + placePos.getZ() + ") — Hand cleared",
                            2000L);
                    runOnServerOrCommand(
                            sp -> DevTestCommands.execPlaceBlockDirectly(sp, placePos, clickedFace, horizFacing, idStr),
                            null);
                }
            }
            case LOOT_CHEST -> {
                String kName = pending.containerKind.id;
                String sName = pending.containerStyle.getSerializedName();
                String tbl = pending.lootTable;
                String fName = horizFacing.getName();
                DevTestState.recordEvent("Placed Loot Chest: " + pending.label + " [" + tbl + "] at " + placePos.toShortString());
                DevTestState.showToast(
                        "PLACED LOOT CHEST: " + pending.label.toUpperCase(Locale.ROOT),
                        "Loot Table: " + tbl + " at (" + placePos.getX() + ", " + placePos.getY() + ", " + placePos.getZ() + ")",
                        2400L);
                runOnServerOrCommand(
                        sp -> DevTestCommands.execPlaceLootChest(sp, placePos, horizFacing, kName, sName, tbl),
                        "nirndev place_loot " + placePos.getX() + " " + placePos.getY() + " " + placePos.getZ()
                                + " " + fName + " " + kName + " " + sName + " \"" + tbl + "\"");
            }
            case NPC -> {
                String safeName = (pending.customName == null || pending.customName.isBlank()) ? "-" : pending.customName.trim();
                DevTestState.recordEvent("Placed NPC: " + pending.label + " at " + placePos.toShortString());
                DevTestState.showToast(
                        "SPAWNED IN WORLD: " + pending.label.toUpperCase(Locale.ROOT),
                        "Spawned at (" + placePos.getX() + ", " + placePos.getY() + ", " + placePos.getZ() + ") — Hand cleared",
                        2200L);
                runOnServerOrCommand(
                        sp -> DevTestCommands.execSpawnNpc(
                                sp,
                                placePos,
                                pending.npcRole.id,
                                pending.npcRace.name(),
                                pending.npcFemale,
                                pending.forceHostile,
                                pending.forcePassive,
                                safeName),
                        "nirndev spawn_npc " + placePos.getX() + " " + placePos.getY() + " " + placePos.getZ()
                                + " " + pending.npcRole.id + " " + pending.npcRace.name()
                                + " " + pending.npcFemale + " " + pending.forceHostile + " " + pending.forcePassive
                                + " \"" + safeName + "\"");
            }
            case ENTITY -> {
                String safeName = (pending.customName == null || pending.customName.isBlank()) ? "-" : pending.customName.trim();
                DevTestState.recordEvent("Placed Entity: " + pending.label + " at " + placePos.toShortString());
                DevTestState.showToast(
                        "SPAWNED IN WORLD: " + pending.label.toUpperCase(Locale.ROOT),
                        "Spawned at (" + placePos.getX() + ", " + placePos.getY() + ", " + placePos.getZ() + ") — Hand cleared",
                        2200L);
                runOnServerOrCommand(
                        sp -> DevTestCommands.execSpawnEntity(
                                sp,
                                placePos,
                                pending.entityId,
                                pending.forceHostile,
                                pending.forcePassive,
                                safeName),
                        "nirndev spawn_entity " + placePos.getX() + " " + placePos.getY() + " " + placePos.getZ()
                                + " \"" + pending.entityId + "\" " + pending.forceHostile + " " + pending.forcePassive
                                + " \"" + safeName + "\"");
            }
        }
    }

    // ------------------------------------------------------------------ Session Capture (= / +)

    public static void triggerSessionCapture(@Nullable Screen returnScreen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        DevTestState.recordEvent("Triggered Session Capture (= / +)");
        try {
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                mc.execute(() -> mc.gui.setScreen(new DevCaptureScreen(returnScreen, image)));
            });
        } catch (Exception e) {
            NirnCraft.LOGGER.warn("Screenshot capture fallback for DevCaptureScreen", e);
            NativeImage fallback = new NativeImage(320, 180, true);
            mc.gui.setScreen(new DevCaptureScreen(returnScreen, fallback));
        }
    }

    // ------------------------------------------------------------------ Tick & Key Handling

    @SubscribeEvent
    public static void onScreenOpened(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !DevTestState.isActive(mc.player)) return;
        Screen next = event.getNewScreen();
        if (next != null) {
            DevTestState.recordEvent("Opened Screen: " + next.getClass().getSimpleName());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            DevTestState.sessionInitialized = false;
            lastInitializedPlayerId = null;
            return;
        }
        if (!DevTestState.isActive(mc.player)) {
            if (DevTestState.isDevPropertySet() && mc.getSingleplayerServer() != null && !DevTestState.sessionInitialized) {
                DevTestState.sessionInitialized = true;
                runOnServerOrCommand(sp -> DevTestCommands.syncServerAuthorization(sp, true), null);
            }
            return;
        }

        // Apply Dev Mode defaults on world join / respawn
        if (!DevTestState.sessionInitialized || !mc.player.getUUID().equals(lastInitializedPlayerId)) {
            DevTestState.sessionInitialized = true;
            lastInitializedPlayerId = mc.player.getUUID();
            worldJoinSyncCooldown = 15;
            DevTestState.recordEvent("Dev/Creative Session Initialized at " + mc.player.blockPosition().toShortString());
        }
        if (worldJoinSyncCooldown > 0) {
            worldJoinSyncCooldown--;
            if (worldJoinSyncCooldown == 1) {
                sendApplyFlags();
                DevTestState.showToast(
                        "NIRNCRAFT DEV / CREATIVE SUITE ACTIVE",
                        "[0-9] Time of Day | [-] Quality & Dev Menu | [=] Capture Issue | [E] Creative Catalog | [M] Teleport Map",
                        4500L);
            }
        }

        // Smooth Time-of-Day fader
        if (DevTestState.fadingTicksRemaining > 0 && DevTestState.targetDayTime >= 0L) {
            DevTestState.fadingTicksRemaining--;
            if (DevTestState.fadingTicksRemaining <= 0) {
                sendSetDayTime(DevTestState.targetDayTime);
            } else {
                double cur = DevTestState.fadingDayTime;
                double tgt = DevTestState.targetDayTime;
                double next = cur + (tgt - cur) * 0.26;
                DevTestState.fadingDayTime = next;
                sendSetDayTime(Math.round(next));
            }
        }

        // Only poll gameplay dev keys when no GUI screen is open
        if (mc.gui.screen() != null) {
            resetKeyEdges();
            return;
        }

        // If one-shot placement is active, allow Q (drop) to cancel it cleanly
        if (DevTestState.activePlacement != null) {
            while (mc.options.keyDrop.consumeClick()) {
                cancelOneShotPlacement(true);
            }
            boolean qDown = InputConstants.isKeyDown(InputConstants.KEY_Q);
            if (qDown && !cancelKeyWasDown) {
                cancelOneShotPlacement(true);
            }
            cancelKeyWasDown = qDown;
        } else {
            cancelKeyWasDown = false;
        }

        // Number keys 0-9 above letters -> Smooth Time-of-Day control (6 = Noon, 0 = Midnight)
        for (int digit = 0; digit <= 9; digit++) {
            int keyCode = InputConstants.KEY_0 + digit;
            boolean down = InputConstants.isKeyDown(keyCode);
            if (down && !NUMBER_KEY_DOWN[digit]) {
                // Consume vanilla hotbar key clicks so 1..9 control Time of Day in Dev mode without switching slots
                for (KeyMapping hotbarKey : mc.options.keyHotbarSlots) {
                    while (hotbarKey.consumeClick()) {
                        // consumed
                    }
                }
                DevTestState.startTimeFade(mc.level.getOverworldClockTime(), digit);
            }
            NUMBER_KEY_DOWN[digit] = down;
        }

        // Also drain any residual hotbar clicks while number keys are held
        for (int digit = 1; digit <= 9; digit++) {
            if (NUMBER_KEY_DOWN[digit] && digit - 1 < mc.options.keyHotbarSlots.length) {
                while (mc.options.keyHotbarSlots[digit - 1].consumeClick()) {
                    // consumed
                }
            }
        }

        // Minus key (-) -> Open DevSettingsScreen (or Shift + - to quick-cycle Quality Profile Ceiling)
        boolean minusDown = InputConstants.isKeyDown(InputConstants.KEY_MINUS);
        if (minusDown && !minusKeyWasDown) {
            boolean shift = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
                    || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
            if (shift) {
                DevTestState.cycleQualityCeiling(1);
            } else {
                DevTestState.recordEvent("Pressed [-] -> Opening DevSettingsScreen");
                mc.gui.setScreen(new DevSettingsScreen(null));
            }
        }
        minusKeyWasDown = minusDown;

        // Equals / Plus key (= / +) -> Capture session screenshot & metrics and open DevCaptureScreen
        boolean equalsDown = InputConstants.isKeyDown(InputConstants.KEY_EQUALS)
                || InputConstants.isKeyDown(InputConstants.KEY_ADD);
        if (equalsDown && !equalsKeyWasDown) {
            triggerSessionCapture(null);
        }
        equalsKeyWasDown = equalsDown;
    }

    private static void resetKeyEdges() {
        for (int i = 0; i < NUMBER_KEY_DOWN.length; i++) NUMBER_KEY_DOWN[i] = false;
        minusKeyWasDown = true;
        equalsKeyWasDown = true;
        cancelKeyWasDown = true;
    }

    // ------------------------------------------------------------------ HUD Overlays (FPS, Toast, One-Shot Indicator)

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !DevTestState.isActive(mc.player)) return;
        DevTestState.recordFrameTick();

        GuiGraphicsExtractor g = event.getGuiGraphics();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // 1. Upper-left FPS & Performance Overlay
        renderFpsOverlay(g, font, mc);

        // 2. Active One-Shot Placement Banner
        DevTestState.OneShotPlacement pending = DevTestState.activePlacement;
        if (pending != null && mc.gui.screen() == null) {
            renderOneShotBanner(g, font, sw, sh, pending);
        }

        // 3. Top-center Toast Banner (Time of Day / Quality Ceiling / Teleport / Dev toggles)
        long now = System.currentTimeMillis();
        if (now < DevTestState.toastUntilMs && !DevTestState.toastTitle.isEmpty()) {
            renderToastBanner(g, font, sw, now);
        }
    }

    private static void renderFpsOverlay(GuiGraphicsExtractor g, Font font, Minecraft mc) {
        DevTestState.FpsOverlayMode mode = DevTestState.fpsOverlayMode;
        if (mode == DevTestState.FpsOverlayMode.OFF) return;

        double[] stats = DevTestState.computeFrameStats();
        int fps = (int) Math.round(stats[0]);
        double avgMs = stats[1];
        double minMs = stats[2];
        double maxMs = stats[3];
        double p50Ms = stats[4];
        double p99Ms = stats[5];
        int onePctFps = (int) Math.round(stats[6]);

        int fpsColor = fps >= 55 ? 0xFF7AD87A : (fps >= 30 ? 0xFFE5C467 : 0xFFE06666);

        if (mode == DevTestState.FpsOverlayMode.BASIC) {
            String text = String.format(
                    Locale.ROOT,
                    "FPS %d  (%.1f ms | min %.1f / max %.1f ms)  [%s]",
                    fps, avgMs, minMs, maxMs, DevTestState.qualityCeiling.shortTag);
            int w = font.width(text) + 10;
            g.fill(6, 6, 6 + w, 20, 0xB80A0A0E);
            g.fill(6, 6, 6 + w, 7, 0x887A7464);
            g.fill(6, 19, 6 + w, 20, 0x887A7464);
            g.text(font, text, 11, 9, fpsColor, true);
            return;
        }

        if (mode == DevTestState.FpsOverlayMode.ADVANCED_SMALL) {
            String hw = QualityClient.qualityName();
            if (hw == null) hw = "AUTO";
            String line1 = String.format(
                    Locale.ROOT,
                    "FPS: %d (1%% Low: %d)  |  %.1f ms (min %.1f / p50 %.1f / p99 %.1f / max %.1f)",
                    fps, onePctFps, avgMs, minMs, p50Ms, p99Ms, maxMs);
            String line2 = String.format(
                    Locale.ROOT,
                    "Profile Ceiling: %s (HW: %s)  |  Lighting: %s",
                    DevTestState.qualityCeiling.label,
                    hw,
                    DevTestState.tracedLighting ? "Traced ON" : "Fallback Low");
            int w = Math.max(font.width(line1), font.width(line2)) + 12;
            SkyUi.panel(g, 6, 6, 6 + w, 6 + 30);
            g.text(font, line1, 12, 10, fpsColor, true);
            g.text(font, line2, 12, 22, SkyUi.TEXT, true);
            return;
        }

        // ADVANCED_LARGE
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);
        BlockPos pos = mc.player.blockPosition();
        String regionName = "Tamriel";
        try {
            Region reg = Regions.at(mc.level, pos);
            if (reg != null) regionName = reg.id();
        } catch (Exception ignored) {
        }
        String atlasLine = AtlasClient.debugInfo();
        if (atlasLine.length() > 74) atlasLine = atlasLine.substring(0, 74);
        double[] k = QualityClient.knobs();
        String hw = QualityClient.qualityName();
        if (hw == null) hw = "AUTO";

        String[] lines = new String[]{
                String.format(Locale.ROOT, "FPS: %d  |  1%% Low: %d FPS  |  Frame: %.2f ms (min %.1f / p50 %.1f / p99 %.1f / max %.1f ms)",
                        fps, onePctFps, avgMs, minMs, p50Ms, p99Ms, maxMs),
                String.format(Locale.ROOT, "Ceiling: %s (HW: %s)  |  Lighting: %s  |  Fallback Tier: %d",
                        DevTestState.qualityCeiling.label,
                        hw,
                        DevTestState.tracedLighting ? "Traced ON" : "Fallback Low",
                        QualityClient.fallbackTier()),
                String.format(Locale.ROOT, "Knobs: Scale %.0f%% | LOD %.0f%% | Detail %.0f%% | Shadows %.0f%% | Clouds %.0f%%",
                        k[Knob.ATLAS_SCALE.ordinal()] * 100.0,
                        k[Knob.ATLAS_LOD.ordinal()] * 100.0,
                        k[Knob.FAR_DETAIL.ordinal()] * 100.0,
                        k[Knob.SHADOW_TAPS.ordinal()] * 100.0,
                        k[Knob.ATLAS_CLOUDS.ordinal()] * 100.0),
                String.format(Locale.ROOT, "JVM Heap: %d / %d MB  |  Pos: (%d, %d, %d) [%s]  |  Time: %s",
                        usedMb, maxMb, pos.getX(), pos.getY(), pos.getZ(), regionName,
                        DevTestState.formatDayTime(mc.level.getOverworldClockTime())),
                "Atlas: " + atlasLine
        };
        int maxW = 0;
        for (String s : lines) maxW = Math.max(maxW, font.width(s));
        int boxW = maxW + 14;
        int boxH = lines.length * 11 + 10;
        SkyUi.panel(g, 6, 6, 6 + boxW, 6 + boxH);
        for (int i = 0; i < lines.length; i++) {
            int col = i == 0 ? fpsColor : (i == 4 ? SkyUi.DIM : SkyUi.TEXT);
            g.text(font, lines[i], 13, 11 + i * 11, col, true);
        }
    }

    private static void renderOneShotBanner(
            GuiGraphicsExtractor g, Font font, int sw, int sh, DevTestState.OneShotPlacement pending) {
        String title = "IN HAND (ONE-SHOT): " + pending.label.toUpperCase(Locale.ROOT);
        String hint = "[Right-Click] Place in World & Clear Hand   |   [Q] Cancel";
        int w = Math.max(font.width(title), font.width(hint)) + 36;
        int x = (sw - w) / 2;
        int y = sh - 54;
        SkyUi.panel(g, x, y, x + w, y + 30);
        SkyUi.diamond(g, x + 10, y + 15, 3, SkyUi.GOLD);
        SkyUi.diamond(g, x + w - 10, y + 15, 3, SkyUi.GOLD);
        g.centeredText(font, Component.literal(title), sw / 2, y + 6, SkyUi.GOLD);
        g.centeredText(font, Component.literal(hint), sw / 2, y + 17, SkyUi.TEXT);
    }

    private static void renderToastBanner(GuiGraphicsExtractor g, Font font, int sw, long now) {
        String title = DevTestState.toastTitle;
        String sub = DevTestState.toastSubtitle;
        int w = Math.max(font.width(title), font.width(sub)) + 36;
        int h = sub.isEmpty() ? 20 : 30;
        int x = (sw - w) / 2;
        int y = switch (DevTestState.fpsOverlayMode) {
            case OFF, BASIC -> 26;
            case ADVANCED_SMALL -> 42;
            case ADVANCED_LARGE -> 76;
        };
        SkyUi.panel(g, x, y, x + w, y + h);
        g.centeredText(font, Component.literal(title), sw / 2, y + 5, SkyUi.GOLD);
        if (!sub.isEmpty()) {
            g.centeredText(font, Component.literal(sub), sw / 2, y + 17, SkyUi.DIM);
        }
    }
}
