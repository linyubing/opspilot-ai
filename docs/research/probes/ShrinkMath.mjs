// 一维凸概率收缩；强度只由校准期交叉熵决定，不读取后续验证结果。
import assert from 'node:assert/strict';
export const keys = ['bullish', 'neutral', 'bearish'];
export const labels = ['BULLISH', 'NEUTRAL', 'BEARISH'];
export function mix(model, prior, weight) {
  assert.ok(Number.isFinite(weight) && weight >= 0 && weight <= 1);
  for (const p of [model, prior]) {
    assert.ok(keys.every(k => Number.isFinite(p[k]) && p[k] >= 0 && p[k] <= 1));
    assert.ok(Math.abs(keys.reduce((sum, k) => sum + p[k], 0) - 1) < 1e-10);
  }
  return Object.fromEntries(keys.map(k => [k, (1 - weight) * model[k] + weight * prior[k]]));
}
export function loss(rows, weight) {
  assert.ok(rows.length > 0);
  return rows.reduce((sum, row) => {
    const key = keys[labels.indexOf(row.actual)];
    assert.ok(key);
    const p = mix(row.model, row.prior, weight)[key];
    assert.ok(p > 0, '真实类别零概率，停止而非猜测或补值');
    return sum - Math.log(p);
  }, 0) / rows.length;
}
export function slope(rows, weight) {
  return rows.reduce((sum, row) => {
    const key = keys[labels.indexOf(row.actual)];
    assert.ok(key);
    const p = mix(row.model, row.prior, weight)[key];
    assert.ok(p > 0);
    return sum + (row.model[key] - row.prior[key]) / p;
  }, 0) / rows.length;
}
export function fit(rows) {
  assert.ok(rows.length > 0);
  // 边界最优直接保留；内部用固定80次二分，不搜索验证期最优权重。
  if (slope(rows, 0) >= 0) return 0;
  if (slope(rows, 1) <= 0) return 1;
  let low = 0, high = 1;
  for (let i = 0; i < 80; i++) {
    const middle = (low + high) / 2;
    if (slope(rows, middle) < 0) low = middle;
    else high = middle;
  }
  return (low + high) / 2;
}
