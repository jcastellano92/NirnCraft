// Build step: the kernels' SPIR-V against their budget (native/shaders/kernel_budget.json "spv"), a ratchet: any
// increase fails the build (the budget changes only in a deliberate commit). Per module (keyed by the .spv file's
// stem; "*" the default for the rest):
//   instructions  every instruction of the module (the inlined copies of the walks: code the instruction cache and the
//                 driver's compile pay for)
//   private       module-scope Private variables (thread-private statics: shared across a real call they are local
//                 memory, docs/AGENT-RULES.md)
//   workgroup     Workgroup (groupshared) variables
//   structCalls   calls to functions with a struct or pointer parameter (a real call passes those through memory: one
//                 grew the far kernel's shared memory from 7936 to 16640 bytes and cost its far pass 40 %), the
//                 functions named in "structCallsOk" aside
//   fnArrays      function-scope arrays (indexed at run time they live in local memory)
//   spv_budget <kernel_budget.json> <dir with .spv files> [--write]   (--write: print today's numbers as budget JSON)
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <map>
#include <set>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {
// ---- a small JSON reader: objects, arrays, strings, numbers (all a budget file holds) ----
struct J {
    enum T { NUL, NUM, STR, ARR, OBJ } t = NUL;
    double n = 0;
    std::string s;
    std::vector<J> a;
    std::map<std::string, J> o;
    const J* get(const std::string& k) const {
        auto it = o.find(k);
        return it == o.end() ? nullptr : &it->second;
    }
};
struct Reader {
    const std::string& s;
    size_t i = 0;
    bool ok = true;
    void ws() {
        while (i < s.size() && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++;
    }
    std::string str() {
        std::string r;
        i++;  // "
        while (i < s.size() && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.size()) i++;
            r += s[i++];
        }
        i++;
        return r;
    }
    J val() {
        ws();
        J v;
        if (i >= s.size()) return ok = false, v;
        if (s[i] == '{') {
            v.t = J::OBJ;
            i++;
            for (ws(); i < s.size() && s[i] != '}'; ws()) {
                if (s[i] == ',') { i++; continue; }
                if (s[i] != '"') return ok = false, v;
                std::string k = str();
                ws();
                if (i >= s.size() || s[i] != ':') return ok = false, v;
                i++;
                v.o[k] = val();
            }
            i++;
        } else if (s[i] == '[') {
            v.t = J::ARR;
            i++;
            for (ws(); i < s.size() && s[i] != ']'; ws()) {
                if (s[i] == ',') { i++; continue; }
                v.a.push_back(val());
            }
            i++;
        } else if (s[i] == '"') {
            v.t = J::STR;
            v.s = str();
        } else {
            v.t = J::NUM;
            size_t e = i;
            while (e < s.size() && (std::isdigit((unsigned char)s[e]) || s[e] == '-' || s[e] == '.' || s[e] == 'e' || s[e] == '+')) e++;
            if (e == i) return ok = false, v;
            v.n = std::stod(s.substr(i, e - i));
            i = e;
        }
        return v;
    }
};

constexpr uint32_t OpName = 5, OpTypeArray = 28, OpTypeStruct = 30, OpTypePointer = 32, OpFunction = 54,
                   OpFunctionParameter = 55, OpFunctionEnd = 56, OpFunctionCall = 57, OpVariable = 59;
constexpr uint32_t ScWorkgroup = 4, ScPrivate = 6, ScFunction = 7;

struct Stats {
    uint64_t instructions = 0, priv = 0, workgroup = 0, fnArrays = 0;
    std::map<std::string, uint64_t> structCalls;  // callee -> calls
};

bool readSpv(const fs::path& p, std::vector<uint32_t>& w) {
    std::FILE* f = std::fopen(p.string().c_str(), "rb");
    if (!f) return false;
    uint32_t v;
    while (std::fread(&v, 4, 1, f) == 1) w.push_back(v);
    std::fclose(f);
    return w.size() >= 5 && w[0] == 0x07230203u;
}

Stats analyse(const std::vector<uint32_t>& w) {
    Stats st;
    std::map<uint32_t, std::string> names;
    std::set<uint32_t> structTypes, arrayTypes;
    std::map<uint32_t, std::pair<uint32_t, uint32_t>> pointers;  // id -> (storage class, pointee)
    std::set<uint32_t> structParamFns;
    std::vector<std::pair<uint32_t, uint32_t>> calls;  // (callee, count index)
    uint32_t curFn = 0;
    bool inFn = false;
    for (size_t i = 5; i < w.size();) {
        uint32_t n = w[i] >> 16, op = w[i] & 0xFFFFu;
        if (n == 0 || i + n > w.size()) break;
        st.instructions++;
        if (op == OpName && n >= 3) {
            names[w[i + 1]] = std::string(reinterpret_cast<const char*>(&w[i + 2]));
        } else if (op == OpTypeStruct) {
            structTypes.insert(w[i + 1]);
        } else if (op == OpTypeArray) {
            arrayTypes.insert(w[i + 1]);
        } else if (op == OpTypePointer && n >= 4) {
            pointers[w[i + 1]] = {w[i + 2], w[i + 3]};
        } else if (op == OpFunction && n >= 3) {
            curFn = w[i + 2];
            inFn = true;
        } else if (op == OpFunctionParameter && n >= 3) {
            uint32_t t = w[i + 1];
            if (structTypes.count(t) || pointers.count(t)) structParamFns.insert(curFn);
        } else if (op == OpFunctionEnd) {
            inFn = false;
        } else if (op == OpFunctionCall && n >= 4) {
            calls.push_back({w[i + 3], 0});
        } else if (op == OpVariable && n >= 4) {
            uint32_t sc = w[i + 3];
            auto pt = pointers.find(w[i + 1]);
            if (!inFn && sc == ScPrivate) st.priv++;
            if (!inFn && sc == ScWorkgroup) st.workgroup++;
            if (inFn && sc == ScFunction && pt != pointers.end() && arrayTypes.count(pt->second.second)) st.fnArrays++;
        }
        i += n;
    }
    for (auto& c : calls)
        if (structParamFns.count(c.first)) {
            auto it = names.find(c.first);
            st.structCalls[it == names.end() ? "%" + std::to_string(c.first) : it->second]++;
        }
    return st;
}

uint64_t total(const std::map<std::string, uint64_t>& m, const std::set<std::string>& ok) {
    uint64_t t = 0;
    for (auto& [k, v] : m)
        if (!ok.count(k)) t += v;
    return t;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: spv_budget <kernel_budget.json> <dir with .spv files> [modules.txt] [--write]\n");
        return 2;
    }
    // modules.txt: the modules this build makes, one stem a line (CMake writes it): only those are checked, so a stale
    // .spv an older build left in the folder is not; --write: print today's numbers as budget JSON
    bool write = false;
    std::string modulesFile;
    for (int a = 3; a < argc; a++) {
        if (std::string(argv[a]) == "--write") write = true;
        else modulesFile = argv[a];
    }
    std::set<std::string> modules;  // (empty: every .spv in the folder)
    if (!modulesFile.empty()) {
        std::FILE* mf = std::fopen(modulesFile.c_str(), "rb");
        if (!mf) return std::fprintf(stderr, "spv_budget: cannot read %s\n", modulesFile.c_str()), 1;
        char line[512];
        while (std::fgets(line, sizeof line, mf)) {
            std::string m(line);
            while (!m.empty() && (m.back() == '\n' || m.back() == '\r' || m.back() == ' ')) m.pop_back();
            if (!m.empty()) modules.insert(m);
        }
        std::fclose(mf);
    }
    std::string text;
    if (std::FILE* f = std::fopen(argv[1], "rb")) {
        char buf[4096];
        size_t k;
        while ((k = std::fread(buf, 1, sizeof buf, f)) > 0) text.append(buf, k);
        std::fclose(f);
    } else if (!write) {
        return std::fprintf(stderr, "spv_budget: cannot read %s\n", argv[1]), 1;
    }
    J root;
    if (!text.empty()) {
        Reader r{text};
        root = r.val();
        if (!r.ok) return std::fprintf(stderr, "spv_budget: %s is not valid JSON\n", argv[1]), 1;
    }
    const J* spv = root.get("spv");
    std::vector<fs::path> files;
    size_t skipped = 0;
    for (auto& e : fs::directory_iterator(argv[2])) {
        std::string s = e.path().filename().string();
        if (e.path().extension() != ".spv" || s.find("_raw.spv") != std::string::npos) continue;
        if (!modules.empty() && !modules.count(e.path().stem().string())) {
            skipped++;  // (not this build's: an older build's file)
            continue;
        }
        files.push_back(e.path());
    }
    std::sort(files.begin(), files.end());
    int fails = 0;
    for (const std::string& m : modules)
        if (!fs::exists(fs::path(argv[2]) / (m + ".spv"))) {
            std::fprintf(stderr, "spv_budget: FAIL %s.spv is not in %s (the build lists it)\n", m.c_str(), argv[2]);
            fails++;
        }
    if (write) std::printf("  \"spv\": {\n");
    for (size_t fi = 0; fi < files.size(); fi++) {
        std::vector<uint32_t> w;
        if (!readSpv(files[fi], w)) continue;
        Stats st = analyse(w);
        std::string key = files[fi].stem().string();
        if (write) {
            std::string ok;
            for (auto& [k, v] : st.structCalls) ok += (ok.empty() ? "\"" : ", \"") + k + "\"";
            std::printf("    \"%s\": {\"instructions\": %llu, \"private\": %llu, \"workgroup\": %llu, \"fnArrays\": %llu, "
                        "\"structCalls\": 0, \"structCallsOk\": [%s]}%s\n",
                        key.c_str(), (unsigned long long)st.instructions, (unsigned long long)st.priv,
                        (unsigned long long)st.workgroup, (unsigned long long)st.fnArrays, ok.c_str(),
                        fi + 1 < files.size() ? "," : "");
            continue;
        }
        const J* b = spv ? spv->get(key) : nullptr;
        if (!b && spv) b = spv->get("*");
        if (!b) {
            std::fprintf(stderr, "spv_budget: FAIL %s has no budget (add it to %s, or a \"*\" default)\n", key.c_str(), argv[1]);
            fails++;
            continue;
        }
        std::set<std::string> ok;
        if (const J* l = b->get("structCallsOk"))
            for (auto& x : l->a) ok.insert(x.s);
        auto check = [&](const char* what, uint64_t have, const char* hint) {
            const J* lim = b->get(what);
            if (!lim) return;
            if (double(have) > lim->n) {
                std::fprintf(stderr, "spv_budget: FAIL %s %s %llu > budget %.0f: %s\n", key.c_str(), what, (unsigned long long)have,
                             lim->n, hint);
                fails++;
            }
        };
        check("instructions", st.instructions, "more inlined code (another call site of a walk?): one call site per structure");
        check("private", st.priv, "a thread-private static: pass it as a parameter (docs/AGENT-RULES.md)");
        check("workgroup", st.workgroup, "a new groupshared variable");
        check("fnArrays", st.fnArrays, "a function-scope array (local memory when indexed at run time): bit tricks or FrameGpu");
        uint64_t sc = total(st.structCalls, ok);
        if (const J* lim = b->get("structCalls"); lim && double(sc) > lim->n) {
            for (auto& [k, v] : st.structCalls)
                if (!ok.count(k))
                    std::fprintf(stderr, "spv_budget: FAIL %s calls %s %llux with a struct or pointer parameter: inline it, or pass "
                                         "scalars (a real call passes those through memory)\n",
                                 key.c_str(), k.c_str(), (unsigned long long)v);
            fails++;
        }
    }
    if (write) {
        std::printf("  }\n");
        return 0;
    }
    if (fails) std::fprintf(stderr, "spv_budget: %d over budget (%s; the budget changes only in a deliberate commit)\n", fails, argv[1]);
    else std::printf("spv_budget: %zu modules within budget (%zu .spv files not this build's, not checked)\n", files.size(), skipped);
    return fails ? 1 : 0;
}
