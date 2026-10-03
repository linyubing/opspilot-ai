// 独立真实输入、训练隔离、收敛和概率复算；不调用Java模型实现。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {names,vector,mix}=require('./FusionMath.cjs'),{recent,score}=require('./InfoMath.cjs');
const labels=['BULLISH','NEUTRAL','BEARISH'],best=p=>p.indexOf(Math.max(...p));
const root=path.join(__dirname,'../../..'),sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const sourceFiles=[
  'docs/research/probes/FusionRun.java','docs/research/probes/FusionCohort.java',
  'docs/research/probes/FusionScale.java','docs/research/probes/FusionMix.java',
  'docs/research/probes/FusionMath.cjs','docs/research/probes/InfoMath.cjs',
  'docs/research/probes/RidgeFit.java','docs/research/probes/SoftmaxFit.java',
  'docs/research/probes/TrainingProbe.java','docs/research/probes/MacroModelProbe.java',
  'backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilder.java',
  'backend/src/main/java/com/opspilot/ai/forecast/learning/FeatureScaler.java',
  'backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java',
  'backend/src/main/java/com/opspilot/ai/macrodata/FredHistoryStore.java',
  'backend/src/main/java/com/opspilot/ai/macrodata/FredHistory.java'];
const near=(a,b,tol=1e-12)=>assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=tol,`${a} != ${b}`);
const arrayNear=(a,b)=>{assert.equal(a.length,b.length);a.forEach((v,j)=>near(v,b[j]));};
function scaling(rows) {
  const mean=Array(9).fill(0),std=Array(9).fill(0);let n=0;
  for(const r of rows){n++;r.x.forEach((v,j)=>{const d=v-mean[j];mean[j]+=d/n;std[j]+=d*(v-mean[j]);});}
  return {mean,std:std.map(v=>Math.sqrt(Math.max(0,v/n)))};
}
const scaled=(r,s)=>r.x.map((v,j)=>s.std[j]===0?0:(v-s.mean[j])/s.std[j]);
function logits(w,x) {assert.equal(w.length,2*(x.length+1));const d=x.length+1,z=[w[d-1],w[2*d-1],0];
  for(let c=0;c<2;c++)x.forEach((v,j)=>z[c]+=v*w[c*d+j]);return z;}
function probabilities(w,x) {const z=logits(w,x),m=Math.max(...z),e=z.map(v=>Math.exp(v-m)),sum=e.reduce((a,b)=>a+b,0);return e.map(v=>v/sum);}
function objective(rows,s,w) {
  assert.equal(w.length,20);assert.ok(w.every(Number.isFinite));const g=Array(20).fill(0);let loss=0;
  for(const r of rows){const x=scaled(r,s),z=logits(w,x),p=probabilities(w,x),a=labels.indexOf(r.actual),m=Math.max(...z);
    loss+=(m+Math.log(z.reduce((v,l)=>v+Math.exp(l-m),0))-z[a])/rows.length;
    for(let c=0;c<2;c++){const d=(p[c]-(a===c?1:0))/rows.length;for(let j=0;j<9;j++)g[c*10+j]+=d*x[j];g[c*10+9]+=d;}}
  for(let j=0;j<9;j++){const a=w[j],b=w[j+10];loss+=.01/3*(a*a+b*b-a*b);g[j]+=.01/3*(2*a-b);g[j+10]+=.01/3*(2*b-a);}
  let gradient=0;for(let j=0;j<10;j++)gradient=Math.max(gradient,Math.abs(g[j]),Math.abs(g[j+10]),Math.abs(g[j]+g[j+10]));
  return {loss,gradient};
}
function pair(rows,against) {
  let newHits=0,newMisses=0;const byActual=Object.fromEntries(labels.map(l=>[l,{newHits:0,newMisses:0}]));
  for(const r of rows){const a=labels.indexOf(r.actual),ok=best(r.candidate)===a,old=best(r[against])===a;
    if(ok&&!old){newHits++;byActual[r.actual].newHits++;}if(!ok&&old){newMisses++;byActual[r.actual].newMisses++;}}
  return {newHits,newMisses,net:newHits-newMisses,byActual};
}
function summary(rows) {
  const out={};for(const key of ['candidate','price','macro','frequencyMix','prior','pitReference'])out[key]=score(rows,key);
  const selected=rows.filter(r=>Math.max(...r.candidate)>=.55);
  out.signals={...score(selected,'candidate'),coverage:selected.length/rows.length};out.priorOnSignals=score(selected,'prior');
  out.againstPrice=pair(rows,'price');out.againstFrequencyMix=pair(rows,'frequencyMix');return out;
}
function audit(report, reference, sources) {
  assert.equal(report.version,'gold-macro-late-fusion-v1');assert.equal(report.promotionAllowed,false);assert.match(report.gitCommit,/^[0-9a-f]{40}$/);
  assert.equal(report.cutoffExclusive,'2024-11-11');assert.equal(report.lambda,.01);assert.equal(report.priceWeight,.5);assert.equal(report.signalThreshold,.55);
  assert.equal(report.barHash,'68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0');
  assert.equal(report.barHash,reference.barHash);assert.equal(report.contentHash,'4ce352acbb854dd4e7e6d2de67c3426f0f62f360b76cf267559d57df07ea0dd3');
  assert.equal(report.referenceHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-02-history-check.json'))));
  assert.deepEqual(Object.keys(report.sourceHashes).sort(),sourceFiles.slice().sort());
  for(const [name,hash]of Object.entries(report.sourceHashes))assert.equal(hash,sha(fs.readFileSync(path.join(root,name))));
  assert.equal(report.protocolHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-fusion-protocol.md'))));
  assert.equal(report.macroInput.policy,'fred-known-before-day-v1');assert.deepEqual(report.featureNames,names);
  for(const id of ['DFII10','DTWEXBGS']) {
    const raw=fs.readFileSync(path.join(process.env.FRED_HISTORY_DIR,id+'.json')),input=sources[id];
    // 来源对象必须就是已绑定字节解析出的内容；包括版本区间与下载元数据。
    assert.deepEqual(input,JSON.parse(raw));
    assert.equal(report.macroInput[id+'.sha256'],sha(raw));assert.equal(input.series,id);assert.equal(input.outputType,1);
    assert.equal(input.count,input.observations.length);assert.equal(report.macroInput[id+'.start'],input.realtimeStart);assert.equal(report.macroInput[id+'.end'],input.realtimeEnd);
  }
  const expected=[];
  for(const r of reference.inputs){const rates=recent(sources.DFII10,r.date),dollars=recent(sources.DTWEXBGS,r.date);
    if(rates.length>=21&&dollars.length>=21)expected.push({date:r.date,target:r.target,actual:r.actual,x:vector(r.date,rates,dollars)});}
  assert.equal(expected.length,1500);assert.equal(report.inputs.length,expected.length);
  report.inputs.forEach((r,i)=>{for(const key of ['date','target','actual'])assert.equal(r[key],expected[i][key]);
    assert.ok(r.date<r.target&&r.target<report.cutoffExclusive);arrayNear(r.x,expected[i].x);});
  const byDate=new Map(report.inputs.map(r=>[r.date,r]));assert.equal(report.folds.length,3);
  const all=[],foldResults=[];
  for(const [i,f]of report.folds.entries()) {
    const old=reference.folds[i];assert.equal(f.start,old.start);assert.equal(f.end,old.end);
    const train=report.inputs.filter(r=>r.date<f.start&&r.target<f.start),scale=scaling(train);
    assert.equal(train.length,779+i*240);assert.deepEqual(f.trainingDates,train.map(r=>r.date));
    arrayNear(f.mean,scale.mean);arrayNear(f.std,scale.std);
    const counts=labels.map(l=>train.filter(r=>r.actual===l).length);arrayNear(f.frequencies,counts.map(n=>n/train.length));
    assert.equal(f.fit.converged,true);assert.equal(f.fit.status,'CONVERGED');assert.equal(f.repeatDifference,0);
    assert.ok(f.fit.trace.length>=2&&f.fit.trace.length<=101);
    for(const [j,t]of f.fit.trace.entries()){assert.equal(t.iteration,j);assert.ok(Number.isFinite(t.loss)&&Number.isFinite(t.gradient));
      assert.equal(t.rank,20);assert.ok(Number.isFinite(t.condition)&&t.condition>=1);if(j)assert.ok(t.loss<=f.fit.trace[j-1].loss+1e-12);}
    const solved=objective(train,scale,f.fit.weights);near(solved.loss,f.fit.trace.at(-1).loss,1e-11);near(solved.gradient,f.fit.trace.at(-1).gradient,1e-11);
    assert.ok(solved.gradient<=1e-6);assert.equal(f.training.length,train.length);
    for(const [j,r]of f.training.entries()){for(const k of ['date','target','actual'])assert.equal(r[k],train[j][k]);
      arrayNear(r.macro,probabilities(f.fit.weights,scaled(train[j],scale)));}
    const validation=report.inputs.filter(r=>r.date>=f.start&&r.date<=f.end);assert.equal(validation.length,240);
    assert.equal(f.predictions.length,240);assert.deepEqual(f.predictions.map(r=>r.date),validation.map(r=>r.date));
    for(const [j,r]of f.predictions.entries()){const raw=byDate.get(r.date),saved=old.predictions[j];
      for(const k of ['date','target','actual']){assert.equal(r[k],raw[k]);assert.equal(r[k],saved[k]);}
      arrayNear(r.price,saved.candidate);arrayNear(r.prior,saved.newPrior);arrayNear(r.pitReference,saved.reference);
      arrayNear(r.macro,probabilities(f.fit.weights,scaled(raw,scale)));arrayNear(r.candidate,mix(r.price,r.macro));arrayNear(r.frequencyMix,mix(r.price,f.frequencies));
      if(all.length)assert.ok(all.at(-1).date<r.date);all.push(r);}
    foldResults.push({start:f.start,end:f.end,trainingCount:train.length,counts,training:score(f.training,'macro'),...summary(f.predictions)});
  }
  assert.equal(all.length,720);const results=summary(all),c=results.candidate,p=results.price;
  const gates={accuracy:c.accuracy>p.accuracy&&c.accuracy>results.pitReference.accuracy&&c.accuracy>results.prior.accuracy,
    balancedGain:c.balancedAccuracy!==null&&p.balancedAccuracy!==null&&c.balancedAccuracy>=p.balancedAccuracy+.02,
    recalls:c.recalls.every(v=>v!==null&&v>=.25),brier:c.brierScore<=p.brierScore,logLoss:c.logLoss<=p.logLoss};
  const s=results.signals,signalGates={coverage:s.coverage>=.3,sameDateBaseline:s.accuracy!==null&&s.accuracy>results.priorOnSignals.accuracy,
    recalls:s.recalls.every(v=>v!==null&&v>=.25)};
  const blocks=[];for(let j=0;j<all.length;j+=20){const rows=all.slice(j,j+20);blocks.push({start:rows[0].date,end:rows.at(-1).date,...summary(rows)});}
  return {status:'PASS',promotionAllowed:false,gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean),
    ...results,foldResults,blocks};
}
module.exports = { audit };
