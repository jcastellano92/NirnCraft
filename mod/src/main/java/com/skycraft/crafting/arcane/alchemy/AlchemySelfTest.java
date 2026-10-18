package com.skycraft.crafting.arcane.alchemy;

import com.mojang.authlib.GameProfile;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.arcane.ArcaneModule;
import com.skycraft.inventory.IngredientUseSelfTest;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Self-test of eating ingredients (run from {@code /skycraft magic selftest}, which the QA script runs):
 * <ul>
 *   <li>the bite decision ({@link Alchemy#chooseTaste}): only discovered effects are applied unless the bite is
 *       itself the discovery, a discovery learns exactly the applied effect (plus Experimenter extras), a fully known
 *       ingredient never discovers;</li>
 *   <li>the four-line effect text ({@link Alchemy#effectLines}) always has four lines, "?" for unknown ones;</li>
 *   <li>the real paths with a player: the lab/inventory bite ({@link Alchemy#discoverByTasting}), the Use action of
 *       the inventory screen ({@link IngredientUseSelfTest}), right-clicking a non-food ingredient and finishing
 *       eating a food ingredient (the event handlers of {@link com.skycraft.crafting.arcane.ArcaneEvents}). Each
 *       learns exactly one effect with exactly one message and applies an effect.</li>
 * </ul>
 */
public final class AlchemySelfTest {
    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("skycraft-alchemy-selftest".getBytes()), "[AlchemyTest]");

    private AlchemySelfTest() {}

    /** Runs every check; returns the problems found (empty when all pass). */
    public static List<String> run() {
        List<String> problems = new ArrayList<>();
        try {
            pureChecks(problems);
        } catch (RuntimeException e) {
            problems.add("alchemy pure checks threw " + e);
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            problems.add("alchemy: no server for the player checks");
            return problems;
        }
        FakePlayer player = FakePlayerFactory.get(server.overworld(), PROFILE);
        // a fake player has no connection: give it one that queues packets, so notifications may be sent to it
        if (player.connection == null) new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player, net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false));
        try {
            playerChecks(player, problems);
        } catch (RuntimeException e) {
            problems.add("alchemy player checks threw " + e);
        } finally {
            reset(player);
        }
        return problems;
    }

    private static void reset(ServerPlayer player) {
        player.removeAllEffects();
        player.getInventory().clearContent();
        SkyData.get(player).module(ArcaneModule.DATA).remove(Alchemy.KNOWN);
    }

    // ------------------------------------------------------------------ pure

    private static void pureChecks(List<String> problems) {
        RandomSource rng = RandomSource.create(20260713L);
        for (int known = 0; known < 16; known++) {
            for (int rank = 0; rank <= 2; rank++) {
                int unknown = ~known & 0xF;
                Set<Integer> applied = new HashSet<>();
                int discoveries = 0;
                for (int n = 0; n < 400; n++) {
                    Alchemy.Taste t = Alchemy.chooseTaste(known, rank, rng);
                    String where = "known=" + known + " rank=" + rank + " -> " + t;
                    if (t.index() < 0 || t.index() > 3) problems.add("alchemy taste: index out of range, " + where);
                    boolean indexKnown = (known & (1 << t.index())) != 0;
                    if ((t.learned() & ~unknown) != 0) problems.add("alchemy taste: learned an already known effect, " + where);
                    if (!indexKnown && (t.learned() & (1 << t.index())) == 0) problems.add("alchemy taste: applied an effect it did not learn, " + where);
                    if (indexKnown && t.learned() != 0) problems.add("alchemy taste: learned while applying a known effect, " + where);
                    if (t.learned() != 0) {
                        discoveries++;
                        if (Integer.bitCount(t.learned()) > 1 + rank) problems.add("alchemy taste: too many learned, " + where);
                        if (Integer.bitCount(t.learned()) != Math.min(1 + rank, Integer.bitCount(unknown))) {
                            problems.add("alchemy taste: Experimenter extras missing, " + where);
                        }
                    }
                    applied.add(t.index());
                }
                if (unknown == 0 && discoveries != 0) problems.add("alchemy taste: discovered with everything known, known=" + known);
                if (known == 0 && discoveries != 400) problems.add("alchemy taste: bite with nothing known did not always discover");
                if (known != 0 && unknown != 0 && discoveries == 0) problems.add("alchemy taste: never discovers, known=" + known);
                if (known != 0) {
                    for (int i = 0; i < 4; i++) {
                        if ((known & (1 << i)) != 0 && !applied.contains(i)) problems.add("alchemy taste: known effect " + i + " never applied, known=" + known);
                    }
                }
            }
        }
        // the effect text: four lines for every ingredient and every knowledge state, "?" exactly for the unknown ones
        int ingredients = 0;
        for (Ingredients.Ingredient ing : Ingredients.all()) {
            ingredients++;
            if (ing.effects().length != 4) problems.add("alchemy: " + ing.id() + " has " + ing.effects().length + " effects");
        }
        if (ingredients == 0) problems.add("alchemy: no ingredients registered");
    }

    // ------------------------------------------------------------------ with a player

    private static void playerChecks(FakePlayer player, List<String> problems) {
        Ingredients.Ingredient flower = Ingredients.get("minecraft:torchflower");  // not food; all four effects last
        Ingredients.Ingredient carrot = Ingredients.get("minecraft:carrot");       // food
        if (flower == null || flower.item() == null || carrot == null || carrot.item() == null) {
            problems.add("alchemy: torchflower/carrot are not ingredients");
            return;
        }
        reset(player);

        // ---- four lines at every knowledge level
        for (int mask : new int[]{0, 0b0101, 0xF}) {
            SkyData.get(player).module(ArcaneModule.DATA).remove(Alchemy.KNOWN);
            if (mask != 0) Alchemy.learn(player, flower, mask);
            List<Component> lines = Alchemy.effectLines(player, flower);
            int unknownLines = 0;
            for (Component c : lines) if (c.getString().equals("?")) unknownLines++;
            if (lines.size() != 4) problems.add("alchemy lines: " + lines.size() + " lines at mask " + mask);
            if (unknownLines != 4 - Integer.bitCount(mask)) problems.add("alchemy lines: " + unknownLines + " '?' lines at mask " + mask);
            for (int i = 0; i < 4 && i < lines.size(); i++) {
                boolean known = (mask & (1 << i)) != 0;
                String text = lines.get(i).getString();
                if (known == text.equals("?")) problems.add("alchemy lines: slot " + i + " (known=" + known + ") shows '" + text + "'");
            }
        }
        reset(player);

        // ---- the first bite of an unknown ingredient: one effect learned, one message, the effect is active
        int before = Alchemy.announcedTotal();
        Alchemy.Tasted first = Alchemy.discoverByTasting(player, flower);
        int mask = Alchemy.knownMask(player, flower);
        if (Integer.bitCount(mask) != 1) problems.add("alchemy bite: learned mask " + mask + " after one bite");
        if (Alchemy.announcedTotal() - before != 1 || first.messages() != 1) problems.add("alchemy bite: expected exactly one 'You learned' message");
        if ((mask & (1 << first.taste().index())) == 0) problems.add("alchemy bite: the applied effect was not the learned one");
        if (first.applied() == null || !player.hasEffect(com.skycraft.platform.Effects.holder(first.applied().getEffect()))) problems.add("alchemy bite: the effect was not applied");

        // ---- everything known: bites apply a random one of the four and never announce again
        Alchemy.learn(player, flower, 0xF);
        Set<Integer> seen = new HashSet<>();
        before = Alchemy.announcedTotal();
        for (int i = 0; i < 40; i++) {
            player.removeAllEffects();
            Alchemy.Tasted t = Alchemy.discoverByTasting(player, flower);
            seen.add(t.taste().index());
            if (t.taste().learned() != 0) problems.add("alchemy bite: learned something when all four were known");
            if (t.applied() == null || !player.hasEffect(com.skycraft.platform.Effects.holder(t.applied().getEffect()))) problems.add("alchemy bite: known effect " + t.taste().index() + " not applied");
        }
        if (Alchemy.announcedTotal() != before) problems.add("alchemy bite: messages sent for known effects");
        if (seen.size() < 2) problems.add("alchemy bite: 40 bites always picked effect " + seen);

        // ---- the inventory Use action (real InventoryActions entry point)
        reset(player);
        problems.addAll(IngredientUseSelfTest.run(player));

        // ---- right-click on a non-food ingredient (the handler of ArcaneEvents)
        reset(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(flower.item(), 3));
        before = Alchemy.announcedTotal();
        PlayerInteractEvent.RightClickItem click = new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(click);
        if (!click.isCanceled()) problems.add("alchemy right-click: the click was not consumed");
        if (player.getMainHandItem().getCount() != 2) problems.add("alchemy right-click: " + player.getMainHandItem().getCount() + " flowers left of 3");
        if (Integer.bitCount(Alchemy.knownMask(player, flower)) != 1) problems.add("alchemy right-click: mask " + Alchemy.knownMask(player, flower));
        if (Alchemy.announcedTotal() - before != 1) problems.add("alchemy right-click: expected exactly one message");
        boolean active = false;
        for (AlchemyEffect e : flower.effects()) if (player.hasEffect(com.skycraft.platform.Effects.holder(e.mobEffect()))) active = true;
        if (!active) problems.add("alchemy right-click: no effect applied");

        // ---- finishing a food ingredient (the handler of ArcaneEvents)
        reset(player);
        ItemStack food = new ItemStack(carrot.item());
        before = Alchemy.announcedTotal();
        NeoForge.EVENT_BUS.post(new LivingEntityUseItemEvent.Finish(player, food, 0, food));
        if (Integer.bitCount(Alchemy.knownMask(player, carrot)) != 1) problems.add("alchemy eat: mask " + Alchemy.knownMask(player, carrot));
        if (Alchemy.announcedTotal() - before != 1) problems.add("alchemy eat: expected exactly one message");
    }
}
