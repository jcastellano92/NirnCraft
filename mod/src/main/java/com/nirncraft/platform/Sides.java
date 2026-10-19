package com.nirncraft.platform;

import java.util.function.Supplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** Defers client-only class linkage until the matching physical distribution executes the action. */
public final class Sides {
    private Sides() {}
    public static void runOn(Dist side,Supplier<Runnable> action) {
        if(FMLEnvironment.getDist()==side) action.get().run();
    }
    public static <T> T callOn(Dist side, Supplier<Supplier<T>> action) {
        return FMLEnvironment.getDist() == side ? action.get().get() : null;
    }
}
