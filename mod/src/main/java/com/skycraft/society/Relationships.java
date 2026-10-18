package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.economy.ItemValues;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What each townsperson thinks of each player (Skyrim's relationship ranks). Disposition runs from -100 to 100 and
 * is stored on the NPC ({@code social.disp.<player uuid>}): gifts, favours and quests raise it; crimes the NPC
 * witnesses (assault, murder, theft, trespass) lower it, and hurting them yourself lowers it a lot. The rank decides
 * greetings, whether they share rumours, prices of favours, and whether they'll consider marriage.
 *
 * <p>Favours are small radiant tasks: the NPC asks for a few items of its trade (wheat, iron, firewood, fish...)
 * and pays in gold and goodwill.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Relationships {
    public enum Rank {
        ENEMY(-100), DISLIKED(-40), STRANGER(-10), ACQUAINTANCE(10), FRIEND(25), CONFIDANT(50), ALLY(75);

        public final int min;

        Rank(int min) {
            this.min = min;
        }

        public static Rank of(int disposition) {
            Rank r = ENEMY;
            for (Rank x : values()) if (disposition >= x.min) r = x;
            return r;
        }

        public Component displayName() {
            return Component.translatable("society.skycraft.rank." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Disposition needed before a townsperson considers a proposal. */
    public static final int MARRIAGE_DISPOSITION = 25;
    private static final long DAY = 24000L;

    private Relationships() {}

    private static CompoundTag disp(NpcEntity npc) {
        return Npcs.sub(npc.social(), "disp");
    }

    public static int personalDisposition(NpcEntity npc, Player player) {
        CompoundTag d = npc.social().getCompoundOrEmpty("disp");
        int base = d.getIntOr(player.getStringUUID(), 0);
        if (!d.contains(player.getStringUUID())) {
            base = Math.max(-30, Math.min(20, Reputation.get(player, Reputation.TOWNSFOLK) / 3));
        }
        return base;
    }

    public static int disposition(NpcEntity npc, Player player) {
        // a spouse always loves you; the town's opinion and regional/racial attitudes colour first impressions
        if (Marriage.isSpouseOf(npc, player)) return 100;
        int base = personalDisposition(npc, player);
        int attitudeDelta = RaceAttitudes.evaluate(npc, player).dispositionDelta();
        return Math.max(-100, Math.min(100, base + attitudeDelta));
    }

    public static Rank rank(NpcEntity npc, Player player) {
        return Rank.of(disposition(npc, player));
    }

    /** Changes how {@code npc} feels about {@code player}; tells the player when the rank changes. */
    public static void adjust(NpcEntity npc, Player player, int delta) {
        if (delta == 0 || npc.level().isClientSide()) return;
        int before = disposition(npc, player);
        int personalBefore = personalDisposition(npc, player);
        int personalAfter = Math.max(-100, Math.min(100, personalBefore + delta));
        disp(npc).putInt(player.getStringUUID(), personalAfter);
        int after = disposition(npc, player);
        Rank was = Rank.of(before);
        Rank now = Rank.of(after);
        if (was != now && player instanceof ServerPlayer sp) {
            Notifier.message(sp, Component.translatable(now.ordinal() > was.ordinal() ? "society.skycraft.rank.up" : "society.skycraft.rank.down",
                    npc.getDisplayName(), now.displayName()));
        }
    }

    /** Public hook for other modules (quests): {@code player} did something for or against {@code npc}. */
    public static void adjust(LivingEntity npc, Player player, int delta) {
        if (npc instanceof NpcEntity n) adjust(n, player, delta);
    }

    // ------------------------------------------------------------------ crimes

    /** Called by the crime module when a crime is reported: everyone who saw it thinks less of the player. */
    public static void onCrimeWitnessed(ServerPlayer player, BlockPos where, int bounty) {
        int loss = Math.max(4, Math.min(40, bounty / 4));
        for (NpcEntity npc : player.level().getEntitiesOfClass(NpcEntity.class, player.getBoundingBox().inflate(20),
                n -> n.isAlive() && n.role().civilian && !n.isSleeping() && n.hasLineOfSight(player))) {
            adjust(npc, player, -loss);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof NpcEntity npc) || npc.level().isClientSide()) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isCreative()) return;
        if (Followers.isFollowerOf(npc, player) && event.getAmount() < 4) return;
        long now = npc.level().getGameTime();
        if (now - npc.social().getLongOr("last_hurt",0L) < 40) return; // one grudge per flurry of blows
        npc.social().putLong("last_hurt", now);
        adjust(npc, player, npc.role().civilian ? -20 : -10);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || npc.level().isClientSide()) return;
        if (event.getSource().getEntity() instanceof ServerPlayer killer && npc.role().civilian) {
            // the dead's friends and neighbours who saw it never forget
            for (NpcEntity w : npc.level().getEntitiesOfClass(NpcEntity.class, npc.getBoundingBox().inflate(20),
                    n -> n != npc && n.isAlive() && n.role().civilian && n.hasLineOfSight(killer))) {
                adjust(w, killer, -50);
            }
        }
    }

    /** The NPC died (or was otherwise destroyed for good). */
    public static void onDeath(NpcEntity npc) {
        NpcRegistry registry = NpcRegistry.get(npc.level());
        if (registry != null) registry.markDead(npc.getUUID());
        Marriage.onSpouseDeath(npc);
    }

    // ------------------------------------------------------------------ gifts

    static void addOptions(ServerPlayer player, NpcEntity npc, List<DialogueOption> out) {
        if (!npc.role().civilian || npc.isPrisoner() || npc.isChild()) return;
        Rank currentRank = rank(npc, player);
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty() && !Marriage.isAmulet(held)
                && (likes(npc, held) || currentRank.ordinal() >= Rank.ACQUAINTANCE.ordinal())) {
            out.add(new DialogueOption("society.gift", Component.translatable("dialogue.skycraft.society.gift", held.getHoverName()), 480,
                    Relationships::gift));
        }
        if (npc.role().townsfolk()) {
            CompoundTag favor = npc.social().getCompoundOrEmpty("favor");
            String key = player.getStringUUID();
            if (favor.contains("item") && key.equals(favor.getStringOr("for",""))) {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(favor.getStringOr("item","")));
                int count = favor.getIntOr("count",0);
                if (count(player, item) >= count) {
                    Component label = com.skycraft.quest.QuestDialogue.questLabel(
                            Component.translatable("dialogue.skycraft.society.favor_done", count, new ItemStack(item).getHoverName()));
                    out.add(new DialogueOption("society.favor_done", label, 105, Relationships::completeFavor));
                }
            } else if (npc.level().getGameTime() >= npc.social().getLongOr("favor_next",0L)
                    && com.skycraft.economy.Merchants.profession(npc) == null
                    && com.skycraft.quest.story.QuestNpcs.idOf(npc).isEmpty()
                    && com.skycraft.quest.QuestDialogue.canOfferQuest(player, npc)
                    && ((npc.getUUID().getLeastSignificantBits() ^ (npc.level().getDefaultClockTime() / DAY)) & 7L) == 0L) {
                out.add(new DialogueOption("society.favor",
                        com.skycraft.quest.QuestDialogue.questLabel(Component.translatable("dialogue.skycraft.society.favor")),
                        475, Relationships::offerFavor));
            }
            if (currentRank.ordinal() >= Rank.FRIEND.ordinal()) {
                out.add(new DialogueOption("society.how", Component.translatable("dialogue.skycraft.society.how"), 590, (p, n) -> {
                    if (!(n instanceof NpcEntity e)) return;
                    Rank r = rank(e, p);
                    Dialogue.open(p, n, Component.translatable("society.skycraft.say.how." + r.name().toLowerCase(Locale.ROOT) + "."
                            + p.getRandom().nextInt(2), p.getDisplayName()));
                }));
            }
        }
    }

    private static void gift(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc) || player.distanceToSqr(npc) > 64) return;
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return;
        long day = player.level().getDefaultClockTime() / DAY;
        CompoundTag gifts = Npcs.sub(npc.social(), "gift_day");
        if (gifts.getLongOr(player.getStringUUID(), 0L) == day + 1) {
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.gift.again"));
            return;
        }
        ItemStack one = held.split(1);
        int value = Math.max(1, ItemValues.get(one));
        boolean liked = likes(npc, one);
        int gain = Math.max(1, Math.min(liked ? 20 : 12, value / 8 + (liked ? 6 : 0)));
        if (value < 3 && !liked) gain = 1;
        gifts.putLong(player.getStringUUID(), day + 1);
        adjust(npc, player, gain);
        npc.level().playSound(null, npc.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.6f, 1.2f);
        String line = liked ? "loved" : value >= 40 ? "generous" : value < 3 ? "trinket" : "thanks";
        Dialogue.open(player, npc, Component.translatable("society.skycraft.say.gift." + line, player.getDisplayName()));
    }

    /** Each trade appreciates its own kind of gift; everyone likes a sweetroll. */
    private static boolean likes(NpcEntity npc, ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id.getPath();
        if (path.contains("sweetroll") || path.contains("mead") || stack.is(Items.CAKE) || path.contains("flower") || stack.is(Items.POPPY)) {
            return true;
        }
        return switch (npc.role()) {
            case BLACKSMITH -> path.contains("ingot") || path.contains("ore");
            case FARMER, BUTCHER, INNKEEPER -> stack.has(net.minecraft.core.component.DataComponents.FOOD);
            case SCHOLAR, MAGE, PRIEST -> path.contains("book") || path.contains("soul_gem") || path.contains("scroll");
            case APOTHECARY -> com.skycraft.economy.ItemCategory.of(stack) == com.skycraft.economy.ItemCategory.INGREDIENTS;
            case BARD -> path.contains("lute") || path.contains("drum") || path.contains("flute") || stack.is(Items.NOTE_BLOCK);
            case HUNTER -> path.contains("pelt") || path.contains("hide") || stack.is(Items.ARROW);
            case MINER -> path.contains("gem") || stack.is(Items.DIAMOND) || stack.is(Items.EMERALD);
            case CHILD -> stack.has(net.minecraft.core.component.DataComponents.FOOD) || stack.is(Items.SNOWBALL);
            case BEGGAR -> stack.has(net.minecraft.core.component.DataComponents.FOOD);
            default -> com.skycraft.economy.ItemCategory.isJewelry(stack);
        };
    }

    // ------------------------------------------------------------------ favours

    private record Favor(Item item, int count, int reward) {}

    private static Favor favorFor(NpcEntity npc, net.minecraft.util.RandomSource r) {
        return switch (npc.role()) {
            case FARMER -> r.nextBoolean() ? new Favor(Items.BONE_MEAL, 12, 40) : new Favor(Items.WHEAT_SEEDS, 16, 30);
            case BLACKSMITH -> r.nextBoolean() ? new Favor(Items.IRON_INGOT, 4, 70) : new Favor(Items.COAL, 12, 45);
            case MINER -> new Favor(Items.TORCH, 16, 35);
            case LUMBERJACK -> new Favor(Items.IRON_AXE, 1, 60);
            case HUNTER -> new Favor(Items.ARROW, 24, 40);
            case FISHER -> r.nextBoolean() ? new Favor(Items.COD, 6, 45) : new Favor(Items.SALMON, 4, 45);
            case BUTCHER -> new Favor(Items.LEATHER, 6, 55);
            case APOTHECARY -> r.nextBoolean() ? new Favor(Items.RED_MUSHROOM, 6, 50) : new Favor(Items.SPIDER_EYE, 3, 55);
            case SCHOLAR, PRIEST -> r.nextBoolean() ? new Favor(Items.BOOK, 3, 60) : new Favor(Items.CANDLE, 4, 40);
            case INNKEEPER -> r.nextBoolean() ? new Favor(Items.HONEY_BOTTLE, 3, 50) : new Favor(Items.OAK_LOG, 16, 40);
            case MERCHANT -> new Favor(Items.PAPER, 12, 40);
            case BEGGAR -> new Favor(Items.BREAD, 2, 0);
            default -> r.nextBoolean() ? new Favor(Items.OAK_LOG, 12, 30) : new Favor(Items.BREAD, 3, 25);
        };
    }

    private static void offerFavor(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc)) return;
        if (rank(npc, player).ordinal() <= Rank.DISLIKED.ordinal()) {
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.favor.refuse"));
            return;
        }
        Favor f = favorFor(npc, player.getRandom());
        CompoundTag favor = new CompoundTag();
        favor.putString("item", BuiltInRegistries.ITEM.getKey(f.item()).toString());
        favor.putInt("count", f.count());
        favor.putInt("reward", f.reward());
        favor.putString("for", player.getStringUUID());
        npc.social().put("favor", favor);
        Dialogue.open(player, npc, Component.translatable("society.skycraft.say.favor.ask." + npc.getRandom().nextInt(3), f.count(),
                new ItemStack(f.item()).getHoverName(), f.reward()));
    }

    private static void completeFavor(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc) || player.distanceToSqr(npc) > 64) return;
        CompoundTag favor = npc.social().getCompoundOrEmpty("favor");
        if (!favor.contains("item") || !player.getStringUUID().equals(favor.getStringOr("for",""))) return;
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(favor.getStringOr("item","")));
        int count = favor.getIntOr("count",0);
        if (count(player, item) < count) return;
        take(player, item, count);
        int reward = favor.getIntOr("reward",0);
        if (reward > 0) Currency.give(player, reward);
        npc.social().remove("favor");
        npc.social().putLong("favor_next", npc.level().getGameTime() + 2 * DAY);
        adjust(npc, player, 15);
        Reputation.add(player, Reputation.TOWNSFOLK, 1);
        Dialogue.open(player, npc, Component.translatable(reward > 0 ? "society.skycraft.say.favor.done" : "society.skycraft.say.favor.done_poor",
                reward));
    }

    private static int count(Player player, Item item) {
        return com.skycraft.inventory.Inv.count(player, item);
    }

    private static void take(Player player, Item item, int count) {
        com.skycraft.inventory.Inv.take(player, item, count);
    }

    /** Gives a stack to the player's bags (or drops it at their feet). */
    static void give(ServerPlayer player, ItemStack stack) {
        if (!ActionHandler.addToBags(player, stack)) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    @Nullable
    static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
