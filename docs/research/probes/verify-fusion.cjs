// 实际黄金绑定由冻结验收器复核，再独立检查宏观九维及后融合。
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
const {audit}=require('./FusionAudit.cjs');
const file=process.argv[2];if(!file)throw new Error('需要真实融合实验JSON路径');
if(!process.env.FRED_HISTORY_DIR)throw new Error('需要真实FRED归档，不得生成替代行情');
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-tree.cjs'),path.join(__dirname,'../2026-10-03-tree-check.json')],{stdio:'pipe',maxBuffer:16*1024*1024});
const report=JSON.parse(fs.readFileSync(file,'utf8')),reference=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-02-history-check.json'),'utf8'));
const sources=Object.fromEntries(['DFII10','DTWEXBGS'].map(id=>[id,JSON.parse(fs.readFileSync(path.join(process.env.FRED_HISTORY_DIR,id+'.json'),'utf8'))]));
const result=audit(report,reference,sources);
if(process.argv[3]){const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));audit(repeat,reference,sources);
  for(const key of Object.keys(report).filter(k=>k!=='createdAt'))assert.deepEqual(report[key],repeat[key]);}
console.log(JSON.stringify(result,null,2));
