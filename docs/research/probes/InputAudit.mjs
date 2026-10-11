import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

// 清点冻结真实输入的信息种类、观测日期滞后和重复列，不计算预测评分。
const [inputPath,outputPath,commit]=process.argv.slice(2);
assert.match(commit??'',/^[0-9a-f]{40}$/);assert(!fs.existsSync(outputPath));
const raw=fs.readFileSync(inputPath,'utf8'),data=JSON.parse(raw);
assert.equal(data.samples.length,1268);
assert.equal(data.macroInput.policy,'fred-known-before-day-v1');
const java=fs.readFileSync(new URL('../../../backend/src/main/java/com/opspilot/ai/forecast/learning/GoldFeatures.java',import.meta.url),'utf8');
const block=java.slice(java.indexOf('NAMES = Set.of('),java.indexOf('\n    );'));
const names=[...block.matchAll(/"([a-zA-Z0-9_]+)"/g)].map(x=>x[1]).sort();
assert.equal(names.length,36);
const macro=names.filter(n=>n.startsWith('real_rate')||n.startsWith('dollar_'));
assert.equal(macro.length,9);
const ages=Object.fromEntries(['real_rate_age','dollar_age'].map(n=>[n,{max:0,sum:0,buckets:{'0..1':0,'2..4':0,'5..7':0,'>7':0}}]));
const pairs=[['candle_body','intradayReturn'],['close_position','closeLocation']]
  .map(([a,b])=>({a,b,exactCount:0,maxAbsDifference:0}));
for(const s of data.samples) {
  assert(s.targetDate<'2024-11-11');assert(s.asOfDate<s.targetDate);
  assert.deepEqual(Object.keys(s.features.values).sort(),names);
  for(const [name,state] of Object.entries(ages)) {
    const v=s.features.values[name];assert(Number.isInteger(v)&&v>=0);
    state.max=Math.max(state.max,v);state.sum+=v;
    state.buckets[v<=1?'0..1':v<=4?'2..4':v<=7?'5..7':'>7']++;
  }
  for(const p of pairs) {
    const delta=Math.abs(s.features.values[p.a]-s.features.values[p.b]);
    p.exactCount+=Number(delta===0);p.maxAbsDifference=Math.max(p.maxAbsDifference,delta);
  }
}
const sha=s=>crypto.createHash('sha256').update(s).digest('hex');
const result={version:'gold-information-inventory-v1',gitCommit:commit,checkedAt:new Date().toISOString(),
 inputHash:sha(raw),featureSchemaHash:sha(java),sampleCount:data.samples.length,
 cutoffExclusive:'2024-11-11',holdoutRead:false,trainingPerformed:false,forecastChanged:false,
 goldFeatures:names.filter(n=>!macro.includes(n)),macroFeatures:macro,
 macroObservationAge:ages,duplicatePairs:pairs,
 availabilityPrecision:'FRED vintage date D-1; no original intraday publication timestamp',
 eventScheduleFeatures:[],economicSurpriseFeatures:[]};
fs.writeFileSync(outputPath,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({gold:result.goldFeatures.length,macro:macro.length,ages,pairs},null,2));
