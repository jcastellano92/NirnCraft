package com.nirncraft.quest;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.quest.story.Story;
import com.nirncraft.quest.story.StoryData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * "The Dragonborn", Skyrim's main quest. The quests themselves are data ({@code nirncraft/quests/main.json}, the
 * {@code main} questline run by {@link Story}): Helgen, Riverwood, Whiterun's Jarl, Bleak Falls Barrow, the first
 * dragon, the Greybeards, the Horn, the Blades, Sky Haven Temple, Paarthurnax, the Elder Scroll, Dragonrend,
 * Odahviing, Skuldafn and Alduin in Sovngarde (the End).
 *
 * <p>This class keeps the coarse progress number other modules read ({@code module("quest").main_stage}: 0 = not
 * started, 1 Helgen and Whiterun, 2 Bleak Falls Barrow, 3 the first dragon, 4 the Way of the Voice to the
 * Fallen, 5 Skuldafn and Sovngarde, 6 = finished), migrates saves from the old five-stage main quest, and names
 * the End's dragon Alduin.</p>
 */
public final class MainQuest {
    public static final String STAGE = "main_stage";
    public static final int FINAL_STAGE = 5;

    private MainQuest() {}

    public static int stage(PlayerData data) {
        return data.module("quest").getIntOr(STAGE, 0);
    }

    /** Called every few seconds per player: migrates old saves, renames Alduin. Story quests start in {@link Story#tickPlayer}. */
    public static void tick(ServerPlayer player) {
        if (!QuestConfig.MAIN_QUEST.get()) return;
        PlayerData data = SkyData.get(player);
        if (data.getRace() == null) return;
        migrateLegacy(player, data);
        if (player.level().dimension() == Level.END && player.level() instanceof ServerLevel end) {
            for (EnderDragon dragon : end.getDragons()) {
                if (!dragon.hasCustomName()) dragon.setCustomName(Component.translatable("entity.nirncraft.alduin"));
            }
        }
    }

    /** Quests of the old five-stage main quest become progress in the story questline. */
    private static void migrateLegacy(ServerPlayer player, PlayerData data) {
        List<Quest> mine = QuestStore.get(player.level().getServer()).quests(Quests.personalKey(player.getUUID()));
        boolean legacy = false;
        for (Quest q : mine) if (q.isMain() && q.kind.startsWith("main_") && !q.extra.contains(Story.DEF)) legacy = true;
        if (!legacy) return;
        int old = stage(data);
        mine.removeIf(q -> q.isMain() && q.kind.startsWith("main_") && !q.extra.contains(Story.DEF));
        Story.setFlag(data, "helgen_arrived", true); // an old character isn't carted off to Helgen
        markBefore(player, old >= FINAL_STAGE + 1 ? Integer.MAX_VALUE : old);
        Quests.changed(player.level().getServer(), Quests.personalKey(player.getUUID()));
    }

    /** Marks every main story quest of a stage lower than {@code stage} as done (migration, admin jumps). */
    private static void markBefore(ServerPlayer player, int stage) {
        for (StoryData.QuestDef def : mainLine()) {
            if (def.stage > 0 && def.stage < stage && !Story.completed(SkyData.get(player), def.id)) {
                Quest q = Story.build(player, def);
                if (q != null) Story.grantStoryRewardsSilently(player, q);
            }
        }
    }

    private static List<StoryData.QuestDef> mainLine() {
        return new ArrayList<>(StoryData.line("main"));
    }

    /** Admin: restarts the main quest at the first quest of a coarse stage (1..5). */
    public static void startStage(ServerPlayer player, int stage) {
        markBefore(player, stage);
        for (StoryData.QuestDef def : mainLine()) {
            if (def.stage == stage) {
                Story.jumpTo(player, def.id, 0);
                return;
            }
        }
    }

    /** Old-style main quests (none are created any more) just advance the stage number. */
    public static void onStageCompleted(MinecraftServer server, String key, Quest q) {
    }

    /** Alduin is dead: the title, the fanfare and word to the whole server. */
    public static void victory(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        data.module("quest").putInt(STAGE, FINAL_STAGE + 1);
        data.module("quest").putString("title", "dragonborn");
        data.markDirty();
        Notifier.title(player, Component.translatable("quest.nirncraft.main.victory_title"), Component.translatable("quest.nirncraft.main.victory_sub"));
        Component victoryMsg = Component.translatable("quest.nirncraft.main.victory_broadcast", player.getDisplayName());
        for (ServerPlayer sp : player.level().getServer().getPlayerList().getPlayers()) {
            if (sp != player) Notifier.message(sp, victoryMsg);
        }
    }
}
