package com.skycraft.magic.spell.spellmaking;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellSounds;
import com.skycraft.magic.spell.Spells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * The Spellmaking Altar (Arcane University, Mages Guild): use it to combine the magic effects you know into a new
 * spell. The screen lives on the client ({@code magic.client.SpellmakingScreen}); the server re-checks everything in
 * {@link #make}.
 */
public class SpellmakingAltarBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(1, 0, 1, 15, 3, 15),
            Block.box(4, 3, 4, 12, 11, 12),
            Block.box(0, 11, 0, 16, 14, 16));

    public SpellmakingAltarBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.magic.client.MagicClient.openSpellmaking(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) != 0) return;
        double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.6;
        double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.6;
        level.addParticle(ParticleTypes.ENCHANT, x, pos.getY() + 1.3, z, (random.nextDouble() - 0.5) * 0.4, -0.3, (random.nextDouble() - 0.5) * 0.4);
        if (random.nextInt(4) == 0) level.addParticle(ParticleTypes.END_ROD, x, pos.getY() + 0.95, z, 0, 0.01, 0);
    }

    /** Whether {@code pos} is an altar within reach of the player (packet validation). */
    public static boolean nearAltar(ServerPlayer player, BlockPos pos) {
        return player.level().isLoaded(pos) && player.level().getBlockState(pos).is(MagicRegistry.SPELLMAKING_ALTAR.get())
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 8 * 8;
    }

    /** Server side of the altar: validates and teaches the made spell. */
    public static void make(ServerPlayer player, BlockPos pos, String id) {
        if (!nearAltar(player, pos)) return;
        CustomSpells.Def def = CustomSpells.def(id);
        if (def == null) {
            Notifier.message(player, Component.translatable("spellmaking.skycraft.problem.invalid"));
            return;
        }
        String problem = CustomSpells.problem(player, def);
        if (problem != null) {
            Notifier.message(player, Component.translatable(problem));
            return;
        }
        long gold = CustomSpells.goldCost(def);
        if (!player.isCreative() && !Currency.take(player, gold)) {
            Notifier.message(player, Component.translatable("spellmaking.skycraft.problem.gold"));
            return;
        }
        Spell spell = Spells.byId(def.id());
        if (spell == null) return;
        MagicData.learn(player, spell.id);
        Notifier.title(player, Component.translatable("spellmaking.skycraft.made"), spell.displayName());
        ServerLevel level = player.serverLevel();
        Vec3 c = Vec3.atCenterOf(pos).add(0, 0.6, 0);
        MagicFx.sendNear(level, c, 48, MagicFx.STREAM, spell.element, c, c, player.getId(), 40);
        SpellSounds.world(level, c, SpellSounds.Slot.SPELLMAKING, 1f, 1.1f);
    }

    /** Forgets a made spell (the magic menu's forget action). */
    public static void forget(ServerPlayer player, String id) {
        if (!CustomSpells.isCustom(id) || !MagicData.knows(player, id)) return;
        MagicData.forget(player, id);
        Spell spell = Spells.byId(id);
        if (spell != null) Notifier.message(player, Component.translatable("spellmaking.skycraft.forgotten", spell.displayName()));
    }
}
