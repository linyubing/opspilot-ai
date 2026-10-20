// 正式预测身份和承诺展示测试；不访问行情、不调用模型。
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function page() {
    const dir = path.resolve(__dirname, '../../main/resources/static');
    const html = fs.readFileSync(path.join(dir, 'forecast.html'), 'utf8');
    const nodes = Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)]
        .map(m => [m[1], {textContent: ''}]));
    const script = fs.readFileSync(path.join(dir, 'forecast.js'), 'utf8');
    const start = script.indexOf('function renderIdentity(');
    const end = script.indexOf('function renderForecast(', start);
    const context = vm.createContext({text:(id,value)=> {nodes[id].textContent=value;}, Date, Intl});
    assert.ok(start >= 0, '页面应有正式身份呈现入口');
    vm.runInContext(script.slice(start, end), context);
    return {nodes, context};
}

test('结算后仍展示原承诺目标，模型身份与统计实验分开', () => {
    const {nodes,context} = page();
    context.renderIdentity({modelName:'local-model',promptVersion:'session-v1',promptHash:'abc',
        forecastRuleVersion:'rule-v1',targetDate:'2026-08-28',
        timing:{targetDate:'2026-08-27',start:'2026-08-26T21:00:00Z',end:'2026-08-27T21:00:00Z',ruleVersion:'candidate-v1'}});
    assert.equal(nodes.targetSession.textContent,'2026-08-27');
    assert.equal(nodes.settlementDate.textContent,'2026-08-28');
    assert.match(nodes.forecastIdentity.textContent,/正式大模型.*local-model/);
    assert.match(nodes.forecastIdentity.textContent,/session-v1.*rule-v1/);
    assert.match(nodes.forecastIdentity.textContent,/不代表.*统计实验|统计实验.*不代表/);
    assert.match(nodes.localInterval.textContent,/Asia\/Shanghai/);
    assert.match(nodes.localInterval.textContent,/2026/);
});

test('旧记录缺版本或时段时显示未知，不追认历史资格', () => {
    const {nodes,context} = page();
    context.renderIdentity({expectedTargetDate:'2026-08-27'});
    assert.match(nodes.forecastIdentity.textContent,/未知/);
    assert.match(nodes.localInterval.textContent,/未保存/);
    assert.equal(nodes.settlementDate.textContent,'尚未结算');
});
