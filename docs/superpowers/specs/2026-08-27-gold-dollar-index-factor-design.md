# 黄金研究快照接入广义美元指数因子设计

## 1. 目标

在现有“黄金价格 + 实际利率”确定性研究快照中加入 FRED `DTWEXBGS` 广义美元指数，使接口和历史留痕能够分别解释实际利率与美元变化对黄金形成的单因子压力或支撑。

本阶段不生成黄金涨跌预测，不合成多空评分，不调用大模型，也不提供交易建议。

## 2. 数据定义与可信边界

- 美元数据固定使用 FRED `DTWEXBGS`。
- 名称统一为“广义美元指数”，不得标记为 ICE DXY。
- 单位保持 `index_2006_100`，表示 2006 年 1 月指数基准为 100。
- 只使用 `macro_observation` 中已版本化保存的真实有效观测。
- 黄金、实际利率和美元指数按三方共同观测日期对齐。
- 不插值，不使用前一工作日数据冒充某个缺失日期，也不生成测试行情或替代数据。
- 任意数据源缺失、字段非法、日期重复或共同日期不足时，拒绝生成部分研究快照。

## 3. 方案选择

采用“独立双因子评估”方案：

- 实际利率继续由现有规则独立评估。
- 广义美元指数由新增规则独立评估。
- 两个因子都使用 `PRESSURING`、`SUPPORTIVE`、`NEUTRAL` 三态语义。
- 当两个因子方向冲突时，原样返回两个独立结果，不强行合成整体方向。

现有 `RealRateFactorStatus` 重构为通用 `GoldFactorStatus`，`ResearchFactorAssessment` 改为持有通用状态。数据库中的小写状态值不变，因此历史数据无需转换。美元因子不会错误复用带有“实际利率”命名的类型。

暂不采用权重评分或大模型综合判断，因为当前没有回测证据支持固定权重，直接合成会制造虚假精确度。

## 4. 日期对齐与指标计算

每次查询最近 120 条黄金、实际利率和美元指数数据，并建立按日期索引。

三方共同日期按日期倒序排列，至少需要 21 个共同日期：

- 第 0 个共同日期是 `analysisDate`。
- 第 1、5、20 个共同日期分别作为 1、5、20 期基准。
- `latestGoldDate`、`latestRealRateDate` 和 `latestDollarIndexDate` 分别记录各数据源自己的最新日期，用来暴露数据滞后，不等同于 `analysisDate`。

美元指数变化使用相对百分比：

```text
change = (current / base - 1) * 100
```

API 统一保留 4 位小数。新增 `DollarIndexChangeMetrics` 保存：

- `currentIndex`
- `return1`
- `return5`
- `return20`
- `collectedAt`

## 5. 美元指数评估规则

新增 `DollarIndexFactorEvaluator`，初始阈值为 20 期绝对变化 `1.0000%`：

- `return20 >= 1.0000` 且 `return5 > 0`：美元持续走强，对黄金标记 `PRESSURING`。
- `return20 <= -1.0000` 且 `return5 < 0`：美元持续走弱，对黄金标记 `SUPPORTIVE`。
- 其他情况：`NEUTRAL`。

阈值是透明、可版本化的初始研究规则，不声明已经经过历史回测证明。规则版本固定为 `gold-dollar-index-v1`。

实际利率规则保持 `gold-real-rate-v1`，不因本阶段加入美元因子而修改其阈值。

## 6. 快照模型与版本

`GoldResearchSnapshot` 增加：

- `latestDollarIndexDate`
- `dollarIndex`
- `dollarIndexAssessment`
- `researchVersion`

现有 `assessment` 字段在 Java 和 HTTP 合同中明确改名为 `realRateAssessment`，避免加入第二个因子后含义模糊。

新快照的整体研究版本固定为 `gold-multifactor-v2`。历史幂等键改为：

```text
(analysis_date, research_version)
```

这允许同一分析日期同时保留旧的单因子版本和新的双因子版本。

## 7. 数据库兼容

新增 Flyway `V5` 迁移，不修改已执行的 `V4`：

1. 将 `rule_version` 重命名为 `research_version`。
2. 新增 `real_rate_rule_version`，旧记录用原 `research_version` 回填。
3. 新增可空的美元指数日期、指标、采集时间、状态、规则版本和解释字段。
4. 新记录必须写入完整美元字段；旧 `gold-real-rate-v1` 记录允许美元字段为空。
5. 唯一约束继续约束分析日期与重命名后的研究版本。
6. 状态约束允许 `pressuring`、`supportive`、`neutral`。

仓储读取旧记录时，将美元指标和评估映射为 `null`；读取新版本时必须完整还原双因子快照。任何字段只部分存在都视为数据损坏并拒绝读取。

## 8. API 合同

`GET /api/research/gold/snapshot`、`POST /api/research/gold/snapshots` 和 `GET /api/research/gold/snapshots` 的响应新增：

- `latestDollarIndexDate`
- `dollarIndex`
- `realRateAssessment`
- `dollarIndexAssessment`
- `researchVersion`

旧字段 `assessment` 被 `realRateAssessment` 替代。这是有意识的合同调整，用来消除多因子场景下的歧义。

旧历史记录返回：

- 实际利率评估正常存在。
- 美元指标和美元评估为 `null`。
- `researchVersion` 为原 `gold-real-rate-v1`。

新快照免责声明明确说明：两个确定性因子不构成黄金方向预测或投资建议。

## 9. 错误处理

- 美元指数没有可用数据：抛出 `InsufficientResearchDataException`。
- 三方共同日期少于 21 个：错误信息包含实际数量和最低要求。
- 美元指数值为空或不大于 0：抛出 `InvalidResearchDataException`。
- 任一数据源日期为空或重复：抛出 `InvalidResearchDataException`。
- 仓储检测到新版本快照美元字段不完整：抛出明确的持久化数据完整性异常，不返回半成品。

现有全局异常映射保持 422 语义。

## 10. 测试与验收

### 单元测试

- 美元指数 1、5、20 期变化计算。
- `+1%`、`-1%` 阈值边界。
- 中短期方向冲突返回 `NEUTRAL`。
- 三方共同日期对齐且不插值。
- 美元数据为空、日期重复、值非法和共同日期不足。
- 两个因子方向一致与冲突时都保持独立输出。

### 仓储与迁移测试

- `V5` 字段和约束存在。
- 新双因子快照首次保存成功、重复保存幂等。
- 双因子快照完整回读。
- `V4` 旧记录仍可读取，美元字段为空。

### HTTP 测试

- 当前快照返回双因子指标、两个独立评估和研究版本。
- 历史接口同时兼容旧单因子记录与新双因子记录。
- 响应不包含数据库内部字段。

### 真实端到端验收

1. 完整测试保持 0 failure、0 error。
2. 使用数据库中的真实 `XAUUSD`、`DFII10`、`DTWEXBGS` 创建快照。
3. 核对三方共同 `analysisDate`、各自最新日期和指标计算。
4. 连续记录两次，确认第二次命中同一研究版本快照。
5. 检查提交差异中不包含 API Key、密码或无关工作区文件。

## 11. 学习分工

- Codex 负责迁移 SQL、JDBC 映射、DTO、Controller 和普通编排代码。
- 兵哥负责实现 `DollarIndexFactorEvaluator` 的阈值与方向判断，并解释为什么美元走强对应黄金压力。
- Codex 提供完整可编译骨架、有效红灯测试、中文类注释、代码审查和回归验证。
