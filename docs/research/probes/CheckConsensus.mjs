import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

// 只核验本次公开页面的固定CPI行；不是通用爬虫或历史PIT适配器。
const [source, output] = process.argv.slice(2);
assert.ok(source && output && !fs.existsSync(output));
const receipt = JSON.parse(fs.readFileSync(source, 'utf8'));
const sha = text => createHash('sha256').update(text).digest('hex');
function extract(r) {
  assert.equal(r.httpStatus, 200);
  assert.equal(r.sourceUrl, 'https://tradingeconomics.com/united-states/calendar');
  assert.equal(sha(r.rawResponse), r.rawSha256);
  assert.ok(Date.parse(r.startedAt)<=Date.parse(r.completedAt));
  const html = r.rawResponse;
  const tz = html.match(/<select\b[^>]*id="DropDownListTimezone"[^>]*>([\s\S]*?)<\/select>/);
  assert.ok(tz);
  const selected = [...tz[1].matchAll(/<option\b[^>]*selected="selected"[^>]*>/g)];
  assert.equal(selected.length, 1); assert.match(selected[0][0], /value="0"/);
  // 页面外层事件行可能包含嵌套国家表，不能截到第一个内部 </tr>。
  const rows = [...html.matchAll(/<tr\b[^>]*data-url="[^"]+"[\s\S]*?(?=<tr\b[^>]*data-url="|$)/g)]
    .map(m=>m[0]).filter(row=>/data-id="398220"/.test(row));
  assert.equal(rows.length, 1);
  const row = rows[0];
  assert.match(row, /data-country="united states"/);assert.match(row, /data-event="cpi"/);
  assert.match(row, /class='\s*2026-10-14'/);assert.match(row, /12:30 PM/);
  assert.match(row, /calendar-reference">SEP<\/span>/);
  function field(id) {
    const parts = [...row.matchAll(new RegExp(`<([a-z]+)\\b[^>]*id='${id}'[^>]*>([\\s\\S]*?)<\\/\\1>`, 'g'))];
    assert.equal(parts.length, 1);
    assert.ok(!parts[0][2].includes('<'));
    return parts[0][2].trim();
  }
  assert.equal(field('actual'), '');
  const releaseAt = '2026-10-14T12:30:00Z';
  const targetStart = '2026-10-13T20:00:00Z';
  assert.ok(Date.parse(r.completedAt)<Date.parse(targetStart));
  assert.ok(Date.parse(targetStart)<Date.parse(releaseAt));
  for(const key of ['previous','consensus','forecast'])assert.match(field(key), /^\d+(\.\d+)?$/);
  return { sourceUrl:r.sourceUrl, sourceSha256:r.rawSha256, observedAt:r.completedAt,
    eventId:'398220', event:'US CPI index', reference:'2026-09', timezone:'UTC',
    plannedReleaseAt:releaseAt, candidateTargetStart:targetStart,
    previous:field('previous'), consensus:field('consensus'), providerProjection:field('forecast'),
    actual:null, surprise:null, currentSnapshotVerified:true, historicalPitVerified:false,
    forecastGenerated:false, accuracyImprovementProven:false, researchOnly:true };
}
const record = extract(receipt);
// 损坏副本只用于拒绝路径测试，不作为行情或共识记录归档。
for(const mutate of [
  r=>r.rawSha256='0'.repeat(64),
  r=>{r.rawResponse=r.rawResponse.replace(/(data-id="398220"[\s\S]*?id='actual'>)<\/span>/,(_,prefix)=>prefix+'1</span>');r.rawSha256=sha(r.rawResponse);},
  r=>{r.rawResponse=r.rawResponse.replace('selected="selected" value="0"','selected="selected" value="60"');r.rawSha256=sha(r.rawResponse);},
  r=>r.completedAt='2026-10-14T13:00:00Z',
]) {
  const changed=structuredClone(receipt);mutate(changed);assert.throws(()=>extract(changed));
}
fs.writeFileSync(output, JSON.stringify(record,null,2)+'\n', {flag:'wx'});
console.log(JSON.stringify({record, rejectionChecksPassed:4}));
