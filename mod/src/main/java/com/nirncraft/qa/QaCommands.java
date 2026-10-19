package com.nirncraft.qa;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.nirncraft.NirnCraft;
import com.nirncraft.landmarks.LandmarkSite;
import com.nirncraft.landmarks.Landmarks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The online side of the harness's tool validation ("online feature index"): what the real world generated around a
 * point, to compare with what the offline tools predicted (exact heights, biomes, seeded landmarks).
 * <pre>  /nirncraft-qa sample &lt;x&gt; &lt;z&gt; &lt;radius&gt; &lt;step&gt;</pre>
 * Generates the chunks of a (2r/step+1)^2 grid if needed and writes {@code nirncraft-qa/sample.json} in the server
 * folder: per point the surface heights (MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR, WORLD_SURFACE) and the biome id, and
 * every seeded landmark in the square (Landmarks.inRect, the same enumerator the far field uses). Host / operator only.
 */
public final class QaCommands {
    private QaCommands() {}

    public static void init() {
        QaRecorder.init();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                Commands.literal("nirncraft-qa").requires(src -> Tour.host(src)  // the host, or the server itself
                                || !(src.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
                                && Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src))
                        .then(Commands.literal("entities").executes(c -> entities(c.getSource())))
                        .then(Commands.literal("subjects").executes(c -> subjects(c.getSource())))
                        .then(Commands.literal("record")
                                .then(Commands.literal("start")
                                        .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .then(Commands.argument("centre", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
                                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                                                                .then(Commands.argument("every", IntegerArgumentType.integer(1, 200))
                                                                        .executes(c -> {
                                                                            QaRecorder.start(c.getSource().getLevel(),
                                                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name"),
                                                                                    net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "centre"),
                                                                                    IntegerArgumentType.getInteger(c, "radius"),
                                                                                    IntegerArgumentType.getInteger(c, "every"));
                                                                            return 1;
                                                                        }))))))
                                .then(Commands.literal("watch")
                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                .executes(c -> {
                                                    QaRecorder.watch(net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"));
                                                    return 1;
                                                })))
                                .then(Commands.literal("stop").executes(c -> {
                                    try {
                                        var out = QaRecorder.stop(c.getSource().getServer());
                                        c.getSource().sendSuccess(() -> Component.literal("qa record -> " + out.getFileName()), false);
                                        return 1;
                                    } catch (java.io.IOException | IllegalStateException ex) {
                                        c.getSource().sendFailure(Component.literal("record stop: " + ex.getMessage()));
                                        return 0;
                                    }
                                })))
                        .then(Commands.literal("npc")
                                .then(Commands.argument("role", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(Commands.argument("race", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                        .executes(c -> npc(c.getSource(),
                                                                com.mojang.brigadier.arguments.StringArgumentType.getString(c, "role"),
                                                                com.mojang.brigadier.arguments.StringArgumentType.getString(c, "race"),
                                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos")))))))
                        .then(Commands.literal("building")
                                .then(Commands.argument("culture", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(Commands.argument("use", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .then(Commands.argument("wealth", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                                .executes(c -> building(c.getSource(),
                                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "culture"),
                                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "use"),
                                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "wealth"),
                                                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"))))))))
                        .then(Commands.literal("sample")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 512))
                                                        .then(Commands.argument("step", IntegerArgumentType.integer(1, 256))
                                                                .executes(c -> sample(c.getSource(),
                                                                        IntegerArgumentType.getInteger(c, "x"),
                                                                        IntegerArgumentType.getInteger(c, "z"),
                                                                        IntegerArgumentType.getInteger(c, "radius"),
                                                                        IntegerArgumentType.getInteger(c, "step"))))))))));
    }

    /**
     * {@code /nirncraft-qa entities}: what every NirnCraft entity type is, from the game itself (the harness gallery
     * reads it instead of guessing from source): kind boss / monster / creature / water / npc / vehicle / misc, its
     * MobCategory and size. Writes {@code nirncraft-qa/entities.json}. Instances are created to inspect, never added.
     */
    static int entities(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        JsonArray out = new JsonArray();
        for (var type : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) {
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!"nirncraft".equals(key.getNamespace())) continue;
            JsonObject e = new JsonObject();
            e.addProperty("id", key.getPath());
            e.addProperty("category", type.getCategory().getName());
            e.addProperty("width", type.getDimensions().width());
            e.addProperty("height", type.getDimensions().height());
            String kind = "misc";
            try {
                var entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (entity != null) {
                    boolean boss = false;
                    for (Class<?> c = entity.getClass(); c != null && !boss; c = c.getSuperclass())
                        for (var f : c.getDeclaredFields())
                            if (net.minecraft.server.level.ServerBossEvent.class.isAssignableFrom(f.getType())) boss = true;
                    boolean water = entity instanceof net.minecraft.world.entity.animal.fish.WaterAnimal
                            || type.getCategory().getName().contains("water");
                    kind = boss ? "boss"
                            : entity instanceof com.nirncraft.society.entity.NpcEntity
                              || entity.getClass().getSimpleName().startsWith("Guard") ? "npc"
                            : entity instanceof com.nirncraft.sailing.ShipEntity
                              || entity instanceof net.minecraft.world.entity.vehicle.VehicleEntity ? "vehicle"
                            : entity instanceof com.nirncraft.mounts.MountBeast ? "mount"
                            : entity instanceof net.minecraft.world.entity.monster.Enemy ? "monster"
                            : water ? (type.getDimensions().width() < 1.0f ? "fish" : "water")
                            : entity instanceof net.minecraft.world.entity.PathfinderMob ? "creature" : "misc";
                    e.addProperty("multipart", entity.isMultipartEntity());
                    e.addProperty("class", entity.getClass().getSimpleName());
                    entity.discard();
                }
            } catch (RuntimeException ex) {
                e.addProperty("error", String.valueOf(ex.getMessage()));
            }
            if (!e.has("class")) {  // no instance (e.g. a monster in peaceful): its registered category decides
                String cat = type.getCategory().getName();
                kind = cat.equals("monster") ? "monster" : cat.contains("water") ? "water"
                        : cat.equals("creature") || cat.equals("ambient") ? "creature" : "misc";
                e.addProperty("kindFrom", "category");
            }
            e.addProperty("kind", kind);
            out.add(e);
        }
        try {
            Path dir = src.getServer().getServerDirectory().resolve("nirncraft-qa");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("entities.json"), new GsonBuilder().create().toJson(out), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            src.sendFailure(Component.literal("Could not write nirncraft-qa/entities.json: " + ex.getMessage()));
            return 0;
        }
        final int n = out.size();
        src.sendSuccess(() -> Component.literal("qa entities: " + n + " types -> nirncraft-qa/entities.json"), false);
        return Math.max(1, n);
    }

    /**
     * {@code /nirncraft-qa subjects}: every testable subject the game defines, by kind, from the registries themselves
     * (the harness generates scenes, cards and gap reports from it, so a new type is covered without a hand list).
     * Writes {@code nirncraft-qa/subjects.json}: entities (as {@code entities}), races, NPC races and roles, blocks and
     * items by kind, ingredients (with effects), fish, spells, trees, furniture kinds, building uses / cultures /
     * wealth tiers and landmark definitions.
     */
    static int subjects(CommandSourceStack src) {
        entities(src);  // writes entities.json too
        JsonObject root = new JsonObject();
        try {
            root.add("entities", com.google.gson.JsonParser.parseString(Files.readString(
                    src.getServer().getServerDirectory().resolve("nirncraft-qa/entities.json"))));
        } catch (java.io.IOException | RuntimeException ex) {
            root.add("entities", new JsonArray());
        }
        JsonArray races = new JsonArray();
        for (var r : com.nirncraft.core.Race.VALUES) races.add(r.id());
        root.add("races", races);
        JsonArray npcRaces = new JsonArray();
        for (var r : com.nirncraft.society.NpcNames.Race.VALUES) npcRaces.add(r.name().toLowerCase(java.util.Locale.ROOT));
        root.add("npcRaces", npcRaces);
        JsonArray roles = new JsonArray();
        for (var r : com.nirncraft.society.NpcRole.VALUES) roles.add(r.id);
        root.add("roles", roles);

        java.util.Set<String> ingredientIds = new java.util.HashSet<>();
        JsonArray ingredients = new JsonArray();
        for (var in : com.nirncraft.crafting.arcane.alchemy.Ingredients.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", in.id());
            JsonArray fx = new JsonArray();
            for (var effect : in.effects()) fx.add(effect.id());
            o.add("effects", fx);
            ingredients.add(o);
            try {  // items are matched by the ingredient's item (its id can differ from the item id)
                var itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(in.item());
                o.addProperty("item", itemKey.toString());
                ingredientIds.add(itemKey.getPath());
            } catch (RuntimeException ignored) {
                ingredientIds.add(in.id());
            }
        }
        root.add("ingredients", ingredients);
        java.util.Set<String> fishIds = new java.util.HashSet<>();
        JsonArray fish = new JsonArray();
        for (var f : com.nirncraft.survival.fishing.FishSpecies.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.id());
            o.addProperty("rarity", f.rarity());
            fish.add(o);
            fishIds.add(f.id());
        }
        root.add("fish", fish);

        JsonArray blocks = new JsonArray();
        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if (!"nirncraft".equals(key.getNamespace())) continue;
            String kind = block instanceof com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock
                    || block instanceof com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlock ? "furniture"
                    : block instanceof net.minecraft.world.level.block.CropBlock ? "crop"
                    : block instanceof net.minecraft.world.level.block.SaplingBlock ? "sapling"
                    : block instanceof com.nirncraft.crafting.arcane.block.IngredientPlantBlock
                      || block instanceof net.minecraft.world.level.block.FlowerBlock
                      || block instanceof net.minecraft.world.level.block.BushBlock ? "flora"
                    : block instanceof net.minecraft.world.level.block.DoorBlock ? "door"
                    : block.defaultBlockState().getLightEmission() > 0 ? "light" : "block";
            JsonObject o = new JsonObject();
            o.addProperty("id", key.getPath());
            o.addProperty("kind", kind);
            blocks.add(o);
        }
        root.add("blocks", blocks);

        JsonArray items = new JsonArray();
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (!"nirncraft".equals(key.getNamespace())) continue;
            var stack = new net.minecraft.world.item.ItemStack(item);
            String id = key.getPath(), kind;
            if (ingredientIds.contains(id)) kind = "ingredient";
            else if (fishIds.contains(id)) kind = "fish";
            else if (item.getClass().getSimpleName().contains("SpawnEgg")) kind = "spawn_egg";
            else if (stack.has(net.minecraft.core.component.DataComponents.WEAPON)) kind = "weapon";
            else if (stack.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)) kind = "armour";
            else if (stack.has(net.minecraft.core.component.DataComponents.TOOL)) kind = "tool";
            else if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) kind = "food";
            else if (item instanceof net.minecraft.world.item.BlockItem) kind = "block_item";
            else kind = "item";
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", kind);
            var eq = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            if (eq != null) o.addProperty("slot", eq.slot().getName());  // head / chest / legs / feet / body / ...
            items.add(o);
        }
        root.add("items", items);

        JsonArray spells = new JsonArray();
        for (var s : com.nirncraft.magic.spell.Spells.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id);
            o.addProperty("school", String.valueOf(s.school).toLowerCase(java.util.Locale.ROOT));
            o.addProperty("tier", String.valueOf(s.tier).toLowerCase(java.util.Locale.ROOT));
            o.addProperty("type", String.valueOf(s.type).toLowerCase(java.util.Locale.ROOT));
            o.addProperty("cost", s.cost);
            spells.add(o);
        }
        root.add("spells", spells);
        JsonArray trees = new JsonArray();
        for (String t : com.nirncraft.tamriel.biome.flora.TreeSpecies.all().keySet()) trees.add(t);
        root.add("trees", trees);
        JsonArray furniture = new JsonArray();
        for (var k : com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.VALUES) furniture.add(k.id);
        root.add("furnitureKinds", furniture);
        JsonObject buildings = new JsonObject();
        JsonArray uses = new JsonArray(), cultures = new JsonArray(), wealth = new JsonArray();
        for (var u : com.nirncraft.tamriel.settlement.grammar.Use.VALUES) uses.add(u.id());
        for (var c : com.nirncraft.tamriel.settlement.Culture.VALUES) cultures.add(c.id());
        for (var w : com.nirncraft.tamriel.settlement.grammar.Wealth.VALUES) wealth.add(w.id());
        buildings.add("uses", uses);
        buildings.add("cultures", cultures);
        buildings.add("wealth", wealth);
        root.add("buildings", buildings);
        JsonArray landmarks = new JsonArray();
        for (var d : com.nirncraft.tamriel.settlement.build.LandmarkRegistry.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id());
            o.addProperty("w", d.w());
            o.addProperty("d", d.d());
            o.addProperty("h", d.h());
            landmarks.add(o);
        }
        root.add("landmarks", landmarks);
        JsonArray weather = new JsonArray();  // per region: its climate, the weather it can have, a place to look at it
        for (var region : com.nirncraft.tamriel.Regions.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("region", region.id());
            var profile = com.nirncraft.atmosphere.weather.WeatherProfile.of(region);
            o.addProperty("profile", profile.id);
            JsonArray kinds = new JsonArray();
            profile.table().forEach((k, w) -> { if (w > 0) kinds.add(k.name().toLowerCase(java.util.Locale.ROOT)); });
            o.add("kinds", kinds);
            com.nirncraft.tamriel.Place at = null;
            for (var pl : com.nirncraft.tamriel.Places.all()) {
                if (!region.id().equals(pl.regionId())) continue;
                if (at == null || pl.id().equals(region.capital())) at = pl;
            }
            if (at != null) {
                o.addProperty("place", at.id());
                o.addProperty("x", at.x());
                o.addProperty("z", at.z());
            }
            weather.add(o);
        }
        root.add("weather", weather);
        JsonArray landmarkKinds = new JsonArray();
        for (var k : com.nirncraft.landmarks.Kinds.all()) landmarkKinds.add(k.id());
        root.add("landmarkKinds", landmarkKinds);
        try {
            Path dir = src.getServer().getServerDirectory().resolve("nirncraft-qa");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("subjects.json"), new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            src.sendFailure(Component.literal("Could not write nirncraft-qa/subjects.json: " + ex.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("qa subjects -> nirncraft-qa/subjects.json"), false);
        return 1;
    }

    /** {@code /nirncraft-qa npc ROLE RACE x y z}: an NPC of that role and race, standing still, for the gallery. */
    static int npc(CommandSourceStack src, String role, String race, net.minecraft.core.BlockPos pos) {
        var r = com.nirncraft.society.NpcRole.byId(role);
        com.nirncraft.society.NpcNames.Race nr = null;
        for (var x : com.nirncraft.society.NpcNames.Race.VALUES) if (x.name().equalsIgnoreCase(race)) nr = x;
        if (r == null || nr == null) {
            src.sendFailure(Component.literal("unknown role or race: " + role + " " + race));
            return 0;
        }
        var mob = com.nirncraft.society.Npcs.spawn(src.getLevel(), pos, r.id);
        if (mob == null) {
            src.sendFailure(Component.literal("could not spawn " + role));
            return 0;
        }
        if (mob instanceof com.nirncraft.society.entity.NpcEntity n) {
            n.setRole(r);
            n.setRace(nr);
        }
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.setYRot(180f);
        mob.setYHeadRot(180f);
        mob.setYBodyRot(180f);
        src.sendSuccess(() -> Component.literal("qa npc " + role + " " + race), false);
        return 1;
    }

    /**
     * {@code /nirncraft-qa building CULTURE USE WEALTH x y z}: one building on a grass platform; writes
     * {@code nirncraft-qa/building.json} (its box and an interior viewpoint) and its isometric / top views.
     */
    static int building(CommandSourceStack src, String culture, String use, String wealth, net.minecraft.core.BlockPos pos) {
        var cul = com.nirncraft.tamriel.settlement.Culture.of(culture);
        var u = com.nirncraft.tamriel.settlement.grammar.Use.byId(use);
        com.nirncraft.tamriel.settlement.grammar.Wealth w = null;
        for (var x : com.nirncraft.tamriel.settlement.grammar.Wealth.VALUES) if (x.id().equals(wealth)) w = x;
        if (cul == null || u == null || w == null) {
            src.sendFailure(Component.literal("unknown culture / use / wealth: " + culture + " " + use + " " + wealth));
            return 0;
        }
        ServerLevel level = src.getLevel();
        var box = com.nirncraft.tamriel.settlement.SettlementDevCommands.placeBuilding(level, cul, u, w,
                pos.getX(), pos.getY(), pos.getZ(), 0);
        JsonObject o = new JsonObject();
        o.addProperty("culture", cul.id());
        o.addProperty("use", u.id());
        o.addProperty("wealth", w.id());
        JsonArray min = new JsonArray(), max = new JsonArray(), inside = new JsonArray();
        min.add(box.minX()); min.add(box.minY()); min.add(box.minZ());
        max.add(box.maxX()); max.add(box.maxY()); max.add(box.maxZ());
        inside.add((box.minX() + box.maxX()) / 2.0); inside.add(pos.getY() + 1.0); inside.add((box.minZ() + box.maxZ()) / 2.0);
        o.add("min", min);
        o.add("max", max);
        o.add("inside", inside);
        // the real front door: a door's lower half on the box's outer wall, the one furthest out (south-facing
        // buildings: the largest z); its outward direction points away from the building's centre
        net.minecraft.core.BlockPos door = null;
        double best = -1e9, cx = (box.minX() + box.maxX()) / 2.0, cz = (box.minZ() + box.maxZ()) / 2.0;
        for (var bp : net.minecraft.core.BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            var st = level.getBlockState(bp);
            if (!(st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                    || st.getValue(net.minecraft.world.level.block.DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                continue;
            double out = Math.max(Math.abs(bp.getX() - cx) / Math.max(1, box.getXSpan()), Math.abs(bp.getZ() - cz) / Math.max(1, box.getZSpan()))
                    + (bp.getZ() - cz) * 1e-3;  // ties: the south side (the street side of the grammar's buildings)
            if (out > best) { best = out; door = bp.immutable(); }
        }
        if (door != null) {
            JsonArray d = new JsonArray();
            d.add(door.getX()); d.add(door.getY()); d.add(door.getZ());
            o.add("door", d);
            double ox = door.getX() - cx, oz = door.getZ() - cz;  // outward: the axis the door sits out on
            JsonArray outward = new JsonArray();
            if (Math.abs(ox) / Math.max(1, box.getXSpan()) > Math.abs(oz) / Math.max(1, box.getZSpan())) {
                outward.add(Math.signum(ox)); outward.add(0);
            } else {
                outward.add(0); outward.add(Math.signum(oz));
            }
            o.add("outward", outward);
        }
        try {
            Path dir = src.getServer().getServerDirectory().resolve("nirncraft-qa");
            Files.createDirectories(dir);
            String name = "building_" + cul.id() + "_" + u.id() + "_" + w.id();
            com.nirncraft.tamriel.settlement.SettlementPreview.iso(level, box.minX() - 3, box.minZ() - 3, box.maxX() + 3,
                    box.maxZ() + 3, 4, dir.resolve(name + "_iso.png").toFile());
            com.nirncraft.tamriel.settlement.SettlementPreview.top(level, box.minX() - 3, box.minZ() - 3, box.maxX() + 3,
                    box.maxZ() + 3, 4, dir.resolve(name + "_top.png").toFile());
            o.addProperty("iso", name + "_iso.png");
            o.addProperty("top", name + "_top.png");
            Files.writeString(dir.resolve("building.json"), new GsonBuilder().create().toJson(o), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            src.sendFailure(Component.literal("Could not write nirncraft-qa/building.json: " + ex.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("qa building " + culture + " " + use + " " + wealth), false);
        return 1;
    }

    static int sample(CommandSourceStack src, int x, int z, int radius, int step) {
        ServerLevel level = src.getServer().overworld();
        JsonObject root = new JsonObject();
        root.addProperty("x", x);
        root.addProperty("z", z);
        root.addProperty("radius", radius);
        root.addProperty("step", step);
        root.addProperty("seed", level.getSeed());
        JsonArray points = new JsonArray();
        for (int px = x - radius; px <= x + radius; px += step) {
            for (int pz = z - radius; pz <= z + radius; pz += step) {
                level.getChunk(px >> 4, pz >> 4);  // generated (or loaded) for real
                JsonObject p = new JsonObject();
                p.addProperty("x", px);
                p.addProperty("z", pz);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                p.addProperty("motionNoLeaves", top);
                p.addProperty("oceanFloor", level.getHeight(Heightmap.Types.OCEAN_FLOOR, px, pz));
                p.addProperty("worldSurface", level.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz));
                p.addProperty("biome", level.getBiome(new BlockPos(px, top, pz)).unwrapKey()
                        .map(k -> k.identifier().toString()).orElse("?"));
                points.add(p);
            }
        }
        root.add("points", points);
        JsonArray marks = new JsonArray();
        for (LandmarkSite s : Landmarks.inRect(level.getSeed(), x - radius, z - radius, x + radius, z + radius)) {
            JsonObject m = new JsonObject();
            m.addProperty("kind", String.valueOf(s.kind()).toLowerCase(java.util.Locale.ROOT));
            m.addProperty("x", s.x());
            m.addProperty("z", s.z());
            marks.add(m);
        }
        root.add("landmarks", marks);
        try {
            Path dir = src.getServer().getServerDirectory().resolve("nirncraft-qa");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("sample.json"), new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            NirnCraft.LOGGER.error("[qa] sample not written", e);
            src.sendFailure(Component.literal("Could not write nirncraft-qa/sample.json: " + e.getMessage()));
            return 0;
        }
        final int n = points.size(), m = marks.size();
        src.sendSuccess(() -> Component.literal("qa sample: " + n + " points, " + m + " landmarks -> nirncraft-qa/sample.json"), false);
        return Math.max(1, n);
    }
}
