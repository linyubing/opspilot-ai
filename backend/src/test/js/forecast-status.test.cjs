// 执行真实页面函数，检查 API 字段能映射到页面节点；不请求行情或调用模型。
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('宏观数据状态显示到正确节点，美元过期不能被总体新鲜遮住', () => {
    const dir = path.resolve(__dirname, '../../main/resources/static');
    const html = fs.readFileSync(path.join(dir, 'forecast.html'), 'utf8');
    const nodes = Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)]
        .map(match => [match[1], {textContent: '', className: ''}]));
    const script = fs.readFileSync(path.join(dir, 'forecast.js'), 'utf8');
    const start = script.indexOf('const dataStates =');
    const end = script.indexOf('async function loadDataStatus()', start);
    const context = {byId: id => nodes[id], text: (id, value) => {nodes[id].textContent = value;}};
    vm.createContext(context);
    vm.runInContext(script.slice(start, end), context);
    context.renderDataStatus({overall: 'FRESH', items: [
        {code: 'gold', state: 'FRESH', detail: '黄金 10/9'},
        {code: 'realRate', state: 'FRESH', detail: '实际利率 10/8'},
        {code: 'dollarIndex', state: 'STALE', detail: '广义美元 10/2，非 DXY'}
    ]});
    assert.equal(nodes.rateStatus.textContent, '新鲜');
    assert.equal(nodes.rateStatusDetail.textContent, '实际利率 10/8');
    assert.equal(nodes.dollarStatus.textContent, '过期');
    assert.equal(nodes.dollarStatusDetail.textContent, '广义美元 10/2，非 DXY');
    assert.match(nodes.dollarStatus.className, /stale/);
    assert.equal(nodes.dataStatusOverall.textContent, '核心数据新鲜，美元仍过期');
    context.renderDataStatus({overall: 'FRESH', items: [{code: 'dollarIndex', state: 'FRESH'}]});
    assert.equal(nodes.dataStatusOverall.textContent, '新鲜');
});
