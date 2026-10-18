package com.skycraft.quest.client;

import com.mojang.authlib.GameProfile;
import com.skycraft.client.SkyKeys;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.QuestPackets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Party management (U): members with health, kick / make leader, leave or create a party, pending invitations and
 * a list of online players (nearby first) to invite.
 */
public class PartyScreen extends Screen {
    private static final int ROW = 16;
    private static final int PAGE_SIZE = 6;

    private int x0, y0, x1, y1;
    private int page;
    private String signature = "";
    private int membersY, invitesY, playersY;
    private final List<ClientQuestData.Member> members = new ArrayList<>();
    private final List<ClientQuestData.Invite> invites = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();

    private record Candidate(UUID id, String name, double distance) {
    }

    public PartyScreen() {
        super(Component.translatable("screen.skycraft.party"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private UUID self() {
        return minecraft.player.getUUID();
    }

    private boolean isLeader() {
        return self().equals(ClientQuestData.leader());
    }

    private static void send(int action, @Nullable UUID target) {
        SkyNetwork.sendToServer(new QuestPackets.PartyAction(action, target == null ? QuestPackets.NONE : target));
    }

    private List<Candidate> findCandidates() {
        List<Candidate> out = new ArrayList<>();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) return out;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            UUID id = profile.id();
            if (id == null || id.equals(self()) || ClientQuestData.isMember(id)) continue;
            Player p = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(id);
            double dist = p == null ? Double.MAX_VALUE : p.distanceTo(minecraft.player);
            out.add(new Candidate(id, profile.name(), dist));
        }
        out.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(Candidate::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private String currentSignature() {
        StringBuilder sb = new StringBuilder(ClientQuestData.partySignature()).append('|').append(page);
        for (Candidate c : findCandidates()) sb.append(c.id());
        return sb.toString();
    }

    @Override
    protected void init() {
        int w = Math.min(width - 24, 340);
        int h = Math.min(height - 24, 280);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        members.clear();
        members.addAll(ClientQuestData.members());
        invites.clear();
        invites.addAll(ClientQuestData.invites());
        candidates.clear();
        candidates.addAll(findCandidates());
        int pages = Math.max(1, (candidates.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        signature = currentSignature();

        boolean inParty = ClientQuestData.inParty();
        boolean leader = isLeader();
        int y = y0 + 30;
        membersY = y;
        if (inParty) {
            for (ClientQuestData.Member m : members) {
                if (m.online() && !m.id().equals(self())) {
                    UUID id = m.id();
                    String name = m.name();
                    boolean isLead = id.equals(ClientQuestData.leader());
                    addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.travel"),
                                    b -> minecraft.gui.setScreen(new PartyTravelScreen(id, name, isLead, this)))
                            .bounds(x1 - 148, y - 3, 44, 14).build());
                }
                if (leader && !m.id().equals(self())) {
                    UUID id = m.id();
                    addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.kick"), b -> send(QuestPackets.PartyAction.KICK, id))
                            .bounds(x1 - 52, y - 3, 44, 14).build());
                    addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.lead"), b -> send(QuestPackets.PartyAction.PROMOTE, id))
                            .bounds(x1 - 100, y - 3, 44, 14).build());
                }
                y += ROW;
            }
            addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.leave"), b -> send(QuestPackets.PartyAction.LEAVE, null))
                    .bounds(x0 + 12, y, 90, 16).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.create"), b -> send(QuestPackets.PartyAction.CREATE, null))
                    .bounds(x0 + 12, y, 90, 16).build());
        }
        y += 26;

        invitesY = y;
        if (!invites.isEmpty()) {
            y += 12;
            for (ClientQuestData.Invite i : invites) {
                UUID party = i.party();
                addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.accept"), b -> send(QuestPackets.PartyAction.ACCEPT, party))
                        .bounds(x1 - 104, y - 3, 48, 14).build());
                addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.decline"), b -> send(QuestPackets.PartyAction.DECLINE, party))
                        .bounds(x1 - 54, y - 3, 48, 14).build());
                y += ROW;
            }
            y += 6;
        }

        playersY = y;
        boolean canInvite = !inParty || leader;
        y += 12;
        int from = page * PAGE_SIZE;
        for (int i = from; i < Math.min(candidates.size(), from + PAGE_SIZE); i++) {
            Candidate c = candidates.get(i);
            if (canInvite) {
                addRenderableWidget(Button.builder(Component.translatable("party.skycraft.button.invite"), b -> send(QuestPackets.PartyAction.INVITE, c.id()))
                        .bounds(x1 - 60, y - 3, 52, 14).build());
            }
            y += ROW;
        }
        if (pages > 1) {
            Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = Math.max(0, page - 1);
                rebuild();
            }).bounds(x1 - 60, y1 - 22, 24, 14).build());
            Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = page + 1;
                rebuild();
            }).bounds(x1 - 32, y1 - 22, 24, 14).build());
            prev.active = page > 0;
            next.active = page < pages - 1;
        }
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public void tick() {
        if (!signature.equals(currentSignature())) rebuild();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (SkyKeys.PARTY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        Parchment.page(g, x0, y0, x1, y1);
        boolean inParty = ClientQuestData.inParty();
        Component title = inParty
                ? Component.translatable("party.skycraft.screen.title_size", members.size(), ClientQuestData.maxSize())
                : Component.translatable("party.skycraft.screen.title");
        g.pose().pushMatrix();
        g.pose().translate((x0 + x1) / 2f, y0 + 9);
        g.pose().scale(1.3f, 1.3f);
        g.centeredText(font, title, 0, 0, 0xFF3A2814);
        g.pose().popMatrix();
        Parchment.rule(g, x0 + 10, x1 - 10, y0 + 23);

        int x = x0 + 14;
        if (inParty) {
            UUID leader = ClientQuestData.leader();
            List<ClientQuestData.Member> live = ClientQuestData.members();
            int y = membersY;
            for (ClientQuestData.Member m : members) {
                ClientQuestData.Member now = m;
                for (ClientQuestData.Member l : live) if (l.id().equals(m.id())) now = l;
                String name = (m.id().equals(leader) ? "★ " : "") + m.name();
                g.text(font, font.plainSubstrByWidth(name, 96), x, y, now.online() ? Parchment.INK : Parchment.INK_FADED, false);
                if (now.online()) {
                    float fill = now.maxHealth() > 0 ? now.health() / now.maxHealth() : 0;
                    Parchment.bar(g, x + 100, y + 2, 70, fill, 0xFFB0262A);
                } else {
                    g.text(font, Component.translatable("party.skycraft.offline"), x + 100, y, Parchment.INK_FADED, false);
                }
                y += ROW;
            }
        } else {
            g.text(font, Component.translatable("party.skycraft.screen.no_party"), x + 100, membersY + 4, Parchment.INK_LIGHT, false);
        }

        if (!invites.isEmpty()) {
            g.text(font, Component.translatable("party.skycraft.screen.invites"), x, invitesY, Parchment.RED, false);
            int y = invitesY + 12;
            for (ClientQuestData.Invite i : invites) {
                g.text(font, Component.translatable("party.skycraft.screen.invite_from", i.from()), x, y, Parchment.INK, false);
                y += ROW;
            }
        }

        g.text(font, Component.translatable("party.skycraft.screen.players"), x, playersY, Parchment.RED, false);
        int y = playersY + 12;
        if (candidates.isEmpty()) {
            g.text(font, Component.translatable("party.skycraft.screen.nobody"), x, y, Parchment.INK_FADED, false);
        }
        int from = page * PAGE_SIZE;
        for (int i = from; i < Math.min(candidates.size(), from + PAGE_SIZE); i++) {
            Candidate c = candidates.get(i);
            g.text(font, font.plainSubstrByWidth(c.name(), 120), x, y, Parchment.INK, false);
            Component where = c.distance() < 1e6 ? Component.translatable("party.skycraft.screen.nearby", (int) c.distance())
                    : Component.translatable("party.skycraft.screen.online");
            g.text(font, where, x + 126, y, Parchment.INK_LIGHT, false);
            y += ROW;
        }
        if (inParty && !isLeader()) {
            g.text(font, Component.translatable("party.skycraft.screen.leader_invites"), x, y1 - 20, Parchment.INK_FADED, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
