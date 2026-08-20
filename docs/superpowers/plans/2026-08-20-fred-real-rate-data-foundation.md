# FRED 实际利率数据基础实施计划

> **给智能体执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项执行；使用复选框跟踪进度。

**目标：** 接入 FRED `DFII10` 真实日度实际利率，保留数据修订版本，并提供同步、最新和历史查询接口。

**架构：** 使用 Provider 隔离 FRED HTTP 协议，Service 协调同步统计，Repository 通过 PostgreSQL 保存当前版本和历史修订版本，Controller 只负责 HTTP 合同。数据库以采集时间作为版本时间，使后续研究可以按当时可见数据复盘。

**技术栈：** Java 21、Spring Boot 3.5、Spring JDBC、`RestClient`、Jackson、Flyway、PostgreSQL 17、JUnit 5、AssertJ、MockMvc。

**规格：** `docs/superpowers/specs/2026-08-20-fred-real-rate-data-design.md`

## 全局约束

- 本阶段只接入 FRED `DFII10`，不实现均线、变化方向、相关性、简报、预测或交易信号。
- 使用真实外部数据；测试固定值只验证程序合同，必须明确注明不是真实实时数据。
- FRED 的 `.` 表示缺失，只跳过并计数，绝不能转换为零。
- `FRED_API_KEY` 只来自环境变量，不记录完整请求 URL、响应原文或 API Key。
- SQL 关键字统一小写；正式数值使用 `BigDecimal`。
- 不常见的版本时间、事务和外部响应处理代码必须写中文注释。
- 测试必须保持可编译，以行为断言制造红灯，不用“生产类不存在”的编译错误充当测试失败。
- 关键生产代码由兵哥编写；每个任务先给完整接口和测试上下文，再由 Codex 审查、运行测试并提交。

---

## 文件结构

新增或修改文件及单一职责：

- `backend/src/main/resources/db/migration/V3__create_macro_observation.sql`：宏观观测版本表、约束和索引。
- `backend/src/main/java/com/opspilot/ai/macrodata/MacroObservation.java`：持久化后的观测版本。
- `backend/src/main/java/com/opspilot/ai/macrodata/IncomingMacroObservation.java`：Provider 返回的待同步观测，不提前生成数据库 ID。
- `backend/src/main/java/com/opspilot/ai/macrodata/SaveObservationResult.java`：单条保存结果枚举 `INSERTED/REVISED/UNCHANGED`。
- `backend/src/main/java/com/opspilot/ai/macrodata/MacroObservationRepository.java`：保存与当前/时间切片查询合同。
- `backend/src/main/java/com/opspilot/ai/macrodata/JdbcMacroObservationRepository.java`：修订事务和 JDBC 查询实现。
- `backend/src/main/java/com/opspilot/ai/macrodata/RealRateBatch.java`：Provider 批次结果，保留收到数和缺失数。
- `backend/src/main/java/com/opspilot/ai/macrodata/RealRateProvider.java`：实际利率外部来源接口。
- `backend/src/main/java/com/opspilot/ai/macrodata/FredProperties.java`：FRED 配置映射。
- `backend/src/main/java/com/opspilot/ai/macrodata/MacroDataConfiguration.java`：FRED 专用 `RestClient` 和 `Clock`。
- `backend/src/main/java/com/opspilot/ai/macrodata/FredRealRateProvider.java`：FRED HTTP 与 JSON 适配。
- `backend/src/main/java/com/opspilot/ai/macrodata/MacroDataUnavailableException.java`：外部宏观数据不可用。
- `backend/src/main/java/com/opspilot/ai/macrodata/InvalidMacroDataRequestException.java`：宏观查询参数非法。
- `backend/src/main/java/com/opspilot/ai/macrodata/RealRateSyncResult.java`：同步统计。
- `backend/src/main/java/com/opspilot/ai/macrodata/RealRateSyncService.java`：同步协调。
- `backend/src/main/java/com/opspilot/ai/macrodata/api/RealRateController.java`：三个 HTTP 接口。
- `backend/src/main/java/com/opspilot/ai/macrodata/api/RealRateResponse.java`：单条观测响应。
- `backend/src/main/java/com/opspilot/ai/macrodata/api/RealRateSyncResponse.java`：同步响应。
- `backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`：增加精确的宏观异常映射。
- `backend/src/main/resources/application.yaml`：增加不含真实 Key 的 FRED 配置。
- 对应测试放在 `backend/src/test/java/com/opspilot/ai/macrodata` 及其 `api` 子包。

### 任务 1：建立宏观观测领域模型和表结构

**文件：**

- 新建：`backend/src/main/resources/db/migration/V3__create_macro_observation.sql`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/MacroObservation.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/IncomingMacroObservation.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/MacroObservationSchemaTests.java`

**接口：**

- 产出：`MacroObservation(UUID id, String seriesId, LocalDate observationDate, BigDecimal value, String unit, String provider, OffsetDateTime collectedAt, OffsetDateTime supersededAt)`。
- 产出：`IncomingMacroObservation(String seriesId, LocalDate observationDate, BigDecimal value, String unit, String provider)`。

- [ ] **步骤 1：先创建两个可编译的 record 骨架**

两个 record 只声明上述字段。为 `IncomingMacroObservation` 增加紧凑构造器，校验字符串非空、`value` 非空；这是领域输入保护，不校验 `DFII10`，以便类型复用于后续序列。

- [ ] **步骤 2：写表结构行为测试**

测试使用 `JdbcTemplate` 查询 `information_schema.columns` 和 `pg_indexes`，明确断言：

```java
assertThat(columnNames).containsExactlyInAnyOrder(
        "id", "series_id", "observation_date", "observation_value",
        "unit", "provider", "collected_at", "superseded_at"
);
assertThat(indexDefinition)
        .contains("unique")
        .contains("where (superseded_at is null)");
```

再直接插入 `superseded_at < collected_at` 的记录，断言 PostgreSQL 抛出数据完整性异常。

- [ ] **步骤 3：运行测试确认红灯来自表不存在**

在 `backend` 执行：

```powershell
.\mvnw.cmd -Dtest=MacroObservationSchemaTests test
```

预期：测试代码能够编译，执行阶段因 `macro_observation` 不存在而失败。

- [ ] **步骤 4：编写 V3 迁移**

使用规格中的完整 `create table`、部分唯一索引和时间切片索引。SQL 关键字全部小写，不修改 V1/V2。

- [ ] **步骤 5：重跑测试并提交**

```powershell
.\mvnw.cmd -Dtest=MacroObservationSchemaTests test
git add backend/src/main/resources/db/migration/V3__create_macro_observation.sql backend/src/main/java/com/opspilot/ai/macrodata backend/src/test/java/com/opspilot/ai/macrodata/MacroObservationSchemaTests.java
git commit -m "feat: 建立宏观观测版本表"
```

预期：测试通过，数据库 schema 版本为 3。

### 任务 2：实现修订保存与时间切片查询

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/SaveObservationResult.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/MacroObservationRepository.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/JdbcMacroObservationRepository.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/JdbcMacroObservationRepositoryTests.java`

**接口：**

```java
public enum SaveObservationResult {
    INSERTED, REVISED, UNCHANGED
}

public interface MacroObservationRepository {
    SaveObservationResult save(
            IncomingMacroObservation observation,
            OffsetDateTime collectedAt
    );

    Optional<MacroObservation> findLatest(String seriesId);

    List<MacroObservation> findRecent(String seriesId, int limit);

    Optional<MacroObservation> findLatestAsOf(
            String seriesId,
            OffsetDateTime researchTime
    );
}
```

- [ ] **步骤 1：创建接口和空实现，使测试始终可编译**

`JdbcMacroObservationRepository` 先注入 `JdbcTemplate`，方法临时抛出 `UnsupportedOperationException`。为事务方法添加 `@Transactional`，并用中文注释说明“关闭旧版本和插入新版本必须原子完成”。

- [ ] **步骤 2：写仓储行为测试**

按业务含义相邻放置五组测试：首次保存返回 `INSERTED`；相同数值返回 `UNCHANGED` 且只有一行；不同数值返回 `REVISED` 且旧行关闭、新行当前；`findRecent` 只返回当前版本；`findLatestAsOf` 在修订前后分别返回旧值和新值。

时间切片测试使用固定时间：

```java
OffsetDateTime firstCollectedAt = OffsetDateTime.parse("2026-08-20T01:00:00Z");
OffsetDateTime revisedAt = OffsetDateTime.parse("2026-08-20T03:00:00Z");

assertThat(repository.findLatestAsOf(SERIES_ID, firstCollectedAt.plusMinutes(30)))
        .get()
        .extracting(MacroObservation::value)
        .isEqualTo(new BigDecimal("1.850000"));
```

固定数字只验证版本合同，测试注释必须说明不代表真实 FRED 实时值。

- [ ] **步骤 3：运行测试确认行为红灯**

```powershell
.\mvnw.cmd -Dtest=JdbcMacroObservationRepositoryTests test
```

预期：编译成功，方法因空实现失败。

- [ ] **步骤 4：实现保存事务**

在事务中先查询当前版本并锁定：

```sql
select id, observation_value
from macro_observation
where series_id = ?
  and observation_date = ?
  and superseded_at is null
for update
```

无当前行则插入；`BigDecimal.compareTo` 相等则返回 `UNCHANGED`；不同则先执行：

```sql
update macro_observation
set superseded_at = ?
where id = ?
  and superseded_at is null
```

再用同一个 `collectedAt` 插入新版本。必须检查更新行数等于 1，否则抛出 `IllegalStateException`，让事务回滚。

- [ ] **步骤 5：实现三个查询**

`findLatest` 和 `findRecent` 必须包含 `superseded_at is null`。`findLatestAsOf` 使用规格中的时间条件，并按 `observation_date desc, collected_at desc limit 1` 排序。

- [ ] **步骤 6：重跑测试并提交**

```powershell
.\mvnw.cmd -Dtest=JdbcMacroObservationRepositoryTests test
git add backend/src/main/java/com/opspilot/ai/macrodata backend/src/test/java/com/opspilot/ai/macrodata/JdbcMacroObservationRepositoryTests.java
git commit -m "feat: 保存宏观数据修订版本"
```

### 任务 3：实现 FRED Provider 与安全配置

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/RealRateBatch.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/RealRateProvider.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/FredProperties.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/MacroDataConfiguration.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/FredRealRateProvider.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/MacroDataUnavailableException.java`
- 修改：`backend/src/main/resources/application.yaml`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/FredRealRateProviderTests.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/FredRealRateProviderLiveTests.java`

**接口：**

```java
public record RealRateBatch(
        List<IncomingMacroObservation> observations,
        int receivedCount,
        int missingCount
) {
    public RealRateBatch {
        observations = List.copyOf(observations);
    }
}

public interface RealRateProvider {
    RealRateBatch fetchDailyObservations();
}
```

`FredProperties` 字段固定为 `URI baseUrl, String apiKey, String seriesId, Duration connectTimeout, Duration readTimeout`，前缀为 `opspilot.macro-data.fred`。

- [ ] **步骤 1：创建可编译边界和专用配置**

在 `application.yaml` 中加入：

```yaml
opspilot:
  macro-data:
    fred:
      base-url: https://api.stlouisfed.org
      api-key: ${FRED_API_KEY:}
      series-id: DFII10
      connect-timeout: 5s
      read-timeout: 20s
```

合并到已有 `opspilot` 节点，不复制或覆盖已有配置。`MacroDataConfiguration` 使用限定名称 `fredRestClient` 和 `macroDataClock`，避免与黄金模块 Bean 冲突。

- [ ] **步骤 2：写固定响应解析测试**

通过本地 `MockWebServer` 或 JDK `HttpServer` 返回脱敏快照：

```json
{
  "observations": [
    {"date": "2026-08-17", "value": "1.850"},
    {"date": "2026-08-18", "value": "."},
    {"date": "2026-08-19", "value": "1.820"}
  ]
}
```

断言 `receivedCount=3`、`missingCount=1`、合法记录两条、单位 `percent`、提供方 `fred`。再覆盖缺少 `observations`、非法日期、非法数值、HTTP 429，以及空 Key 在发请求前失败。

- [ ] **步骤 3：运行测试确认行为红灯**

```powershell
.\mvnw.cmd -Dtest=FredRealRateProviderTests test
```

- [ ] **步骤 4：实现 FRED 请求和整批校验**

请求路径和参数：

```text
/fred/series/observations?series_id=DFII10&api_key=由运行环境注入&file_type=json
```

先验证 Key 非空，再请求 `JsonNode`。遍历前验证 `observations` 是数组；每项先读取 `date/value`。`value="."` 只增加缺失计数，其余任何格式错误都抛 `MacroDataUnavailableException`。只有完整解析成功才返回不可变批次。

日志只允许以下元数据：

```java
log.info(
        "FRED 实际利率获取完成，序列={}，收到={}，有效={}，缺失={}，耗时={}毫秒",
        properties.seriesId(), receivedCount, observations.size(), missingCount, elapsedMillis
);
```

- [ ] **步骤 5：增加条件式 Live Test**

使用 `@EnabledIfEnvironmentVariable(named = "FRED_API_KEY", matches = ".+")`，调用真实 Provider，并断言序列、合法日期、非空数值和至少一条有效观测。不要断言具体实时数值。

- [ ] **步骤 6：验证并提交**

```powershell
.\mvnw.cmd -Dtest=FredRealRateProviderTests test
git add backend/src/main/java/com/opspilot/ai/macrodata backend/src/main/resources/application.yaml backend/src/test/java/com/opspilot/ai/macrodata/FredRealRateProviderTests.java backend/src/test/java/com/opspilot/ai/macrodata/FredRealRateProviderLiveTests.java
git commit -m "feat: 接入 FRED 实际利率数据"
```

默认测试必须不访问公网。

### 任务 4：实现实际利率同步服务

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/RealRateSyncResult.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/RealRateSyncService.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/RealRateSyncServiceTests.java`

**接口：**

```java
public record RealRateSyncResult(
        int receivedCount,
        int missingCount,
        int insertedCount,
        int revisedCount,
        int unchangedCount,
        OffsetDateTime collectedAt
) {}

public class RealRateSyncService {
    public RealRateSyncResult syncDailyObservations();
}
```

- [ ] **步骤 1：写可编译骨架和行为测试**

测试使用内存 Repository 记录每次 `save` 的返回枚举。一个批次安排 2 个 `INSERTED`、1 个 `REVISED`、1 个 `UNCHANGED`，并设置 `receivedCount=5`、`missingCount=1`，断言所有统计和同一个固定 `collectedAt`。

另测 Provider 返回零条有效记录但存在缺失值时不会写库，仍返回正确统计；Provider 抛异常时原样传播专用异常。

- [ ] **步骤 2：运行测试确认行为红灯**

```powershell
.\mvnw.cmd -Dtest=RealRateSyncServiceTests test
```

- [ ] **步骤 3：实现最小协调逻辑**

构造器注入 `RealRateProvider`、`MacroObservationRepository` 和 `Clock`。方法开始只生成一次 `OffsetDateTime collectedAt = OffsetDateTime.now(clock)`；遍历合法观测，按 `SaveObservationResult` 累计三个计数。使用 `switch`，并用中文注释说明枚举统计与修订事务由 Repository 保证。

- [ ] **步骤 4：重跑测试并提交**

```powershell
.\mvnw.cmd -Dtest=RealRateSyncServiceTests test
git add backend/src/main/java/com/opspilot/ai/macrodata/RealRateSyncResult.java backend/src/main/java/com/opspilot/ai/macrodata/RealRateSyncService.java backend/src/test/java/com/opspilot/ai/macrodata/RealRateSyncServiceTests.java
git commit -m "feat: 添加实际利率同步服务"
```

### 任务 5：开放同步和查询 API

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/InvalidMacroDataRequestException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/api/RealRateResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/api/RealRateSyncResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/macrodata/api/RealRateController.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- 新建：`backend/src/test/java/com/opspilot/ai/macrodata/api/RealRateControllerTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**接口：**

- `post /api/macro-data/real-rate/sync`
- `get /api/macro-data/real-rate/latest`
- `get /api/macro-data/real-rate?limit=60`
- 错误码：`MACRO_DATA_UNAVAILABLE`、`INVALID_MACRO_DATA_REQUEST`。

- [ ] **步骤 1：创建 DTO、异常和 Controller 骨架**

`RealRateResponse` 返回 `seriesId/observationDate/value/unit/provider/collectedAt`，不暴露内部 `id/supersededAt`。`RealRateSyncResponse` 与同步结果六个字段一致。Controller 固定使用 `DFII10`，`limit` 范围为 `1..500`。

- [ ] **步骤 2：写完整 MockMvc 合同测试**

测试按接口分组并覆盖：同步统计；latest 成功；latest 空数据 404；recent 显式 limit；默认 60；合法上界 500；`-1/0/501` 返回 400 和 `INVALID_MACRO_DATA_REQUEST`；Provider 失败返回 503 和 `MACRO_DATA_UNAVAILABLE`。

内存 Repository 必须记录 `lastLimit`，避免“接口返回 200 但默认值没有真正传入仓储”的假通过。

- [ ] **步骤 3：运行测试确认行为红灯**

```powershell
.\mvnw.cmd -Dtest=RealRateControllerTests,GlobalExceptionHandlerTests test
```

- [ ] **步骤 4：实现 Controller 和精确异常映射**

在 `GlobalExceptionHandler` 仅新增：

```java
@ExceptionHandler(MacroDataUnavailableException.class)
public ResponseEntity<ApiError> handleMacroDataUnavailable(
        MacroDataUnavailableException exception
) {
    ApiError error = new ApiError(
            "MACRO_DATA_UNAVAILABLE",
            exception.getMessage()
    );
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
}

@ExceptionHandler(InvalidMacroDataRequestException.class)
public ResponseEntity<ApiError> handleInvalidMacroDataRequest(
        InvalidMacroDataRequestException exception
) {
    ApiError error = new ApiError(
            "INVALID_MACRO_DATA_REQUEST",
            exception.getMessage()
    );
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
}
```

不得捕获所有 `IllegalArgumentException`。在 handler 回归测试中直接断言普通 `IllegalArgumentException` 不属于任何 `@ExceptionHandler` 声明类型，防止再次污染其他模块。

- [ ] **步骤 5：重跑测试并提交**

```powershell
.\mvnw.cmd -Dtest=RealRateControllerTests,GlobalExceptionHandlerTests test
git add backend/src/main/java/com/opspilot/ai/macrodata backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/macrodata/api/RealRateControllerTests.java backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git commit -m "feat: 添加实际利率同步与查询接口"
```

### 任务 6：真实数据验收、全量回归与交付

**文件：**

- 可能修改：前述文件中由验收发现的问题；不得顺便扩展新功能。

- [ ] **步骤 1：检查环境变量但不打印值**

```powershell
$env:FRED_API_KEY -ne $null -and $env:FRED_API_KEY.Length -gt 0
```

预期：`True`。若为 `False`，只说明当前进程未读取环境变量，不读取或展示 Key。

- [ ] **步骤 2：执行真实 FRED 验收**

```powershell
.\mvnw.cmd -Dtest=FredRealRateProviderLiveTests test
```

预期：至少返回一条真实 `DFII10` 合法观测；不以具体数值作为验收条件。

- [ ] **步骤 3：执行默认全量测试**

```powershell
.\mvnw.cmd test
```

预期：全部通过；条件式 Live Test 在无 Key 环境中跳过，不导致失败。

- [ ] **步骤 4：执行差异和敏感信息检查**

```powershell
git diff --check
git status --short
git diff --cached --check
rg -n "FRED_API_KEY\s*=|api_key=[a-z0-9]{20,}|[a-z0-9]{32}" backend docs
```

逐条核对扫描命中，只允许环境变量名称和脱敏测试内容，不允许真实凭证。

- [ ] **步骤 5：手工 API 验收**

启动应用后依次调用：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/macro-data/real-rate/sync"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/macro-data/real-rate/latest" | Format-List
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/macro-data/real-rate?limit=5" | Format-Table
```

确认返回 `DFII10`、`percent`、真实日期和数值，日志只包含数量、序列和耗时。

- [ ] **步骤 6：提交验收修正并推送**

只有存在验收修正时才创建最终修正提交：

```powershell
git add -- backend/src/main/java/com/opspilot/ai/macrodata backend/src/test/java/com/opspilot/ai/macrodata backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java backend/src/main/resources/application.yaml backend/src/main/resources/db/migration/V3__create_macro_observation.sql
git commit -m "test: 完成 FRED 实际利率验收"
git push -u origin codex/fred-real-rate
```

不得暂存根工作区的四个用户未跟踪文件，也不得把 API Key 加入任何提交。

- [ ] **步骤 7：创建 PR 并请求审查**

PR 标题使用中文：`feat: 接入 FRED 实际利率数据`。正文列出真实数据口径、修订快照语义、默认测试结果、Live Test 结果、安全扫描结果和未实现范围。审查重点是事务原子性、时间切片边界、异常处理作用域和凭证泄露。
