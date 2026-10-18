package com.skycraft.fire.aftermath;

import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.quest.QuestConfig;
import com.skycraft.quest.story.QuestNpcs;
import com.skycraft.quest.story.StartCottage;
import com.skycraft.society.NpcRole;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * How the people of the ruined town talk. A survivor, a wounded townsperson, a healer, a mourner, a soldier of the
 * burnt town has no topics to open, nothing to sell and no work to give: right-clicking them shows ONE short line
 * (chosen by what they are doing, the hour and whether the player has a weapon out) with only "Continue" (another line)
 * and "Goodbye". The exceptions are the few who carry the story forward (Hadvar and the other named quest people: only
 * their story choices are offered) and the salvage cart's merchant (only "Barter").
 */
public final class HelgenFolk {
    /** Persistent-data flag: this person only speaks one-liners (set on every tableau actor). */
    public static final String BARKS = "skycraft_barks";
    /** Persistent-data flag: the salvage cart's merchant (barter only). */
    public static final String CART = "skycraft_helgen_cart";

    private HelgenFolk() {}

    /** True for somebody within the ruined town (its walls and a little way round). */
    public static boolean inHelgen(LivingEntity e) {
        return QuestConfig.RUINED_HELGEN.get() && e.level().dimension() == Level.OVERWORLD
                && StartCottage.nearHelgen(e.getBlockX(), e.getBlockZ(), 8);
    }

    /** True if this Helgen NPC is a shopkeeper (either the salvage cart or an actual merchant in Helgen). */
    public static boolean isHelgenMerchant(LivingEntity npc) {
        var pd = npc.getPersistentData();
        if (pd.getBooleanOr(CART, false)) return true;
        if (!inHelgen(npc) || pd.getBooleanOr(BARKS, false) || pd.contains(Tableau.KEY)) return false;
        if (npc instanceof NpcEntity n && (n.role() == NpcRole.CITIZEN || n.role() == NpcRole.BEGGAR)) return false;
        return com.skycraft.economy.Merchants.isMerchant(npc);
    }

    /** True if this NPC in Helgen should speak aftermath/fear dialogue (everyone except named quest NPCs and actively arresting guards). */
    public static boolean isHelgenEventNpc(@org.jetbrains.annotations.Nullable ServerPlayer player, LivingEntity npc) {
        if (!QuestNpcs.idOf(npc).isEmpty() || hasActiveArrest(player, npc)) return false;
        var pd = npc.getPersistentData();
        return pd.getBooleanOr(BARKS, false) || pd.contains(Tableau.KEY) || pd.getBooleanOr(CART, false) || inHelgen(npc);
    }

    /** True if this guard in Helgen needs to confront/arrest the player for an active bounty. */
    public static boolean hasActiveArrest(@org.jetbrains.annotations.Nullable ServerPlayer player, LivingEntity npc) {
        if (player == null || !com.skycraft.crime.Crimes.isGuard(npc)) return false;
        String hold = com.skycraft.core.Holds.holdAt(player.level(), player.blockPosition());
        return com.skycraft.crime.Bounty.get(player, hold) > 0;
    }

    /** One-liners only: no menu of topics, trades or work. */
    public static boolean barkOnly(LivingEntity npc) {
        return barkOnly(null, npc);
    }

    /** One-liners only: no menu of topics, trades or work (unless arresting a wanted player or running a shop). */
    public static boolean barkOnly(@org.jetbrains.annotations.Nullable ServerPlayer player, LivingEntity npc) {
        var pd = npc.getPersistentData();
        if (isHelgenMerchant(npc) || !QuestNpcs.idOf(npc).isEmpty() || hasActiveArrest(player, npc)) return false;
        if (pd.getBooleanOr(BARKS, false) || pd.contains(Tableau.KEY)) return true;
        return inHelgen(npc) && (npc instanceof NpcEntity || npc instanceof GuardEntity
                || npc instanceof net.minecraft.world.entity.npc.villager.AbstractVillager);
    }

    /** Whether the menu of this person is cut down to a few options (the cart merchant, the named story people here, or an arresting guard). */
    public static boolean limited(LivingEntity npc) {
        return limited(null, npc);
    }

    public static boolean limited(@org.jetbrains.annotations.Nullable ServerPlayer player, LivingEntity npc) {
        return isHelgenMerchant(npc)
                || (!QuestNpcs.idOf(npc).isEmpty() && inHelgen(npc))
                || (inHelgen(npc) && hasActiveArrest(player, npc));
    }

    /** What a limited person still offers: the merchant barters, the guard arrests, the story people carry the story. */
    public static boolean allowed(LivingEntity npc, String optionId) {
        if (optionId.equals("core.goodbye")) return true;
        if (com.skycraft.crime.Crimes.isGuard(npc) && optionId.startsWith("crime.")) return true;
        if (isHelgenMerchant(npc)) return optionId.equals("economy.barter") || optionId.equals("inn.rent");
        return optionId.startsWith("story.") || optionId.startsWith("quest.");
    }

    /** The "Continue" option of a bark-only person: another line. */
    public static DialogueOption continueOption() {
        return new DialogueOption("helgen.continue", Component.translatable("dialogue.skycraft.continue"), 10,
                (p, n) -> Dialogue.open(p, n, line(p, n)));
    }

    /** A short line for this person now: by what they do (pose or role), with night and a drawn weapon as context. */
    public static Component line(ServerPlayer player, LivingEntity npc) {
        var rnd = npc.getRandom();
        Tableau.Pose pose = Tableau.poseOf(npc);
        String group = pose != null && !pose.lines.isEmpty() ? pose.lines : byRole(npc);
        long time = npc.level().getDefaultClockTime() % 24000L;
        boolean night = time >= 13000L && time < 23000L;
        boolean armed = !com.skycraft.combat.Sheathe.isSheathed(player) && !player.getMainHandItem().isEmpty();
        if (armed && rnd.nextInt(3) == 0) group = "wary";
        else if (night && rnd.nextInt(3) == 0) group = "night";
        int n = Tableau.lineCount(group);
        if (n <= 0) {
            group = "survivor";
            n = Tableau.lineCount(group);
        }
        return Component.translatable("fire.skycraft.line." + group + "." + (1 + rnd.nextInt(n)));
    }

    private static String byRole(LivingEntity npc) {
        if (npc instanceof GuardEntity) return "guard";
        if (npc instanceof NpcEntity n) {
            NpcRole r = n.role();
            return switch (r) {
                case IMPERIAL_SOLDIER, STORMCLOAK_SOLDIER, HOUSECARL -> "guard";
                case PRIEST, APOTHECARY -> "healer";
                case CHILD -> "child";
                case BEGGAR -> "patient";
                default -> "survivor";
            };
        }
        return "survivor";
    }
}
