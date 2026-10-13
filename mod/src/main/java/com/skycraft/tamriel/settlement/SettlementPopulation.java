package com.skycraft.tamriel.settlement;

import com.skycraft.Skycraft;
import com.skycraft.creatures.ModEntities;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.economy.Shop;
import com.skycraft.roads.RoadsData;
import com.skycraft.roads.Settlement;
import com.skycraft.society.Housing;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Settlers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import org.jetbrains.annotations.Nullable;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.survival.inn.Innkeepers;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.settlement.plan.Cities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts people where a settlement's anchors say they belong, through the existing society/economy/creature APIs:
 * the Jarl on the throne with housecarl, steward and court wizard; innkeepers behind their bars (the survival
 * module's innkeeper contract) and bards by the fire; smiths at the forge, merchants and apothecaries behind their
 * counters (economy merchants); priests at the altar; hold guards at barracks, gates and palace; farmers, millers,
 * miners and townsfolk at their fields, mills, mines and beds. Each anchor is filled once (recorded in
 * {@link SettlementAnchors}), only while its chunk is entity-ticking.
 *
 * <p>How many people each settlement gets is decided by {@link Housing}: one resident per bed at most, a job tied to
 * a workplace anchor for each, households in the beds that are left. Guild staff, guards, innkeepers and townsfolk
 * all come through that plan; {@link #housing} is its cached form.</p>
 */
public final class SettlementPopulation {
    private SettlementPopulation() {}

    public static void tick(ServerLevel level, ServerPlayer player) {
        SettlementAnchors data = SettlementAnchors.get(level);
        for (SettlementAnchors.Entry e : data.entries()) {
            if (!e.contains(player.getBlockX(), player.getBlockZ(), 48)) continue;
            populate(level, data, e);
            // the hard cap: one person per plan slot, a handful of visitors (docs: playtest 4, "why so many people")
            com.skycraft.society.SettlementCrowd.audit(level, e);
        }
    }

    public static int tier(SettlementAnchors.Entry e) {
        Optional<Place> p = e.place();
        if (p.isPresent() && (p.get().type() == PlaceType.CAPITAL || p.get().type() == PlaceType.CITY)) return 0;
        if (p.isPresent() && p.get().type() == PlaceType.TOWN || e.radius >= 45) return 1;
        return 2;
    }

    /** The housing plan per settlement, recomputed when more of its anchors have been recorded. */
    private static final Map<String, Housing.Plan> PLANS = new java.util.HashMap<>();
    private static final Map<String, Integer> PLAN_SIZE = new java.util.HashMap<>();

    /** The housing model of a recorded settlement (cached until its anchor list grows). */
    public static Housing.Plan housing(SettlementAnchors.Entry e) {
        int size = e.anchors().size();
        Integer have = PLAN_SIZE.get(e.key);
        Housing.Plan plan = PLANS.get(e.key);
        if (plan == null || have == null || have != size) {
            plan = Housing.plan(e.key, e.anchors(), tier(e));
            PLANS.put(e.key, plan);
            PLAN_SIZE.put(e.key, size);
        }
        return plan;
    }

    /**
     * Fills the settlement's workplaces and beds as the housing model allows: one person per job anchor (only when a
     * bed is free for them), households in what is left, a bed claimed for every resident at spawn. Anchors are filled
     * once ({@link SettlementAnchors.Entry#spawned}, saved); nothing is spawned from an anchor the plan has no one for.
     */
    static void populate(ServerLevel level, SettlementAnchors data, SettlementAnchors.Entry e) {
        List<Anchor> list = e.anchors;
        if (list.isEmpty() || e.homestead()) return;
        Housing.Plan plan = housing(e);
        boolean capital = tier(e) == 0;
        boolean changed = false;
        NpcEntity jarl = null;
        for (int i = 0; i < list.size(); i++) {
            if (e.spawned.contains(i)) continue;
            Anchor a = list.get(i);
            Housing.Resident res = plan.at(i);
            if (res == null) {
                // members' beds of a guild hall: no person, just the faction's ownership of the bed
                if (a.type() == AnchorType.GUILD_BED && level.isPositionEntityTicking(a.pos())) {
                    com.skycraft.quest.faction.FactionStaff.populate(level, a, a.pos(), e.key);
                    e.spawned.add(i);
                    changed = true;
                }
                continue;
            }
            if (!level.isPositionEntityTicking(a.pos())) continue;
            BlockPos bed = null;
            if (res.bed() >= 0) {
                bed = list.get(res.bed()).pos();
                if (!Settlers.bedFree(level, bed)) bed = nearestFreeBed(level, list, a.pos());
                if (bed == null) { // every bed is taken or gone: nobody lives here
                    e.spawned.add(i);
                    changed = true;
                    continue;
                }
            }
            BlockPos at = standing(level, a.pos());
            boolean done;
            try {
                AnchorType t = res.slotType();
                if ("helgen".equals(e.key) && com.skycraft.quest.QuestConfig.RUINED_HELGEN.get()) {
                    // the dragon has been: only survivors and shaken soldiers, no shopkeepers, innkeeper, bard or court
                    done = ruinedHelgen(level, at, t, i);
                    if (done) {
                        adopt(level, at, res, list, bed, e.key + "#" + i);
                        e.spawned.add(i);
                        changed = true;
                    }
                    continue;
                }
                done = switch (t) {
                    case JARL -> {
                        jarl = npc(level, at, NpcRole.JARL, jarlName(e.key), 10);
                        yield jarl != null;
                    }
                    case HOUSECARL -> {
                        NpcEntity hc = npc(level, at, NpcRole.HOUSECARL, null, 12);
                        if (hc != null && jarl != null) hc.setLeader(jarl.getUUID());
                        yield hc != null;
                    }
                    case STEWARD -> steward(level, at, e) != null;
                    case WIZARD -> npc(level, at, NpcRole.MAGE, Component.translatable("entity.skycraft.court_wizard"), 10) != null;
                    case INN -> innkeeper(level, at, e);
                    case BARD -> npc(level, at, NpcRole.BARD, null, 12) != null;
                    case PRIEST -> npc(level, at, NpcRole.PRIEST, null, 10) != null;
                    case SMITH -> merchant(level, at, VillagerProfession.WEAPONSMITH, "blacksmith", "entity.skycraft.blacksmith") != null;
                    case MERCHANT -> merchant(level, at, VillagerProfession.CARTOGRAPHER, "general", "entity.skycraft.merchant") != null;
                    case ALCHEMIST -> merchant(level, at, VillagerProfession.CLERIC, "apothecary", "entity.skycraft.apothecary") != null;
                    case FARMER -> merchant(level, at, VillagerProfession.FARMER, "food", "entity.skycraft.farmer") != null;
                    case MILLER, LUMBERJACK -> npc(level, at, NpcRole.LUMBERJACK, null, 24) != null;
                    case MINER -> npc(level, at, NpcRole.MINER, null, 24) != null;
                    case JEWELER -> merchant(level, at, VillagerProfession.TOOLSMITH, "jeweler", "entity.skycraft.jeweller") != null;
                    case BOOKSELLER -> merchant(level, at, VillagerProfession.LIBRARIAN, "library", "entity.skycraft.bookseller") != null;
                    case TAILOR -> merchant(level, at, VillagerProfession.SHEPHERD, "general", "entity.skycraft.tailor") != null;
                    case BUTCHER -> merchant(level, at, VillagerProfession.BUTCHER, "food", "entity.skycraft.butcher", "hunter") != null;
                    case FLETCHER -> merchant(level, at, VillagerProfession.FLETCHER, "fletcher", "entity.skycraft.fletcher") != null;
                    case FENCE -> merchant(level, at, VillagerProfession.CARTOGRAPHER, "fence", "entity.skycraft.fence") != null;
                    case FISHER -> merchant(level, at, VillagerProfession.FISHERMAN, "fisher", "entity.skycraft.fishmonger", "food") != null;
                    case HUNTER -> merchant(level, at, VillagerProfession.LEATHERWORKER, "hunter", "entity.skycraft.hunter") != null;
                    case WORKER -> npc(level, at, i % 2 == 0 ? NpcRole.LUMBERJACK : NpcRole.MINER, null, 24) != null;
                    case GUARD -> guard(level, at);
                    case GUILD_LEADER, GUILD_TRAINER, GUILD_VENDOR ->
                            com.skycraft.quest.faction.FactionStaff.populate(level, a, at, e.key);
                    case JAIL_PRISONER -> npc(level, at, NpcRole.BEGGAR, Component.translatable("entity.skycraft.prisoner"), 2) != null;
                    // a bed's household: child, elder, a pair of hands at the nearest field or camp, inn cook/server
                    case BED -> res.role() != null && npc(level, at, res.role(), null, 40) != null;
                    default -> true;
                };
            } catch (RuntimeException ex) {
                Skycraft.LOGGER.warn("Could not populate {} anchor of {}", a.type(), e.key, ex);
                done = true;
            }
            if (done) {
                adopt(level, at, res, list, bed, e.key + "#" + i);
                e.spawned.add(i);
                changed = true;
            }
        }
        if (changed) data.setDirty();
    }

    /**
     * Helgen after the attack (the intro's "ruined" set): the soldiers stay at their posts, everyone else who would have
     * kept a shop, an inn or a court is a dazed survivor sitting among the rubble, and only some of the households are
     * still there. Returns true when the anchor is dealt with.
     */
    private static boolean ruinedHelgen(ServerLevel level, BlockPos at, AnchorType t, int i) {
        switch (t) {
            case GUARD -> {
                return guard(level, at);
            }
            case JARL, HOUSECARL, STEWARD, WIZARD, BARD, PRIEST, INN, SMITH, MERCHANT, ALCHEMIST, FARMER, MILLER, LUMBERJACK, MINER, JEWELER,
                    BOOKSELLER, TAILOR, BUTCHER, FLETCHER, FENCE, FISHER, HUNTER, WORKER -> {
                // roughly one in two of the old jobs left a survivor behind
                if (i % 2 == 0) return true;
                boolean wounded = i % 4 == 1;
                return npc(level, at, wounded ? NpcRole.BEGGAR : NpcRole.CITIZEN,
                        Component.translatable(wounded ? "entity.skycraft.helgen_wounded" : "entity.skycraft.helgen_survivor"), 6) != null;
            }
            case BED -> {
                return i % 3 != 0 || npc(level, at, NpcRole.CITIZEN, Component.translatable("entity.skycraft.helgen_survivor"), 8) != null;
            }
            default -> {
                return true;
            }
        }
    }

    /** The nearest household bed anchor of the settlement that nobody holds, or null. */
    @Nullable
    private static BlockPos nearestFreeBed(ServerLevel level, List<Anchor> list, BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (Anchor b : list) {
            if (b.type() != AnchorType.BED || !Settlers.bedFree(level, b.pos())) continue;
            double d = b.pos().distSqr(from);
            if (d < bestD) {
                bestD = d;
                best = b.pos();
            }
        }
        return best;
    }

    /**
     * Ties the person just spawned at {@code at} to the housing plan: records the job, the workplace anchor (for
     * villager shopkeepers' hours and the NPC routine) and the exact bed they sleep in (their home from then on).
     */
    private static void adopt(ServerLevel level, BlockPos at, Housing.Resident res, List<Anchor> list, @Nullable BlockPos bed, String slot) {
        BlockPos post = res.work() >= 0 && res.work() < list.size() ? standing(level, list.get(res.work()).pos()) : null;
        for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(at).inflate(5),
                x -> x.isAlive() && x.tickCount <= 2 && !x.getPersistentData().contains(Housing.JOB_KEY)
                        && (x instanceof NpcEntity || x instanceof AbstractVillager || x instanceof GuardEntity))) {
            CompoundTag pd = l.getPersistentData();
            pd.putString(Housing.JOB_KEY, res.job());
            pd.putString(Housing.SLOT_KEY, slot);
            if (post != null) pd.putLong(Housing.WORK_KEY, post.asLong());
            if (res.household()) { // free hours at the plaza the plan picked for them, or indoors when it is full
                pd.putLong(com.skycraft.society.Workplaces.PLAZA_KEY,
                        res.leisure() >= 0 ? list.get(res.leisure()).pos().asLong() : Long.MIN_VALUE);
            }
            if (l instanceof NpcEntity n) {
                if (post != null) n.setWorkplace(post);
                if (bed != null) Settlers.assignBed(n, bed, 40);
            } else if (l instanceof Villager v) {
                if (bed != null) Settlers.assignBed(v, bed);
            } else if (bed != null) {
                Settlers.reserveBed(level, bed, l.getUUID()); // a barracks bed: held for the guard
            }
            return;
        }
    }

    /** Settlement tier at a position: 0 capital/city, 1 town, 2 village, 3 not in a settlement. */
    public static int tierAt(ServerLevel level, BlockPos p) {
        for (SettlementAnchors.Entry e : SettlementAnchors.get(level).entries()) {
            if (e.contains(p.getX(), p.getZ(), 16)) return tier(e);
        }
        return 3;
    }

    /** Minimum gap between two freshly placed people. Tuning knob. */
    private static final double SPAWN_GAP = 2.5D;

    private static boolean doorway(ServerLevel level, BlockPos p) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    var b = level.getBlockState(p.offset(dx, dy, dz)).getBlock();
                    if (b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.FenceGateBlock
                            || b instanceof net.minecraft.world.level.block.TrapDoorBlock) return true;
                }
            }
        }
        return false;
    }

    /**
     * The anchor itself when it is free standing room (1x2 clearance, floor underneath, not in a gate or doorway, nobody
     * within {@link #SPAWN_GAP}); else the nearest such spot within three blocks; else null (try again later).
     */
    static BlockPos freeSpot(ServerLevel level, BlockPos at) {
        RandomSource r = level.getRandom();
        for (int i = 0; i < 14; i++) {
            int dx = i == 0 ? 0 : r.nextInt(7) - 3;
            int dz = i == 0 ? 0 : r.nextInt(7) - 3;
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos p = at.offset(dx, dy, dz);
                if (!level.isLoaded(p)) continue;
                if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) continue;
                if (doorway(level, p)) continue;
                boolean crowded = !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        new net.minecraft.world.phys.AABB(p).inflate(SPAWN_GAP),
                        l -> l.isAlive() && (l instanceof NpcEntity || l instanceof net.minecraft.world.entity.npc.AbstractVillager
                                || l instanceof GuardEntity)).isEmpty();
                if (!crowded) return p;
            }
        }
        return null;
    }

    /** The position to stand an NPC at: the anchor, or the block above it when the anchor is solid (bed, throne). */
    static BlockPos standing(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty() ? p : p.above();
    }

    static NpcEntity npc(ServerLevel level, BlockPos at, NpcRole role, Component name, int homeRadius) {
        at = freeSpot(level, at);
        if (at == null) return null;
        NpcEntity npc = Npcs.create(level, at, role);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        if (name != null) npc.setCustomName(name);
        npc.setHome(at, homeRadius);
        npc.getPersistentData().putBoolean("skycraft_settler", true);
        return level.addFreshEntity(npc) ? npc : null;
    }

    static NpcEntity steward(ServerLevel level, BlockPos at, SettlementAnchors.Entry e) {
        at = freeSpot(level, at);
        if (at == null) return null;
        NpcEntity npc = Npcs.create(level, at, NpcRole.COURIER);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        npc.setCustomName(Component.translatable("entity.skycraft.steward", firstName(npc.isFemale(), level.getRandom())));
        npc.getPersistentData().putBoolean("skycraft_steward", true);
        npc.getPersistentData().putString("skycraft_steward_of", e.key);
        npc.setHome(at, 8);
        return level.addFreshEntity(npc) ? npc : null;
    }

    static boolean guard(ServerLevel level, BlockPos at) {
        at = freeSpot(level, at);
        if (at == null) return false;
        GuardEntity g = ModEntities.GUARD.get().create(level);
        if (g == null) return false;
        g.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        g.setHome(at);
        g.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);
        g.setPersistenceRequired();
        return level.addFreshEntity(g);
    }

    /**
     * A townsperson merchant with a Skycraft shop of the given pool. Made directly as a society NPC: vanilla villagers
     * are cancelled and replaced by {@link com.skycraft.society.VillagerConversion}, which made a failed
     * {@code addFreshEntity} look like "not spawned yet" and turned every retry of an anchor into one more resident.
     */
    static NpcEntity merchant(ServerLevel level, BlockPos at, VillagerProfession prof, String pool, String nameKey, String... morePools) {
        at = freeSpot(level, at);
        if (at == null) return null;
        NpcRole role = com.skycraft.society.VillagerConversion.roleFor(prof);
        NpcEntity npc = Npcs.create(level, at, role);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        npc.setCustomName(Component.translatable(nameKey, firstName(npc.race(), npc.isFemale(), level.getRandom())));
        CompoundTag pd = npc.getPersistentData();
        var key = net.minecraftforge.registries.ForgeRegistries.VILLAGER_PROFESSIONS.getKey(prof);
        if (key != null) pd.putString(Npcs.PROFESSION_KEY, key.toString());
        CompoundTag shop = new CompoundTag();
        ListTag pools = new ListTag();
        pools.add(StringTag.valueOf(pool));
        for (String extra : morePools) pools.add(StringTag.valueOf(extra));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        pd.put(Shop.KEY, shop);
        npc.setHome(at, 28);
        pd.putBoolean("skycraft_settler", true);
        return level.addFreshEntity(npc) ? npc : null;
    }

    /**
     * An innkeeper following the survival module's contract ({@link Innkeepers#TAG}, settlement id, inn name index), so
     * renting rooms, rumours and the inn's stock all work. Waits until the roads module has registered the town.
     */
    static boolean innkeeper(ServerLevel level, BlockPos at, SettlementAnchors.Entry e) {
        Settlement s = RoadsData.get(level).zoneAt(e.x, e.z);
        if (s == null) return false;
        // one innkeeper per inn: if the survival module already sent one, it stays
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(at).inflate(24, 12, 24),
                l -> l.isAlive() && Innkeepers.isInnkeeper(l)).isEmpty()) return true;
        BlockPos spot = freeSpot(level, at);
        if (spot == null) return false;
        NpcEntity v = Npcs.create(level, spot, NpcRole.INNKEEPER);
        if (v == null) return false;
        String name = innkeeperName(e.key, level.getRandom());
        v.setCustomName(Component.translatable("entity.skycraft.innkeeper", name));
        v.setPersistenceRequired();
        v.addTag(Innkeepers.TAG);
        CompoundTag pd = v.getPersistentData();
        pd.putBoolean(Innkeepers.TAG, true);
        pd.putInt(Innkeepers.SETTLEMENT_KEY, s.id);
        int inn = Cities.innIndex(e.key);
        pd.putInt(Innkeepers.INN_KEY, inn >= 0 ? inn : Math.floorMod(e.key.hashCode(), Innkeepers.INN_NAMES));
        // a city plan names its own inn (lang settlement.skycraft.name.<key>), no index into the survival list needed
        String planned = com.skycraft.tamriel.settlement.plan.CityPlans.innKey(e.key);
        if (planned != null && inn < 0) pd.putString(Innkeepers.INN_NAME_KEY, planned);
        CompoundTag shop = new CompoundTag();
        ListTag pools = new ListTag();
        pools.add(StringTag.valueOf("food"));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        pd.put(Shop.KEY, shop);
        v.setHome(spot, 12);
        pd.putBoolean("skycraft_settler", true);
        return level.addFreshEntity(v);
    }

    static String firstName(boolean female, RandomSource r) {
        return com.skycraft.society.NpcNames.generate(com.skycraft.society.NpcNames.raceFor(NpcRole.COURIER, r), female, r);
    }

    /** A first name in the style of the person's own people. */
    static String firstName(com.skycraft.society.NpcNames.Race race, boolean female, RandomSource r) {
        return com.skycraft.society.NpcNames.generate(race, female, r);
    }

    static String innkeeperName(String key, RandomSource r) {
        return switch (key) {
            case "whiterun" -> "Hulda";
            case "riverwood" -> "Orgnar";
            case "windhelm" -> "Elda";
            case "solitude" -> "Corpulus";
            case "markarth" -> "Kleppr";
            case "winterhold" -> "Dagur";
            case "riften" -> "Keerava";
            case "morthal" -> "Jonna";
            case "dawnstar" -> "Thoring";
            case "falkreath" -> "Valga";
            case "kynesgrove" -> "Iddra";
            case "nightgate_inn" -> "Hadring";
            case "rorikstead" -> "Mralki";
            case "old_hroldan" -> "Eydis";
            case "raven_rock" -> "Geldis";
            case "dragon_bridge" -> "Faida";
            case "ivarstead" -> "Wilhelm";
            default -> {
                String[] names = {"Hilde", "Bergljot", "Torvar", "Gunnar", "Asta", "Ingrid", "Brenuin", "Sigrid", "Varnius", "Dravynea"};
                yield names[Math.floorMod(key.hashCode(), names.length)];
            }
        };
    }

    static Component jarlName(String key) {
        String name = switch (key) {
            case "whiterun" -> "Balgruuf the Greater";
            case "solitude" -> "Elisif the Fair";
            case "windhelm" -> "Ulfric Stormcloak";
            case "riften" -> "Laila Law-Giver";
            case "markarth" -> "Igmund";
            case "winterhold" -> "Korir";
            case "falkreath" -> "Siddgeir";
            case "morthal" -> "Idgrod Ravencrone";
            case "dawnstar" -> "Skald the Elder";
            default -> null;
        };
        return name == null ? null : Component.translatable("entity.skycraft.jarl", name);
    }
}
