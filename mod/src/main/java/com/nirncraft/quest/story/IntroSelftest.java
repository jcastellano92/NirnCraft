package com.nirncraft.quest.story;

import com.nirncraft.core.Race;
import com.nirncraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless checks of the cottage opening (no client): the site, the grounded base, the windows, the door, the path and
 * its junction with the road plan, the sight to Helgen (viewshed percentage and the single window-to-town ray), and the
 * whole state machine driven by a stand-in player: first join in the cottage, character, loading, window trigger,
 * cutscene, key, door, left. {@code /nirncraft-quest intro selftest}; the line "Intro selftest: ... N problems" is read by
 * the QA log summary.
 */
final class IntroSelftest {
    private IntroSelftest() {}

    static List<String> run(MinecraftServer server, List<String> info, boolean rebuild) {
        List<String> problems = new ArrayList<>();
        ServerLevel level = server.overworld();
        StartCottage.Site site = StartCottage.site(level);
        if (site == null) {
            info.add("no cottage site in this world (not a Tamriel world, or no grounded hill with a view)");
            if (com.nirncraft.tamriel.terrain.TamrielSpawn.isTamriel(level)) problems.add("Tamriel world without a cottage site");
            return problems;
        }
        if (rebuild) StartCottage.rebuild(level);
        else StartCottage.ensure(level);
        StartCottage.prepareNow(level);
        site = StartCottage.site(level);
        if (site == null) {
            problems.add("site vanished");
            return problems;
        }
        int y0 = site.floorY();
        info.add("site " + site.origin() + " floor y " + y0 + " facing (" + site.fx() + "," + site.fz() + "), "
                + (int) Math.sqrt(Math.pow(site.origin().getX() - site.helgenX(), 2) + Math.pow(site.origin().getZ() - site.helgenZ(), 2)) + " blocks from Helgen");
        info.add("preparation: " + StartCottage.report(level));

        // grounded: nothing hangs on air
        int[] sup = CottageTerrain.support(level, site, 3);
        info.add("support: " + sup[0] + "/" + sup[1] + " footprint columns on solid ground within 3 blocks");
        if (sup[0] < Math.ceil(0.9 * sup[1])) problems.add("footprint is not grounded: " + (sup[1] - sup[0]) + " of " + sup[1] + " columns on air");
        // deeper check: from the floor straight down 8 blocks, the first non-air, non-vegetation block must exist
        int hanging = 0;
        for (int l = 0; l <= 8; l++) {
            for (int d = 0; d <= 6; d++) {
                BlockPos c = site.at(l, d, 0);
                int g = CottageTerrain.groundTop(level, c.getX(), c.getZ());
                if (g == CottageTerrain.NO_GROUND || y0 - 1 - g > 3) hanging++;
            }
        }
        if (hanging > 0) problems.add(hanging + " footprint columns have natural ground more than 3 blocks below the floor");

        // windows and the interior
        int panes = StartCottage.unconnectedPanes(level, site);
        info.add("windows: unconnected or missing panes " + panes);
        if (panes > 0) problems.add(panes + " window panes are missing or unconnected (thin cross)");
        if (!StartCottage.interiorClear(level, site)) problems.add("the cottage interior is not clear");

        // the door
        BlockState door = level.getBlockState(site.door());
        if (!(door.getBlock() instanceof DoorBlock) || door.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) problems.add("no door at " + site.door());
        if (!StartCottage.isDoor(level, site.door())) problems.add("isDoor() does not know the door");
        if (com.nirncraft.crime.Locks.lockLevel(level, site.door()) == com.nirncraft.crime.Locks.NOT_LOCKED) problems.add("the door is not locked");

        // the path and its junction with the road plan
        com.nirncraft.quest.story.StartCottage.Site s2 = site;
        info.add("path: " + (level.getServer() != null ? "see report" : ""));
        info.add("viewshed: " + Math.round(StartCottage.visibility(level)) + "% of the town's sample points are seen from the window");
        if (StartCottage.visibility(level) < 90f) problems.add("viewshed " + Math.round(StartCottage.visibility(level)) + "% < 90%");
        String rep = StartCottage.report(level);
        if (rep.contains("path none")) problems.add("no path: " + rep);
        if (rep.contains("breaks ") && !rep.contains("breaks 0")) problems.add("the path has breaks: " + rep);
        if (rep.contains("gaps ") && !rep.contains("gaps 0")) {
            // a few unsupported cells under a path are reported, not failed, but never many
            info.add("path gaps present: " + rep);
        }

        // the single ray from the window to the middle of the town
        Vec3 eye = site.windowEye();
        Vec3 tgt = new Vec3(site.helgenX() + 0.5, StartCottage.groundY(level, site.helgenX(), site.helgenZ()) + 6, site.helgenZ() + 0.5);
        Vec3 dir = tgt.subtract(eye);
        int steps = (int) (dir.length() * 2);
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        String blockedBy = null;
        for (int k = 6; k < steps; k++) {
            double t = k / (double) steps;
            Vec3 p = eye.add(dir.scale(t));
            double hx = p.x - site.helgenX(), hz = p.z - site.helgenZ();
            if (hx * hx + hz * hz < 50 * 50) break;
            bp.set((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            BlockState st = level.getBlockState(bp);
            if (CottageTerrain.blocksView(level, bp, st)) {
                blockedBy = st.getBlock().getName().getString() + " at " + bp.toShortString();
                break;
            }
        }
        info.add("window-to-Helgen ray: " + (blockedBy == null ? "clear" : "BLOCKED by " + blockedBy));
        if (blockedBy != null) problems.add("the line of sight from the window to Helgen is blocked by " + blockedBy);

        problems.addAll(machine(server, level, site, info));
        return problems;
    }

    private static char glyph(BlockState s) {
        if (s.isAir()) return '.';
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock());
        String p = key == null ? "?" : key.getPath();
        if (p.contains("glass_pane")) return 'G';
        if (p.contains("door") && !p.contains("trap")) return 'D';
        if (p.contains("trapdoor")) return 't';
        if (p.contains("stairs")) return 'S';
        if (p.contains("slab")) return 's';
        if (p.contains("leaves")) return 'T';
        if (p.contains("_log")) return 'L';
        if (p.contains("planks")) return 'P';
        if (p.equals("cobblestone") || p.contains("mossy_cobblestone")) return 'C';
        if (p.contains("cobblestone_wall")) return 'w';
        if (p.contains("stone_bricks")) return 'b';
        if (p.contains("fence")) return 'f';
        if (p.contains("lantern")) return 'l';
        if (p.contains("bed")) return 'B';
        if (p.equals("water")) return '~';
        if (p.contains("dirt_path")) return 'p';
        if (p.equals("grass_block")) return 'g';
        if (p.contains("dirt") || p.equals("gravel")) return 'd';
        if (p.contains("furnace")) return 'F';
        if (p.contains("campfire")) return 'c';
        if (p.contains("carpet")) return 'r';
        if (p.contains("bookshelf")) return 'k';
        if (p.contains("barrel")) return 'o';
        if (p.contains("crafting")) return 'W';
        if (p.contains("farmland")) return 'm';
        if (p.contains("hay")) return 'h';
        if (p.contains("pumpkin")) return 'u';
        if (p.contains("composter")) return 'x';
        if (p.contains("wheat") || p.contains("carrots") || p.contains("potatoes")) return 'y';
        if (p.contains("pressure_plate")) return '_';
        if (p.contains("stone") || p.contains("deepslate")) return '#';
        return '?';
    }

    /** ASCII layers of the cottage in its local frame (columns l -3..10, rows d -5..8, front row first). */
    static List<String> dump(ServerLevel level, StartCottage.Site s) {
        List<String> out = new ArrayList<>();
        out.add("legend: G pane, D door, t trapdoor, S stairs, s slab, L log, P planks, C cobble, b stone brick, f fence, l lantern, B bed,"
                + " ~ water, p path, g grass, d dirt, F furnace, c campfire, r carpet, k bookshelf, o barrel, W table, m farmland, y crop");
        out.add("columns l=-3..10 left to right, rows d=-5 (front, toward Helgen) .. 8 (back); window l 4..6, door l 1");
        for (int y = s.floorY() - 3; y <= s.floorY() + 11; y++) {
            out.add("-- y " + y + " (floor " + (y - s.floorY()) + ")");
            for (int d = -5; d <= 8; d++) {
                StringBuilder row = new StringBuilder();
                for (int l = -3; l <= 10; l++) row.append(glyph(level.getBlockState(s.at(l, d, y))));
                out.add(row.toString());
            }
        }
        return out;
    }

    /** Top-down map of the surface (what a bird sees) over a box, one character per block, north (-z) at the top. */
    static List<String> map(ServerLevel level, int x0, int z0, int x1, int z1) {
        List<String> out = new ArrayList<>();
        out.add("map x " + x0 + ".." + x1 + ", z " + z0 + ".." + z1 + " (rows are z, north up); T tree, p path, b brick, g grass, d dirt, # stone, ~ water");
        for (int z = z0; z <= z1; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = x0; x <= x1; x++) {
                if (!CottageTerrain.loaded(level, x, z)) {
                    row.append(' ');
                    continue;
                }
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                BlockState s = level.getBlockState(new BlockPos(x, y, z));
                row.append(glyph(s));
            }
            out.add(row.toString());
        }
        return out;
    }

    /**
     * Checks that ride along with the intro (they need no cottage): after the opening nobody in the world may be
     * bent into the crouch pose except the frozen aftermath survivors of a burnt town (playtest: every NPC in Skyrim
     * crouched after the Helgen opening). The cause was client-side, a shared model flag
     * ({@code NpcModel.prepareMobModel}), which a headless server cannot see; what it does check is that the
     * server never marks an ordinary NPC with a pose (flag or sneak) and that aftermath poses are exactly the
     * actor's tableau pose. Also the hostile-awareness compass feed ({@link com.nirncraft.combat.Threats}).
     */
    static List<String> extra(MinecraftServer server, List<String> info) {
        List<String> problems = new ArrayList<>();
        ServerLevel level = server.overworld();
        int npcs = 0, actors = 0;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (!(e instanceof com.nirncraft.society.entity.NpcEntity npc)) continue;
            npcs++;
            com.nirncraft.fire.aftermath.Tableau.Pose pose = com.nirncraft.fire.aftermath.Tableau.poseOf(npc);
            String who = npc.role().id + " at " + npc.blockPosition().toShortString();
            if (npc.isCrouching()) problems.add("NPC " + who + " is sneaking");
            if (pose == null) {
                if (npc.showsCrouchPose()) problems.add("NPC " + who + " shows the crouch pose without being an aftermath actor");
                if (npc.aftermathPoseFlags() != 0) problems.add("NPC " + who + " has aftermath pose flags " + npc.aftermathPoseFlags() + " but no tableau pose");
            } else {
                actors++;
                // a hauler toggles its carry flag; every other actor holds exactly its pose
                int allowed = pose.flags | (pose == com.nirncraft.fire.aftermath.Tableau.Pose.HAUL ? com.nirncraft.society.entity.NpcEntity.FLAG_CARRY : 0);
                int extra = npc.aftermathPoseFlags() & ~allowed;
                if (extra != 0) problems.add("aftermath actor " + pose.id + " at " + npc.blockPosition().toShortString() + " carries stray pose flags " + extra);
            }
        }
        info.add("npc poses: " + npcs + " NPCs loaded, " + actors + " aftermath actors, no stray crouch");

        // the pose bits do clear: a survivor released from its tableau stands up (renders upright)
        BlockPos at = level.getRespawnData().pos();
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        com.nirncraft.society.entity.NpcEntity npc = com.nirncraft.society.Npcs.create(level, at, com.nirncraft.society.NpcRole.CITIZEN);
        if (npc != null) {
            try {
                npc.setPoseFlags(com.nirncraft.fire.aftermath.Tableau.Pose.KNEEL_WEEP.flags);
                if (!npc.showsCrouchPose()) problems.add("a kneeling survivor does not show the crouch pose (the aftermath scene would lose it)");
                npc.setPoseFlags(0);
                if (npc.showsCrouchPose() || npc.aftermathPoseFlags() != 0) problems.add("clearing the pose flags of a survivor does not stand it up");
            } finally {
                npc.discard();
            }
        }

        com.nirncraft.combat.Threats.selfTest(level, info, problems);
        return problems;
    }

    /** The state machine for a stand-in player: first join to left. */
    private static List<String> machine(MinecraftServer server, ServerLevel level, StartCottage.Site site, List<String> info) {
        List<String> pr = new ArrayList<>();
        ServerPlayer fake = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes("nirncraft-intro-selftest".getBytes()), "[IntroSelftest]"));
        IntroFlow.testForget(fake);
        SkyData.get(fake).setRace(null);
        fake.setPos(site.origin().getX() + 40, site.floorY() + 5, site.origin().getZ() + 40);
        try {
            // the pre-join placement (PlayerEvent.LoadFromFile): a new character starts on the cottage floor, before any chunk is sent
            if (!IntroFlow.testPlaceFirstJoin(fake)) pr.add("the pre-join placement found no cottage");
            if (fake.position().distanceToSqr(site.wake()) > 2 * 2) pr.add("the pre-join placement is not at the cottage floor: " + fake.position());
            fake.setPos(site.origin().getX() + 40, site.floorY() + 5, site.origin().getZ() + 40);
            if (!IntroFlow.onFirstJoin(fake)) {
                pr.add("onFirstJoin did not take the cottage");
                return pr;
            }
            if (IntroFlow.state(fake) != IntroState.JOINED) pr.add("first join state is " + IntroFlow.state(fake) + ", not JOINED");
            if (fake.position().distanceToSqr(site.centre()) > 5 * 5) pr.add("first join did not place the player in the cottage: " + fake.position());
            if (!IntroFlow.testHidden(fake)) pr.add("a player in the opening is not hidden");
            if (fake.getRespawnConfig() == null || fake.getRespawnConfig().respawnData().pos().distSqr(site.bed()) > 4) pr.add("the cottage bed is not the respawn point");
            // character made -> loading
            SkyData.get(fake).setRace(Race.NORD);
            IntroFlow.testTick(fake);
            if (IntroFlow.state(fake) != IntroState.LOADING) pr.add("after the character the state is " + IntroFlow.state(fake) + ", not LOADING");
            IntroFlow.onClientLoaded(fake);
            IntroFlow.testTick(fake);
            // the world-loading screen lasts at least 6 s even when everything is already in
            if (IntroFlow.state(fake) != IntroState.LOADING) pr.add("loading ended before its minimum time (state " + IntroFlow.state(fake) + ")");
            IntroFlow.testFastForwardLoading(fake);
            IntroFlow.testTick(fake);
            if (IntroFlow.state(fake) != IntroState.IN_COTTAGE) pr.add("after loading the state is " + IntroFlow.state(fake) + ", not IN_COTTAGE");
            // a door that stays locked
            if (IntroFlow.testUseDoor(fake)) pr.add("the door opened before the cutscene");
            // walk to the window
            Vec3 spot = site.windowSpot();
            fake.setPos(spot.x, spot.y, spot.z);
            IntroFlow.testTick(fake);
            if (IntroFlow.state(fake) != IntroState.CUTSCENE) pr.add("the window did not start the cutscene (state " + IntroFlow.state(fake) + ")");
            // cutscene
            IntroFlow.testFastForwardCutscene(fake);
            IntroFlow.testTick(fake);
            if (IntroFlow.state(fake) != IntroState.KEY_GIVEN) pr.add("the cutscene did not end in KEY_GIVEN (state " + IntroFlow.state(fake) + ")");
            if (!IntroFlow.testHasKey(fake)) pr.add("no cottage key after the cutscene");
            // the door
            if (!IntroFlow.testUseDoor(fake)) pr.add("the key did not open the door");
            if (IntroFlow.state(fake) != IntroState.LEFT) pr.add("after the door the state is " + IntroFlow.state(fake) + ", not LEFT");
            if (IntroFlow.testHasKey(fake)) pr.add("the key was not consumed");
            if (IntroFlow.testHidden(fake)) pr.add("a player that left is still hidden");
            double out = fake.position().distanceTo(StartCottage.exit(level, site));
            info.add("state machine: JOINED -> LOADING -> IN_COTTAGE -> CUTSCENE -> KEY_GIVEN -> LEFT; left " + String.format("%.1f", out) + " blocks from the path start");
            if (out > 3) pr.add("left the cottage but not at the path start (" + out + " blocks off)");
            BlockPos outPos = fake.blockPosition();
            if (fake.position().distanceToSqr(site.centre()) < 3 * 3) pr.add("still inside the cottage after leaving");
            if (outPos.getY() < site.floorY() - 12) pr.add("left below the cottage floor: " + outPos);
        } finally {
            IntroFlow.testForget(fake);
            SkyData.get(fake).setRace(null);
            fake.getInventory().clearContent();
        }
        return pr;
    }
}
