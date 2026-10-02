// 独立复算研究 JSON：不调用 Java 指标实现，不访问数据库，不生成行情。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const file = process.argv[2];
if (!file) throw new Error('需要研究结果 JSON 路径');
const report = JSON.parse(fs.readFileSync(file, 'utf8'));
const names = ['BULLISH', 'NEUTRAL', 'BEARISH'];
const near = (a, b) => assert.ok(Math.abs(a - b) <= .000051, `${a} != ${b}`);
const best = p => p.indexOf(Math.max(...p));
function score(rows, key, threshold = 0) {
  const matrix = Array.from({length: 3}, () => [0,0,0]);
  let correct = 0, covered = 0, brier = 0, logLoss = 0;
  for (const row of rows) {
    const p = row[key], actual = names.indexOf(row.actual);
    assert.equal(p.length, 3); assert.ok(actual >= 0);
    assert.ok(p.every(v => Number.isFinite(v) && v >= 0 && v <= 1));
    assert.ok(Math.abs(p.reduce((a,b) => a+b, 0) - 1) <= 1e-10);
    const direction = best(p);
    if (p[direction] >= threshold) {
      covered++; matrix[actual][direction]++;
      if (direction === actual) correct++;
    }
    brier += p.reduce((s,v,i) => s + (v - (i === actual ? 1 : 0)) ** 2, 0);
    logLoss -= Math.log(Math.max(p[actual], 1e-15));
  }
  const recalls = matrix.map((row,i) => row.reduce((a,b)=>a+b,0) ? row[i] / row.reduce((a,b)=>a+b,0) : null);
  return {samples:rows.length, correct, covered, coverage:covered/rows.length,
    accuracy:covered ? correct/covered : 0,
    balancedAccuracy:recalls.includes(null) ? null : recalls.reduce((a,b)=>a+b)/3,
    brierScore:brier/rows.length, logLoss:logLoss/rows.length, recalls, matrix};
}
function check(actual, expected) {
  assert.equal(actual.samples, expected.sampleCount); assert.equal(actual.covered, expected.coveredCount);
  for (const field of ['coverage','accuracy','brierScore','logLoss']) near(actual[field], expected[field]);
  if (actual.balancedAccuracy === null) assert.equal(expected.balancedAccuracy, null);
  else {
    // 产品先将每类召回四舍五入到四位，再求平均；独立汇总仍保留未舍入值。
    const roundedMean = actual.recalls.reduce((sum,v) => sum + Math.round(v*10000)/10000,0)/3;
    near(roundedMean, expected.balancedAccuracy);
  }
  for (let c = 0; c < 3; c++) {
    if (actual.recalls[c] === null) assert.equal(expected.recalls[names[c]], null);
    else near(actual.recalls[c], expected.recalls[names[c]]);
    for (let d = 0; d < 3; d++) assert.equal(actual.matrix[c][d], expected.confusionMatrix[names[c]][names[d]]);
  }
}
assert.equal(report.promotionAllowed, false);
assert.equal(report.folds.length, 3);
assert.equal(report.cutoffExclusive, '2024-11-11');
for (const [name,hash] of Object.entries(report.sourceHashes)) {
  const digest = crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname,name))).digest('hex');
  assert.equal(digest,hash,`${name} 来源摘要不同`);
}
const all = [];
for (const fold of report.folds) {
  assert.equal(fold.fit.converged,true);
  assert.ok(fold.fit.trace.at(-1).gradient <= 1e-6);
  assert.equal(fold.baselineMaxDifference,0);
  for (let i = 1; i < fold.fit.trace.length; i++) assert.ok(fold.fit.trace[i].loss <= fold.fit.trace[i-1].loss + 1e-12);
  assert.ok(fold.trainTargetEnd < fold.start);
  assert.equal(fold.predictions.length,240);
  assert.equal(fold.predictions[0].date,fold.start); assert.equal(fold.predictions.at(-1).date,fold.end);
  for (const row of fold.predictions) {
    assert.ok(row.date < row.target && row.target < report.cutoffExclusive);
    if (all.length) assert.ok(all.at(-1).date < row.date);
    all.push(row);
  }
  for (const key of ['baseline','reference','prior']) {
    check(score(fold.predictions,key),fold[key+'Validation'].all);
    check(score(fold.predictions,key,.55),fold[key+'Validation'].signals);
  }
  const selected = fold.predictions.filter(row => Math.max(...row.reference) >= .55);
  assert.deepEqual(selected.map(row=>row.date),fold.referenceSelectedDates);
  if (selected.length) check(score(selected,'prior'),fold.priorOnReferenceSignals);
}
const selected = all.filter(row=>Math.max(...row.reference)>=.55);
let wins=0,losses=0;
for (const row of all) {
  const actual=names.indexOf(row.actual), old=best(row.baseline)===actual, improved=best(row.reference)===actual;
  if (!old && improved) wins++;
  if (old && !improved) losses++;
}
if (process.argv[3]) {
  const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
  assert.deepEqual(report.folds,repeat.folds,'重复运行不一致');
}
console.log(JSON.stringify({status:'PASS',samples:all.length,
  baseline:score(all,'baseline'),reference:score(all,'reference'),prior:score(all,'prior'),
  referenceSignals:score(all,'reference',.55),priorOnReferenceSignals:score(selected,'prior'),
  paired:{wins,losses,net:wins-losses}},null,2));
