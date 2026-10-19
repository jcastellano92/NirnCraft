package com.nirncraft.dialogue;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.BiConsumer;

/**
 * One line the player can choose in an NPC conversation.
 *
 * @param id      unique id within the conversation, e.g. {@code "economy.barter"}
 * @param label   what the player says, e.g. "What have you got for sale?"
 * @param order   sort order (lower first); Goodbye is always last
 * @param action  server-side effect when chosen. The dialogue screen closes first unless the action reopens it.
 * @param enabled false for a line that is shown but cannot be chosen (greyed out, not clickable): a room the player
 *                has already rented, an inn that is full
 */
public record DialogueOption(String id, Component label, int order, BiConsumer<ServerPlayer, LivingEntity> action, boolean enabled) {
    public DialogueOption(String id, Component label, int order, BiConsumer<ServerPlayer, LivingEntity> action) {
        this(id, label, order, action, true);
    }

    /** A line that is shown greyed out and cannot be chosen. */
    public static DialogueOption disabled(String id, Component label, int order) {
        return new DialogueOption(id, label, order, (p, n) -> { }, false);
    }
}
