# FRED 广义美元指数真实数据基础实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 通过 FRED `DTWEXBGS` 获取、版本化保存和查询广义美元指数，并显式返回数据新鲜度。

**架构：** 从现有实际利率 Provider 中抽取通用 `FredSeriesClient`，两种领域 Provider 共享外部协议处理但保留各自语义。美元观测复用 `macro_observation`，通过独立同步服务和 API 暴露，新鲜度规则使用 UTC `Clock` 确定性计算。

**技术栈：** Java 21、Spring Boot 3.5、Spring RestClient、Jackson、Spring JDBC、PostgreSQL 17、JUnit 5、MockWebServer、MockMvc

**规格：** `docs/superpowers/specs/2026-08-27-fred-broad-dollar-index-data-design.md`

## 全局约束

- 数据源固定为 FRED `DTWEXBGS`，统一称为“广义美元指数”，不得标记为 ICE DXY。
- 复用 `macro_observation`，不新增数据库表和迁移。
- 只保存 FRED 真实有效值，不补值；`.` 统计为缺失，不能保存为 0。
- 最新观测距 UTC 当前日期不超过 7 个自然日为 `CURRENT`，超过 7 天为 `STALE`。
- SQL、HTTP 机械映射和普通 CRUD 由 Codex 处理；兵哥实现并解释新鲜度业务规则。
- 新增生产类型必须有简洁中文类注释；SQL 关键字统一小写。
- 不使用缺类编译失败作为红灯，不创建 worktree，不提交现有无关文件。
- 本阶段不修改黄金结论、不调用大模型、不生成预测。

---

### 任务 1：抽取通用 FRED 序列客户端

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/FredSeriesObservation.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/FredSeriesBatch.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/FredSeriesClient.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/FredSeriesClientTests.java`
- 修改：`backend/src/main/java/com/opspilot/ai/macrodata/FredRealRateProvider.java`
- 修改：`backend/src/test/java/com/opspilot/ai/macrodata/FredRealRateProviderTests.java`

**接口：**

- 产生：`FredSeriesBatch fetch(String seriesId)`。
- 保持：`FredRealRateProvider.fetchDailyObservations()` 的公开合同不变。

- [ ] **步骤 1：编写通用 Client HTTP 合同测试和可编译骨架**

测试使用本地 `MockWebServer`，固定响应：

```json
{
  "observations": [
    {"date": "2026-08-20", "value": "119.1234"},
    {"date": "2026-08-21", "value": "."}
  ]
}
```

断言：

```java
FredSeriesBatch batch = client.fetch("DTWEXBGS");

assertThat(batch.receivedCount()).isEqualTo(2);
assertThat(batch.missingCount()).isEqualTo(1);
assertThat(batch.observations()).containsExactly(
        new FredSeriesObservation(
                LocalDate.parse("2026-08-20"),
                new BigDecimal("119.1234")
        )
);
```

同时检查请求参数包含 `series_id=DTWEXBGS`、`file_type=json`，且异常信息不包含测试 API Key。

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd -Dtest=FredSeriesClientTests test
```

预期：类型完整可编译，方法因 `UnsupportedOperationException` 失败。

- [ ] **步骤 3：实现通用类型和 Client**

```java
/** 表示 FRED 序列中的一条有效日期和值。 */
public record FredSeriesObservation(
        LocalDate observationDate,
        BigDecimal value
) {
}
```

```java
/** 表示一次通用 FRED 序列响应的有效、接收与缺失统计。 */
public record FredSeriesBatch(
        List<FredSeriesObservation> observations,
        int receivedCount,
        int missingCount
) {
    public FredSeriesBatch {
        observations = List.copyOf(observations);
    }
}
```

`FredSeriesClient.fetch` 必须校验非空序列 ID和 API Key，请求 `/fred/series/observations`，解析数组，过滤 `.`，并将所有外部格式错误转换为 `MacroDataUnavailableException`。

- [ ] **步骤 4：重构实际利率 Provider**

`FredRealRateProvider` 改为注入 `FredSeriesClient`，将通用观测映射为：

```java
new IncomingMacroObservation(
        properties.seriesId(),
        observation.observationDate(),
        observation.value(),
        "percent",
        "fred"
)
```

保留 `RealRateBatch`、`RealRateProvider` 和异常语义，删除 Provider 内重复的 HTTP/JSON 解析。

- [ ] **步骤 5：回归并提交**

```powershell
.\mvnw.cmd "-Dtest=FredSeriesClientTests,FredRealRateProviderTests,RealRateSyncServiceTests" test
git add backend/src/main/java/com/opspilot/ai/macrodata backend/src/test/java/com/opspilot/ai/macrodata/FredSeriesClientTests.java backend/src/test/java/com/opspilot/ai/macrodata/FredRealRateProviderTests.java
git commit -m "refactor: 复用通用 FRED 序列客户端"
git push origin master
```

---

### 任务 2：美元指数 Provider 与真实合同

**文件：**

- 修改：`backend/src/main/java/com/opspilot/ai/macrodata/FredProperties.java`
- 修改：`backend/src/main/resources/application.yaml`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/DollarIndexProvider.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/DollarIndexBatch.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/FredDollarIndexProvider.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/FredDollarIndexProviderTests.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/FredDollarIndexProviderLiveTests.java`

**接口：**

- 消费：`FredSeriesClient.fetch(properties.dollarIndexSeriesId())`。
- 产生：`DollarIndexBatch fetchDailyObservations()`。

- [ ] **步骤 1：增加配置合同**

`FredProperties` 增加：

```java
String dollarIndexSeriesId
```

`application.yaml` 增加：

```yaml
dollar-index-series-id: DTWEXBGS
```

- [ ] **步骤 2：编写 Provider 红灯测试**

通用 Client 返回两条固定观测，断言 Provider 输出的每条 `IncomingMacroObservation` 均满足：

```text
seriesId = DTWEXBGS
unit     = index_2006_100
provider = fred
```

骨架完整可编译，方法暂抛 `UnsupportedOperationException`。

- [ ] **步骤 3：实现 Provider**

```java
/** 定义广义美元指数每日观测获取契约。 */
public interface DollarIndexProvider {
    DollarIndexBatch fetchDailyObservations();
}
```

```java
/** 保存一次广义美元指数获取结果和缺失统计。 */
public record DollarIndexBatch(
        List<IncomingMacroObservation> observations,
        int receivedCount,
        int missingCount
) {
    public DollarIndexBatch {
        observations = List.copyOf(observations);
    }
}
```

`FredDollarIndexProvider` 只负责领域映射和不含凭据的完成日志。

- [ ] **步骤 4：验证普通与真实合同**

```powershell
.\mvnw.cmd -Dtest=FredDollarIndexProviderTests test
$env:FRED_API_KEY = [Environment]::GetEnvironmentVariable("FRED_API_KEY", "User")
.\mvnw.cmd -Dtest=FredDollarIndexProviderLiveTests test
```

实时测试使用环境条件控制，普通回归没有 Key 时跳过，不硬编码日期和值。

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/macrodata backend/src/main/resources/application.yaml backend/src/test/java/com/opspilot/ai/macrodata/FredDollarIndexProviderTests.java backend/src/test/java/com/opspilot/ai/macrodata/FredDollarIndexProviderLiveTests.java
git commit -m "feat: 接入 FRED 广义美元指数"
git push origin master
```

---

### 任务 3：同步服务与新鲜度规则

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/DollarIndexSyncResult.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/DollarIndexSyncService.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/DollarIndexFreshness.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/DollarIndexFreshnessEvaluator.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/DollarIndexSyncServiceTests.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/DollarIndexFreshnessEvaluatorTests.java`

**接口：**

- 产生：`DollarIndexSyncResult syncDailyObservations()`。
- 产生：`DollarIndexFreshness evaluate(LocalDate observationDate)`。

- [ ] **步骤 1：Codex 完成同步服务红绿循环**

复用 `SaveObservationResult` 统计 `INSERTED`、`REVISED`、`UNCHANGED`，同一批使用 `OffsetDateTime.now(clock)`。Provider 抛错时仓储零交互。

- [ ] **步骤 2：Codex 创建新鲜度测试和可编译骨架**

固定当前时间为 `2026-08-27T01:00:00Z`：

```java
assertThat(evaluator.evaluate(LocalDate.parse("2026-08-20")))
        .isEqualTo(DollarIndexFreshness.CURRENT);
assertThat(evaluator.evaluate(LocalDate.parse("2026-08-19")))
        .isEqualTo(DollarIndexFreshness.STALE);
assertThatThrownBy(() ->
        evaluator.evaluate(LocalDate.parse("2026-08-28")))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **步骤 3：兵哥实现新鲜度核心规则**

```java
long ageDays = ChronoUnit.DAYS.between(
        observationDate,
        LocalDate.now(clock)
);
```

要求：负数抛出 `IllegalArgumentException`；`ageDays <= 7` 返回 `CURRENT`；否则返回 `STALE`。

- [ ] **步骤 4：验证并提交**

```powershell
.\mvnw.cmd "-Dtest=DollarIndexSyncServiceTests,DollarIndexFreshnessEvaluatorTests,OpsPilotApplicationTests" test
git add backend/src/main/java/com/opspilot/ai/macrodata backend/src/test/java/com/opspilot/ai/macrodata/DollarIndexSyncServiceTests.java backend/src/test/java/com/opspilot/ai/macrodata/DollarIndexFreshnessEvaluatorTests.java
git commit -m "feat: 同步并判断美元指数新鲜度"
git push origin master
```

---

### 任务 4：美元指数 API

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/api/DollarIndexResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/api/DollarIndexSyncResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/api/DollarIndexController.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/api/DollarIndexControllerTests.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/DollarIndexNotFoundException.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`

**接口：**

- `post /api/macro-data/dollar-index/sync`
- `get /api/macro-data/dollar-index/latest`
- `get /api/macro-data/dollar-index/observations?limit=20`

- [ ] **步骤 1：编写 6 个 HTTP 合同红灯**

覆盖同步 200、最新 `CURRENT`、最新 `STALE`、无数据 404、历史查询、非法 limit 400。

无数据响应：

```json
{
  "code": "DOLLAR_INDEX_NOT_FOUND",
  "message": "尚无广义美元指数数据"
}
```

- [ ] **步骤 2：实现 DTO、Controller 与异常映射**

`DollarIndexResponse` 包含：`seriesId`、`observationDate`、`value`、`unit`、`provider`、`collectedAt`、`freshness`。

Controller 固定查询 `DTWEXBGS`，`limit` 范围 1 到 500；Controller 不包含 SQL和新鲜度算法。

- [ ] **步骤 3：回归并提交**

```powershell
.\mvnw.cmd "-Dtest=DollarIndexControllerTests,GlobalExceptionHandlerTests,RealRateControllerTests" test
git add backend/src/main/java/com/opspilot/ai/macrodata backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/macrodata/api/DollarIndexControllerTests.java
git commit -m "feat: 开放广义美元指数接口"
git push origin master
```

---

### 任务 5：真实数据端到端验收

- [ ] **步骤 1：运行完整回归**

```powershell
.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test
```

预期：0 failures、0 errors；实时测试只按环境条件运行。

- [ ] **步骤 2：启动应用并同步真实数据**

```powershell
$env:FRED_API_KEY = [Environment]::GetEnvironmentVariable("FRED_API_KEY", "User")
$env:OPSPILOT_DB_PASSWORD = [Environment]::GetEnvironmentVariable("OPSPILOT_DB_PASSWORD", "User")
.\mvnw.cmd spring-boot:run
```

```powershell
curl.exe --silent --show-error --fail-with-body `
    -X POST http://localhost:8080/api/macro-data/dollar-index/sync
```

- [ ] **步骤 3：核对最新值和历史**

```powershell
curl.exe --silent --show-error --fail-with-body `
    http://localhost:8080/api/macro-data/dollar-index/latest
curl.exe --silent --show-error --fail-with-body `
    "http://localhost:8080/api/macro-data/dollar-index/observations?limit=5"
```

确认序列为 `DTWEXBGS`、单位为 `index_2006_100`，最新值来自真实 FRED，且新鲜度与 UTC 日期差一致。

- [ ] **步骤 4：检查安全与 Git 边界**

```powershell
git diff --check
git status --short
git rev-parse HEAD
git rev-parse origin/master
```

扫描提交差异不得出现 FRED API Key；不暂存现有根目录设计文档、文档生命周期文件以及 `SaveGoldResearchSnapshotResult.java` 的用户空行改动。
