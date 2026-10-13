import fs from 'node:fs';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

// 不调用Java预测器：重建历史同比、训练缩放、概率、梯度及全部评分。
const [inputPath, archivePath, oldPath, resultPath] = process.argv.slice(2);
const raws = [inputPath, archivePath, oldPath].map(p => fs.readFileSync(p, 'utf8'));
const [input, archive, old] = raws.map(JSON.parse);
const r = JSON.parse(fs.readFileSync(resultPath, 'utf8'));
const sha = text => createHash('sha256').update(text).digest('hex');
['inputHash', 'cpiArchiveHash', 'oldHash'].forEach((key, i) => assert.equal(r[key], sha(raws[i])));
assert.equal(r.holdoutRead, false); assert.equal(r.promotionAllowed, false);
const labels = ['BULLISH', 'NEUTRAL', 'BEARISH'], keys = ['bullish', 'neutral', 'bearish'];
const names = Object.keys(input.samples[0].features.values).sort();
assert.deepEqual(names, r.featureNames);
const near = (a,b,t=5.1e-5) => assert.ok(Math.abs(a-b)<t, `${a} != ${b}`);
const cpi = date => {
  const cutoff = new Date(Date.parse(date+'T00:00:00Z')-86400000).toISOString().slice(0,10);
  const available = archive.observations.filter(o => o.date<=cutoff && o.realtime_start<=cutoff && cutoff<=o.realtime_end && o.value!=='.')
    .sort((a,b)=>b.date.localeCompare(a.date));
  const current = available[0];
  const previousYear = String(Number(current.date.slice(0,4))-1) + current.date.slice(4);
  const year = available.find(o=>o.date===previousYear);
  assert.ok(year);
  return (Number(current.value)/Number(year.value)-1)*100;
};
for (const s of input.samples) near(cpi(s.asOfDate), r.cpiValues[s.asOfDate], 1e-12);
const signal = p => Math.max(...p)>=.55 && p.filter(v=>Math.abs(v-Math.max(...p))<=1e-6).length===1;
function metrics(rows, field, signals=false) {
  const cm = Object.fromEntries(labels.map(a=>[a,Object.fromEntries(labels.map(b=>[b,0]))]));
  let n=0,hits=0,bs=0,ll=0;
  for(const row of rows) {
    const p=keys.map(k=>row[field][k]); assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1)); near(p.reduce((a,b)=>a+b),1,1e-12);
    p.forEach((v,i)=>bs+=(v-Number(row.actual===labels[i]))**2);
    ll-=Math.log(Math.max(1e-15,p[labels.indexOf(row.actual)]));
    if(signals&&!signal(p))continue;
    const predicted=labels[p.indexOf(Math.max(...p))]; n++; hits+=Number(predicted===row.actual); cm[row.actual][predicted]++;
  }
  const recalls=Object.fromEntries(labels.map(a=>{const count=Object.values(cm[a]).reduce((a,b)=>a+b);return [a,count?Math.round(cm[a][a]/count*1e4)/1e4:null];}));
  return {sampleCount:rows.length,coveredCount:n,coverage:n/rows.length,accuracy:n?hits/n:null,
    balancedAccuracy:Object.values(recalls).some(v=>v===null)?null:Object.values(recalls).reduce((a,b)=>a+b)/3,
    brierScore:bs/rows.length,logLoss:ll/rows.length,recalls,confusionMatrix:cm};
}
function match(a,b) {
  for(const k of ['sampleCount','coveredCount'])assert.equal(a[k],b[k]);
  for(const k of ['coverage','accuracy','balancedAccuracy','brierScore','logLoss'])if(a[k]===null)assert.equal(b[k],null);else near(a[k],b[k]);
  assert.deepEqual(a.recalls,b.recalls);assert.deepEqual(a.confusionMatrix,b.confusionMatrix);
}
const totals={base:0,cpi:0,prior:0,samples:0};
for(let k=0;k<3;k++) {
  const start=548+240*k;
  const outer=input.samples.slice(0,start).filter(s=>s.targetDate<input.samples[start].asOfDate);
  const diag=outer.slice(-120),train=outer.slice(0,-120).filter(s=>s.targetDate<diag[0].asOfDate);
  const fold=r.folds[k];assert.equal(train.length,426+240*k);assert.equal(fold.fitCount,train.length);assert.equal(fold.diagnosticStart,diag[0].asOfDate);
  const counts=labels.map(l=>train.filter(s=>s.label===l).length);
  for(const type of ['base','cpi']) {
    const v=fold[type],extra=type==='cpi';
    const rawVector=s=>[...names.map(n=>s.features.values[n]),...(extra?[cpi(s.asOfDate)]:[])];
    const matrix=train.map(rawVector),means=matrix[0].map(()=>0),vars=matrix[0].map(()=>0);
    matrix.forEach((x,i)=>x.forEach((a,j)=>{const d=a-means[j];means[j]+=d/(i+1);vars[j]+=d*(a-means[j]);}));
    const scales=vars.map(v=>Math.sqrt(v/train.length));
    if(extra){near(v.cpiMean,means.at(-1),1e-12);near(v.cpiScale,scales.at(-1),1e-12);}
    const z=s=>rawVector(s).map((x,j)=>scales[j]===0?0:(x-means[j])/scales[j]);
    const width=means.length+1,w=v.solver.weights;assert.equal(w.length,2*width);assert.equal(v.solver.converged,true);
    const gradient=Array(2*width).fill(0);
    for(const [group,samples] of [[v.fit,train],[v.diagnostic,diag]]) {
      assert.equal(group.rows.length,samples.length);
      group.rows.forEach((row,i)=>{
        const s=samples[i];assert.equal(row.date,s.asOfDate);assert.equal(row.target,s.targetDate);assert.equal(row.actual,s.label);assert.ok(s.targetDate<'2024-11-11');
        const x=z(s),logits=[w[width-1],w[2*width-1],0];
        for(let c=0;c<2;c++)x.forEach((a,j)=>logits[c]+=w[c*width+j]*a);
        const max=Math.max(...logits),e=logits.map(a=>Math.exp(a-max)),sum=e.reduce((a,b)=>a+b),p=e.map(a=>a/sum);
        keys.forEach((key,c)=>{near(p[c],row.model[key],1e-7);near(row.prior[key],counts[c]/train.length,1e-12);});
        if(group===v.fit)for(let c=0;c<2;c++)for(let j=0;j<width;j++)gradient[c*width+j]+=(p[c]-Number(s.label===labels[c]))/train.length*(j===width-1?1:x[j]);
      });
      match(metrics(group.rows,'model'),group.model);match(metrics(group.rows,'prior'),group.prior);match(metrics(group.rows,'model',true),group.signals);
    }
    let g=Math.max(...gradient.map(Math.abs));for(let j=0;j<width;j++)g=Math.max(g,Math.abs(gradient[j]+gradient[width+j]));
    assert.ok(g<=1e-6);near(g,v.solver.trace.at(-1).gradient,1e-7);
    if(type==='base')assert.deepEqual(v.diagnostic.rows,old.folds[k].diagnostic.rows);
    for(const row of v.diagnostic.rows){const p=keys.map(key=>row.model[key]);totals[type]+=Number(labels[p.indexOf(Math.max(...p))]===row.actual);}
  }
  totals.samples+=diag.length;
  for(const row of fold.base.diagnostic.rows){const p=keys.map(key=>row.prior[key]);totals.prior+=Number(labels[p.indexOf(Math.max(...p))]===row.actual);}
}
console.log(JSON.stringify({independentVerificationPassed:true,...totals}));
