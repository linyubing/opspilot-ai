import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

// 独立使用整数十进制复核收盘分类，避免将Java自身输出当独立证据。
const [source, output] = process.argv.slice(2);
const raw = readFileSync(source);
const b = JSON.parse(raw);
const result = JSON.parse(readFileSync(output, 'utf8'));
assert.equal(result.sourceSha256, createHash('sha256').update(raw).digest('hex'));
const f = b.forecast;
const base = b.receipts[1].response.values[0];
const day = b.receipts[2].response.values[0];
const quote = b.receipts[0].response;
function decimal(value) {
  const text = String(value);
  assert.match(text, /^\d+(\.\d+)?$/);
  const [whole, fraction = ''] = text.split('.');
  assert.ok(fraction.length <= 8);
  return BigInt(whole + fraction.padEnd(8, '0'));
}
const bp = decimal(base.close);
assert.equal(bp, decimal(f.base_price));
const tp = decimal(day.close);
const change = tp - bp;
// percent > 0.5 等价于 200*(target-base) > base，不做浮点舍入。
const actual = 200n * change > bp ? 'bullish' : 200n * change < -bp ? 'bearish' : 'neutral';
assert.equal(actual, result.actual);
assert.equal(result.hit, actual === f.predicted_direction);
assert.equal(result.fixedNeutralBaselineHit, actual === 'neutral');
assert.ok(Math.abs(Number(change) / Number(bp) * 100 - result.returnPercent) < 1e-10);
assert.equal(result.predicted, f.predicted_direction);
assert.ok(Date.parse(f.created_at) < Date.parse(f.target_start));
assert.ok(b.receipts.every(r => Date.parse(r.startedAt) >= Date.parse(f.target_end)));
const hours = [...b.receipts[3].response.values].sort((a, b) => a.datetime.localeCompare(b.datetime));
const start = Date.parse(f.target_start);
const end = Date.parse(f.target_end);
assert.equal(hours.length, (end - start) / 3600000);
hours.forEach((h, i) => assert.equal(Date.parse(h.datetime.replace(' ', 'T') + 'Z'), start + i * 3600000));
const max = (a, x) => a > x ? a : x;
const min = (a, x) => a < x ? a : x;
assert.equal(decimal(day.open), decimal(hours[0].open));
assert.equal(decimal(day.close), decimal(hours.at(-1).close));
assert.equal(decimal(day.high), hours.map(h => decimal(h.high)).reduce(max));
assert.equal(decimal(day.low), hours.map(h => decimal(h.low)).reduce(min));
for (const name of ['open', 'high', 'low', 'close']) assert.equal(decimal(quote[name]), decimal(day[name]));
assert.equal(result.sessionCheck.status, 'MATCHED');
assert.equal(result.officialSessionCertified, false);
assert.equal(result.accuracyImprovementProven, false);
console.log('独立验收通过：事前发布时间、24小时覆盖、原始十进制OHLC、下跌分类和未命中结果一致');
