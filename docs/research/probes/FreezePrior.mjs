import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

// 仅冻结历史标签频率作为独立未来基线，不替换研究配对或正式模型。
const [inputPath,pairPath,out]=process.argv.slice(2);
assert.ok(out&&!fs.existsSync(out));
const raw=fs.readFileSync(inputPath),pairRaw=fs.readFileSync(pairPath);
const sha=v=>createHash('sha256').update(v).digest('hex');
assert.equal(sha(raw),'0e72f54e73b78efb0c12c3a5c474976afadb4c163aba732f9c39e9b6b124d936');
const samples=JSON.parse(raw).samples,pair=JSON.parse(pairRaw);
assert.equal(samples.length,1268);assert.equal(pair.targetDate,'2026-10-14');
assert.ok(Date.now()<Date.parse(pair.targetStart));
const counts={BULLISH:0,NEUTRAL:0,BEARISH:0};
for(let i=0;i<samples.length;i++){
 const s=samples[i];assert.equal(s.horizon,'NEXT_DAY');assert.ok(s.asOfDate<s.targetDate&&s.targetDate<'2024-11-11');
 if(i)assert.ok(s.asOfDate>samples[i-1].asOfDate);
 assert.ok(Object.hasOwn(counts,s.label));counts[s.label]++;
}
const probabilities=Object.fromEntries(Object.entries(counts).map(([key,n])=>[key,n/samples.length]));
const direction=['NEUTRAL','BULLISH','BEARISH'].reduce((best,key)=>counts[key]>counts[best]?key:best,'NEUTRAL');
const createdAt=new Date().toISOString(),lastTrainingTarget=samples.at(-1).targetDate;
const result={version:'gold-frozen-frequency-forward-v1',createdAt,inputHash:sha(raw),pairInputHash:sha(pairRaw),
 targetDate:pair.targetDate,targetStart:pair.targetStart,targetEnd:pair.targetEnd,settlementBaseDate:'2026-10-13',settlementBasePrice:null,
 trainingSamples:samples.length,firstTrainingDate:samples[0].asOfDate,lastTrainingTarget,
 labelAgeDays:Math.floor((Date.parse(createdAt.slice(0,10))-Date.parse(lastTrainingTarget))/86400000),
 counts,probabilities,direction,researchOnly:true,officialSessionCertified:false,
 liveProbabilityCalibrated:false,actual:null,hit:null,accuracyImprovementProven:false};
fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(result));
