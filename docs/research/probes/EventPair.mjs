import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

// 单次事前配对研究：只有共识块不同；原始响应先留存，不改正式预测。
const model='qwen3.5:9b',digest='6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7';
const deadline='2026-10-13T20:00:00Z';
const sha=s=>createHash('sha256').update(s).digest('hex');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const write=(p,v)=>fs.writeFileSync(p,JSON.stringify(v,null,2)+'\n',{flag:'wx'});
const decimal=v=>{assert.equal(typeof v,'string');assert.match(v,/^-?\d+(\.\d+)?$/);assert.ok(Number.isFinite(Number(v)));return Number(v);};
const request=prompt=>({model,stream:false,think:false,format:'json',messages:[{role:'user',content:prompt}],options:{num_ctx:8192,temperature:0,seed:42,num_predict:2048}});
function verify(i) {
 assert.equal(i.targetDate,'2026-10-14');assert.equal(i.settlementBaseDate,'2026-10-13');assert.equal(i.settlementBasePrice,null);
 assert.equal(i.modelDigest,digest);assert.equal(i.actual,null);assert.equal(i.hit,null);
 assert.equal(i.candidateRequest.messages[0].content,i.controlRequest.messages[0].content+i.eventBlock);
 const same=structuredClone(i.candidateRequest);same.messages[0].content=i.controlRequest.messages[0].content;assert.deepEqual(same,i.controlRequest);
 assert.equal(sha(i.controlRequest.messages[0].content),i.controlPromptHash);assert.equal(sha(i.candidateRequest.messages[0].content),i.candidatePromptHash);
 assert.ok(Date.parse(i.preparedAt)<Date.parse(deadline));assert.equal(i.consensus.actual,null);
}
if(process.argv[2]==='--prepare') {
 const [contextPath,consensusPath,oldPath,out]=process.argv.slice(3);
 assert.ok(out&&!fs.existsSync(out));assert.ok(Date.now()<Date.parse(deadline));
 const raw=fs.readFileSync(contextPath,'utf8'),context=JSON.parse(raw),consensus=read(consensusPath),old=read(oldPath);
 assert.equal(consensus.eventId,'398220');assert.equal(consensus.actual,null);assert.ok(Date.parse(consensus.observedAt)<Date.parse(deadline));
 for(const r of context.receipts){assert.equal(r.error,undefined);assert.equal(r.httpStatus,200);assert.equal(sha(r.rawResponse),r.rawSha256);assert.deepEqual(JSON.parse(r.rawResponse),r.response);assert.ok(Date.parse(r.completedAt)<Date.parse(deadline));}
 const source=context.receipts[0].response,quote=context.receipts[1].response;
 assert.equal(source.status,'ok');assert.equal(source.meta.symbol,'XAU/USD');assert.equal(source.meta.interval,'1day');
 assert.equal(quote.datetime,'2026-10-12');assert.equal(quote.symbol,'XAU/USD');
 const dates=new Set();for(const row of source.values){assert.ok(!dates.has(row.datetime));dates.add(row.datetime);assert.ok(row.datetime<='2026-10-12');
  const o=decimal(row.open),h=decimal(row.high),l=decimal(row.low),c=decimal(row.close);assert.ok(l>0&&h>=o&&h>=c&&l<=o&&l<=c);}
 const weekday=d=>{const n=new Date(d+'T00:00:00Z').getUTCDay();return n!==0&&n!==6;};
 const bars=source.values.filter(r=>weekday(r.datetime)).sort((a,b)=>b.datetime.localeCompare(a.datetime)).slice(0,21);
 assert.equal(bars.length,21);assert.equal(bars[0].datetime,'2026-10-12');
 for(let n=1;n<21;n++){let date=new Date(bars[n-1].datetime+'T00:00:00Z');do{date=new Date(date.getTime()-86400000);}while(!weekday(date.toISOString().slice(0,10)));assert.equal(bars[n].datetime,date.toISOString().slice(0,10));}
 const anchor=old.receipts[2].response.values[0];assert.equal(anchor.datetime,'2026-10-12');
 for(const key of ['open','high','low','close']){assert.equal(decimal(bars[0][key]),decimal(quote[key]));assert.equal(decimal(bars[0][key]),decimal(anchor[key]));}
 const facts={};for(const n of [1,5,20])facts['gold'+n]=Number(((decimal(bars[0].close)/decimal(bars[n].close)-1)*100).toFixed(8));
 facts.range1=Number(((decimal(bars[0].high)-decimal(bars[0].low))/decimal(bars[1].close)*100).toFixed(8));
 const macro={};for(const name of ['DFII10','DTWEXBGS']){
  const r=context.receipts.find(r=>r.name===name);assert.equal(r.request.realtime_start,'2026-10-12');assert.equal(r.response.realtime_start,'2026-10-12');
  const rawRows=r.response.observations;for(const row of rawRows){assert.ok(row.date<='2026-10-12');if(row.value!=='.')decimal(row.value);}
  const rows=rawRows.filter(row=>row.value!=='.').slice(0,21);assert.equal(rows.length,21);
  const current=decimal(rows[0].value);macro[name]={observationDate:rows[0].date,value:current,missingObservationDates:rawRows.filter(row=>row.value==='.').map(row=>row.date),changeBasis:'valid observations, not consecutive trading days'};
  for(const n of [1,5,20])macro[name]['change'+n]=name==='DFII10'?(current-decimal(rows[n].value))*100:(current/decimal(rows[n].value)-1)*100;
 }
 const prompt=`你是黄金方向研究助手，仅使用提供的真实事实，不得编造新闻、DXY或未来价格。
预测目标：2026-10-14候选会话收盘相对2026-10-13真实收盘的收益率类别。目标会话为[2026-10-13T20:00:00Z,2026-10-14T20:00:00Z)。悉尼07:00候选口径未被供应商正式认证。
当前只知道10月12日已结束价格，10月13日收盘是未知结算基准，不要将12日价格冒充13日基准或给出目标价格。信息截止为本次请求前。
已知黄金事实：${JSON.stringify(facts)}。gold1/5/20为配置交易日1/5/20期百分比收益；range1是波幅，不是上涨信号。
宏观D-1有效版本：${JSON.stringify(macro)}。DFII10单位百分比，change单位bp；DTWEXBGS是广义美元指数，不是DXY，其change为百分比；观测旧日期不得说成实时。
分类：收益率>0.5%为BULLISH、<-0.5%为BEARISH，其余含边界NEUTRAL。中性表示落在该区间，不代表信心低；证据不足可NO_SIGNAL。高波幅不保证横盘。不能把缺少上涨证据当作中性或必然下跌。
只输出JSON：{"direction":"BULLISH|NEUTRAL|BEARISH|NO_SIGNAL","reasoning":"简短中文证据和局限","supportingKeys":["gold1"]}。supportingKeys只可引用gold1/gold5/gold20/range1/DFII10/DTWEXBGS；有新增事件块时可引用CPI_CONSENSUS。不要输出未经校准的概率。
`;
 const eventBlock=`新增已观察事件信息：${JSON.stringify(consensus)}。此处consensus是发布前市场共识，providerProjection是供应商自身预测，actual和surprise未知。不能将预期上升说成实际超预期，也不能直接推定黄金涨跌。只可讨论未知实际值下的信息与局限。\n`;
 const i={version:'gold-prospective-event-pair-v1',preparedAt:new Date().toISOString(),targetDate:'2026-10-14',targetStart:deadline,targetEnd:'2026-10-14T20:00:00Z',
  settlementBaseDate:'2026-10-13',settlementBasePrice:null,lastKnownGoldDate:'2026-10-12',contextHash:sha(raw),oldHourSourceHash:sha(fs.readFileSync(oldPath)),
  consensus,facts,macro,weekendRowsExcluded:source.values.filter(r=>!weekday(r.datetime)).length,modelDigest:digest,actual:null,hit:null,
  eventBlock,controlRequest:request(prompt),candidateRequest:request(prompt+eventBlock),controlPromptHash:sha(prompt),candidatePromptHash:sha(prompt+eventBlock)};
 verify(i);write(out,i);console.log(JSON.stringify({prepared:true,facts,macro,weekendRowsExcluded:i.weekendRowsExcluded}));
} else if(process.argv[2]==='--run') {
 const [inputPath,out]=process.argv.slice(3);assert.ok(out&&!fs.existsSync(out));const raw=fs.readFileSync(inputPath,'utf8'),i=JSON.parse(raw);verify(i);
 const tags=await (await fetch('http://127.0.0.1:11435/api/tags')).json();assert.ok(tags.models.some(m=>m.name===model&&m.digest===digest));
 assert.ok(Date.now()<Date.parse(deadline));write(out+'.started',{startedAt:new Date().toISOString(),inputHash:sha(raw),doNotRetry:true});
 const result={version:i.version,inputHash:sha(raw),targetDate:i.targetDate,targetStart:i.targetStart,targetEnd:i.targetEnd,settlementBaseDate:i.settlementBaseDate,
  settlementBasePrice:null,researchOnly:true,officialSessionCertified:false,modelDigest:digest,actual:null,hit:null,outputs:[]};
 for(const name of ['control','candidate']){
  assert.ok(Date.now()<Date.parse(deadline));const entry={name,startedAt:new Date().toISOString(),promptHash:i[name+'PromptHash']};
  try {const reply=await fetch('http://127.0.0.1:11435/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(i[name+'Request']),signal:AbortSignal.timeout(180000)});
   entry.httpStatus=reply.status;entry.rawResponse=await reply.text();entry.completedAt=new Date().toISOString();write(out+'.'+name+'.raw',entry);
   assert.equal(reply.status,200);assert.ok(Date.parse(entry.completedAt)<Date.parse(deadline));const response=JSON.parse(entry.rawResponse);
   assert.equal(response.model,model);assert.equal(response.done,true);assert.equal(response.done_reason,'stop');entry.content=JSON.parse(response.message.content);
   assert.ok(['BULLISH','NEUTRAL','BEARISH','NO_SIGNAL'].includes(entry.content.direction));assert.ok(entry.content.reasoning?.trim());
   assert.ok(Array.isArray(entry.content.supportingKeys)&&entry.content.supportingKeys.length>0);
   assert.ok(entry.content.supportingKeys.every(k=>['gold1','gold5','gold20','range1','DFII10','DTWEXBGS',...(name==='candidate'?['CPI_CONSENSUS']:[])].includes(k)));
   entry.syntaxPassed=true;
  }catch(e){entry.failure=e.name;entry.syntaxPassed=false;entry.completedAt??=new Date().toISOString();}
  result.outputs.push(entry);
 }
 write(out,result);console.log(JSON.stringify(result.outputs.map(e=>({name:e.name,syntaxPassed:e.syntaxPassed,content:e.content,failure:e.failure}))));
}else throw new Error('请使用--prepare或--run');
