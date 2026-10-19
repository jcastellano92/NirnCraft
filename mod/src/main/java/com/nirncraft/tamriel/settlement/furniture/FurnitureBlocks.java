package com.nirncraft.tamriel.settlement.furniture;

import net.minecraft.resources.ResourceKey;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.settlement.Culture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Custom 3D Skyrim-style interior furniture, storage containers, weapon racks, tabletop clutter,
 * and wall mounts across all Tamriel cultures.
 */
public final class FurnitureBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, NirnCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, NirnCraft.MODID);

    public enum ShapeCat implements StringRepresentable {
        SEAT("seat"),
        THRONE("throne"),
        TABLE("table"),
        RACK("rack"),
        CLUTTER("clutter"),
        WALL("wall");

        private final String name;

        ShapeCat(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum FurnitureKind {
        // Seating
        CHAIR("chair", "Chair", ShapeCat.SEAT, false),
        STOOL("stool", "Stool", ShapeCat.SEAT, false),
        BENCH("bench", "Bench", ShapeCat.SEAT, false),
        THRONE("throne", "Throne", ShapeCat.THRONE, false),

        // Tables, Counters, & Desks
        TABLE("table", "Table", ShapeCat.TABLE, false),
        BANQUET_TABLE("banquet_table", "Banquet Table", ShapeCat.TABLE, false),
        NIGHTSTAND("nightstand", "Nightstand", ShapeCat.TABLE, false),
        COUNTER("counter", "Counter", ShapeCat.TABLE, false),
        DESK("desk", "Writing Desk", ShapeCat.TABLE, false),

        // Storage Containers & Weapon Rack
        WARDROBE("wardrobe", "Wardrobe", ShapeCat.TABLE, true),
        DRESSER("dresser", "Dresser", ShapeCat.TABLE, true),
        CABINET("cabinet", "Cabinet", ShapeCat.TABLE, true),
        WEAPON_RACK("weapon_rack", "Weapon Rack", ShapeCat.RACK, false),

        // Tabletop Clutter
        CLUTTER_TANKARD("clutter_tankard", "Ale Tankard", ShapeCat.CLUTTER, false),
        CLUTTER_PLATTER("clutter_platter", "Feast Platter", ShapeCat.CLUTTER, false),
        CLUTTER_GOBLETS("clutter_goblets", "Goblets & Wine", ShapeCat.CLUTTER, false),
        CLUTTER_CANDELABRA("clutter_candelabra", "Candelabra", ShapeCat.CLUTTER, false),
        CLUTTER_APOTHECARY("clutter_apothecary", "Apothecary Vials", ShapeCat.CLUTTER, false),
        CLUTTER_SCALES("clutter_scales", "Merchant Scales", ShapeCat.CLUTTER, false),
        CLUTTER_LEDGER("clutter_ledger", "Merchant Ledger", ShapeCat.CLUTTER, false),
        CLUTTER_ARMOR_STAND("clutter_armor_stand", "Armor Stand", ShapeCat.RACK, false),

        // Wall Clutter
        WALL_SHIELD_PLAQUE("wall_shield_plaque", "Mounted Weapon Plaque", ShapeCat.WALL, false),
        WALL_ANTLER_TROPHY("wall_antler_trophy", "Mounted Stag Trophy", ShapeCat.WALL, false),
        WALL_TAPESTRY("wall_tapestry", "Woven Tapestry", ShapeCat.WALL, false),
        WALL_SHELF("wall_shelf", "Wall Shelf", ShapeCat.WALL, false);

        public static final FurnitureKind[] VALUES = values();

        public final String id;
        public final String displayName;
        public final ShapeCat shapeCat;
        public final boolean container;

        FurnitureKind(String id, String displayName, ShapeCat shapeCat, boolean container) {
            this.id = id;
            this.displayName = displayName;
            this.shapeCat = shapeCat;
            this.container = container;
        }

        public boolean isSeat() {
            return this == CHAIR || this == STOOL || this == BENCH || this == THRONE;
        }

        public boolean isTakeableClutter() {
            return this == WEAPON_RACK || this == CLUTTER_TANKARD || this == CLUTTER_PLATTER
                    || this == CLUTTER_GOBLETS || this == CLUTTER_APOTHECARY
                    || this == CLUTTER_SCALES || this == CLUTTER_LEDGER;
        }

        public static FurnitureKind byId(String id) {
            if (id == null || id.isEmpty()) return TABLE;
            String clean = id.toLowerCase(Locale.ROOT);
            for (FurnitureKind k : VALUES) {
                if (k.id.equals(clean)) return k;
            }
            return TABLE;
        }
    }

    public static final net.neoforged.neoforge.registries.DeferredHolder<Block, Block> SKY_FURNITURE = BLOCKS.register("sky_furniture",
            key -> new FurnitureBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key))
                    .mapColor(MapColor.WOOD)
                    .strength(2.0f, 3.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(FurnitureBlock.SHAPE) == ShapeCat.CLUTTER ? 10 : 0)));

    public static final net.neoforged.neoforge.registries.DeferredHolder<Block, Block> SKY_FURNITURE_CONTAINER = BLOCKS.register("sky_furniture_container",
            key -> new FurnitureContainerBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key))
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f, 3.5f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final Supplier<BlockEntityType<FurnitureBlockEntity>> SKY_FURNITURE_BE =
            BLOCK_ENTITIES.register("sky_furniture", () ->
                    new BlockEntityType<>(FurnitureBlockEntity::new, SKY_FURNITURE.get()));

    public static final Supplier<BlockEntityType<FurnitureContainerBlockEntity>> SKY_FURNITURE_CONTAINER_BE =
            BLOCK_ENTITIES.register("sky_furniture_container", () ->
                    new BlockEntityType<>(FurnitureContainerBlockEntity::new, SKY_FURNITURE_CONTAINER.get()));

    private FurnitureBlocks() {}

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    public static BlockState stateFor(FurnitureKind kind, Direction facing) {
        Direction horiz = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        if (kind.container) {
            if (!SKY_FURNITURE_CONTAINER.isBound()) {
                return net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState();
            }
            return SKY_FURNITURE_CONTAINER.get().defaultBlockState()
                    .setValue(FurnitureContainerBlock.FACING, horiz);
        }
        if (!SKY_FURNITURE.isBound()) {
            return net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState();
        }
        return SKY_FURNITURE.get().defaultBlockState()
                .setValue(FurnitureBlock.FACING, horiz)
                .setValue(FurnitureBlock.SHAPE, kind.shapeCat);
    }

    public static Culture resolveCulture(@Nullable Level level, BlockPos pos, @Nullable String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return mapPaletteToCulture(explicit);
        }
        try {
            var reg = Regions.at(pos.getX(), pos.getZ());
            if (reg != null) {
                return mapPaletteToCulture(reg.id());
            }
        } catch (Throwable ignored) {
        }
        return Culture.NORD;
    }

    public static Culture mapPaletteToCulture(String key) {
        if (key == null || key.isEmpty()) return Culture.NORD;
        String s = key.toLowerCase(Locale.ROOT);
        if (s.contains("imperial") || s.contains("colovian") || s.contains("niben") || s.contains("cyrodiil") || s.contains("gold_coast") || s.contains("heartlands")) return Culture.IMPERIAL;
        if (s.contains("breton") || s.contains("high_rock") || s.contains("glenumbra") || s.contains("stormhaven") || s.contains("rivenspire") || s.contains("reach")) return Culture.BRETON;
        if (s.contains("redguard") || s.contains("yokudan") || s.contains("hammerfell") || s.contains("alikr") || s.contains("stros")) return Culture.REDGUARD;
        if (s.contains("dunmer") || s.contains("morrowind") || s.contains("vvardenfell") || s.contains("ashland") || s.contains("telvanni") || s.contains("redoran") || s.contains("hlaalu")) return Culture.DUNMER;
        if (s.contains("altmer") || s.contains("summerset") || s.contains("auridon") || s.contains("ayleid")) return Culture.ALTMER;
        if (s.contains("bosmer") || s.contains("valenwood") || s.contains("grahtwood") || s.contains("greenshade") || s.contains("malabal")) return Culture.BOSMER;
        if (s.contains("khajiit") || s.contains("elsweyr") || s.contains("anequina") || s.contains("pellitine")) return Culture.KHAJIIT;
        if (s.contains("argonian") || s.contains("black_marsh") || s.contains("shadowfen") || s.contains("murkmire")) return Culture.ARGONIAN;
        if (s.contains("orc") || s.contains("orsinium") || s.contains("wrothgar") || s.contains("stronghold")) return Culture.ORC;
        return Culture.NORD;
    }

    // ------------------------------------------------------------------ Blocks

    public static final class FurnitureBlock extends BaseEntityBlock {


        public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
        public static final EnumProperty<ShapeCat> SHAPE = EnumProperty.create("shape", ShapeCat.class);

        private static final VoxelShape SEAT_COLLISION = Block.box(2.0, 0.0, 2.0, 14.0, 8.5, 14.0);
        private static final VoxelShape SEAT_OUTLINE = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);
        private static final VoxelShape THRONE_COLLISION = Block.box(1.0, 0.0, 1.0, 15.0, 9.5, 15.0);
        private static final VoxelShape THRONE_OUTLINE = Block.box(1.0, 0.0, 1.0, 15.0, 22.0, 15.0);
        private static final VoxelShape TABLE_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);
        private static final VoxelShape RACK_COLLISION = Block.box(2.0, 0.0, 2.0, 14.0, 15.0, 14.0);
        private static final VoxelShape RACK_OUTLINE = Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0);
        private static final VoxelShape CLUTTER_OUTLINE = Block.box(2.0, 0.0, 2.0, 14.0, 10.0, 14.0);
        private static final VoxelShape WALL_NORTH = Block.box(1.0, 1.0, 10.0, 15.0, 15.0, 16.0);
        private static final VoxelShape WALL_SOUTH = Block.box(1.0, 1.0, 0.0, 15.0, 15.0, 6.0);
        private static final VoxelShape WALL_EAST = Block.box(0.0, 1.0, 1.0, 6.0, 15.0, 15.0);
        private static final VoxelShape WALL_WEST = Block.box(10.0, 1.0, 1.0, 16.0, 15.0, 15.0);

        public FurnitureBlock(Properties props) {
            super(props);
            this.registerDefaultState(this.stateDefinition.any()
                    .setValue(FACING, Direction.NORTH)
                    .setValue(SHAPE, ShapeCat.TABLE));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, SHAPE);
        }

        @Nullable
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        public BlockState rotate(BlockState state, Rotation rot) {
            return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
        }

        @Override
        public BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }

        @Override
        public RenderShape getRenderShape(BlockState state) {
            return RenderShape.INVISIBLE;
        }

        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return switch (state.getValue(SHAPE)) {
                case SEAT -> SEAT_OUTLINE;
                case THRONE -> THRONE_OUTLINE;
                case TABLE -> TABLE_SHAPE;
                case RACK -> RACK_OUTLINE;
                case CLUTTER -> CLUTTER_OUTLINE;
                case WALL -> switch (state.getValue(FACING)) {
                    case NORTH -> WALL_NORTH;
                    case SOUTH -> WALL_SOUTH;
                    case EAST -> WALL_EAST;
                    case WEST -> WALL_WEST;
                    default -> WALL_SOUTH;
                };
            };
        }

        @Override
        public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return switch (state.getValue(SHAPE)) {
                case SEAT -> SEAT_COLLISION;
                case THRONE -> THRONE_COLLISION;
                case TABLE -> TABLE_SHAPE;
                case RACK -> RACK_COLLISION;
                case CLUTTER, WALL -> Shapes.empty();
            };
        }

        @Nullable
        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new FurnitureBlockEntity(pos, state);
        }
    }

    public static final class FurnitureContainerBlock extends BaseEntityBlock {


        public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
        private static final VoxelShape CONTAINER_SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0);

        public FurnitureContainerBlock(Properties props) {
            super(props);
            this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Nullable
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        public BlockState rotate(BlockState state, Rotation rot) {
            return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
        }

        @Override
        public BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }

        @Override
        public RenderShape getRenderShape(BlockState state) {
            return RenderShape.INVISIBLE;
        }

        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return CONTAINER_SHAPE;
        }

        @Override
        public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return CONTAINER_SHAPE;
        }

        @Override
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return use(state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            if (level.getBlockEntity(pos) instanceof FurnitureContainerBlockEntity containerBe) {
                player.openMenu(containerBe);
            }
            return InteractionResult.CONSUME;
        }

        @Nullable
        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new FurnitureContainerBlockEntity(pos, state);
        }
    }

    // ------------------------------------------------------------------ BlockEntities

    public static final class FurnitureBlockEntity extends BlockEntity {
        private FurnitureKind kind = FurnitureKind.TABLE;
        private String cultureId = "";
        private boolean empty = false;
        private int variant = 0;

        public FurnitureBlockEntity(BlockPos pos, BlockState state) {
            super(SKY_FURNITURE_BE.get(), pos, state);
            if (state.hasProperty(FurnitureBlock.SHAPE)) {
                this.kind = switch (state.getValue(FurnitureBlock.SHAPE)) {
                    case SEAT -> FurnitureKind.CHAIR;
                    case THRONE -> FurnitureKind.THRONE;
                    case TABLE -> FurnitureKind.TABLE;
                    case RACK -> FurnitureKind.WEAPON_RACK;
                    case CLUTTER -> FurnitureKind.CLUTTER_TANKARD;
                    case WALL -> FurnitureKind.WALL_SHIELD_PLAQUE;
                };
            }
        }

        public FurnitureKind kind() {
            return kind;
        }

        public void configure(FurnitureKind kind, String cultureId, int variant) {
            this.kind = kind != null ? kind : FurnitureKind.TABLE;
            this.cultureId = cultureId != null ? cultureId : "";
            this.variant = variant;
            this.setChanged();
        }

        public Culture culture() {
            return resolveCulture(this.level, this.worldPosition, this.cultureId);
        }

        public String cultureId() {
            return cultureId;
        }

        public boolean isEmpty() {
            return empty;
        }

        public void setEmpty(boolean empty) {
            this.empty = empty;
            this.setChanged();
            if (this.level != null && !this.level.isClientSide()) {
                this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
            }
        }

        public int variant() {
            return variant;
        }

        @Override
        protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput tag) {
            super.saveAdditional(tag);
            tag.putString("Kind", kind.id);
            if (!cultureId.isEmpty()) tag.putString("Culture", cultureId);
            if (empty) tag.putBoolean("Empty", true);
            if (variant != 0) tag.putInt("Variant", variant);
        }

        @Override
        protected void loadAdditional(net.minecraft.world.level.storage.ValueInput tag) {
            super.loadAdditional(tag);
            if (tag.getString("Kind").isPresent()) this.kind = FurnitureKind.byId(tag.getStringOr("Kind", ""));
            this.cultureId = tag.getStringOr("Culture", "");
            this.empty = tag.getBooleanOr("Empty", false);
            this.variant = tag.getIntOr("Variant", 0);
        }

        @Override
        public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
            return this.saveWithoutMetadata(registries);
        }

        @Nullable
        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    public static final class FurnitureContainerBlockEntity extends RandomizableContainerBlockEntity {
        private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        private FurnitureKind kind = FurnitureKind.WARDROBE;
        private String cultureId = "";
        private int variant = 0;

        public FurnitureContainerBlockEntity(BlockPos pos, BlockState state) {
            super(SKY_FURNITURE_CONTAINER_BE.get(), pos, state);
        }

        public FurnitureKind kind() {
            return kind;
        }

        public void configure(FurnitureKind kind, String cultureId, int variant) {
            this.kind = (kind != null && kind.container) ? kind : FurnitureKind.WARDROBE;
            this.cultureId = cultureId != null ? cultureId : "";
            this.variant = variant;
            this.setChanged();
        }

        public Culture culture() {
            return resolveCulture(this.level, this.worldPosition, this.cultureId);
        }

        public int variant() {
            return variant;
        }

        @Override
        protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput tag) {
            super.saveAdditional(tag);
            if (!this.trySaveLootTable(tag)) {
                ContainerHelper.saveAllItems(tag, this.items);
            }
            tag.putString("Kind", kind.id);
            if (!cultureId.isEmpty()) tag.putString("Culture", cultureId);
            if (variant != 0) tag.putInt("Variant", variant);
        }

        @Override
        protected void loadAdditional(net.minecraft.world.level.storage.ValueInput tag) {
            super.loadAdditional(tag);
            this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
            if (!this.tryLoadLootTable(tag)) {
                ContainerHelper.loadAllItems(tag, this.items);
            }
            if (tag.getString("Kind").isPresent()) {
                FurnitureKind k = FurnitureKind.byId(tag.getStringOr("Kind", ""));
                if (k.container) this.kind = k;
            }
            this.cultureId = tag.getStringOr("Culture", "");
            this.variant = tag.getIntOr("Variant", 0);
        }

        @Override
        public int getContainerSize() {
            return 27;
        }

        @Override
        protected NonNullList<ItemStack> getItems() {
            return this.items;
        }

        @Override
        protected void setItems(NonNullList<ItemStack> items) {
            this.items = items;
        }

        @Override
        protected Component getDefaultName() {
            return Component.literal(kind.displayName);
        }

        @Override
        protected AbstractContainerMenu createMenu(int id, Inventory playerInv) {
            return ChestMenu.threeRows(id, playerInv, this);
        }

        @Override
        public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Kind", kind.id);
            if (!cultureId.isEmpty()) tag.putString("Culture", cultureId);
            if (variant != 0) tag.putInt("Variant", variant);
            return tag;
        }

        @Nullable
        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }
}

