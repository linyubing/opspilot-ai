const { test } = require('node:test');
const assert = require('node:assert/strict');
const { recent, group, score, age } = require('./InfoMath.cjs');
// 纯数学版本夹具：捕捉当日泄漏、旧版本回填及时间边界错误，不是行情。
const source = {
  observationStart: '2020-01-01', observationEnd: '2020-01-31',
  realtimeStart: '2020-01-01', realtimeEnd: '2020-01-31',
  observations: [
    { date: '2020-01-08', realtime_start: '2020-01-08', realtime_end: '2020-01-09', value: '2.00' },
    { date: '2020-01-08', realtime_start: '2020-01-10', realtime_end: '2020-01-31', value: '9.00' },
    { date: '2020-01-07', realtime_start: '2020-01-07', realtime_end: '2020-01-08', value: '1.00' },
    { date: '2020-01-07', realtime_start: '2020-01-09', realtime_end: '2020-01-31', value: '.' },
    { date: '2020-01-06', realtime_start: '2020-01-06', realtime_end: '2020-01-31', value: '0.25' },
    { date: '2020-01-11', realtime_start: '2020-01-08', realtime_end: '2020-01-31', value: '8.00' }
  ]
};
test('截止日版本可用，预测当日的新版本不可用，未来观测不可用', () => {
  const rows = recent(source, '2020-01-10');
  assert.deepEqual(rows.map(r => [r.date, r.value, r.realtime_start]),
    [['2020-01-08', '2.00', '2020-01-08'], ['2020-01-06', '0.25', '2020-01-06']]);
});
test('有效版本缺失不回填同一观测日期的旧值', () => {
  assert.equal(recent(source, '2020-01-10').some(r => r.date === '2020-01-07'), false);
});
test('版本结束日包含在有效区间，下一日使用新版本', () => {
  assert.deepEqual(recent(source, '2020-01-11').map(r => r.value), ['9.00', '0.25']);
});
test('窗口数量限制、来源范围外拒绝，不足数量保留真实条数', () => {
  assert.equal(recent(source, '2020-01-10', 1).length, 1);
  assert.equal(recent(source, '2020-01-10', 21).length, 2);
  assert.deepEqual(recent(source, '2020-01-01'), []);
  assert.throws(() => recent(source, '2020-02-02'));
});
test('窗口完整性优先于观测日龄，年龄边界互斥', () => {
  const cases = [[0, null, 'NO_OBSERVATION'], [20, 1, 'UNDER_21'],
    [21, 1, 'AGE_1_3'], [21, 3, 'AGE_1_3'], [21, 4, 'AGE_4_7'],
    [21, 7, 'AGE_4_7'], [21, 8, 'AGE_OVER_7']];
  for (const [count, age, want] of cases) assert.equal(group(count, age), want);
  assert.throws(() => group(21, 0));
  assert.throws(() => group(22, 1));
});
test('自然日龄跨周末计算，不当成交易日或版本发布时间', () => {
  assert.equal(age('2020-01-13', '2020-01-10'), 3);
  assert.throws(() => age('2020-02-30', '2020-01-10'));
});
test('从逐条概率复算命中、混淆矩阵和概率误差，不把总体命中率冒充涨跌召回', () => {
  const rows = [
    { actual: 'BULLISH', candidate: [.8, .1, .1] },
    { actual: 'NEUTRAL', candidate: [.1, .8, .1] },
    { actual: 'BEARISH', candidate: [.1, .8, .1] }
  ];
  const result = score(rows, 'candidate');
  assert.equal(result.correct, 2); assert.equal(result.errors, 1);
  assert.deepEqual(result.matrix, [[1,0,0],[0,1,0],[0,1,0]]);
  assert.deepEqual(result.recalls, [1,1,0]);
  assert.equal(result.accuracy, 2/3);
  assert.ok(Math.abs(result.brierScore - .5266666666666667) < 1e-12);
  assert.ok(Math.abs(result.logLoss - .916290731874155) < 1e-12);
});
test('无样本指标保持null；缺少类别不能用零召回伪造平衡准确率', () => {
  assert.equal(score([], 'candidate').accuracy, null);
  assert.equal(score([{actual:'NEUTRAL', candidate:[.1,.8,.1]}], 'candidate').balancedAccuracy, null);
  assert.throws(() => score([{actual:'NEUTRAL', candidate:[.1,.8,.9]}], 'candidate'));
});
