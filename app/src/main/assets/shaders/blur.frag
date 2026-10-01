precision mediump float;
varying vec2 vUv;
uniform sampler2D uImage;
uniform vec2 uStep;
uniform sampler2D uPortraitMask;
// x: 0=ordinary blur, 1=premultiplied masked horizontal pass, 2=normalize vertical pass.
// y selects the defocused region (0=background, 1=subject); z is its confidence feather.
uniform vec3 uPortraitWeights;
vec4 blurSample(vec2 uv) {
    vec4 color = texture2D(uImage, uv);
    if (uPortraitWeights.x > 0.5 && uPortraitWeights.x < 1.5) {
        float feather = clamp(uPortraitWeights.z, 0.14, 0.48);
        float confidence = texture2D(uPortraitMask, uv).r;
        // Reserve uncertain boundary samples for the original image. Otherwise bilinear
        // sampling can carry bright subject colors into the blurred background (or vice versa).
        float reserve = 0.5 * min(feather, 0.30);
        float backgroundSupport = 1.0 - smoothstep(0.05, 0.5 - reserve, confidence);
        float foregroundSupport = smoothstep(0.5 + reserve, 0.95, confidence);
        float support = mix(backgroundSupport, foregroundSupport, clamp(uPortraitWeights.y, 0.0, 1.0));
        return vec4(color.rgb * support, support);
    }
    return color;
}
void main() {
    vec4 c = blurSample(vUv) * 0.227027;
    c += blurSample(vUv + uStep * 1.384615) * 0.316216;
    c += blurSample(vUv - uStep * 1.384615) * 0.316216;
    c += blurSample(vUv + uStep * 3.230769) * 0.070270;
    c += blurSample(vUv - uStep * 3.230769) * 0.070270;
    if (uPortraitWeights.x > 1.5) {
        // Keep support in alpha. The composite preserves original pixels when too few
        // samples belong to the intended region, instead of producing dark cutout edges.
        c = vec4(clamp(c.rgb / max(c.a, 0.01), 0.0, 1.0), clamp(c.a, 0.0, 1.0));
    }
    gl_FragColor = c;
}
