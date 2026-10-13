import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

// 只读采集已冻结预测的真实验收回执；不重跑模型、不更新数据库。
const [output] = process.argv.slice(2);
assert.ok(output && process.argv.length === 3, '请提供不存在的本地回执文件');
assert.ok(!existsSync(output), '输出已存在，禁止覆盖或重复消耗取数额度');
const key = process.env.TWELVE_DATA_API_KEY;
assert.ok(key?.trim(), '缺少 Twelve Data 凭据');
assert.ok(process.env.PGPASSWORD, '缺少数据库凭据');
const id = '56ff334e-8e5f-45aa-ab39-77833eb5e264';
const query = `select row_to_json(f) from gold_direction_forecast f where id='${id}'`;
const db = spawnSync('D:/workFile/postgresql/17/bin/psql.exe',
  ['-h', 'localhost', '-U', 'postgres', '-d', 'opspilot_ai', '-At', '-c', query],
  { encoding: 'utf8' });
assert.equal(db.status, 0, '读取原始预测失败');
const forecast = JSON.parse(db.stdout.trim());
assert.equal(forecast.base_date, '2026-10-09');
assert.equal(forecast.promised_target_date, '2026-10-12');
assert.equal(forecast.predicted_direction, 'neutral');
assert.equal(forecast.forecast_rule_version, 'gold-next-session-direction-v1');
assert.ok(Date.parse(forecast.created_at) < Date.parse(forecast.target_start));
assert.ok(Date.now() > Date.parse(forecast.target_end));
const requests = [
  ['/quote', { symbol: 'XAU/USD', eod: 'true' }],
  ['/time_series', { symbol: 'XAU/USD', interval: '1day', date: '2026-10-09' }],
  ['/time_series', { symbol: 'XAU/USD', interval: '1day', date: '2026-10-12' }],
  ['/time_series', { symbol: 'XAU/USD', interval: '1h', timezone: 'UTC',
    start_date: '2026-10-11 20:00:00', end_date: '2026-10-12 19:00:00', outputsize: '30' }],
];
const receipts = [];
for (const [path, request] of requests) {
  const startedAt = new Date().toISOString();
  const url = new URL(path, 'https://api.twelvedata.com');
  for (const [name, value] of Object.entries(request)) url.searchParams.set(name, value);
  url.searchParams.set('apikey', key);
  const receipt = { path, request, startedAt };
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(45000) });
    const raw = await response.text();
    receipt.httpStatus = response.status;
    // 外部正文若回显凭据则完全拒绝归档，不把敏感 URL 当错误证据。
    if (raw.includes(key)) throw new Error('SECRET_ECHO');
    receipt.rawSha256 = createHash('sha256').update(raw).digest('hex');
    receipt.rawResponse = raw;
    receipt.response = JSON.parse(raw);
  } catch {
    receipt.error = '请求或回执解析失败；不记录敏感 URL 和原始异常';
  }
  receipt.completedAt = new Date().toISOString();
  receipts.push(receipt);
}
writeFileSync(output, JSON.stringify({ researchOnly: true, officialSessionCertified: false,
  forecast, receipts }, null, 2) + '\n', { flag: 'wx', encoding: 'utf8' });
console.log(JSON.stringify({ id, responses: receipts.map(r => ({ path: r.path,
  httpStatus: r.httpStatus, status: r.response?.status, count: r.response?.values?.length,
  error: r.error ?? null })) }));
