# 黄金回测跨历史时间分层抽样实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建回测任务时从全部真实黄金历史日期中等距抽样并永久冻结计划，使执行、恢复和评估覆盖长期行情且不受新增数据影响。

**Architecture:** 使用 V10 样本计划表保存任务与预测日期的固定顺序，任务创建与计划写入处于同一事务。独立 `BacktestDateSelector` 只按历史日期位置抽样；创建服务负责冻结计划，执行器只消费计划，不重新推算日期。

**Tech Stack:** Java 21、Spring Boot 3.5.14、Spring JDBC、PostgreSQL 17、Flyway、JUnit 5、Mockito、AssertJ、MockMvc

**Spec:** `docs/superpowers/specs/2026-08-28-gold-backtest-stratified-sampling-design.md`

## 全局约束

- 抽样不得读取下一交易日涨跌方向、新闻、模型预测或命中结果。
- 最早 20 个交易日只用于计算指标，最新 1 个交易日只用于结算。
- 新任务的样本日期创建后不得因新增行情或重新启动而改变。
- 已完成的 5 条真实回测保持原样，不删除、不修改。
- 新增生产类、record、exception、service、controller、configuration 必须有简短中文类级 Javadoc。
- Java 方法和变量使用简短清晰名称；特殊逻辑使用简短中文注释。
- SQL 关键字、表名和字段名统一小写。
- 普通测试不得产生真实 GLM 调用；真实批量运行必须再次取得兵哥明确确认。
- 保留现有未跟踪的文档模块文件，不纳入本功能提交。

---

### 任务 1：建立冻结样本计划的数据库边界

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__create_gold_forecast_backtest_sample.sql`
- Modify: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestRepository.java`
- Modify: `backend/src/main/java/com/opspilot/ai/forecast/backtest/JdbcBacktestRepository.java`
- Modify: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestSchemaTests.java`
- Modify: `backend/src/test/java/com/opspilot/ai/forecast/backtest/JdbcBacktestRepositoryTests.java`

**Interfaces:**
- Produces: `BacktestTask create(BacktestTask task, List<LocalDate> dates)`
- Produces: `List<LocalDate> findSampleDates(UUID id)`，按 `position` 升序返回。
- Consumes: 现有任务表、明细表、PostgreSQL datasource 和 Flyway。

- [ ] **Step 1: 扩展数据库红灯测试**

在 `BacktestSchemaTests` 断言 `gold_forecast_backtest_sample` 存在，并检查 `(backtest_id, as_of_date)`、`(backtest_id, position)` 两个唯一约束。运行：

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -q "-Dtest=BacktestSchemaTests" test
```

预期：FAIL，样本计划表数量为 `0`。

- [ ] **Step 2: 创建 V10 迁移**

迁移核心结构：

```sql
create table gold_forecast_backtest_sample (
    backtest_id uuid not null references gold_forecast_backtest(id) on delete cascade,
    position integer not null check (position > 0),
    as_of_date date not null,
    primary key (backtest_id, position),
    unique (backtest_id, as_of_date)
);

insert into gold_forecast_backtest_sample (backtest_id, position, as_of_date)
select backtest_id,
       row_number() over (partition by backtest_id order by as_of_date),
       as_of_date
from gold_forecast_backtest_case
on conflict do nothing;
```

重新运行 `BacktestSchemaTests`，预期 PASS。

- [ ] **Step 3: 写仓储红灯测试**

修改创建测试为：

```java
BacktestTask created = repo.create(
        task,
        List.of(LocalDate.parse("2026-08-19"), LocalDate.parse("2026-08-20"))
);
assertThat(repo.findSampleDates(created.id())).containsExactly(
        LocalDate.parse("2026-08-19"),
        LocalDate.parse("2026-08-20")
);
```

同时增加事务测试：重复日期触发唯一约束后，任务表也不得留下半条记录。

- [ ] **Step 4: 实现事务创建和计划查询**

`JdbcBacktestRepository.create` 标记 `@Transactional`，先写任务，再使用 `batchUpdate` 按列表索引写入 `position=index+1`。`findSampleDates` 使用：

```sql
select as_of_date
from gold_forecast_backtest_sample
where backtest_id = ?
order by position
```

运行：

```powershell
.\mvnw.cmd -q "-Dtest=BacktestSchemaTests,JdbcBacktestRepositoryTests" test
```

预期：全部通过，且已完成的 5 条历史任务由迁移回填 5 个日期。

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main/resources/db/migration/V10__create_gold_forecast_backtest_sample.sql `
        backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestRepository.java `
        backend/src/main/java/com/opspilot/ai/forecast/backtest/JdbcBacktestRepository.java `
        backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestSchemaTests.java `
        backend/src/test/java/com/opspilot/ai/forecast/backtest/JdbcBacktestRepositoryTests.java
git commit -m "feat: 冻结黄金回测样本计划"
```

---

### 任务 2：读取完整黄金历史日期

**Files:**
- Modify: `backend/src/main/java/com/opspilot/ai/marketdata/MarketPriceRepository.java`
- Modify: `backend/src/main/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepository.java`
- Modify: `backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java`

**Interfaces:**
- Produces: `List<MarketPrice> findAll(String symbol)`，按 `price_date` 升序返回。
- Consumes: `market_price` 表和现有 `MarketPrice` 行映射。

- [ ] **Step 1: 写完整历史查询红灯测试**

保存三条乱序日期，断言：

```java
assertThat(repository.findAll(TEST_SYMBOL))
        .extracting(MarketPrice::priceDate)
        .containsExactly(
                LocalDate.parse("2026-08-18"),
                LocalDate.parse("2026-08-19"),
                LocalDate.parse("2026-08-20")
        );
```

运行 `JdbcMarketPriceRepositoryTests`，预期新行为测试失败。

- [ ] **Step 2: 实现无上限升序查询**

JDBC SQL：

```sql
select <现有统一字段>
from market_price
where symbol = ?
order by price_date
```

不得改变现有 `findRecent`、`findAfter` 的行为。

- [ ] **Step 3: 验证并提交**

```powershell
.\mvnw.cmd -q "-Dtest=JdbcMarketPriceRepositoryTests" test
git add backend/src/main/java/com/opspilot/ai/marketdata `
        backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java
git commit -m "feat: 读取完整黄金历史价格"
```

---

### 任务 3：实现确定性时间分层选择器

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestDateSelector.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestDateSelectorTests.java`

**Interfaces:**
- Produces: `List<LocalDate> select(List<MarketPrice> prices, int samples)`。
- Consumes: 任务 2 的完整、真实黄金价格列表。

- [ ] **Step 1: 写选择器红灯测试**

覆盖以下独立行为：

```java
assertThat(selector.select(prices(101), 5)).containsExactly(
        dateAt(20), dateAt(40), dateAt(60), dateAt(79), dateAt(99)
);
assertThat(selector.select(prices(101), 1)).containsExactly(dateAt(59));
```

另测乱序输入、重复日期、少于 `samples + 21` 个唯一日期、`samples` 超出 `1..120`。

- [ ] **Step 2: 实现日期清洗和等距索引**

先按日期去重升序，再取：

```java
List<LocalDate> eligible = dates.subList(20, dates.size() - 1);
```

单样本使用 `(eligible.size() - 1) / 2`。多样本第 `index` 个位置使用：

```java
int position = (int) Math.round(
        index * (eligible.size() - 1D) / (samples - 1D)
);
```

返回不可修改列表；异常消息包含需要数量和实际唯一日期数量。

- [ ] **Step 3: 验证并提交**

```powershell
.\mvnw.cmd -q "-Dtest=BacktestDateSelectorTests" test
git add backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestDateSelector.java `
        backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestDateSelectorTests.java
git commit -m "feat: 跨历史时间分层选择回测日期"
```

---

### 任务 4：创建任务时冻结计划，执行时只消费计划

**Files:**
- Modify: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestService.java`
- Modify: `backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestRunner.java`
- Modify: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestServiceTests.java`
- Modify: `backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestRunnerTests.java`

**Interfaces:**
- Consumes: `MarketPriceRepository.findAll`、`BacktestDateSelector.select`、`BacktestRepository.create(task, dates)`、`findSampleDates`。
- Produces: `List<LocalDate> samples(UUID id)`，不存在任务仍抛 `BacktestNotFoundException`。

- [ ] **Step 1: 修改任务创建红灯测试**

模拟 `priceRepo.findAll("XAUUSD")` 和选择器返回跨年日期，断言创建参数中的 `startDate`、`endDate` 来自冻结计划，并验证：

```java
verify(repo).create(taskCaptor.capture(), eq(selectedDates));
```

增加 `service.samples(id)` 顺序返回计划，以及任务不存在时不查询计划的测试。

- [ ] **Step 2: 修改执行器红灯测试**

仓储计划返回三个日期，完成集合包含中间日期。断言模型只调用剩余两个日期；即使 `MarketPriceRepository` 中存在更新日期，执行器也不调用行情仓储重新选择日期。

- [ ] **Step 3: 实现创建和查询计划**

`BacktestService.create` 改为：

```java
List<LocalDate> dates = selector.select(priceRepo.findAll("XAUUSD"), samples);
BacktestTask task = newTask(dates, samples);
return repo.create(task, dates);
```

删除旧的私有 `selectDates`。`samples(id)` 先调用 `get(id)`，再返回 `repo.findSampleDates(id)`。

- [ ] **Step 4: 实现执行器只消费冻结计划**

`BacktestRunner.run` 读取 `repo.findSampleDates(id)`；若数量不等于 `task.sampleCount()`，调用 `repo.fail` 写入“冻结样本计划不完整”并停止。循环计划日期，继续使用 `doneDates` 跳过已完成日期。

- [ ] **Step 5: 验证并提交**

```powershell
.\mvnw.cmd -q "-Dtest=BacktestServiceTests,BacktestRunnerTests" test
git add backend/src/main/java/com/opspilot/ai/forecast/backtest `
        backend/src/test/java/com/opspilot/ai/forecast/backtest
git commit -m "feat: 按冻结日期执行黄金历史回测"
```

---

### 任务 5：提供可见的冻结样本日期接口

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestSampleResponse.java`
- Modify: `backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestController.java`
- Modify: `backend/src/test/java/com/opspilot/ai/forecast/backtest/api/BacktestControllerTests.java`

**Interfaces:**
- Produces: `GET /api/research/gold/backtests/{id}/samples`
- Response: `List<BacktestSampleResponse>`，字段为 `position`、`asOfDate`。

- [ ] **Step 1: 写 HTTP 红灯测试**

模拟三条跨年日期并断言：

```java
mockMvc.perform(get("/api/research/gold/backtests/{id}/samples", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].position").value(1))
        .andExpect(jsonPath("$[0].asOfDate").value("2012-01-03"))
        .andExpect(jsonPath("$[2].asOfDate").value("2026-08-25"));
```

- [ ] **Step 2: 实现响应 DTO 和控制器**

```java
/** 返回冻结回测样本的执行顺序和历史日期。 */
public record BacktestSampleResponse(int position, LocalDate asOfDate) {
}
```

控制器把 `service.samples(id)` 按索引转换为从 1 开始的响应列表，不返回未来结算价格和真实方向。

- [ ] **Step 3: 验证并提交**

```powershell
.\mvnw.cmd -q "-Dtest=BacktestControllerTests" test
git add backend/src/main/java/com/opspilot/ai/forecast/backtest/api `
        backend/src/test/java/com/opspilot/ai/forecast/backtest/api/BacktestControllerTests.java
git commit -m "feat: 查询黄金回测冻结样本日期"
```

---

### 任务 6：文档、全量回归和零费用验收

**Files:**
- Modify: `README.md`
- Test: 全部非 Live 测试。

**Interfaces:**
- Consumes: 任务 1 至 5 的完整功能。
- Produces: 可创建和查看跨历史样本，但不会自动运行 GLM 的稳定版本。

- [ ] **Step 1: 更新 README**

在创建任务后增加：

```powershell
$samples = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8080/api/research/gold/backtests/$id/samples"

$samples | Format-Table position, asOfDate
```

说明日期按完整历史等距分层且已永久冻结；查看样本不调用模型。

- [ ] **Step 2: 执行全量非 Live 回归**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -q clean "-Dtest=!*LiveTests,!DocumentLifecycleServiceTests" test
```

预期：Maven 退出码 `0`，Surefire 汇总 `failures=0`、`errors=0`，日志中没有真实 GLM 请求。

- [ ] **Step 3: 验证真实数据库抽样但不启动任务**

启动应用后只调用创建 5 条任务和 `/samples`，断言首尾日期跨越多年。不得调用 `/{id}/run`。

- [ ] **Step 4: 检查差异并提交**

```powershell
cd D:\workFile\demo-ai
git diff --check
git status --short
git add README.md
git commit -m "docs: 补充黄金回测分层样本说明"
git push origin master
```

只提交本计划涉及文件，不暂存现有未跟踪文档模块文件。

- [ ] **Step 5: 停在真实调用闸门**

报告新任务编号、冻结样本日期跨度和预计 GLM 调用次数。未取得兵哥新的明确确认前，不调用 `post /api/research/gold/backtests/{id}/run`。
