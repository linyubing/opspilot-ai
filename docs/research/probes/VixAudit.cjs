// 独立核验Java导出的VIX特征，不调用Java公式评分。
'use strict';
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto'), assert=require('node:assert/strict');
const {recent, hashWindow}=require('./InfoMath.cjs');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function audit(tree, source, result) {
  assert.equal(result.version,'vix-known-before-day-four-v1');
  assert.equal(result.promotionAllowed,false);
  assert.deepEqual(result.featureNames,['vixLevel','vixChange1','vixChange5','vixChange20']);
  assert.equal(tree.inputs.length,4361); assert.equal(result.windows.length,4361); assert.equal(result.vectors.length,4361);
  assert.equal(result.treeHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'))));
  assert.equal(result.protocolHash,sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-vix-protocol.md'))));
  const names=['RiskVectors.java','VixFeatures.java','VixFeatureChecks.java','FredHistory.java'];
  assert.deepEqual(Object.keys(result.codeHashes).sort(),names.slice().sort());
  for(const name of names) {
    const file=name==='FredHistory.java'?path.join(__dirname,'../../../backend/src/main/java/com/opspilot/ai/macrodata',name):path.join(__dirname,name);
    assert.equal(result.codeHashes[name],sha(fs.readFileSync(file)));
  }
  const available=new Set();let missing=0;
  for(let i=0;i<tree.inputs.length;i++) {
    const input=tree.inputs[i],rows=recent(source,input.date,21),row=result.vectors[i];
    assert.deepEqual(result.windows[i],{date:input.date,count:rows.length,latest:rows[0]?.date??null,hash:hashWindow('VIXCLS',rows)});
    assert.equal(row.date,input.date);assert.equal(row.target,input.target);
    assert.ok(input.date<input.target&&input.target<'2024-11-11');
    if(i)assert.ok(tree.inputs[i-1].date<input.date);
    if(rows.length!==21){assert.equal(row.values,null);missing++;continue;}
    const v=rows.map(r=>Number(r.value)); assert.ok(v.every(x=>Number.isFinite(x)&&x>0));
    const expected=[v[0],... [1,5,20].map(j=>100*(v[0]/v[j]-1))];
    assert.ok(Array.isArray(row.values)&&row.values.length===4);
    for(let j=0;j<4;j++) assert.ok(Number.isFinite(row.values[j])&&Math.abs(row.values[j]-expected[j])<=1e-10,'VIX独立公式不一致');
    available.add(input.date);
  }
  const validation=tree.folds.flatMap(f=>f.predictions);
  assert.equal(validation.length,720);assert.equal(new Set(validation.map(r=>r.date)).size,720);
  for(const row of validation)assert.ok(available.has(row.date));
  const training=tree.folds.map(f=>({start:f.start,count:tree.inputs.filter(r=>r.date<f.start&&r.target<f.start&&available.has(r.date)).length}));
  assert.deepEqual(training.map(r=>r.count),[2915,3155,3395]);
  return {count:4361,complete:available.size,missing,validationCount:720,training,promotionAllowed:false};
}
module.exports = { audit };
