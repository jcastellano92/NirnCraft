package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.core.Buffs;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.economy.Houses;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Skyrim-style marriage. Wear an Amulet of Mara (sold by any temple priest) and an unmarried townsperson who thinks
 * well enough of you will accept a proposal. The wedding is held at the nearest temple (the betrothed walks to the
 * priest; tell the priest you're ready) or on the spot when there is no temple about. A spouse moves into your house
 * (economy Houses) or lives wherever you ask, keeps house by day, hands over the day's earnings of their trade and
 * cooks a home meal that leaves you "Lover's Comfort".
 *
 * <p>State: player {@code module("society").spouse {uuid, name, since, gold_day, meal_day}} and
 * {@code .engaged {uuid, name}}; NPC {@code social.spouse} / {@code social.engaged} (player UUIDs); the registry
 * remembers the spouse too, so a death while the player is away is reported when they log in.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Marriage {
    public static final Identifier AMULET = Identifier.fromNamespaceAndPath(Skycraft.MODID, "amulet_of_mara");
    public static final int AMULET_PRICE = 200;
    public static final String BUFF = "lovers_comfort";
    private static final String MODULE = "society";
    private static final long DAY = 24000L;

    private Marriage() {}

    // ------------------------------------------------------------------ queries

    public static boolean isAmulet(ItemStack stack) {
        return !stack.isEmpty() && AMULET.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** Worn: in an armor slot (where jewelry is equipped) or held in either hand. */
    public static boolean wearsAmulet(Player player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (isAmulet(player.getItemBySlot(slot))) return true;
        }
        return false;
    }

    public static boolean isSpouse(NpcEntity npc) {
        return npc.social().read("spouse", net.minecraft.core.UUIDUtil.CODEC).isPresent();
    }

    public static boolean isSpouseOf(NpcEntity npc, Player player) {
        return npc.social().read("spouse", net.minecraft.core.UUIDUtil.CODEC).isPresent() && npc.social().read("spouse", net.minecraft.core.UUIDUtil.CODEC).orElseThrow().equals(player.getUUID());
    }

    private static CompoundTag data(Player player) {
        return SkyData.get(player).module(MODULE);
    }

    @Nullable
    public static UUID spouseOf(Player player) {
        CompoundTag s = data(player).getCompoundOrEmpty("spouse");
        return s.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? s.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
    }

    private static boolean engagedTo(Player player, NpcEntity npc) {
        CompoundTag e = data(player).getCompoundOrEmpty("engaged");
        return e.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent() && e.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow().equals(npc.getUUID());
    }

    private static boolean free(Player player) {
        CompoundTag d = data(player);
        return !d.getCompoundOrEmpty("spouse").read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent() && !d.getCompoundOrEmpty("engaged").read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent();
    }

    // ------------------------------------------------------------------ dialogue

    static void addOptions(ServerPlayer player, NpcEntity npc, List<DialogueOption> out) {
        if (npc.isPrisoner() || npc.isChild()) return;
        NpcRole role = npc.role();
        if (role == NpcRole.PRIEST) {
            out.add(new DialogueOption("society.mara_about", Component.translatable("dialogue.skycraft.society.mara_about"), 300,
                    (p, n) -> Dialogue.open(p, n, Component.translatable("society.skycraft.say.mara_about"))));
            out.add(new DialogueOption("society.mara_buy", Component.translatable("dialogue.skycraft.society.mara_buy", AMULET_PRICE), 301,
                    Marriage::buyAmulet));
            CompoundTag e = data(player).getCompoundOrEmpty("engaged");
            if (e.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
                out.add(new DialogueOption("society.wed", Component.translatable("dialogue.skycraft.society.wed", e.getStringOr("name","")), 290,
                        Marriage::wedAtTemple));
            }
        }
        if (isSpouseOf(npc, player)) {
            spouseOptions(player, npc, out);
            return;
        }
        if (engagedTo(player, npc)) {
            out.add(new DialogueOption("society.wed_here", Component.translatable("dialogue.skycraft.society.wed_here"), 291, (p, n) -> {
                if (!(n instanceof NpcEntity fiance)) return;
                NpcEntity priest = nearestPriest(fiance, 48);
                if (priest != null) Dialogue.open(p, n, Component.translatable("society.skycraft.say.wed_temple", priest.getDisplayName()));
                else startWedding(p, fiance, fiance);
            }));
            return;
        }
        if (role.marriageable() && !isSpouse(npc) && !npc.social().read("engaged", net.minecraft.core.UUIDUtil.CODEC).isPresent() && wearsAmulet(player) && free(player)) {
            out.add(new DialogueOption("society.propose", Component.translatable("dialogue.skycraft.society.propose"), 280, Marriage::propose));
        }
    }

    private static void buyAmulet(ServerPlayer player, LivingEntity priest) {
        Item amulet = BuiltInRegistries.ITEM.getValue(AMULET);
        if (amulet == Items.AIR) return;
        if (!Currency.take(player, AMULET_PRICE)) {
            Dialogue.open(player, priest, Component.translatable("society.skycraft.say.mara_no_gold"));
            return;
        }
        Relationships.give(player, new ItemStack(amulet));
        Dialogue.open(player, priest, Component.translatable("society.skycraft.say.mara_sold"));
    }

    private static void propose(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc) || !wearsAmulet(player) || !free(player) || isSpouse(npc)) return;
        int disp = Relationships.disposition(npc, player);
        if (disp < Relationships.MARRIAGE_DISPOSITION) {
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.propose.refuse." + player.getRandom().nextInt(3)));
            return;
        }
        npc.social().store("engaged", net.minecraft.core.UUIDUtil.CODEC,  player.getUUID());
        npc.setPersistenceRequired();
        CompoundTag e = new CompoundTag();
        e.store("uuid", net.minecraft.core.UUIDUtil.CODEC,  npc.getUUID());
        e.putString("name", npc.getDisplayName().getString());
        data(player).put("engaged", e);
        SkyData.get(player).markDirty();
        NpcEntity priest = nearestPriest(npc, 128);
        BlockPos temple = priest != null ? priest.blockPosition()
                : npc.level() instanceof ServerLevel sl ? Workplaces.anchor(sl, npc.blockPosition(), Workplaces.Kind.TEMPLE, 160) : null;
        if (temple != null) {
            npc.setDestination(temple);
            npc.setHomeTo(temple, 6); // waits at the temple (see the routine) until the wedding
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.propose.accept_temple." + player.getRandom().nextInt(2)));
        } else {
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.propose.accept_here"));
            startWedding(player, npc, npc);
        }
    }

    @Nullable
    private static NpcEntity nearestPriest(LivingEntity near, double radius) {
        NpcEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (NpcEntity p : near.level().getEntitiesOfClass(NpcEntity.class, near.getBoundingBox().inflate(radius, 32, radius),
                x -> x.isAlive() && x.role() == NpcRole.PRIEST && !x.isPrisoner())) {
            double d = p.distanceToSqr(near);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static void wedAtTemple(ServerPlayer player, LivingEntity priestEntity) {
        if (!(priestEntity instanceof NpcEntity priest)) return;
        CompoundTag e = data(player).getCompoundOrEmpty("engaged");
        if (!e.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent() || !(player.level().getEntity(e.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow()) instanceof NpcEntity fiance)
                || fiance.distanceToSqr(priest) > 24 * 24) {
            Dialogue.open(player, priest, Component.translatable("society.skycraft.say.wed_bring", e.getStringOr("name","")));
            return;
        }
        Dialogue.endConversation(player, priest.getId());
        startWedding(player, fiance, priest);
    }

    // ------------------------------------------------------------------ the wedding

    private static final class Wedding {
        final ServerPlayer player;
        final NpcEntity spouse;
        final NpcEntity officiant;
        int step;
        long next;

        Wedding(ServerPlayer player, NpcEntity spouse, NpcEntity officiant, long next) {
            this.player = player;
            this.spouse = spouse;
            this.officiant = officiant;
            this.next = next;
        }
    }

    private static final List<Wedding> WEDDINGS = new ArrayList<>();

    private static void startWedding(ServerPlayer player, NpcEntity spouse, NpcEntity officiant) {
        for (Wedding w : WEDDINGS) if (w.player == player) return;
        spouse.setDestination(null);
        if (officiant != spouse && spouse.distanceToSqr(officiant) > 4 * 4) {
            spouse.getNavigation().moveTo(officiant, 0.7D);
        }
        Notifier.title(player, Component.translatable("society.skycraft.wedding.title"), spouse.getDisplayName());
        WEDDINGS.add(new Wedding(player, spouse, officiant, player.level().getGameTime() + 40));
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || WEDDINGS.isEmpty()) return;
        for (Iterator<Wedding> it = WEDDINGS.iterator(); it.hasNext(); ) {
            Wedding w = it.next();
            if (w.player.isRemoved() || !w.spouse.isAlive() || !w.officiant.isAlive() || w.player.distanceToSqr(w.spouse) > 32 * 32) {
                it.remove();
                continue;
            }
            long now = w.player.level().getGameTime();
            if (now < w.next) continue;
            w.next = now + 70;
            ServerLevel level = w.player.level();
            w.spouse.getLookControl().setLookAt(w.player, 30f, 30f);
            if (w.step < 4) {
                boolean selfWed = w.officiant == w.spouse;
                Barks.say(w.officiant, Component.translatable("society.skycraft.wedding." + (selfWed ? "self." : "") + w.step,
                        w.player.getDisplayName(), w.spouse.getDisplayName()));
                if (w.step == 1) level.playSound(null, w.officiant.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 1.0f, 1.2f);
                w.step++;
                continue;
            }
            level.sendParticles(ParticleTypes.HEART, w.spouse.getX(), w.spouse.getY() + 2.1, w.spouse.getZ(), 8, 0.5, 0.3, 0.5, 0.05);
            level.sendParticles(ParticleTypes.HEART, w.player.getX(), w.player.getY() + 2.1, w.player.getZ(), 8, 0.5, 0.3, 0.5, 0.05);
            level.playSound(null, w.spouse.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.1f);
            marry(w.player, w.spouse);
            it.remove();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WEDDINGS.clear();
    }

    private static void marry(ServerPlayer player, NpcEntity npc) {
        npc.social().remove("engaged");
        npc.social().store("spouse", net.minecraft.core.UUIDUtil.CODEC,  player.getUUID());
        if (npc.getHome() != null) npc.setHome(npc.getHome(), 16);
        else npc.setHome(npc.blockPosition(), 16);
        npc.social().putString("spouse_name", player.getName().getString());
        npc.setPersistenceRequired();
        npc.getPersistentData().remove(Encounters.TAG);
        PlayerData pd = SkyData.get(player);
        CompoundTag d = data(player);
        d.remove("engaged");
        CompoundTag s = new CompoundTag();
        s.store("uuid", net.minecraft.core.UUIDUtil.CODEC,  npc.getUUID());
        s.putString("name", npc.getDisplayName().getString());
        s.putLong("since", player.level().getOverworldClockTime() / DAY);
        s.putLong("gold_day", player.level().getOverworldClockTime() / DAY);
        d.put("spouse", s);
        pd.markDirty();
        pd.addStat("married", 1);
        NpcRegistry registry = NpcRegistry.get(player.level().getServer());
        NpcRegistry.onSettled(npc);
        registry.setSpouse(npc.getUUID(), player.getUUID());
        Relationships.adjust(npc, player, 100);
        // the newlyweds' home: the player's house if they own one, else the spouse's old home
        npc.setWorkplace(null);
        Notifier.title(player, Component.translatable("society.skycraft.wedding.done"), npc.getDisplayName());
        Barks.say(npc, Component.translatable("society.skycraft.say.newlywed", player.getDisplayName()));
    }

    // ------------------------------------------------------------------ married life

    private static void spouseOptions(ServerPlayer player, NpcEntity npc, List<DialogueOption> out) {
        BlockPos house = ownedHouse(player);
        if (house != null && (npc.getHome() == null || npc.getHome().distSqr(house) > 24 * 24)) {
            out.add(new DialogueOption("society.spouse_move", Component.translatable("dialogue.skycraft.society.spouse_move"), 140,
                    (p, n) -> {
                        BlockPos h = ownedHouse(p);
                        if (h != null && n instanceof NpcEntity s) {
                            moveHome(s, p.level(), h);
                            Dialogue.open(p, n, Component.translatable("society.skycraft.say.spouse_moved"));
                        }
                    }));
        }
        out.add(new DialogueOption("society.spouse_live_here", Component.translatable("dialogue.skycraft.society.spouse_live_here"), 141,
                (p, n) -> {
                    if (n instanceof NpcEntity s) {
                        moveHome(s, p.level(), s.blockPosition());
                        Dialogue.open(p, n, Component.translatable("society.skycraft.say.spouse_settled"));
                    }
                }));
        if (!Followers.isFollower(npc)) {
            out.add(new DialogueOption("society.spouse_follow", Component.translatable("dialogue.skycraft.society.spouse_follow"), 142,
                    (p, n) -> {
                        Followers.recruit(p, n);
                        Dialogue.open(p, n, Component.translatable("society.skycraft.say.spouse_follow"));
                    }));
        }
        long day = player.level().getOverworldClockTime() / DAY;
        CompoundTag s = data(player).getCompoundOrEmpty("spouse");
        if (s.getLongOr("gold_day",0L) < day) {
            out.add(new DialogueOption("society.spouse_gold", Component.translatable("dialogue.skycraft.society.spouse_gold"), 143,
                    Marriage::collectEarnings));
        }
        if (s.getLongOr("meal_day",0L) < day + 1) {
            out.add(new DialogueOption("society.spouse_meal", Component.translatable("dialogue.skycraft.society.spouse_meal"), 144,
                    Marriage::homeMeal));
        }
    }

    /** Position of a house the player owns (economy Houses), or null. */
    @Nullable
    public static BlockPos ownedHouse(ServerPlayer player) {
        try {
            // the owned home nearest the player, at its bed as built (not the planned spot, which has no floor height)
            BlockPos home = Houses.homeOf(player);
            if (home != null) return home;
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft society: house lookup failed", e);
        }
        return null;
    }

    /** The spouse moves to {@code home} (one chunk is loaded once to put them there). */
    static void moveHome(NpcEntity npc, ServerLevel level, BlockPos home) {
        Settlers.releaseBed(npc);
        if (npc.level() == level && npc.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5) > 32 * 32) {
            level.getChunk(home.getX() >> 4, home.getZ() >> 4);
            BlockPos ground = Encounters.groundAt(level, home.getX(), home.getZ());
            BlockPos at = ground != null && Math.abs(ground.getY() - home.getY()) < 12 ? ground : home;
            npc.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            npc.getNavigation().stop();
        }
        npc.setHome(home, 16);
        npc.setWorkplace(null);
        npc.setInn(null);
        npc.setDestination(null);
        NpcRegistry.get(level.getServer()).setHome(npc.getUUID(), home, level.dimension().identifier().toString());
        if (Followers.isFollower(npc) && npc.level() instanceof ServerLevel) {
            UUID owner = Followers.getOwnerId(npc);
            ServerPlayer p = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
            if (p != null) Followers.dismiss(p, npc);
        }
    }

    private static void collectEarnings(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc) || !isSpouseOf(npc, player)) return;
        long day = player.level().getOverworldClockTime() / DAY;
        CompoundTag d = data(player);
        CompoundTag s = d.getCompoundOrEmpty("spouse");
        if (s.getLongOr("gold_day",0L) >= day) return;
        boolean trade = npc.getPersistentData().contains(com.skycraft.economy.Shop.KEY) || npc.getPersistentData().contains(Npcs.PROFESSION_KEY);
        int gold = SocietyConfig.SPOUSE_DAILY_GOLD.get();
        if (!trade) gold /= 2;
        s.putLong("gold_day", day);
        d.put("spouse", s);
        SkyData.get(player).markDirty();
        if (gold > 0) Currency.give(player, gold);
        Dialogue.open(player, npc, Component.translatable(trade ? "society.skycraft.say.spouse_gold" : "society.skycraft.say.spouse_gold_home", gold));
    }

    private static void homeMeal(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc) || !isSpouseOf(npc, player)) return;
        long day = player.level().getOverworldClockTime() / DAY;
        CompoundTag d = data(player);
        CompoundTag s = d.getCompoundOrEmpty("spouse");
        if (s.getLongOr("meal_day",0L) >= day + 1) return;
        s.putLong("meal_day", day + 1);
        d.put("spouse", s);
        SkyData.get(player).markDirty();
        Item[] dishes = {Items.RABBIT_STEW, Items.BEETROOT_SOUP, Items.MUSHROOM_STEW, Items.COOKED_SALMON, Items.PUMPKIN_PIE};
        ItemStack meal = new ItemStack(dishes[player.getRandom().nextInt(dishes.length)]);
        meal.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("society.skycraft.item.home_meal"));
        Relationships.give(player, meal);
        Buffs.apply(player, BUFF, 20 * 60 * 8);
        player.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.REGENERATION), 20 * 20, 0));
        player.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.LUCK), 20 * 60 * 8, 0));
        Notifier.message(player, Component.translatable("society.skycraft.lovers_comfort"));
        Dialogue.open(player, npc, Component.translatable("society.skycraft.say.spouse_meal." + player.getRandom().nextInt(3)));
    }

    // ------------------------------------------------------------------ death & logins

    /** A spouse died: the player is widowed (now, or when they next log in). */
    static void onSpouseDeath(NpcEntity npc) {
        if (!npc.social().read("spouse", net.minecraft.core.UUIDUtil.CODEC).isPresent() || !(npc.level() instanceof ServerLevel level)) return;
        UUID playerId = npc.social().read("spouse", net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) widow(player, npc.getDisplayName());
        if (npc.social().read("engaged", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(npc.social().read("engaged", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            if (p != null) {
                data(p).remove("engaged");
                SkyData.get(p).markDirty();
            }
        }
    }

    private static void widow(ServerPlayer player, Component name) {
        CompoundTag d = data(player);
        if (!d.getCompoundOrEmpty("spouse").read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent()) return;
        d.remove("spouse");
        SkyData.get(player).markDirty();
        Notifier.title(player, Component.translatable("society.skycraft.widowed.title"), Component.translatable("society.skycraft.widowed", name));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        UUID spouse = spouseOf(player);
        if (spouse != null && NpcRegistry.get(server).isDead(spouse)) {
            widow(player, Component.literal(data(player).getCompoundOrEmpty("spouse").getStringOr("name","")));
        }
        CompoundTag e = data(player).getCompoundOrEmpty("engaged");
        if (e.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent() && NpcRegistry.get(server).isDead(e.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow())) {
            data(player).remove("engaged");
            SkyData.get(player).markDirty();
        }
    }
}
