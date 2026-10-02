// 独立复算逐日概率，不调用 Java 指标实现，也不访问数据库。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const file = process.argv[2];
if (!file) throw new Error('需要研究结果 JSON 路径');
const report = JSON.parse(fs.readFileSync(file, 'utf8'));
const names = ['BULLISH', 'NEUTRAL', 'BEARISH'];
const profiles = ['BASE_16', 'OHLC_20', 'ALL_36'];
const best = p => p.indexOf(Math.max(...p));
const near = (a, b) => assert.ok(Math.abs(a - b) <= .000051, `${a} != ${b}`);
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function score(rows, key, threshold = 0) {
  const matrix = Array.from({length:3}, () => [0,0,0]);
  let correct=0, covered=0, brier=0, logLoss=0;
  for (const row of rows) {
    const p=row[key], actual=names.indexOf(row.actual);
    assert.equal(p.length,3); assert.ok(actual>=0);
    assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));
    // XGBoost 概率由 float 转为 double；保留原值，容忍单精度求和误差。
    assert.ok(Math.abs(p.reduce((a,b)=>a+b,0)-1) <= (key==='tree'?1e-6:1e-10));
    const predicted=best(p);
    if (p[predicted]>=threshold) {
      covered++; matrix[actual][predicted]++;
      if (predicted===actual) correct++;
    }
    brier+=p.reduce((sum,v,c)=>sum+(v-(c===actual?1:0))**2,0);
    logLoss-=Math.log(Math.max(p[actual],1e-15));
  }
  const recalls=matrix.map((r,c)=>r.reduce((a,b)=>a+b,0)?r[c]/r.reduce((a,b)=>a+b,0):null);
  return {samples:rows.length,correct,covered,coverage:rows.length?covered/rows.length:0,
    accuracy:covered?correct/covered:0,
    balancedAccuracy:recalls.includes(null)?null:recalls.reduce((a,b)=>a+b,0)/3,
    brierScore:rows.length?brier/rows.length:null,logLoss:rows.length?logLoss/rows.length:null,recalls,matrix};
}
function check(actual, expected) {
  assert.equal(actual.samples,expected.sampleCount); assert.equal(actual.covered,expected.coveredCount);
  for (const field of ['coverage','accuracy','brierScore','logLoss']) near(actual[field],expected[field]);
  if (actual.balancedAccuracy===null) assert.equal(expected.balancedAccuracy,null);
  else near(actual.recalls.reduce((s,v)=>s+Math.round(v*10000)/10000,0)/3,expected.balancedAccuracy);
  for (let c=0;c<3;c++) {
    if (actual.recalls[c]===null) assert.equal(expected.recalls[names[c]],null);
    else near(actual.recalls[c],expected.recalls[names[c]]);
    for (let d=0;d<3;d++) assert.equal(actual.matrix[c][d],expected.confusionMatrix[names[c]][names[d]]);
  }
}
function paired(rows, left, right) {
  let wins=0, losses=0;
  for (const row of rows) {
    const actual=names.indexOf(row.actual), a=best(row[left])===actual, b=best(row[right])===actual;
    if (!a&&b) wins++; if (a&&!b) losses++;
  }
  return {wins,losses,net:wins-losses};
}
assert.equal(report.version,'fred-fixed-nonlinear-v1');
assert.equal(report.promotionAllowed,false);
assert.equal(report.cutoffExclusive,'2024-11-11');
assert.equal(report.samples,1500);
assert.match(report.gitCommit,/^[0-9a-f]{40}$/);
assert.equal(report.contentHash,'4ce352acbb854dd4e7e6d2de67c3426f0f62f360b76cf267559d57df07ea0dd3');
assert.equal(report.barHash,'68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0');
assert.equal(report.macroInput.policy,'fred-known-before-day-v1');
assert.equal(report.macroInput['DFII10.sha256'],'fe6ff2b7f3be1ceefd05d44123e7fcfca3b70715691e4abb9ad622e8dab9a62b');
assert.equal(report.macroInput['DTWEXBGS.sha256'],'7cf7a4344b8007e1de5c5ecc4a5ad45e00e867c92594075b49732605f4f0a730');
assert.deepEqual(report.treeParameters,{numTrees:200,eta:.03,gamma:0,maxDepth:3,minChildWeight:5,
  subsample:.8,featureSubsample:.8,lambda:1,alpha:0,nThread:1,seed:20260901});
assert.deepEqual(Object.keys(report.sourceHashes).sort(),['MacroModelProbe.java','SoftmaxChecks.java','SoftmaxFit.java','TrainingProbe.java']);
for (const [name,hash] of Object.entries(report.sourceHashes)) assert.equal(sha(path.join(__dirname,name)),hash);
assert.equal(sha(path.join(__dirname,'../2026-10-02-macro-model-protocol.md')),report.protocolHash);
assert.equal(report.folds.length,3);
const all=Object.fromEntries(profiles.map(p=>[p,[]]));
for (const [i,fold] of report.folds.entries()) {
  assert.equal(fold.start,['2022-02-02','2023-01-04','2023-12-07'][i]);
  assert.equal(fold.end,['2023-01-03','2023-12-06','2024-11-07'][i]);
  assert.equal(fold.allConverged,true); assert.equal(fold.trainingCount,779+i*240);
  assert.equal(fold.validationCount,240); assert.equal(fold.trainStart,'2019-02-05');
  assert.ok(fold.trainTargetEnd<fold.start);
  assert.deepEqual(fold.comparisons.map(c=>c.profile),profiles);
  const shared=fold.comparisons[0].predictions.map(r=>({date:r.date,target:r.target,actual:r.actual,prior:r.prior}));
  for (const comparison of fold.comparisons) {
    const rows=comparison.predictions, fit=comparison.fit;
    assert.equal(fit.converged,true); assert.equal(fit.status,'CONVERGED');
    assert.equal(fit.weights.length,({BASE_16:34,OHLC_20:42,ALL_36:74})[comparison.profile]);
    assert.ok(fit.trace.at(-1).gradient<=1e-6); assert.equal(comparison.treeRepeatDifference,0);
    for (let j=1;j<fit.trace.length;j++) assert.ok(fit.trace[j].loss<=fit.trace[j-1].loss+1e-12);
    assert.equal(rows.length,240); assert.equal(rows[0].date,fold.start); assert.equal(rows.at(-1).date,fold.end);
    assert.deepEqual(rows.map(r=>({date:r.date,target:r.target,actual:r.actual,prior:r.prior})),shared);
    for (const row of rows) {
      assert.ok(row.date<row.target&&row.target<report.cutoffExclusive);
      const previous=all[comparison.profile].at(-1); if (previous) assert.ok(previous.date<row.date);
      all[comparison.profile].push(row);
    }
    for (const key of ['linear','tree']) {
      check(score(rows,key),comparison[key+'Validation'].all);
      check(score(rows,key,.55),comparison[key+'Validation'].signals);
      assert.equal(comparison[key+'Train'].all.sampleCount,fold.trainingCount);
    }
    const selected=rows.filter(r=>Math.max(...r.tree)>=.55);
    assert.deepEqual(selected.map(r=>r.date),comparison.treeSelectedDates);
    if (selected.length) check(score(selected,'prior'),comparison.priorOnTreeSignals);
    else assert.equal(comparison.priorOnTreeSignals,null);
  }
  check(score(fold.comparisons[0].predictions,'prior'),fold.prior.all);
  check(score(fold.comparisons[0].predictions,'prior',.55),fold.prior.signals);
}
if (process.argv[3]) {
  const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
  for (const key of ['sourceHashes','protocolHash','contentHash','barHash','datasetHash','macroInput','treeParameters','folds'])
    assert.deepEqual(report[key],repeat[key],`重复运行 ${key} 不一致`);
}
const results=profiles.map(profile=>{
  const rows=all[profile], linear=score(rows,'linear'), tree=score(rows,'tree'), prior=score(rows,'prior');
  const signals=score(rows,'tree',.55), selected=rows.filter(r=>Math.max(...r.tree)>=.55), signalPrior=score(selected,'prior');
  assert.equal(rows.length,720);
  // 门槛中的三方向召回按已覆盖信号计算；全样本召回也完整输出。
  const gates={balancedGain:tree.balancedAccuracy>=linear.balancedAccuracy+.02,
    sameDateBaseline:signals.accuracy>signalPrior.accuracy,coverage:signals.coverage>=.30,
    signalRecalls:signals.recalls.every(r=>r!==null&&r>=.25),
    brier:tree.brierScore<=linear.brierScore,logLoss:tree.logLoss<=linear.logLoss,fullBaseline:tree.accuracy>prior.accuracy};
  return {profile,linear,tree,prior,linearSignals:score(rows,'linear',.55),treeSignals:signals,priorOnTreeSignals:signalPrior,
    treeAgainstLinear:paired(rows,'linear','tree'),treeAgainstPrior:paired(rows,'prior','tree'),gates,
    candidate:Object.values(gates).every(Boolean)};
});
console.log(JSON.stringify({status:'PASS',promotionAllowed:false,results},null,2));
