# 黄金与实际利率确定性研究快照实施计划

> **给智能体执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项执行；步骤使用复选框跟踪。

**目标：** 使用本地真实黄金价格和 FRED `DFII10` 实际利率，生成可复算、可审计且不依赖大模型计算的结构化黄金研究快照。

**架构：** 新增独立 `analysis` 模块，通过现有两个 Repository 读取最近 120 条数据，按共同日期对齐后在 Java 中计算 1、5、20 期指标。Controller 只暴露结构化结果；大模型解释留到后续简报阶段。

**技术栈：** Java 21、Spring Boot 3.5、Spring MVC、`BigDecimal`、JUnit 5、AssertJ、Mockito、MockMvc。

**规格：** `docs/superpowers/specs/2026-08-26-gold-real-rate-research-snapshot-design.md`

## 全局约束

- 正式数字只由确定性 Java 代码计算，不使用 `double`，不调用 ChatClient。
- 只对齐日期完全相同的黄金和实际利率观测，不插值、不前向填充。
- 完整快照至少需要 21 个共同日期，查询窗口固定为 120 条。
- 黄金涨跌幅保留 4 位小数，实际利率百分点变化保留 6 位，基点变化保留 2 位，均采用 `HALF_UP`。
- 单因子规则版本固定为 `gold-real-rate-v1`，只表达压力、支撑或中性，不生成交易信号。
- 不常见的日期对齐、精度和阈值逻辑写简短中文注释。
- 测试必须保持可编译，用行为断言制造红灯，不用缺少生产类制造编译失败。
- SQL 关键字继续使用小写；本阶段不新增 SQL 或数据库迁移。
- 直接在 `D:\workFile\demo-ai` 的 `master` 上执行，不创建 worktree。
- 只暂存本阶段文件，不暂存根目录设计草稿及未完成的 `DocumentLifecycleService` 相关文件。

---

## 文件结构

新增或修改文件及职责：

- `backend/src/main/java/com/opspilot/ai/analysis/RealRateFactorStatus.java`：实际利率单因子状态枚举。
- `backend/src/main/java/com/opspilot/ai/analysis/ResearchFactorAssessment.java`：状态、规则版本和中文解释。
- `backend/src/main/java/com/opspilot/ai/analysis/RealRateFactorEvaluator.java`：10 基点阈值规则。
- `backend/src/main/java/com/opspilot/ai/analysis/GoldReturnMetrics.java`：黄金当前价格和 1/5/20 期涨跌幅。
- `backend/src/main/java/com/opspilot/ai/analysis/RealRateChangeMetrics.java`：实际利率当前值、百分点及基点变化。
- `backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshot.java`：完整确定性研究快照。
- `backend/src/main/java/com/opspilot/ai/analysis/InsufficientResearchDataException.java`：无数据或共同日期不足。
- `backend/src/main/java/com/opspilot/ai/analysis/InvalidResearchDataException.java`：价格或观测数据非法。
- `backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java`：查询、对齐、计算和组装。
- `backend/src/main/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotResponse.java`：HTTP 响应 DTO。
- `backend/src/main/java/com/opspilot/ai/analysis/api/GoldResearchController.java`：快照查询接口。
- `backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`：精确映射研究异常。
- 对应测试位于 `backend/src/test/java/com/opspilot/ai/analysis` 及其 `api` 子包。

### 任务 1：实现实际利率单因子状态规则

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/RealRateFactorStatus.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/ResearchFactorAssessment.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/RealRateFactorEvaluator.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/RealRateFactorEvaluatorTests.java`

**接口：**

- 输入：`evaluate(BigDecimal change5BasisPoints, BigDecimal change20BasisPoints)`。
- 输出：`ResearchFactorAssessment(status, ruleVersion, explanation)`。
- 规则版本：`gold-real-rate-v1`。

- [ ] **步骤 1：创建可编译类型和行为占位实现**

创建枚举并使用简短中文注释：

```java
public enum RealRateFactorStatus {
    /** 压力因素 */
    PRESSURING,
    /** 支撑因素 */
    SUPPORTIVE,
    /** 中性或方向冲突 */
    NEUTRAL
}
```

创建结果 record：

```java
public record ResearchFactorAssessment(
        RealRateFactorStatus status,
        String ruleVersion,
        String explanation
) {
}
```

创建可编译 Evaluator，方法暂时返回固定中性结果；红灯必须来自边界行为不正确：

```java
@Component
public class RealRateFactorEvaluator {

    public ResearchFactorAssessment evaluate(
            BigDecimal change5BasisPoints,
            BigDecimal change20BasisPoints
    ) {
        return new ResearchFactorAssessment(
                RealRateFactorStatus.NEUTRAL,
                "gold-real-rate-v1",
                "尚未计算实际利率单因子状态。"
        );
    }
}
```

- [ ] **步骤 2：写阈值和方向冲突测试**

测试至少覆盖下列精确输入：

```java
assertThat(evaluator.evaluate(
        new BigDecimal("1.00"),
        new BigDecimal("10.00")
).status()).isEqualTo(RealRateFactorStatus.PRESSURING);

assertThat(evaluator.evaluate(
        new BigDecimal("-1.00"),
        new BigDecimal("-10.00")
).status()).isEqualTo(RealRateFactorStatus.SUPPORTIVE);

assertThat(evaluator.evaluate(
        new BigDecimal("-1.00"),
        new BigDecimal("12.00")
).status()).isEqualTo(RealRateFactorStatus.NEUTRAL);

assertThat(evaluator.evaluate(
        BigDecimal.ZERO,
        new BigDecimal("10.00")
).status()).isEqualTo(RealRateFactorStatus.NEUTRAL);
```

所有结果还要断言 `ruleVersion` 等于 `gold-real-rate-v1`，解释文本包含“实际利率”，但不要断言冗长完整句子。

- [ ] **步骤 3：运行测试确认行为红灯**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=RealRateFactorEvaluatorTests test
```

预期：测试代码编译成功，压力和支撑用例因固定 `NEUTRAL` 失败。

- [ ] **步骤 4：实现最小阈值规则**

使用 `compareTo`，避免 `BigDecimal.equals` 受小数位影响：

```java
private static final BigDecimal THRESHOLD_BASIS_POINTS =
        new BigDecimal("10");
private static final String RULE_VERSION = "gold-real-rate-v1";

public ResearchFactorAssessment evaluate(
        BigDecimal change5BasisPoints,
        BigDecimal change20BasisPoints
) {
    Objects.requireNonNull(change5BasisPoints, "5 期基点变化不能为空");
    Objects.requireNonNull(change20BasisPoints, "20 期基点变化不能为空");

    if (change20BasisPoints.compareTo(THRESHOLD_BASIS_POINTS) >= 0
            && change5BasisPoints.compareTo(BigDecimal.ZERO) > 0) {
        return assessment(
                RealRateFactorStatus.PRESSURING,
                "实际利率中期明显上升且短期继续上升，对黄金构成单因子压力。"
        );
    }

    if (change20BasisPoints.compareTo(THRESHOLD_BASIS_POINTS.negate()) <= 0
            && change5BasisPoints.compareTo(BigDecimal.ZERO) < 0) {
        return assessment(
                RealRateFactorStatus.SUPPORTIVE,
                "实际利率中期明显下降且短期继续下降，对黄金构成单因子支撑。"
        );
    }

    return assessment(
            RealRateFactorStatus.NEUTRAL,
            "实际利率变化有限或长短周期方向不一致，单因子状态为中性。"
    );
}

private ResearchFactorAssessment assessment(
        RealRateFactorStatus status,
        String explanation
) {
    return new ResearchFactorAssessment(status, RULE_VERSION, explanation);
}
```

- [ ] **步骤 5：重跑测试并提交**

```powershell
.\mvnw.cmd -Dtest=RealRateFactorEvaluatorTests test
git add -- backend/src/main/java/com/opspilot/ai/analysis backend/src/test/java/com/opspilot/ai/analysis/RealRateFactorEvaluatorTests.java
git commit -m "feat: 添加实际利率单因子规则"
git push origin master
```

预期：全部规则测试通过，且没有使用 `double`。

### 任务 2：实现共同日期对齐和确定性指标计算

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/GoldReturnMetrics.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/RealRateChangeMetrics.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshot.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/InsufficientResearchDataException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/InvalidResearchDataException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java`

**接口：**

```java
public class GoldResearchSnapshotService {
    public GoldResearchSnapshot createSnapshot();
}
```

服务固定读取：

```java
marketPriceRepository.findRecent("XAUUSD", 120);
macroObservationRepository.findRecent("DFII10", 120);
```

- [ ] **步骤 1：创建领域结果类型和专用异常**

`GoldReturnMetrics` 字段：

```java
public record GoldReturnMetrics(
        BigDecimal currentPrice,
        BigDecimal return1,
        BigDecimal return5,
        BigDecimal return20,
        OffsetDateTime collectedAt
) {
}
```

`RealRateChangeMetrics` 字段：

```java
public record RealRateChangeMetrics(
        BigDecimal currentRate,
        BigDecimal percentagePointChange1,
        BigDecimal percentagePointChange5,
        BigDecimal percentagePointChange20,
        BigDecimal basisPointChange1,
        BigDecimal basisPointChange5,
        BigDecimal basisPointChange20,
        OffsetDateTime collectedAt
) {
}
```

`GoldResearchSnapshot` 字段：

```java
public record GoldResearchSnapshot(
        LocalDate analysisDate,
        LocalDate latestGoldDate,
        LocalDate latestRealRateDate,
        GoldReturnMetrics gold,
        RealRateChangeMetrics realRate,
        ResearchFactorAssessment assessment,
        String disclaimer
) {
}
```

两个异常都继承 `RuntimeException`，只提供接收 `String message` 的构造器。

- [ ] **步骤 2：创建可编译 Service 骨架**

构造器注入 `MarketPriceRepository`、`MacroObservationRepository` 和 `RealRateFactorEvaluator`。`createSnapshot()` 暂时抛出：

```java
throw new UnsupportedOperationException("尚未实现黄金研究快照");
```

- [ ] **步骤 3：写服务行为测试**

使用 Mockito 返回固定数据，固定数字旁写中文注释“只验证算法，不代表真实行情”。至少覆盖：

1. 两个列表顺序混乱，仍按共同日期降序计算；
2. 只存在于一侧的日期被排除；
3. 索引 `0/1/5/20` 被正确使用；
4. 黄金返回值保留 4 位小数；
5. 实际利率百分点保留 6 位、基点保留 2 位；
6. 20 个共同日期抛 `InsufficientResearchDataException`；
7. 选中的黄金价格为零或负数抛 `InvalidResearchDataException`；
8. 验证两个 Repository 都以 `120` 为 limit 调用；
9. 验证 Evaluator 收到计算后的 5 期和 20 期基点变化；
10. 通过反射断言 `GoldResearchSnapshotService` 的字段类型不包含 `ChatClient`。

核心断言采用手工可复算数据：当前价格 `2200`、1 期基准 `2000`，应得到 `10.0000`；当前实际利率 `2.38`、5 期基准 `2.20`，应得到 `0.180000` 个百分点和 `18.00` 个基点。

- [ ] **步骤 4：运行测试确认行为红灯**

```powershell
.\mvnw.cmd -Dtest=GoldResearchSnapshotServiceTests test
```

预期：测试代码编译成功，执行到 Service 占位方法后因 `UnsupportedOperationException` 失败。

- [ ] **步骤 5：实现排序、交集和输入校验**

实现顺序必须固定：

1. 查询两个 Repository；
2. 任一列表为空时抛 `InsufficientResearchDataException`；
3. 计算两个列表各自最大日期；
4. 按日期建立 Map，重复日期抛 `InvalidResearchDataException`，不得静默覆盖；
5. 对日期集合取交集并按降序排列；
6. 少于 21 个共同日期时抛数据不足异常；
7. 读取索引 `0/1/5/20` 对应观测；
8. 校验四个参与计算的黄金价格都大于零。

共同日期代码保持显式，不使用会修改原 Map 的视图：

```java
List<LocalDate> commonDates = goldByDate.keySet().stream()
        .filter(realRateByDate::containsKey)
        .sorted(Comparator.reverseOrder())
        .toList();

if (commonDates.size() < 21) {
    throw new InsufficientResearchDataException(
            "共同观测日期不足，实际=" + commonDates.size() + "，最低要求=21"
    );
}
```

- [ ] **步骤 6：实现精确计算和结果组装**

黄金收益率方法：

```java
private BigDecimal calculateReturn(
        BigDecimal current,
        BigDecimal base
) {
    return current
            .divide(base, MathContext.DECIMAL128)
            .subtract(BigDecimal.ONE)
            .multiply(new BigDecimal("100"))
            .setScale(4, RoundingMode.HALF_UP);
}
```

实际利率变化方法：

```java
private BigDecimal percentagePointChange(
        BigDecimal current,
        BigDecimal base
) {
    return current.subtract(base)
            .setScale(6, RoundingMode.HALF_UP);
}

private BigDecimal toBasisPoints(BigDecimal percentagePointChange) {
    return percentagePointChange
            .multiply(new BigDecimal("100"))
            .setScale(2, RoundingMode.HALF_UP);
}
```

调用 Evaluator 时只传基点变化：

```java
ResearchFactorAssessment assessment = evaluator.evaluate(
        realRateMetrics.basisPointChange5(),
        realRateMetrics.basisPointChange20()
);
```

免责声明使用常量：

```java
private static final String DISCLAIMER =
        "实际利率状态仅代表单一研究因素，不构成黄金方向预测或投资建议。";
```

- [ ] **步骤 7：重跑测试和上下文测试**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchSnapshotServiceTests,RealRateFactorEvaluatorTests,OpsPilotApplicationTests" test
```

预期：服务行为和 Spring 依赖注入全部通过。

- [ ] **步骤 8：提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/analysis backend/src/test/java/com/opspilot/ai/analysis/GoldResearchSnapshotServiceTests.java
git diff --cached --check
git commit -m "feat: 计算黄金实际利率研究快照"
git push origin master
```

### 任务 3：开放研究快照 API 和精确错误映射

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/api/GoldResearchSnapshotResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/api/GoldResearchController.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/api/GoldResearchControllerTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**接口：**

```http
get /api/research/gold/snapshot
```

- [ ] **步骤 1：创建响应 DTO 和 Controller 骨架**

响应 DTO 使用完整字段和直接转换，不重新计算指标：

```java
public record GoldResearchSnapshotResponse(
        LocalDate analysisDate,
        LocalDate latestGoldDate,
        LocalDate latestRealRateDate,
        GoldReturnMetrics gold,
        RealRateChangeMetrics realRate,
        ResearchFactorAssessment assessment,
        String disclaimer
) {
    public static GoldResearchSnapshotResponse from(
            GoldResearchSnapshot snapshot
    ) {
        return new GoldResearchSnapshotResponse(
                snapshot.analysisDate(),
                snapshot.latestGoldDate(),
                snapshot.latestRealRateDate(),
                snapshot.gold(),
                snapshot.realRate(),
                snapshot.assessment(),
                snapshot.disclaimer()
        );
    }
}
```

Controller 只注入 `GoldResearchSnapshotService`。`snapshot()` 先抛出 `UnsupportedOperationException("尚未实现研究快照接口")`，保证测试可编译。

- [ ] **步骤 2：写 MockMvc 行为测试**

成功用例断言：

```java
mockMvc.perform(get("/api/research/gold/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysisDate").value("2026-08-24"))
        .andExpect(jsonPath("$.gold.return20").exists())
        .andExpect(jsonPath("$.realRate.basisPointChange20").exists())
        .andExpect(jsonPath("$.assessment.ruleVersion")
                .value("gold-real-rate-v1"))
        .andExpect(jsonPath("$.disclaimer").value(
                "实际利率状态仅代表单一研究因素，不构成黄金方向预测或投资建议。"
        ));
```

再让 mock Service 分别抛出两个专用异常，断言：

- `422/INSUFFICIENT_RESEARCH_DATA`；
- `422/INVALID_RESEARCH_DATA`。

测试固定数值只用于合同验证，不代表真实行情。

- [ ] **步骤 3：运行测试确认行为红灯**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchControllerTests,GlobalExceptionHandlerTests" test
```

预期：编译成功；Controller 占位方法和尚未注册的异常映射导致行为测试失败。

- [ ] **步骤 4：实现 Controller 和响应转换**

Controller 最小实现：

```java
@GetMapping("/snapshot")
public GoldResearchSnapshotResponse snapshot() {
    return GoldResearchSnapshotResponse.from(
            service.createSnapshot()
    );
}
```

类级路径使用：

```java
@RequestMapping("/api/research/gold")
```

- [ ] **步骤 5：增加精确异常映射**

在 `GlobalExceptionHandler` 新增两个方法：

```java
@ExceptionHandler(InsufficientResearchDataException.class)
public ResponseEntity<ApiError> handleInsufficientResearchData(
        InsufficientResearchDataException exception
) {
    return ResponseEntity.unprocessableEntity().body(
            new ApiError(
                    "INSUFFICIENT_RESEARCH_DATA",
                    exception.getMessage()
            )
    );
}

@ExceptionHandler(InvalidResearchDataException.class)
public ResponseEntity<ApiError> handleInvalidResearchData(
        InvalidResearchDataException exception
) {
    return ResponseEntity.unprocessableEntity().body(
            new ApiError(
                    "INVALID_RESEARCH_DATA",
                    exception.getMessage()
            )
    );
}
```

在 `GlobalExceptionHandlerTests` 直接验证两个响应，并保留已有“普通 `IllegalArgumentException` 不被捕获”的反射测试。

- [ ] **步骤 6：重跑定向和上下文测试**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchControllerTests,GlobalExceptionHandlerTests,OpsPilotApplicationTests" test
```

预期：所有接口、异常和 Spring 上下文测试通过。

- [ ] **步骤 7：提交并推送**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/analysis/api backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/analysis/api backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git diff --cached --check
git commit -m "feat: 开放黄金研究快照接口"
git push origin master
```

### 任务 4：真实数据验收和阶段交付

**文件：**

- 不预设代码修改；只修复验收发现且属于本阶段范围的问题。

- [ ] **步骤 1：运行确定性测试和回归测试**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd "-Dtest=RealRateFactorEvaluatorTests,GoldResearchSnapshotServiceTests,GoldResearchControllerTests,GlobalExceptionHandlerTests,OpsPilotApplicationTests" test
.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test
```

预期：本阶段测试全部通过；回归测试只排除已知未完成的文档生命周期测试。

- [ ] **步骤 2：执行敏感信息和范围检查**

```powershell
cd D:\workFile\demo-ai
git diff --check
git status --short
rg -n "FRED_API_KEY\s*=|ALPHA_VANTAGE_API_KEY\s*=|api_key=[a-z0-9]{20,}|Bearer\s+[A-Za-z0-9_-]{20,}" backend docs
```

逐条核对命中，只允许环境变量名称和脱敏测试内容；不得出现真实凭证。确认未跟踪的文档生命周期文件仍未暂存。

- [ ] **步骤 3：启动应用并调用真实快照接口**

在能够读取现有环境变量的新 PowerShell 中启动：

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd spring-boot:run
```

另开 PowerShell 调用：

```powershell
$snapshot = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8080/api/research/gold/snapshot"

$snapshot | ConvertTo-Json -Depth 6
```

验收时检查但不硬编码真实数值：

- `analysisDate` 不晚于两个数据源的最新日期；
- 黄金价格大于零；
- 1/5/20 期字段均存在；
- 实际利率基点字段均存在；
- `ruleVersion` 等于 `gold-real-rate-v1`；
- 免责声明完整；
- 响应没有买入、卖出、目标价或收益承诺字段。

- [ ] **步骤 4：数据库独立复算一个指标**

从 API 响应记录 `analysisDate`，在 PostgreSQL 查询该日期及 5 个共同观测间隔前的两组原始数据。使用计算器或 SQL 单独复算黄金 5 期涨跌幅和实际利率 5 期基点变化，与 API 结果比较，舍入后必须一致。

SQL 只使用小写关键字，并先通过共同日期 CTE 取索引：

```sql
with common_dates as (
    select mp.price_date as observation_date,
           mp.reference_price,
           mo.observation_value,
           row_number() over (order by mp.price_date desc) - 1 as period_index
    from market_price mp
    join macro_observation mo
      on mo.observation_date = mp.price_date
     and mo.series_id = 'DFII10'
     and mo.superseded_at is null
    where mp.symbol = 'XAUUSD'
)
select *
from common_dates
where period_index in (0, 5)
order by period_index;
```

- [ ] **步骤 5：存在验收修正时提交**

只有确实修改了本阶段文件才提交：

```powershell
git add -- backend/src/main/java/com/opspilot/ai/analysis backend/src/test/java/com/opspilot/ai/analysis backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git diff --cached --check
git commit -m "test: 完成黄金研究快照验收"
git push origin master
```

若没有代码修正，不创建空提交；直接记录测试、真实 API 和独立复算结果。

## 完成定义

- Java 能从两个真实仓储生成共同日期研究快照；
- 指标精度、阈值边界和数据不足行为均有自动化测试；
- HTTP 接口返回结构化结果和可信边界信息；
- 真实 API 响应经过至少一个指标的独立复算；
- 大模型没有参与数值计算；
- 本阶段提交已推送，未混入未完成的文档生命周期代码。
