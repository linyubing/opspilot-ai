# 黄金预测发布时间实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 保存真实发布时刻与候选目标承诺，防止过期新建和错日结算，并分层展示评测限制。
**Architecture:** 纯Timing类型与PublicationPolicy负责操作护栏，仓储保存可空旧证据；结算与API复用承诺；评测及页面不把候选或未知升为可信。
**Tech Stack:** Java21、Spring Boot、JDBC/PostgreSQL17、Flyway、JUnit/Mockito、现有静态HTML/JS。
**Spec:** `docs/superpowers/specs/2026-10-04-forecast-publication-design.md`

## Global Constraints

- 保留次日close/base close三方向标签±0.5%，最终留出集不读，不改模型或真实旧预测。
- 候选版本`sydney-0700-candidate-v1`不升级SOURCE；trustedCount=0、trustedAccuracy=null。
- null旧timing不回填；候选等于end拒绝，等于start属于IN_SESSION；Java短名、中文类注释、SQL小写。
- 沿用用户授权master原地实施、验证后显式文件提交推送；保护两项用户文件不读不改。

## Review Focus

- Clock offset改变不得改变绝对边界；任务1测试相同瞬间两个offset。
- 原始后续行日期与日历不一致不得悄悄跳目标；任务2测试第一根不匹配。
- 模型执行跨线或确认目标期间变为已知不得保存；任务1测试两次验证。
- 日历后改/旧幂等不能重写原承诺；任务1幂等及任务2响应测试。
- timing字段半空或候选伪造不能让接口显示可信率；任务1数据库约束与任务2资格测试。

### Task 1: 发布时间护栏与不可变承诺入库

**Files:** 新增forecast/GoldForecastTiming.java、GoldForecastPublicationPolicy.java、InvalidGoldPublicationException.java、migration/V23__add_gold_forecast_timing.sql；修改StoredGoldDirectionForecast、GenerationService、JdbcGoldForecastRepository、GlobalExceptionHandler；测试GenerationServiceTests、PublicationPolicyTests、JdbcGoldForecastRepositoryTests。

**Interfaces:** Produces Timing(targetDate,start,end,ruleVersion)、phase(OffsetDateTime)返回Phase；Policy.plan(LocalDate)、validate(LocalDate,Timing,OffsetDateTime)；StoredForecast新增timing，保留旧构造器。

- [ ] 在现有生成测试加过期/已知目标/跨线与候选持久化行为，先运行观察断言RED，不使用缺类编译错误。
- [ ] 实现类型、Policy、前后检查与422；纯验证逐一覆盖before/equal/after、offset、DST、周末/假日、错误版本/端点。期望有效before-end允许，end及之后拒绝。
- [ ] 增加隔离数据库往返、幂等与半字段拒绝测试，观察RED，再实现V23与仓储读取/插入；旧列保持null。
- [ ] 运行定向和裸`./mvnw.cmd test`，均0失败0错误；记录日志与迁移是否真实执行，中文提交。

### Task 2: 结算资格与对外分层

**Files:** 修改ResolutionService、api/GoldForecastResponse、Evaluation及Service/Response、review/GoldForecastReviewPromptBuilder；新增GoldForecastTimingEvaluation；测试对应service/API/JDBC现有用例。

**Interfaces:** Consumes Task1 Timing及StoredForecast.timing；Produces evaluation.timing()返回未知/候选开盘前/候选盘中/无效计数、分层率与trustedCount/trustedAccuracy，API新增phase、timing、warning。

- [ ] 写行为RED：承诺标签不匹配不结算、目标end前不结算、旧流程不升级、日历改变不修改expectedTargetDate、unknown及candidate不得可信。
- [ ] 实现结算检查；API先持久化承诺后旧回退，阶段依据createdAt；统计完整资格并保留旧历史描述。
- [ ] 复盘提示明确历史描述并展示未知/候选资格，不让大模型把旧overall解释为可信前瞻率。
- [ ] 定向及裸全量回归0失败0错误；中文提交，记录任务证据。

### Task 3: 页面与真实只读验收

**Files:** 修改static/forecast.html、forecast.js及页面测试；研究报告`docs/research/2026-10-04-forecast-publication-results.md`。

**Interfaces:** Consumes Task2 API字段；Produces页面展示UTC候选区间、阶段、固定可信限制、分层计数，不新增预测写入。

- [ ] 按前端测试技能写可运行渲染验证：未知和候选盘中记录均显示正确中文限制，trusted null不显示0%成功率。
- [ ] 实现页面展示；运行定向及全量Maven，浏览器或可运行JS验证无错误。
- [ ] 全分支独立只读审查，重要发现用RED/GREEN修复，记录未判范围和裁决。
- [ ] 启动自己标记且关闭调度的8080服务；GET history/evaluation验证旧记录UNKNOWN、可信0/null，核对旧记录数与内容未修改；只停止自有进程。
- [ ] 保存证据与多轮路线结论，中文提交推送、远程SHA核验；清理仅本计划scratch，总目标保持active。
