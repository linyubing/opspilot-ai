// 故意损坏本地审计副本，确认验收器能拒绝错误；不修改真实归档。
const { test } = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
const chosen = process.env.INFO_AUDIT_FILE;
const file = chosen ? path.resolve(chosen) : path.join(__dirname,'../2026-10-03-info-check.json');
if (chosen && !fs.existsSync(file)) throw new Error('指定的审计归档不存在，请先真实导出，不允许忽略所选文件');
const verifier = path.join(__dirname,'verify-info.cjs');
const available = fs.existsSync(file) && Boolean(process.env.FRED_HISTORY_DIR);
const options = { skip:available?false:'需先生成真实本地宏观审计归档并配置FRED_HISTORY_DIR，不使用假数据' };
let report, directory;
if (available) {
  report = JSON.parse(fs.readFileSync(file,'utf8'));
  directory = fs.mkdtempSync(path.join(__dirname,'../../../backend/target/info-negative-'));
}
test('真实审计归档完整绑定，窗口与独立原始版本选择一致', options, () => {
  assert.doesNotThrow(() => cp.execFileSync(process.execPath,[verifier,file], {stdio:'pipe', maxBuffer:16*1024*1024}));
});
const cases = [
  ['count', r => r.rows.at(-1).series.DFII10.count--],
  ['value', r => r.rows.at(-1).series.DFII10.latest.value = '999999'],
  ['id', r => r.rows.at(-1).series.DFII10.latest.id = '00000000-0000-0000-0000-000000000000'],
  ['date', r => r.rows.at(-1).series.DFII10.latest.date = r.rows.at(-1).date],
  ['window', r => r.rows.at(-1).series.DFII10.windowHash = '0'.repeat(64)],
  ['inputOrder', r => [r.rows[0],r.rows[1]] = [r.rows[1],r.rows[0]]],
  ['source', r => r.macroInput['DFII10.sha256'] = '0'.repeat(64)],
  ['tree', r => r.treeHash = '0'.repeat(64)]
];
for (const [name, damage] of cases) test('拒绝被损坏的'+name+'绑定', options, () => {
  const copy = structuredClone(report); damage(copy);
  const target = path.join(directory,name+'.json'); fs.writeFileSync(target,JSON.stringify(copy));
  assert.throws(() => cp.execFileSync(process.execPath,[verifier,target], {stdio:'pipe', maxBuffer:16*1024*1024}));
});
