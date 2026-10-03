// 先认证真实来源和窗口，再独立核验四特征；不训练或发布模型。
'use strict';
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {audit}=require('./VixAudit.cjs');
const [first,second,file,repeat]=process.argv.slice(2);
assert.ok(first&&second&&file);
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-risk.cjs'),first,second,file],{stdio:'pipe',maxBuffer:24*1024*1024});
const source=JSON.parse(fs.readFileSync(path.join(first,'VIXCLS.json'),'utf8'));
const tree=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-03-tree-check.json'),'utf8'));
const bytes=fs.readFileSync(file),result=JSON.parse(bytes),summary=audit(tree,source,result);
if(repeat){const other=JSON.parse(fs.readFileSync(repeat,'utf8'));audit(tree,source,other);assert.deepEqual(other,result);}
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
summary.resultHash=sha(bytes);summary.sourceHash=result.sourceHash;summary.treeHash=result.treeHash;
summary.protocolHash=result.protocolHash;summary.codeHashes={...result.codeHashes};
for(const name of ['VixAudit.cjs','VixAuditChecks.cjs','verify-vix-vectors.cjs','InfoMath.cjs'])summary.codeHashes[name]=sha(fs.readFileSync(path.join(__dirname,name)));
console.log(JSON.stringify(summary,null,2));
