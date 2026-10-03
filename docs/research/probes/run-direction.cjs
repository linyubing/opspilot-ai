// 原实验先完整验收，再分析已冻结输出；不查询或写入数据库。
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const {diagnose}=require('./DirectionAudit.cjs');
const treeFile=path.join(__dirname,'../2026-10-03-tree-check.json'),fusionFile=path.join(__dirname,'../2026-10-03-fusion-check.json');
cp.execFileSync(process.execPath,[path.join(__dirname,'verify-fusion.cjs'),fusionFile],{stdio:'pipe',maxBuffer:16*1024*1024});
const result=diagnose(JSON.parse(fs.readFileSync(treeFile,'utf8')),JSON.parse(fs.readFileSync(fusionFile,'utf8')));
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
result.inputHashes={tree:sha(fs.readFileSync(treeFile)),fusion:sha(fs.readFileSync(fusionFile))};
result.sourceHashes=Object.fromEntries(['DirectionMath.cjs','DirectionAudit.cjs','run-direction.cjs','InfoMath.cjs','DirectionProbe.java','DirectionProbeChecks.java','DirectionCompare.cjs','verify-direction.cjs'].map(n=>[n,sha(fs.readFileSync(path.join(__dirname,n)))]));
result.protocolHash=sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-discrimination-protocol.md')));
console.log(JSON.stringify(result,null,2));
