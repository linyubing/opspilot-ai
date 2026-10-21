// 只在内存中验证真实页面加载逻辑，不写行情，不调用模型。
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/data-status.js'), 'utf8');

function page(failRate = false) {
    const nodes = new Map();
    const node = id => {
        if (!nodes.has(id)) nodes.set(id, {textContent: '', children: [], hidden: false,
            append(...items) { this.children.push(...items); },
            replaceChildren(...items) { this.children = items; }, addEventListener() {}});
        return nodes.get(id);
    };
    const context = vm.createContext({Date, console,
        document: {getElementById: node, createElement: () => ({textContent: '', children: [],
            append(...items) {this.children.push(...items);}})},
        fetch: async url => {
            if (url.endsWith('/data-status')) return {ok: true, json: async () => ({
                overall: 'STALE', analysisDate: '2026-10-09', generatedAt: '2026-10-21T05:00:00Z',
                items: [{code: 'gold', observationDate: '2026-10-09', state: 'STALE'}]})};
            if (url.includes('real-rate') && failRate) return {ok: false, status: 503};
            const gold = url.includes('daily-bars');
            return {ok: true, json: async () => gold ? {
                priceDate: '2026-10-20', close: 4120.79556, currency: 'usd', unit: 'troy_ounce',
                provider: 'twelve_data', collectedAt: '2026-10-21T05:18:08Z'
            } : {seriesId: url.includes('real-rate') ? 'DFII10' : 'DTWEXBGS',
                observationDate: '2026-10-16', value: 2, unit: 'percent', provider: 'fred',
                collectedAt: '2026-10-21T04:00:00Z'}};
        }});
    vm.runInContext(source, context);
    return {nodes, context};
}

test('仓库新日期不覆盖旧快照，来源和非实时口径可见', async () => {
    const {nodes} = page();
    assert.equal(nodes.get('refreshStatus').disabled, true);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(nodes.get('refreshStatus').disabled, false);
    const warehouse = nodes.get('warehouseItems');
    assert.ok(warehouse?.children.length === 3, '必须独立展示三项仓库数据');
    const rendered = JSON.stringify(warehouse);
    assert.match(rendered, /2026-10-20/);
    assert.match(rendered, /twelve_data/);
    assert.match(rendered, /4120.79556/);
    assert.match(rendered, /非实时/);
    assert.match(rendered, /不是DXY/);
    assert.equal(nodes.get('analysisDate').textContent, '2026-10-09');
    assert.equal(nodes.get('overall').textContent, '过期');
});

test('单个来源失败时仍显示其他仓库数据和原快照', async () => {
    const {nodes} = page(true);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(nodes.get('refreshStatus').disabled, false);
    const rendered = JSON.stringify(nodes.get('warehouseItems'));
    assert.match(rendered, /2026-10-20/);
    assert.match(rendered, /503/);
    assert.match(rendered, /实际利率/);
    assert.equal(nodes.get('analysisDate').textContent, '2026-10-09');
});
