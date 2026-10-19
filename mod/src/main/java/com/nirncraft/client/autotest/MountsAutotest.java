package com.nirncraft.client.autotest;

import com.nirncraft.NirnCraft;
import com.nirncraft.economy.Houses;
import com.nirncraft.mounts.MountBeast;
import com.nirncraft.mounts.MountItems;
import com.nirncraft.mounts.MountKind;
import com.nirncraft.mounts.Mounts;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * {@code -Dnirncraft.autotest.mounts=true} (with {@code -Dnirncraft.autotest=true}): lines up the mounts of every
 * province near spawn (saddled and barded), photographs them, rides one, then flies to provincial capitals and
 * photographs their player homes once built ({@code -Dnirncraft.autotest.homes=talos_plaza_house,sentinel_villa}).
 */
final class MountsAutotest {
    static final boolean ENABLED = Boolean.getBoolean("nirncraft.autotest.mounts");
    private static final String[] HOMES = System.getProperty("nirncraft.autotest.homes", "talos_plaza_house,sentinel_villa").split(",");

    private static final List<List<MountKind>> GROUPS = List.of(
            List.of(MountKind.WHITERUN_BAY, MountKind.CYRODILIC_BAY, MountKind.BRETON_DESTRIER, MountKind.ALIKR_STEED,
                    MountKind.AURIDON_WHITE, MountKind.SHADOWMERE, MountKind.ARVAK),
            List.of(MountKind.CAMEL, MountKind.GUAR, MountKind.SENCHE, MountKind.GIANT_WOLF, MountKind.INDRIK),
            List.of(MountKind.MAMMOTH, MountKind.AKAVIRI_TIGER, MountKind.REALM_GRAZER, MountKind.SENCHE));

    private static final int LINEUP_X = Integer.getInteger("nirncraft.autotest.lineupX", -700);
    private static final int LINEUP_Z = Integer.getInteger("nirncraft.autotest.lineupZ", -140);
    private static int step;
    private static int wait;
    private static int homeTries;
    private static BlockPos origin;
    private static volatile String homeState = "";

    private MountsAutotest() {}

    private static void server(Minecraft mc, java.util.function.Consumer<ServerPlayer> work) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) work.accept(p);
        });
    }

    private static void cmd(ServerPlayer p, String command) {
        var server = p.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                command.replace("@p", p.getGameProfile().name()));
    }

    static void tick(Minecraft mc) {
        if (wait-- > 0) return;
        int s = step++;
        wait = 40;
        switch (s) {
            case 0 -> {
                com.nirncraft.network.SkyNetwork.sendToServer(new com.nirncraft.network.CorePackets.ChooseRace(com.nirncraft.core.Race.NORD.id()));
                mc.gui.setScreen(null);
                server(mc, p -> {
                    cmd(p, "gamerule advance_time false");
                    cmd(p, "gamerule spawn_mobs false");
                    cmd(p, "time set 6000");
                    cmd(p, "weather clear 1000000");
                    cmd(p, "gamemode creative @p");
                    com.nirncraft.quest.story.Story.setFlag(com.nirncraft.core.SkyData.get(p), "helgen_arrived", true);
                    // the open tundra west of Whiterun, away from the forests and the Helgen scene
                    ServerLevel level = p.level();
                    int x = LINEUP_X, z = LINEUP_Z;
                    level.getChunk(x >> 4, z >> 4);
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    origin = new BlockPos(x, y, z);
                    cmd(p, String.format(java.util.Locale.ROOT, "tp @p %d %d %d 0 10", x, y + 2, z - 4));
                });
                wait = 200;
            }
            case 1 -> {
                server(mc, MountsAutotest::lineUp);
                com.nirncraft.client.autotest.ReviewWorlds.hudHidden(mc, true);
            }
            case 2, 4, 6 -> {
                int g = ((s - 2) / 2 + 1) % 3; // the horses last: the Helgen opening may still move the player early on
                server(mc, p -> look(p, g));
                wait = 80;
            }
            case 3, 5, 7 -> ClientAutotest.captureAndSave(mc, String.format("m%02d_mounts_group%d", s, ((s - 3) / 2 + 1) % 3 + 1));
            case 8 -> {
                com.nirncraft.client.autotest.ReviewWorlds.hudHidden(mc, false);
                server(mc, MountsAutotest::ride);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                wait = 80;
            }
            case 9 -> {
                ClientAutotest.captureAndSave(mc, "m09_riding_senche");
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                server(mc, p -> {
                    p.stopRiding();
                    cmd(p, "gamemode spectator @p");
                });
            }
            default -> homes(mc, s - 10);
        }
    }

    // ------------------------------------------------------------------ the line-up

    private static void lineUp(ServerPlayer p) {
        ServerLevel level = p.level();
        BlockPos o = origin != null ? origin : p.blockPosition();
        // a level stage for the line-up and the camera: grass at the origin's height, open sky above
        int y0 = o.getY();
        var grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
        var dirt = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int x = o.getX() - 22; x <= o.getX() + 2 * 60 + 22; x++) {
            for (int z = o.getZ() - 10; z <= o.getZ() + 16; z++) {
                for (int y = y0 - 4; y < y0 - 1; y++) level.setBlock(new BlockPos(x, y, z), dirt, 2);
                level.setBlock(new BlockPos(x, y0 - 1, z), grass, 2);
                for (int y = y0; y <= y0 + 24; y++) {
                    BlockPos q = new BlockPos(x, y, z);
                    if (!level.getBlockState(q).isAir()) level.setBlock(q, air, 2);
                }
            }
        }
        for (int g = 0; g < GROUPS.size(); g++) {
            List<MountKind> kinds = GROUPS.get(g);
            int x = o.getX() + g * 60 - (kinds.size() - 1) * 5 / 2;
            for (int i = 0; i < kinds.size(); i++) {
                MountKind k = kinds.get(i);
                int mx = x + i * (k == MountKind.MAMMOTH ? 7 : 5) + (i > 0 && kinds.get(0) == MountKind.MAMMOTH ? 3 : 0);
                int mz = o.getZ() + 10;
                // a level pad under each mount
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz);
                for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                    level.setBlockAndUpdate(new BlockPos(mx + dx, y - 1, mz + dz), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int dy = 0; dy < 6; dy++) level.setBlockAndUpdate(new BlockPos(mx + dx, y + dy, mz + dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
                Mob m = Mounts.create(level, k, mx + 0.5, y, mz + 0.5, 90f, p);
                if (m == null) continue;
                m.setNoAi(true);
                m.setYBodyRot(90f);
                m.setYHeadRot(90f);
                boolean barded = g == 0 || i % 2 == 1 || g == 2 && i == kinds.size() - 1;
                if (m instanceof Horse h) {
                    var item = switch (k) {
                        case WHITERUN_BAY -> MountItems.NORDIC_BARDING.get();
                        case CYRODILIC_BAY -> MountItems.IMPERIAL_BARDING.get();
                        case BRETON_DESTRIER -> MountItems.BRETON_BARDING.get();
                        case ALIKR_STEED -> MountItems.REDGUARD_BARDING.get();
                        case AURIDON_WHITE -> MountItems.ELVEN_BARDING.get();
                        default -> null;
                    };
                    if (item != null) h.getSlot(401).set(new ItemStack(item));
                } else if (m instanceof MountBeast b && barded) {
                    b.setVariant(2);
                }
                level.addFreshEntity(m);
            }
        }
        NirnCraft.LOGGER.info("Mounts autotest: lined up {} groups at {}", GROUPS.size(), o);
    }

    private static void look(ServerPlayer p, int g) {
        BlockPos o = origin != null ? origin : p.blockPosition();
        int x = o.getX() + g * 60 + 2;
        int z = o.getZ() - 4;
        int y = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 3;
        cmd(p, String.format(java.util.Locale.ROOT, "tp @p %d %d %d 0 12", x, y, z));
    }

    private static void ride(ServerPlayer p) {
        BlockPos o = origin != null ? origin : p.blockPosition();
        var list = p.level().getEntitiesOfClass(MountBeast.class, new net.minecraft.world.phys.AABB(o).inflate(160, 40, 160),
                b -> b.kind() == MountKind.SENCHE && b.getVariant() == 2);
        if (list.isEmpty()) return;
        MountBeast b = list.get(0);
        p.teleportTo(b.getX() - 3, b.getY() + 1, b.getZ());
        p.startRiding(b, true, true);
    }

    // ------------------------------------------------------------------ the homes

    private static void homes(Minecraft mc, int s) {
        int i = s / 4;
        if (i >= HOMES.length) {
            NirnCraft.LOGGER.info("=== NirnCraft Autotest: mounts tour finished. Closing client. ===");
            mc.stop();
            return;
        }
        String id = HOMES[i].trim();
        switch (s % 4) {
            case 0 -> {
                server(mc, p -> {
                    Houses.HouseDef h = Houses.byId(id);
                    if (h == null) return;
                    cmd(p, String.format(java.util.Locale.ROOT, "tp @p %d %d %d 0 30", h.approxPos().getX(), 180, h.approxPos().getZ() - 20));
                    NirnCraft.LOGGER.info("Mounts autotest: flying to home {} near {}", id, h.approxPos());
                });
                homeTries = 0;
                wait = 700;
            }
            case 1 -> {
                homeState = "";
                server(mc, p -> {
                    var site = Houses.site(p.level(), id);
                    if (site.isEmpty() || site.get().bed() == null) {
                        homeState = site.isEmpty() ? "no site" : "not built";
                        return;
                    }
                    BlockPos door = site.get().door();
                    BlockPos c = site.get().center();
                    // stand out in front of the door, looking at it
                    double dx = door.getX() - c.getX(), dz = door.getZ() - c.getZ();
                    double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                    double fx = door.getX() + dx / len * 12, fz = door.getZ() + dz / len * 12;
                    float yaw = (float) Math.toDegrees(Math.atan2(-(door.getX() - fx), door.getZ() - fz));
                    cmd(p, String.format(java.util.Locale.ROOT, "tp @p %.1f %d %.1f %.0f 14", fx + 0.5, door.getY() + 5, fz + 0.5, yaw));
                    homeState = "built";
                    NirnCraft.LOGGER.info("Mounts autotest: home {} built, door {}", id, door);
                });
                wait = 120;
            }
            case 2 -> {
                if (!"built".equals(homeState) && homeTries++ < 4) {
                    NirnCraft.LOGGER.info("Mounts autotest: home {} {} yet, waiting", id, homeState);
                    step -= 2; // back to the lookup
                    wait = 400;
                    return;
                }
                ClientAutotest.captureAndSave(mc, String.format("h%02d_%s_front", i, id));
                server(mc, p -> {
                    var site = Houses.site(p.level(), id);
                    BlockPos c = site.map(x -> x.center()).orElse(Houses.byId(id).approxPos());
                    cmd(p, String.format(java.util.Locale.ROOT, "tp @p %d %d %d 30 40", c.getX() - 12, c.getY() + 22, c.getZ() - 20));
                });
                wait = 100;
            }
            default -> ClientAutotest.captureAndSave(mc, String.format("h%02d_%s_air", i, id));
        }
    }
}
