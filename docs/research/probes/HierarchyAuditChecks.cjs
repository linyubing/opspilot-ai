const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {audit}=require('./HierarchyAudit.cjs');
const tree=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'),'utf8'));
const result=JSON.parse(fs.readFileSync(path.join(__dirname,'../../../backend/target/hierarchy-results.json'),'utf8'));
test('独立认证完整720日期、两头收敛与完整三分类',()=>assert.equal(audit(tree,result).count,720));
for(const [name,damage]of [
  ['拒绝方向头混入震荡训练',r=>r.folds[0].side.dates[0]=tree.inputs.find(v=>v.actual==='NEUTRAL').date],
  ['拒绝训练缩放被改变',r=>r.folds[0].move.mean[0]+=.1],
  ['拒绝非最优方向权重',r=>r.folds[0].side.fit.weights[0]+=.5],
  ['拒绝改写中间概率',r=>r.folds[0].predictions[0].side=0],
  ['拒绝改写目标标签',r=>r.folds[0].predictions[0].actual='INVALID'],
  ['拒绝改变截止日',r=>r.cutoffExclusive='2026-01-01'],
  ['拒绝源码摘要伪造',r=>r.sourceHashes['BinaryFit.java']='0'.repeat(64)],
  ['拒绝删除困难验证日期',r=>r.folds[0].predictions.pop()]
])test(name,()=>{const copy=structuredClone(result);damage(copy);assert.throws(()=>audit(tree,copy));});
