'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict'),crypto=require('node:crypto'),path=require('node:path');
const {recent,hashWindow,group,age}=require('./InfoMath.cjs');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const first=process.argv[2],second=process.argv[3],javaFile=process.argv[4];
assert.ok(first&&second&&javaFile&&process.argv.length===5);
// 首先认证冻结黄金参照，而不是仅信任日期数量。
require('node:child_process').execFileSync(process.execPath,[path.join(__dirname,'verify-tree.cjs'),path.join(__dirname,'../2026-10-03-tree-check.json')],{stdio:'pipe',maxBuffer:16*1024*1024});
function load(dir){const bytes=fs.readFileSync(path.join(dir,'VIXCLS.json')),m=JSON.parse(fs.readFileSync(path.join(dir,'manifest.json'),'utf8'));assert.equal(sha(bytes),m.sha256);const r=JSON.parse(bytes);assert.equal(r.count,r.observations.length);assert.equal(r.series,'VIXCLS');return {bytes,r};}
const a=load(first),b=load(second),strip=r=>Object.fromEntries(Object.entries(r).filter(([k])=>k!=='fetchedAt'));
assert.deepEqual(strip(a.r),strip(b.r));const java=JSON.parse(fs.readFileSync(javaFile,'utf8'));assert.equal(java.sourceHash,sha(a.bytes));
const tree=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'),'utf8'));
assert.equal(tree.inputs.length,4361);assert.equal(java.windows.length,4361);
const validation=new Set(tree.folds.flatMap(f=>f.predictions.map(p=>p.date)));assert.equal(validation.size,720);
const full={},valid={};let firstComplete=null;
for(let i=0;i<tree.inputs.length;i++){
  const d=tree.inputs[i].date,rows=recent(a.r,d,21),last=rows[0]?.date??null;
  assert.deepEqual(java.windows[i],{date:d,count:rows.length,latest:last,hash:hashWindow('VIXCLS',rows)});
  const g=group(rows.length,last?age(d,last):null);full[g]=(full[g]||0)+1;if(validation.has(d))valid[g]=(valid[g]||0)+1;
  if(rows.length===21&&!firstComplete)firstComplete=d;
}
const train=tree.folds.map(f=>({start:f.start,completeTraining:tree.inputs.filter(r=>r.date<f.start&&r.target<f.start&&recent(a.r,r.date,21).length===21).length}));
const codeFiles=['RiskArchive.cjs','RiskArchiveChecks.cjs','RiskCheck.java','RiskPathChecks.cjs','download-risk.cjs','verify-risk.cjs','InfoMath.cjs'];
const codeHashes=Object.fromEntries(codeFiles.map(n=>[n,sha(fs.readFileSync(path.join(__dirname,n)))]));
for(const n of ['FredHistory.java','MacroObservation.java'])codeHashes[n]=sha(fs.readFileSync(path.resolve(__dirname,'../../../backend/src/main/java/com/opspilot/ai/macrodata',n)));
console.log(JSON.stringify({count:4361,validationCount:720,firstComplete,full,validation:valid,training:train,recordCount:a.r.count,firstVintage:a.r.firstAvailableVintage,sourceHashes:{first:sha(a.bytes),repeat:sha(b.bytes)},codeHashes,treeHash:sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'))),promotionAllowed:false},null,2));
