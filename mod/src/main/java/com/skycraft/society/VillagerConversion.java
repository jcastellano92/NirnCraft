package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.economy.Shop;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * There are no vanilla villagers on Nirn. Any villager that would join a level (breeding, zombie curing, spawn eggs,
 * {@code /summon}, structures of other mods, old saves) is cancelled and replaced on the next server tick by a
 * Skycraft townsperson of the region's people: its profession becomes a trade with a matching Skycraft shop
 * (armorer/weaponsmith/toolsmith = blacksmith, cleric = apothecary, librarian = scholar, farmer, butcher, fisher...),
 * babies become children, nitwits and the unemployed plain townsfolk. Wandering traders become Khajiit caravan traders.
 * Names, persistent data (shops, tags from other modules) and position are kept. Conversions are capped per area,
 * so a breeding farm can't flood a town.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class VillagerConversion {
    private static final int MAX_NEARBY = 28;

    private record Pending(ServerLevel level, Vec3 pos, float yaw, NpcRole role, @Nullable String profession, int level5,
                           @Nullable Component name, CompoundTag persistent, Set<String> tags, boolean persistentMob,
                           boolean trader) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    private VillagerConversion() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (event.getLevel().isClientSide || !(e instanceof AbstractVillager v) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (!(v instanceof Villager) && !(v instanceof WanderingTrader)) return; // other mods' villager subclasses keep their own logic
        if (!SocietyConfig.CONVERT_VILLAGERS.get()) return;
        // decide the caps here: only cancel when conversion is certain, otherwise the villager joins unchanged
        // (convert() re-checks, so a bail there can only happen on a chunk unloading within the tick)
        if (PENDING.size() > 256) return;
        BlockPos at = v.blockPosition();
        if (level.getEntitiesOfClass(NpcEntity.class, new net.minecraft.world.phys.AABB(at).inflate(48), n -> true).size() >= MAX_NEARBY) return;
        if (!(v instanceof WanderingTrader) && v.getPersistentData().isEmpty() && v.getTags().isEmpty()
                && SettlementCrowd.settlementFull(level, at)) return;
        event.setCanceled(true);
        NpcRole role;
        String profession = null;
        int lvl = 2;
        boolean trader = v instanceof WanderingTrader;
        if (v instanceof Villager villager) {
            VillagerProfession p = villager.getVillagerData().getProfession();
            lvl = villager.getVillagerData().getLevel();
            role = villager.isBaby() ? NpcRole.CHILD : roleFor(p);
            var key = ForgeRegistries.VILLAGER_PROFESSIONS.getKey(p);
            if (key != null && p != VillagerProfession.NONE && p != VillagerProfession.NITWIT) profession = key.toString();
        } else {
            role = NpcRole.CARAVAN_TRADER;
        }
        if (com.skycraft.survival.inn.Innkeepers.isInnkeeper(v)) role = NpcRole.INNKEEPER;
        PENDING.add(new Pending(level, v.position(), v.getYRot(), role, profession, lvl, v.getCustomName(),
                v.getPersistentData().copy(), Set.copyOf(v.getTags()), v.isPersistenceRequired() || v.hasCustomName(), trader));
    }

    /** The trade a villager profession becomes. */
    public static NpcRole roleFor(VillagerProfession p) {
        if (p == VillagerProfession.ARMORER || p == VillagerProfession.WEAPONSMITH || p == VillagerProfession.TOOLSMITH) return NpcRole.BLACKSMITH;
        if (p == VillagerProfession.CLERIC) return NpcRole.APOTHECARY;
        if (p == VillagerProfession.LIBRARIAN) return NpcRole.SCHOLAR;
        if (p == VillagerProfession.FARMER) return NpcRole.FARMER;
        if (p == VillagerProfession.BUTCHER) return NpcRole.BUTCHER;
        if (p == VillagerProfession.FISHERMAN) return NpcRole.FISHER;
        if (p == VillagerProfession.LEATHERWORKER) return NpcRole.HUNTER;
        if (p == VillagerProfession.MASON) return NpcRole.MINER;
        if (p == VillagerProfession.FLETCHER || p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.SHEPHERD) return NpcRole.MERCHANT;
        return NpcRole.CITIZEN;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        List<Pending> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending p : batch) {
            try {
                convert(p);
            } catch (RuntimeException ex) {
                Skycraft.LOGGER.warn("Skycraft society: villager conversion failed", ex);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void convert(Pending p) {
        ServerLevel level = p.level();
        BlockPos pos = BlockPos.containing(p.pos());
        if (!level.isLoaded(pos)) return;
        int nearby = level.getEntitiesOfClass(NpcEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(48), e -> true).size();
        if (nearby >= MAX_NEARBY) return;
        // a settlement that already holds as many people as its housing plan has slots takes no strangers
        if (!p.trader() && p.persistent().isEmpty() && p.tags().isEmpty() && SettlementCrowd.settlementFull(level, pos)) return;
        NpcEntity npc = NpcEntities.typeFor(p.role()).create(level);
        if (npc == null) return;
        npc.moveTo(p.pos().x, p.pos().y, p.pos().z, p.yaw(), 0f);
        npc.setYHeadRot(p.yaw());
        npc.setRole(p.role());
        // keep what other modules stored (innkeeper tags, shops, quest markers), minus vanilla-only bits
        CompoundTag pd = npc.getPersistentData();
        for (String k : p.persistent().getAllKeys()) pd.put(k, p.persistent().get(k).copy());
        if (p.profession() != null && !pd.contains(Npcs.PROFESSION_KEY)) pd.putString(Npcs.PROFESSION_KEY, p.profession());
        if (p.profession() != null && !pd.contains(Shop.KEY)) {
            // the villager's experience becomes the shop's level
            CompoundTag shop = new CompoundTag();
            shop.putInt("level", Math.max(1, Math.min(5, p.level5())));
            pd.put(Shop.KEY, shop);
        }
        for (String t : p.tags()) npc.addTag(t);
        if (p.name() != null) npc.setCustomName(p.name());
        npc.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.CONVERSION, null, null);
        // a profession-only shop entry needs its pools now that the role is known
        CompoundTag shop = pd.getCompound(Shop.KEY);
        if (!shop.contains("pools")) {
            pd.remove(Shop.KEY);
            Npcs.setupTrade(npc, p.role(), npc.getRandom());
            if (pd.contains(Shop.KEY)) pd.getCompound(Shop.KEY).putInt("level", Math.max(1, Math.min(5, p.level5())));
        }
        if (p.trader()) {
            // caravans move on after a while, like the wandering traders they replace
            Encounters.mark(npc, level.getGameTime() + 48000L, 0);
        } else {
            npc.setPersistenceRequired();
            if (p.role().townsfolk()) npc.setHome(pos, p.role() == NpcRole.CHILD ? 20 : 32);
        }
        level.addFreshEntity(npc);
    }
}
