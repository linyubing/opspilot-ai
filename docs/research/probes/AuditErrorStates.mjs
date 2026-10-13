// 事后失败归因：只复算冻结模型，不调参，也不把诊断标签用于训练。
import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
const file=p=>path.join(root,'docs/research',p);const raw=p=>fs.readFileSync(file(p),'utf8');
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(file(p))).digest('hex');
const input='2026-10-11-prior-compare-input.json',archive='2026-10-11-fit-reference-result.json';
assert.equal(sha(input),'0e72f54e73b78efb0c12c3a5c474976afadb4c163aba732f9c39e9b6b124d936');assert.equal(sha(archive),'3df38e79da391c6c3cfc6f1676a0e9a641df5077394fd71915c999965edeb214');
const samples=JSON.parse(raw(input)).samples,report=JSON.parse(raw(archive));
const names=report.featureNames,labels=['BULLISH','NEUTRAL','BEARISH'],keys=['bullish','neutral','bearish'];
assert.equal(samples.length,1268);assert.equal(names.length,36);assert.ok(samples.every(s=>s.targetDate<'2024-11-11'));
const bin=p=>p<.5?'P_LT_50':p<.6?'P_50_60':p<.8?'P_60_80':'P_GE_80';
function stats(rows){
 const n=rows.length;if(!n)return {samples:0};
 const confusion=Object.fromEntries(labels.map(a=>[a,Object.fromEntries(labels.map(b=>[b,0]))]));
 let hits=0,priorHits=0,confidence=0,brier=0,priorBrier=0,loss=0,priorLoss=0;
 for(const r of rows){hits+=+r.hit;priorHits+=+r.priorHit;confidence+=r.confidence;confusion[r.actual][r.predicted]++;
  for(let j=0;j<3;j++){const actual=+(labels[j]===r.actual);brier+=(r.model[keys[j]]-actual)**2;priorBrier+=(r.prior[keys[j]]-actual)**2;}
  loss-=Math.log(Math.max(1e-15,r.model[keys[labels.indexOf(r.actual)]]));priorLoss-=Math.log(Math.max(1e-15,r.prior[keys[labels.indexOf(r.actual)]]));
 }
 return {samples:n,hits,accuracy:hits/n,priorHits,priorAccuracy:priorHits/n,meanConfidence:confidence/n,confidenceGap:confidence/n-hits/n,brierScore:brier/n,priorBrierScore:priorBrier/n,logLoss:loss/n,priorLogLoss:priorLoss/n,confusion};
}
const groups=rows=>({all:stats(rows),existingSignals:stats(rows.filter(r=>r.signalled)),confidenceBins:Object.fromEntries(['P_LT_50','P_50_60','P_60_80','P_GE_80'].map(b=>[b,stats(rows.filter(r=>r.confidenceBin===b))])),inputState:{normal:stats(rows.filter(r=>!r.extreme)),extreme:stats(rows.filter(r=>r.extreme))},byPrediction:Object.fromEntries(labels.map(l=>[l,stats(rows.filter(r=>r.predicted===l))])),constantFeatureChanged:stats(rows.filter(r=>r.constantChanged.length>0))});
const folds=[];let diagnosticRows=[];
for(let k=0;k<3;k++){
 const archived=report.folds[k],start=548+240*k;
 const outer=samples.slice(0,start).filter(s=>s.targetDate<samples[start].asOfDate),diagnostic=outer.slice(-120),fit=outer.slice(0,-120).filter(s=>s.targetDate<diagnostic[0].asOfDate);
 assert.equal(fit.length,426+240*k);assert.equal(archived.fitCount,fit.length);assert.ok(fit.at(-1).targetDate<diagnostic[0].asOfDate);
 const mean=names.map(()=>0),variance=names.map(()=>0);
 fit.forEach((s,i)=>names.forEach((name,j)=>{const x=s.features.values[name],d=x-mean[j];mean[j]+=d/(i+1);variance[j]+=d*(x-mean[j]);}));
 const scale=variance.map(v=>Math.sqrt(Math.max(0,v/fit.length))),width=names.length+1,w=archived.solver.weights;
 const frequencies=labels.map(l=>fit.filter(s=>s.label===l).length/fit.length);assert.equal(w.length,width*2);
 function convert(xs,saved){assert.equal(xs.length,saved.length);return xs.map((s,i)=>{
  const a=saved[i];assert.equal(s.asOfDate,a.date);assert.equal(s.targetDate,a.target);assert.equal(s.label,a.actual);
  const z=names.map((name,j)=>scale[j]===0?0:(s.features.values[name]-mean[j])/scale[j]);
  const logits=[w[width-1],w[2*width-1],0];for(let c=0;c<2;c++)for(let j=0;j<names.length;j++)logits[c]+=w[c*width+j]*z[j];
  const max=Math.max(...logits),exp=logits.map(v=>Math.exp(v-max)),sum=exp.reduce((a,b)=>a+b,0),p=exp.map(v=>v/sum);
  keys.forEach((key,j)=>{assert.ok(Math.abs(p[j]-a.model[key])<1e-7);assert.ok(Math.abs(a.prior[key]-frequencies[j])<1e-12);});
  const confidence=Math.max(...p),predicted=labels[p.indexOf(confidence)],priorDirection=labels[frequencies.indexOf(Math.max(...frequencies))];
  const abs=z.map(Math.abs),maxAbsZ=Math.max(...abs),top=abs.indexOf(maxAbsZ);
  return {fold:k+1,date:s.asOfDate,target:s.targetDate,actual:s.label,model:a.model,prior:a.prior,predicted,hit:predicted===s.label,priorHit:priorDirection===s.label,confidence,confidenceBin:bin(confidence),signalled:confidence>=.55&&p.filter(v=>Math.abs(v-confidence)<=1e-6).length===1,maxAbsZ,topFeature:names[top],extreme:maxAbsZ>3,constantChanged:names.filter((name,j)=>scale[j]===0&&s.features.values[name]!==mean[j])};
 });}
 const fitRows=convert(fit,archived.fit.rows),testRows=convert(diagnostic,archived.diagnostic.rows);
 folds.push({fold:k+1,fitCount:fit.length,fitTargetEnd:fit.at(-1).targetDate,diagnosticStart:diagnostic[0].asOfDate,diagnosticEnd:diagnostic.at(-1).asOfDate,scaleSource:'FIT_ONLY',fit:groups(fitRows),diagnostic:groups(testRows),rows:testRows});diagnosticRows.push(...testRows);
}
assert.equal(diagnosticRows.length,360);assert.equal(new Set(diagnosticRows.map(r=>r.date)).size,360);
const featureExtremes=names.map(name=>({name,topCount:diagnosticRows.filter(r=>r.extreme&&r.topFeature===name).length,topErrors:diagnosticRows.filter(r=>r.extreme&&r.topFeature===name&&!r.hit).length})).filter(r=>r.topCount).sort((a,b)=>b.topCount-a.topCount);
// 分批导出逐条证据，避免终端截断造成“只验证了一部分”的假象。
const offset=process.argv.find(a=>a.startsWith('--rows='));
if(offset){const start=Number(offset.split('=')[1]);assert.ok(Number.isInteger(start)&&start>=0&&start<360);console.log(JSON.stringify(diagnosticRows.slice(start,start+60)));}
else console.log(JSON.stringify({protocol:'2026-10-13-error-state-protocol.md',kind:'POSTHOC_FAILURE_DIAGNOSTIC_NOT_NEW_HOLDOUT',inputSha256:sha(input),modelArchiveSha256:sha(archive),trainingPerformed:false,holdoutRead:false,promotionAllowed:false,filterSelected:false,aggregateDiagnostic:groups(diagnosticRows),featureExtremes,folds:folds.map(({rows,...f})=>f)}));
