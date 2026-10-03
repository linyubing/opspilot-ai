const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {check}=require('./DirectionCompare.cjs');
const root=path.join(__dirname,'../../..');
function read(name){const b=fs.readFileSync(path.join(root,'backend/target',name));return b.toString(b[0]===255?'utf16le':'utf8').replace(/^\uFEFF/,'');}
const node=JSON.parse(read('direction-results.log')),java=JSON.parse(read('direction-java.log').split('JAVA_DIRECTION_RESULT=')[1].trim());
test('两种独立实现的真实方向诊断一致',()=>assert.equal(check(node,java),true));
for(const [name,damage]of [
  ['拒绝不同样本数量',r=>r.count--],
  ['拒绝幅度分组改变',r=>r.groups[0].count--],
  ['拒绝混淆计数改变',r=>r.all.candidate.matrix[0][0]++],
  ['拒绝AUC改变',r=>r.folds[1].models.macro.directionalAuc+=.01],
  ['拒绝概率误差改变',r=>r.all.candidate.brierScore+=.01]
])test(name,()=>{const copy=structuredClone(java);damage(copy);assert.throws(()=>check(node,copy));});
