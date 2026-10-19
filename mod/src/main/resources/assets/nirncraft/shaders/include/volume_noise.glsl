#ifndef NIRNCRAFT_VOLUME_NOISE
#define NIRNCRAFT_VOLUME_NOISE
float NirnCraftCloudNoise(vec3 p) {
    vec3 voxel=fract(p/16.0)*64.0;
    float z0=floor(voxel.z),z1=mod(z0+1.0,64.0);
    vec2 tile0=vec2(mod(z0,8.0),floor(z0/8.0));
    vec2 tile1=vec2(mod(z1,8.0),floor(z1/8.0));
    vec4 a=texture2DLod(nirncraftCloudNoiseTex,(tile0*66.0+voxel.xy+1.5)/528.0,0.0);
    vec4 b=texture2DLod(nirncraftCloudNoiseTex,(tile1*66.0+voxel.xy+1.5)/528.0,0.0);
    vec4 noise=mix(a,b,fract(voxel.z));
    return mix(noise.b,noise.r,0.30);
}
#endif
