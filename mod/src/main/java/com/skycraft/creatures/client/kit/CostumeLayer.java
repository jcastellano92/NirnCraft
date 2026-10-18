package com.skycraft.creatures.client.kit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.creatures.client.rig.Bone;
import com.skycraft.creatures.client.rig.CreatureDef;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import com.skycraft.client.CapturedPartModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Costume pieces (helmets, hoods, ears, antlers, capes, skirts) worn over a humanoid model: a costume rig
 * ({@code rig.defs.Costumes}) whose six top-level parts copy the humanoid's head, body, arms and legs every frame.
 * Optional pieces (named bones) are shown or hidden per entity, e.g. the draugr's helmet by rank.
 */
public class CostumeLayer<T extends Mob, M extends HumanoidModel<HumanoidRenderState>> extends RenderLayer<HumanoidRenderState, M> {
    private static final String[] PARTS = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
    private final ModelPart[] parts = new ModelPart[PARTS.length];
    private final Map<String, ModelPart> optional = new HashMap<>();
    private record Appearance(Identifier texture,java.util.Map<String,Boolean> visible) {}
    private static final java.util.concurrent.atomic.AtomicInteger IDS=new java.util.concurrent.atomic.AtomicInteger();
    private final net.minecraft.util.context.ContextKey<Appearance> appearanceKey=new net.minecraft.util.context.ContextKey<>(Identifier.fromNamespaceAndPath("skycraft","costume_"+IDS.getAndIncrement()));
    private final CapturedPartModel captured;
    private final Function<T, Identifier> texture;
    private final BiPredicate<T, String> visible;

    /**
     * @param optionalBones names of the costume bones that are toggled by {@code visible}
     */
    public CostumeLayer(RenderLayerParent<HumanoidRenderState, M> parent, ModelPart bakedRoot, CreatureDef def, Function<T, Identifier> texture,
                        List<String> optionalBones, BiPredicate<T, String> visible) {
        super(parent);
        this.texture = texture;
        this.visible = visible;
        ModelPart root = bakedRoot.getChild("root");
        this.captured=new CapturedPartModel(root);
        for (int i = 0; i < PARTS.length; i++) parts[i] = root.getChild(PARTS[i]);
        for (String name : optionalBones) {
            ModelPart p = find(root, def, name);
            if (p != null) optional.put(name, p);
        }
    }

    private static ModelPart find(ModelPart root, CreatureDef def, String name) {
        if (!def.rig.has(name)) return null;
        List<String> path = new ArrayList<>();
        for (Bone b = def.rig.bone(name); b != null && b.parent != null; b = b.parent) path.add(0, b.name);
        ModelPart p = root;
        for (String n : path) {
            if (!p.hasChild(n)) return null;
            p = p.getChild(n);
        }
        return p;
    }

    public void extract(T entity,HumanoidRenderState state) {
        var visibility=new java.util.HashMap<String,Boolean>();
        optional.forEach((name,part)->visibility.put(name,visible.test(entity,name)));
        state.setRenderData(appearanceKey,new Appearance(texture.apply(entity),java.util.Map.copyOf(visibility)));
    }
    @Override public void submit(PoseStack pose, SubmitNodeCollector collector,int light,HumanoidRenderState state,float yaw,float pitch) {
        Appearance appearance=state.getRenderData(appearanceKey);
        if (state.isInvisible||appearance==null) return;
        M model=getParentModel();
        ModelPart[] src={model.head,model.body,model.rightArm,model.leftArm,model.rightLeg,model.leftLeg};
        for(int i=0;i<parts.length;i++) parts[i].loadPose(src[i].storePose());
        optional.forEach((name,part)->part.visible=appearance.visible().getOrDefault(name,true));
        captured.submit(collector,pose,RenderTypes.entityCutout(appearance.texture()),light,LivingEntityRenderer.getOverlayCoords(state,0),-1);
    }
}
