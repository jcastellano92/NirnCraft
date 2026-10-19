package com.nirncraft.quest.client;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.quest.QuestPackets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * "Fast travel to &lt;name&gt; (party leader)?" with [Travel] / [Stay here]. The server validates the travel
 * (combat, jail, target online) and moves the player next to the target, across dimensions if needed.
 */
public class PartyTravelScreen extends Screen {
    private final UUID target;
    private final String name;
    private final boolean leader;
    @Nullable
    private final Screen parent;

    public PartyTravelScreen(UUID target, String name, boolean leader, @Nullable Screen parent) {
        super(Component.translatable("party.nirncraft.travel.title"));
        this.target = target;
        this.name = name;
        this.leader = leader;
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;
        addRenderableWidget(Button.builder(Component.translatable(leader ? "party.nirncraft.travel.go_leader" : "party.nirncraft.travel.go"), b -> {
            SkyNetwork.sendToServer(new QuestPackets.PartyTravel(target));
            minecraft.gui.setScreen(null);
        }).bounds(cx - 104, cy + 14, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("party.nirncraft.travel.stay"), b -> onClose())
                .bounds(cx + 4, cy + 14, 100, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        int cx = width / 2;
        int cy = height / 2;
        Parchment.page(g, cx - 130, cy - 46, cx + 130, cy + 44);
        Component q = Component.translatable(leader ? "party.nirncraft.travel.ask_leader" : "party.nirncraft.travel.ask", name);
        int y = cy - 34;
        for (FormattedCharSequence line : font.split(q, 240)) {
            g.text(font, line, cx - font.width(line) / 2, y, Parchment.INK, false);
            y += 11;
        }
        ClientQuestData.Member m = ClientQuestData.member(target);
        if (m != null && minecraft.player != null) {
            Component where;
            if (minecraft.level != null && m.dim().equals(minecraft.level.dimension().identifier().toString())) {
                Vec3 p = m.position();
                where = Component.translatable("party.nirncraft.travel.distance", (int) Math.sqrt(minecraft.player.distanceToSqr(p)));
            } else {
                where = Component.translatable("party.nirncraft.travel.other_realm");
            }
            g.text(font, where, cx - font.width(where) / 2, y + 2, Parchment.INK_LIGHT, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
