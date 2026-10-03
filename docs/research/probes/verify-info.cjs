// 独立绑定生产窗口、原始历史版本和冻结预测；不训练或修改模型。
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const assert = require('node:assert/strict'), cp = require('node:child_process');
const { recent, group, age, uuid, hashWindow, score } = require('./InfoMath.cjs');
const ids = ['DFII10','DTWEXBGS'], groups = ['NO_OBSERVATION','UNDER_21','AGE_1_3','AGE_4_7','AGE_OVER_7'];
const root = path.join(__dirname, '../../..'), sha = data => crypto.createHash('sha256').update(data).digest('hex');
const sourceFiles = [
  'docs/research/probes/InfoRun.java', 'docs/research/probes/InfoMath.cjs',
  'docs/research/probes/InfoMathChecks.cjs', 'docs/research/probes/verify-info.cjs',
  'backend/src/main/java/com/opspilot/ai/macrodata/FredHistoryStore.java',
  'backend/src/main/java/com/opspilot/ai/macrodata/FredHistory.java',
  'backend/src/main/java/com/opspilot/ai/macrodata/MacroObservation.java'
];
function audit(report, tree, sources) {
  assert.equal(report.version, 'gold-macro-availability-v1'); assert.equal(report.promotionAllowed, false);
  assert.match(report.gitCommit, /^[0-9a-f]{40}$/); assert.equal(report.cutoffExclusive, '2024-11-11');
  assert.equal(report.windowLimit, 21); assert.equal(report.macroInput.policy, 'fred-known-before-day-v1');
  assert.equal(report.rows.length, tree.inputs.length); assert.equal(report.rows.length, 4361);
  const trace = [], byDate = new Map();
  for (let i = 0; i < report.rows.length; i++) {
    const row = report.rows[i]; assert.equal(row.date, tree.inputs[i].date);
    assert.deepEqual(Object.keys(row.series).sort(), [...ids].sort());
    const series = {};
    for (const id of ids) {
      const input = sources[id], saved = row.series[id], selected = recent(input, row.date);
      const latest = selected.length ? selected[0] : null;
      assert.equal(saved.count, selected.length, `${row.date}/${id} 窗口条数`);
      assert.equal(saved.windowHash, hashWindow(id, selected), `${row.date}/${id} 窗口绑定`);
      const binding = latest ? { date:latest.date, value:latest.value, id:uuid(id, latest) } : null;
      assert.deepEqual(saved.latest, binding, `${row.date}/${id} 最新观测绑定`);
      const days = latest ? age(row.date, latest.date) : null;
      series[id] = { ...saved, age:days, group:group(selected.length, days),
        versionStart:latest?.realtime_start ?? null, versionEnd:latest?.realtime_end ?? null };
    }
    const count = Math.min(...ids.map(id => series[id].count));
    const days = count ? Math.max(...ids.map(id => series[id].age)) : null;
    const bound = { date:row.date, series, joint:group(count, days) };
    byDate.set(row.date, bound); trace.push(bound);
  }
  const fullCoverage = {};
  for (const id of [...ids,'joint']) {
    const entries = Object.fromEntries(groups.map(g => [g,0]));
    for (const row of trace) entries[id === 'joint' ? row.joint : row.series[id].group]++;
    fullCoverage[id] = entries;
  }
  const predictions = tree.folds.flatMap(f => f.predictions);
  assert.equal(predictions.length, 720);
  const validation = predictions.map((row,i) => {
    if (i) assert.ok(predictions[i-1].date < row.date);
    assert.ok(row.date < row.target && row.target < '2024-11-11');
    assert.ok(byDate.has(row.date));
    return { ...row, ...byDate.get(row.date) };
  });
  const summaries = {};
  for (const id of [...ids,'joint']) summaries[id] = groups.map(g => {
    const rows = validation.filter(r => (id === 'joint' ? r.joint : r.series[id].group) === g);
    return { group:g, candidate:score(rows, 'candidate'), reference:score(rows,'reference'), prior:score(rows,'prior') };
  });
  const ages = {};
  for (const id of ids) {
    const counts = new Map();
    for (const row of validation) counts.set(row.series[id].age, (counts.get(row.series[id].age)||0)+1);
    ages[id] = [...counts].sort(([a],[b]) => a-b).map(([age,count]) => ({age,count}));
  }
  return { status:'PASS', promotionAllowed:false, fullCoverage, all:score(validation,'candidate'),
    reference:score(validation,'reference'), prior:score(validation,'prior'), summaries, ages, validation };
}
function main() {
  const file = process.argv[2]; if (!file) throw new Error('需要宏观审计JSON路径');
  const report = JSON.parse(fs.readFileSync(file, 'utf8'));
  const treeFile = path.join(__dirname, '../2026-10-03-tree-check.json'), bytes = fs.readFileSync(treeFile);
  // 原验收器检查真实日线、标签、训练隔离、原生树概率，避免只信冻结文件名。
  cp.execFileSync(process.execPath, [path.join(__dirname,'verify-tree.cjs'),treeFile], {stdio:'pipe', maxBuffer:16*1024*1024});
  assert.equal(report.treeHash, sha(bytes));
  assert.deepEqual(Object.keys(report.sourceHashes).sort(), [...sourceFiles].sort());
  for (const [name,hash] of Object.entries(report.sourceHashes)) assert.equal(sha(fs.readFileSync(path.join(root,name))), hash);
  assert.equal(report.protocolHash, sha(fs.readFileSync(path.join(__dirname,'../2026-10-03-info-protocol.md'))));
  const directory = process.env.FRED_HISTORY_DIR; if (!directory) throw new Error('需要真实FRED历史归档，不得用生成值替代');
  const sources = {};
  for (const id of ids) {
    const raw = fs.readFileSync(path.join(directory, id+'.json')), input = JSON.parse(raw);
    assert.equal(report.macroInput[id+'.sha256'], sha(raw));
    assert.equal(input.series, id); assert.equal(input.outputType, 1); assert.equal(input.count, input.observations.length);
    assert.equal(report.macroInput[id+'.start'], input.realtimeStart); assert.equal(report.macroInput[id+'.end'], input.realtimeEnd);
    sources[id] = input;
  }
  const result = audit(report, JSON.parse(bytes), sources);
  if (process.argv[3]) {
    const repeat = JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
    for (const key of ['version','gitCommit','promotionAllowed','treeHash','macroInput','cutoffExclusive','windowLimit','sourceHashes','protocolHash','rows'])
      assert.deepEqual(report[key], repeat[key]);
  }
  // 包含实际观测追踪的完整验收输出只能重定向到被忽略的本地target目录。
  console.log(JSON.stringify(result,null,2));
}
if (require.main === module) main();
module.exports = { audit };
