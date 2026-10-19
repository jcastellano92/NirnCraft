package com.nirncraft.platform;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootTable;

/** Reloaded loot resolves against the caller's current world, never a retained global resolver. */
public final class LootTables {
    private LootTables() {}
    public static LootTable get(ServerLevel level, Identifier id) { return get(level, ResourceKey.create(Registries.LOOT_TABLE, id)); }
    public static LootTable get(ServerLevel level, ResourceKey<LootTable> id) { return level.getServer().reloadableRegistries().getLootTable(id); }
    public static java.util.Set<Identifier> keys(net.minecraft.server.MinecraftServer server) {
        var out = new java.util.TreeSet<Identifier>();
        server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElements().forEach(h -> out.add(h.key().identifier()));
        return out;
    }
}
