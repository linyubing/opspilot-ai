'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process');
test('Java核验不能经target目录链接写到外部',()=>{
  const backend=path.resolve(__dirname,'../../../backend'),root=fs.mkdtempSync(path.join(backend,'target/risk-java-link-'));
  const outside=fs.mkdtempSync(path.join(os.tmpdir(),'risk-java-check-')),link=path.join(root,'link');fs.symlinkSync(outside,link,'junction');
  const classpath='target/probe-classes;target/classes;'+fs.readFileSync(path.join(backend,'target/probe-classpath.txt'),'utf8').trim();
  const normal=cp.spawnSync('java',['-cp',classpath,'com.opspilot.ai.macrodata.RiskCheck','target/vix-history-first/VIXCLS.json',path.join(root,'safe.json')],{cwd:backend,encoding:'utf8'});
  assert.equal(normal.error,undefined);assert.equal(normal.status,0);assert.ok(fs.existsSync(path.join(root,'safe.json')));
  const r=cp.spawnSync('java',['-cp',classpath,'com.opspilot.ai.macrodata.RiskCheck','target/vix-history-first/VIXCLS.json',path.join(link,'windows.json')],{cwd:backend,encoding:'utf8'});
  assert.equal(r.error,undefined);assert.notEqual(r.status,0);assert.equal(fs.existsSync(path.join(outside,'windows.json')),false);
});
