# 黄金研究快照接入广义美元指数因子实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 将真实 `DTWEXBGS` 广义美元指数作为第二个确定性因子接入黄金研究快照、历史留痕和 HTTP 响应。

**架构：** 三类数据按共同观测日期对齐，实际利率和美元指数分别计算指标并独立评估，不合成涨跌预测。使用 `V5` 迁移兼容旧单因子快照，新快照以独立研究版本实现幂等保存。

**技术栈：** Java 21、Spring Boot 3.5、Spring JDBC、PostgreSQL 17、Flyway、JUnit 5、AssertJ、Mockito、MockMvc

**规格：** `docs/superpowers/specs/2026-08-27-gold-dollar-index-factor-design.md`

## 全局约束

- 美元数据固定使用 FRED `DTWEXBGS`，名称为“广义美元指数”，不得写成 ICE DXY。
- 只使用数据库中的真实 `XAUUSD`、`DFII10`、`DTWEXBGS`，不插值、不补值、不生成假行情。
- 本阶段不调用大模型、不合成多空评分、不生成价格预测或交易建议。
- Java 生产类、record、enum 和 exception 必须有简短中文类级 Javadoc。
- SQL 关键字、表名和字段名统一小写。
- 不使用缺类编译错误作为红灯；测试骨架必须完整可编译。
- 不创建 worktree；直接在 `master` 分任务提交和推送。
- 不暂存现有无关设计文档、文档生命周期文件和 `SaveGoldResearchSnapshotResult.java` 的用户改动。
- 完整回归排除未完成测试：`.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test`。

---

### 任务 1：把单因子状态重构为通用黄金因子状态

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/GoldFactorStatus.java`
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/ResearchFactorAssessment.java`
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/RealRateFactorEvaluator.java`
- 修改：所有直接引用 `RealRateFactorStatus` 的生产代码和测试
- 删除：`backend/src/main/java/com/opspilot/ai/analysis/RealRateFactorStatus.java`

**接口：**

- 产生：`ResearchFactorAssessment(GoldFactorStatus status, String ruleVersion, String explanation)`
- 保持：数据库状态文本仍为 `pressuring`、`supportive`、`neutral`

- [ ] **步骤 1：运行重构前基线测试**

```powershell
.\mvnw.cmd "-Dtest=RealRateFactorEvaluatorTests,GoldResearchSnapshotServiceTests,JdbcGoldResearchSnapshotRepositoryTests,GoldResearchControllerTests" test
```

预期：现有单因子行为全部通过，作为类型重命名的比较基线。

- [ ] **步骤 2：一次性完成测试与生产类型引用改名**

```java
assertThat(assessment.status())
        .isEqualTo(GoldFactorStatus.PRESSURING);
```

同时修改 JDBC 仓储测试，确保数据库小写状态仍能还原为通用枚举。

同一步创建通用枚举并修改所有生产引用：

```java
/** 表示单个研究因子对黄金形成的压力、支撑或中性状态。 */
public enum GoldFactorStatus {
    PRESSURING,
    SUPPORTIVE,
    NEUTRAL
}
```

`ResearchFactorAssessment` 改为：

```java
/** 保存单因子状态、规则版本和可读解释。 */
public record ResearchFactorAssessment(
        GoldFactorStatus status,
        String ruleVersion,
        String explanation
) {
}
```

仓储还原状态时使用：

```java
GoldFactorStatus.valueOf(
        resultSet.getString("assessment_status")
                .toUpperCase(Locale.ROOT)
)
```

- [ ] **步骤 3：运行重构后定向回归**

```powershell
.\mvnw.cmd "-Dtest=RealRateFactorEvaluatorTests,GoldResearchSnapshotServiceTests,JdbcGoldResearchSnapshotRepositoryTests,GoldResearchControllerTests" test
```

预期：所有测试通过，HTTP JSON 中状态字符串仍保持大写枚举值。

- [ ] **步骤 4：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis backend/src/test/java/com/opspilot/ai/analysis
git commit -m "refactor: 统一黄金研究因子状态"
git push origin master
```

---

### 任务 2：新增美元指数指标和独立评估规则

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/DollarIndexChangeMetrics.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/DollarIndexFactorEvaluator.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/DollarIndexFactorEvaluatorTests.java`

**接口：**

- 产生：`ResearchFactorAssessment evaluate(BigDecimal return5, BigDecimal return20)`
- 产生：`DollarIndexChangeMetrics(currentIndex, return1, return5, return20, collectedAt)`

- [ ] **步骤 1：Codex 创建指标类型、评估器骨架和有效红灯测试**

```java
/** 保存广义美元指数当前值及不同周期的百分比变化。 */
public record DollarIndexChangeMetrics(
        BigDecimal currentIndex,
        BigDecimal return1,
        BigDecimal return5,
        BigDecimal return20,
        OffsetDateTime collectedAt
) {
}
```

评估器骨架完整可编译，`evaluate` 暂时抛出：

```java
throw new UnsupportedOperationException("请实现美元指数因子规则");
```

测试必须覆盖：

```java
assertThat(evaluator.evaluate(
        new BigDecimal("0.10"),
        new BigDecimal("1.00")
).status()).isEqualTo(GoldFactorStatus.PRESSURING);

assertThat(evaluator.evaluate(
        new BigDecimal("-0.10"),
        new BigDecimal("-1.00")
).status()).isEqualTo(GoldFactorStatus.SUPPORTIVE);

assertThat(evaluator.evaluate(
        new BigDecimal("-0.10"),
        new BigDecimal("1.20")
).status()).isEqualTo(GoldFactorStatus.NEUTRAL);
```

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd -Dtest=DollarIndexFactorEvaluatorTests test
```

预期：类型完整可编译，测试仅因 `UnsupportedOperationException` 失败。

- [ ] **步骤 3：兵哥实现美元指数方向规则**

方法中先用 `Objects.requireNonNull` 校验两个变化值，然后实现：

```java
if (return20.compareTo(new BigDecimal("1.0000")) >= 0
        && return5.compareTo(BigDecimal.ZERO) > 0) {
    return assessment(
            GoldFactorStatus.PRESSURING,
            "广义美元指数中期明显走强且短期继续走强，对黄金构成单因子压力。"
    );
}

if (return20.compareTo(new BigDecimal("-1.0000")) <= 0
        && return5.compareTo(BigDecimal.ZERO) < 0) {
    return assessment(
            GoldFactorStatus.SUPPORTIVE,
            "广义美元指数中期明显走弱且短期继续走弱，对黄金构成单因子支撑。"
    );
}

return assessment(
        GoldFactorStatus.NEUTRAL,
        "广义美元指数变化有限或长短周期方向不一致，单因子状态为中性。"
);
```

私有 `assessment` 固定写入规则版本 `gold-dollar-index-v1`。

- [ ] **步骤 4：审查并运行绿灯**

```powershell
.\mvnw.cmd "-Dtest=DollarIndexFactorEvaluatorTests,RealRateFactorEvaluatorTests" test
```

预期：美元和实际利率两个独立评估器全部通过。

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/DollarIndexChangeMetrics.java backend/src/main/java/com/opspilot/ai/analysis/DollarIndexFactorEvaluator.java backend/src/test/java/com/opspilot/ai/analysis/DollarIndexFactorEvaluatorTests.java
git commit -m "feat: 评估美元指数黄金影响"
git push origin master
```

---

### 任务 3：把美元指数接入确定性研究快照

**文件：**

- 修改：`backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshot.java`
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java`

**接口：**

- 消费：`MacroObservationRepository.findRecent("DTWEXBGS", 120)`
- 产生：包含 `latestDollarIndexDate`、`dollarIndex`、`realRateAssessment`、`dollarIndexAssessment`、`researchVersion` 的快照

- [ ] **步骤 1：扩展服务测试表达三方对齐合同**

测试准备 21 个三方共同日期，并额外给三个数据源各自增加不共同的更新日期。断言：

```java
assertThat(snapshot.analysisDate()).isEqualTo(ANALYSIS_DATE);
assertThat(snapshot.latestDollarIndexDate())
        .isEqualTo(LocalDate.parse("2026-08-25"));
assertThat(snapshot.dollarIndex().currentIndex())
        .isEqualByComparingTo("120.00");
assertThat(snapshot.dollarIndex().return20())
        .isEqualByComparingTo("2.5641");
assertThat(snapshot.realRateAssessment().status())
        .isEqualTo(GoldFactorStatus.PRESSURING);
assertThat(snapshot.dollarIndexAssessment().status())
        .isEqualTo(GoldFactorStatus.PRESSURING);
assertThat(snapshot.researchVersion())
        .isEqualTo("gold-multifactor-v2");
```

增加空美元数据、重复美元日期、非正美元值、三方共同日期不足测试。

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd -Dtest=GoldResearchSnapshotServiceTests test
```

预期：测试因快照尚无美元字段或服务尚未查询 `DTWEXBGS` 而失败。

- [ ] **步骤 3：扩展快照模型和服务编排**

快照合同改为：

```java
public record GoldResearchSnapshot(
        LocalDate analysisDate,
        LocalDate latestGoldDate,
        LocalDate latestRealRateDate,
        LocalDate latestDollarIndexDate,
        GoldReturnMetrics gold,
        RealRateChangeMetrics realRate,
        DollarIndexChangeMetrics dollarIndex,
        ResearchFactorAssessment realRateAssessment,
        ResearchFactorAssessment dollarIndexAssessment,
        String researchVersion,
        String disclaimer
) {
}
```

服务新增常量：

```java
private static final String DOLLAR_INDEX_SERIES_ID = "DTWEXBGS";
private static final String RESEARCH_VERSION = "gold-multifactor-v2";
```

三方共同日期计算：

```java
List<LocalDate> commonDates = goldByDate.keySet().stream()
        .filter(realRateByDate::containsKey)
        .filter(dollarIndexByDate::containsKey)
        .sorted(Comparator.reverseOrder())
        .toList();
```

美元变化复用黄金相对收益公式，结果保留 4 位小数。实际利率仍使用百分点与基点差值。

- [ ] **步骤 4：运行服务与评估器回归**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchSnapshotServiceTests,DollarIndexFactorEvaluatorTests,RealRateFactorEvaluatorTests" test
```

预期：三方对齐、指标计算、两个独立评估和错误边界全部通过。

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java
git commit -m "feat: 构建黄金双因子研究快照"
git push origin master
```

---

### 任务 4：迁移并兼容双因子历史留痕

**文件：**

- 新建：`backend/src/main/resources/db/migration/V5__extend_gold_research_snapshot_with_dollar_index.sql`
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepository.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotSchemaTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepositoryTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRecordingServiceTests.java`

**接口：**

- 幂等键：`(analysis_date, research_version)`
- 兼容：旧 `gold-real-rate-v1` 记录的美元字段为空
- 保证：新 `gold-multifactor-v2` 记录的美元字段必须全部存在

- [ ] **步骤 1：编写迁移和仓储红灯测试**

Schema 测试检查 `research_version`、`real_rate_rule_version`、`latest_dollar_index_date`、`dollar_index`、三期变化、采集时间、状态、规则版本和解释列。

仓储测试必须覆盖：

```java
SaveGoldResearchSnapshotResult first = repository.saveIfAbsent(snapshot, now);
SaveGoldResearchSnapshotResult second = repository.saveIfAbsent(snapshot, now.plusSeconds(1));

assertThat(first.created()).isTrue();
assertThat(second.created()).isFalse();
assertThat(second.record().snapshot()).isEqualTo(first.record().snapshot());
```

并用 SQL 插入一条旧单因子记录，断言读回时 `dollarIndex()` 和 `dollarIndexAssessment()` 为 `null`。

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchSnapshotSchemaTests,JdbcGoldResearchSnapshotRepositoryTests,GoldResearchSnapshotRecordingServiceTests" test
```

预期：迁移列不存在或 JDBC 尚未映射美元字段而失败。

- [ ] **步骤 3：Codex 编写 V5 迁移**

迁移核心语句：

```sql
alter table gold_research_snapshot
    rename column rule_version to research_version;

alter table gold_research_snapshot
    add column real_rate_rule_version varchar(64),
    add column latest_dollar_index_date date,
    add column dollar_index numeric(18, 6),
    add column dollar_index_return_1 numeric(12, 4),
    add column dollar_index_return_5 numeric(12, 4),
    add column dollar_index_return_20 numeric(12, 4),
    add column dollar_index_collected_at timestamptz,
    add column dollar_index_status varchar(32),
    add column dollar_index_rule_version varchar(64),
    add column dollar_index_explanation varchar(500);

update gold_research_snapshot
set real_rate_rule_version = research_version;

alter table gold_research_snapshot
    alter column real_rate_rule_version set not null;
```

增加美元状态约束，以及 `research_version = 'gold-multifactor-v2'` 时美元字段全部非空、旧版本时允许全部为空的成组完整性约束。

- [ ] **步骤 4：Codex 完成 JDBC 写入和兼容读取**

写入新记录时分别保存研究版本、两个规则版本和美元指标。读取时先判断 `latest_dollar_index_date` 是否为空；为空则两个美元对象都映射为 `null`，不为空则要求所有美元字段均非空并完整构造对象。

- [ ] **步骤 5：运行迁移和仓储绿灯**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchSnapshotSchemaTests,JdbcGoldResearchSnapshotRepositoryTests,GoldResearchSnapshotRecordingServiceTests" test
```

预期：迁移、旧数据兼容、新数据回读和幂等全部通过。

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/resources/db/migration/V5__extend_gold_research_snapshot_with_dollar_index.sql backend/src/main/java/com/opspilot/ai/analysis/history backend/src/test/java/com/opspilot/ai/analysis/history
git commit -m "feat: 留痕黄金双因子研究快照"
git push origin master
```

---

### 任务 5：升级当前快照与历史 HTTP 合同

**文件：**

- 修改：`backend/src/main/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotResponse.java`
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/api/StoredGoldResearchSnapshotResponse.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/api/GoldResearchControllerTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotHistoryControllerTests.java`

**接口：**

- 当前：`get /api/research/gold/snapshot`
- 留痕：`post /api/research/gold/snapshots`
- 历史：`get /api/research/gold/snapshots?limit=20`

- [ ] **步骤 1：编写 HTTP 红灯合同**

当前快照和新历史记录断言：

```java
.andExpect(jsonPath("$.latestDollarIndexDate").value("2026-08-21"))
.andExpect(jsonPath("$.dollarIndex.currentIndex").value(118.0628))
.andExpect(jsonPath("$.realRateAssessment.status").value("PRESSURING"))
.andExpect(jsonPath("$.dollarIndexAssessment.status").value("SUPPORTIVE"))
.andExpect(jsonPath("$.researchVersion").value("gold-multifactor-v2"))
.andExpect(jsonPath("$.assessment").doesNotExist());
```

旧历史记录断言 `dollarIndex` 和 `dollarIndexAssessment` 为 JSON `null`，`researchVersion` 为 `gold-real-rate-v1`。

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchControllerTests,GoldResearchSnapshotHistoryControllerTests" test
```

预期：响应尚未包含新字段或仍返回旧 `assessment` 字段而失败。

- [ ] **步骤 3：更新 DTO 映射**

`GoldResearchSnapshotResponse` 与历史响应增加：

```java
LocalDate latestDollarIndexDate,
DollarIndexChangeMetrics dollarIndex,
ResearchFactorAssessment realRateAssessment,
ResearchFactorAssessment dollarIndexAssessment,
String researchVersion
```

删除含义模糊的 `assessment` 字段。DTO 只映射领域对象，不重新计算指标。

- [ ] **步骤 4：运行 HTTP 与异常回归**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchControllerTests,GoldResearchSnapshotHistoryControllerTests,GlobalExceptionHandlerTests" test
```

预期：当前快照、新旧历史记录和原有 422 异常语义全部通过。

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/api backend/src/test/java/com/opspilot/ai/analysis/api
git commit -m "feat: 开放黄金双因子研究结果"
git push origin master
```

---

### 任务 6：真实数据端到端验收

- [ ] **步骤 1：运行完整回归**

```powershell
.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test
```

预期：0 failure、0 error；实时测试只在对应环境变量存在时运行。

- [ ] **步骤 2：启动应用**

```powershell
$env:FRED_API_KEY = [Environment]::GetEnvironmentVariable("FRED_API_KEY", "User")
$env:OPSPILOT_DB_PASSWORD = [Environment]::GetEnvironmentVariable("OPSPILOT_DB_PASSWORD", "User")
$env:ZHIPU_API_KEY = [Environment]::GetEnvironmentVariable("ZHIPU_API_KEY", "User")
.\mvnw.cmd spring-boot:run
```

- [ ] **步骤 3：确保三类真实数据已同步**

```powershell
curl.exe --silent --show-error --fail-with-body -X POST http://localhost:8080/api/market-data/gold/daily/sync
curl.exe --silent --show-error --fail-with-body -X POST http://localhost:8080/api/macro-data/real-rate/sync
curl.exe --silent --show-error --fail-with-body -X POST http://localhost:8080/api/macro-data/dollar-index/sync
```

- [ ] **步骤 4：创建并核对真实双因子快照**

```powershell
curl.exe --silent --show-error --fail-with-body http://localhost:8080/api/research/gold/snapshot
curl.exe --silent --show-error --fail-with-body -X POST http://localhost:8080/api/research/gold/snapshots
curl.exe --silent --show-error --fail-with-body -X POST http://localhost:8080/api/research/gold/snapshots
curl.exe --silent --show-error --fail-with-body "http://localhost:8080/api/research/gold/snapshots?limit=5"
```

验收：三方日期来自真实数据库；两个因子独立返回；第二次记录 `created=false` 且 ID 与第一次一致；响应没有预测和交易建议。

- [ ] **步骤 5：检查安全与 Git 边界**

```powershell
git diff --check
git status --short
git rev-parse HEAD
git rev-parse origin/master
```

确认提交差异不包含 API Key、数据库密码、根目录设计文档、文档生命周期未完成文件和 `SaveGoldResearchSnapshotResult.java` 用户改动。
