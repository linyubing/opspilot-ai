const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {audit}=require('./FusionAudit.cjs');
const selected=process.env.FUSION_AUDIT_FILE || path.join(__dirname,'../2026-10-03-fusion-check.json');
const report=JSON.parse(fs.readFileSync(selected,'utf8'));
const reference=JSON.parse(fs.readFileSync(path.join(__dirname,'../2026-10-02-history-check.json'),'utf8'));
if (!process.env.FRED_HISTORY_DIR) throw new Error('真实审计需要FRED归档，不能生成替代行情');
const sources=Object.fromEntries(['DFII10','DTWEXBGS'].map(id=>[id,JSON.parse(fs.readFileSync(path.join(process.env.FRED_HISTORY_DIR,id+'.json'),'utf8'))]));
test('真实九维宏观、合法收敛和720条预测能够完整复核',()=>assert.equal(audit(report,reference,sources).status,'PASS'));
// 只破坏真实归档的内存副本；不改原市场数据文件。
for (const [name,damage] of [
  ['拒绝宏观输入被替换',r=>r.inputs[0].x[0]++],
  ['拒绝目标日被替换',r=>r.inputs[0].target=r.inputs[1].target],
  ['拒绝尚未结算训练样本',r=>r.folds[0].trainingDates[0]=r.folds[0].start],
  ['拒绝标准化泄漏或改变',r=>r.folds[0].mean[0]++],
  ['拒绝非最优权重',r=>r.folds[0].fit.weights[0]+=.1],
  ['拒绝把融合伪装成单模型',r=>r.folds[0].predictions[0].candidate=r.folds[0].predictions[0].price],
  ['拒绝源码摘要被改',r=>r.sourceHashes['docs/research/probes/FusionMix.java']='0'.repeat(64)],
  ['拒绝重复验证日期',r=>r.folds[0].predictions[1].date=r.folds[0].predictions[0].date]
]) test(name,()=>{const copy=structuredClone(report);damage(copy);assert.throws(()=>audit(copy,reference,sources));});
for (const [name,damage] of [
  ['拒绝来源下载区间元数据被改',s=>s.DFII10.chunks=[]],
  ['拒绝来源采集元数据被改',s=>s.DFII10.fetchedAt='invalid'],
  ['拒绝历史版本生效日被改',s=>s.DFII10.observations[0].realtime_start='2000-01-01'],
  ['拒绝真实历史数值被改',s=>{
    const used=require('./InfoMath.cjs').recent(s.DFII10,report.inputs[0].date)[0];used.value=String(Number(used.value)+.1);
  }]
]) test(name,()=>{const copy=structuredClone(sources);damage(copy);assert.throws(()=>audit(report,reference,copy));});
