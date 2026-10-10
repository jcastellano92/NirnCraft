package com.skycraft.client.screen;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads private state of vanilla screens we restyle (the death message of {@code DeathScreen}, a confirmation's
 * message, a progress screen's stage) by field TYPE and declaration order, so it works with both development and
 * production (SRG) names. Fields are looked up once per class; any failure just returns null and the caller draws
 * without that piece.
 */
public final class ScreenFields {
    private static final Map<String, List<Field>> CACHE = new ConcurrentHashMap<>();

    private ScreenFields() {}

    private static List<Field> fields(Class<?> owner, Class<?> type) {
        return CACHE.computeIfAbsent(owner.getName() + "|" + type.getName(), k -> {
            List<Field> out = new ArrayList<>();
            try {
                for (Field f : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    out.add(f);
                }
            } catch (Throwable ignored) {
                // inaccessible: no fields
            }
            return out;
        });
    }

    /** The {@code index}-th non-static field of {@code owner} holding a {@code type}, read from {@code instance}. */
    public static <T> T get(Object instance, Class<?> owner, Class<T> type, int index) {
        List<Field> list = fields(owner, type);
        if (index < 0 || index >= list.size()) return null;
        try {
            return type.cast(list.get(index).get(instance));
        } catch (Throwable t) {
            return null;
        }
    }

    public static <T> T first(Object instance, Class<?> owner, Class<T> type) {
        return get(instance, owner, type, 0);
    }

    public static Component component(Object instance, Class<?> owner, int index) {
        return get(instance, owner, Component.class, index);
    }
}
