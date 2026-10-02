// 独立复算类别成本实验，不访问数据库，不构造行情。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),cp=require('node:child_process');
const file=process.argv[2];if(!file)throw new Error('需要加权研究 JSON 路径');
const report=JSON.parse(fs.readFileSync(file,'utf8'));
const referenceFile=path.join(__dirname,'../2026-10-02-ridge-check.json'),reference=JSON.parse(fs.readFileSync(referenceFile,'utf8'));
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-ridge.cjs'),referenceFile],{stdio:'pipe'});
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const profiles=['BASE_16','OHLC_20','ALL_36'],modes=['SQRT','BALANCED'],names=['BULLISH','NEUTRAL','BEARISH'];
const best=p=>p.indexOf(Math.max(...p));
const near=(a,b,tolerance=.000051)=>assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=tolerance,`${a} != ${b}`);
function score(rows,key,threshold=0){
  const matrix=Array.from({length:3},()=>[0,0,0]);let covered=0,correct=0,brier=0,logLoss=0;
  for(const row of rows){
    const p=row[key],actual=names.indexOf(row.actual);assert.equal(p.length,3);assert.ok(actual>=0);
    assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));near(p.reduce((a,b)=>a+b,0),1,1e-10);
    const predicted=best(p);if(p[predicted]>=threshold){covered++;matrix[actual][predicted]++;if(predicted===actual)correct++;}
    brier+=p.reduce((s,v,c)=>s+(v-(c===actual?1:0))**2,0);logLoss-=Math.log(Math.max(p[actual],1e-15));
  }
  const recalls=matrix.map((r,c)=>r.reduce((a,b)=>a+b,0)?r[c]/r.reduce((a,b)=>a+b,0):null);
  return {samples:rows.length,correct,covered,coverage:rows.length?covered/rows.length:0,accuracy:covered?correct/covered:0,
    balancedAccuracy:recalls.includes(null)?null:recalls.reduce((a,b)=>a+b,0)/3,
    brierScore:rows.length?brier/rows.length:null,logLoss:rows.length?logLoss/rows.length:null,recalls,matrix};
}
function check(a,b){
  assert.equal(a.samples,b.sampleCount);assert.equal(a.covered,b.coveredCount);
  for(const field of ['coverage','accuracy','brierScore','logLoss'])near(a[field],b[field]);
  if(a.balancedAccuracy===null)assert.equal(b.balancedAccuracy,null);
  else near(a.recalls.reduce((s,v)=>s+Math.round(v*10000)/10000,0)/3,b.balancedAccuracy);
  for(let c=0;c<3;c++){
    if(a.recalls[c]===null)assert.equal(b.recalls[names[c]],null);else near(a.recalls[c],b.recalls[names[c]]);
    for(let d=0;d<3;d++)assert.equal(a.matrix[c][d],b.confusionMatrix[names[c]][names[d]]);
  }
}
function paired(rows,left,right){let wins=0,losses=0;for(const row of rows){
  const actual=names.indexOf(row.actual),a=best(row[left])===actual,b=best(row[right])===actual;
  if(!a&&b)wins++;if(a&&!b)losses++;}return{wins,losses,net:wins-losses};}
assert.equal(report.version,'fred-class-weight-v1');assert.equal(report.promotionAllowed,false);
assert.match(report.gitCommit,/^[0-9a-f]{40}$/);assert.equal(report.lambda,.01);assert.deepEqual(report.modes,modes);
assert.equal(report.cutoffExclusive,'2024-11-11');assert.equal(report.folds.length,3);
for(const key of ['barHash','contentHash','datasetHash','macroInput'])assert.deepEqual(report[key],reference[key]);
assert.equal(report.referenceHash,sha(Buffer.from(fs.readFileSync(referenceFile,'utf8').replace(/\r\n/g,'\n'),'utf8')));
assert.deepEqual(Object.keys(report.sourceHashes).sort(),['MacroModelProbe.java','RidgeFit.java','SoftmaxFit.java','TrainingProbe.java','WeightChecks.java','WeightFit.java','WeightRun.java']);
for(const [name,hash]of Object.entries(report.sourceHashes))assert.equal(sha(fs.readFileSync(path.join(__dirname,name))),hash);
assert.equal(sha(fs.readFileSync(path.join(__dirname,'../2026-10-02-weight-protocol.md'))),report.protocolHash);
const keys=profiles.flatMap(p=>modes.map(m=>`${p}:${m}`)),all=Object.fromEntries(keys.map(key=>[key,[]]));
for(const [i,fold]of report.folds.entries()){
  const old=reference.folds[i];
  for(const key of ['start','end','trainStart','trainTargetEnd','trainingCount'])assert.equal(fold[key],old[key]);
  assert.equal(fold.trainingCount,779+i*240);assert.ok(fold.trainTargetEnd<fold.start);
  assert.deepEqual(fold.results.map(r=>`${r.profile}:${r.mode}`),keys);
  for(const result of fold.results){
    const previous=old.results.find(r=>r.profile===result.profile&&r.lambda===.01),rows=result.predictions,fit=result.fit;
    assert.equal(result.counts.length,3);assert.ok(result.counts.every(n=>Number.isInteger(n)&&n>0));
    assert.equal(result.counts.reduce((a,b)=>a+b,0),fold.trainingCount);
    const power=result.mode==='SQRT'?.5:1;
    const rawWeights=result.counts.map(n=>(fold.trainingCount/(3*n))**power);
    const mean=rawWeights.reduce((s,w,c)=>s+w*result.counts[c]/fold.trainingCount,0);
    const expected=rawWeights.map(w=>w/mean);assert.equal(result.classWeights.length,3);
    for(let c=0;c<3;c++)near(result.classWeights[c],expected[c],1e-12);
    near(result.classWeights.reduce((s,w,c)=>s+w*result.counts[c]/fold.trainingCount,0),1,1e-12);
    assert.equal(fit.converged,true);assert.equal(fit.status,'CONVERGED');
    assert.equal(fit.weights.length,({BASE_16:34,OHLC_20:42,ALL_36:74})[result.profile]);assert.ok(fit.weights.every(Number.isFinite));
    assert.ok(fit.trace.length>0&&fit.trace.at(-1).gradient<=1e-6);
    for(const step of fit.trace){assert.ok(Number.isFinite(step.loss)&&Number.isFinite(step.gradient)&&Number.isFinite(step.condition));assert.equal(step.rank,fit.weights.length);}
    for(let j=1;j<fit.trace.length;j++)assert.ok(fit.trace[j].loss<=fit.trace[j-1].loss+1e-12);
    assert.equal(result.repeatDifference,0);assert.ok(result.referenceDifference<=1e-12);assert.equal(rows.length,240);
    const key=`${result.profile}:${result.mode}`;
    for(const [j,row]of rows.entries()){
      const before=previous.predictions[j];
      for(const field of ['date','target','actual','prior'])assert.deepEqual(row[field],before[field]);assert.deepEqual(row.reference,before.ridge);
      for(let c=0;c<3;c++)near(row.prior[c],result.counts[c]/fold.trainingCount,1e-12);
      const divided=row.raw.map((p,c)=>p/result.classWeights[c]),sum=divided.reduce((a,b)=>a+b,0);
      for(let c=0;c<3;c++)near(row.corrected[c],divided[c]/sum,1e-12);
      assert.ok(row.date<row.target&&row.target<report.cutoffExclusive);
      if(all[key].length)assert.ok(all[key].at(-1).date<row.date);all[key].push(row);
    }
    check(score(rows,'reference'),previous.validation.all);
    for(const output of ['Raw','Corrected']){
      const field=output.toLowerCase();check(score(rows,field),result[`validation${output}`].all);
      check(score(rows,field,.55),result[`validation${output}`].signals);
      assert.equal(result[`training${output}`].all.sampleCount,fold.trainingCount);
      const selected=rows.filter(r=>Math.max(...r[field])>=.55);assert.deepEqual(selected.map(r=>r.date),result[`${field}SelectedDates`]);
      if(selected.length)check(score(selected,'prior'),result[`priorOn${output}Signals`]);else assert.equal(result[`priorOn${output}Signals`],null);
    }
  }
}
if(process.argv[3]){
  const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
  for(const key of ['sourceHashes','protocolHash','referenceHash','barHash','contentHash','datasetHash','macroInput','lambda','modes','folds'])
    assert.deepEqual(report[key],repeat[key],`重复运行 ${key} 不一致`);
}
const results=keys.flatMap(key=>['raw','corrected'].map(output=>{
  const rows=all[key],model=score(rows,output),reference=score(rows,'reference'),prior=score(rows,'prior');assert.equal(rows.length,720);
  const signals=score(rows,output,.55),selected=rows.filter(r=>Math.max(...r[output])>=.55),signalPrior=score(selected,'prior');
  const gates={fullBaseline:model.accuracy>prior.accuracy,balancedGain:model.balancedAccuracy>=reference.balancedAccuracy+.02,
    recalls:model.recalls.every(v=>v!==null&&v>=.25),brier:model.brierScore<=reference.brierScore,logLoss:model.logLoss<=reference.logLoss};
  const signalGates={coverage:signals.coverage>=.3,sameDateBaseline:signals.accuracy>signalPrior.accuracy,
    recalls:signals.recalls.every(v=>v!==null&&v>=.25)};
  return {key,output,model,reference,prior,signals,priorOnSignals:signalPrior,againstReference:paired(rows,'reference',output),againstPrior:paired(rows,'prior',output),
    gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean)};
}));
console.log(JSON.stringify({status:'PASS',promotionAllowed:false,results},null,2));
