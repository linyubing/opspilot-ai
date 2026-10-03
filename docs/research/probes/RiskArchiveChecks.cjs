'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {collect,publish}=require('./RiskArchive.cjs');
// 仅为接口边界数学夹具，不是行情，不能用于模型评分。
function client(change=()=>{}) {
  return async (route,p)=>{
    if(route==='series/vintagedates')return {count:1,vintage_dates:['2024-11-07']};
    const r={realtime_start:p.realtime_start,realtime_end:p.realtime_end,output_type:1,count:2,offset:p.offset,observations:[
      {date:'2024-11-06',realtime_start:'2024-11-07',realtime_end:'2024-11-08',value:'20'},
      {date:'2024-11-07',realtime_start:'2024-11-07',realtime_end:'2024-11-08',value:'.'}]};
    change(r);return r;
  };
}
test('采集保留版本和缺失标记，不伪造数值',async()=>{
  const r=await collect(client());assert.equal(r.count,2);assert.equal(r.series,'VIXCLS');
  assert.equal(r.observations[1].value,'.');assert.equal(r.firstAvailableVintage,'2024-11-07');
  assert.deepEqual(r.chunks,[{start:'2024-11-07',end:'2024-11-08',count:2}]);
});
test('拒绝分页总数与实际返回不符',async()=>{await assert.rejects(collect(client(r=>r.count=1)));});
test('拒绝版本超出下载区间',async()=>{await assert.rejects(collect(client(r=>r.observations[0].realtime_end='2024-11-09')));});
test('拒绝API返回错误输出口径',async()=>{await assert.rejects(collect(client(r=>r.output_type=4)));});
test('拒绝非有限数值',async()=>{await assert.rejects(collect(client(r=>r.observations[0].value='NaN')));});
test('跨分页保留全部记录和正确偏移',async()=>{
  const get=client();const r=await collect(async(route,p)=>{
    const v=await get(route,p);if(route==='series/vintagedates')return v;
    v.count=3;if(p.offset===0)return v;assert.equal(p.offset,2);
    v.observations=[{date:'2024-11-08',realtime_start:'2024-11-08',realtime_end:'2024-11-08',value:'21'}];return v;
  });assert.equal(r.count,3);assert.equal(r.observations[2].date,'2024-11-08');
});
test('分页中总数改变时拒绝发布',async()=>{
  const get=client();await assert.rejects(collect(async(route,p)=>{const r=await get(route,p);if(route==='series/observations')r.count=p.offset===0?3:4;return r;}));
});
test('归档仅发布到本地target且不能覆盖已存在批次',async()=>{
  const fs=require('node:fs'),path=require('node:path');const target=path.resolve(__dirname,'../../../backend/target');
  const root=fs.mkdtempSync(path.join(target,'risk-math-check-')),out=path.join(root,'archive'),r=await collect(client());
  assert.equal(publish(out,r),out);assert.deepEqual(JSON.parse(fs.readFileSync(path.join(out,'VIXCLS.json'),'utf8')),r);
  assert.throws(()=>publish(out,r));assert.throws(()=>publish(path.resolve(__dirname,'outside-risk'),r));
});
test('目录链接逃逸不能在外部创建父目录',async()=>{
  const fs=require('node:fs'),path=require('node:path'),os=require('node:os');
  const root=fs.mkdtempSync(path.resolve(__dirname,'../../../backend/target/risk-link-check-'));
  const outside=fs.mkdtempSync(path.join(os.tmpdir(),'risk-link-math-')),link=path.join(root,'link');
  fs.symlinkSync(outside,link,'junction');
  const archive=await collect(client());assert.throws(()=>publish(path.join(link,'new','archive'),archive));
  assert.equal(fs.existsSync(path.join(outside,'new')),false);
});
