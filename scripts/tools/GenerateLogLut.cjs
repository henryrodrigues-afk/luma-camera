// Generate matching inverse LUTs; retain the v1 file for earlier recordings.
const fs = require('node:fs');
const path = require('node:path');
const {projectPaths} = require('../lib/ProjectPaths.cjs');
const BLACK = .09375, WHITE = .9375, K = 63;
const CHROMA_COMPRESSION = .4, CURRENT_VERSION = 2;
const weights = [.2126,.7152,.0722];
const encode = (value, strength=1) => {
  const linear = value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4;
  return value * (1-strength) + (BLACK + (WHITE-BLACK)*Math.log1p(K*linear)/Math.log1p(K))*strength;
};
const decode = (value, strength=1) => {
  if (!strength) return value;
  if (strength===1) {
    const normalized = Math.max(0,Math.min(1,(value-BLACK)/(WHITE-BLACK)));
    const linear = ((1+K)**normalized-1)/K;
    return linear <= .0031308 ? 12.92*linear : 1.055*linear**(1/2.4)-.055;
  }
  let low=0, high=1;
  for(let i=0;i<28;i++) {
    const middle=(low+high)/2;
    if(encode(middle,strength)<value) low=middle; else high=middle;
  }
  return (low+high)/2;
};
const encodeRgb = (rgb,strength=1,version=CURRENT_VERSION) => {
  const encoded = rgb.map(value=>encode(Math.max(0,Math.min(1,value)),strength));
  if(version===1) return encoded;
  const luminance=encoded.reduce((sum,value,i)=>sum+value*weights[i],0);
  return encoded.map(value=>luminance+(value-luminance)*(1-CHROMA_COMPRESSION*strength));
};
const decodeExtended = (value,strength=1) => {
  if(!strength) return value;
  const floor=BLACK*strength, ceiling=1-strength+WHITE*strength;
  if(value<=floor) return (value-floor)/(1-strength+strength*(WHITE-BLACK)*K/(12.92*Math.log1p(K)));
  if(value>=ceiling) return 1+(value-ceiling)/(1-strength+strength*(WHITE-BLACK)*K*2.4/((1+K)*1.055*Math.log1p(K)));
  return decode(value,strength);
};
const decodeRgb = (rgb,strength=1,version=CURRENT_VERSION,clamp=true) => {
  const luminance=rgb.reduce((sum,value,i)=>sum+value*weights[i],0);
  const retention=version===1 ? 1 : 1-CHROMA_COMPRESSION*strength;
  return rgb.map(value=>{
    const result=version===1 ? decode(value,strength) : decodeExtended(luminance+(value-luminance)/retention,strength);
    return clamp ? Math.max(0,Math.min(1,result)) : result;
  });
};
function cube(size=33, strength=1, version=CURRENT_VERSION) {
  if(!Number.isInteger(size)||size<2||size>65||!Number.isFinite(strength)||strength<0||strength>1) throw new Error('Invalid LUT size/strength');
  if(version!==1&&version!==2) throw new Error('Unknown Log profile version');
  if(version===2) {
    const count=16385, minimum=-.7, maximum=1.7;
    const inverseTable=Array.from({length:count},(_,i)=>decodeExtended(minimum+(maximum-minimum)*i/(count-1),strength));
    const cached=value=>{
      const coordinate=Math.max(0,Math.min(count-1,(value-minimum)/(maximum-minimum)*(count-1)));
      const low=Math.min(count-2,Math.floor(coordinate)), fraction=coordinate-low;
      return inverseTable[low]*(1-fraction)+inverseTable[low+1]*fraction;
    };
    const retention=1-CHROMA_COMPRESSION*strength;
    const lines=[
      '# LumaLog v2 chroma-compressed simulated SDR to source SDR',
      '# Use matching version/strength; clamp output AFTER LUT; no gamut conversion',
      `TITLE "LumaLog v2 to SDR ${Math.round(strength*100)}%"`,
      `LUT_3D_SIZE ${size}`,'DOMAIN_MIN 0.0 0.0 0.0','DOMAIN_MAX 1.0 1.0 1.0'
    ];
    for(let b=0;b<size;b++) for(let g=0;g<size;g++) for(let r=0;r<size;r++) {
      const channels=[r/(size-1),g/(size-1),b/(size-1)];
      const luminance=channels.reduce((sum,value,i)=>sum+value*weights[i],0);
      lines.push(channels.map(value=>cached(luminance+(value-luminance)/retention).toFixed(7)).join(' '));
    }
    return lines.join('\n')+'\n';
  }
  const rows = Array.from({length:size},(_,i)=>decode(i/(size-1),strength).toFixed(7));
  const lines = [
    '# LumaLog v1 simulated SDR to source SDR; matching strength required',
    '# Assumed sRGB-like input transfer; no gamut conversion or added dynamic range',
    `TITLE "LumaLog v1 to SDR ${Math.round(strength*100)}%"`,
    `LUT_3D_SIZE ${size}`,'DOMAIN_MIN 0.0 0.0 0.0','DOMAIN_MAX 1.0 1.0 1.0'
  ];
  for(let b=0;b<size;b++) for(let g=0;g<size;g++) for(let r=0;r<size;r++) lines.push(`${rows[r]} ${rows[g]} ${rows[b]}`);
  return lines.join('\n')+'\n';
}
if(require.main===module) {
  // Export editor resources; regeneration must never replace the bundled assets.
  const directory=projectPaths.lutDirectory;
  fs.mkdirSync(directory,{recursive:true});
  const legacyFile=path.join(directory,'LumaLog-v1-to-SDR-33.cube');
  const legacyCube=cube(33,1,1);
  if(fs.existsSync(legacyFile)) {
    if(fs.readFileSync(legacyFile,'utf8')!==legacyCube) throw new Error('Legacy v1 LUT differs; refusing to replace compatibility resource');
  } else fs.writeFileSync(legacyFile,legacyCube);
  const file=path.join(directory,'LumaLog-v2-to-SDR-33.cube');
  fs.writeFileSync(file,cube());
  console.log(JSON.stringify({file,legacyFile,legacyPreserved:true,grid:33,entries:33**3,strength:1,black:BLACK,white:WHITE,chromaRetention:1-CHROMA_COMPRESSION}));
}
module.exports={encode,decode,encodeRgb,decodeRgb,cube,BLACK,WHITE,CHROMA_COMPRESSION,CURRENT_VERSION};
