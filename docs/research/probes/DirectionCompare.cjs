// 比较两种独立实现的真实幅度分组、混淆与AUC，不训练或筛选模型。
const assert=require('node:assert/strict');
const keys=['candidate','price','macro','frequencyMix','prior','pitReference'];
function near(a,b){if(a===null||b===null){assert.equal(a,b);return;}assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=1e-12);}
function models(node,java) {
  assert.deepEqual(Object.keys(java).sort(),keys.slice().sort());
  for(const key of keys){const a=node[key],b=java[key];assert.equal(a.score.count,b.count);assert.equal(a.score.correct,b.correct);
    assert.deepEqual(a.score.matrix,b.matrix);assert.deepEqual(a.predictedCounts,b.predictedCounts);
    near(a.score.brierScore,b.brierScore);near(a.score.logLoss,b.logLoss);assert.equal(b.oneVsRestAuc.length,3);
    a.oneVsRestAuc.forEach((v,c)=>near(v,b.oneVsRestAuc[c]));near(a.directionalAuc,b.directionalAuc);}
}
function check(node,java) {
  assert.equal(node.count,720);assert.equal(java.count,node.count);models(node.all,java.all);
  assert.equal(node.groups.length,5);assert.equal(java.groups.length,node.groups.length);
  node.groups.forEach((g,i)=>{const h=java.groups[i];assert.equal(h.group,g.group);assert.equal(h.count,g.count);models(g.models,h.models);});
  assert.equal(node.folds.length,3);assert.equal(java.folds.length,node.folds.length);
  node.folds.forEach((f,i)=>{assert.equal(f.start,java.folds[i].start);models(f.models,java.folds[i].models);});
  return true;
}
module.exports={check};
