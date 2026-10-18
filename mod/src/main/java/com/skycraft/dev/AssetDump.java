package com.skycraft.dev;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

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
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class AssetDump {
    static final String REQUEST = "skycraft-asset-audit.request";
    static final String OUTPUT = "skycraft-asset-audit.json";

    private AssetDump() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        boolean harness = !System.getProperty("skycraft.autorun", "").isEmpty() || System.getProperty("skycraft.dev") != null;
        if (!harness && !server.getServerDirectory().resolve(REQUEST).toFile().exists()) return;
        write(server);
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-assets").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("dump").executes(ctx -> {
                    String msg = write(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                })));
    }

    private static String write(MinecraftServer server) {
        try {
            JsonObject root = dump();
            File out = server.getServerDirectory().resolve(OUTPUT).toFile();
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
        for (var e : BuiltInRegistries.BLOCK.listElements().toList()) {
            Identifier id = e.key().identifier();
            if (!id.getNamespace().equals(Skycraft.MODID)) continue;
            Block b = e.value();
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
        for (var e : BuiltInRegistries.ITEM.listElements().toList()) {
            Identifier id = e.key().identifier();
            if (!id.getNamespace().equals(Skycraft.MODID)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("class", e.value().getClass().getName());
            o.addProperty("block", e.value() instanceof BlockItem);
            items.add(id.getPath(), o);
        }
        JsonArray entities = new JsonArray();
        for (var e : BuiltInRegistries.ENTITY_TYPE.listElements().toList()) {
            Identifier id = e.key().identifier();
            if (id.getNamespace().equals(Skycraft.MODID)) entities.add(id.getPath());
        }
        // every other builtin registry (enchantments, effects, particle types, sounds, villager professions, ...)
        JsonObject registries = new JsonObject();
        for (net.minecraft.core.Registry<?> reg : net.minecraft.core.registries.BuiltInRegistries.REGISTRY) {
            JsonArray names = new JsonArray();
            for (Identifier k : reg.keySet()) {
                if (k.getNamespace().equals(Skycraft.MODID)) names.add(k.getPath());
            }
            if (!names.isEmpty()) registries.add(reg.key().identifier().getPath(), names);
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
