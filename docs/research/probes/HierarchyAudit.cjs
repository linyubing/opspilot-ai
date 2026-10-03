// 独立复核真实输入、两头训练与三分类预测，不能据评分再调参数。
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {probability,combine,objective}=require('./HierarchyMath.cjs'),{score}=require('./InfoMath.cjs'),{auc,outcome}=require('./DirectionMath.cjs');
const keys=['candidate','price','pitReference','prior'],labels=['BULLISH','NEUTRAL','BEARISH'];
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function near(a,b){assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=1e-10*(1+Math.abs(b)));}
function scale(rows){const mean=new Array(20).fill(0),std=mean.slice();let n=0;
  for(const r of rows){n++;r.x.forEach((v,j)=>{const d=v-mean[j];mean[j]+=d/n;std[j]+=d*(v-mean[j]);});}
  return {mean,std:std.map(v=>Math.sqrt(Math.max(0,v/n)))};
}
function values(row,s){return row.x.map((v,j)=>s.std[j]===0?0:(v-s.mean[j])/s.std[j]);}
function head(rows,h,direction){
  assert.deepEqual(h.dates,rows.map(r=>r.date));assert.equal(h.mean.length,20);assert.equal(h.std.length,20);
  const s=scale(rows);s.mean.forEach((v,j)=>near(h.mean[j],v));s.std.forEach((v,j)=>near(h.std[j],v));
  const x=rows.map(r=>values(r,s)),y=rows.map(r=>direction?(r.actual==='BULLISH'?1:0):(r.actual==='NEUTRAL'?0:1));
  assert.equal(h.fit.converged,true);assert.equal(h.fit.weights.length,21);assert.ok(Number.isInteger(h.fit.steps)&&h.fit.steps>=0&&h.fit.steps<=100);
  const f=objective(x,y,h.fit.weights,.01),gradient=Math.max(...f.gradient.map(Math.abs));
  assert.ok(gradient<=1e-6);near(h.fit.loss,f.loss);near(h.fit.gradient,gradient);assert.ok(Number.isFinite(h.repeatDifference)&&h.repeatDifference<=1e-12&&h.repeatDifference>=0);
  return {s,weights:h.fit.weights,count:rows.length,loss:f.loss,gradient,steps:h.fit.steps};
}
function scores(rows){return Object.fromEntries(keys.map(k=>[k,score(rows,k)]));}
function pair(rows,k){let wins=0,losses=0;const byActual=labels.map(actual=>({actual,wins:0,losses:0}));
  for(const r of rows){const c=labels.indexOf(r.actual),a=r.candidate.indexOf(Math.max(...r.candidate))===c,b=r[k].indexOf(Math.max(...r[k]))===c;
    if(a&&!b){wins++;byActual[c].wins++;}if(!a&&b){losses++;byActual[c].losses++;}}
  return {wins,losses,net:wins-losses,byActual};
}
function heads(rows){const side=rows.filter(r=>r.actual!=='NEUTRAL'),moveMatrix=[[0,0],[0,0]],sideMatrix=[[0,0],[0,0]];
  for(const r of rows)moveMatrix[r.actual==='NEUTRAL'?0:1][r.move>=.5?1:0]++;
  for(const r of side)sideMatrix[r.actual==='BULLISH'?1:0][r.side>=.5?1:0]++;
  return {moveCount:rows.length,sideCount:side.length,moveAuc:auc(rows.map(r=>({positive:r.actual!=='NEUTRAL',p:r.move}))),
    sideAuc:auc(side.map(r=>({positive:r.actual==='BULLISH',p:r.side}))),moveMatrix,sideMatrix,
    byActual:labels.map(actual=>{const selected=rows.filter(r=>r.actual===actual);return {actual,count:selected.length,
      meanMove:selected.length?selected.reduce((s,r)=>s+r.move/selected.length,0):null,meanSide:selected.length?selected.reduce((s,r)=>s+r.side/selected.length,0):null};})};
}
function audit(tree,result){
  const file=path.join(__dirname,'../2026-10-03-tree-check.json');assert.deepEqual(tree,JSON.parse(fs.readFileSync(file,'utf8')));
  assert.equal(result.treeHash,sha(fs.readFileSync(file)));assert.equal(result.inputHash,tree.inputHash);assert.equal(result.version,'gold-hierarchy-v1');
  assert.match(result.gitCommit,/^[0-9a-f]{40}$/);assert.equal(result.promotionAllowed,false);assert.equal(result.lambda,.01);assert.equal(result.signalThreshold,.55);assert.equal(result.cutoffExclusive,'2024-11-11');
  const names=['HierarchyRun.java','HierarchyModel.java','HierarchyChecks.java','BinaryFit.java','HistorySlice.java','FusionScale.java','TrainingProbe.java'];
  assert.deepEqual(Object.keys(result.sourceHashes).sort(),names.slice().sort());for(const n of names)assert.equal(result.sourceHashes[n],sha(fs.readFileSync(path.join(__dirname,n))));
  assert.equal(result.protocolHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-hierarchy-protocol.md'))));
  assert.equal(result.folds.length,3);assert.equal(tree.inputs.length,4361);const byDate=new Map(tree.inputs.map(r=>[r.date,r]));
  const bars=new Map(tree.bars.map((r,i)=>[r.date,{base:r,next:tree.bars[i+1]}]));const all=[],folds=[],blocks=[];
  for(const [i,f]of result.folds.entries()){
    const old=tree.folds[i];assert.equal(f.start,old.start);assert.equal(f.end,old.end);
    const train=tree.inputs.filter(r=>r.date<f.start&&r.target<f.start),direction=train.filter(r=>r.actual!=='NEUTRAL');assert.equal(train.length,3640+i*240);
    const move=head(train,f.move,false),side=head(direction,f.side,true);near(f.repeatDifference,Math.max(f.move.repeatDifference,f.side.repeatDifference));
    assert.equal(f.predictions.length,240);const rows=[];
    for(const [j,r]of f.predictions.entries()){
      const saved=old.predictions[j],input=byDate.get(r.date);assert.ok(input);
      for(const n of ['date','target','actual']){assert.equal(r[n],saved[n]);assert.equal(r[n],input[n]);}
      assert.ok(r.target<'2024-11-11'&&r.target>r.date);if(all.length)assert.ok(all.at(-1).date<r.date);
      const bar=bars.get(r.date);assert.ok(bar?.next);assert.equal(r.target,bar.next.date);const settlement=outcome(bar.base.close,bar.next.close);assert.equal(r.actual,settlement.label);
      const pMove=probability(move.weights,values(input,move.s)),pSide=probability(side.weights,values(input,side.s));near(r.move,pMove);near(r.side,pSide);
      assert.equal(r.candidate.length,3);combine(pMove,pSide).forEach((v,c)=>near(r.candidate[c],v));
      assert.deepEqual(r.price,saved.reference);assert.deepEqual(r.pitReference,saved.pitReference);assert.deepEqual(r.prior,saved.prior);
      const row={...r,group:settlement.group};rows.push(row);all.push(row);
    }
    folds.push({start:f.start,end:f.end,count:rows.length,move:{count:move.count,loss:move.loss,gradient:move.gradient,steps:move.steps},side:{count:side.count,loss:side.loss,gradient:side.gradient,steps:side.steps},scores:scores(rows),heads:heads(rows),againstPrice:pair(rows,'price')});
    for(let j=0;j<240;j+=20){const b=rows.slice(j,j+20);blocks.push({start:b[0].date,end:b.at(-1).date,scores:scores(b),heads:heads(b),againstPrice:pair(b,'price')});}
  }
  assert.equal(all.length,720);const total=scores(all),signals=all.filter(r=>Math.max(...r.candidate)>=.55),signalScores=scores(signals);
  const c=total.candidate,p=total.price;const gates={accuracy:c.accuracy>p.accuracy&&c.accuracy>total.pitReference.accuracy&&c.accuracy>total.prior.accuracy,
    balancedGain:c.balancedAccuracy!==null&&p.balancedAccuracy!==null&&c.balancedAccuracy>=p.balancedAccuracy+.02,recalls:c.recalls.every(v=>v!==null&&v>=.25),brier:c.brierScore<=p.brierScore,logLoss:c.logLoss<=p.logLoss};
  const sig=signalScores.candidate,signalGates={coverage:signals.length/720>=.3,sameDateBaseline:signals.length>0&&['price','pitReference','prior'].every(k=>sig.accuracy>signalScores[k].accuracy),recalls:sig.recalls.every(v=>v!==null&&v>=.25)};
  return {count:720,promotionAllowed:false,scope:'repeated old development dates; no final holdout or production promotion',scores:total,heads:heads(all),gates,signalGates,
    signals:{count:signals.length,coverage:signals.length/720,scores:signalScores},againstPrice:pair(all,'price'),folds,blocks,
    groups:['WITHIN_0_5','EDGE_0_5_0_6','MID_0_6_1','LARGE_1_2','OVER_2'].map(group=>{const rows=all.filter(r=>r.group===group);return {group,count:rows.length,scores:scores(rows),againstPrice:pair(rows,'price')};}),
    sourceHashes:{...result.sourceHashes,...Object.fromEntries(['HierarchyMath.cjs','HierarchyAudit.cjs','HierarchyAuditChecks.cjs','verify-hierarchy.cjs','InfoMath.cjs','DirectionMath.cjs'].map(n=>[n,sha(fs.readFileSync(path.join(__dirname,n)))]))},protocolHash:result.protocolHash,inputHashes:{tree:result.treeHash}};
}
module.exports={audit};
