#ifndef NIRNCRAFT_LOCAL_OCCLUSION
#define NIRNCRAFT_LOCAL_OCCLUSION
vec4 NirnCraftCollisionBytes(ivec2 pixel) {
    return floor(texelFetch(nirncraftLocalOcclusionTex,pixel,0)*255.0+0.5);
}
float NirnCraftCollisionInt(vec4 data) {
    return dot(data.rgb,vec3(1.0,256.0,65536.0))+(data.a<128.0?data.a:data.a-256.0)*16777216.0;
}
bool nirncraftCollisionLoaded=false,nirncraftCollisionActive=false;
vec3 nirncraftCollisionLow=vec3(0.0);
void NirnCraftLoadCollision() {
    if(nirncraftCollisionLoaded) return;
    nirncraftCollisionLoaded=true;
    nirncraftCollisionActive=NirnCraftCollisionBytes(ivec2(3,260)).r>0.5;
    if(nirncraftCollisionActive)
        nirncraftCollisionLow=vec3(NirnCraftCollisionInt(NirnCraftCollisionBytes(ivec2(0,260))),
                                 NirnCraftCollisionInt(NirnCraftCollisionBytes(ivec2(1,260))),
                                 NirnCraftCollisionInt(NirnCraftCollisionBytes(ivec2(2,260))));
}
float NirnCraftCollisionByte(int address,int row) {
    vec4 bytes=NirnCraftCollisionBytes(ivec2((address/4)%256,row+address/1024));
    return bytes[address%4];
}
vec3 NirnCraftCollisionPoint(int address) {
    vec4 a=NirnCraftCollisionBytes(ivec2(address%256,262+address/256));
    address++;
    vec4 b=NirnCraftCollisionBytes(ivec2(address%256,262+address/256));
    return (vec3(a.r+a.g*256.0,a.b+a.a*256.0,b.r+b.g*256.0)-16384.0)/16384.0;
}
bool NirnCraftCollisionBox(vec3 origin,vec3 direction,vec3 low,vec3 high,float end) {
    vec3 safe=direction;
    for(int axis=0;axis<3;axis++) if(abs(safe[axis])<0.0000001) safe[axis]=0.0000001;
    vec3 a=(low-origin)/safe,b=(high-origin)/safe;
    vec3 entry=min(a,b),exit=max(a,b);
    float nearHit=max(0.03,max(entry.x,max(entry.y,entry.z)));
    float farHit=min(end-0.06,min(exit.x,min(exit.y,exit.z)));
    return farHit>nearHit;
}
float NirnCraftReceiverVisibility(vec3 receiver,vec3 source) {
    NirnCraftLoadCollision();
    if(!nirncraftCollisionActive) return 1.0;
    vec3 low=nirncraftCollisionLow;
    vec3 start=receiver+cameraPosition,target=source+cameraPosition;
    vec3 margins=min(min(start-low,low+64.0-start),min(target-low,low+64.0-target));
    float margin=min(margins.x,min(margins.y,margins.z));
    // Fade only the coverage boundary of this explicitly bounded cache. Source
    // visibility inside the cache is queried from the actual receiver, never camera.
    if(margin<=0.0) return 1.0;
    vec3 delta=target-start;
    float lengthM=length(delta);
    if(lengthM<0.12) return 1.0;
    vec3 direction=delta/lengthM;
    float distanceM=0.03,visible=1.0;
    for(int iteration=0;iteration<96;iteration++) {
        if(distanceM>=lengthM-0.06) break;
        vec3 position=start+direction*distanceM;
        ivec3 cell=ivec3(floor(position)),macro=ivec3(mod(floor(position/4.0),16.0));
        int macroAddress=(macro.y*16+macro.z)*16+macro.x;
        float scale=NirnCraftCollisionByte(macroAddress,256)<0.5?4.0:1.0;
        if(scale<1.5) {
            ivec3 wrapped=cell&ivec3(63);
            int id=int(NirnCraftCollisionByte((wrapped.y*64+wrapped.z)*64+wrapped.x,0));
            if(id==255) return 1.0; // Unavailable geometry is not claimed as validated visibility.
            if(id>0) {
                int count=int(NirnCraftCollisionBytes(ivec2(id,261)).r);
                for(int box=0;box<8;box++) {
                    if(box>=count) break;
                    int address=id*32+box*4;
                    vec3 boxLow=vec3(cell)+NirnCraftCollisionPoint(address);
                    vec3 boxHigh=vec3(cell)+NirnCraftCollisionPoint(address+2);
                    if(NirnCraftCollisionBox(start,direction,boxLow,boxHigh,lengthM)) { visible=0.0;break; }
                }
                if(visible<0.5) break;
            }
        }
        vec3 grid=floor(position/scale)*scale;
        vec3 safe=direction;
        for(int axis=0;axis<3;axis++) if(abs(safe[axis])<0.0000001) safe[axis]=0.0000001;
        vec3 boundary=grid+step(vec3(0.0),direction)*scale;
        vec3 next=(boundary-start)/safe;
        // Axis-parallel rays never select a boundary on their zero component.
        for(int axis=0;axis<3;axis++) if(abs(direction[axis])<0.0000001) next[axis]=1e20;
        distanceM=min(next.x,min(next.y,next.z))+0.00001;
    }
    return mix(1.0,visible,smoothstep(0.0,4.0,margin));
}
#endif
