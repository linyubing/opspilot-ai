// 独立两阶段数学复算，不训练或选参。
const assert=require('node:assert/strict');
function probability(w,x){
  assert.ok(Array.isArray(w)&&Array.isArray(x)&&w.length===x.length+1&&w.every(Number.isFinite)&&x.every(Number.isFinite));
  const z=x.reduce((s,v,j)=>s+v*w[j],w.at(-1));assert.ok(Number.isFinite(z));
  return z>=0?1/(1+Math.exp(-z)):Math.exp(z)/(1+Math.exp(z));
}
function combine(move,side){
  assert.ok(Number.isFinite(move)&&Number.isFinite(side)&&move>=0&&move<=1&&side>=0&&side<=1);
  return [move*side,1-move,move*(1-side)];
}
function objective(x,y,w,lambda){
  assert.ok(x.length>0&&x.length===y.length&&Number.isFinite(lambda)&&lambda>0&&y.includes(0)&&y.includes(1));
  const gradient=new Array(w.length).fill(0);let loss=0;
  x.forEach((row,i)=>{assert.ok(y[i]===0||y[i]===1);const p=probability(w,row),z=row.reduce((s,v,j)=>s+v*w[j],w.at(-1));
    loss+=(Math.max(z,0)+Math.log1p(Math.exp(-Math.abs(z)))-y[i]*z)/x.length;
    [...row,1].forEach((v,j)=>gradient[j]+=(p-y[i])*v/x.length);});
  for(let j=0;j<w.length-1;j++){loss+=lambda*w[j]**2/4;gradient[j]+=lambda*w[j]/2;}
  return {loss,gradient};
}
module.exports={probability,combine,objective};
