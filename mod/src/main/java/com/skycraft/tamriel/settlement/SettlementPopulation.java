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
import net.minecraft.world.entity.npc.villager.AbstractVillager;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts people where a settlement's anchors say they belong, through the existing society/economy/creature APIs:
 * the Jarl on the throne with housecarl, steward and court wizard; innkeepers behind their bars (the survival
 * module's innkeeper contract) and bards by the fire; smiths at the forge, merchants and apothecaries behind their
 * counters (economy merchants); priests at the altar; the watch is not placed here ({@link com.skycraft.society.GuardWatch}); farmers, millers,
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

    /** Entry identity isolates saves; an exact immutable snapshot detects replacements as well as appends. */
    private record CachedHousing(List<Anchor> anchors, int tier, Housing.Plan plan) {}
    private static final Map<SettlementAnchors.Entry, CachedHousing> PLANS = new java.util.WeakHashMap<>();

    public static synchronized Housing.Plan housing(SettlementAnchors.Entry e) {
        int tier = tier(e);
        CachedHousing cached = PLANS.get(e);
        if (cached == null || cached.tier != tier || !cached.anchors.equals(e.anchors)) {
            cached = new CachedHousing(List.copyOf(e.anchors), tier, Housing.plan(e.key, e.anchors, tier));
            PLANS.put(e, cached);
        }
        return cached.plan;
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
        Map<Integer, com.skycraft.society.SocietyResidents.Entry> authoredBySlot = plan.resolveAuthoredResidents(e.key);
        boolean capital = tier(e) == 0;
        boolean changed = false;
        NpcEntity jarl = null;
        String regionId = e.place().map(Place::regionId)
                .orElseGet(() -> com.skycraft.tamriel.Regions.at(level, new BlockPos(e.x, 64, e.z)).id());
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
                if (bed == null) { // bed chunk not loaded or temporarily unavailable: try again on a later tick
                    continue;
                }
            }
            BlockPos at = standing(level, a.pos());
            com.skycraft.society.SocietyResidents.Entry authored = authoredBySlot.get(i);
            String authoredName = authored != null ? authored.name() : null;
            boolean done;
            try {
                AnchorType t = res.slotType();
                if ("helgen".equals(e.key) && com.skycraft.quest.QuestConfig.RUINED_HELGEN.get()) {
                    // the dragon has been: only survivors and shaken soldiers, no shopkeepers, innkeeper, bard or court
                    done = ruinedHelgen(level, at, t, i, a.owner(), list);
                    if (done) {
                        adopt(level, at, res, list, bed, e.key + "#" + i, regionId, null);
                        e.spawned.add(i);
                        changed = true;
                    }
                    continue;
                }
                if (res.role() == NpcRole.TRAINER && t != AnchorType.GUILD_TRAINER) {
                    done = trainer(level, at, e, regionId, res.job(), i) != null;
                } else {
                    done = switch (t) {
                        case JARL -> {
                            jarl = npc(level, at, NpcRole.JARL, jarlName(e.key), 10);
                            yield jarl != null;
                        }
                        case HOUSECARL -> {
                            NpcEntity hc = npc(level, at, NpcRole.HOUSECARL, com.skycraft.society.Courts.housecarlDisplayName(e.key), 16);
                            if (hc != null) {
                                NpcEntity leader = jarl;
                                if (leader == null) {
                                    var found = level.getEntitiesOfClass(NpcEntity.class, new net.minecraft.world.phys.AABB(at).inflate(32),
                                            x -> x.isAlive() && x.role() == NpcRole.JARL);
                                    if (!found.isEmpty()) leader = found.get(0);
                                }
                                if (leader != null) hc.setLeader(leader.getUUID());
                            }
                            yield hc != null;
                        }
                        case STEWARD -> steward(level, at, e) != null;
                        case WIZARD -> npc(level, at, NpcRole.MAGE, com.skycraft.society.Courts.wizardDisplayName(e.key), 10) != null;
                        case INN -> innkeeper(level, at, e);
                        case BARD -> npc(level, at, NpcRole.BARD, null, 12) != null;
                        case PRIEST -> npc(level, at, NpcRole.PRIEST, null, 10) != null;
                        case SMITH -> merchant(level, at, VillagerProfession.WEAPONSMITH, "blacksmith", "entity.skycraft.blacksmith", authoredName) != null;
                        case MERCHANT -> merchant(level, at, VillagerProfession.CARTOGRAPHER, "general", "entity.skycraft.merchant", authoredName) != null;
                        case ALCHEMIST -> merchant(level, at, VillagerProfession.CLERIC, "apothecary", "entity.skycraft.apothecary", authoredName) != null;
                        case FARMER -> merchant(level, at, VillagerProfession.FARMER, "food", "entity.skycraft.farmer", authoredName) != null;
                        case MILLER, LUMBERJACK -> npc(level, at, NpcRole.LUMBERJACK, null, 24) != null;
                        case MINER -> npc(level, at, NpcRole.MINER, null, 24) != null;
                        case JEWELER -> merchant(level, at, VillagerProfession.TOOLSMITH, "jeweler", "entity.skycraft.jeweller", authoredName) != null;
                        case BOOKSELLER -> merchant(level, at, VillagerProfession.LIBRARIAN, "library", "entity.skycraft.bookseller", authoredName) != null;
                        case TAILOR -> merchant(level, at, VillagerProfession.SHEPHERD, "general", "entity.skycraft.tailor", authoredName) != null;
                        case BUTCHER -> merchant(level, at, VillagerProfession.BUTCHER, "food", "entity.skycraft.butcher", authoredName, "hunter") != null;
                        case FLETCHER -> merchant(level, at, VillagerProfession.FLETCHER, "fletcher", "entity.skycraft.fletcher", authoredName) != null;
                        case FENCE -> merchant(level, at, VillagerProfession.CARTOGRAPHER, "fence", "entity.skycraft.fence", authoredName) != null;
                        case FISHER -> merchant(level, at, VillagerProfession.FISHERMAN, "fisher", "entity.skycraft.fishmonger", authoredName, "food") != null;
                        case HUNTER -> merchant(level, at, VillagerProfession.LEATHERWORKER, "hunter", "entity.skycraft.hunter", authoredName) != null;
                        case WORKER -> npc(level, at, i % 2 == 0 ? NpcRole.LUMBERJACK : NpcRole.MINER, null, 24) != null;
                        case GUARD -> guard(level, at);
                        case GUILD_LEADER, GUILD_VENDOR ->
                                com.skycraft.quest.faction.FactionStaff.populate(level, a, at, e.key);
                        case GUILD_TRAINER -> {
                            boolean ok = com.skycraft.quest.faction.FactionStaff.populate(level, a, at, e.key);
                            if (!ok && res.role() == NpcRole.TRAINER) {
                                yield trainer(level, at, e, regionId, res.job(), i) != null;
                            }
                            yield ok;
                        }
                        case JAIL_PRISONER -> npc(level, at, NpcRole.BEGGAR, Component.translatable("entity.skycraft.prisoner"), 2) != null;
                        // a bed's household (or a court member / trainer seated from a bed slot)
                        case BED, GUILD_BED, GUEST_BED -> {
                            if ("steward".equals(res.job())) yield steward(level, at, e) != null;
                            if ("jarl".equals(res.job())) yield npc(level, at, NpcRole.JARL, jarlName(e.key), 10) != null;
                            if ("court_wizard".equals(res.job())) yield npc(level, at, NpcRole.MAGE, com.skycraft.society.Courts.wizardDisplayName(e.key), 10) != null;
                            if ("housecarl".equals(res.job())) yield npc(level, at, NpcRole.HOUSECARL, com.skycraft.society.Courts.housecarlDisplayName(e.key), 16) != null;
                            yield res.role() != null && npc(level, at, res.role(), null, 40) != null;
                        }
                        default -> true;
                    };
                }
            } catch (RuntimeException ex) {
                Skycraft.LOGGER.warn("Could not populate {} anchor of {}", a.type(), e.key, ex);
                done = true;
            }
            if (done) {
                adopt(level, at, res, list, bed, e.key + "#" + i, regionId, authored);
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
    private static BlockPos ruinedSurvivorSpot(ServerLevel level, BlockPos at, List<Anchor> list, int slotIdx) {
        BlockPos meeting = null;
        for (Anchor a : list) {
            if (a.type() == AnchorType.MEETING) {
                meeting = a.pos();
                break;
            }
        }
        BlockPos base = meeting != null ? meeting : at;
        double ang = (slotIdx * 2.399963229728653) % (Math.PI * 2);
        int dist = 3 + (slotIdx % 6);
        BlockPos candidate = base.offset((int) Math.round(Math.cos(ang) * dist), 0, (int) Math.round(Math.sin(ang) * dist));
        if (level.isLoaded(candidate)) {
            BlockPos outdoor = com.skycraft.society.NpcPlacement.outdoorGroundAt(level, candidate.getX(), candidate.getZ(), base.getY());
            if (outdoor != null && !com.skycraft.society.NpcPlacement.isFireHazard(level, outdoor)) {
                return outdoor;
            }
        }
        return at;
    }

    private static boolean ruinedHelgen(ServerLevel level, BlockPos at, AnchorType t, int i, String owner, List<Anchor> list) {
        switch (t) {
            case GUARD -> {
                return guard(level, at);
            }
            case MERCHANT -> {
                if ("helgen_cart".equals(owner)) {
                    // the salvage cart's merchant still sells (barter only: the dialogue offers nothing else)
                    NpcEntity m = merchant(level, at, VillagerProfession.CARTOGRAPHER, "general", "entity.skycraft.merchant");
                    if (m == null) return false;
                    m.getPersistentData().putBoolean(com.skycraft.fire.aftermath.HelgenFolk.CART, true);
                    return true;
                }
                if (i % 2 == 0) return true;
                return npc(level, ruinedSurvivorSpot(level, at, list, i), NpcRole.CITIZEN, Component.translatable("entity.skycraft.helgen_survivor"), 10) != null;
            }
            case JARL, HOUSECARL, STEWARD, WIZARD, BARD, PRIEST, INN, SMITH, ALCHEMIST, FARMER, MILLER, LUMBERJACK, MINER, JEWELER,
                    BOOKSELLER, TAILOR, BUTCHER, FLETCHER, FENCE, FISHER, HUNTER, WORKER -> {
                // roughly one in two of the old jobs left a survivor behind
                if (i % 2 == 0) return true;
                boolean wounded = i % 4 == 1;
                return npc(level, ruinedSurvivorSpot(level, at, list, i), wounded ? NpcRole.BEGGAR : NpcRole.CITIZEN,
                        Component.translatable(wounded ? "entity.skycraft.helgen_wounded" : "entity.skycraft.helgen_survivor"), 10) != null;
            }
            case BED -> {
                return i % 3 != 0 || npc(level, ruinedSurvivorSpot(level, at, list, i), NpcRole.CITIZEN, Component.translatable("entity.skycraft.helgen_survivor"), 12) != null;
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
            if ((b.type() != AnchorType.BED && b.type() != AnchorType.GUILD_BED && b.type() != AnchorType.GUEST_BED)
                    || !Settlers.bedFree(level, b.pos())) continue;
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
     * villager shopkeepers' hours and the NPC routine), the causal world-sheet {@code why} field IDs (MS22),
     * and the exact bed they sleep in (their home from then on).
     */
    private static void adopt(ServerLevel level, BlockPos at, Housing.Resident res, List<Anchor> list,
                              @Nullable BlockPos bed, String slot, @Nullable String regionId) {
        adopt(level, at, res, list, bed, slot, regionId, null);
    }

    private static void adopt(ServerLevel level, BlockPos at, Housing.Resident res, List<Anchor> list,
                              @Nullable BlockPos bed, String slot, @Nullable String regionId,
                              @Nullable com.skycraft.society.SocietyResidents.Entry authored) {
        BlockPos post = res.work() >= 0 && res.work() < list.size() ? standing(level, list.get(res.work()).pos()) : null;
        String settlementKey = slot.contains("#") ? slot.substring(0, slot.indexOf('#')) : slot;
        boolean ruinedHelgen = slot.startsWith("helgen#") && com.skycraft.quest.QuestConfig.RUINED_HELGEN.get();
        BlockPos searchCenter = ruinedHelgen ? ruinedSurvivorSpot(level, at, list, res.slot()) : at;
        for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(searchCenter).inflate(8),
                x -> x.isAlive() && x.tickCount <= 2 && !x.getPersistentData().contains(Housing.JOB_KEY)
                        && (x instanceof NpcEntity || x instanceof AbstractVillager || x instanceof GuardEntity))) {
            CompoundTag pd = l.getPersistentData();
            String job = ruinedHelgen
                    && !pd.getBooleanOr(com.skycraft.fire.aftermath.HelgenFolk.CART, false) && !(l instanceof GuardEntity)
                    ? "survivor" : res.job();
            pd.putString(Housing.JOB_KEY, job);
            pd.putString(Housing.SLOT_KEY, slot);
            if (l instanceof NpcEntity n && authored != null && !"survivor".equals(job)) {
                com.skycraft.society.Courts.Spec courtSpec = com.skycraft.society.Courts.courtFor(settlementKey);
                boolean hasCourtOverride = courtSpec != null && switch (n.role()) {
                    case JARL -> courtSpec.jarl() != null && !courtSpec.jarl().isEmpty();
                    case STEWARD -> courtSpec.steward() != null && !courtSpec.steward().isEmpty();
                    case HOUSECARL -> courtSpec.housecarl() != null && !courtSpec.housecarl().isEmpty();
                    case MAGE -> courtSpec.courtWizard() != null && !courtSpec.courtWizard().isEmpty();
                    default -> false;
                };
                boolean hasShopWrap = pd.contains(Shop.KEY) || n.role() == NpcRole.INNKEEPER || n.role() == NpcRole.TRAINER;
                if (n.role() == NpcRole.INNKEEPER) {
                    n.setCustomName(Component.translatable("entity.skycraft.innkeeper", authored.name()));
                }
                com.skycraft.society.SocietyResidents.applyToNpc(
                        n,
                        authored,
                        regionId,
                        slot.hashCode(),
                        !hasCourtOverride && !hasShopWrap
                );
            }
            if (regionId != null && !regionId.isEmpty()) {
                List<String> why = res.why(regionId, authored);
                if (!why.isEmpty()) {
                    pd.putString(Housing.WHY_KEY, String.join(",", why));
                }
            }
            if ("survivor".equals(job)) {
                if (l instanceof NpcEntity n) {
                    n.setWorkplace(null);
                    n.setBed(null);
                    n.setHome(l.blockPosition(), 12);
                }
                return;
            }
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
     * The anchor itself when it is free standing room (1x2 clearance, floor underneath, not in a gate or doorway,
     * not on a roof, slab, or furniture, nobody within {@link #SPAWN_GAP}); else the nearest such spot within three
     * blocks; else null (try again later).
     */
    static BlockPos freeSpot(ServerLevel level, BlockPos at) {
        boolean anchorIndoors = !level.canSeeSky(at) && !level.canSeeSky(at.above());
        for (int dy : new int[]{0, -1, 1}) {
            for (int r = 0; r <= 3; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos p = at.offset(dx, dy, dz);
                        if (!level.isLoaded(p)) continue;
                        if (anchorIndoors && (level.canSeeSky(p) || level.canSeeSky(p.above()))) continue;
                        if (level.canSeeSky(p) && com.skycraft.society.NpcPlacement.hasRoomBelow(level, p)) continue;
                        if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                                || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                                || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                                || !level.getFluidState(p).isEmpty()) continue;
                        if (doorway(level, p)
                                || com.skycraft.society.NpcPlacement.isFireHazard(level, p)
                                || com.skycraft.society.NpcPlacement.isSlabHazard(level, p)
                                || com.skycraft.society.NpcPlacement.isFurnitureHazard(level, p)) continue;
                        boolean crowded = !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                                new net.minecraft.world.phys.AABB(p).inflate(SPAWN_GAP),
                                l -> l.isAlive() && (l instanceof NpcEntity || l instanceof net.minecraft.world.entity.npc.villager.AbstractVillager
                                        || l instanceof GuardEntity)).isEmpty();
                        if (!crowded) return p;
                    }
                }
            }
        }
        return null;
    }

    /** The floor-level anchor position: freeSpot finds the nearest clear floor cell beside any bed or station. */
    static BlockPos standing(ServerLevel level, BlockPos p) {
        return p;
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
        NpcEntity npc = Npcs.create(level, at, NpcRole.STEWARD);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        npc.setCustomName(com.skycraft.society.Courts.stewardDisplayName(e.key));
        npc.getPersistentData().putBoolean("skycraft_steward", true);
        npc.getPersistentData().putString("skycraft_steward_of", e.key);
        npc.getPersistentData().putBoolean("skycraft_settler", true);
        npc.setHome(at, 12);
        return level.addFreshEntity(npc) ? npc : null;
    }

    @Nullable
    static NpcEntity trainer(ServerLevel level, BlockPos at, SettlementAnchors.Entry e,
                             @Nullable String regionId, String jobId, int slotIdx) {
        at = freeSpot(level, at);
        if (at == null) return null;
        return com.skycraft.society.SocietyTrainers.spawnSettledTrainer(level, at, e.key, tier(e), regionId, jobId, slotIdx);
    }

    static boolean guard(ServerLevel level, BlockPos at) {
        at = freeSpot(level, at);
        if (at == null) return false;
        GuardEntity g = ModEntities.GUARD.get().create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        if (g == null) return false;
        g.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        // only a scripted garrison (Helgen's soldiers) is placed from guard anchors now: the watch is society.GuardWatch's
        g.getPersistentData().putBoolean(com.skycraft.society.GuardWatch.SCRIPTED, true);
        g.setHome(at);
        g.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
        g.setPersistenceRequired();
        return level.addFreshEntity(g);
    }

    /**
     * A townsperson merchant with a Skycraft shop of the given pool. Made directly as a society NPC: vanilla villagers
     * are cancelled and replaced by {@link com.skycraft.society.VillagerConversion}, which made a failed
     * {@code addFreshEntity} look like "not spawned yet" and turned every retry of an anchor into one more resident.
     */
    static NpcEntity merchant(ServerLevel level, BlockPos at, net.minecraft.resources.ResourceKey<VillagerProfession> prof, String pool, String nameKey) {
        return merchant(level, at, prof, pool, nameKey, null);
    }

    static NpcEntity merchant(ServerLevel level, BlockPos at, net.minecraft.resources.ResourceKey<VillagerProfession> prof, String pool, String nameKey,
                              @Nullable String authoredName, String... morePools) {
        at = freeSpot(level, at);
        if (at == null) return null;
        NpcRole role = com.skycraft.society.VillagerConversion.roleFor(prof);
        NpcEntity npc = Npcs.create(level, at, role);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        String chosenName = (authoredName != null && !authoredName.isEmpty())
                ? authoredName
                : firstName(npc.race(), npc.isFemale(), level.getRandom());
        npc.setCustomName(Component.translatable(nameKey, chosenName));
        CompoundTag pd = npc.getPersistentData();
        var key = prof.identifier();
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
                com.skycraft.society.SocietyResidents.Entry inn = com.skycraft.society.SocietyResidents.innkeeperFor(key);
                if (inn != null && !inn.name().isEmpty()) {
                    yield inn.name();
                }
                String regionId = com.skycraft.tamriel.Places.byId(key).map(Place::regionId).orElse("skyrim/whiterun");
                com.skycraft.society.NpcNames.Race race = com.skycraft.society.Cultures.majority(com.skycraft.tamriel.Regions.get(regionId));
                boolean female = (key.hashCode() & 1) != 0;
                RandomSource seeded = RandomSource.create(key.hashCode() ^ 0x9E3779B97F4A7C15L);
                yield com.skycraft.society.NpcNames.generate(race, female, seeded);
            }
        };
    }

    static Component jarlName(String key) {
        return com.skycraft.society.Courts.jarlDisplayName(key);
    }
}
