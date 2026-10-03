// 独立复算任意维训练缩放、概率、中心化L2损失与梯度。
'use strict';
const assert=require('node:assert/strict');
function scaling(rows){
  assert.ok(rows.length>0&&rows[0].length>0);const d=rows[0].length,mean=Array(d).fill(0),s=Array(d).fill(0);let n=0;
  for(const row of rows){assert.equal(row.length,d);assert.ok(row.every(Number.isFinite));n++;
    row.forEach((v,j)=>{const delta=v-mean[j];mean[j]+=delta/n;s[j]+=delta*(v-mean[j]);});}
  return {mean,std:s.map(v=>Math.sqrt(Math.max(0,v/n)))};
}
function logits(w,x){
  const d=x.length+1;assert.equal(w.length,2*d);assert.ok(w.every(Number.isFinite)&&x.every(Number.isFinite));
  const z=[w[d-1],w[2*d-1],0];for(let c=0;c<2;c++)x.forEach((v,j)=>z[c]+=w[c*d+j]*v);return z;
}
function probabilities(w,x){const z=logits(w,x),m=Math.max(...z),p=z.map(v=>Math.exp(v-m)),sum=p.reduce((a,b)=>a+b,0);return p.map(v=>v/sum);}
function objective(x,y,w){
  assert.ok(x.length>0&&x.length===y.length);const d=x[0].length+1,g=Array(2*d).fill(0);let loss=0;
  for(let i=0;i<x.length;i++){
    assert.equal(x[i].length,d-1);assert.ok(Number.isInteger(y[i])&&y[i]>=0&&y[i]<3);
    const z=logits(w,x[i]),m=Math.max(...z),p=probabilities(w,x[i]);
    loss+=(m+Math.log(z.reduce((sum,v)=>sum+Math.exp(v-m),0))-z[y[i]])/x.length;
    for(let c=0;c<2;c++){const delta=(p[c]-(y[i]===c?1:0))/x.length;
      for(let j=0;j<d-1;j++)g[c*d+j]+=delta*x[i][j];g[c*d+d-1]+=delta;}
  }
  for(let j=0;j<d-1;j++){const a=w[j],b=w[j+d];loss+=.01/3*(a*a+b*b-a*b);g[j]+=.01/3*(2*a-b);g[j+d]+=.01/3*(2*b-a);}
  let maxGradient=Math.max(...g.map(Math.abs));for(let j=0;j<d;j++)maxGradient=Math.max(maxGradient,Math.abs(g[j]+g[j+d]));
  return {loss,gradient:g,maxGradient};
}
module.exports={scaling,probabilities,objective};
