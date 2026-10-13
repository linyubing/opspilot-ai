import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

// 从实际源回执独立检查事实、配对唯一变量和事前时点，不给未来结果打分。
const [contextPath,inputPath,resultPath,consensusPath,hourPath]=process.argv.slice(2);
const raw=fs.readFileSync(contextPath),inputRaw=fs.readFileSync(inputPath);
const context=JSON.parse(raw),i=JSON.parse(inputRaw),r=JSON.parse(fs.readFileSync(resultPath)),consensus=JSON.parse(fs.readFileSync(consensusPath));
const sha=x=>createHash('sha256').update(x).digest('hex');
const near=(a,b)=>assert.ok(Math.abs(a-b)<1e-7,`${a} != ${b}`);
assert.equal(i.contextHash,sha(raw));assert.equal(r.inputHash,sha(inputRaw));assert.deepEqual(i.consensus,consensus);
assert.equal(i.oldHourSourceHash,sha(fs.readFileSync(hourPath)));
for(const source of context.receipts){assert.equal(source.httpStatus,200);assert.equal(sha(source.rawResponse),source.rawSha256);assert.deepEqual(JSON.parse(source.rawResponse),source.response);}
const day=x=>new Date(x+'T00:00:00Z').getUTCDay();
const rows=context.receipts.find(x=>x.name==='gold').response.values;
assert.equal(new Set(rows.map(x=>x.datetime)).size,rows.length);
const bars=rows.filter(x=>day(x.datetime)!==0&&day(x.datetime)!==6).sort((a,b)=>b.datetime.localeCompare(a.datetime)).slice(0,21);
assert.equal(bars.length,21);assert.equal(i.weekendRowsExcluded,rows.filter(x=>day(x.datetime)===0||day(x.datetime)===6).length);
for(const n of [1,5,20])near(i.facts['gold'+n],(Number(bars[0].close)/Number(bars[n].close)-1)*100);
near(i.facts.range1,(Number(bars[0].high)-Number(bars[0].low))/Number(bars[1].close)*100);
for(const name of ['DFII10','DTWEXBGS']){
 const source=context.receipts.find(x=>x.name===name);assert.equal(source.request.realtime_start,'2026-10-12');assert.equal(source.request.realtime_end,'2026-10-12');
 const rows=source.response.observations.filter(x=>x.value!=='.').slice(0,21);assert.equal(rows.length,21);
 assert.equal(i.macro[name].observationDate,rows[0].date);near(i.macro[name].value,Number(rows[0].value));
 assert.deepEqual(i.macro[name].missingObservationDates,source.response.observations.filter(x=>x.value==='.').map(x=>x.date));
 for(const n of [1,5,20])near(i.macro[name]['change'+n],name==='DFII10'?(Number(rows[0].value)-Number(rows[n].value))*100:(Number(rows[0].value)/Number(rows[n].value)-1)*100);
}
const old=JSON.parse(fs.readFileSync(hourPath));const hours=old.receipts[3].response.values.sort((a,b)=>a.datetime.localeCompare(b.datetime));
assert.equal(hours.length,24);hours.forEach((h,j)=>assert.equal(Date.parse(h.datetime.replace(' ','T')+'Z'),Date.parse('2026-10-11T20:00:00Z')+j*3600000));
const quote=context.receipts.find(x=>x.name==='quote').response;
for(const key of ['open','high','low','close']){near(Number(quote[key]),Number(bars[0][key]));near(Number(bars[0][key]),Number(old.receipts[2].response.values[0][key]));}
assert.equal(i.candidateRequest.messages[0].content,i.controlRequest.messages[0].content+i.eventBlock);
const equal=structuredClone(i.candidateRequest);equal.messages[0].content=i.controlRequest.messages[0].content;assert.deepEqual(equal,i.controlRequest);
assert.deepEqual(equal.options,{num_ctx:8192,temperature:0,seed:42,num_predict:2048});assert.equal(equal.think,false);assert.equal(equal.format,'json');
assert.equal(i.settlementBasePrice,null);assert.equal(r.settlementBasePrice,null);assert.equal(r.settlementBaseDate,'2026-10-13');assert.equal(r.targetDate,'2026-10-14');
assert.equal(r.hit,null);assert.equal(r.actual,null);assert.equal(r.officialSessionCertified,false);
for(const output of r.outputs){
 assert.ok(Date.parse(output.startedAt)>=Date.parse(i.preparedAt));assert.ok(Date.parse(output.completedAt)<Date.parse(r.targetStart));
 assert.equal(output.promptHash,sha(i[output.name+'Request'].messages[0].content));assert.equal(output.httpStatus,200);assert.equal(output.syntaxPassed,true);
 const outer=JSON.parse(output.rawResponse);assert.equal(outer.done,true);assert.equal(outer.done_reason,'stop');assert.equal(outer.model,i.controlRequest.model);
 assert.deepEqual(JSON.parse(outer.message.content),output.content);assert.equal(output.content.direction,'NO_SIGNAL');
}
assert.equal(r.outputs.length,2);
console.log('独立核验通过：同事实、仅共识块不同、基准未知保留null、两条事前不发信号记录，无命中评分');
