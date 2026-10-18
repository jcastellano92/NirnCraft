// Transient emissive curtains. Packet lifetimes also drive the diffuse lighting cache.
vec3 GetAuroraBorealis(vec3 viewPos, float VdotU, float dither) {
    vec4 state = texelFetch(skycraftSmokeTex, ivec2(5, 1), 0);
    vec4 options = texelFetch(skycraftSmokeTex, ivec2(7, 1), 0);
    float activity = texelFetch(skycraftSmokeTex, ivec2(15, 1), 0).a;
    vec3 ray = normalize(mat3(gbufferModelViewInverse) * viewPos);
    if (options.a < 0.5 || activity < 0.002 || ray.y < 0.015) return vec3(0.0);
    float seconds = SkycraftSkyTime();
    float azimuth = atan(ray.x, -ray.z);
    float elevation = asin(clamp(ray.y, -1.0, 1.0));
    vec3 emission = vec3(0.0);
    for (int packet = 0; packet < 6; packet++) {
        vec4 shape = texelFetch(skycraftSmokeTex, ivec2(16 + packet * 2, 1), 0);
        vec4 detail = texelFetch(skycraftSmokeTex, ivec2(17 + packet * 2, 1), 0);
        float amplitude = (detail.r * 255.0 + detail.g * 65280.0) / 65535.0;
        if (amplitude < 0.001) continue;
        float seed = detail.b * 37.0;
        float centre = shape.r * 6.2831853 - 3.14159265;
        float az = atan(sin(azimuth - centre), cos(azimuth - centre));
        float width = max(0.10, shape.g * 1.5);
        float angular = az / width;
        float spread = exp(-angular * angular * angular * angular * 1.4);
        if (spread < 0.002) continue;
        float drift = seconds * (0.025 + detail.a * 0.025);
        vec2 flow = texture2DLod(noisetex, vec2(az * 0.13 + drift * 0.017, seed * 0.17 + seconds * 0.0017), 0.0).rg;
        float fold = sin(az * 4.3 + seed + drift) * 0.06
                   + sin(az * 11.7 - drift * 1.7 + flow.g * 3.0) * 0.025
                   + (flow.r - 0.5) * 0.12;
        float hem = shape.b * 1.5 + fold;
        float height = elevation - hem;
        float extent = max(0.10, shape.a);
        float h = height / extent;
        if (h < -0.35 || h > 2.2) continue;
        // Broader developing patches fragment into ragged rays. There is no fixed line
        // of constant brightness along the bottom of every curtain.
        float breakup = texture2DLod(noisetex, vec2(az * 0.29 - drift * 0.025, h * 0.13 + seed), 0.0).g;
        float base = smoothstep(-0.20, 0.22 + breakup * 0.18, h);
        float deposition = base * exp(-max(h, 0.0) * (2.0 + detail.a));
        float rayBend = sin(h * 3.8 + seconds * 0.15 + seed) * (0.015 + 0.025 * detail.a) * clamp(h, 0.0, 1.0);
        float rays = texture2DLod(noisetex, vec2((az + rayBend) * 0.7 + flow.r * 0.10 - seconds * 0.0025, seed + seconds * 0.002), 0.0).r;
        rays = 0.14 + smoothstep(0.18, 0.83, rays) * 1.3;
        float folds = 0.35 + 0.65 * smoothstep(0.18, 0.75, breakup);
        float pulse = 0.60 + 0.40 * sin(az * 5.0 - seconds * (0.35 + detail.a * 0.35) + seed);
        vec3 oxygen = mix(vec3(0.055, 0.86, 0.32), vec3(0.10, 0.68, 0.77), state.r * 0.35 + flow.g * 0.25);
        vec3 nitrogen = vec3(0.42, 0.08, 0.65);
        vec3 upper = mix(vec3(0.20, 0.34, 0.60), vec3(0.80, 0.055, 0.07), state.b);
        vec3 color = mix(nitrogen, oxygen, smoothstep(0.01, 0.20, h));
        color = mix(color, upper, smoothstep(0.40, 1.40, h));
        emission += color * deposition * spread * amplitude * rays * folds * pulse * 2.5;
    }
    float horizon = smoothstep(0.015, 0.11, ray.y);
    return emission * horizon * (1.0 - sunVisibility2) * (1.0 - maxBlindnessDarkness);
}
