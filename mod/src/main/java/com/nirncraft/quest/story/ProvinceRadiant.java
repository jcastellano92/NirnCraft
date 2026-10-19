package com.nirncraft.quest.story;

import com.nirncraft.core.SkyData;
import com.nirncraft.dialogue.DialogueOption;
import com.nirncraft.quest.Locate;
import com.nirncraft.quest.Names;
import com.nirncraft.quest.Objective;
import com.nirncraft.quest.Quest;
import com.nirncraft.quest.QuestDialogue;
import com.nirncraft.quest.QuestSpawner.Spawn;
import com.nirncraft.quest.Quests;
import com.nirncraft.quest.RadiantQuests;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Radiant work beyond Skyrim, flavored by each culture (Ayleid ruin surveys in Cyrodiil, ash creature culls and
 * ancestral tombs in Morrowind, Hist sap in Black Marsh, moon-sugar caravans in Elsweyr, Green Pact hunts in
 * Valenwood, Maormer raiders on Summerset, sword-singer duels in Hammerfell, witch covens in High Rock, stronghold
 * trials in Orsinium), and the exploration bounties of the uncharted lands: charting a foreign shore from afar, and
 * a survey quest the first time a party sets foot in each generated region of the endless lands.
 */
public final class ProvinceRadiant {
    private static final Set<String> FOREIGN = Set.of("realm", "atmora", "akavir", "yokuda", "pyandonea", "thras");

    private ProvinceRadiant() {}

    // ------------------------------------------------------------------ dialogue

    public static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out, boolean guard) {
        Region r = Regions.at(npc.level(), npc.blockPosition());
        boolean questGiver = guard || QuestDialogue.isInnkeeper(npc)
                || (npc instanceof NpcEntity n && (n.role() == NpcRole.JARL || n.role() == NpcRole.STEWARD));
        if (!"skyrim".equals(r.province()) && !"sea".equals(r.province()) && !"dimension".equals(r.kind()) && questGiver) {
            out.add(new DialogueOption("story.province.work",
                    QuestDialogue.questLabel(Component.translatable("dialogue.nirncraft.story.local_work", r.name())), 305,
                    (pl, n) -> QuestDialogue.offer(pl, n, "province", () -> local(pl, n))));
        }
        boolean chartist = npc instanceof Villager v && v.getVillagerData().profession().unwrapKey().orElseThrow() == VillagerProfession.CARTOGRAPHER
                || npc instanceof NpcEntity ne && (ne.role() == NpcRole.ADVENTURER || ne.role() == NpcRole.COURIER);
        if (chartist && SkyData.get(player).getLevel() >= 8) {
            out.add(new DialogueOption("story.uncharted",
                    QuestDialogue.questLabel(Component.translatable("dialogue.nirncraft.story.uncharted")), 306,
                    (pl, n) -> QuestDialogue.offer(pl, n, "uncharted", () -> expedition(pl, n))));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static Quest base(ServerPlayer p, LivingEntity npc, String kind, Quest.Category cat, Object... args) {
        Quest q = new Quest(kind, cat, tr("quest.nirncraft." + kind + ".title", args), tr("quest.nirncraft." + kind + ".desc", args));
        q.giver = npc.getUUID();
        q.giverName = npc.getDisplayName();
        q.giverPos = npc.blockPosition();
        q.giverDim = npc.level().dimension().identifier().toString();
        q.hold = Regions.at(npc.level(), npc.blockPosition()).id();
        return q;
    }

    private static long scale(int base, ServerPlayer p, int min, int max) {
        int lvl = SkyData.get(p).getLevel();
        return Math.max(min, Math.min(max, Math.round(base * (1 + lvl / 20.0))));
    }

    private static Objective returnTo(LivingEntity npc, String topic) {
        return new Objective(Objective.Type.TALK_TO, tr("quest.nirncraft.obj.return", npc.getDisplayName()))
                .npc(npc.getUUID()).at(npc.blockPosition(), npc.level().dimension().identifier().toString(), 0)
                .topic(tr(topic), tr("quest.nirncraft.reply.thanks")).label(npc.getDisplayName().getString());
    }

    /** A spot 150..450 blocks away that is still in the same province when possible. */
    private static BlockPos spotNear(ServerLevel level, ServerPlayer p, RandomSource r, String province) {
        BlockPos best = null;
        for (int i = 0; i < 8; i++) {
            BlockPos s = Locate.randomSpot(level, r, p.blockPosition(), 150, 450);
            best = s;
            Region at = TamrielMap.region(s.getX(), s.getZ());
            if (province.equals(at.province()) && TamrielMap.elevation(s.getX(), s.getZ()) > TamrielMap.SEA_LEVEL + 1) return s;
        }
        return best;
    }

    /** Another named city or town of the same province, for deliveries. */
    @Nullable
    private static Place otherSettlement(Region region, BlockPos from, RandomSource r) {
        List<Place> pool = new ArrayList<>();
        for (Place p : Places.all()) {
            if (!p.type().isSettlement()) continue;
            String prov = p.regionId().contains("/") ? p.regionId().substring(0, p.regionId().indexOf('/')) : p.regionId();
            if (!prov.equals(region.province())) continue;
            if (p.distSqr(from.getX(), from.getZ()) < 200L * 200L) continue;
            pool.add(p);
        }
        return pool.isEmpty() ? null : pool.get(r.nextInt(pool.size()));
    }

    private static String dim(Level level) {
        return level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------------ local work

    /** What the people of this province need done. */
    @Nullable
    public static RadiantQuests.Offer local(ServerPlayer p, LivingEntity npc) {
        ServerLevel level = p.level();
        RandomSource r = p.getRandom();
        Region region = Regions.at(level, npc.blockPosition());
        String culture = switch (region.culture()) {
            case "imperial", "dunmer", "argonian", "khajiit", "bosmer", "altmer", "redguard", "breton", "orc" -> region.culture();
            default -> "foreign";
        };
        // two times in five: a culture-flavored archetype from the radiant templates (Daggerfall-style work)
        if (r.nextInt(5) < 2) {
            RadiantQuests.Offer t = RadiantTemplates.offer(p, npc, region, culture, r);
            if (t != null) return t;
        }
        int roll = r.nextInt(3);
        if (roll == 2) {
            RadiantQuests.Offer o = delivery(p, npc, region, culture, r);
            if (o != null) return o;
            roll = r.nextInt(2);
        }
        return roll == 0 ? cull(p, npc, region, culture, r) : relic(p, npc, region, culture, r);
    }

    /** Creatures or outlaws threatening the land. */
    private static RadiantQuests.Offer cull(ServerPlayer p, LivingEntity npc, Region region, String culture, RandomSource r) {
        ServerLevel level = p.level();
        BlockPos spot = spotNear(level, p, r, region.province());
        Component dir = Names.direction(npc.blockPosition(), spot);
        String foe;
        String foeName;
        int n = 4 + r.nextInt(3);
        switch (culture) {
            case "imperial" -> { foe = "minecraft:skeleton"; foeName = "Ayleid Revenant"; }
            case "dunmer" -> { foe = "minecraft:husk"; foeName = "Ash Ghoul"; }
            case "argonian" -> { foe = "nirncraft:troll"; foeName = "Marsh Wamasu"; n = 2; }
            case "khajiit" -> { foe = "nirncraft:bandit"; foeName = "Dune Raider"; }
            case "bosmer" -> { foe = "nirncraft:spriggan"; foeName = "Wild Spriggan"; n = 3; }
            case "altmer" -> { foe = "nirncraft:bandit"; foeName = "Maormer Raider"; }
            case "redguard" -> { foe = "minecraft:husk"; foeName = "Sand Wraith"; }
            case "breton" -> { foe = "minecraft:witch"; foeName = "Coven Witch"; n = 3; }
            case "orc" -> { foe = "nirncraft:troll"; foeName = "Mountain Troll"; n = 2; }
            default -> { foe = "minecraft:spider"; foeName = "Strange Beast"; }
        }
        long gold = scale(260, p, 150, 1200);
        Quest q = base(p, npc, "province_" + culture + "_cull", Quest.Category.BOUNTY, dir, region.name());
        q.add(new Objective(Objective.Type.CLEAR_AREA, tr("quest.nirncraft.obj.province_" + culture + "_cull", n))
                .at(spot, dim(level), 32).guessY().count(n)
                .spawn(Spawn.of(foe, n).name(Component.literal(foeName)).build()).label(foeName));
        q.add(returnTo(npc, "dialogue.nirncraft.story.done"));
        q.reward(gold);
        return new RadiantQuests.Offer(q, tr("quest.nirncraft.pitch.province_" + culture + "_cull", dir, gold));
    }

    /** Something precious to bring back from a guarded place. */
    private static RadiantQuests.Offer relic(ServerPlayer p, LivingEntity npc, Region region, String culture, RandomSource r) {
        ServerLevel level = p.level();
        BlockPos spot = Locate.dungeon(level, p.blockPosition(), r);
        if (spot == null || TamrielMap.inTamriel(spot.getX(), spot.getZ()) && !region.province().equals(TamrielMap.region(spot.getX(), spot.getZ()).province())) {
            spot = spotNear(level, p, r, region.province());
        }
        Component dir = Names.direction(npc.blockPosition(), spot);
        String relic = "radiant_" + culture;
        String guard = switch (culture) {
            case "dunmer", "redguard" -> "nirncraft:draugr";
            case "argonian", "bosmer" -> "nirncraft:spriggan";
            case "breton" -> "minecraft:witch";
            default -> "nirncraft:bandit";
        };
        long gold = scale(320, p, 180, 1400);
        Quest q = base(p, npc, "province_" + culture + "_relic", Quest.Category.MISC, dir, region.name());
        Objective get = new Objective(Objective.Type.RETRIEVE, tr("quest.nirncraft.obj.province_" + culture + "_relic"))
                .at(spot, dim(level), 12).guessY().placement("structure").label(Names.ruin(spot))
                .spawn(Spawn.of(guard, 2 + r.nextInt(2)).build());
        get.data.putString(Story.RELIC, relic);
        Objective give = new Objective(Objective.Type.FETCH, tr("quest.nirncraft.obj.province_bring", npc.getDisplayName()))
                .npc(npc.getUUID()).at(npc.blockPosition(), dim(level), 0)
                .topic(tr("dialogue.nirncraft.story.bring"), tr("quest.nirncraft.reply.thanks")).label(npc.getDisplayName().getString());
        give.data.putString(Story.RELIC, relic);
        q.add(get).add(give).reward(gold);
        return new RadiantQuests.Offer(q, tr("quest.nirncraft.pitch.province_" + culture + "_relic", dir, gold));
    }

    /** A message or parcel for another city of the province. */
    @Nullable
    private static RadiantQuests.Offer delivery(ServerPlayer p, LivingEntity npc, Region region, String culture, RandomSource r) {
        Place to = otherSettlement(region, npc.blockPosition(), r);
        if (to == null) return null;
        ServerLevel level = p.level();
        BlockPos at = new BlockPos(to.x(), Locate.surfaceY(level, to.x(), to.z()), to.z());
        int dist = (int) Locate.horizontalDist(at, npc.blockPosition());
        long gold = Mth.clamp(120 + dist / 10, 120, 900);
        Quest q = base(p, npc, "province_" + culture + "_delivery", Quest.Category.MISC, to.name(), region.name());
        q.add(new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.travel_to", to.name())).at(at, dim(level), 80).guessY()
                .label(to.name().getString()));
        Objective talk = new Objective(Objective.Type.TALK_TO, tr("quest.nirncraft.obj.deliver"))
                .filter("any").at(at, dim(level), Math.max(96, to.radius())).guessY()
                .topic(tr("dialogue.nirncraft.story.parcel"), tr("quest.nirncraft.reply.province_" + culture + "_delivery"))
                .label(to.name().getString());
        q.add(talk).reward(gold);
        return new RadiantQuests.Offer(q, tr("quest.nirncraft.pitch.province_" + culture + "_delivery", to.name(), gold));
    }

    // ------------------------------------------------------------------ uncharted lands

    /** "Chart the shore beyond the sea": the nearest foreign or generated land, paid by the distance. */
    @Nullable
    public static RadiantQuests.Offer expedition(ServerPlayer p, LivingEntity npc) {
        ServerLevel level = p.level();
        if (level.dimension() != Level.OVERWORLD) return null;
        RandomSource r = p.getRandom();
        BlockPos from = npc.blockPosition();
        BlockPos target = null;
        Region land = null;
        double a0 = r.nextDouble() * Math.PI * 2;
        // walk outward on a few bearings until the map says: foreign land, above the sea
        search:
        for (int ring = 1500; ring <= 24000; ring += 750) {
            for (int k = 0; k < 12; k++) {
                double a = a0 + k * Math.PI / 6;
                int x = from.getX() + (int) (Math.cos(a) * ring);
                int z = from.getZ() + (int) (Math.sin(a) * ring);
                Region reg = TamrielMap.region(x, z);
                if (FOREIGN.contains(reg.province()) && TamrielMap.elevation(x, z) > TamrielMap.SEA_LEVEL + 2
                        && level.getWorldBorder().isWithinBounds(new BlockPos(x, 64, z))) {
                    target = new BlockPos(x, Locate.surfaceY(level, x, z), z);
                    land = reg;
                    break search;
                }
            }
        }
        if (target == null) return null;
        int dist = (int) Locate.horizontalDist(target, from);
        Component dir = Names.direction(from, target);
        long gold = Mth.clamp(500 + dist / 6, 500, 6000);
        Quest q = base(p, npc, "uncharted_expedition", Quest.Category.BOUNTY, land.name(), dir, dist);
        q.add(new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.uncharted_reach", land.name())).at(target, dim(level), 96).guessY()
                .label(land.name().getString()));
        q.add(new Objective(Objective.Type.CONDITION, tr("quest.nirncraft.obj.uncharted_survey")).target("wait:1200"));
        q.add(returnTo(npc, "dialogue.nirncraft.story.charted"));
        q.reward(gold);
        q.extra.putBoolean(Quests.PER_PLAYER, true);
        return new RadiantQuests.Offer(q, tr("quest.nirncraft.pitch.uncharted_expedition", land.name(), dir, dist, gold));
    }

    /**
     * Every five seconds: a party in a generated or foreign region it hasn't charted yet gets a survey of it (three
     * vantage points and whatever lairs there), paid by how far it lies from Whiterun.
     */
    public static void tickPlayer(ServerPlayer p) {
        ServerLevel level = p.level();
        if (level.dimension() != Level.OVERWORLD || SkyData.get(p).getRace() == null) return;
        Region region = Regions.at(level, p.blockPosition());
        if (!FOREIGN.contains(region.province()) || "sea".equals(region.kind())) return;
        String flag = "charted_" + region.id().replace('/', '_');
        if (Story.hasFlag(SkyData.get(p), flag) || !Quests.canTakeMore(p)) return;
        for (Quests.Ref ref : Quests.active(p.level().getServer(), p.getUUID())) if (flag.equals(ref.quest().extra.getStringOr("survey",""))) return;
        RandomSource r = p.getRandom();
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < 40 && points.size() < 3; i++) {
            BlockPos s = Locate.randomSpot(level, r, p.blockPosition(), 120 + points.size() * 60, 360 + points.size() * 60);
            if (!region.id().equals(TamrielMap.region(s.getX(), s.getZ()).id())) continue;
            if (TamrielMap.elevation(s.getX(), s.getZ()) <= TamrielMap.SEA_LEVEL + 1) continue;
            points.add(new BlockPos(s.getX(), Locate.surfaceY(level, s.getX(), s.getZ()), s.getZ()));
        }
        if (points.size() < 2) return;
        String dim = dim(level);
        int far = (int) Math.sqrt((double) p.getBlockX() * p.getBlockX() + (double) p.getBlockZ() * p.getBlockZ());
        long gold = Mth.clamp(300 + far / 20, 300, 4000);
        Quest q = new Quest("uncharted_survey", Quest.Category.BOUNTY, tr("quest.nirncraft.uncharted_survey.title", region.name()),
                tr("quest.nirncraft.uncharted_survey.desc", region.name()));
        q.giverName = tr("quest.nirncraft.uncharted_survey.giver");
        q.hold = region.id();
        q.extra.putString("survey", flag);
        for (int i = 0; i < points.size(); i++) {
            Objective o = new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.uncharted_point", i + 1, points.size()))
                    .at(points.get(i), dim, 20).guessY().label(tr("quest.nirncraft.uncharted_survey.point", i + 1).getString());
            q.add(o);
        }
        BlockPos lair = points.get(points.size() - 1);
        String beast = r.nextBoolean() ? "nirncraft:troll" : "minecraft:ravager";
        q.add(new Objective(Objective.Type.CLEAR_AREA, tr("quest.nirncraft.obj.uncharted_lair")).at(lair, dim, 32).guessY().count(2)
                .spawn(Spawn.of(beast, 1).name(tr("quest.nirncraft.uncharted_survey.beast")).glow().health(2f).build())
                .spawn(Spawn.of("minecraft:spider", 1).build()).label(tr("quest.nirncraft.uncharted_survey.lair").getString()));
        q.reward(gold);
        q.extra.putBoolean(Quests.PER_PLAYER, true);
        if (Quests.start(p, q)) Story.setFlag(SkyData.get(p), flag, true);
    }
}
