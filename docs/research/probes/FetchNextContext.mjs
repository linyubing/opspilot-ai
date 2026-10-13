import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

// 只读采集下一未开始窗口的研究上下文；不读取目标结果或改数据库。
const [output, previousPath] = process.argv.slice(2);
assert.ok(output && !fs.existsSync(output));
const goldKey=process.env.TWELVE_DATA_API_KEY, fredKey=process.env.FRED_API_KEY;
assert.ok(goldKey?.trim() && fredKey?.trim(), '行情或宏观凭据缺失');
assert.ok(Date.now()<Date.parse('2026-10-13T20:00:00Z'), '目标已开始，不再创建事前输入');
const previous=previousPath?JSON.parse(fs.readFileSync(previousPath,'utf8')):null;
const jobs=[
 ...(previous?[]:[['gold','https://api.twelvedata.com/time_series',{symbol:'XAU/USD',interval:'1day',end_date:'2026-10-12 23:59:59',outputsize:'40'},goldKey,'apikey'],
 ['quote','https://api.twelvedata.com/quote',{symbol:'XAU/USD',eod:'true'},goldKey,'apikey']]),
 ...['DFII10','DTWEXBGS'].map(series=>[series,'https://api.stlouisfed.org/fred/series/observations',
   {series_id:series,file_type:'json',realtime_start:'2026-10-12',realtime_end:'2026-10-12',
    observation_end:'2026-10-12',sort_order:'desc',limit:'40',units:'lin',output_type:'1'},fredKey,'api_key']),
];
const receipts=previous?previous.receipts.filter(r=>r.name==='gold'||r.name==='quote'):[];
for(const [name,path,request,key,keyName] of jobs) {
 const url=new URL(path);for(const [k,v] of Object.entries(request))url.searchParams.set(k,v);
 url.searchParams.set(keyName,key);
 const receipt={name,request,startedAt:new Date().toISOString()};
 try {
  const reply=await fetch(url,{signal:AbortSignal.timeout(45000)});const raw=await reply.text();
  receipt.httpStatus=reply.status;assert.ok(!raw.includes(key));
  receipt.rawResponse=raw;receipt.rawSha256=createHash('sha256').update(raw).digest('hex');receipt.response=JSON.parse(raw);
 } catch {receipt.error='源请求或回执解析失败，不记录带凭据URL及异常';}
 receipt.completedAt=new Date().toISOString();receipts.push(receipt);
}
fs.writeFileSync(output,JSON.stringify({researchOnly:true,lastKnownGoldDate:'2026-10-12',
 settlementBaseDate:'2026-10-13',settlementBasePrice:null,targetDate:'2026-10-14',
 targetStart:'2026-10-13T20:00:00Z',targetEnd:'2026-10-14T20:00:00Z',receipts},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(receipts.map(r=>({name:r.name,httpStatus:r.httpStatus,count:r.response?.values?.length??r.response?.observations?.length,error:r.error??null}))));
