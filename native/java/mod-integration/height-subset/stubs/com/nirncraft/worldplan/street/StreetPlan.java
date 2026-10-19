package com.nirncraft.worldplan.street;

/** harness stub: no town street plots (a few blocks of levelling inside towns, below far-field voxel size) */
public final class StreetPlan {
    private static final StreetPlan NONE = new StreetPlan();

    public static StreetPlan get() { return NONE; }

    public boolean pad(int x, int z, com.nirncraft.worldplan.PlanGround.Bed b) { return false; }
}
