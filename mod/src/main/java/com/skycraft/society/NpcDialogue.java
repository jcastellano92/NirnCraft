package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.core.Buffs;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Conversations with society NPCs. Right-clicking one opens the shared dialogue menu with a role greeting (hostile
 * NPCs don't talk). Topics: the beggar's coin, the bard's song, the priest's healing, the innkeeper's mead, the
 * soldiers' war news, the Jarl's opinion of you, freeing prisoners, and "what do you do?" for everyone. Rooms, rumors
 * and disease cures are offered by the survival module, which recognizes our NPCs by persistent {@code skycraft_role}.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class NpcDialogue {
    public static final int DRINK_PRICE = 5;
    /** Stack tag on the fugitive's "Stolen Nordic Blade" (the one the bounty hunter wants back). */
    public static final String STOLEN_BLADE_TAG = "skycraft_fugitive_blade";
    /** Persistent UUID on an NPC who accepted a duel with that player: killing them is not murder. */
    public static final String DUEL_TAG = "skycraft_duel_with";

    private NpcDialogue() {}

    /** Inventory slot of the fugitive's stolen blade, or -1. */
    private static int stolenBladeSlot(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getTag() != null && s.getTag().getBoolean(STOLEN_BLADE_TAG)) return i;
        }
        return -1;
    }

    static void register() {
        Dialogue.registerProvider(NpcDialogue::addOptions);
    }

    /** Handles the click ourselves (both sides) so every NPC type gets a role greeting and hostile NPCs stay silent. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof NpcEntity npc) || !npc.isAlive()) return;
        Player player = event.getEntity();
        if (player.isShiftKeyDown()) return; // pickpocketing (crime module)
        ItemStack held = event.getItemStack();
        if (held.is(Items.NAME_TAG) || held.is(Items.LEAD)) return;
        if (player instanceof ServerPlayer sp) {
            if (npc.getTarget() == sp || npc.isHostileTo(sp)) {
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
                return;
            }
            if (sp.getUUID().equals(npc.getFearedPlayer())) {
                Barks.say(npc, Component.literal("Stay away from me! Leave me alone!"));
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }
            // townsfolk chatting among themselves break off for the player, like in Skyrim
            if (npc.isConversing() && sp.serverLevel().getEntity(npc.getConversationPartner()) instanceof NpcEntity) Chatter.interrupt(npc);
            if (npc.isConversing() && npc.getConversationPartner() != sp.getId()) {
                Entity current = sp.serverLevel().getEntity(npc.getConversationPartner());
                if (current != null && current.isAlive() && npc.distanceToSqr(current) <= 64) {
                    Notifier.message(sp, Component.translatable("society.skycraft.busy"));
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                }
            }
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(sp, 30f, 30f);
            Dialogue.open(sp, npc, greeting(sp, npc));
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    static Component greeting(ServerPlayer player, NpcEntity npc) {
        if (npc.getPersistentData().getBoolean("skycraft_old_orc")) {
            return Component.literal("I am waiting for a good death. Old age is a shame for an Orc warrior. Send me to Malacath with honor!");
        }
        if (npc.getPersistentData().getBoolean("skycraft_fugitive")) {
            return Component.literal("Take this! Don't tell anyone you saw me, or I'll kill you when I return!");
        }
        if (npc.getPersistentData().getBoolean("skycraft_bounty_hunter")) {
            return Component.literal("Hold, traveler. Did you see a fugitive flee through here carrying stolen property?");
        }
        if (npc.getPersistentData().getBoolean("skycraft_reveler")) {
            return Component.literal("Hail, traveler! We are drinking Honningbrew Mead in celebration of life! Care to share a toast?");
        }
        NpcRole role = npc.role();
        if (npc.isPrisoner()) return line("prisoner");
        String travelKind = travelerKind(npc);
        if (travelKind != null && npc.getRandom().nextInt(4) != 0) {
            return Component.translatable("society.skycraft.greet.traveler." + travelKind + "." + npc.getRandom().nextInt(3), player.getName());
        }
        if (Marriage.isSpouseOf(npc, player)) {
            return Component.translatable("society.skycraft.greet.spouse." + npc.getRandom().nextInt(4), player.getName());
        }
        int townsfolk = Reputation.get(player, Reputation.TOWNSFOLK);
        Relationships.Rank rank = role.civilian ? Relationships.rank(npc, player) : Relationships.Rank.STRANGER;
        if (role.civilian && (townsfolk <= -40 || rank.ordinal() <= Relationships.Rank.DISLIKED.ordinal())) return line("cold");
        if (role == NpcRole.JARL && townsfolk >= 50) return line("jarl_honored");
        if (role == NpcRole.THALMOR) return line("thalmor");
        if (npc.isChild()) return Component.translatable("society.skycraft.greet.child." + npc.getRandom().nextInt(4), player.getName());
        if (role.civilian && rank.ordinal() >= Relationships.Rank.FRIEND.ordinal() && npc.getRandom().nextBoolean()) {
            return Component.translatable("society.skycraft.greet.friend." + npc.getRandom().nextInt(4), player.getName());
        }
        // Khajiit speak of themselves in the third person; Argonians and Dunmer have their own way of greeting
        if (role.civilian && npc.getRandom().nextInt(5) < 2) {
            switch (npc.race()) {
                case KHAJIIT -> {
                    return Component.translatable("society.skycraft.greet.khajiit." + npc.getRandom().nextInt(4), player.getName());
                }
                case ARGONIAN -> {
                    return Component.translatable("society.skycraft.greet.argonian." + npc.getRandom().nextInt(3), player.getName());
                }
                case DUNMER -> {
                    return Component.translatable("society.skycraft.greet.dunmer." + npc.getRandom().nextInt(3), player.getName());
                }
                case ORC -> {
                    return Component.translatable("society.skycraft.greet.orc." + npc.getRandom().nextInt(2), player.getName());
                }
                default -> {
                }
            }
        }
        return Component.translatable("society.skycraft.greet." + role.id + "." + npc.getRandom().nextInt(2), player.getName());
    }

    /** The kinds of road traveller that have their own lines (see roads.Travelers), or null for everyone else. */
    private static final java.util.Set<String> TRAVELER_KINDS = java.util.Set.of("caravan", "courier", "pilgrim", "hunter", "bard",
            "soldiers", "tinker", "farmer", "refugees", "adventurer", "tax");

    @org.jetbrains.annotations.Nullable
    static String travelerKind(NpcEntity npc) {
        CompoundTag t = npc.getPersistentData().getCompound("skycraft_traveler");
        String kind = t.getString("kind");
        return TRAVELER_KINDS.contains(kind) ? kind : null;
    }

    private static Component line(String key) {
        return Component.translatable("society.skycraft.greet." + key);
    }

    private static Component say(String key, Object... args) {
        return Component.translatable("society.skycraft.say." + key, args);
    }

    private static Component opt(String key, Object... args) {
        return Component.translatable("dialogue.skycraft.society." + key, args);
    }

    // ------------------------------------------------------------------ topics

    static void addOptions(ServerPlayer player, LivingEntity entity, List<DialogueOption> out) {
        if (!(entity instanceof NpcEntity npc) || npc.isHostileTo(player)) return;
        String hold = com.skycraft.core.Holds.holdAt(player.level(), player.blockPosition());
        if (com.skycraft.crime.Crimes.isGuard(npc) && com.skycraft.crime.Bounty.get(player, hold) > 0) return;
        NpcRole role = npc.role();
        if (npc.isPrisoner()) {
            out.add(new DialogueOption("society.free", opt("free"), 10, NpcDialogue::freePrisoner));
            return;
        }

        if (npc.getPersistentData().getBoolean("skycraft_old_orc")) {
            out.add(new DialogueOption("society.orc_duel", Component.literal("I will grant you a warrior's death."), 10, (p, n) -> {
                if (n instanceof NpcEntity orc) {
                    // an accepted duel: killing him is not murder (see crime.Crimes#onDeath)
                    orc.getPersistentData().putUUID(DUEL_TAG, p.getUUID());
                    orc.setTarget(p);
                    Barks.sayLineNow(orc, "attack.generic", p.getDisplayName());
                    Dialogue.open(p, n, Component.literal("Malacath guide our blades! To glory!"));
                }
            }));
            out.add(new DialogueOption("society.orc_why", Component.literal("Why do you seek death?"), 20, (p, n) -> {
                Dialogue.open(p, n, Component.literal("I am old. My family is gone, my stronghold forgotten. I will not die useless in a bed like a weakling."));
            }));
            return;
        }

        if (npc.getPersistentData().getBoolean("skycraft_fugitive")) {
            out.add(new DialogueOption("society.fugitive_take", Component.literal("Take the item and let him run."), 10, (p, n) -> {
                if (n instanceof NpcEntity fug) {
                    ItemStack gift = new ItemStack(Items.IRON_SWORD);
                    gift.setHoverName(Component.literal("Stolen Nordic Blade"));
                    gift.getOrCreateTag().putBoolean(STOLEN_BLADE_TAG, true);
                    com.skycraft.crime.Bounty.markStolen(gift);
                    if (!ActionHandler.addToBags(p, gift)) p.drop(gift, false);
                    fug.getPersistentData().remove("skycraft_fugitive");
                    Notifier.message(p, Component.literal("The fugitive shoved a Stolen Nordic Blade into your hands and bolted!"));
                    Dialogue.open(p, n, Component.literal("Keep it safe! If you betray me, you'll regret it!"));
                }
            }));
            return;
        }

        if (npc.getPersistentData().getBoolean("skycraft_bounty_hunter")) {
            out.add(new DialogueOption("society.hunter_point", Component.literal("He ran that way into the woods."), 10, (p, n) -> {
                Dialogue.open(p, n, Component.literal("Good. He won't get far. The law always catches up."));
            }));
            out.add(new DialogueOption("society.hunter_lie", Component.literal("I haven't seen anyone pass by."), 20, (p, n) -> {
                Dialogue.open(p, n, Component.literal("Hmm. If you're hiding him, you'll share his fate. Keep your eyes open."));
            }));
            if (!npc.getPersistentData().getBoolean("skycraft_blade_returned") && stolenBladeSlot(player) >= 0) {
                out.add(new DialogueOption("society.hunter_return", Component.literal("He gave me this stolen blade. Take it."), 30, (p, n) -> {
                    // re-check on choose: the blade must still be in the bags, and each hunter pays only once
                    int slot = stolenBladeSlot(p);
                    if (slot < 0 || n.getPersistentData().getBoolean("skycraft_blade_returned")) return;
                    p.getInventory().getItem(slot).shrink(1);
                    n.getPersistentData().putBoolean("skycraft_blade_returned", true);
                    Currency.give(p, 40);
                    Notifier.message(p, Component.literal("Bounty Hunter handed you 40 Septims as a reward for returning stolen property."));
                    Dialogue.open(p, n, Component.literal("An honest citizen in Skyrim! Rare indeed. Take this reward for your trouble."));
                }));
            }
            return;
        }

        if (npc.getPersistentData().getBoolean("skycraft_reveler")) {
            out.add(new DialogueOption("society.reveler_drink", Component.literal("I'll share a drink with you! (Toast)"), 10, (p, n) -> {
                // one toast per reveler per player per in-game day
                CompoundTag toasts = n.getPersistentData().getCompound("skycraft_toasts");
                long day = p.level().getDayTime() / 24000L;
                String key = p.getUUID().toString();
                if (toasts.contains(key) && toasts.getLong(key) == day) {
                    Dialogue.open(p, n, Component.literal("Ha! You've had your share today, friend. Come back tomorrow!"));
                    return;
                }
                toasts.putLong(key, day);
                n.getPersistentData().put("skycraft_toasts", toasts);
                ItemStack mead = new ItemStack(Items.HONEY_BOTTLE);
                mead.setHoverName(Component.literal("Honningbrew Mead"));
                if (!ActionHandler.addToBags(p, mead)) p.drop(mead, false);
                p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 60 * 3, 0));
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 15, 0));
                Notifier.message(p, Component.literal("You shared a toast of Honningbrew Mead! (+Strength & Regeneration)"));
                Dialogue.open(p, n, Component.literal("To victory and good health! May your travels be blessed!"));
            }));
            out.add(new DialogueOption("society.reveler_why", Component.literal("What are you celebrating?"), 20, (p, n) -> {
                Dialogue.open(p, n, Component.literal("Skyrim! Life, friendship, and fine Nord mead! What else does one need?"));
            }));
            return;
        }

        // "what do you do around here?" is a silly thing to ask a jarl, a steward, a mage or a court wizard
        boolean obviousJob = role == NpcRole.JARL || role == NpcRole.STEWARD || role == NpcRole.HOUSECARL || role == NpcRole.MAGE
                || npc.getPersistentData().getString(com.skycraft.economy.Merchants.PROFESSION_KEY).equals("skycraft:court_wizard")
                || !com.skycraft.quest.story.QuestNpcs.idOf(npc).isEmpty();
        if (!obviousJob) out.add(new DialogueOption("society.job", opt("job"), 600,
                (p, n) -> Dialogue.open(p, n, jobLine(n, role))));
        // people on the road talk about the road (pilgrims of their shrine, soldiers of their orders, tinkers of their wares)
        String travelKind = travelerKind(npc);
        if (travelKind != null) {
            out.add(new DialogueOption("society.traveler", opt("traveler_news"), 15, (p, n) -> Dialogue.open(p, n,
                    Component.translatable("society.skycraft.say.traveler." + travelKind + "." + p.getRandom().nextInt(3)))));
        }
        Relationships.addOptions(player, npc, out);
        Marriage.addOptions(player, npc, out);
        if (role.civilian && !npc.isChild()) {
            out.add(new DialogueOption("society.region", opt("region"), 530, NpcDialogue::aboutRegion));
            if (role != NpcRole.INNKEEPER && Relationships.rank(npc, player).ordinal() >= Relationships.Rank.STRANGER.ordinal()) {
                out.add(new DialogueOption("society.news", opt("news"), 520, NpcDialogue::news));
            }
        }

        switch (role) {
            case BEGGAR -> out.add(new DialogueOption("society.coin", opt("coin"), 10, NpcDialogue::giveCoin));
            case BARD -> {
                boolean atInn = isNearInn(npc);
                out.add(new DialogueOption("society.song", opt("song"), 10, (p, n) -> {
                    if (atInn) {
                        int song = p.getRandom().nextInt(Chatter.SONGS);
                        if (n instanceof NpcEntity b) {
                            b.requestSong();
                            Chatter.sing(b, song);
                        }
                        Dialogue.open(p, n, Component.translatable("society.skycraft.say.song_named", Component.translatable("society.skycraft.song." + song + ".title")));
                    } else {
                        Dialogue.open(p, n, Component.literal("I only perform at taverns and inns, traveler. Look for me there."));
                    }
                }));
            }
            // room, rumors and disease cures come from the survival module (it recognizes persistent "skycraft_role")
            case PRIEST -> out.add(new DialogueOption("society.heal", opt("heal"), 10, NpcDialogue::heal));
            case INNKEEPER -> out.add(new DialogueOption("society.drink", opt("drink", DRINK_PRICE), 97, NpcDialogue::buyDrink));
            case IMPERIAL_SOLDIER, STORMCLOAK_SOLDIER -> out.add(new DialogueOption("society.war", opt("war"), 10,
                    (p, n) -> Dialogue.open(p, n, say("war." + role.id + "." + p.getRandom().nextInt(2)))));
            case JARL -> out.add(new DialogueOption("society.standing", opt("standing"), 10, NpcDialogue::standing));
            case STEWARD -> out.add(new DialogueOption("society.hold", opt("hold"), 10,
                    (p, n) -> Dialogue.open(p, n, say("hold." + p.getRandom().nextInt(3)))));
            case FARMER -> out.add(new DialogueOption("society.harvest", opt("harvest"), 10,
                    (p, n) -> Dialogue.open(p, n, say("harvest." + p.getRandom().nextInt(3)))));
            case BLACKSMITH -> out.add(new DialogueOption("society.smithing", opt("smithing"), 10,
                    (p, n) -> Dialogue.open(p, n, say("smithing." + p.getRandom().nextInt(3)))));
            case MERCHANT, APOTHECARY -> out.add(new DialogueOption("society.business", opt("business"), 10,
                    (p, n) -> Dialogue.open(p, n, say("business." + p.getRandom().nextInt(3)))));
            case CARAVAN_TRADER -> out.add(new DialogueOption("society.caravan", opt("caravan"), 10,
                    (p, n) -> Dialogue.open(p, n, say("caravan." + p.getRandom().nextInt(3)))));
            case FISHER -> out.add(new DialogueOption("society.fishing", opt("fishing"), 10,
                    (p, n) -> Dialogue.open(p, n, say("fishing." + p.getRandom().nextInt(2)))));
            case SCHOLAR -> out.add(new DialogueOption("society.studies", opt("studies"), 10,
                    (p, n) -> Dialogue.open(p, n, say("studies." + p.getRandom().nextInt(3)))));
            case CHILD -> out.add(new DialogueOption("society.play", opt("play"), 10,
                    (p, n) -> Dialogue.open(p, n, say("play." + p.getRandom().nextInt(4)))));
            case HUNTER -> out.add(new DialogueOption("society.game", opt("game"), 10,
                    (p, n) -> Dialogue.open(p, n, say("game." + p.getRandom().nextInt(2)))));
            default -> {
            }
        }
    }

    /** "What do you do?": the role's answer, with a word about their family name or their homeland now and then. */
    private static Component jobLine(LivingEntity n, NpcRole role) {
        if (n instanceof NpcEntity npc && role.townsfolk() && npc.getRandom().nextInt(3) == 0) {
            String race = npc.race().name().toLowerCase(java.util.Locale.ROOT);
            return Component.translatable("society.skycraft.say.origin." + race);
        }
        return say("job." + role.id);
    }

    /** "Tell me about this place.": the region's own line, else its province's (ids listed in society/region_voice.json). */
    private static void aboutRegion(ServerPlayer player, LivingEntity npc) {
        com.skycraft.tamriel.Region region = Cultures.regionAt(player.level(), npc.blockPosition());
        String key = RegionVoice.sayKey(region.shortId(), region.province());
        Dialogue.open(player, npc, say(key));
    }

    /** "Any news?": a rumour about a nearby named place, which goes on the player's map. */
    private static void news(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc)) return;
        long day = player.level().getDayTime() / 24000L;
        CompoundTag told = Npcs.sub(npc.social(), "news_day");
        if (told.getLong(player.getStringUUID()) == day + 1) {
            Dialogue.open(player, npc, say("news.none." + player.getRandom().nextInt(2)));
            return;
        }
        told.putLong(player.getStringUUID(), day + 1);
        com.skycraft.tamriel.Place place = Chatter.rumorPlace(npc.blockPosition(), player.getRandom());
        if (place != null) {
            Component revealed = com.skycraft.world.Discovery.revealNear(player, new net.minecraft.core.BlockPos(place.x(), npc.getBlockY(), place.z()),
                    Math.max(64, place.radius() + 32));
            Component name = revealed != null ? revealed : place.name();
            String dir = direction(npc.getX(), npc.getZ(), place.x(), place.z());
            // a place with written lore: the townsfolk's own rumour of it (lore module), else the generic line
            Component lore = com.skycraft.lore.place.PlaceLore.rumour(place.id());
            if (lore != null && (revealed == null || revealed.getString().equals(place.name().getString()))) {
                Dialogue.open(player, npc, Component.translatable("place_lore.skycraft.say.news", lore, name,
                        Component.translatable("society.skycraft.dir." + dir)));
                return;
            }
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.news.place." + player.getRandom().nextInt(4), name,
                    Component.translatable("society.skycraft.dir." + dir)));
            return;
        }
        Component loc = com.skycraft.world.Discovery.revealNear(player, player.blockPosition(), 1500);
        if (loc != null) Dialogue.open(player, npc, Component.translatable("society.skycraft.rumor.revealed", loc));
        else Dialogue.open(player, npc, say("news.none." + player.getRandom().nextInt(2)));
    }

    /** Compass direction from (x, z) to (tx, tz): north is -Z. */
    static String direction(double x, double z, double tx, double tz) {
        double angle = Math.toDegrees(Math.atan2(tx - x, -(tz - z)));
        String[] dirs = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        return dirs[Math.floorMod((int) Math.round(angle / 45.0), 8)];
    }

    private static void giveCoin(ServerPlayer player, LivingEntity npc) {
        if (!Currency.take(player, 1)) {
            Notifier.message(player, Component.translatable("message.skycraft.not_enough_gold"));
            return;
        }
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module(Reputation.MODULE);
        long day = player.level().getDayTime() / 24000L;
        if (mod.getLong("charity_day") != day) {
            mod.putLong("charity_day", day);
            mod.putInt("charity", 0);
        }
        int given = mod.getInt("charity");
        mod.putInt("charity", given + 1);
        data.markDirty();
        if (given < 3) Reputation.add(player, Reputation.TOWNSFOLK, 1);
        Buffs.apply(player, "gift_of_charity", 20 * 60 * 2);
        if (npc instanceof NpcEntity n) Barks.sayLineNow(n, "beggar_thanks");
        Notifier.message(player, Component.translatable("society.skycraft.charity"));
        Dialogue.open(player, npc, say("coin"));
    }

    private static void heal(ServerPlayer player, LivingEntity npc) {
        player.heal(player.getMaxHealth());
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
        if (npc instanceof NpcEntity n) Barks.sayLineNow(n, "priest_heal");
        Dialogue.open(player, npc, say("heal"));
    }

    private static void buyDrink(ServerPlayer player, LivingEntity npc) {
        if (!Currency.take(player, DRINK_PRICE)) {
            Notifier.message(player, Component.translatable("message.skycraft.not_enough_gold"));
            return;
        }
        ItemStack mead = new ItemStack(Items.HONEY_BOTTLE);
        mead.setHoverName(Component.translatable("society.skycraft.item.mead"));
        if (!ActionHandler.addToBags(player, mead)) player.drop(mead, false);
        Dialogue.open(player, npc, say("drink"));
    }

    private static void standing(ServerPlayer player, LivingEntity npc) {
        int rep = Reputation.get(player, Reputation.TOWNSFOLK);
        Reputation.Tier tier = Reputation.Tier.of(rep);
        Dialogue.open(player, npc, say("standing." + tier.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static void freePrisoner(ServerPlayer player, LivingEntity entity) {
        if (!(entity instanceof NpcEntity prisoner) || !prisoner.isPrisoner()) return;
        java.util.UUID leaderId = prisoner.getLeader();
        prisoner.setPrisoner(false);
        prisoner.setLeader(null);
        Barks.sayLineNow(prisoner, "prisoner_freed");
        Reputation.add(player, prisoner.role().faction == null || prisoner.role().civilian ? Reputation.TOWNSFOLK : prisoner.role().faction, 6);
        // the escort doesn't take kindly to this
        String captors = null;
        for (NpcEntity e : prisoner.level().getEntitiesOfClass(NpcEntity.class, prisoner.getBoundingBox().inflate(32),
                x -> x != prisoner && x.isAlive() && !x.isPrisoner())) {
            boolean escort = leaderId != null && (leaderId.equals(e.getUUID()) || leaderId.equals(e.getLeader()));
            if (!escort) continue;
            captors = e.role().faction;
            e.setTarget(player);
        }
        if (captors != null) Reputation.add(player, captors, -8);
        // the freed prisoner runs for it
        double dx = prisoner.getX() - player.getX();
        double dz = prisoner.getZ() - player.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        prisoner.setDestination(net.minecraft.core.BlockPos.containing(prisoner.getX() + dx / len * 60, prisoner.getY(), prisoner.getZ() + dz / len * 60));
        Dialogue.open(player, prisoner, say("freed"));
    }

    private static boolean isNearInn(LivingEntity npc) {
        return !npc.level().getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(32),
                e -> e != npc && com.skycraft.survival.inn.Innkeepers.isInnkeeper(e)).isEmpty();
    }
}

