package com.nirncraft.tamriel.settlement;

import com.nirncraft.economy.Houses;
import com.nirncraft.society.Cultures;
import com.nirncraft.society.Housing;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.SocietyResidents;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.settlement.build.Canvas;
import com.nirncraft.tamriel.settlement.grammar.Use;
import com.nirncraft.tamriel.settlement.plan.Archetype;
import com.nirncraft.tamriel.settlement.plan.CityPlans;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the human-readable building or homeowner title for a settlement door so the door hover
 * interaction HUD shows whose home or shop the player is looking at (e.g. "Alvor and Sigrid's House",
 * "Faendal's House", "Halvar's Cottage", "The Sleeping Giant Inn").
 */
public final class SettlementDoors {
    private SettlementDoors() {}

    @Nullable
    public static Component describeDoor(ServerLevel level, BlockPos rawPos) {
        if (level == null || rawPos == null) return null;
        BlockState state = level.getBlockState(rawPos);
        BlockPos pos = (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
                ? rawPos.below()
                : rawPos;

        // 1. Check purchasable player homes first (Breezehome, Honeyside, Proudspire Manor, etc.)
        Optional<SettlementAnchors.Entry> nearEntry = SettlementAnchors.near(level, pos, 96);
        if (nearEntry.isPresent()) {
            SettlementAnchors.Entry e = nearEntry.get();
            for (Anchor a : e.anchors(AnchorType.HOUSE_DOOR)) {
                if (a.pos().distManhattan(pos) <= 2) {
                    for (Houses.HouseDef def : Houses.HOUSES) {
                        if (!def.homestead() && def.placeId().equals(e.key)) {
                            return def.displayName();
                        }
                    }
                }
            }
        }

        // 2. Resolve building owner string from BUILDING_DOOR anchors, Canvas entrance map, or interior anchors
        SettlementAnchors.Entry entry = nearEntry.orElse(null);
        String owner = null;
        if (entry != null) {
            for (Anchor a : entry.anchors(AnchorType.BUILDING_DOOR)) {
                if (a.owner() != null && !a.owner().isEmpty() && !"street".equals(a.owner()) && a.pos().distManhattan(pos) <= 2) {
                    owner = a.owner();
                    break;
                }
            }
        }
        if (owner == null) {
            owner = Canvas.entranceOwner(pos);
        }
        if (owner == null && entry != null) {
            net.minecraft.core.Direction intoBuilding = state.getBlock() instanceof DoorBlock
                    ? state.getValue(DoorBlock.FACING)
                    : null;
            double bestScore = Double.MAX_VALUE;
            for (Anchor a : entry.anchors()) {
                if (a.owner() == null || a.owner().isEmpty() || "street".equals(a.owner())) continue;
                double d = a.pos().distSqr(pos);
                if (d > 16.0 * 16.0) continue;
                double score = d;
                if (intoBuilding != null) {
                    int dx = a.pos().getX() - pos.getX();
                    int dz = a.pos().getZ() - pos.getZ();
                    int dot = dx * intoBuilding.getStepX() + dz * intoBuilding.getStepZ();
                    if (dot < 0) score += 400.0; // behind the door (outside in the street / another building)
                }
                if (score < bestScore) {
                    bestScore = score;
                    owner = a.owner();
                }
            }
        }
        if (owner == null || owner.isEmpty() || "street".equals(owner)) {
            return null;
        }

        int hashIdx = owner.indexOf('#');
        String base = (hashIdx >= 0 ? owner.substring(0, hashIdx) : owner).toLowerCase(Locale.ROOT);
        if (base.isEmpty()) return null;

        // 3. Specific authored building name in lang (e.g. "alvor_and_sigrids_house", "sleeping_giant_inn", "dragonsreach")
        if (!isGenericKindOrUse(base)) {
            return Component.translatable("settlement.nirncraft.name." + base);
        }

        // 4. Generic residential home: resolve the homeowner's name from bed/resident assignment or deterministic seed
        if (isResidentialBase(base)) {
            String dwellingNoun = dwellingNoun(base);
            String residentName = resolveHomeownerName(level, pos, entry, owner);
            if (residentName != null && !residentName.isEmpty()) {
                if (residentName.toLowerCase(Locale.ROOT).contains("house")
                        || residentName.toLowerCase(Locale.ROOT).contains("cottage")
                        || residentName.toLowerCase(Locale.ROOT).contains("manor")
                        || residentName.toLowerCase(Locale.ROOT).contains("farm")) {
                    return Component.literal(residentName);
                }
                return Component.literal(possessive(residentName) + " " + dwellingNoun);
            }
            return Component.translatable("settlement.nirncraft.kind." + base);
        }

        // 5. Generic inn: check settlement's named inn first
        if (base.equals("inn") && entry != null) {
            String plannedInn = CityPlans.innKey(entry.key);
            if (plannedInn != null && !plannedInn.isEmpty()) {
                return Component.translatable("settlement.nirncraft.name." + plannedInn);
            }
        }

        // 6. Generic shop, temple, guild, keep, or guard building
        return Component.translatable("settlement.nirncraft.kind." + base);
    }

    private static boolean isGenericKindOrUse(String base) {
        if (Use.byId(base) != null) return true;
        for (Archetype a : Archetype.VALUES) {
            if (a.name().toLowerCase(Locale.ROOT).equals(base)) return true;
        }
        return base.equals("jail") || base.equals("guardhouse") || base.equals("market_shop") || base.equals("mead_hall");
    }

    private static boolean isResidentialBase(String base) {
        return switch (base) {
            case "hovel", "cottage", "house", "townhouse", "manor", "farmhouse", "shack" -> true;
            default -> false;
        };
    }

    private static String dwellingNoun(String base) {
        return switch (base) {
            case "cottage", "hovel" -> "Cottage";
            case "manor" -> "Manor";
            case "townhouse" -> "Townhouse";
            case "farmhouse" -> "Farmhouse";
            case "shack" -> "Cabin";
            default -> "House";
        };
    }

    private static String possessive(String name) {
        String trimmed = name.trim();
        return trimmed.endsWith("s") ? (trimmed + "'") : (trimmed + "'s");
    }

    private static String resolveHomeownerName(
            ServerLevel level, BlockPos doorPos,
            @Nullable SettlementAnchors.Entry entry, String buildingOwner
    ) {
        Set<Long> buildingBeds = new HashSet<>();
        if (entry != null) {
            List<Anchor> anchors = entry.anchors();
            for (Anchor a : anchors) {
                if (buildingOwner.equals(a.owner()) && (a.type() == AnchorType.BED || a.type() == AnchorType.HOUSE_BED)) {
                    buildingBeds.add(a.pos().asLong());
                }
            }

            // Check authored residents assigned to any slot or bed in this building
            try {
                Housing.Plan plan = SettlementPopulation.housing(entry);
                Map<Integer, SocietyResidents.Entry> authored = plan.resolveAuthoredResidents(entry.key);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < anchors.size(); i++) {
                    Anchor a = anchors.get(i);
                    Housing.Resident res = plan.at(i);
                    boolean inBuilding = buildingOwner.equals(a.owner());
                    if (!inBuilding && res != null && res.bed() >= 0 && res.bed() < anchors.size()) {
                        inBuilding = buildingOwner.equals(anchors.get(res.bed()).owner());
                    }
                    if (inBuilding) {
                        SocietyResidents.Entry auth = authored.get(i);
                        if (auth != null && auth.name() != null && !auth.name().isEmpty() && !names.contains(auth.name())) {
                            names.add(auth.name());
                        }
                    }
                }
                if (!names.isEmpty()) {
                    return names.size() >= 2 ? (names.get(0) + " and " + names.get(1)) : names.get(0);
                }
            } catch (Throwable ignored) {
            }
        }

        // Check live spawned NpcEntity residents whose claimed bed is in this building
        try {
            List<NpcEntity> nearby = level.getEntitiesOfClass(NpcEntity.class, new AABB(doorPos).inflate(40, 14, 40));
            List<String> liveNames = new ArrayList<>();
            for (NpcEntity npc : nearby) {
                BlockPos bed = npc.getBed();
                if (bed != null && (buildingBeds.contains(bed.asLong()) || bed.distManhattan(doorPos) <= 7)) {
                    String n = extractPersonalName(npc);
                    if (n != null && !n.isEmpty() && !liveNames.contains(n)) {
                        liveNames.add(n);
                    }
                }
            }
            if (!liveNames.isEmpty()) {
                return liveNames.size() >= 2 ? (liveNames.get(0) + " and " + liveNames.get(1)) : liveNames.get(0);
            }
        } catch (Throwable ignored) {
        }

        // Deterministic fallback homeowner name seeded by settlement key + building owner id
        String settlementKey = entry != null ? entry.key : "tamriel";
        String regionId = Places.byId(settlementKey).map(Place::regionId)
                .orElseGet(() -> Regions.at(doorPos.getX(), doorPos.getZ()).id());
        NpcNames.Race race = Cultures.majority(Regions.get(regionId));
        long seed = ((long) settlementKey.hashCode() << 32) ^ (buildingOwner.hashCode() * 0x9E3779B97F4A7C15L);
        RandomSource rng = RandomSource.create(seed);
        boolean female = rng.nextBoolean();
        return NpcNames.generate(race, female, rng);
    }

    @Nullable
    private static String extractPersonalName(NpcEntity npc) {
        Component custom = npc.getCustomName();
        if (custom == null) return null;
        String s = custom.getString().trim();
        if (s.isEmpty()) return null;
        int paren = s.indexOf('(');
        if (paren > 0) {
            s = s.substring(0, paren).trim();
        }
        int comma = s.indexOf(',');
        if (comma > 0) {
            s = s.substring(0, comma).trim();
        }
        for (String prefix : new String[]{"Blacksmith ", "Apothecary ", "Innkeeper ", "Merchant ", "Priest ", "Farmer ", "Miner ", "Miller "}) {
            if (s.startsWith(prefix) && s.length() > prefix.length()) {
                s = s.substring(prefix.length()).trim();
            }
        }
        return s;
    }
}
