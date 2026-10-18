package com.skycraft.aether;

/**
 * compile stub for the stub-based checks (compile_check.ps1, the exact-source harness): the real AetherSettlements
 * needs the mod's settlement classes and is compiled against the real mod by sandbox_check.ps1. Never moved in.
 */
final class AetherSettlements implements AetherFarField.Overlay {
    static AetherSettlements create() {
        throw new UnsupportedOperationException("stub: settlements need the real mod (sandbox_check.ps1)");
    }

    @Override
    public void cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {}
}
