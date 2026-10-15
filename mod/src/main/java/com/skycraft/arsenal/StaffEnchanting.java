package com.skycraft.arsenal;

import com.skycraft.arsenal.item.EnchantedStaffItem;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.SpellSounds;
import com.skycraft.magic.spell.Spells;
import com.skycraft.skills.Progression;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Staff enchanting (Dragonborn): at a Staff Enchanter, bind any spell you know into an Unenchanted Staff, powered by a
 * filled soul gem. The staff gets charges from the soul's size (plus a little from Enchanting), and making one trains
 * Enchanting. Shared by the server action and the client screen ({@link #problem}).
 */
public final class StaffEnchanting {
    /** Spells that make no sense on a staff: wards (held up by your own hands), teleports and Equilibrium. */
    private static final Set<String> NOT_ON_STAVES = Set.of("mark", "recall", "divine_intervention", "almsivi_intervention", "equilibrium",
            "telekinesis");

    private StaffEnchanting() {}

    public static boolean allowed(Spell spell) {
        return spell != null && !spell.isCustom() && !SpellCasting.WARDS.contains(spell.id) && !NOT_ON_STAVES.contains(spell.id);
    }

    /** Index in the player's inventory of the first unenchanted staff, or -1. */
    public static int findStaff(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(ArsenalItems.UNENCHANTED_STAFF.get())) return i;
        }
        // the overflow pack: a virtual slot id (com.skycraft.inventory.Pack)
        int packed = com.skycraft.inventory.Pack.find(player, s -> s.is(ArsenalItems.UNENCHANTED_STAFF.get()));
        return packed >= 0 ? com.skycraft.inventory.Pack.VIRTUAL_BASE + packed : -1;
    }

    /** Index of the filled soul gem with the biggest soul (Azura's and the Black Star are emptied, not consumed), or -1. */
    public static int findGem(Player player) {
        Inventory inv = player.getInventory();
        int best = -1, bestSoul = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(ArsenalTags.SOUL_GEMS) || s.getTag() == null) continue;
            int soul = s.getTag().getInt("soul");
            if (soul > bestSoul) {
                bestSoul = soul;
                best = i;
            }
        }
        java.util.List<ItemStack> packed = com.skycraft.inventory.Pack.entries(player);
        for (int i = 0; i < packed.size(); i++) {
            ItemStack s = packed.get(i);
            if (s.isEmpty() || !s.is(ArsenalTags.SOUL_GEMS) || s.getTag() == null) continue;
            int soul = s.getTag().getInt("soul");
            if (soul > bestSoul) {
                bestSoul = soul;
                best = com.skycraft.inventory.Pack.VIRTUAL_BASE + i;
            }
        }
        return best;
    }

    public static int soulAt(Player player, int slot) {
        if (slot < 0) return 0;
        ItemStack s = com.skycraft.inventory.Pack.stackAt(player, slot);
        return s.getTag() == null ? 0 : s.getTag().getInt("soul");
    }

    /** Charges a staff gets from a soul of size 1 (petty) .. 5 (grand/black), plus Enchanting skill. */
    public static int chargesFor(Player player, int soul) {
        int enchanting = SkyData.get(player).getSkill(Skill.ENCHANTING);
        return 6 + soul * 6 + enchanting / 10;
    }

    /** Why the player can't enchant this spell right now (lang key), or null. */
    @Nullable
    public static String problem(Player player, @Nullable Spell spell) {
        if (spell == null || !allowed(spell)) return "staff_enchanter.skycraft.problem.spell";
        if (!MagicData.knows(player, spell.id) && !player.isCreative()) return "staff_enchanter.skycraft.problem.unknown";
        if (findStaff(player) < 0) return "staff_enchanter.skycraft.problem.staff";
        if (findGem(player) < 0) return "staff_enchanter.skycraft.problem.gem";
        return null;
    }

    public static boolean nearEnchanter(ServerPlayer player, BlockPos pos) {
        return player.level().isLoaded(pos) && player.level().getBlockState(pos).is(ArsenalBlocks.STAFF_ENCHANTER.get())
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 8 * 8;
    }

    /** Server action (packet {@code MagicPackets.StaffEnchant}). */
    public static void enchant(ServerPlayer player, BlockPos pos, String spellId) {
        if (!nearEnchanter(player, pos)) return;
        Spell spell = Spells.byId(spellId);
        String problem = problem(player, spell);
        if (problem != null) {
            Notifier.message(player, Component.translatable(problem));
            return;
        }
        Inventory inv = player.getInventory();
        int staffSlot = findStaff(player);
        int gemSlot = findGem(player);
        int[] resolved = com.skycraft.inventory.Pack.resolveAll(player, new int[]{staffSlot, gemSlot});
        if (resolved == null) return;
        staffSlot = resolved[0];
        gemSlot = resolved[1];
        int soul = soulAt(player, gemSlot);
        ItemStack gem = inv.getItem(gemSlot);
        String gemId = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(gem.getItem()));
        if (gemId.endsWith(":azuras_star") || gemId.endsWith(":black_star")) {
            // the reusable stars are emptied, not used up
            gem.getOrCreateTag().remove("soul");
            gem.getOrCreateTag().remove("skycraft_value");
            if (gem.getOrCreateTag().isEmpty()) gem.setTag(null);
        } else {
            gem.shrink(1);
        }
        ItemStack rawStaff = inv.getItem(staffSlot).copy();
        inv.getItem(staffSlot).shrink(1);
        ItemStack staff = new ItemStack(ArsenalItems.ENCHANTED_STAFF.get());
        com.skycraft.loot.affix.ItemAffixes.copyAffixes(rawStaff, staff);
        EnchantedStaffItem.bind(staff, spell, chargesFor(player, soul));
        com.skycraft.inventory.Pack.giveOrDrop(player, staff);
        inv.setChanged();
        Progression.addSkillXp(player, Skill.ENCHANTING, 0.6f + 0.15f * soul);
        SkyData.get(player).addStat("staves_enchanted", 1);
        Vec3 c = Vec3.atCenterOf(pos).add(0, 0.7, 0);
        MagicFx.sendNear(player.serverLevel(), c, 48, MagicFx.STREAM, spell.element, c, c, player.getId(), 40);
        SpellSounds.world(player.serverLevel(), c, SpellSounds.Slot.STAFF_ENCHANT, 1f, 0.8f);
        Notifier.title(player, Component.translatable("staff_enchanter.skycraft.made"), staff.getHoverName());
    }
}
