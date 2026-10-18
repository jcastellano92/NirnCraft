#ifndef SKYCRAFT_INTERIOR_AIR
#define SKYCRAFT_INTERIOR_AIR
#include "/lib/atmospherics/skycraftLocalOcclusion.glsl"
// The local fog texture shares a sampler with propagated block light and roofs.
// Four selected media volumes plus four fading predecessors; four visible lights.
vec4 SkycraftAirBytes(ivec2 p) { return floor(texelFetch(skycraftFogRadianceTex,p,0)*255.0+0.5); }
vec3 SkycraftAirOrigin() {
    // Signed world anchor in complete 32-bit texels. Local coordinates keep the
    // existing quarter-block precision; only the current frame origin changes.
    vec3 origin=vec3(0.0);
    for(int axis=0;axis<3;axis++) {
        vec4 bytes=SkycraftAirBytes(ivec2(60+axis,74));
        origin[axis]=dot(bytes.rgb,vec3(1.0,256.0,65536.0))
                +(bytes.a<128.0?bytes.a:bytes.a-256.0)*16777216.0;
    }
    return origin-cameraPosition;
}
vec3 skycraftAirOrigin=SkycraftAirOrigin();
float SkycraftAirTime() {
    vec4 bytes=SkycraftAirBytes(ivec2(0,75));
    return (dot(bytes,vec4(1.0,256.0,65536.0,16777216.0))
            +texelFetch(skycraftFogRadianceTex,ivec2(1,75),0).r)*0.05;
}
vec3 SkycraftAirPoint(int column,int row) {
    vec4 a=SkycraftAirBytes(ivec2(column,row)),b=SkycraftAirBytes(ivec2(column+1,row));
    return (vec3(a.r+a.g*256.0,a.b+a.a*256.0,b.r+b.g*256.0)-32768.0)*0.25+skycraftAirOrigin;
}
struct SkycraftRoomAir { vec3 low; vec3 high; float floorY; float style; float weight; };
SkycraftRoomAir SkycraftReadRoom(int i) {
    if(SkycraftAirBytes(ivec2(63,66)).r<=float(i))
        return SkycraftRoomAir(vec3(0.0),vec3(0.0),0.0,0.0,0.0);
    vec4 data=SkycraftAirBytes(ivec2(i,66));
    return SkycraftRoomAir(SkycraftAirPoint(i*2,64),SkycraftAirPoint(i*2,65),
            (data.r+data.g*256.0-32768.0)*0.25+skycraftAirOrigin.y,data.b,data.a);
}
SkycraftRoomAir skycraftRoom0=SkycraftReadRoom(0),skycraftRoom1=SkycraftReadRoom(1),
        skycraftRoom2=SkycraftReadRoom(2),skycraftRoom3=SkycraftReadRoom(3),
        skycraftRoom4=SkycraftReadRoom(4),skycraftRoom5=SkycraftReadRoom(5),
        skycraftRoom6=SkycraftReadRoom(6),skycraftRoom7=SkycraftReadRoom(7);
SkycraftRoomAir SkycraftRoom(int i) {
    if(i==0) return skycraftRoom0; if(i==1) return skycraftRoom1;
    if(i==2) return skycraftRoom2; if(i==3) return skycraftRoom3;
    if(i==4) return skycraftRoom4; if(i==5) return skycraftRoom5;
    if(i==6) return skycraftRoom6; return skycraftRoom7;
}
struct SkycraftAirLight { vec3 position; vec3 energy; float radius; };
SkycraftAirLight SkycraftReadAirLight(int i) {
    if(SkycraftAirBytes(ivec2(63,69)).r<=float(i)) return SkycraftAirLight(vec3(0.0),vec3(0.0),0.0);
    vec4 data=texelFetch(skycraftFogRadianceTex,ivec2(i,69),0);
    return SkycraftAirLight(SkycraftAirPoint(i*2,68),data.rgb*4.0,data.a*32.0);
}
SkycraftAirLight skycraftAirLight0=SkycraftReadAirLight(0),skycraftAirLight1=SkycraftReadAirLight(1),
        skycraftAirLight2=SkycraftReadAirLight(2),skycraftAirLight3=SkycraftReadAirLight(3);
SkycraftAirLight SkycraftReadLocalLight(int i) {
    if(i==0) return skycraftAirLight0; if(i==1) return skycraftAirLight1;
    if(i==2) return skycraftAirLight2; return skycraftAirLight3;
}
vec3 SkycraftLocalLight(vec3 playerPos,vec3 normal,bool surface) {
    vec3 light=vec3(0.0);
    for(int i=0;i<4;i++) {
        SkycraftAirLight source=SkycraftReadLocalLight(i);
        if(source.radius<0.1) continue;
        vec3 delta=source.position-playerPos;
        float d2=dot(delta,delta),d=sqrt(d2);
        if(d>=source.radius) continue;
        float falloff=max(0.0,1.0-d/source.radius);
        float facing=surface?max(0.0,dot(normal,delta/max(0.05,d))):1.0;
        float visible=SkycraftReceiverVisibility(playerPos+(surface?normal*0.03:vec3(0.0)),source.position);
        light+=source.energy*falloff*falloff/(1.0+0.08*d2)*facing*visible;
    }
    return light;
}
float SkycraftAirDisplacement(vec3 playerPos) {
    if(SkycraftAirBytes(ivec2(63,73)).r<1.0) return 1.0;
    float keep=1.0;
    for(int i=0;i<4;i++) {
        vec4 data=texelFetch(skycraftFogRadianceTex,ivec2(i,73),0);
        if(data.a<0.5) continue;
        float age=data.g,radius=data.r*16.0*(0.25+age);
        float d=length(playerPos-SkycraftAirPoint(i*2,72));
        keep*=1.0-(1.0-smoothstep(radius*0.45,radius,d))*sin(age*3.14159265)*0.85;
    }
    return keep;
}
#endif
