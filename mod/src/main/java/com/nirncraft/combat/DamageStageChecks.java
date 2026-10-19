package com.nirncraft.combat;

import com.nirncraft.inventory.PackSelfTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Real native hurtServer acceptance checks; invoked by the existing inventory selftest/autorun. */
public final class DamageStageChecks {
    private DamageStageChecks() {}
    public static void run(ServerLevel level, PackSelfTest.Checks checks) {
        var victim = EntityTypes.COW.create(level, EntitySpawnReason.EVENT);
        if (victim == null) { checks.check("damage stages: native victim created", false, "cow creation returned null"); return; }
        victim.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        victim.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(8);
        checks.check("damage stages: victim has nonzero native armor", victim.getArmorValue() >= 8, "armor=" + victim.getArmorValue());
        var source = level.damageSources().mobAttack(victim);
        checks.check("damage stages: fixture source respects native armor",
                !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR), "mob_attack");
        java.util.List<String> order = new java.util.ArrayList<>();
        java.util.function.Consumer<BeforeArmorDamageEvent> high = e -> { if (e.getEntity() == victim) { order.add("high"); e.setAmount(e.getAmount() * 2); } };
        java.util.function.Consumer<BeforeArmorDamageEvent> low = e -> { if (e.getEntity() == victim) order.add("low"); };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, high);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, low);
        try {
            float before = victim.getHealth();
            float expected = net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(victim, 8, source, victim.getArmorValue(), 0);
            boolean first = victim.hurtServer(level, source, 4);
            checks.check("damage stages: accepted hit priority and armor health", first && order.equals(java.util.List.of("high", "low")) && Math.abs(before - victim.getHealth() - expected) < 0.001f, "order=" + order + ", health damage=" + (before - victim.getHealth()) + ", expected=" + expected);
            float afterFirst = victim.getHealth();
            boolean rejected = !victim.hurtServer(level, source, 4);
            checks.check("damage stages: cooldown rejection has no accepted-hit callbacks", rejected && order.size() == 2 && victim.getHealth() == afterFirst, "callbacks=" + order.size());
            float extraExpected = net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(victim, 4, source, victim.getArmorValue(), 0);
            victim.hurtServer(level, source, 6);
            checks.check("damage stages: stronger cooldown hit scales only the excess", order.size() == 4 && Math.abs(afterFirst - victim.getHealth() - extraExpected) < 0.001f, "health damage=" + (afterFirst - victim.getHealth()) + ", expected=" + extraExpected);
        } finally {
            NeoForge.EVENT_BUS.unregister(high); NeoForge.EVENT_BUS.unregister(low); victim.discard();
        }
    }
}
