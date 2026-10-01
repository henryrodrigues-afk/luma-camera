// Synthetic color comparison. Uses the exact profile formula and the distributed LUT.
// This is not a photograph, a phone capture, or an Android camera verification.
const fs = require('node:fs');
const path = require('node:path');
const {encodeRgb} = require('./GenerateLogLut.cjs');
const {projectPaths} = require('../lib/ProjectPaths.cjs');
const root = projectPaths.projectRoot;
let playwright;
try { playwright = require('playwright'); }
catch { playwright = require(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')); }
const size=33;
const lut=fs.readFileSync(path.join(root,'app/src/main/assets/luts/LumaLog-v2-to-SDR-33.cube'),'utf8')
  .split(/\r?\n/).filter(line=>/^[-+0-9]/.test(line)).map(line=>line.trim().split(/\s+/).map(Number));
if(lut.length!==size**3) throw new Error('Unexpected LUT dimensions');
function restore(input) {
  const coordinates=input.map(value=>value*(size-1));
  const lows=coordinates.map(value=>Math.min(size-2,Math.floor(value)));
  const fractions=coordinates.map((value,i)=>value-lows[i]);
  const output=[0,0,0];
  for(let b=0;b<2;b++) for(let g=0;g<2;g++) for(let r=0;r<2;r++) {
    const weight=(r?fractions[0]:1-fractions[0])*(g?fractions[1]:1-fractions[1])*(b?fractions[2]:1-fractions[2]);
    const row=lut[((lows[2]+b)*size+lows[1]+g)*size+lows[0]+r];
    for(let c=0;c<3;c++) output[c]+=row[c]*weight;
  }
  return output.map(value=>Math.max(0,Math.min(1,value)));
}
const quantize=rgb=>rgb.map(value=>Math.round(value*255)/255);
function variants(rgb) {
  const source=rgb.map(value=>value/255), encoded=quantize(encodeRgb(source,1,2));
  return [source,encoded,quantize(restore(encoded))].map(values=>values.map(value=>Math.round(value*255)));
}
const patches=[
  ['Vermelho',[224,32,64]],['Verde',[34,176,86]],['Azul',[40,88,220]],
  ['Amarelo',[234,194,38]],['Ciano',[38,190,205]],['Magenta',[182,48,176]],
  ['Pele clara',[222,173,144]],['Pele média',[169,116,85]],['Pele escura',[91,60,46]],
  ['Preto',[0,0,0]],['Cinza',[128,128,128]],['Branco',[255,255,255]]
].map(([label,rgb])=>({label,colors:variants(rgb)}));
const gradient=Array.from({length:256},(_,value)=>variants([value,value,value]));
(async()=>{
  const browser=await playwright.chromium.launch({executablePath:process.env.LUMA_CHROME||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  try {
    const page=await browser.newPage({viewport:{width:1500,height:1040},deviceScaleFactor:1});
    await page.setContent('<html><body style="margin:0"><canvas id="comparison" width="1500" height="1040"></canvas></body></html>');
    await page.evaluate(({patches,gradient})=>{
      const ctx=document.querySelector('canvas').getContext('2d');
      const text=(value,x,y,size=18,color='#cbd5e1',weight=400)=>{
        ctx.font=`${weight} ${size}px Arial`;ctx.fillStyle=color;ctx.fillText(value,x,y);
      };
      const rounded=(x,y,w,h,r,fill)=>{ctx.fillStyle=fill;ctx.beginPath();ctx.roundRect(x,y,w,h,r);ctx.fill();};
      ctx.fillStyle='#0b1019';ctx.fillRect(0,0,1500,1040);
      text('LumaLog v2',48,66,36,'#f1f5f9',700);
      text('Uma imagem flat para editar; uma LUT para restaurar contraste e cores.',48,105,21,'#a9b8cf');
      text('Comparação técnica · sinal SDR simulado · força de 100%',48,139,16,'#7f95b2');
      const titles=['SDR de referência','LumaLog v2 · 100%','Após LUT v2'];
      const subtitles=['Cores de entrada','Curva Log + crominância a 60%','LUT 3D de 33 pontos'];
      const positions=[48,524,1000];
      const color=rgb=>`rgb(${rgb.join(',')})`;
      for(let panel=0;panel<3;panel++) {
        const x=positions[panel], width=452;
        rounded(x,166,width,747,18,'#141d2b');
        rounded(x+24,192,30,30,9,panel===1?'#bda8ff':'#55d4bf');
        text(String(panel+1),x+34,214,17,'#0c1420',700);
        text(titles[panel],x+66,215,23,'#edf3fa',700);
        text(subtitles[panel],x+24,250,16,'#8fa5be');
        for(let i=0;i<patches.length;i++) {
          const col=i%3,row=Math.floor(i/3), px=x+24+col*138,py=283+row*120;
          rounded(px,py,128,83,8,color(patches[i].colors[panel]));
          text(patches[i].label,px,py+105,15,'#b7c8dc');
        }
        text('Gradiente de cinza',x+24,796,16,'#b7c8dc');
        for(let i=0;i<404;i++) {
          const value=Math.round(i/403*255);
          ctx.fillStyle=color(gradient[value][panel]);ctx.fillRect(x+24+i,810,1,54);
        }
        text('0',x+24,887,13,'#7f95b2');text('255',x+399,887,13,'#7f95b2');
      }
      text('Cores sintéticas · não é captura do A14',48,959,22,'#e4ecf7',700);
      text('Fórmula LumaLog v2 e LUT distribuída pelo projeto. Codificação e restauração quantizadas a 8 bits.',48,994,16,'#93a8c1');
      text('A comparação demonstra a transformação de cor; não mede ruído, compressão H.264 ou faixa dinâmica do sensor.',48,1020,14,'#6e849f');
    },{patches,gradient});
    const directory=projectPaths.previewDirectory;fs.mkdirSync(directory,{recursive:true});
    const output=path.join(directory,'log-v2-comparacao.png');
    await page.locator('canvas').screenshot({path:output});
    console.log(JSON.stringify({output,width:1500,height:1040,source:'Synthetic CPU LumaLog v2 formula + distributed 33-point LUT',a14Capture:false}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
