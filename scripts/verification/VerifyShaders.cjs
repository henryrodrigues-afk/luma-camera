// Runs the actual 2D GLSL assets against synthetic pixels in headless WebGL/ANGLE.
// This validates shader behavior; it is not an Android EGL/camera/MP4 device test.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {encode: encodeLog,encodeRgb,cube} = require('../tools/GenerateLogLut.cjs');
const {projectPaths} = require('../lib/ProjectPaths.cjs');
const root = projectPaths.projectRoot;
let playwright;
try { playwright = require('playwright'); }
catch {
  const bundled = path.join(process.env.USERPROFILE || '', '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
  playwright = require(bundled);
}
const assets = path.join(root, 'app/src/main/assets/shaders');
const sources = Object.fromEntries(['fullscreen.vert', 'camera.frag', 'copy.frag', 'blur.frag', 'effects.frag'].map(file => [file, fs.readFileSync(path.join(assets, file), 'utf8')]));
(async () => {
  const browser = await playwright.chromium.launch({
    executablePath: process.env.LUMA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']
  });
  try {
    const page = await browser.newPage();
    const result = await page.evaluate(({sources}) => {
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 16;
      const gl = canvas.getContext('webgl', {preserveDrawingBuffer:true, antialias:false});
      if (!gl) throw new Error('WebGL unavailable');
      const compile = (kind, source) => {
        const shader = gl.createShader(kind); gl.shaderSource(shader, source); gl.compileShader(shader);
        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
        return shader;
      };
      const programs = {};
      // Browser WebGL cannot provide Android's external camera texture. Substitute
      // only the sampler target; retain the actual camera shader transform/body.
      sources['camera.mock.frag']=sources['camera.frag'].replace(/^#extension GL_OES_EGL_image_external : require\s*/,'').replace('samplerExternalOES','sampler2D');
      for (const file of ['camera.mock.frag','copy.frag', 'blur.frag', 'effects.frag']) {
        const p = gl.createProgram(); gl.attachShader(p, compile(gl.VERTEX_SHADER, sources['fullscreen.vert']));
        gl.attachShader(p, compile(gl.FRAGMENT_SHADER, sources[file])); gl.linkProgram(p);
        if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
        programs[file] = p;
      }
      const vertices = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vertices);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
      function texture(pixels) {
        const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,t);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
        gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,16,16,0,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
        return t;
      }
      function lutTexture(size, transform) {
        const pixels=new Uint8Array(size**3*4);
        for(let g=0;g<size;g++) for(let b=0;b<size;b++) for(let r=0;r<size;r++) {
          const color=transform([r/(size-1),g/(size-1),b/(size-1)]);
          pixels.set([...color.map(v=>Math.round(Math.max(0,Math.min(1,v))*255)),255],(g*size*size+b*size+r)*4);
        }
        const t=gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,t);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
        gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,size*size,size,0,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
        return t;
      }
      const identityLut17=lutTexture(17,rgb=>rgb), identityLut33=lutTexture(33,rgb=>rgb);
      const inverseLut33=lutTexture(33,rgb=>rgb.map(v=>1-v));
      const blueSwapLut17=lutTexture(17,([r,g,b])=>[b,g,r]);
      const solid = rgb => {
        const pixels = new Uint8Array(16*16*4);
        for(let i=0;i<256;i++) pixels.set([...rgb,255],i*4);
        return texture(pixels);
      };
      const image = solid([64,96,128]), blur = solid([200,210,220]), history = solid([0,0,0]);
      const checkerPixels = new Uint8Array(16*16*4);
      for(let y=0;y<16;y++) for(let x=0;x<16;x++) {
        const v=(x+y)%2 ? 192 : 64; checkerPixels.set([v,v,v,255],(y*16+x)*4);
      }
      const checker = texture(checkerPixels);
      const primary = solid([224,32,64]);
      const cornerPixels = new Uint8Array(16*16*4);
      const coordinatePixels = new Uint8Array(16*16*4);
      for(let y=0;y<16;y++) for(let x=0;x<16;x++) {
        const color = y<8 ? (x<8 ? [255,0,0] : [0,255,0]) : (x<8 ? [0,0,255] : [255,255,0]);
        cornerPixels.set([...color,255],(y*16+x)*4);
        coordinatePixels.set([Math.round(x/15*255),Math.round(y/15*255),0,255],(y*16+x)*4);
      }
      const corners = texture(cornerPixels);
      const coordinates = texture(coordinatePixels);
      const portraitPixels = new Uint8Array(16*16*4);
      const edgePixels = new Uint8Array(16*16*4);
      const gradientPixels = new Uint8Array(16*16*4);
      for(let y=0;y<16;y++) for(let x=0;x<16;x++) {
        const foreground = x>=4&&x<12&&y>=4&&y<12 ? 255 : 0;
        portraitPixels.set([foreground,foreground,foreground,255],(y*16+x)*4);
        const value = Math.round(x/15*255);
        gradientPixels.set([value,value,value,255],(y*16+x)*4);
        const edgeValue = x < 8 ? 40 : 220;
        edgePixels.set([edgeValue,edgeValue,edgeValue,255],(y*16+x)*4);
      }
      const portraitMask = texture(portraitPixels), gradient = texture(gradientPixels), edgeTexture = texture(edgePixels);
      const neutral = {uGain:0,uTemperature:0,uContrast:1,uSaturation:1,uSharpness:0,uFlat:0,uBlurEnabled:0,uTrail:0,
        uLogStrength:0,uLogProfileVersion:2,uPortraitEnabled:0,uPortraitStrength:0,uPortraitEdgeSoftness:.35,uFocusBackground:0,
        uTexel:[1/16,1/16],uCenter:[.5,.5],uRadius:[.3,.3]};
      function render(file, values={}, sourceImage=image, maskImage=portraitMask, previewLut=identityLut17, blurTargets={}) {
        const p=programs[file]; gl.useProgram(p); gl.viewport(0,0,16,16);
        const position=gl.getAttribLocation(p,'aPosition'); gl.enableVertexAttribArray(position);
        gl.vertexAttribPointer(position,2,gl.FLOAT,false,0,0);
        for(const [name,t,unit] of [['uImage',sourceImage,0],['uCamera',sourceImage,0],['uBlur',blurTargets.oval||blur,1],['uHistory',history,2],['uPortraitMask',maskImage,3],['uPortraitBlur',blurTargets.portrait||blur,4],['uPreviewLut',previewLut,5]]) {
          gl.activeTexture(gl.TEXTURE0+unit); gl.bindTexture(gl.TEXTURE_2D,t);
          gl.uniform1i(gl.getUniformLocation(p,name),unit);
        }
        const uniforms = file==='effects.frag' ? {...neutral,...values} : file==='copy.frag' ? {
          uLogAssist:0,uLogProfileVersion:2,uUvTransformEnabled:0,uStabilizationEnabled:0,
          uStabilizationZoom:1,uStabilizationOffset:[0,0],uPreviewMonitorEnabled:0,
          uStabilizationGeometry:[1,0,1],
          uZebraEnabled:0,uZebraThreshold:.95,uFalseColorEnabled:0,uPeakingEnabled:0,uPeakingStrength:.5,
          uMonitorOverlayStrength:.6,uMonitorLogStrength:0,uSourceTexel:[1/16,1/16],
          uLutTuning:[0,1,17],uLutDomainMin:[0,0,0],uLutDomainMax:[1,1,1],...values} :
          file==='blur.frag' ? {uPortraitWeights:[0,0,.28],uStep:[0,0],...values} : values;
        if(file==='copy.frag') {
          // Match GpuPipeline's packed binding; named switches above make each contract readable.
          uniforms.uMonitorModes=[uniforms.uPreviewMonitorEnabled,uniforms.uZebraEnabled,uniforms.uFalseColorEnabled,uniforms.uPeakingEnabled];
          uniforms.uMonitorTuning=[uniforms.uZebraThreshold,uniforms.uPeakingStrength,uniforms.uMonitorOverlayStrength,uniforms.uMonitorLogStrength];
          uniforms.uDisplayTuning=[uniforms.uLogAssist,uniforms.uLogProfileVersion,uniforms.uUvTransformEnabled,uniforms.uStabilizationEnabled];
        }
        for(const [name,value] of Object.entries(uniforms)) {
          const location=gl.getUniformLocation(p,name);
          if(Array.isArray(value)&&value.length===16) gl.uniformMatrix4fv(location,false,value);
          else if(Array.isArray(value)&&value.length===9) gl.uniformMatrix3fv(location,false,value);
          else if(Array.isArray(value)&&value.length===4) gl.uniform4f(location,...value);
          else if(Array.isArray(value)&&value.length===3) gl.uniform3f(location,...value);
          else if(Array.isArray(value)) gl.uniform2f(location,...value); else gl.uniform1f(location,value);
        }
        gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
        const pixels=new Uint8Array(16*16*4); gl.readPixels(0,0,16,16,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
        const error=gl.getError(); if(error) throw new Error('GL error '+error);
        const pixel=(x,y)=>Array.from(pixels.slice((y*16+x)*4,(y*16+x)*4+3));
        const sample = {center:pixel(8,8),corner:pixel(0,0),bottomRight:pixel(15,0),topLeft:pixel(0,15),
          topRight:pixel(15,15),leftEdge:pixel(7,8),flatLeft:pixel(3,8)};
        Object.defineProperty(sample,'pixels',{value:pixels});
        return sample;
      }
      const subjectPixels = new Uint8Array(16*16*4);
      for(let y=0;y<16;y++) for(let x=0;x<16;x++) {
        const foreground=x>=4&&x<12&&y>=4&&y<12;
        subjectPixels.set([...(foreground ? [240,30,20] : [10,30,230]),255],(y*16+x)*4);
      }
      const subjectImage=texture(subjectPixels), noSupport=texture(new Uint8Array(16*16*4));
      const emptyMask=solid([0,0,0]), fullMask=solid([255,255,255]), ovalGreen=solid([20,210,40]);
      function twoPassBlur(source,focus,mask=portraitMask,weighted=true) {
        const horizontal=render('blur.frag',{uPortraitWeights:[weighted?1:0,focus,.28],uStep:[1/16,0]},source,mask);
        return render('blur.frag',{uPortraitWeights:[weighted?2:0,focus,.28],uStep:[0,1/16]},texture(horizontal.pixels),mask);
      }
      const weightedBackground=twoPassBlur(subjectImage,0), weightedForeground=twoPassBlur(subjectImage,1);
      const ordinarySubjectBlur=twoPassBlur(subjectImage,0,portraitMask,false);
      const backgroundBlurTexture=texture(weightedBackground.pixels), foregroundBlurTexture=texture(weightedForeground.pixels);
      const weightedBlurContracts={
        background:weightedBackground,foreground:weightedForeground,ordinary:ordinarySubjectBlur,
        foregroundBoundary:Array.from(weightedForeground.pixels.slice((8*16+4)*4,(8*16+4)*4+3)),
        composite:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1},subjectImage,portraitMask,identityLut17,{portrait:backgroundBlurTexture}),
        inverse:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1,uFocusBackground:1},subjectImage,portraitMask,identityLut17,{portrait:foregroundBlurTexture}),
        unsupported:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1,uFocusBackground:1},subjectImage,portraitMask,identityLut17,{portrait:noSupport}),
        disabled:render('effects.frag',{uPortraitEnabled:0,uPortraitStrength:1},subjectImage,emptyMask,identityLut17,{portrait:noSupport}),
        zeroStrength:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:0},subjectImage,portraitMask,identityLut17,{portrait:backgroundBlurTexture}),
        zeroBackgroundSupport:twoPassBlur(subjectImage,0,fullMask),
        zeroForegroundSupport:twoPassBlur(subjectImage,1,emptyMask),
        portraitSamplerIndependent:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1},subjectImage,portraitMask,identityLut17,{oval:ovalGreen,portrait:backgroundBlurTexture}),
        ovalSamplerIndependent:render('effects.frag',{uBlurEnabled:1,uPortraitEnabled:0},subjectImage,portraitMask,identityLut17,{oval:ovalGreen,portrait:backgroundBlurTexture}),
        bothSamplers:render('effects.frag',{uBlurEnabled:1,uPortraitEnabled:1,uPortraitStrength:.35},subjectImage,portraitMask,identityLut17,{oval:ovalGreen,portrait:backgroundBlurTexture}),
        backgroundSupportCenter:weightedBackground.pixels[(8*16+8)*4+3],
        zeroSupportBackgroundAlpha:twoPassBlur(subjectImage,0,fullMask).pixels[(8*16+8)*4+3],
        zeroSupportForegroundAlpha:twoPassBlur(subjectImage,1,emptyMask).pixels[(8*16+8)*4+3]
      };
      const logFull=render('effects.frag',{uLogStrength:1});
      const logPartial=render('effects.frag',{uLogStrength:.45});
      const logFullTexture=texture(logFull.pixels), logPartialTexture=texture(logPartial.pixels);
      const logGradient=render('effects.frag',{uLogStrength:1},gradient);
      const gradientSamples=Array.from({length:16},(_,x)=>logGradient.pixels[(8*16+x)*4]);
      const logPrimary=render('effects.frag',{uLogStrength:1},primary);
      const logPrimaryPartial=render('effects.frag',{uLogStrength:.45},primary);
      const legacyFull=render('effects.frag',{uLogStrength:1,uLogProfileVersion:1});
      const legacyPartial=render('effects.frag',{uLogStrength:.45,uLogProfileVersion:1});
      const canonicalNative=[1,0,0,0,0,-1,0,0,0,0,1,0,0,1,0,1];
      const cameraCanonical=render('camera.mock.frag',{uTransform:canonicalNative},corners);
      const cameraCanonicalTexture=texture(cameraCanonical.pixels);
      const cameraPipeline={};
      for(const [name,matrix] of Object.entries({
        rotate0:[1,0,0,0,1,0,0,0,1],rotate90:[0,1,0,-1,0,0,1,0,1],
        rotate180:[-1,0,0,0,-1,0,1,1,1],rotate270:[0,-1,0,1,0,0,0,1,1],
        mirror0:[-1,0,0,0,1,0,1,0,1],mirror90:[0,-1,0,-1,0,0,1,1,1],
        mirror180:[1,0,0,0,-1,0,0,1,1],mirror270:[0,1,0,1,0,0,0,0,1]
      })) cameraPipeline[name]=render('copy.frag',{uUvTransformEnabled:1,uUvTransform:matrix},cameraCanonicalTexture);
      const monitorBase={uPreviewMonitorEnabled:1,uMonitorOverlayStrength:1};
      const allMonitors={...monitorBase,uZebraEnabled:1,uFalseColorEnabled:1,uPeakingEnabled:1};
      const monitorPalette={};
      for(const level of [0,.06,.2,.4,.5,.65,.8,.94,1]) {
        monitorPalette[level]=render('copy.frag',{...monitorBase,uFalseColorEnabled:1},solid([level*255,level*255,level*255]));
      }
      const logWhite=render('effects.frag',{uLogStrength:1},solid([255,255,255]));
      const logWhiteTexture=texture(logWhite.pixels);
      const logEdge=render('effects.frag',{uLogStrength:1},edgeTexture), logEdgeTexture=texture(logEdge.pixels);
      const logCorner=render('effects.frag',{uLogStrength:1},corners), logCornerTexture=texture(logCorner.pixels);
      const croppedRotation={uUvTransformEnabled:1,uUvTransform:[0,1,0,-1,0,0,1,0,1],
        uStabilizationEnabled:1,uStabilizationZoom:1.25,uStabilizationOffset:[.05,-.04]};
      const matches=(a,b)=>a.pixels.every((value,index)=>value===b.pixels[index]);
      const smallRoll=5*Math.PI/180;
      const rotationTuning={uStabilizationEnabled:1,uStabilizationZoom:1.25,uStabilizationOffset:[0,0],
        uStabilizationGeometry:[Math.cos(smallRoll),Math.sin(smallRoll),2]};
      const rotatedWide=render('copy.frag',rotationTuning,coordinates);
      const noClampEdgeReplication=rotatedWide.pixels.every((v,i)=>i%4===2||i%4===3||(v>0&&v<255));
      let copyUniformVectors=0;
      for(let i=0;i<gl.getProgramParameter(programs['copy.frag'],gl.ACTIVE_UNIFORMS);i++) {
        const uniform=gl.getActiveUniform(programs['copy.frag'],i);
        copyUniformVectors+=uniform.size*(uniform.type===gl.FLOAT_MAT3?3:uniform.type===gl.FLOAT_MAT4?4:1);
      }
      const monitorEncoderMatches=matches(render('copy.frag',croppedRotation,corners),
        render('copy.frag',{...croppedRotation,...allMonitors,uPreviewMonitorEnabled:0,uMonitorLogStrength:1},corners));
      const monitorEncoderLogMatches=matches(render('copy.frag',croppedRotation,logCornerTexture),
        render('copy.frag',{...croppedRotation,...allMonitors,uPreviewMonitorEnabled:0,uMonitorLogStrength:1},logCornerTexture));
      const monitorZeroOpacityMatches=matches(render('copy.frag',{},logEdgeTexture),
        render('copy.frag',{...allMonitors,uMonitorLogStrength:1,uMonitorOverlayStrength:0},logEdgeTexture));
      return {
        rotationCases:{wide:rotatedWide,
          tall:render('copy.frag',{...rotationTuning,uStabilizationGeometry:[Math.cos(smallRoll),Math.sin(smallRoll),.5]},coordinates),
          reverse:render('copy.frag',{...rotationTuning,uStabilizationGeometry:[Math.cos(smallRoll),-Math.sin(smallRoll),2]},coordinates),
          quarter:render('copy.frag',{uStabilizationEnabled:1,uStabilizationZoom:1,uStabilizationGeometry:[0,1,1]},coordinates),
          shifted:render('copy.frag',{...rotationTuning,uStabilizationOffset:[.01,-.02]},coordinates),
          upright:render('copy.frag',{...rotationTuning,uUvTransformEnabled:1,uUvTransform:[0,1,0,-1,0,0,1,0,1]},coordinates),
          mirrored:render('copy.frag',{...rotationTuning,uUvTransformEnabled:1,uUvTransform:[-1,0,0,0,1,0,1,0,1]},coordinates)},
        noClampEdgeReplication,
        rotationEncoderLutGateMatches:matches(render('copy.frag',rotationTuning,logCornerTexture),
          render('copy.frag',{...rotationTuning,uLutTuning:[0,1,33],...allMonitors,uPreviewMonitorEnabled:0},logCornerTexture,portraitMask,inverseLut33)),
        rotationZeroMatches:matches(render('copy.frag',croppedRotation,corners),
          render('copy.frag',{...croppedRotation,uStabilizationGeometry:[1,0,2]},corners)),
        copyUniformVectors,
        lutCornerIdentity33:render('copy.frag',{uLutTuning:[1,1,33]},corners,portraitMask,identityLut33),
        lutIdentity17:render('copy.frag',{uLutTuning:[1,1,17]},image,portraitMask,identityLut17),
        lutIdentity33:render('copy.frag',{uLutTuning:[1,1,33]},image,portraitMask,identityLut33),
        lutInverse33:render('copy.frag',{uLutTuning:[1,1,33]},image,portraitMask,inverseLut33),
        lutHalfStrength:render('copy.frag',{uLutTuning:[1,.5,33]},image,portraitMask,inverseLut33),
        lutZeroStrength:render('copy.frag',{uLutTuning:[1,0,33]},image,portraitMask,inverseLut33),
        lutBlueSwap17:render('copy.frag',{uLutTuning:[1,1,17]},image,portraitMask,blueSwapLut17),
        lutDisabledEncoderMatches:matches(render('copy.frag',croppedRotation,logCornerTexture),
          render('copy.frag',{...croppedRotation,uLutTuning:[0,1,33]},logCornerTexture,portraitMask,inverseLut33)),
        lutAfterLogAssist:render('copy.frag',{uLogAssist:1,uLutTuning:[1,1,33]},logFullTexture,portraitMask,inverseLut33),
        lutDomain:render('copy.frag',{uLutTuning:[1,1,17],uLutDomainMax:[.5,.5,.5]},image,portraitMask,identityLut17),
        lutZebraAnalyzesOriginal:render('copy.frag',{...monitorBase,uZebraEnabled:1,uLutTuning:[1,1,33]},
          solid([255,255,255]),portraitMask,inverseLut33),
        lutFalseColorOriginalLog:render('copy.frag',{...monitorBase,uFalseColorEnabled:1,uMonitorLogStrength:1,
          uLogAssist:1,uLutTuning:[1,1,33]},logWhiteTexture,portraitMask,inverseLut33),
        monitorPalette,monitorEncoderMatches,monitorEncoderLogMatches,monitorZeroOpacityMatches,
        monitorFlagsOff:render('copy.frag',monitorBase),
        zebraWhite:render('copy.frag',{...monitorBase,uZebraEnabled:1},solid([255,255,255])),
        zebraDark:render('copy.frag',{...monitorBase,uZebraEnabled:1},solid([128,128,128])),
        zebraLowThreshold:render('copy.frag',{...monitorBase,uZebraEnabled:1,uZebraThreshold:.4},solid([128,128,128])),
        zebraLogWhite:render('copy.frag',{...monitorBase,uZebraEnabled:1,uMonitorLogStrength:1},logWhiteTexture),
        zebraLogWhiteAssist:render('copy.frag',{...monitorBase,uZebraEnabled:1,uMonitorLogStrength:1,uLogAssist:1},logWhiteTexture),
        falseColorLogWhite:render('copy.frag',{...monitorBase,uFalseColorEnabled:1,uMonitorLogStrength:1},logWhiteTexture),
        falseColorOpacity:render('copy.frag',{...monitorBase,uFalseColorEnabled:1,uMonitorOverlayStrength:.5},solid([128,128,128])),
        monitorRotated:render('copy.frag',{...monitorBase,...croppedRotation,uFalseColorEnabled:1},corners),
        peakingFlat:render('copy.frag',{...monitorBase,uPeakingEnabled:1}),
        peakingEdge:render('copy.frag',{...monitorBase,uPeakingEnabled:1},edgeTexture),
        peakingLogEdge:render('copy.frag',{...monitorBase,uPeakingEnabled:1,uMonitorLogStrength:1},logEdgeTexture),
        peakingThresholdLow:render('copy.frag',{...monitorBase,uPeakingEnabled:1,uPeakingStrength:0},gradient),
        peakingThresholdHigh:render('copy.frag',{...monitorBase,uPeakingEnabled:1,uPeakingStrength:1},gradient),
        renderer:gl.getParameter(gl.RENDERER),cameraCanonical,cameraPipeline,
        copy:render('copy.frag'), neutral:render('effects.frag'),
        gain:render('effects.frag',{uGain:1}), temperature:render('effects.frag',{uTemperature:1}),
        flat:render('effects.frag',{uFlat:1}), gray:render('effects.frag',{uSaturation:0}),
        independent:render('effects.frag',{uGain:1,uSaturation:0}),
        selective:render('effects.frag',{uBlurEnabled:1}),
        trail:render('effects.frag',{uTrail:.5}), gaussian:render('blur.frag',{uStep:[1/16,0]}),weightedBlurContracts,
        checker:render('copy.frag',{},checker),
        checkerBlur:render('blur.frag',{uStep:[1/16,0]},checker),
        checkerSharp:render('effects.frag',{uSharpness:1},checker),
        portrait:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1}),
        portraitBackground:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1,uFocusBackground:1}),
        portraitAmount:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:.4}),
        portraitInactive:render('effects.frag',{uPortraitEnabled:0,uPortraitStrength:1}),
        portraitEdgeSharp:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1,uPortraitEdgeSoftness:0},image,gradient),
        portraitEdgeSoft:render('effects.frag',{uPortraitEnabled:1,uPortraitStrength:1,uPortraitEdgeSoftness:1},image,gradient),
        stabilizationIdentity:render('copy.frag',{uStabilizationEnabled:1,uStabilizationZoom:1},gradient),
        stabilizationCrop:render('copy.frag',{uStabilizationEnabled:1,uStabilizationZoom:1.25,uStabilizationOffset:[.05,-.04]},gradient),
        stabilizationRotate:render('copy.frag',{uStabilizationEnabled:1,uStabilizationZoom:1.25,uStabilizationOffset:[.05,-.04],
          uUvTransformEnabled:1,uUvTransform:[0,1,0,-1,0,0,1,0,1]},gradient),
        logFull,logPartial,gradientSamples,
        logFullAssist:render('copy.frag',{uLogAssist:1},logFullTexture),
        logPartialAssist:render('copy.frag',{uLogAssist:.45},logPartialTexture),
        logEncoderCopy:render('copy.frag',{},logFullTexture),
        logFlat:render('effects.frag',{uFlat:1,uLogStrength:1}),
        logTrail:render('effects.frag',{uLogStrength:1,uTrail:.5}),
        logPrimary,logPrimaryPartial,
        logPrimaryAssist:render('copy.frag',{uLogAssist:1},texture(logPrimary.pixels)),
        logPrimaryPartialAssist:render('copy.frag',{uLogAssist:.45},texture(logPrimaryPartial.pixels)),
        logPrimaryLegacy:render('effects.frag',{uLogStrength:1,uLogProfileVersion:1},primary),
        legacyFull,legacyPartial,
        legacyAssist:render('copy.frag',{uLogAssist:1,uLogProfileVersion:1},texture(legacyFull.pixels)),
        legacyPartialAssist:render('copy.frag',{uLogAssist:.45,uLogProfileVersion:1},texture(legacyPartial.pixels)),
        rotate90:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[0,1,0,-1,0,0,1,0,1]},corners),
        rotate270:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[0,-1,0,1,0,0,0,1,1]},corners),
        mirror:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[-1,0,0,0,1,0,1,0,1]},corners),
        transformDisabled:render('copy.frag',{uUvTransformEnabled:0,uUvTransform:[0,1,0,-1,0,0,1,0,1]},corners),
        rotate0:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[1,0,0,0,1,0,0,0,1]},corners),
        rotate180:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[-1,0,0,0,-1,0,1,1,1]},corners),
        mirror90:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[0,-1,0,-1,0,0,1,1,1]},corners),
        mirror180:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[1,0,0,0,-1,0,0,1,1]},corners),
        mirror270:render('copy.frag',{uUvTransformEnabled:1,uUvTransform:[0,1,0,1,0,0,0,0,1]},corners)
      };
    }, {sources});
    const near = (actual, expected, tolerance=2) => actual.every((v,i)=>Math.abs(v-expected[i])<=tolerance);
    const lutContracts=[];
    const lutCheck=(name,condition)=>{assert(condition,name);lutContracts.push(name);};
    lutCheck('Preview LUT stays inside GLES 2 minimum fragment uniform budget',result.copyUniformVectors<=16);
    lutCheck('17-point identity preserves interpolated RGB',near(result.lutIdentity17.center,[64,96,128]));
    lutCheck('33-point identity preserves interpolated RGB',near(result.lutIdentity33.center,[64,96,128]));
    lutCheck('33-point tile borders preserve cube RGB extremes',near(result.lutCornerIdentity33.corner,[255,0,0])&&
      near(result.lutCornerIdentity33.topRight,[255,255,0])&&near(result.lutCornerIdentity33.topLeft,[0,0,255]));
    lutCheck('33-point creative LUT visibly changes color',near(result.lutInverse33.center,[191,159,127]));
    lutCheck('LUT strength mixes half intensity',near(result.lutHalfStrength.center,[128,128,128]));
    lutCheck('Zero LUT strength preserves the source',near(result.lutZeroStrength.center,[64,96,128]));
    lutCheck('Red-fastest atlas with separate blue slices swaps R/B correctly',near(result.lutBlueSwap17.center,[128,96,64]));
    lutCheck('Disabled LUT does not alter encoded LOG MP4 copy',result.lutDisabledEncoderMatches);
    lutCheck('Preview LUT follows LOG display assistance',near(result.lutAfterLogAssist.center,[191,159,127]));
    lutCheck('LUT input domain scales before interpolation',near(result.lutDomain.center,[128,192,255]));
    lutCheck('Zebra evaluates recorded exposure rather than LUT output',near(result.lutZebraAnalyzesOriginal.center,[20,20,20]));
    lutCheck('LOG false color remains based on original exposure with LUT and assist enabled',near(result.lutFalseColorOriginalLog.center,[255,31,31]));
    const stabilizationContracts=[];
    const stabilizationCheck=(name,condition)=>{assert(condition,name);stabilizationContracts.push(name);};
    const expectedCoordinates=(x,y,angle,aspect,offset=[0,0],orientation='none')=>{
      let u=(x+.5)/16,v=(y+.5)/16;
      if(orientation==='upright') [u,v]=[1-v,u];
      if(orientation==='mirrored') u=1-u;
      const c=Math.cos(angle),s=Math.sin(angle);
      const source=[.5+(c*(u-.5)-s*(v-.5)/aspect)/1.25+offset[0],
        .5+(s*(u-.5)*aspect+c*(v-.5))/1.25+offset[1]];
      return [...source.map(value=>Math.round(Math.max(0,Math.min(1,(value*16-.5)/15))*255)),0];
    };
    const angle=5*Math.PI/180;
    for(const [name,a,aspect,offset,orientation] of [['wide',angle,2,[0,0],'none'],['tall',angle,.5,[0,0],'none'],
      ['reverse',-angle,2,[0,0],'none'],['shifted',angle,2,[.01,-.02],'none'],
      ['upright',angle,2,[0,0],'upright'],['mirrored',angle,2,[0,0],'mirrored']]) {
      const sample=result.rotationCases[name];
      stabilizationCheck('Physical roll sampler '+name,
        near(sample.corner,expectedCoordinates(0,0,a,aspect,offset,orientation))&&
        near(sample.topRight,expectedCoordinates(15,15,a,aspect,offset,orientation))&&
        near(sample.center,expectedCoordinates(8,8,a,aspect,offset,orientation)));
    }
    stabilizationCheck('Positive quarter-turn has correct source-coordinate sign',
      near(result.rotationCases.quarter.corner,[255,0,0])&&near(result.rotationCases.quarter.topRight,[0,255,0]));
    stabilizationCheck('Reserved crop absorbs small roll without replicated texture borders',result.noClampEdgeReplication);
    stabilizationCheck('Zero roll remains byte-identical to translation-only sampling',result.rotationZeroMatches);
    stabilizationCheck('Rotated MP4 copy excludes LUT and every display monitor',result.rotationEncoderLutGateMatches);
    const monitorContracts=[];
    const monitorCheck=(name,condition)=>{assert(condition,name);monitorContracts.push(name);};
    const lime=[199,243,107],zebraYellow=[255,245,102],zebraDark=[20,20,20];
    monitorCheck('All monitors disabled by preview gate preserve every encoded SDR pixel',result.monitorEncoderMatches);
    monitorCheck('All monitors disabled by preview gate preserve every encoded LOG pixel',result.monitorEncoderLogMatches);
    monitorCheck('Zero opacity preserves every encoded LOG pixel',result.monitorZeroOpacityMatches);
    monitorCheck('Independent monitor switches preserve unmodified preview',near(result.monitorFlagsOff.center,[64,96,128],0));
    monitorCheck('Zebra marks white with alternating screen-space stripes',near(result.zebraWhite.center,zebraDark)&&near(result.zebraWhite.topRight,zebraYellow));
    monitorCheck('Zebra leaves below-threshold pixels unmodified',near(result.zebraDark.center,[128,128,128],0));
    monitorCheck('Zebra uses adjustable threshold',near(result.zebraLowThreshold.center,zebraDark));
    monitorCheck('Zebra recovers LOG exposure with display assistance off',near(result.zebraLogWhite.center,zebraDark));
    monitorCheck('Zebra exposure agrees with display assistance on',near(result.zebraLogWhiteAssist.center,result.zebraLogWhite.center,0));
    monitorCheck('False color recovers LOG exposure with display assistance off',near(result.falseColorLogWhite.center,[255,31,31]));
    monitorCheck('False color overlay opacity mixes with preview',near(result.falseColorOpacity.center,[192,109,134]));
    monitorCheck('False color sampling follows rotation and stabilization crop',near(result.monitorRotated.corner,[77,191,64])&&near(result.monitorRotated.topLeft,[0,166,191]));
    monitorCheck('Peaking preserves flat backgrounds',near(result.peakingFlat.center,[64,96,128],0)&&near(result.peakingFlat.corner,[64,96,128],0));
    monitorCheck('Peaking highlights edges without tinting flat adjacent regions',near(result.peakingEdge.center,lime)&&near(result.peakingEdge.leftEdge,lime)&&near(result.peakingEdge.flatLeft,[40,40,40],0));
    monitorCheck('Peaking detects encoded LOG edges without display assistance',near(result.peakingLogEdge.center,lime));
    monitorCheck('Peaking sensitivity changes the contour threshold',!near(result.peakingThresholdLow.center,lime,0)&&near(result.peakingThresholdHigh.center,lime));
    for(const [level,color] of Object.entries({0:[64,0,115],'.06':[26,64,230],'.2':[0,166,191],'.4':[77,89,102],
      '.5':[255,89,140],'.65':[77,191,64],'.8':[255,217,38],'.94':[255,107,13],1:[255,31,31]})) {
      monitorCheck('False color SDR palette '+level,near(result.monitorPalette[String(Number(level))].center,color));
    }
    assert(near(result.copy.center,[64,96,128]));
    assert(near(result.neutral.center,[64,96,128]));
    assert(near(result.gain.center,[128,192,255]));
    assert(result.temperature.center[0]>64 && result.temperature.center[2]<128);
    assert(result.flat.center[0]>64 && result.flat.center[2]<=128);
    assert(Math.max(...result.gray.center)-Math.min(...result.gray.center)<=2);
    assert(result.independent.center[0]>result.gray.center[0]);
    assert(near(result.selective.center,[64,96,128]) && near(result.selective.corner,[200,210,220]));
    assert(near(result.trail.center,[32,48,64]));
    assert(near(result.gaussian.center,[64,96,128]));
    assert(Math.abs(result.checkerBlur.center[0]-128)<Math.abs(result.checker.center[0]-128));
    assert(Math.abs(result.checkerSharp.center[0]-128)>Math.abs(result.checker.center[0]-128));
    assert(near(result.portrait.center,[64,96,128]) && near(result.portrait.corner,[200,210,220]));
    assert(near(result.portraitBackground.center,[200,210,220]) && near(result.portraitBackground.corner,[64,96,128]));
    assert(near(result.portraitAmount.corner,[118,142,165]));
    assert(near(result.portraitInactive.corner,[64,96,128]));
    assert(result.portraitEdgeSoft.center[0]>result.portraitEdgeSharp.center[0]);
    const weighted=result.weightedBlurContracts;
    const blurContracts=[
      'background taps reject foreground color','inverse focus taps reject background color',
      'portrait preserves sharp subject','cinematic inverse preserves background',
      'missing blur support preserves original','disabled portrait preserves original',
      'zero strength preserves original','unsupported kernels remain finite and transparent',
      'portrait sampler ignores oval blur','oval sampler ignores portrait blur','simultaneous samplers compose independently'
    ];
    assert(near(weighted.background.flatLeft,[10,30,230],4));
    assert(weighted.ordinary.flatLeft[0]>weighted.background.flatLeft[0]+30);
    assert(near(weighted.foreground.center,[240,30,20],3)&&near(weighted.foregroundBoundary,[240,30,20],4));
    assert(near(weighted.composite.center,[240,30,20],2)&&near(weighted.composite.flatLeft,[10,30,230],4));
    assert(near(weighted.inverse.center,[240,30,20],3)&&near(weighted.inverse.corner,[10,30,230],2));
    assert(near(weighted.unsupported.center,[240,30,20],2)&&near(weighted.unsupported.corner,[10,30,230],2));
    assert(near(weighted.disabled.center,[240,30,20],2)&&near(weighted.disabled.corner,[10,30,230],2));
    assert(near(weighted.zeroStrength.center,[240,30,20],2)&&near(weighted.zeroStrength.corner,[10,30,230],2));
    assert.equal(weighted.zeroSupportBackgroundAlpha,0);assert.equal(weighted.zeroSupportForegroundAlpha,0);
    assert(near(weighted.portraitSamplerIndependent.corner,[10,30,230],3));
    assert(near(weighted.ovalSamplerIndependent.corner,[20,210,40],2));
    assert(near(weighted.bothSamplers.corner,[17,147,107],3));
    assert(near(result.stabilizationIdentity.corner,[0,0,0])&&near(result.stabilizationIdentity.topRight,[255,255,255]));
    assert(near(result.stabilizationCrop.corner,[39,39,39])&&near(result.stabilizationCrop.topRight,[243,243,243])&&
      near(result.stabilizationCrop.center,[148,148,148]));
    assert(near(result.stabilizationRotate.corner,[243,243,243])&&near(result.stabilizationRotate.topLeft,[39,39,39]));
    assert(near(result.logFull.center,encodeRgb([64,96,128].map(v=>v/255)).map(v=>Math.round(v*255))));
    assert(near(result.logPartial.center,encodeRgb([64,96,128].map(v=>v/255),.45).map(v=>Math.round(v*255))));
    assert(result.gradientSamples.every((v,i)=>i===0||v>result.gradientSamples[i-1]) && Math.abs(result.gradientSamples[0]-24)<=1 && Math.abs(result.gradientSamples.at(-1)-239)<=1);
    assert(near(result.logFullAssist.center,[64,96,128]));
    assert(near(result.logPartialAssist.center,[64,96,128]));
    assert(near(result.logEncoderCopy.center,result.logFull.center,0));
    assert(near(result.logFlat.center,encodeRgb([64,96,128].map(v=>.12+.76*v/255)).map(v=>Math.round(v*255))));
    assert(near(result.logTrail.center,result.logFull.center.map(v=>v*.5)));
    assert(near(result.logPrimary.center,encodeRgb([224,32,64].map(v=>v/255)).map(v=>Math.round(v*255))));
    assert(near(result.logPrimaryPartial.center,encodeRgb([224,32,64].map(v=>v/255),.45).map(v=>Math.round(v*255))));
    const chroma = rgb => Math.max(...rgb)-Math.min(...rgb);
    assert(chroma(result.logPrimary.center)<=chroma(result.logPrimaryLegacy.center)*.62);
    assert(near(result.logPrimaryAssist.center,[224,32,64]));
    assert(near(result.logPrimaryPartialAssist.center,[224,32,64]));
    assert(near(result.legacyFull.center,[64,96,128].map(v=>Math.round(encodeLog(v/255)*255))));
    assert(near(result.legacyPartial.center,[64,96,128].map(v=>Math.round(encodeLog(v/255,.45)*255))));
    assert(near(result.legacyAssist.center,[64,96,128])&&near(result.legacyPartialAssist.center,[64,96,128]));
    assert(near(result.rotate90.corner,[0,255,0])&&near(result.rotate90.bottomRight,[255,255,0])&&near(result.rotate90.topLeft,[255,0,0])&&near(result.rotate90.topRight,[0,0,255]));
    assert(near(result.rotate270.corner,[0,0,255])&&near(result.rotate270.bottomRight,[255,0,0])&&near(result.rotate270.topLeft,[255,255,0])&&near(result.rotate270.topRight,[0,255,0]));
    assert(near(result.mirror.corner,[0,255,0])&&near(result.mirror.bottomRight,[255,0,0])&&near(result.mirror.topLeft,[255,255,0])&&near(result.mirror.topRight,[0,0,255]));
    assert(near(result.transformDisabled.corner,[255,0,0])&&near(result.transformDisabled.topRight,[255,255,0]));
    const red=[255,0,0],green=[0,255,0],blue=[0,0,255],yellow=[255,255,0];
    for(const [name,expected] of Object.entries({rotate0:[red,green,blue,yellow],rotate180:[yellow,blue,green,red],
      mirror90:[yellow,green,blue,red],mirror180:[blue,yellow,red,green],mirror270:[red,blue,green,yellow]})) {
      assert(['corner','bottomRight','topLeft','topRight'].every((key,i)=>near(result[name][key],expected[i])));
    }
    assert(near(result.cameraCanonical.corner,blue)&&near(result.cameraCanonical.bottomRight,yellow)&&
      near(result.cameraCanonical.topLeft,red)&&near(result.cameraCanonical.topRight,green));
    const cameraExpected={rotate0:[blue,yellow,red,green],rotate90:[yellow,green,blue,red],
      rotate180:[green,red,yellow,blue],rotate270:[red,blue,green,yellow],
      mirror0:[yellow,blue,green,red],mirror90:[green,yellow,red,blue],
      mirror180:[red,green,blue,yellow],mirror270:[blue,red,yellow,green]};
    for(const [name,expected] of Object.entries(cameraExpected)) {
      assert(['corner','bottomRight','topLeft','topRight'].every((key,i)=>near(result.cameraPipeline[name][key],expected[i])));
    }
    const lutResults=[];
    function parseCube(text) {
      return text.split(/\r?\n/).filter(line=>/^[-+0-9]/.test(line)).map(line=>line.trim().split(/\s+/).map(Number));
    }
    function sampleCube(rows,size,input) {
      const coordinates=input.map(value=>Math.max(0,Math.min(1,value))*(size-1));
      const lows=coordinates.map(value=>Math.min(size-2,Math.floor(value)));
      const fractions=coordinates.map((value,i)=>value-lows[i]);
      const output=[0,0,0];
      for(let b=0;b<2;b++) for(let g=0;g<2;g++) for(let r=0;r<2;r++) {
        const weight=(r?fractions[0]:1-fractions[0])*(g?fractions[1]:1-fractions[1])*(b?fractions[2]:1-fractions[2]);
        const row=rows[((lows[2]+b)*size+lows[1]+g)*size+lows[0]+r];
        for(let c=0;c<3;c++) output[c]+=row[c]*weight;
      }
      return output.map(value=>Math.max(0,Math.min(1,value)));
    }
    const legacyAsset=fs.readFileSync(path.join(root,'app/src/main/assets/luts/LumaLog-v1-to-SDR-33.cube'),'utf8');
    assert.equal(legacyAsset,cube(33,1,1),'Legacy LUT must stay byte-compatible');
    for(const [size,strength] of [[33,1],[65,.45],[65,.75]]) {
      const text=strength===1 ? fs.readFileSync(path.join(root,'app/src/main/assets/luts/LumaLog-v2-to-SDR-33.cube'),'utf8') : cube(size,strength,2);
      const rows=parseCube(text); assert.equal(rows.length,size**3);
      let maxError=0;
      for(let b=0;b<=8;b++) for(let g=0;g<=8;g++) for(let r=0;r<=8;r++) {
        const source=[r,g,b].map(value=>Math.round(value/8*255)/255);
        const encoded=encodeRgb(source,strength,2).map(value=>Math.round(value*255)/255);
        const recovered=sampleCube(rows,size,encoded);
        maxError=Math.max(maxError,...recovered.map((value,i)=>Math.abs(value-source[i])));
      }
      assert(maxError<.014,`Quantized color LUT error ${maxError}`);
      lutResults.push({size,strength,quantizedColorPatches:729,maxError});
    }
    const report={passed:58+monitorContracts.length+lutContracts.length+stabilizationContracts.length+blurContracts.length,monitorContracts,lutContracts,stabilizationContracts,blurContracts,
      source:'Actual GLSL assets; synthetic pixel and inverse-LUT color contracts in headless WebGL/ANGLE',
      androidEglVerified:false,androidExternalOesVerified:false,cameraShaderTextureTargetSubstituted:true,lutResults,results:result};
    fs.mkdirSync(projectPaths.reportDirectory,{recursive:true});
    fs.writeFileSync(path.join(projectPaths.reportDirectory,'shader-verification.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify(report));
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
