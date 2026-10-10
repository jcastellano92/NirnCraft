package com.skycraft.society;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Skill;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.Leveling;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Contract 11 (docs/PLAYTEST-1.md): follower relationships.
 *
 * <p>An NPC follows a player only after a relationship is built: a favor quest, enough gold for hirelings
 * (500 Septims), a successful Speech check, or a quest reward (Housecarl). Followers obey commands (wait,
 * follow, trade items, dismiss), use their gear, and level with the region.</p>
 */
public final class Followers {
    public static final String FOLLOWER_KEY = "skycraft_follower";
    public static final String INVENTORY_KEY = "skycraft_follower_inv";
    public static final int INVENTORY_SIZE = 27;
    public static final int HIRELING_COST = 500;
    /** Key in the player's {@code society} data: UUID of their one follower (survives restarts). */
    private static final String PLAYER_KEY = "follower";
    /** Persistent compound on an NPC: player uuid -> game time of their last failed persuasion. */
    private static final String PERSUADE_FAIL_KEY = "skycraft_persuade_fail";
    /** Persistent compound on an NPC: players who already earned Speech XP persuading them. */
    private static final String PERSUADE_XP_KEY = "skycraft_persuade_xp";
    /** Native gear a follower took off for gear the player gave them (slot name -> item). */
    private static final String NATIVE_KEY = "skycraft_follower_native";
    /** A failed persuasion can't be retried on the same NPC for one in-game day. */
    private static final long PERSUADE_COOLDOWN = 24000L;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private Followers() {}

    /** The UUID of the player's follower as recorded in their (persisted) data, or null. */
    @Nullable
    public static UUID followerOf(Player player) {
        CompoundTag mod = SkyData.get(player).module(Reputation.MODULE);
        return mod.hasUUID(PLAYER_KEY) ? mod.getUUID(PLAYER_KEY) : null;
    }

    private static void setFollowerOf(Player player, @Nullable UUID npc) {
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module(Reputation.MODULE);
        if (npc == null) mod.remove(PLAYER_KEY);
        else mod.putUUID(PLAYER_KEY, npc);
        data.markDirty();
    }

    /**
     * Keeps "one follower per player" true across restarts and unloaded chunks: a follower whose owner's data names
     * another NPC (they recruited someone else meanwhile) quietly stops following. Legacy followers claim the empty
     * record. Returns whether {@code npc} is still the owner's follower.
     */
    public static boolean validate(LivingEntity npc, Player owner) {
        UUID recorded = followerOf(owner);
        if (recorded == null) {
            setFollowerOf(owner, npc.getUUID());
            return true;
        }
        if (recorded.equals(npc.getUUID())) return true;
        release(npc);
        return false;
    }

    private static void release(LivingEntity npc) {
        npc.getPersistentData().remove(FOLLOWER_KEY);
        if (npc instanceof NpcEntity n) {
            n.setLeader(null);
            if (n.getHome() != null) n.setDestination(n.getHome());
        }
    }

    public static void register() {
        Dialogue.registerProvider(Followers::addOptions);
    }

    /**
     * Contract 11: checks whether {@code npc} is an active follower of {@code player}.
     */
    public static boolean isFollowerOf(@Nullable Entity npc, @Nullable Player player) {
        if (npc == null || player == null) return false;
        CompoundTag pd = npc.getPersistentData();
        if (!pd.contains(FOLLOWER_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag f = pd.getCompound(FOLLOWER_KEY);
        return player.getStringUUID().equals(f.getString("owner"));
    }

    public static boolean isFollower(Entity npc) {
        if (npc == null) return false;
        return npc.getPersistentData().contains(FOLLOWER_KEY, Tag.TAG_COMPOUND);
    }

    @Nullable
    public static UUID getOwnerId(Entity npc) {
        if (npc == null) return null;
        CompoundTag pd = npc.getPersistentData();
        if (!pd.contains(FOLLOWER_KEY, Tag.TAG_COMPOUND)) return null;
        try {
            return UUID.fromString(pd.getCompound(FOLLOWER_KEY).getString("owner"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isWaiting(Entity npc) {
        if (npc == null) return false;
        CompoundTag pd = npc.getPersistentData();
        if (!pd.contains(FOLLOWER_KEY, Tag.TAG_COMPOUND)) return false;
        return "WAIT".equals(pd.getCompound(FOLLOWER_KEY).getString("state"));
    }

    public static void setWaiting(LivingEntity npc, boolean wait) {
        CompoundTag pd = npc.getPersistentData();
        if (!pd.contains(FOLLOWER_KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag f = pd.getCompound(FOLLOWER_KEY);
        f.putString("state", wait ? "WAIT" : "FOLLOW");
        pd.put(FOLLOWER_KEY, f);
    }

    public static void recruit(ServerPlayer player, LivingEntity npc) {
        if (!npc.isAlive()) return;
        UUID owner = getOwnerId(npc);
        if (owner != null && !owner.equals(player.getUUID())) return; // someone else's follower
        // Dismiss any existing follower (an unloaded one lets go when it next meets the owner, see validate)
        UUID existing = followerOf(player);
        if (existing != null && !existing.equals(npc.getUUID()) && player.getServer() != null) {
            for (ServerLevel sl : player.getServer().getAllLevels()) {
                Entity old = sl.getEntity(existing);
                if (old instanceof LivingEntity livingOld && isFollowerOf(livingOld, player)) {
                    dismiss(player, livingOld);
                    break;
                }
            }
        }

        CompoundTag f = new CompoundTag();
        f.putString("owner", player.getStringUUID());
        f.putString("owner_name", player.getName().getString());
        f.putString("state", "FOLLOW");
        npc.getPersistentData().put(FOLLOWER_KEY, f);
        // no longer a temporary encounter NPC: never despawned
        npc.getPersistentData().remove(Encounters.TAG);

        setFollowerOf(player, npc.getUUID());

        if (npc instanceof NpcEntity n) {
            n.setLeader(player.getUUID());
            n.setDestination(null);
            n.setPersistenceRequired();
        }

        rescale(npc, player, true);

        Notifier.message(player, Component.translatable("society.skycraft.follower.recruited", npc.getDisplayName()));
        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.3f);
    }

    public static void dismiss(ServerPlayer player, LivingEntity npc) {
        release(npc);
        if (npc.getUUID().equals(followerOf(player))) setFollowerOf(player, null);
        Notifier.message(player, Component.translatable("society.skycraft.follower.dismissed", npc.getDisplayName()));
    }

    /**
     * Scales a follower with the larger of its owner's level and the region's level. Called on recruit and
     * periodically while following; only touches the attributes when that level changed (keeps the health ratio).
     */
    public static void rescale(LivingEntity npc, Player owner, boolean fullHeal) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        CompoundTag pd = npc.getPersistentData();
        if (!pd.contains(FOLLOWER_KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag f = pd.getCompound(FOLLOWER_KEY);
        int lvl = Math.max(Leveling.regionLevel(level, npc.blockPosition()), SkyData.get(owner).getLevel());
        if (!fullHeal && f.getInt("lvl") == lvl) return;
        f.putInt("lvl", lvl);
        pd.put(FOLLOWER_KEY, f);

        double baseHp = 30.0 + lvl * 1.5;
        double baseDmg = 4.0 + lvl * 0.2;
        double baseArmor = 2.0 + lvl * 0.3;
        float ratio = npc.getMaxHealth() > 0 ? npc.getHealth() / npc.getMaxHealth() : 1f;

        AttributeInstance hp = npc.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(baseHp);

        AttributeInstance dmg = npc.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(baseDmg);

        AttributeInstance arm = npc.getAttribute(Attributes.ARMOR);
        if (arm != null) arm.setBaseValue(baseArmor);

        npc.setHealth(fullHeal ? npc.getMaxHealth() : Math.max(1f, npc.getMaxHealth() * ratio));
    }

    /**
     * Whether a follower should fight {@code target} for its owner: someone who hurt the owner, or whom the owner
     * hit, within the last 10 s. Never players, other followers, the owner's pets or the follower's allies.
     */
    public static boolean isDefendTarget(LivingEntity npc, Player owner, @Nullable LivingEntity target) {
        if (target == null || !target.isAlive() || target == npc || target == owner) return false;
        if (target instanceof Player || isFollower(target) || target.isAlliedTo(npc) || target.isAlliedTo(owner)) return false;
        if (target instanceof net.minecraft.world.entity.TamableAnimal pet && owner.getUUID().equals(pet.getOwnerUUID())) return false;
        if (target.distanceToSqr(npc) > 24 * 24) return false;
        if (target == owner.getLastHurtByMob() && owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200) return true;
        return target == owner.getLastHurtMob() && owner.tickCount - owner.getLastHurtMobTimestamp() <= 200;
    }

    /** The enemy a follower should engage for its owner right now, or null. */
    @Nullable
    public static LivingEntity defendTarget(LivingEntity npc, Player owner) {
        if (isDefendTarget(npc, owner, owner.getLastHurtByMob())) return owner.getLastHurtByMob();
        if (isDefendTarget(npc, owner, owner.getLastHurtMob())) return owner.getLastHurtMob();
        return null;
    }

    /** Called when a follower dies: everything the player gave them drops (equipped gear drops via drop chances). */
    public static void onDeath(LivingEntity npc) {
        if (npc.level().isClientSide) return;
        SimpleContainer inv = getFollowerInventory(npc);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) npc.spawnAtLocation(s);
        }
        npc.getPersistentData().remove(INVENTORY_KEY);
        UUID owner = getOwnerId(npc);
        if (owner != null && npc.level() instanceof ServerLevel sl) {
            Player p = sl.getServer().getPlayerList().getPlayer(owner);
            if (p != null && npc.getUUID().equals(followerOf(p))) setFollowerOf(p, null);
        }
    }

    public static SimpleContainer getFollowerInventory(LivingEntity npc) {
        SimpleContainer container = new SimpleContainer(INVENTORY_SIZE);
        CompoundTag pd = npc.getPersistentData();
        if (pd.contains(INVENTORY_KEY, Tag.TAG_LIST)) {
            ListTag list = pd.getList(INVENTORY_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag itemTag = list.getCompound(i);
                int slot = itemTag.getInt("Slot");
                if (slot >= 0 && slot < INVENTORY_SIZE) {
                    container.setItem(slot, ItemStack.of(itemTag));
                }
            }
        }
        return container;
    }

    public static void saveFollowerInventory(LivingEntity npc, Container container) {
        ListTag list = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putInt("Slot", i);
                stack.save(itemTag);
                list.add(itemTag);
            }
        }
        npc.getPersistentData().put(INVENTORY_KEY, list);
    }

    // ------------------------------------------------------------------ gear

    private static double armorScore(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem a ? a.getDefense() + a.getToughness() : -1;
    }

    private static boolean isMelee(ItemStack stack) {
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem;
    }

    private static boolean isRanged(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        double dmg = 0;
        for (var mod : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) dmg += mod.getAmount();
        if (isMelee(stack)) return 10 + dmg; // any real weapon beats bare hands and tools
        if (isRanged(stack)) return 5;       // used only when no melee weapon was given
        return -1;
    }

    /** Puts {@code stack} into the follower's bags, or drops it at their feet if they are full. */
    private static void stow(LivingEntity npc, Container container, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, stack);
                return;
            }
        }
        npc.spawnAtLocation(stack);
    }

    /** Swaps {@code best} (moved out of the bags) into {@code slot}; given gear goes back into the bags, native gear aside. */
    private static void equip(NpcEntity npc, Container container, EquipmentSlot slot, int from) {
        ItemStack chosen = container.removeItem(from, 1);
        if (chosen.isEmpty()) return;
        ItemStack old = npc.getItemBySlot(slot);
        boolean oldGiven = npc.isGivenGear(slot);
        npc.setItemSlot(slot, chosen);
        npc.setDropChance(slot, 2.0f); // given gear always drops on death, undamaged
        if (old.isEmpty()) return;
        if (oldGiven) {
            stow(npc, container, old);
        } else {
            CompoundTag nat = npc.getPersistentData().getCompound(NATIVE_KEY);
            if (!nat.contains(slot.getName())) nat.put(slot.getName(), old.save(new CompoundTag()));
            npc.getPersistentData().put(NATIVE_KEY, nat);
        }
    }

    /** Moves (never copies) the best armor and weapon from the follower's bags into their equipment slots. */
    private static void autoEquipBestGear(LivingEntity entity, Container container) {
        if (!(entity instanceof NpcEntity npc)) return;
        for (EquipmentSlot slot : ARMOR) {
            int best = -1;
            double bestScore = armorScore(npc.getItemBySlot(slot));
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot && armorScore(s) > bestScore) {
                    best = i;
                    bestScore = armorScore(s);
                }
            }
            if (best >= 0) equip(npc, container, slot, best);
        }
        int best = -1;
        double bestScore = weaponScore(npc.getMainHandItem());
        for (int i = 0; i < container.getContainerSize(); i++) {
            double score = weaponScore(container.getItem(i));
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        if (best >= 0) equip(npc, container, EquipmentSlot.MAINHAND, best);
        restoreNative(npc);
    }

    /** Empty slots get the follower's own gear back. */
    private static void restoreNative(NpcEntity npc) {
        CompoundTag nat = npc.getPersistentData().getCompound(NATIVE_KEY);
        if (nat.isEmpty()) return;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!nat.contains(slot.getName()) || !npc.getItemBySlot(slot).isEmpty()) continue;
            npc.setItemSlot(slot, ItemStack.of(nat.getCompound(slot.getName())));
            npc.setDropChance(slot, 0.085f);
            nat.remove(slot.getName());
        }
        if (nat.isEmpty()) npc.getPersistentData().remove(NATIVE_KEY);
        else npc.getPersistentData().put(NATIVE_KEY, nat);
    }

    /** Opening the trade screen takes off the gear the player gave, so it can be taken back; closing re-equips. */
    private static void unequipGiven(LivingEntity entity, Container container) {
        if (!(entity instanceof NpcEntity npc)) return;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!npc.isGivenGear(slot) || npc.getItemBySlot(slot).isEmpty()) continue;
            int free = -1;
            for (int i = 0; i < container.getContainerSize() && free < 0; i++) if (container.getItem(i).isEmpty()) free = i;
            if (free < 0) break;
            container.setItem(free, npc.getItemBySlot(slot));
            npc.setItemSlot(slot, ItemStack.EMPTY);
            npc.setDropChance(slot, 0.085f);
        }
    }

    private static boolean canTrade(LivingEntity npc, Player player) {
        return npc.isAlive() && !npc.isRemoved() && npc.level() == player.level()
                && npc.distanceToSqr(player) <= 8 * 8 && isFollowerOf(npc, player);
    }

    public static void openTrade(ServerPlayer player, LivingEntity npc) {
        if (!canTrade(npc, player)) return;
        SimpleContainer container = getFollowerInventory(npc);
        unequipGiven(npc, container);
        saveFollowerInventory(npc, container);
        container.addListener(c -> saveFollowerInventory(npc, c));
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInv, p) -> new ChestMenu(MenuType.GENERIC_9x3, containerId, playerInv, container, 3) {
                    @Override
                    public boolean stillValid(Player pl) {
                        return canTrade(npc, pl);
                    }

                    @Override
                    public void clicked(int slot, int button, ClickType type, Player pl) {
                        // the NPC may have died this tick (its bags already dropped): no more taking
                        if (!canTrade(npc, pl)) return;
                        super.clicked(slot, button, type, pl);
                    }

                    @Override
                    public void removed(Player pl) {
                        super.removed(pl);
                        if (!npc.isAlive() || npc.isRemoved()) return;
                        autoEquipBestGear(npc, container);
                        saveFollowerInventory(npc, container);
                    }
                },
                Component.translatable("society.skycraft.follower.trade_title", npc.getDisplayName())
        ));
    }

    // ------------------------------------------------------------------ Dialogue

    static void addOptions(ServerPlayer player, LivingEntity entity, List<DialogueOption> out) {
        if (!(entity instanceof NpcEntity npc) || !npc.isAlive() || npc.isPrisoner() || npc.isHostileTo(player)) return;

        if (isFollowerOf(npc, player)) {
            boolean wait = isWaiting(npc);
            if (wait) {
                out.add(new DialogueOption("follower.follow", Component.translatable("dialogue.skycraft.follower.follow"), 50, (p, n) -> {
                    setWaiting((LivingEntity) n, false);
                    Dialogue.open(p, (LivingEntity) n, Component.translatable("society.skycraft.say.follower_follow"));
                }));
            } else {
                out.add(new DialogueOption("follower.wait", Component.translatable("dialogue.skycraft.follower.wait"), 50, (p, n) -> {
                    setWaiting((LivingEntity) n, true);
                    Dialogue.open(p, (LivingEntity) n, Component.translatable("society.skycraft.say.follower_wait"));
                }));
            }
            out.add(new DialogueOption("follower.trade", Component.translatable("dialogue.skycraft.follower.trade"), 51, (p, n) -> {
                openTrade(p, (LivingEntity) n);
            }));
            out.add(new DialogueOption("follower.dismiss", Component.translatable("dialogue.skycraft.follower.dismiss"), 52, (p, n) -> {
                dismiss(p, (LivingEntity) n);
                Dialogue.open(p, (LivingEntity) n, Component.translatable("society.skycraft.say.follower_dismiss"));
            }));
            return;
        }
        // someone else's follower: only their owner talks them into anything
        if (isFollower(npc)) return;

        // Potential recruitment
        NpcRole role = npc.role();
        boolean canPersuade = !persuadeOnCooldown(npc, player);
        if (role == NpcRole.ADVENTURER) {
            out.add(new DialogueOption("follower.hire", Component.translatable("dialogue.skycraft.follower.hire", HIRELING_COST), 60, (p, n) -> {
                if (isFollower(n)) return;
                if (!Currency.take(p, HIRELING_COST)) {
                    Dialogue.open(p, (LivingEntity) n, Component.translatable("message.skycraft.not_enough_gold"));
                    return;
                }
                recruit(p, (LivingEntity) n);
                Dialogue.open(p, (LivingEntity) n, Component.translatable("society.skycraft.say.follower_accept"));
            }));
            if (canPersuade) {
                out.add(new DialogueOption("follower.persuade", Component.translatable("dialogue.skycraft.follower.persuade"), 61,
                        (p, n) -> persuade(p, (LivingEntity) n, 45)));
            }
        } else if (role == NpcRole.HOUSECARL) {
            int rep = Reputation.get(player, Reputation.GUARDS);
            int townRep = Reputation.get(player, Reputation.TOWNSFOLK);
            if (rep >= 20 || townRep >= 30) {
                out.add(new DialogueOption("follower.housecarl", Component.translatable("dialogue.skycraft.follower.housecarl"), 60, (p, n) -> {
                    recruit(p, (LivingEntity) n);
                    Dialogue.open(p, (LivingEntity) n, Component.translatable("society.skycraft.say.housecarl_accept"));
                }));
            }
        } else if (role == NpcRole.HUNTER || role == NpcRole.MAGE) {
            int townRep = Reputation.get(player, Reputation.TOWNSFOLK);
            if (townRep >= 30) {
                out.add(new DialogueOption("follower.friend", Component.translatable("dialogue.skycraft.follower.friend"), 60, (p, n) -> {
                    recruit(p, (LivingEntity) n);
                    Dialogue.open(p, (LivingEntity) n, Component.translatable("society.skycraft.say.follower_accept"));
                }));
            } else if (canPersuade) {
                out.add(new DialogueOption("follower.persuade_friend", Component.translatable("dialogue.skycraft.follower.persuade"), 61,
                        (p, n) -> persuade(p, (LivingEntity) n, 40)));
            }
        }
    }

    private static boolean persuadeOnCooldown(LivingEntity npc, Player player) {
        CompoundTag fails = npc.getPersistentData().getCompound(PERSUADE_FAIL_KEY);
        String key = player.getStringUUID();
        return fails.contains(key) && npc.level().getGameTime() - fails.getLong(key) < PERSUADE_COOLDOWN;
    }

    /** One try per NPC per player per day; the Speech XP is only earned the first time this NPC is won over. */
    private static void persuade(ServerPlayer player, LivingEntity npc, int difficulty) {
        if (isFollower(npc) || persuadeOnCooldown(npc, player)) return;
        CompoundTag pd = npc.getPersistentData();
        CompoundTag xp = pd.getCompound(PERSUADE_XP_KEY);
        String key = player.getStringUUID();
        if (SpeechChecks.checkPersuade(player, difficulty, !xp.getBoolean(key))) {
            xp.putBoolean(key, true);
            pd.put(PERSUADE_XP_KEY, xp);
            recruit(player, npc);
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.follower_accept"));
        } else {
            CompoundTag fails = pd.getCompound(PERSUADE_FAIL_KEY);
            fails.putLong(key, npc.level().getGameTime());
            pd.put(PERSUADE_FAIL_KEY, fails);
            Dialogue.open(player, npc, Component.translatable("society.skycraft.say.follower_refuse"));
        }
    }
}
