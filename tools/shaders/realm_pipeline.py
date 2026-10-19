"""Isolated realm shader variants built from the pinned pack, never from terrestrial weather patches."""
import pathlib
import re
import zipfile

PREFIX="shaders/nirncraftRealm/"
REALMS={
    "nirncraftIsles":("world0","nirncraft:shivering_isles",True),
    "nirncraftApocrypha":("world-1","nirncraft:apocrypha",False),
    "nirncraftSoulCairn":("world-1","nirncraft:soul_cairn",False),
    "nirncraftColdharbour":("world-1","nirncraft:coldharbour",False),
}


def includes(text):
    return re.sub(r'(#include\s+")/(lib|program)/',r'\1/nirncraftRealm/\2/',text)


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
    varying="in vec2 nirncraftRealmUV;\n" if textured else ""
    sample="texture2D(tex,nirncraftRealmUV)*nirncraftRealmColor" if textured else "nirncraftRealmColor"
    output_uv="out vec2 nirncraftRealmUV;\n" if textured else ""
    assign_uv="nirncraftRealmUV=(gl_TextureMatrix[0]*gl_MultiTexCoord0).xy;" if textured else ""
    return f'''#include "/nirncraftRealm/lib/common.glsl"
// NIRNCRAFT_AUTHORED_REALM_SKY: preserve RealmSky's dome, sprites and emission.
#ifdef FRAGMENT_SHADER
{varying}in vec4 nirncraftRealmColor;
void main() {{
    /* DRAWBUFFERS:0 */
    gl_FragData[0]={sample};
}}
#endif
#ifdef VERTEX_SHADER
{output_uv}out vec4 nirncraftRealmColor;
void main() {{ gl_Position=ftransform();nirncraftRealmColor=gl_Color;{assign_uv} }}
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
                text+='\n// NIRNCRAFT_REALM_LOCAL_UNIFORMS\n'
                for sampler in ("nirncraftFogRadianceTex","nirncraftCloudNoiseTex","nirncraftLocalOcclusionTex"):
                    text+="uniform sampler2D "+sampler+";\n"
            elif name=="shaders/lib/lighting/mainLighting.glsl":
                text='#include "/lib/atmospherics/nirncraftInterior.glsl"\n'+text
                anchor="    // Vanilla Ambient Occlusion"
                text=required_once(text,anchor,
                    "    // NIRNCRAFT_REALM_LOCAL_LIGHT: independent of sun, weather and moon phase.\n"
                    "    sceneLighting += NirnCraftLocalLight(playerPos,normalize(mat3(gbufferModelViewInverse)*normalM),true);\n\n"+anchor,
                    "local receiver")
            elif name=="shaders/program/composite1.glsl":
                anchor="//Program//\nvoid main() {\n    vec3 color = texelFetch(colortex0, texelCoord, 0).rgb;"
                text=required_once(text,anchor,'#include "/lib/atmospherics/nirncraftLocalMedia.glsl"\n\n'+anchor,"local medium include")
                anchor="    color = pow(color, vec3(2.2));"
                text=required_once(text,anchor,
                    "    // NIRNCRAFT_REALM_LOCAL_INTEGRAL: clipped by native and LOD scene depth.\n"
                    "    if(isEyeInWater==0) color=NirnCraftRealmLocalMedia(color,normalize(mat3(gbufferModelViewInverse)*nViewPos),min(lViewPos,lViewPos1));\n\n"+anchor,
                    "local medium integral")
            elif name=="shaders/lib/common.glsl":
                anchor="    #if NETHER_COLOR_MODE == 3"
                text=required_once(text,anchor,
                    "    // NIRNCRAFT_REALM_PALETTE: respect the authored biome rather than a vanilla Nether lookup.\n"
                    "    #undef NETHER_COLOR_MODE\n    #define NETHER_COLOR_MODE 2\n"+anchor,"realm palette")
                text=required_once(text,"const vec3 endSkyColor = vec3(0.095, 0.07, 0.15) * 1.65;",
                    "vec3 endSkyColor = fogColor;","Sovngarde sky palette")
            elif name=="shaders/lib/colors/lightAndAmbientColors.glsl":
                text=required_once(text,"vec3 endLightColor = vec3(0.68, 0.51, 1.07);",
                    "vec3 endLightColor = fogColor/max(length(fogColor),0.0001)*1.35;","Sovngarde light palette")
            elif name in ("shaders/program/gbuffers_skybasic.glsl","shaders/program/gbuffers_skytextured.glsl"):
                text="#ifdef NIRNCRAFT_REALM_DAYNIGHT\n"+text+"\n#else\n"+sky_passthrough("skytextured" in name)+"#endif\n"
            output[PREFIX+name.removeprefix("shaders/")]=text.encode()
        folders={"world-1":("world-1",False),"world1":("world1",False)}
        folders.update({folder:(base,daynight) for folder,(base,dimension,daynight) in REALMS.items()})
        for folder,(base,daynight) in folders.items():
            for name in pack.namelist():
                if not name.startswith("shaders/"+base+"/") or name.endswith("/"): continue
                text=includes(pack.read(name).decode("utf-8").replace("\r\n","\n"))
                if name.endswith((".fsh",".vsh",".csh",".gsh",".tcs",".tes")):
                    first,rest=text.split("\n",1)
                    flags="\n#define NIRNCRAFT_REALM\n"+("#define NIRNCRAFT_REALM_DAYNIGHT\n" if daynight else "")
                    text=first+flags+rest
                output["shaders/"+folder+"/"+name.rsplit("/",1)[1]]=text.encode()
        dimensions=pack.read("shaders/dimension.properties").decode("utf-8")
        for folder,(_,dimension,_) in REALMS.items(): dimensions+="\ndimension."+folder+"="+dimension
        output["shaders/dimension.properties"]=(dimensions+"\n").encode()
    return output
