# 黄金与实际利率确定性研究快照设计

## 1. 背景与目标

OpsPilot AI 已经具备两条经过真实数据验收的基础链路：

- Alpha Vantage `XAUUSD` 黄金日参考价同步、保存和查询；
- FRED `DFII10` 美国 10 年期实际利率同步、修订版本保存和查询。

本阶段在两条真实数据链路之上建立第一版确定性研究分析。系统在 Java 中完成日期对齐和数值计算，输出可复现、可审计的结构化研究快照，为后续中文简报和大模型解释提供可靠输入。

本阶段不让大模型计算数字，不生成买入、卖出、目标价、收益承诺或方向预测。

## 2. 范围

### 2.1 包含范围

- 对齐黄金价格与实际利率的共同观测日期；
- 计算黄金 1、5、20 个有效观测间隔涨跌幅；
- 计算实际利率 1、5、20 个有效观测间隔的百分点和基点变化；
- 根据透明规则生成实际利率单因子状态；
- 返回分析日期、原始值、指标、数据时间、规则版本和免责声明；
- 提供结构化 JSON 查询接口。

### 2.2 不包含范围

- 不调用 ChatClient 或其他大模型生成结论；
- 不计算美元、通胀、央行购金、新闻情绪或技术指标；
- 不生成完整黄金研究简报；
- 不做回测、预测评分或自动调参；
- 不实现交易、下单、持仓、通知或定时任务。

## 3. 可信性原则

1. 所有数字只由确定性 Java 代码计算。
2. 只使用两个数据源日期完全相同的观测，不插值、不前向填充。
3. 固定测试数据只验证算法合同，不代表真实行情。
4. 单因子状态必须同时返回原始变化值和规则版本，不能脱离数据单独展示。
5. 实际利率与黄金之间只表达研究机制上的压力或支撑因素，不宣称因果关系。
6. 数据不足时明确失败，不使用默认值、零值或模型常识补齐。
7. 所有计算使用 `BigDecimal`，禁止使用 `double` 参与正式计算。

## 4. 日期对齐口径

### 4.1 共同日期

分别读取最近黄金价格和当前版本的实际利率观测，以观测日期为键取交集。交集按日期降序排列，最新共同日期为 `analysisDate`。

只要某个日期缺少任一数据源的观测，该日期就不进入分析序列。周末和节假日不按自然日补值，也不因缺少记录自动判断数据异常。

### 4.2 有效观测间隔

“1、5、20 期”表示共同日期序列中的有效观测间隔，不表示自然日：

- 1 期使用索引 `0` 与索引 `1`；
- 5 期使用索引 `0` 与索引 `5`；
- 20 期使用索引 `0` 与索引 `20`。

因此，计算完整快照至少需要 21 个共同日期。

### 4.3 数据时间

响应必须包含：

- `analysisDate`：最新共同观测日期；
- `latestGoldDate`：黄金仓储中的最新日期；
- `latestRealRateDate`：实际利率仓储中的最新日期；
- 两条参与最新共同观测的数据各自的 `collectedAt`。

第一版不根据自然日设置硬性过期阈值，避免周末和节假日误报。调用者通过上述日期判断数据新鲜度。

## 5. 计算口径

### 5.1 黄金涨跌幅

黄金第 `n` 期涨跌幅：

```text
(currentPrice / basePrice - 1) * 100
```

输入价格必须大于零。计算使用较高内部精度，API 百分比统一保留 4 位小数并采用 `HALF_UP`。

### 5.2 实际利率变化

实际利率第 `n` 期百分点变化：

```text
currentRate - baseRate
```

基点变化：

```text
percentagePointChange * 100
```

例如实际利率从 `2.20%` 上升到 `2.38%`：

- 百分点变化为 `+0.18`；
- 基点变化为 `+18`；
- 不表达为上涨 `8.18%`。

百分点变化保留 6 位小数；基点变化保留 2 位小数，均采用 `HALF_UP`。

## 6. 单因子状态规则

第一版规则版本固定为 `gold-real-rate-v1`。

状态枚举：

- `PRESSURING`：压力因素；
- `SUPPORTIVE`：支撑因素；
- `NEUTRAL`：中性或方向不一致。

规则：

- 20 期实际利率变化大于等于 `+10` 个基点，并且 5 期变化大于 `0`，返回 `PRESSURING`；
- 20 期实际利率变化小于等于 `-10` 个基点，并且 5 期变化小于 `0`，返回 `SUPPORTIVE`；
- 其他情况返回 `NEUTRAL`。

10 个基点是透明、可审计但尚未经过统计验证的初始阈值。响应必须携带以下声明：

```text
实际利率状态仅代表单一研究因素，不构成黄金方向预测或投资建议。
```

## 7. 模块与职责

新增 `com.opspilot.ai.analysis` 模块：

- `GoldResearchSnapshot`：完整研究快照领域结果；
- `GoldReturnMetrics`：黄金 1、5、20 期涨跌幅；
- `RealRateChangeMetrics`：实际利率 1、5、20 期百分点及基点变化；
- `ResearchFactorAssessment`：单因子状态、规则版本和说明；
- `RealRateFactorStatus`：`PRESSURING/SUPPORTIVE/NEUTRAL` 枚举；
- `GoldResearchSnapshotService`：查询、对齐、校验、计算与组装；
- `InsufficientResearchDataException`：数据不足异常；
- `InvalidResearchDataException`：数据完整性异常。

新增 `com.opspilot.ai.analysis.api`：

- `GoldResearchController`：HTTP 边界，不执行计算；
- `GoldResearchSnapshotResponse`：对外 JSON DTO。

现有 `marketdata` 和 `macrodata` 模块保持独立，不反向依赖 `analysis`。

## 8. 数据流

```text
MarketPriceRepository.findRecent(XAUUSD, 查询窗口)
                         ┐
                         ├─ 按日期取交集并降序排列
                         │            ↓
MacroObservationRepository.findRecent(DFII10, 查询窗口)
                                      ↓
                             检查至少 21 个共同日期
                                      ↓
                           计算黄金与实际利率指标
                                      ↓
                             生成单因子状态和快照
                                      ↓
                         get /api/research/gold/snapshot
```

第一版查询窗口固定为最近 120 条，以容纳双方节假日差异。若仍不足 21 个共同日期，则明确返回数据不足，不扩大为无界查询。

## 9. HTTP 合同

### 9.1 查询快照

```http
get /api/research/gold/snapshot
```

成功返回 `200`。为避免用虚构数字冒充行情，规格只定义字段合同：

- 顶层：`analysisDate/latestGoldDate/latestRealRateDate/disclaimer`；
- `gold`：`symbol/currentPrice/return1/return5/return20/collectedAt`；
- `realRate`：`seriesId/currentRate/percentagePointChange1/5/20/basisPointChange1/5/20/collectedAt`；
- `assessment`：`status/ruleVersion/explanation`。

正式验收响应中的所有数值必须来自本地已同步的真实数据，并能由对应原始记录复算。

## 10. 错误处理

- 任一仓储没有数据：返回 `422 Unprocessable Entity`；
- 共同日期少于 21 个：返回 `422`，错误码 `INSUFFICIENT_RESEARCH_DATA`；
- 黄金价格不大于零或必要字段非法：返回 `422`，错误码 `INVALID_RESEARCH_DATA`；
- 异常信息可以包含实际共同日期数量，但不能包含外部 API Key、完整外部响应或敏感配置；
- `GlobalExceptionHandler` 只精确捕获上述研究异常，不捕获所有 `IllegalArgumentException`。

## 11. 测试设计

### 11.1 服务测试

- 输入顺序混乱时按日期正确排序；
- 日期不同的数据不参与配对；
- 21 个共同日期正确选择 1、5、20 期基准；
- 黄金涨跌幅按公式和舍入规则计算；
- 实际利率百分点和基点变化计算正确；
- `+10` 和 `-10` 个基点边界分类正确；
- 20 期与 5 期方向冲突时返回 `NEUTRAL`；
- 共同日期不足 21 个时抛出数据不足异常；
- 黄金价格不大于零时抛出数据完整性异常；
- 领域服务不依赖 ChatClient 或其他大模型组件。

### 11.2 HTTP 测试

- 成功响应字段、状态、规则版本和免责声明完整；
- 内部对象或数据库字段不暴露；
- 数据不足返回 `422/INSUFFICIENT_RESEARCH_DATA`；
- 非法研究数据返回 `422/INVALID_RESEARCH_DATA`；
- 普通 `IllegalArgumentException` 不被误映射成研究错误。

### 11.3 回归测试

- 黄金同步和查询行为不变；
- FRED 同步、修订版本和查询行为不变；
- Spring 上下文中没有新增的同类型 Bean 歧义；
- 已知未完成的文档生命周期文件不纳入本阶段提交。

## 12. 验收标准

- 使用真实数据库中的黄金和 `DFII10` 数据能够生成研究快照；
- `analysisDate` 确实是双方最新共同日期；
- 所有数值可以通过原始记录独立复算；
- 重复请求在底层数据不变时返回相同业务结果；
- 数据不足或非法时不生成部分结论；
- 结果不包含预测、交易指令或收益承诺；
- 测试、差异检查和敏感信息扫描全部通过。

## 13. 后续阶段

完成本阶段后，再独立设计中文黄金研究简报。简报可以让大模型解释本快照，但必须引用结构化字段，并且不得重新计算或修改确定性指标。
