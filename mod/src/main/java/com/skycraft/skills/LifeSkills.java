package com.skycraft.skills;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crime.Ownership;
import com.skycraft.dig.DiggingRules;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.perk.Perks;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.TickTask;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.common.Tags;

import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Mining, Woodcutting, Fishing and Hunting: XP gains and perk effects. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class LifeSkills {
    /** Extra-valuable ores (Skyrim ebony, moonstone, etc.) give more Mining XP. */
    public static final TagKey<Block> VALUABLE_ORES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Skycraft.MODID, "valuable_ores"));
    private static final ThreadLocal<Boolean> CHAIN_BREAKING = ThreadLocal.withInitial(() -> false);

    private LifeSkills() {}

    // ------------------------------------------------------------------ mining & woodcutting

    public static float oreValue(BlockState state) {
        if (state.is(Tags.Blocks.ORES_NETHERITE_SCRAP)) return 25;
        if (state.is(VALUABLE_ORES)) return 14;
        if (state.is(Tags.Blocks.ORES_DIAMOND) || state.is(Tags.Blocks.ORES_EMERALD)) return 15;
        if (state.is(Tags.Blocks.ORES_GOLD)) return 8;
        if (state.is(Tags.Blocks.ORES_IRON) || state.is(Tags.Blocks.ORES_LAPIS)) return 6;
        if (state.is(Tags.Blocks.ORES_REDSTONE)) return 5;
        if (state.is(Tags.Blocks.ORES_COPPER) || state.is(Tags.Blocks.ORES_QUARTZ)) return 4;
        if (state.is(Tags.Blocks.ORES_COAL)) return 3;
        if (state.is(Tags.Blocks.ORES)) return 7;
        return 0;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player) || player.isCreative()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        BlockPos pos = event.getPos();
        var rnd = player.getRandom();

        float ore = oreValue(state);
        if (ore > 0) {
            Progression.addSkillXp(player, Skill.MINING, ore);
            SkyData.get(player).addStat("ores_mined", 1);
            int prospector = Perks.rank(player, "mining.prospector");
            if (prospector > 0 && rnd.nextFloat() < 0.1f * prospector) {
                for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, player.getMainHandItem())) {
                    Block.popResource(level, pos, drop);
                }
            }
            if (!CHAIN_BREAKING.get() && player.isCrouching() && Perks.has(player, "mining.vein_miner")) {
                chainBreak(player, level, pos, state.getBlock(), 24, false);
            }
        } else if (state.is(DiggingRules.HARD) || state.is(DiggingRules.DEEP)) {
            Progression.addSkillXp(player, Skill.MINING, 0.3f);
            if (Perks.has(player, "mining.geologist") && rnd.nextFloat() < 0.02f) {
                ItemStack gem = switch (rnd.nextInt(4)) {
                    case 0 -> new ItemStack(Items.AMETHYST_SHARD);
                    case 1 -> new ItemStack(Items.LAPIS_LAZULI, 2);
                    case 2 -> new ItemStack(Items.EMERALD);
                    default -> new ItemStack(Items.DIAMOND);
                };
                Block.popResource(level, pos, gem);
            }
        } else if (state.is(DiggingRules.SOFT)) {
            Progression.addSkillXp(player, Skill.MINING, 0.15f);
        } else if (state.is(BlockTags.LOGS)) {
            Progression.addSkillXp(player, Skill.WOODCUTTING, 2.5f);
            SkyData.get(player).addStat("logs_chopped", 1);
            // Only natural trees fall (and get replanted): never log cabins, village houses or player builds.
            FellingJob tree = CHAIN_BREAKING.get() ? null : scanNaturalTree(player, level, pos);
            if (tree != null) {
                if (level.getBlockState(pos.below()).is(BlockTags.DIRT)) {
                    Block sapling = saplingFor(state);
                    com.skycraft.magic.MagicScheduler.schedule(level.getServer(), 1, ignored263 -> {
                        if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(BlockTags.DIRT)) {
                            level.setBlock(pos, sapling.defaultBlockState(), 3);
                        }
                    });
                }
                // Entire tree crumbles and falls over in chop direction, a few blocks per tick
                FELLING.add(tree);
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, net.minecraft.sounds.SoundSource.BLOCKS, 1.2f, 0.7f);
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.CHEST_OPEN, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 0.5f);
            }
        } else if (state.is(BlockTags.LEAVES) && Perks.has(player, "woodcutting.forager") && rnd.nextFloat() < 0.1f) {
            Block.popResource(level, pos, new ItemStack(rnd.nextBoolean() ? Items.APPLE : Items.STICK));
        }
    }

    private static Block saplingFor(BlockState logState) {
        Block b = logState.getBlock();
        if (b == Blocks.SPRUCE_LOG || b == Blocks.SPRUCE_WOOD || b == Blocks.STRIPPED_SPRUCE_LOG || b == Blocks.STRIPPED_SPRUCE_WOOD) return Blocks.SPRUCE_SAPLING;
        if (b == Blocks.BIRCH_LOG || b == Blocks.BIRCH_WOOD || b == Blocks.STRIPPED_BIRCH_LOG || b == Blocks.STRIPPED_BIRCH_WOOD) return Blocks.BIRCH_SAPLING;
        if (b == Blocks.DARK_OAK_LOG || b == Blocks.DARK_OAK_WOOD || b == Blocks.STRIPPED_DARK_OAK_LOG || b == Blocks.STRIPPED_DARK_OAK_WOOD) return Blocks.DARK_OAK_SAPLING;
        if (b == Blocks.ACACIA_LOG || b == Blocks.ACACIA_WOOD || b == Blocks.STRIPPED_ACACIA_LOG || b == Blocks.STRIPPED_ACACIA_WOOD) return Blocks.ACACIA_SAPLING;
        if (b == Blocks.JUNGLE_LOG || b == Blocks.JUNGLE_WOOD || b == Blocks.STRIPPED_JUNGLE_LOG || b == Blocks.STRIPPED_JUNGLE_WOOD) return Blocks.JUNGLE_SAPLING;
        if (b == Blocks.CHERRY_LOG || b == Blocks.CHERRY_WOOD || b == Blocks.STRIPPED_CHERRY_LOG || b == Blocks.STRIPPED_CHERRY_WOOD) return Blocks.CHERRY_SAPLING;
        if (b == Blocks.MANGROVE_LOG || b == Blocks.MANGROVE_WOOD || b == Blocks.STRIPPED_MANGROVE_LOG || b == Blocks.STRIPPED_MANGROVE_WOOD) return Blocks.MANGROVE_PROPAGULE;
        return Blocks.OAK_SAPLING;
    }

    // ------------------------------------------------------------------ tree felling

    /** A tree with more connected logs than this is not treated as a tree (log builds, giant structures). */
    private static final int MAX_TREE_LOGS = 200;
    /** Leaves crumbled with the tree (the rest decays naturally once the logs are gone). */
    private static final int MAX_TREE_LEAVES = 160;
    /** Natural leaves near the top of the trunk needed to call it a tree. */
    private static final int MIN_CROWN_LEAVES = 4;
    /** Logs turned into falling blocks, and leaves crumbled, per tick (per job). */
    private static final int LOGS_PER_TICK = 12;
    private static final int LEAVES_PER_TICK = 24;

    /** Trees currently falling, processed a few blocks per server tick. */
    private static final List<FellingJob> FELLING = new ArrayList<>();

    private static final class FellingJob {
        final ServerLevel level;
        final ServerPlayer player;
        final BlockPos cutPos;
        final Deque<BlockPos> logs;
        final Deque<BlockPos> leaves;
        final double fallX;
        final double fallZ;

        FellingJob(ServerLevel level, ServerPlayer player, BlockPos cutPos, List<BlockPos> logs, List<BlockPos> leaves) {
            this.level = level;
            this.player = player;
            this.cutPos = cutPos;
            this.logs = new ArrayDeque<>(logs);
            this.leaves = new ArrayDeque<>(leaves);
            // Direction vector the tree falls towards (away from player)
            double lookX = player.getLookAngle().x;
            double lookZ = player.getLookAngle().z;
            double mag = Math.sqrt(lookX * lookX + lookZ * lookZ);
            this.fallX = mag > 0.01 ? (lookX / mag) * 0.28 : 0.2;
            this.fallZ = mag > 0.01 ? (lookZ / mag) * 0.28 : 0.0;
        }
    }

    /**
     * Finds the natural tree the cut log belongs to, or null if it isn't one: every connected log must be natural
     * (not player-placed), the cut log must not be owned or inside a generated structure (village houses, camps),
     * and the top of the trunk must carry natural (non-persistent) leaves.
     */
    private static FellingJob scanNaturalTree(ServerPlayer player, ServerLevel level, BlockPos cutPos) {
        if (PlacedBlocks.isPlayerPlaced(level, cutPos) || Ownership.isOwnedByOther(player, level, cutPos) || inStructure(level, cutPos)) {
            return null;
        }
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> logs = new ArrayList<>();
        queue.add(cutPos);
        seen.add(cutPos);
        int topY = cutPos.getY();
        // Breadth-first search through connected logs only (never through leaves into a neighbouring tree)
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (BlockPos next : BlockPos.betweenClosed(cur.offset(-1, -1, -1), cur.offset(1, 1, 1))) {
                if (seen.contains(next) || next.getY() < cutPos.getY() - 1) continue;
                BlockPos imm = next.immutable();
                seen.add(imm);
                if (!level.getBlockState(imm).is(BlockTags.LOGS)) continue;
                if (PlacedBlocks.isPlayerPlaced(level, imm)) return null;
                logs.add(imm);
                if (logs.size() > MAX_TREE_LOGS) return null;
                topY = Math.max(topY, imm.getY());
                queue.add(imm);
            }
        }

        // Natural leaves around the logs: the crown decides whether this is a tree; they crumble with it.
        List<BlockPos> leaves = new ArrayList<>();
        Set<BlockPos> leafSeen = new HashSet<>();
        int crown = 0;
        List<BlockPos> trunk = new ArrayList<>(logs);
        trunk.add(cutPos);
        for (BlockPos log : trunk) {
            for (Direction d : Direction.values()) {
                BlockPos p = log.relative(d);
                if (!leafSeen.add(p)) continue;
                BlockState s = level.getBlockState(p);
                if (!s.is(BlockTags.LEAVES)) continue;
                if (s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT)) return null; // placed leaves: a build
                if (p.getY() >= topY - 3) crown++;
                if (leaves.size() < MAX_TREE_LEAVES) leaves.add(p);
            }
        }
        if (crown < MIN_CROWN_LEAVES) return null;
        // One more ring of leaves so the canopy visibly comes down; farther leaves decay on their own.
        for (int i = 0, n = leaves.size(); i < n && leaves.size() < MAX_TREE_LEAVES; i++) {
            for (Direction d : Direction.values()) {
                BlockPos p = leaves.get(i).relative(d);
                if (!leafSeen.add(p)) continue;
                BlockState s = level.getBlockState(p);
                if (s.is(BlockTags.LEAVES) && !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT))) {
                    leaves.add(p);
                    if (leaves.size() >= MAX_TREE_LEAVES) break;
                }
            }
        }
        logs.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        return new FellingJob(level, player, cutPos, logs, leaves);
    }

    private static boolean inStructure(ServerLevel level, BlockPos pos) {
        var structures = level.structureManager();
        for (var structure : structures.getAllStructuresAt(pos).keySet()) {
            if (structures.getStructureWithPieceAt(pos, h -> h.value() == structure).isValid()) return true;
        }
        return false;
    }

    /** Topples falling trees a few blocks per tick so a big tree never stalls the server. */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || FELLING.isEmpty()) return;
        CHAIN_BREAKING.set(true);
        try {
            for (Iterator<FellingJob> it = FELLING.iterator(); it.hasNext(); ) {
                FellingJob job = it.next();
                if (!tickFelling(job)) it.remove();
            }
        } finally {
            CHAIN_BREAKING.set(false);
        }
    }

    /** Processes a slice of the job; false when done. */
    private static boolean tickFelling(FellingJob job) {
        ServerLevel level = job.level;
        // Turn connected logs above or near the cut into falling block entities falling over
        for (int i = 0; i < LOGS_PER_TICK && !job.logs.isEmpty(); i++) {
            BlockPos logPos = job.logs.poll();
            if (!level.isLoaded(logPos)) continue;
            BlockState s = level.getBlockState(logPos);
            if (!s.is(BlockTags.LOGS) || PlacedBlocks.isPlayerPlaced(level, logPos)) continue;

            // Replant if this log was touching ground dirt
            if (level.getBlockState(logPos.below()).is(BlockTags.DIRT)) {
                level.setBlock(logPos, saplingFor(s).defaultBlockState(), 3);
            } else {
                level.setBlock(logPos, Blocks.AIR.defaultBlockState(), 3);
            }

            // Spawn FallingBlockEntity with falling velocity
            net.minecraft.world.entity.item.FallingBlockEntity falling =
                    net.minecraft.world.entity.item.FallingBlockEntity.fall(level, logPos, s);
            if (falling != null) {
                double heightBonus = Math.max(0, logPos.getY() - job.cutPos.getY()) * 0.04;
                falling.setDeltaMovement(job.fallX + (level.getRandom().nextDouble() - 0.5) * 0.05, 0.12 + heightBonus, job.fallZ + (level.getRandom().nextDouble() - 0.5) * 0.05);
                falling.dropItem = true;
            }
        }
        if (!job.logs.isEmpty()) return true;
        // Crumble leaves: break them into leaf particles, sticks/sapling drops, and sounds
        for (int i = 0; i < LEAVES_PER_TICK && !job.leaves.isEmpty(); i++) {
            BlockPos leafPos = job.leaves.poll();
            if (!level.isLoaded(leafPos)) continue;
            BlockState s = level.getBlockState(leafPos);
            if (s.is(BlockTags.LEAVES) && !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT))) {
                level.destroyBlock(leafPos, true, job.player.isRemoved() ? null : job.player);
            }
        }
        return !job.leaves.isEmpty();
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        FELLING.clear();
    }

    /** Breaks connected blocks (ore veins). */
    private static void chainBreak(ServerPlayer player, ServerLevel level, BlockPos origin, Block match, int max, boolean logs) {
        CHAIN_BREAKING.set(true);
        try {
            Deque<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> seen = new HashSet<>();
            queue.add(origin);
            seen.add(origin);
            int broken = 0;
            while (!queue.isEmpty() && broken < max) {
                BlockPos current = queue.poll();
                for (BlockPos next : BlockPos.betweenClosed(current.offset(-1, -1, -1), current.offset(1, 1, 1))) {
                    if (seen.contains(next)) continue;
                    BlockState s = level.getBlockState(next);
                    boolean ok = logs ? s.is(BlockTags.LOGS) : s.is(match);
                    if (!ok) continue;
                    BlockPos immutable = next.immutable();
                    seen.add(immutable);
                    queue.add(immutable);
                    if (player.gameMode.destroyBlock(immutable)) {
                        broken++;
                    }
                    if (broken >= max) break;
                }
            }
        } finally {
            CHAIN_BREAKING.set(false);
        }
    }

    // ------------------------------------------------------------------ fishing

    @SubscribeEvent
    public static void onFished(ItemFishedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var rnd = player.getRandom();
        float xp = 8f * event.getDrops().size();
        if (Perks.has(player, "fishing.patient_hands")) xp *= 1.5f;
        Progression.addSkillXp(player, Skill.FISHING, xp);
        SkyData.get(player).addStat("fish_caught", event.getDrops().size());

        List<ItemStack> extra = new ArrayList<>();
        int angler = Perks.rank(player, "fishing.angler");
        if (angler > 0 && rnd.nextFloat() < 0.1f * angler) {
            // another fish of the same kind (Skycraft rods catch the local species)
            extra.add(event.getDrops().isEmpty() ? new ItemStack(rnd.nextBoolean() ? Items.COD : Items.SALMON) : event.getDrops().get(0).copy());
        }
        if (Perks.has(player, "fishing.double_catch") && rnd.nextFloat() < 0.25f) {
            for (ItemStack s : event.getDrops()) extra.add(s.copy());
        }
        int treasure = Perks.rank(player, "fishing.treasure_fisher");
        if (treasure > 0 && rnd.nextFloat() < 0.08f * treasure && player.level() instanceof ServerLevel level) {
            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, player.position())
                    .withParameter(LootContextParams.TOOL, player.getMainHandItem())
                    .withLuck(player.getLuck())
                    .create(LootContextParamSets.FISHING);
            extra.addAll(com.skycraft.platform.LootTables.get(level, BuiltInLootTables.FISHING_TREASURE).getRandomItems(params));
        }
        if (Perks.has(player, "fishing.legendary_angler") && rnd.nextFloat() < 0.02f) {
            ItemStack legendary = com.skycraft.survival.fishing.Fishing.trophyOf(event.getDrops().isEmpty() ? ItemStack.EMPTY : event.getDrops().get(0), rnd);
            if (legendary.isEmpty()) {
                legendary = new ItemStack(Items.TROPICAL_FISH);
                legendary.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("item.skycraft.legendary_fish").withStyle(ChatFormatting.GOLD));
            }
            extra.add(legendary);
            Notifier.message(player, Component.translatable("message.skycraft.legendary_fish"));
            Progression.addSkillXp(player, Skill.FISHING, 100f);
        }
        for (ItemStack stack : extra) {
            if (!ActionHandler.addToBags(player, stack)) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
    }

    // ------------------------------------------------------------------ hunting

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        LivingEntity victim = event.getEntity();
        if (victim instanceof Animal) {
            float xp = 4f + victim.getMaxHealth() * 0.8f;
            if (Perks.has(player, "hunting.apex_predator")) xp *= 2f;
            Progression.addSkillXp(player, Skill.HUNTING, xp);
            SkyData.get(player).addStat("animals_killed", 1);
        } else if (victim.getType().builtInRegistryHolder().is(com.skycraft.combat.CombatHandler.MONSTERS)) {
            Progression.addSkillXp(player, Skill.HUNTING, victim.getMaxHealth() * 0.5f);
        }
        if (victim instanceof net.minecraft.world.entity.monster.Enemy) SkyData.get(player).addStat("enemies_killed", 1);
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || !(event.getEntity() instanceof Animal animal)) return;
        var rnd = player.getRandom();
        List<ItemEntity> extra = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            boolean hide = stack.is(Items.LEATHER) || stack.is(Items.RABBIT_HIDE) || stack.is(Items.RABBIT_FOOT) || stack.is(net.minecraft.tags.ItemTags.WOOL);
            boolean meat = stack.has(net.minecraft.core.component.DataComponents.FOOD);
            boolean dup = hide && Perks.has(player, "hunting.skinner") && rnd.nextFloat() < 0.5f
                    || meat && Perks.has(player, "hunting.field_dresser") && rnd.nextFloat() < 0.5f
                    || Perks.has(player, "hunting.apex_predator") && rnd.nextFloat() < 0.25f;
            if (dup) extra.add(new ItemEntity(animal.level(), drop.getX(), drop.getY(), drop.getZ(), stack.copy()));
        }
        if (Perks.has(player, "hunting.skinner") && rnd.nextFloat() < 0.3f) {
            extra.add(new ItemEntity(animal.level(), animal.getX(), animal.getY(), animal.getZ(), new ItemStack(Items.LEATHER)));
        }
        event.getDrops().addAll(extra);
    }

    // Hunting "Beast Lore" (predators ignore you) lives with the other targeting rules in combat/Stealth.
}
