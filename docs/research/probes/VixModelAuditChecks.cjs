// 真实实验只读副本的认证与损坏拒绝测试，不修改行情文件。
'use strict';
const fs=require('node:fs'),test=require('node:test'),assert=require('node:assert/strict');
const {audit}=require('./VixModelAudit.cjs');
const tree=JSON.parse(fs.readFileSync('docs/research/2026-10-03-tree-check.json','utf8'));
const vectors=JSON.parse(fs.readFileSync('backend/target/vix-four-vectors.json','utf8'));
const result=JSON.parse(fs.readFileSync('backend/target/vix-fit-results.json','utf8'));
test('真实匹配对照保留全部720验证与三个共同训练折',()=>{
  const r=audit(tree,vectors,result);assert.equal(r.scores.candidate.count,720);
  assert.deepEqual(r.folds.map(f=>f.trainingCount),[2915,3155,3395]);assert.equal(r.promotionAllowed,false);
});
for(const [name,mutate]of[
  ['拒绝训练日期变化',r=>{r.folds[0].trainingDates[0]='2022-02-02';}],
  ['拒绝训练缩放变化',r=>{r.folds[0].candidate.mean[20]+=1;}],
  ['拒绝未收敛权重',r=>{r.folds[0].candidate.fit.weights[0]+=1;}],
  ['拒绝改写预测概率',r=>{r.folds[0].predictions[0].candidate=[.9,.05,.05];}],
  ['拒绝未来目标',r=>{r.folds[0].predictions[0].target='2024-11-11';}],
  ['拒绝源码摘要变化',r=>{r.sourceHashes['VixRun.java']='0'.repeat(64);}],
  ['拒绝参数变化',r=>{r.lambda=.1;}],
  ['拒绝删除验证样本',r=>{r.folds[2].predictions.pop();}],
])test(name,()=>{const r=structuredClone(result);mutate(r);assert.throws(()=>audit(tree,vectors,r));});
