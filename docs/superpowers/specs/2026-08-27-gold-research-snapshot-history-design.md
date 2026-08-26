# 黄金研究快照历史留痕设计

## 1. 目标

在现有黄金与实际利率确定性研究快照基础上，增加不可变的历史留痕能力。

本阶段解决三个问题：

1. 每次正式采用的研究结果都能追溯到分析日期、原始数据采集时间和规则版本；
2. 同一分析日期、同一规则版本重复执行不会产生重复记录；
3. 后续增加更多研究因子、生成简报和评测方向判断时，有稳定的历史输入作为依据。

本阶段不生成涨跌预测，不计算准确率，不调用大模型，也不自动修改研究规则。

## 2. 核心原则

### 2.1 正式快照不可修改

快照一旦写入便不能更新。行情修订、宏观数据修订或研究规则调整必须生成新版本，不能覆盖已经形成的历史判断。

这样可以避免使用后来才知道的数据改写过去，确保未来评测不存在前视偏差。

### 2.2 幂等键

正式快照使用以下组合键保证唯一：

```text
analysis_date + rule_version
```

- 第一次保存返回 `created`；
- 相同组合键再次保存时返回数据库中已有快照；
- 不更新已有字段，不生成重复记录；
- 规则变化必须使用新的 `rule_version`。

### 2.3 预览与留痕分离

现有接口：

```text
get /api/research/gold/snapshot
```

继续只生成当前预览，不产生数据库副作用。

新增正式留痕接口：

```text
post /api/research/gold/snapshots
```

该接口生成当前快照并尝试保存。首次保存返回 HTTP 201，命中已有幂等记录返回 HTTP 200。

新增历史查询接口：

```text
get /api/research/gold/snapshots?limit=20
```

按 `analysis_date`、`created_at` 倒序返回最近快照。`limit` 默认 20，合法范围为 1 到 100。

## 3. 数据模型

新增表 `gold_research_snapshot`：

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | `uuid` | 快照主键 |
| `analysis_date` | `date` | 黄金与实际利率共同可用的分析日期 |
| `latest_gold_date` | `date` | 生成时数据库中的最新黄金日期 |
| `latest_real_rate_date` | `date` | 生成时数据库中的最新实际利率日期 |
| `gold_price` | `numeric(19, 8)` | 分析日黄金参考价 |
| `gold_return_1` | `numeric(12, 4)` | 黄金 1 期收益率，单位为百分比 |
| `gold_return_5` | `numeric(12, 4)` | 黄金 5 期收益率，单位为百分比 |
| `gold_return_20` | `numeric(12, 4)` | 黄金 20 期收益率，单位为百分比 |
| `gold_collected_at` | `timestamptz` | 黄金数据采集时间 |
| `real_rate` | `numeric(18, 6)` | 实际利率当前值，单位为百分比 |
| `real_rate_change_1` | `numeric(18, 6)` | 1 期实际利率变化，单位为百分点 |
| `real_rate_change_5` | `numeric(18, 6)` | 5 期实际利率变化，单位为百分点 |
| `real_rate_change_20` | `numeric(18, 6)` | 20 期实际利率变化，单位为百分点 |
| `assessment_status` | `varchar(32)` | 单因子状态，数据库统一保存小写值 |
| `rule_version` | `varchar(64)` | 研究规则版本 |
| `explanation` | `varchar(500)` | 状态解释 |
| `disclaimer` | `varchar(500)` | 可信边界说明 |
| `created_at` | `timestamptz` | 正式留痕时间 |

基点变化不单独保存。它可以由百分点变化确定性换算得到，避免同一含义保存两份数据后发生不一致。

数据库约束：

- `gold_price > 0`；
- `assessment_status` 只能为 `pressuring`、`supportive`、`neutral`，与现有 Java 枚举一一对应；
- `unique (analysis_date, rule_version)`；
- 所有 SQL 关键字、表名和字段名统一小写。

## 4. Java 组件边界

### 4.1 `StoredGoldResearchSnapshot`

表示已经正式写入数据库的研究快照，包含 `id`、确定性研究结果和 `createdAt`。类注释必须说明它是历史事实记录，不是实时计算对象。

### 4.2 `GoldResearchSnapshotRepository`

定义两个能力：

- 按幂等键保存不存在的快照并返回保存结果；
- 按时间倒序查询最近快照。

接口不暴露 JDBC 或 SQL 细节。

### 4.3 `JdbcGoldResearchSnapshotRepository`

负责 Java 对象与 PostgreSQL 行之间的映射。使用 `insert ... on conflict do nothing` 保证并发请求下仍然幂等，再按组合键读取最终记录。

禁止使用“先查再插”作为唯一防重手段，因为两个并发请求可能同时查不到记录。

### 4.4 `SaveGoldResearchSnapshotResult`

返回最终数据库记录和 `created` 标志，让 Controller 能区分 HTTP 201 与 HTTP 200。类注释说明 `created=false` 表示命中已有幂等记录，不是保存失败。

### 4.5 `GoldResearchSnapshotRecordingService`

编排现有 `GoldResearchSnapshotService` 与新仓储：

1. 生成确定性快照；
2. 写入或读取已有记录；
3. 返回保存结果。

事务边界放在该服务，不把事务控制散落到 Controller。

### 4.6 API 层

Controller 只负责 HTTP 状态码、参数校验和 DTO 转换，不复制指标计算与幂等判断。

## 5. 数据流程

```text
post /api/research/gold/snapshots
        ↓
GoldResearchSnapshotRecordingService
        ↓
GoldResearchSnapshotService.createSnapshot()
        ↓
GoldResearchSnapshotRepository.saveIfAbsent(...)
        ↓
created=true  → HTTP 201
created=false → HTTP 200，并返回原有不可变快照
```

查询流程：

```text
get /api/research/gold/snapshots?limit=20
        ↓
参数校验
        ↓
GoldResearchSnapshotRepository.findRecent(limit)
        ↓
按分析日期倒序返回
```

## 6. 错误处理

- 当前黄金或实际利率数据不足：沿用 `INSUFFICIENT_RESEARCH_DATA`，HTTP 422；
- 原始研究数据非法：沿用 `INVALID_RESEARCH_DATA`，HTTP 422；
- `limit` 超出 1 到 100：返回 `INVALID_RESEARCH_REQUEST`，HTTP 400；
- 数据库异常：不伪装为数据不足，交由统一服务器错误处理并记录日志；
- 幂等冲突：属于正常结果，不返回 409。

## 7. 测试策略

### 7.1 数据库迁移测试

- 表、主键、唯一约束和检查约束存在；
- 状态值与黄金价格约束能够拒绝非法数据。

### 7.2 仓储集成测试

- 首次保存返回 `created=true`；
- 相同幂等键重复保存返回 `created=false`，且不覆盖第一次的数据；
- 最近记录按日期倒序返回；
- 并发安全由数据库唯一约束和 `on conflict do nothing` 保证，不编写依赖线程时序的脆弱测试。

### 7.3 服务单元测试

- 生成快照后正确交给仓储；
- 原有数据异常原样向上传递；
- 保存结果不被服务重新解释。

### 7.4 API 测试

- 新建返回 201；
- 重复请求返回 200；
- 历史查询默认值和合法 `limit` 正确；
- 非法 `limit` 返回 400 与稳定错误码。

## 8. 学习分工

为了让实现过程具有学习价值：

- Codex 负责数据库迁移、完整类型骨架、中文类注释、测试上下文与验收命令；
- 兵哥负责实现 `JdbcGoldResearchSnapshotRepository.saveIfAbsent` 的核心 JDBC 幂等逻辑；
- Codex 审查并发语义、事务边界、SQL 参数顺序和对象映射；
- 测试只验证真实业务行为，不再使用“类不存在所以编译失败”的无价值红灯。

## 9. 验收标准

- 正式快照能够写入 PostgreSQL；
- 相同分析日期和规则版本重复调用不会增加记录，也不会覆盖历史值；
- 能查询最近 1 到 100 条历史快照；
- 预览 GET 接口仍然无数据库副作用；
- 新增生产类型均有简洁中文类注释；
- 回归测试排除现有未完成的 `DocumentLifecycleServiceTests` 后全部通过；
- 本阶段不包含大模型调用、方向预测、准确率统计或自动调参。
