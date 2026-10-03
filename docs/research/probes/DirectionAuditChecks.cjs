const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {diagnose}=require('./DirectionAudit.cjs');
const tree=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'),'utf8'));
const fusion=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-03-fusion-check.json'),'utf8'));
test('真实分组覆盖全部720日期而不改变349命中',()=>{
  const r=diagnose(tree,fusion);assert.equal(r.count,720);assert.ok(r.all?.candidate?.score,'必须实际结算全部预测而不返回空摘要');assert.equal(r.all.candidate.score.correct,349);
  assert.equal(r.groups.reduce((s,g)=>s+g.count,0),720);assert.equal(r.groups.reduce((s,g)=>s+g.models.candidate.score.correct,0),349);
});
for(const [name,damage]of [
  ['拒绝不是下一根真实日线的目标',r=>r.folds[0].predictions[0].target=r.folds[0].predictions[1].target],
  ['拒绝错误实际标签',r=>r.folds[0].predictions[0].actual='INVALID'],
  ['拒绝非法概率',r=>r.folds[0].predictions[0].macro=[0,0,0]],
  ['拒绝重复验证日期',r=>r.folds[0].predictions[1].date=r.folds[0].predictions[0].date]
])test(name,()=>{const copy=structuredClone(fusion);damage(copy);assert.throws(()=>diagnose(tree,copy));});
