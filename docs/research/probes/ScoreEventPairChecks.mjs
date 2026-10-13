import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import vm from 'node:vm';

// 执行真实评分代码；固定测试时钟并隔离I/O，禁止访问行情、模型或数据库。
// 只损坏已发布基线的元数据副本，不生成或归档假行情/新预测。
const source=fs.readFileSync(new URL('./ScoreEventPair.mjs',import.meta.url),'utf8')
 .replace(/^import .*;\r?$/gm,'');
const root=new URL('../',import.meta.url);
const input=fs.readFileSync(new URL('2026-10-13-event-pair-input.json',root));
const result=fs.readFileSync(new URL('2026-10-13-event-pair-result.json',root));
const baseline=fs.readFileSync(new URL('2026-10-13-forward-prior.json',root));
class TestClock extends Date {
 constructor(...args){super(...(args.length?args:['2026-10-13T10:00:00Z']));}
 static now(){return Date.parse('2026-10-13T10:00:00Z');}
}
async function run(bytes){
 const files=new Map([['input',input],['result',result],['baseline',bytes]]),writes=new Map();
 const io={existsSync:p=>writes.has(p),readFileSync:p=>{assert.ok(files.has(p));return files.get(p);},
  writeFileSync:(p,text)=>{assert.equal(p,'output');assert.ok(!writes.has(p));writes.set(p,text);}};
 await vm.runInNewContext(`(async()=>{${source}\n})()`,{
  fs:io,assert,createHash,Date:TestClock,console:{log(){}},
  process:{argv:['node','score','input','result','output','baseline'],env:{}},
  fetch:()=>{throw new Error('测试禁止访问外部接口');}
 });
 return JSON.parse(writes.get('output'));
}
const accepted=await run(baseline);
assert.equal(accepted.status,'WAITING_FOR_TARGET_CLOSE');assert.equal(accepted.apiCalls,0);
assert.equal(accepted.baseline.direction,'NEUTRAL');assert.equal(accepted.baseline.hit,null);
assert.equal(accepted.outputs[0].coverage,0);assert.equal(accepted.outputs[1].coverage,0);
const changed=JSON.parse(baseline);
changed.counts.BULLISH=352;changed.counts.NEUTRAL=624;
for(const key of ['BULLISH','NEUTRAL','BEARISH'])changed.probabilities[key]=changed.counts[key]/1268;
await assert.rejects(run(Buffer.from(JSON.stringify(changed))),{message:/冻结基线/});
console.log('2项通过：原始冻结基线可验收；自洽但被替换的基线在任何取数/输出前被拒绝');
