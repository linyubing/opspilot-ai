// 只研究美元摘要是否诱导误读；不连接数据库，不替换正式预测。
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';

const base = new URL('../', import.meta.url);
const inputPath = new URL('2026-10-11-summary-pair-input.json', base);
const resultPath = new URL('2026-10-11-summary-pair-result.json', base);
const marker = new URL('2026-10-11-summary-pair-started.json', base);
const model = 'qwen3.5:9b';
const digest = '6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7';
const deadline = '2026-10-11T20:00:00Z';
const removed = '美元指数解释：广义美元指数中期明显走强且短期继续走强，对黄金构成单因子压力。\n';
const hash = text => crypto.createHash('sha256').update(text).digest('hex');
const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const write = (path, value) => fs.writeFileSync(path, JSON.stringify(value, null, 2) + '\n', {flag: 'wx'});

function verify(input) {
  const control = input.controlRequest;
  const candidate = input.candidateRequest;
  const old = control.messages[0].content;
  const changed = candidate.messages[0].content;
  assert.equal(old.split(removed).length, 2);
  assert.equal(changed, old.replace(removed, ''));
  const copy = structuredClone(candidate);
  copy.messages[0].content = old;
  assert.deepEqual(copy, control);
  assert.equal(control.model, model);
  assert.equal(control.format, 'json');
  assert.deepEqual(control.options, {num_ctx: 8192, temperature: 0, seed: 42, num_predict: 2048});
  assert.equal(hash(old), input.controlPromptHash);
  assert.equal(hash(changed), input.candidatePromptHash);
  assert.equal(input.snapshot.id, '64b8c2a6-8b67-4f1d-a31f-b0f1f0d37e93');
  assert.equal(input.targetDate, '2026-10-12');
}

async function tags() {
  const response = await fetch('http://127.0.0.1:11435/api/tags', {signal: AbortSignal.timeout(8000)});
  assert.equal(response.status, 200);
  assert.ok((await response.json()).models.some(x => x.name === model && x.digest === digest));
}

if (process.argv[2] === '--prepare') {
  const prior = read(new URL('2026-10-11-native-json-result.json', base));
  const frozen = read(new URL('2026-10-11-evidence-pair-input.json', base));
  const control = structuredClone(prior.request);
  const candidate = structuredClone(control);
  candidate.messages[0].content = control.messages[0].content.replace(removed, '');
  const input = {experimentVersion: 'gold-summary-removal-diagnostic-v1',
    snapshot: frozen.snapshot, targetDate: '2026-10-12', preparedAt: new Date().toISOString(),
    modelDigest: digest, removedLine: removed, controlRequest: control, candidateRequest: candidate,
    controlPromptHash: hash(control.messages[0].content),
    candidatePromptHash: hash(candidate.messages[0].content)};
  verify(input);
  write(inputPath, input);
  console.log('PREPARED; ONE_REMOVED_LINE; NO_MODEL_CALL');
} else if (process.argv[2] === '--check') {
  const input = read(inputPath);
  verify(input);
  // 离线检查会拒绝多改参数或未删除摘要的输入，不伪造行情与模型结果。
  const bad = structuredClone(input);
  bad.candidateRequest.options.seed = 43;
  assert.throws(() => verify(bad));
  const unchanged = structuredClone(input);
  unchanged.candidateRequest = structuredClone(input.controlRequest);
  assert.throws(() => verify(unchanged));
  console.log('OFFLINE_INPUT_CHECKS_PASSED');
} else if (process.argv[2] === '--run') {
  const text = fs.readFileSync(inputPath, 'utf8');
  const input = JSON.parse(text);
  verify(input);
  assert.ok(Date.now() < Date.parse(deadline));
  await tags();
  // 开始标记只创建一次；请求中断也不重启、不覆盖已留存证据。
  write(marker, {startedAt: new Date().toISOString(), inputHash: hash(text), status: 'STARTED_DO_NOT_RETRY'});
  const result = {experimentVersion: input.experimentVersion, inputHash: hash(text),
    targetDate: input.targetDate, snapshotId: input.snapshot.id, modelDigest: digest,
    researchOnly: true, officialSessionCertified: false, trustedAccuracyEligible: false,
    actualReturn: null, hit: null, brierScore: null, logLoss: null, outputs: []};
  for (const variant of ['control', 'candidate']) {
    assert.ok(Date.now() < Date.parse(deadline));
    const entry = {variant, request: input[variant + 'Request'], startedAt: new Date().toISOString()};
    result.outputs.push(entry);
    try {
      const response = await fetch('http://127.0.0.1:11435/api/chat', {
        method: 'POST', headers: {'Content-Type': 'application/json; charset=utf-8'},
        body: JSON.stringify(entry.request), signal: AbortSignal.timeout(45000)});
      entry.httpStatus = response.status;
      entry.rawResponse = await response.text();
      entry.completedAt = new Date().toISOString();
      entry.rawResponseHash = hash(entry.rawResponse);
      // 每条原始响应独立且只创建一次，先存原文，后解析。
      write(new URL(`2026-10-11-summary-${variant}-raw.json`, base), entry);
      assert.ok(Date.parse(entry.completedAt) < Date.parse(deadline));
      const outer = JSON.parse(entry.rawResponse);
      assert.equal(entry.httpStatus, 200);
      assert.equal(outer.done, true);
      assert.equal(outer.done_reason, 'stop');
      assert.equal(outer.model, model);
      entry.content = JSON.parse(outer.message.content);
      entry.syntaxPassed = true;
    } catch (error) {
      entry.failure = error.name;
      entry.syntaxPassed = false;
      entry.completedAt ??= new Date().toISOString();
    }
  }
  await tags();
  result.status = 'OUTPUTS_RECORDED_NOT_SCORED';
  write(resultPath, result);
  console.log('OUTPUTS_RECORDED_NOT_SCORED; NO_DATABASE_WRITE');
} else {
  throw new Error('请使用 --prepare、--check 或 --run');
}
