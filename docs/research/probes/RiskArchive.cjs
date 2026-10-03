'use strict';
const assert=require('node:assert/strict');
const START='2007-11-01',END='2024-11-08';
function day(s){assert.equal(typeof s,'string');assert.match(s,/^\d{4}-\d{2}-\d{2}$/);assert.equal(new Date(s).toISOString().slice(0,10),s);return s;}
// 按年分块，API分页完整结束后才返回归档；缺失标记原样保留。
async function collect(get) {
  const dates=await get('series/vintagedates',{sort_order:'asc',limit:1,realtime_end:END});
  assert.equal(dates.vintage_dates?.length,1);const first=day(dates.vintage_dates[0]);assert.ok(first<=END);
  const rows=[],chunks=[];let from=first<START?START:first;
  while(from<=END){
    const yearEnd=from.slice(0,4)+'-12-31',to=yearEnd<END?yearEnd:END;let offset=0,total=null;
    do {
      const r=await get('series/observations',{observation_start:START,observation_end:END,realtime_start:from,realtime_end:to,output_type:1,limit:100000,offset});
      assert.equal(r.output_type,1);assert.equal(r.realtime_start,from);assert.equal(r.realtime_end,to);
      assert.ok(Number.isSafeInteger(r.count)&&r.count>=0);assert.equal(r.offset,offset);assert.ok(Array.isArray(r.observations));
      if(total===null)total=r.count;assert.equal(r.count,total);assert.ok(r.observations.length<=100000&&offset+r.observations.length<=total);
      assert.ok(r.observations.length>0||offset===total);
      for(const row of r.observations){
        const d=day(row.date),a=day(row.realtime_start),b=day(row.realtime_end);
        assert.ok(d>=START&&d<=END&&a>=from&&b<=to&&a<=b);
        assert.equal(typeof row.value,'string');assert.ok(row.value==='.'||(/^\d+(\.\d+)?$/.test(row.value)&&Number.isFinite(Number(row.value))&&Number(row.value)>0));
        rows.push({date:d,realtime_start:a,realtime_end:b,value:row.value});
      }
      offset+=r.observations.length;
    }while(offset<total);
    assert.equal(offset,total);chunks.push({start:from,end:to,count:offset});
    from=new Date(Date.parse(to)+86400000).toISOString().slice(0,10);
  }
  assert.ok(rows.length>0);const byDate=new Map();
  for(const r of rows){const list=byDate.get(r.date)||[];list.push(r);byDate.set(r.date,list);}
  for(const list of byDate.values()){list.sort((a,b)=>a.realtime_start.localeCompare(b.realtime_start));for(let i=1;i<list.length;i++)assert.ok(list[i].realtime_start>list[i-1].realtime_end);}
  return {series:'VIXCLS',fetchedAt:new Date().toISOString(),observationStart:START,observationEnd:END,realtimeStart:START,realtimeEnd:END,outputType:1,firstAvailableVintage:first,count:rows.length,chunks,observations:rows};
}
function publish(out,archive){
  const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
  const target=fs.realpathSync(path.resolve(__dirname,'../../../backend/target'));
  out=path.resolve(out);const relative=path.relative(target,out);
  assert.ok(relative&&!relative.startsWith('..')&&!path.isAbsolute(relative));assert.ok(!fs.existsSync(out));
  const parent=path.dirname(out);let existing=parent;while(!fs.existsSync(existing))existing=path.dirname(existing);
  const realExisting=fs.realpathSync(existing),existingRel=path.relative(target,realExisting);assert.ok(!existingRel.startsWith('..')&&!path.isAbsolute(existingRel));
  fs.mkdirSync(parent,{recursive:true});
  const realParent=fs.realpathSync(parent),rel=path.relative(target,realParent);assert.ok(!rel.startsWith('..')&&!path.isAbsolute(rel));
  const pending=fs.mkdtempSync(path.join(realParent,'.risk-pending-'));
  const bytes=JSON.stringify(archive);fs.writeFileSync(path.join(pending,'VIXCLS.json'),bytes,{encoding:'utf8',flag:'wx'});
  const manifest={series:'VIXCLS',count:archive.count,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),source:'https://api.stlouisfed.org/fred/series/observations'};
  fs.writeFileSync(path.join(pending,'manifest.json'),JSON.stringify(manifest),{encoding:'utf8',flag:'wx'});
  assert.ok(!fs.existsSync(out));fs.renameSync(pending,out);return out;
}
module.exports={collect,publish};
