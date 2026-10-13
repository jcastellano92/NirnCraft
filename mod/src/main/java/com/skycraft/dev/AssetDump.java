package com.skycraft.dev;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Developer tool for {@code tools/qa/asset_audit.py}: what the running game registered, so the static audit can check
 * every block's blockstate (each state of each property combination has a variant), model and textures, and every item's
 * model, and find {@code skycraft:...} ids in the data that nothing registered.
 *
 * <p>Writes {@code skycraft-asset-audit.json} into the server directory when the server starts under the QA harness
 * ({@code -Dskycraft.autorun} or {@code -Dskycraft.dev} set) or when {@code skycraft-asset-audit.request} exists, and on
 * demand with the op command {@code /skycraft-assets dump}. Nothing happens on ordinary servers.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class AssetDump {
    static final String REQUEST = "skycraft-asset-audit.request";
    static final String OUTPUT = "skycraft-asset-audit.json";

    private AssetDump() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        boolean harness = !System.getProperty("skycraft.autorun", "").isEmpty() || System.getProperty("skycraft.dev") != null;
        if (!harness && !new File(server.getServerDirectory(), REQUEST).exists()) return;
        write(server);
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-assets").requires(s -> s.hasPermission(2))
                .then(Commands.literal("dump").executes(ctx -> {
                    String msg = write(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                })));
    }

    private static String write(MinecraftServer server) {
        try {
            JsonObject root = dump();
            File out = new File(server.getServerDirectory(), OUTPUT);
            try (Writer w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
                new GsonBuilder().disableHtmlEscaping().create().toJson(root, w);
            }
            String msg = "Asset dump: " + root.getAsJsonObject("blocks").size() + " blocks, " + root.getAsJsonObject("items").size()
                    + " items, " + root.getAsJsonArray("entities").size() + " entities -> " + OUTPUT;
            Skycraft.LOGGER.info(msg);
            return msg;
        } catch (Exception e) {
            Skycraft.LOGGER.error("Asset dump failed", e);
            return "Asset dump failed: " + e;
        }
    }

    static JsonObject dump() {
        JsonObject blocks = new JsonObject();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> e : ForgeRegistries.BLOCKS.getEntries()) {
            ResourceLocation id = e.getKey().location();
            if (!id.getNamespace().equals(Skycraft.MODID)) continue;
            Block b = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("class", b.getClass().getName());
            RenderShape rs = b.defaultBlockState().getRenderShape();
            o.addProperty("render", rs.name());
            JsonObject props = new JsonObject();
            for (Property<?> p : b.getStateDefinition().getProperties()) props.add(p.getName(), values(p));
            o.add("props", props);
            Item item = b.asItem();
            o.addProperty("item", item != net.minecraft.world.item.Items.AIR);
            blocks.add(id.getPath(), o);
        }
        JsonObject items = new JsonObject();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> e : ForgeRegistries.ITEMS.getEntries()) {
            ResourceLocation id = e.getKey().location();
            if (!id.getNamespace().equals(Skycraft.MODID)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("class", e.getValue().getClass().getName());
            o.addProperty("block", e.getValue() instanceof BlockItem);
            items.add(id.getPath(), o);
        }
        JsonArray entities = new JsonArray();
        for (Map.Entry<net.minecraft.resources.ResourceKey<EntityType<?>>, EntityType<?>> e : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            ResourceLocation id = e.getKey().location();
            if (id.getNamespace().equals(Skycraft.MODID)) entities.add(id.getPath());
        }
        // every other builtin registry (enchantments, effects, particle types, sounds, villager professions, ...)
        JsonObject registries = new JsonObject();
        for (net.minecraft.core.Registry<?> reg : net.minecraft.core.registries.BuiltInRegistries.REGISTRY) {
            JsonArray names = new JsonArray();
            for (ResourceLocation k : reg.keySet()) {
                if (k.getNamespace().equals(Skycraft.MODID)) names.add(k.getPath());
            }
            if (!names.isEmpty()) registries.add(reg.key().location().getPath(), names);
        }
        JsonObject root = new JsonObject();
        root.add("registries", registries);
        root.add("blocks", blocks);
        root.add("items", items);
        root.add("entities", entities);
        return root;
    }

    private static <T extends Comparable<T>> JsonArray values(Property<T> p) {
        JsonArray a = new JsonArray();
        List<T> vs = p.getPossibleValues().stream().toList();
        for (T v : vs) a.add(p.getName(v));
        return a;
    }
}
