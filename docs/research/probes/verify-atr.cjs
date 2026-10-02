// 从十进制真实日线绑定基准日分母，独立复算ATR变换、缩放、概率与门槛。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),cp=require('node:child_process');
const file=process.argv[2];if(!file)throw new Error('需要ATR研究JSON路径');
const report=JSON.parse(fs.readFileSync(file,'utf8')),referenceFile=path.join(__dirname,'../2026-10-02-history-check.json');
const reference=JSON.parse(fs.readFileSync(referenceFile,'utf8'));
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-history.cjs'),referenceFile],{stdio:'pipe'});
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),names=['BULLISH','NEUTRAL','BEARISH'],best=p=>p.indexOf(Math.max(...p));
const near=(a,b,tol=1e-12)=>assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=tol,`${a} != ${b}`);
function score(rows,key,threshold=0){const matrix=Array.from({length:3},()=>[0,0,0]);let covered=0,correct=0,brier=0,logLoss=0;
  for(const row of rows){const p=row[key],a=names.indexOf(row.actual);assert.ok(a>=0);assert.equal(p.length,3);assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));near(p.reduce((a,b)=>a+b,0),1,1e-10);
    const d=best(p);if(p[d]>=threshold){covered++;matrix[a][d]++;if(a===d)correct++;}brier+=p.reduce((s,v,c)=>s+(v-(c===a?1:0))**2,0);logLoss-=Math.log(Math.max(p[a],1e-15));}
  const recalls=matrix.map((r,c)=>r.reduce((a,b)=>a+b,0)?r[c]/r.reduce((a,b)=>a+b,0):null);
  return{samples:rows.length,correct,covered,coverage:rows.length?covered/rows.length:0,accuracy:covered?correct/covered:0,balancedAccuracy:recalls.includes(null)?null:recalls.reduce((a,b)=>a+b,0)/3,
    brierScore:rows.length?brier/rows.length:null,logLoss:rows.length?logLoss/rows.length:null,recalls,matrix};}
function check(a,b){assert.equal(a.samples,b.sampleCount);assert.equal(a.covered,b.coveredCount);for(const k of ['coverage','accuracy','brierScore','logLoss'])near(a[k],b[k],.000051);
  if(a.balancedAccuracy===null)assert.equal(b.balancedAccuracy,null);else near(a.recalls.reduce((s,v)=>s+Math.round(v*10000)/10000,0)/3,b.balancedAccuracy,.000051);
  for(let c=0;c<3;c++){if(a.recalls[c]===null)assert.equal(b.recalls[names[c]],null);else near(a.recalls[c],b.recalls[names[c]],.000051);for(let d=0;d<3;d++)assert.equal(a.matrix[c][d],b.confusionMatrix[names[c]][names[d]]);}}
function scaling(rows){const mean=Array(20).fill(0),std=Array(20).fill(0);let n=0;for(const r of rows){n++;for(let j=0;j<20;j++){const delta=r.x[j]-mean[j];mean[j]+=delta/n;std[j]+=delta*(r.x[j]-mean[j]);}}
  return{mean,std:std.map(v=>Math.sqrt(Math.max(0,v/n)))};}
const scaled=(row,scale)=>row.x.map((v,j)=>scale.std[j]===0?0:(v-scale.mean[j])/scale.std[j]);
function logits(w,x){assert.equal(w.length,42);const z=[w[20],w[41],0];for(let j=0;j<20;j++){z[0]+=w[j]*x[j];z[1]+=w[j+21]*x[j];}return z;}
function probabilities(w,x){const z=logits(w,x),max=Math.max(...z),p=z.map(v=>Math.exp(v-max)),sum=p.reduce((a,b)=>a+b,0);return p.map(v=>v/sum);}
function counts(rows){return rows.reduce((n,r)=>(n[names.indexOf(r.actual)]++,n),[0,0,0]);}
function paired(rows,left,right){let wins=0,losses=0;for(const r of rows){const a=names.indexOf(r.actual),l=best(r[left])===a,q=best(r[right])===a;if(!l&&q)wins++;if(l&&!q)losses++;}return{wins,losses,net:wins-losses};}
function objective(rows,scale,w){let loss=0;const g=Array(42).fill(0);for(const row of rows){const x=scaled(row,scale),p=probabilities(w,x),a=names.indexOf(row.actual),z=logits(w,x),max=Math.max(...z);
  loss+=(max+Math.log(z.reduce((s,v)=>s+Math.exp(v-max),0))-z[a])/rows.length;for(let c=0;c<2;c++){const e=(p[c]-(a===c?1:0))/rows.length;for(let j=0;j<20;j++)g[c*21+j]+=e*x[j];g[c*21+20]+=e;}}
  for(let j=0;j<20;j++){const a=w[j],b=w[j+21];loss+=.01/3*(a*a+b*b-a*b);g[j]+=.01/3*(2*a-b);g[j+21]+=.01/3*(2*b-a);}
  let gradient=0;for(let j=0;j<21;j++)gradient=Math.max(gradient,Math.abs(g[j]),Math.abs(g[j+21]),Math.abs(g[j]+g[j+21]));return{loss,gradient};}
// 标签边界用十进制整数交叉乘法复核，避免0.5%附近浮点误分类。
function label(base,target){const scale=Math.max((base.split('.')[1]||'').length,(target.split('.')[1]||'').length);
  const integer=s=>{const [a,b='']=s.split('.');return BigInt(a+b.padEnd(scale,'0'));};const b=integer(base),t=integer(target);
  return t*200n>b*201n?'BULLISH':t*200n<b*199n?'BEARISH':'NEUTRAL';}
assert.equal(report.version,'ohlc-relative-atr-v1');assert.equal(report.promotionAllowed,false);assert.match(report.gitCommit,/^[0-9a-f]{40}$/);
assert.equal(report.lambda,.01);assert.equal(report.signalThreshold,.55);assert.equal(report.cutoffExclusive,'2024-11-11');assert.equal(report.barCount,4382);
assert.equal(report.featureVersion,'ohlc-atr-close-percent-v1');assert.equal(report.rawFeatureVersion,reference.featureVersion);assert.equal(report.ruleVersion,reference.ruleVersion);
assert.equal(report.referenceHash,sha(Buffer.from(fs.readFileSync(referenceFile,'utf8').replace(/\r\n/g,'\n'),'utf8')));assert.equal(report.barHash,reference.barHash);assert.equal(report.rawInputHash,reference.inputHash);
assert.deepEqual(report.featureNames,reference.featureNames);assert.equal(report.inputs.length,4361);assert.equal(report.prices.length,4361);assert.equal(report.bars.length,4382);assert.equal(report.folds.length,3);
assert.deepEqual(Object.keys(report.sourceHashes).sort(),['AtrChecks.java','AtrRun.java','AtrScale.java','HistoryChecks.java','HistoryRun.java','HistorySlice.java','RidgeFit.java','SoftmaxFit.java','TrainingProbe.java']);
for(const [name,hash]of Object.entries(report.sourceHashes))assert.equal(sha(fs.readFileSync(path.join(__dirname,name))),hash);
assert.equal(report.protocolHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-02-atr-protocol.md'))));
const bars=new Map();let barText='';for(const [i,b]of report.bars.entries()){
  for(const key of ['open','high','low','close']){assert.match(b[key],/^\d+(\.\d+)?$/);assert.ok(Number(b[key])>0&&Number.isFinite(Number(b[key])));}
  assert.ok(Number(b.high)>=Math.max(Number(b.open),Number(b.close),Number(b.low)));assert.ok(Number(b.low)<=Math.min(Number(b.open),Number(b.close)));
  assert.ok(b.date<report.cutoffExclusive);if(i)assert.ok(report.bars[i-1].date<b.date);bars.set(b.date,b);barText+=`${b.date}|${b.open}|${b.high}|${b.low}|${b.close}\n`;}
assert.equal(sha(Buffer.from(barText,'utf8')),report.barHash);
const byDate=new Map(),rawByDate=new Map(reference.inputs.map(r=>[r.date,r])),atr=report.featureNames.indexOf('atr14'),digest=crypto.createHash('sha256');
for(const name of report.featureNames)digest.update(name+'\0');
for(const [i,row]of report.inputs.entries()){const raw=reference.inputs[i],price=report.prices[i],base=report.bars[i+20],target=report.bars[i+21];
  for(const key of ['date','target','actual'])assert.equal(row[key],raw[key]);assert.equal(row.date,base.date);assert.equal(row.target,target.date);assert.equal(row.actual,label(base.close,target.close));
  assert.equal(price.date,row.date);near(price.close,Number(bars.get(row.date).close));assert.equal(row.x.length,20);assert.ok(row.x.every(Number.isFinite));
  for(let j=0;j<20;j++)if(j===atr)near(row.x[j],raw.x[j]/price.close*100);else assert.equal(row.x[j],raw.x[j]);
  assert.ok(row.date<row.target&&row.target<report.cutoffExclusive);if(i)assert.ok(report.inputs[i-1].date<row.date);byDate.set(row.date,row);
  digest.update(row.date+'\0'+row.target+'\0'+row.actual+'\0');for(const v of row.x){const bytes=Buffer.alloc(8);bytes.writeDoubleBE(v);digest.update(bytes);}}
assert.equal(report.inputHash,digest.digest('hex'));
const all=[],foldResults=[];
for(const [i,fold]of report.folds.entries()){const old=reference.folds[i];assert.equal(fold.start,old.start);assert.equal(fold.end,old.end);assert.equal(fold.trainingCount,3640+i*240);
  const train=report.inputs.filter(r=>r.date<fold.start&&r.target<fold.start),rawTrain=fold.trainingDates.map(d=>rawByDate.get(d));
  assert.deepEqual(fold.trainingDates,train.map(r=>r.date));assert.deepEqual(fold.trainingDates,old.trainingDates);assert.equal(train.length,fold.trainingCount);
  assert.equal(fold.trainStart,train[0].date);assert.equal(fold.trainTargetEnd,train.at(-1).target);assert.ok(fold.trainTargetEnd<fold.start);
  const scale=scaling(train),rawScale=scaling(rawTrain),trainCounts=counts(train);for(let j=0;j<20;j++){near(scale.mean[j],fold.mean[j]);near(scale.std[j],fold.std[j]);}
  for(let c=0;c<3;c++)assert.equal(trainCounts[c],fold.trainLabels[names[c]]);
  assert.equal(fold.fit.converged,true);assert.equal(fold.fit.status,'CONVERGED');assert.ok(fold.fit.weights.every(Number.isFinite));assert.equal(fold.repeatDifference,0);assert.ok(fold.referenceDifference<=1e-12);
  assert.ok(fold.fit.trace.length>=2&&fold.fit.trace.length<=101);for(const [j,s]of fold.fit.trace.entries()){assert.equal(s.iteration,j);assert.equal(s.rank,42);assert.ok(Number.isFinite(s.loss)&&Number.isFinite(s.gradient)&&Number.isFinite(s.condition));if(j)assert.ok(s.loss<=fold.fit.trace[j-1].loss+1e-12);}
  const fitted=objective(train,scale,fold.fit.weights);near(fitted.loss,fold.fit.trace.at(-1).loss,1e-11);near(fitted.gradient,fold.fit.trace.at(-1).gradient,1e-11);assert.ok(fitted.gradient<=1e-6);
  const trainingRows=train.map(r=>({...r,candidate:probabilities(fold.fit.weights,scaled(r,scale))}));check(score(trainingRows,'candidate'),fold.training.all);check(score(trainingRows,'candidate',.55),fold.training.signals);
  const expected=report.inputs.filter(r=>r.date>=fold.start&&r.date<=fold.end);assert.deepEqual(fold.predictions.map(r=>r.date),expected.map(r=>r.date));assert.equal(expected.length,240);
  for(const [j,row]of fold.predictions.entries()){const raw=byDate.get(row.date),saved=old.predictions[j];for(const key of ['date','target','actual']){assert.equal(row[key],raw[key]);assert.equal(row[key],saved[key]);}
    assert.deepEqual(row.reference,saved.candidate);assert.deepEqual(row.pitReference,saved.reference);assert.deepEqual(row.prior,saved.newPrior);
    const p=probabilities(fold.fit.weights,scaled(raw,scale)),q=probabilities(old.fit.weights,scaled(rawByDate.get(row.date),rawScale));
    for(let c=0;c<3;c++){near(row.candidate[c],p[c]);near(row.reference[c],q[c]);near(row.prior[c],trainCounts[c]/train.length);}
    if(all.length)assert.ok(all.at(-1).date<row.date);all.push(row);}
  const rows=fold.predictions,selected=rows.filter(r=>Math.max(...r.candidate)>=.55);assert.deepEqual(fold.selectedDates,selected.map(r=>r.date));check(score(rows,'candidate'),fold.validation.all);check(score(rows,'candidate',.55),fold.validation.signals);
  if(selected.length)check(score(selected,'prior'),fold.priorOnSignals);else assert.equal(fold.priorOnSignals,null);
  foldResults.push({start:fold.start,trainingCount:train.length,training:score(trainingRows,'candidate'),candidate:score(rows,'candidate'),reference:score(rows,'reference'),pitReference:score(rows,'pitReference'),prior:score(rows,'prior'),
    againstReference:paired(rows,'reference','candidate'),signals:score(rows,'candidate',.55),priorOnSignals:score(selected,'prior')});}
assert.equal(all.length,720);
if(process.argv[3]){const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));for(const key of ['sourceHashes','protocolHash','referenceHash','barHash','barCount','rawInputHash','inputHash','featureNames','featureVersion','rawFeatureVersion','ruleVersion','lambda','signalThreshold','cutoffExclusive','promotionAllowed','bars','prices','inputs','folds'])assert.deepEqual(report[key],repeat[key]);}
const candidate=score(all,'candidate'),frozen=score(all,'reference'),pitReference=score(all,'pitReference'),prior=score(all,'prior'),signals=score(all,'candidate',.55),selected=all.filter(r=>Math.max(...r.candidate)>=.55),priorOnSignals=score(selected,'prior');
const gates={accuracy:candidate.accuracy>frozen.accuracy&&candidate.accuracy>pitReference.accuracy&&candidate.accuracy>prior.accuracy,balancedGain:candidate.balancedAccuracy>=frozen.balancedAccuracy+.02,
  recalls:candidate.recalls.every(v=>v!==null&&v>=.25),brier:candidate.brierScore<=frozen.brierScore,logLoss:candidate.logLoss<=frozen.logLoss};
const signalGates={coverage:signals.coverage>=.3,sameDateBaseline:signals.accuracy>priorOnSignals.accuracy,recalls:signals.recalls.every(v=>v!==null&&v>=.25)};
const blocks=[];for(let j=0;j<all.length;j+=20){const rows=all.slice(j,j+20);blocks.push({start:rows[0].date,end:rows.at(-1).date,candidate:score(rows,'candidate'),reference:score(rows,'reference'),prior:score(rows,'prior')});}
const classPairs=names.map(name=>({actual:name,...paired(all.filter(r=>r.actual===name),'reference','candidate')}));
console.log(JSON.stringify({status:'PASS',promotionAllowed:false,gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean),candidate,reference:frozen,pitReference,prior,signals,priorOnSignals,
  againstReference:paired(all,'reference','candidate'),againstPitReference:paired(all,'pitReference','candidate'),againstPrior:paired(all,'prior','candidate'),classPairs,foldResults,blocks},null,2));
