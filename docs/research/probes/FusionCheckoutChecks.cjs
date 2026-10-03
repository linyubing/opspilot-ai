const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');
const root=path.join(__dirname,'../../..');
// git实际检出过滤器，而非只看.gitattributes有没有写某句话。
for(const file of [
  'backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilder.java',
  'backend/src/main/java/com/opspilot/ai/forecast/learning/FeatureScaler.java',
  'backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java',
  'backend/src/main/java/com/opspilot/ai/macrodata/FredHistoryStore.java',
  'backend/src/main/java/com/opspilot/ai/macrodata/FredHistory.java'
])test('生产指纹重新检出保持一致：'+path.basename(file),()=>{
  const current=fs.readFileSync(path.join(root,file));
  const canonical=cp.execFileSync('git',['show','HEAD:'+file],{cwd:root});
  assert.equal(current.toString('utf8').replace(/\r\n/g,'\n'),canonical.toString('utf8'),'本轮不修改生产代码');
  const checkout=cp.execFileSync('git',['-c','core.autocrlf=true','cat-file','--filters','HEAD:'+file],{cwd:root});
  assert.ok(current.equals(checkout),'Windows检出不应改变冻结指纹字节');
});
