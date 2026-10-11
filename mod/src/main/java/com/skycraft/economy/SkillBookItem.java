package com.skycraft.economy;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.economy.client.EconomyClient;
import com.skycraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A Skyrim skill book. Reading it for the first time raises its skill by one level; the book is never consumed,
 * and reading it again does nothing. Read books are tracked per character in {@code module("economy").books_read}.
 *
 * <p>Every skill has {@value #VOLUMES} books, like Skyrim: volume 1 is the plain item (the one merchants sell, tracked
 * by the skill id), volumes 2-5 carry the int NBT {@value #VOLUME_NBT}, have their own titles
 * ({@code item.skycraft.skill_book_<skill>.v<n>}) and are tracked as {@code <skill>#<n>}. They turn up in loot
 * ({@code skycraft:skill_book}); reading all five of a skill is a collection goal (the loot module's Journal page).</p>
 */
public class SkillBookItem extends Item {
    public static final String MODULE = "economy";
    public static final String READ_KEY = "books_read";
    public static final String VOLUME_NBT = "volume";
    public static final int VOLUMES = 5;

    private final Skill skill;

    public SkillBookItem(Skill skill, Properties props) {
        super(props);
        this.skill = skill;
    }

    public Skill skill() {
        return skill;
    }

    public static boolean hasRead(PlayerData data, Skill skill) {
        return hasRead(data, skill, 1);
    }

    public static boolean hasRead(PlayerData data, Skill skill, int volume) {
        String key = readKey(skill, volume);
        ListTag read = data.module(MODULE).getList(READ_KEY, Tag.TAG_STRING);
        for (int i = 0; i < read.size(); i++) {
            if (read.getString(i).equals(key)) return true;
        }
        return false;
    }

    private static String readKey(Skill skill, int volume) {
        return volume <= 1 ? skill.id() : skill.id() + "#" + volume;
    }

    /** The book's volume, 1..{@value #VOLUMES}. */
    public static int volume(ItemStack stack) {
        int v = stack.hasTag() ? stack.getTag().getInt(VOLUME_NBT) : 0;
        return Math.max(1, Math.min(VOLUMES, v));
    }

    @Override
    public Component getName(ItemStack stack) {
        int v = volume(stack);
        return v <= 1 ? super.getName(stack) : Component.translatable(getDescriptionId() + ".v" + v);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            PlayerData data = SkyData.get(sp);
            int volume = volume(stack);
            if (hasRead(data, skill, volume)) {
                Notifier.message(sp, Component.translatable("message.skycraft.economy.book_already_read"));
            } else {
                CompoundTag module = data.module(MODULE);
                ListTag read = module.getList(READ_KEY, Tag.TAG_STRING);
                read.add(StringTag.valueOf(readKey(skill, volume)));
                module.put(READ_KEY, read);
                data.addStat("skill_books_read", 1);
                data.markDirty();
                if (data.getSkill(skill) < Skill.MAX_LEVEL) {
                    Progression.increaseSkill(sp, skill, 1);
                } else {
                    Notifier.message(sp, Component.translatable("message.skycraft.economy.book_mastered", skill.displayName()));
                }
            }
            level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.skycraft.economy.skill_book", skill.displayName()).withStyle(ChatFormatting.GRAY));
        if (level != null && level.isClientSide) {
            int volume = volume(stack);
            Boolean read = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> EconomyClient.hasReadBook(skill, volume));
            if (Boolean.TRUE.equals(read)) {
                tooltip.add(Component.translatable("tooltip.skycraft.economy.skill_book_read").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }
    }
}
