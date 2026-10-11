// 仅为概率数学夹具，不代表真实行情、新闻或实验成绩。
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {fit, loss, mix, slope} from './ShrinkMath.mjs';
const model = {bullish: .9, neutral: .1, bearish: 0};
const prior = {bullish: .5, neutral: .5, bearish: 0};
const row = actual => ({actual, model, prior});
test('边界权重保留原概率', () => {
  assert.deepEqual(mix(model, prior, 0), model);
  assert.deepEqual(mix(model, prior, 1), prior);
});
test('模型与基线边界最优', () => {
  assert.equal(fit([row('BULLISH')]), 0);
  assert.equal(fit([row('NEUTRAL')]), 1);
});
test('内部凸最优及差分导数', () => {
  const rows = [row('BULLISH'), row('BULLISH'), row('BULLISH'), row('NEUTRAL')];
  assert.ok(Math.abs(fit(rows) - .375) < 1e-12);
  const w = .2, step = 1e-6;
  assert.ok(Math.abs(slope(rows, w) - (loss(rows, w + step) - loss(rows, w - step)) / (2 * step)) < 1e-8);
});
test('拒绝非法概率与空校准集', () => {
  assert.throws(() => mix({...model, bullish: NaN}, prior, .5));
  assert.throws(() => mix({...model, bullish: .8}, prior, .5));
  assert.throws(() => mix(model, prior, -1));
  assert.throws(() => fit([]));
});
