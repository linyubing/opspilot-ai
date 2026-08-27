# 黄金研究大模型解读实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 基于已正式留痕的真实黄金双因子快照，生成、校验、幂等保存并查询可追溯的 GLM-4.7 中文研究解读。

**架构：** 新增独立 `analysis.narrative` 子系统，按快照 ID 查询不可变输入，通过专用提示词构建器和模型网关获得结构化结果，经安全校验后写入 PostgreSQL。确定性指标和因子状态仍由现有 Java 规则产生，大模型只负责解释，不参与计算和决策。

**技术栈：** Java 21、Spring Boot 3.5.14、Spring AI 1.1.8、GLM-4.7 OpenAI 兼容接口、Jackson、Spring JDBC、PostgreSQL 17、Flyway、JUnit 5、AssertJ、Mockito、MockMvc

**规格：** `docs/superpowers/specs/2026-08-27-gold-research-narrative-design.md`

## 全局约束

- 模型输入只能来自已保存的 `gold_research_snapshot`，不允许客户端提交自由行情或自由提示词。
- 不接新闻、研报、社交媒体或 RAG；不生成假新闻。
- 不输出目标价、涨跌概率、仓位、止损、买入或卖出建议。
- 相同 `snapshot_id + model_name + prompt_version` 不重复调用模型。
- 模型格式或安全校验失败时不保存半成品，也不自动调用模型修复。
- 提示词版本 `gold-narrative-prompt-v1` 发布后内容不可静默修改；内容变化必须升级版本。
- SQL 关键字、表名和字段名统一小写。
- 新增生产类、`record`、异常、服务、控制器和配置类添加简短中文类级 Javadoc。
- 不使用缺少生产类型的编译错误作为红灯；先创建可编译契约或抛出 `UnsupportedOperationException` 的骨架，再验证行为红灯。
- 兵哥实现提示词核心内容和安全校验规则；Codex 负责完整骨架、基础设施、测试审查和排障。
- 不创建 worktree，继续在 `master` 分任务提交和推送。
- 不暂存根目录设计文档、文档生命周期未完成文件及其他现有无关改动。
- 所有 Maven 命令均在 `D:\workFile\demo-ai\backend` 中执行。
- 完整回归使用：`.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test`。

---

### 任务 1：支持按正式快照 ID 查询不可变输入

**文件：**

- 修改：`backend/src/main/java/com/opspilot/ai/analysis/history/GoldResearchSnapshotRepository.java`
- 修改：`backend/src/main/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepository.java`
- 修改：`backend/src/test/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepositoryTests.java`

**接口：**

- 产生：`Optional<StoredGoldResearchSnapshot> findById(UUID id)`
- 后续消费：`GoldResearchNarrativeService` 使用该方法拒绝不存在的快照 ID。

- [ ] **步骤 1：补充可编译契约和行为红灯**

在接口增加：

```java
Optional<StoredGoldResearchSnapshot> findById(UUID id);
```

在 JDBC 类先增加可编译骨架：

```java
@Override
public Optional<StoredGoldResearchSnapshot> findById(UUID id) {
    throw new UnsupportedOperationException("请实现按 ID 查询黄金研究快照");
}
```

测试先保存一条正式快照，再断言按返回 ID 能读回完全相同的记录；另测随机 UUID 返回空。红灯必须来自 `UnsupportedOperationException`，不能来自缺少类。

- [ ] **步骤 2：运行有效红灯**

```powershell
.\mvnw.cmd -Dtest=JdbcGoldResearchSnapshotRepositoryTests test
```

预期：测试可编译，仅新增按 ID 查询测试因 `UnsupportedOperationException` 失败。

- [ ] **步骤 3：实现最小 JDBC 查询**

复用现有 `COLUMNS` 和 `rowMapper`：

```java
List<StoredGoldResearchSnapshot> records = jdbcTemplate.query(
        "select " + COLUMNS + " from gold_research_snapshot where id = ?",
        rowMapper,
        id
);
return records.stream().findFirst();
```

构造函数或方法入口使用 `Objects.requireNonNull(id, "id 不能为空")`。

- [ ] **步骤 4：运行绿灯**

```powershell
.\mvnw.cmd -Dtest=JdbcGoldResearchSnapshotRepositoryTests test
```

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/history backend/src/test/java/com/opspilot/ai/analysis/history/JdbcGoldResearchSnapshotRepositoryTests.java
git commit -m "feat: 支持按编号查询黄金研究快照"
git push origin master
```

---

### 任务 2：定义结构化解读合同并由兵哥实现提示词

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeContent.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativePrompt.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativePromptBuilder.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/narrative/ResearchNarrativePromptBuilderTests.java`

**接口：**

- 产生：`ResearchNarrativePrompt build(StoredGoldResearchSnapshot record)`
- 产生：`ResearchNarrativePrompt(String version, String content, String sha256)`
- 产生：`ResearchNarrativeContent(String summary, String realRateAnalysis, String dollarIndexAnalysis, List<String> risks, List<String> watchList, String disclaimer)`

- [ ] **步骤 1：Codex 创建完整可编译领域骨架**

`ResearchNarrativeContent`：

```java
/** 保存大模型生成并等待安全校验的结构化黄金研究解读。 */
public record ResearchNarrativeContent(
        String summary,
        String realRateAnalysis,
        String dollarIndexAnalysis,
        List<String> risks,
        List<String> watchList,
        String disclaimer
) {
}
```

`ResearchNarrativePrompt`：

```java
/** 保存不可变提示词版本、完整内容和 SHA-256 摘要。 */
public record ResearchNarrativePrompt(
        String version,
        String content,
        String sha256
) {
}
```

`ResearchNarrativePromptBuilder.build` 暂时抛出：

```java
throw new UnsupportedOperationException("请实现黄金研究解读提示词");
```

- [ ] **步骤 2：Codex 编写提示词行为测试**

测试固定快照只表示合同数据，不代表真实行情。必须断言提示词包含：

```java
assertThat(prompt.version()).isEqualTo("gold-narrative-prompt-v1");
assertThat(prompt.content())
        .contains("2026-08-21")
        .contains("4520.00894962")
        .contains("2.400000")
        .contains("118.062800")
        .contains("NEUTRAL")
        .contains("SUPPORTIVE")
        .contains("只返回 JSON")
        .contains("不得生成新闻")
        .contains("不得给出目标价")
        .contains("不得给出买入或卖出建议");
assertThat(prompt.sha256()).matches("[0-9a-f]{64}");
```

再断言同一快照生成相同摘要，不同内容生成不同摘要。

- [ ] **步骤 3：运行有效红灯**

```powershell
.\mvnw.cmd -Dtest=ResearchNarrativePromptBuilderTests test
```

预期：仅因骨架抛出的 `UnsupportedOperationException` 失败。

- [ ] **步骤 4：兵哥实现提示词和摘要计算**

提示词必须使用固定 JSON 字段：

```json
{
  "summary": "",
  "realRateAnalysis": "",
  "dollarIndexAnalysis": "",
  "risks": [""],
  "watchList": [""],
  "disclaimer": ""
}
```

使用 UTF-8 和 `MessageDigest.getInstance("SHA-256")` 计算小写十六进制摘要。提示词中逐项写入快照原值，不允许模型自行查询或补充外部事实。

- [ ] **步骤 5：Codex 审查并运行绿灯**

```powershell
.\mvnw.cmd -Dtest=ResearchNarrativePromptBuilderTests test
```

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/narrative backend/src/test/java/com/opspilot/ai/analysis/narrative/ResearchNarrativePromptBuilderTests.java
git commit -m "feat: 构建黄金研究解读提示词"
git push origin master
```

---

### 任务 3：由兵哥实现结构和金融安全校验

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/UnsafeResearchNarrativeException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeValidator.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeValidatorTests.java`

**接口：**

- 消费：`ResearchNarrativeContent`
- 产生：`void validate(ResearchNarrativeContent content)`

- [ ] **步骤 1：Codex 创建异常、校验器骨架和完整测试夹具**

异常：

```java
/** 表示模型解读违反结构完整性或金融安全边界。 */
public class UnsafeResearchNarrativeException extends RuntimeException {
    public UnsafeResearchNarrativeException(String message) {
        super(message);
    }
}
```

校验器骨架保持可编译并暂时抛出：

```java
public void validate(ResearchNarrativeContent content) {
    throw new UnsupportedOperationException("请实现研究解读安全校验");
}
```

测试覆盖：合法报告、空字段、摘要超过 500 字、因子分析超过 2000 字、列表为空、列表超过 5 条、单项超过 300 字，以及以下越界文本：

```java
"建议买入黄金"
"建议卖出黄金"
"目标价 5000 美元"
"止损位 4300 美元"
"上涨概率为 70%"
```

- [ ] **步骤 2：运行有效红灯**

```powershell
.\mvnw.cmd -Dtest=ResearchNarrativeValidatorTests test
```

预期：合法报告测试因 `UnsupportedOperationException` 失败。

- [ ] **步骤 3：兵哥实现最小校验规则**

实现顺序：空值和空白、文本长度、集合数量、集合单项长度、禁止短语及概率模式、免责声明。禁止模式至少覆盖：

```java
private static final Pattern NUMERIC_PROBABILITY = Pattern.compile(
        "(?:上涨|下跌|涨|跌).{0,8}\\d+(?:\\.\\d+)?%"
);
```

禁止短语只拦截明确行动指令，不得因为免责声明出现“不构成投资建议”而误判。

- [ ] **步骤 4：Codex 审查并运行绿灯**

```powershell
.\mvnw.cmd -Dtest=ResearchNarrativeValidatorTests test
```

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/narrative backend/src/test/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeValidatorTests.java
git commit -m "feat: 校验黄金研究解读安全边界"
git push origin master
```

---

### 任务 4：接入专用 Spring AI 结构化解读网关

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/GeneratedResearchNarrative.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeGateway.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchAiUnavailableException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/InvalidResearchAiResponseException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeProperties.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeConfiguration.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/SpringAiResearchNarrativeGateway.java`
- 修改：`backend/src/main/resources/application.yaml`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/narrative/SpringAiResearchNarrativeGatewayTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**接口：**

- 产生：`GeneratedResearchNarrative generate(ResearchNarrativePrompt prompt)`
- 产生：`GeneratedResearchNarrative(String modelName, String rawResponse, ResearchNarrativeContent content)`
- 配置：`opspilot.research.narrative.model-name=glm-4.7`

- [ ] **步骤 1：创建可编译网关合同和异常**

```java
/** 封装模型名称、原始响应和结构化研究解读。 */
public record GeneratedResearchNarrative(
        String modelName,
        String rawResponse,
        ResearchNarrativeContent content
) {
}
```

```java
/** 定义黄金研究专用大模型调用边界。 */
@FunctionalInterface
public interface ResearchNarrativeGateway {
    GeneratedResearchNarrative generate(ResearchNarrativePrompt prompt);
}
```

两个异常分别表示上游不可用和响应不可解析，均保留 `message + cause` 构造函数。

- [ ] **步骤 2：编写网关红灯测试**

使用现有 `ChatModel` lambda 和 `ChatClient.builder(model)`，避免模拟多层 fluent API。测试覆盖：

- 捕获的 `Prompt` 包含完整研究提示词；
- 合法 JSON 能解析为 `ResearchNarrativeContent`；
- 返回的模型名为 `glm-4.7`；
- 非 JSON 转换为 `InvalidResearchAiResponseException`；
- 模型抛错转换为 `ResearchAiUnavailableException`；
- 日志只记录提示词长度、响应长度、模型名和耗时，不记录真实指标或完整响应。

先让实现骨架抛出 `UnsupportedOperationException`，确保红灯原因有效。

- [ ] **步骤 3：运行红灯**

```powershell
.\mvnw.cmd -Dtest=SpringAiResearchNarrativeGatewayTests test
```

- [ ] **步骤 4：完成配置和最小实现**

配置：

```yaml
opspilot:
  research:
    narrative:
      model-name: glm-4.7
```

使用 Spring Boot 自动提供的 `ObjectMapper` 解析原始字符串：

```java
ResearchNarrativeContent content = objectMapper.readValue(
        rawResponse,
        ResearchNarrativeContent.class
);
```

异常分类顺序必须保证 JSON 解析错误不会被包装成上游不可用。

- [ ] **步骤 5：运行网关和上下文绿灯**

```powershell
.\mvnw.cmd "-Dtest=SpringAiResearchNarrativeGatewayTests,OpsPilotApplicationTests" test
```

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/narrative backend/src/main/resources/application.yaml backend/src/test/java/com/opspilot/ai/analysis/narrative/SpringAiResearchNarrativeGatewayTests.java backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java
git commit -m "feat: 接入黄金研究大模型解读网关"
git push origin master
```

---

### 任务 5：使用 V6 和 JDBC 幂等留痕解读

**文件：**

- 新建：`backend/src/main/resources/db/migration/V6__create_gold_research_narrative.sql`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/StoredResearchNarrative.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/SaveResearchNarrativeResult.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeRepository.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/JdbcResearchNarrativeRepository.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/narrative/ResearchNarrativeSchemaTests.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/narrative/JdbcResearchNarrativeRepositoryTests.java`

**接口：**

- 产生：`Optional<StoredResearchNarrative> findByKey(UUID snapshotId, String modelName, String promptVersion)`
- 产生：`SaveResearchNarrativeResult saveIfAbsent(StoredResearchNarrative candidate)`
- 产生：`List<StoredResearchNarrative> findBySnapshotId(UUID snapshotId)`

- [ ] **步骤 1：创建完整可编译仓储合同和红灯测试**

`StoredResearchNarrative` 字段严格对应规格中的正式记录；`SaveResearchNarrativeResult` 保存 `record` 和 `created`。仓储实现先抛 `UnsupportedOperationException`。

Schema 测试检查 13 个字段、外键和唯一索引：

```sql
(snapshot_id, model_name, prompt_version)
```

JDBC 测试覆盖 JSONB 列表往返、首次创建、重复不覆盖、按快照生成时间倒序查询和不存在的幂等键返回空。

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd "-Dtest=ResearchNarrativeSchemaTests,JdbcResearchNarrativeRepositoryTests" test
```

预期：Schema 测试因 V6 表不存在失败，仓储测试因骨架未实现失败。

- [ ] **步骤 3：编写 V6 迁移**

核心结构：

```sql
create table gold_research_narrative (
    id uuid primary key,
    snapshot_id uuid not null,
    summary varchar(500) not null,
    real_rate_analysis text not null,
    dollar_index_analysis text not null,
    risks jsonb not null,
    watch_list jsonb not null,
    disclaimer varchar(500) not null,
    model_name varchar(100) not null,
    prompt_version varchar(64) not null,
    prompt_hash char(64) not null,
    raw_response text not null,
    created_at timestamptz not null,
    constraint fk_gold_research_narrative_snapshot
        foreign key (snapshot_id)
        references gold_research_snapshot (id),
    constraint uk_gold_research_narrative_idempotency
        unique (snapshot_id, model_name, prompt_version),
    constraint ck_gold_research_narrative_prompt_hash
        check (prompt_hash ~ '^[0-9a-f]{64}$')
);
```

增加 `(snapshot_id, created_at desc)` 查询索引。外键不使用级联删除，防止误删正式快照后静默删除审计记录。

- [ ] **步骤 4：实现 JDBC 映射**

使用自动注入的 `ObjectMapper` 将 `List<String>` 写成 JSON 字符串并从 `jsonb` 读取。插入语句使用：

```sql
on conflict (snapshot_id, model_name, prompt_version) do nothing
```

随后按幂等键读取数据库最终记录，返回正确 `created` 状态。

- [ ] **步骤 5：运行绿灯**

```powershell
.\mvnw.cmd "-Dtest=ResearchNarrativeSchemaTests,JdbcResearchNarrativeRepositoryTests" test
```

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/resources/db/migration/V6__create_gold_research_narrative.sql backend/src/main/java/com/opspilot/ai/analysis/narrative backend/src/test/java/com/opspilot/ai/analysis/narrative
git commit -m "feat: 留痕黄金研究大模型解读"
git push origin master
```

---

### 任务 6：编排快照、模型、校验和幂等保存

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/GoldResearchSnapshotNotFoundException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/GoldResearchNarrativeService.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/narrative/GoldResearchNarrativeServiceTests.java`

**接口：**

- 产生：`SaveResearchNarrativeResult generate(UUID snapshotId)`
- 产生：`List<StoredResearchNarrative> findBySnapshotId(UUID snapshotId)`

- [ ] **步骤 1：创建服务骨架和行为红灯**

测试使用真实领域对象和 mock 端口，覆盖：

1. 快照不存在时抛 `GoldResearchSnapshotNotFoundException`，模型和解读仓储均无交互；
2. 已有相同模型和提示词版本时直接返回 `created=false`，不构建提示词、不调用模型；
3. 首次生成按“查询快照 -> 查幂等键 -> 构建提示词 -> 调模型 -> 校验 -> 保存”执行；
4. 模型失败、解析失败或安全校验失败时不保存；
5. 历史查询前先确认快照存在。

服务骨架先抛 `UnsupportedOperationException`，不制造缺类错误。

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd -Dtest=GoldResearchNarrativeServiceTests test
```

- [ ] **步骤 3：实现最小编排**

服务通过 `ResearchNarrativeProperties` 获取当前模型名，通过 `ResearchNarrativePromptBuilder.PROMPT_VERSION` 获取提示词版本。只有幂等记录不存在时才调用模型。

生成成功后使用 `Clock` 产生 UTC 时间并构造候选记录，再交给仓储处理并发幂等。事务不能覆盖耗时的远程模型调用；只在 JDBC 仓储单次写入边界完成数据库原子性。

- [ ] **步骤 4：运行服务和邻接绿灯**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchNarrativeServiceTests,ResearchNarrativePromptBuilderTests,ResearchNarrativeValidatorTests" test
```

- [ ] **步骤 5：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/narrative backend/src/test/java/com/opspilot/ai/analysis/narrative/GoldResearchNarrativeServiceTests.java
git commit -m "feat: 编排黄金研究大模型解读"
git push origin master
```

---

### 任务 7：开放 HTTP 合同并完成真实验收

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/api/ResearchNarrativeResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/api/SaveResearchNarrativeResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/analysis/narrative/api/GoldResearchNarrativeController.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- 新建：`backend/src/test/java/com/opspilot/ai/analysis/narrative/api/GoldResearchNarrativeControllerTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**接口：**

- `post /api/research/gold/snapshots/{snapshotId}/narratives`
- `get /api/research/gold/snapshots/{snapshotId}/narratives`

- [ ] **步骤 1：编写 HTTP 红灯合同**

首次生成断言 `201` 和 `created=true`，重复生成断言 `200` 和 `created=false`。响应必须包含：

```java
.andExpect(jsonPath("$.record.snapshotId").value(snapshotId.toString()))
.andExpect(jsonPath("$.record.modelName").value("glm-4.7"))
.andExpect(jsonPath("$.record.promptVersion")
        .value("gold-narrative-prompt-v1"))
.andExpect(jsonPath("$.record.content.summary").isNotEmpty())
.andExpect(jsonPath("$.record.rawResponse").doesNotExist());
```

历史接口断言按时间倒序。异常测试断言：

- 404：`GOLD_RESEARCH_SNAPSHOT_NOT_FOUND`
- 503：`RESEARCH_AI_UNAVAILABLE`
- 502：`INVALID_RESEARCH_AI_RESPONSE`
- 422：`UNSAFE_RESEARCH_NARRATIVE`

先创建 DTO 和 Controller 可编译骨架，方法暂抛 `UnsupportedOperationException`，再运行有效红灯。

- [ ] **步骤 2：运行红灯**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchNarrativeControllerTests,GlobalExceptionHandlerTests" test
```

- [ ] **步骤 3：实现 DTO、Controller 和异常映射**

Controller 只调用服务并根据 `created` 选择状态码。API DTO 不返回 `rawResponse` 和完整提示词，只返回正式结构化内容及审计元数据。

- [ ] **步骤 4：运行 HTTP 绿灯**

```powershell
.\mvnw.cmd "-Dtest=GoldResearchNarrativeControllerTests,GlobalExceptionHandlerTests" test
```

- [ ] **步骤 5：运行完整回归**

```powershell
.\mvnw.cmd "-Dtest=!DocumentLifecycleServiceTests" test
```

预期：0 failure、0 error；真实外部模型不作为普通自动化测试依赖。

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/analysis/narrative/api backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java backend/src/test/java/com/opspilot/ai/analysis/narrative/api backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git commit -m "feat: 开放黄金研究大模型解读接口"
git push origin master
```

- [ ] **步骤 7：使用真实快照和 GLM-4.7 手动验收**

启动前只从用户环境变量加载密钥：

```powershell
$env:ZHIPU_API_KEY = [Environment]::GetEnvironmentVariable("ZHIPU_API_KEY", "User")
$env:OPSPILOT_DB_PASSWORD = [Environment]::GetEnvironmentVariable("OPSPILOT_DB_PASSWORD", "User")
.\mvnw.cmd spring-boot:run
```

从历史快照接口取得一个真实 `gold-multifactor-v2` 快照 ID，再执行两次：

```powershell
curl.exe --silent --show-error --fail-with-body `
  -X POST `
  "http://localhost:8080/api/research/gold/snapshots/{snapshotId}/narratives"
```

验收：第一次 `created=true`，第二次 `created=false` 且 ID 相同；内容引用快照实际日期和指标，两个因子分开解释；没有新闻、目标价、概率或交易指令。

- [ ] **步骤 8：最终 Git 和安全检查**

```powershell
git diff --check
git status --short
git rev-parse HEAD
git rev-parse origin/master
```

确认 API Key、数据库密码、模型原始敏感错误和现有无关文件未进入提交。
