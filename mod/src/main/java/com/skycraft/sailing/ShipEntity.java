package com.skycraft.sailing;

import com.skycraft.core.Notifier;
import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * A ship you can sail: rowboat, Nord longship or Imperial cog ({@link ShipType}).
 *
 * <p><b>Handling.</b> Like vanilla boats, the helmsman's client simulates the ship and the server accepts its moves;
 * without a helmsman the server simulates (drifting, slowing). Oars push with forward/back, the rudder turns with
 * left/right (better with way on), and the sail ({@link #getSail()}: furled, half, full, trimmed by any crew member
 * with the sail keys) draws from the wind ({@link SeaWind}, synced by the server) according to the point of sail:
 * best on a broad reach, nothing in irons. The hull has momentum, a keel (little sideways drift) and leeway, floats
 * at its draft on the water surface and runs aground: the bow and stern sections ({@link ShipPart}) are checked
 * against the shore before every move and turn, and hitting it hard damages the hull.</p>
 *
 * <p><b>Hull.</b> Damage (attacks, rams, storms far from shore) lowers the hull; at zero the ship sinks, spilling its
 * cargo. Planks repair it (use them on the ship). Sneak-use opens the cargo hold; sneak-attack by the owner (or in
 * creative) on an empty ship packs it back into its item. Moored ferries at piers are scenery.</p>
 */
public class ShipEntity extends Entity implements HasCustomInventoryScreen {
    private static final EntityDataAccessor<Float> DATA_HULL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_SAIL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_WIND_DIR = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WIND = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_INPUT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_HURT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SINKING = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_MOORED = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_LIVERY = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);

    public static final int IN_FORWARD = 1, IN_BACK = 2, IN_LEFT = 4, IN_RIGHT = 8;
    public static final int SAIL_FURLED = 0, SAIL_HALF = 1, SAIL_FULL = 2;
    private static final int SINK_TICKS = 220;

    public final ShipType shipType;
    private final ShipPart[] parts;
    private final Cargo cargo;

    /** The cargo hold: its slots and, behind them, an overflow (inventory.OverflowHost) so a hold is never "full". */
    private static final class Cargo extends SimpleContainer implements com.skycraft.inventory.OverflowHost {
        final com.skycraft.inventory.BulkStore bulk = new com.skycraft.inventory.BulkStore(
                com.skycraft.inventory.ContainerOverflow.MAX_ENTRIES, com.skycraft.inventory.ContainerOverflow.MAX_PER_ENTRY, null);

        Cargo(int size) {
            super(size);
        }

        @Override
        public com.skycraft.inventory.BulkStore overflow() {
            return bulk;
        }
    }

    // controller-side state
    private int input;
    private float deltaYaw;
    private int crashCooldown;
    // client interpolation
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot;
    // visuals (client)
    public float rowPhase, rowPhaseO;
    public float sailDeploy, sailDeployO;
    public float rudder, rudderO;
    private int soundTimer;

    @Nullable
    private UUID owner;
    /** The port a moored ferry belongs to (empty for ordinary ships). */
    private String port = "";
    private boolean stormWarned;

    /**
     * The ship's pose at the start of the tick on this side: the frame {@link DeckPhysics} collides deck riders in
     * before carrying them along with the ship's motion since.
     */
    public double carryX, carryY, carryZ;
    public float carryYaw;
    public boolean carryReady;

    /** Who steers when no player holds the helm (NPC ships of {@code com.skycraft.seas}); server side, never saved. */
    public interface Autopilot {
        /** Helm bits ({@link #IN_FORWARD}...) for this tick. */
        int helm(ShipEntity ship);

        /** Every server tick, before the ship sails (sail trim, crew, despawning). */
        default void tick(ShipEntity ship) {}
    }

    @Nullable
    private Autopilot autopilot;
    /** A ship of the sea traffic: crewed by NPCs, never saved with the chunk (it is gone when nobody is near). */
    private boolean transientShip;

    public ShipEntity(EntityType<? extends ShipEntity> type, Level level, ShipType shipType) {
        super(type, level);
        this.shipType = shipType;
        this.blocksBuilding = true;
        this.cargo = new Cargo(Math.max(9, shipType.cargoSlots));
        this.parts = new ShipPart[shipType.parts.length];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = new ShipPart(this, shipType.parts[i], shipType.hitWidth(), shipType.height);
        }
        if (parts.length > 0) this.setId(ENTITY_COUNTER.getAndAdd(parts.length + 1) + 1);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_HULL, 0f);
        entityData.define(DATA_SAIL, SAIL_FURLED);
        entityData.define(DATA_WIND_DIR, 0f);
        entityData.define(DATA_WIND, 0.5f);
        entityData.define(DATA_INPUT, (byte) 0);
        entityData.define(DATA_HURT, 0);
        entityData.define(DATA_SINKING, 0);
        entityData.define(DATA_MOORED, false);
        entityData.define(DATA_LIVERY, 0);
    }

    /** Freshly built ships start with a sound hull. */
    public void initNew(@Nullable Player owner) {
        setHull(shipType.maxHull);
        if (owner != null) this.owner = owner.getUUID();
    }

    // ------------------------------------------------------------------ accessors

    public float getHull() {
        return entityData.get(DATA_HULL);
    }

    public void setHull(float hull) {
        entityData.set(DATA_HULL, Mth.clamp(hull, 0f, shipType.maxHull));
    }

    public int getSail() {
        return shipType.hasSail() ? entityData.get(DATA_SAIL) : SAIL_FURLED;
    }

    public void setSail(int sail) {
        entityData.set(DATA_SAIL, Mth.clamp(sail, SAIL_FURLED, SAIL_FULL));
    }

    public float getWindDir() {
        return entityData.get(DATA_WIND_DIR);
    }

    public float getWind() {
        return entityData.get(DATA_WIND);
    }

    /** The helm's input bits: the helmsman's own client knows them first, everyone else via the server. */
    public int getInput() {
        return isControlledByLocalInstance() && level().isClientSide ? input : entityData.get(DATA_INPUT);
    }

    public void setSyncedInput(int bits) {
        entityData.set(DATA_INPUT, (byte) (bits & 15));
    }

    public int getSinking() {
        return entityData.get(DATA_SINKING);
    }

    public boolean isMoored() {
        return entityData.get(DATA_MOORED);
    }

    public void setMoored(boolean moored, String port) {
        entityData.set(DATA_MOORED, moored);
        this.port = port;
    }

    public String port() {
        return port;
    }

    /** Colours and sail ({@link ShipLivery}): pirates, the Imperial navy, Maormer, Sload, ghost ships... */
    public ShipLivery getLivery() {
        return ShipLivery.byId(entityData.get(DATA_LIVERY));
    }

    public void setLivery(ShipLivery livery) {
        entityData.set(DATA_LIVERY, livery.ordinal());
    }

    @Nullable
    public Autopilot autopilot() {
        return autopilot;
    }

    /** Hands the helm to an NPC captain (null: nobody steers). */
    public void setAutopilot(@Nullable Autopilot autopilot) {
        this.autopilot = autopilot;
        if (autopilot == null && !level().isClientSide) setSyncedInput(0);
    }

    public boolean isTransient() {
        return transientShip;
    }

    /** Sea traffic: not saved with the chunk. A captured ship is made permanent again with {@code false}. */
    public void setTransient(boolean value) {
        this.transientShip = value;
    }

    @Override
    public boolean shouldBeSaved() {
        return !transientShip && super.shouldBeSaved();
    }

    /** The carry frame catches up with the ship's pose (end of a tick, see {@link DeckPhysics}). */
    public void syncCarryPose() {
        carryX = getX();
        carryY = getY();
        carryZ = getZ();
        carryYaw = getYRot();
        carryReady = true;
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        syncCarryPose();
        DeckPhysics.track(this);
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        DeckPhysics.untrack(this);
    }

    public int getHurtTime() {
        return entityData.get(DATA_HURT);
    }

    public SimpleContainer cargo() {
        return cargo;
    }

    /** Set by the helmsman's client every tick (see {@code SailingClientEvents}). */
    public void setInput(boolean forward, boolean back, boolean left, boolean right) {
        input = (forward ? IN_FORWARD : 0) | (back ? IN_BACK : 0) | (left ? IN_LEFT : 0) | (right ? IN_RIGHT : 0);
    }

    public int localInput() {
        return input;
    }

    public float deltaYaw() {
        return deltaYaw;
    }

    // ------------------------------------------------------------------ multipart

    @Override
    public boolean isMultipartEntity() {
        return parts.length > 0;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return parts;
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        for (int i = 0; i < parts.length; i++) parts[i].setId(id + i + 1);
    }

    private Vec3 forward() {
        float r = getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(r), 0, Mth.cos(r));
    }

    /** Ship-local (x = port, y = up, z = bow) to world, at the given position and yaw. */
    public Vec3 toWorld(double lx, double ly, double lz, double px, double py, double pz, float yaw) {
        float r = yaw * Mth.DEG_TO_RAD;
        double s = Math.sin(r), c = Math.cos(r); // exact: deck riders are carried through these every tick
        return new Vec3(px + lx * c - lz * s, py + ly, pz + lx * s + lz * c);
    }

    public Vec3 toWorld(double lx, double ly, double lz) {
        return toWorld(lx, ly, lz, getX(), getY(), getZ(), getYRot());
    }

    /** World to ship-local at the current pose (the inverse of {@link #toWorld(double, double, double)}). */
    public Vec3 toLocal(double x, double y, double z) {
        float r = getYRot() * Mth.DEG_TO_RAD;
        double s = Math.sin(r), c = Math.cos(r); // exact: deck riders are carried through these every tick
        double dx = x - getX(), dz = z - getZ();
        return new Vec3(dx * c + dz * s, y - getY(), -dx * s + dz * c);
    }

    private void updateParts() {
        for (ShipPart p : parts) {
            Vec3 w = toWorld(0, 0, p.offset);
            p.xo = p.getX();
            p.yo = p.getY();
            p.zo = p.getZ();
            p.xOld = p.xo;
            p.yOld = p.yo;
            p.zOld = p.zo;
            p.setPos(w.x, w.y, w.z);
        }
    }

    /**
     * Whether one end of the hull ({@code end} +1 = bow, -1 = stern) would be in a block at a hypothetical position and
     * yaw (raised a little off the keel, so a sandy bed under the hull doesn't count).
     */
    private boolean endBlocked(double x, double y, double z, float yaw, int end) {
        float hw = shipType.hitWidth() / 2f;
        double top = y + Math.min(shipType.height, 1.2);
        float off = parts.length > 0 ? shipType.length / 2f - hw * 0.6f : shipType.length / 2f - 0.5f;
        Vec3 c = toWorld(0, 0, off * end, x, y, z, yaw);
        AABB box = new AABB(c.x - hw * 0.7, y + 0.15, c.z - hw * 0.7, c.x + hw * 0.7, top, c.z + hw * 0.7);
        return level().getBlockCollisions(this, box).iterator().hasNext();
    }

    /** A turn is refused only when it swings an end into something it wasn't already touching. */
    private boolean turnBlocked(float newYaw) {
        for (int end : new int[]{1, -1}) {
            if (endBlocked(getX(), getY(), getZ(), newYaw, end) && !endBlocked(getX(), getY(), getZ(), getYRot(), end)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        int hurt = getHurtTime();
        if (hurt > 0) entityData.set(DATA_HURT, hurt - 1);
        if (crashCooldown > 0) crashCooldown--;
        super.tick();
        tickLerp();

        rowPhaseO = rowPhase;
        sailDeployO = sailDeploy;
        rudderO = rudder;
        int bits = getInput();
        if ((bits & (IN_FORWARD | IN_BACK)) != 0 && shipType.hasOars()) rowPhase += (bits & IN_BACK) != 0 ? -0.22f : 0.26f;
        sailDeploy += Mth.clamp(getSail() / 2f - sailDeploy, -0.04f, 0.04f);
        float rudderTarget = (bits & IN_LEFT) != 0 ? 1f : (bits & IN_RIGHT) != 0 ? -1f : 0f;
        rudder += (rudderTarget - rudder) * 0.2f;

        if (getSinking() > 0) {
            tickSinking();
            updateParts();
            return;
        }
        if (!level().isClientSide && autopilot != null && getSinking() == 0) autopilot.tick(this);
        if (isRemoved()) return;
        boolean broke = !isMoored() && breakIce();
        if (isMoored()) {
            setDeltaMovement(Vec3.ZERO);
        } else if (isControlledByLocalInstance()) {
            if (broke) setDeltaMovement(getDeltaMovement().multiply(0.8, 1, 0.8));
            sail();
            double speed = getDeltaMovement().horizontalDistance();
            move(MoverType.SELF, getDeltaMovement());
            if (horizontalCollision && !minorHorizontalCollision) crash(speed);
        } else {
            setDeltaMovement(Vec3.ZERO);
        }
        updateParts();
        tryCheckInsideBlocks();
        if (!level().isClientSide) serverTick();
        else clientEffects();
    }

    /** One tick of seamanship on the controlling side. */
    private void sail() {
        Vec3 f = forward();
        Vec3 side = new Vec3(f.z, 0, -f.x);
        Vec3 v = getDeltaMovement();
        double vf = v.x * f.x + v.z * f.z;
        double vs = v.x * side.x + v.z * side.z;
        double vy;
        double surface = waterSurface();
        int bits = level().isClientSide ? input : autopilot != null && getControllingPassenger() == null ? autopilot.helm(this) & 15 : 0;
        if (!level().isClientSide && autopilot != null && entityData.get(DATA_INPUT) != (byte) bits) setSyncedInput(bits);
        boolean afloat = !Double.isNaN(surface) && surface > getY() + shipType.draft * 0.35;
        if (afloat) {
            double thrust = 0;
            if (shipType.hasOars()) {
                if ((bits & IN_FORWARD) != 0) thrust += shipType.oarSpeed * shipType.drag;
                if ((bits & IN_BACK) != 0) thrust -= shipType.oarSpeed * 0.45 * shipType.drag;
            }
            float sail = getSail() / 2f;
            if (sail > 0) {
                float off = Mth.wrapDegrees(getWindDir() - getYRot());
                float eff = shipType.sailEfficiency(off);
                thrust += shipType.sailSpeed * shipType.drag * getWind() * eff * sail;
                // leeway: the wind sets the ship a little sideways
                float wr = getWindDir() * Mth.DEG_TO_RAD;
                double wx = -Mth.sin(wr), wz = Mth.cos(wr);
                vs += (wx * side.x + wz * side.z) * getWind() * sail * 0.0025;
            }
            vf = vf * (1 - shipType.drag) + thrust;
            vf = Mth.clamp(vf, -shipType.maxSpeed * 0.35, shipType.maxSpeed);
            vs *= 0.82;
            double targetY = surface - shipType.draft;
            vy = Mth.clamp((targetY - getY()) * 0.3, -0.25, 0.12);
            float steer = ((bits & IN_RIGHT) != 0 ? 1f : 0f) - ((bits & IN_LEFT) != 0 ? 1f : 0f);
            float steerage = 0.3f + 0.7f * (float) Math.min(1, Math.abs(vf) / (shipType.maxSpeed * 0.45));
            deltaYaw += steer * shipType.turn * steerage;
        } else {
            // aground or on land: heavy friction, gravity
            vf *= 0.55;
            vs *= 0.55;
            vy = onGround() ? 0 : Math.max(v.y - 0.04, -0.6);
        }
        deltaYaw *= 0.86f;

        // the bow or stern meets the shore: stop (bounce a little) instead of sliding into it
        double nx = getX() + f.x * vf + side.x * vs, nz = getZ() + f.z * vf + side.z * vs;
        // only the leading end can run into something: a stern resting against a pier must not pin the ship
        int lead = vf >= 0 ? 1 : -1;
        if ((Math.abs(vf) > 1e-4 || Math.abs(vs) > 1e-4) && endBlocked(nx, getY() + Math.max(0, vy), nz, getYRot(), lead)) {
            if (Math.abs(vf) > 0.17) crash(Math.abs(vf));
            vf = -vf * 0.2;
            vs = 0;
        }
        if (Math.abs(deltaYaw) > 1e-3 && turnBlocked(getYRot() + deltaYaw)) {
            deltaYaw = 0;
        }
        setYRot(getYRot() + deltaYaw);
        setDeltaMovement(f.x * vf + side.x * vs, vy, f.z * vf + side.z * vs);
    }

    /**
     * Ships are icebreakers: sea ice (the frozen Sea of Ghosts) at the waterline around the hull and just ahead of the
     * bow is broken into open water, at the cost of some way. Done on both sides (the helmsman's client predicts the
     * server's change, so the hull is not stopped by ice that is about to break).
     */
    private boolean breakIce() {
        Vec3 f = forward();
        double speed = getDeltaMovement().horizontalDistance();
        float half = shipType.length / 2f + 1.0f + (float) speed * 3f;
        float side = shipType.beam / 2f + 0.6f;
        int y0 = Mth.floor(getY() + shipType.draft - 1.0), y1 = Mth.floor(getY() + shipType.draft + 0.6);
        boolean broke = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (float a = -half + 1; a <= half; a += 0.8f) {
            for (float s = -side; s <= side; s += 0.8f) {
                Vec3 w = toWorld(s, 0, a);
                for (int y = y0; y <= y1; y++) {
                    p.set(Mth.floor(w.x), y, Mth.floor(w.z));
                    var state = level().getBlockState(p);
                    if (!state.is(net.minecraft.tags.BlockTags.ICE)) continue;
                    if (!level().isClientSide) level().levelEvent(2001, p, net.minecraft.world.level.block.Block.getId(state));
                    level().setBlock(p, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
                    BlockPos up = p.above();
                    if (level().getBlockState(up).is(net.minecraft.world.level.block.Blocks.SNOW)) {
                        level().setBlock(up, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                    broke = true;
                }
            }
        }
        return broke;
    }

    /** Average water surface under the hull (bow, middle, stern), or NaN when there is none. */
    private double waterSurface() {
        Vec3 f = forward();
        double sum = 0;
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        float half = shipType.length * 0.35f;
        boolean middle = false;
        for (float off : new float[]{0, half, -half}) {
            double x = getX() + f.x * off, z = getZ() + f.z * off;
            int top = Mth.floor(getY() + shipType.draft + 1.2);
            int bottom = Mth.floor(getY() - 0.6);
            for (int y = top; y >= bottom; y--) {
                p.set(Mth.floor(x), y, Mth.floor(z));
                FluidState fs = level().getFluidState(p);
                if (fs.is(FluidTags.WATER)) {
                    sum += y + fs.getHeight(level(), p);
                    n++;
                    if (off == 0) middle = true;
                    break;
                }
            }
        }
        return n == 0 || !middle ? Double.NaN : sum / n;
    }

    /** A hard knock against the shore or a wreck: the controller reports it, the server applies the damage. */
    private void crash(double speed) {
        if (crashCooldown > 0 || speed < 0.17) return;
        crashCooldown = 20;
        float damage = (float) ((speed - 0.15) * SailingConfig.COLLISION_DAMAGE.get());
        if (damage <= 0) return;
        if (level().isClientSide) {
            SkyNetwork.sendToServer(new SailingPackets.ShipCrash(getId(), (float) speed));
            level().playLocalSound(getX(), getY(), getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.NEUTRAL, 0.7f, 0.6f, false);
        } else {
            damageHull(damage, null);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.NEUTRAL, 0.7f, 0.6f);
        }
    }

    /** Server side of {@link SailingPackets.ShipCrash}. */
    void reportedCrash(ServerPlayer reporter, float speed) {
        if (getControllingPassenger() != reporter || crashCooldown > 0) return;
        crashCooldown = 20;
        float s = Math.min(speed, shipType.maxSpeed + 0.1f);
        float damage = (float) ((s - 0.15) * SailingConfig.COLLISION_DAMAGE.get());
        if (damage > 0) damageHull(damage, null);
    }

    private void serverTick() {
        ServerLevel level = (ServerLevel) level();
        if ((tickCount & 1) == 0) {
            var entries = DeckPhysics.deckEntries(level, this);
            if (!entries.isEmpty()) {
                SkyNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> this),
                        new SailingPackets.DeckSync(getId(), entries));
            }
        }
        if ((tickCount + getId()) % 20 == 0) {
            float[] wind = SeaWind.at(level, getX(), getZ());
            entityData.set(DATA_WIND_DIR, wind[0]);
            entityData.set(DATA_WIND, wind[1]);
        }
        if ((tickCount + getId()) % 100 == 0 && SailingConfig.STORM_DAMAGE.get() && !isMoored()) {
            var kind = SeaWind.weatherAt(level, blockPosition());
            float coast = level.dimension() == Level.OVERWORLD ? TamrielMap.sampleScratch(getBlockX(), getBlockZ()).coastDistance : 0;
            if (SeaWind.stormy(kind) && coast < -48 && isInWater()) {
                float dmg = 0.5f + getSail() * 0.75f;
                damageHull(dmg, null);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1.0f, 0.6f);
                if (!stormWarned) {
                    stormWarned = true;
                    for (Entity e : getPassengers()) {
                        if (e instanceof ServerPlayer sp) {
                            Notifier.message(sp, Component.translatable(getSail() > 0 ? "sailing.skycraft.storm_sail" : "sailing.skycraft.storm"));
                        }
                    }
                }
            } else {
                stormWarned = false;
            }
        }
        // a lost ship (no crew, nobody around for a long time) never despawns: ships are property
    }

    private void clientEffects() {
        if (getLivery() == ShipLivery.GHOST && random.nextInt(3) == 0) {
            // a ghost ship trails cold mist and drifting soul-lights
            Vec3 p = toWorld((random.nextFloat() - 0.5) * shipType.beam * 1.2, shipType.draft + random.nextFloat() * 3,
                    (random.nextFloat() - 0.5) * shipType.length);
            level().addParticle(random.nextInt(4) == 0 ? ParticleTypes.SOUL : ParticleTypes.WHITE_ASH, p.x, p.y, p.z, 0, 0.02, 0);
        }
        double speed = Math.hypot(getX() - xo, getZ() - zo);
        Vec3 f = forward();
        if (speed > 0.12 && isInWater() && random.nextFloat() < speed * 2.5) {
            Vec3 bow = toWorld((random.nextFloat() - 0.5) * shipType.beam * 0.8, shipType.draft + 0.05, shipType.length * 0.48);
            level().addParticle(ParticleTypes.SPLASH, bow.x, bow.y, bow.z, f.x * 0.1, 0.12, f.z * 0.1);
            if (random.nextInt(3) == 0) {
                Vec3 wake = toWorld((random.nextFloat() - 0.5) * shipType.beam, shipType.draft, -shipType.length * 0.5);
                level().addParticle(ParticleTypes.BUBBLE_POP, wake.x, wake.y, wake.z, 0, 0.02, 0);
            }
        }
        if (++soundTimer >= 40) {
            soundTimer = 0;
            if ((getInput() & (IN_FORWARD | IN_BACK)) != 0 && shipType.hasOars()) {
                level().playLocalSound(getX(), getY(), getZ(), SoundEvents.BOAT_PADDLE_WATER, SoundSource.NEUTRAL, 0.8f, 0.8f + random.nextFloat() * 0.3f, false);
            } else if (random.nextInt(4) == 0) {
                level().playLocalSound(getX(), getY(), getZ(), SoundEvents.WOOD_STEP, SoundSource.NEUTRAL, 0.5f, 0.5f + random.nextFloat() * 0.2f, false);
            }
            if (getSail() > 0 && getWind() > 0.7f && random.nextInt(3) == 0) {
                level().playLocalSound(getX(), getY() + 4, getZ(), SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, 0.7f, 0.6f, false);
            }
        }
    }

    private void tickLerp() {
        if (isControlledByLocalInstance()) {
            lerpSteps = 0;
            syncPacketPositionCodec(getX(), getY(), getZ());
        }
        if (lerpSteps > 0) {
            double x = getX() + (lerpX - getX()) / lerpSteps;
            double y = getY() + (lerpY - getY()) / lerpSteps;
            double z = getZ() + (lerpZ - getZ()) / lerpSteps;
            setYRot(getYRot() + Mth.wrapDegrees(lerpYRot - getYRot()) / lerpSteps);
            --lerpSteps;
            setPos(x, y, z);
            setRot(getYRot(), getXRot());
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        // short interpolation: people on deck are placed against this pose, so it must not trail the server far
        lerpSteps = 4;
    }

    // ------------------------------------------------------------------ damage, sinking

    public void damageHull(float amount, @Nullable Entity by) {
        if (level().isClientSide || getSinking() > 0 || isMoored()) return;
        setHull(getHull() - amount);
        entityData.set(DATA_HURT, 10);
        if (getHull() <= 0) startSinking();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || isRemoved()) return false;
        if (level().isClientSide) return true;
        if (isMoored() || getSinking() > 0) return false;
        Entity attacker = source.getEntity();
        if (attacker != null && hasPassenger(attacker) && !(attacker instanceof Player p && p.isShiftKeyDown())) return false;
        // a swing at someone on deck that catches the hull underfoot: deckhands don't hack at their own ship
        if (attacker instanceof Player p && !p.isShiftKeyDown() && DeckPhysics.deckShip(p) == this && source.getDirectEntity() == p) return false;
        if (attacker instanceof Player p && p.isShiftKeyDown() && getPassengers().isEmpty()
                && (p.getAbilities().instabuild || owner == null || owner.equals(p.getUUID()))) {
            dismantle(p);
            return true;
        }
        float factor;
        if (source.is(DamageTypeTags.IS_EXPLOSION)) factor = 2.5f;
        else if (source.is(DamageTypeTags.IS_FIRE)) factor = 1.2f;
        else if (source.is(DamageTypeTags.IS_PROJECTILE)) factor = 0.5f;
        else if (attacker instanceof Player p) factor = p.getAbilities().instabuild ? 10f : 0.35f;
        else factor = 0.6f;
        damageHull(amount * factor, attacker);
        markHurt();
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.NEUTRAL, 0.4f, 1.2f);
        return true;
    }

    private void startSinking() {
        entityData.set(DATA_SINKING, 1);
        setSail(SAIL_FURLED);
        for (Entity e : List.copyOf(getPassengers())) {
            if (e instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("sailing.skycraft.sinking"));
        }
        ejectPassengers();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack s = cargo.removeItemNoUpdate(i);
            if (!s.isEmpty()) spawnAtLocation(s, 1.5f);
        }
        com.skycraft.inventory.ContainerOverflow.spill(level(), blockPosition(), cargo.bulk);
        spawnAtLocation(new ItemStack(Items.OAK_PLANKS, 2 + random.nextInt(4)), 1.2f);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.NEUTRAL, 1.0f, 0.6f);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1.2f, 0.5f);
    }

    private void tickSinking() {
        if (level().isClientSide) {
            if (random.nextInt(2) == 0) {
                Vec3 p = toWorld((random.nextFloat() - 0.5) * shipType.beam, shipType.draft + 0.3, (random.nextFloat() - 0.5) * shipType.length);
                level().addParticle(ParticleTypes.BUBBLE_COLUMN_UP, p.x, p.y, p.z, 0, 0.1, 0);
            }
            return;
        }
        int t = getSinking() + 1;
        entityData.set(DATA_SINKING, t);
        if (t > 20) setPos(getX(), getY() - 0.025 - t * 0.0002, getZ());
        if (t > SINK_TICKS) discard();
    }

    private void dismantle(Player player) {
        ItemStack item = new ItemStack(SailingRegistry.SHIP_ITEMS.get(shipType).get());
        if (getHull() < shipType.maxHull) item.getOrCreateTag().putFloat("Hull", getHull());
        if (hasCustomName()) item.setHoverName(getCustomName());
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack s = cargo.removeItemNoUpdate(i);
            if (!s.isEmpty()) spawnAtLocation(s, 1f);
        }
        com.skycraft.inventory.ContainerOverflow.spill(level(), blockPosition(), cargo.bulk);
        if (!player.getAbilities().instabuild || !com.skycraft.inventory.Inv.has(player, item.getItem())) {
            ItemStack rest = com.skycraft.inventory.Pack.give(player, item);
            if (!rest.isEmpty()) spawnAtLocation(rest, 1f);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.WOOD_BREAK, SoundSource.NEUTRAL, 1f, 0.8f);
        discard();
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (getSinking() > 0) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (isMoored()) {
            if (!level().isClientSide && player instanceof ServerPlayer sp) {
                Notifier.message(sp, Component.translatable("sailing.skycraft.moored_ferry"));
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (held.is(ItemTags.PLANKS) && getHull() < shipType.maxHull) {
            if (!level().isClientSide) {
                setHull(getHull() + shipType.maxHull / 12f);
                if (!player.getAbilities().instabuild) held.shrink(1);
                level().playSound(null, getX(), getY(), getZ(), SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 1f, 0.9f);
                if (player instanceof ServerPlayer sp) {
                    Notifier.message(sp, Component.translatable("sailing.skycraft.repaired", Math.round(getHull()), Math.round(shipType.maxHull)));
                }
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (player.isSecondaryUseActive()) {
            if (!level().isClientSide) openCustomInventoryScreen(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (boardAnimals(player)) return InteractionResult.CONSUME;
        if (player.getVehicle() instanceof AbstractHorse horse && shipType.horseSeats > 0 && countHorses() < shipType.horseSeats) {
            player.stopRiding();
            horse.startRiding(this, true);
        }
        if (hasPassenger(player)) return InteractionResult.PASS;
        return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** Leashed horses (and other leashed animals) the player brings to the ship go aboard. */
    private boolean boardAnimals(Player player) {
        boolean any = false;
        for (Mob mob : level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(10),
                m -> m.isLeashed() && m.getLeashHolder() == player && !m.isPassenger())) {
            if (canAddPassenger(mob)) {
                mob.dropLeash(true, !player.getAbilities().instabuild);
                if (mob.startRiding(this, true)) any = true;
            }
        }
        return any;
    }

    @Override
    public void openCustomInventoryScreen(Player player) {
        if (shipType.cargoSlots <= 0) return;
        int rows = Math.max(1, Math.min(6, shipType.cargoSlots / 9));
        MenuType<?> menu = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(menu, id, inv, cargo, rows) {
            @Override
            public boolean stillValid(Player who) {
                return ShipEntity.this.isAlive() && who.distanceToSqr(ShipEntity.this) < (shipType.length + 6) * (shipType.length + 6);
            }
        }, Component.translatable("sailing.skycraft.cargo", getDisplayName())));
    }

    // ------------------------------------------------------------------ passengers

    private static boolean isLarge(Entity e) {
        return e instanceof AbstractHorse || (!(e instanceof Player) && e.getBbWidth() > 1.0f);
    }

    private int countHorses() {
        int n = 0;
        for (Entity e : getPassengers()) if (isLarge(e)) n++;
        return n;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (isMoored() || getSinking() > 0) return false;
        if (autopilot != null && passenger instanceof Player) return false;
        if (isLarge(passenger)) return countHorses() < shipType.horseSeats;
        return getPassengers().size() - countHorses() < shipType.seats.length;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        for (Entity e : getPassengers()) {
            if (isLarge(e)) continue;
            return e instanceof Player p ? p : null;
        }
        return null;
    }

    /** The seat (ship-local) of a passenger. */
    public Vec3 seatOf(Entity passenger) {
        int seat = 0, horse = 0;
        for (Entity e : getPassengers()) {
            boolean large = isLarge(e);
            if (e == passenger) {
                if (large) return shipType.horseSpots.length > 0 ? shipType.horseSpots[Math.min(horse, shipType.horseSpots.length - 1)] : Vec3.ZERO;
                return shipType.seats[Math.min(seat, shipType.seats.length - 1)];
            }
            if (large) horse++;
            else seat++;
        }
        return Vec3.ZERO;
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 seat = seatOf(passenger);
        double bob = 0;
        if (getSinking() > 0) bob = -getSinking() * 0.01;
        Vec3 w = toWorld(seat.x, 0, seat.z);
        double y = getY() + seat.y + bob + (passenger instanceof Player ? -0.2 : passenger.getMyRidingOffset());
        move.accept(passenger, w.x, y, w.z);
        if (deltaYaw != 0) {
            passenger.setYRot(passenger.getYRot() + deltaYaw);
            passenger.setYHeadRot(passenger.getYHeadRot() + deltaYaw);
        }
        if (isLarge(passenger)) {
            passenger.setYRot(getYRot());
            passenger.setYBodyRot(getYRot());
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide && getControllingPassenger() == null) setSyncedInput(0);
        if (level().isClientSide && getControllingPassenger() == null) input = 0;
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.4;
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Vec3 seat = seatOf(passenger);
        // stand up on the deck beside the seat (the decks are walkable, see DeckPhysics)
        if (getSinking() == 0 && !isLarge(passenger)) {
            ShipDeck.Deck deck = ShipDeck.of(shipType);
            for (double[] off : new double[][]{{0, 0}, {0, -0.7}, {0, 0.7}, {-0.6, 0}, {0.6, 0}}) {
                double lx = seat.x * 0.6 + off[0], lz = seat.z + off[1];
                float floor = deck.floorAt(lx, lz, seat.y + 0.6);
                if (Float.isNaN(floor)) continue;
                Vec3 at = toWorld(lx, floor + 0.01, lz);
                if (level().noCollision(passenger, passenger.getDimensions(Pose.STANDING).makeBoundingBox(at))) {
                    passenger.setPose(Pose.STANDING);
                    return at;
                }
            }
        }
        float half = shipType.beam / 2f + 1.2f;
        double[][] spots = {{half, seat.z}, {-half, seat.z}, {half, 0}, {-half, 0},
                {0, shipType.length / 2f + 1.2}, {0, -shipType.length / 2f - 1.2},
                {half + 1.5, seat.z}, {-half - 1.5, seat.z}};
        for (double[] s : spots) {
            Vec3 w = toWorld(s[0], 0, s[1]);
            BlockPos base = BlockPos.containing(w.x, getY() + shipType.height + 1, w.z);
            for (int dy = 0; dy <= 4; dy++) {
                BlockPos p = base.below(dy);
                double floor = level().getBlockFloorHeight(p);
                if (!DismountHelper.isBlockFloorValid(floor)) continue;
                if (!level().getFluidState(p).isEmpty() && floor <= 0) continue;
                Vec3 at = Vec3.upFromBottomCenterOf(p, floor);
                for (Pose pose : passenger.getDismountPoses()) {
                    if (DismountHelper.canDismountTo(level(), passenger, passenger.getLocalBoundsForPose(pose).move(at))) {
                        passenger.setPose(pose);
                        return at;
                    }
                }
            }
        }
        // at sea: over the side into the water
        Vec3 w = toWorld(half, 0, seat.z);
        return new Vec3(w.x, getY() + shipType.draft + 0.2, w.z);
    }

    // ------------------------------------------------------------------ misc entity behaviour

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.EVENTS;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(SailingRegistry.SHIP_ITEMS.get(shipType).get());
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        double r = shipType.length / 2.0 + 1;
        return new AABB(getX() - r, getY() - 1, getZ() - r, getX() + r, getY() + (shipType.hasSail() ? 13 : 3), getZ() + r);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSq) {
        double r = (shipType.length + 10) * 12 * getViewScale();
        return distSq < r * r;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
        resetFallDistance();
    }

    @Override
    public boolean fireImmune() {
        return false;
    }

    // ------------------------------------------------------------------ save

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Hull", getHull());
        tag.putInt("Sail", getSail());
        tag.putBoolean("Moored", isMoored());
        if (!port.isEmpty()) tag.putString("Port", port);
        if (owner != null) tag.putUUID("Owner", owner);
        ListTag items = new ListTag();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack s = cargo.getItem(i);
            if (s.isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) i);
            s.save(t);
            items.add(t);
        }
        tag.put("Cargo", items);
        if (!cargo.bulk.isEmpty()) tag.put("CargoBulk", cargo.bulk.save());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setHull(tag.contains("Hull") ? tag.getFloat("Hull") : shipType.maxHull);
        setSail(tag.getInt("Sail"));
        setMoored(tag.getBoolean("Moored"), tag.getString("Port"));
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ListTag items = tag.getList("Cargo", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag t = items.getCompound(i);
            int slot = t.getByte("Slot") & 255;
            ItemStack stack = ItemStack.of(t);
            if (stack.isEmpty()) continue;
            // a slot that no longer exists (a smaller hold) keeps its stack in the overflow: loading drops nothing
            if (slot < cargo.getContainerSize() && cargo.getItem(slot).isEmpty()) cargo.setItem(slot, stack);
            else cargo.bulk.add(stack);
        }
        cargo.bulk.load(tag.getCompound("CargoBulk"));
    }
}
