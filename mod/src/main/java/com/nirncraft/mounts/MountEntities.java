package com.nirncraft.mounts;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.entity.anim.AnimSlot;
import com.nirncraft.creatures.spec.Attacks;
import com.nirncraft.creatures.spec.CreatureSpec;
import com.nirncraft.creatures.spec.HitEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ridden beasts ({@link MountBeast}): their entity types and creature specs. They reuse the bestiary's rig models
 * (senche-tiger, wolf, indrik, mammoth, realm grazer) with their own saddled and barded skins. Variant 0 is the bare
 * beast, 1 saddled, 2 barded.
 */
public final class MountEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, NirnCraft.MODID);

    private static final Map<EntityType<?>, CreatureSpec> SPECS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<EntityType<?>, MountKind> KINDS = Collections.synchronizedMap(new IdentityHashMap<>());
    /** Mount kind of each beast type, by registry id. */
    public static final Map<MountKind, Supplier<EntityType<MountBeast>>> BY_KIND = new LinkedHashMap<>();

    public static final Supplier<EntityType<MountBeast>> SENCHE = register(MountKind.SENCHE, sencheSpec());
    public static final Supplier<EntityType<MountBeast>> GIANT_WOLF = register(MountKind.GIANT_WOLF, wolfSpec());
    public static final Supplier<EntityType<MountBeast>> INDRIK = register(MountKind.INDRIK, indrikSpec());
    public static final Supplier<EntityType<MountBeast>> MAMMOTH = register(MountKind.MAMMOTH, mammothSpec());
    public static final Supplier<EntityType<MountBeast>> AKAVIRI_TIGER = register(MountKind.AKAVIRI_TIGER, tigerSpec());
    public static final Supplier<EntityType<MountBeast>> REALM_GRAZER = register(MountKind.REALM_GRAZER, grazerSpec());

    private MountEntities() {}

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(MountEntities::onAttributes);
    }

    private static Supplier<EntityType<MountBeast>> register(MountKind kind, CreatureSpec spec) {
        spec.variants = 3; // bare, saddled, barded
        Supplier<EntityType<MountBeast>> type = ENTITIES.register(spec.id, () -> {
            EntityType<MountBeast> t = EntityType.Builder.<MountBeast>of(MountBeast::new, MobCategory.CREATURE)
                    .sized(spec.width, spec.height).clientTrackingRange(10).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse(NirnCraft.MODID + ":" + spec.id)));
            SPECS.put(t, spec);
            KINDS.put(t, kind);
            return t;
        });
        BY_KIND.put(kind, type);
        return type;
    }

    /** The spec of a mount beast type (throws for other types). */
    public static CreatureSpec spec(EntityType<?> type) {
        CreatureSpec s = SPECS.get(type);
        if (s == null) throw new IllegalStateException("No mount spec for " + EntityType.getKey(type));
        return s;
    }

    @Nullable
    public static MountKind kind(EntityType<?> type) {
        return KINDS.get(type);
    }

    @Nullable
    public static EntityType<MountBeast> type(MountKind kind) {
        Supplier<EntityType<MountBeast>> ro = BY_KIND.get(kind);
        return ro == null ? null : ro.get();
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        for (Supplier<EntityType<MountBeast>> ro : BY_KIND.values()) {
            event.put(ro.get(), spec(ro.get()).attributes().build());
        }
    }

    // ------------------------------------------------------------------ specs

    /** Senche-tiger mount: a tamed great cat of Elsweyr, swift with claws that fight beside its rider. */
    static CreatureSpec sencheSpec() {
        CreatureSpec s = new CreatureSpec("senche_mount").size(1.3f, 1.4f).stats(60, 7, 0.31).xp(0)
                .temper(CreatureSpec.Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f).timing(7, 14, 18).range(0, 1.8f))
                .attack(Attacks.melee(1.2f, HitEffect.knock(0.8, 0.2)).anim(AnimSlot.ATTACK2).timing(8, 16, 30).range(0, 1.8f))
                .voice("senche_tiger", 1.0f);
        s.category = MobCategory.CREATURE;
        s.persistent = true;
        s.stepHeight = 1.1f;
        s.armor = 4;
        return s;
    }

    /** Giant wolf: the Bosmer's war wolves of Valenwood (the Green Pact forbids felling trees, not riding wolves). */
    static CreatureSpec wolfSpec() {
        CreatureSpec s = new CreatureSpec("giant_wolf").size(1.2f, 1.5f).stats(50, 6, 0.33).xp(0)
                .temper(CreatureSpec.Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f).timing(6, 12, 16).range(0, 1.7f))
                .voice("wolf", 0.75f);
        s.category = MobCategory.CREATURE;
        s.persistent = true;
        s.stepHeight = 1.1f;
        s.armor = 2;
        return s;
    }

    /** Indrik: Summerset's rare feathered steed; gentle, the fastest of all mounts, light on its hooves. */
    static CreatureSpec indrikSpec() {
        CreatureSpec s = new CreatureSpec("indrik_mount").size(1.2f, 1.9f).stats(55, 0, 0.34).xp(0)
                .temper(CreatureSpec.Temper.NEUTRAL)
                .voice("indrik", 1.0f)
                .step(() -> SoundEvents.HORSE_STEP);
        s.category = MobCategory.CREATURE;
        s.persistent = true;
        s.stepHeight = 1.1f;
        return s;
    }

    /** Woolly mammoth of Atmora: slow, enormous, carries two and tramples what its rider strikes. */
    static CreatureSpec mammothSpec() {
        CreatureSpec s = new CreatureSpec("mammoth_mount").size(2.6f, 3.4f).stats(180, 12, 0.22).xp(0)
                .temper(CreatureSpec.Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.6, 0.4)).timing(10, 20, 30).range(0, 2.8f))
                .voice("mammoth", 1.0f);
        s.category = MobCategory.CREATURE;
        s.persistent = true;
        s.stepHeight = 1.6f;
        s.armor = 8;
        s.kbResist = 1.0;
        return s;
    }

    /** Akaviri tiger: the great striped cats the Ka Po' Tun ride to war. */
    static CreatureSpec tigerSpec() {
        CreatureSpec s = new CreatureSpec("akaviri_tiger").size(1.4f, 1.5f).stats(70, 8, 0.32).xp(0)
                .temper(CreatureSpec.Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f).timing(7, 14, 18).range(0, 1.9f))
                .attack(Attacks.melee(1.3f, HitEffect.knock(1.0, 0.25)).anim(AnimSlot.ATTACK2).timing(8, 16, 30).range(0, 1.9f))
                .voice("senche_tiger", 0.85f);
        s.category = MobCategory.CREATURE;
        s.persistent = true;
        s.stepHeight = 1.1f;
        s.armor = 5;
        return s;
    }

    /** A tamed grazer of the endless lands: sure-footed and patient. */
    static CreatureSpec grazerSpec() {
        CreatureSpec s = new CreatureSpec("realm_grazer_mount").size(1.3f, 1.7f).stats(45, 4, 0.29).xp(0)
                .temper(CreatureSpec.Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.0, 0.2)).timing(8, 16, 30))
                .voice("realm_grazer", 1.0f);
        s.category = MobCategory.CREATURE;
        s.persistent = true;
        s.stepHeight = 1.2f;
        s.armor = 2;
        return s;
    }
}
