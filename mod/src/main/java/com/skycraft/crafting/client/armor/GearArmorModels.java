package com.skycraft.crafting.client.armor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the worn 3D armour models from {@code skycraft/gear/armor_models.json}, written by
 * {@code tools/textures/gear.py} together with the 64x32 layer textures whose UVs it lays out. Per style and slot the
 * file lists the vanilla humanoid parts the slot covers (with their inflation) and the extra cubes that give each set
 * its silhouette: horns, crests, hoods, pauldrons, faulds, gauntlet cuffs, boot cuffs and spikes. Extra cubes are
 * children of a humanoid part, so they follow its animation.
 *
 * <pre>{"styles": {"daedric": {"head": {"base": {"head": 1.0}, "cubes": [
 *     {"part": "head", "uv": [32, 0], "from": [-5, -10, -1], "size": [1, 4, 1], "inflate": 0.0,
 *      "pivot": [0, 0, 0], "rot": [0, 0, -20], "mirror": false}]}, "chest": ..., "legs": ..., "feet": ...}}}</pre>
 *
 * Built lazily on the render thread and cached; textures are 64x32 (vanilla armour layer size).
 */
public final class GearArmorModels {
    private static final String PATH = "/skycraft/gear/armor_models.json";
    private static final String[] PARTS = {"head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
    private static final Map<String, SkyArmorModel> CACHE = new HashMap<>();
    @Nullable
    private static JsonObject styles;
    private static boolean failed;

    private GearArmorModels() {}

    /** The model of a style for a slot, or null if the style is unknown (the vanilla armour model is used). */
    @Nullable
    public static synchronized SkyArmorModel get(String style, EquipmentSlot slot) {
        String slotKey = switch (slot) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            default -> null;
        };
        if (slotKey == null) return null;
        String key = style + "/" + slotKey;
        SkyArmorModel model = CACHE.get(key);
        if (model != null || CACHE.containsKey(key)) return model;
        model = build(style, slotKey);
        CACHE.put(key, model);
        return model;
    }

    @Nullable
    private static JsonObject styles() {
        if (styles == null && !failed) {
            try (InputStream in = GearArmorModels.class.getResourceAsStream(PATH)) {
                if (in == null) throw new IllegalStateException("missing " + PATH);
                styles = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("styles");
            } catch (Exception e) {
                failed = true;
                Skycraft.LOGGER.error("Skycraft: could not load armour models {}", PATH, e);
            }
        }
        return styles;
    }

    @Nullable
    private static SkyArmorModel build(String style, String slot) {
        JsonObject all = styles();
        if (all == null || !all.has(style)) return null;
        JsonObject def = all.getAsJsonObject(style).getAsJsonObject(slot);
        if (def == null) return null;
        try {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            JsonObject base = def.has("base") ? def.getAsJsonObject("base") : new JsonObject();
            Map<String, PartDefinition> parts = new HashMap<>();
            for (String name : PARTS) {
                CubeListBuilder cubes = CubeListBuilder.create();
                if (base.has(name)) addBase(cubes, name, base.get(name).getAsFloat());
                PartDefinition parent = name.equals("hat") ? parts.get("head") : root;
                parts.put(name, parent.addOrReplaceChild(name, cubes, pose(name)));
            }
            JsonArray extra = def.has("cubes") ? def.getAsJsonArray("cubes") : new JsonArray();
            int n = 0;
            for (JsonElement e : extra) {
                JsonObject c = e.getAsJsonObject();
                PartDefinition parent = parts.get(c.get("part").getAsString());
                if (parent == null) continue;
                float[] from = floats(c, "from", 0f);
                float[] size = floats(c, "size", 1f);
                float[] pivot = floats(c, "pivot", 0f);
                float[] rot = floats(c, "rot", 0f);
                int[] uv = {c.getAsJsonArray("uv").get(0).getAsInt(), c.getAsJsonArray("uv").get(1).getAsInt()};
                float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : 0f;
                boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
                CubeListBuilder cube = CubeListBuilder.create().texOffs(uv[0], uv[1]).mirror(mirror)
                        .addBox(from[0], from[1], from[2], size[0], size[1], size[2], new CubeDeformation(inflate));
                parent.addOrReplaceChild("x" + n++, cube, PartPose.offsetAndRotation(pivot[0], pivot[1], pivot[2],
                        rot[0] * Mth.DEG_TO_RAD, rot[1] * Mth.DEG_TO_RAD, rot[2] * Mth.DEG_TO_RAD));
            }
            return new SkyArmorModel(LayerDefinition.create(mesh, 64, 32).bakeRoot());
        } catch (RuntimeException ex) {
            Skycraft.LOGGER.error("Skycraft: bad armour model {}/{}", style, slot, ex);
            return null;
        }
    }

    /** The vanilla humanoid cube of a part (same UVs as the vanilla armour layers). */
    private static void addBase(CubeListBuilder cubes, String part, float inflate) {
        CubeDeformation d = new CubeDeformation(inflate);
        switch (part) {
            case "head" -> cubes.texOffs(0, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f, d);
            case "hat" -> cubes.texOffs(32, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f, d.extend(0.5f));
            case "body" -> cubes.texOffs(16, 16).addBox(-4f, 0f, -2f, 8f, 12f, 4f, d);
            case "right_arm" -> cubes.texOffs(40, 16).addBox(-3f, -2f, -2f, 4f, 12f, 4f, d);
            case "left_arm" -> cubes.texOffs(40, 16).mirror().addBox(-1f, -2f, -2f, 4f, 12f, 4f, d);
            case "right_leg" -> cubes.texOffs(0, 16).addBox(-2f, 0f, -2f, 4f, 12f, 4f, d);
            case "left_leg" -> cubes.texOffs(0, 16).mirror().addBox(-2f, 0f, -2f, 4f, 12f, 4f, d);
            default -> {
            }
        }
    }

    private static PartPose pose(String part) {
        return switch (part) {
            case "right_arm" -> PartPose.offset(-5f, 2f, 0f);
            case "left_arm" -> PartPose.offset(5f, 2f, 0f);
            case "right_leg" -> PartPose.offset(-1.9f, 12f, 0f);
            case "left_leg" -> PartPose.offset(1.9f, 12f, 0f);
            default -> PartPose.ZERO;
        };
    }

    private static float[] floats(JsonObject o, String key, float def) {
        float[] out = {def, def, def};
        if (!o.has(key)) return out;
        JsonArray a = o.getAsJsonArray(key);
        for (int i = 0; i < 3 && i < a.size(); i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    /** Drops cached models (resource reload). */
    public static synchronized void clear() {
        CACHE.clear();
        styles = null;
        failed = false;
    }
}
