// 事后收益分组与概率排序诊断；不能作为事前输入或改写现有标签。
const assert=require('node:assert/strict');
function decimal(text) {
  assert.ok(typeof text==='string'&&/^\d+(\.\d+)?$/.test(text));
  const n=BigInt(text.replace('.','')),d=10n**BigInt((text.split('.')[1]||'').length);
  assert.ok(n>0n);return {n,d};
}
function outcome(base,target) {
  const a=decimal(base),b=decimal(target),num=100n*(b.n*a.d-a.n*b.d),den=a.n*b.d,abs=num<0n?-num:num;
  const within=(n,d)=>abs*BigInt(d)<=den*BigInt(n);
  const label=within(1,2)?'NEUTRAL':num>0n?'BULLISH':'BEARISH';
  const group=within(1,2)?'WITHIN_0_5':within(3,5)?'EDGE_0_5_0_6':within(1,1)?'MID_0_6_1':within(2,1)?'LARGE_1_2':'OVER_2';
  return {label,group};
}
function auc(points) {
  for(const r of points)assert.ok(typeof r.positive==='boolean'&&Number.isFinite(r.p)&&r.p>=0&&r.p<=1);
  const pos=points.filter(r=>r.positive),neg=points.filter(r=>!r.positive);
  if(!pos.length||!neg.length)return null;
  let wins=0;for(const p of pos)for(const n of neg)wins+=p.p>n.p?1:p.p===n.p?.5:0;
  return wins/(pos.length*neg.length);
}
module.exports={outcome,auc};
