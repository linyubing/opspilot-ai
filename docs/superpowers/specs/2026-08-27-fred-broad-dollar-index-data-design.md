# FRED 广义美元指数真实数据基础设计

## 1. 目标

接入 FRED 官方序列 `DTWEXBGS`，为黄金研究助手提供可追溯的美元强弱数据基础。

该序列是美联储发布的名义广义美元指数，单位为 `Index Jan 2006=100`。指数上升表示美元对广泛贸易伙伴货币整体走强。

本阶段只完成真实数据获取、版本化保存、同步、查询和新鲜度判断，不直接修改黄金研究结论，不生成方向预测，也不调用大模型。

## 2. 数据源选择

### 2.1 采用的序列

- 数据平台：FRED；
- 原始来源：Board of Governors of the Federal Reserve System (US)；
- 序列：`DTWEXBGS`；
- 名称：Nominal Broad U.S. Dollar Index；
- 频率：Daily；
- 调整方式：Not Seasonally Adjusted；
- 单位：Index Jan 2006=100；
- 项目内部单位值：`index_2006_100`。

官方页面：`https://fred.stlouisfed.org/series/DTWEXBGS`

### 2.2 不采用 ICE DXY

ICE DXY 更贴近交易市场常用美元指数，但正式实时或完整历史数据通常涉及商业授权。本项目当前是个人学习用途，优先采用免费、官方、可追溯的 FRED 广义美元指数。

系统和文档中统一称为“广义美元指数”，不得将 `DTWEXBGS` 标记为 ICE DXY。

### 2.3 发布滞后

FRED 将该序列标记为每日数据，但通常按周补充上一周每日观测。系统必须区分“观测日期”和“采集时间”，并显式判断数据是否过期。

## 3. 复用与重构边界

### 3.1 复用现有表

继续使用 `macro_observation`：

- `series_id = 'DTWEXBGS'`；
- `unit = 'index_2006_100'`；
- `provider = 'fred'`。

现有表已经支持不同序列、修订版本、当前版本唯一约束和按时间回看，不创建美元指数专用表，也不新增 Flyway 迁移。

### 3.2 抽取通用 FRED Client

现有 `FredRealRateProvider` 同时承担 HTTP 调用、JSON 解析和实际利率语义，直接复制会产生两套相同的外部协议处理。

新增 `FredSeriesClient`，只负责：

1. 校验 FRED API Key；
2. 请求 `/fred/series/observations`；
3. 处理 `file_type=json`；
4. 过滤 FRED 使用 `.` 表示的缺失值；
5. 将有效项转换为通用 FRED 观测批次；
6. 将网络、空响应和格式错误转换为稳定宏观数据异常；
7. 日志不输出 API Key、完整 URL 或完整响应。

`FredSeriesClient` 不知道“实际利率”或“美元指数”的研究含义。

### 3.3 保留语义 Provider

- `FredRealRateProvider`：调用通用 Client，传入 `DFII10` 和 `percent`，继续实现现有 `RealRateProvider`；
- `FredDollarIndexProvider`：调用通用 Client，传入 `DTWEXBGS` 和 `index_2006_100`，实现新的 `DollarIndexProvider`。

这样外部协议只有一份，领域语义仍然清晰，不把所有宏观序列塞进一个无边界 Service。

## 4. 配置模型

保留现有配置：

```yaml
opspilot:
  macro-data:
    fred:
      base-url: https://api.stlouisfed.org
      api-key: ${FRED_API_KEY:}
      series-id: DFII10
      dollar-index-series-id: DTWEXBGS
      connect-timeout: 5s
      read-timeout: 20s
```

为了减少对既有代码和测试的无关改动，`series-id` 继续表示实际利率序列；新增 `dollar-index-series-id` 表示广义美元指数序列。

两条序列共用同一个 FRED API Key、Base URL、连接超时和读取超时。

## 5. Java 组件

### 5.1 `FredSeriesObservation`

表示通用 FRED 响应中的一条有效日期和值，不包含项目仓储字段。

```java
public record FredSeriesObservation(
        LocalDate observationDate,
        BigDecimal value
) {
}
```

### 5.2 `FredSeriesBatch`

包含有效观测、原始接收数量和缺失数量。`receivedCount` 必须等于 FRED 原始数组长度，不能用有效数量替代。

### 5.3 `FredSeriesClient`

公开方法：

```java
FredSeriesBatch fetch(String seriesId)
```

`seriesId` 不能为空。API Key 缺失、FRED 返回空数组、字段缺失、日期非法或数值非法时，抛出 `MacroDataUnavailableException`。

异常信息允许包含序列 ID，禁止包含 API Key。

### 5.4 `DollarIndexProvider` 与 `DollarIndexBatch`

`DollarIndexProvider` 定义：

```java
DollarIndexBatch fetchDailyObservations()
```

Provider 将通用 FRED 观测转换为现有 `IncomingMacroObservation`，补充序列 ID、单位和供应商。

### 5.5 `DollarIndexSyncService`

复用 `MacroObservationRepository.save(...)` 的版本化语义，返回插入、修订、不变和缺失数量。

本阶段允许与 `RealRateSyncService` 保持两个清晰的语义服务，不提前抽象一个复杂的万能同步框架。

### 5.6 `DollarIndexFreshnessEvaluator`

输入最新观测日期和当前日期，输出：

- `CURRENT`：相差不超过 7 个自然日；
- `STALE`：相差超过 7 个自然日。

判断使用注入的 UTC `Clock`，测试不得依赖系统当前时间。

这里的 7 天是数据可用性护栏，不代表市场交易日算法。周末和节假日不会因为短暂停更立即误判为过期。

## 6. API

新增：

```text
post /api/macro-data/dollar-index/sync
get  /api/macro-data/dollar-index/latest
get  /api/macro-data/dollar-index/observations?limit=20
```

### 6.1 同步接口

返回接收、缺失、插入、修订、不变数量和采集时间。

### 6.2 最新观测接口

返回序列 ID、观测日期、指数值、单位、供应商、采集时间和新鲜度状态。

不存在数据时返回：

- HTTP 404；
- 错误码 `DOLLAR_INDEX_NOT_FOUND`。

### 6.3 历史查询接口

- `limit` 默认 20；
- 合法范围 1 到 500；
- 非法值返回 HTTP 400 和 `INVALID_MACRO_DATA_REQUEST`；
- 按观测日期倒序返回当前版本。

## 7. 数据流程

```text
FRED DTWEXBGS
    ↓
FredSeriesClient
    ↓
FredDollarIndexProvider
    ↓
DollarIndexSyncService
    ↓
MacroObservationRepository
    ↓
macro_observation
```

查询流程：

```text
macro_observation
    ↓
最新 DTWEXBGS 当前版本
    ↓
DollarIndexFreshnessEvaluator
    ↓
CURRENT / STALE
    ↓
API 响应
```

## 8. 可信度规则

- 只保存 FRED 真实返回的有效观测，不生成补值；
- `.` 表示缺失，不能转换为 0；
- 修订数据继续使用现有版本化仓储，不覆盖历史版本；
- 最新记录超过 7 个自然日时必须返回 `STALE`；
- 后续黄金双因子研究只能消费 `CURRENT` 美元数据；
- `STALE` 数据仍可查询和展示，但不得伪装为当前研究依据；
- 本阶段不使用测试假数据填充生产数据库。

## 9. Bean 注入边界

新增 `DollarIndexProvider` 后不会与现有 `RealRateProvider` 形成同类型冲突。

通用 `FredSeriesClient` 只注册一个 Bean，继续使用现有 `fredRestClient`。不新增第二个无 Qualifier 的 `RestClient` 或 `Clock` Bean。

每增加一个 Spring Bean 后，必须运行 `OpsPilotApplicationTests` 验证完整依赖链。

## 10. 测试策略

### 10.1 通用 Client 合同测试

- 正确发送序列 ID、API Key 和 `file_type=json`；
- 解析有效观测；
- 过滤 `.` 并准确统计缺失值；
- 拒绝空响应、空数组、缺失字段和非法值；
- 错误与日志不泄露 API Key。

### 10.2 Provider 测试

- 实际利率 Provider 重构前后合同保持一致；
- 美元 Provider 正确补充 `DTWEXBGS`、`index_2006_100` 和 `fred`。

### 10.3 同步服务测试

- 正确统计插入、修订、不变和缺失数量；
- 同一批次使用同一个采集时间；
- Provider 失败时不写入仓储。

### 10.4 新鲜度测试

- 观测日与当前日相差 7 天为 `CURRENT`；
- 相差 8 天为 `STALE`；
- 未来观测日期视为非法数据，不判断为当前。

### 10.5 API 测试

- 同步、最新值和历史查询合同；
- 无数据 404；
- 参数错误 400；
- `CURRENT` 与 `STALE` 正确序列化。

### 10.6 真实数据验收

- 使用用户级 `FRED_API_KEY` 请求真实 `DTWEXBGS`；
- 响应至少包含一条有效观测；
- 同步后 PostgreSQL 可以查询最新值；
- 最新 API 的序列、单位、日期和新鲜度与数据库一致；
- 不把实时测试硬编码进普通回归测试。

## 11. 学习分工

- Codex 负责 FRED HTTP/JSON 通用化、配置、SQL/仓储复用、DTO 和机械映射；
- 兵哥负责实现 `DollarIndexFreshnessEvaluator` 的业务规则，以及审查“数据过期时能否进入研究”的边界；
- 不安排普通 CRUD 或 SQL 参数顺序作为兵哥练习；
- 不使用“类不存在”导致的编译失败作为红灯；
- 不常见代码和所有新增生产类型均提供简洁中文注释。

## 12. 验收标准

- 真实 `DTWEXBGS` 能通过现有 FRED 凭据获取并同步；
- 实际利率现有测试和真实同步行为不回退；
- 美元观测能够版本化保存和查询；
- 最新数据明确返回 `CURRENT` 或 `STALE`；
- 过期数据不会在后续阶段被当作当前研究依据；
- 全部普通回归测试通过；
- 代码、日志和 Git 中不包含 FRED API Key；
- 本阶段不修改黄金快照结论，不调用大模型，不生成预测。
