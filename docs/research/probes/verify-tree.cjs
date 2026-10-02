// 独立绑定真实日线、复算折内缩放，逐棵遍历原生JSON复核概率与评分。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),cp=require('node:child_process');
const {predict}=require('./TreeMath.cjs'),file=process.argv[2];if(!file)throw new Error('需要树实验JSON路径');
const report=JSON.parse(fs.readFileSync(file,'utf8')),refFile=path.join(__dirname,'../2026-10-02-history-check.json'),reference=JSON.parse(fs.readFileSync(refFile,'utf8'));
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-history.cjs'),refFile],{stdio:'pipe'});
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),labels=['BULLISH','NEUTRAL','BEARISH'],best=p=>p.indexOf(Math.max(...p));
const near=(a,b,tol=1e-12)=>assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=tol,`${a} != ${b}`);
function score(rows,key,threshold=0){const matrix=Array.from({length:3},()=>[0,0,0]);let covered=0,correct=0,brier=0,loss=0;
  for(const row of rows){const p=row[key],a=labels.indexOf(row.actual);assert.ok(a>=0);assert.equal(p.length,3);assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));near(p.reduce((a,b)=>a+b,0),1,1e-6);
    const d=best(p);if(p[d]>=threshold){covered++;matrix[a][d]++;if(a===d)correct++;}brier+=p.reduce((s,v,c)=>s+(v-(c===a?1:0))**2,0);loss-=Math.log(Math.max(p[a],1e-15));}
  const recalls=matrix.map((r,c)=>r.reduce((a,b)=>a+b,0)?r[c]/r.reduce((a,b)=>a+b,0):null);
  return{samples:rows.length,correct,covered,coverage:rows.length?covered/rows.length:0,accuracy:covered?correct/covered:0,
    balancedAccuracy:recalls.includes(null)?null:recalls.reduce((a,b)=>a+b,0)/3,brierScore:rows.length?brier/rows.length:null,logLoss:rows.length?loss/rows.length:null,recalls,matrix};}
function check(a,b){assert.equal(a.samples,b.sampleCount);assert.equal(a.covered,b.coveredCount);
  for(const k of ['coverage','accuracy','brierScore','logLoss'])near(a[k],b[k],.000051);
  if(a.balancedAccuracy===null)assert.equal(b.balancedAccuracy,null);else near(a.recalls.reduce((s,v)=>s+Math.round(v*10000)/10000,0)/3,b.balancedAccuracy,.000051);
  for(let c=0;c<3;c++){if(a.recalls[c]===null)assert.equal(b.recalls[labels[c]],null);else near(a.recalls[c],b.recalls[labels[c]],.000051);
    for(let d=0;d<3;d++)assert.equal(a.matrix[c][d],b.confusionMatrix[labels[c]][labels[d]]);}}
function scaling(rows){const mean=Array(20).fill(0),std=Array(20).fill(0);let n=0;for(const r of rows){n++;for(let j=0;j<20;j++){const delta=r.x[j]-mean[j];mean[j]+=delta/n;std[j]+=delta*(r.x[j]-mean[j]);}}
  return{mean,std:std.map(v=>Math.sqrt(Math.max(0,v/n)))};}
function linear(w,row,scale){const x=row.x.map((v,j)=>scale.std[j]===0?0:(v-scale.mean[j])/scale.std[j]),z=[w[20],w[41],0];assert.equal(w.length,42);
  for(let j=0;j<20;j++){z[0]+=w[j]*x[j];z[1]+=w[j+21]*x[j];}const max=Math.max(...z),e=z.map(v=>Math.exp(v-max)),sum=e.reduce((a,b)=>a+b,0);return e.map(v=>v/sum);}
function label(base,target){const scale=Math.max((base.split('.')[1]||'').length,(target.split('.')[1]||'').length),integer=s=>{const[a,b='']=s.split('.');return BigInt(a+b.padEnd(scale,'0'));};
  const b=integer(base),t=integer(target);return t*200n>b*201n?'BULLISH':t*200n<b*199n?'BEARISH':'NEUTRAL';}
function paired(rows,left,right){let wins=0,losses=0;for(const r of rows){const a=labels.indexOf(r.actual),l=best(r[left])===a,q=best(r[right])===a;if(!l&&q)wins++;if(l&&!q)losses++;}return{wins,losses,net:wins-losses};}
function native(model){assert.deepEqual([...model.featureOrder].sort(),reference.featureNames);assert.deepEqual([...model.labelOrder].sort(),[...labels].sort());
  const l=model.state.learner,m=l.gradient_booster.model;assert.equal(l.objective.name,'multi:softprob');assert.equal(l.gradient_booster.name,'gbtree');
  assert.equal(l.learner_model_param.num_feature,'20');assert.equal(l.learner_model_param.num_class,'3');assert.equal(l.learner_model_param.num_target,'1');near(Number(l.learner_model_param.base_score),.5);
  assert.deepEqual(model.state.version,[1,6,2]);assert.equal(m.gbtree_model_param.num_parallel_tree,'1');assert.equal(m.gbtree_model_param.num_trees,'600');assert.equal(m.trees.length,600);assert.equal(m.tree_info.length,600);
  for(const[i,t]of m.trees.entries()){assert.equal(t.id,i);assert.equal(m.tree_info[i],i%3);assert.equal(t.tree_param.num_feature,'20');assert.equal(t.tree_param.num_deleted,'0');
    const n=Number(t.tree_param.num_nodes);assert.ok(n>=1&&n<=15);for(const key of ['left_children','right_children','split_indices','split_conditions','default_left','split_type'])assert.equal(t[key].length,n);
    const reached=new Set();function walk(j,depth){assert.ok(Number.isInteger(j)&&j>=0&&j<n&&!reached.has(j));reached.add(j);assert.ok(Number.isFinite(t.split_conditions[j]));assert.equal(t.split_type[j],0);
      const a=t.left_children[j],b=t.right_children[j];if(a===-1){assert.equal(b,-1);return;}assert.ok(depth<3);assert.ok(t.split_indices[j]>=0&&t.split_indices[j]<20);assert.ok(t.default_left[j]===0||t.default_left[j]===1);walk(a,depth+1);walk(b,depth+1);}
    walk(0,0);assert.equal(reached.size,n);}}
assert.equal(report.version,'ohlc-full-history-xgboost-v1');assert.equal(report.promotionAllowed,false);assert.match(report.gitCommit,/^[0-9a-f]{40}$/);
assert.deepEqual(report.parameters,{numTrees:200,eta:.03,gamma:0,maxDepth:3,minChildWeight:5,subsample:.8,featureSubsample:.8,lambda:1,alpha:0,nThread:1,seed:20260901});
assert.equal(report.probabilitySumTolerance,1e-6);assert.equal(report.predictionCheckTolerance,2e-6);assert.equal(report.signalThreshold,.55);assert.equal(report.cutoffExclusive,'2024-11-11');
assert.equal(report.barCount,4382);assert.equal(report.barHash,reference.barHash);assert.equal(report.inputHash,reference.inputHash);
assert.equal(report.referenceHash,sha(Buffer.from(fs.readFileSync(refFile,'utf8').replace(/\r\n/g,'\n'),'utf8')));assert.equal(report.featureVersion,reference.featureVersion);assert.equal(report.ruleVersion,reference.ruleVersion);
assert.deepEqual(report.featureNames,reference.featureNames);assert.equal(report.inputs.length,4361);assert.equal(report.bars.length,4382);assert.equal(report.folds.length,3);
const root=path.join(__dirname,'../../..'),expectedSources=[...['TreeFit.java','TreeChecks.java','TreeRun.java','HistorySlice.java','HistoryChecks.java','HistoryRun.java','TrainingProbe.java','RidgeFit.java','SoftmaxFit.java'].map(n=>'docs/research/probes/'+n),
  ...['XgboostGoldTrainer.java','XgboostProperties.java','GoldFeatureCalculator.java','GoldOhlcFeatures.java','DirectionProbabilities.java','ForecastEvaluator.java'].map(n=>'backend/src/main/java/com/opspilot/ai/forecast/learning/'+n),'backend/src/main/java/com/opspilot/ai/forecast/GoldForecastRule.java'];
assert.deepEqual(Object.keys(report.sourceHashes).sort(),expectedSources.sort());for(const[name,hash]of Object.entries(report.sourceHashes))assert.equal(sha(fs.readFileSync(path.join(root,name))),hash);
assert.equal(report.protocolHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-protocol.md'))));
let text='';for(const[i,b]of report.bars.entries()){for(const key of ['open','high','low','close']){assert.match(b[key],/^\d+(\.\d+)?$/);assert.ok(Number(b[key])>0&&Number.isFinite(Number(b[key])));}
  assert.ok(Number(b.high)>=Math.max(Number(b.open),Number(b.close),Number(b.low)));assert.ok(Number(b.low)<=Math.min(Number(b.open),Number(b.close)));assert.ok(b.date<report.cutoffExclusive);
  if(i)assert.ok(report.bars[i-1].date<b.date);text+=`${b.date}|${b.open}|${b.high}|${b.low}|${b.close}\n`;}
assert.equal(sha(Buffer.from(text,'utf8')),report.barHash);
const byDate=new Map(),digest=crypto.createHash('sha256');for(const name of report.featureNames)digest.update(name+'\0');
for(const[i,row]of report.inputs.entries()){assert.deepEqual(row,reference.inputs[i]);const b=report.bars[i+20],t=report.bars[i+21];assert.equal(row.date,b.date);assert.equal(row.target,t.date);assert.equal(row.actual,label(b.close,t.close));
  assert.ok(row.date<row.target&&row.target<report.cutoffExclusive);assert.equal(row.x.length,20);assert.ok(row.x.every(Number.isFinite));if(i)assert.ok(report.inputs[i-1].date<row.date);byDate.set(row.date,row);
  digest.update(row.date+'\0'+row.target+'\0'+row.actual+'\0');for(const v of row.x){const bytes=Buffer.alloc(8);bytes.writeDoubleBE(v);digest.update(bytes);}}
assert.equal(report.inputHash,digest.digest('hex'));
const all=[],foldResults=[];let maxDifference=0;
for(const[i,fold]of report.folds.entries()){const old=reference.folds[i];assert.equal(fold.start,old.start);assert.equal(fold.end,old.end);
  const train=report.inputs.filter(r=>r.date<fold.start&&r.target<fold.start);assert.equal(fold.trainingCount,3640+i*240);assert.equal(train.length,fold.trainingCount);
  assert.deepEqual(fold.trainingDates,train.map(r=>r.date));assert.deepEqual(fold.trainingDates,old.trainingDates);assert.equal(fold.trainStart,train[0].date);assert.equal(fold.trainTargetEnd,train.at(-1).target);
  const scale=scaling(train),counts=labels.map(l=>train.filter(r=>r.actual===l).length);for(let j=0;j<20;j++){near(scale.mean[j],fold.mean[j]);near(scale.std[j],fold.std[j]);}for(let c=0;c<3;c++)assert.equal(fold.trainLabels[labels[c]],counts[c]);
  native(fold.model);assert.equal(fold.repeatDifference,0);assert.ok(fold.referenceDifference<=1e-12);
  const training=fold.trainingPredictions;assert.equal(training.length,train.length);
  for(let j=0;j<training.length;j++){const saved=training[j],input=train[j];for(const key of ['date','target','actual'])assert.equal(saved[key],input[key]);
    const p=predict(fold.model,input.x,report.featureNames,scale);assert.equal(best(p),best(saved.candidate));assert.equal(Math.max(...p)>=.55,Math.max(...saved.candidate)>=.55);
    for(let c=0;c<3;c++){near(saved.candidate[c],p[c],2e-6);maxDifference=Math.max(maxDifference,Math.abs(saved.candidate[c]-p[c]));}}
  check(score(training,'candidate'),fold.training.all);check(score(training,'candidate',.55),fold.training.signals);
  const expected=report.inputs.filter(r=>r.date>=fold.start&&r.date<=fold.end),rows=fold.predictions;assert.equal(rows.length,240);assert.deepEqual(rows.map(r=>r.date),expected.map(r=>r.date));
  for(const[j,row]of rows.entries()){const input=byDate.get(row.date),saved=old.predictions[j];for(const key of ['date','target','actual']){assert.equal(row[key],input[key]);assert.equal(row[key],saved[key]);}
    assert.deepEqual(row.reference,saved.candidate);assert.deepEqual(row.pitReference,saved.reference);assert.deepEqual(row.prior,saved.newPrior);
    const p=predict(fold.model,input.x,report.featureNames,scale),q=linear(old.fit.weights,input,scale);assert.equal(best(p),best(row.candidate));assert.equal(Math.max(...p)>=.55,Math.max(...row.candidate)>=.55);
    for(let c=0;c<3;c++){near(row.candidate[c],p[c],2e-6);maxDifference=Math.max(maxDifference,Math.abs(row.candidate[c]-p[c]));near(row.reference[c],q[c]);near(row.prior[c],counts[c]/train.length);}
    if(all.length)assert.ok(all.at(-1).date<row.date);all.push(row);}
  const selected=rows.filter(r=>Math.max(...r.candidate)>=.55);assert.deepEqual(fold.selectedDates,selected.map(r=>r.date));check(score(rows,'candidate'),fold.validation.all);check(score(rows,'candidate',.55),fold.validation.signals);
  if(selected.length)check(score(selected,'prior'),fold.priorOnSignals);else assert.equal(fold.priorOnSignals,null);
  foldResults.push({start:fold.start,trainingCount:train.length,training:score(training,'candidate'),candidate:score(rows,'candidate'),reference:score(rows,'reference'),prior:score(rows,'prior'),signals:score(rows,'candidate',.55),priorOnSignals:score(selected,'prior'),againstReference:paired(rows,'reference','candidate')});}
assert.equal(all.length,720);
if(process.argv[3]){const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));for(const key of ['sourceHashes','protocolHash','referenceHash','barHash','barCount','inputHash','featureNames','featureVersion','ruleVersion','parameters','probabilitySumTolerance','predictionCheckTolerance','signalThreshold','cutoffExclusive','promotionAllowed','bars','inputs','folds'])assert.deepEqual(report[key],repeat[key]);}
const candidate=score(all,'candidate'),frozen=score(all,'reference'),pitReference=score(all,'pitReference'),prior=score(all,'prior'),signals=score(all,'candidate',.55),selected=all.filter(r=>Math.max(...r.candidate)>=.55),priorOnSignals=score(selected,'prior');
const gates={accuracy:candidate.accuracy>frozen.accuracy&&candidate.accuracy>pitReference.accuracy&&candidate.accuracy>prior.accuracy,balancedGain:candidate.balancedAccuracy>=frozen.balancedAccuracy+.02,
  recalls:candidate.recalls.every(v=>v!==null&&v>=.25),brier:candidate.brierScore<=frozen.brierScore,logLoss:candidate.logLoss<=frozen.logLoss};
const signalGates={coverage:signals.coverage>=.3,sameDateBaseline:signals.accuracy>priorOnSignals.accuracy,recalls:signals.recalls.every(v=>v!==null&&v>=.25)};
const blocks=[];for(let i=0;i<all.length;i+=20){const rows=all.slice(i,i+20);blocks.push({start:rows[0].date,end:rows.at(-1).date,candidate:score(rows,'candidate'),reference:score(rows,'reference'),prior:score(rows,'prior')});}
console.log(JSON.stringify({status:'PASS',promotionAllowed:false,maxPredictionDifference:maxDifference,gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean),candidate,reference:frozen,pitReference,prior,signals,priorOnSignals,
  againstReference:paired(all,'reference','candidate'),againstPrior:paired(all,'prior','candidate'),classPairs:labels.map(l=>({actual:l,...paired(all.filter(r=>r.actual===l),'reference','candidate')})),foldResults,blocks},null,2));
