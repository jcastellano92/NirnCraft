package com.skycraft.fauna;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.Horse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

/** Goal lifecycle is tested on real GoalSelector/animal types without constructing a server world. */
class WildFleeGoalLifecycleTest {
    private static final String OLD_FLAG = "skycraft_flee_goal_added";
    private static final Unsafe UNSAFE;
    static {
        try { Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); UNSAFE = (Unsafe)f.get(null); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    public static class ActiveNavigation extends GroundPathNavigation {
        public ActiveNavigation() { super(null, null); }
        @Override public boolean isDone() { return false; }
    }
    public static class TestChicken extends Chicken {
        CompoundTag data;
        PathNavigation nav;
        public TestChicken() { super(null, null); }
        @Override public CompoundTag getPersistentData() { return data; }
        @Override public PathNavigation getNavigation() { return nav; }
    }
    public static class TestHorse extends Horse {
        CompoundTag data;
        PathNavigation nav;
        boolean tamed;
        public TestHorse() { super(null, null); }
        @Override public CompoundTag getPersistentData() { return data; }
        @Override public PathNavigation getNavigation() { return nav; }
        @Override public boolean isTamed() { return tamed; }
    }
    private static TestChicken chicken(CompoundTag saved) throws Exception {
        var animal = (TestChicken)UNSAFE.allocateInstance(TestChicken.class);
        animal.data = saved.copy(); animal.nav = (ActiveNavigation)UNSAFE.allocateInstance(ActiveNavigation.class);
        var goals = new GoalSelector();
        var targets = new GoalSelector();
        Field goalField = Mob.class.getField("goalSelector"); goalField.setAccessible(true); goalField.set(animal, goals);
        Field targetField = Mob.class.getField("targetSelector"); targetField.setAccessible(true); targetField.set(animal, targets);
        return animal;
    }
    private static Goal onlyGoal(Animal animal) {
        assertEquals(1, animal.goalSelector.getAvailableGoals().size());
        return animal.goalSelector.getAvailableGoals().iterator().next().getGoal();
    }
    @Test void oldSavedMarkerDoesNotSuppressReconstructionAfterReload() throws Exception {
        var saved = new CompoundTag(); saved.putBoolean(OLD_FLAG, true);
        var reloaded = chicken(saved);
        FaunaBehaviors.applyWildFleeGoal(reloaded);
        onlyGoal(reloaded);
        assertFalse(reloaded.data.contains(OLD_FLAG));
        var nextReload = chicken(reloaded.data);
        FaunaBehaviors.applyWildFleeGoal(nextReload);
        onlyGoal(nextReload);
    }
    @Test void repeatedJoinInstallsOnlyOneRuntimeGoal() throws Exception {
        var animal = chicken(new CompoundTag());
        FaunaBehaviors.applyWildFleeGoal(animal);
        var goal = onlyGoal(animal);
        FaunaBehaviors.applyWildFleeGoal(animal);
        assertSame(goal, onlyGoal(animal));
    }
    @Test void changingOwnershipImmediatelyStopsAnExistingFleeGoal() throws Exception {
        var animal = chicken(new CompoundTag());
        FaunaBehaviors.applyWildFleeGoal(animal);
        var goal = onlyGoal(animal);
        assertTrue(goal.canContinueToUse());
        animal.data.putBoolean(Livestock.OWNED, true);
        assertFalse(goal.canContinueToUse()); assertFalse(goal.canUse());
        animal.data.remove(Livestock.OWNED);
        assertTrue(goal.canContinueToUse());
        animal.data.putBoolean(Livestock.PLAYER_OWNED, true);
        assertFalse(goal.canContinueToUse()); assertFalse(goal.canUse());
    }
    @Test void tamingAHorseStopsAndUntamingRestoresEligibilityWithoutDuplicateGoals() throws Exception {
        var horse = (TestHorse)UNSAFE.allocateInstance(TestHorse.class);
        horse.data = new CompoundTag(); horse.nav = (ActiveNavigation)UNSAFE.allocateInstance(ActiveNavigation.class);
        Field goals = Mob.class.getField("goalSelector"); goals.setAccessible(true);
        goals.set(horse, new GoalSelector());
        Field targets = Mob.class.getField("targetSelector"); targets.setAccessible(true);
        targets.set(horse, new GoalSelector());
        FaunaBehaviors.applyWildFleeGoal(horse);
        var goal = onlyGoal(horse); assertTrue(goal.canContinueToUse());
        horse.tamed = true;
        assertFalse(goal.canContinueToUse()); assertFalse(goal.canUse());
        horse.tamed = false; assertTrue(goal.canContinueToUse());
        FaunaBehaviors.applyWildFleeGoal(horse); assertSame(goal, onlyGoal(horse));
    }

}
