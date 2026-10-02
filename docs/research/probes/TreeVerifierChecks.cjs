// 仅在target生成故意损坏的验收副本；不造行情、不更改真实归档。
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),assert=require('node:assert/strict');
const root=path.join(__dirname,'../../..'),file=process.argv[2]||path.join(root,'docs/research/2026-10-03-tree-check.json');
const original=JSON.parse(fs.readFileSync(file,'utf8'));
const mutations=[
  ['feature',r=>r.inputs[0].x[1]+=.001],
  ['label',r=>r.inputs[0].actual=r.inputs[0].actual==='BULLISH'?'BEARISH':'BULLISH'],
  ['date',r=>r.folds[0].trainingDates[0]=r.folds[0].start],
  ['scale',r=>r.folds[0].std[0]+=1],
  ['leaf',r=>{const t=r.folds[0].model.state.learner.gradient_booster.model.trees[0];const j=t.left_children.indexOf(-1);t.split_conditions[j]+=1;}],
  ['probability',r=>{const p=r.folds[0].predictions[0].candidate;p[0]+=.001;p[1]-=.001;}]
];
for(const[name,mutate]of mutations){const r=structuredClone(original);mutate(r);const target=path.join(root,`backend/target/tree-damaged-${name}.json`);fs.writeFileSync(target,JSON.stringify(r));
  const result=cp.spawnSync(process.execPath,[path.join(__dirname,'verify-tree.cjs'),target],{encoding:'utf8',maxBuffer:4*1024*1024});
  assert.equal(result.error,undefined);assert.equal(result.signal,null);assert.equal(result.status,1,name+'副本未被拒绝');assert.match(result.stderr,/AssertionError/,name+'没有由核验断言拒绝');}
console.log('PASS: 拒绝特征/标签/训练日期/缩放/树叶/概率损坏');
