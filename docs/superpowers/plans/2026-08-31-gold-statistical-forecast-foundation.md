# 黄金统计预测基础实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立不读取未来数据的 1 日、5 日、20 日黄金特征数据集，并用多数类基线和多分类逻辑回归完成可视化的滚动前推验证。

**Architecture:** 新增独立的 `forecast.learning` 包，将真实行情转为带目标日期的训练样本；时间切分器永久保留最后 240 条最终留出样本，本阶段只在留出样本之前的开发区间进行滚动前推实验。模型通过统一 `GoldClassifier` 接口输出三方向概率，`NO_SIGNAL` 由置信度策略产生，大模型不参与数值预测。

**Tech Stack:** Java 21、Spring Boot 3.5.14、JUnit 5、AssertJ、Mockito、Tribuo 4.3.2 `tribuo-classification-sgd`、原生 HTML/CSS/JavaScript。

**Spec:** `docs/superpowers/specs/2026-08-31-gold-forecast-learning-system-design.md`

## Global Constraints

- 所有计划、步骤、类注释和特殊逻辑注释使用简洁中文。
- Java 方法名和变量名保持简短清晰，不使用无意义缩写。
- 正式数据只读取 `symbol=XAUUSD`、`provider=twelve_data` 的真实 OHLC。
- 不生成、插值或补造行情、宏观数据、新闻和标签。
- `NEUTRAL` 是真实方向标签，`NO_SIGNAL` 是弃权状态，两者不得混用。
- 最后 240 条样本作为最终留出区间，本计划任何实验均不得读取其标签或指标。
- 每个样本的特征只能使用 `asOfDate` 当日及之前已存在的数据。
- 每个生产 `class`、`record`、`enum`、`service` 和 `controller` 都写一段简短中文类级 Javadoc。
- SQL 关键字、表名和字段名保持小写；本阶段不新增数据库迁移。
- 不修改或提交 `.env.example`、项目根目录旧设计文档和未完成的 `document` 模块文件。

---

## 文件结构

### 新增生产文件

- `backend/src/main/java/com/opspilot/ai/forecast/learning/ForecastHorizon.java`：定义 1、5、20 个交易日周期。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/SignalStatus.java`：区分已给方向和证据不足。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldFeatures.java`：保存固定名称的数值特征。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldSample.java`：保存分析日、目标日、标签和特征。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDataset.java`：保存完整样本和被拒绝样本数量。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilder.java`：从真实 OHLC 和历史研究快照构建样本。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/TemporalDataset.java`：保存训练、验证和最终留出区间。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/TemporalSplitter.java`：执行时间切分和周期隔离。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/DirectionProbabilities.java`：保存三方向概率。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldPrediction.java`：保存模型输出、方向和弃权状态。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/SettledPrediction.java`：把预测与真实方向组成评估输入。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldClassifier.java`：统一分类器推理边界。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldTrainer.java`：统一分类器训练边界。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/MajorityGoldTrainer.java`：提供多数类基线。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/TribuoGoldTrainer.java`：训练多分类逻辑回归。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/ConfidencePolicy.java`：把概率转换为方向或 `NO_SIGNAL`。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/ForecastMetrics.java`：保存统一评估结果。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/ForecastEvaluator.java`：计算准确率、平衡准确率、覆盖率和混淆矩阵。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/WalkForwardReport.java`：保存滚动实验结果。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/WalkForwardService.java`：运行开发区间滚动前推实验。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/api/ModelExperimentController.java`：提供只读实验接口。
- `backend/src/main/java/com/opspilot/ai/forecast/learning/api/ModelExperimentResponse.java`：固定接口响应。
- `backend/src/main/resources/static/model-lab.html`：模型实验页面。
- `backend/src/main/resources/static/model-lab.css`：页面样式。
- `backend/src/main/resources/static/model-lab.js`：请求接口并展示结果。

### 新增测试文件

- `backend/src/test/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilderTests.java`
- `backend/src/test/java/com/opspilot/ai/forecast/learning/TemporalSplitterTests.java`
- `backend/src/test/java/com/opspilot/ai/forecast/learning/ConfidencePolicyTests.java`
- `backend/src/test/java/com/opspilot/ai/forecast/learning/ForecastEvaluatorTests.java`
- `backend/src/test/java/com/opspilot/ai/forecast/learning/TribuoGoldTrainerTests.java`
- `backend/src/test/java/com/opspilot/ai/forecast/learning/WalkForwardServiceTests.java`
- `backend/src/test/java/com/opspilot/ai/forecast/learning/api/ModelExperimentControllerTests.java`
- `backend/src/test/java/com/opspilot/ai/forecast/learning/api/ModelLabPageTests.java`

### 修改文件

- `backend/pom.xml`：增加 Tribuo 4.3.2 的纯 Java 多分类 SGD 模块。
- `backend/src/main/resources/static/forecast.html`：增加“模型实验”入口。

---

### Task 1: 建立多周期预测领域类型

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/ForecastHorizon.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/SignalStatus.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldFeatures.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldSample.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDataset.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/DirectionProbabilities.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldPrediction.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/SettledPrediction.java`

**Interfaces:**
- Produces: `ForecastHorizon.sessions()`、`GoldFeatures.values()`、`GoldSample`、`DirectionProbabilities` 和 `GoldPrediction`，供后续所有任务使用。

- [ ] **Step 1: 在 `pom.xml` 添加最小 Tribuo 依赖**

```xml
<properties>
    <tribuo.version>4.3.2</tribuo.version>
</properties>

<!-- 使用纯 Java SGD 训练多分类逻辑回归，不引入 XGBoost 原生库。 -->
<dependency>
    <groupId>org.tribuo</groupId>
    <artifactId>tribuo-classification-sgd</artifactId>
    <version>${tribuo.version}</version>
</dependency>
```

- [ ] **Step 2: 创建三个核心枚举**

```java
/** 定义黄金预测需要结算的交易日周期。 */
public enum ForecastHorizon {
    NEXT_DAY(1), FIVE_DAYS(5), TWENTY_DAYS(20);

    private final int sessions;

    ForecastHorizon(int sessions) {
        this.sessions = sessions;
    }

    public int sessions() {
        return sessions;
    }
}
```

```java
/** 区分模型给出正式方向和证据不足两种状态。 */
public enum SignalStatus {
    PREDICTED,
    NO_SIGNAL
}
```

- [ ] **Step 3: 创建不可变特征和样本类型**

`GoldFeatures` 使用 `Map.copyOf(values)` 保存以下固定键：

```text
gold_return_1, gold_return_5, gold_return_20, gold_volatility_20,
intraday_range, candle_body, close_position,
real_rate, real_rate_bp_1, real_rate_bp_5, real_rate_bp_20, real_rate_age,
dollar_return_1, dollar_return_5, dollar_return_20, dollar_age
```

```java
/** 保存预测日当时真实可用的固定数值特征。 */
public record GoldFeatures(Map<String, Double> values) {
    public GoldFeatures {
        values = Map.copyOf(values);
        if (values.size() != 16 || values.values().stream().anyMatch(v -> !Double.isFinite(v))) {
            throw new IllegalArgumentException("黄金特征必须包含 16 个有限数值");
        }
    }
}
```

```java
/** 保存一个可审计的黄金监督学习样本。 */
public record GoldSample(
        LocalDate asOfDate,
        LocalDate targetDate,
        ForecastHorizon horizon,
        GoldFeatures features,
        ForecastDirection label
) {
}
```

```java
/** 保存成功构建的样本和因真实数据不完整而拒绝的数量。 */
public record GoldDataset(List<GoldSample> samples, int skippedCount) {
    public GoldDataset {
        samples = List.copyOf(samples);
        if (skippedCount < 0) {
            throw new IllegalArgumentException("拒绝样本数量不能为负数");
        }
    }
}
```

- [ ] **Step 4: 创建概率和预测类型**

`DirectionProbabilities` 构造时验证三项处于 `[0,1]`，且总和与 `1` 的差不超过 `0.000001`。`GoldPrediction.direction` 在 `NO_SIGNAL` 时必须为 `null`，在 `PREDICTED` 时不能为空。

```java
/** 保存一条已经获得真实方向的概率预测。 */
public record SettledPrediction(
        LocalDate asOfDate,
        DirectionProbabilities probabilities,
        GoldPrediction prediction,
        ForecastDirection actual
) {
}
```

- [ ] **Step 5: 编译确认领域类型和依赖可解析**

Run: `cd backend; .\mvnw.cmd -DskipTests compile`

Expected: `BUILD SUCCESS`，且依赖树中不出现 `xgboost4j`、TensorFlow 或 ONNX Runtime。

- [ ] **Step 6: 提交**

```powershell
git add backend/pom.xml backend/src/main/java/com/opspilot/ai/forecast/learning
git commit -m "feat: 建立黄金统计预测领域类型"
```

### Task 2: 从真实历史数据构建无泄漏样本

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilder.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilderTests.java`

**Interfaces:**
- Consumes: `GoldDailyBarRepository.findAll(...)`、`GoldResearchSnapshotService.createSnapshot(LocalDate)`、`GoldForecastRule.classify(BigDecimal)`。
- Produces: `GoldDataset build(ForecastHorizon horizon)`，其中样本按 `asOfDate` 升序排列。

- [ ] **Step 1: 写 OHLC 目标日和特征公式红灯测试**

测试夹具提供至少 41 根连续交易日线，并固定最后 20 根历史。断言：

```java
assertThat(samples.getFirst().targetDate()).isEqualTo(bars.get(21).priceDate());
assertThat(sample.features().values())
        .containsEntry("intraday_range", 4.0)
        .containsEntry("candle_body", 1.0)
        .containsEntry("close_position", 0.75);
```

公式统一为：

```text
intraday_range = (high - low) / open * 100
candle_body = (close - open) / open * 100
close_position = high == low ? 0.5 : (close - low) / (high - low)
```

- [ ] **Step 2: 写周期和标签红灯测试**

分别使用 `NEXT_DAY`、`FIVE_DAYS`、`TWENTY_DAYS`，断言目标日索引为基准日之后 `1/5/20` 根日线。标签收益率统一为：

```text
(target.close - base.close) / base.close * 100
```

并交给现有 `GoldForecastRule` 分类，禁止在数据集构建器中复制涨跌阈值。

- [ ] **Step 3: 写未来数据隔离和缺失数据测试**

使用 Mockito 验证每次调用：

```java
verify(snapshotService).createSnapshot(sample.asOfDate());
assertThat(sample.targetDate()).isAfter(sample.asOfDate());
```

快照缺少实际利率或美元指数时，该日期样本被跳过并记录计数；不得用 `0` 或相邻未来值填充。

- [ ] **Step 4: 运行测试确认红灯**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldDatasetBuilderTests test`

Expected: FAIL，提示 `GoldDatasetBuilder` 不存在。

- [ ] **Step 5: 实现最小数据集构建器**

实现要点：

```java
public GoldDataset build(ForecastHorizon horizon) {
    List<GoldDailyBar> bars = barRepo.findAll(SYMBOL, PROVIDER).stream()
            .sorted(Comparator.comparing(GoldDailyBar::priceDate))
            .toList();
    List<GoldSample> samples = new ArrayList<>();
    int skippedCount = 0;
    for (int index = 20; index + horizon.sessions() < bars.size(); index++) {
        GoldDailyBar base = bars.get(index);
        GoldDailyBar target = bars.get(index + horizon.sessions());
        GoldResearchSnapshot snapshot = snapshots.createSnapshot(base.priceDate());
        samples.add(toSample(base, target, snapshot, horizon));
    }
    return new GoldDataset(samples, skippedCount);
}
```

某日真实宏观快照缺失时递增 `skippedCount` 后跳过该日；宏观数据年龄使用 `ChronoUnit.DAYS.between(latestDataDate, analysisDate)`。数据日期晚于分析日时直接抛出 `IllegalStateException`，不得静默接受。

- [ ] **Step 6: 运行测试确认绿灯**

Run: `cd backend; .\mvnw.cmd -Dtest=GoldDatasetBuilderTests test`

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilder.java backend/src/test/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilderTests.java
git commit -m "feat: 构建黄金多周期真实样本"
```

### Task 3: 建立时间切分和最终留出保护

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/TemporalDataset.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/TemporalSplitter.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/TemporalSplitterTests.java`

**Interfaces:**
- Consumes: 按时间升序的 `List<GoldSample>`。
- Produces: `TemporalDataset split(List<GoldSample> samples, ForecastHorizon horizon)`。

- [ ] **Step 1: 写区间不重叠红灯测试**

使用 1,000 条顺序样本，要求：

```java
assertThat(result.finalHoldout()).hasSize(240);
assertThat(result.validation()).hasSize(240);
assertThat(result.training().getLast().targetDate())
        .isBefore(result.validation().getFirst().asOfDate());
assertThat(result.validation().getLast().targetDate())
        .isBefore(result.finalHoldout().getFirst().asOfDate());
```

- [ ] **Step 2: 写周期隔离红灯测试**

对 `TWENTY_DAYS` 验证训练和验证之间、验证和最终留出之间至少跳过 20 个交易日样本，避免训练标签跨入后续区间。

- [ ] **Step 3: 写样本不足测试**

少于 `240 + 240 + 500 + 2 * horizon.sessions()` 条完整样本时抛出 `BacktestDataInsufficientException`，错误信息包含实际数量和所需数量。

- [ ] **Step 4: 运行测试确认红灯**

Run: `cd backend; .\mvnw.cmd -Dtest=TemporalSplitterTests test`

Expected: FAIL，提示切分类型不存在。

- [ ] **Step 5: 实现时间切分**

`TemporalDataset` 包含 `training`、`validation`、`finalHoldout` 三个不可变列表。`TemporalSplitter` 从尾部先冻结 240 条最终留出，再跳过 `horizon.sessions()` 条隔离样本，再取 240 条验证样本，再次隔离后把剩余前段作为训练区间。

- [ ] **Step 6: 运行测试确认绿灯**

Run: `cd backend; .\mvnw.cmd -Dtest=TemporalSplitterTests test`

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/forecast/learning/TemporalDataset.java backend/src/main/java/com/opspilot/ai/forecast/learning/TemporalSplitter.java backend/src/test/java/com/opspilot/ai/forecast/learning/TemporalSplitterTests.java
git commit -m "feat: 隔离黄金预测时间样本"
```

### Task 4: 建立弃权策略、指标和多数类基线

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldClassifier.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/GoldTrainer.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/MajorityGoldTrainer.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/ConfidencePolicy.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/ForecastMetrics.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/ForecastEvaluator.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/ConfidencePolicyTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/ForecastEvaluatorTests.java`

**Interfaces:**
- Produces: `GoldTrainer.train(List<GoldSample>)`、`GoldClassifier.predict(GoldFeatures)`、`ConfidencePolicy.apply(DirectionProbabilities)`、`ForecastEvaluator.evaluate(List<SettledPrediction>)`。

- [ ] **Step 1: 写置信度策略红灯测试**

固定阈值 `0.55`：

```java
assertThat(policy.apply(probabilities(0.60, 0.25, 0.15)).direction())
        .isEqualTo(ForecastDirection.BULLISH);
assertThat(policy.apply(probabilities(0.40, 0.35, 0.25)).status())
        .isEqualTo(SignalStatus.NO_SIGNAL);
```

概率相同时输出 `NO_SIGNAL`，不得依赖枚举顺序决定方向。

- [ ] **Step 2: 写评估指标红灯测试**

使用以下六条固定案例：`BULLISH→BULLISH`、`BULLISH→NEUTRAL`、`BULLISH→NO_SIGNAL`、`NEUTRAL→NEUTRAL`、`NEUTRAL→BULLISH`、`BEARISH→BEARISH`。箭头左侧为真实方向，右侧为预测。准确断言：

```java
assertThat(metrics.sampleCount()).isEqualTo(6);
assertThat(metrics.coveredCount()).isEqualTo(5);
assertThat(metrics.coverage()).isEqualByComparingTo("0.8333");
assertThat(metrics.accuracy()).isEqualByComparingTo("0.6000");
assertThat(metrics.balancedAccuracy()).isEqualByComparingTo("0.6667");
```

总体准确率和混淆矩阵只使用已覆盖信号；覆盖率单独报告。Brier 分数使用全部概率预测计算，不能排除 `NO_SIGNAL`。

- [ ] **Step 3: 写多数类训练器红灯测试**

训练标签为 `NEUTRAL, NEUTRAL, BULLISH` 时，分类器对任意特征都返回：

```text
BULLISH=0, NEUTRAL=1, BEARISH=0
```

- [ ] **Step 4: 运行测试确认红灯**

Run: `cd backend; .\mvnw.cmd '-Dtest=ConfidencePolicyTests,ForecastEvaluatorTests' test`

Expected: FAIL，提示评估类型不存在。

- [ ] **Step 5: 实现统一边界和指标**

```java
public interface GoldClassifier {
    DirectionProbabilities predict(GoldFeatures features);
}

public interface GoldTrainer {
    String name();
    GoldClassifier train(List<GoldSample> samples);
}
```

指标统一保留四位小数，除零时对应类别召回率为空，并让 `promotionReady=false`；不得把空类别当作 100% 正确。

- [ ] **Step 6: 运行测试确认绿灯**

Run: `cd backend; .\mvnw.cmd '-Dtest=ConfidencePolicyTests,ForecastEvaluatorTests' test`

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/forecast/learning backend/src/test/java/com/opspilot/ai/forecast/learning
git commit -m "feat: 建立黄金分类评估基线"
```

### Task 5: 接入可解释的多分类逻辑回归

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/TribuoGoldTrainer.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/TribuoGoldTrainerTests.java`

**Interfaces:**
- Consumes: `GoldTrainer`、`GoldSample`、Tribuo `LogisticRegressionTrainer`。
- Produces: 名称为 `logistic-v1` 的 `GoldClassifier`，输出合法三方向概率。

- [ ] **Step 1: 写可学习模式红灯测试**

生成 300 条确定性样本：`gold_return_5 > 0` 为上涨，`< 0` 为下跌，接近 `0` 为中性。前 240 条训练，后 60 条测试，要求：

```java
assertThat(metrics.accuracy()).isGreaterThan(new BigDecimal("0.90"));
assertThat(metrics.balancedAccuracy()).isGreaterThan(new BigDecimal("0.90"));
```

该测试只验证适配器能够学习已知模式，不代表真实行情准确率。

- [ ] **Step 2: 写概率完整性和重复训练测试**

断言每次预测包含三个方向，概率和为 `1`；相同固定样本和固定随机种子训练两次，预测差异小于 `0.000001`。

- [ ] **Step 3: 运行测试确认红灯**

Run: `cd backend; .\mvnw.cmd -Dtest=TribuoGoldTrainerTests test`

Expected: FAIL，提示 `TribuoGoldTrainer` 不存在。

- [ ] **Step 4: 实现 Tribuo 适配器**

使用 `LabelFactory`、`ArrayExample<Label>`、`MutableDataset<Label>` 和 `LogisticRegressionTrainer`。每次训练都创建新的默认训练器；Tribuo 4.3.2 的默认训练器固定使用 AdaGrad、5 个 epoch 和 `Trainer.DEFAULT_SEED`：

```java
var trainer = new LogisticRegressionTrainer();
Model<Label> model = trainer.train(dataset);
```

训练和推理必须共用同一组按名称排序的 16 个特征。推理后先验证 `prediction.hasProbabilities()`，再通过 `prediction.getOutputScores()` 读取三项 `Label.getScore()` 并转换成 `DirectionProbabilities`；缺少任一方向或返回非概率分数时抛出 `IllegalStateException`。适配器内部隔离 Tribuo 类型，控制器和业务服务不得依赖 Tribuo API。

- [ ] **Step 5: 运行测试确认绿灯**

Run: `cd backend; .\mvnw.cmd -Dtest=TribuoGoldTrainerTests test`

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/forecast/learning/TribuoGoldTrainer.java backend/src/test/java/com/opspilot/ai/forecast/learning/TribuoGoldTrainerTests.java
git commit -m "feat: 接入黄金逻辑回归模型"
```

### Task 6: 运行开发区间滚动前推实验

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/WalkForwardReport.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/WalkForwardService.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/WalkForwardServiceTests.java`

**Interfaces:**
- Consumes: `GoldDatasetBuilder`、`TemporalSplitter`、`MajorityGoldTrainer`、`TribuoGoldTrainer`、`ForecastEvaluator`。
- Produces: `WalkForwardReport run(ForecastHorizon horizon)`，只使用 `training + validation`，不返回最终留出标签。

- [ ] **Step 1: 写滚动训练红灯测试**

固定验证集 240 条、每 20 条重新训练一次，验证训练器被调用 12 次：

```java
verify(trainer, times(12)).train(anyList());
assertThat(report.validationSamples()).isEqualTo(240);
assertThat(report.refitCount()).isEqualTo(12);
```

每次训练列表最后一条的 `targetDate` 必须早于当前评分块第一条的 `asOfDate`。

- [ ] **Step 2: 写最终留出保护红灯测试**

给最终留出样本设置会使测试立即失败的特征访问器，调用 `run` 后确认从未访问。响应只包含最终留出区间起止日期和数量，不包含标签、准确率或方向分布。

- [ ] **Step 3: 写同口径比较红灯测试**

多数类和逻辑回归必须使用完全相同的 240 条验证日期。报告包含：

```java
public record WalkForwardReport(
        ForecastHorizon horizon,
        LocalDate trainStart,
        LocalDate validationStart,
        LocalDate validationEnd,
        int validationSamples,
        int refitEvery,
        int refitCount,
        ForecastMetrics majority,
        ForecastMetrics logistic,
        int finalHoldoutSamples,
        LocalDate finalHoldoutStart,
        LocalDate finalHoldoutEnd
) {
}
```

- [ ] **Step 4: 运行测试确认红灯**

Run: `cd backend; .\mvnw.cmd -Dtest=WalkForwardServiceTests test`

Expected: FAIL，提示服务不存在。

- [ ] **Step 5: 实现滚动前推**

验证集按 20 条切块。每一块使用初始训练区间加上此前已经结算的验证块重新训练；当前块和未来块不得进入训练。多数类基线使用相同训练窗口重新计算。置信度策略固定为 `0.55`，本阶段不在验证结果上搜索阈值。

- [ ] **Step 6: 运行测试确认绿灯**

Run: `cd backend; .\mvnw.cmd -Dtest=WalkForwardServiceTests test`

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/forecast/learning/WalkForwardReport.java backend/src/main/java/com/opspilot/ai/forecast/learning/WalkForwardService.java backend/src/test/java/com/opspilot/ai/forecast/learning/WalkForwardServiceTests.java
git commit -m "feat: 建立黄金模型滚动验证"
```

### Task 7: 提供实验接口和可视化页面

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/api/ModelExperimentController.java`
- Create: `backend/src/main/java/com/opspilot/ai/forecast/learning/api/ModelExperimentResponse.java`
- Create: `backend/src/main/resources/static/model-lab.html`
- Create: `backend/src/main/resources/static/model-lab.css`
- Create: `backend/src/main/resources/static/model-lab.js`
- Modify: `backend/src/main/resources/static/forecast.html`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/api/ModelExperimentControllerTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/forecast/learning/api/ModelLabPageTests.java`

**Interfaces:**
- Produces: `GET /api/research/gold/model-experiments?horizon=NEXT_DAY|FIVE_DAYS|TWENTY_DAYS`。

- [ ] **Step 1: 写控制器合同红灯测试**

```java
mvc.perform(get("/api/research/gold/model-experiments")
        .param("horizon", "FIVE_DAYS"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.horizon").value("FIVE_DAYS"))
    .andExpect(jsonPath("$.majority.accuracy").isNumber())
    .andExpect(jsonPath("$.logistic.balancedAccuracy").isNumber())
    .andExpect(jsonPath("$.finalHoldout.samples").value(240))
    .andExpect(jsonPath("$.finalHoldout.accuracy").doesNotExist());
```

非法周期返回 `400` 和中文错误信息，不返回栈信息。

- [ ] **Step 2: 写静态页面红灯测试**

使用现有静态页面测试模式读取文件，断言包含：

```text
1 个交易日
5 个交易日
20 个交易日
多数类基线
逻辑回归
总体准确率
平衡准确率
覆盖率
最终留出样本未启用
```

- [ ] **Step 3: 运行测试确认红灯**

Run: `cd backend; .\mvnw.cmd '-Dtest=ModelExperimentControllerTests,ModelLabPageTests' test`

Expected: FAIL，提示控制器或页面不存在。

- [ ] **Step 4: 实现只读接口**

`ModelExperimentResponse.from(report)` 只转换开发验证结果。最终留出区间只返回数量和日期，不暴露任何标签统计。接口不接收置信度阈值、特征列表或训练参数，防止通过页面反复调参。

- [ ] **Step 5: 实现页面**

页面默认加载 `FIVE_DAYS`，三个周期按钮只切换固定周期。结果卡片同时展示准确率、平衡准确率、Brier 分数、覆盖率、三方向召回率和验证日期。页面明确写明：

```text
当前结果只用于开发区间筛选，最终留出样本尚未启用，不能据此宣布正式准确率提高。
```

- [ ] **Step 6: 运行测试确认绿灯**

Run: `cd backend; .\mvnw.cmd '-Dtest=ModelExperimentControllerTests,ModelLabPageTests' test`

Expected: PASS。

- [ ] **Step 7: 启动应用进行浏览器验收**

Run: `cd backend; .\mvnw.cmd spring-boot:run`

浏览器检查：

- `http://localhost:8080/model-lab.html` 可打开；
- 5 日实验可以完成并显示真实日期；
- 三个周期切换没有控制台错误；
- 页面不显示最终留出准确率；
- `forecast.html` 的“模型实验”入口可正常跳转。

- [ ] **Step 8: 提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/forecast/learning/api backend/src/main/resources/static/model-lab.* backend/src/main/resources/static/forecast.html backend/src/test/java/com/opspilot/ai/forecast/learning/api
git commit -m "feat: 展示黄金统计模型实验"
```

### Task 8: 完整回归与第一阶段验收

**Files:**
- Modify only if verification finds a defect in files created by Tasks 1-7.

**Interfaces:**
- Consumes: 第一阶段全部生产和测试类型。
- Produces: 可重复运行的开发区间多数类与逻辑回归对比结果。

- [ ] **Step 1: 运行统计学习定向测试**

Run:

```powershell
cd backend
.\mvnw.cmd '-Dtest=GoldDatasetBuilderTests,TemporalSplitterTests,ConfidencePolicyTests,ForecastEvaluatorTests,TribuoGoldTrainerTests,WalkForwardServiceTests,ModelExperimentControllerTests,ModelLabPageTests' test
```

Expected: 全部 PASS，`Failures: 0, Errors: 0`。

- [ ] **Step 2: 运行相关黄金预测回归**

Run:

```powershell
.\mvnw.cmd '-Dtest=JdbcGoldDailyBarRepositoryTests,GoldResearchSnapshotServiceTests,GoldResearchSnapshotOhlcTests,GoldForecastGenerationServiceTests,GoldForecastResolutionServiceTests,BacktestServiceTests,BacktestRunnerTests,BacktestEvaluationServiceTests,GoldForecastControllerTests,BacktestControllerTests,GoldForecastPageTests,BacktestDashboardTests,OpsPilotApplicationTests' test
```

Expected: 全部 PASS，且不会调用真实大模型或外部行情接口。

- [ ] **Step 3: 运行完整测试**

Run: `cd backend; .\mvnw.cmd test`

Expected: `BUILD SUCCESS`。带显式在线开关的真实接口测试保持跳过，不因本机 API Key 存在而自动执行。

- [ ] **Step 4: 核对数据真实性和切分边界**

从接口结果记录三个周期的训练起止日、验证起止日和最终留出起止日；抽查每个周期 3 条样本，确认目标日是对应的第 `1/5/20` 根真实日线，且训练末端目标日早于验证首日。

- [ ] **Step 5: 记录第一阶段结论**

只报告开发验证区间的多数类与逻辑回归指标，并明确以下三种结论之一：

```text
逻辑回归优于开发基线，可进入下一阶段候选模型比较。
逻辑回归未优于开发基线，需要调整特征或模型，但不得查看最终留出结果。
有效历史样本不足，当前不能形成可信结论。
```

- [ ] **Step 6: 最终提交并推送**

```powershell
git status --short
git push
```

Expected: 只剩兵哥原有的未提交文件；本计划提交全部已推送到 `master`。

---

## 后续独立计划

第一阶段通过验收后，再分别创建并评审以下计划，不在本计划提前实现：

1. 梯度提升树、概率校准和候选模型组合。
2. 模型注册、正式/候选状态、升级门槛和版本回退。
3. 预测结算、错误分类、重复误差聚合和漂移检测。
4. 正式三周期预测、大模型解释和错误复盘页面。
5. 唯一胜出候选模型的 240 条最终留出验收。
