import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';

// 只重放本地归档；不加载网络或数据库客户端。
export function checkRun(run, protocol, protocolHash) {
    assert.equal(run.status, 'COMPLETED', '探针未完成，不能补造成功');
    assert.equal(run.protocolSha256, protocolHash, '固定协议摘要不符');
    for (const [file, hash] of Object.entries(protocol.configSha256)) {
        assert.equal(run.configSha256[file], hash, '配置摘要不符');
    }
    assert.equal(run.request.model, protocol.model, '请求模型不符');
    assert.equal(run.reportedModel, protocol.model, '响应模型不符');
    assert.equal(run.request.think, false, '未关闭额外思考');
    if ('think' in run.request.options) assert.equal(run.request.options.think, false, '思考开关存在冲突');
    assert.equal(run.request.stream, false, '探针必须为单次非流式请求');
    assert.equal(run.request.options.num_ctx, 8192, '上下文限制变化');
    assert.equal(run.request.options.num_predict, 2048, '输出限制变化');
    assert.equal(run.request.options.temperature, 0, '温度变化');
    assert.equal(run.response.signal, 'ABSTAINED', '无行情探针不得输出方向');
    assert.equal(run.response.direction, null, '弃权必须没有方向');
    assert.match(protocol.digest, /^[0-9a-f]{64}$/);
    for (const key of ['beforeModel', 'afterModel', 'loadedModel']) {
        assert.equal(run[key].name, protocol.model, '观察到的模型名不符');
        assert.equal(run[key].digest, protocol.digest, '模型摘要变化');
    }
    assert.equal(run.beforeVersion.version, run.afterVersion.version, '服务版本变化');
    assert.ok(run.beforeVersion.version, '缺少服务版本');
    const times = ['startedAt','beforeCheckedAt','modelCallStartedAt','modelCallEndedAt','afterCheckedAt']
        .map(key => Date.parse(run[key]));
    assert.ok(times.every(Number.isFinite), '缺少有效观察时刻');
    assert.ok(times.every((time, i) => i === 0 || time >= times[i - 1]), '观察顺序错误');
    return {matched: true, model: protocol.model, digest: protocol.digest,
        scope: '本次探针前后摘要一致；不认证调用中工件，不回填旧预测，不证明准确率'};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [protocolFile, archiveFile, archiveHash] = process.argv.slice(2);
    const bytes = readFileSync(archiveFile);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), archiveHash, '归档摘要不符');
    const protocolBytes = readFileSync(protocolFile);
    const result = checkRun(JSON.parse(bytes), JSON.parse(protocolBytes),
        createHash('sha256').update(protocolBytes).digest('hex'));
    console.log(JSON.stringify(result));
}
