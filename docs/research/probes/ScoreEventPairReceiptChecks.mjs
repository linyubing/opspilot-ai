import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import vm from 'node:vm';

// 使用真实、已结束的本地回执验证研究验收器；不造行情，不联网，不写正式数据库。
const receiptPath=process.argv[2];
assert.ok(receiptPath,'需要已有真实来源回执文件');
const root=new URL('../',import.meta.url);
const input=fs.readFileSync(new URL('2026-10-13-event-pair-input.json',root));
const result=fs.readFileSync(new URL('2026-10-13-event-pair-result.json',root));
const baseline=fs.readFileSync(new URL('2026-10-13-forward-prior.json',root));
const receipt=fs.readFileSync(receiptPath);
const sha=x=>createHash('sha256').update(x).digest('hex');
const source=fs.readFileSync(new URL('./ScoreEventPair.mjs',import.meta.url),'utf8')
    .replace(/^import .*;\r?$/gm,'');
async function run(receiptBytes) {
const files=new Map([['input',input],['result',result],['baseline',baseline],['receipt',receiptBytes]]);
const before=[...files].map(([name,raw])=>[name,sha(raw)]);
const writes=new Map();
const io={existsSync:p=>writes.has(p),readFileSync:p=>{assert.ok(files.has(p));return files.get(p);},
    writeFileSync:(p,text)=>{assert.ok(!writes.has(p));writes.set(p,text);}};
let calls=0;
await vm.runInNewContext(`(async()=>{${source}\n})()`,{
    fs:io,assert,createHash,Date,URL,AbortSignal,console:{log(){}},
    process:{argv:['node','score','input','result','output','baseline','receipt'],env:{TWELVE_DATA_API_KEY:'offline-no-secret'}},
    fetch:()=>{calls++;throw new Error('复验禁止重新取数');}
});
const score=JSON.parse(writes.get('output'));
assert.equal(score.apiCalls,0,'已有回执必须零请求复验');
assert.equal(calls,0);
assert.deepEqual([...files].map(([name,raw])=>[name,sha(raw)]),before);
return {score,archived:JSON.parse(writes.get('output.source.json'))};
}
const {score,archived}=await run(receipt);
assert.equal(score.status,'SOURCE_NOT_VERIFIED');
assert.equal(score.verificationStage,'OHLC_AGGREGATION');
assert.equal(score.actual,null);
assert.equal(score.baseline.hit,null);
assert.ok(score.outputs.every(row=>row.signalCount===0&&row.hit===null));
assert.equal(score.accuracyImprovementProven,false);
for (const r of archived.receipts) {
    assert.equal(sha(r.rawResponse),r.rawSha256);
    assert.deepEqual(r.response,JSON.parse(r.rawResponse),'排序不能改写已解析回执');
}
// 即便JSON语义相同，也不能把未锁定的新字节归档当作原HTTP证据。
const changed=await run(Buffer.concat([receipt,Buffer.from('\n')]));
assert.equal(changed.score.status,'SOURCE_NOT_VERIFIED');
assert.equal(changed.score.verificationStage,'SOURCE_RECEIPTS');
assert.equal(changed.score.actual,null);
console.log('真实回执复验通过：零请求、OHLC不一致拒绝评分、回执与冻结输入不变');
