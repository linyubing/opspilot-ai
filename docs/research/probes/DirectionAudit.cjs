// 固定720条旧验证的诊断，真实未来收益只用于结算与事后描述。
const assert=require('node:assert/strict'),crypto=require('node:crypto');
const {outcome,auc}=require('./DirectionMath.cjs'),{score}=require('./InfoMath.cjs');
const labels=['BULLISH','NEUTRAL','BEARISH'],keys=['candidate','price','macro','frequencyMix','prior','pitReference'];
const groups=['WITHIN_0_5','EDGE_0_5_0_6','MID_0_6_1','LARGE_1_2','OVER_2'];
const best=p=>p.indexOf(Math.max(...p));
function models(rows) {
  return Object.fromEntries(keys.map(key=>{
    const scored=score(rows,key),predictedCounts=[0,0,0];for(const r of rows)predictedCounts[best(r[key])]++;
    const direction=rows.filter(r=>r.actual!=='NEUTRAL');
    const byActual=labels.map((actual,c)=>{
      const selected=rows.filter(r=>r.actual===actual),mean=[0,0,0],predicted=[0,0,0];
      for(const r of selected){r[key].forEach((p,j)=>mean[j]+=p/selected.length);predicted[best(r[key])]++;}
      return {actual,count:selected.length,meanProbability:selected.length?mean:null,predictedCounts:predicted};
    });
    const oneVsRest=labels.map((actual,c)=>auc(rows.map(r=>({positive:r.actual===actual,p:r[key][c]}))));
    const directionalAuc=auc(direction.map(r=>{
      assert.ok(r[key][0]+r[key][2]>0,'条件方向概率不可定义，不能静默排除样本');
      return {positive:r.actual==='BULLISH',p:r[key][0]/(r[key][0]+r[key][2])};
    }));
    return [key,{score:scored,predictedCounts,oneVsRestAuc:oneVsRest,directionalAuc,directionalCount:direction.length,byActual}];
  }));
}
function pair(rows,key) {
  let newHits=0,newMisses=0;const byActual=labels.map(actual=>({actual,newHits:0,newMisses:0}));
  for(const r of rows){const actual=labels.indexOf(r.actual),ok=best(r.candidate)===actual,old=best(r[key])===actual;
    if(ok&&!old){newHits++;byActual[actual].newHits++;}if(!ok&&old){newMisses++;byActual[actual].newMisses++;}}
  return {newHits,newMisses,net:newHits-newMisses,byActual};
}
function diagnose(tree,fusion) {
  assert.equal(tree.bars.length,4382);assert.equal(fusion.promotionAllowed,false);
  const hash=crypto.createHash('sha256').update(tree.bars.map(b=>`${b.date}|${b.open}|${b.high}|${b.low}|${b.close}\n`).join('')).digest('hex');
  assert.equal(hash,'68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0');assert.equal(hash,tree.barHash);assert.equal(hash,fusion.barHash);
  const byDate=new Map(tree.bars.map((b,i)=>[b.date,{bar:b,next:tree.bars[i+1]}]));assert.equal(byDate.size,4382);
  tree.bars.forEach((b,i)=>{assert.ok(b.date<'2024-11-11');if(i)assert.ok(tree.bars[i-1].date<b.date);});
  assert.equal(fusion.folds.length,3);const rows=[],folds=[];
  for(const [i,f]of fusion.folds.entries()) {
    assert.equal(f.start,tree.folds[i].start);assert.equal(f.end,tree.folds[i].end);assert.equal(f.predictions.length,240);
    const block=[];for(const [j,row]of f.predictions.entries()) {
      const binding=byDate.get(row.date),saved=tree.folds[i].predictions[j];assert.ok(binding?.next);
      for(const field of ['date','target','actual'])assert.equal(row[field],saved[field]);
      assert.equal(row.target,binding.next.date);assert.ok(row.date<row.target&&row.target<'2024-11-11');
      if(rows.length)assert.ok(rows.at(-1).date<row.date);const settled=outcome(binding.bar.close,binding.next.close);assert.equal(row.actual,settled.label);
      const bound={...row,group:settled.group};rows.push(bound);block.push(bound);
    }
    folds.push({start:f.start,end:f.end,count:block.length,models:models(block)});
  }
  assert.equal(rows.length,720);const all=models(rows);
  const result=groups.map(group=>{const selected=rows.filter(r=>r.group===group);
    return {group,count:selected.length,models:models(selected),againstPrice:pair(selected,'price'),againstFrequencyMix:pair(selected,'frequencyMix')};});
  assert.equal(result.reduce((n,g)=>n+g.count,0),rows.length);
  for(const key of keys)assert.equal(result.reduce((n,g)=>n+g.models[key].score.correct,0),all[key].score.correct);
  return {count:rows.length,labels,all,groups:result,folds,promotionAllowed:false,scope:'post-outcome diagnostic only; repeated development dates; no model or threshold selection'};
}
module.exports={diagnose};
