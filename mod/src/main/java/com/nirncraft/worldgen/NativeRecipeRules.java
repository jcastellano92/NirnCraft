package com.nirncraft.worldgen;

import com.nirncraft.NirnCraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/** Authored removals must also override recipes supplied by NeoForge's higher mod-data pack. */
public final class NativeRecipeRules {
    private NativeRecipeRules() {}

    public static void addPacks(AddPackFindersEvent event) {
        event.addPackFinders(Identifier.fromNamespaceAndPath(NirnCraft.MODID, "resourcepacks/recipe_rules"),
                PackType.SERVER_DATA, Component.literal("NirnCraft recipe rules"), PackSource.BUILT_IN,
                true, Pack.Position.TOP);
    }
}
