const {test}=require('node:test'),assert=require('node:assert/strict');
const {probability,combine,objective}=require('./HierarchyMath.cjs');
test('两阶段概率按手算组合，不丢掉方向头',()=>{
  const p=combine(.8,.75);p.forEach((v,i)=>assert.ok(Math.abs(v-[.6,.2,.2][i])<1e-12));
  assert.deepEqual(combine(0,.3),[0,1,0]);assert.deepEqual(combine(1,0),[0,0,1]);
  assert.throws(()=>combine(NaN,.5));assert.throws(()=>combine(.8,1.1));
});
test('二分类概率与L2交叉熵，不惩罚截距',()=>{
  assert.equal(probability([0,0],[1]),.5);assert.ok(Math.abs(probability([1,0],[1])-.7310585786300049)<1e-12);
  const x=[[-1],[1]],y=[0,1];const f=objective(x,y,[0,0],.01);
  assert.ok(Math.abs(f.loss-Math.log(2))<1e-12);assert.deepEqual(f.gradient,[-.5,0]);
  assert.equal(objective(x,y,[0,.4],.01).loss,objective(x,y,[0,.4],1).loss);
  const p=objective(x,y,[2,0],.01),q=objective(x,y,[2,0],1);assert.ok(Math.abs(q.loss-p.loss-.99)<1e-12);
  assert.throws(()=>probability([1,0],[NaN]));
});
