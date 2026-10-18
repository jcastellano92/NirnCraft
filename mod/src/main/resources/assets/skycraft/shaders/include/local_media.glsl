#ifndef SKYCRAFT_REALM_LOCAL_MEDIA
#define SKYCRAFT_REALM_LOCAL_MEDIA
#include "/lib/atmospherics/skycraftInterior.glsl"
#include "/lib/atmospherics/skycraftVolumeNoise.glsl"
#include "/lib/atmospherics/skycraftInteriorDensity.glsl"
#include "/lib/atmospherics/skycraftFogLight.glsl"
vec3 SkycraftRealmLocalMedia(vec3 background,vec3 direction,float sceneDistance) {
    if(SkycraftAirBytes(ivec2(2,75)).r<0.5) return background;
    vec4 range=SkycraftAirBytes(ivec2(3,75));
    float nearM=(range.r+range.g*256.0)/16.0,farM=(range.b+range.a*256.0)/16.0;
    float ambientSigma=farM>nearM?1.0/(farM-nearM):0.0;
    float transmission=1.0;
    vec3 scattering=vec3(0.0);
    for(int step=0;step<16;step++) {
        float a=float(step)/16.0,b=float(step+1)/16.0;
        float start=128.0*a*a,end=min(sceneDistance,128.0*b*b);
        if(end<=start) break;
        float distanceM=(start+end)*0.5;
        vec3 receiver=direction*distanceM;
        vec4 room=SkycraftInteriorMedium(receiver);
        vec3 localLight=SkycraftLocalLight(receiver,vec3(0.0),false);
        vec3 lamps=SkycraftFogRadiance(receiver);
        float roomAlpha=1.0-exp(-room.a*(end-start));
        vec3 roomLight=room.rgb*(fogColor*0.04+lamps+localLight);
        scattering+=transmission*roomLight*roomAlpha;
        // Existing authored realm fog already supplies background extinction.
        // Add only its response to local emission; never apply that fog twice.
        float fogLightAlpha=exp(-max(0.0,start-nearM)*ambientSigma)
                -exp(-max(0.0,end-nearM)*ambientSigma);
        scattering+=transmission*(lamps+localLight)*max(0.0,fogLightAlpha)*(1.0-roomAlpha);
        transmission*=1.0-roomAlpha;
    }
    return background*transmission+scattering;
}
#endif
