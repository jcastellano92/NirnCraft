package com.skycraft.society.entity;

import com.skycraft.core.Currency;
import com.skycraft.society.Barks;
import com.skycraft.society.Encounters;
import com.skycraft.society.NpcEntities;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * {@code skycraft:npc} / {@code skycraft:npc_fighter}: a person of Skyrim with a {@link NpcRole}. The role and skin
 * variant are synced to clients (renderer texture); everything else (pursuit target, group leader, destination,
 * home) is server-side state saved with the entity.
 */
public class NpcEntity extends PathfinderMob implements RangedAttackMob {
    private static final EntityDataAccessor<String> ROLE = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> FLAGS = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> CONVERSATION_PARTNER = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /** Packed appearance (see {@link com.skycraft.society.NpcLook}); -1 until chosen. */
    private static final EntityDataAccessor<Integer> LOOK = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    public static final int FLAG_PRISONER = 1;
    public static final int FLAG_CASTING = 2;
    public static final int FLAG_PLAYING = 4;

    public enum Style { NONE, MELEE, BOW, CASTER }

    @Nullable
    private NpcRole roleCache;
    /** A player this NPC was sent to kill (hit squads, vampire attacks, thugs, assassins). */
    @Nullable
    private UUID huntTarget;
    /** A player this NPC walks up to (couriers). */
    @Nullable
    private UUID seekTarget;
    /** The group member this NPC follows (escorts, prisoners, housecarls). */
    @Nullable
    private UUID leader;
    /** Where a traveling NPC is going. */
    @Nullable
    private BlockPos destination;
    @Nullable
    private BlockPos home;
    /** The bed this NPC claimed (see Settlers#claimBed); each townsperson has their own. */
    @Nullable
    private BlockPos bed;
    private boolean initialized;
    private long nextCombatBark;
    private long lastProvokedBy;
    @Nullable
    private UUID provoker;
    /** Courier: kind of letter carried (-1 = none). */
    private int letter = -1;
    /** Necromancer: skeletons already raised in the current fight. */
    private boolean raisedDead;
    /** Bard: a player asked for a song. */
    private boolean songRequested;
    /** Where this townsperson works (forge, counter, field, temple...), found once and re-checked daily. */
    @Nullable
    private BlockPos workplace;
    /** The inn (or meeting place) of this NPC's settlement: lunch and evenings. */
    @Nullable
    private BlockPos inn;
    /** Relationships with players, favours and marriage (see Relationships / Marriage); saved as is. */
    private CompoundTag social = new CompoundTag();
    /** What the daily routine is doing right now (server only, not saved). */
    public com.skycraft.society.Schedule.Activity activity = com.skycraft.society.Schedule.Activity.IDLE;
    /** Feared attacker: non-combatant civilians fear and flee this player until they leave. */
    @Nullable
    private UUID fearedPlayerUuid;

    @Nullable
    public UUID getFearedPlayer() {
        return fearedPlayerUuid;
    }

    public void setFearedPlayer(@Nullable UUID player) {
        this.fearedPlayerUuid = player;
    }

    public NpcEntity(EntityType<? extends NpcEntity> type, Level level) {
        super(type, level);
        this.xpReward = 5;
        this.setCanPickUpLoot(false);
        if (this.getNavigation() instanceof GroundPathNavigation nav) nav.setCanOpenDoors(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.1);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(ROLE, "");
        this.entityData.define(SKIN, 0);
        this.entityData.define(FLAGS, (byte) 0);
        this.entityData.define(CONVERSATION_PARTNER, -1);
        this.entityData.define(LOOK, -1);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (ROLE.equals(key)) {
            roleCache = null;
            this.refreshDimensions();
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new NpcGoals.Follower(this));
        this.goalSelector.addGoal(1, new NpcGoals.Panic(this));
        this.goalSelector.addGoal(2, new NpcGoals.Avoid(this));
        this.goalSelector.addGoal(2, new NpcGoals.Caster(this));
        this.goalSelector.addGoal(3, new NpcGoals.Bow(this));
        this.goalSelector.addGoal(3, new NpcGoals.Melee(this));
        this.goalSelector.addGoal(4, new NpcGoals.Travel(this));
        this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(5, new NpcGoals.Work(this));
        this.goalSelector.addGoal(6, new NpcGoals.Routine(this));
        this.goalSelector.addGoal(7, new MoveTowardsRestrictionGoal(this, 0.6D));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.55D) {
            @Override
            public boolean canUse() {
                return !NpcEntity.this.isBusy() && super.canUse();
            }
        });
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override
            public boolean canUse() {
                // a follower doesn't turn on its owner over a stray blow
                if (NpcEntity.this.getLastHurtByMob() instanceof Player p && com.skycraft.society.Followers.isFollowerOf(NpcEntity.this, p)) return false;
                return NpcEntity.this.canFight() && super.canUse();
            }
        });
        this.targetSelector.addGoal(1, new NpcGoals.FollowerDefend(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, this::isHostileTo) {
            @Override
            public boolean canUse() {
                return NpcEntity.this.canFight() && super.canUse();
            }
        });
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Animal.class, 20, true, false,
                e -> e.getType().is(NpcEntities.GAME) && !e.isBaby() && !e.hasCustomName() && !(e instanceof Mob m && m.isLeashed())) {
            @Override
            public boolean canUse() {
                return NpcEntity.this.role() == NpcRole.HUNTER && !NpcEntity.this.isPrisoner() && super.canUse();
            }
        });
    }

    // ------------------------------------------------------------------ role & looks

    public NpcRole role() {
        NpcRole r = roleCache;
        if (r == null) {
            r = NpcRole.byId(this.entityData.get(ROLE));
            if (r == null) return this.getType() == NpcEntities.NPC_FIGHTER.get() ? NpcRole.THUG : NpcRole.ADVENTURER;
            roleCache = r;
        }
        return r;
    }

    public boolean hasRole() {
        return NpcRole.byId(this.entityData.get(ROLE)) != null;
    }

    public void setRole(NpcRole role) {
        this.entityData.set(ROLE, role.id);
        roleCache = role;
        // soft contract read by other modules (e.g. survival's innkeeper/priest topics)
        this.getPersistentData().putString("skycraft_role", role.id);
    }

    public int getLook() {
        return this.entityData.get(LOOK);
    }

    public void setLook(int look) {
        this.entityData.set(LOOK, look);
        if (look >= 0) setSkin(com.skycraft.society.NpcLook.female(look) ? 1 : 0);
    }

    /** The NPC's race (from its look; Nord until one is chosen). */
    public com.skycraft.society.NpcNames.Race race() {
        int look = getLook();
        return look < 0 ? com.skycraft.society.NpcNames.Race.NORD : com.skycraft.society.NpcLook.race(look);
    }

    /** Children are small and can't be harmed. */
    public boolean isChild() {
        return role() == NpcRole.CHILD;
    }

    @Override
    public float getScale() {
        return isChild() ? 0.62F : 1.0F;
    }

    public int getSkin() {
        return this.entityData.get(SKIN);
    }

    public void setSkin(int skin) {
        this.entityData.set(SKIN, Math.floorMod(skin, NpcRole.SKINS));
    }

    public boolean isFemale() {
        return (getSkin() & 1) == 1;
    }

    private boolean flag(int f) {
        return (this.entityData.get(FLAGS) & f) != 0;
    }

    private void setFlag(int f, boolean on) {
        byte b = this.entityData.get(FLAGS);
        this.entityData.set(FLAGS, (byte) (on ? b | f : b & ~f));
    }

    public boolean isPrisoner() {
        return flag(FLAG_PRISONER);
    }

    public void setPrisoner(boolean prisoner) {
        setFlag(FLAG_PRISONER, prisoner);
        if (prisoner) {
            this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            this.setTarget(null);
        }
    }

    public boolean isCasting() {
        return flag(FLAG_CASTING);
    }

    public void setCasting(boolean casting) {
        setFlag(FLAG_CASTING, casting);
    }

    public boolean isPlaying() {
        return flag(FLAG_PLAYING);
    }

    public void setPlaying(boolean playing) {
        setFlag(FLAG_PLAYING, playing);
    }

    // ------------------------------------------------------------------ server state

    @Nullable
    public UUID getHuntTarget() {
        return huntTarget;
    }

    public void setHuntTarget(@Nullable UUID player) {
        this.huntTarget = player;
    }

    @Nullable
    public UUID getSeekTarget() {
        return seekTarget;
    }

    public void setSeekTarget(@Nullable UUID player) {
        this.seekTarget = player;
    }

    @Nullable
    public UUID getLeader() {
        return leader;
    }

    public void setLeader(@Nullable UUID leader) {
        this.leader = leader;
    }

    @Nullable
    public BlockPos getDestination() {
        return destination;
    }

    public void setDestination(@Nullable BlockPos destination) {
        this.destination = destination == null ? null : destination.immutable();
    }

    @Nullable
    public BlockPos getHome() {
        return home;
    }

    /** Townsfolk stay within {@code radius} blocks of their home. */
    public void setHome(BlockPos pos, int radius) {
        this.home = pos.immutable();
        this.restrictTo(this.home, radius);
        if (!this.level().isClientSide && this.isAddedToWorld()) com.skycraft.society.NpcRegistry.onSettled(this);
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!this.level().isClientSide && home != null) com.skycraft.society.NpcRegistry.onSettled(this);
    }

    @Nullable
    public BlockPos getBed() {
        return bed;
    }

    public void setBed(@Nullable BlockPos bed) {
        this.bed = bed == null ? null : bed.immutable();
    }

    @Nullable
    public BlockPos getWorkplace() {
        return workplace;
    }

    public void setWorkplace(@Nullable BlockPos workplace) {
        this.workplace = workplace == null ? null : workplace.immutable();
    }

    @Nullable
    public BlockPos getInn() {
        return inn;
    }

    public void setInn(@Nullable BlockPos inn) {
        this.inn = inn == null ? null : inn.immutable();
    }

    /** Mutable relationship data (dispositions, favours, marriage); see com.skycraft.society.Relationships. */
    public CompoundTag social() {
        return social;
    }

    public int getLetter() {
        return letter;
    }

    public void setLetter(int letter) {
        this.letter = letter;
    }

    public int getConversationPartner() {
        return this.entityData.get(CONVERSATION_PARTNER);
    }

    public void setConversationPartner(int partnerId) {
        this.entityData.set(CONVERSATION_PARTNER, partnerId);
    }

    public boolean isConversing() {
        return getConversationPartner() >= 0;
    }

    /** Walking somewhere on purpose (no idle strolling). */
    public boolean isBusy() {
        return isConversing() || isPrisoner() || huntTarget != null || seekTarget != null || leader != null || destination != null || isPlaying() || com.skycraft.society.Followers.isFollower(this);
    }

    public boolean willFightBack() {
        // Deterministic courage: roughly half of civilians will fight back when attacked
        boolean brave = (this.getUUID().hashCode() & 1) == 0;
        return role().combatant || (brave && this.getHealth() > this.getMaxHealth() * 0.25f);
    }

    public boolean canFight() {
        return willFightBack() && !isPrisoner();
    }

    public Style style() {
        if (!canFight() || isPrisoner()) return Style.NONE;
        if (this.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) return Style.BOW;
        if (role().caster && role() != NpcRole.THALMOR) return Style.CASTER;
        return Style.MELEE;
    }

    public boolean isEncounter() {
        return Encounters.isTagged(this);
    }

    // ------------------------------------------------------------------ hostility

    /** Whether this NPC attacks {@code e} on sight (server side). */
    public boolean isHostileTo(LivingEntity e) {
        if (e == this || !e.isAlive() || isPrisoner()) return false;
        NpcRole r = role();
        if (e instanceof Player p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (huntTarget != null && huntTarget.equals(p.getUUID()) && r.combatant) return true;
            return Npcs.hostileToPlayer(r, p);
        }
        if (e instanceof NpcEntity o) {
            if (o.isPrisoner()) return false;
            // story NPCs (Ralof, Hadvar...) never fight each other: they stand side by side as quest givers
            if (this.getPersistentData().contains(com.skycraft.quest.story.QuestNpcs.TAG) && o.getPersistentData().contains(com.skycraft.quest.story.QuestNpcs.TAG)) return false;
            NpcRole or = o.role();
            if (or == NpcRole.VAMPIRE && !Npcs.isNight(this.level()) && r != NpcRole.MAGE) return false;
            return NpcRole.opposed(r, or) || (r.protector && or.villain && o.getTarget() != null);
        }
        if (r.protector && e instanceof Enemy && !(e instanceof Creeper) && !(e instanceof net.minecraft.world.entity.NeutralMob)) {
            return !(e instanceof Mob m) || !m.isNoAi();
        }
        return false;
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        if (super.isAlliedTo(other)) return true;
        if (other instanceof NpcEntity o && !o.isPrisoner() && !isPrisoner()) {
            NpcRole a = role();
            NpcRole b = o.role();
            if (a == b) return true;
            return a.faction != null && a.faction.equals(b.faction);
        }
        return false;
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        LivingEntity previous = this.getTarget();
        super.setTarget(target);
        if (this.level().isClientSide || target == null || target == previous) return;
        if (target instanceof Player player) {
            long now = this.level().getGameTime();
            if (now >= nextCombatBark) {
                nextCombatBark = now + 300;
                Barks.combat(this, player);
            }
            if (role().villain) Npcs.alertGuards(this, player);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // like Skyrim's children: nothing but /kill and the void can hurt them
        if (isChild() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        Entity attacker = source.getEntity();
        if (attacker instanceof NpcEntity o && this.isAlliedTo(o)) return false;
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide && attacker instanceof LivingEntity living && living != this
                && !(living instanceof Player p && com.skycraft.society.Followers.isFollowerOf(this, p))) {
            provoker = living.getUUID();
            lastProvokedBy = this.level().getGameTime();
            Npcs.rallyAllies(this, living);
        }
        return hurt;
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            this.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 0.7f, 1.0f);
            if (role() == NpcRole.VAMPIRE) this.heal(2.0f); // life drain
        }
        return hit;
    }

    // ------------------------------------------------------------------ attacks

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), power);
        ItemStack bow = this.getMainHandItem();
        if (bow.getItem() instanceof BowItem bowItem) arrow = bowItem.customArrow(arrow);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontal * 0.2D, dz, 1.6F, (float) (14 - this.level().getDifficulty().getId() * 4));
        this.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    /** A fire charge that burns its victim but never sets the world on fire. */
    public void castFireball(LivingEntity target) {
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.5D) - this.getY(0.5D);
        double dz = target.getZ() - this.getZ();
        float spread = 0.2f;
        SmallFireball ball = new SmallFireball(this.level(), this, dx + this.getRandom().nextGaussian() * spread, dy,
                dz + this.getRandom().nextGaussian() * spread) {
            @Override
            protected void onHitBlock(BlockHitResult hit) {
                // spell fire fizzles on stone and wood alike
            }
        };
        ball.setPos(ball.getX(), this.getY(0.5D) + 0.5D, ball.getZ());
        this.level().addFreshEntity(ball);
        this.playSound(SoundEvents.BLAZE_SHOOT, 0.8f, 1.1f + this.getRandom().nextFloat() * 0.2f);
    }

    public void requestSong() {
        this.songRequested = true;
    }

    public boolean takeSongRequest() {
        boolean r = songRequested;
        songRequested = false;
        return r;
    }

    public boolean hasRaisedDead() {
        return raisedDead;
    }

    public void setRaisedDead(boolean raised) {
        this.raisedDead = raised;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        initialize();
        return data;
    }

    /** Picks role (if none was set), skin, name, gear and stats once. */
    public void initialize() {
        if (initialized || this.level().isClientSide) return;
        initialized = true;
        if (!hasRole()) setRole(Npcs.randomRole(this.getRandom(), this.getType() == NpcEntities.NPC_FIGHTER.get()));
        if (getLook() < 0) setSkin(this.getRandom().nextInt(NpcRole.SKINS));
        Npcs.outfit(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && !initialized) initialize();
        if (!this.level().isClientSide && getLook() < 0 && this.tickCount % 20 == 1) Npcs.ensureLook(this); // older saves
        if (!this.level().isClientSide && isConversing()) {
            this.getNavigation().stop();
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (isConversing()) {
            Entity partner = this.level().getEntity(getConversationPartner());
            if (partner == null || !partner.isAlive() || this.distanceToSqr(partner) > 64) {
                setConversationPartner(-1);
            } else {
                this.getNavigation().stop();
                this.getLookControl().setLookAt(partner, 30.0F, 30.0F);
            }
        }
        if (this.tickCount % 20 != 0) return;
        if (fearedPlayerUuid != null) {
            Player p = this.level().getPlayerByUUID(fearedPlayerUuid);
            if (p == null || !p.isAlive() || this.distanceToSqr(p) > 28.0 * 28.0) {
                fearedPlayerUuid = null; // calmed down after player left
            }
        }
        LivingEntity target = this.getTarget();
        if (target != null) {
            boolean provoked = provoker != null && provoker.equals(target.getUUID())
                    && this.level().getGameTime() - lastProvokedBy < 1200;
            if (!provoked && com.skycraft.society.Followers.isFollower(this) && target instanceof Mob m && m.getTarget() != null) {
                // a follower keeps fighting whatever is fighting it or its owner
                UUID owner = com.skycraft.society.Followers.getOwnerId(this);
                provoked = m.getTarget() == this || (owner != null && owner.equals(m.getTarget().getUUID()));
            }
            if (!provoked && com.skycraft.society.Followers.isFollower(this)) {
                UUID owner = com.skycraft.society.Followers.getOwnerId(this);
                Player p = owner == null ? null : this.level().getPlayerByUUID(owner);
                provoked = p != null && com.skycraft.society.Followers.isDefendTarget(this, p, target);
            }
            if (!target.isAlive() || !canFight() || (!provoked && !isHostileTo(target) && !(target instanceof Animal))) {
                this.setTarget(null);
                this.setAggressive(false);
            }
        } else {
            raisedDead = false;
            if (this.getHealth() < this.getMaxHealth() && this.tickCount % 60 == 0) this.heal(1.0f);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceSqr) {
        return isEncounter() && !com.skycraft.society.Followers.isFollower(this) && distanceSqr > 96 * 96;
    }

    /** Whether the item in {@code slot} was given by the follower's player (it always drops; see Followers). */
    public boolean isGivenGear(net.minecraft.world.entity.EquipmentSlot slot) {
        return this.getEquipmentDropChance(slot) > 1.0f;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        if (recentlyHit && role() != NpcRole.BEGGAR && this.getRandom().nextFloat() < 0.6f) {
            int gold = 3 + this.getRandom().nextInt(role().civilian ? 12 : 30);
            this.spawnAtLocation(Currency.coins(gold));
        }
    }

    /** Runs on every death, whatever doMobLoot says: a follower's bags (the player's items) always drop. */
    @Override
    protected void dropEquipment() {
        super.dropEquipment();
        com.skycraft.society.Followers.onDeath(this);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    // ------------------------------------------------------------------ save

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Role", role().id);
        tag.putInt("Skin", getSkin());
        tag.putByte("NpcFlags", (byte) (this.entityData.get(FLAGS) & FLAG_PRISONER));
        tag.putBoolean("SkycraftInit", initialized);
        if (huntTarget != null) tag.putUUID("Hunt", huntTarget);
        if (seekTarget != null) tag.putUUID("Seek", seekTarget);
        if (leader != null) tag.putUUID("Leader", leader);
        if (destination != null) tag.put("Destination", NbtUtils.writeBlockPos(destination));
        if (home != null) {
            tag.put("Home", NbtUtils.writeBlockPos(home));
            tag.putInt("HomeRadius", (int) this.getRestrictRadius());
        }
        if (letter >= 0) tag.putInt("Letter", letter);
        if (fearedPlayerUuid != null) tag.putUUID("FearedPlayer", fearedPlayerUuid);
        if (bed != null) tag.put("Bed", NbtUtils.writeBlockPos(bed));
        tag.putInt("Look", getLook());
        if (workplace != null) tag.put("Workplace", NbtUtils.writeBlockPos(workplace));
        if (inn != null) tag.put("Inn", NbtUtils.writeBlockPos(inn));
        if (!social.isEmpty()) tag.put("Social", social);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        NpcRole r = NpcRole.byId(tag.getString("Role"));
        if (r != null) setRole(r);
        setSkin(tag.getInt("Skin"));
        this.entityData.set(FLAGS, (byte) (tag.getByte("NpcFlags") & FLAG_PRISONER));
        initialized = tag.getBoolean("SkycraftInit");
        huntTarget = tag.hasUUID("Hunt") ? tag.getUUID("Hunt") : null;
        seekTarget = tag.hasUUID("Seek") ? tag.getUUID("Seek") : null;
        leader = tag.hasUUID("Leader") ? tag.getUUID("Leader") : null;
        destination = tag.contains("Destination") ? NbtUtils.readBlockPos(tag.getCompound("Destination")) : null;
        if (tag.contains("Home")) setHome(NbtUtils.readBlockPos(tag.getCompound("Home")), Math.max(8, tag.getInt("HomeRadius")));
        letter = tag.contains("Letter") ? tag.getInt("Letter") : -1;
        fearedPlayerUuid = tag.hasUUID("FearedPlayer") ? tag.getUUID("FearedPlayer") : null;
        bed = tag.contains("Bed") ? NbtUtils.readBlockPos(tag.getCompound("Bed")) : null;
        if (tag.contains("Look")) setLook(tag.getInt("Look"));
        workplace = tag.contains("Workplace") ? NbtUtils.readBlockPos(tag.getCompound("Workplace")) : null;
        inn = tag.contains("Inn") ? NbtUtils.readBlockPos(tag.getCompound("Inn")) : null;
        social = tag.getCompound("Social");
    }

    @Override
    public void remove(RemovalReason reason) {
        // killed or discarded for good (not just unloaded): free the bed for someone else
        if (reason.shouldDestroy() && !this.level().isClientSide) {
            com.skycraft.society.Settlers.releaseBed(this);
            if (reason == RemovalReason.KILLED) com.skycraft.society.Relationships.onDeath(this);
        }
        super.remove(reason);
    }
}
