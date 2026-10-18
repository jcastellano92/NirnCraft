package com.skycraft.magic;

import com.skycraft.magic.bound.BoundWeapons;
import com.skycraft.magic.spell.AlterationSpells;
import com.skycraft.magic.spell.Candlelight;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.Spells;
import com.skycraft.magic.spell.Summons;
import com.skycraft.network.SkyNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The spell effects of a player that last for a while but are not vanilla mob effects, as a list of countdowns for the
 * HUD ({@link MagicPackets.Timers}): bound weapons (read from the held item), Candlelight and Magelight, the detect
 * spells (which only make other creatures glow), summons and lingering areas. Mob effects on the player itself (Oakflesh,
 * Waterbreathing, Invisibility, fortify potions...) are read by the client from the player's active effects.
 */
public final class ActiveMagic {
    /** Effects longer than this are not shown (permanent allies, unlimited things). */
    private static final int MAX_TICKS = 20 * 60 * 30;

    private record Noted(Component name, long end, int total, int rgb) {}

    /** player -> key -> a timer recorded by the spell that made it (detect spells...). Nothing here is saved. */
    private static final Map<UUID, Map<String, Noted>> NOTED = new HashMap<>();
    /** The last list sent to each player, to resend only when it changed. */
    private static final Map<UUID, List<MagicPackets.Timers.Entry>> SENT = new HashMap<>();
    private record Summon(Component name, long until) {}
    /** The player's summons, refreshed every two seconds. */
    private static final Map<UUID, List<Summon>> SUMMONS = new HashMap<>();

    private ActiveMagic() {}

    /** The HUD countdown text: "42s" under a minute, "1:05" from a minute on. */
    public static String format(long seconds) {
        seconds = Math.max(0, seconds);
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }

    /** A spell effect that lasts {@code ticks} and has no mob effect on the caster. */
    public static void note(ServerPlayer p, Spell spell, int ticks) {
        if (ticks <= 0) return;
        NOTED.computeIfAbsent(p.getUUID(), k -> new HashMap<>())
                .put(spell.id, new Noted(spell.displayName(), p.level().getGameTime() + ticks, ticks, spell.school.color));
    }

    /** Same for made spells (Spellmaking) and other effects with their own name. */
    public static void note(ServerPlayer p, String key, Component name, int ticks, int rgb) {
        if (ticks <= 0) return;
        NOTED.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(key, new Noted(name, p.level().getGameTime() + ticks, ticks, rgb));
    }

    /** Every non-mob-effect countdown of {@code p} right now, soonest first. */
    public static List<MagicPackets.Timers.Entry> collect(ServerPlayer p) {
        long now = p.level().getGameTime();
        List<MagicPackets.Timers.Entry> out = new ArrayList<>();
        Map<String, Noted> noted = NOTED.get(p.getUUID());
        if (noted != null) {
            noted.values().removeIf(n -> n.end <= now);
            for (Noted n : noted.values()) add(out, n.name, (int) (n.end - now), n.total, n.rgb);
        }
        // bound weapons: the item carries its own end time
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!BoundWeapons.isBound(s) || com.skycraft.inventory.ItemData.custom(s) == null) continue;
            CompoundTag t = com.skycraft.inventory.ItemData.custom(s);
            add(out, s.getHoverName(), (int) (t.getLongOr(BoundWeapons.UNTIL, 0L) - now), t.getIntOr(BoundWeapons.DURATION, 0), 0xB890E8);
        }
        long candle = Candlelight.until(p);
        if (candle > now) add(out, spellName("candlelight"), (int) (candle - now), 0, 0xFFE8A0);
        long mage = AlterationSpells.magelightUntil(p);
        if (mage > now) add(out, spellName("magelight"), (int) (mage - now), 0, 0xFFE8A0);
        // summons are looked up every couple of seconds (an entity query), see tick()
        for (Summon s : SUMMONS.getOrDefault(p.getUUID(), List.of())) add(out, s.name, (int) (s.until - now), 0, 0xA0D8A0);
        for (MagicScheduler.Area a : MagicScheduler.areas(p.getUUID(), "")) {
            Component name = areaName(a.kind);
            if (name != null) add(out, name, a.ticksLeft, 0, 0x9CC8F0);
        }
        out.sort(Comparator.comparingInt(MagicPackets.Timers.Entry::ticks));
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    private static void add(List<MagicPackets.Timers.Entry> out, Component name, int ticks, int total, int rgb) {
        if (ticks <= 0 || ticks > MAX_TICKS) return;
        out.add(new MagicPackets.Timers.Entry(name, ticks, Math.max(total, 0), rgb));
    }

    private static Component spellName(String id) {
        Spell s = Spells.byId(id);
        return s != null ? s.displayName() : Component.translatable("spell.skycraft." + id);
    }

    /** Lingering areas worth a countdown (runes and fire walls are placed traps, not buffs). */
    private static Component areaName(String kind) {
        return switch (kind) {
            case "blizzard", "guardian_circle", "circle_of_protection", "storm_call" -> Component.translatable("magic.skycraft.area." + kind);
            default -> null;
        };
    }

    /** Server side tick: sends the list when it changed, and at least once a second so the client stays in step. */
    public static void tick(ServerPlayer p) {
        if (p.tickCount % 10 != 0) return;
        if (p.tickCount % 40 == 0) {
            List<Summon> found = new ArrayList<>();
            for (Mob m : Summons.summonsOf(p)) {
                CompoundTag pd = m.getPersistentData();
                if (pd.contains(Summons.UNTIL)) found.add(new Summon(m.getDisplayName(), pd.getLongOr(Summons.UNTIL, 0L) + com.skycraft.world.RestManager.skippedTicks())); // live time -> game time
            }
            if (found.isEmpty()) SUMMONS.remove(p.getUUID());
            else SUMMONS.put(p.getUUID(), found);
        }
        List<MagicPackets.Timers.Entry> now = collect(p);
        List<MagicPackets.Timers.Entry> last = SENT.get(p.getUUID());
        boolean lastEmpty = last == null || last.isEmpty();
        if (now.isEmpty() && lastEmpty) return;
        boolean sameSet = !lastEmpty && last.size() == now.size();
        for (int i = 0; sameSet && i < now.size(); i++) sameSet = now.get(i).name().equals(last.get(i).name());
        // unchanged: only the once-a-second resync (the client counts down on its own in between)
        if (sameSet && p.tickCount % 20 != 0) return;
        SENT.put(p.getUUID(), now);
        SkyNetwork.sendToPlayer(p, new MagicPackets.Timers(now));
    }

    public static void forget(UUID id) {
        NOTED.remove(id);
        SUMMONS.remove(id);
        SENT.remove(id);
    }

    public static void clear() {
        NOTED.clear();
        SUMMONS.clear();
        SENT.clear();
    }
}
