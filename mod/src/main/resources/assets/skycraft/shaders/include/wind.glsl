#ifndef SKYCRAFT_CIRCULAR_WIND
#define SKYCRAFT_CIRCULAR_WIND
float SkycraftMixWindAngle(float a,float b,float weight) {
    return fract(a+(fract(b-a+0.5)-0.5)*weight+1.0);
}

vec4 SkycraftSampleDynamics(sampler2D field,vec2 uv) {
    // Preserve regular bilinear interpolation for altitude/fog. Wind is a
    // periodic angle, so unwrap the four corners before interpolating it.
    vec2 cell=fract(uv)*128.0-0.5;
    ivec2 base=ivec2(floor(cell));
    vec2 weight=fract(cell);
    vec4 a=texelFetch(field,(base+ivec2(128))%128,0);
    vec4 b=texelFetch(field,(base+ivec2(129,128))%128,0);
    vec4 c=texelFetch(field,(base+ivec2(128,129))%128,0);
    vec4 d=texelFetch(field,(base+ivec2(129))%128,0);
    vec4 value=mix(mix(a,b,weight.x),mix(c,d,weight.x),weight.y);
    value.b=SkycraftMixWindAngle(SkycraftMixWindAngle(a.b,b.b,weight.x),
            SkycraftMixWindAngle(c.b,d.b,weight.x),weight.y);
    return value;
}
#endif
