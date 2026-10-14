package com.skycraft.quest.client;

import com.skycraft.client.hud.CompassMarkers;
import com.skycraft.core.SkyData;
import com.skycraft.quest.Objective;
import com.skycraft.quest.Quest;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Client-side copy of the player's quests and party state (filled by quest packets). */
public final class ClientQuestData {
    public static final int TRACKED_COLOR = 0xE8C060;
    public static final int OTHER_COLOR = 0x9A8E72;

    private static List<Quest> quests = List.of();
    private static CompoundTag party = new CompoundTag();
    private static PartyTravelScreen pendingPrompt;

    private ClientQuestData() {}

    public static final int PARTY_COLOR = 0x5FD08A;

    public record Member(UUID id, String name, boolean online, float health, float maxHealth, String dim, double x, double y, double z) {
        /** Best known position: the live entity when it's loaded on this client, else the last synced position. */
        public Vec3 position() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.dimension().location().toString().equals(dim)) {
                Player p = mc.level.getPlayerByUUID(id);
                if (p != null) return p.position();
            }
            return new Vec3(x, y, z);
        }
    }

    public record Invite(UUID party, String from) {
    }

    // ------------------------------------------------------------------ packets

    public static void onQuests(CompoundTag tag) {
        List<Quest> list = new ArrayList<>();
        for (Tag t : tag.getList("quests", Tag.TAG_COMPOUND)) list.add(Quest.load((CompoundTag) t));
        quests = list;
        refreshCompass();
    }

    public static void onParty(CompoundTag tag) {
        party = tag;
        refreshPartyCompass();
    }

    /** The server asks whether to fast travel to the party (leader); shown as soon as no other screen is open. */
    public static void onTravelPrompt(UUID target, String name, boolean leader) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || target.equals(mc.player.getUUID())) return;
        PartyTravelScreen screen = new PartyTravelScreen(target, name, leader, null);
        if (mc.screen == null || mc.screen instanceof PartyScreen) mc.setScreen(screen);
        else pendingPrompt = screen;
    }

    /** Opens a prompt that arrived while another screen was open. Called every client tick. */
    static void showPendingPrompt() {
        Minecraft mc = Minecraft.getInstance();
        if (pendingPrompt != null && mc.player != null && mc.screen == null && mc.getOverlay() == null) {
            mc.setScreen(pendingPrompt);
            pendingPrompt = null;
        }
    }

    /** Other online party members in this dimension, always shown on the compass. */
    public static void refreshPartyCompass() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !inParty()) {
            CompassMarkers.clear("party");
            return;
        }
        String dim = mc.level.dimension().location().toString();
        List<CompassMarkers.Marker> out = new ArrayList<>();
        for (Member m : members()) {
            if (!m.online() || m.id().equals(mc.player.getUUID()) || !m.dim().equals(dim)) continue;
            out.add(new CompassMarkers.Marker(m.position(), CompassMarkers.Shape.PLAYER, PARTY_COLOR, m.name(), 0));
        }
        CompassMarkers.set("party", out);
    }

    public static void clear() {
        quests = List.of();
        party = new CompoundTag();
        pendingPrompt = null;
        CompassMarkers.clear("quest");
        CompassMarkers.clear("party");
    }

    // ------------------------------------------------------------------ quests

    public static List<Quest> quests() {
        return quests;
    }

    public static String tracked() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "" : SkyData.get(mc.player).module("quest").getString("tracked");
    }

    /** The tracked quest, or the main quest if nothing is tracked. */
    public static boolean isTracked(Quest q) {
        String t = tracked();
        // a tracked id that is finished, abandoned or unknown here no longer counts: fall back to the main quest
        if (!t.isEmpty() && isActiveQuest(t)) return q.id.equals(t);
        return q.isMain();
    }

    private static boolean isActiveQuest(String id) {
        for (Quest q : quests) if (q.isActive() && q.id.equals(id)) return true;
        return false;
    }

    /** Current-objective markers of every active quest in this dimension; the tracked one highlighted. */
    public static void refreshCompass() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        String dim = mc.level.dimension().location().toString();
        List<CompassMarkers.Marker> others = new ArrayList<>();
        List<CompassMarkers.Marker> tracked = new ArrayList<>();
        for (Quest q : quests) {
            if (!q.isActive()) continue;
            Objective o = q.current();
            if (o == null || !o.hasPos || !o.dim.equals(dim)) continue;
            boolean t = isTracked(q);
            // the tracked quest outranks every other marker near the same spot, so it is never merged away
            CompassMarkers.Marker m = new CompassMarkers.Marker(Vec3.atBottomCenterOf(o.pos), CompassMarkers.Shape.QUEST,
                    t ? TRACKED_COLOR : OTHER_COLOR, q.title.getString(), 0, -1, t ? CompassMarkers.TRACKED_PRIORITY : 0);
            (t ? tracked : others).add(m);
        }
        others.addAll(tracked); // tracked last so it's drawn on top
        CompassMarkers.set("quest", others);
    }

    // ------------------------------------------------------------------ party

    public static boolean inParty() {
        return party.getBoolean("inParty");
    }

    public static int maxSize() {
        return Math.max(2, party.getInt("max"));
    }

    public static UUID leader() {
        return party.hasUUID("leader") ? party.getUUID("leader") : null;
    }

    public static List<Member> members() {
        List<Member> out = new ArrayList<>();
        for (Tag t : party.getList("members", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) t;
            out.add(new Member(m.getUUID("uuid"), m.getString("name"), m.getBoolean("online"),
                    m.getFloat("health"), m.getFloat("max"), m.getString("dim"), m.getDouble("x"), m.getDouble("y"), m.getDouble("z")));
        }
        return out;
    }

    @org.jetbrains.annotations.Nullable
    public static Member member(UUID id) {
        for (Member m : members()) if (m.id().equals(id)) return m;
        return null;
    }

    public static boolean isMember(UUID id) {
        for (Member m : members()) if (m.id().equals(id)) return true;
        return false;
    }

    public static List<Invite> invites() {
        List<Invite> out = new ArrayList<>();
        for (Tag t : party.getList("invites", Tag.TAG_COMPOUND)) {
            CompoundTag i = (CompoundTag) t;
            out.add(new Invite(i.getUUID("party"), i.getString("from")));
        }
        return out;
    }

    /** A signature of the party state, so screens know when to rebuild their buttons. */
    public static String partySignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(inParty()).append(leader());
        for (Member m : members()) sb.append(m.id()).append(m.online());
        for (Invite i : invites()) sb.append(i.party());
        return sb.toString();
    }
}
