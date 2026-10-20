// 仅验证页面呈现合同，测试夹具不写入行情或实验数据库。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/model-lab.js'), 'utf8');

function page() {
    const nodes = new Map();
    const get = selector => {
        if (!nodes.has(selector)) nodes.set(selector, {
            textContent: '', innerHTML: '', hidden: false,
            addEventListener() {}, querySelectorAll() { return []; }, classList: { toggle() {} }
        });
        return nodes.get(selector);
    };
    const context = vm.createContext({
        document: { querySelector: get, querySelectorAll: () => [] },
        fetch: async () => ({ ok: false, json: async () => ({ message: 'UI测试不运行模型' }) }),
        alert() {}, console, Date
    });
    vm.runInContext(source, context);
    return { nodes, context };
}

test('完整准确率、信号准确率及同日期基线明确分开', () => {
    const { nodes, context } = page();
    context.metric = {
        sampleCount: 10, coveredCount: 4, accuracy: .75, balancedAccuracy: .6,
        coverage: .4, brierScore: .5, logLoss: 1.1, recalls: {},
        paired: { all: { sampleCount: 10, accuracy: .5, balancedAccuracy: .45, recalls: {} },
            baselineOnSignals: { sampleCount: 4, accuracy: .25 }, signalDates: [] }
    };
    vm.runInContext('fillMetrics("logistic", metric)', context);
    assert.equal(nodes.get('#logisticAccuracy').textContent, '75.00%');
    assert.equal(nodes.get('#logisticFullAccuracy')?.textContent, '50.00%');
    assert.equal(nodes.get('#logisticFullBalanced')?.textContent, '45.00%');
    assert.equal(nodes.get('#logisticBaseline')?.textContent, '25.00%');
    assert.match(nodes.get('#logisticPopulation')?.textContent || '', /4.*10/);
});

test('旧记录不借用信号准确率冒充完整成绩且清空上一条结果', () => {
    const { nodes, context } = page();
    context.metric = { sampleCount: 10, coveredCount: 4, accuracy: .75,
        balancedAccuracy: .6, coverage: .4, brierScore: .5, recalls: {}, logLoss: 1 };
    vm.runInContext('fillMetrics("logistic", metric)', context);
    assert.equal(nodes.get('#logisticFullAccuracy')?.textContent, '缺少配对证据');
    assert.equal(nodes.get('#logisticBaseline')?.textContent, '缺少配对证据');
    vm.runInContext('fillMetrics("logistic", null)', context);
    assert.equal(nodes.get('#logisticAccuracy').textContent, '无法计算');
    assert.equal(nodes.get('#logisticLogLoss').textContent, '无法计算');
});

test('零信号显示无信号而不是0%胜率或虚构基线', () => {
    const { nodes, context } = page();
    context.metric = { sampleCount: 10, coveredCount: 0, accuracy: 0, coverage: 0, recalls: {},
        paired: { all: { sampleCount: 10, accuracy: .4, balancedAccuracy: .3, recalls: {} },
            baselineOnSignals: null, signalDates: [] } };
    vm.runInContext('fillMetrics("xgboost", metric)', context);
    assert.equal(nodes.get('#xgboostAccuracy').textContent, '无信号');
    assert.equal(nodes.get('#xgboostBaseline')?.textContent, '无信号');
    assert.equal(nodes.get('#xgboostFullAccuracy')?.textContent, '40.00%');
});

test('历史列表零信号不能显示为0%准确率', async () => {
    const { nodes, context } = page();
    context.fetch = async () => ({ ok: true, json: async () => [{ id: 'old', coverage: 0,
        logisticAccuracy: 0, featureProfile: 'BASE_16', horizon: 'NEXT_DAY' }] });
    await vm.runInContext('loadHistory()', context);
    assert.match(nodes.get('#experimentList').innerHTML, /逻辑回归信号准确率 无信号/);
});

test('历史切换同步周期和特征且较早预览不能覆盖', async () => {
    const { nodes, context } = page();
    let release;
    context.fetch = url => url.endsWith('/history-case')
        ? Promise.resolve({ ok: true, json: async () => ({ id: 'history-case', gitCommit: 'abc1234',
            horizon: 'NEXT_DAY', featureProfile: 'BASE_16', parameters: {}, status: 'COMPLETED' }) })
        : new Promise(resolve => { release = resolve; });
    const earlier = vm.runInContext('load("FIVE_DAYS", "ALL_36")', context);
    await vm.runInContext('showDetail("history-case")', context);
    assert.equal(vm.runInContext('currentHorizon', context), 'NEXT_DAY');
    assert.equal(vm.runInContext('currentProfile', context), 'BASE_16');
    assert.match(nodes.get('#metricSource').textContent, /1 个交易日/);
    release({ ok: true, json: async () => ({ featureProfile: 'ALL_36', finalHoldout: {} }) });
    await earlier;
    assert.match(nodes.get('#metricSource').textContent, /history-case/);
    assert.equal(nodes.get('#candidateResult').hidden, true);
});

test('迟到的对比不能覆盖历史成绩或重新显示候选', async () => {
    const { nodes, context } = page();
    let release;
    context.fetch = url => url.includes('/compare?')
        ? new Promise(resolve => { release = resolve; })
        : Promise.resolve({ ok: true, json: async () => ({ id: 'chosen', gitCommit: 'abc1234',
            horizon: 'NEXT_DAY', featureProfile: 'BASE_16', parameters: {} }) });
    const earlier = vm.runInContext('runComparison()', context);
    await vm.runInContext('showDetail("chosen")', context);
    release({ ok: true, json: async () => ({ experiments: [], stage8Candidate: true }) });
    await earlier;
    assert.match(nodes.get('#metricSource').textContent, /chosen/);
    assert.equal(nodes.get('#candidateResult').hidden, true);
});
