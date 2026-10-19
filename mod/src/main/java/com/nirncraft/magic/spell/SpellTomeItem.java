package com.nirncraft.magic.spell;

import com.nirncraft.core.Notifier;
import com.nirncraft.magic.MagicData;
import com.nirncraft.magic.MagicFx;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A spell tome: read it (use) to learn its spell. The tome is consumed. Spells cannot be crafted. */
public class SpellTomeItem extends Item {
    private final String spellId;

    public SpellTomeItem(String spellId, Properties props) {
        super(props);
        this.spellId = spellId;
    }

    @Nullable
    public Spell spell() {
        return Spells.byId(spellId);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Spell spell = spell();
        if (spell == null) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ServerPlayer sp = (ServerPlayer) player;
        return readStack(sp, stack) ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }

    /** Reads this spell tome directly from any inventory slot without moving it into the player's hand. */
    public boolean readStack(ServerPlayer sp, ItemStack stack) {
        Spell spell = spell();
        if (spell == null) return false;
        Level level = sp.level();
        if (MagicData.knows(sp, spell.id)) {
            Notifier.message(sp, Component.translatable("message.nirncraft.spell_known", spell.displayName()));
            return false;
        }
        if (spell.tier != Tier.NOVICE && !sp.isCreative()) {
            com.nirncraft.core.PlayerData data = com.nirncraft.core.SkyData.get(sp);
            if (!com.nirncraft.quest.Factions.isMember(data, com.nirncraft.quest.Faction.COLLEGE)) {
                Notifier.message(sp, Component.translatable("message.nirncraft.tome_requires_college", spell.school.displayName()));
                return false;
            }
            int collegeRank = com.nirncraft.quest.Factions.rank(data, com.nirncraft.quest.Faction.COLLEGE);
            String schoolQuestKey = "college_" + spell.school.id();
            boolean completedSchoolQuest = data.module("quest").getBooleanOr(schoolQuestKey + "_completed", false);
            int skillLevel = data.getSkill(spell.school.skill);
            boolean allowed = switch (spell.tier) {
                case NOVICE -> true;
                case APPRENTICE -> skillLevel >= 25 || collegeRank >= 1;
                case ADEPT -> (collegeRank >= 1 && skillLevel >= 40) || completedSchoolQuest;
                case EXPERT -> (collegeRank >= 2 && skillLevel >= 65) || (completedSchoolQuest && skillLevel >= 60);
                case MASTER -> (collegeRank >= 3 && skillLevel >= 85) || (completedSchoolQuest && skillLevel >= 80);
            };
            if (!allowed) {
                Notifier.message(sp, Component.translatable("message.nirncraft.tome_requires_school_progress",
                        spell.school.displayName(), spell.tier.displayName()));
                return false;
            }
        }
        MagicData.learn(sp, spell.id);
        if (!sp.isCreative()) stack.shrink(1);
        Notifier.title(sp, Component.translatable("notify.nirncraft.spell_learned"), spell.displayName());
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 0.9f);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 1.1f);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4f, 1.6f);
        MagicFx.send(sp, MagicFx.AURA, spell.element, sp.position(), sp.position(), sp.getId(), 4);
        sp.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this));
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        Spell spell = spell();
        if (spell == null) return;
        tooltip.accept(spell.displayName().copy().withStyle(ChatFormatting.WHITE));
        tooltip.accept(Component.translatable("tooltip.nirncraft.spell_school", spell.school.displayName(), spell.tier.displayName())
                .withStyle(s -> s.withColor(spell.school.color)));
        String unit = spell.isConcentration() ? "tooltip.nirncraft.spell_cost_per_second" : "tooltip.nirncraft.spell_cost";
        tooltip.accept(Component.translatable(unit, Math.round(spell.cost)).withStyle(ChatFormatting.AQUA));
        if (spell.chargeTicks > 0) tooltip.accept(Component.translatable("tooltip.nirncraft.spell_charged").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.accept(spell.description().copy().withStyle(ChatFormatting.GRAY));
        if (spell.tier != Tier.NOVICE) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.spell_tome_requires_college", spell.school.displayName())
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }
        tooltip.accept(Component.translatable("tooltip.nirncraft.spell_tome_hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        Spell spell = spell();
        return spell != null && spell.tier.ordinal() >= Tier.EXPERT.ordinal();
    }
}
