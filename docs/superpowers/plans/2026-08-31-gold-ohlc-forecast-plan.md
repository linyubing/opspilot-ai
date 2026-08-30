# 黄金 OHLC 预测口径改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 让正式预测、结算和历史回测统一使用 Twelve Data 的真实 `XAU/USD` OHLC 日线，并通过无未来数据泄漏的回测重新评估准确率。

**架构：** `GoldDailyBarRepository` 是黄金正式价格的唯一读取边界；研究快照使用收盘价和 OHLC 特征，预测结算读取下一根真实日线。旧 `MarketPrice` 链路保留但不再进入新预测和新回测，页面明确显示价格口径与数据来源。

**技术栈：** Java 21、Spring Boot 3.5、Spring JDBC、PostgreSQL 17、JUnit 5、Mockito、AssertJ、MockMvc、原生 HTML/CSS/JavaScript。

**设计：** `docs/superpowers/specs/2026-08-31-gold-ohlc-forecast-design.md`

## 全局约束

- 只使用真实 `XAU/USD` OHLC，不生成、不插值、不补造黄金行情。
- 正式价格提供方固定为 `twelve_data`，正式品种固定为 `XAUUSD`。
- 预测基准价、目标价和收益率统一使用 `close`。
- 周末报价不作为黄金交易日；下一交易日由数据库下一根真实日线决定。
- 缺少真实数据时明确失败或保持待结算，不回退到 `MarketPrice.referencePrice`。
- 回测严格按时间切片，样本只能读取基准日及之前的数据。
- Java 新生产类型使用简短中文类级 Javadoc；方法名和变量名简短清晰。
- SQL 关键字、表名和字段名统一小写。
- 不修改或提交现有未完成的 document 模块文件和根目录 `.env.example`。
- 每个任务先看到红灯，再写最小实现，相关测试通过后独立提交并推送。

---

### 任务 1：扩展真实黄金日线查询边界

**文件：**
- 修改：`backend/src/main/java/com/opspilot/ai/marketdata/GoldDailyBarRepository.java`
- 修改：`backend/src/main/java/com/opspilot/ai/marketdata/JdbcGoldDailyBarRepository.java`
- 修改：`backend/src/test/java/com/opspilot/ai/marketdata/JdbcGoldDailyBarRepositoryTests.java`

**接口：**
- 产出：`List<GoldDailyBar> findRecent(String symbol, String provider, LocalDate endDate, int limit)`
- 产出：`List<GoldDailyBar> findAll(String symbol, String provider)`
- 产出：`Optional<GoldDailyBar> findNext(String symbol, String provider, LocalDate baseDate)`
- 后续任务只通过这三个方法读取正式黄金历史。

- [ ] **步骤 1：写仓储红灯测试**

```java
@Test
void readsRecentBarsWithoutFutureData() {
    repository.saveAll(List.of(
            bar("2026-08-27", "4400"),
            bar("2026-08-28", "4456"),
            bar("2026-09-01", "4500")
    ));

    List<GoldDailyBar> bars = repository.findRecent(
            "XAUUSD", "twelve_data", LocalDate.parse("2026-08-28"), 2
    );

    assertThat(bars).extracting(GoldDailyBar::priceDate)
            .containsExactly(
                    LocalDate.parse("2026-08-28"),
                    LocalDate.parse("2026-08-27")
            );
}

@Test
void readsNextRealTradingBar() {
    repository.saveAll(List.of(
            bar("2026-08-28", "4456"),
            bar("2026-09-01", "4500")
    ));

    GoldDailyBar next = repository.findNext(
            "XAUUSD", "twelve_data", LocalDate.parse("2026-08-28")
    ).orElseThrow();

    assertThat(next.priceDate()).isEqualTo("2026-09-01");
    assertThat(next.close()).isEqualByComparingTo("4500");
}
```

- [ ] **步骤 2：运行测试并确认因接口不存在而失败**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=JdbcGoldDailyBarRepositoryTests test
```

预期：编译失败，提示 `findRecent` 或 `findNext` 不存在。

- [ ] **步骤 3：实现最小查询**

```java
@Override
public List<GoldDailyBar> findRecent(
        String symbol, String provider, LocalDate endDate, int limit
) {
    return jdbc.query(
            "select " + COLUMNS + """
                    from gold_daily_bar
                    where symbol = ? and provider = ? and price_date <= ?
                    order by price_date desc
                    limit ?
                    """,
            mapper, symbol, provider, endDate, limit
    );
}

@Override
public Optional<GoldDailyBar> findNext(
        String symbol, String provider, LocalDate baseDate
) {
    return jdbc.query(
            "select " + COLUMNS + """
                    from gold_daily_bar
                    where symbol = ? and provider = ? and price_date > ?
                    order by price_date
                    limit 1
                    """,
            mapper, symbol, provider, baseDate
    ).stream().findFirst();
}
```

`findAll` 使用相同过滤条件并按 `price_date` 升序返回。

- [ ] **步骤 4：运行仓储测试**

```powershell
.\mvnw.cmd -Dtest=JdbcGoldDailyBarRepositoryTests test
```

预期：全部通过，且 PostgreSQL 查询没有未来日期泄漏。

- [ ] **步骤 5：提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/marketdata/GoldDailyBarRepository.java backend/src/main/java/com/opspilot/ai/marketdata/JdbcGoldDailyBarRepository.java backend/src/test/java/com/opspilot/ai/marketdata/JdbcGoldDailyBarRepositoryTests.java
git commit -m "feat: 扩展黄金OHLC日线查询"
git push
```

---

### 任务 2：研究快照改用真实收盘价

**文件：**
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java`
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/GoldVolatilityCalculator.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/GoldVolatilityCalculatorTests.java`

**接口：**
- 消费：任务 1 的 `GoldDailyBarRepository.findRecent(...)`。
- 产出：`GoldResearchSnapshot.gold().currentPrice()` 明确等于基准日 `close`。
- 产出：`GoldVolatilityCalculator.calculateBars(List<GoldDailyBar> bars)`。
- 保持现有 `GoldResearchSnapshot` API 结构，减少调用方迁移范围。

- [ ] **步骤 1：写快照红灯测试**

```java
@Test
void usesCloseInsteadOfOpenAsCurrentPrice() {
    when(bars.findRecent(eq("XAUUSD"), eq("twelve_data"), any(), eq(120)))
            .thenReturn(goldBars(21, "4601", "4456"));

    GoldResearchSnapshot snapshot = service.createSnapshot(
            LocalDate.parse("2026-08-28")
    );

    assertThat(snapshot.gold().currentPrice())
            .isEqualByComparingTo("4456");
}
```

`goldBars` 中最近日线的 `open=4601`、`close=4456`，防止测试在错误使用开盘价时仍然通过。

- [ ] **步骤 2：运行快照测试并确认失败**

```powershell
.\mvnw.cmd -Dtest=GoldResearchSnapshotServiceTests,GoldVolatilityCalculatorTests test
```

预期：构造器类型或断言失败，因为服务仍读取 `MarketPriceRepository`。

- [ ] **步骤 3：替换黄金读取依赖**

```java
private static final String GOLD_PROVIDER = "twelve_data";
private final GoldDailyBarRepository bars;

List<GoldDailyBar> gold = bars.findRecent(
        GOLD_SYMBOL, GOLD_PROVIDER, asOf, QUERY_LIMIT
);
```

把 `MarketPrice::priceDate` 改为 `GoldDailyBar::priceDate`，所有黄金收益计算改为 `bar.close()`。实际利率和美元指数仍读取基准日之前最近的真实宏观观测。

- [ ] **步骤 4：让波动率显式读取 close**

```java
public BigDecimal calculateBars(List<GoldDailyBar> bars) {
    List<BigDecimal> closes = bars.stream()
            .sorted(Comparator.comparing(GoldDailyBar::priceDate))
            .map(GoldDailyBar::close)
            .toList();
    return calculateValues(closes);
}
```

保留旧 `calculate(List<MarketPrice>)` 只供尚未迁移的旧功能使用，两个入口复用私有 `calculateValues`。

- [ ] **步骤 5：运行快照相关测试**

```powershell
.\mvnw.cmd -Dtest=GoldResearchSnapshotServiceTests,GoldVolatilityCalculatorTests,GoldForecastGenerationServiceTests test
```

预期：快照当前价格、1/5/20 日收益和波动率全部来自收盘价。

- [ ] **步骤 6：提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java backend/src/main/java/com/opspilot/ai/analysis/GoldVolatilityCalculator.java backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java backend/src/test/java/com/opspilot/ai/analysis/GoldVolatilityCalculatorTests.java
git commit -m "feat: 使用黄金收盘价生成研究快照"
git push
```

---

### 任务 3：预测结算改用下一根真实日线

**文件：**
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/GoldForecastResolutionService.java`
- 修改：`backend/src/test/java/com/opspilot/ai/forecast/GoldForecastResolutionServiceTests.java`
- 删除生产依赖：该服务中的 `NextValidMarketPriceSelector` 和 `MarketPriceRepository`。

**接口：**
- 消费：`GoldDailyBarRepository.findNext("XAUUSD", "twelve_data", baseDate)`。
- 产出：`ForecastResolution.targetPrice` 等于下一根日线的 `close`。
- 没有下一根日线时 `resolveOne` 返回 `false`，状态保持 `PENDING`。

- [ ] **步骤 1：写结算红灯测试**

```java
@Test
void resolvesWithNextBarClose() {
    when(bars.findNext("XAUUSD", "twelve_data", FRIDAY))
            .thenReturn(Optional.of(bar(TUESDAY, "4500")));

    service.resolvePending(10);

    ArgumentCaptor<ForecastResolution> captor =
            ArgumentCaptor.forClass(ForecastResolution.class);
    verify(forecasts).resolve(eq(ID), captor.capture());
    assertThat(captor.getValue().targetDate()).isEqualTo(TUESDAY);
    assertThat(captor.getValue().targetPrice()).isEqualByComparingTo("4500");
}
```

- [ ] **步骤 2：运行测试并确认旧价格依赖导致失败**

```powershell
.\mvnw.cmd -Dtest=GoldForecastResolutionServiceTests test
```

- [ ] **步骤 3：实现 OHLC 结算**

```java
Optional<GoldDailyBar> target = bars.findNext(
        GOLD_SYMBOL, GOLD_PROVIDER, forecast.baseDate()
);
if (target.isEmpty()) return false;

BigDecimal targetClose = target.get().close();
BigDecimal actualReturn = calculateReturn(
        forecast.basePrice(), targetClose
);
```

`ForecastResolution.targetDate` 使用 `bar.priceDate()`，`targetPrice` 使用 `bar.close()`。

- [ ] **步骤 4：运行结算和评估测试**

```powershell
.\mvnw.cmd -Dtest=GoldForecastResolutionServiceTests,GoldForecastEvaluationServiceTests test
```

- [ ] **步骤 5：提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast/GoldForecastResolutionService.java backend/src/test/java/com/opspilot/ai/forecast/GoldForecastResolutionServiceTests.java
git commit -m "feat: 使用黄金收盘价结算预测"
git push
```

---

### 任务 4：历史回测统一使用 OHLC 收盘口径

**文件：**
- 创建：`backend/src/main/resources/db/migration/V13__add_backtest_price_basis.sql`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestService.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestTask.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/JdbcBacktestRepository.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestDateSelector.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestRunner.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/HistoricalHorizonDiagnosticService.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/HorizonDiagnosticService.java`
- 修改：`backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestServiceTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestDateSelectorTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestRunnerTests.java`
- 修改：对应两个 Horizon 测试类。

**接口：**
- 消费：`GoldDailyBarRepository.findAll(...)` 生成样本日期。
- 消费：`GoldDailyBarRepository.findNext(...)` 结算每个样本。
- 产出：`BacktestDateSelector.selectBars(List<GoldDailyBar> bars, int samples, BacktestSampleSet sampleSet)`。
- 产出：`BacktestTask.priceBasis="OHLC_CLOSE"`；历史任务迁移为 `LEGACY_REFERENCE`。
- 产出：新 `BacktestCase.basePrice` 与 `targetPrice` 都是 `close`。
- 产出：所有 horizon 诊断使用后续第 N 根真实日线，而不是自然日。

- [ ] **步骤 1：写回测红灯测试**

```java
@Test
void settlesCaseWithNextOhlcClose() {
    when(bars.findNext("XAUUSD", "twelve_data", BASE_DATE))
            .thenReturn(Optional.of(bar(TARGET_DATE, "4456")));

    runner.run(TASK_ID);

    ArgumentCaptor<BacktestCase> captor =
            ArgumentCaptor.forClass(BacktestCase.class);
    verify(repo).saveCase(captor.capture());
    assertThat(captor.getValue().targetPrice())
            .isEqualByComparingTo("4456");
}
```

- [ ] **步骤 2：运行四组回测测试并确认失败**

```powershell
.\mvnw.cmd '-Dtest=BacktestServiceTests,BacktestRunnerTests,HorizonDiagnosticServiceTests,HistoricalHorizonDiagnosticServiceTests' test
```

- [ ] **步骤 3：替换样本和结算价格来源**

先增加价格口径字段，保留旧任务的真实含义：

```sql
alter table backtest_task
    add column price_basis varchar(32) not null
    default 'LEGACY_REFERENCE';

alter table backtest_task
    alter column price_basis drop default;
```

```java
List<GoldDailyBar> bars = barRepo.findAll(SYMBOL, PROVIDER);
List<LocalDate> dates = selector.selectBars(bars, samples, sampleSet);
```

`BacktestDateSelector.selectBars` 只把按日期升序的真实日线转换为候选日期，不插入周末。`BacktestRunner` 使用任务 3 相同的下一日线规则和收益率公式。

- [ ] **步骤 4：迁移 horizon 诊断**

```java
List<GoldDailyBar> future = barsAfter(baseDate);
BigDecimal target = future.get(sessions - 1).close();
```

当 `future.size() < sessions` 时跳过样本并计入数据不足，不使用最后一条价格代替。

- [ ] **步骤 5：运行回测相关回归**

```powershell
.\mvnw.cmd '-Dtest=BacktestServiceTests,BacktestRunnerTests,HorizonDiagnosticServiceTests,HistoricalHorizonDiagnosticServiceTests,BacktestEvaluationServiceTests' test
```

- [ ] **步骤 6：提交并推送**

```powershell
git add -- backend/src/main/resources/db/migration/V13__add_backtest_price_basis.sql backend/src/main/java/com/opspilot/ai/forecast/backtest backend/src/test/java/com/opspilot/ai/forecast/backtest
git commit -m "feat: 使用黄金OHLC执行历史回测"
git push
```

提交前必须用 `git diff --cached --name-only` 确认没有暂存未完成的 document 模块文件。

---

### 任务 5：页面明确显示真实行情与价格口径

**文件：**
- 修改：`backend/src/main/resources/static/forecast.html`
- 修改：`backend/src/main/resources/static/forecast.js`
- 修改：`backend/src/main/resources/static/forecast.css`
- 修改：`backend/src/main/resources/static/backtest.html`
- 修改：`backend/src/main/resources/static/backtest.js`
- 修改：`backend/src/test/java/com/opspilot/ai/forecast/api/GoldForecastControllerTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/forecast/backtest/api/BacktestControllerTests.java`

**接口：**
- 预测页调用现有 `get /api/market-data/gold/daily-bars/latest` 展示真实 OHLC。
- 回测页固定展示新任务价格口径 `OHLC 收盘价`。
- 不从前端推断或计算预测方向。

- [ ] **步骤 1：先写页面 API 契约红灯测试**

```java
mockMvc.perform(get("/api/market-data/gold/daily-bars/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceDate").value("2026-08-28"))
        .andExpect(jsonPath("$.close").value(4456.4))
        .andExpect(jsonPath("$.provider").value("twelve_data"));
```

- [ ] **步骤 2：在预测页增加真实行情区域**

```javascript
async function loadGoldBar() {
    const bar = await request("/api/market-data/gold/daily-bars/latest");
    text("barDate", bar.priceDate);
    text("barOpen", money(bar.open));
    text("barHigh", money(bar.high));
    text("barLow", money(bar.low));
    text("barClose", money(bar.close));
    text("barProvider", bar.provider === "twelve_data" ? "Twelve Data" : bar.provider);
}
```

页面文案把“黄金基准价”改为“基准日收盘价”，回测页增加“价格口径：真实 OHLC 收盘价”。

- [ ] **步骤 3：运行后端页面契约测试**

```powershell
.\mvnw.cmd -Dtest=GoldDailyBarControllerTests,GoldForecastControllerTests,BacktestControllerTests test
```

- [ ] **步骤 4：启动应用并人工验收页面**

```powershell
$env:TWELVE_DATA_API_KEY = [Environment]::GetEnvironmentVariable('TWELVE_DATA_API_KEY','User')
.\mvnw.cmd spring-boot:run
```

验收 `http://localhost:8080/forecast.html`：页面显示 `2026-08-28` 和数据库中的真实 OHLC，且“基准日收盘价”与 `close` 一致。

- [ ] **步骤 5：提交并推送**

```powershell
git add -- backend/src/main/resources/static/forecast.html backend/src/main/resources/static/forecast.js backend/src/main/resources/static/forecast.css backend/src/main/resources/static/backtest.html backend/src/main/resources/static/backtest.js backend/src/test/java/com/opspilot/ai/forecast/api/GoldForecastControllerTests.java backend/src/test/java/com/opspilot/ai/forecast/backtest/api/BacktestControllerTests.java
git commit -m "feat: 展示黄金OHLC预测口径"
git push
```

---

### 任务 6：重新回测并建立准确率准入门槛

**文件：**
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestEvaluationService.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestEvaluation.java`
- 修改：`backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestEvaluationResponse.java`
- 修改：`backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestEvaluationServiceTests.java`
- 修改：`backend/src/main/resources/static/backtest.html`
- 修改：`backend/src/main/resources/static/backtest.js`

**接口：**
- 产出：响应字段 `priceBasis="OHLC_CLOSE"`。
- 产出：响应字段 `beatsBaseline`，只有样本外平衡准确率高于多数类基线才为 `true`。
- 产出：响应字段 `promotionReady`，要求有效样本不少于 60 且 `beatsBaseline=true`。

- [ ] **步骤 1：写准入门槛红灯测试**

```java
@Test
void doesNotPromoteModelBelowMajorityBaseline() {
    BacktestEvaluation result = service.evaluate(casesWith(
            60, 0.48, 0.52
    ));

    assertThat(result.beatsBaseline()).isFalse();
    assertThat(result.promotionReady()).isFalse();
}
```

- [ ] **步骤 2：实现诚实准入规则**

```java
boolean beatsBaseline = balancedAccuracy != null
        && majorityBaselineAccuracy != null
        && balancedAccuracy.compareTo(majorityBaselineAccuracy) > 0;
boolean promotionReady = sampleCount >= 60 && beatsBaseline;
```

这里不设置虚构的“目标胜率”。模型未超过基线时页面明确显示“尚未证明有预测优势”。

- [ ] **步骤 3：运行完整相关测试**

```powershell
.\mvnw.cmd '-Dtest=JdbcGoldDailyBarRepositoryTests,GoldResearchSnapshotServiceTests,GoldVolatilityCalculatorTests,GoldForecastGenerationServiceTests,GoldForecastResolutionServiceTests,BacktestServiceTests,BacktestRunnerTests,HorizonDiagnosticServiceTests,HistoricalHorizonDiagnosticServiceTests,BacktestEvaluationServiceTests,GoldDailyBarControllerTests,GoldForecastControllerTests,BacktestControllerTests' test
```

预期：所有 OHLC 预测链路测试通过，且没有读取 `MarketPrice.referencePrice` 的新正式链路。

- [ ] **步骤 4：运行真实历史回测**

使用页面或现有回测 API 创建最近 120 个有效交易日样本，记录：

- 总体准确率；
- 平衡准确率；
- 多数类基线；
- 相对基线提升；
- 上涨、震荡、下跌各方向准确率；
- 被跳过样本数量及原因。

结果不满足 `promotionReady` 时不宣称准确率提高，继续进入特征和模型实验，而不是修改门槛。

- [ ] **步骤 5：提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestEvaluationService.java backend/src/main/java/com/opspilot/ai/forecast/backtest/BacktestEvaluation.java backend/src/main/java/com/opspilot/ai/forecast/backtest/api/BacktestEvaluationResponse.java backend/src/test/java/com/opspilot/ai/forecast/backtest/BacktestEvaluationServiceTests.java backend/src/main/resources/static/backtest.html backend/src/main/resources/static/backtest.js
git commit -m "feat: 建立黄金预测准确率准入规则"
git push
```

---

## 最终验收

- 数据库最新 OHLC 与 Twelve Data 原始返回抽样一致。
- 正式预测基准价等于基准交易日 `close`。
- 预测结算目标价等于下一根真实交易日线 `close`。
- 历史回测没有自然日补值和未来数据泄漏。
- 页面明确显示真实 OHLC、数据来源和价格口径。
- 新回测结果与旧基准价回测隔离。
- 只有样本外结果超过多数类基线时才显示“具备候选优势”。
- 任何未达到准入门槛的结果都如实展示，不承诺准确率。
