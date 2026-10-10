package com.skycraft.quest;

import com.skycraft.core.Holds;
import com.skycraft.core.SkyData;
import com.skycraft.quest.QuestSpawner.Spawn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Generators for radiant quests: villager favors, guard bounties, legendary monster hunts and faction trials and
 * contracts. Every generator returns an {@link Offer}: the quest (not yet started) and the NPC's pitch.
 */
public final class RadiantQuests {
    /** Entity ids that count as "bandits or beasts" for the Companions' trial. */
    public static final String BANDITS_AND_BEASTS = "skycraft:bandit,skycraft:bandit_chief,skycraft:skeever,skycraft:troll,"
            + "minecraft:wolf,minecraft:spider,minecraft:cave_spider,minecraft:polar_bear,minecraft:pillager,minecraft:vindicator";
    private static final int STORMCLOAK_BLUE = 0x2F4F8F;
    private static final int IMPERIAL_RED = 0x8B1E1E;

    private RadiantQuests() {}

    public record Offer(Quest quest, Component pitch) {
    }

    /** Everything a generator needs. */
    static final class Ctx {
        final ServerPlayer player;
        final ServerLevel level;
        final LivingEntity npc;
        final RandomSource r;
        final int lvl;
        final String dim;
        final String hold;

        Ctx(ServerPlayer player, LivingEntity npc) {
            this.player = player;
            this.level = (ServerLevel) player.level();
            this.npc = npc;
            this.r = player.getRandom();
            this.lvl = SkyData.get(player).getLevel();
            this.dim = level.dimension().location().toString();
            this.hold = Holds.holdAt(level, npc.blockPosition());
        }

        Component holdName() {
            return Holds.displayName(hold);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static Component lit(String s) {
        return Component.literal(s);
    }

    private static Quest base(Ctx c, String kind, Quest.Category cat, Object... args) {
        Quest q = new Quest(kind, cat, tr("quest.skycraft." + kind + ".title", args), tr("quest.skycraft." + kind + ".desc", args));
        q.giver = c.npc.getUUID();
        q.giverName = c.npc.getDisplayName();
        q.giverPos = c.npc.blockPosition();
        q.giverDim = c.dim;
        q.hold = c.hold;
        return q;
    }

    private static Offer offer(Quest q, Object... pitchArgs) {
        return new Offer(q, tr("quest.skycraft.pitch." + q.kind, pitchArgs));
    }

    /** Gold scaled with the player's level, clamped. */
    static long scale(int base, int lvl, int min, int max) {
        long v = Math.round(base * (1 + lvl / 20.0));
        return Math.max(min, Math.min(max, v));
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static <T> T pick(RandomSource r, T[] arr) {
        return arr[r.nextInt(arr.length)];
    }

    static Objective fetchObjective(Ctx c, Item item, int count) {
        Component name = item.getDescription();
        return new Objective(Objective.Type.FETCH, tr("quest.skycraft.obj.fetch", count, name, c.npc.getDisplayName()))
                .target(itemId(item)).count(count).npc(c.npc.getUUID()).at(c.npc.blockPosition(), c.dim, 0)
                .topic(tr("dialogue.skycraft.quest.fetch", count, name), tr("quest.skycraft.reply.thanks"))
                .label(c.npc.getDisplayName().getString());
    }

    static Objective returnTo(Ctx c, String topicKey) {
        return new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.return", c.npc.getDisplayName()))
                .npc(c.npc.getUUID()).at(c.npc.blockPosition(), c.dim, 0)
                .topic(tr(topicKey), tr("quest.skycraft.reply.thanks"))
                .label(c.npc.getDisplayName().getString());
    }

    private static Objective killTarget(Ctx c, Component text, BlockPos spot, String label) {
        return new Objective(Objective.Type.KILL_TARGET, text).at(spot, c.dim, 0).guessY().placement("surface").label(label);
    }

    private static void giveOnStart(Quest q, ItemStack stack) {
        ListTag list = q.extra.getList("give_on_start", 10);
        list.add(stack.save(new CompoundTag()));
        q.extra.put("give_on_start", list);
    }

    // ------------------------------------------------------------------ villagers

    private record Fetch(Item item, int count, int gold, String pitch) {
    }

    @Nullable
    private static Fetch[] fetchTable(VillagerProfession prof) {
        if (prof == VillagerProfession.FARMER) return new Fetch[]{new Fetch(Items.WHEAT, 16, 60, "harvest"),
                new Fetch(Items.CARROT, 16, 60, "harvest"), new Fetch(Items.POTATO, 16, 60, "harvest"), new Fetch(Items.PUMPKIN, 4, 70, "harvest")};
        if (prof == VillagerProfession.BUTCHER) return new Fetch[]{new Fetch(Items.LEATHER, 8, 80, "hides"),
                new Fetch(Items.BEEF, 10, 70, "meat"), new Fetch(Items.PORKCHOP, 10, 70, "meat")};
        if (prof == VillagerProfession.LEATHERWORKER) return new Fetch[]{new Fetch(Items.LEATHER, 8, 80, "hides"),
                new Fetch(Items.RABBIT_HIDE, 6, 90, "hides")};
        if (prof == VillagerProfession.FISHERMAN) return new Fetch[]{new Fetch(Items.COD, 10, 70, "fish"), new Fetch(Items.SALMON, 8, 80, "fish")};
        if (prof == VillagerProfession.WEAPONSMITH || prof == VillagerProfession.ARMORER || prof == VillagerProfession.TOOLSMITH)
            return new Fetch[]{new Fetch(Items.IRON_INGOT, 8, 120, "iron"), new Fetch(Items.COAL, 16, 70, "coal")};
        if (prof == VillagerProfession.CLERIC) return new Fetch[]{new Fetch(Items.GLOWSTONE_DUST, 5, 110, "ingredients"),
                new Fetch(Items.NETHER_WART, 6, 120, "ingredients"), new Fetch(Items.SPIDER_EYE, 4, 90, "ingredients")};
        if (prof == VillagerProfession.SHEPHERD) return new Fetch[]{new Fetch(Items.WHITE_WOOL, 12, 70, "wool")};
        if (prof == VillagerProfession.FLETCHER) return new Fetch[]{new Fetch(Items.FEATHER, 12, 70, "feathers"), new Fetch(Items.FLINT, 8, 70, "feathers")};
        if (prof == VillagerProfession.MASON) return new Fetch[]{new Fetch(Items.CLAY_BALL, 16, 70, "clay")};
        if (prof == VillagerProfession.LIBRARIAN) return new Fetch[]{new Fetch(Items.BOOK, 6, 100, "books")};
        if (prof == VillagerProfession.CARTOGRAPHER) return new Fetch[]{new Fetch(Items.PAPER, 24, 80, "paper")};
        return null;
    }

    /** "Do you need help with anything?" — a profession-flavored favor, or null if the villager has nothing. */
    @Nullable
    public static Offer villager(ServerPlayer player, Villager villager) {
        Ctx c = new Ctx(player, villager);
        VillagerProfession prof = villager.getVillagerData().getProfession();
        if (prof == VillagerProfession.FARMER && c.r.nextBoolean()) return skeevers(c);
        if (prof == VillagerProfession.LIBRARIAN && c.r.nextInt(3) > 0) {
            Offer o = tome(c, false);
            if (o != null) return o;
        }
        if (prof == VillagerProfession.CARTOGRAPHER && c.r.nextInt(4) > 0) {
            Offer o = courier(c);
            if (o != null) return o;
        }
        Fetch[] table = fetchTable(prof);
        if (table == null) return null;
        return fetch(c, pick(c.r, table));
    }

    private static Offer fetch(Ctx c, Fetch f) {
        Component item = f.item().getDescription();
        long gold = scale(f.gold(), c.lvl, 50, 500);
        Quest q = base(c, "villager_fetch", Quest.Category.MISC, f.count(), item, c.npc.getDisplayName(), c.holdName());
        q.add(fetchObjective(c, f.item(), f.count())).reward(gold);
        return new Offer(q, tr("quest.skycraft.pitch." + f.pitch(), f.count(), item, gold));
    }

    private static Offer skeevers(Ctx c) {
        int n = 6;
        double angle = c.r.nextDouble() * Math.PI * 2;
        int d = 10 + c.r.nextInt(7);
        BlockPos spot = c.npc.blockPosition().offset((int) (Math.cos(angle) * d), 0, (int) (Math.sin(angle) * d));
        long gold = scale(110, c.lvl, 60, 300);
        Quest q = base(c, "villager_skeevers", Quest.Category.MISC, c.npc.getDisplayName(), c.holdName());
        q.add(new Objective(Objective.Type.KILL_TYPE_COUNT, tr("quest.skycraft.obj.kill_skeevers", n))
                .target("skycraft:skeever").count(n).at(spot, c.dim, 0).placement("near")
                .spawn(Spawn.of("skycraft:skeever", n).build()).label("Skeevers"));
        q.add(returnTo(c, "dialogue.skycraft.quest.skeevers_done"));
        q.reward(gold);
        return offer(q, n, gold);
    }

    /** Recover the ancient tome from a dungeon (librarian favor or College contract). */
    @Nullable
    static Offer tome(Ctx c, boolean college) {
        BlockPos d = Locate.dungeon(c.level, c.player.blockPosition(), c.r);
        if (d == null) return null;
        String ruin = Names.ruin(d);
        int n = 3 + c.r.nextInt(3);
        String kind = college ? "college_tome" : "villager_tome";
        Component dir = Names.direction(c.npc.blockPosition(), d);
        Quest q = base(c, kind, college ? Quest.Category.FACTION : Quest.Category.MISC, ruin, dir, c.npc.getDisplayName());
        q.add(new Objective(Objective.Type.CLEAR_AREA, tr("quest.skycraft.obj.clear_ruin", ruin))
                .at(d, c.dim, 48).guessY().placement("structure").count(n)
                .spawn(Spawn.of("skycraft:draugr", n).build()).onComplete("give:skycraft:ancient_tome").label(ruin));
        q.add(fetchObjective(c, QuestItems.ANCIENT_TOME.get(), 1));
        long gold = scale(college ? 300 : 220, c.lvl, 150, 500);
        if (college) {
            q.faction(Faction.COLLEGE.id, 1, false);
            ItemStack tome = Factions.randomSpellTome(c.r);
            q.reward(gold, tome);
        } else {
            q.reward(gold);
        }
        return offer(q, ruin, dir, gold);
    }

    /** Deliver a letter to a village far away. */
    @Nullable
    private static Offer courier(Ctx c) {
        BlockPos v = Locate.farVillage(c.level, c.npc.blockPosition(), 300, c.r);
        if (v == null) return null;
        int dist = (int) Locate.horizontalDist(v, c.npc.blockPosition());
        Component dir = Names.direction(c.npc.blockPosition(), v);
        long gold = Mth.clamp(100 + dist / 8, 100, 500);
        Quest q = base(c, "villager_courier", Quest.Category.MISC, dir, dist, c.npc.getDisplayName());
        addDelivery(c, q, v, dir, "dialogue.skycraft.quest.letter", "quest.skycraft.reply.letter", "villager");
        giveOnStart(q, QuestItems.letter(v.getX() + ", " + v.getZ()));
        q.reward(gold);
        return offer(q, dir, dist, gold);
    }

    private static void addDelivery(Ctx c, Quest q, BlockPos village, Component dir, String topic, String reply, String filter) {
        q.add(new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.travel_village", dir)).at(village, c.dim, 64).guessY().label("Village"));
        Objective talk = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.deliver"))
                .filter(filter).at(village, c.dim, 128).guessY().topic(tr(topic), tr(reply)).label("Village");
        talk.data.putString("consume", "skycraft:courier_letter");
        q.add(talk);
    }

    // ------------------------------------------------------------------ guard bounties

    /** "Any bounties posted?" */
    public static Offer bounty(ServerPlayer player, LivingEntity guard) {
        Ctx c = new Ctx(player, guard);
        int roll = c.r.nextInt(100);
        int dragonWeight = c.lvl >= 8 ? 15 : 5;
        if (roll < dragonWeight) return bountyDragon(c);
        if (roll < dragonWeight + 20) {
            Offer o = bountyDeathlord(c);
            if (o != null) return o;
        }
        if (roll < dragonWeight + 45) return bountyGiant(c);
        return bountyBandit(c);
    }

    private static Offer bountyBandit(Ctx c) {
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 250, 700);
        String chief = Names.banditChief(c.r);
        String camp = Names.camp(c.r);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        long gold = scale(300, c.lvl, 200, 1500);
        Quest q = base(c, "bounty_bandit", Quest.Category.BOUNTY, chief, camp, dir, c.holdName());
        q.add(killTarget(c, tr("quest.skycraft.obj.kill_named", chief), spot, camp)
                .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit(chief)).target().build())
                .spawn(Spawn.of("skycraft:bandit", 3 + c.r.nextInt(3)).build()));
        q.reward(gold);
        return offer(q, chief, camp, dir, gold);
    }

    private static Offer bountyGiant(Ctx c) {
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 300, 700);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        long gold = scale(500, c.lvl, 300, 1500);
        Quest q = base(c, "bounty_giant", Quest.Category.BOUNTY, dir, c.holdName());
        q.add(killTarget(c, tr("quest.skycraft.obj.kill_giant"), spot, "Giant's Camp")
                .spawn(Spawn.of("skycraft:giant", 1).target().build()));
        q.reward(gold);
        return offer(q, dir, gold);
    }

    @Nullable
    private static Offer bountyDeathlord(Ctx c) {
        BlockPos d = Locate.dungeon(c.level, c.player.blockPosition(), c.r);
        if (d == null) return null;
        String ruin = Names.ruin(d);
        Component dir = Names.direction(c.player.blockPosition(), d);
        long gold = scale(700, c.lvl, 400, 1500);
        Quest q = base(c, "bounty_deathlord", Quest.Category.BOUNTY, ruin, dir, c.holdName());
        q.add(new Objective(Objective.Type.KILL_TARGET, tr("quest.skycraft.obj.kill_deathlord", ruin))
                .at(d, c.dim, 0).guessY().placement("structure").label(ruin)
                .spawn(Spawn.of("skycraft:draugr_deathlord", 1).target().build())
                .spawn(Spawn.of("skycraft:draugr", 2 + c.r.nextInt(2)).build()));
        q.reward(gold);
        return offer(q, ruin, dir, gold);
    }

    private static Offer bountyDragon(Ctx c) {
        // dragons roost at the word walls and burial mounds of the hold, like Skyrim's dragon sightings
        var roost = Locate.namedPlace(c.level, c.player.blockPosition(), c.r, 200, 1500,
                java.util.EnumSet.of(com.skycraft.tamriel.PlaceType.DRAGON_MOUND, com.skycraft.tamriel.PlaceType.WORD_WALL));
        BlockPos spot = roost != null
                ? new BlockPos(roost.x(), Locate.surfaceY(c.level, roost.x(), roost.z()), roost.z())
                : Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 300, 600);
        String name = Names.dragon(c.r);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        long gold = scale(1000, c.lvl, 600, 1500);
        Quest q = base(c, "bounty_dragon", Quest.Category.BOUNTY, name, dir, c.holdName());
        q.add(killTarget(c, tr("quest.skycraft.obj.kill_dragon", name), spot, name)
                .spawn(Spawn.of("skycraft:dragon", 1).name(lit(name)).target().boss().build()));
        q.reward(gold);
        return offer(q, name, dir, gold);
    }

    // ------------------------------------------------------------------ legendary hunts

    /** "Any monsters that need hunting?" — a named, boosted legendary beast with a trophy. */
    public static Offer hunt(ServerPlayer player, LivingEntity guard) {
        Ctx c = new Ctx(player, guard);
        String kind = pick(c.r, new String[]{"troll", "giant", "skeever", "draugr", "witch"});
        String type;
        int base;
        switch (kind) {
            case "giant" -> {
                type = "skycraft:giant";
                base = 1000;
            }
            case "skeever" -> {
                type = "skycraft:skeever";
                base = 400;
            }
            case "draugr" -> {
                type = "skycraft:draugr";
                base = 800;
            }
            case "witch" -> {
                type = "minecraft:witch";
                base = 600;
            }
            default -> {
                type = "skycraft:troll";
                base = 700;
            }
        }
        String name = Names.legendary(c.r, kind);
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 250, 600);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        long gold = scale(base, c.lvl, 400, 1500);
        Quest q = base(c, "hunt_" + kind, Quest.Category.BOUNTY, name, dir, c.holdName());
        q.add(killTarget(c, tr("quest.skycraft.obj.slay", name), spot, name)
                .spawn(Spawn.of(type, 1).name(lit(name)).target().boss().glow().health(3f).damage(1.5f).build()));
        q.reward(gold, QuestItems.trophy(name, (int) (gold / 2)));
        return offer(q, name, dir, gold);
    }

    // ------------------------------------------------------------------ factions

    public static Offer companionsTrial(ServerPlayer player, LivingEntity npc) {
        Ctx c = new Ctx(player, npc);
        Quest q = base(c, "companions_trial", Quest.Category.FACTION);
        q.add(new Objective(Objective.Type.KILL_TYPE_COUNT, tr("quest.skycraft.obj.kill_bandits_beasts", 5)).target(BANDITS_AND_BEASTS).count(5));
        q.faction(Faction.COMPANIONS.id, 0, true).reward(100);
        return offer(q);
    }

    public static Offer companionsContract(ServerPlayer player, LivingEntity npc) {
        Ctx c = new Ctx(player, npc);
        int roll = c.r.nextInt(3);
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 200, 500);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        Quest q;
        long gold;
        if (roll == 0) {
            gold = scale(250, c.lvl, 150, 800);
            q = base(c, "companions_beasts", Quest.Category.FACTION, dir);
            q.add(new Objective(Objective.Type.CLEAR_AREA, tr("quest.skycraft.obj.clear_beasts")).at(spot, c.dim, 32).guessY().count(6)
                    .spawn(Spawn.of("skycraft:troll", 1).build()).spawn(Spawn.of("skycraft:skeever", 3).build())
                    .spawn(Spawn.of("minecraft:spider", 2).build()).label("Beasts"));
        } else if (roll == 1) {
            gold = scale(300, c.lvl, 200, 900);
            String chief = Names.banditChief(c.r);
            q = base(c, "companions_bandits", Quest.Category.FACTION, chief, dir);
            q.add(killTarget(c, tr("quest.skycraft.obj.kill_named", chief), spot, Names.camp(c.r))
                    .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit(chief)).target().build())
                    .spawn(Spawn.of("skycraft:bandit", 3).build()));
        } else {
            gold = scale(450, c.lvl, 300, 1200);
            q = base(c, "companions_giant", Quest.Category.FACTION, dir);
            q.add(killTarget(c, tr("quest.skycraft.obj.kill_giant"), spot, "Giant")
                    .spawn(Spawn.of("skycraft:giant", 1).target().build()));
        }
        q.faction(Faction.COMPANIONS.id, 1, false).reward(gold);
        return offer(q, dir, gold);
    }

    @Nullable
    public static Offer collegeContract(ServerPlayer player, LivingEntity npc) {
        Ctx c = new Ctx(player, npc);
        if (c.r.nextBoolean()) {
            Offer o = tome(c, true);
            if (o != null) return o;
        }
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 200, 500);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        String name = Names.necromancer(c.r);
        long gold = scale(350, c.lvl, 200, 1000);
        Quest q = base(c, "college_necromancer", Quest.Category.FACTION, name, dir);
        q.add(killTarget(c, tr("quest.skycraft.obj.kill_named", name), spot, name)
                .spawn(Spawn.of("minecraft:evoker", 1).name(lit(name)).target().health(1.5f).build())
                .spawn(Spawn.of("skycraft:draugr", 2).build()));
        q.faction(Faction.COLLEGE.id, 1, false).reward(gold, Factions.randomSpellTome(c.r));
        return offer(q, name, dir, gold);
    }

    public static Offer thievesContract(ServerPlayer player, LivingEntity npc) {
        Ctx c = new Ctx(player, npc);
        int roll = c.r.nextInt(3);
        if (roll == 2) {
            BlockPos v = Locate.farVillage(c.level, c.npc.blockPosition(), 250, c.r);
            if (v != null) {
                Component dir = Names.direction(c.npc.blockPosition(), v);
                long gold = scale(300, c.lvl, 200, 900);
                Quest q = base(c, "thieves_numbers", Quest.Category.FACTION, dir);
                addDelivery(c, q, v, dir, "dialogue.skycraft.quest.ledger", "quest.skycraft.reply.ledger", "villager");
                q.faction(Faction.THIEVES_GUILD.id, 1, false).reward(gold);
                return offer(q, dir, gold);
            }
            roll = 0;
        }
        if (roll == 0) {
            int n = 4 + c.r.nextInt(5);
            long gold = scale(200, c.lvl, 150, 800);
            Quest q = base(c, "thieves_bedlam", Quest.Category.FACTION, n);
            q.add(new Objective(Objective.Type.CONDITION, tr("quest.skycraft.obj.steal", n)).target("crime:items_stolen").count(n));
            q.faction(Faction.THIEVES_GUILD.id, 1, false).reward(gold);
            return offer(q, n, gold);
        }
        int n = 2 + c.r.nextInt(3);
        long gold = scale(250, c.lvl, 150, 800);
        Quest q = base(c, "thieves_fishing", Quest.Category.FACTION, n);
        q.add(new Objective(Objective.Type.CONDITION, tr("quest.skycraft.obj.pickpocket", n)).target("crime:pickpockets").count(n));
        q.faction(Faction.THIEVES_GUILD.id, 1, false).reward(gold);
        return offer(q, n, gold);
    }

    public static Offer darkBrotherhoodContract(ServerPlayer player, LivingEntity npc) {
        Ctx c = new Ctx(player, npc);
        String name = Names.contractTarget(c.r);
        BlockPos spot = null;
        String where = "lone";
        if (c.r.nextBoolean()) {
            BlockPos v = Locate.farVillage(c.level, c.player.blockPosition(), 200, c.r);
            if (v != null) {
                spot = v;
                where = "village";
            }
        }
        if (spot == null) spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 200, 500);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        long gold = scale(450, c.lvl, 300, 1500);
        Quest q = base(c, "db_contract", Quest.Category.FACTION, name, dir);
        String type = c.r.nextInt(4) == 0 ? "minecraft:wandering_trader" : "minecraft:villager";
        Objective kill = killTarget(c, tr("quest.skycraft.obj.assassinate", name), spot, name)
                .spawn(Spawn.of(type, 1).name(lit(name)).target().glow().build());
        if ("lone".equals(where) && c.r.nextBoolean()) {
            kill.spawn(Spawn.of("skycraft:bandit", 1 + c.r.nextInt(2)).name(tr("entity.skycraft.bodyguard")).build());
        }
        q.add(kill);
        q.faction(Faction.DARK_BROTHERHOOD.id, 2, false).reward(gold);
        return new Offer(q, tr("quest.skycraft.pitch.db_contract_" + where, name, dir, gold));
    }

    private static Spawn soldiers(boolean imperialEnemy, int n) {
        return Spawn.of("skycraft:bandit", n)
                .name(tr(imperialEnemy ? "entity.skycraft.imperial_soldier" : "entity.skycraft.stormcloak_soldier"))
                .armor(imperialEnemy ? IMPERIAL_RED : STORMCLOAK_BLUE).weapon(Items.IRON_SWORD);
    }

    /** "Joining the Legion" / "Joining the Stormcloaks": clear an enemy camp. */
    public static Offer civilWarTrial(ServerPlayer player, LivingEntity npc, Faction f) {
        Ctx c = new Ctx(player, npc);
        boolean imperial = f == Faction.IMPERIAL_LEGION;
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 200, 450);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        Quest q = base(c, imperial ? "legion_trial" : "stormcloak_trial", Quest.Category.FACTION, dir);
        q.add(new Objective(Objective.Type.CLEAR_AREA, tr(imperial ? "quest.skycraft.obj.clear_stormcloaks" : "quest.skycraft.obj.clear_imperials"))
                .at(spot, c.dim, 32).guessY().count(4).spawn(soldiers(!imperial, 4).build()).label(Names.camp(c.r)));
        q.faction(f.id, 0, true).reward(150);
        return offer(q, dir);
    }

    public static Offer civilWarContract(ServerPlayer player, LivingEntity npc, Faction f) {
        Ctx c = new Ctx(player, npc);
        boolean imperial = f == Faction.IMPERIAL_LEGION;
        String prefix = imperial ? "legion" : "stormcloak";
        if (c.r.nextInt(3) == 0) {
            BlockPos v = Locate.farVillage(c.level, c.npc.blockPosition(), 250, c.r);
            if (v != null) {
                Component dir = Names.direction(c.npc.blockPosition(), v);
                long gold = scale(250, c.lvl, 150, 800);
                Quest q = base(c, prefix + "_dispatch", Quest.Category.FACTION, dir);
                addDelivery(c, q, v, dir, "dialogue.skycraft.quest.orders", "quest.skycraft.reply.orders", "any");
                giveOnStart(q, QuestItems.letter(v.getX() + ", " + v.getZ()));
                q.faction(f.id, 1, false).reward(gold);
                return offer(q, dir, gold);
            }
        }
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 200, 600);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        String camp = Names.camp(c.r);
        long gold = scale(350, c.lvl, 200, 1000);
        Quest q = base(c, prefix + "_camp", Quest.Category.FACTION, camp, dir);
        q.add(killTarget(c, tr(imperial ? "quest.skycraft.obj.kill_stormcloak_officer" : "quest.skycraft.obj.kill_imperial_officer"), spot, camp)
                .spawn(soldiers(!imperial, 1).target().health(2f)
                        .name(tr(imperial ? "entity.skycraft.stormcloak_officer" : "entity.skycraft.imperial_officer")).build())
                .spawn(soldiers(!imperial, 3 + c.r.nextInt(3)).build()));
        q.faction(f.id, 1, false).reward(gold);
        return offer(q, camp, dir, gold);
    }

    public static Offer bardsTrial(ServerPlayer player, LivingEntity npc) {
        Ctx c = new Ctx(player, npc);
        Quest q = base(c, "bards_trial", Quest.Category.FACTION, c.npc.getDisplayName());
        q.add(fetchObjective(c, Items.NOTE_BLOCK, 4));
        q.faction(Faction.BARDS_COLLEGE.id, 0, true).reward(100);
        return offer(q);
    }

    public static Offer bardsContract(ServerPlayer player, LivingEntity npc) {
        Ctx c = new Ctx(player, npc);
        Fetch f = pick(c.r, new Fetch[]{new Fetch(Items.NOTE_BLOCK, 6, 150, ""), new Fetch(Items.GOAT_HORN, 1, 400, ""),
                new Fetch(Items.JUKEBOX, 1, 300, ""), new Fetch(Items.AMETHYST_SHARD, 8, 200, "")});
        long gold = scale(f.gold(), c.lvl, 100, 500);
        Quest q = base(c, "bards_instruments", Quest.Category.FACTION, f.count(), f.item().getDescription());
        q.add(fetchObjective(c, f.item(), f.count()));
        q.faction(Faction.BARDS_COLLEGE.id, 1, false).reward(gold);
        return offer(q, f.count(), f.item().getDescription(), gold);
    }

    // ------------------------------------------------------------------ unique challenges

    public static Offer challenge(ServerPlayer player, LivingEntity guard) {
        Ctx c = new Ctx(player, guard);
        int roll = c.r.nextInt(4);
        return switch (roll) {
            case 0 -> challengeStealth(c);
            case 1 -> challengeTimed(c);
            case 2 -> challengeNoMagic(c);
            default -> challengeEscort(c);
        };
    }

    private static Offer challengeStealth(Ctx c) {
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 250, 550);
        String camp = Names.camp(c.r);
        String chief = Names.banditChief(c.r);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        long gold = scale(550, c.lvl, 350, 1800);
        Quest q = base(c, "challenge_stealth", Quest.Category.BOUNTY, chief, camp, dir);
        q.extra.putBoolean("challenge", true);
        Objective kill = killTarget(c, tr("quest.skycraft.obj.challenge_stealth", chief), spot, camp)
                .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit(chief)).target().boss().build())
                .spawn(Spawn.of("skycraft:bandit", 3).build());
        kill.sneakKill = true;
        q.add(kill);
        q.reward(gold);
        return offer(q, chief, camp, dir, gold);
    }

    private static Offer challengeTimed(Ctx c) {
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 200, 450);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        long gold = scale(500, c.lvl, 300, 1600);
        Quest q = base(c, "challenge_timed", Quest.Category.BOUNTY, dir);
        q.extra.putBoolean("challenge", true);
        long duration = 20L * 240; // 4 minutes
        q.extra.putLong("deadline", c.level.getGameTime() + duration);
        q.add(new Objective(Objective.Type.CLEAR_AREA, tr("quest.skycraft.obj.challenge_timed"))
                .at(spot, c.dim, 32).guessY().count(4)
                .spawn(Spawn.of("skycraft:bandit", 4).name(lit("Fleeing Raider")).build())
                .label("Bandit Ambush"));
        q.reward(gold);
        return offer(q, dir, 4, gold);
    }

    private static Offer challengeNoMagic(Ctx c) {
        BlockPos spot = Locate.randomSpot(c.level, c.r, c.player.blockPosition(), 200, 500);
        Component dir = Names.direction(c.player.blockPosition(), spot);
        String chief = "Warlord " + Names.banditChief(c.r);
        long gold = scale(600, c.lvl, 400, 2000);
        Quest q = base(c, "challenge_nomagic", Quest.Category.BOUNTY, chief, dir);
        q.extra.putBoolean("challenge", true);
        q.extra.putBoolean("nomagic", true);
        q.add(killTarget(c, tr("quest.skycraft.obj.challenge_nomagic", chief), spot, "Duel Arena")
                .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit(chief)).target().boss().health(2.2f).damage(1.4f).build()));
        q.reward(gold);
        return offer(q, chief, dir, gold);
    }

    private static Offer challengeEscort(Ctx c) {
        BlockPos v = Locate.farVillage(c.level, c.npc.blockPosition(), 250, c.r);
        if (v == null) v = c.npc.blockPosition().offset(200, 0, 200);
        Component dir = Names.direction(c.npc.blockPosition(), v);
        long gold = scale(450, c.lvl, 250, 1500);
        Quest q = base(c, "challenge_escort", Quest.Category.BOUNTY, dir);
        q.extra.putBoolean("challenge", true);
        q.add(new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.challenge_escort", dir))
                .at(v, c.dim, 48).guessY().label("Village Safehouse"));
        q.reward(gold);
        return offer(q, dir, gold);
    }
}
