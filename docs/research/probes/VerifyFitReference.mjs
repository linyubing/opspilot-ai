import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

// 独立从冻结特征重建缩放、权重预测、梯度与全部评分，不调用 Java 模型。
const [inputPath,oldPath,resultPath]=process.argv.slice(2);
const inputRaw=fs.readFileSync(inputPath,'utf8'),oldRaw=fs.readFileSync(oldPath,'utf8');
const input=JSON.parse(inputRaw),old=JSON.parse(oldRaw),report=JSON.parse(fs.readFileSync(resultPath,'utf8'));
const sha=s=>crypto.createHash('sha256').update(s).digest('hex');
assert.equal(report.inputHash,sha(inputRaw));assert.equal(report.oldAuditHash,sha(oldRaw));
assert.equal(report.holdoutRead,false);assert.equal(report.promotionAllowed,false);
for(const [name,hash] of Object.entries(report.sourceHashes))assert.equal(sha(fs.readFileSync(new URL(name,import.meta.url),'utf8')),hash);
const labels=['BULLISH','NEUTRAL','BEARISH'],keys=['bullish','neutral','bearish'];
const names=Object.keys(input.samples[0].features.values).sort();assert.deepEqual(report.featureNames,names);
const near=(a,b,tol=0.000051)=>assert(Math.abs(a-b)<tol,`${a} != ${b}`);
const signalled=p=>Math.max(...p)>=.55&&p.filter(v=>Math.abs(v-Math.max(...p))<=1e-6).length===1;
function metrics(rows,field,signals=false) {
  const matrix=Object.fromEntries(labels.map(a=>[a,Object.fromEntries(labels.map(b=>[b,0]))]));
  let count=0,hits=0,brier=0,loss=0;
  for(const row of rows) {
    const p=keys.map(k=>row[field][k]);assert(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));near(p.reduce((a,b)=>a+b,0),1,1e-10);
    for(let j=0;j<3;j++)brier+=(p[j]-Number(labels[j]===row.actual))**2;
    loss-=Math.log(Math.max(1e-15,p[labels.indexOf(row.actual)]));
    if(signals&&!signalled(p))continue;
    const predicted=labels[p.indexOf(Math.max(...p))];count++;hits+=Number(predicted===row.actual);matrix[row.actual][predicted]++;
  }
  const recalls=Object.fromEntries(labels.map(l=>{
    const n=Object.values(matrix[l]).reduce((a,b)=>a+b,0);
    return [l,n?Math.round(matrix[l][l]/n*10000)/10000:null];
  }));
  return {sampleCount:rows.length,coveredCount:count,coverage:count/rows.length,
    accuracy:count?hits/count:null,balancedAccuracy:Object.values(recalls).some(v=>v===null)?null:Object.values(recalls).reduce((a,b)=>a+b,0)/3,
    brierScore:brier/rows.length,logLoss:loss/rows.length,recalls,confusionMatrix:matrix};
}
function match(a,b) {
  for(const k of ['sampleCount','coveredCount'])assert.equal(a[k],b[k]);
  for(const k of ['accuracy','balancedAccuracy','coverage','brierScore','logLoss'])if(a[k]===null)assert.equal(b[k],null);else near(a[k],b[k]);
  assert.deepEqual(a.recalls,b.recalls);assert.deepEqual(a.confusionMatrix,b.confusionMatrix);
}
assert.equal(report.folds.length,3);
let hits=0,priorHits=0,oldHits=0;
for(let index=0;index<3;index++) {
  const fold=report.folds[index],start=548+240*index;
  const outer=input.samples.slice(0,start).filter(s=>s.targetDate<input.samples[start].asOfDate);
  const diagnostic=outer.slice(-120),fit=outer.slice(0,-120).filter(s=>s.targetDate<diagnostic[0].asOfDate);
  assert.equal(fold.fitCount,fit.length);assert.equal(fit.length,426+240*index);
  assert.equal(fold.fitTargetEnd,fit.at(-1).targetDate);assert.equal(fold.diagnosticStart,diagnostic[0].asOfDate);
  assert(fold.fitTargetEnd<fold.diagnosticStart);
  let previous=Infinity;
  for(const step of fold.solver.trace){assert(step.loss<=previous+1e-9);previous=step.loss;assert(Number.isFinite(step.gradient));}
  if(!fold.solver.converged) {
    assert.equal(fold.fit,null);assert.equal(fold.diagnostic,null);assert.equal(fold.signals,null);continue;
  }
  assert.equal(fold.solver.status,'CONVERGED');assert(fold.solver.trace.at(-1).gradient<=1e-6);
  const means=names.map(()=>0),vars=names.map(()=>0);
  fit.forEach((s,i)=>names.forEach((n,j)=>{const delta=s.features.values[n]-means[j];means[j]+=delta/(i+1);vars[j]+=delta*(s.features.values[n]-means[j]);}));
  const scales=vars.map(v=>Math.sqrt(Math.max(0,v/fit.length)));
  const z=s=>names.map((n,j)=>scales[j]===0?0:(s.features.values[n]-means[j])/scales[j]);
  const width=names.length+1,w=fold.solver.weights;assert.equal(w.length,width*2);assert(w.every(Number.isFinite));
  function probabilities(x) {
    const logits=[w[width-1],w[2*width-1],0];
    for(let c=0;c<2;c++)for(let j=0;j<x.length;j++)logits[c]+=w[c*width+j]*x[j];
    const max=Math.max(...logits),exps=logits.map(v=>Math.exp(v-max)),sum=exps.reduce((a,b)=>a+b,0);
    return exps.map(v=>v/sum);
  }
  const counts=labels.map(l=>fit.filter(s=>s.label===l).length),gradient=Array(width*2).fill(0);
  for(const [group,samples] of [[fold.fit,fit],[fold.diagnostic,diagnostic]]) {
    assert.equal(group.rows.length,samples.length);
    group.rows.forEach((row,i)=>{
      const s=samples[i];assert.equal(row.date,s.asOfDate);assert.equal(row.target,s.targetDate);assert.equal(row.actual,s.label);assert(row.target<'2024-11-11');
      const x=z(s),p=probabilities(x);
      keys.forEach((k,c)=>{near(p[c],row.model[k],1e-7);near(row.prior[k],counts[c]/fit.length,1e-12);});
      if(group===fold.fit)for(let c=0;c<2;c++)for(let j=0;j<width;j++)gradient[c*width+j]+=(p[c]-Number(s.label===labels[c]))/fit.length*(j===width-1?1:x[j]);
    });
    match(metrics(group.rows,'model'),group.model);match(metrics(group.rows,'prior'),group.prior);
  }
  let max=Math.max(...gradient.map(Math.abs));
  for(let j=0;j<width;j++)max=Math.max(max,Math.abs(gradient[j]+gradient[width+j]));
  assert(max<=1e-6);near(max,fold.solver.trace.at(-1).gradient,1e-7);
  near(metrics(fold.fit.rows,'model').logLoss,fold.solver.trace.at(-1).loss,1e-7);
  const oldRows=old.folds[index].diagnostic.rows;
  fold.diagnostic.rows.forEach((row,i)=>{
    assert.equal(row.date,oldRows[i].date);assert.equal(row.target,oldRows[i].target);assert.equal(row.actual,oldRows[i].actual);assert.deepEqual(row.prior,oldRows[i].prior);
    const hit=p=>labels[p.indexOf(Math.max(...p))]===row.actual;
    hits+=Number(hit(keys.map(k=>row.model[k])));priorHits+=Number(hit(keys.map(k=>row.prior[k])));oldHits+=Number(hit(keys.map(k=>oldRows[i].model[k])));
  });
  match(metrics(fold.diagnostic.rows,'model',true),fold.signals);
  const selected=fold.diagnostic.rows.filter(row=>signalled(keys.map(k=>row.model[k])));
  if(selected.length)match(metrics(selected,'prior'),fold.priorOnSignals);else assert.equal(fold.priorOnSignals,null);
}
console.log(`VERIFIED_FOLD_SCALING_PARAMETERS_PROBABILITIES_GRADIENTS_AND_ALL_METRICS; referenceHits=${hits}; priorHits=${priorHits}; oldHits=${oldHits}`);
