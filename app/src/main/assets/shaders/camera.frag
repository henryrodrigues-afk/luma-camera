#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uCamera;
uniform mat4 uTransform;
void main() {
    vec2 uv = (uTransform * vec4(vUv, 0.0, 1.0)).xy;
    gl_FragColor = texture2D(uCamera, uv);
}
