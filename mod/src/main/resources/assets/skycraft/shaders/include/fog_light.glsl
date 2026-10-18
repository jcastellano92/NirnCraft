#ifndef SKYCRAFT_FOG_LIGHT
#define SKYCRAFT_FOG_LIGHT
vec3 SkycraftFogRadiance(vec3 playerPos) {
    if(max(abs(playerPos.x),abs(playerPos.z))>26.0 || abs(playerPos.y)>26.0) return vec3(0.0);
    vec3 cellPos=(playerPos+cameraPosition)/4.0-0.5;
    vec3 base=floor(cellPos),f=fract(cellPos),radiance=vec3(0.0);
    for(int y=0;y<2;y++) for(int z=0;z<2;z++) for(int x=0;x<2;x++) {
        vec3 c=mod(base+vec3(x,y,z),16.0);
        vec2 uv=(vec2(mod(c.y,4.0),floor(c.y/4.0))*16.0+c.xz+vec2(64.5,0.5))/128.0;
        vec3 w=mix(1.0-f,f,vec3(x,y,z));
        radiance+=texture2DLod(skycraftFogRadianceTex,uv,0.0).rgb*w.x*w.y*w.z;
    }
    return radiance*max(radiance.r,max(radiance.g,radiance.b))*1.8;
}
#endif
