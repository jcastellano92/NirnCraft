// Included after the shared smooth cloud noise, so smoke and weather use one volume basis.
vec4 SkycraftInteriorMedium(vec3 playerPos) {
    float sigma=0.0;
    float sumDensity=0.0, maximumDensity=0.0;
    vec3 sumTint=vec3(0.0);
    vec3 tint=vec3(0.78,0.80,0.82);
    float roomNoise=-1.0;
    for(int i=0;i<8;i++) {
        SkycraftRoomAir room=SkycraftRoom(i);
        if(room.weight<0.5) continue;
        vec3 margin=min(playerPos-room.low,room.high-playerPos);
        float edge=min(margin.x,min(margin.y,margin.z));
        if(edge<=0.0) continue;
        float floorHeight=max(0.0,playerPos.y-room.floorY);
        float density=room.style<0.5?0.005:room.style<1.5?0.011:room.style<2.5?0.010:0.008;
        if(room.style>0.5) density+=0.028*exp(-floorHeight/2.2);
        vec3 world=playerPos+cameraPosition;
        if(roomNoise<0.0) roomNoise=SkycraftCloudNoise(world*0.10-vec3(SkycraftAirTime()*0.014,0.0,0.0));
        density*=smoothstep(0.0,2.0,edge)*(0.72+0.28*roomNoise);
        maximumDensity=max(maximumDensity,density);
        float weighted=density*room.weight/255.0;
        sumDensity+=weighted;
        vec3 roomTint=room.style<0.5?vec3(0.82,0.74,0.62):room.style<1.5?vec3(0.76,0.82,0.82)
                    :room.style<2.5?vec3(0.70,0.83,0.94):vec3(0.63,0.85,0.84);
        sumTint+=roomTint*weighted;
    }
    // A retiring and arriving copy of the same medium must preserve extinction.
    // Cap overlapping fully active rooms so their union never doubles the fog.
    sigma=min(sumDensity,maximumDensity);
    if(sumDensity>0.000001) tint=sumTint/sumDensity;
    int smokeCount=int(SkycraftAirBytes(ivec2(63,71)).r);
    for(int i=0;i<4;i++) {
        if(i>=smokeCount) break;
        vec3 offset=playerPos-SkycraftAirPoint(i*2,70);
        float height=texelFetch(skycraftFogRadianceTex,ivec2(i,71),0).r*32.0;
        if(offset.y<=0.0 || offset.y>=height) continue;
        float rise=offset.y/max(1.0,height);
        float radius=0.35+offset.y*0.18+smoothstep(0.65,0.95,rise)*2.5;
        vec3 world=playerPos+cameraPosition;
        float billow=SkycraftCloudNoise(world*vec3(1.3,0.8,1.3)-vec3(0.0,SkycraftAirTime()*0.5,0.0));
        float plume=(1.0-smoothstep(radius*0.3,radius*(0.8+billow*0.4),length(offset.xz)))
                *smoothstep(0.0,0.3,offset.y)*(1.0-smoothstep(0.90,1.0,rise))*0.16*(0.6+0.4*billow);
        tint=mix(tint,vec3(0.45),plume/max(0.0001,sigma+plume));
        sigma+=plume;
    }
    return vec4(tint,sigma*SkycraftAirDisplacement(playerPos));
}
