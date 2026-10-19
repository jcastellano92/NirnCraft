#ifndef NIRNCRAFT_INTERIOR_AIR
#define NIRNCRAFT_INTERIOR_AIR
#include "/lib/atmospherics/nirncraftLocalOcclusion.glsl"
// The local fog texture shares a sampler with propagated block light and roofs.
// Four selected media volumes plus four fading predecessors; four visible lights.
vec4 NirnCraftAirBytes(ivec2 p) { return floor(texelFetch(nirncraftFogRadianceTex,p,0)*255.0+0.5); }
vec3 NirnCraftAirOrigin() {
    // Signed world anchor in complete 32-bit texels. Local coordinates keep the
    // existing quarter-block precision; only the current frame origin changes.
    vec3 origin=vec3(0.0);
    for(int axis=0;axis<3;axis++) {
        vec4 bytes=NirnCraftAirBytes(ivec2(60+axis,74));
        origin[axis]=dot(bytes.rgb,vec3(1.0,256.0,65536.0))
                +(bytes.a<128.0?bytes.a:bytes.a-256.0)*16777216.0;
    }
    return origin-cameraPosition;
}
vec3 nirncraftAirOrigin=NirnCraftAirOrigin();
float NirnCraftAirTime() {
    vec4 bytes=NirnCraftAirBytes(ivec2(0,75));
    return (dot(bytes,vec4(1.0,256.0,65536.0,16777216.0))
            +texelFetch(nirncraftFogRadianceTex,ivec2(1,75),0).r)*0.05;
}
vec3 NirnCraftAirPoint(int column,int row) {
    vec4 a=NirnCraftAirBytes(ivec2(column,row)),b=NirnCraftAirBytes(ivec2(column+1,row));
    return (vec3(a.r+a.g*256.0,a.b+a.a*256.0,b.r+b.g*256.0)-32768.0)*0.25+nirncraftAirOrigin;
}
struct NirnCraftRoomAir { vec3 low; vec3 high; float floorY; float style; float weight; };
NirnCraftRoomAir NirnCraftReadRoom(int i) {
    if(NirnCraftAirBytes(ivec2(63,66)).r<=float(i))
        return NirnCraftRoomAir(vec3(0.0),vec3(0.0),0.0,0.0,0.0);
    vec4 data=NirnCraftAirBytes(ivec2(i,66));
    return NirnCraftRoomAir(NirnCraftAirPoint(i*2,64),NirnCraftAirPoint(i*2,65),
            (data.r+data.g*256.0-32768.0)*0.25+nirncraftAirOrigin.y,data.b,data.a);
}
NirnCraftRoomAir nirncraftRoom0=NirnCraftReadRoom(0),nirncraftRoom1=NirnCraftReadRoom(1),
        nirncraftRoom2=NirnCraftReadRoom(2),nirncraftRoom3=NirnCraftReadRoom(3),
        nirncraftRoom4=NirnCraftReadRoom(4),nirncraftRoom5=NirnCraftReadRoom(5),
        nirncraftRoom6=NirnCraftReadRoom(6),nirncraftRoom7=NirnCraftReadRoom(7);
NirnCraftRoomAir NirnCraftRoom(int i) {
    if(i==0) return nirncraftRoom0; if(i==1) return nirncraftRoom1;
    if(i==2) return nirncraftRoom2; if(i==3) return nirncraftRoom3;
    if(i==4) return nirncraftRoom4; if(i==5) return nirncraftRoom5;
    if(i==6) return nirncraftRoom6; return nirncraftRoom7;
}
struct NirnCraftAirLight { vec3 position; vec3 energy; float radius; };
NirnCraftAirLight NirnCraftReadAirLight(int i) {
    if(NirnCraftAirBytes(ivec2(63,69)).r<=float(i)) return NirnCraftAirLight(vec3(0.0),vec3(0.0),0.0);
    vec4 data=texelFetch(nirncraftFogRadianceTex,ivec2(i,69),0);
    return NirnCraftAirLight(NirnCraftAirPoint(i*2,68),data.rgb*4.0,data.a*32.0);
}
NirnCraftAirLight nirncraftAirLight0=NirnCraftReadAirLight(0),nirncraftAirLight1=NirnCraftReadAirLight(1),
        nirncraftAirLight2=NirnCraftReadAirLight(2),nirncraftAirLight3=NirnCraftReadAirLight(3);
NirnCraftAirLight NirnCraftReadLocalLight(int i) {
    if(i==0) return nirncraftAirLight0; if(i==1) return nirncraftAirLight1;
    if(i==2) return nirncraftAirLight2; return nirncraftAirLight3;
}
vec3 NirnCraftLocalLight(vec3 playerPos,vec3 normal,bool surface) {
    vec3 light=vec3(0.0);
    for(int i=0;i<4;i++) {
        NirnCraftAirLight source=NirnCraftReadLocalLight(i);
        if(source.radius<0.1) continue;
        vec3 delta=source.position-playerPos;
        float d2=dot(delta,delta),d=sqrt(d2);
        if(d>=source.radius) continue;
        float falloff=max(0.0,1.0-d/source.radius);
        float facing=surface?max(0.0,dot(normal,delta/max(0.05,d))):1.0;
        float visible=NirnCraftReceiverVisibility(playerPos+(surface?normal*0.03:vec3(0.0)),source.position);
        light+=source.energy*falloff*falloff/(1.0+0.08*d2)*facing*visible;
    }
    return light;
}
float NirnCraftAirDisplacement(vec3 playerPos) {
    if(NirnCraftAirBytes(ivec2(63,73)).r<1.0) return 1.0;
    float keep=1.0;
    for(int i=0;i<4;i++) {
        vec4 data=texelFetch(nirncraftFogRadianceTex,ivec2(i,73),0);
        if(data.a<0.5) continue;
        float age=data.g,radius=data.r*16.0*(0.25+age);
        float d=length(playerPos-NirnCraftAirPoint(i*2,72));
        keep*=1.0-(1.0-smoothstep(radius*0.45,radius,d))*sin(age*3.14159265)*0.85;
    }
    return keep;
}
#endif
