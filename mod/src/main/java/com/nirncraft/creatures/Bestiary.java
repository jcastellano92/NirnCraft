package com.nirncraft.creatures;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.entity.CreatureBolt;
import com.nirncraft.creatures.entity.DragonPriestEntity;
import com.nirncraft.creatures.entity.GuarEntity;
import com.nirncraft.creatures.entity.SandSerpentEntity;
import com.nirncraft.creatures.entity.SiltStriderEntity;
import com.nirncraft.creatures.entity.SkyBeast;
import com.nirncraft.creatures.entity.SkyMonster;
import com.nirncraft.creatures.spec.CreatureSpec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bestiary's entity types: every spec-driven creature of {@link BestiarySpecs} (registry ids = spec ids, a
 * cross-module contract), their spawn eggs, attributes and spawn placements, and the creature projectile.
 *
 * <p>Ids other modules may use: the atronachs ({@code nirncraft:flame_atronach}, {@code frost_atronach},
 * {@code storm_atronach}) and {@code nirncraft:dremora} are built to be conjured — magic's Summons can create them
 * instead of the vanilla stand-ins; they never target their summoner.</p>
 */
public final class Bestiary {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NirnCraft.MODID);

    private static final Map<EntityType<?>, CreatureSpec> SPECS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<String, CreatureSpec> BY_ID = new LinkedHashMap<>();
    /** Every bestiary creature type, in registration order. */
    public static final List<Supplier<? extends EntityType<? extends Mob>>> ALL = new ArrayList<>();

    public static final Supplier<EntityType<CreatureBolt>> BOLT = ENTITIES.register("creature_bolt",
            () -> EntityType.Builder.<CreatureBolt>of(CreatureBolt::new, MobCategory.MISC).sized(0.4f, 0.4f)
                    .clientTrackingRange(6).updateInterval(2).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse(NirnCraft.MODID + ":creature_bolt"))));

    // ---------------------------------------------------------------- Skyrim wildlife
    public static final Supplier<EntityType<SkyBeast>> WOLF = beast(BestiarySpecs.wolf());

    // ---------------------------------------------------------------- Skyrim creatures
    public static final Supplier<EntityType<SkyMonster>> CHAURUS = monster(BestiarySpecs.chaurus());
    public static final Supplier<EntityType<SkyMonster>> CHAURUS_HUNTER = monster(BestiarySpecs.chaurusHunter());
    public static final Supplier<EntityType<SkyMonster>> RIEKLING = monster(BestiarySpecs.riekling());
    public static final Supplier<EntityType<SkyMonster>> ASH_SPAWN = monster(BestiarySpecs.ashSpawn());
    // gargoyle and death_hound are registered by com.nirncraft.curse (statue gargoyles, vampire hounds)
    public static final Supplier<EntityType<SkyMonster>> WISP = monster(BestiarySpecs.wisp());
    public static final Supplier<EntityType<SkyMonster>> WILL_O_WISP = monster(BestiarySpecs.willOWisp());
    public static final Supplier<EntityType<SkyMonster>> DWARVEN_BALLISTA = monster(BestiarySpecs.dwarvenBallista());
    public static final Supplier<EntityType<DragonPriestEntity>> DRAGON_PRIEST = register(BestiarySpecs.dragonPriest(), DragonPriestEntity::new);

    // ---------------------------------------------------------------- daedra (conjurable: see the class doc)
    public static final Supplier<EntityType<SkyMonster>> DREMORA = monster(BestiarySpecs.dremora());
    public static final Supplier<EntityType<SkyMonster>> FLAME_ATRONACH = monster(BestiarySpecs.flameAtronach());
    public static final Supplier<EntityType<SkyMonster>> FROST_ATRONACH = monster(BestiarySpecs.frostAtronach());
    public static final Supplier<EntityType<SkyMonster>> STORM_ATRONACH = monster(BestiarySpecs.stormAtronach());
    public static final Supplier<EntityType<SkyMonster>> SCAMP = monster(BestiarySpecs.scamp());
    public static final Supplier<EntityType<SkyMonster>> IMP = monster(BestiarySpecs.imp());

    // ---------------------------------------------------------------- Morrowind
    public static final Supplier<EntityType<SkyMonster>> CLIFF_RACER = monster(BestiarySpecs.cliffRacer());
    public static final Supplier<EntityType<GuarEntity>> GUAR = register(BestiarySpecs.guar(), GuarEntity::new);
    public static final Supplier<EntityType<SkyBeast>> KAGOUTI = beast(BestiarySpecs.kagouti());
    public static final Supplier<EntityType<SkyBeast>> ALIT = beast(BestiarySpecs.alit());
    public static final Supplier<EntityType<SkyBeast>> NIX_HOUND = beast(BestiarySpecs.nixHound());
    public static final Supplier<EntityType<SkyBeast>> NETCH = beast(BestiarySpecs.netch());
    public static final Supplier<EntityType<SiltStriderEntity>> SILT_STRIDER = register(BestiarySpecs.siltStrider(), SiltStriderEntity::new);

    // ---------------------------------------------------------------- Cyrodiil, Black Marsh, Elsweyr
    public static final Supplier<EntityType<SkyMonster>> MINOTAUR = monster(BestiarySpecs.minotaur());
    public static final Supplier<EntityType<SkyMonster>> OGRE = monster(BestiarySpecs.ogre());
    public static final Supplier<EntityType<SkyMonster>> LAND_DREUGH = monster(BestiarySpecs.landDreugh());
    public static final Supplier<EntityType<SkyMonster>> WAMASU = monster(BestiarySpecs.wamasu());
    public static final Supplier<EntityType<SkyMonster>> SWAMP_LEVIATHAN = monster(BestiarySpecs.swampLeviathan());
    public static final Supplier<EntityType<SkyBeast>> HACKWING = beast(BestiarySpecs.hackwing());
    public static final Supplier<EntityType<SkyBeast>> SENCHE_TIGER = beast(BestiarySpecs.sencheTiger());
    public static final Supplier<EntityType<SkyBeast>> ALFIQ = beast(BestiarySpecs.alfiq());

    // ---------------------------------------------------------------- Hammerfell, High Rock, Valenwood, Summerset, the seas
    public static final Supplier<EntityType<SandSerpentEntity>> SAND_SERPENT = register(BestiarySpecs.sandSerpent(), SandSerpentEntity::new);
    public static final Supplier<EntityType<SkyMonster>> GIANT_SCORPION = monster(BestiarySpecs.giantScorpion());
    public static final Supplier<EntityType<SkyMonster>> HARPY = monster(BestiarySpecs.harpy());
    public static final Supplier<EntityType<SkyBeast>> INDRIK = beast(BestiarySpecs.indrik());
    public static final Supplier<EntityType<SkyMonster>> SEA_SERPENT = monster(BestiarySpecs.seaSerpent());
    public static final Supplier<EntityType<SkyBeast>> ECHATERE = beast(BestiarySpecs.echatere());
    public static final Supplier<EntityType<SkyBeast>> DURZOG = beast(BestiarySpecs.durzog());

    // ---------------------------------------------------------------- the foreign lands and the endless realms
    public static final Supplier<EntityType<SkyMonster>> FROST_GIANT = monster(BestiarySpecs.frostGiant());
    public static final Supplier<EntityType<SkyMonster>> KAMAL = monster(BestiarySpecs.kamal());
    public static final Supplier<EntityType<SkyMonster>> TSAESCI = monster(BestiarySpecs.tsaesci());
    public static final Supplier<EntityType<SkyBeast>> REALM_STALKER = beast(BestiarySpecs.realmStalker());
    public static final Supplier<EntityType<SkyBeast>> REALM_GRAZER = beast(BestiarySpecs.realmGrazer());
    public static final Supplier<EntityType<SkyMonster>> REALM_WYRM = monster(BestiarySpecs.realmWyrm());

    private Bestiary() {}

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Bestiary::onAttributes);
        modBus.addListener(Bestiary::onSpawnPlacements);
    }

    // ---------------------------------------------------------------- registration helpers

    public static Supplier<EntityType<SkyBeast>> beast(CreatureSpec spec) {
        return register(spec, SkyBeast::new);
    }

    public static Supplier<EntityType<SkyMonster>> monster(CreatureSpec spec) {
        return register(spec, SkyMonster::new);
    }

    public static <T extends Mob> Supplier<EntityType<T>> register(CreatureSpec spec, EntityType.EntityFactory<T> factory) {
        if (BY_ID.put(spec.id, spec) != null) throw new IllegalStateException("Duplicate bestiary id " + spec.id);
        Supplier<EntityType<T>> type = ENTITIES.register(spec.id, () -> {
            EntityType.Builder<T> b = EntityType.Builder.of(factory, spec.category).sized(spec.width, spec.height)
                    .clientTrackingRange(spec.trackingRange);
            if (spec.category == MobCategory.MONSTER) b.notInPeaceful();
            if (spec.fireImmune) b.fireImmune();
            EntityType<T> t = b.build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse(NirnCraft.MODID + ":" + spec.id)));
            SPECS.put(t, spec);
            return t;
        });
        ITEMS.register(spec.id + "_spawn_egg", () -> new SpawnEggItem(com.nirncraft.platform.SkyProperties.item(spec.id + "_spawn_egg").spawnEgg(type.get())));
        ALL.add(type);
        return type;
    }

    /** The spec of a bestiary entity type (throws for other types). */
    public static CreatureSpec spec(EntityType<?> type) {
        CreatureSpec s = SPECS.get(type);
        if (s == null) throw new IllegalStateException("No bestiary spec for " + EntityType.getKey(type));
        return s;
    }

    public static Optional<CreatureSpec> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    @SuppressWarnings("unchecked")
    private static void onAttributes(EntityAttributeCreationEvent event) {
        for (Supplier<? extends EntityType<? extends Mob>> ro : ALL) {
            EntityType<? extends Mob> type = ro.get();
            event.put((EntityType<? extends net.minecraft.world.entity.LivingEntity>) type, spec(type).attributes().build());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void onSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        for (Supplier<? extends EntityType<? extends Mob>> ro : ALL) {
            EntityType type = ro.get();
            CreatureSpec s = spec(type);
            net.minecraft.world.entity.SpawnPlacementType place = switch (s.move) {
                case SWIM -> net.minecraft.world.entity.SpawnPlacementTypes.IN_WATER;
                case FLY, HOVER -> net.minecraft.world.entity.SpawnPlacementTypes.NO_RESTRICTIONS;
                default -> net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND;
            };
            // Natural spawning is done by the region spawner (RegionSpawner), which checks its own rules: vanilla's
            // biome spawning (e.g. a biome JSON listing a bestiary creature) only gets the light/ground basics.
            event.register(type, place, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (t, level, reason, pos, random) -> s.temper != CreatureSpec.Temper.HOSTILE
                            || net.minecraft.world.entity.monster.Monster.isDarkEnoughToSpawn((net.minecraft.world.level.ServerLevelAccessor) level, pos, random)
                            || s.move != CreatureSpec.Move.WALK,
                    RegisterSpawnPlacementsEvent.Operation.REPLACE);
        }
    }
}
