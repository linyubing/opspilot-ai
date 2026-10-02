// 故意破坏副本以验证拒绝能力；副本不是行情或实验结果。
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
const original=JSON.parse(fs.readFileSync(path.resolve(process.argv[2]||'docs/research/2026-10-02-atr-check.json'),'utf8'));
const cases={
  denominator:r=>{r.prices[0].close+=1;},
  bar:r=>{r.bars[20].close=String(Number(r.bars[20].close)+1);},
  otherFeature:r=>{r.inputs[0].x[1]+=.001;},
  scaling:r=>{r.folds[0].mean[0]+=1;},
  dates:r=>{r.folds[0].trainingDates[0]=r.folds[0].start;},
  prediction:r=>{const p=r.folds[0].predictions[0].candidate;p[0]+=.001;p[1]-=.001;}
};
for(const [name,mutate]of Object.entries(cases)){
  const r=structuredClone(original);mutate(r);const file=path.resolve('backend/target',`atr-invalid-${name}.json`);fs.writeFileSync(file,JSON.stringify(r));
  const run=cp.spawnSync(process.execPath,[path.join(__dirname,'verify-atr.cjs'),file],{encoding:'utf8'});
  assert.equal(run.error,undefined);assert.equal(run.signal,null);assert.equal(run.status,1,`${name} 损坏未被拒绝`);assert.match(run.stderr,/AssertionError/);
}
console.log('PASS: 拒绝分母/原始日线/其他特征/缩放/日期/概率损坏');
