// 研究用的独立版本重选器；与Java生产加载器分开实现，不使用采集时间选版本。
const assert = require('node:assert/strict'), crypto = require('node:crypto');
const indexes = new WeakMap();
function time(day) {
  assert.match(day, /^\d{4}-\d{2}-\d{2}$/);
  const value = Date.parse(day + 'T00:00:00Z');
  assert.ok(Number.isFinite(value));
  assert.equal(new Date(value).toISOString().slice(0, 10), day);
  return value;
}
function age(day, observation) { return (time(day) - time(observation)) / 86400000; }
function recent(source, day, limit = 21) {
  assert.ok(Number.isInteger(limit) && limit > 0);
  const cutoff = new Date(time(day) - 86400000).toISOString().slice(0, 10);
  assert.ok(cutoff <= source.realtimeEnd && cutoff <= source.observationEnd, '来源未覆盖截止日');
  if (cutoff < source.realtimeStart || cutoff < source.observationStart
      || (source.firstAvailableVintage && cutoff < source.firstAvailableVintage)) return [];
  if (!indexes.has(source)) {
    const byDate = new Map();
    for (const row of source.observations) {
      for (const key of ['date', 'realtime_start', 'realtime_end']) time(row[key]);
      assert.ok(row.realtime_start <= row.realtime_end);
      assert.ok(row.date >= source.observationStart && row.date <= source.observationEnd);
      assert.ok(row.value === '.' || (typeof row.value === 'string'
        && /^-?\d+(\.\d+)?$/.test(row.value) && Number.isFinite(Number(row.value))));
      if (!byDate.has(row.date)) byDate.set(row.date, []);
      byDate.get(row.date).push(row);
    }
    for (const versions of byDate.values()) {
      versions.sort((a, b) => a.realtime_start.localeCompare(b.realtime_start));
      for (let i = 1; i < versions.length; i++)
        assert.ok(versions[i - 1].realtime_end < versions[i].realtime_start, '版本区间重叠');
    }
    indexes.set(source, [...byDate].sort(([a], [b]) => b.localeCompare(a)));
  }
  const result = [];
  for (const [date, versions] of indexes.get(source)) {
    if (date > cutoff) continue;
    const version = versions.find(r => r.realtime_start <= cutoff && r.realtime_end >= cutoff);
    if (version && version.value !== '.') result.push(version);
    if (result.length === limit) break;
  }
  return result;
}
function group(count, days) {
  assert.ok(Number.isInteger(count) && count >= 0 && count <= 21);
  if (count === 0) { assert.equal(days, null); return 'NO_OBSERVATION'; }
  assert.ok(Number.isInteger(days) && days >= 1);
  if (count < 21) return 'UNDER_21';
  return days <= 3 ? 'AGE_1_3' : days <= 7 ? 'AGE_4_7' : 'AGE_OVER_7';
}
function uuid(series, row) {
  // Java nameUUIDFromBytes采用MD5版本3；这是窗口标识绑定，不是安全凭据。
  const bytes = crypto.createHash('md5').update(`${series}/${row.date}/${row.realtime_start}`).digest();
  bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128;
  const s = bytes.toString('hex');
  return `${s.slice(0,8)}-${s.slice(8,12)}-${s.slice(12,16)}-${s.slice(16,20)}-${s.slice(20)}`;
}
function hashWindow(series, rows) {
  return crypto.createHash('sha256').update(rows.map(r => `${r.date}|${uuid(series, r)}|${r.value}\n`).join('')).digest('hex');
}
function score(rows, key) {
  const labels = ['BULLISH', 'NEUTRAL', 'BEARISH'], matrix = Array.from({length:3}, () => [0,0,0]);
  let correct = 0, brier = 0, loss = 0;
  for (const row of rows) {
    const p = row[key], actual = labels.indexOf(row.actual);
    assert.ok(actual >= 0 && Array.isArray(p) && p.length === 3);
    assert.ok(p.every(v => Number.isFinite(v) && v >= 0 && v <= 1));
    assert.ok(Math.abs(p.reduce((a,b) => a+b, 0) - 1) <= 1e-6);
    const predicted = p.indexOf(Math.max(...p)); matrix[actual][predicted]++;
    if (actual === predicted) correct++;
    brier += p.reduce((s,v,c) => s+(v-(c===actual?1:0))**2, 0);
    loss -= Math.log(Math.max(p[actual], 1e-15));
  }
  const recalls = matrix.map((r,c) => r.reduce((a,b) => a+b, 0) ? r[c]/r.reduce((a,b) => a+b, 0) : null);
  return { count:rows.length, correct, errors:rows.length-correct, accuracy:rows.length?correct/rows.length:null,
    balancedAccuracy:recalls.includes(null)?null:recalls.reduce((a,b) => a+b, 0)/3,
    brierScore:rows.length?brier/rows.length:null, logLoss:rows.length?loss/rows.length:null, recalls, matrix };
}
module.exports = { recent, group, age, uuid, hashWindow, score };
