// 独立重算持仓符号、样本选择和计分，不调用预测接口或读取数据库。
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8').replace(/^\uFEFF/,'');
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex');
const input='docs/research/2026-10-11-prior-compare-input.json';
assert.equal(hash(input),'0e72f54e73b78efb0c12c3a5c474976afadb4c163aba732f9c39e9b6b124d936');
assert.equal(hash('docs/research/2026-10-13-cot-rule-protocol.md'),'015b014c03269398c65330f32c2949c2eff5e515ffae211c88b9d957926808c7');
const samples=JSON.parse(read(input)).samples.sort((a,b)=>a.asOfDate.localeCompare(b.asOfDate));
const manifest=JSON.parse(read('docs/research/2026-10-13-cot-archive-record.json'));
const result=JSON.parse(read('docs/research/2026-10-13-cot-rule-result.json'));
assert.equal(samples.length,1268);assert.equal(result.rows.length,52);
const labels=['BULLISH','NEUTRAL','BEARISH'];
for(const s of samples){assert.ok(s.targetDate<'2024-11-11'&&s.targetDate>s.asOfDate);assert.ok(labels.includes(s.label));}
const day=d=>Date.parse(`${d}T00:00:00Z`)/86400000;
const date=n=>new Date(n*86400000).toISOString().slice(0,10);
const positions=manifest.rows.map(m=>{
 assert.equal(hash(m.localReceipt),m.receiptSha256);
 const html=read(m.localReceipt);const start=html.indexOf('GOLD - COMMODITY EXCHANGE INC.');assert.ok(start>=0);
 const block=html.slice(start,start+3200);assert.ok(block.includes('Code-088691'));
 const line=block.match(/^\s*All\s*:[^\r\n]+/m)?.[0];assert.ok(line);
 const nums=line.replaceAll(',','').match(/\d+/g).map(BigInt);
 return {date:m.positionDate,oi:nums[0],long:nums[6],short:nums[7]};
});
const counts={NO_PREVIOUS_REPORT:0,STALE_POSITION:0,NO_ELIGIBLE_SAMPLE:0,NO_SIGNAL:0,SCORED:0};
let hits=0,priorHits=0,fixed=0,regressed=0;
const confusion=Object.fromEntries(labels.map(a=>[a,Object.fromEntries(labels.map(p=>[p,0]))]));
const months=new Map();const scoredBases=new Set();
for(let i=0;i<result.rows.length;i++){
 const c=result.rows[i],m=manifest.rows[i];assert.equal(c.positionDate,m.positionDate);assert.equal(c.updatedDate,m.archiveUpdatedDate);assert.equal(c.evidence,m.evidence);
 let status;
 const earliest=day(m.archiveUpdatedDate)+2;
 const sample=samples.find(s=>day(s.asOfDate)>=earliest);
 if(i===0)status='NO_PREVIOUS_REPORT';
 else if(!sample||day(sample.asOfDate)-earliest>4)status='NO_ELIGIBLE_SAMPLE';
 else if(day(sample.asOfDate)-day(c.positionDate)>14)status='STALE_POSITION';
 else{
  assert.equal(c.baseDate,sample.asOfDate);assert.equal(c.targetDate,sample.targetDate);assert.equal(c.actual,sample.label);
  assert.ok(!scoredBases.has(c.baseDate));scoredBases.add(c.baseDate);
  const p=positions[i],prev=positions[i-1];assert.equal(c.previousPositionDate,prev.date);
  assert.equal(BigInt(c.long),p.long);assert.equal(BigInt(c.short),p.short);assert.equal(BigInt(c.openInterest),p.oi);
  assert.ok(day(manifest.rows[i-1].archiveUpdatedDate)<=earliest);
  const delta=(p.long-p.short)*prev.oi-(prev.long-prev.short)*p.oi;
  const dir=delta>0n?'BULLISH':delta<0n?'BEARISH':'NO_SIGNAL';assert.equal(c.direction,dir);
  status=dir==='NO_SIGNAL'?'NO_SIGNAL':'SCORED';
  const freq=Object.fromEntries(labels.map(l=>[l,0]));for(const old of samples)if(old.targetDate<c.baseDate)freq[old.label]++;
  assert.deepEqual(c.priorCounts,freq);
  const prior=labels.reduce((best,l)=>freq[l]>freq[best]?l:best,labels[0]);assert.equal(c.prior,prior);
  if(status==='SCORED'){
   const h=dir===sample.label,ph=prior===sample.label;assert.equal(c.hit,h);assert.equal(c.priorHit,ph);
   hits+=+h;priorHits+=+ph;fixed+=+(h&&!ph);regressed+=+(!h&&ph);confusion[sample.label][dir]++;
   const month=c.baseDate.slice(0,7);const ms=months.get(month)||{month,signals:0,hits:0,priorHits:0};ms.signals++;ms.hits+=+h;ms.priorHits+=+ph;months.set(month,ms);
  }
 }
 assert.equal(c.status,status);counts[status]++;
}
assert.deepEqual(result.confusionActualByPrediction,confusion);assert.deepEqual(result.months,[...months.values()]);
assert.deepEqual(result.summary,{opportunities:52,noPrevious:counts.NO_PREVIOUS_REPORT,stale:counts.STALE_POSITION,missingSample:counts.NO_ELIGIBLE_SAMPLE,noSignal:counts.NO_SIGNAL,signals:counts.SCORED,hits,signalAccuracy:hits/counts.SCORED,coverage:counts.SCORED/52,priorHits,priorAccuracy:priorHits/counts.SCORED,fixed,regressed});
for(const flag of ['strictPitVerified','trainingPerformed','holdoutRead','promotionAllowed'])assert.equal(result[flag],false);
assert.equal(result.brierScore,null);assert.equal(result.logLoss,null);
console.log(JSON.stringify({verifiedReports:52,scored:counts.SCORED,hits,priorHits,independentArithmetic:'BigInt',duplicateScoredBaseDates:0}));
