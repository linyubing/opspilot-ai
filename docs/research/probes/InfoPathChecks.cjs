const { test } = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
test('明确指定不存在的审计归档必须失败，不能忽略入口或静默跳过', () => {
  const directory = fs.mkdtempSync(path.join(__dirname,'../../../backend/target/info-path-'));
  const env = {...process.env, INFO_AUDIT_FILE:path.join(directory,'missing.json')};
  // 子进程应启动自己的测试运行器，不能继承当前运行器的内部子测试标记。
  delete env.NODE_TEST_CONTEXT;
  const result = cp.spawnSync(process.execPath, ['--test',path.join(__dirname,'InfoVerifierChecks.cjs')], {
    env, encoding:'utf8'
  });
  assert.notEqual(result.status, 0, '错误归档路径不应仍运行默认文件并报告成功');
});
