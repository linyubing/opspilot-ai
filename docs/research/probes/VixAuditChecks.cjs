// 只读真实本地归档的认证测试；改坏副本不会写回行情文件。
'use strict';
const fs = require('node:fs'), test = require('node:test'), assert = require('node:assert/strict');
const { audit } = require('./VixAudit.cjs');
const tree = JSON.parse(fs.readFileSync('docs/research/2026-10-03-tree-check.json','utf8'));
const source = JSON.parse(fs.readFileSync('backend/target/vix-history-first/VIXCLS.json','utf8'));
const result = JSON.parse(fs.readFileSync('backend/target/vix-four-vectors.json','utf8'));
test('真实特征窗口与独立公式一致', () => {
  const r = audit(tree, source, result);
  assert.equal(r.count, 4361); assert.equal(r.complete, 3636); assert.equal(r.missing, 725);
  assert.equal(r.validationCount, 720);
  assert.deepEqual(r.training.map(x=>x.count), [2915,3155,3395]);
});
for (const [name, mutate] of [
  ['拒绝特征数值变动', r=>{r.vectors[1000].values[2] += 1;}],
  ['拒绝完整窗口被改为缺失', r=>{r.vectors[1000].values=null;}],
  ['拒绝早期缺失被补零', r=>{r.vectors[0].values=[0,0,0,0];}],
  ['拒绝观测日期偷换', r=>{r.windows[1000].latest=r.windows[1000].date;}],
  ['拒绝目标日期偷换', r=>{r.vectors[1000].target=r.vectors[1000].date;}],
  ['拒绝删除验证日期', r=>{r.vectors.pop();}],
  ['拒绝源码摘要变化', r=>{r.codeHashes['VixFeatures.java']='0'.repeat(64);}],
  ['拒绝协议摘要变化', r=>{r.protocolHash='0'.repeat(64);}],
]) test(name, ()=>{const r=structuredClone(result);mutate(r);assert.throws(()=>audit(tree,source,r));});
