// 独立按冻结标签与逐日概率复算，不训练、不联网、不改结果。
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const dir = new URL('../', import.meta.url);
const text = fs.readFileSync(new URL('2026-10-11-prior-compare-input.json', dir), 'utf8');
const input = JSON.parse(text);
const result = JSON.parse(fs.readFileSync(new URL('2026-10-11-prior-compare-result.json', dir), 'utf8'));
const labels = ['BULLISH', 'NEUTRAL', 'BEARISH'];
const keys = ['bullish', 'neutral', 'bearish'];
const near = (a, b) => assert.ok(Math.abs(a - b) < 0.000051, `${a} != ${b}`);
const best = p => keys.reduce((a, k) => p[k] > p[a] ? k : a, keys[0]);
const direction = p => labels[keys.indexOf(best(p))];
const signalled = p => {
  const max = p[best(p)];
  return max >= 0.55 && keys.filter(k => Math.abs(p[k] - max) <= 0.000001).length === 1;
};
function score(rows, field, signals = false) {
  const matrix = Object.fromEntries(labels.map(a => [a, Object.fromEntries(labels.map(b => [b, 0]))]));
  let correct = 0, covered = 0, brier = 0, loss = 0;
  for (const row of rows) {
    const p = row[field];
    for (const k of keys) assert.ok(Number.isFinite(p[k]) && p[k] >= 0 && p[k] <= 1);
    assert.ok(Math.abs(keys.reduce((s, k) => s + p[k], 0) - 1) < 1e-10);
    for (let j = 0; j < 3; j++) brier += (p[keys[j]] - Number(row.actual === labels[j])) ** 2;
    loss -= Math.log(Math.max(1e-15, p[keys[labels.indexOf(row.actual)]]));
    if (signals && !signalled(p)) continue;
    const d = direction(p);
    covered++;
    matrix[row.actual][d]++;
    correct += Number(row.actual === d);
  }
  const recalls = {};
  for (const label of labels) {
    const n = Object.values(matrix[label]).reduce((a, b) => a + b, 0);
    recalls[label] = n ? Math.round(matrix[label][label] / n * 10000) / 10000 : null;
  }
  return {sampleCount: rows.length, coveredCount: covered, coverage: covered / rows.length,
    accuracy: covered ? correct / covered : 0,
    balancedAccuracy: Object.values(recalls).some(x => x === null) ? null
      : Object.values(recalls).reduce((a, b) => a + b, 0) / 3,
    brierScore: brier / rows.length, logLoss: loss / rows.length, recalls, confusionMatrix: matrix};
}
function matches(actual, expected) {
  for (const k of ['sampleCount', 'coveredCount']) assert.equal(actual[k], expected[k]);
  for (const k of ['accuracy', 'coverage', 'balancedAccuracy', 'brierScore', 'logLoss']) {
    if (actual[k] === null) assert.equal(expected[k], null);
    else near(actual[k], expected[k]);
  }
  for (const label of labels) assert.equal(actual.recalls[label], expected.recalls[label]);
  assert.deepEqual(actual.confusionMatrix, expected.confusionMatrix);
}
assert.equal(crypto.createHash('sha256').update(text).digest('hex'), result.inputHash);
assert.equal(result.holdoutRead, false);
assert.equal(result.promotionAllowed, false);
assert.equal(input.samples.length, 1268);
assert.equal(result.folds.length, 3);
let totalLogistic = 0, totalPrior = 0;
for (let index = 0; index < 3; index++) {
  const fold = result.folds[index], start = 548 + 240 * index;
  const test = input.samples.slice(start, start + 240);
  const train = input.samples.slice(0, start).filter(x => x.targetDate < test[0].asOfDate);
  assert.equal(train.length, fold.trainingCount);
  assert.equal(train.at(-1).targetDate, fold.lastTrainingTarget);
  assert.ok(fold.lastTrainingTarget < fold.start);
  assert.equal(fold.rows.length, 240);
  const counts = Object.fromEntries(labels.map(x => [x, train.filter(y => y.label === x).length]));
  assert.deepEqual(counts, fold.trainingCounts);
  let lOnly = 0, pOnly = 0;
  fold.rows.forEach((row, i) => {
    assert.equal(row.date, test[i].asOfDate);
    assert.equal(row.target, test[i].targetDate);
    assert.equal(row.actual, test[i].label);
    assert.ok(row.target < '2024-11-11');
    keys.forEach((k, j) => assert.ok(Math.abs(row.prior[k] - counts[labels[j]] / train.length) < 1e-12));
    const l = direction(row.logistic) === row.actual, p = direction(row.prior) === row.actual;
    lOnly += Number(l && !p); pOnly += Number(p && !l);
    totalLogistic += Number(l); totalPrior += Number(p);
  });
  assert.equal(lOnly, fold.logisticOnlyHits); assert.equal(pOnly, fold.priorOnlyHits);
  matches(score(fold.rows, 'prior'), fold.priorAll);
  matches(score(fold.rows, 'logistic'), fold.logisticAll);
  matches(score(fold.rows, 'logistic', true), fold.logisticSignals);
  const selected = fold.rows.filter(row => signalled(row.logistic));
  if (selected.length) matches(score(selected, 'prior'), fold.priorOnLogisticSignals);
  else assert.equal(fold.priorOnLogisticSignals, null);
  // one-hot 参照仅按训练期频率的最大类别生成，不利用验证期真实标签。
  const majorityRows = fold.rows.map(row => ({...row,
    majority: Object.fromEntries(keys.map(k => [k, Number(k === best(row.prior))]))}));
  matches(score(majorityRows, 'majority'), fold.majorityAll);
}
console.log(`INDEPENDENT_RECOMPUTATION_PASSED; logisticHits=${totalLogistic}/720; priorHits=${totalPrior}/720; NO_PROMOTION`);
