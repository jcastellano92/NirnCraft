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
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
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
    /** Active spell id from {@link com.skycraft.magic.spell.Spells} while {@link #FLAG_CASTING} is set. */
    private static final EntityDataAccessor<String> CASTING_SPELL = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    public static final int FLAG_PRISONER = 1;
    public static final int FLAG_CASTING = 2;
    public static final int FLAG_PLAYING = 4;
    // aftermath poses (com.skycraft.fire.aftermath): synced so the client model can pose the body; saved with the NPC
    /** Kneeling (hips low, legs folded back). */
    public static final int FLAG_KNEEL = 8;
    /** Sitting huddled on the ground, knees up, head down. */
    public static final int FLAG_HUDDLE = 16;
    /** Bent over somebody (a healer at a bedside, a mourner). */
    public static final int FLAG_TEND = 32;
    /** Arms out in front, carrying or working (a shovel, a bucket, a plank). */
    public static final int FLAG_CARRY = 64;
    /** Crying: hands at the face, tears. */
    public static final int FLAG_WEEP = 128;
    /** The flags that are saved with the NPC. */
    private static final int SAVED_FLAGS = FLAG_PRISONER | FLAG_KNEEL | FLAG_HUDDLE | FLAG_TEND | FLAG_CARRY | FLAG_WEEP;

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
    /** Caster: whether Oakflesh/Stoneflesh was already cast in the current fight. */
    private boolean fleshCast;
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
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.FIRE, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.FIRE_IN_NEIGHBOR, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LAVA, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGING, -1.0F);
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
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ROLE, "");
        builder.define(SKIN, 0);
        builder.define(FLAGS, (byte) 0);
        builder.define(CONVERSATION_PARTNER, -1);
        builder.define(LOOK, -1);
        builder.define(CASTING_SPELL, "");
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (ROLE.equals(key)) {
            roleCache = null;
            this.refreshDimensions();
        } else if (FLAGS.equals(key)) {
            this.refreshDimensions();
        }
    }

    /** Kneeling and huddled people are shorter: their hitbox follows the pose. */
    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions d = super.getDefaultDimensions(pose);
        if (this.entityData == null) return d;
        int f = this.entityData.get(FLAGS);
        if ((f & FLAG_HUDDLE) != 0) return net.minecraft.world.entity.EntityDimensions.scalable(d.width(), d.height() * 0.52f);
        if ((f & FLAG_KNEEL) != 0) return net.minecraft.world.entity.EntityDimensions.scalable(d.width(), d.height() * 0.68f);
        return d;
    }

    public boolean isKneeling() {
        return flag(FLAG_KNEEL);
    }

    public boolean isHuddled() {
        return flag(FLAG_HUDDLE);
    }

    public boolean isTending() {
        return flag(FLAG_TEND);
    }

    public boolean isCarrying() {
        return flag(FLAG_CARRY);
    }

    public boolean isWeeping() {
        return flag(FLAG_WEEP);
    }

    /**
     * Whether the client model bends this NPC into the crouch body pose: the aftermath kneeling and tending poses,
     * or a real sneak. Used by {@code NpcModel} every frame (it assigns, never just sets) and checked headless by
     * the intro self-test: no ordinary townsperson may ever answer true.
     */
    public boolean showsCrouchPose() {
        return isKneeling() || isTending() || this.isCrouching();
    }

    /** The aftermath pose bits (kneel, huddle, tend, carry, weep) that are set; 0 for an ordinary townsperson. */
    public int aftermathPoseFlags() {
        return this.entityData.get(FLAGS) & (FLAG_KNEEL | FLAG_HUDDLE | FLAG_TEND | FLAG_CARRY | FLAG_WEEP);
    }

    /** Sets or clears one of the aftermath pose flags ({@link #FLAG_KNEEL} ... {@link #FLAG_WEEP}). */
    public void setPoseFlag(int poseFlag, boolean on) {
        setFlag(poseFlag, on);
    }

    /** Replaces all aftermath pose flags at once (the prisoner / playing flags are kept). */
    public void setPoseFlags(int flags) {
        int keep = this.entityData.get(FLAGS) & ~(FLAG_KNEEL | FLAG_HUDDLE | FLAG_TEND | FLAG_CARRY | FLAG_WEEP);
        this.entityData.set(FLAGS, (byte) (keep | (flags & (FLAG_KNEEL | FLAG_HUDDLE | FLAG_TEND | FLAG_CARRY | FLAG_WEEP))));
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new com.skycraft.fire.aftermath.TableauGoal(this));
        this.goalSelector.addGoal(1, new NpcGoals.Follower(this));
        this.goalSelector.addGoal(1, new NpcGoals.Panic(this));
        this.goalSelector.addGoal(2, new NpcGoals.Avoid(this));
        this.goalSelector.addGoal(2, new NpcGoals.Shelter(this));
        this.goalSelector.addGoal(11, new NpcGoals.Spread(this));
        this.goalSelector.addGoal(2, new NpcGoals.Caster(this));
        this.goalSelector.addGoal(3, new NpcGoals.Bow(this));
        this.goalSelector.addGoal(3, new NpcGoals.Melee(this));
        this.goalSelector.addGoal(4, new NpcGoals.Travel(this));
        this.goalSelector.addGoal(5, new NpcGoals.Door(this));
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
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, (target, level) -> this.isHostileTo(target)) {
            @Override
            public boolean canUse() {
                return NpcEntity.this.canFight() && super.canUse();
            }
        });
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Animal.class, 20, true, false,
                (e, level) -> e.getType().builtInRegistryHolder().is(NpcEntities.GAME) && !e.isBaby() && !e.hasCustomName() && !(e instanceof Mob m && m.isLeashed())) {
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

    /** Updates the NPC's look to use the specified {@link com.skycraft.society.NpcNames.Race}. */
    public void setRace(com.skycraft.society.NpcNames.Race race) {
        if (race == null) return;
        int cur = getLook();
        if (cur < 0) {
            setLook(com.skycraft.society.NpcLook.pack(race, isFemale(), 0, 1, 0, 0,
                    com.skycraft.society.Outfit.cultural(race), 0, isChild()));
        } else {
            setLook(com.skycraft.society.NpcLook.pack(race,
                    com.skycraft.society.NpcLook.raceVariant(cur),
                    com.skycraft.society.NpcLook.female(cur),
                    com.skycraft.society.NpcLook.tone(cur),
                    com.skycraft.society.NpcLook.hair(cur),
                    com.skycraft.society.NpcLook.hairColor(cur),
                    com.skycraft.society.NpcLook.beard(cur),
                    com.skycraft.society.NpcLook.outfit(cur),
                    com.skycraft.society.NpcLook.variant(cur),
                    com.skycraft.society.NpcLook.child(cur)));
        }
    }

    /** Children are small and can't be harmed. */
    public boolean isChild() {
        return role() == NpcRole.CHILD;
    }

    @Override
    public float getAgeScale() {
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
        if (!casting) this.entityData.set(CASTING_SPELL, "");
    }

    public String getCastingSpellId() {
        return this.entityData.get(CASTING_SPELL);
    }

    @Nullable
    public com.skycraft.magic.spell.Spell getCastingSpell() {
        String id = getCastingSpellId();
        return id == null || id.isEmpty() ? null : com.skycraft.magic.spell.Spells.byId(id);
    }

    public void startSpellWindup(String spellId) {
        this.entityData.set(CASTING_SPELL, spellId == null ? "" : spellId);
        setFlag(FLAG_CASTING, true);
        if (!this.level().isClientSide()) {
            this.playSound(com.skycraft.audio.AudioSounds.spell("charge"), 0.7f, 1.0f + this.getRandom().nextFloat() * 0.2f);
        }
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
        this.setHomeTo(this.home, radius);
        if (!this.level().isClientSide() && this.isAddedToLevel()) com.skycraft.society.NpcRegistry.onSettled(this);
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!this.level().isClientSide() && home != null) com.skycraft.society.NpcRegistry.onSettled(this);
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
    protected boolean considersEntityAsAlly(Entity other) {
        if (super.considersEntityAsAlly(other)) return true;
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
        if (this.level().isClientSide() || target == null || target == previous) return;
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
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
        // like Skyrim's children: nothing but /kill and the void can hurt them
        if (isChild() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        Entity attacker = source.getEntity();
        if (attacker == null && (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
                || source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR))
                && (!role().villain || !this.level().canSeeSky(this.blockPosition()))) {
            this.clearFire();
            if (!this.level().isClientSide()) com.skycraft.society.NpcPlacement.rescueFromFireOrSlab(this);
            return false;
        }
        if (attacker instanceof NpcEntity o && this.isAlliedTo(o)) return false;
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && !this.level().isClientSide() && attacker instanceof LivingEntity living && living != this
                && !(living instanceof Player p && com.skycraft.society.Followers.isFollowerOf(this, p))) {
            provoker = living.getUUID();
            lastProvokedBy = this.level().getGameTime();
            Npcs.rallyAllies(this, living);
        }
        return hurt;
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit) {
            this.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 0.7f, 1.0f);
            if (role() == NpcRole.VAMPIRE) this.heal(2.0f); // life drain
        }
        return hit;
    }

    // ------------------------------------------------------------------ attacks

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), power, this.getMainHandItem());
        ItemStack bow = this.getMainHandItem();
        if (bow.getItem() instanceof BowItem bowItem) arrow = bowItem.customArrow(arrow, new ItemStack(Items.ARROW), bow);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontal * 0.2D, dz, 1.6F, (float) (14 - this.level().getDifficulty().getId() * 4));
        this.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    /** Chooses an appropriate Skycraft spell from {@link com.skycraft.magic.spell.Spells} for this NPC's role and combat situation. */
    public String chooseCombatSpell(LivingEntity target) {
        NpcRole r = role();
        if (!fleshCast && (r == NpcRole.MAGE || r == NpcRole.NECROMANCER || r == NpcRole.THALMOR || r == NpcRole.PRIEST)) {
            return r == NpcRole.MAGE || r == NpcRole.THALMOR ? "stoneflesh" : "oakflesh";
        }
        if (this.getHealth() < this.getMaxHealth() * 0.5f) {
            return r == NpcRole.MAGE || r == NpcRole.PRIEST ? "close_wounds" : "fast_healing";
        }
        if (r == NpcRole.PRIEST || r == NpcRole.MAGE) {
            for (NpcEntity ally : this.level().getEntitiesOfClass(NpcEntity.class, this.getBoundingBox().inflate(10.0),
                    a -> a != this && a.isAlive() && this.isAlliedTo(a) && a.getHealth() < a.getMaxHealth() * 0.6f)) {
                return "healing_hands";
            }
        }
        if ((r == NpcRole.NECROMANCER || r == NpcRole.VAMPIRE) && !hasRaisedDead()) {
            return r == NpcRole.NECROMANCER ? "raise_zombie" : "conjure_familiar";
        }
        int roll = this.getRandom().nextInt(3);
        return switch (r) {
            case NECROMANCER, VAMPIRE -> roll == 0 ? "lightning_bolt" : "ice_spike";
            case THALMOR -> roll == 0 ? "chain_lightning" : (roll == 1 ? "lightning_bolt" : "firebolt");
            case PRIEST -> "sun_fire";
            default -> switch (this.getRandom().nextInt(5)) {
                case 0 -> "firebolt";
                case 1 -> "ice_spike";
                case 2 -> "lightning_bolt";
                case 3 -> "fireball";
                default -> "chain_lightning";
            };
        };
    }

    /** Casts a Skycraft spell from {@link com.skycraft.magic.spell.Spells} at {@code target} (or self/allies for Restoration/Alteration/Conjuration). */
    public void castSpell(LivingEntity target, String spellId) {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        com.skycraft.magic.spell.Spell spell = com.skycraft.magic.spell.Spells.byId(spellId != null && !spellId.isEmpty() ? spellId : "firebolt");
        if (spell == null) spell = com.skycraft.magic.spell.Spells.byId("firebolt");
        if (spell == null) return;

        switch (spell.id) {
            case "oakflesh", "stoneflesh" -> {
                fleshCast = true;
                var eff = "stoneflesh".equals(spell.id)
                        ? com.skycraft.magic.MagicRegistry.STONEFLESH.get()
                        : com.skycraft.magic.MagicRegistry.OAKFLESH.get();
                this.addEffect(new net.minecraft.world.effect.MobEffectInstance(com.skycraft.platform.Effects.holder(eff), spell.duration, Math.max(0, (int) spell.magnitude - 1)));
                com.skycraft.magic.MagicFx.send(this, com.skycraft.magic.MagicFx.AURA, com.skycraft.magic.spell.Element.NATURE,
                        this.position(), this.position(), this.getId(), 0);
                this.playSound(com.skycraft.audio.AudioSounds.spell("alteration"), 0.8f, 1.0f);
                return;
            }
            case "fast_healing", "close_wounds" -> {
                this.heal(spell.magnitude);
                com.skycraft.magic.MagicFx.send(this, com.skycraft.magic.MagicFx.HEAL, com.skycraft.magic.spell.Element.HOLY,
                        this.position(), this.position(), this.getId(), 0);
                this.playSound(com.skycraft.audio.AudioSounds.spell("heal"), 0.85f, 1.05f);
                return;
            }
            case "healing_hands" -> {
                LivingEntity healTarget = this;
                for (NpcEntity ally : serverLevel.getEntitiesOfClass(NpcEntity.class, this.getBoundingBox().inflate(10.0),
                        a -> a != this && a.isAlive() && this.isAlliedTo(a) && a.getHealth() < a.getMaxHealth())) {
                    healTarget = ally;
                    break;
                }
                healTarget.heal(spell.magnitude * 4.0f);
                com.skycraft.magic.MagicFx.send(this, com.skycraft.magic.MagicFx.HEAL, com.skycraft.magic.spell.Element.HOLY,
                        healTarget.position(), healTarget.position(), healTarget.getId(), 0);
                this.playSound(com.skycraft.audio.AudioSounds.spell("heal"), 0.85f, 1.1f);
                return;
            }
            case "raise_zombie", "conjure_familiar" -> {
                setRaisedDead(true);
                Npcs.raiseDead(this, target);
                com.skycraft.magic.MagicFx.send(this, com.skycraft.magic.MagicFx.SUMMON, com.skycraft.magic.spell.Element.SOUL,
                        this.position(), this.position(), this.getId(), 0);
                this.playSound(com.skycraft.audio.AudioSounds.spell("conjure"), 0.9f, 1.0f);
                return;
            }
            case "lightning_bolt", "chain_lightning" -> {
                net.minecraft.world.phys.Vec3 from = this.getEyePosition().add(0, -0.2, 0);
                net.minecraft.world.phys.Vec3 to = target.getBoundingBox().getCenter();
                com.skycraft.magic.MagicFx.sendNear(serverLevel, from, 64, com.skycraft.magic.MagicFx.LIGHTNING,
                        com.skycraft.magic.spell.Element.SHOCK, from, to, -1, 0);
                com.skycraft.magic.MagicFx.burst(serverLevel, to, com.skycraft.magic.spell.Element.SHOCK, 1.2f);
                this.playSound(com.skycraft.audio.AudioSounds.spell("shock"), 0.9f, 1.0f + this.getRandom().nextFloat() * 0.2f);
                DamageSource shockSrc = com.skycraft.magic.MagicDamage.source(serverLevel, com.skycraft.magic.MagicDamage.SHOCK, null, this);
                com.skycraft.platform.Effects.hurt(target, shockSrc, spell.magnitude);
                if ("chain_lightning".equals(spell.id)) {
                    int chained = 0;
                    for (LivingEntity extra : serverLevel.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(spell.radius),
                            e -> e != this && e != target && e.isAlive() && !this.isAlliedTo(e) && this.isHostileTo(e))) {
                        net.minecraft.world.phys.Vec3 arcTo = extra.getBoundingBox().getCenter();
                        com.skycraft.magic.MagicFx.sendNear(serverLevel, to, 64, com.skycraft.magic.MagicFx.ARC,
                                com.skycraft.magic.spell.Element.SHOCK, to, arcTo, -1, 0);
                        com.skycraft.platform.Effects.hurt(extra, shockSrc, spell.magnitude * 0.65f);
                        if (++chained >= 2) break;
                    }
                }
                return;
            }
            default -> {}
        }

        // Aimed SpellProjectile (firebolt, ice_spike, fireball, sun_fire, etc.)
        final com.skycraft.magic.spell.Spell castSpell = spell;
        com.skycraft.magic.spell.SpellProjectile proj = new com.skycraft.magic.spell.SpellProjectile(serverLevel, this, castSpell) {
            private int npcLife;

            @Override
            public void tick() {
                // Run Entity/Projectile base movement and collision without SpellProjectile's ServerPlayer-only check
                super.baseTick();
                net.minecraft.world.phys.HitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS && !net.neoforged.neoforge.event.EventHooks.onProjectileImpact(this, hit)) {
                    this.onHit(hit);
                }
                this.applyEffectsFromBlocks(this.position(), this.position());
                net.minecraft.world.phys.Vec3 v = this.getDeltaMovement();
                this.setPos(this.getX() + v.x, this.getY() + v.y, this.getZ() + v.z);
                this.updateRotation();
                if (this.level().isClientSide() || this.isRemoved()) return;
                if (++npcLife > 80 || (castSpell.element == com.skycraft.magic.spell.Element.FIRE && this.isInWater())) {
                    this.discard();
                }
            }

            @Override
            protected boolean canHitEntity(Entity candidate) {
                if (candidate == NpcEntity.this || candidate instanceof NpcEntity ally && NpcEntity.this.isAlliedTo(ally)) return false;
                return candidate instanceof LivingEntity l && l.isAlive();
            }

            @Override
            protected void onHitEntity(net.minecraft.world.phys.EntityHitResult result) {
                if (this.level() instanceof net.minecraft.server.level.ServerLevel sl && result.getEntity() instanceof LivingEntity hitLiving) {
                    applyNpcProjectileHit(sl, this, castSpell, hitLiving, result.getLocation());
                }
                this.discard();
            }

            @Override
            protected void onHitBlock(BlockHitResult result) {
                if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                    net.minecraft.world.phys.Vec3 pos = result.getLocation();
                    applyNpcProjectileHit(sl, this, castSpell, null, pos);
                }
                this.discard();
            }
        };
        proj.setPos(this.getX(), this.getEyeY() - 0.15D, this.getZ());
        double dx = target.getX() - proj.getX();
        double dy = target.getY(0.5D) - proj.getY();
        double dz = target.getZ() - proj.getZ();
        proj.shoot(dx, dy, dz, Math.max(1.4f, castSpell.speed), 1.5f);
        serverLevel.addFreshEntity(proj);
        String soundKey = switch (castSpell.element) {
            case FROST -> "frost";
            case SHOCK -> "shock";
            case HOLY -> "heal";
            default -> "fire";
        };
        this.playSound(com.skycraft.audio.AudioSounds.spell(soundKey), 0.85f, 1.0f + this.getRandom().nextFloat() * 0.2f);
    }

    private void applyNpcProjectileHit(net.minecraft.server.level.ServerLevel level, Entity direct,
                                       com.skycraft.magic.spell.Spell spell, @Nullable LivingEntity directTarget,
                                       net.minecraft.world.phys.Vec3 pos) {
        DamageSource src = com.skycraft.magic.MagicDamage.source(level, com.skycraft.magic.MagicDamage.forElement(spell.element), direct, this);
        float radius = Math.max(1.2f, spell.radius);
        com.skycraft.magic.MagicFx.burst(level, pos, spell.element, radius);
        String impactSound = switch (spell.element) {
            case FROST -> "impact_frost";
            case SHOCK -> "impact_shock";
            default -> "impact_fire";
        };
        com.skycraft.magic.MagicFx.worldSound(level, pos, com.skycraft.audio.AudioSounds.spell(impactSound),
                net.minecraft.sounds.SoundSource.HOSTILE, 0.8f, 1.0f);
        if (directTarget != null) {
            com.skycraft.platform.Effects.hurt(directTarget, src, spell.magnitude);
            applyNpcSpellElement(spell.element, directTarget);
        }
        if (spell.radius > 1.5f) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                    net.minecraft.world.phys.AABB.ofSize(pos, spell.radius * 2, spell.radius * 2, spell.radius * 2),
                    e -> e != this && e != directTarget && e.isAlive() && !this.isAlliedTo(e))) {
                com.skycraft.platform.Effects.hurt(e, src, spell.magnitude * 0.65f);
                applyNpcSpellElement(spell.element, e);
            }
        }
    }

    private static void applyNpcSpellElement(com.skycraft.magic.spell.Element element, LivingEntity target) {
        switch (element) {
            case FIRE -> target.igniteForSeconds(4);
            case FROST -> target.addEffect(new net.minecraft.world.effect.MobEffectInstance(com.skycraft.platform.Effects.holder(
                    com.skycraft.platform.Effects.holder(net.minecraft.world.effect.MobEffects.SLOWNESS)), 60, 1));
            default -> {}
        }
    }

    /** Launches this NPC's chosen Skycraft spell at {@code target}. */
    public void castFireball(LivingEntity target) {
        castSpell(target, chooseCombatSpell(target));
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
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                        @Nullable SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        initialize();
        return data;
    }

    /** Picks role (if none was set), skin, name, gear and stats once. */
    public void initialize() {
        if (initialized || this.level().isClientSide()) return;
        initialized = true;
        if (!hasRole()) setRole(Npcs.randomRole(this.getRandom(), this.getType() == NpcEntities.NPC_FIGHTER.get()));
        if (getLook() < 0) setSkin(this.getRandom().nextInt(NpcRole.SKINS));
        Npcs.outfit(this);
    }

    // ------------------------------------------------------------------ crowds

    /** Walking people pass through each other so doorways and gates never jam, but stationary people do not stack on the same spot. */
    private boolean passThrough(Entity other) {
        if (!(other instanceof NpcEntity || other instanceof net.minecraft.world.entity.npc.villager.AbstractVillager)) return false;
        if (this.isSleeping() || (other instanceof LivingEntity l && l.isSleeping())) return true;
        if (!this.getNavigation().isDone()) return true;
        if (other instanceof Mob m && !m.getNavigation().isDone()) return true;
        return false;
    }

    @Override
    public void push(Entity other) {
        if (passThrough(other)) return;
        super.push(other);
    }

    @Override
    protected void doPush(Entity other) {
        if (passThrough(other)) return;
        super.doPush(other);
    }

    /** Roofs, fire pits, half-slabs, and counters/furniture are not places to wander to. */
    @Override
    public float getWalkTargetValue(BlockPos pos, net.minecraft.world.level.LevelReader level) {
        if (!this.level().isClientSide()) {
            if (com.skycraft.society.NpcPlacement.isFireHazard(level, pos)
                    || com.skycraft.society.NpcPlacement.isSlabHazard(level, pos)
                    || com.skycraft.society.NpcPlacement.isFurnitureHazard(level, pos)) {
                return -1.0e9F;
            }
            if (com.skycraft.society.NpcPlacement.isRoof(this, pos)) return -1.0e9F;
        }
        return super.getWalkTargetValue(pos, level);
    }

    /** Set once the schedule has put this person where they should be right now (re-done on every load). */
    private boolean placed;
    private int placeTries;

    private void placeBySchedule() {
        if (placed || this.tickCount < 4 || this.tickCount % 10 != 0) return;
        if (!com.skycraft.society.SocietyConfig.ROUTINES.get() || home == null || isBusy() || getTarget() != null || isPrisoner()
                || !(role().townsfolk() || com.skycraft.society.Marriage.isSpouse(this)) || social.read("engaged", net.minecraft.core.UUIDUtil.CODEC).isPresent() || isSleeping()) {
            placed = true;
            return;
        }
        boolean done;
        try {
            done = com.skycraft.society.NpcPlacement.snapToSchedule(this);
        } catch (RuntimeException e) {
            done = true;
        }
        if (done || ++placeTries > 12) placed = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            if (isCasting() && this.tickCount % 2 == 0) {
                com.skycraft.magic.spell.Spell spell = getCastingSpell();
                com.skycraft.magic.spell.Element elem = spell != null ? spell.element : com.skycraft.magic.spell.Element.FIRE;
                net.minecraft.core.particles.ParticleOptions particle = switch (elem) {
                    case FROST -> net.minecraft.core.particles.ParticleTypes.SNOWFLAKE;
                    case SHOCK -> net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK;
                    case HOLY, NATURE -> net.minecraft.core.particles.ParticleTypes.END_ROD;
                    case SOUL -> net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME;
                    default -> net.minecraft.core.particles.ParticleTypes.FLAME;
                };
                double yawRad = Math.toRadians(this.yBodyRot);
                double fwdX = -Math.sin(yawRad) * 0.45;
                double fwdZ = Math.cos(yawRad) * 0.45;
                double sideX = Math.cos(yawRad) * 0.32;
                double sideZ = Math.sin(yawRad) * 0.32;
                double handY = this.getY() + this.getBbHeight() * 0.68;
                this.level().addParticle(particle, this.getX() + fwdX + sideX, handY, this.getZ() + fwdZ + sideZ, 0, 0.015, 0);
                this.level().addParticle(particle, this.getX() + fwdX - sideX, handY, this.getZ() + fwdZ - sideZ, 0, 0.015, 0);
            }
            return;
        }
        placeBySchedule();
        if (!initialized) initialize();
        if (this.isOnFire() || (this.tickCount + this.getId()) % 10 == 0) {
            com.skycraft.society.NpcPlacement.rescueFromFireOrSlab(this);
        }
        if ((this.tickCount + this.getId()) % 20 == 0) {
            com.skycraft.society.NpcPlacement.rescueFromRoof(this);
        }
        if (getLook() < 0 && this.tickCount % 20 == 1) Npcs.ensureLook(this); // older saves
        if (isConversing()) {
            this.getNavigation().stop();
        }
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel level) {
        super.customServerAiStep(level);
        if (isConversing()) {
            Entity partner = this.level().getEntity(getConversationPartner());
            if (partner == null || !partner.isAlive() || this.distanceToSqr(partner) > 64) {
                setConversationPartner(-1);
            } else {
                this.getNavigation().stop();
                this.zza = 0.0F;
                this.xxa = 0.0F;
                this.setSpeed(0.0F);
                this.getLookControl().setLookAt(partner, 30.0F, 30.0F);
                double dx = partner.getX() - this.getX();
                double dz = partner.getZ() - this.getZ();
                float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                this.setYRot(Mth.approachDegrees(this.getYRot(), targetYaw, 25.0F));
                this.yBodyRot = Mth.approachDegrees(this.yBodyRot, targetYaw, 25.0F);
                this.setYHeadRot(Mth.approachDegrees(this.getYHeadRot(), targetYaw, 30.0F));
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
            fleshCast = false;
            if (this.getHealth() < this.getMaxHealth() && this.tickCount % 60 == 0) this.heal(1.0f);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceSqr) {
        return isEncounter() && !com.skycraft.society.Followers.isFollower(this) && distanceSqr > 96 * 96;
    }

    /** Whether the item in {@code slot} was given by the follower's player (it always drops; see Followers). */
    public boolean isGivenGear(net.minecraft.world.entity.EquipmentSlot slot) {
        return this.getDropChances().byEquipment(slot) > 1.0f;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        if (recentlyHit && role() != NpcRole.BEGGAR && this.getRandom().nextFloat() < 0.6f) {
            int gold = 3 + this.getRandom().nextInt(role().civilian ? 12 : 30);
            this.spawnAtLocation(level, Currency.coins(gold));
        }
    }

    /** Runs on every death, whatever doMobLoot says: a follower's bags (the player's items) always drop. */
    @Override
    protected void dropEquipment(net.minecraft.server.level.ServerLevel level) {
        super.dropEquipment(level);
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
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putString("Role", role().id);
        tag.putInt("Skin", getSkin());
        tag.putByte("NpcFlags", (byte) (this.entityData.get(FLAGS) & SAVED_FLAGS));
        tag.putBoolean("SkycraftInit", initialized);
        if (huntTarget != null) tag.store("Hunt", net.minecraft.core.UUIDUtil.CODEC,  huntTarget);
        if (seekTarget != null) tag.store("Seek", net.minecraft.core.UUIDUtil.CODEC,  seekTarget);
        if (leader != null) tag.store("Leader", net.minecraft.core.UUIDUtil.CODEC,  leader);
        if (destination != null) tag.put("Destination", com.skycraft.platform.NbtPositions.write(destination));
        if (home != null) {
            tag.put("Home", com.skycraft.platform.NbtPositions.write(home));
            tag.putInt("HomeRadius", (int) this.getHomeRadius());
        }
        if (letter >= 0) tag.putInt("Letter", letter);
        if (fearedPlayerUuid != null) tag.store("FearedPlayer", net.minecraft.core.UUIDUtil.CODEC,  fearedPlayerUuid);
        if (bed != null) tag.put("Bed", com.skycraft.platform.NbtPositions.write(bed));
        tag.putInt("Look", getLook());
        if (workplace != null) tag.put("Workplace", com.skycraft.platform.NbtPositions.write(workplace));
        if (inn != null) tag.put("Inn", com.skycraft.platform.NbtPositions.write(inn));
        if (!social.isEmpty()) tag.put("Social", social);
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "Role", "Skin", "NpcFlags", "SkycraftInit", "Hunt", "Seek", "Leader", "Destination", "Home", "HomeRadius", "Letter", "FearedPlayer", "Bed", "Look", "Workplace", "Inn", "Social");
        
        NpcRole r = NpcRole.byId(tag.getStringOr("Role",""));
        if (r != null) setRole(r);
        setSkin(tag.getIntOr("Skin",0));
        this.entityData.set(FLAGS, (byte) (tag.getByteOr("NpcFlags",(byte)0) & SAVED_FLAGS));
        initialized = tag.getBooleanOr("SkycraftInit",false);
        huntTarget = tag.read("Hunt", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? tag.read("Hunt", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
        seekTarget = tag.read("Seek", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? tag.read("Seek", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
        leader = tag.read("Leader", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? tag.read("Leader", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
        destination = tag.contains("Destination") ? com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Destination")) : null;
        if (tag.contains("Home")) setHome(com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Home")), Math.max(8, tag.getIntOr("HomeRadius",0)));
        letter = tag.contains("Letter") ? tag.getIntOr("Letter",0) : -1;
        fearedPlayerUuid = tag.read("FearedPlayer", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? tag.read("FearedPlayer", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
        bed = tag.contains("Bed") ? com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Bed")) : null;
        if (tag.contains("Look")) setLook(tag.getIntOr("Look",0));
        workplace = tag.contains("Workplace") ? com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Workplace")) : null;
        inn = tag.contains("Inn") ? com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Inn")) : null;
        social = tag.getCompoundOrEmpty("Social");
    }

    @Override
    public void remove(RemovalReason reason) {
        // killed or discarded for good (not just unloaded): free the bed for someone else
        if (reason.shouldDestroy() && !this.level().isClientSide()) {
            com.skycraft.society.Settlers.releaseBed(this);
            if (reason == RemovalReason.KILLED) com.skycraft.society.Relationships.onDeath(this);
        }
        super.remove(reason);
    }
}
