"""Validate full patched shader source with glslang, independently of Minecraft/GPU timing.

Expands real pack includes, applies the shipped options, supplies Minecraft/Iris capability macros,
and links vertex/fragment pairs in a desktop330 compatibility profile. Driver linking, runtime uniform
binding and visual/performance acceptance remain separate gates. --full adds qualities1/2/3, AMD
reflection compatibility and primary/reflection/shadow/DH programs. All compute entry points are also
validated at colored-lighting0/128/256/1024; disabled programs are checked through actual properties
preprocessing rather than compiled with intentionally absent resources.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT=pathlib.Path(__file__).resolve().parents[2]
FOLDERS=("world0","world-1","world1","skycraftIsles","skycraftApocrypha","skycraftSoulCairn","skycraftColdharbour")


def load_patch():
    spec=importlib.util.spec_from_file_location("skycraft_patch",pathlib.Path(__file__).with_name("patch_complementary_clouds.py"))
    patch=importlib.util.module_from_spec(spec);spec.loader.exec_module(patch);return patch


class Sources:
    def __init__(self,pack):
        with zipfile.ZipFile(pack) as archive:
            self.raw={name:archive.read(name).decode("utf-8") for name in archive.namelist()
                      if name.endswith((".glsl",".fsh",".vsh",".csh"))}
            self.properties=archive.read("shaders/shaders.properties").decode("utf-8")
        self.cache={}

    def expand(self,name,stack=()):
        if name in stack: return "" # Original guarded include cycles are already represented by the outer body.
        if name in self.cache: return self.cache[name]
        text=self.raw[name].replace("\r\n","\n")
        text=re.sub(r'(?m)^\s*#include\s+"(/[^\"]+)"\s*$',
                    lambda match:self.expand("shaders"+match[1],stack+(name,)),text)
        if "#include" in re.sub(r'//[^\n]*',"",text):
            raise ValueError("Unexpanded shader include: "+name)
        self.cache[name]=text
        return text


def prepare(text,profile,quality,dh,amd):
    # Iris's preprocessor strips comments before desktop GLSL parsing. In particular
    # the upstream ASCII art ends lines in backslashes, which raw glslang rejects.
    for key,value in profile.items():
        expression=r"(?m)^\s*(?://\s*)?#define\s+"+re.escape(key)+r"\b[^\n]*"
        if value=="true": replacement="#define "+key
        elif value=="false": replacement="// disabled "+key
        else: replacement="#define "+key+" "+value
        text=re.sub(expression,lambda _:replacement,text)
    text=re.sub(r"(?m)^(\s*#define CLOUD_QUALITY)\s+\S+",lambda match:match[1]+" "+str(quality),text)
    text=text.replace("\\\n", "") # Iris expands directive line continuations before GLSL parsing.
    text=re.sub(r'/\*.*?\*/',"",text,flags=re.S)
    text=re.sub(r'//[^\n]*',"",text)
    text=re.sub(r"(?m)^#version[^\n]*\n","",text,count=1)
    macros="""#version 330 compatibility
#define MC_VERSION 12001
#define IS_IRIS
#define IRIS_VERSION 10800
#define MC_OS_WINDOWS
#define MC_GL_VERSION 450
#define MC_RENDER_QUALITY 1.0
#define MC_SHADOW_QUALITY 1.0
"""
    if "#define COMPUTE_SHADER" in text: macros=macros.replace("#version 330", "#version 430")
    macros+="#define IRIS_FEATURE_CUSTOM_IMAGES\n#define IRIS_FEATURE_COMPUTE_SHADERS\n"
    macros+="#define MC_GL_VENDOR_AMD\n#define FIX_AMD_REFLECTION_CRASH\n" if amd else "#define MC_GL_VENDOR_NVIDIA\n"
    if dh:
        macros+="#define DISTANT_HORIZONS\n"
        # Exact Oculus1.8 StandardMacros values; dhMaterialId is normally injected
        # into vertex programs by Iris DHTerrainTransformer before desktop parsing.
        materials=("UNKNOWN","LEAVES","STONE","WOOD","METAL","DIRT","LAVA","DEEPSLATE",
                   "SNOW","SAND","TERRACOTTA","NETHER_STONE","WATER","GRASS","AIR","ILLUMINATED")
        macros+="".join(f"#define DH_BLOCK_{material} {index}\n" for index,material in enumerate(materials))
        text=text.replace("#define VERTEX_SHADER", "#define VERTEX_SHADER\nint dhMaterialId;", 1)
    return macros+text


def disabled_programs(compiler,temp,properties,profile):
    """Use the real GLSL preprocessor for original conditional program enablement."""
    definitions="".join("#define "+key+(" 1" if value=="true" else " "+value)+"\n"
                        for key,value in profile.items() if value!="false")
    lines=[]
    for line in properties.splitlines():
        stripped=line.strip()
        if re.match(r"#(?:if|ifdef|ifndef|elif|else|endif|define|undef)\b",stripped): lines.append(stripped)
        match=re.match(r"program\.([^/]+)/([^.]+)\.enabled\s*=\s*false",stripped)
        if match: lines.append("SKYCRAFT_DISABLED_"+match[1].replace("-","_minus_")+"__"+match[2])
    target=temp/"program-policy.comp"
    target.write_text("#version 430\n"+definitions+"\n".join(lines)+"\n",encoding="utf-8")
    result=subprocess.run([str(compiler),"-E","-S","comp",str(target)],capture_output=True,text=True)
    if result.returncode: raise ValueError("Program policy preprocessing failed: "+result.stdout+result.stderr)
    return {(folder.replace("_minus_","-"),program) for folder,program in re.findall(r"SKYCRAFT_DISABLED_(\w+)__(\w+)",result.stdout)}


def compute_cases(compiler,output,temp,sources,profile,full):
    results=[]
    for colored in ((0,128,256,1024) if full else (0,128)):
        options={**profile,"COLORED_LIGHTING":str(colored)}
        disabled=disabled_programs(compiler,temp,sources.properties,options)
        for folder in FOLDERS:
            for name in sorted(name for name in sources.raw if name.startswith("shaders/"+folder+"/") and name.endswith(".csh")):
                program=pathlib.Path(name).stem
                for amd in ((False,True) if full else (False,)):
                    tag=f"{folder}-{program}-compute-colored{colored}-{'amd' if amd else 'generic'}"
                    if (folder,program) in disabled:
                        # Never let a blanket disable masquerade as a lighting fix.
                        allowed=colored==0
                        result={"case":tag,"exit":0 if allowed else 1,"kind":"disabled-program-policy",
                                "output":"Host disables this compute program for selected options." if allowed else
                                         "Enabled colored lighting unexpectedly suppresses its compute program."}
                    else:
                        target=temp/"shader.comp"
                        target.write_text(prepare(sources.expand(name),options,2,False,amd),encoding="utf-8")
                        completed=subprocess.run([str(compiler),"-l","-S","comp",str(target)],capture_output=True,text=True)
                        log=completed.stdout+completed.stderr
                        result={"case":tag,"exit":completed.returncode,"kind":"compute-link","output":log}
                        if completed.returncode:
                            failure=output/tag;failure.mkdir(exist_ok=True)
                            shutil.copyfile(target,failure/target.name)
                            (failure/"compiler.log").write_text(log,encoding="utf-8")
                    results.append(result)
                    print(("PASS" if result["exit"]==0 else "FAIL"),tag,result["kind"],flush=True)
    return results


def run(compiler,output,pack=None,full=False,compute_only=False):
    output.mkdir(parents=True,exist_ok=True)
    patch=load_patch()
    if pack is None:
        pack=output/"matrix-fixture.zip"
        cached=next((ROOT/"dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip"))
        shutil.copyfile(cached,pack);patch.patch_shaderpack_zip(pack)
        solar_spec=importlib.util.spec_from_file_location("skycraft_solar_cleanup",pathlib.Path(__file__).with_name("patch_sun_horizon.py"))
        solar=importlib.util.module_from_spec(solar_spec);solar_spec.loader.exec_module(solar)
        solar.patch_zip(pack,pack)
    sources=Sources(pack)
    profile=dict(line.split("=",1) for line in patch.SHADER_TXT_CONTENT.splitlines() if "=" in line and not line.startswith("#"))
    results=[]
    with tempfile.TemporaryDirectory(prefix="skycraft-glsl-") as temporary:
        temp=pathlib.Path(temporary)
        for folder in (() if compute_only else FOLDERS):
            for quality in ((1,2,3) if full else (2,)):
                for dh in (False,True):
                    for amd in ((False,True) if full else (False,)):
                        programs=["deferred1","composite1"]
                        if full: programs += ["gbuffers_terrain","gbuffers_water","shadow"]+(["dh_terrain","dh_water"] if dh else [])
                        for program in programs:
                            tag=f"{folder}-{program}-q{quality}-{'dh' if dh else 'native'}-{'amd' if amd else 'generic'}"
                            paths=[]
                            for source_suffix,target_suffix in ((".vsh",".vert"),(".fsh",".frag")):
                                name=f"shaders/{folder}/{program}{source_suffix}"
                                target=temp/("shader"+target_suffix)
                                target.write_text(prepare(sources.expand(name),profile,quality,dh,amd),encoding="utf-8")
                                paths.append(target)
                            completed=subprocess.run([str(compiler),"-l",*(str(path) for path in paths)],capture_output=True,text=True)
                            text=completed.stdout+completed.stderr
                            result={"case":tag,"exit":completed.returncode,"kind":"graphics-link","output":text}
                            results.append(result)
                            if completed.returncode:
                                failure=output/tag;failure.mkdir(exist_ok=True)
                                for path in paths: shutil.copyfile(path,failure/path.name)
                                (failure/"compiler.log").write_text(text,encoding="utf-8")
                            print(("PASS" if completed.returncode==0 else "FAIL"),tag,flush=True)
        results.extend(compute_cases(compiler,output,temp,sources,profile,full))
    report={"scope":__doc__,"compiler":str(compiler),"compiler_sha256":hashlib.sha256(compiler.read_bytes()).hexdigest(),
            "pack":str(pack),"pack_sha256":hashlib.sha256(pack.read_bytes()).hexdigest(),"full":full,
            "passed":sum(result["exit"]==0 for result in results),"total":len(results),
            "graphics_links":sum(result.get("kind")=="graphics-link" for result in results),
            "compute_links":sum(result.get("kind")=="compute-link" for result in results),
            "disabled_policy_checks":sum(result.get("kind")=="disabled-program-policy" for result in results),"cases":results}
    (output/"report.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(f"Shader validation cases: {report['passed']}/{report['total']} ({report['graphics_links']} graphics links, {report['compute_links']} compute links, {report['disabled_policy_checks']} disabled policy checks)")
    return report["passed"]==report["total"]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler",type=pathlib.Path,required=True)
    parser.add_argument("--shaderpack",type=pathlib.Path)
    parser.add_argument("--full",action="store_true")
    parser.add_argument("--compute-only",action="store_true")
    parser.add_argument("--out",type=pathlib.Path,default=ROOT/"dist/code-review/shader-matrix")
    args=parser.parse_args()
    if not args.compiler.is_file(): parser.error("GLSL compiler missing")
    return 0 if run(args.compiler.resolve(),args.out,args.shaderpack,args.full,args.compute_only) else 1


if __name__=="__main__":raise SystemExit(main())
