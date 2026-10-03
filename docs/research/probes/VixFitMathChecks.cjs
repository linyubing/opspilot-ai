// 手算及有限差分数学夹具，不是行情。
'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {scaling,probabilities,objective}=require('./VixFitMath.cjs');
const near=(a,b)=>assert.ok(Number.isFinite(a)&&Math.abs(a-b)<1e-8);
test('训练缩放用总体标准差且常量列为零',()=>{
  assert.deepEqual(scaling([[2,4,7],[4,8,7]]),{mean:[3,6,7],std:[1,2,0]});
});
test('零权重三概率均匀且大截距仍有限',()=>{
  probabilities([0,0,0,0],[3]).forEach(p=>near(p,1/3));
  assert.equal(probabilities([0,0,0,0],[3]).length,3);
  assert.deepEqual(probabilities([0,1000,0,-1000],[3]),[1,0,0]);
});
test('平均交叉熵与中心化L2梯度匹配手算',()=>{
  const a=objective([[0],[0]],[0,1],[1,0,0,0]);near(a.loss,Math.log(3)+.01/3);
  const expected=[.02/3,-1/6,-.01/3,-1/6];
  assert.equal(a.gradient.length,4);a.gradient.forEach((v,j)=>near(v,expected[j]));near(a.maxGradient,1/3);
});
test('各维梯度与独立有限差分一致',()=>{
  const x=[[-1,2],[3,-2],[0,1]],y=[0,1,2],w=[.3,-.2,.1,-.4,.2,-.1];
  const a=objective(x,y,w);assert.equal(a.gradient.length,6);
  for(let j=0;j<w.length;j++){const p=w.slice(),m=w.slice();p[j]+=1e-5;m[j]-=1e-5;
    near(a.gradient[j],(objective(x,y,p).loss-objective(x,y,m).loss)/2e-5);}
});
