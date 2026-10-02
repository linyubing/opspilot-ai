// 从完整真实20维输入重建折内标准化、逐日概率、指标与验收，不读取数据库。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),cp=require('node:child_process');
const file=process.argv[2];if(!file)throw new Error('需要全历史实验JSON路径');
const report=JSON.parse(fs.readFileSync(file,'utf8'));
const stateFile=path.join(__dirname,'../2026-10-02-state-check.json'),ridgeFile=path.join(__dirname,'../2026-10-02-ridge-check.json');
const state=JSON.parse(fs.readFileSync(stateFile,'utf8')),ridge=JSON.parse(fs.readFileSync(ridgeFile,'utf8'));
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-state.cjs'),stateFile],{stdio:'pipe'});
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),fileHash=file=>sha(Buffer.from(fs.readFileSync(file,'utf8').replace(/\r\n/g,'\n'),'utf8'));
const names=['BULLISH','NEUTRAL','BEARISH'],best=p=>p.indexOf(Math.max(...p));
const featureNames=['return1','return3','return5','return10','return20','overnightGap','intradayReturn','dailyRange','closeLocation','atr14','volatility5','volatility20','ma5Distance','ma20Distance','ma5Slope','ma20Slope','rsi14','drawdown20','highBreakout20','lowBreakdown20'].sort();
const near=(a,b,tol=1e-12)=>assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=tol,`${a} != ${b}`);
function score(rows,key,threshold=0){
  const matrix=Array.from({length:3},()=>[0,0,0]);let covered=0,correct=0,brier=0,logLoss=0;
  for(const row of rows){const p=row[key],a=names.indexOf(row.actual);assert.ok(a>=0);assert.equal(p.length,3);
    assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));near(p.reduce((a,b)=>a+b,0),1,1e-10);
    const d=best(p);if(p[d]>=threshold){covered++;matrix[a][d]++;if(a===d)correct++;}
    brier+=p.reduce((s,v,c)=>s+(v-(c===a?1:0))**2,0);logLoss-=Math.log(Math.max(p[a],1e-15));}
  const recalls=matrix.map((r,c)=>r.reduce((a,b)=>a+b,0)?r[c]/r.reduce((a,b)=>a+b,0):null);
  return{samples:rows.length,correct,covered,coverage:rows.length?covered/rows.length:0,accuracy:covered?correct/covered:0,
    balancedAccuracy:recalls.includes(null)?null:recalls.reduce((a,b)=>a+b,0)/3,
    brierScore:rows.length?brier/rows.length:null,logLoss:rows.length?logLoss/rows.length:null,recalls,matrix};
}
function check(a,b){assert.equal(a.samples,b.sampleCount);assert.equal(a.covered,b.coveredCount);
  for(const key of ['coverage','accuracy','brierScore','logLoss'])near(a[key],b[key],.000051);
  if(a.balancedAccuracy===null)assert.equal(b.balancedAccuracy,null);
  else near(a.recalls.reduce((s,v)=>s+Math.round(v*10000)/10000,0)/3,b.balancedAccuracy,.000051);
  for(let c=0;c<3;c++){if(a.recalls[c]===null)assert.equal(b.recalls[names[c]],null);else near(a.recalls[c],b.recalls[names[c]],.000051);
    for(let d=0;d<3;d++)assert.equal(a.matrix[c][d],b.confusionMatrix[names[c]][names[d]]);}}
function scaling(rows){const mean=Array(20).fill(0),std=Array(20).fill(0);let n=0;
  for(const row of rows){n++;for(let j=0;j<20;j++){const delta=row.x[j]-mean[j];mean[j]+=delta/n;std[j]+=delta*(row.x[j]-mean[j]);}}
  return{mean,std:std.map(v=>Math.sqrt(Math.max(0,v/n)))};}
const scaled=(row,scale)=>row.x.map((v,j)=>scale.std[j]===0?0:(v-scale.mean[j])/scale.std[j]);
function probabilities(w,x){assert.equal(w.length,42);const logits=[w[20],w[41],0];
  for(let j=0;j<20;j++){logits[0]+=w[j]*x[j];logits[1]+=w[j+21]*x[j];}
  const max=Math.max(...logits),p=logits.map(v=>Math.exp(v-max)),sum=p.reduce((a,b)=>a+b,0);return p.map(v=>v/sum);}
function counts(rows){return rows.reduce((n,r)=>(n[names.indexOf(r.actual)]++,n),[0,0,0]);}
const quantile=(xs,p)=>xs.slice().sort((a,b)=>a-b)[Math.ceil(p*xs.length)-1];
function paired(rows,left,right){let wins=0,losses=0;for(const row of rows){const a=names.indexOf(row.actual),l=best(row[left])===a,r=best(row[right])===a;
  if(!l&&r)wins++;if(l&&!r)losses++;}return{wins,losses,net:wins-losses};}
// 单独重算完整三类梯度与中心化L2；不以汇总converged标记替代收敛证据。
function objective(rows,scale,w){let loss=0;const g=Array(42).fill(0);
  for(const row of rows){const x=scaled(row,scale),p=probabilities(w,x),a=names.indexOf(row.actual),logits=[w[20],w[41],0];
    for(let j=0;j<20;j++){logits[0]+=w[j]*x[j];logits[1]+=w[j+21]*x[j];}
    const max=Math.max(...logits);loss+=(max+Math.log(logits.reduce((s,v)=>s+Math.exp(v-max),0))-logits[a])/rows.length;
    for(let c=0;c<2;c++){const error=(p[c]-(a===c?1:0))/rows.length;for(let j=0;j<20;j++)g[c*21+j]+=error*x[j];g[c*21+20]+=error;}}
  for(let j=0;j<20;j++){const a=w[j],b=w[j+21];loss+=.01/3*(a*a+b*b-a*b);g[j]+=.01/3*(2*a-b);g[j+21]+=.01/3*(2*b-a);}
  let gradient=0;for(let j=0;j<21;j++)gradient=Math.max(gradient,Math.abs(g[j]),Math.abs(g[j+21]),Math.abs(g[j]+g[j+21]));return{loss,gradient};}
assert.equal(report.version,'ohlc-full-history-ridge-v1');assert.equal(report.promotionAllowed,false);assert.match(report.gitCommit,/^[0-9a-f]{40}$/);
assert.equal(report.lambda,.01);assert.equal(report.signalThreshold,.55);assert.equal(report.cutoffExclusive,'2024-11-11');
assert.equal(report.barCount,4382);assert.equal(report.barHash,state.barHash);assert.equal(report.barHash,ridge.barHash);
assert.equal(report.stateHash,fileHash(stateFile));assert.equal(report.ridgeHash,fileHash(ridgeFile));
assert.deepEqual(Object.keys(report.sourceHashes).sort(),['HistoryChecks.java','HistoryRun.java','HistorySlice.java','RidgeFit.java','SoftmaxFit.java','TrainingProbe.java']);
for(const [name,hash]of Object.entries(report.sourceHashes))assert.equal(sha(fs.readFileSync(path.join(__dirname,name))),hash);
assert.equal(report.protocolHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-02-history-protocol.md'))));
assert.deepEqual(report.featureNames,featureNames);assert.equal(report.inputs.length,4361);assert.equal(report.folds.length,3);
const byDate=new Map(),digest=crypto.createHash('sha256');for(const name of featureNames)digest.update(name+'\0');
for(const [i,row]of report.inputs.entries()){assert.ok(names.includes(row.actual));assert.equal(row.x.length,20);assert.ok(row.x.every(Number.isFinite));
  assert.ok(row.date<row.target&&row.target<report.cutoffExclusive);if(i)assert.ok(report.inputs[i-1].date<row.date);
  assert.ok(!byDate.has(row.date));byDate.set(row.date,row);digest.update(row.date+'\0'+row.target+'\0'+row.actual+'\0');
  for(const v of row.x){const bytes=Buffer.alloc(8);bytes.writeDoubleBE(v);digest.update(bytes);}}
assert.equal(report.inputHash,digest.digest('hex'));
const all=[],foldResults=[];
for(const [i,fold]of report.folds.entries()){
  const old=state.folds[i],frozen=ridge.folds[i].results.find(r=>r.profile==='OHLC_20'&&r.lambda===.01);
  assert.equal(fold.start,old.start);assert.equal(fold.end,old.end);assert.equal(fold.trainingCount,3640+i*240);
  const train=report.inputs.filter(r=>r.date<fold.start&&r.target<fold.start),pit=old.training.map(r=>byDate.get(r.date));
  assert.deepEqual(fold.trainingDates,train.map(r=>r.date));assert.equal(train.length,fold.trainingCount);
  assert.deepEqual(fold.pitTrainingDates,old.training.map(r=>r.date));assert.equal(pit.length,779+i*240);
  assert.equal(fold.trainStart,train[0].date);assert.equal(fold.trainEnd,train.at(-1).date);assert.equal(fold.trainTargetEnd,train.at(-1).target);
  assert.ok(fold.trainTargetEnd<fold.start);
  for(const [j,row]of pit.entries()){const saved=old.training[j];assert.equal(row.target,saved.target);assert.equal(row.actual,saved.actual);
    assert.ok(row.target<fold.start);for(const [name,key]of [['volatility20','volatility'],['overnightGap','gap'],['return1','return1'],['return20','return20']])near(row.x[featureNames.indexOf(name)],saved.input[key]);}
  const scale=scaling(train),pitScale=scaling(pit),newCounts=counts(train),oldCounts=counts(pit);
  for(let j=0;j<20;j++){near(scale.mean[j],fold.mean[j]);near(scale.std[j],fold.std[j]);near(pitScale.mean[j],fold.pitMean[j]);near(pitScale.std[j],fold.pitStd[j]);}
  for(let c=0;c<3;c++){assert.equal(newCounts[c],fold.trainLabels[names[c]]);assert.equal(oldCounts[c],fold.pitLabels[names[c]]);}
  assert.deepEqual(fold.referenceWeights,frozen.fit.weights);assert.equal(fold.fit.converged,true);assert.equal(fold.fit.status,'CONVERGED');
  assert.ok(fold.fit.weights.every(Number.isFinite));assert.ok(fold.fit.trace.length>=2&&fold.fit.trace.length<=101);
  for(const [j,step]of fold.fit.trace.entries()){assert.equal(step.iteration,j);assert.ok(Number.isFinite(step.loss)&&Number.isFinite(step.gradient));
    assert.equal(step.rank,42);assert.ok(Number.isFinite(step.condition)&&step.condition>=1);if(j)assert.ok(step.loss<=fold.fit.trace[j-1].loss+1e-12);}
  const fitted=objective(train,scale,fold.fit.weights);near(fitted.loss,fold.fit.trace.at(-1).loss,1e-11);near(fitted.gradient,fold.fit.trace.at(-1).gradient,1e-11);
  assert.ok(fitted.gradient<=1e-6);assert.equal(fold.repeatDifference,0);assert.ok(fold.referenceDifference<=1e-12);
  const trainingRows=train.map(row=>({...row,candidate:probabilities(fold.fit.weights,scaled(row,scale))}));
  check(score(trainingRows,'candidate'),fold.training.all);check(score(trainingRows,'candidate',.55),fold.training.signals);
  const expected=report.inputs.filter(r=>r.date>=fold.start&&r.date<=fold.end);
  assert.deepEqual(fold.predictions.map(r=>r.date),expected.map(r=>r.date));assert.equal(expected.length,240);
  for(const [j,row]of fold.predictions.entries()){const raw=byDate.get(row.date),saved=old.predictions[j];
    for(const key of ['date','target','actual']){assert.equal(row[key],raw[key]);assert.equal(row[key],saved[key]);}
    assert.deepEqual(row.reference,saved.reference);assert.deepEqual(row.oldPrior,saved.prior);
    const p=probabilities(fold.fit.weights,scaled(raw,scale)),q=probabilities(fold.referenceWeights,scaled(raw,pitScale));
    for(let c=0;c<3;c++){near(row.candidate[c],p[c]);near(row.reference[c],q[c]);near(row.oldPrior[c],oldCounts[c]/pit.length);near(row.newPrior[c],newCounts[c]/train.length);}
    if(all.length)assert.ok(all.at(-1).date<row.date);all.push(row);}
  const rows=fold.predictions,selected=rows.filter(r=>Math.max(...r.candidate)>=.55);
  assert.deepEqual(fold.selectedDates,selected.map(r=>r.date));check(score(rows,'candidate'),fold.validation.all);check(score(rows,'candidate',.55),fold.validation.signals);
  for(const [key,field]of [['oldPrior','oldPriorOnSignals'],['newPrior','newPriorOnSignals']]){if(selected.length)check(score(selected,key),fold[field]);else assert.equal(fold[field],null);}
  const atrIndex=featureNames.indexOf('atr14'),trainAtr=train.map(r=>r.x[atrIndex]),pitAtr=pit.map(r=>r.x[atrIndex]),valAtr=expected.map(r=>r.x[atrIndex]);
  const atr={trainMedian:quantile(trainAtr,.5),pitMedian:quantile(pitAtr,.5),validationMedian:quantile(valAtr,.5),
    trainQ75:quantile(trainAtr,.75),validationAboveTrainQ75:valAtr.filter(v=>v>quantile(trainAtr,.75)).length};
  foldResults.push({start:fold.start,end:fold.end,trainingCount:train.length,trainLabels:newCounts,atr,training:score(trainingRows,'candidate'),
    candidate:score(rows,'candidate'),reference:score(rows,'reference'),oldPrior:score(rows,'oldPrior'),newPrior:score(rows,'newPrior'),
    againstReference:paired(rows,'reference','candidate'),signals:score(rows,'candidate',.55),oldPriorOnSignals:score(selected,'oldPrior'),newPriorOnSignals:score(selected,'newPrior')});
}
assert.equal(all.length,720);
if(process.argv[3]){const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
  for(const key of ['sourceHashes','protocolHash','stateHash','ridgeHash','barHash','barCount','inputHash','featureNames','featureVersion','ruleVersion','lambda','signalThreshold','cutoffExclusive','promotionAllowed','inputs','folds'])assert.deepEqual(report[key],repeat[key]);}
const candidate=score(all,'candidate'),reference=score(all,'reference'),oldPrior=score(all,'oldPrior'),newPrior=score(all,'newPrior');
const selected=all.filter(r=>Math.max(...r.candidate)>=.55),signals=score(all,'candidate',.55),oldPriorOnSignals=score(selected,'oldPrior'),newPriorOnSignals=score(selected,'newPrior');
const gates={accuracy:candidate.accuracy>reference.accuracy&&candidate.accuracy>oldPrior.accuracy&&candidate.accuracy>newPrior.accuracy,
  balancedGain:candidate.balancedAccuracy>=reference.balancedAccuracy+.02,recalls:candidate.recalls.every(v=>v!==null&&v>=.25),
  brier:candidate.brierScore<=reference.brierScore,logLoss:candidate.logLoss<=reference.logLoss};
const signalGates={coverage:signals.coverage>=.3,sameDateBaselines:signals.accuracy>oldPriorOnSignals.accuracy&&signals.accuracy>newPriorOnSignals.accuracy,
  recalls:signals.recalls.every(v=>v!==null&&v>=.25)};
const blocks=[];for(let j=0;j<all.length;j+=20){const rows=all.slice(j,j+20);blocks.push({start:rows[0].date,end:rows.at(-1).date,candidate:score(rows,'candidate'),reference:score(rows,'reference'),newPrior:score(rows,'newPrior')});}
console.log(JSON.stringify({status:'PASS',promotionAllowed:false,gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean),
  candidate,reference,oldPrior,newPrior,signals,oldPriorOnSignals,newPriorOnSignals,
  againstReference:paired(all,'reference','candidate'),againstOldPrior:paired(all,'oldPrior','candidate'),againstNewPrior:paired(all,'newPrior','candidate'),foldResults,blocks},null,2));
