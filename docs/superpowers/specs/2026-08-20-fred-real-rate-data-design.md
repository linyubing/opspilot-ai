# FRED 实际利率数据接入设计

## 1. 背景与目标

黄金研究助手已经具备真实黄金日线价格的同步、保存和查询能力。下一阶段接入真实宏观数据，第一条序列选择 FRED 的 `DFII10`：美国 10 年期通胀指数国债实际利率。

本阶段目标：

- 从 FRED 官方 API 获取 `DFII10` 日度观测值；
- 将观测值及其后续修订保存到 PostgreSQL；
- 支持查询最新数据和有限条历史数据；
- 为未来“按研究时间还原当时可见数据”提供仓储接口；
- 保持外部数据源、业务协调、持久化和 HTTP 接口职责分离。

本阶段不计算均线、变化方向、黄金相关性，不生成简报，也不做任何预测或交易信号。

## 2. 数据来源与口径

- 数据源：Federal Reserve Economic Data（FRED）官方 API V1。
- 序列：`DFII10`。
- 频率：日度。
- 单位：百分比，系统保存为 `percent`。
- 提供方标识：`fred`。
- FRED 使用字符串 `.` 表示该日期没有观测值。系统必须跳过并计数，不得转换为零。
- API Key 通过环境变量 `FRED_API_KEY` 注入，不进入代码、配置文件、日志或 Git 历史。

官方参考：

- 序列说明：<https://fred.stlouisfed.org/series/DFII10>
- 观测值接口：<https://fred.stlouisfed.org/docs/api/fred/series_observations.html>
- API Key 说明：<https://fred.stlouisfed.org/docs/api/api_key.html>

## 3. 总体架构

数据流如下：

`FRED API -> FredRealRateProvider -> RealRateSyncService -> MacroObservationRepository -> PostgreSQL`

### 3.1 `FredRealRateProvider`

只负责外部接口适配：

- 构造 FRED 请求；
- 读取并校验 JSON；
- 将合法观测值转换为领域对象；
- 将 `.` 识别为缺失值；
- 将网络、限流和 FRED 错误转换为统一的宏观数据不可用异常。

它不写数据库，不判断数据是否为修订值，也不暴露 API Key。

### 3.2 `RealRateSyncService`

负责一次同步的业务协调：

- 调用 Provider 获取数据；
- 统计收到、缺失、新增、修订和未变化数量；
- 调用 Repository 保存合法观测值；
- 保证一次修订操作在事务中完成；
- 输出可用于 API 和日志的同步结果。

### 3.3 `MacroObservationRepository`

负责宏观观测值持久化：

- 保存首次出现的观测值；
- 相同数值不重复保存；
- 数值变化时关闭当前版本并插入新版本；
- 查询当前最新版本；
- 查询指定研究时间当时可见的版本。

### 3.4 HTTP 接口

Controller 只处理参数边界、响应转换和 HTTP 状态，不包含 FRED 或 SQL 逻辑。

## 4. 领域模型

使用通用领域类型 `MacroObservation`，避免后续接入 CPI、美元指数等序列时重复建模。

核心字段：

- `id`：记录版本标识；
- `seriesId`：FRED 序列代码，本阶段固定为 `DFII10`；
- `observationDate`：观测日期；
- `value`：观测数值；
- `unit`：单位，本阶段为 `percent`；
- `provider`：数据提供方，本阶段为 `fred`；
- `collectedAt`：该版本被系统采集的时间；
- `supersededAt`：该版本被新版本替代的时间，当前版本为空。

金额和利率数值使用 `BigDecimal`，禁止使用浮点数保存正式数据。

## 5. 数据库设计

新增 Flyway 迁移，SQL 关键字统一小写：

```sql
create table macro_observation (
    id uuid primary key,
    series_id varchar(64) not null,
    observation_date date not null,
    observation_value numeric(18, 6) not null,
    unit varchar(32) not null,
    provider varchar(32) not null,
    collected_at timestamptz not null,
    superseded_at timestamptz null,
    constraint ck_macro_observation_version_time
        check (superseded_at is null or superseded_at >= collected_at)
);

create unique index uk_macro_observation_current
    on macro_observation(series_id, observation_date)
    where superseded_at is null;

create index idx_macro_observation_as_of
    on macro_observation(series_id, observation_date desc, collected_at, superseded_at);
```

部分唯一索引保证每个“序列 + 观测日期”最多只有一个当前版本，同时允许保留多个历史版本。

## 6. 修订与时间一致性

同步同一个 `series_id + observation_date` 时：

1. 没有当前记录：插入新版本；
2. 当前数值相同：不写库，计为未变化；
3. 当前数值不同：在同一事务内把旧版本的 `superseded_at` 设置为本次采集时间，再插入新版本，计为修订。

按研究时间查询时，版本必须满足：

```sql
where collected_at <= :research_time
  and (superseded_at is null or superseded_at > :research_time)
```

该语义保证未来生成或复盘历史简报时，只能使用当时已经采集到的数据，不能使用后来修订的数值。

Repository 必须预留：

```java
Optional<MacroObservation> findLatestAsOf(
        String seriesId,
        OffsetDateTime researchTime
);
```

本阶段不开放对应 HTTP 接口，仅在仓储层建立正确能力。

## 7. FRED 响应处理

Provider 读取响应中的 `observations` 数组，至少校验每项的 `date` 和 `value`：

- 合法日期与数值：转换为待同步观测值；
- `value` 为 `.`：跳过并计入缺失数量；
- 单项日期或数值格式非法：拒绝整个批次；
- 响应不是合法 JSON、缺少 `observations` 或结构错误：拒绝整个批次；
- 不允许解析一半后把部分数据写入数据库。

采集时间由应用统一生成并传递，使同一批次的版本关闭时间和新版本采集时间一致。

## 8. 配置与安全

建议配置结构：

```properties
opspilot.macro-data.fred.base-url=https://api.stlouisfed.org
opspilot.macro-data.fred.series-id=DFII10
opspilot.macro-data.fred.api-key=${FRED_API_KEY:}
```

- 缺少 API Key 时应用可以启动，但真实同步返回 503；
- 日志只记录序列、数量、耗时和异常类型；
- 不记录完整请求 URL，因为查询参数包含 API Key；
- 不记录响应原文或远端错误详情，避免泄露凭证和不稳定的外部内容；
- 单元测试使用脱敏后的真实响应结构快照，不伪装成实时数据。

## 9. HTTP API

### 9.1 同步实际利率

`post /api/macro-data/real-rate/sync`

成功返回同步统计，包括：收到数量、缺失数量、新增数量、修订数量、未变化数量和采集时间。

### 9.2 查询最新实际利率

`get /api/macro-data/real-rate/latest`

- 有数据：返回 200；
- 无数据：返回 404 和统一 `ApiError`。

### 9.3 查询历史实际利率

`get /api/macro-data/real-rate?limit=60`

- 默认 `limit=60`；
- 合法范围 `1..500`；
- 非法参数返回 400 和宏观数据专用错误码；
- 只返回每个观测日期的当前版本，不返回已关闭的修订版本。

## 10. 异常与降级

- 缺少 `FRED_API_KEY`：抛出 `MacroDataUnavailableException`，HTTP 503；
- FRED 400、404、423、429、5xx、超时或网络失败：转换为安全的 `MacroDataUnavailableException`，HTTP 503；
- 非法查询参数：抛出宏观数据专用参数异常，HTTP 400；
- 最新数据不存在：HTTP 404；
- 非法 JSON 或批次数据格式错误：整批失败，不产生部分写入；
- 已保存的数据不因一次同步失败而删除或标记为最新采集结果。

全局异常处理器只捕获明确的宏观数据异常，不捕获所有 `IllegalArgumentException`，避免污染文档、聊天等其他模块的错误语义。

## 11. 测试策略

### 11.1 Provider 单元测试

使用脱敏的 FRED 响应结构快照验证：

- 正常观测值解析；
- `.` 缺失值识别；
- 非法 JSON、缺少数组和非法单项导致整批失败；
- 远端错误被转换为安全异常；
- 日志和异常不包含 API Key。

### 11.2 真实接口测试

提供独立 Live Test，仅当环境中存在 `FRED_API_KEY` 时执行。它验证官方接口当前仍可访问以及 `DFII10` 能返回真实观测值，不加入默认构建的稳定性判断。

### 11.3 PostgreSQL 集成测试

验证：

- 首次写入；
- 相同数值不重复；
- 修订时关闭旧版本并创建新版本；
- 当前查询只返回新版本；
- `findLatestAsOf` 能在修订前后返回各自正确版本；
- 部分唯一索引和时间约束生效。

### 11.4 Service 与 Controller 测试

验证同步统计、默认 `limit=60`、边界 `1/500`、非法值 `-1/0/501`、最新数据 404、外部服务失败 503，以及统一 JSON 错误结构。

测试方法按业务含义相邻排列，并写中文注释说明不常见的版本时间语义。测试先保持可编译，再通过行为断言形成红灯，避免用“类尚未创建”的编译失败代替有效测试。

## 12. 验收标准

- 使用真实 FRED `DFII10` 数据完成一次独立同步验收；
- 默认测试不依赖网络和 FRED 配额；
- `.` 不入库且不会被当成零；
- 相同值不会产生重复版本，修订值会保留新旧版本；
- 指定研究时间能还原当时可见的最新观测；
- 三个 HTTP API 的正常和错误合同均有自动化测试；
- API Key 不出现在代码、Git 差异、测试报告和日志中；
- 全量 Maven 测试、差异检查和敏感信息扫描通过。

