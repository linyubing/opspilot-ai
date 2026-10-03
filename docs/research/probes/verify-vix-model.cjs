// 完整真实输入认证后，独立重算两个匹配模型；可检查重复输出。
'use strict';
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {audit}=require('./VixModelAudit.cjs');
const [file,repeat]=process.argv.slice(2);assert.ok(file);
const root=path.join(__dirname,'../../..');
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-vix-vectors.cjs'),path.join(root,'backend/target/vix-history-first'),
  path.join(root,'backend/target/vix-history-repeat'),path.join(root,'backend/target/vix-four-vectors.json'),path.join(root,'backend/target/vix-four-vectors-repeat.json')],{stdio:'pipe',maxBuffer:24*1024*1024});
const tree=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'),'utf8'));
const vectors=JSON.parse(fs.readFileSync(path.join(root,'backend/target/vix-four-vectors.json'),'utf8'));
const bytes=fs.readFileSync(file),result=JSON.parse(bytes),summary=audit(tree,vectors,result);
if(repeat){const other=JSON.parse(fs.readFileSync(repeat,'utf8'));audit(tree,vectors,other);
  const a={...result},b={...other};delete a.createdAt;delete b.createdAt;assert.deepEqual(a,b);}
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');summary.resultHash=sha(bytes);
summary.treeHash=result.treeHash;summary.vectorsHash=result.vectorsHash;summary.protocolHash=result.protocolHash;
summary.codeHashes={...result.sourceHashes};for(const n of ['VixFitMath.cjs','VixFitMathChecks.cjs','VixModelAudit.cjs','VixModelAuditChecks.cjs','verify-vix-model.cjs','InfoMath.cjs'])
  summary.codeHashes[n]=sha(fs.readFileSync(path.join(__dirname,n)));
console.log(JSON.stringify(summary,null,2));
