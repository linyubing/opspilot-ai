// 独立宏观字段和概率混合复算，不调用Java生产特征或评分实现。
const names = ['dollar_age','dollar_return_1','dollar_return_20','dollar_return_5',
  'real_rate','real_rate_age','real_rate_bp_1','real_rate_bp_20','real_rate_bp_5'];
const assert = require('node:assert/strict'), { age } = require('./InfoMath.cjs');
// 十进制转为整数分数，避免二进制浮点在HALF_UP边界舍入错误。
function decimal(value) {
  assert.ok(typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value));
  const scale = (value.split('.')[1] || '').length;
  return [BigInt(value.replace('.', '')), 10n ** BigInt(scale)];
}
function rounded(num, den) {
  assert.ok(den > 0n);
  const sign = num < 0n ? -1n : 1n, abs = num * sign;
  return sign * (abs / den + (abs % den * 2n >= den ? 1n : 0n));
}
function change(a, b, rate) {
  const [x, xs] = decimal(a), [y, ys] = decimal(b);
  const diff = x * ys - y * xs, denom = xs * ys;
  if (rate) {
    const six = rounded(diff * 1000000n, denom);
    return Number(rounded(six, 100n)) / 100;
  }
  assert.ok(y > 0n && x > 0n);
  return Number(rounded(diff * 1000000n, xs * y)) / 10000;
}
function vector(day, rates, dollars) {
  for (const rows of [rates, dollars]) {
    assert.ok(Array.isArray(rows) && rows.length >= 21);
    for (const row of rows.slice(0,21)) {
      decimal(row.value); assert.ok(Number.isFinite(Number(row.value)));
      assert.ok(age(day,row.date) >= 1);
    }
  }
  const dollar = n => change(dollars[0].value,dollars[n].value,false);
  const bp = n => change(rates[0].value,rates[n].value,true);
  return [age(day,dollars[0].date),dollar(1),dollar(20),dollar(5),
    Number(rates[0].value),age(day,rates[0].date),bp(1),bp(20),bp(5)];
}
function mix(price, macro) {
  for (const p of [price,macro]) {
    assert.ok(Array.isArray(p) && p.length === 3 && p.every(v => Number.isFinite(v) && v>=0 && v<=1));
    assert.ok(Math.abs(p.reduce((a,b)=>a+b,0)-1) <= 1e-10);
  }
  return price.map((v,c)=>(v+macro[c])/2);
}
module.exports = { names, vector, mix };
