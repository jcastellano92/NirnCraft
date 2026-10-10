package com.skycraft.survival;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.Skill;
import com.skycraft.crafting.arcane.alchemy.Ingredients;
import com.skycraft.crafting.arcane.block.IngredientPlantBlock;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import com.skycraft.crime.Ownership;
import com.skycraft.crime.Theft;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.economy.ItemValues;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import com.skycraft.survival.block.SkyCropBlock;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Skyrim-style harvesting of crops, wild flora, mushrooms and plants.
 *
 * <p>Deliberate harvesting ([F] / Take or left-click breaking) adds the produce
 * directly into the player's inventory/bags, plays authentic harvest audio, awards
 * Alchemy/Farming progression, and cleanly replants mature crops so farmlands remain intact.</p>
 *
 * <p>What can be harvested is an explicit allowlist: the block tags {@code #skycraft:harvestable_crops} and
 * {@code #skycraft:harvestable_flora} (never leaves). Flora a player placed is simply picked back up (no XP, no Green
 * Thumb bonus), crops in a settlement belong to it (taking them is theft), and this class is the only place the Alchemy
 * perk Green Thumb adds produce.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Harvesting {
    /** Farmed plants that regrow / are replanted when harvested. */
    public static final TagKey<Block> HARVESTABLE_CROPS = BlockTags.create(new ResourceLocation(Skycraft.MODID, "harvestable_crops"));
    /** Wild flowers, mushrooms and alchemy plants that are picked. */
    public static final TagKey<Block> HARVESTABLE_FLORA = BlockTags.create(new ResourceLocation(Skycraft.MODID, "harvestable_flora"));

    /** True while {@link #mayHarvest} posts its permission-check break event, so {@link #onBreak} ignores it. */
    private static boolean probing;
    /** Player-placed status of the block being broken, captured before other handlers forget it. */
    private static BlockPos breakingPos;
    private static boolean breakingPlaced;

    private Harvesting() {}

    /**
     * True if this block can be harvested as flora, herbs, flowers, mushrooms or crops.
     * Ambient grass, tall grass, ferns, leaves and dead bushes cannot be harvested (matching Skyrim).
     */
    public static boolean isHarvestable(BlockState state) {
        if (state == null || state.isAir() || state.is(BlockTags.LEAVES)) return false;
        return state.is(HARVESTABLE_CROPS) || state.is(HARVESTABLE_FLORA);
    }

    /** True if the plant or crop is mature/ready for harvest. */
    public static boolean isReadyToHarvest(BlockState state) {
        if (!isHarvestable(state)) return false;
        Block b = state.getBlock();

        if (b instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (b instanceof PitcherCropBlock) {
            return state.getValue(PitcherCropBlock.AGE) >= PitcherCropBlock.MAX_AGE;
        }
        if (b instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }
        if (b instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) >= 2;
        }
        if (b instanceof IngredientPlantBlock) {
            return !state.getValue(IngredientPlantBlock.HARVESTED);
        }
        if (b instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= 2;
        }
        if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
            return CaveVines.hasGlowBerries(state);
        }

        // Wild flowers and mushrooms are always ready
        return true;
    }

    /**
     * Deliberate harvesting ([F] / Take, right-clicking an alchemy plant) on the server. Checks reach, the player's
     * right to break the block (adventure mode, spawn protection, claims, jail: a {@link BlockEvent.BreakEvent} is
     * posted and must not be cancelled), then yields the produce directly into the player's bags.
     */
    public static boolean harvest(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || player.isSpectator()) return false;
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 36.0) return false;

        BlockState state = level.getBlockState(pos);
        if (!isHarvestable(state)) return false;
        if (state.getBlock() instanceof PitcherCropBlock && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            pos = pos.below();
            state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof PitcherCropBlock)) return false;
        }
        if (!isReadyToHarvest(state)) {
            notReady(player, state);
            return false;
        }
        boolean placed = PlacedBlocks.isPlayerPlaced(level, pos);
        if (!mayHarvest(player, level, pos, state, placed)) return false;
        return doHarvest(player, level, pos, state, placed);
    }

    /** Vanilla/Forge permission checks for taking the block at {@code pos}, as if the player broke it. */
    private static boolean mayHarvest(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, boolean placed) {
        if (!player.mayInteract(level, pos) || !player.mayBuild()) return false;
        if (player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) return false;
        if (level.getServer().isUnderSpawnProtection(level, pos, player)) return false;
        BlockEvent.BreakEvent probe = new BlockEvent.BreakEvent(level, pos, state, player);
        probing = true;
        try {
            MinecraftForge.EVENT_BUS.post(probe);
        } finally {
            probing = false;
        }
        // listeners treat the probe like a real break (e.g. the dig module forgets placed blocks): undo that
        if (placed && !PlacedBlocks.isPlayerPlaced(level, pos)) PlacedBlocks.mark(level, pos, true);
        return !probe.isCanceled();
    }

    private static void notReady(ServerPlayer player, BlockState state) {
        Block block = state.getBlock();
        String key;
        if (block instanceof CropBlock || block instanceof PitcherCropBlock) key = "crop_growing";
        else if (block instanceof NetherWartBlock) key = "nether_wart_growing";
        else if (block instanceof CocoaBlock) key = "cocoa_ripening";
        else if (block instanceof IngredientPlantBlock) key = "already_harvested";
        else if (block instanceof SweetBerryBushBlock) key = "berries_unripe";
        else if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) key = "no_glow_berries";
        else return;
        Notifier.message(player, Component.translatable("message.skycraft.survival.harvest." + key));
    }

    /**
     * Takes the produce of a ready plant (checks already done). {@code placed}: the block was placed by a player,
     * so flora is just picked back up and crops in a settlement are the player's own.
     */
    private static boolean doHarvest(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, boolean placed) {
        Block block = state.getBlock();
        boolean greenThumb = Perks.has(player, "alchemy.green_thumb");
        boolean stolen = isOwnedBySomeoneElse(player, level, pos, state, placed);
        List<ItemStack> harvestedItems = new ArrayList<>();

        // ------------------------------------------------------------------ 1. Crops
        if (block instanceof CropBlock crop) {
            // Retrieve drops from loot tables
            List<ItemStack> drops = Block.getDrops(state, level, pos, null, player, player.getMainHandItem());
            if (drops.isEmpty()) {
                // Fallback produce if loot table returned empty
                drops = getCropFallbackDrops(crop, state);
            }

            // The replanted crop costs one seed of the harvest
            Item seed = getSeedItem(crop);
            boolean seedPaid = false;
            boolean seedIsProduce = seed.isEdible();
            for (ItemStack s : drops) {
                if (s.isEmpty()) continue;
                ItemStack copy = s.copy();
                if (!seedPaid && copy.is(seed)) {
                    copy.shrink(1);
                    seedPaid = true;
                }
                // Green thumb doubles the produce (not the spare seeds)
                if (greenThumb && (seedIsProduce || copy.getItem() != seed)) {
                    copy.grow(copy.getCount());
                }
                if (!copy.isEmpty()) harvestedItems.add(copy);
            }

            // Replant crop at stage 0 (Skyrim farm preservation)
            level.setBlock(pos, crop.getStateForAge(0), 3);
            if (placed) PlacedBlocks.mark(level, pos, true);
            level.playSound(null, pos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
            level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 0.8f, 1.2f);
            Progression.addSkillXp(player, Skill.ALCHEMY, 2.0f);

        } else if (block instanceof PitcherCropBlock) {
            // The flower is the harvest; the pod doesn't come back (like breaking a ripe pitcher crop)
            harvestedItems.add(new ItemStack(Items.PITCHER_PLANT, 1));
            removeFlora(level, pos, state);
            level.playSound(null, pos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
            Progression.addSkillXp(player, Skill.ALCHEMY, 2.0f);

        } else if (block instanceof NetherWartBlock) {
            int count = 2 + level.random.nextInt(3) - 1; // one wart replants the crop
            if (greenThumb) count *= 2;
            harvestedItems.add(new ItemStack(Items.NETHER_WART, count));

            level.setBlock(pos, state.setValue(NetherWartBlock.AGE, 0), 3);
            if (placed) PlacedBlocks.mark(level, pos, true);
            level.playSound(null, pos, SoundEvents.NETHER_WART_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
            Progression.addSkillXp(player, Skill.ALCHEMY, 2.0f);

        } else if (block instanceof CocoaBlock) {
            int count = 2 + (greenThumb ? 2 : 0);
            harvestedItems.add(new ItemStack(Items.COCOA_BEANS, count));

            level.setBlock(pos, state.setValue(CocoaBlock.AGE, 0), 3);
            if (placed) PlacedBlocks.mark(level, pos, true);
            level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0f, 1.0f);
            Progression.addSkillXp(player, Skill.ALCHEMY, 2.0f);

        // ------------------------------------------------------------------ 2. Alchemy Plants
        } else if (block instanceof IngredientPlantBlock) {
            if (placed) {
                // A plant the player put down is picked back up as it was: no regrowing stems, XP or bonus
                harvestedItems.add(new ItemStack(block.asItem(), 1));
                removeFlora(level, pos, state);
            } else {
                harvestedItems.add(new ItemStack(block.asItem(), greenThumb ? 2 : 1));
                level.setBlock(pos, state.setValue(IngredientPlantBlock.HARVESTED, true), Block.UPDATE_CLIENTS);
                Progression.addSkillXp(player, Skill.ALCHEMY, 2.5f);
            }
            level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0f, 1.0f);

        // ------------------------------------------------------------------ 3. Berry Bushes & Vines
        } else if (block instanceof SweetBerryBushBlock) {
            int age = state.getValue(SweetBerryBushBlock.AGE);
            int count = 1 + level.random.nextInt(2) + (age == 3 ? 1 : 0);
            if (greenThumb) count *= 2;
            harvestedItems.add(new ItemStack(Items.SWEET_BERRIES, count));

            level.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), 3);
            if (placed) PlacedBlocks.mark(level, pos, true);
            level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0f, 1.0f);
            Progression.addSkillXp(player, Skill.ALCHEMY, 1.5f);

        } else if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
            int count = greenThumb ? 2 : 1;
            harvestedItems.add(new ItemStack(Items.GLOW_BERRIES, count));

            level.setBlock(pos, state.setValue(CaveVines.BERRIES, false), 3);
            if (placed) PlacedBlocks.mark(level, pos, true);
            level.playSound(null, pos, SoundEvents.CAVE_VINES_PICK_BERRIES, SoundSource.BLOCKS, 1.0f, 1.0f);
            Progression.addSkillXp(player, Skill.ALCHEMY, 1.5f);

        // ------------------------------------------------------------------ 4. Flowers, Mushrooms, Flora
        } else {
            Item item = block.asItem();
            if (item != Items.AIR) {
                // placed flowers come back exactly as placed; wild ones may yield a Green Thumb bonus
                int count = greenThumb && !placed ? 2 : 1;
                harvestedItems.add(new ItemStack(item, count));
            }

            removeFlora(level, pos, state);
            level.playSound(null, pos, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.9f, 1.1f);
            if (!placed) Progression.addSkillXp(player, Skill.ALCHEMY, 1.0f);
        }

        if (stolen) steal(player, pos, harvestedItems);

        // Deliver all items directly into bags
        for (ItemStack item : harvestedItems) {
            deliverToPlayer(player, level, item);
        }

        return true;
    }

    /**
     * Crops a settlement grows (not planted by a player) and any block with an explicit owner belong to someone:
     * taking their produce is theft.
     */
    private static boolean isOwnedBySomeoneElse(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, boolean placed) {
        if (player.isCreative()) return false;
        if (Ownership.isOwnedByOther(player, level, pos)) return true;
        if (placed) return false;
        Block b = state.getBlock();
        boolean farmed = b instanceof CropBlock || b instanceof PitcherCropBlock || b instanceof NetherWartBlock || b instanceof CocoaBlock;
        return farmed && Theft.inVillage(level, pos);
    }

    /** Marks the produce stolen and reports the theft (half its value as bounty, like container theft) if seen. */
    private static void steal(ServerPlayer player, BlockPos pos, List<ItemStack> items) {
        long value = 0;
        for (ItemStack stack : items) {
            value += ItemValues.getStack(stack);
            Bounty.markStolen(stack);
        }
        Crimes.report(player, pos, (int) Math.max(1, Math.min(Integer.MAX_VALUE, value / 2)), false, null);
    }

    private static void removeFlora(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)) {
            DoubleBlockHalf half = state.getValue(DoublePlantBlock.HALF);
            BlockPos otherPos = (half == DoubleBlockHalf.UPPER) ? pos.below() : pos.above();
            level.removeBlock(pos, false);
            PlacedBlocks.mark(level, pos, false);
            if (level.getBlockState(otherPos).is(state.getBlock())) {
                level.removeBlock(otherPos, false);
                PlacedBlocks.mark(level, otherPos, false);
            }
        } else {
            level.removeBlock(pos, false);
            PlacedBlocks.mark(level, pos, false);
        }
    }

    private static void deliverToPlayer(ServerPlayer player, ServerLevel level, ItemStack item) {
        if (item.isEmpty()) return;
        Component name = item.getHoverName();
        int count = item.getCount();

        // 1. Put item directly into bags / inventory
        ActionHandler.addToBags(player, item);

        // 2. If bags are full, pop surplus at player feet so nothing is lost
        if (!item.isEmpty()) {
            Block.popResource(level, player.blockPosition(), item);
        }

        // 3. Skyrim HUD message: "Wheat (2) added"
        Component msg = count > 1
                ? Component.translatable("message.skycraft.survival.harvest.added_count", name, count)
                : Component.translatable("message.skycraft.survival.harvest.added", name);
        Notifier.message(player, msg);
    }

    private static List<ItemStack> getCropFallbackDrops(CropBlock crop, BlockState state) {
        List<ItemStack> list = new ArrayList<>();
        if (crop == Blocks.WHEAT) {
            list.add(new ItemStack(Items.WHEAT, 1));
            list.add(new ItemStack(Items.WHEAT_SEEDS, 1 + (int) (Math.random() * 2)));
        } else if (crop == Blocks.CARROTS) {
            list.add(new ItemStack(Items.CARROT, 2 + (int) (Math.random() * 2)));
        } else if (crop == Blocks.POTATOES) {
            list.add(new ItemStack(Items.POTATO, 2 + (int) (Math.random() * 2)));
        } else if (crop == Blocks.BEETROOTS) {
            list.add(new ItemStack(Items.BEETROOT, 1));
            list.add(new ItemStack(Items.BEETROOT_SEEDS, 1 + (int) (Math.random() * 2)));
        } else if (crop instanceof SkyCropBlock) {
            Item seedItem = getSeedItem(crop);
            list.add(new ItemStack(seedItem, 1 + (int) (Math.random() * 2)));
            Item produceItem = getProduceForSkyCrop(crop);
            if (produceItem != null && produceItem != Items.AIR) {
                list.add(new ItemStack(produceItem, 1 + (int) (Math.random() * 2)));
            }
        }
        return list;
    }

    public static Item getSeedItem(CropBlock crop) {
        if (crop == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (crop == Blocks.CARROTS) return Items.CARROT;
        if (crop == Blocks.POTATOES) return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (crop == SurvivalRegistry.CABBAGE_CROP.get()) return SurvivalRegistry.CABBAGE_SEEDS.get();
        if (crop == SurvivalRegistry.LEEK_CROP.get()) return SurvivalRegistry.LEEK_SEEDS.get();
        if (crop == SurvivalRegistry.TOMATO_CROP.get()) return SurvivalRegistry.TOMATO_SEEDS.get();
        return crop.asItem();
    }

    private static Item getProduceForSkyCrop(CropBlock crop) {
        if (crop == SurvivalRegistry.CABBAGE_CROP.get()) return SurvivalRegistry.CABBAGE.get();
        if (crop == SurvivalRegistry.LEEK_CROP.get()) return SurvivalRegistry.LEEK.get();
        if (crop == SurvivalRegistry.TOMATO_CROP.get()) return SurvivalRegistry.TOMATO.get();
        return null;
    }

    /** Remembers whether the block being broken was player-placed before the dig module forgets it. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreakFirst(BlockEvent.BreakEvent event) {
        if (probing || !(event.getLevel() instanceof ServerLevel level)) return;
        breakingPos = event.getPos().immutable();
        breakingPlaced = PlacedBlocks.isPlayerPlaced(level, event.getPos());
    }

    /**
     * Intercepts left-click breaking of flowers, mushrooms and crops once every protection handler has allowed it,
     * so the produce goes directly into the bags instead of falling to the ground.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (probing || event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player) || player.isCreative()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        BlockPos pos = event.getPos();
        boolean placed = pos.equals(breakingPos) ? breakingPlaced : PlacedBlocks.isPlayerPlaced(level, pos);
        breakingPos = null;

        if (isHarvestable(state)) {
            if (state.getBlock() instanceof PitcherCropBlock && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
                return; // vanilla breaks both halves and drops the crop's own loot
            }
            // If it's ready, harvest directly into inventory!
            if (isReadyToHarvest(state)) {
                doHarvest(player, level, pos, state, placed);
                event.setCanceled(true); // Cancel vanilla break so drops aren't spawned twice or onto ground
            } else if (state.getBlock() instanceof CropBlock crop) {
                // Immature crop broken with left-click: give seed back directly to bags
                Item seedItem = getSeedItem(crop);
                if (seedItem != Items.AIR) {
                    deliverToPlayer(player, level, new ItemStack(seedItem, 1));
                }
                level.removeBlock(pos, false);
                event.setCanceled(true);
            }
        } else if (!placed && Perks.has(player, "alchemy.green_thumb")) {
            // Green Thumb on plants this class doesn't harvest itself (kelp, sea pickles, spore blossoms, modded flowers)
            ItemStack bonus = greenThumbBonus(state);
            if (!bonus.isEmpty() && Ingredients.isIngredient(bonus)) Block.popResource(level, pos, bonus);
        }
    }

    private static ItemStack greenThumbBonus(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.KELP || block == Blocks.KELP_PLANT) return new ItemStack(Items.KELP);
        if (block instanceof FlowerBlock || block instanceof MushroomBlock || state.is(BlockTags.SMALL_FLOWERS)
                || block == Blocks.SPORE_BLOSSOM || block == Blocks.SEA_PICKLE) {
            Item item = block.asItem();
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        }
        return ItemStack.EMPTY;
    }
}
