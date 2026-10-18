// Build step for the exact-height kernel (aether_height.slang): makes the SPIR-V's floating-point contract explicit
// and refuses to build when it is not what the kernel's exactness argument needs.
//  * adds the float-controls execution modes for 32 and 64 bit: RoundingModeRTE and SignedZeroInfNanPreserve (with
//    their capabilities), so a conforming driver must round to nearest even and keep signed zeros, infinities and
//    NaNs (the host checks the device supports both). Denormal handling is left to the device: the kernel never lets
//    a denormal reach an unflagged result (see aether_height.slang), and refuses DenormFlushToZero/DenormPreserve
//    requests it did not make
//  * checks every floating-point arithmetic instruction carries NoContraction (no fused multiply-add, no
//    reassociation), and that no extended instruction other than the bit packers is used (no driver transcendentals)
//   spv_exact in.spv out.spv
#include <cstdint>
#include <cstdio>
#include <set>
#include <vector>

namespace {
constexpr uint32_t OpExtInstImport = 11, OpExtInst = 12, OpEntryPoint = 15, OpExecutionMode = 16, OpCapability = 17,
                   OpDecorate = 71, OpFNegate = 127, OpFAdd = 129, OpFSub = 131, OpFMul = 133, OpFDiv = 136,
                   OpFRem = 140, OpFMod = 141, OpVectorTimesScalar = 142, OpMatrixTimesScalar = 143, OpDot = 148;
constexpr uint32_t NoContraction = 42;
constexpr uint32_t CapSignedZeroInfNanPreserve = 4466, CapRoundingModeRTE = 4467;
constexpr uint32_t ModeDenormPreserve = 4459, ModeDenormFlushToZero = 4460, ModeSignedZeroInfNanPreserve = 4461,
                   ModeRoundingModeRTE = 4462;
// GLSL.std.450 instructions that only move bits
const std::set<uint32_t> kBitOnly = {58 /*PackDouble2x32*/, 59 /*UnpackDouble2x32*/};

bool arithmetic(uint32_t op) {
    return op == OpFNegate || op == OpFAdd || op == OpFSub || op == OpFMul || op == OpFDiv || op == OpFRem ||
           op == OpFMod || op == OpVectorTimesScalar || op == OpMatrixTimesScalar || op == OpDot;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: spv_exact in.spv out.spv\n");
        return 2;
    }
    std::FILE* f = std::fopen(argv[1], "rb");
    if (!f) return std::fprintf(stderr, "spv_exact: cannot read %s\n", argv[1]), 1;
    std::vector<uint32_t> w;
    uint32_t v;
    while (std::fread(&v, 4, 1, f) == 1) w.push_back(v);
    std::fclose(f);
    if (w.size() < 5 || w[0] != 0x07230203u) return std::fprintf(stderr, "spv_exact: not SPIR-V\n"), 1;
    uint32_t version = w[1];
    std::set<uint32_t> caps, noContraction, arith;
    std::set<std::pair<uint32_t, uint32_t>> modes;  // (mode, width)
    std::vector<uint32_t> entries;
    size_t lastCap = 0, lastMode = 0, lastEntry = 0;
    for (size_t i = 5; i < w.size();) {
        uint32_t n = w[i] >> 16, op = w[i] & 0xFFFFu;
        if (n == 0 || i + n > w.size()) return std::fprintf(stderr, "spv_exact: malformed\n"), 1;
        if (op == OpCapability) {
            caps.insert(w[i + 1]);
            lastCap = i + n;
        } else if (op == OpEntryPoint) {
            entries.push_back(w[i + 2]);
            lastEntry = i + n;
        } else if (op == OpExecutionMode) {
            if (n >= 4) modes.insert({w[i + 2], w[i + 3]});
            if (n >= 3 && (w[i + 2] == ModeDenormPreserve || w[i + 2] == ModeDenormFlushToZero))
                return std::fprintf(stderr, "spv_exact: unexpected denormal mode (the host does not require one)\n"), 1;
            lastMode = i + n;
        } else if (op == OpDecorate && n >= 3 && w[i + 2] == NoContraction) {
            noContraction.insert(w[i + 1]);
        } else if (op == OpExtInst) {
            if (!kBitOnly.count(w[i + 4]))
                return std::fprintf(stderr, "spv_exact: extended instruction %u is not exact by spec\n", w[i + 4]), 1;
        } else if (arithmetic(op)) {
            arith.insert(w[i + 2]);  // result id
        }
        i += n;
    }
    for (uint32_t id : arith)
        if (!noContraction.count(id))
            return std::fprintf(stderr, "spv_exact: arithmetic result %%%u lacks NoContraction\n", id), 1;
    if (version < 0x00010400u)
        return std::fprintf(stderr, "spv_exact: SPIR-V 1.4+ needed (float controls in core)\n"), 1;
    std::vector<uint32_t> addCaps, addModes;
    for (uint32_t c : {CapSignedZeroInfNanPreserve, CapRoundingModeRTE})
        if (!caps.count(c)) addCaps.insert(addCaps.end(), {(2u << 16) | OpCapability, c});
    for (uint32_t e : entries)
        for (uint32_t m : {ModeSignedZeroInfNanPreserve, ModeRoundingModeRTE})
            for (uint32_t width : {32u, 64u})
                if (!modes.count({m, width})) addModes.insert(addModes.end(), {(4u << 16) | OpExecutionMode, e, m, width});
    size_t modeAt = lastMode ? lastMode : lastEntry;
    std::vector<uint32_t> out(w.begin(), w.begin() + lastCap);
    out.insert(out.end(), addCaps.begin(), addCaps.end());
    out.insert(out.end(), w.begin() + lastCap, w.begin() + modeAt);
    out.insert(out.end(), addModes.begin(), addModes.end());
    out.insert(out.end(), w.begin() + modeAt, w.end());
    f = std::fopen(argv[2], "wb");
    if (!f) return std::fprintf(stderr, "spv_exact: cannot write %s\n", argv[2]), 1;
    std::fwrite(out.data(), 4, out.size(), f);
    std::fclose(f);
    std::printf("spv_exact: %zu arithmetic instructions all NoContraction; float controls RTE + signed-zero/inf/nan "
                "preserve (32, 64)\n", arith.size());
    return 0;
}
