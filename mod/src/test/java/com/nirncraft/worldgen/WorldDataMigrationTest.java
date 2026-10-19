package com.nirncraft.worldgen;

import com.google.gson.JsonParser;
import com.nirncraft.testing.BoundedServerProvider;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.neoforged.neoforge.resource.NeoForgeReloadListeners;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/** Native reload validation: malformed optional resources must not silently disappear. */
@ExtendWith(BoundedServerProvider.class)
class WorldDataMigrationTest {
    private static com.google.gson.JsonElement authoredJson(String path) throws Exception {
        try (var input = WorldDataMigrationTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    private static int checkPaletteBlocks(com.google.gson.JsonElement value, String origin) throws Exception {
        if (value == null || value.isJsonNull()) return 0;
        int count = 0;
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) count += checkPaletteBlocks(entry.getValue(), origin + "/" + entry.getKey());
        } else if (value.isJsonArray()) {
            for (var entry : value.getAsJsonArray()) count += checkPaletteBlocks(entry, origin);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (text.startsWith("minecraft:") || text.startsWith("nirncraft:")) {
                String block = text.split("[@*]", 2)[0];
                assertDoesNotThrow(() -> net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                        BuiltInRegistries.BLOCK, block, false), origin + ": " + text);
                count++;
            }
        }
        return count;
    }

    @Test void everyAuthoredCavernAndBlueprintPaletteUsesRealNativeBlockStates(MinecraftServer server) throws Exception {
        String cavern = "/nirncraft/dungeons/caverns/";
        var index = authoredJson(cavern + "index.json").getAsJsonObject();
        int profiles = 0, blocks = 0;
        for (var file : index.getAsJsonArray("profiles")) {
            String path = cavern + file.getAsString();
            for (var element : authoredJson(path).getAsJsonArray()) {
                var profile = element.getAsJsonObject();
                for (String field : new String[]{"palette", "flora", "features", "fluid", "lights", "setpiece"}) {
                    blocks += checkPaletteBlocks(profile.get(field), path + "/" + profile.get("id").getAsString() + "/" + field);
                }
                profiles++;
            }
        }
        String blueprints = "/nirncraft/dungeons/blueprints/";
        for (var file : authoredJson(blueprints + "index.json").getAsJsonArray()) {
            String path = blueprints + file.getAsString();
            blocks += checkPaletteBlocks(authoredJson(path).getAsJsonObject().get("key"), path + "/key");
        }
        assertTrue(profiles >= 60, "Keep the full authored cavern library");
        assertTrue(blocks >= 1000, "Validate each palette entry rather than only the moved examples");
    }

    private static <T> T registered(MinecraftServer server, ResourceKey<? extends Registry<T>> registry, String id) {
        return server.registryAccess().lookupOrThrow(registry)
                .getOrThrow(ResourceKey.create(registry, Identifier.parse(id))).value();
    }

    @Test void allEightDimensionTypesKeepThePreservedLegacyGameplayFieldsAndBounds(MinecraftServer server) throws Exception {
        try (var input = WorldDataMigrationTest.class.getResourceAsStream("/worldgen/dimension-types-legacy-1.20.1.json")) {
            assertNotNull(input);
            var dimensions = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(8, dimensions.size());
            for (var entry : dimensions.entrySet()) {
                String id = entry.getKey();
                var old = entry.getValue().getAsJsonObject();
                var type = registered(server, Registries.DIMENSION_TYPE, id);
                assertEquals(old.get("ambient_light").getAsFloat(), type.ambientLight(), id);
                assertEquals(old.get("coordinate_scale").getAsDouble(), type.coordinateScale(), id);
                assertEquals(old.get("has_ceiling").getAsBoolean(), type.hasCeiling(), id);
                assertEquals(old.get("has_skylight").getAsBoolean(), type.hasSkyLight(), id);
                assertEquals(old.get("height").getAsInt(), type.height(), id);
                assertEquals(old.get("logical_height").getAsInt(), type.logicalHeight(), id);
                assertEquals(old.get("min_y").getAsInt(), type.minY(), id);
                assertEquals(old.has("fixed_time"), type.hasFixedTime(), id);
                assertEquals(id.equals("minecraft:the_end"), type.hasEnderDragonFight(), id);
                assertEquals(old.get("infiniburn").getAsString().substring(1),
                        type.infiniburn().unwrapKey().orElseThrow().location().toString(), id);
                assertEquals(old.get("monster_spawn_block_light_limit").getAsInt(), type.monsterSpawnBlockLightLimit(), id);
                var light = old.get("monster_spawn_light_level");
                int low = light.isJsonPrimitive() ? light.getAsInt() : light.getAsJsonObject().getAsJsonObject("value").get("min_inclusive").getAsInt();
                int high = light.isJsonPrimitive() ? light.getAsInt() : light.getAsJsonObject().getAsJsonObject("value").get("max_inclusive").getAsInt();
                assertEquals(low, type.monsterSpawnLightTest().minInclusive(), id);
                assertEquals(high, type.monsterSpawnLightTest().maxInclusive(), id);
                var attributes = type.attributes();
                for (var flag : new Object[][]{
                        {EnvironmentAttributes.WATER_EVAPORATES, old.get("ultrawarm").getAsBoolean()},
                        {EnvironmentAttributes.FAST_LAVA, old.get("ultrawarm").getAsBoolean()},
                        {EnvironmentAttributes.NETHER_PORTAL_SPAWNS_PIGLINS, old.get("natural").getAsBoolean()},
                        {EnvironmentAttributes.CAN_START_RAID, old.get("has_raids").getAsBoolean()},
                        {EnvironmentAttributes.PIGLINS_ZOMBIFY, !old.get("piglin_safe").getAsBoolean()},
                        {EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, old.get("respawn_anchor_works").getAsBoolean()}}) {
                    @SuppressWarnings("unchecked") var attribute = (net.minecraft.world.attribute.EnvironmentAttribute<Boolean>) flag[0];
                    assertTrue(attributes.contains(attribute), id + " must explicitly preserve false as well as true: " + attribute);
                    assertEquals(flag[1], attributes.applyModifier(attribute, attribute.defaultValue()), id);
                }
                var drip = attributes.applyModifier(EnvironmentAttributes.DEFAULT_DRIPSTONE_PARTICLE,
                        EnvironmentAttributes.DEFAULT_DRIPSTONE_PARTICLE.defaultValue());
                assertEquals(old.get("ultrawarm").getAsBoolean() ? "minecraft:dripping_dripstone_lava" : "minecraft:dripping_dripstone_water",
                        BuiltInRegistries.PARTICLE_TYPE.getKey(drip.getType()).toString(), id);
                var bed = attributes.applyModifier(EnvironmentAttributes.BED_RULE, EnvironmentAttributes.BED_RULE.defaultValue());
                boolean usable = old.get("bed_works").getAsBoolean() && old.get("natural").getAsBoolean();
                assertEquals(usable ? BedRule.Rule.WHEN_DARK : BedRule.Rule.NEVER, bed.canSleep(), id);
                assertEquals(usable ? BedRule.Rule.ALWAYS : BedRule.Rule.NEVER, bed.canSetSpawn(), id);
                assertEquals(!old.get("bed_works").getAsBoolean(), bed.destroyOnUse(), id);
                assertFalse(bed.destroyOnLeave(), id);
            }
        }
    }

    @Test void authoredWorldgenAndRealmDefinitionsSurviveNativeRegistryDecoding(MinecraftServer server) {
        assertNotNull(registered(server, Registries.NOISE_SETTINGS, "nirncraft:tamriel"));
        for (String realm : new String[]{"deadlands", "sovngarde", "apocrypha", "coldharbour", "shivering_isles", "soul_cairn"}) {
            assertNotNull(registered(server, Registries.NOISE_SETTINGS, "nirncraft:realm/" + realm));
        }
        for (String feature : new String[]{"biome/akaviri_pine", "biome/baobab", "biome/giant_snowy_spruce", "realm/sg_golden_tree"}) {
            assertNotNull(registered(server, Registries.FEATURE, "nirncraft:" + feature));
        }
        for (String feature : new String[]{"cave_crystals", "cave_moss_patches", "dwemer_pipes", "glowing_mushrooms", "ice_formations"}) {
            assertNotNull(registered(server, Registries.PLACED_FEATURE, "nirncraft:biome/" + feature));
        }
        assertNotNull(registered(server, Registries.STRUCTURE, "nirncraft:dwemer_ruin"));
        assertNotNull(registered(server, Registries.STRUCTURE, "nirncraft:site/dwemer"));
        for (String carver : new String[]{"cave", "cave_extra_underground", "canyon"}) {
            var disabled = assertInstanceOf(CaveWorldCarver.class, registered(server, Registries.CARVER, "minecraft:" + carver));
            assertEquals(0.0f, disabled.probability(), "Tamriel's plan-aware caves must not gain vanilla post-surface carving");
        }
        for (String type : new String[]{"minecraft:the_nether", "minecraft:the_end", "nirncraft:apocrypha",
                "nirncraft:soul_cairn", "nirncraft:coldharbour", "nirncraft:jail"}) {
            assertTrue(registered(server, Registries.DIMENSION_TYPE, type).hasFixedTime(),
                    type + " must retain fixed-time day/night gameplay semantics as well as its paused clock");
        }
        assertFalse(registered(server, Registries.DIMENSION_TYPE, "nirncraft:shivering_isles").hasFixedTime());
    }

    @Test void overworldNativeCloudHeightRetainsTheAuthoredMountainClearance(MinecraftServer server) {
        var overworld = registered(server, Registries.DIMENSION_TYPE, "minecraft:overworld");
        assertFalse(overworld.hasFixedTime());
        assertTrue(overworld.attributes().contains(EnvironmentAttributes.CLOUD_HEIGHT));
        assertEquals(330.0f, (float) overworld.attributes().applyModifier(EnvironmentAttributes.CLOUD_HEIGHT, 192.33f));
    }

    @Test void fixedRealmClocksRetainTheirAuthoredBiomeFogAndDoNotGainSunriseTint(MinecraftServer server) {
        for (String name : new String[]{"the_nether", "the_end", "apocrypha", "soul_cairn", "coldharbour"}) {
            var day = registered(server, Registries.TIMELINE, "nirncraft:fixed/" + name + "/day");
            assertFalse(day.attributes().contains(EnvironmentAttributes.FOG_COLOR), name + " keeps its unscaled biome fog");
            assertFalse(day.attributes().contains(EnvironmentAttributes.SUNRISE_SUNSET_COLOR), name + " has no sunrise tint");
            assertTrue(day.attributes().contains(EnvironmentAttributes.SKY_LIGHT_FACTOR), "Keep the authored fixed-time lighting");
            assertTrue(day.attributes().contains(EnvironmentAttributes.SKY_LIGHT_LEVEL));
            var moon = registered(server, Registries.TIMELINE, "nirncraft:fixed/" + name + "/moon");
            assertTrue(moon.attributes().contains(EnvironmentAttributes.MOON_PHASE));
        }
        var jail = registered(server, Registries.TIMELINE, "nirncraft:fixed/jail/day");
        assertTrue(jail.attributes().contains(EnvironmentAttributes.FOG_COLOR), "Jail retains vanilla fixed-night fog");
        assertTrue(jail.attributes().contains(EnvironmentAttributes.SUNRISE_SUNSET_COLOR));
    }

    @Test void allGlobalModifiersLoadInTheirOriginalAuthoredOrder(MinecraftServer server) throws Exception {
        var expected = new ArrayList<Identifier>();
        String manifest = "/data/neoforge/nirncraft_legacy/global_loot_modifiers.json";
        try (var input = WorldDataMigrationTest.class.getResourceAsStream(manifest)) {
            assertNotNull(input, manifest);
            var authored = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : authored.getAsJsonArray("entries")) expected.add(Identifier.parse(entry.getAsString()));
        }
        assertEquals(21, expected.size());
        var manager = server.getServerResources().managers().getListener(NeoForgeReloadListeners.LOOT_MODIFIERS_KEY);
        var actual = new ArrayList<Identifier>();
        int previous = Integer.MAX_VALUE;
        for (var modifier : manager.getSortedModifiers()) {
            Identifier id = manager.getId(modifier);
            assertNotNull(id);
            if (!id.getNamespace().equals("nirncraft")) continue;
            assertTrue(modifier.priority() < previous, "Every authored modifier needs an unambiguous native priority");
            previous = modifier.priority();
            actual.add(id);
        }
        assertEquals(expected, actual, "A nonfatal codec error must not drop a forage or chest modifier");
    }

    @Test void intentionallyDisabledEnchantingRecipeDoesNotReappear(MinecraftServer server) {
        var recipes = server.getRecipeManager();
        assertTrue(recipes.getRecipes().size() > 100, "Check a genuinely loaded recipe manager");
        assertTrue(recipes.byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse("minecraft:bread"))).isPresent());
        assertTrue(recipes.byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse("minecraft:enchanting_table"))).isEmpty(),
                () -> "The authored enchanting-table recipe must remain disabled. " + recipeResourceEvidence(server));
        assertTrue(server.getServerResources().resourceManager().getResource(
                Identifier.parse("minecraft:advancement/recipes/decorations/enchanting_table.json")).isEmpty(),
                "The removed recipe's own unlock must not retain an unbound native recipe holder");
    }

    private static String recipeResourceEvidence(MinecraftServer server) {
        var resources = server.getServerResources().resourceManager();
        var id = Identifier.parse("minecraft:recipe/enchanting_table.json");
        StringBuilder evidence = new StringBuilder("resolvedSource=")
                .append(resources.getResource(id).map(net.minecraft.server.packs.resources.Resource::sourcePackId).orElse("absent"));
        resources.listPacks().forEach(pack -> {
            evidence.append("; pack=").append(pack.packId());
            try {
                var filter = pack.getMetadataSection(net.minecraft.server.packs.resources.ResourceFilterSection.TYPE);
                evidence.append(",filtersRecipe=").append(filter != null && filter.isFiltered(id));
                evidence.append(",hasRecipe=").append(pack.getResource(net.minecraft.server.packs.PackType.SERVER_DATA, id) != null);
            } catch (java.io.IOException error) {
                evidence.append(",metadataError=").append(error);
            }
        });
        return evidence.toString();
    }
}
