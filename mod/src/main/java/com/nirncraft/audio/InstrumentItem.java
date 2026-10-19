package com.nirncraft.audio;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Playable cultural musical instrument carried by travelling and tavern bards across Tamriel, and usable by players
 * via right-click to play a short modal phrase in the instrument's regional scale.
 */
public class InstrumentItem extends Item {
    public enum Pose {
        STRUM,
        FLUTE,
        DRUM,
        HORN,
        HARP
    }

    private final String culture;
    private final Pose pose;
    private final Supplier<Holder<SoundEvent>> primarySound;
    @Nullable
    private final Supplier<Holder<SoundEvent>> accentSound;
    private final float pitchBase;
    private final int[] scale;

    public InstrumentItem(Properties properties,
                          String culture,
                          Pose pose,
                          Supplier<Holder<SoundEvent>> primarySound,
                          @Nullable Supplier<Holder<SoundEvent>> accentSound,
                          float pitchBase,
                          int[] scale) {
        super(properties);
        this.culture = culture;
        this.pose = pose;
        this.primarySound = primarySound;
        this.accentSound = accentSound;
        this.pitchBase = pitchBase;
        this.scale = scale.clone();
    }

    public String culture() {
        return culture;
    }

    public Pose pose() {
        return pose;
    }

    public Holder<SoundEvent> sound() {
        return primarySound.get();
    }

    @Nullable
    public Holder<SoundEvent> accentSound() {
        return accentSound != null ? accentSound.get() : null;
    }

    public float pitchBase() {
        return pitchBase;
    }

    public int[] scale() {
        return scale.clone();
    }

    public float notePitch(int semitone) {
        float raw = (float) Math.pow(2.0, (semitone - 12) / 12.0) * pitchBase;
        return Mth.clamp(raw, 0.5f, 2.0f);
    }

    /** Plays a single note from a bard performance or player flourish at {@code (x, y, z)}. */
    public void playNote(Level level, double x, double y, double z, int semitone, int step) {
        float pitch = notePitch(semitone);
        SoundEvent main = sound().value();
        level.playSound(null, x, y, z, main, SoundSource.RECORDS, 0.95f, pitch);
        Holder<SoundEvent> accent = accentSound();
        if (accent != null && step % 3 == 0) {
            float accentPitch = Mth.clamp(pitch * (pose == Pose.DRUM ? 1.15f : 1.0f), 0.5f, 2.0f);
            level.playSound(null, x, y, z, accent.value(), SoundSource.RECORDS, 0.55f, accentPitch);
        }
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.NOTE, x, y + 1.25, z, 0, (semitone % 24) / 24.0, 0.0, 0.0, 1.0);
        }
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        RandomSource r = player.getRandom();
        // Map player look pitch (-90..90) onto the cultural scale index so looking up/down bends the phrase
        float lookFactor = Mth.clamp((90.0f - player.getXRot()) / 180.0f, 0.0f, 1.0f);
        int baseIdx = Mth.clamp(Math.round(lookFactor * (scale.length - 1)), 0, scale.length - 1);
        int secondIdx = Math.min(scale.length - 1, baseIdx + (r.nextBoolean() ? 2 : 1));
        int thirdIdx = Math.max(0, Math.min(scale.length - 1, baseIdx + (r.nextBoolean() ? 4 : -1)));

        if (!level.isClientSide()) {
            playNote(level, player.getX(), player.getY() + 1.0, player.getZ(), scale[baseIdx], 0);
            if (pose != Pose.HORN) {
                playNote(level, player.getX(), player.getY() + 1.0, player.getZ(), scale[secondIdx], 1);
                if (r.nextBoolean()) {
                    playNote(level, player.getX(), player.getY() + 1.0, player.getZ(), scale[thirdIdx], 3);
                }
            } else {
                Holder<SoundEvent> hornAccent = accentSound();
                if (hornAccent != null) {
                    level.playSound(null, player.getX(), player.getY() + 1.0, player.getZ(),
                            hornAccent.value(), SoundSource.RECORDS, 0.85f, notePitch(scale[baseIdx]));
                }
            }
        }

        player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        player.getCooldowns().addCooldown(player.getItemInHand(hand), pose == Pose.HORN ? 28 : 14);
        player.awardStat(Stats.ITEM_USED.get(this));
        return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return pose == Pose.HORN || pose == Pose.FLUTE ? ItemUseAnimation.TOOT_HORN : ItemUseAnimation.BOW;
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable(getDescriptionId() + ".desc")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
