import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

// 仅审计版本和样本日期覆盖；不读取标签字段、不训练或评分。
const [receiptPath, inputPath, archivePath, resultPath] = process.argv.slice(2);
assert.ok(!fs.existsSync(archivePath) && !fs.existsSync(resultPath));
const receiptRaw = fs.readFileSync(receiptPath);
const source = JSON.parse(receiptRaw);
const sha = raw => createHash('sha256').update(raw).digest('hex');
for (const r of source.receipts) {
  assert.equal(r.error, undefined); assert.equal(r.httpStatus, 200);
  assert.equal(sha(r.rawResponse), r.rawSha256);
  assert.deepEqual(JSON.parse(r.rawResponse), r.response);
  assert.equal(r.response.output_type, 1); assert.equal(r.response.offset, 0);
  assert.equal(r.response.count, r.response.observations.length);
  assert.equal(r.request.series_id, 'CPIAUCNS');
}
assert.equal(source.receipts.length, 4);
const history = source.receipts.filter(r => r.name.startsWith('history'));
const rows = history.flatMap(r => r.response.observations);
const groups = new Map();
for (const r of history) for (const o of r.response.observations) {
  assert.ok(o.date >= '2016-01-01' && o.date <= '2024-11-08');
  assert.ok(o.realtime_start >= r.request.realtime_start && o.realtime_end <= r.request.realtime_end);
  assert.ok(o.realtime_start <= o.realtime_end);
  assert.ok(o.value === '.' || (Number.isFinite(Number(o.value)) && Number(o.value) > 0));
  if (!groups.has(o.date)) groups.set(o.date, []);
  groups.get(o.date).push(o);
}
for (const versions of groups.values()) {
  versions.sort((a, b) => a.realtime_start.localeCompare(b.realtime_start));
  for (let i = 1; i < versions.length; i++) assert.ok(versions[i].realtime_start > versions[i - 1].realtime_end);
}
const known = cutoff => [...groups].map(([date, versions]) => ({ date,
  version: versions.find(o => o.realtime_start <= cutoff && cutoff <= o.realtime_end) }))
  .filter(o => o.date <= cutoff && o.version && o.version.value !== '.')
  .sort((a, b) => b.date.localeCompare(a.date));
const previousDay = day => new Date(Date.parse(day + 'T00:00:00Z') - 86400000).toISOString().slice(0, 10);
const monthShift = (month, shift) => {
  const day = new Date(month + 'T00:00:00Z');
  day.setUTCMonth(day.getUTCMonth() + shift);
  return day.toISOString().slice(0, 10);
};
for (const name of ['before', 'release']) {
  const receipt = source.receipts.find(r => r.name === name);
  const selected = known(receipt.request.realtime_start);
  const snapshot = receipt.response.observations.filter(o => o.value !== '.');
  assert.equal(selected.length, snapshot.length);
  for (const o of snapshot) assert.equal(selected.find(s => s.date === o.date)?.version.value, o.value);
}
assert.equal(known('2024-10-09').some(o => o.date === '2024-09-01'), false);
assert.equal(known('2024-10-10')[0].date, '2024-09-01');
assert.equal(known(previousDay('2024-10-10'))[0].date, '2024-08-01');
assert.equal(known(previousDay('2024-10-11'))[0].date, '2024-09-01');
const inputRaw = fs.readFileSync(inputPath);
assert.equal(sha(inputRaw), '0e72f54e73b78efb0c12c3a5c474976afadb4c163aba732f9c39e9b6b124d936');
const dates = JSON.parse(inputRaw).samples.map(s => s.asOfDate);
assert.equal(dates.length, 1268);
let covered = 0;
const missingDates = [], months = new Set();
for (const date of dates) {
  const selected = known(previousDay(date));
  const latest = selected[0];
  if (!latest) { missingDates.push(date); continue; }
  const prior = selected.find(s => s.date === monthShift(latest.date, -1));
  const year = selected.find(s => s.date === monthShift(latest.date, -12));
  if (!prior || !year) { missingDates.push(date); continue; }
  // 只检查实际通胀表达可计算，不将其冒充发布前共识差。
  assert.ok(Number.isFinite((Number(latest.version.value) / Number(year.version.value) - 1) * 100));
  assert.ok(Number.isFinite((Number(latest.version.value) / Number(prior.version.value) - 1) * 100));
  covered++; months.add(latest.date);
}
const archive = { series: 'CPIAUCNS', fetchedAt: source.receipts.at(-1).completedAt,
  outputType: 1, observationStart: '2016-01-01', observationEnd: '2024-11-08',
  realtimeStart: '2018-01-01', realtimeEnd: '2024-11-08', count: rows.length,
  chunks: history.map(r => ({ start: r.request.realtime_start, end: r.request.realtime_end, count: r.response.count })),
  observations: rows };
const archiveText = JSON.stringify(archive, null, 2) + '\n';
fs.writeFileSync(archivePath, archiveText, { flag: 'wx' });
const result = { series: 'CPIAUCNS', sourceSha256: sha(receiptRaw), archiveSha256: sha(archiveText),
  frozenInputSha256: sha(inputRaw), versionRows: rows.length, sampleCount: dates.length, covered,
  missingDates, distinctObservationMonths: months.size,
  releaseDayExcluded: true, followingDayAvailable: true, independentSnapshotsMatched: true,
  availabilityPolicy: 'fred-known-before-day-v1', intradayPublicationProven: false,
  consensusAvailable: false, trainingPerformed: false, probabilityOrAccuracyComputed: false,
  holdoutRead: false, productionChanged: false };
fs.writeFileSync(resultPath, JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(result));
