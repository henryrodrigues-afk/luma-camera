#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D uImage;
uniform sampler2D uPreviewLut;
// Preview gate, mix strength, cube side length. Atlas keeps GLES 2 devices supported.
uniform vec3 uLutTuning;
uniform vec3 uLutDomainMin;
uniform vec3 uLutDomainMax;
// Pack switches to leave the LUT within GLES 2's minimum fragment uniform budget.
uniform vec4 uDisplayTuning;
#define uLogAssist uDisplayTuning.x
#define uLogProfileVersion uDisplayTuning.y
#define uUvTransformEnabled uDisplayTuning.z
#define uStabilizationEnabled uDisplayTuning.w
uniform mat3 uUvTransform;
// Applied after orientation, in canonical source space; preview and MP4 share this sampler crop.
uniform float uStabilizationZoom;
uniform vec2 uStabilizationOffset;
// cos(theta), sin(theta), source width/height; rotation uses physical rather than stretched UV space.
uniform vec3 uStabilizationGeometry;
// Packed to stay within GLES 2's minimum fragment uniform budget and reduce driver calls.
// Modes: preview gate, zebra, false color, contour peaking. Tuning: threshold, sensitivity, opacity, LOG.
uniform vec4 uMonitorModes;
uniform vec4 uMonitorTuning;
uniform vec2 uSourceTexel;
float logChannel(float signal) {
    float linear = signal <= 0.04045 ? signal / 12.92 : pow((signal + 0.055) / 1.055, 2.4);
    return 0.09375 + 0.84375 * log(1.0 + 63.0 * linear) / log(64.0);
}
float decodeChannel(float encoded, float strength) {
    if (uLogProfileVersion > 1.5) {
        float floorValue = 0.09375 * strength;
        float ceilingValue = 1.0 - strength + 0.9375 * strength;
        if (encoded <= floorValue) {
            float slope = 1.0 - strength + strength * 0.84375 * 63.0 / (12.92 * log(64.0));
            return (encoded - floorValue) / slope;
        }
        if (encoded >= ceilingValue) {
            float slope = 1.0 - strength + strength * 0.84375 * 63.0 * 2.4 / (64.0 * 1.055 * log(64.0));
            return 1.0 + (encoded - ceilingValue) / slope;
        }
    }
    if (strength >= 0.9999) {
        float normalized = clamp((encoded - 0.09375) / 0.84375, 0.0, 1.0);
        float linear = (pow(64.0, normalized) - 1.0) / 63.0;
        return linear <= 0.0031308 ? 12.92 * linear : 1.055 * pow(linear, 1.0 / 2.4) - 0.055;
    }
    float low = 0.0;
    float high = 1.0;
    // Fixed iteration count compiles on GLES 2.0; error is under one 8-bit step.
    for (int i = 0; i < 9; i++) {
        float middle = (low + high) * 0.5;
        float candidate = mix(middle, logChannel(middle), strength);
        if (candidate < encoded) low = middle; else high = middle;
    }
    return (low + high) * 0.5;
}
vec3 decodeColor(vec3 encoded, float strength) {
    if (uLogProfileVersion > 1.5) {
        float luminance = dot(encoded, vec3(0.2126, 0.7152, 0.0722));
        encoded = vec3(luminance) + (encoded - luminance) / (1.0 - 0.4 * strength);
    }
    return clamp(vec3(decodeChannel(encoded.r, strength), decodeChannel(encoded.g, strength),
        decodeChannel(encoded.b, strength)), 0.0, 1.0);
}
vec3 falseColor(float luminance) {
    // Approximate SDR bands, not sensor clipping or calibrated camera IRE.
    if (luminance < 0.02) return vec3(0.25, 0.0, 0.45);
    if (luminance < 0.10) return vec3(0.10, 0.25, 0.90);
    if (luminance < 0.35) return vec3(0.0, 0.65, 0.75);
    if (luminance < 0.48) return vec3(0.30, 0.35, 0.40);
    if (luminance < 0.55) return vec3(1.0, 0.35, 0.55);
    if (luminance < 0.75) return vec3(0.30, 0.75, 0.25);
    if (luminance < 0.90) return vec3(1.0, 0.85, 0.15);
    if (luminance < 0.98) return vec3(1.0, 0.42, 0.05);
    return vec3(1.0, 0.12, 0.12);
}
vec3 previewLutColor(vec3 inputColor) {
    float size = uLutTuning.z;
    vec3 point = clamp((inputColor - uLutDomainMin) / max(uLutDomainMax - uLutDomainMin, vec3(0.0001)), 0.0, 1.0);
    point *= size - 1.0;
    float slice = floor(point.b);
    float nextSlice = min(slice + 1.0, size - 1.0);
    // Address texel centers within each slice, so bilinear filtering never crosses a blue tile edge.
    vec2 first = vec2((slice * size + point.r + 0.5) / (size * size), (point.g + 0.5) / size);
    vec2 second = vec2((nextSlice * size + point.r + 0.5) / (size * size), first.y);
    return mix(texture2D(uPreviewLut, first).rgb, texture2D(uPreviewLut, second).rgb, fract(point.b));
}
float inverseLogSlope(float sdrLuminance, float strength) {
    // Local contrast approximation avoids decoding twelve additional neighbour channels.
    float signal = clamp(sdrLuminance, 0.0, 1.0);
    float linear = signal <= 0.04045 ? signal / 12.92 : pow((signal + 0.055) / 1.055, 2.4);
    float derivative = signal <= 0.04045 ? 1.0 / 12.92 : 2.4 * linear / (signal + 0.055);
    float logSlope = 0.84375 * 63.0 * derivative / ((1.0 + 63.0 * linear) * log(64.0));
    return clamp(1.0 / max(0.125, mix(1.0, logSlope, strength)), 0.25, 8.0);
}
void main() {
    vec2 uv = uUvTransformEnabled > 0.5 ? (uUvTransform * vec3(vUv, 1.0)).xy : vUv;
    if (uStabilizationEnabled > 0.5) {
        vec2 centered = (uv - vec2(0.5)) / max(1.0, uStabilizationZoom);
        float aspect = max(0.0001, uStabilizationGeometry.z);
        uv = vec2(0.5) + vec2(uStabilizationGeometry.x * centered.x - uStabilizationGeometry.y * centered.y / aspect,
            uStabilizationGeometry.y * centered.x * aspect + uStabilizationGeometry.x * centered.y) + uStabilizationOffset;
    }
    vec4 image = texture2D(uImage, uv);
    vec3 encoded = image.rgb;
    if (uLogAssist > 0.0) image.rgb = decodeColor(encoded, uLogAssist);
    if (uLutTuning.x > 0.5) image.rgb = mix(image.rgb, previewLutColor(image.rgb), clamp(uLutTuning.y, 0.0, 1.0));
    // Encoder, clean meters and mask analysis never enter this branch.
    if (uMonitorModes.x > 0.5) {
        vec3 analysis = encoded;
        if (uMonitorTuning.w > 0.0) {
            analysis = uLutTuning.x < 0.5 && uLogAssist > 0.0 && abs(uLogAssist - uMonitorTuning.w) < 0.0001
                ? image.rgb : decodeColor(encoded, uMonitorTuning.w);
        }
        float luminance = dot(analysis, vec3(0.2126, 0.7152, 0.0722));
        float opacity = clamp(uMonitorTuning.z, 0.0, 1.0);
        if (uMonitorModes.z > 0.5) image.rgb = mix(image.rgb, falseColor(luminance), opacity);
        if (uMonitorModes.y > 0.5 && luminance >= clamp(uMonitorTuning.x, 0.0, 1.0)) {
            float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 12.0));
            image.rgb = mix(image.rgb, mix(vec3(0.08), vec3(1.0, 0.96, 0.40), stripe), opacity);
        }
        if (uMonitorModes.w > 0.5) {
            vec3 weights = vec3(0.2126, 0.7152, 0.0722);
            float left = dot(texture2D(uImage, uv - vec2(uSourceTexel.x, 0.0)).rgb, weights);
            float right = dot(texture2D(uImage, uv + vec2(uSourceTexel.x, 0.0)).rgb, weights);
            float down = dot(texture2D(uImage, uv - vec2(0.0, uSourceTexel.y)).rgb, weights);
            float up = dot(texture2D(uImage, uv + vec2(0.0, uSourceTexel.y)).rgb, weights);
            float gradient = abs(right - left) + abs(up - down);
            if (uMonitorTuning.w > 0.0) gradient *= inverseLogSlope(luminance, uMonitorTuning.w);
            float threshold = mix(0.12, 0.018, clamp(uMonitorTuning.y, 0.0, 1.0));
            float edge = smoothstep(threshold, threshold * 1.6, gradient);
            image.rgb = mix(image.rgb, vec3(0.78, 0.953, 0.42), opacity * edge);
        }
    }
    gl_FragColor = image;
}
