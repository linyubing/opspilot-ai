import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

// 只读检验公开日历能否自动取得；403时不绕过访问控制。
const [output] = process.argv.slice(2);
assert.ok(output && !fs.existsSync(output), '请提供不存在的本地归档文件');
const url = 'https://tradingeconomics.com/united-states/calendar';
const receipt = { sourceUrl: url, startedAt: new Date().toISOString(), automatedExtractionComplete: false };
try {
  const reply = await fetch(url, { signal: AbortSignal.timeout(45000) });
  const raw = await reply.text();
  receipt.httpStatus = reply.status;
  receipt.contentType = reply.headers.get('content-type');
  receipt.rawSha256 = createHash('sha256').update(raw).digest('hex');
  receipt.rawResponse = raw;
} catch {
  receipt.error = '公开日历取数失败，没有绕过访问控制或生成共识';
}
receipt.completedAt = new Date().toISOString();
fs.writeFileSync(output, JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ startedAt: receipt.startedAt, completedAt: receipt.completedAt,
  httpStatus: receipt.httpStatus, rawSha256: receipt.rawSha256, error: receipt.error ?? null }));
