import fs from 'node:fs';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

// 只读验收冻结研究预测；不改输入/输出，不重跑模型，不写正式数据库。
const [inputPath, resultPath, outputPath, baselinePath, sourcePath] = process.argv.slice(2);
assert.ok(inputPath && resultPath && outputPath && !fs.existsSync(outputPath));
assert.ok(!fs.existsSync(outputPath+'.source.json'),'来源归档已存在，拒绝重复取数或覆盖');
const inputRaw=fs.readFileSync(inputPath), resultRaw=fs.readFileSync(resultPath);
const input=JSON.parse(inputRaw), pair=JSON.parse(resultRaw);
const sha=v=>createHash('sha256').update(v).digest('hex');
assert.equal(pair.inputHash,sha(inputRaw));
assert.equal(sha(inputRaw),'60b9537061d52d3a40ae4e71c2fc3e54adf752d789b1d8648a219fb0ee232a2e');
assert.equal(sha(resultRaw),'be92a5a2256ec7bfd34cfc999cf448fb8946b6e821ad6e61990582870e3874bc');
assert.equal(input.targetDate,'2026-10-14');assert.equal(pair.targetDate,input.targetDate);
assert.equal(pair.settlementBaseDate,'2026-10-13');assert.equal(pair.settlementBasePrice,null);
assert.equal(pair.targetStart,'2026-10-13T20:00:00Z');assert.equal(pair.targetEnd,'2026-10-14T20:00:00Z');
assert.equal(pair.actual,null);assert.equal(pair.hit,null);assert.equal(pair.outputs.length,2);
for(const entry of pair.outputs){
 assert.equal(entry.syntaxPassed,true);assert.ok(Date.parse(entry.completedAt)<Date.parse(pair.targetStart));
 assert.ok(['BULLISH','NEUTRAL','BEARISH','NO_SIGNAL'].includes(entry.content.direction));
 assert.equal(entry.promptHash,sha(input[entry.name+'Request'].messages[0].content));
}
const report={researchOnly:true,officialSessionCertified:false,inputHash:sha(inputRaw),pairHash:sha(resultRaw),
 checkedAt:new Date().toISOString(),targetDate:pair.targetDate,targetEnd:pair.targetEnd,
 settlementBaseDate:pair.settlementBaseDate,actual:null,returnPercent:null,apiCalls:0,
 outputs:pair.outputs.map(e=>({name:e.name,direction:e.content.direction,sampleCount:1,
  signalCount:e.content.direction==='NO_SIGNAL'?0:1,coverage:e.content.direction==='NO_SIGNAL'?0:1,
  hit:null,directionAccuracy:null,brierScore:null,logLoss:null}))};
if(baselinePath){
 const baselineRaw=fs.readFileSync(baselinePath),baseline=JSON.parse(baselineRaw);
 // 自洽不代表是原基线；锁定已发布字节摘要，拒绝事后替换分布。
 assert.equal(sha(baselineRaw),'61edb1aacaa69b0134e103e22b3f78e9fa0f913c593dd105a25952033b37df34','冻结基线被替换或改写');
 assert.equal(baseline.version,'gold-frozen-frequency-forward-v1');assert.equal(baseline.trainingSamples,1268);
 assert.equal(baseline.inputHash,'0e72f54e73b78efb0c12c3a5c474976afadb4c163aba732f9c39e9b6b124d936');
 assert.equal(baseline.pairInputHash,sha(inputRaw));assert.equal(baseline.targetDate,pair.targetDate);
 assert.equal(baseline.targetStart,pair.targetStart);assert.equal(baseline.targetEnd,pair.targetEnd);
 assert.ok(Date.parse(baseline.createdAt)<Date.parse(pair.targetStart));assert.equal(baseline.settlementBasePrice,null);
 const labels=['BULLISH','NEUTRAL','BEARISH'];
 assert.equal(labels.reduce((n,k)=>n+baseline.counts[k],0),baseline.trainingSamples);
 for(const key of labels){assert.ok(Number.isInteger(baseline.counts[key])&&baseline.counts[key]>0);assert.ok(Math.abs(baseline.probabilities[key]-baseline.counts[key]/baseline.trainingSamples)<1e-12);}
 assert.equal(baseline.direction,['NEUTRAL','BULLISH','BEARISH'].reduce((best,k)=>baseline.counts[k]>baseline.counts[best]?k:best,'NEUTRAL'));
 report.baselineHash=sha(baselineRaw);
 report.baseline={direction:baseline.direction,probabilities:baseline.probabilities,labelAgeDays:baseline.labelAgeDays,
  liveProbabilityCalibrated:false,confidenceThresholdApplied:false,sampleCount:1,signalCount:1,coverage:1,
  hit:null,directionAccuracy:null,brierScore:null,logLoss:null};
}
if(Date.now()<Date.parse(pair.targetEnd)){
 report.status='WAITING_FOR_TARGET_CLOSE';
 report.reason='真实目标会话未结束；拒绝取未结束日线或提前评分';
}else{
 const key=process.env.TWELVE_DATA_API_KEY;
 if(!sourcePath) assert.ok(key?.trim(),'缺少行情凭据');
 const jobs=[
  ['base',{symbol:'XAU/USD',interval:'1day',date:'2026-10-13'}],
  ['target',{symbol:'XAU/USD',interval:'1day',date:'2026-10-14'}],
  ['hours',{symbol:'XAU/USD',interval:'1h',timezone:'UTC',start_date:'2026-10-12 20:00:00',end_date:'2026-10-14 19:00:00',outputsize:'60'}]
 ];
 const receipts=[];
 report.sourceMode=sourcePath?'ARCHIVED_RECEIPT_REPLAY':'LIVE_RECEIPTS';
 report.verificationStage='SOURCE_RECEIPTS';
 try{
  if(sourcePath){
   const raw=fs.readFileSync(sourcePath);report.sourceArchiveHash=sha(raw);
   // 绑定本轮首次真实HTTP归档的字节摘要；同文件内自洽的rawSha256不能代替来源锚点。
   assert.equal(report.sourceArchiveHash,'a78b45cc330438d43439cb14fcb11bf617b9d3ffe8349a36dc04105e9227632f',
     '回放归档不是已冻结的原始HTTP证据');
   const archived=JSON.parse(raw);assert.equal(archived.receipts.length,jobs.length);
   for(let i=0;i<jobs.length;i++){
    const r=archived.receipts[i];assert.equal(r.name,jobs[i][0]);assert.deepEqual(r.request,jobs[i][1]);
    assert.equal(r.rawSha256,sha(r.rawResponse));
    assert.ok(Number.isFinite(Date.parse(r.startedAt))&&Number.isFinite(Date.parse(r.completedAt)));
    assert.ok(Date.parse(r.startedAt)<=Date.parse(r.completedAt)&&Date.parse(r.completedAt)<=Date.now());
    assert.ok(Date.parse(r.startedAt)>=Date.parse(pair.targetEnd),'来源回执必须晚于目标结束');
    // 原始正文与摘要是复验依据；旧代码曾排序解析副本，绝不沿用该副本评分。
    receipts.push({...r,response:JSON.parse(r.rawResponse)});
   }
  }else for(const [name,request] of jobs){
   const url=new URL('https://api.twelvedata.com/time_series');for(const [k,v]of Object.entries(request))url.searchParams.set(k,v);url.searchParams.set('apikey',key);
   const receipt={name,request,startedAt:new Date().toISOString()};
   receipts.push(receipt);report.apiCalls++;
   const response=await fetch(url,{signal:AbortSignal.timeout(45000)});const raw=await response.text();assert.ok(!raw.includes(key));
   receipt.httpStatus=response.status;receipt.rawResponse=raw;receipt.rawSha256=sha(raw);receipt.completedAt=new Date().toISOString();receipt.response=JSON.parse(raw);
   assert.equal(response.status,200);assert.equal(receipt.response.status,'ok');assert.equal(receipt.response.meta.symbol,'XAU/USD');assert.equal(receipt.response.meta.interval,request.interval);
  }
  for(let i=0;i<receipts.length;i++){
   const r=receipts[i];assert.equal(r.httpStatus,200);assert.equal(r.response.status,'ok');
   assert.equal(r.response.meta.symbol,'XAU/USD');assert.equal(r.response.meta.interval,jobs[i][1].interval);
  }
  report.verificationStage='DAILY_BARS';
  // 价格比较全程整数十进制，不能用浮点误差改变±0.5%分类。
  const price=v=>{assert.equal(typeof v,'string');assert.match(v,/^\d+(\.\d+)?$/);const [a,b='']=v.split('.');assert.ok(b.length<=12);return BigInt(a+b.padEnd(12,'0'));};
  const valid=row=>{const [o,h,l,c]=['open','high','low','close'].map(k=>price(row[k]));assert.ok(l>0n&&h>=o&&h>=c&&l<=o&&l<=c);};
  const daily=receipts.slice(0,2).map(r=>{assert.equal(r.response.values.length,1);const bar=r.response.values[0];assert.equal(bar.datetime,r.request.date);valid(bar);return bar;});
  report.verificationStage='HOUR_COVERAGE';
  const timezone=receipts[2].response.meta.timezone;
  if(timezone!=null) assert.equal(timezone,'UTC','小时响应时区与UTC请求不一致');
  report.hourTimezoneEvidence=timezone==='UTC'?'UTC_REQUEST_AND_RESPONSE':'UTC_REQUEST_NOT_ECHOED';
  const hours=[...receipts[2].response.values].sort((a,b)=>a.datetime.localeCompare(b.datetime));assert.equal(hours.length,48);
  const first=Date.parse('2026-10-12T20:00:00Z');hours.forEach((row,i)=>{valid(row);assert.equal(Date.parse(row.datetime.replace(' ','T')+'Z'),first+i*3600000);});
  report.verificationStage='OHLC_AGGREGATION';
  report.dailyHourChecks=[];
  for(let j=0;j<2;j++){
   const part=hours.slice(j*24,(j+1)*24),bar=daily[j];
   report.dailyHourChecks.push({date:bar.datetime,hours:part.length,
    openMatches:price(bar.open)===price(part[0].open),closeMatches:price(bar.close)===price(part.at(-1).close),
    highMatches:price(bar.high)===part.map(h=>price(h.high)).reduce((a,b)=>a>b?a:b),
    lowMatches:price(bar.low)===part.map(h=>price(h.low)).reduce((a,b)=>a<b?a:b)});
  }
  assert.ok(report.dailyHourChecks.every(r=>r.openMatches&&r.closeMatches&&r.highMatches&&r.lowMatches));
  const base=price(daily[0].close),delta=price(daily[1].close)-base;
  report.actual=200n*delta>base?'BULLISH':200n*delta< -base?'BEARISH':'NEUTRAL';
  report.returnPercent=Number(delta)/Number(base)*100;
  report.verificationStage='VERIFIED';
  report.status='RESOLVED_RESEARCH_ONLY';report.reason='两日各24小时覆盖与原始日线OHLC一致，候选口径研究验收';
  for(const item of report.outputs){if(item.signalCount){item.hit=item.direction===report.actual;item.directionAccuracy=Number(item.hit);}else{item.hit=null;item.directionAccuracy=null;}}
  if(report.baseline){
   const b=report.baseline;b.hit=b.direction===report.actual;b.directionAccuracy=Number(b.hit);
   b.brierScore=['BULLISH','NEUTRAL','BEARISH'].reduce((sum,k)=>sum+(b.probabilities[k]-Number(k===report.actual))**2,0);
   b.logLoss=-Math.log(Math.max(1e-15,b.probabilities[report.actual]));
  }
 }catch{report.status='SOURCE_NOT_VERIFIED';report.reason=report.verificationStage==='OHLC_AGGREGATION'
   ?'完整48小时已取得，但与两日原生日线OHLC不一致；不改时段、不填数据、不评分'
   :'真实来源或完整覆盖未通过；不填数据、不评分';report.actual=null;report.returnPercent=null;}
 report.sourceSummary=receipts.map(r=>({name:r.name,httpStatus:r.httpStatus,rawSha256:r.rawSha256,
  completedAt:r.completedAt,rows:r.response?.values?.length}));
 // 原始供应商价格仅本地保存；即便失败也保留实际回执，不覆盖既有证据。
 fs.writeFileSync(outputPath+'.source.json',JSON.stringify({receipts},null,2)+'\n',{flag:'wx'});
}
report.accuracyImprovementProven=false;
fs.writeFileSync(outputPath,JSON.stringify(report,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(report));
