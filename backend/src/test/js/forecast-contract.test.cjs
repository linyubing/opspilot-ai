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
        .map(m => [m[1], {textContent: '',children:[],replaceChildren(){this.children=[];},appendChild(row){this.children.push(row);}}]));
    const script = fs.readFileSync(path.join(dir, 'forecast.js'), 'utf8');
    const start = script.indexOf('function renderIdentity(');
    const end = script.indexOf('function renderForecast(', start);
    const context = vm.createContext({text:(id,value)=> {nodes[id].textContent=value;}, Date, Intl,
        byId:id=>nodes[id], document:{createElement:()=>({textContent:''})},
        percent:value=>value==null?'暂无':`${(value*100).toFixed(1)}%`});
    assert.ok(start >= 0, '页面应有正式身份呈现入口');
    vm.runInContext(script.slice(start, end), context);
    const stats = script.indexOf('function renderSignalStats(');
    if(stats>=0) vm.runInContext(script.slice(stats,script.indexOf('async function loadAccuracy()',stats)),context);
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

test('响应标识与不可变模型工件明确区分，切回旧记录不保留核对标记', () => {
    const {nodes,context} = page();
    context.renderIdentity({modelName:'qwen3.5:9b',reportedModel:'qwen3.5:9b'});
    assert.match(nodes.forecastIdentity.textContent,/响应模型标识已核对：qwen3.5:9b/);
    assert.match(nodes.forecastIdentity.textContent,/不是不可变工件版本/);
    assert.match(nodes.forecastIdentity.textContent,/完整推理参数尚未留存/);
    context.renderIdentity({modelName:'qwen3.5:9b'});
    assert.match(nodes.forecastIdentity.textContent,/旧记录未保存响应模型标识/);
    assert.doesNotMatch(nodes.forecastIdentity.textContent,/响应模型标识已核对/);
});

test('正式弃权与旧未知协议分开呈现', () => {
    const {nodes,context} = page();
    context.renderIdentity({signal:'ABSTAINED'});
    assert.equal(nodes.signalState?.textContent,'不出信号（弃权），不是中性');
    context.renderIdentity({});
    assert.equal(nodes.signalState.textContent,'旧协议未声明信号状态');
});

test('覆盖率、同日期基线与版本成绩不可互相借用',()=>{
    const {nodes,context}=page();
    assert.equal(typeof context.renderSignalStats,'function');
    context.renderSignalStats({signals:{sampleCount:2,declaredSignalCount:1,abstainedCount:1,
        legacyCount:0,coverage:.5,signalAccuracy:1,neutralBaselineAccuracy:0,fullSampleAccuracy:null,note:'不补方向'},
        versions:[{modelName:'model-a',promptVersion:'signal-v2',forecastRuleVersion:'rule-v1',accuracy:1,
            sampleCount:1,signals:{coverage:.5,abstainedCount:1,legacyCount:0,fullSampleAccuracy:null}}]});
    assert.match(nodes.signalStats.textContent,/50.0%/);
    assert.match(nodes.signalStats.textContent,/完整样本准确率 暂无/);
    assert.match(nodes.signalStats.textContent,/同信号日期中性基线 0.0%/);
    assert.match(nodes.versionStats.children[0].textContent,/model-a.*signal-v2.*50.0%/);
});
