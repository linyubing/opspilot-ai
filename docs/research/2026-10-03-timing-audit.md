# 黄金预测时点审计：先保证结算可信，再研究方向增益

## 范围与结论

审计基线：`3796850ed3a0b1fea1e09f3f3520475c794d0145`。
本轮没有调整标签、模型、晋级门槛、真实行情或最终留出集，也没有重新宣称预测准确率提升。

已复现生产结算边界缺口：`GoldForecastResolutionService` 不检查目标行情是否来自未来、采集时间是否晚于结算时间，也没有和实时快照一致的当天日线排除规则。
诊断证明的是代码可接受这些输入，不证明实际数据库已经发生过这些异常，也不证明这是历史开发集低准确率的原因。

上一轮匹配训练日期的 VIX 增量实验从 353/720 降至 349/720。因此暂停无界指标堆叠，转而确认“预测何时生成、哪些数据当时可得、何时才能结算”的信息合同。

## 数据链路事实

1. `TwelveDataGoldBarProvider` 请求 `XAU/USD`、`1day`，保存 `datetime` 为 `priceDate`；没有保存响应中的日线时区或已收盘状态。
2. `GoldDailyBarSyncService` 只排除周末，工作日记录会落库；仓储冲突更新覆盖 OHLC 和 `collectedAt`，没有历史修订版本。
3. 实时 `GoldResearchSnapshotService.createSnapshot()` 用纽约当前日期排除当天及之后日线。因此不能说实时快照完全没有未收盘保护。
4. `GoldForecastGenerationService` 用快照黄金日期/价格作为基准；新鲜度检查采用注入的 UTC Clock，黄金允许三自然日。没有独立的“目标交易时段是否已开始/已结束”检查。
5. `GoldForecastResolutionService` 读取 `findNext(baseDate)` 后直接调用 `createResolution()`；没有日期、采集时间或已收盘检查。仓储 `findNext` 只限制 `price_date > baseDate`。
6. `GoldDatasetBuilder` 历史样本入口读取全部日线，与实时快照不同；宏观输入来自冻结的 FRED 历史批次。研究中的旧日期实验是否受日线未完成问题影响，应单独检查，不能由实时结算缺口直接推导。

## 可复现诊断

`probes/TimingProbe.java` 使用 Java 动态代理隔离仓储，直接调用生产 `GoldForecastResolutionService`，不连接数据库、不调用大模型、不写行情或预测表。
100、101 等价格仅为数学夹具；不参与任何市场准确率统计。

冻结时钟：2026-09-02 12:00 UTC（纽约 08:00）；基准日期 2026-08-31。

| 输入 | 目标日期 | 采集时间 UTC | 当前生产行为 |
|---|---|---|---|
| 过去行情 | 2026-09-01 | 2026-09-02 01:00 | 已结算 |
| 当前日行情 | 2026-09-02 | 2026-09-02 11:00 | 已结算 |
| 未来行情 | 2026-09-03 | 2026-09-04 01:00 | 已结算 |
| 无目标行情 | 无 | 无 | 继续等待 |

诊断还检查生产返回的目标日期、1% 收益率及结算时刻。当前日案例只能证明没有等待保护，不能单靠日期证明供应商这一根蜡烛的真实收盘时间。
未来日期及未来采集时刻则是无需供应商时区假设即可确定的时间一致性问题。

运行命令（项目根目录 PowerShell）：

```powershell
$timingCp = 'backend/target/classes;backend/target/probe-classes;' + (Get-Content -Raw backend/target/probe-classpath.txt).Trim()
javac -encoding UTF-8 -cp $timingCp -d backend/target/probe-classes docs/research/probes/TimingProbe.java
java '-Dfile.encoding=UTF-8' -cp $timingCp TimingProbe
```

先在 backend 运行 `mvnw.cmd test` 重新编译生产源码，再运行诊断，避免把旧 class 当作证据。
2026-10-03 20:11:28 +08:00 全量结果：650 tests，0 failures，0 errors，3 skipped；其后诊断四项观测通过。
诊断不由 Maven 自动执行；它记录缺陷修复前的行为，不能用其 PASS 证明结算正确。修复后应更新诊断预期，并以拒绝异常时点的回归断言作为验收。

SHA-256：

| 文件 | 摘要 |
|---|---|
| GoldForecastResolutionService.java | 01619a5941d0e4375ad0e2e72950f6238d3955c813931df91fcdbfd11a97a5c7 |
| GoldResearchSnapshotService.java | f8e55ed860060d412b28afa4b33b2c42326d808eaccb9afc5d0b552d866ec6b7 |
| TimingProbe.java | 18e7627f9a6c83585462c6b7f47c8c82d03be8311971ce6aa927f22213ee5ba2 |

## 供应商时区：尚未闭环，不猜测收盘时间

2026-10-03 查阅 [Twelve Data 时区说明](https://support.twelvedata.com/en/articles/5745849-timezones)：Forex 默认 Australia/Sydney。
而 [API 文档](https://twelvedata.com/docs/markets/market-state) 的搜索索引包含 Forex UTC、日线忽略 timezone 的不同说明；此轮未完成对应正文及 XAU/USD 元数据核实，这段仅是待核实线索，不能用搜索摘要定义生产合同。
[历史数据说明](https://support.twelvedata.com/en/articles/5214728-getting-historical-data) 解释了 end_date 上界，但没有解决本项目 XAU/USD 的日线最终确认时刻。

因此不直接把纽约午夜、UTC 午夜或纽约 17:00 硬编码成已证明的供应商收盘时间。应核对实际响应元数据和来源说明；没有充分证据时不结算、不补造已收盘状态。

当前本地 8080 未发现监听进程，本轮没有取得正在运行页面或数据库状态；不把源码诊断写成线上异常发生报告。

## 后续迭代与验收边界

1. 先 TDD 修复无歧义的未来目标日期、未来采集时刻保护；异常行情不得写入已结算状态。保留可诊断信息，不篡改历史结算记录。
2. 统一实时快照与结算的已完成日线合同；供应商时区/收盘证据未闭环时保持等待。覆盖 UTC/纽约跨日、夏令时、周末及节假日场景。
3. 审计旧快照生成预测的目标是否已经结束；禁止晚生成的预测冒充真正事前预测，既有记录另行标注，不静默删除。
4. 同一可信口径下收集新预测及结算，再分析误判方向和信息缺口。时点修复只增加评测可信度；准确率是否提升仍需独立样本证据。
5. 若多轮真实事前预测仍不胜固定基线，继续评估信息与任务路线，不降低门槛、不将中性覆盖率包装成涨跌预测能力。

本轮完成根因调查和边界复现；生产修复、供应商日线合同、实时数据库样本核验仍未完成。
