// 独立验证匹配训练、收敛及概率；不调用Java拟合实现。
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {scaling,probabilities,objective}=require('./VixFitMath.cjs'),{score}=require('./InfoMath.cjs');
const root=path.join(__dirname,'../../..'),sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const labels=['BULLISH','NEUTRAL','BEARISH'],keys=['price','candidate','prior','fullPrice'];
const near=(a,b,t=1e-11)=>assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=t);
const arr=(a,b)=>{assert.equal(a.length,b.length);a.forEach((v,j)=>near(v,b[j]));};
const scaled=(x,s)=>x.map((v,j)=>s.std[j]===0?0:(v-s.mean[j])/s.std[j]);
function pair(rows,key){
  const byActual=Object.fromEntries(labels.map(l=>[l,{newHits:0,newMisses:0}]));let newHits=0,newMisses=0;
  for(const r of rows){const a=labels.indexOf(r.actual),ok=r.candidate.indexOf(Math.max(...r.candidate))===a,old=r[key].indexOf(Math.max(...r[key]))===a;
    if(ok&&!old){newHits++;byActual[r.actual].newHits++;}if(!ok&&old){newMisses++;byActual[r.actual].newMisses++;}}
  return {newHits,newMisses,net:newHits-newMisses,byActual};
}
function summary(rows){
  const scores=Object.fromEntries(keys.map(k=>[k,score(rows,k)])),selected=rows.filter(r=>Math.max(...r.candidate)>=.55);
  return {scores,signals:{...score(selected,'candidate'),coverage:rows.length?selected.length/rows.length:null},
    priceOnSignals:score(selected,'price'),priorOnSignals:score(selected,'prior'),againstPrice:pair(rows,'price')};
}
function audit(tree,vectors,result){
  assert.equal(result.version,'vix-matched-ridge-v1');assert.match(result.gitCommit,/^[0-9a-f]{40}$/);
  assert.equal(result.lambda,.01);assert.equal(result.signalThreshold,.55);assert.equal(result.cutoffExclusive,'2024-11-11');assert.equal(result.promotionAllowed,false);
  assert.equal(result.treeHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'))));
  assert.equal(result.vectorsHash,sha(fs.readFileSync(path.join(root,'backend/target/vix-four-vectors.json'))));
  assert.equal(result.protocolHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-vix-protocol.md'))));
  const code=['VixRun.java','VixCohort.java','VixCohortChecks.java','FusionScale.java','RidgeFit.java','SoftmaxFit.java'];
  assert.deepEqual(Object.keys(result.sourceHashes).sort(),code.slice().sort());
  for(const n of code)assert.equal(result.sourceHashes[n],sha(fs.readFileSync(path.join(__dirname,n))));
  assert.equal(tree.inputs.length,4361);assert.equal(vectors.vectors.length,4361);assert.equal(result.folds.length,3);
  const rows=tree.inputs.map((r,i)=>{const v=vectors.vectors[i];assert.equal(r.date,v.date);assert.equal(r.target,v.target);
    assert.ok(r.date<r.target&&r.target<'2024-11-11');assert.equal(r.x.length,20);
    if(v.values!==null){assert.equal(v.values.length,4);assert.ok(v.values.every(Number.isFinite));}
    return {...r,risk:v.values};});
  for(let i=1;i<rows.length;i++)assert.ok(rows[i-1].date<rows[i].date);
  const all=[],folds=[];
  for(const [i,f]of result.folds.entries()){
    const old=tree.folds[i];assert.equal(f.start,old.start);assert.equal(f.end,old.end);
    const train=rows.filter(r=>r.risk!==null&&r.date<f.start&&r.target<f.start),valid=rows.filter(r=>r.date>=f.start&&r.date<=f.end);
    assert.equal(train.length,2915+i*240);assert.equal(valid.length,240);assert.ok(valid.every(r=>r.risk!==null));
    assert.deepEqual(f.trainingDates,train.map(r=>r.date));assert.equal(f.training.length,train.length);assert.equal(f.predictions.length,240);
    const counts=labels.map(l=>train.filter(r=>r.actual===l).length),prior=counts.map(n=>n/train.length),solved={};
    for(const k of ['price','candidate']){
      const x=train.map(r=>k==='price'?r.x:[...r.x,...r.risk]),s=scaling(x),m=f[k];arr(m.mean,s.mean);arr(m.std,s.std);
      assert.equal(m.fit.converged,true);assert.equal(m.fit.status,'CONVERGED');assert.equal(m.repeatDifference,0);
      assert.ok(m.fit.trace.length>=2&&m.fit.trace.length<=101);
      for(const [j,t]of m.fit.trace.entries()){assert.equal(t.iteration,j);assert.ok(Number.isFinite(t.loss)&&Number.isFinite(t.gradient));
        assert.equal(t.rank,2*(x[0].length+1));assert.ok(Number.isFinite(t.condition)&&t.condition>=1);if(j)assert.ok(t.loss<=m.fit.trace[j-1].loss+1e-12);}
      const fit=objective(x.map(r=>scaled(r,s)),train.map(r=>labels.indexOf(r.actual)),m.fit.weights);
      near(fit.loss,m.fit.trace.at(-1).loss);near(fit.maxGradient,m.fit.trace.at(-1).gradient);assert.ok(fit.maxGradient<=1e-6);
      solved[k]={scale:s,fit};
    }
    for(const [saved,raws,isValidation]of[[f.training,train,false],[f.predictions,valid,true]]){
      for(const [j,r]of saved.entries()){
        const raw=raws[j];for(const k of ['date','target','actual'])assert.equal(r[k],raw[k]);arr(r.prior,prior);
        for(const k of ['price','candidate']){
          const x=k==='price'?raw.x:[...raw.x,...raw.risk];arr(r[k],probabilities(f[k].fit.weights,scaled(x,solved[k].scale)));
        }
        if(isValidation){const original=old.predictions[j];for(const k of ['date','target','actual'])assert.equal(r[k],original[k]);
          arr(r.fullPrice,original.reference);if(all.length)assert.ok(all.at(-1).date<r.date);all.push(r);}
        else assert.equal(r.fullPrice,null);
      }
    }
    folds.push({start:f.start,end:f.end,trainingCount:train.length,trainLabels:counts,
      gradients:Object.fromEntries(['price','candidate'].map(k=>[k,solved[k].fit.maxGradient])),
      training:{price:score(f.training,'price'),candidate:score(f.training,'candidate')},...summary(f.predictions)});
  }
  assert.equal(all.length,720);const stats=summary(all),c=stats.scores.candidate,p=stats.scores.price,s=stats.signals;
  const gates={accuracy:c.accuracy>p.accuracy&&c.accuracy>stats.scores.prior.accuracy,
    balancedGain:c.balancedAccuracy!==null&&p.balancedAccuracy!==null&&c.balancedAccuracy>=p.balancedAccuracy+.02,
    recalls:c.recalls.every(v=>v!==null&&v>=.25),brier:c.brierScore<=p.brierScore,logLoss:c.logLoss<=p.logLoss};
  const signalGates={coverage:s.coverage>=.30,sameDateBaselines:s.accuracy!==null&&s.accuracy>stats.priceOnSignals.accuracy&&s.accuracy>stats.priorOnSignals.accuracy,
    recalls:s.recalls.every(v=>v!==null&&v>=.25)};
  const blocks=[];for(let j=0;j<all.length;j+=20)blocks.push({start:all[j].date,end:all[j+19].date,...summary(all.slice(j,j+20))});
  return {promotionAllowed:false,...stats,folds,blocks,gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean)};
}
module.exports={audit};
