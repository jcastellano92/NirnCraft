package com.skycraft.quest.faction;

import com.skycraft.Skycraft;
import com.skycraft.core.Buffs;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.quest.story.Story;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Reputation;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How the factions of Tamriel react to each other and to the player: joining one side sours its enemies
 * (the Thalmor and the Talos-worshipping Blades and Stormcloaks, the Morag Tong and the Dark Brotherhood, the
 * Vigilants and the vampires, the Forsworn and the Reach's guards), praying to Talos is heresy the Thalmor punish on
 * sight, killing a guild brother gets the killer thrown out, Orcs are Blood-Kin of the strongholds by birth, the
 * Greybeards accept whoever walked the Way of the Voice, and the Imperial Cult's faithful keep a Divine's blessing
 * twice as long.
 *
 * <p>State in {@code module("quest")}: {@code faction_expelled} (faction id -> true; cleared by penance at a hall).</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class FactionEffects {
    private static final String EXPELLED = "faction_expelled";
    /** Story flag: the player has prayed at a shrine of Talos. */
    public static final String TALOS_FLAG = "talos_worshipper";

    /** Shrine blessings to lengthen for the Imperial Cult, by player: the divine and the tick to apply it. */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private record Pending(String divine, long at) {
    }

    private FactionEffects() {}

    // ------------------------------------------------------------------ expulsion

    public static boolean expelled(PlayerData data, Faction f) {
        return data.module("quest").getCompound(EXPELLED).getBoolean(f.id);
    }

    public static void markExpelled(PlayerData data, Faction f) {
        CompoundTag mod = data.module("quest");
        CompoundTag t = mod.getCompound(EXPELLED);
        t.putBoolean(f.id, true);
        mod.put(EXPELLED, t);
        data.markDirty();
    }

    /** Penance paid: the faction will take the player back. */
    public static void forgive(PlayerData data, Faction f) {
        CompoundTag mod = data.module("quest");
        CompoundTag t = mod.getCompound(EXPELLED);
        t.remove(f.id);
        mod.put(EXPELLED, t);
        data.markDirty();
    }

    // ------------------------------------------------------------------ joining

    /** Standing changes with the other powers when the player joins {@code f}. */
    public static void onJoined(ServerPlayer p, Faction f) {
        switch (f) {
            case THALMOR, ALDMERI_DOMINION -> {
                Reputation.add(p, Reputation.THALMOR, 30);
                Reputation.add(p, Reputation.STORMCLOAKS, -25);
            }
            case BLADES -> Reputation.add(p, Reputation.THALMOR, -40);
            case MORAG_TONG -> Reputation.add(p, Reputation.DARK_BROTHERHOOD, -40);
            case VIGILANTS_OF_STENDARR, KNIGHTS_OF_THE_NINE -> Reputation.add(p, Reputation.VAMPIRES, -40);
            case FORSWORN -> {
                Reputation.add(p, Reputation.FORSWORN, 90);
                Reputation.add(p, Reputation.GUARDS, -10);
            }
            case CAMONNA_TONG -> Reputation.add(p, Reputation.THIEVES_GUILD, -30);
            case FIGHTERS_GUILD, UNDAUNTED, ARENA -> Reputation.add(p, Reputation.TOWNSFOLK, 5);
            case IMPERIAL_CULT, TRIBUNAL_TEMPLE -> Reputation.add(p, Reputation.TOWNSFOLK, 8);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ events

    /** Every five seconds per player: birthright and Way of the Voice memberships, pending blessings. */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer p)) return;
        Pending pending = PENDING.get(p.getUUID());
        if (pending != null && p.level().getGameTime() >= pending.at()) {
            PENDING.remove(p.getUUID());
            lengthenBlessing(p, pending.divine());
        }
        if (p.tickCount % 100 != 37 || p.isSpectator()) return;
        PlayerData data = SkyData.get(p);
        if (data.getRace() == null) return;
        if (data.getRace() == Race.ORSIMER && !Factions.isMember(data, Faction.ORC_STRONGHOLDS) && !expelled(data, Faction.ORC_STRONGHOLDS)
                && !data.module("quest").getBoolean("orc_blood_kin")) {
            data.module("quest").putBoolean("orc_blood_kin", true);
            data.markDirty();
            Factions.join(p, Faction.ORC_STRONGHOLDS);
            Notifier.message(p, Component.translatable("faction.skycraft.orc_strongholds.birthright").withStyle(ChatFormatting.GOLD));
        }
        if (!Factions.isMember(data, Faction.GREYBEARDS) && !expelled(data, Faction.GREYBEARDS)
                && Story.completed(data, "main_way_of_the_voice") && !data.module("quest").getBoolean("greybeards_joined")) {
            data.module("quest").putBoolean("greybeards_joined", true);
            data.markDirty();
            Factions.join(p, Faction.GREYBEARDS);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    /** Prayers at the shrines of the Divines: Talos worship and the Imperial Cult's longer blessings. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer p) || p.isSpectator()) return;
        BlockState state = event.getLevel().getBlockState(event.getPos());
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!Skycraft.MODID.equals(id.getNamespace()) || !id.getPath().startsWith("shrine_of_")) return;
        String divine = id.getPath().substring("shrine_of_".length());
        PlayerData data = SkyData.get(p);
        if (Factions.isMember(data, Faction.IMPERIAL_CULT) || Factions.isMember(data, Faction.KNIGHTS_OF_THE_NINE)) {
            PENDING.put(p.getUUID(), new Pending(divine, p.level().getGameTime() + 2));
        }
        if (divine.equals("talos")) talosPrayer(p, event.getPos());
    }

    /** Talos worship: the Thalmor's heresy. Their members are cast out, their agents nearby attack. */
    static void talosPrayer(ServerPlayer p, BlockPos at) {
        PlayerData data = SkyData.get(p);
        boolean first = !Story.hasFlag(data, TALOS_FLAG);
        Story.setFlag(data, TALOS_FLAG, true);
        if (Factions.isMember(data, Faction.THALMOR)) Factions.expel(p, Faction.THALMOR, "faction.skycraft.reason.heresy");
        if (Factions.isMember(data, Faction.ALDMERI_DOMINION) && Factions.rank(data, Faction.ALDMERI_DOMINION) >= 2) {
            Factions.expel(p, Faction.ALDMERI_DOMINION, "faction.skycraft.reason.heresy");
        }
        boolean seen = false;
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32),
                e -> e.isAlive() && e instanceof NpcEntity n && n.role() == NpcRole.THALMOR)) {
            if (m.hasLineOfSight(p)) {
                seen = true;
                m.setTarget(p);
            }
        }
        if (seen) {
            Reputation.add(p, Reputation.THALMOR, -60);
            Notifier.message(p, Component.translatable("faction.skycraft.talos_seen").withStyle(ChatFormatting.RED));
        } else if (first) {
            Reputation.add(p, Reputation.THALMOR, -10);
            Notifier.message(p, Component.translatable("faction.skycraft.talos_prayer").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Doubles the blessing a faithful member of the Imperial Cult (or a Knight of the Nine) just received. */
    static void lengthenBlessing(ServerPlayer p, String divine) {
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation(Skycraft.MODID, "blessing_" + divine));
        if (effect == null) return;
        MobEffectInstance cur = p.getEffect(effect);
        if (cur == null || cur.getDuration() <= 0) return;
        int ticks = cur.getDuration() * 2;
        p.addEffect(new MobEffectInstance(effect, ticks, cur.getAmplifier(), false, false, true));
        Buffs.apply(p, "blessing_" + divine, ticks);
        Notifier.message(p, Component.translatable("faction.skycraft.imperial_cult.blessing").withStyle(ChatFormatting.GOLD));
    }

    /** Killing one of a faction's own people gets a member thrown out (and the Thalmor remember a dead justiciar). */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        String fid = victim.getPersistentData().getString(FactionStaff.TAG);
        Faction f = fid.isEmpty() ? null : Faction.byId(fid);
        if (f == null && victim instanceof NpcEntity n && n.role() == NpcRole.THALMOR) f = Faction.THALMOR;
        if (f == null) return;
        PlayerData data = SkyData.get(killer);
        if (Factions.isMember(data, f)) {
            Factions.expel(killer, f, "faction.skycraft.reason.kinslayer");
        }
    }
}
