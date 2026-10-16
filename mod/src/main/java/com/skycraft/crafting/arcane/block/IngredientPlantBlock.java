package com.skycraft.crafting.arcane.block;

import com.skycraft.survival.Harvesting;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A wild 3D alchemy plant or fungus across Tamriel (Nightshade, Deathbell, Nirnroot, Crimson Nirnroot, Blisterwort,
 * Imp Stool, Bleeding Crown, Trama Root, Marshmerrow, Coda Flower, Moon Sugar cane, etc.).
 *
 * <p>Right-click (or [F]) to harvest through {@link Harvesting}: the player gets the ingredient (two with Green Thumb)
 * plus any forage loot-table bonuses, and the plant transitions to bare regrowing stems ({@link #HARVESTED}), which
 * regrow after about one in-game day of random ticks. A plant the player placed is picked back up as placed.</p>
 */
public class IngredientPlantBlock extends FlowerBlock {
    public static final BooleanProperty HARVESTED = BooleanProperty.create("harvested");
    /** One random tick in this many regrows the plant: ~18 random ticks at the default tick speed is about 24000 ticks. */
    private static final int REGROW_CHANCE = 18;

    private final boolean glowing;
    private final boolean ceilingAllowed;
    private final Supplier<Item> ingredientSupplier;

    public IngredientPlantBlock(MobEffect stewEffect, int stewDuration, boolean glowing, Properties props) {
        this(stewEffect, stewDuration, glowing, false, null, props);
    }

    public IngredientPlantBlock(MobEffect stewEffect, int stewDuration, boolean glowing, boolean ceilingAllowed,
                                Supplier<Item> ingredientSupplier, Properties props) {
        super(stewEffect, stewDuration, props);
        this.glowing = glowing;
        this.ceilingAllowed = ceilingAllowed;
        this.ingredientSupplier = ingredientSupplier;
        this.registerDefaultState(this.stateDefinition.any().setValue(HARVESTED, false));
    }

    /** Returns the alchemy ingredient item harvested from this plant when ripe. */
    public Item ingredientItem() {
        if (ingredientSupplier != null) {
            Item supplied = ingredientSupplier.get();
            if (supplied != null && supplied != Items.AIR) {
                return supplied;
            }
        }
        Item self = this.asItem();
        return self != Items.AIR ? self : Items.BROWN_MUSHROOM;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HARVESTED);
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return super.mayPlaceOn(state, level, pos)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.MUD)
                || state.is(Blocks.MUDDY_MANGROVE_ROOTS)
                || state.is(Blocks.MANGROVE_ROOTS)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(BlockTags.ICE)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MYCELIUM)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.BASALT)
                || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.LOGS)
                || state.is(BiomeBlocks.ASH_BLOCK.get())
                || state.is(BiomeBlocks.VOLCANIC_ROCK.get())
                || state.is(BiomeBlocks.CRACKED_VOLCANIC_ROCK.get())
                || state.is(BiomeBlocks.MAGMA_VOLCANIC_ROCK.get())
                || state.is(BiomeBlocks.CORAL_STONE.get())
                || state.is(BiomeBlocks.DUNE_SAND.get())
                || state.is(BiomeBlocks.FROSTED_STONE.get())
                || state.is(BiomeBlocks.PARASOL_STEM.get())
                || state.is(BiomeBlocks.PARASOL_CAP.get())
                || state.is(BiomeBlocks.TELVANNI_CAP.get());
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        if (this.mayPlaceOn(level.getBlockState(below), level, below)) {
            return true;
        }
        if (ceilingAllowed) {
            BlockPos above = pos.above();
            BlockState aboveState = level.getBlockState(above);
            return aboveState.is(BlockTags.LOGS) || aboveState.is(BlockTags.LEAVES)
                    || aboveState.is(BlockTags.BASE_STONE_OVERWORLD) || this.mayPlaceOn(aboveState, level, above);
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (state.getValue(HARVESTED)) {
            return Collections.emptyList();
        }
        List<ItemStack> drops = super.getDrops(state, params);
        if (drops.isEmpty()) {
            return List.of(new ItemStack(ingredientItem(), 1));
        }
        return drops;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (state.getValue(HARVESTED)) return InteractionResult.PASS;
        // The survival module's harvesting does the protection checks, Green Thumb and player-placed plants
        if (player instanceof ServerPlayer sp && Harvesting.harvest(sp, pos)) {
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return state.getValue(HARVESTED);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(HARVESTED) && random.nextInt(REGROW_CHANCE) == 0) {
            level.setBlock(pos, state.setValue(HARVESTED, false), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // Nirnroot & Crimson Nirnroot glitter and hum
        if (glowing && !state.getValue(HARVESTED)) {
            if (random.nextInt(4) == 0) {
                level.addParticle(ParticleTypes.END_ROD, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 0.4 + random.nextDouble() * 0.5,
                        pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0, 0.01, 0);
            }
            if (random.nextInt(35) == 0) {
                level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        com.skycraft.world.WorldSounds.WORLD_NIRNROOT_HUM.get(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.0f, false);
            }
        }
    }
}
