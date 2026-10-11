import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

// 独立按冻结输入重建时序分区、概率基线、评分和标准化分布；不训练。
const [inputPath, oldPath, resultPath] = process.argv.slice(2);
const inputRaw=fs.readFileSync(inputPath,'utf8'), oldRaw=fs.readFileSync(oldPath,'utf8');
const input=JSON.parse(inputRaw), old=JSON.parse(oldRaw), result=JSON.parse(fs.readFileSync(resultPath,'utf8'));
const sha=raw=>crypto.createHash('sha256').update(raw).digest('hex');
assert.equal(result.inputHash,sha(inputRaw));assert.equal(result.oldProbabilityHash,sha(oldRaw));
assert.equal(result.holdoutRead,false);assert.equal(result.promotionAllowed,false);assert.equal(result.newParametersTested,false);
const labels=['BULLISH','NEUTRAL','BEARISH'], keys=['bullish','neutral','bearish'];
const near=(a,b,tol=0.000051)=>assert(Math.abs(a-b)<tol,`${a} != ${b}`);
const names=Object.keys(input.samples[0].features.values).sort();assert.equal(names.length,36);
function score(rows,field) {
  const matrix=Object.fromEntries(labels.map(a=>[a,Object.fromEntries(labels.map(b=>[b,0]))]));
  let hits=0,brier=0,loss=0;
  for(const row of rows) {
    const p=keys.map(k=>row[field][k]);
    assert(p.every(x=>Number.isFinite(x)&&x>=0&&x<=1));near(p.reduce((a,b)=>a+b,0),1,1e-10);
    const pred=labels[p.indexOf(Math.max(...p))];matrix[row.actual][pred]++;hits+=Number(pred===row.actual);
    for(let i=0;i<3;i++)brier+=(p[i]-Number(labels[i]===row.actual))**2;
    loss-=Math.log(Math.max(1e-15,p[labels.indexOf(row.actual)]));
  }
  const recalls=Object.fromEntries(labels.map(l=>[l,Math.round(matrix[l][l]/Object.values(matrix[l]).reduce((a,b)=>a+b,0)*10000)/10000]));
  return {sampleCount:rows.length,coveredCount:rows.length,coverage:1,accuracy:hits/rows.length,
    balancedAccuracy:Object.values(recalls).reduce((a,b)=>a+b,0)/3,brierScore:brier/rows.length,
    logLoss:loss/rows.length,recalls,confusionMatrix:matrix};
}
assert.equal(result.folds.length,3);
for(let i=0;i<3;i++) {
  const f=result.folds[i],start=548+240*i;
  const outer=input.samples.slice(0,start).filter(s=>s.targetDate<input.samples[start].asOfDate);
  const diagnostic=outer.slice(-120);
  const fit=outer.slice(0,-120).filter(s=>s.targetDate<diagnostic[0].asOfDate);
  assert.equal(f.fitCount,fit.length);assert.equal(fit.length,426+240*i);
  assert.equal(f.fitTargetEnd,fit.at(-1).targetDate);assert(f.fitTargetEnd<f.diagnosticStart);
  assert.equal(f.diagnosticStart,diagnostic[0].asOfDate);
  const counts=Object.fromEntries(labels.map(l=>[l,fit.filter(s=>s.label===l).length]));assert.deepEqual(counts,f.fitCounts);
  // 两遍均值/方差独立于 Java 的 Welford 实现。
  const means=names.map(n=>fit.reduce((sum,s)=>sum+s.features.values[n],0)/fit.length);
  const scales=names.map((n,j)=>Math.sqrt(fit.reduce((sum,s)=>sum+(s.features.values[n]-means[j])**2,0)/fit.length));
  for(const [group,samples] of [[f.fit,fit],[f.diagnostic,diagnostic]]) {
    assert.equal(group.rows.length,samples.length);
    let max=0,over=0;
    group.rows.forEach((row,j)=>{
      const s=samples[j];assert.equal(row.date,s.asOfDate);assert.equal(row.target,s.targetDate);
      assert.equal(row.actual,s.label);assert(row.target<'2024-11-11');
      keys.forEach((k,c)=>near(row.prior[k],counts[labels[c]]/fit.length,1e-12));
      const z=Math.max(...names.map((n,k)=>scales[k]===0?0:Math.abs((s.features.values[n]-means[k])/scales[k])));
      max=Math.max(max,z);if(z>5)over++;
    });
    near(max,group.shift.maxAbsZ,1e-8);assert.equal(over,group.shift.overFiveCount);
    for(const field of ['model','prior']) {
      const computed=score(group.rows,field),saved=group[field];
      for(const key of ['sampleCount','coveredCount'])assert.equal(saved[key],computed[key]);
      for(const key of ['accuracy','coverage','balancedAccuracy','brierScore','logLoss'])near(saved[key],computed[key]);
      assert.deepEqual(saved.recalls,computed.recalls);assert.deepEqual(saved.confusionMatrix,computed.confusionMatrix);
    }
  }
  assert.deepEqual(f.diagnostic.rows,old.folds[i].calibration);
}
console.log('VERIFIED_THREE_PURGED_SPLITS_ALL_TRAINING_AND_DIAGNOSTIC_METRICS_SHIFTS_AND_OLD_PROBABILITIES');
