package com.skycraft.tamriel.settlement;

import com.skycraft.Skycraft;
import com.skycraft.creatures.ModEntities;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.economy.Shop;
import com.skycraft.roads.RoadsData;
import com.skycraft.roads.Settlement;
import com.skycraft.society.NpcRole;
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
 */
public final class SettlementPopulation {
    /** Upper bounds per settlement and kind (capital / town / village). */
    private static final int[] GUARDS = {12, 4, 2}, MERCHANTS = {8, 3, 2}, CITIZENS = {14, 7, 4};

    private SettlementPopulation() {}

    static void tick(ServerLevel level, ServerPlayer player) {
        SettlementAnchors data = SettlementAnchors.get(level);
        for (SettlementAnchors.Entry e : data.entries()) {
            if (!e.contains(player.getBlockX(), player.getBlockZ(), 48)) continue;
            populate(level, data, e);
        }
    }

    static int tier(SettlementAnchors.Entry e) {
        Optional<Place> p = e.place();
        if (p.isPresent() && (p.get().type() == PlaceType.CAPITAL || p.get().type() == PlaceType.CITY)) return 0;
        if (p.isPresent() && p.get().type() == PlaceType.TOWN || e.radius >= 45) return 1;
        return 2;
    }

    static void populate(ServerLevel level, SettlementAnchors data, SettlementAnchors.Entry e) {
        List<Anchor> list = e.anchors;
        Map<AnchorType, Integer> counts = new EnumMap<>(AnchorType.class);
        for (int i : e.spawned) if (i < list.size()) counts.merge(list.get(i).type(), 1, Integer::sum);
        int tier = tier(e);
        boolean capital = tier == 0;
        RandomSource r = level.getRandom();
        boolean changed = false;
        NpcEntity jarl = null;
        for (int i = 0; i < list.size(); i++) {
            if (e.spawned.contains(i)) continue;
            Anchor a = list.get(i);
            int have = counts.getOrDefault(a.type(), 0);
            int cap = switch (a.type()) {
                case JARL, STEWARD, WIZARD, HOUSECARL -> 1;
                case GUARD -> GUARDS[tier];
                case MERCHANT -> MERCHANTS[tier];
                case BED -> CITIZENS[tier];
                case INN, BARD, SMITH, ALCHEMIST, PRIEST, FARMER, MILLER, MINER, LUMBERJACK -> 3;
                // faction halls (a city may keep several) and the jail's other inmates
                case GUILD_LEADER, GUILD_VENDOR -> 12;
                case GUILD_TRAINER -> 24;
                case GUILD_BED -> 96;
                case JAIL_PRISONER -> 4;
                case JEWELER, BOOKSELLER, TAILOR, BUTCHER, FLETCHER, FISHER, HUNTER, WORKER -> 3;
                case FENCE -> 1;
                default -> 0;
            };
            if (cap == 0) continue;
            if (have >= cap) {
                e.spawned.add(i);
                changed = true;
                continue;
            }
            if (!level.isPositionEntityTicking(a.pos())) continue;
            BlockPos at = standing(level, a.pos());
            boolean done;
            try {
                done = switch (a.type()) {
                    case JARL -> {
                        if (!capital) yield true;
                        jarl = npc(level, at, NpcRole.JARL, jarlName(e.key), 10);
                        yield jarl != null;
                    }
                    case HOUSECARL -> {
                        if (!capital) yield true;
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
                    case WORKER -> npc(level, at, level.getRandom().nextBoolean() ? NpcRole.LUMBERJACK : NpcRole.MINER, null, 24) != null;
                    case GUARD -> guard(level, at);
                    case GUILD_LEADER, GUILD_TRAINER, GUILD_VENDOR, GUILD_BED ->
                            com.skycraft.quest.faction.FactionStaff.populate(level, a, at, e.key);
                    case JAIL_PRISONER -> npc(level, at, NpcRole.BEGGAR, Component.translatable("entity.skycraft.prisoner"), 2) != null;
                    case BED -> {
                        // about one townsperson for every two beds
                        if (r.nextBoolean()) yield true;
                        NpcRole role = switch (r.nextInt(5)) {
                            case 0 -> NpcRole.HUNTER;
                            case 1 -> NpcRole.LUMBERJACK;
                            case 2 -> NpcRole.MINER;
                            case 3 -> capital ? NpcRole.BARD : NpcRole.HUNTER;
                            default -> r.nextInt(6) == 0 ? NpcRole.BEGGAR : NpcRole.LUMBERJACK;
                        };
                        yield npc(level, at, role, null, 20) != null;
                    }
                    default -> true;
                };
            } catch (RuntimeException ex) {
                Skycraft.LOGGER.warn("Could not populate {} anchor of {}", a.type(), e.key, ex);
                done = true;
            }
            if (done) {
                e.spawned.add(i);
                counts.merge(a.type(), 1, Integer::sum);
                changed = true;
            }
        }
        if (changed) data.setDirty();
    }

    /** The position to stand an NPC at: the anchor, or the block above it when the anchor is solid (bed, throne). */
    static BlockPos standing(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty() ? p : p.above();
    }

    static NpcEntity npc(ServerLevel level, BlockPos at, NpcRole role, Component name, int homeRadius) {
        NpcEntity npc = Npcs.create(level, at, role);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        if (name != null) npc.setCustomName(name);
        npc.setHome(at, homeRadius);
        npc.getPersistentData().putBoolean("skycraft_settler", true);
        return level.addFreshEntity(npc) ? npc : null;
    }

    static NpcEntity steward(ServerLevel level, BlockPos at, SettlementAnchors.Entry e) {
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
        GuardEntity g = ModEntities.GUARD.get().create(level);
        if (g == null) return false;
        g.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        g.setHome(at);
        g.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);
        g.setPersistenceRequired();
        return level.addFreshEntity(g);
    }

    /** A villager merchant with a Skycraft shop of the given pool. */
    static Villager merchant(ServerLevel level, BlockPos at, VillagerProfession prof, String pool, String nameKey, String... morePools) {
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) return null;
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
        v.setVillagerData(v.getVillagerData().setType(VillagerType.byBiome(level.getBiome(at))).setProfession(prof).setLevel(3));
        v.setVillagerXp(100);
        v.setPersistenceRequired();
        v.setCustomName(Component.translatable(nameKey, firstName(level.getRandom().nextBoolean(), level.getRandom())));
        CompoundTag shop = new CompoundTag();
        ListTag pools = new ListTag();
        pools.add(StringTag.valueOf(pool));
        for (String extra : morePools) pools.add(StringTag.valueOf(extra));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        v.getPersistentData().put(Shop.KEY, shop);
        v.getPersistentData().putBoolean("skycraft_settler", true);
        return level.addFreshEntity(v) ? v : null;
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
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) return false;
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
        v.setVillagerData(v.getVillagerData().setType(VillagerType.byBiome(level.getBiome(at)))
                .setProfession(VillagerProfession.BUTCHER).setLevel(3));
        v.setVillagerXp(100);
        String name = innkeeperName(e.key, level.getRandom());
        v.setCustomName(Component.translatable("entity.skycraft.innkeeper", name));
        v.setPersistenceRequired();
        v.addTag(Innkeepers.TAG);
        CompoundTag pd = v.getPersistentData();
        pd.putBoolean(Innkeepers.TAG, true);
        pd.putInt(Innkeepers.SETTLEMENT_KEY, s.id);
        int inn = Cities.innIndex(e.key);
        pd.putInt(Innkeepers.INN_KEY, inn >= 0 ? inn : Math.floorMod(e.key.hashCode(), Innkeepers.INN_NAMES));
        CompoundTag shop = new CompoundTag();
        ListTag pools = new ListTag();
        pools.add(StringTag.valueOf("food"));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        pd.put(Shop.KEY, shop);
        pd.putBoolean("skycraft_settler", true);
        return level.addFreshEntity(v);
    }

    static String firstName(boolean female, RandomSource r) {
        return com.skycraft.society.NpcNames.generate(com.skycraft.society.NpcNames.raceFor(NpcRole.COURIER, r), female, r);
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
