# 黄金方向预测评测闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于正式黄金双因子快照生成不可修改的下一有效交易日方向预测，并用后续真实黄金价格确定性评分和统计版本表现。

**Architecture:** 预测生成、实际方向规则、下一有效工作日选择、到期解析和评测统计使用独立组件。模型只生成三分类方向及研究依据；Java 负责边界校验、真实价格选择、实际方向计算和评分；PostgreSQL 保存不可变预测及解析结果，HTTP 不暴露完整提示词和模型原始响应。

**Tech Stack:** Java 21、Spring Boot 3.5.14、Spring AI、GLM-4.7、PostgreSQL 17、Flyway、JdbcTemplate、Jackson、JUnit 5、AssertJ、Mockito、MockMvc。

**Spec:** `docs/superpowers/specs/2026-08-27-gold-forecast-evaluation-design.md`

## 全局约束

- 直接在现有 `master` 推进；每个任务独立提交并推送。
- 只使用正式 `gold-multifactor-v2` 快照和主行情来源真实价格，不生成假行情、假新闻或伪历史预测。
- 不输出目标价、止损位、仓位、买卖指令或数值化涨跌概率。
- SQL 关键字、表名、字段名和数据库枚举值统一小写；Java 枚举常量遵循大写规范。
- 新增生产类、`record`、枚举、异常、服务、控制器和配置类添加简短中文类级 Javadoc。
- 不常见实现添加必要中文注释，不写教程式长注释。
- 兵哥实现三分类阈值与有效工作日选择；Codex 提供可编译骨架、红灯测试、审查和其余工程代码。
- 不暂存现有无关文件：项目根设计草稿及未完成的 `DocumentLifecycleService` 相关文件。
- 完整回归命令：`.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test`。

---

### 任务 1：实现三分类实际方向规则

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/ForecastDirection.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastRule.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/GoldForecastRuleTests.java`

**Interfaces:**
- Consumes: 真实涨跌幅百分比 `BigDecimal actualReturn`
- Produces: `ForecastDirection classify(BigDecimal actualReturn)`
- Produces: `String RULE_VERSION = "gold-next-session-direction-v1"`

- [ ] **Step 1: Codex 创建枚举、可编译规则骨架和完整边界测试**

```java
/** 黄金下一有效交易日的三分类方向。 */
public enum ForecastDirection {
    BULLISH,
    NEUTRAL,
    BEARISH
}
```

```java
/** 按版本化阈值把真实涨跌幅转换为方向。 */
@Component
public class GoldForecastRule {
    public static final String RULE_VERSION =
            "gold-next-session-direction-v1";

    public ForecastDirection classify(BigDecimal actualReturn) {
        throw new UnsupportedOperationException("请实现黄金方向分类规则");
    }
}
```

测试必须覆盖：`0.500001 -> BULLISH`、`0.500000 -> NEUTRAL`、`0 -> NEUTRAL`、`-0.500000 -> NEUTRAL`、`-0.500001 -> BEARISH` 和 `null` 拒绝。

- [ ] **Step 2: 运行有效红灯**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldForecastRuleTests test`

Expected: 测试可编译，并因 `UnsupportedOperationException` 失败。

- [ ] **Step 3: 兵哥实现最小阈值规则**

实现只能使用 `BigDecimal.compareTo`，不能转成 `double`：

```java
private static final BigDecimal BULLISH_THRESHOLD =
        new BigDecimal("0.500000");
private static final BigDecimal BEARISH_THRESHOLD =
        new BigDecimal("-0.500000");
```

比较必须严格大于或严格小于；两个边界值归为 `NEUTRAL`。

- [ ] **Step 4: Codex 审查并运行绿灯**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldForecastRuleTests test`

Expected: 6 tests，0 failures，0 errors。

- [ ] **Step 5: 提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast/ForecastDirection.java backend/src/main/java/com/opspilot/ai/forecast/GoldForecastRule.java backend/src/test/java/com/opspilot/ai/forecast/GoldForecastRuleTests.java
git commit -m "feat: 定义黄金预测方向规则"
git push origin master
```

---

### 任务 2：选择下一条真实有效工作日价格

**Files:**
- Modify: `backend/src/main/java/com/opspilot/ai/marketdata/MarketPriceRepository.java`
- Modify: `backend/src/main/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepository.java`
- Modify: `backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/NextValidMarketPriceSelector.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/NextValidMarketPriceSelectorTests.java`

**Interfaces:**
- Produces: `List<MarketPrice> findAfter(String symbol, LocalDate baseDate, int limit)`，按日期升序
- Consumes: 基准日期之后的真实价格列表
- Produces: `Optional<MarketPrice> select(List<MarketPrice> candidates)`

- [ ] **Step 1: Codex 为 JDBC 查询和选择器创建红灯测试**

仓储测试断言 SQL 结果只包含 `price_date > baseDate`，并按日期升序。选择器骨架保持可编译：

```java
/** 从真实候选价格中选择第一条周一至周五记录。 */
@Component
public class NextValidMarketPriceSelector {
    public Optional<MarketPrice> select(List<MarketPrice> candidates) {
        throw new UnsupportedOperationException("请实现有效工作日价格选择");
    }
}
```

选择器测试覆盖：周五后存在周六、周日、周一时选周一；节假日没有记录时选下一条真实工作日；只有周末时返回空；输入乱序时仍选择最早有效日期；空列表返回空。

- [ ] **Step 2: 运行有效红灯**

Run: `cd backend; .\mvnw.cmd "-Dtest=NextValidMarketPriceSelectorTests,JdbcMarketPriceRepositoryTests" test`

Expected: 选择器因骨架失败，仓储新增行为因未实现失败；不得出现缺类编译错误。

- [ ] **Step 3: Codex 实现仓储查询**

```sql
select symbol, price_date, reference_price, currency, unit, provider, collected_at
from market_price
where symbol = ?
  and price_date > ?
order by price_date asc
limit ?
```

`limit` 必须在 `1..100`，错误信息为 `limit 必须在 1 到 100 之间`。

- [ ] **Step 4: 兵哥实现有效工作日选择**

实现先按 `MarketPrice::priceDate` 排序，再排除 `SATURDAY` 和 `SUNDAY`，最后返回第一条。不得维护节假日表；节假日由“没有真实价格记录”自然表达。

- [ ] **Step 5: Codex 审查并运行绿灯**

Run: `cd backend; .\mvnw.cmd "-Dtest=NextValidMarketPriceSelectorTests,JdbcMarketPriceRepositoryTests" test`

- [ ] **Step 6: 提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/marketdata backend/src/main/java/com/opspilot/ai/forecast backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java backend/src/test/java/com/opspilot/ai/forecast/NextValidMarketPriceSelectorTests.java
git commit -m "feat: 选择黄金下一有效工作日价格"
git push origin master
```

---

### 任务 3：接入专用方向预测模型网关

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldDirectionForecastContent.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastPrompt.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastPromptBuilder.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GeneratedGoldForecast.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastGateway.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastValidator.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastAiUnavailableException.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/InvalidGoldForecastAiResponseException.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/UnsafeGoldForecastException.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/SpringAiGoldForecastGateway.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastProperties.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastConfiguration.java`
- Modify: `backend/src/main/resources/application.yaml`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/GoldForecastPromptBuilderTests.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/GoldForecastValidatorTests.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/SpringAiGoldForecastGatewayTests.java`
- Modify: `backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**Interfaces:**
- Produces: `GoldForecastPrompt build(StoredGoldResearchSnapshot snapshot)`
- Produces: `GeneratedGoldForecast generate(GoldForecastPrompt prompt)`
- Produces: `void validate(GoldDirectionForecastContent content)`
- Config: `opspilot.forecast.gold.model-name=glm-4.7`

- [ ] **Step 1: 创建领域合同和提示词红灯**

```java
/** 保存模型生成并等待安全校验的结构化方向预测。 */
public record GoldDirectionForecastContent(
        ForecastDirection direction,
        String reasoning,
        List<String> invalidationConditions
) {
}
```

```java
/** 保存预测提示词版本、内容和 SHA-256 摘要。 */
public record GoldForecastPrompt(
        String version,
        String content,
        String sha256
) {
}
```

提示词版本固定为 `gold-direction-forecast-prompt-v1`。测试断言提示词包含快照 ID、真实日期、黄金收益率、两个因子状态和三分类阈值；同时包含“不得生成新闻、目标价、概率、仓位和买卖建议”。同一快照产生稳定 64 位小写 SHA-256。

- [ ] **Step 2: 运行提示词红灯并实现**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldForecastPromptBuilderTests test`

Expected: 骨架因 `UnsupportedOperationException` 失败。Codex 随后实现稳定 UTF-8 提示词和摘要，再运行至绿灯。

- [ ] **Step 3: 创建安全校验红灯并实现**

校验规则：方向非空；`reasoning` 去空白后长度 `1..2000`；失效条件 `1..5` 条，每条 `1..300`；完整文本禁止 `建议买入`、`建议卖出`、`目标价`、`止损位`、`仓位`；禁止模式：

```java
Pattern.compile("(?:上涨|下跌|涨|跌).{0,8}\\d+(?:\\.\\d+)?%")
```

Run: `cd backend; .\mvnw.cmd -Dtest=GoldForecastValidatorTests test`

先确认骨架红灯，再实现并确认全部绿灯。

- [ ] **Step 4: 创建 Spring AI 网关红灯并实现**

```java
/** 封装模型名、原始响应和结构化方向预测。 */
public record GeneratedGoldForecast(
        String modelName,
        String rawResponse,
        GoldDirectionForecastContent content
) {
}
```

测试使用 `ChatModel` lambda 和 `ChatClient.builder(model)`，覆盖完整提示词传递、合法 JSON 解析、非法 JSON 专用异常、上游异常转换、日志只记录模型名/长度/耗时而不记录价格和完整响应。JSON 解析错误必须与上游不可用异常分开。

- [ ] **Step 5: 验证配置和邻接测试**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastPromptBuilderTests,GoldForecastValidatorTests,SpringAiGoldForecastGatewayTests,OpsPilotApplicationTests" test`

- [ ] **Step 6: 提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast backend/src/main/resources/application.yaml backend/src/test/java/com/opspilot/ai/forecast backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java
git commit -m "feat: 接入黄金方向预测模型"
git push origin master
```

---

### 任务 4：使用 V8 和 JDBC 留痕不可变预测

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__create_gold_direction_forecast.sql`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/ForecastStatus.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/StoredGoldDirectionForecast.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/ForecastResolution.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/SaveGoldForecastResult.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastRepository.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/JdbcGoldForecastRepository.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/GoldForecastSchemaTests.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/JdbcGoldForecastRepositoryTests.java`

**Interfaces:**
- Produces: `Optional<StoredGoldDirectionForecast> findByKey(UUID snapshotId, String modelName, String promptVersion, String ruleVersion)`
- Produces: `SaveGoldForecastResult saveIfAbsent(StoredGoldDirectionForecast candidate)`
- Produces: `List<StoredGoldDirectionForecast> findPending(int limit)`
- Produces: `List<StoredGoldDirectionForecast> findRecent(int limit)`
- Produces: `List<StoredGoldDirectionForecast> findAllForEvaluation()`
- Produces: `StoredGoldDirectionForecast resolve(UUID id, ForecastResolution resolution)`

- [ ] **Step 1: 创建完整可编译合同和数据库红灯测试**

`StoredGoldDirectionForecast` 严格包含规格中的 20 个字段；`ForecastStatus` 包含 `PENDING`、`RESOLVED`、`DATA_MISSING`、`VOIDED`；`ForecastResolution` 保存目标日期、目标价格、实际收益率、实际方向、是否命中和解析时间；待验证记录的目标及评分字段为空。

Schema 测试检查 20 列、`jsonb`、快照外键、方向/状态检查约束、64 位摘要约束和唯一键：

```sql
(snapshot_id, model_name, prompt_version, forecast_rule_version)
```

JDBC 测试覆盖首次保存、重复不覆盖、JSONB 往返、待验证查询、历史倒序、解析后字段完整更新、重复解析不覆盖。

- [ ] **Step 2: 运行数据库红灯**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastSchemaTests,JdbcGoldForecastRepositoryTests" test`

Expected: V8 表不存在和仓储骨架未实现导致有效红灯。

- [ ] **Step 3: 编写 V8 迁移**

迁移必须包含小写 SQL、`on delete restrict` 外键、`jsonb_typeof(invalidation_conditions) = 'array'`、预测方向三值约束、状态四值约束以及查询索引：

```sql
create index idx_gold_direction_forecast_status_created
    on gold_direction_forecast (status, created_at asc);
```

- [ ] **Step 4: 实现 JDBC 幂等保存与条件解析**

插入使用：

```sql
on conflict (
    snapshot_id,
    model_name,
    prompt_version,
    forecast_rule_version
) do nothing
```

解析更新必须带 `where id = ? and status = 'pending'`；更新数为 0 时读取并返回数据库已有记录，不能覆盖已解析结果。

- [ ] **Step 5: 运行绿灯**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastSchemaTests,JdbcGoldForecastRepositoryTests" test`

- [ ] **Step 6: 提交并推送**

```powershell
git add -- backend/src/main/resources/db/migration/V8__create_gold_direction_forecast.sql backend/src/main/java/com/opspilot/ai/forecast backend/src/test/java/com/opspilot/ai/forecast
git commit -m "feat: 留痕黄金方向预测"
git push origin master
```

---

### 任务 5：编排预测生成并保证模型费用幂等

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/InvalidGoldForecastSnapshotException.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastGenerationService.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/GoldForecastGenerationServiceTests.java`

**Interfaces:**
- Produces: `SaveGoldForecastResult generate(UUID snapshotId)`

- [ ] **Step 1: 创建服务骨架和行为红灯**

测试使用真实领域对象和 mock 端口，严格验证顺序：查询快照 → 验证 `gold-multifactor-v2` → 查询幂等键 → 构建提示词 → 调模型 → 安全校验 → 保存 `PENDING` 记录。

额外覆盖：快照不存在；快照研究版本不合法；幂等命中不构建提示词且不调用模型；模型失败、解析失败、校验失败均不保存；候选记录冻结快照中的 `baseDate` 和 `basePrice`；创建时间使用注入 `Clock` 的 UTC 时间。

- [ ] **Step 2: 运行有效红灯**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldForecastGenerationServiceTests test`

- [ ] **Step 3: 实现最小编排**

```java
if (!"gold-multifactor-v2".equals(snapshot.snapshot().researchVersion())) {
    throw new InvalidGoldForecastSnapshotException(
            "只有正式双因子快照可以生成方向预测"
    );
}
```

远程模型调用不得放进数据库事务。数据库唯一约束负责并发最终幂等。

- [ ] **Step 4: 运行生成服务和邻接绿灯**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastGenerationServiceTests,GoldForecastPromptBuilderTests,GoldForecastValidatorTests" test`

- [ ] **Step 5: 提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast backend/src/test/java/com/opspilot/ai/forecast/GoldForecastGenerationServiceTests.java
git commit -m "feat: 编排黄金方向预测生成"
git push origin master
```

---

### 任务 6：使用真实后续价格解析待验证预测

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/ResolveGoldForecastsResult.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastResolutionService.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/GoldForecastResolutionServiceTests.java`

**Interfaces:**
- Produces: `ResolveGoldForecastsResult resolvePending(int limit)`
- Consumes: `MarketPriceRepository.findAfter`、`NextValidMarketPriceSelector.select`、`GoldForecastRule.classify`、`GoldForecastRepository.resolve`

- [ ] **Step 1: 创建解析服务骨架和完整红灯**

测试覆盖：没有待验证记录；找到周一价格并解析周五预测；只有周末价格时保持 `PENDING`；节假日缺记录时选择下一条真实工作日价格；真实涨跌幅按 `(target-base)/base*100` 计算并保留 6 位小数；方向边界复用 `GoldForecastRule`；命中和未命中；已解析记录不在待处理集合中；`limit` 在 `1..100`。

- [ ] **Step 2: 运行有效红灯**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldForecastResolutionServiceTests test`

- [ ] **Step 3: 实现确定性解析**

涨跌幅计算使用：

```java
targetPrice.subtract(basePrice)
        .divide(basePrice, 8, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"))
        .setScale(6, RoundingMode.HALF_UP);
```

没有真实目标价格时不得写 `targetDate`、`targetPrice`、`actualReturn`、`actualDirection`、`hit` 或 `resolvedAt`。

- [ ] **Step 4: 运行解析和仓储绿灯**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastResolutionServiceTests,JdbcGoldForecastRepositoryTests,NextValidMarketPriceSelectorTests,GoldForecastRuleTests" test`

- [ ] **Step 5: 提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast backend/src/test/java/com/opspilot/ai/forecast/GoldForecastResolutionServiceTests.java
git commit -m "feat: 解析黄金方向预测结果"
git push origin master
```

---

### 任务 7：统计版本表现并比较中性基线

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/DirectionEvaluation.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/ForecastVersionEvaluation.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastEvaluation.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/GoldForecastEvaluationService.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/GoldForecastEvaluationServiceTests.java`

**Interfaces:**
- Produces: `GoldForecastEvaluation evaluate()`
- Consumes: `GoldForecastRepository.findAllForEvaluation()`

- [ ] **Step 1: 创建统计合同和红灯测试**

`GoldForecastEvaluation` 返回总数、待验证数、已验证数、`BigDecimal overallAccuracy`、三个 `DirectionEvaluation`、`BigDecimal rolling20Accuracy`、`BigDecimal neutralBaselineAccuracy` 和 `List<ForecastVersionEvaluation>`。版本结果严格按 `modelName/promptVersion/ruleVersion` 三元组分组。

测试覆盖：无已验证样本时所有准确率为 `null`；总体准确率；每类样本数与准确率；最近 20 条只取最新已解析记录；中性基线按 `actualDirection == NEUTRAL` 计算；版本不能混合统计；除法使用 4 位小数和 `HALF_UP`。

- [ ] **Step 2: 运行红灯并实现纯 Java 统计**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldForecastEvaluationServiceTests test`

统计服务不得调用模型或行情 API，只消费数据库记录。

- [ ] **Step 3: 运行绿灯**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastEvaluationServiceTests,JdbcGoldForecastRepositoryTests" test`

- [ ] **Step 4: 提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast backend/src/test/java/com/opspilot/ai/forecast/GoldForecastEvaluationServiceTests.java
git commit -m "feat: 统计黄金方向预测表现"
git push origin master
```

---

### 任务 8：开放 HTTP 合同并完成真实前向预测验收

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/api/GoldForecastResponse.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/api/SaveGoldForecastResponse.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/api/ResolveGoldForecastsResponse.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/api/GoldForecastEvaluationResponse.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/api/GoldForecastController.java`
- Modify: `backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/opspilot/ai/forecast/api/GoldForecastControllerTests.java`
- Modify: `backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**Interfaces:**
- `post /api/research/gold/snapshots/{snapshotId}/forecasts`
- `get /api/research/gold/forecasts?limit=20`
- `post /api/research/gold/forecasts/resolve?limit=100`
- `get /api/research/gold/forecasts/evaluation`

- [ ] **Step 1: 编写 HTTP 红灯合同**

首次生成断言 `201/created=true`，重复生成断言 `200/created=false`。预测响应包含快照 ID、基准日期、基准价格、预测方向、依据、失效条件、版本、状态和解析字段，但以下字段不存在：

```java
.andExpect(jsonPath("$.record.rawResponse").doesNotExist())
.andExpect(jsonPath("$.record.prompt").doesNotExist())
```

解析接口断言不会调用预测网关。评测无已验证样本时准确率 JSON 值为 `null`。异常合同使用固定错误码：快照不存在 `404/GOLD_RESEARCH_SNAPSHOT_NOT_FOUND`、非法快照 `422/INVALID_GOLD_FORECAST_SNAPSHOT`、模型不可用 `503/GOLD_FORECAST_AI_UNAVAILABLE`、模型响应非法 `502/INVALID_GOLD_FORECAST_AI_RESPONSE`、预测越界 `422/UNSAFE_GOLD_FORECAST`。

- [ ] **Step 2: 运行有效红灯并实现 DTO、Controller、异常映射**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastControllerTests,GlobalExceptionHandlerTests" test`

Controller 只做参数、服务调用、DTO 映射和 `created` 状态选择，不执行 SQL、方向计算或模型解析。

- [ ] **Step 3: 运行 HTTP 绿灯和完整回归**

Run: `cd backend; .\mvnw.cmd "-Dtest=GoldForecastControllerTests,GlobalExceptionHandlerTests" test`

Run: `cd backend; .\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test`

Expected: 0 failures，0 errors；普通自动化测试不依赖真实 GLM 调用。

- [ ] **Step 4: 提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/forecast/api backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/forecast/api backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git commit -m "feat: 开放黄金方向预测评测接口"
git push origin master
```

- [ ] **Step 5: 使用最新真实正式快照生成前向预测**

启动时只从环境变量读取密钥。选择最新 `gold-multifactor-v2` 正式快照，调用两次生成接口。验收第一次 `201/created=true`、第二次 `200/created=false`、ID 相同且应用日志只有一次真实模型调用。

预测必须保持 `pending`，因为本阶段不使用后来数据伪造历史评分。响应不得包含概率、目标价、仓位、买卖建议、完整提示词或模型原始响应。

- [ ] **Step 6: 验证解析与空样本统计**

如果主行情表尚无基准日期之后的真实有效工作日价格，调用解析接口后预测保持 `pending`；评测接口返回已验证样本数 `0` 且准确率为 `null`。如果真实后续价格已经自然入库，则允许解析为 `resolved`，但必须展示目标日期和来源数据，不手工改写数据库。

- [ ] **Step 7: 最终 Git 和安全检查**

```powershell
git diff --check
git status --short
git rev-parse HEAD
git rev-parse origin/master
```

确认 `HEAD == origin/master`，API Key、数据库密码、完整提示词、模型原始响应和无关未完成文件均未进入提交。
