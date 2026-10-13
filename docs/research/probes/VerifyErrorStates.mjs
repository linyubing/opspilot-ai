// 独立用冻结概率和直接均值/方差核验失败状态及所有诊断分组。
import fs from 'node:fs';import crypto from 'node:crypto';import assert from 'node:assert/strict';
const base=new URL('../',import.meta.url),read=p=>JSON.parse(fs.readFileSync(new URL(p,base),'utf8'));
const report=read('2026-10-13-error-state-result.json'),archive=read('2026-10-11-fit-reference-result.json'),samples=read('2026-10-11-prior-compare-input.json').samples;
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(new URL(p,base))).digest('hex');
assert.equal(report.inputSha256,hash('2026-10-11-prior-compare-input.json'));assert.equal(report.modelArchiveSha256,hash('2026-10-11-fit-reference-result.json'));
const labels=['BULLISH','NEUTRAL','BEARISH'],keys=['bullish','neutral','bearish'],names=archive.featureNames;
const near=(a,b)=>assert.ok(Math.abs(a-b)<1e-9,`${a} != ${b}`);
const bin=p=>p<.5?'P_LT_50':p<.6?'P_50_60':p<.8?'P_60_80':'P_GE_80';
assert.equal(report.diagnosticRows.length,360);assert.equal(new Set(report.diagnosticRows.map(r=>r.date)).size,360);
for(let k=0;k<3;k++){
 const start=548+240*k,outer=samples.slice(0,start).filter(s=>s.targetDate<samples[start].asOfDate),test=outer.slice(-120),fit=outer.slice(0,-120).filter(s=>s.targetDate<test[0].asOfDate);
 const means=names.map(n=>fit.reduce((a,s)=>a+s.features.values[n],0)/fit.length),scales=names.map((n,j)=>Math.sqrt(fit.reduce((a,s)=>a+(s.features.values[n]-means[j])**2,0)/fit.length));
 const rows=report.diagnosticRows.filter(r=>r.fold===k+1);assert.equal(rows.length,120);
 assert.equal(report.folds[k].fitTargetEnd,fit.at(-1).targetDate);assert.ok(fit.at(-1).targetDate<test[0].asOfDate);
 rows.forEach((r,i)=>{
  const a=archive.folds[k].diagnostic.rows[i],s=test[i];assert.equal(r.date,s.asOfDate);assert.equal(r.target,s.targetDate);assert.equal(r.actual,s.label);assert.ok(r.target<'2024-11-11');assert.deepEqual(r.model,a.model);assert.deepEqual(r.prior,a.prior);
  const p=keys.map(key=>a.model[key]);const confidence=Math.max(...p);near(r.confidence,confidence);assert.equal(r.confidenceBin,bin(confidence));assert.equal(r.signalled,confidence>=.55&&p.filter(v=>Math.abs(v-confidence)<=1e-6).length===1);
  assert.equal(r.predicted,labels[p.indexOf(confidence)]);assert.equal(r.hit,r.predicted===r.actual);
  const prior=keys.map(key=>a.prior[key]);assert.equal(r.priorHit,labels[prior.indexOf(Math.max(...prior))]===r.actual);
  const z=names.map((n,j)=>scales[j]===0?0:Math.abs((s.features.values[n]-means[j])/scales[j]));near(r.maxAbsZ,Math.max(...z));near(z[names.indexOf(r.topFeature)],r.maxAbsZ);assert.equal(r.extreme,r.maxAbsZ>3);
 });
 // 训练内分组也独立核对，但不能把它们当训练期样本外表现。
 const fitRows=archive.folds[k].fit.rows.map((a,i)=>{
  const s=fit[i],p=keys.map(key=>a.model[key]),prior=keys.map(key=>a.prior[key]);
  assert.equal(a.date,s.asOfDate);assert.equal(a.actual,s.label);
  const confidence=Math.max(...p),predicted=labels[p.indexOf(confidence)],z=names.map((n,j)=>scales[j]===0?0:Math.abs((s.features.values[n]-means[j])/scales[j]));
  return {actual:a.actual,model:a.model,prior:a.prior,confidence,predicted,hit:predicted===a.actual,priorHit:labels[prior.indexOf(Math.max(...prior))]===a.actual,confidenceBin:bin(confidence),signalled:confidence>=.55&&p.filter(v=>Math.abs(v-confidence)<=1e-6).length===1,extreme:Math.max(...z)>3,constantChanged:names.filter((n,j)=>scales[j]===0&&s.features.values[n]!==means[j])};
 });
 grouped(fitRows,report.folds[k].fit);
}
function check(rows,saved){
 assert.equal(saved.samples,rows.length);if(!rows.length)return;
 const hits=rows.filter(r=>r.hit).length,priorHits=rows.filter(r=>r.priorHit).length;assert.equal(saved.hits,hits);assert.equal(saved.priorHits,priorHits);near(saved.accuracy,hits/rows.length);near(saved.priorAccuracy,priorHits/rows.length);
 const conf=rows.reduce((a,r)=>a+r.confidence,0)/rows.length;near(saved.meanConfidence,conf);near(saved.confidenceGap,conf-saved.accuracy);
 for(const [field,prefix] of [['model',''],['prior','prior']]){
  let brier=0,loss=0;for(const r of rows){for(let j=0;j<3;j++)brier+=(r[field][keys[j]]-Number(r.actual===labels[j]))**2;loss-=Math.log(Math.max(1e-15,r[field][keys[labels.indexOf(r.actual)]]));}
  near(saved[prefix?'priorBrierScore':'brierScore'],brier/rows.length);near(saved[prefix?'priorLogLoss':'logLoss'],loss/rows.length);
 }
 for(const a of labels)for(const p of labels)assert.equal(saved.confusion[a][p],rows.filter(r=>r.actual===a&&r.predicted===p).length);
}
function grouped(rows,g){check(rows,g.all);check(rows.filter(r=>r.signalled),g.existingSignals);for(const b of ['P_LT_50','P_50_60','P_60_80','P_GE_80'])check(rows.filter(r=>r.confidenceBin===b),g.confidenceBins[b]);check(rows.filter(r=>r.extreme),g.inputState.extreme);check(rows.filter(r=>!r.extreme),g.inputState.normal);for(const l of labels)check(rows.filter(r=>r.predicted===l),g.byPrediction[l]);check(rows.filter(r=>r.constantChanged.length>0),g.constantFeatureChanged);}
grouped(report.diagnosticRows,report.aggregateDiagnostic);for(let k=0;k<3;k++)grouped(report.diagnosticRows.filter(r=>r.fold===k+1),report.folds[k].diagnostic);
for(const flag of ['trainingPerformed','holdoutRead','promotionAllowed','filterSelected'])assert.equal(report[flag],false);
console.log(JSON.stringify({verifiedDiagnosticRows:360,hits:report.aggregateDiagnostic.all.hits,priorHits:report.aggregateDiagnostic.all.priorHits,signalled:report.aggregateDiagnostic.existingSignals.samples,signalHits:report.aggregateDiagnostic.existingSignals.hits,extreme:report.aggregateDiagnostic.inputState.extreme.samples}));
