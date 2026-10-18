package com.skycraft.creatures;

import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.*;
import com.skycraft.fauna.entity.MammothEntity;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/**
 * Faction protection behaviors:
 * <ul>
 *     <li>Giants & Mammoths: Giants fiercely protect their Mammoth herds. Harming a mammoth aggroes nearby giants.</li>
 *     <li>Bandits & Chiefs: Bandits swarm together to defend their camp.</li>
 *     <li>Forsworn: Tribal reachmen fight as a unified warband.</li>
 *     <li>Draugr: Ancient barrow guardians awaken and swarm tomb raiders together.</li>
 *     <li>Falmer: Blind subterranean elves coordinate via pack echolocation.</li>
 *     <li>Towns: Hold guards immediately defend attacked villagers and citizens.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class FactionDefenseEvents {
    private FactionDefenseEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide()) return;
        LivingEntity victim = event.getEntity();
        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity le ? le : null;
        if (attacker == null || attacker == victim) return;

        // 1. Giants & Mammoths herd defense
        if (victim instanceof MammothEntity mammoth) {
            // Aggro nearby giants
            List<GiantEntity> giants = mammoth.level().getEntitiesOfClass(GiantEntity.class, mammoth.getBoundingBox().inflate(36), Mob::isAlive);
            for (GiantEntity giant : giants) {
                if (giant.getTarget() == null || giant.getTarget() != attacker) {
                    giant.setTarget(attacker);
                }
            }
            // Aggro other mammoths
            List<MammothEntity> mammoths = mammoth.level().getEntitiesOfClass(MammothEntity.class, mammoth.getBoundingBox().inflate(32), m -> m != mammoth && m.isAlive());
            for (MammothEntity other : mammoths) {
                if (other.getTarget() == null) other.setTarget(attacker);
            }
        } else if (victim instanceof GiantEntity giant) {
            List<MammothEntity> mammoths = giant.level().getEntitiesOfClass(MammothEntity.class, giant.getBoundingBox().inflate(36), Mob::isAlive);
            for (MammothEntity m : mammoths) {
                if (m.getTarget() == null) m.setTarget(attacker);
            }
            List<GiantEntity> giants = giant.level().getEntitiesOfClass(GiantEntity.class, giant.getBoundingBox().inflate(32), g -> g != giant && g.isAlive());
            for (GiantEntity other : giants) {
                if (other.getTarget() == null) other.setTarget(attacker);
            }
        }

        // 2. Bandits & Bandit Chiefs
        if (victim instanceof BanditEntity || victim instanceof BanditChiefEntity) {
            List<Mob> bandits = victim.level().getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(24),
                    m -> (m instanceof BanditEntity || m instanceof BanditChiefEntity) && m != victim && m.isAlive());
            for (Mob bandit : bandits) {
                if (bandit.getTarget() == null) bandit.setTarget(attacker);
            }
        }

        // 3. Forsworn
        if (victim instanceof ForswornEntity) {
            List<ForswornEntity> forsworn = victim.level().getEntitiesOfClass(ForswornEntity.class, victim.getBoundingBox().inflate(24),
                    f -> f != victim && f.isAlive());
            for (ForswornEntity f : forsworn) {
                if (f.getTarget() == null) f.setTarget(attacker);
            }
        }

        // 4. Draugr & Draugr Deathlords
        if (victim instanceof DraugrEntity || victim instanceof DraugrDeathlordEntity) {
            List<Mob> draugrList = victim.level().getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(24),
                    m -> (m instanceof DraugrEntity || m instanceof DraugrDeathlordEntity) && m != victim && m.isAlive());
            for (Mob d : draugrList) {
                if (d.getTarget() == null) d.setTarget(attacker);
            }
        }

        // 5. Falmer
        if (victim instanceof FalmerEntity falmer) {
            falmer.alertPack(attacker);
        }

        // 6. Villagers / Townsfolk -> Hold Guards
        if (victim instanceof Villager || victim instanceof NpcEntity) {
            boolean ignore = attacker instanceof GuardEntity || attacker instanceof Villager || attacker instanceof NpcEntity
                    || attacker instanceof Player p && (p.isCreative() || p.isSpectator()
                    || com.skycraft.crime.Crimes.selfDefense(victim, (net.minecraft.server.level.ServerPlayer) p));
            if (!ignore) {
                double r = CreaturesConfig.GUARD_ALERT_RADIUS.get();
                List<GuardEntity> guards = victim.level().getEntitiesOfClass(GuardEntity.class, victim.getBoundingBox().inflate(r, 16, r), Mob::isAlive);
                for (GuardEntity guard : guards) {
                    if (!(guard.getTarget() instanceof Player)) guard.setTarget(attacker);
                }
            }
        }
    }
}
