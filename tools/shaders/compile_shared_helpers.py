"""Compile owned local-media/wind/celestial helpers with a real GLSL compiler.

This is a syntax/type gate for shared source, not full-pack linking or image acceptance.
Example: py -3 tools/shaders/compile_shared_helpers.py --compiler dist/.cache/glslang-16.6.0/glslang.exe
"""
import argparse
import json
import pathlib
import re
import subprocess

ROOT=pathlib.Path(__file__).resolve().parents[2]
ASSETS=ROOT/"mod/src/main/resources/assets/skycraft/shaders/include"
INCLUDES={"skycraftInterior":"interior","skycraftInteriorDensity":"interior_density",
          "skycraftLocalOcclusion":"local_occlusion","skycraftVolumeNoise":"volume_noise",
          "skycraftFogLight":"fog_light","skycraftLocalMedia":"local_media","skycraftWind":"wind"}


def expand(text):
    def include(match):
        name=match[1].rsplit("/",1)[-1].removesuffix(".glsl")
        if name not in INCLUDES: raise ValueError("Unregistered helper include: "+match[1])
        return expand((ASSETS/(INCLUDES[name]+".glsl")).read_text())
    return re.sub(r'#include\s+"([^"]+)"',include,text)


def run(compiler,out):
    out.mkdir(parents=True,exist_ok=True)
    results=[]
    declarations='''uniform sampler2D skycraftFogRadianceTex,skycraftLocalOcclusionTex,skycraftCloudNoiseTex,skycraftDynamicsTex,skycraftSmokeTex;
uniform vec3 cameraPosition,fogColor;
uniform mat4 gbufferModelViewInverse,gbufferModelView;
uniform float overworldAngle,sunPathRotation,timeAngle;
#define SUN_ROTATION_DATA vec2(1.0,0.0)
'''
    cases={
        "local-media":(expand((ASSETS/"local_media.glsl").read_text()),
                       "SkycraftRealmLocalMedia(vec3(0.1),normalize(vec3(1.0)),80.0)"),
        "wind":((ASSETS/"wind.glsl").read_text(),"SkycraftSampleDynamics(skycraftDynamicsTex,vec2(0.5)).rgb"),
        "celestial-frame":((ASSETS/"celestial_frame.glsl").read_text(),"SkycraftLightVector()"),
    }
    for version in (130,330):
        for name,(helper,expression) in cases.items():
            shader=out/f"{name}-{version}.frag"
            shader.write_text(f"#version {version}\n#define OVERWORLD\n"+declarations+helper+
                              "\nvoid main(){gl_FragColor=vec4("+expression+",1.0);}\n")
            completed=subprocess.run([str(compiler),"-S","frag",str(shader)],capture_output=True,text=True)
            results.append({"case":name,"glsl":version,"exit":completed.returncode,"output":completed.stdout+completed.stderr})
    report={"scope":"Owned helper GLSL syntax/type check; full pack compiler/link/visual matrix remains required.",
            "compiler":str(compiler),"cases":results,"passed":sum(r["exit"]==0 for r in results),"total":len(results)}
    (out/"report.json").write_text(json.dumps(report,indent=2)+"\n")
    for item in results:
        print(("PASS" if item["exit"]==0 else "FAIL"),item["case"],item["glsl"])
        if item["exit"]: print(item["output"])
    return report["passed"]==report["total"]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler",type=pathlib.Path,required=True)
    parser.add_argument("--out",type=pathlib.Path,default=ROOT/"dist/code-review/shader-helpers")
    args=parser.parse_args()
    if not args.compiler.is_file(): parser.error("GLSL compiler missing; pass an installed glslang executable")
    return 0 if run(args.compiler.resolve(),args.out) else 1


if __name__=="__main__": raise SystemExit(main())
