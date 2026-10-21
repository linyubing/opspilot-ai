// 结算证据页面状态测试，不访问真实行情或模型。
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(request) {
    const script = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/forecast.js'), 'utf8');
    const nodes = {};
    const node = id => nodes[id] ||= {textContent:'',hidden:true,href:''};
    const context = vm.createContext({request, text:(id,value)=>{node(id).textContent=value;},byId:node,
        Date, encodeURIComponent, Error});
    const start = script.indexOf('async function loadChecks(');
    if(start>=0) vm.runInContext('let checksSeq=0;'+script.slice(start, script.indexOf('function renderMissReason(',start)),context);
    return {nodes,node,load:context.loadChecks || (async()=>{})};
}

test('没有回执不能冒充已核验，原始证据链接保持可读',async()=>{
    const p=page(async()=>[]);
    await p.load({id:'one'});
    assert.match(p.node('checkReason').textContent,/未留存.*不能/);
    assert.match(p.node('checkEvidence').textContent,/不补造/);
    assert.equal(p.node('checkLink').href,'/api/research/gold/forecasts/one/checks?limit=10');
});

test('来源失败不显示命中，显示检查时刻与两端核验范围',async()=>{
    const p=page(async()=>[{code:'TARGET_SESSION_FAILED',reason:'目标小时窗口不匹配',checkedAt:'2026-10-21T06:00:00Z',
        baseCheck:{status:'MATCHED',receivedRows:24,expectedHours:24,input:{timezoneBasis:'REQUEST_ONLY_UTC'}},
        targetCheck:{status:'OHLC_MISMATCH',receivedRows:24,expectedHours:24,input:{timezoneBasis:'RESPONSE_UTC'}}}]);
    await p.load({id:'two'});
    assert.match(p.node('checkReason').textContent,/目标小时窗口不匹配/);
    assert.match(p.node('checkEvidence').textContent,/基准.*一致.*目标.*不一致/);
    assert.match(p.node('checkEvidence').textContent,/请求UTC.*响应确认UTC/);
    assert.match(p.node('checkTime').textContent,/2026/);
});

test('迟到回执和读取失败不能污染新预测或冒充无证据',async()=>{
    let old;
    const p=page(url=>url.includes('/old/') ? new Promise(resolve=>{old=resolve;}) : Promise.reject(new Error('offline')));
    const pending=p.load({id:'old'});
    await p.load({id:'new'});
    old([{reason:'旧记录错误覆盖'}]);
    await pending;
    assert.match(p.node('checkReason').textContent,/读取失败/);
    assert.doesNotMatch(p.node('checkReason').textContent,/旧记录/);
    assert.equal(p.node('checkLink').hidden,true);
});
