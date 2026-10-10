package com.skycraft.creatures;

import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.creatures.entity.SkeeverEntity;
import com.skycraft.magic.spell.Summons;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Skyrim "loot body": creatures killed by a player (or a player's summon, pet or follower) keep their drops on a
 * {@link CorpseEntity} instead of scattering them. Dragons hand their corpse to the dragon, which leaves it after its
 * death animation.
 *
 * <p>The body holds exactly what the creature dropped (loot table, the equipment its drop chances let it drop, picked
 * up items), nothing more: equipment that didn't drop stays on the lying body but can't be taken. Bodies are capped
 * per area ({@value #MAX_NEARBY} within {@value #CAP_RADIUS} blocks; the oldest unused ones go first).</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CorpseEvents {
    /** The vanilla death animation (falling over) lasts 20 ticks; the body appears right after. */
    private static final int APPEAR_DELAY = 19;
    private static final int MAX_NEARBY = 24;
    private static final double CAP_RADIUS = 64.0;
    /** Loot slot order of {@link CorpseEntity}'s worn slots. */
    private static final EquipmentSlot[] WORN = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    /** What each dying creature wore, captured before its equipment drops (the dropped stacks are the same objects). */
    private static final Map<LivingEntity, ItemStack[]> WORN_AT_DEATH = new WeakHashMap<>();

    private CorpseEvents() {}

    /** Persistent-data key where the arsenal module records arrows stuck in a creature (contract 27). */
    public static final String ARROWS_KEY = "skycraft_arrows";

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (event.isCanceled() || dead.level().isClientSide || !(dead instanceof Mob)) return;
        ItemStack[] worn = new ItemStack[WORN.length];
        for (int i = 0; i < WORN.length; i++) worn[i] = dead.getItemBySlot(WORN[i]);
        WORN_AT_DEATH.put(dead, worn);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity dead = event.getEntity();
        ItemStack[] worn = WORN_AT_DEATH.remove(dead);
        if (event.isCanceled()) return;
        if (!(dead.level() instanceof ServerLevel level)) return;
        List<ItemStack> arrows = takeArrows(dead);

        boolean corpseAllowed = CreaturesConfig.CORPSES.get() && dead instanceof Mob && !(dead instanceof Slime)
                && !dead.getType().is(CreatureTags.NO_CORPSE)
                && (dead.getBbHeight() >= 0.35f || dead instanceof SkeeverEntity)
                && !Summons.isSummon(dead) && killedByPlayer(event);
        if (!corpseAllowed) {
            for (ItemStack arrow : arrows) event.getDrops().add(new ItemEntity(level, dead.getX(), dead.getY() + 0.5, dead.getZ(), arrow));
            return;
        }

        CorpseEntity corpse = ModEntities.CORPSE.get().create(level);
        if (corpse == null) return;

        // Exactly what the creature dropped goes into the body; dropped equipment goes back on the lying body.
        List<ItemEntity> stored = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            int slot = wornSlot(worn, stack);
            if (slot >= 0 && corpse.addWorn(slot, stack.copy())) {
                stored.add(drop);
                continue;
            }
            ItemStack rest = corpse.addLoot(stack.copy());
            if (rest.isEmpty()) {
                stored.add(drop);
            } else {
                drop.setItem(rest);
            }
        }
        event.getDrops().removeAll(stored);
        for (ItemStack arrow : arrows) {
            ItemStack rest = corpse.addLoot(arrow);
            if (!rest.isEmpty()) event.getDrops().add(new ItemEntity(level, dead.getX(), dead.getY() + 0.5, dead.getZ(), rest));
        }
        // Townspeople's clothes aren't equipment: generated, but only when mobs drop loot at all.
        if (level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) corpse.addClothes(dead);

        corpse.setBody(dead);

        if (dead instanceof DragonEntity dragon) {
            dragon.setPendingCorpse(corpse);
            return;
        }

        // Center the body where it will lie (it falls over sideways, like the vanilla death animation).
        float yaw = dead.yBodyRot;
        double a = Math.toRadians(180.0 - yaw);
        double half = dead.getBbHeight() * 0.5;
        Vec3 center = dead.position().add(-Mth.cos((float) a) * half, 0, Mth.sin((float) a) * half);
        corpse.moveTo(center.x, dead.getY(), center.z, yaw, 0);
        if (!level.noCollision(corpse)) {
            corpse.moveTo(dead.getX(), dead.getY(), dead.getZ(), yaw, 0);
            corpse.setCentered(false);
        }
        corpse.setAppearDelay(0);
        makeRoom(level, corpse.position());
        level.addFreshEntity(corpse);
        dead.discard();
    }

    /** The loot slot (0-5) of the equipment {@code stack} was dropped from, or -1. */
    private static int wornSlot(ItemStack[] worn, ItemStack stack) {
        if (worn == null || stack.isEmpty()) return -1;
        for (int i = 0; i < worn.length; i++) {
            if (worn[i] == stack) return i;
        }
        return -1;
    }

    /**
     * The kill counts as a player's: hit by a player shortly before dying (vanilla's "recently hit", which also covers
     * tamed wolves), or killed by a player, a player's projectile, or a creature fighting for a player (summons,
     * Animal Allegiance allies, pets, followers).
     */
    private static boolean killedByPlayer(LivingDropsEvent event) {
        if (event.isRecentlyHit()) return true;
        Entity killer = event.getSource().getEntity();
        if (killer instanceof Player) return true;
        if (killer == null) return false;
        if (Summons.ownerOf(killer) != null) return true;
        return killer instanceof OwnableEntity owned && owned.getOwner() instanceof Player;
    }

    /** Keeps at most {@value #MAX_NEARBY} bodies around {@code pos}: the oldest ones nobody is using go first. */
    public static void makeRoom(ServerLevel level, Vec3 pos) {
        List<CorpseEntity> near = level.getEntitiesOfClass(CorpseEntity.class,
                new net.minecraft.world.phys.AABB(pos, pos).inflate(CAP_RADIUS), c -> c.isAlive() && !c.inUse());
        int excess = near.size() - (MAX_NEARBY - 1);
        if (excess <= 0) return;
        near.sort(Comparator.comparingInt(CorpseEntity::age).reversed());
        for (int i = 0; i < excess && i < near.size(); i++) near.get(i).discard();
    }

    /** Reads (and clears) the arrows the arsenal module recorded on the creature. */
    private static List<ItemStack> takeArrows(LivingEntity dead) {
        List<ItemStack> out = new ArrayList<>();
        CompoundTag data = dead.getPersistentData();
        if (!data.contains(ARROWS_KEY, Tag.TAG_LIST)) return out;
        ListTag list = data.getList(ARROWS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) out.add(stack);
        }
        data.remove(ARROWS_KEY);
        return out;
    }
}
