package com.skycraft.curse;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.NotifyKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Public API of Skycraft's vampirism and lycanthropy, for quests, commands and the hooks other modules call.
 *
 * <p>All state lives in {@code SkyData.get(player).module("curse")} (keys below). Every query works on both logical
 * sides: the owner's client gets the module through the full player-data sync, other players' clients only know the
 * {@link Form} and the vampire stage ({@link CursePackets.Sync}).</p>
 *
 * <ul>
 *     <li>{@link #grantBeastBlood} / {@link #cure} with {@link CurseType#LYCANTHROPY}: the Companions' gift and the
 *     Glenmoril cure</li>
 *     <li>{@link #infect}: Sanguinare Vampiris (vampires, death hounds); {@link #turnVampire}: the disease ran its
 *     course or Harkon's gift; {@link #grantVampireLord}: Harkon's gift of the Vampire Lord form</li>
 *     <li>{@link #cure} with {@link CurseType#VAMPIRISM}: Falion's ritual ("Rising at Dawn") or any quest</li>
 * </ul>
 */
public final class Curses {
    public static final String MODULE = "curse";
    // ------------------------------------------------------------------ module keys
    static final String VAMPIRE = "vampire";
    /** Game time of the last feeding (the stage follows from the days since). */
    static final String FED = "fed";
    static final String STAGE_SEEN = "stage_seen";
    static final String LORD = "lord";
    /** Game time Sanguinare Vampiris was contracted (0 = not infected). */
    static final String SANGUINARE = "sanguinare";
    static final String SANG_DAYS = "sang_days";
    /** Game time Sanies Lupinus (Stage 1 lycanthropy disease) was contracted (0 = not infected). */
    static final String SANIES_LUPINUS = "sanies_lupinus";
    static final String LUPINUS_DAYS = "lupinus_days";
    static final String WEREWOLF = "werewolf";
    static final String FORM = "form";
    static final String FORM_UNTIL = "form_until";
    static final String BEAST_READY = "beast_ready";
    static final String SIGHT = "sight";
    static final String COOLDOWNS = "cooldowns";
    static final String SELECTED_HOWL = "howl";
    static final String SELECTED_SPELL = "lord_spell";
    static final String SELECTED_POWER = "lord_power";

    private Curses() {}

    public static CompoundTag tag(Player player) {
        return SkyData.get(player).module(MODULE);
    }

    static void dirty(Player player) {
        SkyData.get(player).markDirty();
    }

    // ------------------------------------------------------------------ queries (both sides)

    public static boolean isVampire(Player player) {
        if (player.level().isClientSide() && !isLocal(player)) return ClientView.stage(player) > 0;
        return tag(player).getBooleanOr(VAMPIRE, false);
    }

    /** Vampire stage 1..4 (0 = not a vampire). */
    public static int stage(Player player) {
        if (player.level().isClientSide() && !isLocal(player)) return ClientView.stage(player);
        CompoundTag t = tag(player);
        if (!t.getBooleanOr(VAMPIRE, false)) return 0;
        return Vampirism.stageFor(player.level().getGameTime() - t.getLongOr(FED, 0L));
    }

    public static boolean hasVampireLord(Player player) {
        return isVampire(player) && tag(player).getBooleanOr(LORD, false);
    }

    public static boolean isWerewolf(Player player) {
        if (player.level().isClientSide() && !isLocal(player)) return ClientView.werewolf(player);
        return tag(player).getBooleanOr(WEREWOLF, false);
    }

    public static boolean hasSanguinare(Player player) {
        return tag(player).getLongOr(SANGUINARE, 0L) > 0;
    }

    public static boolean hasSaniesLupinus(Player player) {
        return tag(player).getLongOr(SANIES_LUPINUS, 0L) > 0;
    }

    public static Form form(Player player) {
        if (player.level().isClientSide()) return ClientView.form(player);
        return Form.byId(tag(player).getStringOr(FORM, ""));
    }

    /** In Beast Form or Vampire Lord form: no weapons, no spells, no items. */
    public static boolean transformed(Player player) {
        return form(player) != Form.NONE;
    }

    /** Has either curse. */
    public static boolean cursed(Player player) {
        return isVampire(player) || isWerewolf(player);
    }

    /**
     * Townsfolk and guards attack (or flee) on sight: a transformed beast, or a vampire at stage 4 whose hunger shows
     * on its face.
     */
    public static boolean shunned(Player player) {
        return transformed(player) || stage(player) >= 4;
    }

    private static boolean isLocal(Player player) {
        return ClientView.isLocal(player);
    }

    // ------------------------------------------------------------------ hooks called by other modules

    /** Survival's {@code Diseases.infect}: werewolves and vampires never catch diseases. */
    public static boolean immuneToDisease(LivingEntity entity) {
        return entity instanceof Player p && !p.level().isClientSide() && cursed(p);
    }

    /**
     * Vitals' regeneration: how much of the normal regeneration a pool gets ({@code 'h'}ealth, {@code 'm'}agicka,
     * {@code 's'}tamina). Vampires in sunlight regenerate nothing; beasts only heal by feeding.
     */
    public static float regenMultiplier(Player player, char pool) {
        if (Vampirism.inSun(player) && isVampire(player)) return 0f;
        Form form = form(player);
        if (pool == 'h' && form != Form.NONE) return 0f;
        return 1f;
    }

    /** Survival's {@code Diseases.cureAll} (potions, shrines, priests, death): Sanguinare is a disease until it turns. */
    public static boolean cureSanguinare(LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        boolean had = player.hasEffect(com.skycraft.platform.Effects.holder(CurseRegistry.SANGUINARE.get())) || hasSanguinare(player);
        if (!had) return false;
        player.removeEffect(com.skycraft.platform.Effects.holder(CurseRegistry.SANGUINARE.get()));
        tag(player).remove(SANGUINARE);
        tag(player).remove(SANG_DAYS);
        dirty(player);
        return true;
    }

    /** Survival's {@code Diseases.cureAll}: Sanies Lupinus (Stage 1 lycanthropy) is curable until it turns after 3 days. */
    public static boolean cureSaniesLupinus(LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        boolean had = player.hasEffect(com.skycraft.platform.Effects.holder(CurseRegistry.SANIES_LUPINUS.get())) || hasSaniesLupinus(player);
        if (!had) return false;
        player.removeEffect(com.skycraft.platform.Effects.holder(CurseRegistry.SANIES_LUPINUS.get()));
        tag(player).remove(SANIES_LUPINUS);
        tag(player).remove(LUPINUS_DAYS);
        dirty(player);
        return true;
    }

    // ------------------------------------------------------------------ granting and curing (server)

    /** Sanguinare Vampiris (unless immune). Returns whether the player caught it. */
    public static boolean infect(ServerPlayer player) {
        return Vampirism.infect(player);
    }

    /** Sanies Lupinus (Stage 1 lycanthropy disease from werewolf hits, unless immune). */
    public static boolean infectLycanthropy(ServerPlayer player) {
        return Lycanthropy.infect(player);
    }

    /** Makes the player a full vampire at stage 1 (curing beast blood: the two curses don't mix). */
    public static void turnVampire(ServerPlayer player) {
        Vampirism.turn(player);
    }

    /** Harkon's gift: vampirism (if not yet) plus the Vampire Lord form. */
    public static void grantVampireLord(ServerPlayer player) {
        if (!tag(player).getBooleanOr(VAMPIRE, false)) Vampirism.turn(player);
        if (tag(player).getBooleanOr(LORD, false)) return;
        tag(player).putBoolean(LORD, true);
        dirty(player);
        CursePackets.sync(player);
        Notifier.title(player, Component.translatable("curse.skycraft.lord.granted"), Component.translatable("curse.skycraft.lord.granted.sub"));
    }

    /** The Circle's gift (Companions' quest) or Sanies Lupinus after 3 days: lycanthropy and Beast Form. Cures vampirism. */
    public static void grantBeastBlood(ServerPlayer player) {
        CompoundTag t = tag(player);
        if (t.getBooleanOr(VAMPIRE, false) || hasSanguinare(player)) {
            cureSanguinare(player);
            cure(player, CurseType.VAMPIRISM, false);
        }
        cureSaniesLupinus(player);
        boolean had = t.getBooleanOr(WEREWOLF, false);
        t.putBoolean(WEREWOLF, true);
        t.remove(BEAST_READY);
        // the quest module's own flag (journal, +5% damage) follows the curse
        SkyData.get(player).module("quest").putBoolean("beast_blood", true);
        dirty(player);
        CursePackets.sync(player);
        if (!had) {
            Notifier.title(player, Component.translatable("curse.skycraft.beast.granted"), Component.translatable("curse.skycraft.beast.granted.sub",
                    CursePowers.name(CursePowers.BEAST_FORM)));
            player.level().playSound(null, player.blockPosition(), com.skycraft.audio.AudioSounds.creature("wolf", "howl"), SoundSource.PLAYERS, 1f, 0.6f);
        }
    }

    /** Cures a curse (quest hook): returns the player to their own form first. */
    public static void cure(ServerPlayer player, CurseType type) {
        cure(player, type, true);
    }

    static void cure(ServerPlayer player, CurseType type, boolean announce) {
        CompoundTag t = tag(player);
        PlayerData data = SkyData.get(player);
        boolean had;
        if (type == CurseType.VAMPIRISM) {
            had = t.getBooleanOr(VAMPIRE, false) || hasSanguinare(player);
            if (form(player) == Form.LORD) VampireLord.revert(player, false);
            cureSanguinare(player);
            t.remove(VAMPIRE);
            t.remove(FED);
            t.remove(STAGE_SEEN);
            t.remove(LORD);
            t.remove(SIGHT);
            Vampirism.clearEffects(player);
        } else {
            had = t.getBooleanOr(WEREWOLF, false) || hasSaniesLupinus(player);
            if (form(player) == Form.BEAST) Lycanthropy.revert(player, false);
            cureSaniesLupinus(player);
            t.remove(WEREWOLF);
            t.remove(BEAST_READY);
            data.module("quest").putBoolean("beast_blood", false);
        }
        Vampirism.refreshBonuses(player);
        data.markDirty();
        CursePackets.sync(player);
        if (had && announce) {
            Notifier.send(player, NotifyKind.BIG_TITLE, Component.translatable("curse.skycraft.cured." + type.id()), Component.empty());
            player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8f, 1.2f);
        }
    }

    /**
     * What clients know about other players (filled by {@link CursePackets.Sync}). Plain maps keyed by entity id, so
     * the class is safe to load on a dedicated server.
     */
    public static final class ClientView {
        private static final java.util.Map<Integer, int[]> STATE = new java.util.concurrent.ConcurrentHashMap<>();
        /** Set by the client module: the local player's entity id (the owner reads its own module). */
        public static volatile int localId = Integer.MIN_VALUE;

        private ClientView() {}

        static boolean isLocal(Player player) {
            return player.getId() == localId;
        }

        /** {form ordinal, stage, flags}. */
        public static void put(int entityId, int form, int stage, int flags) {
            STATE.put(entityId, new int[]{form, stage, flags});
        }

        public static void clear() {
            STATE.clear();
        }

        public static void forget(int entityId) {
            STATE.remove(entityId);
        }

        public static Form form(Player player) {
            int[] s = STATE.get(player.getId());
            return s == null ? Form.NONE : Form.byOrdinal(s[0]);
        }

        public static int stage(Player player) {
            int[] s = STATE.get(player.getId());
            return s == null ? 0 : s[1];
        }

        public static boolean werewolf(Player player) {
            int[] s = STATE.get(player.getId());
            return s != null && (s[2] & CursePackets.Sync.WEREWOLF) != 0;
        }

        public static int flags(Player player) {
            int[] s = STATE.get(player.getId());
            return s == null ? 0 : s[2];
        }
    }
}
