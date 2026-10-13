// 两根真实日线的人群评分；保持符号规则，不调参，不把回溯探索当正式晋级。
import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8').replace(/^\uFEFF/,'');
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex');
assert.equal(hash('docs/research/2026-10-13-cot-cohort-prices.json'),'438fde19c8d6bc3d7e41554acef7b0680a8300d097a87b9f40e9dbc294cfa7c7');
assert.equal(hash('docs/research/2026-10-13-cot-cohort-protocol.md'),'b291c36795867071aff7927fa6b492a9d59a26eb7a2cad767240d7f6eb51f507');
const samplesPath='docs/research/2026-10-11-prior-compare-input.json';assert.equal(hash(samplesPath),'0e72f54e73b78efb0c12c3a5c474976afadb4c163aba732f9c39e9b6b124d936');
const samples=JSON.parse(read(samplesPath)).samples;
const prices=JSON.parse(read('docs/research/2026-10-13-cot-cohort-prices.json'));
const old=JSON.parse(read('docs/research/2026-10-13-cot-rule-result.json'));
const manifest=JSON.parse(read('docs/research/2026-10-13-cot-archive-record.json'));
assert.equal(prices.rows.length,52);assert.equal(manifest.rows.length,52);
assert.equal(prices.legacyChecks.length,36);
for(const check of prices.legacyChecks){const original=old.rows.find(c=>c.status==='SCORED'&&c.baseDate===check.baseDate&&c.targetDate===check.targetDate);assert.ok(original);assert.equal(check.actual,original.actual);assert.equal(check.matched,true);}
const day=s=>Date.parse(`${s}T00:00:00Z`);const iso=n=>new Date(n).toISOString().slice(0,10);
const next=date=>{let n=day(date);do{n+=86400000;}while([0,6].includes(new Date(n).getUTCDay())||prices.holidays.includes('--'+iso(n).slice(5)));return iso(n);};
const value=x=>{assert.match(String(x),/^\d+(?:\.\d{1,8})?$/);const [a,b='']=String(x).split('.');return BigInt(a)*100000000n+BigInt(b.padEnd(8,'0'));};
const labels=['BULLISH','NEUTRAL','BEARISH'];
const positions=manifest.rows.map(m=>{assert.equal(hash(m.localReceipt),m.receiptSha256);const html=read(m.localReceipt);const start=html.indexOf('GOLD - COMMODITY EXCHANGE INC.');assert.ok(start>=0);const block=html.slice(start,start+3200);assert.ok(block.includes('Code-088691'));const line=block.match(/^\s*All\s*:[^\r\n]+/m)?.[0];assert.ok(line);const nums=line.replaceAll(',','').match(/\d+/g).map(BigInt);return {oi:nums[0],long:nums[6],short:nums[7]};});
let paired=0,newCases=0,changedDates=0;const rows=[];const bases=new Set();
for(let i=0;i<prices.rows.length;i++){
 const c={...prices.rows[i]},m=manifest.rows[i];assert.equal(c.positionDate,m.positionDate);
 const earliest=iso(day(m.archiveUpdatedDate)+2*86400000);assert.equal(c.baseDate,next(iso(day(earliest)-86400000)));assert.equal(c.targetDate,next(c.baseDate));assert.ok(c.targetDate<'2024-11-11');
 assert.equal(c.positionAgeDays,(day(c.baseDate)-day(c.positionDate))/86400000);
 if(i===0){assert.equal(c.status,'NO_PREVIOUS_REPORT');rows.push(c);continue;}
 if(c.positionAgeDays>14){assert.equal(c.status,'STALE_POSITION');rows.push(c);continue;}
 if(c.status!=='ELIGIBLE'){rows.push(c);continue;}
 assert.ok(!bases.has(c.baseDate));bases.add(c.baseDate);
 assert.equal(c.bars.length,2);assert.equal(c.bars[0].priceDate,c.baseDate);assert.equal(c.bars[1].priceDate,c.targetDate);
 for(const b of c.bars){
  assert.equal(b.provider,'twelve_data');assert.equal(b.symbol,'XAUUSD');assert.equal(b.currency.toLowerCase(),'usd');assert.equal(b.unit,'troy_ounce');
  const [o,h,l,close]=['open','high','low','close'].map(k=>value(b[k]));assert.ok(l>0n&&h>=l&&h>=o&&h>=close&&l<=o&&l<=close);
  const p=b.confirmation;assert.equal(p.source,'twelve_data_quote_eod_v1');assert.match(p.receiptHash,/^[0-9a-f]{64}$/);
  assert.ok(b.priceDate<=p.closedDay&&p.closedDay<=p.checkedAt.slice(0,10)&&p.closedDay<=prices.verifiedAt.slice(0,10));
  assert.ok(Date.parse(p.checkedAt)>=Date.parse(b.collectedAt)&&Date.parse(p.checkedAt)<=Date.parse(prices.verifiedAt));
 }
 // 用整数比较±0.5%边界，独立验证生产BigDecimal分类结果。
 const base=value(c.bars[0].close),delta=value(c.bars[1].close)-base;
 const actual=delta*200n>base?'BULLISH':delta*200n< -base?'BEARISH':'NEUTRAL';assert.equal(c.actual,actual);
 const p=positions[i],prev=positions[i-1];const net=(p.long-p.short)*prev.oi-(prev.long-prev.short)*p.oi;
 c.direction=net>0n?'BULLISH':net<0n?'BEARISH':'NO_SIGNAL';c.hit=c.direction==='NO_SIGNAL'?null:c.direction===actual;
 const freq=Object.fromEntries(labels.map(l=>[l,0]));for(const s of samples){assert.ok(s.targetDate<'2024-11-11');if(s.targetDate<c.baseDate)freq[s.label]++;}
 c.prior=labels.reduce((best,l)=>freq[l]>freq[best]?l:best,labels[0]);c.priorCounts=freq;c.priorHit=c.prior===actual;
 const previous=old.rows.find(o=>o.positionDate===c.positionDate);
 if(previous.status==='SCORED'&&previous.baseDate===c.baseDate&&previous.targetDate===c.targetDate){assert.equal(c.actual,previous.actual);assert.equal(c.direction,previous.direction);assert.equal(c.prior,previous.prior);paired++;c.oldRelationship='SAME_PAIR';}
 else if(previous.status==='SCORED'){changedDates++;c.oldRelationship='CHANGED_PAIR';}
 else{newCases++;c.oldRelationship='NEW_ELIGIBLE';}
 c.evidence=m.evidence;c.status=c.direction==='NO_SIGNAL'?'NO_SIGNAL':'SCORED';rows.push(c);
}
const measure=xs=>{const n=xs.length,h=xs.filter(c=>c.hit).length,p=xs.filter(c=>c.priorHit).length;return {signals:n,hits:h,accuracy:n?h/n:null,priorHits:p,priorAccuracy:n?p/n:null,fixed:xs.filter(c=>c.hit&&!c.priorHit).length,regressed:xs.filter(c=>!c.hit&&c.priorHit).length};};
const scored=rows.filter(c=>c.status==='SCORED');
const confusion=Object.fromEntries(labels.map(a=>[a,Object.fromEntries(labels.map(p=>[p,scored.filter(c=>c.actual===a&&c.direction===p).length]))]));
const months=[...new Set(scored.map(c=>c.baseDate.slice(0,7)))].map(month=>({month,...measure(scored.filter(c=>c.baseDate.startsWith(month)))}));
console.log(JSON.stringify({protocol:'2026-10-13-cot-cohort-protocol.md',kind:'RETROSPECTIVE_COVERAGE_DIAGNOSTIC_NOT_STRICT_PIT',priceReceiptSha256:hash('docs/research/2026-10-13-cot-cohort-prices.json'),priceVerifiedAt:prices.verifiedAt,legacyLabelsVerified:prices.legacyChecks.length,strictPitVerified:false,trainingPerformed:false,holdoutRead:false,promotionAllowed:false,brierScore:null,logLoss:null,summary:{opportunities:52,noPrevious:rows.filter(c=>c.status==='NO_PREVIOUS_REPORT').length,stale:rows.filter(c=>c.status==='STALE_POSITION').length,rejectedPrice:rows.filter(c=>['PRICE_MISSING','UNCONFIRMED','CALENDAR_CONFLICT'].includes(c.status)).length,coverage:scored.length/52,...measure(scored)},relationship:{samePair:paired,newEligible:newCases,changedPair:changedDates},samePair:measure(scored.filter(c=>c.oldRelationship==='SAME_PAIR')),newEligible:measure(scored.filter(c=>c.oldRelationship==='NEW_ELIGIBLE')),confusion,months,rows}));
