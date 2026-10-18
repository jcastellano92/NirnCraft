"""Isolated realm shader variants built from the pinned pack, never from terrestrial weather patches."""
import pathlib
import re
import zipfile

PREFIX="shaders/skycraftRealm/"
REALMS={
    "skycraftIsles":("world0","skycraft:shivering_isles",True),
    "skycraftApocrypha":("world-1","skycraft:apocrypha",False),
    "skycraftSoulCairn":("world-1","skycraft:soul_cairn",False),
    "skycraftColdharbour":("world-1","skycraft:coldharbour",False),
}


def includes(text):
    return re.sub(r'(#include\s+")/(lib|program)/',r'\1/skycraftRealm/\2/',text)


def required_once(text,anchor,replacement,label):
    if text.count(anchor)!=1:
        raise ValueError(f"Realm shader anchor {label}: expected one occurrence, found {text.count(anchor)}")
    return text.replace(anchor,replacement,1)


def program_properties(text):
    """Clone base-folder program policy in place, preserving its option conditionals."""
    text=text.replace("\r\n","\n").replace("\r","\n")
    aliases={}
    for folder,(base,dimension,daynight) in REALMS.items(): aliases.setdefault(base,[]).append(folder)
    # Idempotent upgrade: remove only our generated folder-specific policy lines.
    folders="|".join(re.escape(folder) for folder in REALMS)
    text=re.sub(r"(?m)^[ \t]*program\.(?:"+folders+r")/[^\r\n]*(?:\r?\n|$)","",text)
    def clone(match):
        head,base,tail=match.groups()
        return match[0]+"".join("\n"+head+folder+tail for folder in aliases.get(base,()))
    return re.sub(r"(?m)^([ \t]*program\.)(world0|world-1)(/[^\r\n]+)",clone,text)


def sky_passthrough(textured):
    varying="in vec2 skycraftRealmUV;\n" if textured else ""
    sample="texture2D(tex,skycraftRealmUV)*skycraftRealmColor" if textured else "skycraftRealmColor"
    output_uv="out vec2 skycraftRealmUV;\n" if textured else ""
    assign_uv="skycraftRealmUV=(gl_TextureMatrix[0]*gl_MultiTexCoord0).xy;" if textured else ""
    return f'''#include "/skycraftRealm/lib/common.glsl"
// SKYCRAFT_AUTHORED_REALM_SKY: preserve RealmSky's dome, sprites and emission.
#ifdef FRAGMENT_SHADER
{varying}in vec4 skycraftRealmColor;
void main() {{
    /* DRAWBUFFERS:0 */
    gl_FragData[0]={sample};
}}
#endif
#ifdef VERTEX_SHADER
{output_uv}out vec4 skycraftRealmColor;
void main() {{ gl_Position=ftransform();skycraftRealmColor=gl_Color;{assign_uv} }}
#endif
'''


def build(upstream):
    output={}
    with zipfile.ZipFile(upstream) as pack:
        for name in pack.namelist():
            if not name.endswith((".glsl",".json")) or not name.startswith(("shaders/lib/","shaders/program/")):
                continue
            text=pack.read(name).decode("utf-8").replace("\r\n","\n")
            text=includes(text)
            if name=="shaders/lib/uniforms.glsl":
                text+='\n// SKYCRAFT_REALM_LOCAL_UNIFORMS\n'
                for sampler in ("skycraftFogRadianceTex","skycraftCloudNoiseTex","skycraftLocalOcclusionTex"):
                    text+="uniform sampler2D "+sampler+";\n"
            elif name=="shaders/lib/lighting/mainLighting.glsl":
                text='#include "/lib/atmospherics/skycraftInterior.glsl"\n'+text
                anchor="    // Vanilla Ambient Occlusion"
                text=required_once(text,anchor,
                    "    // SKYCRAFT_REALM_LOCAL_LIGHT: independent of sun, weather and moon phase.\n"
                    "    sceneLighting += SkycraftLocalLight(playerPos,normalize(mat3(gbufferModelViewInverse)*normalM),true);\n\n"+anchor,
                    "local receiver")
            elif name=="shaders/program/composite1.glsl":
                anchor="//Program//\nvoid main() {\n    vec3 color = texelFetch(colortex0, texelCoord, 0).rgb;"
                text=required_once(text,anchor,'#include "/lib/atmospherics/skycraftLocalMedia.glsl"\n\n'+anchor,"local medium include")
                anchor="    color = pow(color, vec3(2.2));"
                text=required_once(text,anchor,
                    "    // SKYCRAFT_REALM_LOCAL_INTEGRAL: clipped by native and LOD scene depth.\n"
                    "    if(isEyeInWater==0) color=SkycraftRealmLocalMedia(color,normalize(mat3(gbufferModelViewInverse)*nViewPos),min(lViewPos,lViewPos1));\n\n"+anchor,
                    "local medium integral")
            elif name=="shaders/lib/common.glsl":
                anchor="    #if NETHER_COLOR_MODE == 3"
                text=required_once(text,anchor,
                    "    // SKYCRAFT_REALM_PALETTE: respect the authored biome rather than a vanilla Nether lookup.\n"
                    "    #undef NETHER_COLOR_MODE\n    #define NETHER_COLOR_MODE 2\n"+anchor,"realm palette")
                text=required_once(text,"const vec3 endSkyColor = vec3(0.095, 0.07, 0.15) * 1.65;",
                    "vec3 endSkyColor = fogColor;","Sovngarde sky palette")
            elif name=="shaders/lib/colors/lightAndAmbientColors.glsl":
                text=required_once(text,"vec3 endLightColor = vec3(0.68, 0.51, 1.07);",
                    "vec3 endLightColor = fogColor/max(length(fogColor),0.0001)*1.35;","Sovngarde light palette")
            elif name in ("shaders/program/gbuffers_skybasic.glsl","shaders/program/gbuffers_skytextured.glsl"):
                text="#ifdef SKYCRAFT_REALM_DAYNIGHT\n"+text+"\n#else\n"+sky_passthrough("skytextured" in name)+"#endif\n"
            output[PREFIX+name.removeprefix("shaders/")]=text.encode()
        folders={"world-1":("world-1",False),"world1":("world1",False)}
        folders.update({folder:(base,daynight) for folder,(base,dimension,daynight) in REALMS.items()})
        for folder,(base,daynight) in folders.items():
            for name in pack.namelist():
                if not name.startswith("shaders/"+base+"/") or name.endswith("/"): continue
                text=includes(pack.read(name).decode("utf-8").replace("\r\n","\n"))
                if name.endswith((".fsh",".vsh",".csh",".gsh",".tcs",".tes")):
                    first,rest=text.split("\n",1)
                    flags="\n#define SKYCRAFT_REALM\n"+("#define SKYCRAFT_REALM_DAYNIGHT\n" if daynight else "")
                    text=first+flags+rest
                output["shaders/"+folder+"/"+name.rsplit("/",1)[1]]=text.encode()
        dimensions=pack.read("shaders/dimension.properties").decode("utf-8")
        for folder,(_,dimension,_) in REALMS.items(): dimensions+="\ndimension."+folder+"="+dimension
        output["shaders/dimension.properties"]=(dimensions+"\n").encode()
    return output
