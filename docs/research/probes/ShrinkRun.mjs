// 只读预登记的拟合输出，权重仅从校准期计算，不写生产数据库。
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {keys, labels, mix, fit, slope, loss} from './ShrinkMath.mjs';
const dir = new URL('../', import.meta.url);
const text = fs.readFileSync(new URL('2026-10-11-calibration-data.json', dir), 'utf8');
const data = JSON.parse(text);
const input = JSON.parse(fs.readFileSync(new URL('2026-10-11-prior-compare-input.json', dir), 'utf8'));
assert.equal(data.datasetHash, 'fbca9db1f618ee5f7b144c6f32ab155352f3a5dd4d8de7eece854602409fa68d');
assert.equal(data.folds.length, 3);
const best = p => keys.reduce((a, k) => p[k] > p[a] ? k : a, keys[0]);
function score(rows, field) {
  let hit = 0, brier = 0, logLoss = 0;
  const matrix = Object.fromEntries(labels.map(a => [a, Object.fromEntries(labels.map(b => [b, 0]))]));
  for (const row of rows) {
    const p = row[field], d = labels[keys.indexOf(best(p))];
    mix(p, p, 0);
    hit += Number(d === row.actual); matrix[row.actual][d]++;
    keys.forEach((k, i) => brier += (p[k] - Number(labels[i] === row.actual)) ** 2);
    logLoss -= Math.log(Math.max(1e-15, p[keys[labels.indexOf(row.actual)]]));
  }
  const recalls = Object.fromEntries(labels.map(a => {
    const count = Object.values(matrix[a]).reduce((a, b) => a + b, 0);
    return [a, count ? matrix[a][a] / count : null];
  }));
  return {sampleCount: rows.length, hits: hit, accuracy: rows.length ? hit / rows.length : null,
    balancedAccuracy: Object.values(recalls).includes(null) ? null : Object.values(recalls).reduce((a, b) => a + b, 0) / 3,
    brier: rows.length ? brier / rows.length : null, logLoss: rows.length ? logLoss / rows.length : null,
    recalls, matrix};
}
function signal(p) {
  const max = p[best(p)];
  return max >= .55 && keys.filter(k => Math.abs(p[k] - max) <= 1e-6).length === 1;
}
const folds = data.folds.map((fold, index) => {
  const start = 548 + 240 * index;
  const outer = input.samples.slice(0, start).filter(x => x.targetDate < input.samples[start].asOfDate);
  const cal = outer.slice(-120), train = outer.slice(0, -120).filter(x => x.targetDate < cal[0].asOfDate);
  assert.equal(train.length, fold.fitCount);
  assert.equal(train.at(-1).targetDate, fold.fitTargetEnd);
  for (const [rows, expected] of [[fold.calibration, cal], [fold.validation, input.samples.slice(start, start + 240)]]) {
    assert.equal(rows.length, expected.length);
    rows.forEach((row, i) => {
      assert.equal(row.date, expected[i].asOfDate); assert.equal(row.target, expected[i].targetDate);
      assert.equal(row.actual, expected[i].label);
      keys.forEach((k, j) => assert.ok(Math.abs(row.prior[k] - train.filter(x => x.label === labels[j]).length / train.length) < 1e-12));
    });
  }
  const weight = fit(fold.calibration);
  const rows = fold.validation.map(row => ({...row, shrunk: mix(row.model, row.prior, weight)}));
  const selected = rows.filter(row => signal(row.shrunk));
  const result = {start: rows[0].date, end: rows.at(-1).date, fitCount: fold.fitCount, weight,
    calibrationSlope: slope(fold.calibration, weight), calibrationLoss: loss(fold.calibration, weight),
    model: score(rows, 'model'), prior: score(rows, 'prior'), shrunk: score(rows, 'shrunk'),
    coverage: selected.length / rows.length, signal: score(selected, 'shrunk'),
    priorOnSignals: score(selected, 'prior'), rows};
  console.log(JSON.stringify({start: result.start, weight, model: result.model.accuracy,
    shrunk: result.shrunk.accuracy, prior: result.prior.accuracy}));
  return result;
});
const result = {version: 'gold-training-only-shrink-v1', sourceCommit: data.gitCommit,
  rawInputHash: crypto.createHash('sha256').update(text).digest('hex'), createdAt: new Date().toISOString(),
  holdoutRead: false, promotionAllowed: false, folds};
fs.writeFileSync(new URL('2026-10-11-calibration-result.json', dir), JSON.stringify(result, null, 2) + '\n', {flag: 'wx'});
