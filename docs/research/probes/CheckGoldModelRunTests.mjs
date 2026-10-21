import assert from 'node:assert/strict';
import {test} from 'node:test';
import {checkRun} from './CheckGoldModelRun.mjs';

// 仅用于拒绝规则的合成结构样例，不是行情、运行回执或模型成绩。
const protocol = {model:'test-model', digest:'a'.repeat(64),configSha256:{file:'b'.repeat(64)}};
function fixture() {
    const model = {name:protocol.model,digest:protocol.digest};
    return {status:'COMPLETED',protocolSha256:'c'.repeat(64), configSha256:protocol.configSha256,
        request:{model:protocol.model,think:false,stream:false,options:{num_ctx:8192,num_predict:2048,temperature:0}},
        reportedModel:protocol.model, response:{signal:'ABSTAINED',direction:null},
        beforeModel:model,afterModel:model,loadedModel:model,
        beforeVersion:{version:'test'},afterVersion:{version:'test'},
        startedAt:'2026-10-21T00:00:00Z',beforeCheckedAt:'2026-10-21T00:00:01Z',
        modelCallStartedAt:'2026-10-21T00:00:02Z',modelCallEndedAt:'2026-10-21T00:00:03Z',
        afterCheckedAt:'2026-10-21T00:00:04Z'};
}
test('完整结构满足已固定规则，但不宣称执行工件认证', () => {
    assert.equal(checkRun(fixture(),protocol,'c'.repeat(64)).matched,true);
});
for (const [name, change] of [
    ['失败尝试', r => {r.status='FAILED'}],
    ['协议替换', r => {r.protocolSha256='d'.repeat(64)}],
    ['实际请求开启思考', r => {r.request.think=true}],
    ['双处思考开关冲突', r => {r.request.options.think=true}],
    ['输出参数被丢弃', r => {delete r.request.options.num_predict}],
    ['响应模型错配', r => {r.reportedModel='another-model'}],
    ['加载工件不同', r => {r.loadedModel={...r.loadedModel,digest:'e'.repeat(64)}}],
    ['前后服务版本改变', r => {r.afterVersion.version='changed'}],
    ['无行情仍出方向', r => {r.response={signal:'PREDICTED',direction:'BULLISH'}}],
    ['观察顺序逆转', r => {r.afterCheckedAt=r.startedAt}],
]) test(`拒绝${name}`, () => {
    const run=fixture(); change(run);
    assert.throws(() => checkRun(run,protocol,'c'.repeat(64)));
});
