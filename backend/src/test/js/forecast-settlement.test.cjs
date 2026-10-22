// 执行真实页面入口；只替代浏览器节点和检查接口，不请求行情、不调用模型。
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page() {
    const dir = path.resolve(__dirname, '../../main/resources/static');
    const html = fs.readFileSync(path.join(dir, 'forecast.html'), 'utf8');
    const nodes = Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)]
        .map(m => [m[1], {textContent: '', hidden: true, children: [],
            replaceChildren(...rows) { this.children = rows; },
            appendChild(row) { this.children.push(row); }}]));
    const script = fs.readFileSync(path.join(dir, 'forecast.js'), 'utf8');
    const context = vm.createContext({document: {getElementById: id => nodes[id],
        createElement: () => ({textContent: ''})},
        fetch: async () => ({ok: true, json: async () => []})});
    // 不执行页面自动加载；renderForecast 本身必须连通新的结算展示。
    vm.runInContext(script.slice(0, script.indexOf('generateButton.addEventListener')), context);
    return {show: context.renderForecast, value: id => nodes[id]?.textContent};
}

function resolved(changes = {}) {
    return {id: 'resolved', status: 'RESOLVED', signal: 'ABSTAINED',
        baseDate: '2026-10-20', basePrice: 4120.79556000,
        targetDate: '2026-10-21', targetPrice: 4134.54213000,
        actualReturn: 0.333590, actualDirection: 'NEUTRAL', hit: null,
        forecastRuleVersion: 'gold-next-session-direction-v1', ...changes};
}

test('已结算弃权展示真实涨幅与中性分类，不能乘百分数两次或记作命中', () => {
    const p = page();
    p.show(resolved());
    assert.equal(p.value('actualDate'), '2026-10-21');
    assert.equal(p.value('actualPrice'), '$4134.54');
    assert.equal(p.value('actualReturn'), '+0.3336%');
    assert.equal(p.value('actualDirection'), '中性');
    assert.equal(p.value('hitResult'), '弃权，不计命中');
    assert.match(p.value('settlementNote'), /实际价格上涨/);
    assert.match(p.value('settlementNote'), /\[-0\.5%, 0\.5%\]/);
});

test('切换未结算或作废记录必须清除上一条真实结果，不拿最新行情补结算', () => {
    const p = page();
    for (const status of ['PENDING', 'DATA_MISSING', 'VOIDED']) {
        p.show(resolved());
        p.show(resolved({status}));
        assert.equal(p.value('actualDate'), '尚无有效结算');
        assert.equal(p.value('actualPrice'), '暂无');
        assert.equal(p.value('actualReturn'), '暂无');
        assert.equal(p.value('actualDirection'), '暂无');
        assert.equal(p.value('hitResult'), '不判定');
        assert.doesNotMatch(p.value('settlementNote'), /实际价格上涨/);
    }
});

test('有信号时按原命中字段展示，缺失不能解释为错误，旧协议不能冒充新协议', () => {
    const p = page();
    p.show(resolved({signal: 'PREDICTED', hit: true}));
    assert.equal(p.value('hitResult'), '命中');
    p.show(resolved({signal: 'PREDICTED', hit: false}));
    assert.equal(p.value('hitResult'), '未命中');
    p.show(resolved({signal: 'PREDICTED', hit: null}));
    assert.equal(p.value('hitResult'), '命中结果未留存');
    p.show(resolved({signal: null, hit: false, forecastRuleVersion: null}));
    assert.match(p.value('hitResult'), /旧协议.*未命中.*资格未知/);
    assert.doesNotMatch(p.value('settlementNote'), /\[-0\.5%, 0\.5%\]/);
});

test('结算字段残缺或非有限时不显示零收益或沿用上一条结果', () => {
    const p = page();
    for (const bad of [{targetPrice: null}, {actualReturn: null}, {actualReturn: NaN},
        {actualReturn: Infinity}, {targetPrice: 0}, {targetDate: null}, {actualDirection: null}]) {
        p.show(resolved());
        p.show(resolved(bad));
        assert.equal(p.value('actualPrice'), '暂无');
        assert.equal(p.value('actualReturn'), '暂无');
        assert.match(p.value('settlementNote'), /字段不完整/);
    }
});

test('跌幅和零收益按原百分数展示，不把中性当作持平', () => {
    const p = page();
    p.show(resolved({actualReturn: -0.5}));
    assert.equal(p.value('actualReturn'), '-0.5000%');
    assert.match(p.value('settlementNote'), /实际价格下跌/);
    p.show(resolved({actualReturn: 0}));
    assert.equal(p.value('actualReturn'), '0.0000%');
    assert.match(p.value('settlementNote'), /实际价格持平/);
});
