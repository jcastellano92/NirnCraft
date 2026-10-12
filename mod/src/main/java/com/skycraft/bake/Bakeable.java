package com.skycraft.bake;

import java.io.IOException;

/**
 * One baked data set: {@code skycraft/baked/<name>.bin}, written by {@link Bake#bakeAll} and checked against a fresh
 * computation by {@link Bake#check}. {@code compute} must depend only on the map, the places and the code listed in
 * the bake's input hash (never on the world seed), and return identical bytes on every run.
 */
public record Bakeable(String name, Compute compute) {
    @FunctionalInterface
    public interface Compute {
        byte[] run() throws IOException;
    }
}
