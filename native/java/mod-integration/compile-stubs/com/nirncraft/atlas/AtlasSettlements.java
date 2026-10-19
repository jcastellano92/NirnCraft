package com.nirncraft.atlas;

/**
 * compile stub for the stub-based checks (compile_check.ps1, the exact-source harness): the real AtlasSettlements
 * needs the mod's settlement classes and is compiled against the real mod by sandbox_check.ps1. Never moved in.
 */
final class AtlasSettlements implements AtlasFarField.Overlay {
    static AtlasSettlements create(java.util.function.IntBinaryOperator ground) {
        throw new UnsupportedOperationException("stub: settlements need the real mod (sandbox_check.ps1)");
    }

    @Override
    public void cover(AtlasNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {}
}
