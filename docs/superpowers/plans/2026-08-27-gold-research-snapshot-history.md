# 黄金研究快照历史留痕实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 将黄金与实际利率确定性研究快照不可变地保存到 PostgreSQL，并提供幂等写入和最近历史查询接口。

**架构：** 保留现有无副作用的快照预览接口，新增独立的历史留痕服务和 Controller。仓储使用 PostgreSQL 唯一约束与 `insert ... on conflict do nothing` 处理并发幂等，应用层通过 `created` 标志区分首次写入和命中已有记录。

**技术栈：** Java 21、Spring Boot 3.5、Spring JDBC、PostgreSQL 17、Flyway、JUnit 5、AssertJ、MockMvc

**规格：** `docs/superpowers/specs/2026-08-27-gold-research-snapshot-history-design.md`

## 全局约束

- 正式快照写入后不可更新；相同 `analysis_date + rule_version` 返回已有记录。
- 预览接口 `get /api/research/gold/snapshot` 保持无数据库副作用。
- 新增生产类型必须带简洁中文类注释，说明职责与边界。
- SQL 关键字、表名和字段名全部小写；Java 枚举常量保持大写。
- 本阶段不调用大模型，不生成方向预测，不计算准确率，不自动调参。
- 测试验证业务行为，不使用“生产类型不存在”造成的编译失败作为红灯。
- 不使用 Git worktree；直接在当前 `master` 分阶段提交并推送。
- 不暂存现有未完成的文档生命周期文件和根目录学习设计文档。

## 文件结构

### 新建文件

- `backend/src/main/resources/db/migration/V4__create_gold_research_snapshot.sql`：定义不可变快照表及数据库约束。
- `backend/src/main/java/com/opspilot/ai/analysis/history/StoredGoldResearchSnapshot.java`：表示数据库中的正式历史记录。
- `backend/src/main/java/com/opspilot/ai/analysis/history/SaveGoldResearchSnapshotResult.java`：表示首次创建或命中已有记录。
- `backend/src/main/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRepository.java`：定义幂等保存和最近查询契约。
- `backend/src/main/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepository.java`：实现 PostgreSQL 幂等写入和对象映射。
- `backend/src/main/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRecordingService.java`：编排快照生成、时钟和仓储写入。
- `backend/src/main/java/com/opspilot/ai/analysis/history/InvalidResearchHistoryRequestException.java`：表示历史查询参数错误。
- `backend/src/main/java/com/opspilot/ai/analysis/api/StoredGoldResearchSnapshotResponse.java`：定义历史记录 JSON。
- `backend/src/main/java/com/opspilot/ai/analysis/api/SaveGoldResearchSnapshotResponse.java`：定义幂等写入 JSON。
- `backend/src/main/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotHistoryController.java`：提供正式写入和历史查询接口。
- 对应的迁移、仓储、服务和 API 测试类。

### 修改文件

- `backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`：增加非法历史查询参数的 400 映射。
- `backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`：验证稳定错误码。
- `backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`：验证新增 Bean 可注入。

---

### 任务 1：数据库迁移与领域契约

**文件：**

- 新建：`backend/src/main/resources/db/migration/V4__create_gold_research_snapshot.sql`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/history/StoredGoldResearchSnapshot.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/history/SaveGoldResearchSnapshotResult.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRepository.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotSchemaTests.java`

**接口：**

- 输入：现有 `GoldResearchSnapshot`。
- 输出：`saveIfAbsent(GoldResearchSnapshot, OffsetDateTime)` 和 `findRecent(int)` 契约。
- 后续任务依赖这里确定的记录类型、字段名称和数据库约束。

- [ ] **步骤 1：编写可编译的迁移测试**

创建 `GoldResearchSnapshotSchemaTests`，使用 `JdbcTemplate` 查询 `information_schema` 和 `pg_indexes`：

```java
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class GoldResearchSnapshotSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 创建黄金研究快照表")
    void createsSnapshotTable() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name = 'gold_research_snapshot'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("分析日期和规则版本具有唯一约束")
    void createsIdempotencyConstraint() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'gold_research_snapshot'
                  and indexdef like '%(analysis_date, rule_version)%'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }
}
```

- [ ] **步骤 2：执行迁移测试并确认业务红灯**

运行：

```powershell
.\mvnw.cmd -Dtest=GoldResearchSnapshotSchemaTests test
```

预期：测试能够编译，但因表不存在而失败；不是“类不存在”的编译失败。

- [ ] **步骤 3：创建 V4 迁移**

核心结构：

```sql
create table gold_research_snapshot (
    id uuid primary key,
    analysis_date date not null,
    latest_gold_date date not null,
    latest_real_rate_date date not null,
    gold_price numeric(19, 8) not null,
    gold_return_1 numeric(12, 4) not null,
    gold_return_5 numeric(12, 4) not null,
    gold_return_20 numeric(12, 4) not null,
    gold_collected_at timestamptz not null,
    real_rate numeric(18, 6) not null,
    real_rate_change_1 numeric(18, 6) not null,
    real_rate_change_5 numeric(18, 6) not null,
    real_rate_change_20 numeric(18, 6) not null,
    real_rate_collected_at timestamptz not null,
    assessment_status varchar(32) not null,
    rule_version varchar(64) not null,
    explanation varchar(500) not null,
    disclaimer varchar(500) not null,
    created_at timestamptz not null,

    constraint ck_gold_research_snapshot_price
        check (gold_price > 0),
    constraint ck_gold_research_snapshot_status
        check (assessment_status in ('pressuring', 'supportive', 'neutral')),
    constraint uk_gold_research_snapshot_idempotency
        unique (analysis_date, rule_version)
);

create index idx_gold_research_snapshot_recent
    on gold_research_snapshot (analysis_date desc, created_at desc);
```

- [ ] **步骤 4：创建领域记录和仓储接口**

```java
/** 表示已正式写入数据库且不可修改的黄金研究历史记录。 */
public record StoredGoldResearchSnapshot(
        UUID id,
        GoldResearchSnapshot snapshot,
        OffsetDateTime createdAt
) {
}
```

```java
/** 表示快照首次创建或命中已有幂等记录，不表示保存失败。 */
public record SaveGoldResearchSnapshotResult(
        StoredGoldResearchSnapshot record,
        boolean created
) {
}
```

```java
/** 定义黄金研究快照的不可变保存与最近历史查询契约。 */
public interface GoldResearchSnapshotRepository {

    SaveGoldResearchSnapshotResult saveIfAbsent(
            GoldResearchSnapshot snapshot,
            OffsetDateTime createdAt
    );

    List<StoredGoldResearchSnapshot> findRecent(int limit);
}
```

- [ ] **步骤 5：运行迁移测试与现有快照测试**

运行：

```powershell
.\mvnw.cmd "-Dtest=GoldResearchSnapshotSchemaTests,GoldResearchSnapshotServiceTests" test
```

预期：全部通过。

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/resources/db/migration/V4__create_gold_research_snapshot.sql backend/src/main/java/com/opspilot/ai/analysis/history backend/src/test/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotSchemaTests.java
git commit -m "feat: 建立黄金研究快照留痕模型"
git push origin master
```

---

### 任务 2：JDBC 幂等仓储（兵哥核心练习）

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepository.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepositoryTests.java`

**接口：**

- 实现：`GoldResearchSnapshotRepository`。
- 保证：相同 `analysisDate + ruleVersion` 只保留第一份数据。
- 返回：最终数据库记录与 `created` 标志。

- [ ] **步骤 1：Codex 创建完整测试上下文和仓储骨架**

仓储类先完整声明依赖、Mapper、方法签名和中文类注释，方法体抛出：

```java
throw new UnsupportedOperationException("请实现黄金研究快照幂等保存");
```

测试至少覆盖：

```java
@Test
@DisplayName("首次保存创建正式快照")
void createsSnapshot() {
    SaveGoldResearchSnapshotResult result =
            repository.saveIfAbsent(snapshot("2500.00"), CREATED_AT);

    assertThat(result.created()).isTrue();
    assertThat(result.record().snapshot().gold().currentPrice())
            .isEqualByComparingTo("2500.00");
}

@Test
@DisplayName("重复保存不覆盖第一次的历史数据")
void keepsFirstSnapshotForSameKey() {
    repository.saveIfAbsent(snapshot("2500.00"), CREATED_AT);

    SaveGoldResearchSnapshotResult repeated =
            repository.saveIfAbsent(snapshot("9999.00"), CREATED_AT.plusHours(1));

    assertThat(repeated.created()).isFalse();
    assertThat(repeated.record().snapshot().gold().currentPrice())
            .isEqualByComparingTo("2500.00");
    assertThat(repeated.record().createdAt()).isEqualTo(CREATED_AT);
}

@Test
@DisplayName("最近快照按分析日期倒序并限制数量")
void findsRecentSnapshots() {
    save(snapshotOn("2026-08-22", "gold-real-rate-test-v1"));
    save(snapshotOn("2026-08-25", "gold-real-rate-test-v1"));
    save(snapshotOn("2026-08-24", "gold-real-rate-test-v1"));

    assertThat(repository.findRecent(2))
            .extracting(record -> record.snapshot().analysisDate())
            .containsExactly(
                    LocalDate.parse("2026-08-25"),
                    LocalDate.parse("2026-08-24")
            );
}
```

测试数据使用专属规则版本 `gold-real-rate-test-v1`，并在 `@BeforeEach`、`@AfterEach` 中按该版本删除，避免污染真实记录。

- [ ] **步骤 2：运行测试并确认行为红灯**

运行：

```powershell
.\mvnw.cmd -Dtest=JdbcGoldResearchSnapshotRepositoryTests test
```

预期：编译成功，测试因 `UnsupportedOperationException` 失败。

- [ ] **步骤 3：兵哥实现 `saveIfAbsent` 核心逻辑**

实现顺序固定为：

1. 生成 `UUID id`；
2. 执行 `insert ... on conflict (analysis_date, rule_version) do nothing`；
3. 根据更新行数判断 `created`；
4. 按组合键查询数据库中的最终记录；
5. 返回 `new SaveGoldResearchSnapshotResult(record, created)`。

SQL 不允许出现 `do update`：

```sql
insert into gold_research_snapshot (
    id,
    analysis_date,
    latest_gold_date,
    latest_real_rate_date,
    gold_price,
    gold_return_1,
    gold_return_5,
    gold_return_20,
    gold_collected_at,
    real_rate,
    real_rate_change_1,
    real_rate_change_5,
    real_rate_change_20,
    real_rate_collected_at,
    assessment_status,
    rule_version,
    explanation,
    disclaimer,
    created_at
)
values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
on conflict (analysis_date, rule_version)
do nothing
```

状态写入时使用：

```java
snapshot.assessment().status().name().toLowerCase(Locale.ROOT)
```

读取时使用：

```java
RealRateFactorStatus.valueOf(
        resultSet.getString("assessment_status")
                .toUpperCase(Locale.ROOT)
)
```

基点变化从百分点变化重建：

```java
private BigDecimal toBasisPoints(BigDecimal percentagePoints) {
    return percentagePoints.multiply(new BigDecimal("100"));
}
```

- [ ] **步骤 4：兵哥运行定向测试**

```powershell
.\mvnw.cmd -Dtest=JdbcGoldResearchSnapshotRepositoryTests test
```

预期：3 个核心场景全部通过。若失败，把完整错误和当前方法贴给 Codex审查，不要绕过断言。

- [ ] **步骤 5：Codex 审查并补齐边界**

审查项：

- 19 个 SQL 参数顺序与列顺序完全一致；
- 重复保存没有更新任何字段；
- 查询 Mapper 能完整重建嵌套快照；
- `findRecent(0)` 和 `findRecent(101)` 抛出明确参数异常；
- 没有在日志中输出环境变量或凭据。

- [ ] **步骤 6：运行仓储与迁移回归并提交**

```powershell
.\mvnw.cmd "-Dtest=JdbcGoldResearchSnapshotRepositoryTests,GoldResearchSnapshotSchemaTests" test
git add backend/src/main/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepository.java backend/src/test/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepositoryTests.java
git commit -m "feat: 实现黄金研究快照幂等仓储"
git push origin master
```

---

### 任务 3：正式留痕服务与事务边界

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRecordingService.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRecordingServiceTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**接口：**

- 消费：`GoldResearchSnapshotService.createSnapshot()`。
- 消费：`GoldResearchSnapshotRepository.saveIfAbsent(snapshot, createdAt)`。
- 产生：`recordCurrentSnapshot()`。

- [ ] **步骤 1：编写服务行为测试**

```java
@Test
@DisplayName("生成当前快照后使用统一时钟正式留痕")
void recordsCurrentSnapshot() {
    when(snapshotService.createSnapshot()).thenReturn(SNAPSHOT);
    when(repository.saveIfAbsent(SNAPSHOT, CREATED_AT))
            .thenReturn(SAVE_RESULT);

    SaveGoldResearchSnapshotResult result =
            service.recordCurrentSnapshot();

    assertThat(result).isSameAs(SAVE_RESULT);
    verify(repository).saveIfAbsent(SNAPSHOT, CREATED_AT);
}
```

使用 `Clock.fixed(...)`，禁止在断言中依赖当前系统时间。

- [ ] **步骤 2：执行测试并确认行为红灯**

骨架完整可编译，`recordCurrentSnapshot()` 暂时抛出 `UnsupportedOperationException`。运行：

```powershell
.\mvnw.cmd -Dtest=GoldResearchSnapshotRecordingServiceTests test
```

预期：因未实现编排逻辑失败。

- [ ] **步骤 3：实现最小服务**

```java
/** 编排当前研究快照生成与不可变历史留痕，不负责指标计算。 */
@Service
public class GoldResearchSnapshotRecordingService {

    private final GoldResearchSnapshotService snapshotService;
    private final GoldResearchSnapshotRepository repository;
    private final Clock clock;

    public GoldResearchSnapshotRecordingService(
            GoldResearchSnapshotService snapshotService,
            GoldResearchSnapshotRepository repository,
            Clock clock
    ) {
        this.snapshotService = snapshotService;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public SaveGoldResearchSnapshotResult recordCurrentSnapshot() {
        GoldResearchSnapshot snapshot = snapshotService.createSnapshot();
        return repository.saveIfAbsent(
                snapshot,
                OffsetDateTime.now(clock)
        );
    }
}
```

直接复用 `MarketDataConfiguration.marketDataClock()` 提供的 UTC `Clock` Bean，不再创建第二个同类型 Bean，避免 Spring 按类型注入产生歧义。

- [ ] **步骤 4：验证 Spring 上下文和服务测试**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchSnapshotRecordingServiceTests,OpsPilotApplicationTests" test
```

预期：全部通过，且 `Clock`、仓储、留痕服务均可注入。

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRecordingService.java backend/src/test/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRecordingServiceTests.java backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java
git commit -m "feat: 编排黄金研究快照正式留痕"
git push origin master
```

---

### 任务 4：正式留痕与历史查询 API

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/history/InvalidResearchHistoryRequestException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/api/StoredGoldResearchSnapshotResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/api/SaveGoldResearchSnapshotResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotHistoryController.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotHistoryControllerTests.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- 修改：`backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**接口：**

- `post /api/research/gold/snapshots`：首次创建 201，重复请求 200。
- `get /api/research/gold/snapshots?limit=20`：返回最近历史。

- [ ] **步骤 1：编写 API 测试**

至少覆盖以下 HTTP 合同：

```java
mockMvc.perform(post("/api/research/gold/snapshots"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(jsonPath("$.record.id").value(ID.toString()))
        .andExpect(jsonPath("$.record.snapshot.analysisDate")
                .value("2026-08-24"));
```

```java
mockMvc.perform(get("/api/research/gold/snapshots")
                .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
```

```java
mockMvc.perform(get("/api/research/gold/snapshots")
                .param("limit", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code")
                .value("INVALID_RESEARCH_REQUEST"));
```

- [ ] **步骤 2：执行测试并确认行为红灯**

创建所有 DTO 和 Controller 骨架保证编译，方法暂时抛出 `UnsupportedOperationException`。运行：

```powershell
.\mvnw.cmd -Dtest=GoldResearchSnapshotHistoryControllerTests test
```

预期：HTTP 行为断言失败。

- [ ] **步骤 3：实现响应 DTO 和 Controller**

响应类型：

```java
/** 表示可通过 API 查询的正式黄金研究历史记录。 */
public record StoredGoldResearchSnapshotResponse(
        UUID id,
        GoldResearchSnapshotResponse snapshot,
        OffsetDateTime createdAt
) {
    public static StoredGoldResearchSnapshotResponse from(
            StoredGoldResearchSnapshot record
    ) {
        return new StoredGoldResearchSnapshotResponse(
                record.id(),
                GoldResearchSnapshotResponse.from(record.snapshot()),
                record.createdAt()
        );
    }
}
```

```java
/** 表示正式快照写入结果，created=false 代表命中已有记录。 */
public record SaveGoldResearchSnapshotResponse(
        boolean created,
        StoredGoldResearchSnapshotResponse record
) {
}
```

Controller 使用 `ResponseEntity.status(HttpStatus.CREATED)` 或 `ResponseEntity.ok(...)` 区分结果；查询前显式校验 `limit` 的 1 到 100 范围。

- [ ] **步骤 4：增加稳定异常映射**

```java
/** 表示历史快照查询参数不符合公开 API 约束。 */
public class InvalidResearchHistoryRequestException
        extends RuntimeException {

    public InvalidResearchHistoryRequestException(String message) {
        super(message);
    }
}
```

`GlobalExceptionHandler` 返回：

```java
new ApiError("INVALID_RESEARCH_REQUEST", exception.getMessage())
```

HTTP 状态为 400。

- [ ] **步骤 5：运行 API 与异常测试**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchSnapshotHistoryControllerTests,GlobalExceptionHandlerTests,GoldResearchControllerTests" test
```

预期：新接口通过，原预览接口保持通过。

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/api backend/src/main/java/com/opspilot/ai/analysis/history/InvalidResearchHistoryRequestException.java backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotHistoryControllerTests.java backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git commit -m "feat: 开放黄金研究快照历史接口"
git push origin master
```

---

### 任务 5：真实数据库端到端验收

**文件：**

- 不新增生产文件；仅在发现真实缺陷时修改对应组件并补回归测试。

**接口：**

- 验证真实 PostgreSQL、真实已同步黄金数据和真实 FRED 数据形成完整闭环。

- [ ] **步骤 1：运行回归测试**

```powershell
.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test
```

预期：0 failures、0 errors；外部实时测试允许按环境条件 skipped。

- [ ] **步骤 2：加载用户级环境变量并启动应用**

```powershell
$env:OPSPILOT_DB_PASSWORD = [Environment]::GetEnvironmentVariable(
    "OPSPILOT_DB_PASSWORD",
    "User"
)
$env:FRED_API_KEY = [Environment]::GetEnvironmentVariable(
    "FRED_API_KEY",
    "User"
)
$env:ALPHA_VANTAGE_API_KEY = [Environment]::GetEnvironmentVariable(
    "ALPHA_VANTAGE_API_KEY",
    "User"
)
.\mvnw.cmd spring-boot:run
```

- [ ] **步骤 3：首次正式留痕**

```powershell
curl.exe --silent --show-error --fail-with-body `
    -X POST `
    -D - `
    http://localhost:8080/api/research/gold/snapshots
```

预期：HTTP 201，`created=true`，响应包含真实分析日期、规则版本和数据采集时间。

- [ ] **步骤 4：重复调用验证幂等**

再次执行相同命令。

预期：HTTP 200，`created=false`，`record.id` 与第一次完全相同。

- [ ] **步骤 5：查询历史记录**

```powershell
curl.exe --silent --show-error --fail-with-body `
    "http://localhost:8080/api/research/gold/snapshots?limit=20"
```

预期：返回数组，刚才记录出现一次，中文解释正常显示。

- [ ] **步骤 6：检查 Git 边界与远端同步**

```powershell
git diff --check
git status --short
git rev-parse HEAD
git rev-parse origin/master
```

只允许以下既有未跟踪文件继续存在：

```text
2026-07-21-opspilot-ai-learning-project-design.md
backend/src/main/java/com/opspilot/ai/document/DocumentLifecycleService.java
backend/src/main/java/com/opspilot/ai/document/DocumentNotFoundException.java
backend/src/test/java/com/opspilot/ai/document/DocumentLifecycleServiceTests.java
```

若端到端验收发现缺陷，先补能复现该缺陷的测试，再修复、回归、提交并推送；若无代码变化，不创建空提交。
