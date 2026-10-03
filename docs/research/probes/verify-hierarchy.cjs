// 先认证原始真实来源，再验证两头拟合；只输出可公开的统计摘要。
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process'),crypto=require('node:crypto');
const {audit}=require('./HierarchyAudit.cjs');
const file=process.argv[2];if(!file)throw new Error('需要真实两阶段结果路径');
const treeFile=path.join(__dirname,'../2026-10-03-tree-check.json');
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-tree.cjs'),treeFile],{stdio:'pipe',maxBuffer:16*1024*1024});
const raw=fs.readFileSync(file),tree=JSON.parse(fs.readFileSync(treeFile,'utf8')),result=JSON.parse(raw.toString('utf8'));
const summary=audit(tree,result);summary.inputHashes.result=crypto.createHash('sha256').update(raw).digest('hex');
if(process.argv[3]){const repeat=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));audit(tree,repeat);const a={...result},b={...repeat};delete a.createdAt;delete b.createdAt;assert.deepEqual(a,b);}
console.log(JSON.stringify(summary,null,2));
