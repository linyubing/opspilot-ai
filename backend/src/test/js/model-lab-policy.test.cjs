// 验证真实页面脚本的列表和详情输出；接口夹具只用于 UI 测试，不是行情实验。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/model-lab.js'), 'utf8');

function page(policy) {
    const nodes = new Map();
    const get = selector => {
        if (!nodes.has(selector)) nodes.set(selector, {
            textContent: '', innerHTML: '', hidden: false,
            addEventListener() {}, querySelectorAll() { return []; }, classList: { toggle() {} }
        });
        return nodes.get(selector);
    };
    const data = { id: 'ui-fixture', status: 'COMPLETED', featureProfile: 'BASE_16', dataPolicy: policy };
    const context = vm.createContext({
        document: { querySelector: get, querySelectorAll: () => [] },
        fetch: async url => ({ ok: url.includes('/history') || url.endsWith('/ui-fixture'),
            json: async () => url.includes('/history') ? [data] : url.endsWith('/ui-fixture') ? data : { message: 'UI 测试不运行模型' } }),
        alert: message => { throw Error(message); }, console, Date
    });
    vm.runInContext(source, context);
    return { nodes, context };
}

for (const [policy, label] of [
    ['legacy-latest-version', '旧口径：未证明当时可得'],
    ['fred-known-before-day-v1', '历史版本：前一日已知'],
    [undefined, '口径未知：不可直接比较'],
    ['future-unrecognized', '口径未知：不可直接比较']
]) {
    test(`列表和详情显示正确来源状态：${policy}`, async () => {
        const { nodes, context } = page(policy);
        await vm.runInContext('loadHistory()', context);
        await vm.runInContext('showDetail("ui-fixture")', context);
        assert.ok(nodes.get('#experimentList').innerHTML.includes(label));
        assert.equal(nodes.get('#detailDataPolicy')?.textContent, label);
    });
}
