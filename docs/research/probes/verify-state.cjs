// 从逐日输入独立重算训练分界/状态/频率/指标，不读取数据库。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),cp=require('node:child_process');
const file=process.argv[2];if(!file)throw new Error('需要状态研究 JSON 路径');
const report=JSON.parse(fs.readFileSync(file,'utf8'));
const referenceFile=path.join(__dirname,'../2026-10-02-ridge-check.json'),reference=JSON.parse(fs.readFileSync(referenceFile,'utf8'));
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-ridge.cjs'),referenceFile],{stdio:'pipe'});
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),names=['BULLISH','NEUTRAL','BEARISH'];
const best=p=>p.indexOf(Math.max(...p));
const near=(a,b,tolerance=.000051)=>assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=tolerance,`${a} != ${b}`);
function score(rows,key,threshold=0){
  const matrix=Array.from({length:3},()=>[0,0,0]);let covered=0,correct=0,brier=0,logLoss=0;
  for(const row of rows){
    const p=row[key],actual=names.indexOf(row.actual);assert.equal(p.length,3);assert.ok(actual>=0);
    assert.ok(p.every(v=>Number.isFinite(v)&&v>=0&&v<=1));near(p.reduce((a,b)=>a+b,0),1,1e-10);
    const predicted=best(p);if(p[predicted]>=threshold){covered++;matrix[actual][predicted]++;if(predicted===actual)correct++;}
    brier+=p.reduce((s,v,c)=>s+(v-(c===actual?1:0))**2,0);logLoss-=Math.log(Math.max(p[actual],1e-15));
  }
  const recalls=matrix.map((r,c)=>r.reduce((a,b)=>a+b,0)?r[c]/r.reduce((a,b)=>a+b,0):null);
  return {samples:rows.length,correct,covered,coverage:rows.length?covered/rows.length:0,accuracy:covered?correct/covered:0,
    balancedAccuracy:recalls.includes(null)?null:recalls.reduce((a,b)=>a+b,0)/3,
    brierScore:rows.length?brier/rows.length:null,logLoss:rows.length?logLoss/rows.length:null,recalls,matrix};
}
function check(a,b){
  assert.equal(a.samples,b.sampleCount);assert.equal(a.covered,b.coveredCount);
  for(const field of ['coverage','accuracy','brierScore','logLoss'])near(a[field],b[field]);
  if(a.balancedAccuracy===null)assert.equal(b.balancedAccuracy,null);
  else near(a.recalls.reduce((s,v)=>s+Math.round(v*10000)/10000,0)/3,b.balancedAccuracy);
  for(let c=0;c<3;c++){
    if(a.recalls[c]===null)assert.equal(b.recalls[names[c]],null);else near(a.recalls[c],b.recalls[names[c]]);
    for(let d=0;d<3;d++)assert.equal(a.matrix[c][d],b.confusionMatrix[names[c]][names[d]]);
  }
}
function paired(rows,left,right){let wins=0,losses=0;for(const row of rows){const a=names.indexOf(row.actual),l=best(row[left])===a,r=best(row[right])===a;
  if(!l&&r)wins++;if(l&&!r)losses++;}return{wins,losses,net:wins-losses};}
function trend(input){for(const key of ['volatility','gap','return1','return20'])assert.ok(Number.isFinite(input[key]));
  assert.ok(input.volatility>=0&&input.return1> -100&&input.return20> -100);return ((1+input.return20/100)/(1+input.return1/100)-1)*100;}
function cell(input,cuts){const t=trend(input);return (input.volatility>cuts.volatility?4:0)+(Math.abs(input.gap)>cuts.gap?2:0)
  +(input.return1!==0&&t!==0&&Math.sign(input.return1)!==Math.sign(t)?1:0);}
function member(group,c){if(group==='ALL')return true;if(group==='HIGH_VOL')return !!(c&4);if(group==='OTHER_VOL')return !(c&4);
  if(group==='LARGE_GAP')return !!(c&2);if(group==='SMALL_GAP')return !(c&2);if(group==='CONFLICT')return !!(c&1);if(group==='NO_CONFLICT')return !(c&1);
  return c===Number(group.slice(5));}
const groups=['ALL','HIGH_VOL','OTHER_VOL','LARGE_GAP','SMALL_GAP','CONFLICT','NO_CONFLICT',...Array.from({length:8},(_,i)=>`CELL_${i}`)];
assert.equal(report.version,'fred-price-state-audit-v1');assert.equal(report.promotionAllowed,false);assert.match(report.gitCommit,/^[0-9a-f]{40}$/);
assert.equal(report.cutoffExclusive,'2024-11-11');assert.deepEqual(report.groupNames,groups);assert.equal(report.folds.length,3);
for(const key of ['barHash','contentHash','datasetHash','macroInput'])assert.deepEqual(report[key],reference[key]);
assert.equal(report.referenceHash,sha(Buffer.from(fs.readFileSync(referenceFile,'utf8').replace(/\r\n/g,'\n'),'utf8')));
assert.deepEqual(Object.keys(report.sourceHashes).sort(),['MacroModelProbe.java','SoftmaxFit.java','StateChecks.java','StateCuts.java','StateRun.java','TrainingProbe.java']);
for(const [name,hash]of Object.entries(report.sourceHashes))assert.equal(sha(fs.readFileSync(path.join(__dirname,name))),hash);
assert.equal(sha(fs.readFileSync(path.join(__dirname,'../2026-10-02-state-protocol.md'))),report.protocolHash);
const all=[];
for(const [i,fold]of report.folds.entries()){
  const old=reference.folds[i],frozen=old.results.find(r=>r.profile==='OHLC_20'&&r.lambda===.01);
  for(const key of ['start','end','trainStart','trainTargetEnd','trainingCount'])assert.equal(fold[key],old[key]);
  assert.equal(fold.trainingCount,779+i*240);assert.equal(fold.training.length,fold.trainingCount);assert.equal(fold.predictions.length,240);
  assert.ok(fold.trainTargetEnd<fold.start);assert.ok(fold.referenceDifference<=1e-12);
  const q=Math.ceil(.75*fold.training.length)-1;
  near(fold.cuts.volatility,fold.training.map(r=>r.input.volatility).sort((a,b)=>a-b)[q],1e-12);
  near(fold.cuts.gap,fold.training.map(r=>Math.abs(r.input.gap)).sort((a,b)=>a-b)[q],1e-12);
  const counts=Array.from({length:8},()=>[0,0,0]),global=[0,0,0];
  for(const [j,row]of fold.training.entries()){
    const label=names.indexOf(row.actual);assert.ok(label>=0);assert.ok(row.date<row.target&&row.target<fold.start);
    if(j)assert.ok(fold.training[j-1].date<row.date);counts[cell(row.input,fold.cuts)][label]++;global[label]++;
  }
  assert.equal(fold.training[0].date,fold.trainStart);assert.equal(fold.training.at(-1).target,fold.trainTargetEnd);
  assert.deepEqual(fold.cellCounts,counts);assert.deepEqual(fold.globalCounts,global);
  for(const [j,row]of fold.predictions.entries()){
    const before=frozen.predictions[j];for(const key of ['date','target','actual','prior'])assert.deepEqual(row[key],before[key]);assert.deepEqual(row.reference,before.ridge);
    assert.ok(row.date<row.target&&row.target<report.cutoffExclusive);assert.equal(row.cell,cell(row.input,fold.cuts));near(row.priorTrend,trend(row.input),1e-12);
    let selected=counts[row.cell],n=selected.reduce((a,b)=>a+b,0);if(n<30){selected=global;n=fold.trainingCount;}
    for(let c=0;c<3;c++){near(row.statePrior[c],selected[c]/n,1e-12);near(row.prior[c],global[c]/fold.trainingCount,1e-12);}
    if(all.length)assert.ok(all.at(-1).date<row.date);all.push(row);
  }
  check(score(fold.predictions,'reference'),frozen.validation.all);assert.deepEqual(fold.groups.map(g=>g.name),groups);
  for(const group of fold.groups){const rows=fold.predictions.filter(r=>member(group.name,r.cell));assert.equal(group.count,rows.length);assert.equal(group.small,rows.length<30);
    for(const key of ['reference','prior','statePrior']){if(rows.length){check(score(rows,key),group[key].all);check(score(rows,key,.55),group[key].signals);}else assert.equal(group[key],null);}
    for(const [key,field]of [['reference','priorOnReferenceSignals'],['statePrior','priorOnStateSignals']]){
      const selected=rows.filter(r=>Math.max(...r[key])>=.55);if(selected.length)check(score(selected,'prior'),group[field]);else assert.equal(group[field],null);
    }
  }
}
assert.equal(all.length,720);
if(process.argv[3]){const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));for(const key of ['sourceHashes','protocolHash','referenceHash','barHash','contentHash','datasetHash','macroInput','groupNames','folds'])assert.deepEqual(report[key],repeat[key]);}
const results=groups.map(name=>{const rows=all.filter(r=>member(name,r.cell)),reference=score(rows,'reference'),prior=score(rows,'prior'),statePrior=score(rows,'statePrior');
  const selected=rows.filter(r=>Math.max(...r.statePrior)>=.55),signals=score(rows,'statePrior',.55),signalPrior=score(selected,'prior');
  return {name,count:rows.length,small:rows.length<30,reference,prior,statePrior,signals,priorOnSignals:signalPrior,
    againstReference:paired(rows,'reference','statePrior'),againstPrior:paired(rows,'prior','statePrior')};});
const first=results[0],p=first.statePrior,r=first.reference;
const gates={accuracy:p.accuracy>r.accuracy&&p.accuracy>first.prior.accuracy,balancedGain:p.balancedAccuracy>=r.balancedAccuracy+.02,
  recalls:p.recalls.every(v=>v!==null&&v>=.25),brier:p.brierScore<=r.brierScore,logLoss:p.logLoss<=r.logLoss};
const signalGates={coverage:first.signals.coverage>=.3,sameDateBaseline:first.signals.accuracy>first.priorOnSignals.accuracy,
  recalls:first.signals.recalls.every(v=>v!==null&&v>=.25)};
console.log(JSON.stringify({status:'PASS',promotionAllowed:false,gates,signalGates,diagnosticPass:Object.values(gates).every(Boolean),signalPass:Object.values(signalGates).every(Boolean),results},null,2));
