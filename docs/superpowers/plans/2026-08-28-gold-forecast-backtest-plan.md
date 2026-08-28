# 黄金方向预测历史回测实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用真实历史数据异步生成最多 120 条与实时数据完全隔离的黄金方向回测结果，并提供任务、明细和独立评测 API。

**Architecture:** 在 `forecast.backtest` 包内建立独立领域模型、JDBC 仓储、历史快照构建器和单线程任务执行器。复用现有因子计算、预测提示词、模型网关、校验器和结算规则，但不写入实时快照与实时预测表；每条结果立即保存，以任务和日期唯一约束支持中断续跑。

**Tech Stack:** Java 21、Spring Boot 3.5.14、Spring JDBC、PostgreSQL 17、Flyway、Spring AI 1.1.8、JUnit 5、Mockito、AssertJ、MockMvc

**Spec:** `docs/superpowers/specs/2026-08-28-gold-forecast-backtest-design.md`

## 全局约束

- 所有计划、说明、错误信息和特殊逻辑注释使用中文。
- Java 方法名和变量名优先简短清晰；特殊业务含义使用简短中文注释。
- 新增生产类、record、enum、exception、service、controller、configuration 必须有简短中文类级 Javadoc。
- SQL 关键字、表名和字段名统一小写。
- 回测不得写入 `gold_research_snapshot`、`gold_research_narrative`、`gold_direction_forecast`。
- 日期 `T` 的模型输入不得读取 `T` 之后的数据；下一交易日价格只用于结算。
- 普通测试使用伪模型，不允许产生真实 API 调用或费用。
- 真正批量调用 GLM 前必须再次取得兵哥明确确认。
- AI 核心提示词或模型调用变更由兵哥手写；外围代码、测试、数据库和 Git 由助手处理。
- 保留现有未跟踪文档模块文件，不纳入本功能提交。

---

### 任务 1：回测任务和明细数据库边界

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__create_gold_forecast_backtest.sql`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestStatus.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestTask.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestCase.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestRepository.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/JdbcBacktestRepository.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestSchemaTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/JdbcBacktestRepositoryTests.java`

**Interfaces:**
- Produces: `BacktestTask create(BacktestTask)`、`Optional<BacktestTask> findTask(UUID)`、`List<BacktestCase> findCases(UUID,int)`、`Set<LocalDate> findDoneDates(UUID)`、`boolean start(UUID,OffsetDateTime)`、`boolean saveCase(BacktestCase)`、`void fail(UUID,String)`、`void complete(UUID,OffsetDateTime)`。
- Consumes: 现有 PostgreSQL datasource、Flyway、`GoldResearchSnapshot`、`ForecastDirection`。

- [ ] **Step 1: 写数据库红灯测试**

```java
@Test
void createsBacktestTables() {
    Integer taskTable = jdbc.queryForObject(
            "select count(*) from information_schema.tables "
                    + "where table_schema = 'public' and table_name = 'gold_forecast_backtest'",
            Integer.class
    );
    Integer caseTable = jdbc.queryForObject(
            "select count(*) from information_schema.tables "
                    + "where table_schema = 'public' and table_name = 'gold_forecast_backtest_case'",
            Integer.class
    );
    assertThat(taskTable).isEqualTo(1);
    assertThat(caseTable).isEqualTo(1);
}
```

- [ ] **Step 2: 运行测试确认缺表失败**

Run: `cd backend; .\mvnw.cmd -Dtest=BacktestSchemaTests test`

Expected: FAIL，断言表数量为 `0`。

- [ ] **Step 3: 新增 V9 迁移**

迁移必须包含任务状态检查、样本数 `1..120`、任务日期唯一明细、JSONB 快照和列表字段、提示词哈希长度检查，以及以下核心唯一约束：

```sql
create unique index uk_gold_forecast_backtest_case
    on gold_forecast_backtest_case (backtest_id, as_of_date);
```

任务表状态只允许：

```sql
check (status in ('created', 'running', 'completed', 'failed'))
```

- [ ] **Step 4: 定义简短领域类型和仓储接口**

```java
/** 表示回测任务生命周期。 */
public enum BacktestStatus {
    CREATED, RUNNING, COMPLETED, FAILED
}
```

`BacktestTask` 必须保存 `id`、`startDate`、`endDate`、`sampleCount`、`modelName`、`promptVersion`、`ruleVersion`、状态与计数时间字段。`BacktestCase` 必须保存完整 `GoldResearchSnapshot`、预测、结算、版本与审计字段。

- [ ] **Step 5: 写仓储行为测试**

覆盖首次创建、按编号查询、条件启动、相同任务日期幂等保存、倒序明细、完成计数和失败摘要。断言公开查询对象仍携带 `rawResponse` 仅供内部服务使用，HTTP 响应在任务 7 负责隐藏。

- [ ] **Step 6: 实现 JDBC 仓储**

使用 `ObjectMapper` 把 `GoldResearchSnapshot`、失效条件保存为 JSONB；`start` 使用条件更新：

```sql
update gold_forecast_backtest
set status = 'running', started_at = coalesce(started_at, ?), last_error = null
where id = ? and status in ('created', 'failed')
```

`saveCase` 和任务计数更新放在一个 `@Transactional` 方法中，重复明细使用 `on conflict do nothing`，只有插入成功才增加计数。

- [ ] **Step 7: 运行任务 1 测试**

Run: `cd backend; .\mvnw.cmd -Dtest=BacktestSchemaTests,JdbcBacktestRepositoryTests test`

Expected: PASS，0 failures、0 errors。

- [ ] **Step 8: 提交**

```powershell
git add backend/src/main/resources/db/migration/V9__create_gold_forecast_backtest.sql `
        backend/src/main/java/com/opspilot/ai/forecast/backtest `
        backend/src/test/java/com/opspilot/ai/forecast/backtest
git commit -m "feat: 建立黄金预测回测存储边界"
```

---

### 任务 2：支持按历史截止日期读取真实数据

**Files:**
- Modify: `backend/src/main/java/com/opspilot/ai/marketdata/MarketPriceRepository.java`
- Modify: `backend/src/main/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepository.java`
- Modify: `backend/src/main/java/com/opspilot/ai/macrodata/MacroObservationRepository.java`
- Modify: `backend/src/main/java/com/opspilot/ai/macrodata/JdbcMacroObservationRepository.java`
- Test: `backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/macrodata/JdbcMacroObservationRepositoryTests.java`

**Interfaces:**
- Produces: `List<MarketPrice> findRecent(String symbol, LocalDate endDate, int limit)`。
- Produces: `List<MacroObservation> findRecent(String seriesId, LocalDate endDate, int limit)`。
- Consumes: 任务 1 无直接依赖。

- [ ] **Step 1: 写防未来数据红灯测试**

准备截止日前后各一条数据，断言新方法只返回 `date <= endDate`，并按日期倒序限制数量。

```java
assertThat(repository.findRecent("XAUUSD", LocalDate.parse("2026-08-20"), 10))
        .allMatch(price -> !price.priceDate().isAfter(LocalDate.parse("2026-08-20")));
```

- [ ] **Step 2: 运行测试确认新签名尚未实现**

Run: `cd backend; .\mvnw.cmd -Dtest=JdbcMarketPriceRepositoryTests,JdbcMacroObservationRepositoryTests test`

Expected: RED；补齐测试编译所需接口签名后，失败原因必须是查询仍包含未来记录，而不是缺少类。

- [ ] **Step 3: 实现带截止日期查询**

黄金查询核心条件：

```sql
where symbol = ? and price_date <= ?
order by price_date desc
limit ?
```

宏观查询核心条件：

```sql
where series_id = ?
  and observation_date <= ?
  and superseded_at is null
order by observation_date desc
limit ?
```

- [ ] **Step 4: 验证参数范围和旧接口兼容**

`limit` 仍使用现有范围；旧实时查询方法不得修改行为。运行两个仓储测试类并要求全部通过。

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/marketdata `
        backend/src/main/java/com/opspilot/ai/macrodata `
        backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java `
        backend/src/test/java/com/opspilot/ai/macrodata/JdbcMacroObservationRepositoryTests.java
git commit -m "feat: 支持按历史日期读取研究数据"
```

---

### 任务 3：重建历史研究快照且不污染实时表

**Files:**
- Modify: `backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java`
- Test: `backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java`

**Interfaces:**
- Consumes: 任务 2 的两个历史截止日期查询方法。
- Produces: `GoldResearchSnapshot createSnapshot(LocalDate asOf)`；现有无参 `createSnapshot()` 保持不变。

- [ ] **Step 1: 写历史快照红灯测试**

测试数据必须在截止日前提供至少 21 个共同观测日期，并额外放入一条截止日后的极端值。断言结果的三个最新数据日期均不晚于 `asOf`，当前价格不等于未来极端值。

- [ ] **Step 2: 运行测试并确认失败原因是使用了未来数据**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldResearchSnapshotServiceTests test`

- [ ] **Step 3: 提取共享计算方法**

保留现有实时入口：

```java
public GoldResearchSnapshot createSnapshot() {
    return calculate(
            priceRepo.findRecent(GOLD_SYMBOL, QUERY_LIMIT),
            macroRepo.findRecent(REAL_RATE_SERIES_ID, QUERY_LIMIT),
            macroRepo.findRecent(DOLLAR_INDEX_SERIES_ID, QUERY_LIMIT)
    );
}
```

新增历史入口：

```java
public GoldResearchSnapshot createSnapshot(LocalDate asOf) {
    Objects.requireNonNull(asOf, "回测日期不能为空");
    return calculate(
            priceRepo.findRecent(GOLD_SYMBOL, asOf, QUERY_LIMIT),
            macroRepo.findRecent(REAL_RATE_SERIES_ID, asOf, QUERY_LIMIT),
            macroRepo.findRecent(DOLLAR_INDEX_SERIES_ID, asOf, QUERY_LIMIT)
    );
}
```

原有指标计算整体移动到私有 `calculate(List<MarketPrice> gold, List<MacroObservation> rates, List<MacroObservation> dollars)`，不复制算法，不改变研究版本。

- [ ] **Step 4: 证明未写实时表**

测试只注入读取仓储，确认历史入口没有 `GoldResearchSnapshotRepository` 依赖，也不会生成 `StoredGoldResearchSnapshot`。

- [ ] **Step 5: 运行所有快照测试并提交**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldResearchSnapshotServiceTests test`

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java `
        backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java
git commit -m "feat: 按截止日期重建黄金研究快照"
```

---

### 任务 4：创建可恢复回测任务和选择可结算日期

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/InvalidBacktestRequestException.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestNotFoundException.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestDataInsufficientException.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestService.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestServiceTests.java`

**Interfaces:**
- Consumes: `MarketPriceRepository.findRecent("XAUUSD", sampleCount + 21)`、任务 1 仓储、`GoldForecastProperties`、正式提示词和规则版本常量。
- Produces: `BacktestTask create(int samples)`、`BacktestTask get(UUID id)`、`List<BacktestCase> results(UUID id,int limit)`。

- [ ] **Step 1: 写创建任务红灯测试**

覆盖默认 60 的上层调用、`1..120` 校验、至少需要下一交易日价格、起止日期来自可结算日期、模型与版本被冻结。

- [ ] **Step 2: 运行红灯测试**

Run: `cd backend; .\mvnw.cmd -Dtest=BacktestServiceTests test`

- [ ] **Step 3: 实现简短业务方法**

```java
public BacktestTask create(int samples) {
    checkSamples(samples);
    List<MarketPrice> prices = priceRepo.findRecent("XAUUSD", samples + 21);
    List<LocalDate> dates = selectDates(prices, samples);
    BacktestTask task = newTask(dates, samples);
    return repo.create(task);
}
```

`selectDates` 必须排除没有下一有效价格可结算的最新日期，并保持从旧到新执行；禁止使用 `date.plusDays(1)` 推断交易日。

- [ ] **Step 4: 实现查询和错误边界**

任务不存在抛出 `BacktestNotFoundException`；`samples` 或 `limit` 越界抛出 `InvalidBacktestRequestException`。异常消息包含参数和允许范围，不包含 SQL 或敏感配置。

- [ ] **Step 5: 运行测试并提交**

```powershell
.\backend\mvnw.cmd -q -f backend\pom.xml -Dtest=BacktestServiceTests test
git add backend/src/main/java/com/opspilot/ai/forecast/backtest `
        backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestServiceTests.java
git commit -m "feat: 创建可恢复黄金回测任务"
```

---

### 任务 5：执行单条历史预测并用下一交易日真实价格结算

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestRunner.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestPromptBuilder.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestRunnerTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestPromptBuilderTests.java`

**Interfaces:**
- Consumes: `GoldResearchSnapshotService.createSnapshot(LocalDate)`、`GoldForecastGateway.generate(GoldForecastPrompt)`、`GoldForecastValidator.validate(GoldDirectionForecastContent)`、`GoldForecastRule.classify(BigDecimal)`、`NextValidMarketPriceSelector.select(LocalDate,List<MarketPrice>)`、任务 1 仓储。
- Produces: `void run(UUID id)`、内部 `BacktestCase runOne(BacktestTask task, LocalDate date)`。

- [ ] **Step 1: 写单条执行红灯测试**

验证顺序：历史快照 → 提示词 → 模型 → 安全校验 → 下一有效价格 → 真实方向 → 命中 → 保存。伪模型固定返回 `BULLISH`，不得构造真实 `ChatClient`。

- [ ] **Step 2: 写防未来数据与幂等测试**

仓储已存在日期时不得调用 `GoldForecastGateway`；结算价格必须由 `NextValidMarketPriceSelector` 返回，提示词不得包含目标日期或目标价格。

- [ ] **Step 3: 运行红灯测试**

Run: `cd backend; .\mvnw.cmd -Dtest=BacktestRunnerTests,BacktestPromptBuilderTests test`

- [ ] **Step 4: 由助手创建外围构建器，兵哥只写 AI 关键方法**

`BacktestPromptBuilder` 复用正式提示词的事实和安全合同，但版本单独使用：

```java
public static final String VERSION = "gold-backtest-prompt-v1";
public GoldForecastPrompt build(UUID caseId, GoldResearchSnapshot snapshot)
```

兵哥只手写提示词文本与 `String.formatted(Object[])` 的实际参数；助手负责文件、构造、哈希、测试和格式整理。提示词必须明确“这是历史日期的盲测输入”，但不能提供下一交易日答案。

- [ ] **Step 5: 实现单条运行和结算**

方法和变量保持简短：`task`、`date`、`snapshot`、`prompt`、`generated`、`nextPrice`、`actualReturn`。特殊顺序使用中文注释说明。

```java
BigDecimal actualReturn = nextPrice.referencePrice()
        .subtract(basePrice)
        .divide(basePrice, 8, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"));
ForecastDirection actual = rule.classify(actualReturn);
boolean hit = generated.content().direction() == actual;
```

- [ ] **Step 6: 实现任务级循环和恢复**

先读取 `doneDates`，只处理未保存日期。模型上游不可用时任务标记失败并停止；非法 JSON、安全失败和单日数据不足增加失败数后继续。任务全部处理完毕后标记完成。

- [ ] **Step 7: 运行测试并提交**

```powershell
.\backend\mvnw.cmd -q -f backend\pom.xml -Dtest=BacktestRunnerTests,BacktestPromptBuilderTests test
git add backend/src/main/java/com/opspilot/ai/forecast/backtest `
        backend/src/test/java/com/opspilot/ai/forecast/backtest
git commit -m "feat: 执行并结算黄金历史回测"
```

---

### 任务 6：单线程后台运行和并发保护

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestConfig.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestJobService.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestJobServiceTests.java`

**Interfaces:**
- Consumes: `BacktestRepository.start(UUID,OffsetDateTime)`、`BacktestRunner.run(UUID)`。
- Produces: `BacktestTask start(UUID id)`；仅条件更新成功时提交后台任务。

- [ ] **Step 1: 写并发保护红灯测试**

连续调用两次 `start(id)`，断言只向执行器提交一次；任务处于 `running` 时返回当前状态，不重复运行。

- [ ] **Step 2: 运行红灯测试**

Run: `cd backend; .\mvnw.cmd -Dtest=BacktestJobServiceTests test`

- [ ] **Step 3: 配置独立单线程执行器**

```java
@Bean(name = "backtestExecutor")
public TaskExecutor backtestExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix("gold-backtest-");
    executor.initialize();
    return executor;
}
```

- [ ] **Step 4: 实现启动服务**

不使用公共异步线程池。`repo.start(id, now)` 返回是否取得运行权；只有 `true` 才调用 `executor.execute(() -> runner.run(id))`。

- [ ] **Step 5: 运行测试并提交**

```powershell
.\backend\mvnw.cmd -q -f backend\pom.xml -Dtest=BacktestJobServiceTests test
git add backend/src/main/java/com/opspilot/ai/forecast/backtest `
        backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestJobServiceTests.java
git commit -m "feat: 后台顺序运行黄金历史回测"
```

---

### 任务 7：独立回测评测和 HTTP API

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestEvaluationService.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestController.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestTaskResponse.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestCaseResponse.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestEvaluationResponse.java`
- Modify: `backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestEvaluationServiceTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/backtest/api/BacktestControllerTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**Interfaces:**
- Consumes: 任务 4 `BacktestService`、任务 6 `BacktestJobService`、任务 1 仓储明细。
- Produces: 设计文档规定的五个 `/api/research/gold/backtests` 接口。

- [ ] **Step 1: 写独立评测红灯测试**

使用 21 条回测明细验证总体准确率、最近 20 条、三个预测方向和中性基线；断言实时 `GoldForecastRepository` 完全不参与。

- [ ] **Step 2: 实现回测评测**

返回对象必须带固定来源：

```java
public record BacktestEvaluation(
        String source,
        int sampleCount,
        BigDecimal accuracy,
        BigDecimal rolling20Accuracy,
        BigDecimal neutralBaselineAccuracy,
        DirectionEvaluation bullish,
        DirectionEvaluation neutral,
        DirectionEvaluation bearish
) { }
```

`source` 固定为 `BACKTEST`。

- [ ] **Step 3: 写五个 HTTP 合同测试**

覆盖创建返回 201、启动返回 202、状态返回 200、结果倒序、评测来源为 `BACKTEST`；同时验证 `rawResponse`、完整提示词和数据库错误不出现在 JSON。

- [ ] **Step 4: 实现控制器和响应 DTO**

```java
@RestController
@RequestMapping("/api/research/gold/backtests")
public class BacktestController {
    private final BacktestService service;
    private final BacktestJobService jobs;
    private final BacktestEvaluationService evalService;

    @PostMapping
    public ResponseEntity<BacktestTaskResponse> create(
            @RequestParam(defaultValue = "60") int samples) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BacktestTaskResponse.from(service.create(samples)));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<BacktestTaskResponse> run(@PathVariable UUID id) {
        return ResponseEntity.accepted()
                .body(BacktestTaskResponse.from(jobs.start(id)));
    }
}
```

其余三个 GET 方法使用简短名称 `get`、`results`、`evaluation`。

- [ ] **Step 5: 映射稳定错误码**

- `BACKTEST_NOT_FOUND` → 404。
- `INVALID_BACKTEST_REQUEST` → 400。
- `BACKTEST_DATA_INSUFFICIENT` → 422。
- 后台模型失败只写任务状态，不通过已返回的 202 请求泄露异常。

- [ ] **Step 6: 运行任务 7 测试并提交**

```powershell
.\backend\mvnw.cmd -q -f backend\pom.xml "-Dtest=BacktestEvaluationServiceTests,BacktestControllerTests,GlobalExceptionHandlerTests" test
git add backend/src/main/java/com/opspilot/ai/forecast/backtest `
        backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java `
        backend/src/test/java/com/opspilot/ai/forecast/backtest `
        backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git commit -m "feat: 提供黄金历史回测查询和评测接口"
```

---

### 任务 8：全量验证、文档和真实运行闸门

**Files:**
- Modify: `README.md`
- Modify: `.env.example`
- Test: 所有非 Live 测试。

**Interfaces:**
- Consumes: 任务 1 至 7 的完整功能。
- Produces: 可创建但不会自动执行真实 GLM 调用的回测模块。

- [ ] **Step 1: 更新 README**

写明创建、启动、查询状态、查询明细和评测的 PowerShell 示例。明确 `run` 会按未完成日期调用 GLM 并消耗额度；创建和查询不调用模型。

- [ ] **Step 2: 更新示例配置**

只记录非敏感运行参数，不增加或提交 API Key：

```dotenv
GOLD_BACKTEST_MAX_SAMPLES=120
GOLD_BACKTEST_THREAD_COUNT=1
```

- [ ] **Step 3: 执行完整回归**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -q clean "-Dtest=!*LiveTests,!DocumentLifecycleServiceTests" test
```

Expected: Maven exit code `0`；Surefire `failures=0`、`errors=0`。

- [ ] **Step 4: 执行数据库和 Git 检查**

```powershell
cd D:\workFile\demo-ai
git diff --check
git status --short
```

只暂存回测功能和文档文件，不暂存既有无关未跟踪文件。

- [ ] **Step 5: 验证无外部调用副作用**

普通测试日志不得出现真实 GLM 请求。应用启动后不自动创建或运行回测；必须显式调用 `/{id}/run`。

- [ ] **Step 6: 提交最终文档**

```powershell
git add README.md .env.example
git commit -m "docs: 补充黄金历史回测使用说明"
git push origin master
```

- [ ] **Step 7: 停在真实调用闸门**

向兵哥报告预计剩余模型调用次数、API 额度影响和任务编号。未取得单独确认前，不调用 `post /api/research/gold/backtests/{id}/run`。
