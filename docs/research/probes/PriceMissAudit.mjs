import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';

// 只读回查既有错判的真实收盘价，不训练、不改标签、不碰最终留出期。
const [input, output, commit] = process.argv.slice(2);
assert.match(commit ?? '', /^[0-9a-f]{40}$/);
assert(!fs.existsSync(output), '禁止覆盖已归档审计');
const raw = fs.readFileSync(input, 'utf8');
const report = JSON.parse(raw);
assert.equal(report.cutoffExclusive, '2024-11-11');
const rows = report.folds.flatMap((fold, index) => fold.rows.map(row => ({...row, fold: index + 1})));
assert.equal(rows.length, 720);
for (const r of rows) {
  for (const date of [r.date, r.target]) {
    assert.match(date, /^\d{4}-\d{2}-\d{2}$/);
    assert(date < '2024-11-11');
  }
  assert(r.date < r.target);
}
const values = rows.map(r => `('${r.date}'::date,'${r.target}'::date)`).join(',');
// SQL 侧独立计算标签及区间，随后与整数运算核对；事务强制只读。
const sql = `begin transaction isolation level repeatable read read only;
with dates(base_date,target_date) as (values ${values}), prices as (
 select d.*, b.close_price::text as base, t.close_price::text as target,
 (t.close_price-b.close_price)/b.close_price*100 as ret
 from dates d join gold_daily_bar b on b.price_date=d.base_date
 join gold_daily_bar t on t.price_date=d.target_date
 where b.symbol='XAUUSD' and t.symbol='XAUUSD'
 and b.provider='twelve_data' and t.provider='twelve_data'
 and b.price_date < date '2024-11-11' and t.price_date < date '2024-11-11'
)
select json_agg(json_build_object('date',base_date,'targetDate',target_date,
 'base',base,'target',target,'actualReturn',ret::text,
 'label',case when ret>0.5 then 'BULLISH' when ret< -0.5 then 'BEARISH' else 'NEUTRAL' end,
 'bucket',case when abs(ret)<0.45 then 'CORE' when abs(ret)<=0.55 then 'BOUNDARY'
 when abs(ret)<1 then 'MODERATE' else 'LARGE' end) order by base_date) from prices;
rollback;`;
const prices = JSON.parse(execFileSync('D:/workFile/postgresql/17/bin/psql.exe',
  ['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-h','localhost','-U','postgres','-d','opspilot_ai','-c',sql],
  {encoding:'utf8',maxBuffer:4*1024*1024}).trim());
assert.equal(prices.length, 720);
const byDate = new Map(prices.map(p => [p.date, p]));
assert.equal(byDate.size, 720);
const fixed = text => {
  assert.match(text, /^\d+\.\d{8}$/);
  return BigInt(text.replace('.',''));
};
const summary = {};
const details = [];
const dirs = ['BULLISH','NEUTRAL','BEARISH'];
for (const r of rows) {
  const p = byDate.get(r.date);
  assert.equal(p.targetDate, r.target);
  const base = fixed(p.base), diff = fixed(p.target)-base;
  const abs = diff<0n ? -diff : diff;
  // 价格都是八位小数，用交叉相乘避免浮点数误差改变 ±0.5% 边界。
  const label = diff*200n>base ? 'BULLISH' : diff*200n< -base ? 'BEARISH' : 'NEUTRAL';
  const bucket = abs*10000n<base*45n ? 'CORE' : abs*10000n<=base*55n ? 'BOUNDARY'
    : abs*100n<base ? 'MODERATE' : 'LARGE';
  assert.equal(label,p.label); assert.equal(label,r.actual); assert.equal(bucket,p.bucket);
  const probs = dirs.map(d => r.logistic[d.toLowerCase()]);
  assert(probs.every(x=>Number.isFinite(x)&&x>=0&&x<=1));
  assert(Math.abs(probs.reduce((a,b)=>a+b,0)-1)<1e-8);
  const confidence = Math.max(...probs), predicted = dirs[probs.indexOf(confidence)];
  const hit = predicted===label;
  for (const key of ['ALL',`FOLD_${r.fold}`,bucket,`ACTUAL_${label}`]) {
    const s = summary[key] ??= {count:0,hits:0,misses:0,signals:0,signalHits:0,
      opposite:0,directionAsNeutral:0,neutralAsDirection:0};
    s.count++; s.hits+=Number(hit); s.misses+=Number(!hit);
    if(confidence>=0.55) {s.signals++;s.signalHits+=Number(hit);}
    if(!hit) {
      s.opposite+=Number(label!=='NEUTRAL'&&predicted!=='NEUTRAL');
      s.directionAsNeutral+=Number(label!=='NEUTRAL'&&predicted==='NEUTRAL');
      s.neutralAsDirection+=Number(label==='NEUTRAL');
    }
  }
  details.push({...p,fold:r.fold,predicted,confidence,hit,probabilities:r.logistic});
}
assert.equal(summary.ALL.hits,265,'旧预测方向不得改变');
const result={version:'gold-price-miss-audit-v1',createdAt:new Date().toISOString(),gitCommit:commit,
 inputHash:crypto.createHash('sha256').update(raw).digest('hex'),cutoffExclusive:'2024-11-11',
 holdoutRead:false,trainingPerformed:false,formalForecastChanged:false,summary,rows:details};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(summary,null,2));
