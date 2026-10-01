precision mediump float;
varying vec2 vUv;
uniform sampler2D uImage;
uniform sampler2D uBlur;
uniform sampler2D uHistory;
uniform sampler2D uPortraitMask;
uniform sampler2D uPortraitBlur;
uniform vec2 uTexel;
uniform float uGain;
uniform float uTemperature;
uniform float uContrast;
uniform float uSaturation;
uniform float uSharpness;
uniform float uFlat;
uniform float uBlurEnabled;
uniform vec2 uCenter;
uniform vec2 uRadius;
uniform float uTrail;
uniform float uPortraitEnabled;
uniform float uPortraitStrength;
uniform float uPortraitEdgeSoftness;
uniform float uFocusBackground;
uniform float uLogStrength;
uniform float uLogProfileVersion;
float logChannel(float signal) {
    float linear = signal <= 0.04045 ? signal / 12.92 : pow((signal + 0.055) / 1.055, 2.4);
    return 0.09375 + 0.84375 * log(1.0 + 63.0 * linear) / log(64.0);
}
void main() {
    vec3 c = texture2D(uImage, vUv).rgb;
    if (uSharpness > 0.0) {
        vec3 neighbors = texture2D(uImage, vUv + vec2(uTexel.x, 0.0)).rgb;
        neighbors += texture2D(uImage, vUv - vec2(uTexel.x, 0.0)).rgb;
        neighbors += texture2D(uImage, vUv + vec2(0.0, uTexel.y)).rgb;
        neighbors += texture2D(uImage, vUv - vec2(0.0, uTexel.y)).rgb;
        c += (c - neighbors * 0.25) * uSharpness;
    }
    if (uBlurEnabled > 0.0) {
        float distanceFromCenter = length((vUv - uCenter) / uRadius);
        float background = smoothstep(0.8, 1.2, distanceFromCenter);
        c = mix(c, texture2D(uBlur, vUv).rgb, background);
    }
    if (uPortraitEnabled > 0.0) {
        float feather = clamp(0.14 + 0.4 * uPortraitEdgeSoftness, 0.14, 0.48);
        float foreground = smoothstep(0.5 - feather, 0.5 + feather, texture2D(uPortraitMask, vUv).r);
        float defocus = mix(1.0 - foreground, foreground, uFocusBackground);
        vec4 portraitBlur = texture2D(uPortraitBlur, vUv);
        float reliableBlur = smoothstep(0.04, 0.18, portraitBlur.a);
        c = mix(c, portraitBlur.rgb, defocus * uPortraitStrength * uPortraitEnabled * reliableBlur);
    }
    c *= exp2(uGain);
    c *= vec3(1.0 + 0.25 * uTemperature, 1.0, 1.0 - 0.25 * uTemperature);
    float luminance = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luminance), c, uSaturation);
    c = (c - 0.5) * uContrast + 0.5;
    c = mix(c, 0.12 + 0.76 * c, uFlat);
    c = clamp(c, 0.0, 1.0);
    if (uLogStrength > 0.0) {
        c = mix(c, vec3(logChannel(c.r), logChannel(c.g), logChannel(c.b)), uLogStrength);
        if (uLogProfileVersion > 1.5) {
            float logLuminance = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(vec3(logLuminance), c, 1.0 - 0.4 * uLogStrength);
        }
    }
    // History stores the encoded frame: the optional trail blends in that domain.
    if (uTrail > 0.0) c = mix(c, texture2D(uHistory, vUv).rgb, uTrail);
    gl_FragColor = vec4(c, 1.0);
}
