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
    private static boolean inHelgen(LivingEntity e) {
        return QuestConfig.RUINED_HELGEN.get() && e.level().dimension() == Level.OVERWORLD
                && StartCottage.nearHelgen(e.getBlockX(), e.getBlockZ(), 8);
    }

    /** One-liners only: no menu of topics, trades or work. */
    public static boolean barkOnly(LivingEntity npc) {
        var pd = npc.getPersistentData();
        if (pd.getBoolean(CART) || !QuestNpcs.idOf(npc).isEmpty()) return false;
        if (pd.getBoolean(BARKS) || pd.contains(Tableau.KEY)) return true;
        return inHelgen(npc) && (npc instanceof NpcEntity || npc instanceof GuardEntity
                || npc instanceof net.minecraft.world.entity.npc.AbstractVillager);
    }

    /** Whether the menu of this person is cut down to a few options (the cart merchant, the named story people here). */
    public static boolean limited(LivingEntity npc) {
        var pd = npc.getPersistentData();
        return pd.getBoolean(CART) || (!QuestNpcs.idOf(npc).isEmpty() && inHelgen(npc));
    }

    /** What a limited person still offers: the merchant barters, the story people carry the story. */
    public static boolean allowed(LivingEntity npc, String optionId) {
        if (npc.getPersistentData().getBoolean(CART)) return optionId.equals("economy.barter") || optionId.equals("core.goodbye");
        return optionId.startsWith("story.") || optionId.startsWith("quest.") || optionId.equals("core.goodbye");
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
        long time = npc.level().getDayTime() % 24000L;
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
