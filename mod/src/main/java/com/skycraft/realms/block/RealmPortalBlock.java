package com.skycraft.realms.block;

import com.skycraft.realms.Realm;
import com.skycraft.realms.RealmTravel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Supplier;

/**
 * The sheet of a realm gate: an Oblivion gate's fire, the Soul Cairn's violet portal, Coldharbour's dark anchor,
 * Sovngarde's golden way home. Walking into it travels: {@code return=false} into the realm (from Nirn),
 * {@code return=true} back to where the traveller came from ({@link RealmTravel}). Placed by structures only; it has
 * no collision and no item.
 */
public class RealmPortalBlock extends Block {
    public static final BooleanProperty RETURN = BooleanProperty.create("return");
    public static final BooleanProperty AXIS_X = BooleanProperty.create("axis_x");
    private static final VoxelShape SHEET_X = Block.box(0, 0, 6, 16, 16, 10);
    private static final VoxelShape SHEET_Z = Block.box(6, 0, 0, 10, 16, 16);

    private final Realm realm;
    private final Supplier<ParticleOptions> particle;

    public RealmPortalBlock(Properties properties, Realm realm, Supplier<ParticleOptions> particle) {
        super(properties);
        this.realm = realm;
        this.particle = particle;
        registerDefaultState(stateDefinition.any().setValue(RETURN, false).setValue(AXIS_X, true));
    }

    public Realm realm() {
        return realm;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RETURN, AXIS_X);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(AXIS_X) ? SHEET_X : SHEET_Z;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public boolean skipRendering(BlockState state, BlockState adjacent, net.minecraft.core.Direction dir) {
        return adjacent.is(this) || super.skipRendering(state, adjacent, dir);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) return;
        if (player.isPassenger() || player.isVehicle() || !player.canChangeDimensions()) return;
        RealmTravel.usePortal(player, realm, state.getValue(RETURN), pos);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) != 0) return;
        double x = pos.getX() + random.nextDouble();
        double y = pos.getY() + random.nextDouble();
        double z = pos.getZ() + random.nextDouble();
        level.addParticle(particle.get(), x, y, z, (random.nextDouble() - 0.5) * 0.05, 0.03 + random.nextDouble() * 0.05,
                (random.nextDouble() - 0.5) * 0.05);
        if (random.nextInt(120) == 0) {
            level.playLocalSound(x, y, z, SoundEvents.PORTAL_AMBIENT, SoundSource.BLOCKS, 0.35f,
                    realm == Realm.DEADLANDS ? 0.55f : 0.8f + random.nextFloat() * 0.2f, false);
        }
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return ItemStack.EMPTY;
    }
}
