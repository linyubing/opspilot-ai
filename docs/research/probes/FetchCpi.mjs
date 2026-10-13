import assert from 'node:assert/strict';
import { existsSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

// 研究用只读可得性探针，不注册应用组件，不写行情或模型数据库。
const [output] = process.argv.slice(2);
assert.ok(output && !existsSync(output), '需不存在的本地输出');
const key = process.env.FRED_API_KEY;
assert.ok(key?.trim(), '缺少 FRED 凭据');
const common = { series_id: 'CPIAUCNS', observation_start: '2016-01-01',
  observation_end: '2024-11-08', output_type: '1', units: 'lin', sort_order: 'asc', limit: '100000' };
const ranges = [
  ['history1', '2018-01-01', '2022-12-31'],
  ['history2', '2023-01-01', '2024-11-08'],
  ['before', '2024-10-09', '2024-10-09'],
  ['release', '2024-10-10', '2024-10-10'],
];
const receipts = [];
for (const [name, start, end] of ranges) {
  let offset = 0, count = null;
  do {
    const request = { ...common, realtime_start: start, realtime_end: end, offset: String(offset) };
    const url = new URL('https://api.stlouisfed.org/fred/series/observations');
    for (const [k, v] of Object.entries(request)) url.searchParams.set(k, v);
    url.searchParams.set('file_type', 'json');
    url.searchParams.set('api_key', key);
    const receipt = { name, request, startedAt: new Date().toISOString() };
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(45000) });
      const raw = await response.text();
      receipt.httpStatus = response.status;
      assert.ok(!raw.includes(key), '拒绝回显凭据');
      receipt.rawResponse = raw;
      receipt.rawSha256 = createHash('sha256').update(raw).digest('hex');
      const body = JSON.parse(raw);
      receipt.response = body;
      assert.equal(response.status, 200);
      assert.equal(body.offset, offset);
      assert.equal(body.output_type, 1);
      assert.equal(body.units, 'lin');
      assert.equal(body.realtime_start, start);
      assert.equal(body.realtime_end, end);
      assert.ok(Array.isArray(body.observations));
      assert.ok(Number.isInteger(body.count));
      if (count !== null) assert.equal(body.count, count);
      count = body.count;
      offset += body.observations.length;
      assert.ok(offset <= count && (offset === count || body.observations.length > 0));
    } catch {
      receipt.error = 'FRED 请求或合同验证失败；未记录含凭据URL及原始异常';
    }
    receipt.completedAt = new Date().toISOString();
    receipts.push(receipt);
    if (receipt.error) break;
  } while (offset < count);
}
writeFileSync(output, JSON.stringify({ researchOnly: true, receipts }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(receipts.map(r => ({ name: r.name, httpStatus: r.httpStatus,
  count: r.response?.count, received: r.response?.observations?.length, error: r.error ?? null }))));
