package com.nirncraft.devtest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.Holds;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.Race;
import com.nirncraft.core.SkyData;
import com.nirncraft.crime.Bounty;
import com.nirncraft.inventory.Inv;
import com.nirncraft.loot.container.ContainerKind;
import com.nirncraft.loot.container.ContainerRegistry;
import com.nirncraft.loot.container.ContainerStyle;
import com.nirncraft.loot.container.LootContainerBlock;
import com.nirncraft.loot.container.LootContainerBlockEntity;
import com.nirncraft.society.Cultures;
import com.nirncraft.society.NpcLook;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.Npcs;
import com.nirncraft.society.Outfit;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Locale;
import java.util.Set;

/**
 * Server-side execution and Brigadier {@code /nirndev} commands for the Dev / Creative Testing Suite.
 * Avoids modifying {@code SkyNetwork} packet registration order so wire-protocol hashes stay identical.
 */
@EventBusSubscriber(modid = NirnCraft.MODID)
public final class DevTestCommands {
    /** Teleport altitude mode: safe surface (+2 blocks above highest obstacle/roof). */
    public static final int TP_SURFACE_SAFE = 0;
    /** Teleport altitude mode: low aerial overview (+35 blocks above surface, flying enabled). */
    public static final int TP_ABOVE_GROUND = 1;
    /** Teleport altitude mode: high aerial overview (+120 blocks above surface, flying enabled). */
    public static final int TP_HIGH_AERIAL = 2;

    private DevTestCommands() {}

    /**
     * Authoritative server check for whether {@code player} is allowed to use Creative / Dev mode features.
     */
    public static boolean isServerAuthorized(ServerPlayer player) {
        if (player == null) return false;
        if (player.isCreative()) return true;
        var server = player.level().getServer();
        if (server != null && (server.isSingleplayerOwner(player.nameAndId()) || server.getPlayerList().isOp(player.nameAndId()))) {
            return DevTestState.isDevPropertySet();
        }
        return false;
    }

    /** Syncs the server's authoritative Dev/Creative mode access flag to the client via {@link SkyData}. */
    public static void syncServerAuthorization(ServerPlayer player, boolean forceCreativeOnDevProp) {
        if (player == null) return;
        if (forceCreativeOnDevProp && DevTestState.isDevPropertySet() && !player.isCreative() && !player.isSpectator()) {
            var server = player.level().getServer();
            if (server != null && (server.isSingleplayerOwner(player.nameAndId()) || server.getPlayerList().isOp(player.nameAndId()))) {
                player.setGameMode(GameType.CREATIVE);
            }
        }
        boolean auth = isServerAuthorized(player);
        SkyData.get(player).module("devtest").putBoolean("authorized", auth);
        SkyData.get(player).markDirty();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            syncServerAuthorization(sp, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            syncServerAuthorization(sp, false);
        }
    }

    @SubscribeEvent
    public static void onGameModeChanged(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            boolean nextCreative = event.getNewGameMode() == GameType.CREATIVE;
            boolean auth = nextCreative || (DevTestState.isDevPropertySet() && isServerAuthorized(sp));
            SkyData.get(sp).module("devtest").putBoolean("authorized", auth);
            SkyData.get(sp).markDirty();
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirndev")
                .requires(src -> isServerAuthorized(src.getPlayer()))
                .then(Commands.literal("time")
                        .then(Commands.argument("ticks", LongArgumentType.longArg(0L))
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                    long t = LongArgumentType.getLong(ctx, "ticks");
                                    execSetDayTime(sp, t);
                                    return 1;
                                })))
                .then(Commands.literal("flags")
                        .then(Commands.argument("god", BoolArgumentType.bool())
                                .then(Commands.argument("fly", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                            execApplyFlags(
                                                    sp,
                                                    BoolArgumentType.getBool(ctx, "god"),
                                                    BoolArgumentType.getBool(ctx, "fly"));
                                            return 1;
                                        }))))
                .then(Commands.literal("give")
                        .then(Commands.argument("itemId", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                            execGiveItem(
                                                    sp,
                                                    StringArgumentType.getString(ctx, "itemId"),
                                                    IntegerArgumentType.getInteger(ctx, "count"));
                                            return 1;
                                        }))))
                .then(Commands.literal("tp_map")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("mode", IntegerArgumentType.integer(0, 2))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                                    execTeleportMapSafe(
                                                            sp,
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "z"),
                                                            IntegerArgumentType.getInteger(ctx, "mode"));
                                                    return 1;
                                                })))))
                .then(Commands.literal("bounty")
                        .then(Commands.argument("action", StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100000))
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                            execBountyAction(
                                                    sp,
                                                    StringArgumentType.getString(ctx, "action"),
                                                    IntegerArgumentType.getInteger(ctx, "amount"));
                                            return 1;
                                        }))))
                .then(Commands.literal("place_loot")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("facing", StringArgumentType.word())
                                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                                .then(Commands.argument("style", StringArgumentType.word())
                                                                        .then(Commands.argument("table", StringArgumentType.string())
                                                                                .executes(ctx -> {
                                                                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                                                                    execPlaceLootChest(
                                                                                            sp,
                                                                                            new BlockPos(
                                                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                                                    IntegerArgumentType.getInteger(ctx, "z")),
                                                                                            Direction.byName(StringArgumentType.getString(ctx, "facing")),
                                                                                            StringArgumentType.getString(ctx, "kind"),
                                                                                            StringArgumentType.getString(ctx, "style"),
                                                                                            StringArgumentType.getString(ctx, "table"));
                                                                                    return 1;
                                                                                })))))))))
                .then(Commands.literal("spawn_npc")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("role", StringArgumentType.word())
                                                        .then(Commands.argument("race", StringArgumentType.word())
                                                                .then(Commands.argument("female", BoolArgumentType.bool())
                                                                        .then(Commands.argument("hostile", BoolArgumentType.bool())
                                                                                .then(Commands.argument("passive", BoolArgumentType.bool())
                                                                                        .then(Commands.argument("name", StringArgumentType.string())
                                                                                                .executes(ctx -> {
                                                                                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                                                                                    execSpawnNpc(
                                                                                                            sp,
                                                                                                            new BlockPos(
                                                                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                                                                    IntegerArgumentType.getInteger(ctx, "z")),
                                                                                                            StringArgumentType.getString(ctx, "role"),
                                                                                                            StringArgumentType.getString(ctx, "race"),
                                                                                                            BoolArgumentType.getBool(ctx, "female"),
                                                                                                            BoolArgumentType.getBool(ctx, "hostile"),
                                                                                                            BoolArgumentType.getBool(ctx, "passive"),
                                                                                                            StringArgumentType.getString(ctx, "name"));
                                                                                                    return 1;
                                                                                                })))))))))))
                .then(Commands.literal("spawn_entity")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("entityId", StringArgumentType.string())
                                                        .then(Commands.argument("hostile", BoolArgumentType.bool())
                                                                .then(Commands.argument("passive", BoolArgumentType.bool())
                                                                        .then(Commands.argument("name", StringArgumentType.string())
                                                                                .executes(ctx -> {
                                                                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                                                                    execSpawnEntity(
                                                                                            sp,
                                                                                            new BlockPos(
                                                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                                                    IntegerArgumentType.getInteger(ctx, "z")),
                                                                                            StringArgumentType.getString(ctx, "entityId"),
                                                                                            BoolArgumentType.getBool(ctx, "hostile"),
                                                                                            BoolArgumentType.getBool(ctx, "passive"),
                                                                                            StringArgumentType.getString(ctx, "name"));
                                                                                    return 1;
                                                                                })))))))))
                .then(Commands.literal("race_preview")
                        .then(Commands.argument("race", StringArgumentType.word())
                                .then(Commands.argument("look", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                            execApplyRaceWithoutReset(
                                                    sp,
                                                    StringArgumentType.getString(ctx, "race"),
                                                    IntegerArgumentType.getInteger(ctx, "look"));
                                            return 1;
                                        })))));
    }

    // ------------------------------------------------------------------ Server implementations

    public static void execSetDayTime(ServerPlayer player, long targetTime) {
        if (!isServerAuthorized(player)) return;
        var server = player.level().getServer();
        if (server == null) return;
        long clamped = Math.max(0L, targetTime);
        ServerLevel overworld = server.overworld();
        overworld.dimensionType().defaultClock().ifPresent(clock -> server.clockManager().setTotalTicks(clock, clamped));
        server.getPlayerList().broadcastAll(server.clockManager().createFullSyncPacket());
    }

    public static void execApplyFlags(ServerPlayer player, boolean godMode, boolean flyMode) {
        if (!isServerAuthorized(player)) return;
        syncServerAuthorization(player, true);
        var abilities = player.getAbilities();
        abilities.invulnerable = godMode;
        abilities.mayfly = flyMode || player.isCreative();
        if (flyMode) {
            abilities.flying = true;
        } else {
            abilities.flying = false;
        }
        player.onUpdateAbilities();
        PlayerData data = SkyData.get(player);
        if (godMode) {
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.clearFire();
            player.resetFallDistance();
            data.setMagicka(data.maxMagicka());
            data.setStamina(data.maxStamina());
        }
        data.markDirty();
    }

    public static void execGiveItem(ServerPlayer player, String itemId, int count) {
        if (!isServerAuthorized(player) || count <= 0) return;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) return;
        int remaining = count;
        int maxStack = Math.max(1, item.getDefaultMaxStackSize());
        while (remaining > 0) {
            int batch = Math.min(remaining, Math.max(maxStack, 64));
            ItemStack stack = new ItemStack(item, batch);
            Inv.giveOrDrop(player, stack);
            remaining -= batch;
        }
        player.level().playSound(
                null,
                player.blockPosition(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.PLAYERS,
                0.5f,
                1.1f);
    }

    public static void execPlaceBlockDirectly(ServerPlayer player, BlockPos pos, Direction clickedFace, Direction horizFacing, String itemId) {
        if (!isServerAuthorized(player) || pos == null) return;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (!(item instanceof BlockItem bi)) return;
        ServerLevel level = player.level();
        Block block = bi.getBlock();
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && horizFacing != null && horizFacing.getAxis().isHorizontal()) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, horizFacing.getOpposite());
        } else if (state.hasProperty(BlockStateProperties.FACING) && clickedFace != null) {
            state = state.setValue(BlockStateProperties.FACING, clickedFace);
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        level.playSound(null, pos, state.getSoundType(level, pos, player).getPlaceSound(), SoundSource.BLOCKS, 0.9f, 1.0f);
    }

    public static void execPlaceLootChest(
            ServerPlayer player,
            BlockPos pos,
            Direction horizFacing,
            String kindName,
            String styleName,
            String lootTable) {
        if (!isServerAuthorized(player) || pos == null) return;
        ContainerKind kind = ContainerKind.CHEST;
        for (ContainerKind k : ContainerKind.VALUES) {
            if (k.id.equalsIgnoreCase(kindName) || k.name().equalsIgnoreCase(kindName)) {
                kind = k;
                break;
            }
        }
        ContainerStyle style = ContainerStyle.byId(styleName);
        if (style == null) style = ContainerStyle.NORDIC;
        Direction facing = (horizFacing != null && horizFacing.getAxis().isHorizontal())
                ? horizFacing.getOpposite()
                : player.getDirection().getOpposite();

        ServerLevel level = player.level();
        Block block = ContainerRegistry.block(kind);
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(LootContainerBlock.FACING)) {
            state = state.setValue(LootContainerBlock.FACING, facing);
        }
        if (state.hasProperty(LootContainerBlock.STYLE)) {
            state = state.setValue(LootContainerBlock.STYLE, style);
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, Direction.UP);
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof LootContainerBlockEntity be) {
            String tbl = (lootTable == null || lootTable.isBlank()) ? "nirncraft:containers/dungeon/minor" : lootTable.trim();
            be.setWorldLoot(tbl);
            be.setChanged();
        }
        level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.85f, 0.95f);
    }

    public static void execSpawnNpc(
            ServerPlayer player,
            BlockPos pos,
            String roleName,
            String raceName,
            boolean female,
            boolean forceHostile,
            boolean forcePassive,
            String customName) {
        if (!isServerAuthorized(player) || pos == null) return;
        ServerLevel level = player.level();
        NpcRole role = NpcRole.byId(roleName.toLowerCase(Locale.ROOT));
        if (role == null) {
            try {
                role = NpcRole.valueOf(roleName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                role = NpcRole.CITIZEN;
            }
        }
        NpcNames.Race race = NpcNames.Race.NORD;
        for (NpcNames.Race r : NpcNames.Race.VALUES) {
            if (r.name().equalsIgnoreCase(raceName)) {
                race = r;
                break;
            }
        }
        NpcEntity npc = Npcs.create(level, pos, role);
        if (npc == null) return;
        Outfit outfit = Cultures.outfitFor(role, race, npc.getRandom());
        int look = NpcLook.random(race, female, role == NpcRole.CHILD, outfit, npc.getRandom());
        npc.setLook(look);
        Npcs.outfit(npc);
        if (customName != null && !customName.isBlank() && !"-".equals(customName)) {
            npc.setCustomName(Component.literal(customName.trim()));
            npc.setCustomNameVisible(true);
        }
        float yaw = player.getYRot() + 180.0f;
        npc.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        npc.setYHeadRot(yaw);
        npc.setPersistenceRequired();
        if (forcePassive) {
            npc.setNoAi(false);
            npc.setTarget(null);
        } else if (forceHostile) {
            npc.setTarget(player);
        }
        level.addFreshEntity(npc);
        level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.NEUTRAL, 0.8f, 1.0f);
    }

    public static void execSpawnEntity(
            ServerPlayer player,
            BlockPos pos,
            String entityId,
            boolean forceHostile,
            boolean forcePassive,
            String customName) {
        if (!isServerAuthorized(player) || pos == null) return;
        Identifier id = Identifier.tryParse(entityId);
        if (id == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        if (type == null) return;
        ServerLevel level = player.level();
        Entity entity = type.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) return;
        float yaw = player.getYRot() + 180.0f;
        entity.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        if (entity instanceof Mob mob) {
            mob.setYHeadRot(yaw);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.COMMAND, null);
            mob.setPersistenceRequired();
            if (forcePassive) {
                mob.setTarget(null);
            } else if (forceHostile) {
                mob.setTarget(player);
            }
        }
        if (customName != null && !customName.isBlank() && !"-".equals(customName)) {
            entity.setCustomName(Component.literal(customName.trim()));
            entity.setCustomNameVisible(true);
        }
        level.addFreshEntity(entity);
        level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 0.6f, 0.9f);
    }

    /**
     * Teleports the player to {@code (x, z)} with safe vertical clearance so the player never spawns trapped inside
     * terrain or a building.
     */
    public static void execTeleportMapSafe(ServerPlayer player, int x, int z, int mode) {
        if (!isServerAuthorized(player)) return;
        ServerLevel level = player.level();
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        int topMotion = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
        int topWorld = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
        int baseSurfaceY = Math.max(level.getMinY() + 4, Math.max(topMotion, topWorld));

        // Scan upward if there is any roof or structure overhead so we never get trapped inside a building
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, baseSurfaceY, z);
        int maxScanY = Math.min(level.getMaxY() - 4, baseSurfaceY + 64);
        for (int y = baseSurfaceY; y <= maxScanY; y++) {
            cursor.setY(y);
            BlockState s0 = level.getBlockState(cursor);
            cursor.setY(y + 1);
            BlockState s1 = level.getBlockState(cursor);
            if (s0.isAir() && s1.isAir() && level.canSeeSky(cursor)) {
                baseSurfaceY = y;
                break;
            }
        }

        double targetY = switch (mode) {
            case TP_ABOVE_GROUND -> baseSurfaceY + 35.0;
            case TP_HIGH_AERIAL -> baseSurfaceY + 120.0;
            default -> baseSurfaceY + 2.0;
        };

        var abilities = player.getAbilities();
        abilities.mayfly = true;
        if (mode != TP_SURFACE_SAFE || DevTestState.flyMode) {
            abilities.flying = true;
        }
        if (DevTestState.godMode) {
            abilities.invulnerable = true;
        }
        player.onUpdateAbilities();
        player.resetFallDistance();
        player.teleportTo(
                level,
                x + 0.5,
                targetY,
                z + 0.5,
                Set.of(),
                player.getYRot(),
                player.getXRot(),
                true);
        player.resetFallDistance();
        level.playSound(null, BlockPos.containing(x + 0.5, targetY, z + 0.5), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.65f, 1.1f);
    }

    public static void execBountyAction(ServerPlayer player, String action, int amount) {
        if (!isServerAuthorized(player)) return;
        String hold = Holds.holdAt(player.level(), player.blockPosition());
        switch (action.toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                Bounty.clearAll(player);
                Bounty.clearHostile(player);
            }
            case "add" -> Bounty.add(player, hold, Math.max(1, amount));
            case "hostile" -> {
                if (Bounty.isHostile(player)) {
                    Bounty.clearHostile(player);
                } else {
                    Bounty.add(player, hold, Math.max(1000, amount));
                    Bounty.makeHostile(player, 24000);
                }
            }
            default -> {}
        }
    }

    /** Updates race & appearance look in dev/test mode without resetting inventory, health, or world position. */
    public static void execApplyRaceWithoutReset(ServerPlayer player, String raceName, int look) {
        if (!isServerAuthorized(player)) return;
        Race chosen = Race.byId(raceName);
        if (chosen == null) {
            chosen = Race.NORD;
            for (Race r : Race.VALUES) {
                if (r.name().equalsIgnoreCase(raceName)) {
                    chosen = r;
                    break;
                }
            }
        }
        PlayerData data = SkyData.get(player);
        int sanitizedLook = look < 0
                ? NpcLook.defaultFor(chosen, player.getUUID().getLeastSignificantBits())
                : NpcLook.sanitizePlayer(look, chosen);
        data.module("race").putInt("look", sanitizedLook);
        data.setRace(chosen);
        data.markDirty();
    }
}
