// 独立复算正则化研究概率；不访问数据库，不生成行情。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),cp=require('node:child_process');
const file=process.argv[2];if(!file)throw new Error('需要正则化研究 JSON 路径');
const report=JSON.parse(fs.readFileSync(file,'utf8'));
const referenceFile=path.join(__dirname,'../2026-10-02-macro-model-check.json');
const reference=JSON.parse(fs.readFileSync(referenceFile,'utf8'));
// 先独立验证历史参照本身，不能只相信其摘要或最终分数。
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-macro-model.cjs'),referenceFile],{stdio:'pipe'});
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const profiles=['BASE_16','OHLC_20','ALL_36'],strengths=[.01,.1,1],names=['BULLISH','NEUTRAL','BEARISH'];
const best=p=>p.indexOf(Math.max(...p));
const near=(a,b)=>assert.ok(Math.abs(a-b)<=.000051,`${a} != ${b}`);
function score(rows,key,threshold=0){
  const matrix=Array.from({length:3},()=>[0,0,0]);let covered=0,correct=0,brier=0,logLoss=0;
  for(const row of rows){
    const p=row[key],actual=names.indexOf(row.actual);assert.equal(p.length,3);assert.ok(actual>=0);
    assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));assert.ok(Math.abs(p.reduce((a,b)=>a+b,0)-1)<=1e-10);
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
assert.equal(report.version,'fred-centered-ridge-v1');assert.equal(report.promotionAllowed,false);
assert.match(report.gitCommit,/^[0-9a-f]{40}$/);assert.deepEqual(report.strengths,strengths);
assert.equal(report.cutoffExclusive,'2024-11-11');assert.equal(report.folds.length,3);
for(const key of ['barHash','contentHash','datasetHash','macroInput'])assert.deepEqual(report[key],reference[key]);
assert.equal(report.referenceHash,sha(Buffer.from(fs.readFileSync(referenceFile,'utf8').replace(/\r\n/g,'\n'),'utf8')));
assert.deepEqual(Object.keys(report.sourceHashes).sort(),['MacroModelProbe.java','RidgeChecks.java','RidgeFit.java','RidgeRun.java','SoftmaxFit.java','TrainingProbe.java']);
for(const [file,hash]of Object.entries(report.sourceHashes))assert.equal(sha(fs.readFileSync(path.join(__dirname,file))),hash);
assert.equal(sha(fs.readFileSync(path.join(__dirname,'../2026-10-02-ridge-protocol.md'))),report.protocolHash);
const keys=profiles.flatMap(p=>strengths.map(s=>`${p}:${s}`));
const all=Object.fromEntries(keys.map(key=>[key,[]]));
for(const [i,fold]of report.folds.entries()){
  const old=reference.folds[i];
  for(const key of ['start','end','trainStart','trainTargetEnd','trainingCount'])assert.equal(fold[key],old[key]);
  assert.equal(fold.trainingCount,779+i*240);assert.ok(fold.trainTargetEnd<fold.start);
  assert.deepEqual(fold.results.map(r=>`${r.profile}:${r.lambda}`),keys);
  for(const result of fold.results){
    const comparison=old.comparisons.find(c=>c.profile===result.profile),rows=result.predictions,fit=result.fit;
    assert.equal(fit.converged,true);assert.equal(fit.status,'CONVERGED');
    assert.equal(fit.weights.length,({BASE_16:34,OHLC_20:42,ALL_36:74})[result.profile]);
    assert.ok(fit.weights.every(Number.isFinite));
    assert.ok(fit.trace.length>0);assert.ok(fit.trace.at(-1).gradient<=1e-6);
    for(const step of fit.trace){assert.ok(Number.isFinite(step.loss)&&Number.isFinite(step.gradient)&&Number.isFinite(step.condition));assert.equal(step.rank,fit.weights.length);}
    for(let j=1;j<fit.trace.length;j++)assert.ok(fit.trace[j].loss<=fit.trace[j-1].loss+1e-12);
    assert.equal(result.repeatDifference,0);assert.ok(result.referenceDifference<=1e-12);assert.equal(rows.length,240);
    const key=`${result.profile}:${result.lambda}`;
    for(const [j,row]of rows.entries()){
      const previous=comparison.predictions[j];
      for(const field of ['date','target','actual','prior'])assert.deepEqual(row[field],previous[field]);
      assert.deepEqual(row.reference,previous.linear);assert.ok(row.date<row.target&&row.target<report.cutoffExclusive);
      if(all[key].length)assert.ok(all[key].at(-1).date<row.date);all[key].push(row);
    }
    check(score(rows,'ridge'),result.validation.all);check(score(rows,'ridge',.55),result.validation.signals);
    check(score(rows,'reference'),comparison.linearValidation.all);
    assert.equal(result.training.all.sampleCount,fold.trainingCount);
    const selected=rows.filter(r=>Math.max(...r.ridge)>=.55);assert.deepEqual(selected.map(r=>r.date),result.selectedDates);
    if(selected.length)check(score(selected,'prior'),result.priorOnSignals);else assert.equal(result.priorOnSignals,null);
  }
}
if(process.argv[3]){
  const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
  for(const key of ['sourceHashes','protocolHash','referenceHash','barHash','contentHash','datasetHash','macroInput','strengths','folds'])
    assert.deepEqual(report[key],repeat[key],`重复运行 ${key} 不一致`);
}
const results=keys.map(key=>{
  const rows=all[key],ridge=score(rows,'ridge'),reference=score(rows,'reference'),prior=score(rows,'prior');assert.equal(rows.length,720);
  const signals=score(rows,'ridge',.55),selected=rows.filter(r=>Math.max(...r.ridge)>=.55),signalPrior=score(selected,'prior');
  const gates={fullBaseline:ridge.accuracy>prior.accuracy,balancedGain:ridge.balancedAccuracy>=reference.balancedAccuracy+.02,
    recalls:ridge.recalls.every(v=>v!==null&&v>=.25),brier:ridge.brierScore<=reference.brierScore,logLoss:ridge.logLoss<=reference.logLoss};
  const signalGates={coverage:signals.coverage>=.3,sameDateBaseline:signals.accuracy>signalPrior.accuracy,
    recalls:signals.recalls.every(v=>v!==null&&v>=.25)};
  return {key,ridge,reference,prior,signals,priorOnSignals:signalPrior,againstReference:paired(rows,'reference','ridge'),
    againstPrior:paired(rows,'prior','ridge'),gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean)};
});
console.log(JSON.stringify({status:'PASS',promotionAllowed:false,results},null,2));
