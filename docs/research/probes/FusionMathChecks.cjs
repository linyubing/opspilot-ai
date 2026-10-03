const { test } = require('node:test'), assert = require('node:assert/strict');
const { vector, mix } = require('./FusionMath.cjs');
// 数学版本窗口仅验证公式，不代表真实黄金或宏观数据。
const rates = Array.from({length:21}, (_,i) => ({ date:'2020-01-31', value:(2-i/100).toFixed(2) }));
const dollars = Array.from({length:21}, (_,i) => ({ date:'2020-01-31', value:String(120-i) }));
test('独立九维宏观公式遵守单位、日龄和四位小数，不读取黄金字段', () => {
  assert.deepEqual(vector('2020-02-02',rates,dollars), [2,.8403,20,4.3478,2,2,1,20,5]);
});
test('不完整真实窗口拒绝，不能补零产生合法九维样本', () => {
  assert.throws(() => vector('2020-02-02',rates.slice(1),dollars));
});
test('独立等权概率混合，不退化为价格单模型', () => {
  assert.deepEqual(mix([.7,.2,.1],[.1,.2,.7]), [.39999999999999997,.2,.39999999999999997]);
  assert.throws(() => mix([.7,.2,.1], [0,0,0]));
});
