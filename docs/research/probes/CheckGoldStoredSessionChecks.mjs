import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

// 仅复验已取得的真实归档；密钥置空，不创建行情、不请求供应商或连接数据库。
const file = resolve(process.argv[2] || 'backend/target/oct21-current-base-session.json');
const hash = 'b846bca9122fd983b2e77f5cf5f339945b1d59847f311b1c4081f652b04c79bf';
assert.equal(createHash('sha256').update(readFileSync(file)).digest('hex'), hash, '必须使用原归档，不接受现算替换摘要');
const cp = readFileSync('backend/target/oct21-runtime-cp.txt', 'utf8').trim()
    + ';' + resolve('backend/target/classes') + ';' + resolve('backend/target/oct21-session-probe');
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin/java.exe')
    : 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe';
const run = args => spawnSync(java, ['-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '--class-path', cp,
    'com.opspilot.ai.marketdata.CheckGoldStoredSession', ...args], {
    encoding:'utf8', env:{...process.env, OPSPILOT_DB_PASSWORD:'', TWELVE_DATA_API_KEY:''}, timeout:10000
});
const valid = run(['--replay', file, hash]);
assert.equal(valid.status, 0, valid.stderr);
const result = JSON.parse(valid.stdout.trim().split(/\r?\n/).findLast(line => line.startsWith('{')));
assert.equal(result.status, 'MATCHED');
assert.equal(result.expectedHours, 24);
assert.equal(result.receivedRows, 24);
assert.equal(result.externalCalls, 0);
assert.equal(result.databaseWrites, 0);
assert.equal(result.scoresChanged, false);
const invalid = run(['--replay', file, '0'.repeat(64)]);
assert.notEqual(invalid.status, 0);
assert.match(invalid.stderr, /核验归档摘要不符/);
const duplicate = run([file]);
assert.notEqual(duplicate.status, 0);
assert.match(duplicate.stderr, /归档已存在/);
console.log(JSON.stringify({checks:3, failed:0, input:'原真实归档', scope:'离线复算、摘要拒绝、同路径重取拒绝'}));
