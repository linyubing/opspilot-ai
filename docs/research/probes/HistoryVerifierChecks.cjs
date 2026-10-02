// 故意损坏归档副本，验证核验器会拒绝；这些副本不是行情或实验结果。
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
const input=path.resolve(process.argv[2]||'docs/research/2026-10-02-history-check.json');
const original=JSON.parse(fs.readFileSync(input,'utf8')),output=path.resolve('backend/target');
const cases={
  input:r=>{r.inputs[0].x[0]+=1;},
  scaling:r=>{r.folds[0].mean[0]+=1;},
  dates:r=>{r.folds[0].trainingDates[0]=r.folds[0].start;},
  prediction:r=>{const p=r.folds[0].predictions[0].candidate;p[0]+=.001;p[1]-=.001;}
};
for(const [name,mutate]of Object.entries(cases)){
  const r=structuredClone(original);mutate(r);const file=path.join(output,`history-invalid-${name}.json`);
  fs.writeFileSync(file,JSON.stringify(r));
  const run=cp.spawnSync(process.execPath,[path.join(__dirname,'verify-history.cjs'),file],{encoding:'utf8'});
  assert.equal(run.error,undefined);assert.equal(run.signal,null);assert.equal(run.status,1,`${name} 损坏未被拒绝`);
  assert.match(run.stderr,/AssertionError/);
}
console.log('PASS: 拒绝输入/标准化/训练日期/逐日概率损坏');
