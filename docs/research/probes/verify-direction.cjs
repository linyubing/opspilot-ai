// 保存的研究汇总仍须匹配真实归档、当前源码及独立Java复算。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),cp=require('node:child_process');
const {check}=require('./DirectionCompare.cjs');
function text(file){const b=fs.readFileSync(file);return b.toString(b[0]===255?'utf16le':'utf8').replace(/^\uFEFF/,'');}
const nodeFile=process.argv[2],javaFile=process.argv[3];if(!nodeFile||!javaFile)throw new Error('需要Node与Java真实诊断输出路径');
const node=JSON.parse(text(nodeFile)),java=JSON.parse(text(javaFile).split('JAVA_DIRECTION_RESULT=')[1].trim());
const expected=JSON.parse(cp.execFileSync(process.execPath,[path.join(__dirname,'run-direction.cjs')],{encoding:'utf8',maxBuffer:16*1024*1024}));
assert.deepEqual(node,expected);assert.equal(check(node,java),true);
if(process.argv[4])assert.deepEqual(node,JSON.parse(text(process.argv[4])));
console.log('DIRECTION_VERIFY_PASS: 720 dates, 5 magnitude groups, 3 folds, 6 models, decimal outcomes and probability ranking');
