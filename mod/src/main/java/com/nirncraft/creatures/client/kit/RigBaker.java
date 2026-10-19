package com.nirncraft.creatures.client.kit;

import com.nirncraft.creatures.client.rig.Anim;
import com.nirncraft.creatures.client.rig.Bone;
import com.nirncraft.creatures.client.rig.Box;
import com.nirncraft.creatures.client.rig.Rig;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/** Converts the plain-Java rig kit into vanilla model layers and keyframe animations. */
public final class RigBaker {
    private static final Map<Anim, AnimationDefinition> BAKED = new IdentityHashMap<>();

    private RigBaker() {}

    /** The rig as a vanilla layer definition (texture size from the packed UVs). */
    public static LayerDefinition layer(Rig rig) {
        rig.pack();
        MeshDefinition mesh = new MeshDefinition();
        addBone(mesh.getRoot(), rig.root());
        return LayerDefinition.create(mesh, rig.texWidth(), rig.texHeight());
    }

    private static void addBone(PartDefinition parent, Bone bone) {
        CubeListBuilder cubes = CubeListBuilder.create();
        for (Box b : bone.boxes) {
            cubes.texOffs(b.u, b.v).mirror(b.mirror)
                    .addBox(b.x, b.y, b.z, b.w, b.h, b.d, new CubeDeformation(b.inflate));
        }
        PartDefinition part = parent.addOrReplaceChild(bone.name, cubes, PartPose.offsetAndRotation(bone.x, bone.y, bone.z,
                bone.rx * Mth.DEG_TO_RAD, bone.ry * Mth.DEG_TO_RAD, bone.rz * Mth.DEG_TO_RAD));
        for (Bone child : bone.children) addBone(part, child);
    }

    /** The animation as a vanilla {@link AnimationDefinition} (cached per animation object). */
    public static synchronized AnimationDefinition bake(Anim anim) {
        AnimationDefinition def = BAKED.get(anim);
        if (def != null) return def;
        AnimationDefinition.Builder builder = AnimationDefinition.Builder.withLength(anim.length);
        if (anim.loop) builder.looping();
        for (Anim.Channel c : anim.channels) {
            Keyframe[] keys = new Keyframe[c.times.length];
            AnimationChannel.Interpolation interp = c.smooth ? AnimationChannel.Interpolations.CATMULLROM : AnimationChannel.Interpolations.LINEAR;
            for (int i = 0; i < keys.length; i++) {
                float[] v = c.values[i];
                Vector3f target = switch (c.target) {
                    case ROTATION -> KeyframeAnimations.degreeVec(v[0], v[1], v[2]);
                    case POSITION -> KeyframeAnimations.posVec(v[0], v[1], v[2]);
                    case SCALE -> KeyframeAnimations.scaleVec(v[0], v[1], v[2]);
                };
                keys[i] = new Keyframe(c.times[i], target, interp);
            }
            AnimationChannel.Target target = switch (c.target) {
                case ROTATION -> AnimationChannel.Targets.ROTATION;
                case POSITION -> AnimationChannel.Targets.POSITION;
                case SCALE -> AnimationChannel.Targets.SCALE;
            };
            builder.addAnimation(c.bone, new AnimationChannel(target, keys));
        }
        def = builder.build();
        BAKED.put(anim, def);
        return def;
    }
}
